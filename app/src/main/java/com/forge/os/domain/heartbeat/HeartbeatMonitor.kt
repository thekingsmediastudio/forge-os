package com.forge.os.domain.heartbeat

import android.content.Context
import com.forge.os.domain.config.ConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthLevel { HEALTHY, WARNING, CRITICAL, DOWN }

@Serializable
data class ComponentStatus(
    val name: String,
    val health: String,        // HealthLevel as string for serialization
    val metrics: Map<String, String> = emptyMap(),
    val lastCheck: Long = System.currentTimeMillis(),
    val message: String? = null
)

@Serializable
data class AlertInfo(val component: String, val message: String)

@Serializable
data class SystemStatus(
    val timestamp: Long = System.currentTimeMillis(),
    val overallHealth: HealthLevel = HealthLevel.HEALTHY,
    val components: Map<String, ComponentStatus> = emptyMap(),
    val alerts: List<AlertInfo> = emptyList(),
    val recommendations: List<String> = emptyList()
)

@Singleton
class HeartbeatMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val alertManager: AlertManager,
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null

    private val heartbeatDir = File(context.filesDir, "workspace/heartbeat").apply { mkdirs() }
    private val statusFile = File(heartbeatDir, "status.json")
    private val metricsDir = File(heartbeatDir, "metrics").apply { mkdirs() }

    private val _status = MutableStateFlow(SystemStatus())
    val status: StateFlow<SystemStatus> = _status

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun start() {
        if (monitorJob?.isActive == true) return
        val intervalMs = configRepository.get().heartbeatSettings.intervalSeconds * 1000L
        monitorJob = scope.launch {
            Timber.i("Heartbeat monitor started (interval: ${intervalMs}ms)")
            while (isActive) {
                try {
                    val status = performHealthCheck()
                    saveStatus(status)
                    _status.value = status
                    if (status.overallHealth != HealthLevel.HEALTHY) {
                        alertManager.sendAlert(status)
                        attemptSelfHealing(status)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Heartbeat check failed")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        Timber.i("Heartbeat monitor stopped")
    }

    suspend fun checkNow(): SystemStatus {
        val status = performHealthCheck()
        saveStatus(status)
        _status.value = status
        return status
    }

    private suspend fun performHealthCheck(): SystemStatus = withContext(Dispatchers.IO) {
        val components = mutableMapOf<String, ComponentStatus>()
        val alerts = mutableListOf<AlertInfo>()
        val recommendations = mutableListOf<String>()
        val settings = configRepository.get().heartbeatSettings

        // 1. Storage
        if (settings.checkStorage) {
            val storage = checkStorage()
            components["storage"] = storage
            if (storage.health != HealthLevel.HEALTHY.name) {
                alerts += AlertInfo("storage", storage.message ?: "Storage issue")
                recommendations += "Run memory cleanup to free space"
            }
        }

        // 2. RAM
        if (settings.checkMemory) {
            components["memory"] = checkMemory()
        }

        // 3. API reachability (lightweight ping, no actual calls)
        if (settings.checkApiHealth) {
            val api = checkApiReachability()
            components["api"] = api
            if (api.health == HealthLevel.WARNING.name) {
                alerts += AlertInfo("api", api.message ?: "Some providers unreachable")
                recommendations += "Switch to fallback provider if issues persist"
            }
        }

        // 4. Workspace integrity
        components["workspace"] = checkWorkspace()

        // 5. Config validity
        components["config"] = checkConfig()

        val overallHealth = when {
            components.values.any { it.health == HealthLevel.CRITICAL.name } -> HealthLevel.CRITICAL
            components.values.any { it.health == HealthLevel.WARNING.name } -> HealthLevel.WARNING
            else -> HealthLevel.HEALTHY
        }

        val status = SystemStatus(
            overallHealth = overallHealth,
            components = components,
            alerts = alerts,
            recommendations = recommendations
        )
        
        // Enhanced Integration: Learn heartbeat monitoring patterns
        try {
            // Record monitoring frequency patterns
            userPreferencesManager.recordInteractionPattern("heartbeat_monitoring_active", 1)
            
            // Learn system health trends
            when (overallHealth) {
                HealthLevel.HEALTHY -> {
                    reflectionManager.recordPattern(
                        pattern = "System health stable",
                        description = "Heartbeat monitor reports all systems healthy: ${components.keys.joinToString()}",
                        applicableTo = listOf("system_health", "monitoring", "stability"),
                        tags = listOf("health_stable", "monitoring_success", "system_reliability")
                    )
                }
                HealthLevel.WARNING -> {
                    reflectionManager.recordPattern(
                        pattern = "System health warning detected",
                        description = "Heartbeat monitor detected warnings: ${alerts.joinToString { it.message }}",
                        applicableTo = listOf("system_health", "monitoring", "early_warning"),
                        tags = listOf("health_warning", "monitoring_alert", "preventive_maintenance")
                    )
                }
                HealthLevel.CRITICAL -> {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "heartbeat_critical_${System.currentTimeMillis()}",
                        failureReason = "Critical system health issues detected: ${alerts.joinToString { it.message }}",
                        recoveryStrategy = "Immediate attention required: ${recommendations.joinToString("; ")}",
                        tags = listOf("health_critical", "system_failure", "urgent_maintenance")
                    )
                }
                HealthLevel.DOWN -> {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "heartbeat_down_${System.currentTimeMillis()}",
                        failureReason = "System components are down: ${alerts.joinToString { it.message }}",
                        recoveryStrategy = "System restart or emergency maintenance required",
                        tags = listOf("health_down", "system_outage", "emergency_maintenance")
                    )
                }
            }
            
            // Learn component-specific patterns
            components.forEach { (component, status) ->
                if (status.health != HealthLevel.HEALTHY.name) {
                    userPreferencesManager.recordInteractionPattern("${component}_health_issues", 1)
                }
            }
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to record heartbeat monitoring patterns")
        }
        
        return@withContext status
    }

    private fun checkStorage(): ComponentStatus {
        val workspace = File(context.filesDir, "workspace")
        workspace.mkdirs()
        val total = workspace.totalSpace
        val usable = workspace.usableSpace
        val used = total - usable
        val pct = if (total > 0) (used * 100.0 / total).toInt() else 0
        val maxMb = configRepository.get().sandboxLimits.maxWorkspaceSizeMb
        val workspaceMb = (getDirSize(workspace) / (1024 * 1024)).toInt()

        val health = when {
            pct > 95 || workspaceMb > maxMb -> HealthLevel.CRITICAL
            pct > 80 || workspaceMb > maxMb * 0.85 -> HealthLevel.WARNING
            else -> HealthLevel.HEALTHY
        }

        return ComponentStatus(
            name = "storage",
            health = health.name,
            metrics = mapOf(
                "deviceUsagePercent" to "$pct%",
                "workspaceMb" to "${workspaceMb}MB",
                "maxMb" to "${maxMb}MB",
                "usableMb" to "${usable / (1024 * 1024)}MB"
            ),
            message = if (health != HealthLevel.HEALTHY) "Storage at $pct% (workspace ${workspaceMb}MB)" else null
        )
    }

    private fun checkMemory(): ComponentStatus {
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val usedMem = totalMem - freeMem
        val pct = (usedMem * 100.0 / maxMem).toInt()

        val health = when {
            pct > 90 -> HealthLevel.CRITICAL
            pct > 75 -> HealthLevel.WARNING
            else -> HealthLevel.HEALTHY
        }

        return ComponentStatus(
            name = "memory",
            health = health.name,
            metrics = mapOf(
                "usagePercent" to "$pct%",
                "usedMb" to "${usedMem / (1024 * 1024)}MB",
                "maxMb" to "${maxMem / (1024 * 1024)}MB"
            ),
            message = if (health != HealthLevel.HEALTHY) "RAM at $pct%" else null
        )
    }

    private suspend fun checkApiReachability(): ComponentStatus = withContext(Dispatchers.IO) {
        val endpoints = mapOf(
            "OpenAI" to "https://api.openai.com",
            "Anthropic" to "https://api.anthropic.com",
            "Groq" to "https://api.groq.com"
        )

        val results = mutableMapOf<String, Boolean>()
        for ((name, url) in endpoints) {
            results[name] = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                conn.connect()
                conn.disconnect()
                true
            } catch (e: Exception) {
                false
            }
        }

        val failed = results.filter { !it.value }.keys.toList()
        val health = when {
            failed.size == results.size -> HealthLevel.CRITICAL
            failed.isNotEmpty() -> HealthLevel.WARNING
            else -> HealthLevel.HEALTHY
        }

        ComponentStatus(
            name = "api",
            health = health.name,
            metrics = results.mapValues { it.value.toString() },
            message = if (failed.isNotEmpty()) "Unreachable: ${failed.joinToString()}" else null
        )
    }

    private fun checkWorkspace(): ComponentStatus {
        val workspace = File(context.filesDir, "workspace")
        val requiredDirs = listOf("memory", "cron", "plugins", "heartbeat", "system", "projects")
        val missing = requiredDirs.filter { !File(workspace, it).exists() }

        if (missing.isNotEmpty()) {
            missing.forEach { File(workspace, it).mkdirs() }
        }

        return ComponentStatus(
            name = "workspace",
            health = HealthLevel.HEALTHY.name,
            metrics = mapOf(
                "dirs" to requiredDirs.size.toString(),
                "repaired" to missing.size.toString()
            )
        )
    }

    private fun checkConfig(): ComponentStatus {
        return try {
            val config = configRepository.get()
            ComponentStatus(
                name = "config",
                health = HealthLevel.HEALTHY.name,
                metrics = mapOf(
                    "version" to config.version,
                    "agentName" to config.agentIdentity.name,
                    // `enabledTools` is the static seed allow-list; user toggles
                    // mutate `disabledTools`. The actually-enabled count must
                    // subtract the user-disabled set so the heartbeat reflects
                    // current state instead of staying frozen at the seed size.
                    "enabledTools" to (config.toolRegistry.enabledTools.toSet() -
                        config.toolRegistry.disabledTools.toSet()).size.toString()
                )
            )
        } catch (e: Exception) {
            ComponentStatus(
                name = "config",
                health = HealthLevel.WARNING.name,
                message = "Config read error: ${e.message}"
            )
        }
    }

    private suspend fun attemptSelfHealing(status: SystemStatus) {
        for (alert in status.alerts) {
            when (alert.component) {
                "storage" -> {
                    Timber.i("Self-healing: cleaning up old heartbeat metrics")
                    metricsDir.listFiles()
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(7) // Keep last 7 days
                        ?.forEach { it.delete() }
                }
                "api" -> {
                    Timber.i("Self-healing: switching to fallback provider")
                    val config = configRepository.get()
                    if (config.modelRouting.defaultProvider != config.modelRouting.fallbackProvider) {
                        configRepository.update { c ->
                            c.copy(modelRouting = c.modelRouting.copy(
                                defaultProvider = c.modelRouting.fallbackProvider,
                                defaultModel = c.modelRouting.fallbackModel
                            ))
                        }
                        alertManager.send("api", "Auto-switched to fallback provider: ${config.modelRouting.fallbackProvider}", AlertSeverity.INFO)
                    }
                }
            }
        }
    }

    private fun saveStatus(status: SystemStatus) {
        statusFile.writeText(json.encodeToString(status))
        // Append to daily metrics
        val today = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
        val metricsFile = File(metricsDir, "health-$today.jsonl")
        metricsFile.appendText(json.encodeToString(status) + "\n")
    }

    fun getLastStatus(): SystemStatus {
        return if (statusFile.exists()) {
            try {
                json.decodeFromString(statusFile.readText())
            } catch (e: Exception) {
                SystemStatus()
            }
        } else {
            SystemStatus()
        }
    }

    private fun getDirSize(dir: File): Long {
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
