package com.forge.os.domain.control

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — central registry of every "powerful" capability the agent is
 * allowed to flip on or off. Every capability has:
 *   - a stable id (used in tools, consent ledger, audit log)
 *   - a human description of what enabling it actually means
 *   - a default state (the safe, factory-shipped state)
 *   - a "requires consent" flag — if true, the agent cannot toggle it without
 *     a matching grant in [UserConsentLedger]
 *
 * Subsystems (SecurityPolicy, plugin loader, proactive scheduler, …) read
 * their behaviour from this plane via [isEnabled]/[stateOf] rather than from
 * hardcoded constants, so the user can grant or revoke power at runtime.
 */
@Serializable
data class CapabilitySpec(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val defaultEnabled: Boolean,
    val requiresConsent: Boolean,
    val danger: String,
)

@Serializable
data class CapabilityState(
    val id: String,
    val enabled: Boolean,
    val updatedAtMs: Long,
    val updatedBy: String,
)

@Serializable
private data class StateFile(val states: List<CapabilityState> = emptyList())

@Singleton
class AgentControlPlane @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consent: UserConsentLedger,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val file: File
        get() = context.filesDir.resolve("workspace/control/control_state.json").apply {
            parentFile?.mkdirs()
        }

    private val _states = MutableStateFlow<Map<String, CapabilityState>>(emptyMap())
    val states: StateFlow<Map<String, CapabilityState>> = _states

    val capabilities: List<CapabilitySpec> = ALL_CAPABILITIES

    init { _states.value = load() }

    private fun load(): Map<String, CapabilityState> {
        val now = System.currentTimeMillis()
        val stored = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<StateFile>(file.readText()).states
        }.getOrDefault(emptyList())
        val byId = stored.associateBy { it.id }
        return capabilities.associate { spec ->
            spec.id to (byId[spec.id]
                ?: CapabilityState(spec.id, spec.defaultEnabled, now, "factory_default"))
        }
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(StateFile(_states.value.values.toList()))) }
            .onFailure { Timber.w(it, "AgentControlPlane persist failed") }
    }

    fun spec(id: String): CapabilitySpec? = capabilities.firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean = _states.value[id]?.enabled
        ?: spec(id)?.defaultEnabled
        ?: false

    fun stateOf(id: String): CapabilityState? = _states.value[id]

    /**
     * Set a capability to [enabled]. If [requestedBy] is "agent" and the
     * capability requires consent, this fails unless [UserConsentLedger]
     * already records a fresh user grant for that capability.
     */
    @Synchronized
    fun set(id: String, enabled: Boolean, requestedBy: String, source: String = ""): SetResult {
        val spec = spec(id) ?: return SetResult.UnknownCapability(id)
        if (requestedBy == "agent" && spec.requiresConsent && !consent.isGranted(id)) {
            return SetResult.ConsentRequired(spec)
        }
        val newState = CapabilityState(id, enabled, System.currentTimeMillis(),
            if (source.isNotBlank()) "$requestedBy:$source" else requestedBy)
        _states.value = _states.value + (id to newState)
        persist()
        Timber.i("AgentControlPlane: $id -> $enabled by $requestedBy")
        return SetResult.Ok(newState)
    }

    /** User-driven set (always allowed). The accompanying consent grant is
     * also recorded so subsequent agent re-toggles within TTL succeed. */
    fun setByUser(id: String, enabled: Boolean, ttlMs: Long = 0L, source: String = "ui"): SetResult {
        consent.grantFromUser(id, ttlMs, source, note = if (enabled) "enable" else "disable")
        return set(id, enabled, requestedBy = "user", source = source)
    }

    fun summary(): String = buildString {
        appendLine("Agent control plane (${capabilities.size} capabilities):")
        capabilities.groupBy { it.category }.forEach { (cat, list) ->
            appendLine("  • $cat")
            list.forEach { spec ->
                val st = isEnabled(spec.id)
                val flag = if (st) "ON " else "off"
                val lock = if (spec.requiresConsent) "🔒" else "  "
                appendLine("      $lock $flag  ${spec.id}  — ${spec.title}")
            }
        }
    }

    sealed class SetResult {
        data class Ok(val state: CapabilityState) : SetResult()
        data class UnknownCapability(val id: String) : SetResult()
        data class ConsentRequired(val spec: CapabilitySpec) : SetResult()
    }

    companion object {
        // ──── Security ────────────────────────────────────────────────────
        const val PYTHON_IMPORT_GUARD = "security.python_import_guard"
        const val SHELL_BLOCKLIST     = "security.shell_blocklist"
        const val FILE_PROTECTION     = "security.file_protection"
        const val FILE_SIZE_LIMIT     = "security.file_size_limit"
        const val SAFETY_FILTER       = "security.safety_filter"
        // ──── Plugins ─────────────────────────────────────────────────────
        const val PLUGIN_PERSIST      = "plugins.persistent_install"
        const val PLUGIN_AUTO_RESTORE = "plugins.auto_restore_on_upgrade"
        const val PLUGIN_NETWORK      = "plugins.allow_network"
        // ──── Notifications ───────────────────────────────────────────────
        const val NOTIFY_ACTIONS      = "notify.action_callbacks"
        // ──── Hardware ────────────────────────────────────────────────────
        const val HW_SCREENSHOT_WEB   = "hardware.web_screenshot"
        const val HW_LAUNCH_APPS      = "hardware.launch_apps"
        const val HW_VOLUME_CONTROL   = "hardware.volume_control"
        const val HW_DEVICE_INFO      = "hardware.device_info"
        // ──── Browser ─────────────────────────────────────────────────────
        const val BROWSER_HISTORY     = "browser.persistent_history"
        const val BROWSER_AGENT_NAV   = "browser.agent_navigation"
        // ──── Project serving / network ───────────────────────────────────
        const val PROJECT_SERVE_LAN   = "network.project_serve_lan"
        const val MCP_PROJECT_SERVER  = "network.mcp_project_server"
        // ──── Proactive ───────────────────────────────────────────────────
        const val PROACTIVE_SUGGEST   = "proactive.suggestions"
        const val PROACTIVE_AUTOACT   = "proactive.auto_act"

        val ALL_CAPABILITIES = listOf(
            CapabilitySpec(PYTHON_IMPORT_GUARD,
                "Block dangerous Python imports",
                "When ON, the Python sandbox blocks imports of socket, subprocess, ssl, ctypes, etc. " +
                "Disable to let the agent run unrestricted Python (only do this if you trust the prompts).",
                "Security", true, true, "high"),
            CapabilitySpec(SHELL_BLOCKLIST,
                "Block dangerous shell patterns",
                "When ON, shell_exec rejects rm -rf /, fork bombs, base64 -d, sudo, etc. " +
                "Disable to allow arbitrary shell commands.",
                "Security", true, true, "high"),
            CapabilitySpec(FILE_PROTECTION,
                "Protect build files",
                "When ON, file_write refuses to overwrite AndroidManifest.xml, build.gradle, etc.",
                "Security", true, true, "medium"),
            CapabilitySpec(FILE_SIZE_LIMIT,
                "Enforce 10 MB file size cap",
                "When ON, file_write rejects payloads larger than 10 MB.",
                "Security", true, true, "low"),
            CapabilitySpec(SAFETY_FILTER,
                "Companion safety filter",
                "When ON, romantic/sexual content and dependency-encouraging language are filtered " +
                "from companion replies (Phase O safety rails).",
                "Security", true, true, "high"),
            CapabilitySpec(PLUGIN_PERSIST,
                "Plugin persistent install",
                "When ON, installed plugins are exported to a public folder so they survive app " +
                "uninstall + reinstall (and APK upgrades).",
                "Plugins", true, false, "low"),
            CapabilitySpec(PLUGIN_AUTO_RESTORE,
                "Auto-restore plugins after upgrade",
                "When ON, on first launch after an upgrade the app re-imports any plugins it " +
                "exported under the persistent install folder.",
                "Plugins", true, false, "low"),
            CapabilitySpec(PLUGIN_NETWORK,
                "Allow plugins to use the network",
                "When ON, plugins may call out to the internet from inside the Python sandbox.",
                "Plugins", false, true, "medium"),
            CapabilitySpec(NOTIFY_ACTIONS,
                "Clickable notification actions",
                "When ON, agent-posted notifications can carry buttons that, when tapped, dispatch " +
                "a tool call back to Forge OS.",
                "Notifications", true, false, "low"),
            CapabilitySpec(HW_SCREENSHOT_WEB,
                "Web screenshot tool",
                "When ON, the agent may render any URL in an offscreen WebView and save the bitmap " +
                "to the workspace.",
                "Hardware", true, false, "low"),
            CapabilitySpec(HW_LAUNCH_APPS,
                "Launch installed apps",
                "When ON, android_launch_app may start any installed package.",
                "Hardware", true, true, "medium"),
            CapabilitySpec(HW_VOLUME_CONTROL,
                "Change device volume",
                "When ON, android_set_volume may change media/ring/notification volume.",
                "Hardware", true, true, "low"),
            CapabilitySpec(HW_DEVICE_INFO,
                "Read device & network info",
                "When ON, the agent may read battery, network, storage, screen, installed apps.",
                "Hardware", true, false, "low"),
            CapabilitySpec(BROWSER_HISTORY,
                "Persistent browser history",
                "When ON, every navigation in the in-app browser is recorded with timestamp, URL, " +
                "title and which session (user / agent) initiated it.",
                "Browser", true, false, "low"),
            CapabilitySpec(BROWSER_AGENT_NAV,
                "Allow agent to drive the browser",
                "When ON, browser_navigate / browser_eval_js / browser_click can be called by the agent.",
                "Browser", true, true, "medium"),
            CapabilitySpec(PROJECT_SERVE_LAN,
                "Serve a project on the local network",
                "When ON, project_serve starts an HTTP server bound to the device's Wi-Fi IP and " +
                "serves a chosen workspace folder so other devices on the LAN can browse it.",
                "Network", true, true, "medium"),
            CapabilitySpec(MCP_PROJECT_SERVER,
                "Expose project as an MCP server",
                "When ON, mcp_project_serve publishes the project's files as MCP resources/tools.",
                "Network", false, true, "medium"),
            CapabilitySpec(PROACTIVE_SUGGEST,
                "Proactive suggestions",
                "When ON, a periodic worker reviews recent activity and may post a gentle " +
                "suggestion notification (e.g. \"want me to serve this project?\").",
                "Proactive", false, true, "low"),
            CapabilitySpec(PROACTIVE_AUTOACT,
                "Proactive auto-act",
                "When ON, the proactive worker may execute the suggested action without waiting " +
                "for a tap (e.g. start the LAN server, open the page). Strongly recommended OFF " +
                "unless you've explicitly told the agent you want this.",
                "Proactive", false, true, "high"),
        )
    }
}
