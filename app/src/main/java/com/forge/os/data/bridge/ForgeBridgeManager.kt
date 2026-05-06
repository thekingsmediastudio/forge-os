package com.forge.os.data.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.forge.os.bridge.IForgeBridgeCallback
import com.forge.os.bridge.IForgeBridgeService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AIDL connections to all installed Forge Bridge apps.
 *
 * A Forge Bridge app is any Android application that:
 *  1. Implements [IForgeBridgeService] as a bound service.
 *  2. Exports that service with the intent action:
 *         com.forge.os.bridge.TOOL_PROVIDER
 *  3. Optionally holds the permission:
 *         com.forge.os.permission.USE_API  (recommended)
 *
 * This manager:
 *  - Discovers bridge apps at startup and on [refresh].
 *  - Binds to each, queries BridgeInfo + tool manifest.
 *  - Stores live [BridgeConnection] state in [connections].
 *  - Routes [dispatch] calls to the correct bridge by tool name.
 *  - Auto-rebinds when a bridge service disconnects unexpectedly.
 *
 * Adding a new bridge app requires ZERO Forge OS source changes — just install
 * the app and call [refresh] (or restart Forge OS).
 */
@Singleton
class ForgeBridgeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    // Enhanced Integration: Connect with other systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val doctorService: com.forge.os.domain.doctor.DoctorService,
) {
    companion object {
        const val ACTION_TOOL_PROVIDER = "com.forge.os.bridge.TOOL_PROVIDER"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── State ─────────────────────────────────────────────────────────────────

    data class BridgeConnection(
        val packageName:  String,
        val info:         BridgeInfo?,
        val tools:        List<BridgeToolEntry>,
        val connected:    Boolean,
        val error:        String? = null,
    )

    private val _connections = MutableStateFlow<Map<String, BridgeConnection>>(emptyMap())
    val connections: StateFlow<Map<String, BridgeConnection>> = _connections.asStateFlow()

    /** Live service stubs keyed by package name */
    private val services = mutableMapOf<String, IForgeBridgeService>()

    /** tool-name → packageName lookup (populated after manifest fetch) */
    private val toolIndex = mutableMapOf<String, String>()

    private val serviceConnections = mutableMapOf<String, ServiceConnection>()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Discover all installed bridge apps and bind to them. Call once at startup. */
    fun refresh() {
        val found = discoverBridgeApps()
        Timber.i("ForgeBridgeManager: discovered ${found.size} bridge app(s): ${found.joinToString()}")
        
        // Enhanced Integration: Learn bridge discovery patterns
        scope.launch {
            try {
                reflectionManager.recordPattern(
                    pattern = "Bridge discovery: ${found.size} apps found",
                    description = "Discovered bridge apps: ${found.joinToString()}",
                    applicableTo = listOf("bridge_discovery", "system_integration"),
                    tags = listOf("bridge_manager", "discovery", "integration")
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record bridge discovery patterns")
            }
        }
        
        found.forEach { pkg ->
            if (!_connections.value.containsKey(pkg)) {
                bindBridge(pkg)
            }
        }
        // Unbind stale apps that are no longer installed
        val stale = _connections.value.keys.filter { it !in found }
        stale.forEach { pkg -> unbindBridge(pkg) }
    }

    /** Dispatch a tool call to the appropriate bridge. Returns null if no bridge handles it. */
    fun dispatch(toolName: String, argsJson: String): String? {
        val pkg = toolIndex[toolName] ?: return null
        val svc = services[pkg] ?: return """{"ok":false,"error":"Bridge $pkg not connected"}"""
        
        // Enhanced Integration: Pre-dispatch health check
        try {
            val healthReport = doctorService.runChecks()
            if (healthReport.hasFailures) {
                Timber.w("Bridge dispatch with health issues: ${healthReport.checks.filter { it.status == com.forge.os.domain.doctor.CheckStatus.FAIL }.map { it.id }}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Health check failed during bridge dispatch")
        }
        
        return try {
            val result = svc.dispatch(toolName, argsJson)
            
            // Enhanced Integration: Learn from bridge tool usage
            scope.launch {
                try {
                    val isError = result.contains("\"ok\":false") || result.contains("error")
                    reflectionManager.recordPattern(
                        pattern = "Bridge tool execution: $toolName via $pkg",
                        description = "Bridge $pkg executed $toolName with result: ${if (isError) "error" else "success"}",
                        applicableTo = listOf("bridge_tool_usage", toolName, pkg),
                        tags = listOf("bridge_execution", "tool_usage", toolName, pkg, if (isError) "error" else "success")
                    )
                    
                    if (isError) {
                        reflectionManager.recordFailureAndRecovery(
                            taskId = "bridge_${System.currentTimeMillis()}",
                            failureReason = "Bridge tool execution failed: $result",
                            recoveryStrategy = "Check bridge connection, validate tool parameters, or try alternative bridge",
                            tags = listOf("bridge_failure", toolName, pkg)
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record bridge execution patterns")
                }
            }
            
            result
        } catch (e: RemoteException) {
            Timber.e(e, "Bridge dispatch failed: $pkg.$toolName")
            
            // Enhanced Integration: Record bridge failures
            scope.launch {
                try {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "bridge_${System.currentTimeMillis()}",
                        failureReason = "Bridge RPC error: ${e.message}",
                        recoveryStrategy = "Check bridge connection, restart bridge app, or use alternative tool",
                        tags = listOf("bridge_rpc_failure", toolName, pkg)
                    )
                } catch (e2: Exception) {
                    Timber.w(e2, "Failed to record bridge failure patterns")
                }
            }
            
            """{"ok":false,"error":"Bridge RPC error: ${e.message}"}"""
        }
    }

    /** Check if any bridge owns this tool name */
    fun handles(toolName: String): Boolean = toolIndex.containsKey(toolName)

    /** All tool entries from all connected bridges */
    fun allTools(): List<Pair<String, BridgeToolEntry>> =
        _connections.value.values
            .filter { it.connected }
            .flatMap { conn -> conn.tools.map { conn.packageName to it } }

    /** Summary for agent (used by bridge_list tool) */
    fun summary(): String = buildString {
        val conns = _connections.value
        if (conns.isEmpty()) { appendLine("No bridge apps discovered."); return@buildString }
        appendLine("Forge Bridges (${conns.size}):")
        conns.values.forEach { c ->
            val status  = if (c.connected) "✓ Connected" else "✗ ${c.error ?: "Disconnected"}"
            val name    = c.info?.name ?: c.packageName
            val version = c.info?.version ?: "?"
            val tools   = c.tools.size
            appendLine("  • $name v$version [$status] — $tools tool(s)")
            if (c.connected && c.tools.isNotEmpty()) {
                c.tools.forEach { t -> appendLine("      – ${t.name}") }
            }
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun discoverBridgeApps(): List<String> {
        val intent = Intent(ACTION_TOOL_PROVIDER)
        val flags  = PackageManager.GET_META_DATA.toLong()
        return runCatching {
            context.packageManager
                .queryIntentServices(intent, flags.toInt())
                .mapNotNull { it.serviceInfo?.packageName }
                .filter { it != context.packageName } // never bind to self
                .distinct()
        }.onFailure { Timber.e(it, "Bridge discovery failed") }.getOrElse { emptyList() }
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private fun bindBridge(packageName: String) {
        Timber.i("Binding to bridge: $packageName")
        markConnection(packageName, connected = false, error = "Connecting…")

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val svc = IForgeBridgeService.Stub.asInterface(binder)
                services[packageName] = svc

                // Register our callback
                runCatching {
                    svc.setCallback(object : IForgeBridgeCallback.Stub() {
                        override fun onBridgeEvent(eventJson: String?) {
                            Timber.d("Bridge event from $packageName: ${eventJson?.take(120)}")
                        }
                        override fun onToolManifestChanged() {
                            Timber.i("Bridge manifest changed: $packageName — refreshing")
                            loadManifest(packageName, svc)
                        }
                        override fun onBridgeDisconnecting(reason: String?) {
                            Timber.i("Bridge $packageName disconnecting: $reason")
                            markConnection(packageName, connected = false, error = reason ?: "Intentional disconnect")
                        }
                    })
                }

                loadManifest(packageName, svc)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Timber.w("Bridge disconnected: $packageName — scheduling rebind")
                services.remove(packageName)
                // Remove tools from index for this bridge
                toolIndex.entries.removeAll { (_, pkg) -> pkg == packageName }
                markConnection(packageName, connected = false, error = "Disconnected — rebinding")
                // Android will rebind automatically with BIND_AUTO_CREATE
            }
        }

        serviceConnections[packageName] = conn
        val intent = Intent(ACTION_TOOL_PROVIDER).apply { setPackage(packageName) }
        val bound  = runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrElse { false }

        if (!bound) {
            Timber.w("bindService failed for $packageName")
            markConnection(packageName, connected = false, error = "bindService failed — app may be missing the exported service")
        }
    }

    private fun unbindBridge(packageName: String) {
        serviceConnections.remove(packageName)?.let {
            runCatching { context.unbindService(it) }
        }
        services.remove(packageName)
        toolIndex.entries.removeAll { (_, pkg) -> pkg == packageName }
        val updated = _connections.value.toMutableMap()
        updated.remove(packageName)
        _connections.value = updated
        Timber.i("Unbound bridge: $packageName")
    }

    private fun loadManifest(packageName: String, svc: IForgeBridgeService) {
        try {
            val infoJson  = runCatching { svc.getBridgeInfo() }.getOrNull() ?: "{}"
            val toolsJson = runCatching { svc.getToolManifest() }.getOrElse { "[]" }

            val info  = BridgeManifestParser.parseBridgeInfo(infoJson)
            val tools = BridgeManifestParser.parseToolManifest(toolsJson)

            // Update the tool index — remove old entries for this bridge first
            toolIndex.entries.removeAll { (_, pkg) -> pkg == packageName }
            tools.forEach { t -> toolIndex[t.name] = packageName }

            val updated = _connections.value.toMutableMap()
            updated[packageName] = BridgeConnection(
                packageName = packageName,
                info        = info,
                tools       = tools,
                connected   = true,
            )
            _connections.value = updated

            Timber.i("Bridge '$packageName' loaded — ${tools.size} tool(s): ${tools.map { it.name }}")
        } catch (e: RemoteException) {
            Timber.e(e, "Failed to load manifest from $packageName")
            markConnection(packageName, connected = false, error = "Manifest fetch error: ${e.message}")
        }
    }

    private fun markConnection(packageName: String, connected: Boolean, error: String? = null) {
        val updated = _connections.value.toMutableMap()
        val existing = updated[packageName]
        updated[packageName] = BridgeConnection(
            packageName = packageName,
            info        = existing?.info,
            tools       = if (connected) (existing?.tools ?: emptyList()) else emptyList(),
            connected   = connected,
            error       = error,
        )
        _connections.value = updated
    }
}
