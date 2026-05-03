package com.forge.os.domain.config

import com.forge.os.domain.model.RoutingRule
import com.forge.os.presentation.theme.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
data class ForgeConfig(
    val version: String = "1.0.0",
    val agentIdentity: AgentIdentity = AgentIdentity(),
    val behaviorRules: BehaviorRules = BehaviorRules(),
    val toolRegistry: ToolRegistryConfig = ToolRegistryConfig(),
    val modelRouting: ModelRoutingConfig = ModelRoutingConfig(),
    val sandboxLimits: SandboxLimits = SandboxLimits(),
    val cronSettings: CronSettings = CronSettings(),
    val pluginSettings: PluginSettings = PluginSettings(),
    val memorySettings: MemorySettings = MemorySettings(),
    val heartbeatSettings: HeartbeatSettings = HeartbeatSettings(),
    val delegationRules: DelegationRules = DelegationRules(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val externalApi: ExternalApiSettings = ExternalApiSettings(),
    val friendMode: FriendModeSettings = FriendModeSettings(),
    /**
     * Phase S — user-editable padlock overrides. The agent's `control_set`
     * cannot widen these lists when [PermissionPolicy.userOverrides.lockAgentOut]
     * is true (which is the default).
     */
    val permissions: PermissionPolicy = PermissionPolicy(),
    val hybridExecution: HybridExecutionSettings = HybridExecutionSettings(),
    val costBudget: CostBudgetConfig = CostBudgetConfig(),
    val prefetchSettings: PrefetchSettings = PrefetchSettings(),
    val intelligenceUpgrades: IntelligenceUpgrades = IntelligenceUpgrades(),
    val forgeBridge: ForgeBridgeSettings = ForgeBridgeSettings(),
)

/**
 * Controls how ForgeOS interacts with a locally running forge-bridge-android instance.
 * When [enabled] is true and forge-bridge-android is running on localhost:8745, ForgeOS
 * will use it as the primary AI provider — routing all LLM calls through the bridge
 * instead of calling providers directly. This means the user only needs to configure
 * API keys once in forge-bridge-android, not in every AI app separately.
 */
@Serializable
data class ForgeBridgeSettings(
    /** When true, ForgeOS probes localhost:8745 at startup and uses bridge if found. */
    val enabled: Boolean = true,
    /** Base URL of the forge-bridge-android server. */
    val url: String = "http://127.0.0.1:8745",
    /** Auto-probe on startup — activates FORGE_BRIDGE provider if bridge is running. */
    val autoDiscover: Boolean = true,
    /** When true, FORGE_BRIDGE is preferred over direct API keys in autoRoute(). */
    val preferBridge: Boolean = true,
)

@Serializable
data class PermissionPolicy(
    val userOverrides: UserOverrides = UserOverrides(),
)

@Serializable
data class IntelligenceUpgrades(
    val reflectionEnabled: Boolean = true,
    val memoryRagEnabled: Boolean = true,
    val conversationRagEnabled: Boolean = true,
    val minimalToolCatalog: Boolean = false,
    val visionEnabled: Boolean = true,
    val reasoningEnabled: Boolean = true,
)

@Serializable
data class PrefetchSettings(
    val enabled: Boolean = true,
    val allowUnsafeTools: Boolean = false,
)

@Serializable
data class UserOverrides(
    /** When true, the agent's `control_set` cannot modify any `permissions.*` path. */
    val lockAgentOut: Boolean = true,
    /** Hosts the user has added to the network blocklist on top of the defaults. */
    val extraBlockedHosts: List<String> = emptyList(),
    /** File extensions the user has added to the file/download blocklist. */
    val extraBlockedExtensions: List<String> = emptyList(),
    /** Config paths the user has added to the config-write blocklist. */
    val extraBlockedConfigPaths: List<String> = emptyList(),
)

@Serializable
data class FriendModeSettings(
    val enabled: Boolean = false,
    val autoRoute: Boolean = false,
    val proactiveCheckInsEnabled: Boolean = false,
    val maxProactivePerDay: Int = 2,
    val realWorldNudgesEnabled: Boolean = false,
    val morningCheckInEnabled: Boolean = false,
    val morningCheckInTime: String = "08:30",
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:30",
    val followUpsEnabled: Boolean = true,
    val anniversariesEnabled: Boolean = true,
    val voiceEnabled: Boolean = false,
    val moodChipsEnabled: Boolean = true,
    val showRelationshipHeader: Boolean = true,
    val dependencyMonitorEnabled: Boolean = true,
    val dependencyThresholdHoursPerDay: Float = 3.0f,
    val dependencyThresholdConsecutiveDays: Int = 14,
    val dependencyThresholdSessionsPerWeek: Int = 50,
    val companionDailyTokenBudget: Int = 50_000,
    val crisisLineRegion: String = "",
    val crisisLineCustomText: String = "",
)

@Serializable
data class ExternalApiSettings(
    val enabled: Boolean = false,
    val defaultCallsPerMinute: Int = 30,
    val defaultTokensPerDay: Int = 50_000,
    val grantTtlSeconds: Long = 0,
)

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticFeedbackEnabled: Boolean = true,
)

@Serializable
data class AgentIdentity(
    val name: String = "Forge",
    val personality: String = "precise, helpful, security-conscious",
    val defaultGreeting: String = "Ready to build. What's the mission?",
    val language: String = "en",
    val timezone: String = "UTC"
)

@Serializable
data class BehaviorRules(
    val autoConfirmToolCalls: Boolean = false,
    val confirmDestructive: List<String> = listOf(
        "file_delete",
        "shell_exec",
        "git_push",
        "git_pull",
        "git_clone",
        "git_checkout",
        "file_download",
        "browser_download",
        "config_write",
        "config_rollback"
    ),
    val maxIterations: Int = 15,
    val replanThreshold: Double = 0.7,
    val autoSaveInterval: Int = 300,
    val verboseLogging: Boolean = true,
    val notifyOnLongTask: Boolean = true,
    val longTaskThreshold: Int = 60,
    /** Phase 3 — USD threshold for agent execution cost gate. 0.0 = disabled. */
    val costThresholdUsd: Double = 0.0,
)

@Serializable
data class ToolRegistryConfig(
    /** When true ALL built-in tools are enabled regardless of [enabledTools].
     *  Default ON so the agent can see every registered tool — including
     *  Telegram, named-secrets, alarms, channels, doctor, server and the
     *  rest of the post-Phase-T surface that was being silently filtered
     *  out by the seed allow-list below. Users who want a smaller surface
     *  can flip this OFF in Settings → Advanced. */
    val enableAllTools: Boolean = true,
    val enabledTools: List<String> = listOf(
        "file_read", "file_write", "file_list", "file_delete",
        "shell_exec", "python_run", "workspace_info", "workspace_describe",
        // Phase S — full git surface (init/status/commit shipped earlier).
        "git_init", "git_status", "git_add", "git_commit", "git_log", "git_diff",
        "git_branch", "git_checkout", "git_remote_set", "git_clone",
        "git_push", "git_pull",
        // Phase S — downloads (cookie-aware variant uses headless browser jar).
        "file_download", "browser_download",
        // Phase S — Android device tools enabled by default (read-only set;
        // android_set_volume / android_launch_app stay behind confirmation).
        "android_device_info", "android_battery", "android_volume",
        "android_network", "android_storage", "android_screen",
        "android_snapshot", "android_list_apps",
        "memory_store", "memory_recall", "semantic_recall_facts",
        "memory_store_skill", "memory_summary",
        "cron_add", "cron_list", "cron_remove", "cron_run_now", "cron_history",
        "plugin_list", "plugin_install", "plugin_uninstall", "plugin_execute",
        "config_read", "config_write", "config_rollback",
        "delegate_task", "delegate_batch", "agents_list", "agent_status", "agent_cancel",
        "heartbeat_check",
        "snapshot_create", "snapshot_list", "snapshot_restore", "snapshot_delete",
        "mcp_refresh", "mcp_list_tools",
        "http_fetch", "curl_exec", "ddg_search",
        "browser_navigate", "browser_get_html", "browser_eval_js",
        "browser_fill_field", "browser_click", "browser_scroll",
        "temp_list", "temp_clear",
        "request_user_input",
        "composio_call",
        // Phase Q — agent control plane
        "control_list", "control_describe", "control_set", "control_grant",
        "control_revoke", "control_status",
        // Phase Q — plugin persistence
        "plugin_export_all", "plugin_export_list", "plugin_restore_missing",
        // Phase Q — notifications, web screenshot, browser history
        "notify_send", "web_screenshot",
        "browser_history_list", "browser_history_clear", "browser_session_new",
        // Phase Q — LAN project server
        "project_serve", "project_unserve", "project_serve_list",
        // Phase Q — proactive
        "proactive_status", "proactive_schedule", "proactive_cancel",
        // Phase T — named secrets (the agent never sees raw values)
        "secret_list", "secret_request",
        // Phase T — channels / Telegram (the agent owns its own chats)
        "channel_list", "channel_send", "channel_toggle", "channel_add_telegram",
        "telegram_send_voice", "telegram_main_chat", "telegram_list_chats",
        "telegram_get_allowed_chats", "telegram_set_allowed_chats",
        "telegram_allow_chat", "telegram_deny_chat",
        // Self-knowledge + diagnostics + local API server
        "app_describe", "doctor_check", "doctor_fix",
        "server_start", "server_stop", "server_status", "server_rotate_key",
        // Alarms
        "alarm_set", "alarm_list", "alarm_cancel",
        // Phase R — browser viewport + helpers
        "browser_set_viewport", "browser_screenshot_region",
        "browser_wait_for_selector", "browser_get_text",
        "browser_get_attribute", "browser_list_links",
        "file_upload_to_browser", "plugin_create",
        // Missing from original list — registered in ToolRegistry but not shown in UI
        "plan_and_execute_dag",
        "memory_store_image", "memory_get_skill", "memory_list_skills",
        "telegram_react", "telegram_reply", "telegram_send_file",
        "message_bus_publish", "message_bus_read", "message_bus_topics",
        "python_run_remote", "python_packages",
        "image_analyze", "mcp_call_tool",
        "delegate_ghost",
        "system_backup_export", "system_backup_import",
        "android_set_volume", "android_launch_app",
    ),
    val disabledTools: List<String> = emptyList(),
    val toolTimeouts: Map<String, Int> = mapOf(
        "shell_exec" to 30,
        "python_run" to 30,
        "file_write" to 10,
        "http_fetch" to 30,
        "curl_exec" to 30,
        "ddg_search" to 20,
        "browser_navigate" to 15,
        "browser_get_html" to 10,
        "browser_eval_js" to 10,
        // Phase S
        "file_download" to 120,
        "browser_download" to 120,
        "git_clone" to 120,
        "git_push" to 60,
        "git_pull" to 60,
    )
)

@Serializable
data class CompanionRouting(
    val provider: String? = null,
    val model: String? = null,
    val preferredOrder: List<String> = listOf("ANTHROPIC", "GEMINI", "OPENAI", "GROQ"),
)

@Serializable
data class CompactMode(
    val enabled: Boolean = false,
    val maxTokensPerRequest: Int = 512,
    val maxContextMessages: Int = 8,
    val preferProvider: String = "GROQ",
    val preferModel: String = "llama-3.3-70b-versatile",
)

@Serializable
data class ProviderModelPair(
    val provider: String,
    val model: String,
)

@Serializable
data class ModelRoutingConfig(
    val defaultProvider: String = "OPENAI",
    val defaultModel: String = "gpt-4o",
    val fallbackProvider: String = "GROQ",
    val fallbackModel: String = "llama-3.3-70b-versatile",
    /**
     * Phase R — global fallback chain used by background callers (cron,
     * alarms, proactive, sub-agents) when the primary provider returns an
     * error. Tried in order, top to bottom, until one succeeds. Empty by
     * default — back-compat with [fallbackProvider]/[fallbackModel].
     */
    val fallbackChain: List<ProviderModelPair> = listOf(
        ProviderModelPair("GROQ", "llama-3.3-70b-versatile"),
        ProviderModelPair("OPENROUTER", "anthropic/claude-3.5-sonnet"),
        ProviderModelPair("GEMINI", "gemini-2.0-flash"),
    ),
    val routingRules: List<RoutingRule> = listOf(
        RoutingRule("code_generation", "ANTHROPIC", "claude-3-5-sonnet-latest"),
        RoutingRule("quick_chat", "GROQ", "llama-3.3-70b-versatile")
    ),
    val companion: CompanionRouting = CompanionRouting(),
    val compactMode: CompactMode = CompactMode(),
    /**
     * Phase S — per-caller fallback opt-in for background callers. ON means
     * the caller walks [fallbackChain] when the primary fails; OFF means the
     * call dies on the first error. Default ON for everything (matches Phase R).
     */
    val backgroundUsesFallback: BackgroundUsesFallback = BackgroundUsesFallback(),
    /** Phase S — global override for background summarization calls. */
    val summarizationProvider: String? = null,
    val summarizationModel: String? = null,
    /** Phase S — global override for background system calls (skill synthesis). */
    val systemProvider: String? = null,
    val systemModel: String? = null,
    /** Feature 3 — Dedicated LLM for JSON DAG generation. */
    val plannerProvider: String? = null,
    val plannerModel: String? = null,
    /** Phase 4 — Vision routing. */
    val visionProvider: String? = null,
    val visionModel: String? = null,
    /** Phase 4 — Reasoning routing. */
    val reasoningProvider: String? = null,
    val reasoningModel: String? = null,
    /** Phase 4 — Reflection (learning) routing. */
    val reflectionProvider: String? = null,
    val reflectionModel: String? = null,
)

@Serializable
data class BackgroundUsesFallback(
    val cron: Boolean = true,
    val alarms: Boolean = true,
    val subAgents: Boolean = true,
    val proactive: Boolean = true,
)

@Serializable
data class SandboxLimits(
    val maxFileSizeMb: Int = 10,
    val maxWorkspaceSizeMb: Int = 500,
    val maxOutputLength: Int = 10000,
    val allowedExtensions: List<String> = listOf(
        "txt", "md", "py", "js", "ts", "html", "css", "json", "yaml", "xml",
        "kt", "java", "c", "cpp", "h", "rs", "go", "rb", "php", "sh"
    ),
    val blockedPaths: List<String> = listOf("/etc", "/sys", "/proc", "/dev"),
)

@Serializable
data class CronSettings(
    val maxConcurrentJobs: Int = 3,
    val defaultTimeoutSeconds: Int = 300,
    val retryAttempts: Int = 3,
    val retryDelaySeconds: Int = 60,
    val historyRetentionDays: Int = 30,
    val maxJobs: Int = 50,
    val notifyOnFailure: Boolean = true,
)

@Serializable
data class PluginSettings(
    val allowNetworkAccess: Boolean = false,
    val allowFileSystemAccess: Boolean = true,
    val maxPluginSizeMb: Int = 50,
    val pluginStorageCapMb: Int = 200,
    val sandboxTimeout: Int = 30,
    val keepRollback: Boolean = true,
    val allowUserPlugins: Boolean = true,
    val allowNetworkPlugins: Boolean = false,
)

@Serializable
data class MemorySettings(
    val maxDailyEntries: Int = 1000,
    val maxLongtermEntries: Int = 10000,
    val compressionThreshold: Int = 800,
    val retentionDays: Int = 90,
    val autoCompress: Boolean = true,
    val embeddingProvider: String = "OPENAI",
    val embeddingModel: String = "text-embedding-3-small"
)

@Serializable
data class HeartbeatSettings(
    val intervalSeconds: Int = 900,
    val maxMissedBeats: Int = 3,
    val alertOnMiss: Boolean = true,
    val checkStorage: Boolean = true,
    val checkMemory: Boolean = true,
    val checkApiHealth: Boolean = true,
)

@Serializable
data class DelegationRules(
    val maxConcurrentAgents: Int = 3,
    val defaultAgentTimeout: Int = 300,
    val allowSelfDelegation: Boolean = false,
    val maxSubAgents: Int = 3,
    val subAgentTimeout: Int = 300,
    val allowRecursiveDelegation: Boolean = false,
)

/**
 * Phase 3 — Hybrid Execution settings.
 * When a remote Python worker URL is configured, heavy scripts (torch,
 * tensorflow, transformers, etc.) are routed to it instead of running
 * on-device via Chaquopy.
 */
@Serializable
data class HybridExecutionSettings(
    /** Base URL of the remote Python execution endpoint, e.g. "https://gpu.example.com/run". Empty = disabled. */
    val remotePythonWorkerUrl: String = "",
    /** Authorization token/API key sent as Bearer header to the remote worker. */
    val remotePythonWorkerAuthToken: String = "",
    /** Regex-style import patterns that trigger remote routing when detected in a script. */
    val heavyImportPatterns: List<String> = listOf(
        "torch", "tensorflow", "transformers", "cv2", "opencv",
        "jax", "flax", "diffusers", "accelerate", "bitsandbytes",
        "onnxruntime", "triton", "torchaudio", "torchvision"
    ),
)

@Serializable
data class CostBudgetConfig(
    val enabled: Boolean = false,
    val dailyLimitUsd: Double = 1.0,
    val ecoProvider: String = "GROQ",
    val ecoModel: String = "llama-3.3-70b-versatile",
)