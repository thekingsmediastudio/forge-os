package com.forge.os.presentation.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.domain.security.PermissionManager
import com.forge.os.domain.security.ToolAuditEntry
import com.forge.os.domain.security.ToolAuditLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ToolRow(
    val name: String,
    val description: String,
    val isPlugin: Boolean,
    val enabled: Boolean,
    val requiresConfirmation: Boolean,
    val parametersJson: String = "{}",
)

data class ToolsUiState(
    val tools: List<ToolRow> = emptyList(),
    val audit: List<ToolAuditEntry> = emptyList(),
    val testRunning: String? = null,
    val testResult: String? = null,
    val showAudit: Boolean = false,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val permissionManager: PermissionManager,
    private val auditLog: ToolAuditLog,
    private val toolRegistry: ToolRegistry,
    private val pluginManager: PluginManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val config = configRepository.get()
        val perms = permissionManager.getPermissions()

        // ── Step 1: collect every plugin tool name (all plugins, enabled or not)
        // We need this BEFORE touching allRegistered so enabled-plugin tools don't
        // silently leak into the system tools section.
        val pluginToolNames: Set<String> = pluginManager.listPlugins()
            .flatMap { manifest -> manifest.tools.map { it.name } }
            .toSet()

        // ── Step 2: build schema + short-description maps in ONE O(n) pass
        // (calling getSchema() per tool would be O(n²) — each call re-runs getAllDefinitions())
        val allDefs = toolRegistry.getAllDefinitions()
        val schemaByName: Map<String, String> = allDefs.associate { def ->
            def.function.name to runCatching {
                kotlinx.serialization.json.Json.encodeToString(
                    com.forge.os.data.api.FunctionParameters.serializer(),
                    def.function.parameters
                )
            }.getOrDefault("{}")
        }

        // ── Step 3: system tool names = everything registered EXCEPT plugin tools
        // and MCP tools (mcp.* prefix). The allRegistered set intentionally excludes
        // them so they never show up mixed into the system section.
        val allRegistered: Set<String> = allDefs
            .map { it.function.name }
            .filter { name -> name !in pluginToolNames && !name.startsWith("mcp.") }
            .toSet()

        // Merge with explicitly disabled tool names that might have fallen out of the
        // live registry so disabled entries stay visible and can be re-enabled.
        val builtinNames: List<String> = (allRegistered +
            config.toolRegistry.disabledTools.filter { it !in pluginToolNames && !it.startsWith("mcp.") })
            .distinct()
            .sorted()

        val systemTools = builtinNames.map { name ->
            val disabledGlobally = name in config.toolRegistry.disabledTools
            val enabledByConfig = config.toolRegistry.enableAllTools ||
                name in config.toolRegistry.enabledTools
            val perm = perms.toolPermissions[name]
            ToolRow(
                name = name,
                description = TOOL_DESCRIPTIONS[name] ?: "(built-in tool)",
                isPlugin = false,
                enabled = enabledByConfig && !disabledGlobally && (perm?.allowed != false),
                requiresConfirmation = perm?.requiresConfirmation == true,
                parametersJson = schemaByName[name] ?: "{}",
            )
        }

        // ── Step 4: plugin tools — all tools from all plugins, always at the bottom.
        // Shown regardless of enabled state so disabled plugins stay visible and
        // can be toggled back on without leaving the screen.
        val pluginRows = pluginManager.listPlugins().flatMap { manifest ->
            manifest.tools.map { tool ->
                val isUserDisabled = tool.name in config.toolRegistry.disabledTools
                ToolRow(
                    name = tool.name,
                    description = tool.description.ifBlank { "(plugin tool from ${manifest.id})" },
                    isPlugin = true,
                    enabled = manifest.enabled && !isUserDisabled,
                    requiresConfirmation = tool.name in config.behaviorRules.confirmDestructive,
                    parametersJson = schemaByName[tool.name] ?: "{}",
                )
            }
        }

        _state.value = _state.value.copy(
            tools = systemTools + pluginRows,
            audit = auditLog.entries.value,
        )
    }

    fun toggle(name: String, enabled: Boolean) {
        viewModelScope.launch {
            configRepository.update { c ->
                val disabled = c.toolRegistry.disabledTools.toMutableList()
                if (enabled) disabled.remove(name) else if (name !in disabled) disabled += name
                c.copy(toolRegistry = c.toolRegistry.copy(disabledTools = disabled))
            }
            permissionManager.updateToolPermission(toolName = name, enabled = enabled)
            refresh()
        }
    }

    fun setRequiresConfirmation(name: String, requires: Boolean) {
        viewModelScope.launch {
            configRepository.update { c ->
                val list = c.behaviorRules.confirmDestructive.toMutableList()
                if (requires) { if (name !in list) list += name } else list.remove(name)
                c.copy(behaviorRules = c.behaviorRules.copy(confirmDestructive = list))
            }
            refresh()
        }
    }

    fun runTest(name: String, args: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(testRunning = name, testResult = null)
            val safeArgs = args.ifBlank { "{}" }
            val result = toolRegistry.dispatch(name, safeArgs, toolCallId = "ui_test_${System.currentTimeMillis()}")
            _state.value = _state.value.copy(
                testRunning = null,
                testResult = if (result.isError) "❌ ${result.output}" else "✅ ${result.output}",
                audit = auditLog.entries.value,
            )
        }
    }

    fun toggleAudit() { _state.value = _state.value.copy(showAudit = !_state.value.showAudit) }

    fun clearAudit() {
        auditLog.clear()
        refresh()
    }

    fun dismissTestResult() { _state.value = _state.value.copy(testResult = null) }

    companion object {
        val TOOL_DESCRIPTIONS = mapOf(
            // Files & Shell
            "file_read" to "Read a file from the workspace",
            "file_write" to "Write to a workspace file",
            "file_list" to "List a workspace directory",
            "file_delete" to "Delete a workspace file",
            "shell_exec" to "Run a shell command",
            "python_run" to "Execute Python in Chaquopy (on-device)",
            "python_run_remote" to "Execute Python on a remote worker endpoint",
            "python_packages" to "List installed Python packages",
            "workspace_info" to "Workspace stats (size, file count)",
            "workspace_describe" to "Describe workspace folder layout",
            "file_download" to "Stream a URL into workspace",
            "browser_download" to "Download with browser cookies",
            "file_upload_to_browser" to "Upload a file through a browser form",
            // Git
            "git_init" to "Init a git repo",
            "git_status" to "git status",
            "git_add" to "Stage files",
            "git_commit" to "Commit current state",
            "git_log" to "Show last N commits",
            "git_diff" to "Diff against HEAD",
            "git_branch" to "List branches",
            "git_checkout" to "Switch / create branch",
            "git_remote_set" to "Set remote URL",
            "git_clone" to "Clone a repo",
            "git_push" to "Push to remote",
            "git_pull" to "Pull from remote",
            // Config
            "config_read" to "Read agent config",
            "config_write" to "Mutate config in natural language",
            "config_rollback" to "Roll back config version",
            // Memory
            "memory_store" to "Store a long-term fact",
            "memory_recall" to "Recall facts by keyword",
            "semantic_recall_facts" to "Semantic / vector search over facts",
            "memory_store_skill" to "Save a named Python skill",
            "memory_get_skill" to "Retrieve a saved skill by name",
            "memory_list_skills" to "List all saved skills",
            "memory_store_image" to "Tag and index a workspace image in memory",
            "memory_summary" to "Summary of all memory tiers",
            // Web & Network
            "http_fetch" to "Make an HTTP request",
            "curl_exec" to "curl-style HTTP call",
            "ddg_search" to "DuckDuckGo web search",
            "web_screenshot" to "Screenshot a URL",
            "image_analyze" to "Analyze / describe an image with vision",
            // Browser automation
            "browser_navigate" to "Navigate to a URL",
            "browser_get_html" to "Get full HTML of current page",
            "browser_get_text" to "Get readable text from current page",
            "browser_eval_js" to "Run JavaScript on the page",
            "browser_fill_field" to "Fill a form field",
            "browser_click" to "Click an element by selector",
            "browser_click_at" to "Click at (x, y) coordinates",
            "browser_type" to "Type text into focused element",
            "browser_scroll" to "Scroll the page",
            "browser_set_viewport" to "Set browser viewport size",
            "browser_screenshot_region" to "Screenshot a region of the page",
            "browser_wait_for_selector" to "Wait until an element appears",
            "browser_get_attribute" to "Get an element attribute",
            "browser_list_links" to "List all links on the current page",
            "browser_history_list" to "List browser history",
            "browser_history_clear" to "Clear browser history",
            "browser_session_new" to "Start a fresh browser session",
            // Cron
            "cron_add" to "Schedule a recurring AI task",
            "cron_list" to "List scheduled cron jobs",
            "cron_remove" to "Remove a cron job",
            "cron_run_now" to "Run a cron job immediately",
            "cron_history" to "Recent cron execution history",
            // Alarms
            "alarm_set" to "Set a time-triggered agent session",
            "alarm_list" to "List all alarms",
            "alarm_cancel" to "Cancel an alarm",
            // Plugins
            "plugin_list" to "List installed plugins",
            "plugin_install" to "Install a plugin (manifest + code)",
            "plugin_uninstall" to "Uninstall a plugin",
            "plugin_execute" to "Invoke a plugin tool",
            "plugin_create" to "Create and install a new plugin",
            "plugin_export_all" to "Export all plugins to a zip",
            "plugin_export_list" to "List exportable plugins",
            "plugin_restore_missing" to "Re-install missing built-in plugins",
            // Sub-agents & Multi-agent
            "plan_and_execute_dag" to "Plan a complex goal as a DAG and run steps in parallel",
            "delegate_task" to "Spawn a sub-agent for a task",
            "delegate_batch" to "Spawn multiple sub-agents at once",
            "delegate_ghost" to "Spawn a silent background sub-agent",
            "agents_list" to "List running sub-agents",
            "agent_status" to "Check a sub-agent's status",
            "agent_cancel" to "Cancel a running sub-agent",
            "message_bus_publish" to "Publish a message to a topic",
            "message_bus_read" to "Read messages from a topic",
            "message_bus_topics" to "List active message bus topics",
            // Telegram
            "telegram_react" to "Add emoji reaction to a Telegram message",
            "telegram_reply" to "Reply to a Telegram message",
            "telegram_send_file" to "Send a file to a Telegram chat",
            "telegram_send_voice" to "Send a voice message to Telegram",
            "telegram_main_chat" to "Get the main Telegram chat ID",
            "telegram_list_chats" to "List active Telegram chats",
            "telegram_get_allowed_chats" to "Get allowed chat IDs",
            "telegram_set_allowed_chats" to "Set allowed chat IDs",
            "telegram_allow_chat" to "Allow a Telegram chat ID",
            "telegram_deny_chat" to "Deny a Telegram chat ID",
            // Channels
            "channel_list" to "List configured channels",
            "channel_send" to "Send a message on a channel",
            "channel_toggle" to "Enable / disable a channel",
            "channel_add_telegram" to "Add a Telegram channel",
            // Android Device
            "android_device_info" to "Phone model, OS, identifiers",
            "android_battery" to "Battery level and charging state",
            "android_volume" to "Read current volume levels",
            "android_set_volume" to "Set device volume (requires confirm)",
            "android_network" to "Wi-Fi / mobile network state",
            "android_storage" to "Storage usage stats",
            "android_screen" to "Screen state, resolution, brightness",
            "android_snapshot" to "Snapshot full device state",
            "android_list_apps" to "List installed apps",
            "android_launch_app" to "Launch an app by package (requires confirm)",
            // Heartbeat & Health
            "heartbeat_check" to "Run a full system health check",
            "doctor_check" to "Check a specific subsystem",
            "doctor_fix" to "Attempt to fix a failing subsystem",
            "app_describe" to "Describe the Forge OS agent capabilities",
            // Snapshots
            "snapshot_create" to "Save a workspace snapshot",
            "snapshot_list" to "List all snapshots",
            "snapshot_restore" to "Restore a snapshot",
            "snapshot_delete" to "Delete a snapshot",
            // Backup
            "system_backup_export" to "Export a full system backup zip",
            "system_backup_import" to "Restore from a backup zip",
            // MCP
            "mcp_refresh" to "Discover / refresh MCP servers",
            "mcp_list_tools" to "List tools from connected MCP servers",
            "mcp_call_tool" to "Call a tool on an MCP server",
            // Control plane
            "control_list" to "List agent capability flags",
            "control_describe" to "Describe a capability flag",
            "control_set" to "Enable or disable a capability",
            "control_grant" to "Grant a capability",
            "control_revoke" to "Revoke a capability",
            "control_status" to "Status of all capability flags",
            // Local HTTP server
            "server_start" to "Start the local HTTP API server",
            "server_stop" to "Stop the local HTTP API server",
            "server_status" to "Status of the local HTTP API server",
            "server_rotate_key" to "Rotate the local server API key",
            // LAN project serving
            "project_serve" to "Serve a workspace project on the LAN",
            "project_unserve" to "Stop serving a project",
            "project_serve_list" to "List served projects",
            // Proactive
            "proactive_status" to "Check proactive scheduling status",
            "proactive_schedule" to "Schedule a proactive nudge",
            "proactive_cancel" to "Cancel a proactive nudge",
            // Secrets
            "secret_list" to "List named secrets (no values shown)",
            "secret_request" to "Request a secret by name",
            // Notifications
            "notify_send" to "Push a notification to the user",
            // Temp
            "temp_list" to "List temp files",
            "temp_clear" to "Clear temp files",
            // Misc
            "request_user_input" to "Pause and ask the user a question",
            "composio_call" to "Call a Composio action (200+ services)",
        )
    }
}
