package com.forge.os.domain.agent

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.data.android.AndroidController
import com.forge.os.data.browser.BrowserSessionManager
import com.forge.os.data.mcp.McpClient
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.data.server.ForgeHttpServer
import dagger.Lazy
import com.forge.os.data.server.ForgeHttpService
import com.forge.os.domain.alarms.AlarmAction
import com.forge.os.domain.alarms.AlarmItem
import com.forge.os.domain.alarms.AlarmRepository
import com.forge.os.domain.alarms.ForgeAlarmScheduler
import com.forge.os.domain.channels.ChannelManager
import com.forge.os.domain.doctor.DoctorService
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.forge.os.domain.snapshots.SnapshotManager
import com.forge.os.domain.agents.DelegationManager
import com.forge.os.domain.config.ConfigMutationEngine
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.cron.TaskType
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.memory.MemoryTier
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.domain.security.PermissionManager
import com.forge.os.domain.security.ToolAuditEntry
import com.forge.os.domain.security.ToolAuditLog
import com.forge.os.domain.workspace.WorkspaceLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Sentinel returned from request_user_input so the agent loop can detect
// that user input was requested.
const val USER_INPUT_SENTINEL = "__FORGE_INPUT_REQUESTED__"

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean = false
)

@Singleton
class ToolRegistry @Inject constructor(
    private val sandboxManager: SandboxManager,
    private val configRepository: ConfigRepository,
    private val configMutationEngine: ConfigMutationEngine,
    private val permissionManager: PermissionManager,
    private val heartbeatMonitor: HeartbeatMonitor,
    private val memoryManager: MemoryManager,
    private val cronManager: CronManager,
    private val pluginManager: PluginManager,
    private val delegationManager: DelegationManager,
    private val auditLog: ToolAuditLog,
    private val snapshotManager: SnapshotManager,
    private val mcpClient: McpClient,
    private val browserSessionManager: BrowserSessionManager,
    private val userInputBroker: UserInputBroker,
    private val namedSecretRegistry: com.forge.os.domain.security.NamedSecretRegistry,
    @ApplicationContext private val appContext: Context,
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: ForgeAlarmScheduler,
    private val androidController: AndroidController,
    private val httpServerLazy: Lazy<ForgeHttpServer>,
    private val doctorService: DoctorService,
    private val channelManager: ChannelManager,
    // Phase Q
    private val controlPlane: com.forge.os.domain.control.AgentControlPlane,
    private val consentLedger: com.forge.os.domain.control.UserConsentLedger,
    private val pluginExporter: com.forge.os.domain.plugins.PluginExporter,
    private val pluginRepository: com.forge.os.domain.plugins.PluginRepository,
    private val webScreenshotter: com.forge.os.data.web.WebScreenshotter,
    private val headlessBrowser: com.forge.os.data.web.HeadlessBrowser,
    private val browserHistory: com.forge.os.data.browser.BrowserHistory,
    private val projectServer: com.forge.os.data.server.ProjectStaticServer,
    private val agentNotifier: com.forge.os.domain.notifications.AgentNotificationBuilder,
    private val notificationActions: com.forge.os.domain.notifications.NotificationActionRegistry,
    private val proactiveScheduler: com.forge.os.domain.proactive.ProactiveScheduler,
    // Phase S
    private val gitRunner: com.forge.os.data.git.GitRunner,
    private val downloadManager: com.forge.os.data.net.DownloadManager,
    private val fileToolProvider: com.forge.os.domain.agent.providers.FileToolProvider,
    private val visionTool: com.forge.os.domain.tools.VisionTool,
    // Phase 3
    private val agentMessageBus: com.forge.os.domain.agents.AgentMessageBus,
    private val planAndExecuteDagTool: com.forge.os.domain.agent.planner.PlanAndExecuteDagTool,
    private val prefetchCache: com.forge.os.domain.proactive.PrefetchCache,
    private val securityPolicy: com.forge.os.data.sandbox.SecurityPolicy,
    // AutoPhone + Phone OS providers
    private val autoPhoneToolProvider: com.forge.os.domain.agent.providers.AutoPhoneToolProvider,
    private val contactsToolProvider: com.forge.os.domain.agent.providers.ContactsToolProvider,
    private val smsToolProvider: com.forge.os.domain.agent.providers.SmsToolProvider,
    private val calendarToolProvider: com.forge.os.domain.agent.providers.CalendarToolProvider,
    private val clipboardToolProvider: com.forge.os.domain.agent.providers.ClipboardToolProvider,
    private val phoneCallToolProvider: com.forge.os.domain.agent.providers.PhoneCallToolProvider,
    private val mediaControlToolProvider: com.forge.os.domain.agent.providers.MediaControlToolProvider,
    private val bridgeToolProvider: com.forge.os.domain.agent.providers.BridgeToolProvider,
    private val wifiToolProvider: com.forge.os.domain.agent.providers.WifiToolProvider,
    private val bluetoothToolProvider: com.forge.os.domain.agent.providers.BluetoothToolProvider,
    private val batteryToolProvider: com.forge.os.domain.agent.providers.BatteryToolProvider,
    private val deviceInfoToolProvider: com.forge.os.domain.agent.providers.DeviceInfoToolProvider,
    private val locationToolProvider: com.forge.os.domain.agent.providers.LocationToolProvider,
    private val storageToolProvider: com.forge.os.domain.agent.providers.StorageToolProvider,
    private val androidUiToolProvider: com.forge.os.domain.agent.providers.AndroidUiToolProvider,
    // Phase 1: Project-AI Integration
    private val projectToolProvider: com.forge.os.domain.projects.ProjectToolProvider,
    // Phase 2: Project Python Execution
    private val projectPythonRunner: com.forge.os.domain.projects.ProjectPythonRunner,
    // Task 4: Agent Learning & Personalization
    private val reflectionManager: ReflectionManager,
    private val executionHistoryManager: ExecutionHistoryManager,
    private val agentPersonality: AgentPersonality,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
    // Wishlist Features
    private val voiceInputManager: com.forge.os.domain.voice.VoiceInputManager,
    private val multiDeviceSyncManager: com.forge.os.domain.sync.MultiDeviceSyncManager,
    private val codeReviewService: com.forge.os.domain.code.CodeReviewService,
    private val projectHealthMonitor: com.forge.os.domain.projects.ProjectHealthMonitor,
    // API Manager for model catalog
    private val aiApiManager: com.forge.os.data.api.AiApiManager,
) {
    private val httpServer: ForgeHttpServer get() = httpServerLazy.get()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun phoneProviderTools(): List<ToolDefinition> =
        autoPhoneToolProvider.getTools() +
        contactsToolProvider.getTools() +
        smsToolProvider.getTools() +
        calendarToolProvider.getTools() +
        clipboardToolProvider.getTools() +
        phoneCallToolProvider.getTools() +
        mediaControlToolProvider.getTools() +
        bridgeToolProvider.getTools() +
        wifiToolProvider.getTools() +
        bluetoothToolProvider.getTools() +
        batteryToolProvider.getTools() +
        deviceInfoToolProvider.getTools() +
        locationToolProvider.getTools() +
        storageToolProvider.getTools() +
        androidUiToolProvider.getTools() +
        projectToolProvider.getTools()

    fun getDefinitions(): List<ToolDefinition> {
        val config = configRepository.get()
        val builtins = (fileToolProvider.getTools() + phoneProviderTools() + ALL_TOOLS + planAndExecuteDagTool.definition).let { all ->
            if (config.toolRegistry.enableAllTools) all
            else all.filter { it.function.name in config.toolRegistry.enabledTools }
        }
        val pluginTools = pluginManager.listAllTools().map { (_, tool) ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = tool.name,
                    description = "[plugin] " + tool.description,
                    parameters = FunctionParameters(
                        properties = tool.params.mapValues { (_, spec) ->
                            val parts = spec.split(":", limit = 2)
                            ParameterProperty(type = parts[0], description = parts.getOrElse(1) { "" })
                        },
                        required = tool.params.keys.toList()
                    )
                )
            )
        }
        val mcpTools = mcpClient.cachedToolsWithServer().map { (server, spec) ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = mcpClient.qualifiedName(server, spec.name),
                    description = "[mcp:${server.name}] " + spec.description,
                    parameters = mcpSchemaToFunctionParameters(spec.inputSchema)
                )
            )
        }
        return builtins + pluginTools + mcpTools
    }

    fun getAllDefinitions(): List<ToolDefinition> {
        val builtins = fileToolProvider.getTools() + phoneProviderTools() + ALL_TOOLS + planAndExecuteDagTool.definition
        val pluginTools = pluginManager.listAllTools().map { (_, tool) ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = tool.name,
                    description = "[plugin] " + tool.description,
                    parameters = FunctionParameters(
                        properties = tool.params.mapValues { (_, spec) ->
                            val parts = spec.split(":", limit = 2)
                            ParameterProperty(type = parts[0], description = parts.getOrElse(1) { "" })
                        },
                        required = tool.params.keys.toList()
                    )
                )
            )
        }
        val mcpTools = mcpClient.cachedToolsWithServer().map { (server, spec) ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = mcpClient.qualifiedName(server, spec.name),
                    description = "[mcp:${server.name}] " + spec.description,
                    parameters = mcpSchemaToFunctionParameters(spec.inputSchema)
                )
            )
        }
        return builtins + pluginTools + mcpTools
    }

    fun getSchema(toolName: String): String? = runCatching {
        val def = getAllDefinitions().firstOrNull { it.function.name == toolName } ?: return null
        json.encodeToString(FunctionParameters.serializer(), def.function.parameters)
    }.getOrNull()

    private fun mcpSchemaToFunctionParameters(schema: kotlinx.serialization.json.JsonElement?): FunctionParameters {
        val obj = (schema as? kotlinx.serialization.json.JsonObject) ?: return FunctionParameters(
            properties = emptyMap(), required = emptyList()
        )
        val propsObj = (obj["properties"] as? kotlinx.serialization.json.JsonObject)
            ?: return FunctionParameters(properties = emptyMap(), required = emptyList())
        val props = propsObj.entries.associate { (name, propEl) ->
            val propObj = propEl as? kotlinx.serialization.json.JsonObject
            val type = propObj?.get("type")?.let { t ->
                (t as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: ((t as? kotlinx.serialization.json.JsonArray)
                        ?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            } ?: "string"
            val desc = (propObj?.get("description") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
            val enum = (propObj?.get("enum") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
            val coerced = when (type) {
                "string", "number", "integer", "boolean", "array", "object" -> type
                else -> "string"
            }
            name to ParameterProperty(type = coerced, description = desc, enum = enum)
        }
        val required = (obj["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        return FunctionParameters(properties = props, required = required)
    }

    suspend fun dispatch(toolName: String, argsJson: String, toolCallId: String): ToolResult {
        // Feature 9 — Predictive Prefetch Cache Check
        if (!toolCallId.startsWith("prefetch_")) {
            val cached = prefetchCache.getAndRemove(toolName, argsJson)
            if (cached != null) {
                Timber.i("🚀 PREFETCH HIT: $toolName (args=$argsJson)")
                // Re-stamp with the current toolCallId so the agent loop
                // thinks it was just executed.
                return cached.copy(toolCallId = toolCallId)
            }
        }

        val started = System.currentTimeMillis()
        val source = if (toolCallId.startsWith("ui_test_")) "user" else "agent"
        Timber.d("Tool dispatch: $toolName args=$argsJson")
        val check = permissionManager.checkTool(toolName, parseArgs(argsJson))
        if (!check.allowed) {
            val r = ToolResult(toolCallId, toolName, "❌ Permission denied: ${check.reason}", isError = true)
            recordAudit(toolName, argsJson, started, r, source)
            return r
        }
        return try {
            val args = parseArgs(argsJson)
            val output = if (toolName == "python_run") {
                smartPythonRun(args)
            } else {
                fileToolProvider.dispatch(toolName, args)
                    ?: autoPhoneToolProvider.dispatch(toolName, args)
                    ?: contactsToolProvider.dispatch(toolName, args)
                    ?: smsToolProvider.dispatch(toolName, args)
                    ?: calendarToolProvider.dispatch(toolName, args)
                    ?: clipboardToolProvider.dispatch(toolName, args)
                    ?: phoneCallToolProvider.dispatch(toolName, args)
                    ?: mediaControlToolProvider.dispatch(toolName, args)
                    ?: bridgeToolProvider.dispatch(toolName, args)
                    ?: wifiToolProvider.dispatch(toolName, args)
                    ?: bluetoothToolProvider.dispatch(toolName, args)
                    ?: batteryToolProvider.dispatch(toolName, args)
                    ?: deviceInfoToolProvider.dispatch(toolName, args)
                    ?: locationToolProvider.dispatch(toolName, args)
                    ?: storageToolProvider.dispatch(toolName, args)
                    ?: androidUiToolProvider.dispatch(toolName, args)
            } ?: when (toolName) {
                "plan_and_execute_dag" -> {
                    val complexGoal = args["complex_goal"]?.toString()
                        ?: return ToolResult(toolCallId, toolName, "Error: complex_goal required", isError = true)
                
                    val jo = kotlinx.serialization.json.JsonObject(
                        mapOf("complex_goal" to kotlinx.serialization.json.JsonPrimitive(complexGoal))
                    )
                    planAndExecuteDagTool.execute(jo)
                }
                "config_read"        -> configRead()
                "config_write"       -> configWrite(args)
                "config_rollback"    -> configRollback(args)
                // ─── System ────────────────────────────────────────────────────────
                "heartbeat_check"    -> heartbeatCheck()
                // ─── Memory ────────────────────────────────────────────────────────
                "memory_store"       -> memoryStore(args)
                "memory_recall"      -> memoryRecall(args)
                "semantic_recall_facts" -> semanticRecallFacts(args)
                "memory_store_skill" -> memoryStoreSkill(args)
                "memory_get_skill"   -> memoryGetSkill(args)
                "memory_list_skills" -> memoryListSkills()
                "memory_store_image" -> memoryStoreImage(args)
                "memory_summary"     -> memorySummary()
                // ─── Channels ──────────────────────────────────────────────────────
                "telegram_react"     -> telegramReact(args)
                "telegram_reply"     -> telegramReply(args)
                "telegram_send_file" -> telegramSendFile(args)
                // ─── Cron ──────────────────────────────────────────────────────────
                "cron_add"           -> {
                    val name = args["name"]?.toString() ?: "Untitled"
                    val schedule = args["schedule"]?.toString() ?: ""
                    val payload = args["payload"]?.toString() ?: ""
                    val typeStr = args["task_type"]?.toString() ?: "PROMPT"
                    val type = try { com.forge.os.domain.cron.TaskType.valueOf(typeStr.uppercase()) } catch (e: Exception) { com.forge.os.domain.cron.TaskType.PROMPT }
                    val tags = args["tags"]?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    val modelStr = args["model"]?.toString()
                    val (provider, model) = if (modelStr != null) {
                        // Very simple heuristic: if it contains a slash it might be provider/model
                        if (modelStr.contains("/")) {
                            val parts = modelStr.split("/", limit = 2)
                            parts[0] to parts[1]
                        } else {
                            // If just a model name, we try to guess or use it as is
                            null to modelStr
                        }
                    } else null to null
                    
                    cronManager.addJob(
                        name = name,
                        taskType = type,
                        payload = payload,
                        scheduleText = schedule,
                        tags = tags,
                        overrideProvider = provider,
                        overrideModel = model
                    ).fold(
                        { "Scheduled job '${it.name}' (id: ${it.id}) using ${it.schedule.pretty()}" },
                        { err ->
                            val hint = if (schedule.isNotBlank())
                                " — ${com.forge.os.domain.cron.CronScheduler.diagnose(schedule)}"
                            else ""
                            "❌ Could not schedule cron job: ${err.message}$hint\n" +
                            "💡 Valid formats: 'every 10 minutes' (min 5 min), 'every 2 hours', " +
                            "'every hour', 'every day', 'daily at 09:00'"
                        }
                    )
                }
                "cron_list"          -> cronList()
                "cron_remove"        -> cronRemove(args)
                "cron_run_now"       -> cronRunNow(args)
                "cron_history"       -> cronHistory()
                // ─── Backup ────────────────────────────────────────────────────────
                "system_backup_export" -> {
                    val name = args["filename"]?.toString() ?: "forge_backup_${System.currentTimeMillis()}.zip"
                    val dest = java.io.File(appContext.filesDir, "workspace/exports/$name")
                    dest.parentFile?.mkdirs()
                    snapshotManager.exportFullBackup(dest).fold(
                        { "✅ Backup created at exports/$name (${it.length() / 1024} KB)" },
                        { "❌ Backup failed: ${it.message}" }
                    )
                }
                "system_backup_import" -> {
                    val path = args["path"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: path required", isError = true)
                    val source = java.io.File(appContext.filesDir, "workspace/$path")
                    if (!source.exists()) return ToolResult(toolCallId, toolName, "Error: Backup file not found at $path", isError = true)
                    snapshotManager.importFullBackup(source).fold(
                        { "✅ System restored! $it files recovered. The app may need a restart to refresh all modules." },
                        { "❌ Restore failed: ${it.message}" }
                    )
                }
                // ─── Plugins ───────────────────────────────────────────────────────
                "plugin_list"        -> pluginList()
                "plugin_install"     -> pluginInstall(args)
                "plugin_uninstall"   -> pluginUninstall(args)
                "plugin_execute"     -> pluginExecute(args)
                // ─── Delegation ────────────────────────────────────────────────────
                "delegate_task"      -> {
                    val goal = args["goal"]?.toString() ?: ""
                    val context = args["context"]?.toString() ?: ""
                    val tags = args["tags"]?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    val modelStr = args["model"]?.toString()
                    val (provider, model) = if (modelStr != null) {
                        if (modelStr.contains("/")) {
                            val parts = modelStr.split("/", limit = 2)
                            parts[0] to parts[1]
                        } else null to modelStr
                    } else null to null

                    val ctx = kotlin.coroutines.coroutineContext[com.forge.os.domain.agents.AgentContext]
                    delegationManager.spawnAndAwait(
                        goal = goal,
                        context = context,
                        tags = tags,
                        overrideProvider = provider,
                        overrideModel = model,
                        parentId = ctx?.agentId,
                        callerDepth = ctx?.depth ?: 0
                    ).summary
                }
                "delegate_batch"     -> delegateBatch(args)
                "agents_list"        -> agentsList()
                "agent_status"       -> agentStatus(args)
                "agent_cancel"       -> agentCancel(args)
                // ─── Message Bus ───────────────────────────────────────────────────
                "message_bus_publish" -> messageBusPublish(args)
                "message_bus_read"    -> messageBusRead(args)
                "message_bus_topics"  -> messageBusTopics()
                // ─── Snapshots ─────────────────────────────────────────────────────
                "snapshot_create"    -> snapshotCreate(args)
                "snapshot_list"      -> snapshotList()
                "snapshot_restore"   -> snapshotRestore(args)
                "snapshot_delete"    -> snapshotDelete(args)
                // Model catalog
                "model_cache_refresh" -> modelCacheRefresh()
                // ─── MCP ───────────────────────────────────────────────────────────
                "mcp_refresh"        -> mcpRefresh()
                "mcp_list_tools"     -> mcpListTools()
                // ─── HTTP / Fetch tools ────────────────────────────────────────────
                "http_fetch"         -> httpFetch(args)
                "curl_exec"          -> curlExec(args)
                // ─── Search ────────────────────────────────────────────────────────
                "ddg_search"         -> ddgSearch(args)
                // ─── Browser control ───────────────────────────────────────────────
                "browser_navigate"   -> browserNavigate(args)
                "browser_get_html"   -> browserGetHtml(args)
                "browser_eval_js"    -> browserEvalJs(args)
                "browser_fill_field" -> browserFillField(args)
                "browser_click"      -> browserClick(args)
                "browser_click_at"   -> browserClickAt(args)
                "browser_type"       -> browserType(args)
                "browser_scroll"     -> browserScroll(args)
                "browser_set_viewport"     -> browserSetViewport(args)
                "browser_screenshot_region" -> browserScreenshotRegion(args)
                "browser_wait_for_selector" -> browserWaitForSelector(args)
                "browser_get_text"         -> browserGetText(args)
                "browser_get_attribute"    -> browserGetAttribute(args)
                "browser_list_links"       -> browserListLinks(args)
                "file_upload_to_browser"   -> fileUploadToBrowser(args)
                "plugin_create"            -> pluginCreate(args)
                // ─── Temp / upload folder ──────────────────────────────────────────
                "temp_list"          -> tempList()
                "temp_clear"         -> tempClear()
                // ─── Mid-run user input ─────────────────────────────────────────────
                "request_user_input" -> {
                    val question = args["question"]?.toString() ?: "What do you need?"
                    userInputBroker.awaitResponse(question)
                }
                // ─── Composio ──────────────────────────────────────────────────────
                "composio_call"      -> composioCall(args)
                // ─── Phase 3: Hybrid Python Execution ──────────────────────────────
                "python_run_remote"  -> pythonRunRemote(args)
                // ─── Alarms ────────────────────────────────────────────────────────
                "alarm_set"          -> {
                    val label = args["label"]?.toString() ?: "Alarm"
                    val inSeconds = args["in_seconds"]?.toString()?.toLongOrNull()
                    val atMillis = args["at_millis"]?.toString()?.toLongOrNull()
                    val actionStr = args["action"]?.toString() ?: "NOTIFY"
                    val payload = args["payload"]?.toString() ?: ""
                    val modelStr = args["model"]?.toString()
                    val repeatMs = args["repeat_ms"]?.toString()?.toLongOrNull() ?: 0L
                    
                    val (provider, model) = if (modelStr != null) {
                        if (modelStr.contains("/")) {
                            val parts = modelStr.split("/", limit = 2)
                            parts[0] to parts[1]
                        } else null to modelStr
                    } else null to null

                    val triggerAt = when {
                        atMillis != null -> atMillis
                        inSeconds != null -> {
                            if (inSeconds <= 0L) return ToolResult(
                                toolCallId, toolName,
                                "❌ Error: 'in_seconds' must be a positive number (got $inSeconds). " +
                                "Use a value > 0 to schedule an alarm in the future.",
                                isError = true
                            )
                            System.currentTimeMillis() + (inSeconds * 1000)
                        }
                        else -> return ToolResult(toolCallId, toolName, "❌ Error: either 'in_seconds' or 'at_millis' is required", isError = true)
                    }

                    // Reject alarms whose trigger time is already in the past (guards
                    // against a negative at_millis or a race between computation and check).
                    if (triggerAt <= System.currentTimeMillis()) {
                        return ToolResult(
                            toolCallId, toolName,
                            "❌ Error: trigger time ${java.util.Date(triggerAt)} is in the past. " +
                            "Provide a positive 'in_seconds' or a future 'at_millis' timestamp.",
                            isError = true
                        )
                    }

                    val act = runCatching { AlarmAction.valueOf(actionStr) }.getOrElse { AlarmAction.NOTIFY }
                    val item = AlarmItem(
                        id = "alarm_${System.currentTimeMillis()}",
                        label = label,
                        triggerAt = triggerAt,
                        repeatIntervalMs = repeatMs,
                        action = act,
                        payload = payload,
                        overrideProvider = provider,
                        overrideModel = model
                    )
                    alarmScheduler.addAlarm(item)
                    "✅ Alarm '${item.label}' scheduled at ${java.util.Date(item.triggerAt)} (id=${item.id})"
                }
                "alarm_list"         -> alarmList()
                "alarm_cancel"       -> alarmCancel(args)
                // ─── Android control ───────────────────────────────────────────────
                "android_device_info" -> androidController.deviceInfo().toString()
                "android_battery"     -> androidController.battery().toString()
                "android_volume"      -> androidController.volume().toString()
                "android_set_volume"  -> androidSetVolume(args)
                "android_network"     -> androidController.networkState().toString()
                "android_storage"     -> androidController.storage().toString()
                "android_list_apps"   -> androidListApps(args)
                "android_launch_app"  -> androidLaunchApp(args)
                "android_screen"      -> androidController.screenInfo().toString()
                "android_snapshot"    -> androidController.snapshot().toString()
                // ─── Local HTTP server ─────────────────────────────────────────────
                "server_start"        -> serverStart(args)
                "server_stop"         -> serverStop()
                "server_status"       -> serverStatus()
                "server_rotate_key"   -> serverRotateKey()
                // ─── Doctor ────────────────────────────────────────────────────────
                "doctor_check"        -> doctorCheck()
                "doctor_fix"          -> doctorFix(args)
                // ─── Channels ──────────────────────────────────────────────────────
                "channel_list"        -> channelList()
                "channel_send"        -> channelSend(args)
                "channel_toggle"      -> channelToggle(args)
                "channel_add_telegram" -> channelAddTelegram(args)
                "telegram_send_voice" -> telegramSendVoice(args)
                // ─── Telegram session / allow-list helpers ─────────────────────────
                "telegram_main_chat"        -> telegramMainChat(args)
                "telegram_list_chats"       -> telegramListChats(args)
                "telegram_get_allowed_chats"-> telegramGetAllowedChats(args)
                "telegram_set_allowed_chats"-> telegramSetAllowedChats(args)
                "telegram_allow_chat"       -> telegramAllowChat(args)
                "telegram_deny_chat"        -> telegramDenyChat(args)
                "telegram_get_history"      -> telegramGetHistory(args)
                // ─── Self-description ──────────────────────────────────────────────
                "app_describe"        -> appDescribe()
                // ─── Phase Q: Agent Control Plane ──────────────────────────────────
                "control_list"        -> controlList()
                "control_describe"    -> controlDescribe(args)
                "control_set"         -> controlSet(args, source)
                "control_grant"       -> controlGrant(args, source)
                "control_revoke"      -> controlRevoke(args, source)
                "control_status"      -> controlPlane.summary()
                // ─── Phase Q: Plugin persistence ───────────────────────────────────
                "plugin_export_all"   -> pluginExportAll()
                "plugin_export_list"  -> pluginExportList()
                "plugin_restore_missing" -> pluginRestoreMissing()
                // ─── Phase Q: Notifications with actions ───────────────────────────
                "notify_send"         -> notifySend(args)
                // ─── Phase Q: Web screenshot ───────────────────────────────────────
                "web_screenshot"      -> webScreenshot(args)
                // ─── Phase Q: Browser history & sessions ───────────────────────────
                "browser_history_list"  -> browserHistoryList(args)
                "browser_history_clear" -> browserHistoryClear(args)
                "browser_session_new"   -> browserSessionNew(args)
                // ─── Phase Q: LAN project server ───────────────────────────────────
                "project_serve"       -> projectServe(args)
                "project_unserve"     -> projectUnserve(args)
                "project_serve_list"  -> projectServeList()
                // ─── Phase Q: Proactive ────────────────────────────────────────────
                "proactive_status"    -> proactiveStatus()
                "proactive_schedule"  -> proactiveSchedule()
                "proactive_cancel"    -> proactiveCancel()
                // ─── Phase S: Git ──────────────────────────────────────────────────
                "git_init"            -> gitRunner.init(args["path"]?.toString() ?: ".")
                "git_status"          -> gitRunner.status(args["path"]?.toString() ?: ".")
                "git_add"             -> gitRunner.add(
                    args["path"]?.toString() ?: ".",
                    args["pattern"]?.toString() ?: "."
                )
                "git_commit"          -> {
                    val msg = args["message"]?.toString()
                        ?: return ToolResult(toolCallId, toolName, "Error: message required", isError = true)
                    gitRunner.commit(
                        path        = args["path"]?.toString() ?: ".",
                        message     = msg,
                        authorName  = args["author"]?.toString(),
                        authorEmail = args["email"]?.toString(),
                        addAll      = (args["add_all"] as? Boolean) ?: true,
                    )
                }
                "git_log"             -> gitRunner.log(
                    args["path"]?.toString() ?: ".",
                    (args["limit"] as? Number)?.toInt() ?: 20
                )
                "git_diff"            -> gitRunner.diff(args["path"]?.toString() ?: ".")
                "git_branch"          -> gitRunner.branch(args["path"]?.toString() ?: ".")
                "git_checkout"        -> {
                    val name = args["name"]?.toString()
                        ?: return ToolResult(toolCallId, toolName, "Error: name required", isError = true)
                    gitRunner.checkout(
                        path = args["path"]?.toString() ?: ".",
                        branch = name,
                        create = (args["create"] as? Boolean) ?: false,
                    )
                }
                "git_remote_set"      -> {
                    val url = args["url"]?.toString()
                        ?: return ToolResult(toolCallId, toolName, "Error: url required", isError = true)
                    gitRunner.remoteSet(
                        path = args["path"]?.toString() ?: ".",
                        name = args["name"]?.toString() ?: "origin",
                        url  = url,
                    )
                }
                "git_clone"           -> {
                    val url = args["url"]?.toString()
                        ?: return ToolResult(toolCallId, toolName, "Error: url required", isError = true)
                    val dest = args["dest"]?.toString()
                        ?: return ToolResult(toolCallId, toolName, "Error: dest required", isError = true)
                    gitRunner.clone(url = url, intoPath = dest, token = args["token"]?.toString())
                }
                "git_push"            -> gitRunner.push(
                    path   = args["path"]?.toString() ?: ".",
                    remote = args["remote"]?.toString() ?: "origin",
                    branch = args["branch"]?.toString(),
                    token  = args["token"]?.toString(),
                )
                "git_pull"            -> gitRunner.pull(
                    path   = args["path"]?.toString() ?: ".",
                    remote = args["remote"]?.toString() ?: "origin",
                    token  = args["token"]?.toString(),
                )
                // ─── Phase S: Downloads ────────────────────────────────────────────
                "file_download"       -> downloadManager.download(
                    url      = args["url"]?.toString()
                        ?: return ToolResult(toolCallId, toolName,
                            "Error: url required", isError = true),
                    saveAs   = args["save_as"]?.toString(),
                    headers  = (args["headers"] as? Map<*, *>)
                        ?.mapNotNull { (k, v) -> if (k != null && v != null) k.toString() to v.toString() else null }
                        ?.toMap() ?: emptyMap(),
                    maxBytes = (args["max_bytes"] as? Number)?.toLong()
                        ?: com.forge.os.data.net.DownloadManager.DEFAULT_MAX_BYTES,
                ).toString()
                // ─── Phase T: Named secrets (extension mechanism) ──────────────────
                "secret_list"         -> secretList()
                "secret_request"      -> secretRequest(args)
                "delegate_ghost"      -> delegateGhost(args)
                // ─── MCP explicit invocation ─────────────────────────────────────
                "mcp_call_tool"       -> mcpCallTool(args)
                // ─── Vision: analyse a workspace image via a vision LLM ──────────
                "image_analyze"       -> {
                    val path = args["path"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: path required", isError = true)
                    val prompt = args["prompt"]?.toString() ?: "Describe this image."
                    val model = args["model"]?.toString()
                    visionTool.analyze(path, prompt, model)
                }
                "browser_download"    -> downloadManager.downloadWithBrowserCookies(
                    url      = args["url"]?.toString()
                        ?: return ToolResult(toolCallId, toolName,
                            "Error: url required", isError = true),
                    saveAs   = args["save_as"]?.toString(),
                    maxBytes = (args["max_bytes"] as? Number)?.toLong()
                        ?: com.forge.os.data.net.DownloadManager.DEFAULT_MAX_BYTES,
                ).toString()
                // ─── Phase 1: Project-AI Integration ───────────────────────────────
                "project_list"           -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_list"
                "project_read_metadata"  -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_read_metadata"
                "project_write_metadata" -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_write_metadata"
                "project_read_file"      -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_read_file"
                "project_write_file"     -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_write_file"
                "project_list_files"     -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_list_files"
                "project_activate"       -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_activate"
                "project_get_active"     -> projectToolProvider.dispatch(toolName, args) ?: "Error dispatching project_get_active"
                // ─── Phase 2: Project Python Execution ─────────────────────────────
                "project_python_run_file" -> {
                    val slug = args["slug"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: slug required", isError = true)
                    val scriptPath = args["script_path"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: script_path required", isError = true)
                    val argsJson = args["args"] as? List<*> ?: emptyList<String>()
                    val argsList = argsJson.mapNotNull { it as? String }
                    projectPythonRunner.executeFile(slug, scriptPath, argsList)
                }
                "project_python_run_code" -> {
                    val slug = args["slug"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: slug required", isError = true)
                    val code = args["code"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: code required", isError = true)
                    val argsJson = args["args"] as? List<*> ?: emptyList<String>()
                    val argsList = argsJson.mapNotNull { it as? String }
                    projectPythonRunner.executeCode(slug, code, argsList)
                }
                // ─── Task 4: Agent Learning & Personalization ──────────────────────
                "reflection_get_context" -> {
                    val goal = args["goal"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: goal required", isError = true)
                    val context = reflectionManager.createReflectionPrompt(goal)
                    context
                }
                "history_show" -> {
                    val sessionId = args["session_id"]?.toString()
                    val history = if (sessionId != null) {
                        executionHistoryManager.getFormattedHistory(sessionId)
                    } else {
                        val current = executionHistoryManager.getCurrentSession()
                        if (current != null) {
                            executionHistoryManager.getFormattedHistory(current.sessionId)
                        } else {
                            "No execution history available yet."
                        }
                    }
                    history
                }
                "personality_update" -> {
                    val personality = agentPersonality.getPersonality()
                    val updated = personality.copy(
                        name = args["name"]?.toString() ?: personality.name,
                        systemPrompt = args["system_prompt"]?.toString() ?: personality.systemPrompt,
                        traits = args["traits"]?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: personality.traits,
                        communicationStyle = args["communication_style"]?.toString() ?: personality.communicationStyle,
                        customInstructions = args["custom_instructions"]?.toString() ?: personality.customInstructions,
                        lastModified = System.currentTimeMillis()
                    )
                    agentPersonality.updatePersonality(updated)
                    "✅ Agent personality updated:\n${agentPersonality.getPersonalitySummary()}"
                }
                "personality_list" -> {
                    val profiles = agentPersonality.listProfiles()
                    val current = agentPersonality.getPersonality().name
                    if (profiles.isEmpty()) "No saved personality profiles yet. Use personality_save to save the current one."
                    else "🎭 Personality profiles:\n" + profiles.joinToString("\n") { p ->
                        "  ${if (p == current) "▶" else "  "} $p"
                    } + "\n\nActive: $current"
                }
                "personality_save" -> {
                    val name = args["name"]?.toString()?.trim()
                        ?: return@dispatch "Error: name required"
                    agentPersonality.saveProfile(name)
                    "✅ Saved personality profile: $name"
                }
                "personality_switch" -> {
                    val name = args["name"]?.toString()?.trim()
                        ?: return@dispatch "Error: name required"
                    val ok = agentPersonality.switchToProfile(name)
                    if (ok) "✅ Switched to personality: ${agentPersonality.getPersonality().name}\n\nThe new personality takes effect on the next message."
                    else "❌ No profile named '$name'. Use personality_list to see available profiles."
                }
                "personality_reset" -> {
                    agentPersonality.resetToDefault()
                    "✅ Personality reset to Forge defaults.\n\n${agentPersonality.getPersonalitySummary()}"
                }
                "preferences_show" -> {
                    userPreferencesManager.getPreferencesSummary()
                }
                // ─── Wishlist Features ─────────────────────────────────────────────
                "voice_start_listening" -> {
                    voiceInputManager.startListening()
                    "🎤 Started listening for voice input"
                }
                "voice_stop_listening" -> {
                    voiceInputManager.stopListening()
                    "🎤 Stopped listening"
                }
                "voice_speak" -> {
                    val text = args["text"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: text required", isError = true)
                    voiceInputManager.speak(text)
                    "🔊 Speaking: $text"
                }
                "sync_export" -> {
                    val includeConfig = args["include_config"]?.toString()?.toBoolean() ?: true
                    val includeProjects = args["include_projects"]?.toString()?.toBoolean() ?: true
                    val includeMemory = args["include_memory"]?.toString()?.toBoolean() ?: true
                    val includePreferences = args["include_preferences"]?.toString()?.toBoolean() ?: true
                    
                    val options = com.forge.os.domain.sync.SyncOptions(
                        syncConfig = includeConfig,
                        syncProjects = includeProjects,
                        syncMemory = includeMemory,
                        syncPreferences = includePreferences
                    )
                    
                    val file = multiDeviceSyncManager.exportSyncPackage(options)
                    "✅ Sync package exported to: ${file.name}\nSize: ${file.length() / 1024} KB"
                }
                "sync_import" -> {
                    val path = args["path"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: path required", isError = true)
                    val file = java.io.File(appContext.filesDir, "workspace/$path")
                    
                    val includeConfig = args["include_config"]?.toString()?.toBoolean() ?: true
                    val includeProjects = args["include_projects"]?.toString()?.toBoolean() ?: true
                    val includeMemory = args["include_memory"]?.toString()?.toBoolean() ?: true
                    val includePreferences = args["include_preferences"]?.toString()?.toBoolean() ?: true
                    
                    val options = com.forge.os.domain.sync.SyncOptions(
                        syncConfig = includeConfig,
                        syncProjects = includeProjects,
                        syncMemory = includeMemory,
                        syncPreferences = includePreferences
                    )
                    
                    val result = multiDeviceSyncManager.importSyncPackage(file, options)
                    if (result.success) "✅ ${result.message}" else "❌ ${result.message}"
                }
                "sync_init_device" -> {
                    val deviceName = args["device_name"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: device_name required", isError = true)
                    val deviceId = args["device_id"]?.toString()
                    
                    if (deviceId != null) {
                        multiDeviceSyncManager.initializeDevice(deviceName, deviceId)
                    } else {
                        multiDeviceSyncManager.initializeDevice(deviceName)
                    }
                    "✅ Device initialized: $deviceName"
                }
                "code_review_project" -> {
                    val slug = args["slug"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: slug required", isError = true)
                    val checkQuality = args["check_quality"]?.toString()?.toBoolean() ?: true
                    val checkSecurity = args["check_security"]?.toString()?.toBoolean() ?: true
                    val checkPerformance = args["check_performance"]?.toString()?.toBoolean() ?: true
                    val checkDocs = args["check_documentation"]?.toString()?.toBoolean() ?: true
                    val useAI = args["use_ai"]?.toString()?.toBoolean() ?: false
                    
                    val options = com.forge.os.domain.code.ReviewOptions(
                        checkQuality = checkQuality,
                        checkSecurity = checkSecurity,
                        checkPerformance = checkPerformance,
                        checkDocumentation = checkDocs,
                        useAI = useAI
                    )
                    
                    val result = codeReviewService.reviewProject(slug, options)
                    if (result.success) {
                        buildString {
                            appendLine(result.summary)
                            appendLine()
                            appendLine("Overall Score: ${result.overallScore}/100")
                            appendLine()
                            appendLine("Files with issues:")
                            result.fileReviews.filter { it.issues.isNotEmpty() }.take(10).forEach { review ->
                                appendLine("  • ${review.filePath}: ${review.issues.size} issues (score: ${review.score})")
                            }
                        }
                    } else {
                        "❌ ${result.error}"
                    }
                }
                "code_review_file" -> {
                    val path = args["path"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: path required", isError = true)
                    val checkQuality = args["check_quality"]?.toString()?.toBoolean() ?: true
                    val checkSecurity = args["check_security"]?.toString()?.toBoolean() ?: true
                    val checkPerformance = args["check_performance"]?.toString()?.toBoolean() ?: true
                    val checkDocs = args["check_documentation"]?.toString()?.toBoolean() ?: true
                    val useAI = args["use_ai"]?.toString()?.toBoolean() ?: false
                    
                    val options = com.forge.os.domain.code.ReviewOptions(
                        checkQuality = checkQuality,
                        checkSecurity = checkSecurity,
                        checkPerformance = checkPerformance,
                        checkDocumentation = checkDocs,
                        useAI = useAI
                    )
                    
                    val review = codeReviewService.reviewFile(path, options)
                    buildString {
                        appendLine("Code Review: $path")
                        appendLine("Score: ${review.score}/100")
                        appendLine("Lines of Code: ${review.linesOfCode}")
                        appendLine()
                        if (review.issues.isEmpty()) {
                            appendLine("✅ No issues found!")
                        } else {
                            appendLine("Issues found:")
                            review.issues.forEach { issue ->
                                appendLine("  [${issue.severity}] Line ${issue.line}: ${issue.message}")
                                if (issue.suggestion != null) {
                                    appendLine("    💡 ${issue.suggestion}")
                                }
                            }
                        }
                    }
                }
                "project_health" -> {
                    val slug = args["slug"]?.toString() ?: return ToolResult(toolCallId, toolName, "Error: slug required", isError = true)
                    val health = projectHealthMonitor.getProjectHealth(slug)
                    projectHealthMonitor.generateHealthReport(health)
                }
                "project_health_all" -> {
                    val healthMap = projectHealthMonitor.getAllProjectsHealth()
                    buildString {
                        appendLine("🏥 All Projects Health")
                        appendLine("=" .repeat(50))
                        appendLine()
                        healthMap.forEach { (slug, health) ->
                            appendLine("${health.status.emoji} ${health.projectName}")
                            appendLine("  Tests: ${health.testStatus.passingTests}/${health.testStatus.totalTests} passing")
                            appendLine("  Code Quality: ${health.codeQuality.score}/100")
                            appendLine("  Build: ${if (health.buildStatus.canBuild) "✓" else "✗"}")
                            appendLine()
                        }
                    }
                }
                else -> {
                    if (toolName.startsWith("mcp.")) {
                        val resolved = mcpClient.resolveTool(toolName)
                            ?: return ToolResult(toolCallId, toolName,
                                "❌ MCP tool not found or its server is removed", isError = true)
                        val r = mcpClient.callTool(resolved.first, resolved.second, args)
                        r.fold(onSuccess = { it },
                               onFailure = { return ToolResult(toolCallId, toolName, "❌ ${it.message}", isError = true) })
                    } else if (pluginManager.resolveTool(toolName) != null) {
                        val r = pluginManager.executeTool(toolName, args)
                        if (!r.success) return ToolResult(toolCallId, toolName, r.output, isError = true)
                        r.output
                    } else "Unknown tool: $toolName"
                }
            }
            val r = ToolResult(toolCallId, toolName, output)
            recordAudit(toolName, argsJson, started, r, source)
            r
        } catch (e: Exception) {
            Timber.e(e, "Tool $toolName failed")
            val r = ToolResult(toolCallId, toolName, "Error: ${e.message}", isError = true)
            recordAudit(toolName, argsJson, started, r, source)
            r
        }
    }

    private fun recordAudit(toolName: String, argsJson: String, startedAt: Long, result: ToolResult, source: String) {
        runCatching {
            auditLog.record(ToolAuditEntry(
                id = "audit_${startedAt}_${(0..9999).random()}",
                timestamp = startedAt, toolName = toolName,
                args = argsJson.take(400), success = !result.isError,
                durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
                outputPreview = result.output.take(200), source = source,
            ))
        }
    }

    // ─── Config tools ────────────────────────────────────────────────────────

    private fun configRead(): String {
        val config = configRepository.get()
        return buildString {
            appendLine("Config v${config.version}:")
            appendLine("• Agent: ${config.agentIdentity.name}")
            appendLine("• Model: ${config.modelRouting.defaultProvider}/${config.modelRouting.defaultModel}")
            appendLine("• Auto-confirm: ${config.behaviorRules.autoConfirmToolCalls}")
            appendLine("• Max iterations: ${config.behaviorRules.maxIterations}")
            appendLine("• Enabled tools: ${config.toolRegistry.enabledTools.size}")
            appendLine("• Enable all tools: ${config.toolRegistry.enableAllTools}")
            appendLine("• Heartbeat: ${config.heartbeatSettings.intervalSeconds}s")
        }
    }

    private suspend fun configWrite(args: Map<String, Any>): String {
        val request = args["request"]?.toString() ?: return "Error: request required"
        return when (val result = configMutationEngine.processConfigRequest(request)) {
            is com.forge.os.domain.config.ConfigMutationResult.Success -> result.message
            is com.forge.os.domain.config.ConfigMutationResult.Error -> "❌ ${result.reason}"
        }
    }

    private fun configRollback(args: Map<String, Any>): String {
        val version = args["version"]?.toString() ?: return "Error: version required"
        return try { configMutationEngine.rollbackToVersion(version); "✅ Config rolled back to v$version" }
        catch (e: Exception) { "❌ ${e.message}" }
    }

    private suspend fun heartbeatCheck(): String {
        val status = heartbeatMonitor.checkNow()
        return buildString {
            appendLine("💓 System Health: ${status.overallHealth}")
            status.components.forEach { (name, comp) ->
                appendLine("  $name: ${comp.health} ${comp.message?.let { "— $it" } ?: ""}")
                comp.metrics.forEach { (k, v) -> appendLine("    $k: $v") }
            }
            if (status.alerts.isNotEmpty()) {
                appendLine("⚠️ Alerts:")
                status.alerts.forEach { appendLine("  ${it.component}: ${it.message}") }
            }
        }
    }

    // ─── Memory tools ────────────────────────────────────────────────────────

    private fun memoryStore(args: Map<String, Any>): String {
        val content = args["content"]?.toString() ?: return "Error: content required"
        val key = args["key"]?.toString() ?: "mem_${System.currentTimeMillis()}"
        val tagsRaw = args["tags"]?.toString() ?: ""
        val tags = tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        memoryManager.store(key, content, tags)
        return "✅ Stored memory: '$key' (${content.length} chars)"
    }

    private suspend fun memoryRecall(args: Map<String, Any>): String {
        val query = args["query"]?.toString() ?: return "Error: query required"
        val results = memoryManager.recall(query, k = 5)
        if (results.isEmpty()) return "No memories found for: $query"
        return buildString {
            appendLine("🧠 Memory recall for '$query' (${results.size} hit(s)):")
            results.forEach { r ->
                val content = if (r.source == com.forge.os.domain.memory.MemoryTier.SKILL) {
                    "### SKILL CODE ###\n${r.content}\n\n💡 HINT: Use 'python_run' to execute this snippet."
                } else r.content.take(280)
                appendLine("• [${r.source}] ${r.key}: $content")
            }
        }
    }

    private fun memoryGetSkill(args: Map<String, Any>): String {
        val name = args["name"]?.toString() ?: return "Error: name required"
        val entry = memoryManager.skill.recall(name) ?: return "❌ Skill '$name' not found."
        return buildString {
            appendLine("🛠 SKILL: ${entry.name}")
            appendLine("📝 DESCRIPTION: ${entry.description}")
            appendLine("🏷 TAGS: ${entry.tags.joinToString(", ")}")
            appendLine("📊 USAGE: ${entry.useCount} times (last used ${humanAgo(System.currentTimeMillis() - entry.lastUsed)})")
            appendLine()
            appendLine("```python")
            appendLine(entry.code)
            appendLine("```")
            appendLine("\n💡 HINT: Run this via 'python_run'. You may need to add a call to the main function if one is defined.")
        }
    }

    private fun memoryListSkills(): String {
        val skills = memoryManager.skill.getAll()
        if (skills.isEmpty()) return "No skills stored yet. Use memory_store_skill to save Python snippets."
        return buildString {
            appendLine("🛠 Stored Skills (${skills.size}):")
            skills.forEach { s ->
                appendLine("• ${s.name}: ${s.description} (${s.code.length} chars, used ${s.useCount}x)")
            }
        }
    }

    private suspend fun semanticRecallFacts(args: Map<String, Any>): String {
        val query = args["query"]?.toString() ?: return "Error: query required"
        val k = args["k"]?.toString()?.toIntOrNull()?.coerceIn(1, 25) ?: 5
        val results = memoryManager.semanticRecallFacts(query, k)
        if (results.isEmpty()) return "No semantic matches for: $query"
        return buildString {
            appendLine("🔍 Semantic recall for '$query' (top $k):")
            results.forEach { r ->
                val content = if (r.source == com.forge.os.domain.memory.MemoryTier.SKILL) r.content else r.content.take(280)
                appendLine("• ${r.key} (score=${r.score}): $content")
            }
        }
    }

    private fun memoryStoreSkill(args: Map<String, Any>): String {
        val name = args["name"]?.toString() ?: return "Error: name required"
        val description = args["description"]?.toString() ?: return "Error: description required"
        val code = args["code"]?.toString() ?: return "Error: code required"
        val tagsRaw = args["tags"]?.toString() ?: ""
        val tags = tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        memoryManager.storeSkill(name, description, code, tags)
        return "✅ Stored skill '$name' (${code.length} chars)"
    }

    private fun memoryStoreImage(args: Map<String, Any>): String {
        val path = args["path"]?.toString() ?: return "Error: path required"
        val tagsRaw = args["tags"]?.toString() ?: ""
        val tags = tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        
        // In a full Vision implementation, we would pass the file bytes to AiApiManager
        // for auto-captioning. For now, we store the file path alongside its tags
        // in the semantic memory index so the agent can "recall" the image context.
        val fileContent = "Visual Resource: $path\nUser Tags: $tagsRaw"
        val key = "img_${System.currentTimeMillis()}"
        memoryManager.store(key, fileContent, tags)
        
        return "✅ Stored visual memory index for $path (key: $key)"
    }

    private fun memorySummary(): String = memoryManager.fullSummary()

    // ─── Cron tools ─────────────────────────────────────────────────────────


    private fun cronList(): String {
        val jobs = cronManager.listJobs()
        if (jobs.isEmpty()) return "No cron jobs scheduled."
        return buildString {
            appendLine("⏰ ${jobs.size} job(s):")
            jobs.forEach { j ->
                appendLine("• [${j.id}] ${j.name} — ${j.schedule.pretty()} — ${j.taskType} — next: ${java.util.Date(j.nextRunAt)}")
            }
        }
    }

    private fun cronRemove(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (cronManager.removeJob(id)) "✅ Removed cron job $id" else "❌ Job not found: $id"
    }

    private suspend fun cronRunNow(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        val job = cronManager.getJob(id) ?: return "❌ Job not found: $id"
        return try {
            val exec = cronManager.runJob(job)
            if (exec.success) "✅ ${exec.output.take(500)}"
            else "❌ ${exec.error ?: exec.output.take(500)}"
        } catch (e: Exception) {
            "❌ ${e.message}"
        }
    }

    private fun cronHistory(): String {
        val history = cronManager.recentHistory(limit = 15)
        if (history.isEmpty()) return "No cron executions recorded yet."
        return buildString {
            appendLine("📜 Recent cron executions (${history.size}):")
            history.forEach {
                val mark = if (it.success) "✓" else "✗"
                appendLine("$mark ${java.util.Date(it.startedAt)} ${it.jobName} (${it.durationMs}ms)")
            }
        }
    }

    // ─── Plugin tools ────────────────────────────────────────────────────────

    private fun pluginList(): String {
        val plugins = pluginManager.listPlugins()
        if (plugins.isEmpty()) return "No plugins installed."
        return buildString {
            appendLine("🧩 ${plugins.size} plugin(s):")
            plugins.forEach { p ->
                appendLine("• ${p.id} — ${p.name} v${p.version} [${if (p.enabled) "on" else "off"}] — ${p.tools.size} tools")
            }
        }
    }

    private fun pluginInstall(args: Map<String, Any>): String {
        val manifestJson = args["manifest"]?.toString() ?: return "Error: manifest required"
        val code = args["code"]?.toString() ?: return "Error: code required"
        return pluginManager.install(manifestJson, code).fold(
            onSuccess = { meta ->
                // Phase U2 — Plugin tools are now exposed as first-class tools
                // (the dispatcher in `runTool` looks them up by name through
                // `pluginManager.resolveTool`). Tell the model so it stops
                // wrapping every plugin call in a `plugin_execute` envelope.
                val toolNames = meta.tools.map { it.name }
                buildString {
                    append("✅ Plugin installed: ${meta.name} v${meta.version}.")
                    if (toolNames.isNotEmpty()) {
                        append(" Registered tool")
                        if (toolNames.size > 1) append("s")
                        append(": ")
                        append(toolNames.joinToString(", "))
                        append(". Call them DIRECTLY by name — do NOT wrap them in ")
                        append("plugin_execute. They will appear in the next tool ")
                        append("schema refresh.")
                    }
                }
            },
            onFailure = { "❌ Install failed: ${it.message}" }
        )
    }

    private fun pluginUninstall(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (pluginManager.uninstall(id)) "✅ Uninstalled $id"
        else "❌ Could not uninstall $id (not found, or it is a builtin plugin)"
    }

    private suspend fun pluginExecute(args: Map<String, Any>): String {
        // Accept both "tool" and "tool_name" so older model generations that
        // emit `tool_name` instead of `tool` still work. "tool" takes priority.
        val tool = args["tool"]?.toString()?.takeIf { it.isNotBlank() }
            ?: args["tool_name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return "Error: tool required (pass as 'tool' or 'tool_name')"
        // Strip the dispatch envelope keys before forwarding so the plugin
        // function receives only its declared kwargs. Previously the entire
        // args map (including `tool`) was passed through, which (a) leaked
        // `tool` into the plugin's kwargs causing TypeError "unexpected
        // keyword argument 'tool'", and (b) made plugins like
        // `custom_roll_dice` return null because the wrapper crashed before
        // the function body ran.
        val inner = args.filterKeys { it != "tool" && it != "tool_name" }
        val r = pluginManager.executeTool(tool, inner)
        return if (r.success) r.output else "❌ ${r.output}"
    }

    // ─── Delegation tools ────────────────────────────────────────────────────

    private suspend fun delegateTask(args: Map<String, Any>): String {
        val goal = args["goal"]?.toString() ?: return "Error: goal required"
        val context = args["context"]?.toString() ?: ""
        val tagsRaw = args["tags"]?.toString() ?: ""
        val tags = tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val model = args["model"]?.toString()
        val outcome = delegationManager.spawnAndAwait(
            goal = goal,
            context = context,
            tags = tags,
            overrideModel = model
        )
        val mark = if (outcome.success) "✅" else "❌"
        val summary = (outcome.agent.result ?: outcome.agent.error ?: "").take(200)
        return "$mark Sub-agent ${outcome.agent.id} [${outcome.agent.status}] — $summary"
    }

    private suspend fun delegateGhost(args: Map<String, Any>): String {
        val goal = args["goal"]?.toString() ?: return "Error: goal required"
        val context = args["context"]?.toString() ?: ""
        val outcome = delegationManager.spawnGhost(goal, context)
        val mark = if (outcome.success) "👻" else "❌"
        val summary = (outcome.agent.result ?: outcome.agent.error ?: "").take(200)
        return "$mark Ghost Agent ${outcome.agent.id} [${outcome.agent.status}] — $summary (Sandbox destroyed)"
    }

    private suspend fun delegateBatch(args: Map<String, Any>): String {
        val goalsRaw = args["goals"]?.toString() ?: return "Error: goals required"
        val goals = goalsRaw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val strategyStr = args["strategy"]?.toString() ?: "sequential"
        val context = args["context"]?.toString() ?: ""
        val strategy = try { com.forge.os.domain.agents.AggregationStrategy.valueOf(strategyStr.uppercase()) }
                       catch (_: Exception) { com.forge.os.domain.agents.AggregationStrategy.SEQUENTIAL }
        val results = delegationManager.spawnBatch(goals, strategy, context)
        return buildString {
            appendLine("🤖 Batch (${results.size}, strategy=${strategy}):")
            results.forEach { o ->
                val mark = if (o.success) "✓" else "✗"
                val text = (o.agent.result ?: o.agent.error ?: "").take(160)
                appendLine("$mark ${o.agent.id} [${o.agent.status}] — $text")
            }
        }
    }

    private fun agentsList(): String {
        val agents = delegationManager.listAll()
        if (agents.isEmpty()) return "No sub-agents spawned yet."
        return buildString {
            appendLine("🤖 ${agents.size} agent(s):")
            agents.take(20).forEach { a ->
                appendLine("• ${a.id} [${a.status}] d=${a.depth} — ${a.goal.take(60)}")
            }
        }
    }

    private fun agentStatus(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        val agent = delegationManager.get(id) ?: return "Agent not found: $id"
        return buildString {
            appendLine("🤖 Agent ${agent.id}")
            appendLine("Status: ${agent.status}")
            appendLine("Goal: ${agent.goal}")
            appendLine("Started: ${agent.startedAt?.let { java.util.Date(it) } ?: "not started"}")
            agent.result?.let { appendLine("Result: ${it.take(300)}") }
        }
    }

    private fun agentCancel(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (delegationManager.cancel(id)) "✅ Cancelled $id" else "❌ Agent not found or already done: $id"
    }

    // ─── Message Bus tools (Phase 3) ─────────────────────────────────────────

    private suspend fun messageBusPublish(args: Map<String, Any>): String {
        val topic = args["topic"]?.toString() ?: return "Error: topic required"
        val message = args["message"]?.toString() ?: return "Error: message required"
        val senderId = args["sender_id"]?.toString() ?: "agent"
        agentMessageBus.publish(topic, senderId, message)
        return "✅ Published to topic '$topic' (${message.length} chars)"
    }

    private fun messageBusRead(args: Map<String, Any>): String {
        val topic = args["topic"]?.toString() ?: return "Error: topic required"
        val limit = args["limit"]?.toString()?.toIntOrNull() ?: 20
        val messages = agentMessageBus.read(topic, limit)
        if (messages.isEmpty()) return "📡 Topic '$topic' has no messages."
        return buildString {
            appendLine("📡 Topic '$topic' (${messages.size} message(s)):")
            messages.forEach { m ->
                val ago = java.util.Date(m.timestamp)
                appendLine("  [${m.senderId}] ${m.content.take(300)}")
            }
        }
    }

    private fun messageBusTopics(): String = agentMessageBus.summary()

    // ─── Snapshot tools ──────────────────────────────────────────────────────

    private fun snapshotCreate(args: Map<String, Any>): String {
        val label = args["label"]?.toString()
        return snapshotManager.create(label).fold(
            onSuccess = { "✅ Snapshot ${it.id} (${it.fileCount} files, ${it.sizeBytes}b)" },
            onFailure = { "❌ ${it.message}" }
        )
    }

    private fun snapshotList(): String {
        val list = snapshotManager.list()
        if (list.isEmpty()) return "No snapshots yet. Use snapshot_create to take one."
        return buildString {
            appendLine("📦 ${list.size} snapshot(s):")
            list.forEach { s -> appendLine("• ${s.id} — ${s.label} (${s.fileCount} files, ${s.sizeBytes}b)") }
        }
    }

    private fun snapshotRestore(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return snapshotManager.restore(id).fold(
            onSuccess = { "✅ Restored $it files from $id" },
            onFailure = { "❌ ${it.message}" }
        )
    }

    private fun snapshotDelete(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (snapshotManager.delete(id)) "✅ Deleted $id" else "❌ Snapshot not found: $id"
    }

    // ─── Model catalog ───────────────────────────────────────────────────────

    private suspend fun modelCacheRefresh(): String {
        return try {
            val models = aiApiManager.availableModels(forceRefresh = true)
            val providerCount = models.map { it.providerKey }.distinct().size
            val modelCount = models.size
            "✅ Model catalog refreshed: $modelCount models across $providerCount provider(s)"
        } catch (e: Exception) {
            "❌ Model cache refresh failed: ${e.message}"
        }
    }

    // ─── MCP tools ───────────────────────────────────────────────────────────

    private suspend fun mcpRefresh(): String {
        val tools = mcpClient.refreshTools()
        return "🔌 MCP refresh: ${tools.size} tool(s) discovered across configured servers"
    }

    private fun mcpListTools(): String {
        val tools = mcpClient.cachedToolsWithServer()
        if (tools.isEmpty()) return "No MCP tools cached. Run mcp_refresh first."
        return buildString {
            appendLine("🔌 ${tools.size} MCP tool(s):")
            tools.forEach { (server, spec) ->
                appendLine("• ${mcpClient.qualifiedName(server, spec.name)} — ${spec.description}")
            }
        }
    }

    // ─── HTTP / Fetch tools ──────────────────────────────────────────────────

    private suspend fun httpFetch(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val url = args["url"]?.toString() ?: return@withContext "Error: url required"
        val method = (args["method"]?.toString() ?: "GET").uppercase()
        val body = args["body"]?.toString()
        val headers = args["headers"]?.toString() // "Key: Value\nKey: Value"
        val maxChars = args["max_chars"]?.toString()?.toIntOrNull() ?: 8000

        try {
            securityPolicy.validateUrl(url)
            val reqBuilder = Request.Builder().url(url)
            headers?.lines()?.forEach { line ->
                val idx = line.indexOf(":")
                if (idx > 0) reqBuilder.addHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }
            when (method) {
                "GET" -> reqBuilder.get()
                "POST" -> {
                    val ct = "application/json; charset=utf-8".toMediaType()
                    reqBuilder.post((body ?: "{}").toRequestBody(ct))
                }
                "DELETE" -> reqBuilder.delete()
                else -> reqBuilder.get()
            }
            val response = httpClient.newCall(reqBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val truncated = responseBody.take(maxChars)
            buildString {
                appendLine("HTTP ${response.code} ${response.message}")
                appendLine("Content-Type: ${response.header("Content-Type")}")
                appendLine("─────")
                append(truncated)
                if (responseBody.length > maxChars) appendLine("\n…(${responseBody.length - maxChars} chars truncated)")
            }
        } catch (e: Exception) { "❌ http_fetch failed: ${e.javaClass.simpleName}: ${e.message}" }
    }

    // ─── Phase T: Named secrets ──────────────────────────────────────────────
    //
    // The agent NEVER sees raw secret values — `secret_list` returns names +
    // descriptions only, and `secret_request` performs the HTTP call locally
    // with the value attached at send time. This lets a user wire any
    // bearer/header/query-style API into the agent without committing the
    // key into a prompt or memory entry.

    private fun secretList(): String {
        val items = namedSecretRegistry.list()
        if (items.isEmpty()) return "(no named secrets registered — the user can " +
            "add one in Settings → Custom API Keys)"
        return buildString {
            appendLine("Registered named secrets (use the name with secret_request):")
            for (s in items) {
                val auth = when (s.authStyle) {
                    "bearer" -> "Authorization: Bearer …"
                    "header" -> "${s.headerName}: …"
                    "query"  -> "?${s.queryParam}=…"
                    else     -> s.authStyle
                }
                val desc = if (s.description.isBlank()) "" else " — ${s.description}"
                appendLine("  • ${s.name}  [${auth}]${desc}")
            }
        }.trimEnd()
    }

    private suspend fun secretRequest(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val name = args["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return@withContext "❌ Error: 'name' (the registered secret name) is required"
        val url = args["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return@withContext "❌ Error: 'url' is required"
        val method = (args["method"]?.toString() ?: "GET").uppercase()
        val body = args["body"]?.toString()
        val contentType = args["content_type"]?.toString() ?: "application/json"
        val maxChars = (args["max_chars"] as? Number)?.toInt()
            ?: args["max_chars"]?.toString()?.toIntOrNull() ?: 8000

        val secret = namedSecretRegistry.get(name)
            ?: return@withContext "❌ Error: no named secret '$name' is registered. " +
                "Call secret_list to see available names, or ask the user to add " +
                "one in Settings → Custom API Keys."
        val value = namedSecretRegistry.getValue(name)
            ?: return@withContext "❌ Error: secret '$name' is registered but has no " +
                "stored value. Ask the user to set it in Settings → Custom API Keys."

        var finalUrl = url
        try {
            securityPolicy.validateUrl(url)
        } catch (e: Exception) {
            return@withContext "❌ Security Error: ${e.message}"
        }
        val extraHeaders = mutableMapOf<String, String>()
        when (secret.authStyle) {
            "bearer" -> extraHeaders["Authorization"] = "Bearer $value"
            "header" -> extraHeaders[secret.headerName.ifBlank { "Authorization" }] = value
            "query"  -> {
                val sep = if (url.contains('?')) '&' else '?'
                val param = secret.queryParam.ifBlank { "key" }
                finalUrl = "$url$sep$param=" + java.net.URLEncoder.encode(value, "UTF-8")
            }
        }
        // Optional caller-supplied headers ("Key: Value\n…")
        args["headers"]?.toString()?.lines()?.forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) extraHeaders[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }

        val uploadFile = args["upload_file"]?.toString()?.takeIf { it.isNotBlank() }
        val saveAs = args["save_as"]?.toString()?.takeIf { it.isNotBlank() }

        return@withContext try {
            val reqBuilder = Request.Builder().url(finalUrl)
            extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            
            when (method) {
                "GET", "HEAD" -> if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
                "POST", "PUT", "PATCH", "DELETE" -> {
                    val rb = if (uploadFile != null) {
                        val file = sandboxManager.resolveSafe(uploadFile)
                        if (!file.exists()) return@withContext "❌ Error: upload_file not found: $uploadFile"
                        file.asRequestBody(contentType.toMediaTypeOrNull())
                    } else {
                        (body ?: "").toRequestBody(contentType.toMediaTypeOrNull())
                    }
                    reqBuilder.method(method, rb)
                }
                else -> reqBuilder.get()
            }

            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val respContentType = resp.header("Content-Type")?.lowercase() ?: ""
            
            // Heuristic for binary content if not explicitly asked to save
            val isBinary = respContentType.let { ct ->
                ct.contains("audio/") || ct.contains("video/") || ct.contains("image/") ||
                ct.contains("application/octet-stream") || ct.contains("application/pdf") ||
                ct.contains("application/zip") || ct.contains("application/gzip")
            }

            val targetPath = saveAs ?: if (isBinary) {
                val ext = when {
                    respContentType.contains("audio/mpeg") -> "mp3"
                    respContentType.contains("audio/wav") -> "wav"
                    respContentType.contains("audio/ogg") -> "ogg"
                    respContentType.contains("image/png") -> "png"
                    respContentType.contains("image/jpeg") -> "jpg"
                    respContentType.contains("application/pdf") -> "pdf"
                    else -> "bin"
                }
                "downloads/secret_${System.currentTimeMillis()}.$ext"
            } else null

            if (targetPath != null) {
                val respBody = resp.body ?: return@withContext "HTTP ${resp.code} ${resp.message} (Empty body)"
                val bytesRead = sandboxManager.importStream(targetPath, respBody.byteStream()).getOrThrow()
                buildString {
                    appendLine("✅ HTTP ${resp.code} ${resp.message} (auth: ${secret.authStyle} via '$name')")
                    appendLine("Content-Type: $respContentType")
                    appendLine("File saved to: $targetPath ($bytesRead bytes)")
                    appendLine("─────")
                    appendLine("The file content was saved to the workspace. Use file_read or other tools to process it.")
                }
            } else {
                val rb = resp.body?.string() ?: ""
                buildString {
                    appendLine("HTTP ${resp.code} ${resp.message}  (auth: ${secret.authStyle} via secret '$name')")
                    appendLine("Content-Type: $respContentType")
                    appendLine("─────")
                    append(rb.take(maxChars))
                    if (rb.length > maxChars) appendLine("\n…(${rb.length - maxChars} chars truncated)")
                }
            }
        } catch (e: Exception) {
            "❌ secret_request failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Explicit MCP invocation. The agent can also reach MCP tools by calling
     * them with their qualified `mcp.<server>.<tool>` name (handled in the
     * fall-through above), but a discoverable named entry makes it obvious
     * that the capability exists and lets the model pass server + tool +
     * args without having to construct the dotted name.
     */
    private suspend fun mcpCallTool(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val serverArg = args["server"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return@withContext "❌ Error: 'server' (MCP server name or id) is required. Call mcp_list_tools to see available servers."
        val tool = args["tool"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return@withContext "❌ Error: 'tool' (tool name on that server) is required. Call mcp_list_tools to see available tools."

        // Look the McpServer up the same way McpClient.resolveTool does.
        val mcpServer = mcpClient.cachedToolsWithServer().map { it.first }
            .firstOrNull { it.name == serverArg || it.id == serverArg }
            ?: return@withContext "❌ Error: no MCP server matched '$serverArg'. Available: " +
                mcpClient.cachedToolsWithServer().map { it.first.name }.distinct().joinToString(", ")

        @Suppress("UNCHECKED_CAST")
        val callArgs: Map<String, Any> = (args["args"] as? Map<String, Any>)
            ?: args["args"]?.toString()?.let { raw ->
                runCatching {
                    val parsed = Json.parseToJsonElement(raw)
                    (parsed as? JsonObject)
                        ?.mapValues { (_, v) ->
                            (v as? JsonPrimitive)?.content ?: v.toString()
                        } ?: emptyMap()
                }.getOrElse { emptyMap() }
            } ?: emptyMap()

        val result = mcpClient.callTool(mcpServer, tool, callArgs)
        result.fold(
            onSuccess = { it },
            onFailure = { "❌ MCP call failed: ${it.message}" },
        )
    }



    private suspend fun curlExec(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val url = args["url"]?.toString() ?: return@withContext "Error: url required"
        val method = (args["method"]?.toString() ?: "GET").uppercase()
        val body = args["body"]?.toString()
        val contentType = args["content_type"]?.toString() ?: "application/json"
        val headersRaw = args["headers"]?.toString() ?: ""
        val maxChars = args["max_chars"]?.toString()?.toIntOrNull() ?: 8000
        val uploadFile = args["upload_file"]?.toString()?.takeIf { it.isNotBlank() }
        val saveAs = args["save_as"]?.toString()?.takeIf { it.isNotBlank() }

        try {
            val reqBuilder = Request.Builder().url(url)
            headersRaw.lines().forEach { line ->
                val idx = line.indexOf(":")
                if (idx > 0) reqBuilder.addHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }
            
            when (method) {
                "GET", "HEAD" -> if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
                "POST", "PUT", "PATCH", "DELETE" -> {
                    val rb = if (uploadFile != null) {
                        val file = sandboxManager.resolveSafe(uploadFile)
                        if (!file.exists()) return@withContext "❌ Error: upload_file not found: $uploadFile"
                        file.asRequestBody(contentType.toMediaTypeOrNull())
                    } else {
                        (body ?: "").toRequestBody(contentType.toMediaTypeOrNull())
                    }
                    reqBuilder.method(method, rb)
                }
                else -> reqBuilder.get()
            }

            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val respContentType = resp.header("Content-Type")?.lowercase() ?: ""
            
            // Heuristic for binary content
            val isBinary = respContentType.let { ct ->
                ct.contains("audio/") || ct.contains("video/") || ct.contains("image/") ||
                ct.contains("application/octet-stream") || ct.contains("application/pdf") ||
                ct.contains("application/zip")
            }

            val targetPath = saveAs ?: if (isBinary) {
                "downloads/curl_${System.currentTimeMillis()}.bin"
            } else null

            if (targetPath != null) {
                val respBody = resp.body ?: return@withContext "HTTP ${resp.code} ${resp.message} (Empty body)"
                val bytesRead = sandboxManager.importStream(targetPath, respBody.byteStream()).getOrThrow()
                buildString {
                    appendLine("< HTTP/1.1 ${resp.code} ${resp.message}")
                    resp.headers.forEach { (k, v) -> appendLine("< $k: $v") }
                    appendLine("< ")
                    appendLine("✅ File saved to: $targetPath ($bytesRead bytes)")
                }
            } else {
                val rb = resp.body?.string() ?: ""
                buildString {
                    appendLine("< HTTP/1.1 ${resp.code} ${resp.message}")
                    resp.headers.forEach { (k, v) -> appendLine("< $k: $v") }
                    appendLine("< ")
                    append(rb.take(maxChars))
                    if (rb.length > maxChars) appendLine("\n…(${rb.length - maxChars} chars truncated)")
                }
            }
        } catch (e: Exception) { "❌ curl_exec failed: ${e.javaClass.simpleName}: ${e.message}" }
    }

    // ─── DuckDuckGo search ──────────────────────────────────────────────────

    private suspend fun ddgSearch(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val query = args["query"]?.toString() ?: return@withContext "Error: query required"
        val maxResults = args["max_results"]?.toString()?.toIntOrNull() ?: 10

        try {
            // Use DuckDuckGo Lite (no JS, no API key needed)
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/?q=$encodedQuery")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) Forge/1.0")
                .header("Accept", "text/html")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext "❌ Empty response from DuckDuckGo"

            // Parse result snippets from the HTML (simple text extraction)
            val results = mutableListOf<String>()
            val linkRegex = Regex("""<a[^>]+class="result-link"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("""<td[^>]+class="result-snippet"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
            val links = linkRegex.findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()
            val snippets = snippetRegex.findAll(html).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()

            for (i in 0 until minOf(maxResults, links.size)) {
                val link = links.getOrElse(i) { "" }
                val snippet = snippets.getOrElse(i) { "" }
                if (link.isNotBlank()) results.add("${i + 1}. $link\n   $snippet")
            }

            if (results.isEmpty()) {
                // Fallback: extract any text content
                val text = html.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .take(2000)
                "DuckDuckGo results for '$query':\n$text"
            } else {
                buildString {
                    appendLine("🔍 DuckDuckGo results for '$query' (${results.size} results):")
                    appendLine()
                    results.forEach { appendLine(it); appendLine() }
                }
            }
        } catch (e: Exception) { "❌ ddg_search failed: ${e.javaClass.simpleName}: ${e.message}" }
    }

    // ─── Browser control tools ──────────────────────────────────────────────
    //
    // Design note: these tools drive the agent's OWN persistent off-screen
    // WebView (HeadlessBrowser). They DO NOT touch the on-screen Browser tab,
    // so the user can keep browsing in their own tab undisturbed while the
    // agent works in the background. Cookie / session state IS shared with
    // the on-screen tab via Android's global CookieManager, so logging into
    // a site once on the visible tab automatically gives the agent the same
    // logged-in session.

    private suspend fun browserNavigate(args: Map<String, Any>): String {
        val url = args["url"]?.toString() ?: return "Error: url required"
        val result = headlessBrowser.navigate(url)
        runCatching {
            browserHistory.record(
                com.forge.os.data.browser.BrowserHistoryEntry(
                    ts = System.currentTimeMillis(),
                    sessionId = "agent",
                    url = headlessBrowser.currentUrl,
                    source = "agent",
                )
            )
        }
        return result
    }

    private suspend fun browserGetHtml(args: Map<String, Any> = emptyMap()): String {
        val explicitUrl = args["url"]?.toString()?.takeIf { it.isNotBlank() }
        if (explicitUrl != null) {
            // navigate first so subsequent ops (click/fill/eval) act on this page
            val nav = headlessBrowser.navigate(explicitUrl)
            if (nav.startsWith("❌")) return nav
        }
        val text = headlessBrowser.getReadableText()
        return if (text.isBlank())
            "❌ No page loaded yet. Call browser_navigate first, or pass a `url` arg."
        else
            "📄 Page text (${headlessBrowser.currentUrl}):\n$text"
    }

    private suspend fun browserEvalJs(args: Map<String, Any>): String {
        val script = args["script"]?.toString() ?: return "Error: script required"
        val raw = headlessBrowser.evalJs(script)
        return if (raw.isBlank()) "✅ JS executed (no return value)" else "✅ JS result: $raw"
    }

    private suspend fun browserFillField(args: Map<String, Any>): String {
        val selector = args["selector"]?.toString() ?: return "Error: selector required"
        val value = args["value"]?.toString() ?: return "Error: value required"
        return headlessBrowser.fillField(selector, value)
    }

    private suspend fun browserClick(args: Map<String, Any>): String {
        val selector = args["selector"]?.toString() ?: return "Error: selector required"
        return headlessBrowser.click(selector)
    }

    private suspend fun browserClickAt(args: Map<String, Any>): String {
        val x = (args["x"] as? Number)?.toInt() ?: return "Error: x required"
        val y = (args["y"] as? Number)?.toInt() ?: return "Error: y required"
        return headlessBrowser.clickAt(x, y)
    }

    private suspend fun browserType(args: Map<String, Any>): String {
        val text = args["text"]?.toString() ?: return "Error: text required"
        return headlessBrowser.typeText(text)
    }

    private suspend fun browserScroll(args: Map<String, Any>): String {
        val x = args["x"]?.toString()?.toIntOrNull() ?: 0
        val y = args["y"]?.toString()?.toIntOrNull() ?: 500
        return headlessBrowser.scroll(x, y)
    }

    // ─── Phase R: agent-browser additions ───────────────────────────────────

    private suspend fun browserSetViewport(args: Map<String, Any>): String {
        val device = args["device"]?.toString()?.takeIf { it.isNotBlank() }
        val width = args["width"]?.toString()?.toIntOrNull()
        val height = args["height"]?.toString()?.toIntOrNull()
        val ua = args["user_agent"]?.toString()?.takeIf { it.isNotBlank() }
        return headlessBrowser.setViewport(device, width, height, ua)
    }

    private suspend fun browserScreenshotRegion(args: Map<String, Any>): String {
        val out = args["save_as"]?.toString()?.takeIf { it.isNotBlank() } ?: "screenshots/region-${System.currentTimeMillis()}.png"
        // Coerce destination into the workspace via SandboxManager.
        // First create the target file (lazily) so we can resolve its abs path.
        val parent = out.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) sandboxManager.mkdir(parent)
        val abs = sandboxManager.absolutePathFor(out)
        val selector = args["selector"]?.toString()?.takeIf { it.isNotBlank() }
        val x = args["x"]?.toString()?.toIntOrNull()
        val y = args["y"]?.toString()?.toIntOrNull()
        val w = args["width"]?.toString()?.toIntOrNull()
        val h = args["height"]?.toString()?.toIntOrNull()
        return headlessBrowser.screenshotRegion(abs, selector, x, y, w, h)
    }

    private suspend fun browserWaitForSelector(args: Map<String, Any>): String {
        val sel = args["selector"]?.toString() ?: return "Error: selector required"
        val timeout = args["timeout_ms"]?.toString()?.toLongOrNull() ?: 8_000L
        return headlessBrowser.waitForSelector(sel, timeout)
    }

    private suspend fun browserGetText(args: Map<String, Any>): String {
        val sel = args["selector"]?.toString() ?: return "Error: selector required"
        return headlessBrowser.getText(sel)
    }

    private suspend fun browserGetAttribute(args: Map<String, Any>): String {
        val sel = args["selector"]?.toString() ?: return "Error: selector required"
        val attr = args["attribute"]?.toString() ?: return "Error: attribute required"
        return headlessBrowser.getAttribute(sel, attr)
    }

    private suspend fun browserListLinks(args: Map<String, Any>): String {
        val limit = args["limit"]?.toString()?.toIntOrNull() ?: 100
        return headlessBrowser.listLinks(limit)
    }

    /**
     * Set the value of a workspace file path on a file-input <input type=file>
     * in the on-screen browser. The user is prompted via the visible Browser
     * tab's existing file-chooser flow, so this surfaces a normal upload
     * dialog the user can accept.
     */
    private suspend fun fileUploadToBrowser(args: Map<String, Any>): String {
        val selector = args["selector"]?.toString() ?: return "Error: selector required"
        val path = args["workspace_path"]?.toString() ?: return "Error: workspace_path required"
        val abs = try { sandboxManager.absolutePathFor(path) } catch (t: Throwable) { return "❌ Cannot resolve $path: ${t.message}" }
        val f = java.io.File(abs)
        if (!f.exists()) return "❌ Workspace file not found: $path"
        // Click the file input via JS so the chooser opens; the user (or the
        // browser screen's WebChromeClient.onShowFileChooser hook) can then
        // pick from the workspace. We surface the file path so the user sees
        // exactly which file to choose.
        val js = "(function(){var e=document.querySelector(${jsLitForBrowser(selector)});" +
            "if(!e) return 'NF'; e.scrollIntoView({behavior:'instant',block:'center'}); e.click(); return 'OK';})()"
        val r = headlessBrowser.evalJs(js)
        return if (r.contains("OK"))
            "✅ Opened file chooser for '$selector'. Pick this workspace file: $path (size=${f.length()}b)."
        else
            "❌ No element matches '$selector' (got: $r)"
    }

    private fun jsLitForBrowser(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            else -> sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }

    /** Phase R — let the agent scaffold its own first-class plugin. */
    private fun pluginCreate(args: Map<String, Any>): String {
        val id = args["id"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return "Error: id required (lowercase letters/digits/_)"
        val name = args["name"]?.toString() ?: id
        val description = args["description"]?.toString() ?: ""
        val toolName = args["tool_name"]?.toString() ?: id
        val toolDescription = args["tool_description"]?.toString() ?: description
        val pythonCode = args["python_code"]?.toString()
        return pluginManager.createPlugin(
            id = id,
            name = name,
            description = description,
            toolName = toolName,
            toolDescription = toolDescription,
            pythonCode = pythonCode,
        )
    }

    // ─── Temp / upload folder tools ─────────────────────────────────────────

    private suspend fun tempList(): String {
        val tempFiles = sandboxManager.listFiles("temp").fold(
            onSuccess = { it }, onFailure = { emptyList() }
        )
        val uploadFiles = sandboxManager.listFiles("uploads").fold(
            onSuccess = { it }, onFailure = { emptyList() }
        )
        return buildString {
            appendLine("📁 temp/ (${tempFiles.size} items):")
            if (tempFiles.isEmpty()) appendLine("  (empty)")
            else tempFiles.forEach { appendLine("  ${if (it.isDirectory) "📁" else "📄"} ${it.name} (${it.size}b)") }
            appendLine()
            appendLine("📁 uploads/ (${uploadFiles.size} items):")
            if (uploadFiles.isEmpty()) appendLine("  (empty)")
            else uploadFiles.forEach { appendLine("  ${if (it.isDirectory) "📁" else "📄"} ${it.name} (${it.size}b)") }
        }
    }

    private suspend fun tempClear(): String {
        val tempFiles = sandboxManager.listFiles("temp").getOrElse { emptyList() }
        var deleted = 0
        for (f in tempFiles) {
            sandboxManager.deleteFile("temp/${f.name}").onSuccess { deleted++ }
        }
        return "✅ Cleared $deleted file(s) from temp/"
    }

    // ─── Composio ────────────────────────────────────────────────────────────

    private suspend fun composioCall(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        // Try args first, then memory ("composio_api_key"), then config (none defined yet).
        val apiKey = args["api_key"]?.toString()?.takeIf { it.isNotBlank() }
            ?: try { memoryManager.recall("composio_api_key", k = 1).firstOrNull()?.content } catch (_: Exception) { null }
        val action = args["action"]?.toString() ?: return@withContext "Error: action required"
        val params = args["params"]?.toString() ?: "{}"
        val entityId = args["entity_id"]?.toString() ?: "default"

        if (apiKey.isNullOrBlank()) {
            return@withContext """
❌ Composio API key not provided.

To use Composio:
1. Sign up at https://composio.dev and get your API key
2. Store it: memory_store with key="composio_api_key" content="your_key_here"
3. Or provide api_key in this call directly
4. Then retry this tool call with api_key="your_key"
            """.trimIndent()
        }

        try {
            val ct = "application/json; charset=utf-8".toMediaType()
            val reqBody = """{
  "entityId": "$entityId",
  "input": $params
}""".toRequestBody(ct)
            val req = Request.Builder()
                .url("https://backend.composio.dev/api/v1/actions/$action/execute")
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .post(reqBody)
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            buildString {
                appendLine("⚡ Composio: $action")
                appendLine("HTTP ${resp.code}")
                append(body.take(4000))
            }
        } catch (e: Exception) { "❌ composio_call failed: ${e.javaClass.simpleName}: ${e.message}" }
    }

    // ─── Phase 3: Hybrid Python Execution ────────────────────────────────────

    private suspend fun smartPythonRun(args: Map<String, Any>): String {
        val code = args["code"]?.toString() ?: return "Error: code required"
        val hybrid = configRepository.get().hybridExecution
        
        // Check if remote worker is configured
        if (hybrid.remotePythonWorkerUrl.isNotBlank()) {
            // Check for heavy imports using basic regex
            val isHeavy = hybrid.heavyImportPatterns.any { pattern ->
                code.contains(Regex("import\\s+$pattern", RegexOption.IGNORE_CASE)) ||
                code.contains(Regex("from\\s+$pattern", RegexOption.IGNORE_CASE))
            }
            
            if (isHeavy) {
                Timber.d("HybridExecution: Heavy import detected, auto-routing to remote worker.")
                return pythonRunRemote(args)
            }
        }
        
        // Otherwise fall back to local
        val localResult = fileToolProvider.dispatch("python_run", args) ?: "❌ Local execution failed"
        
        // Phase 3 optimization: if local fails with ImportError, suggest checking packages or using remote
        if (localResult.contains("ImportError") || localResult.contains("ModuleNotFoundError")) {
            return localResult + "\n\n💡 HINT: This module is missing in the local Chaquopy sandbox. " +
                "Call 'python_packages' to see what's installed, or try using the Remote GPU Worker " +
                "by including 'import torch' or similar in your script (if configured)."
        }
        
        return localResult
    }

    private suspend fun pythonRunRemote(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val code = args["code"]?.toString() ?: return@withContext "Error: code required"
        val timeout = args["timeout"]?.toString()?.toIntOrNull() ?: 120
        val hybrid = configRepository.get().hybridExecution

        // If no remote worker configured, fall back to local execution
        if (hybrid.remotePythonWorkerUrl.isBlank()) {
            return@withContext "⚠️ No remote Python worker configured. " +
                "Set hybridExecution.remotePythonWorkerUrl in Settings → Advanced. " +
                "Falling back to local python_run.\n\n" +
                sandboxManager.executePython(code, timeoutSeconds = timeout).fold(
                    onSuccess = { it },
                    onFailure = { "❌ Local fallback error: ${it.message}" }
                )
        }

        try {
            val ct = "application/json; charset=utf-8".toMediaType()
            val reqBody = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(mapOf(
                    "code" to kotlinx.serialization.json.JsonPrimitive(code),
                    "timeout" to kotlinx.serialization.json.JsonPrimitive(timeout)
                ))
            ).toRequestBody(ct)

            val reqBuilder = Request.Builder()
                .url(hybrid.remotePythonWorkerUrl)
                .header("Content-Type", "application/json")
                .post(reqBody)

            // Add auth token if configured
            if (hybrid.remotePythonWorkerAuthToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hybrid.remotePythonWorkerAuthToken}")
            }

            val remoteClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val resp = remoteClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string().orEmpty()

            if (!resp.isSuccessful) {
                return@withContext "❌ Remote worker error (HTTP ${resp.code}):\n${body.take(2000)}"
            }

            buildString {
                appendLine("🖥️ Remote GPU execution result:")
                append(body.take(8000))
                if (body.length > 8000) appendLine("\n…(${body.length - 8000} chars truncated)")
            }
        } catch (e: Exception) {
            "❌ Remote execution failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ─── Alarms ──────────────────────────────────────────────────────────────

    private fun alarmList(): String {
        val list = alarmRepository.all()
        if (list.isEmpty()) return "No alarms scheduled."
        return buildString {
            appendLine("⏱ ${list.size} alarm(s):")
            list.forEach { a ->
                appendLine("• [${a.id}] ${a.label} — ${a.action} — ${java.util.Date(a.triggerAt)} ${if (a.enabled) "" else "(disabled)"}")
            }
        }
    }

    private fun alarmCancel(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (alarmScheduler.removeAlarm(id)) "✅ Cancelled $id" else "❌ Alarm not found: $id"
    }

    // ─── Android ─────────────────────────────────────────────────────────────

    private fun androidSetVolume(args: Map<String, Any>): String {
        val stream = args["stream"]?.toString() ?: "music"
        val pct = args["percent"]?.toString()?.toIntOrNull() ?: return "Error: percent required"
        return androidController.setVolume(stream, pct).toString()
    }

    private fun androidListApps(args: Map<String, Any>): String {
        val userOnly = args["user_only"]?.toString()?.toBooleanStrictOrNull() ?: true
        val limit = args["limit"]?.toString()?.toIntOrNull() ?: 200
        return androidController.listApps(userOnly, limit).toString()
    }

    private fun androidLaunchApp(args: Map<String, Any>): String {
        val pkg = args["package"]?.toString() ?: return "Error: package required"
        return androidController.launchApp(pkg).toString()
    }

    // ─── Local HTTP server ───────────────────────────────────────────────────

    private fun serverStart(args: Map<String, Any>): String {
        val port = args["port"]?.toString()?.toIntOrNull() ?: ForgeHttpServer.DEFAULT_PORT
        ForgeHttpService.start(appContext, port)
        return "✅ Requested server start on port $port (key: ${httpServer.apiKey().take(8)}…)"
    }

    private fun serverStop(): String {
        ForgeHttpService.stop(appContext)
        return "✅ Server stop requested"
    }

    private fun serverStatus(): String = buildString {
        appendLine("HTTP server: ${if (httpServer.isRunning()) "RUNNING" else "stopped"}")
        appendLine("Port: ${httpServer.port()}")
        appendLine("API key (prefix): ${httpServer.apiKey().take(8)}…")
    }

    private fun serverRotateKey(): String {
        val k = httpServer.rotateKey()
        return "✅ Rotated API key. New prefix: ${k.take(8)}…"
    }

    // ─── Doctor ──────────────────────────────────────────────────────────────

    private fun doctorCheck(): String {
        val report = doctorService.runChecks()
        val okCount = report.checks.count {
            it.status == com.forge.os.domain.doctor.CheckStatus.OK
        }
        return buildString {
            appendLine("🩺 Doctor — $okCount/${report.checks.size} OK")
            report.checks.forEach { c ->
                val icon = when (c.status) {
                    com.forge.os.domain.doctor.CheckStatus.OK   -> "✅"
                    com.forge.os.domain.doctor.CheckStatus.WARN -> "⚠️"
                    com.forge.os.domain.doctor.CheckStatus.FAIL -> "❌"
                }
                appendLine("$icon [${c.id}] ${c.title} — ${c.detail}")
            }
        }
    }

    private fun doctorFix(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        val r = doctorService.fix(id)
        val icon = when (r.status) {
            com.forge.os.domain.doctor.CheckStatus.OK   -> "✅"
            com.forge.os.domain.doctor.CheckStatus.WARN -> "⚠️"
            com.forge.os.domain.doctor.CheckStatus.FAIL -> "❌"
        }
        return "$icon [${r.id}] ${r.title} — ${r.detail}"
    }

    // ─── Channels ────────────────────────────────────────────────────────────

    private fun channelList(): String {
        val list = channelManager.list()
        if (list.isEmpty()) return "No channels configured."
        return buildString {
            appendLine("📡 ${list.size} channel(s):")
            list.forEach { c ->
                appendLine("• [${c.id}] ${c.displayName} (${c.type}) ${if (c.enabled) "enabled" else "disabled"}")
            }
        }
    }

    private suspend fun channelSend(args: Map<String, Any>): String {
        val to = args["to"]?.toString() ?: return "Error: to required"
        val text = args["text"]?.toString() ?: return "Error: text required"
        val name = args["channel"]?.toString()
        val id = args["channel_id"]?.toString()
        val res = when {
            !id.isNullOrBlank() -> channelManager.send(id, to, text)
            !name.isNullOrBlank() -> channelManager.sendByName(name, to, text)
            else -> return "Error: channel or channel_id required"
        }
        // Phase U2 — if the agent is currently running on behalf of a channel
        // turn AND it just sent text back to that exact same chat, mark the
        // route so ChannelManager.runAgentReply skips its own auto-send and
        // we don't deliver the message twice.
        if (res.success) {
            val routeKey = currentCoroutineContext()[InputRoute]?.routeKey
            if (routeKey != null && routeKey.startsWith("channel:")) {
                val rest = routeKey.removePrefix("channel:")
                val sep = rest.indexOf(':')
                if (sep > 0) {
                    val activeChannelId = rest.substring(0, sep)
                    val activeChatId = rest.substring(sep + 1)
                    val targetChannelId = id?.takeIf { it.isNotBlank() }
                        ?: name?.let { n ->
                            channelManager.list().firstOrNull {
                                it.displayName.equals(n, ignoreCase = true)
                            }?.id
                        }
                    if (targetChannelId == activeChannelId && to == activeChatId) {
                        channelManager.suppressAutoReplyFor(routeKey)
                    }
                }
            }
        }
        return if (res.success) "✅ Sent: ${res.detail}" else "❌ ${res.detail}"
    }

    private suspend fun telegramSendVoice(args: Map<String, Any>): String {
        val to = args["to"]?.toString() ?: return "Error: to required"
        val path = args["path"]?.toString() ?: return "Error: path required"
        val caption = args["caption"]?.toString()
        val name = args["channel"]?.toString()
        val id = args["channel_id"]?.toString()
        val res = when {
            !id.isNullOrBlank()  -> channelManager.sendVoice(id, to, path, caption)
            !name.isNullOrBlank() -> channelManager.sendVoiceByName(name, to, path, caption)
            else -> return "Error: channel or channel_id required"
        }
        return if (res.success) "✅ Voice sent: ${res.detail}" else "❌ ${res.detail}"
    }

    private suspend fun telegramSendFile(args: Map<String, Any>): String {
        val to = args["to"]?.toString() ?: return "Error: to required"
        val path = args["path"]?.toString() ?: return "Error: path required"
        val caption = args["caption"]?.toString()
        val name = args["channel"]?.toString()
        val id = args["channel_id"]?.toString()
        val res = when {
            !id.isNullOrBlank()  -> channelManager.sendFile(id, to, path, caption)
            !name.isNullOrBlank() -> channelManager.sendFileByName(name, to, path, caption)
            else -> {
                val (resolvedId, _) = resolveTelegramChannelId(args)
                if (resolvedId != null) channelManager.sendFile(resolvedId, to, path, caption)
                else return "Error: channel or channel_id required"
            }
        }
        return if (res.success) "✅ File sent: ${res.detail}" else "❌ ${res.detail}"
    }

    private suspend fun telegramReact(args: Map<String, Any>): String {
        val to = args["to"]?.toString() ?: return "Error: to required"
        val messageId = args["message_id"]?.toString()?.toLongOrNull() ?: return "Error: message_id (long) required"
        val reaction = args["reaction"]?.toString() ?: "👍"
        val name = args["channel"]?.toString()
        val id = args["channel_id"]?.toString()
        val res = when {
            !id.isNullOrBlank() -> channelManager.reactToMessage(id, to, messageId, reaction)
            !name.isNullOrBlank() -> channelManager.reactToMessageByName(name, to, messageId, reaction)
            else -> {
                val (resolvedId, _) = resolveTelegramChannelId(args)
                if (resolvedId != null) channelManager.reactToMessage(resolvedId, to, messageId, reaction)
                else return "Error: channel or channel_id required"
            }
        }
        return if (res.success) "✅ Reacted with $reaction" else "❌ ${res.detail}"
    }

    private suspend fun telegramReply(args: Map<String, Any>): String {
        val to = args["to"]?.toString() ?: return "Error: to required"
        val replyToId = args["reply_to_id"]?.toString()?.toLongOrNull() ?: return "Error: reply_to_id (long) required"
        val text = args["text"]?.toString() ?: return "Error: text required"
        val name = args["channel"]?.toString()
        val id = args["channel_id"]?.toString()
        val res = when {
            !id.isNullOrBlank() -> channelManager.replyToMessage(id, to, replyToId, text)
            !name.isNullOrBlank() -> channelManager.replyToMessageByName(name, to, replyToId, text)
            else -> {
                val (resolvedId, _) = resolveTelegramChannelId(args)
                if (resolvedId != null) channelManager.replyToMessage(resolvedId, to, replyToId, text)
                else return "Error: channel or channel_id required"
            }
        }
        return if (res.success) "✅ Replied to $replyToId" else "❌ ${res.detail}"
    }

    /**
     * Expose per-chat conversation history (user + assistant turns) so the
     * agent can review what was said in a Telegram chat without relying solely
     * on the current context window.
     */
    private fun telegramGetHistory(args: Map<String, Any>): String {
        val chatId = args["chat_id"]?.toString() ?: return "Error: chat_id required"
        val n = args["limit"]?.toString()?.toIntOrNull()?.coerceIn(1, 80) ?: 20

        // Reuse the shared resolver so channel lookup is consistent with every
        // other Telegram tool (explicit id → by name → auto-detect most-recent).
        val (resolvedId, err) = resolveTelegramChannelId(args)
        if (resolvedId == null) return err ?: "❌ Could not resolve Telegram channel"

        val history = channelManager.getHistory(resolvedId, chatId, n)
        if (history.isEmpty()) return "No history recorded for chat $chatId on channel $resolvedId yet."

        return buildString {
            appendLine("Last ${history.size} message(s) for chat $chatId:")
            history.forEachIndexed { i, msg ->
                val who = if (msg.role == "assistant") "Agent" else "User"
                appendLine("${i + 1}. [$who] ${msg.content?.take(400)}")
            }
        }.trimEnd()
    }

    private fun channelToggle(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        val enabled = args["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true
        channelManager.setEnabled(id, enabled)
        return "✅ Channel $id ${if (enabled) "enabled" else "disabled"}"
    }

    private fun channelAddTelegram(args: Map<String, Any>): String {
        val name = args["name"]?.toString() ?: "Telegram"
        val token = args["bot_token"]?.toString() ?: return "Error: bot_token required"
        val chat = args["default_chat_id"]?.toString() ?: ""
        val cfg = channelManager.createTelegram(name, token, chat)
        return "✅ Added channel '${cfg.displayName}' (id=${cfg.id})"
    }

    // ─── Telegram session helpers ────────────────────────────────────────────
    //
    // These tools surface "the chat the agent is currently talking to" and
    // make the per-channel allow-list (ChannelConfig.allowedChatIds) writable
    // from inside an agent run. The chat-id discovery uses three sources, in
    // priority order:
    //   1. The current InputRoute coroutine context element ("channel:<id>:<chat>")
    //      — set by ChannelManager when a Telegram message kicks off the run,
    //      so this is the closest thing to "main chat id for THIS session".
    //   2. The most-recent inbound IncomingMessage on the named channel.
    //   3. The channel's configured `defaultChatId` from configJson.
    //
    // All Telegram channels have type == "telegram"; passing channel_id is
    // optional — when omitted we pick the most recently active Telegram
    // channel from `channelManager.list()`.

    /** Resolve a channel id from args, falling back to the InputRoute coroutine
     *  context (set when the agent is running on behalf of a Telegram turn),
     *  then to the only / most-recently-active Telegram channel. */
    private suspend fun resolveTelegramChannelId(args: Map<String, Any>): Pair<String?, String?> {
        val explicit = args["channel_id"]?.toString()?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit to null
        val byName = args["channel"]?.toString()?.takeIf { it.isNotBlank() }
        if (byName != null) {
            val match = channelManager.list().firstOrNull {
                it.displayName.equals(byName, ignoreCase = true)
            } ?: return null to "❌ No channel named '$byName'"
            return match.id to null
        }
        // Auto-detect from the current coroutine's InputRoute (set by ChannelManager
        // when a Telegram message triggers this agent run). This is the most reliable
        // source — it's the exact channel/chat that sent the message.
        val routeKey = currentCoroutineContext()[InputRoute]?.routeKey
        if (routeKey != null && routeKey.startsWith("channel:")) {
            val rest = routeKey.removePrefix("channel:")
            val sep = rest.indexOf(':')
            if (sep > 0) {
                val channelId = rest.substring(0, sep)
                if (channelManager.find(channelId) != null) return channelId to null
            }
        }
        val tg = channelManager.list().filter { it.type == "telegram" }
        return when (tg.size) {
            0 -> null to "❌ No Telegram channel configured. Use channel_add_telegram first."
            1 -> tg.first().id to null
            else -> {
                // Pick the channel whose most recent inbound is newest.
                val recent = channelManager.recent.value
                val ranked = tg.sortedByDescending { ch ->
                    recent.firstOrNull { it.channelId == ch.id }?.receivedAt ?: 0L
                }
                ranked.first().id to null
            }
        }
    }

    /** Returns the chat id the agent should treat as "this conversation". */
    private suspend fun telegramMainChat(args: Map<String, Any>): String {
        // 1. If we're running inside a Telegram-routed coroutine, the
        //    InputRoute carries the exact channel/chat key — use it verbatim.
        val routeKey = currentCoroutineContext()[InputRoute]?.routeKey
        if (routeKey != null && routeKey.startsWith("channel:")) {
            val rest = routeKey.removePrefix("channel:")
            val sep = rest.indexOf(':')
            if (sep > 0) {
                val channelId = rest.substring(0, sep)
                val chatId = rest.substring(sep + 1)
                val cfg = channelManager.find(channelId)
                return buildString {
                    appendLine("📍 Current Telegram session:")
                    appendLine("  channel_id: $channelId")
                    appendLine("  channel:    ${cfg?.displayName ?: "?"}")
                    appendLine("  chat_id:    $chatId")
                    appendLine("(source: live agent run)")
                }.trimEnd()
            }
        }
        // 2. Fall back to the most-recent inbound message for the resolved channel.
        val (id, err) = resolveTelegramChannelId(args)
        if (err != null) return err
        val cfg = channelManager.find(id!!)
            ?: return "❌ Unknown channel id: $id"
        val recent = channelManager.recent.value
            .firstOrNull { it.channelId == id }
        if (recent != null) {
            return buildString {
                appendLine("📍 Most recent Telegram chat for '${cfg.displayName}':")
                appendLine("  channel_id: $id")
                appendLine("  chat_id:    ${recent.fromId}")
                appendLine("  from:       ${recent.fromName}")
                appendLine("  last seen:  ${recent.receivedAt}")
                appendLine("(source: most recent inbound message)")
            }.trimEnd()
        }
        // 3. Last resort: the channel's configured defaultChatId.
        val defaultChatId = parseDefaultChatId(cfg.configJson)
        if (defaultChatId.isNotBlank()) {
            return "📍 Default chat id for '${cfg.displayName}': $defaultChatId\n" +
                "(source: channel configuration; no inbound messages yet)"
        }
        return "(no chats seen yet on '${cfg.displayName}'. Send the bot a " +
            "message from Telegram first, then call this tool again.)"
    }

    /** Best-effort extraction of `defaultChatId` from the channel's configJson
     *  blob without pulling kotlinx.serialization for a single field. */
    private fun parseDefaultChatId(configJson: String): String {
        val key = "\"defaultChatId\""
        val i = configJson.indexOf(key)
        if (i < 0) return ""
        val colon = configJson.indexOf(':', i + key.length)
        if (colon < 0) return ""
        val q1 = configJson.indexOf('"', colon + 1)
        if (q1 < 0) return ""
        val q2 = configJson.indexOf('"', q1 + 1)
        if (q2 < 0) return ""
        return configJson.substring(q1 + 1, q2)
    }

    // ─── Enhanced Integration: Project Context Methods ──────────────────────

    /**
     * Get the currently active project for enhanced context building.
     */
    fun getActiveProject(): com.forge.os.domain.projects.Project? {
        return try {
            kotlinx.coroutines.runBlocking {
                projectToolProvider.dispatch("project_get_active", emptyMap())
            }?.let { result ->
                if (result.contains("No active project")) null
                else {
                    // Extract project slug from the result and get full project details
                    val slugMatch = Regex("\\(([^)]+)\\)").find(result)
                    val slug = slugMatch?.groupValues?.get(1)
                    if (slug != null) {
                        // Get project details from repository
                        val projectsRepository = projectToolProvider.javaClass.getDeclaredField("repository").let { field ->
                            field.isAccessible = true
                            field.get(projectToolProvider) as com.forge.os.domain.projects.ProjectsRepository
                        }
                        projectsRepository.get(slug)
                    } else null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get active project")
            null
        }
    }

    /**
     * Get the file count for a project.
     */
    fun getProjectFileCount(slug: String): Int {
        return try {
            val projectsRepository = projectToolProvider.javaClass.getDeclaredField("repository").let { field ->
                field.isAccessible = true
                field.get(projectToolProvider) as com.forge.os.domain.projects.ProjectsRepository
            }
            projectsRepository.fileCount(slug)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get project file count for $slug")
            0
        }
    }

    /** Recent inbound chats per channel (or for one channel if specified). */
    private fun telegramListChats(args: Map<String, Any>): String {
        val onlyChannel = args["channel_id"]?.toString()?.takeIf { it.isNotBlank() }
        val limit = (args["limit"] as? Number)?.toInt()
            ?: args["limit"]?.toString()?.toIntOrNull() ?: 20
        val recent = channelManager.recent.value
            .let { if (onlyChannel != null) it.filter { m -> m.channelId == onlyChannel } else it }
        if (recent.isEmpty()) {
            return "(no inbound messages seen yet" +
                (if (onlyChannel != null) " on channel $onlyChannel" else "") + ")"
        }
        // Distinct (channelId, chatId) pairs, ordered by most recent.
        val seen = LinkedHashSet<Pair<String, String>>()
        val rows = mutableListOf<com.forge.os.domain.channels.IncomingMessage>()
        for (m in recent) {
            val key = m.channelId to m.fromId
            if (seen.add(key)) rows += m
            if (rows.size >= limit) break
        }
        return buildString {
            appendLine("📨 ${rows.size} recent chat(s):")
            rows.forEach { m ->
                val cfg = channelManager.find(m.channelId)
                appendLine(
                    "• [${cfg?.displayName ?: m.channelType}] chat_id=${m.fromId} " +
                    "from=${m.fromName} (channel_id=${m.channelId})"
                )
            }
        }.trimEnd()
    }

    private fun telegramGetAllowedChats(args: Map<String, Any>): String {
        val (id, err) = resolveTelegramChannelId(args)
        if (err != null) return err
        val cfg = channelManager.find(id!!)
            ?: return "❌ Unknown channel id: $id"
        val csv = cfg.allowedChatIds.trim()
        if (csv.isBlank()) {
            return "🔓 '${cfg.displayName}' has NO allow-list — every chat that " +
                "messages the bot is allowed to interact with the agent."
        }
        val ids = csv.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return buildString {
            appendLine("🔒 '${cfg.displayName}' allow-list (${ids.size}):")
            ids.forEach { appendLine("  • $it") }
        }.trimEnd()
    }

    private fun telegramSetAllowedChats(args: Map<String, Any>): String {
        val (id, err) = resolveTelegramChannelId(args)
        if (err != null) return err
        val csv = args["chat_ids"]?.toString() ?: ""
        channelManager.setAllowedChatIds(id!!, csv)
        val cfg = channelManager.find(id)
        return if (csv.isBlank()) {
            "✅ Cleared allow-list for '${cfg?.displayName ?: id}'. All chats " +
                "may now message the bot."
        } else {
            val n = csv.split(',').count { it.trim().isNotBlank() }
            "✅ Replaced allow-list for '${cfg?.displayName ?: id}' with $n chat id(s)."
        }
    }

    private fun telegramAllowChat(args: Map<String, Any>): String {
        val (id, err) = resolveTelegramChannelId(args)
        if (err != null) return err
        val cfg = channelManager.find(id!!) ?: return "❌ Unknown channel id: $id"
        val chatId = args["chat_id"]?.toString()?.trim().orEmpty()
        if (chatId.isBlank()) return "Error: chat_id required"
        val current = cfg.allowedChatIds.split(',').map { it.trim() }
            .filter { it.isNotBlank() }.toMutableSet()
        if (!current.add(chatId)) {
            return "ℹ️ chat_id $chatId was already in the allow-list for " +
                "'${cfg.displayName}'."
        }
        channelManager.setAllowedChatIds(id, current.joinToString(","))
        return "✅ Added chat_id $chatId to allow-list for '${cfg.displayName}'. " +
            "(${current.size} total)"
    }

    private fun telegramDenyChat(args: Map<String, Any>): String {
        val (id, err) = resolveTelegramChannelId(args)
        if (err != null) return err
        val cfg = channelManager.find(id!!) ?: return "❌ Unknown channel id: $id"
        val chatId = args["chat_id"]?.toString()?.trim().orEmpty()
        if (chatId.isBlank()) return "Error: chat_id required"
        val current = cfg.allowedChatIds.split(',').map { it.trim() }
            .filter { it.isNotBlank() }.toMutableSet()
        if (!current.remove(chatId)) {
            return "ℹ️ chat_id $chatId is not in the allow-list for " +
                "'${cfg.displayName}'."
        }
        channelManager.setAllowedChatIds(id, current.joinToString(","))
        return "✅ Removed chat_id $chatId from allow-list for '${cfg.displayName}'. " +
            "(${current.size} remaining)"
    }

    // ─── Self-description ────────────────────────────────────────────────────

    /** Returns a markdown summary of the host app — what Forge OS is, what
     *  capability surfaces it exposes, and how to reach its built-in HTTP API
     *  and bound AIDL service. The agent can call this when the user asks
     *  "what can you do?" / "what is this app?" / "do you have an API?" etc.
     *  All values are read live (the API key is never leaked — only the
     *  prefix is shown). */
    private fun appDescribe(): String {
        val server = httpServer
        val key = runCatching { server.apiKey() }.getOrNull().orEmpty()
        val keyHint = if (key.length >= 6) "${key.take(6)}…" else "(unavailable)"
        val running = runCatching { server.isRunning() }.getOrDefault(false)
        val port = runCatching { server.port() }.getOrDefault(8789)
        val toolCount = runCatching { getDefinitions().size }.getOrDefault(0)
        val channels = runCatching { channelManager.list() }.getOrDefault(emptyList())
        val tgCount = channels.count { it.type == "telegram" }
        return buildString {
            appendLine("# Forge OS — agentic on-device LLM operating system")
            appendLine()
            appendLine("You are running INSIDE Forge OS, an Android app the user installed")
            appendLine("on their own device. Treat the device as the user's machine — files")
            appendLine("you create live in the app's sandboxed workspace under")
            appendLine("`/sdcard/Android/data/com.forge.os/files/workspace/` (use the")
            appendLine("`workspace_describe` tool for the live layout).")
            appendLine()
            appendLine("## What you can do here")
            appendLine("- File / shell / Python execution scoped to the workspace.")
            appendLine("- Long-term semantic memory (`memory_*`, `semantic_recall_facts`).")
            appendLine("- Cron jobs (`cron_add`) and one-shot alarms (`alarm_set`).")
            appendLine("- Persistent headless browser (`browser_navigate`, `browser_get_html`,")
            appendLine("  `browser_click`, `browser_fill_field`, …) sharing cookies with the")
            appendLine("  on-screen tab.")
            appendLine("- Git over HTTPS (`git_init`, `git_clone`, `git_commit`, `git_push`, …).")
            appendLine("- File / browser downloads (`file_download`, `browser_download`).")
            appendLine("- Multi-channel messaging (Telegram today; see the `telegram_*` and")
            appendLine("  `channel_*` tools).")
            appendLine("- Plugin host (`plugin_*`) and MCP client (tools prefixed `mcp.`).")
            appendLine("- Sub-agent delegation, notifications, screen-shotting, project hosting.")
            appendLine()
            appendLine("Total tools available right now: $toolCount.")
            appendLine()
            appendLine("## Built-in HTTP API (`ForgeHttpServer`)")
            appendLine("The app ships its own local API so external scripts (Tasker, a")
            appendLine("desktop helper, a browser extension, another bot) can drive YOU.")
            appendLine("- Status:   ${if (running) "running on port $port" else "not running (call `server_start`)"}")
            appendLine("- Bearer key (prefix only): $keyHint  — rotate via `server_rotate_key`")
            appendLine("- Endpoints (all need `Authorization: Bearer <key>`):")
            appendLine("  - `GET  /api/status` — server health JSON.")
            appendLine("  - `GET  /api/tools`  — list of every tool you can invoke.")
            appendLine("  - `POST /api/tool`   — body `{\"name\":\"<tool>\",\"args\":{…}}`,")
            appendLine("                          returns `{\"ok\":bool,\"output\":\"…\"}`.")
            appendLine("- The same registry that backs YOUR tool calls is exposed there, so")
            appendLine("  any tool listed in `getDefinitions` is reachable via HTTP.")
            appendLine()
            appendLine("## Bound AIDL service (`com.forge.os.api.IForgeOsService`)")
            appendLine("Other Android apps on the device can bind to this service to:")
            appendLine("`getApiVersion`, `listTools`, `invokeTool`, `invokeToolAsync`,")
            appendLine("`askAgent` (streaming), `getMemory` / `putMemory`, `runSkill`.")
            appendLine()
            appendLine("## Channels currently configured")
            if (channels.isEmpty()) {
                appendLine("- (none yet — use `channel_add_telegram` to wire a bot)")
            } else {
                channels.forEach { c ->
                    appendLine("- [${c.id}] ${c.displayName} (${c.type}) " +
                        if (c.enabled) "enabled" else "disabled")
                }
                if (tgCount > 0) {
                    appendLine()
                    appendLine("Telegram quick-ref: `telegram_main_chat` returns the chat id")
                    appendLine("of the conversation that called you, `telegram_list_chats`")
                    appendLine("enumerates recent senders, `telegram_allow_chat` /")
                    appendLine("`telegram_deny_chat` manage the per-channel allow-list, and")
                    appendLine("`telegram_send_voice` posts an OGG/Opus voice note.")
                }
            }
            appendLine()
            appendLine("Use `control_status` for live capability gates and")
            appendLine("`workspace_describe` for the workspace layout.")
        }.trimEnd()
    }

    // ─── Arg parsing ─────────────────────────────────────────────────────────

    /**
     * Parse the LLM-supplied tool arguments JSON into a Map<String, Any>.
     *
     * Robust against:
     *  - Empty / blank input (returns empty map instead of throwing).
     *  - Non-primitive values (JsonObject / JsonArray) — these are stringified
     *    so a later `.toString()` in the tool body still works.
     *  - JsonNull — the key is dropped so callers see a missing arg, not "null".
     *  - A single bad entry — only that key is dropped, the rest of the map
     *    survives. The previous implementation aborted the whole map on any
     *    failure which produced spurious "X required" errors when the model
     *    legitimately supplied X but also supplied a separate non-primitive
     *    value alongside it.
     *  - Loosely-quoted JSON (Json instance is `isLenient = true`).
     *  - Top-level non-object JSON — a best-effort recovery is attempted by
     *    wrapping single-key salvage; otherwise an empty map is returned and
     *    the failure is logged so the user can see WHY parsing failed.
     */
    private fun parseArgs(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        val root: JsonObject = try {
            this.json.decodeFromString<JsonObject>(json)
        } catch (e: Exception) {
            Timber.w(e, "parseArgs: top-level JSON parse failed for: ${json.take(200)}")
            return emptyMap()
        }
        return root.entries.mapNotNull { (k, v) ->
            try {
                val value: Any = when (v) {
                    is kotlinx.serialization.json.JsonNull -> return@mapNotNull null
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        when {
                            v.isString -> v.content
                            v.content == "true" -> true
                            v.content == "false" -> false
                            else -> v.content.toLongOrNull() ?: v.content.toDoubleOrNull() ?: v.content
                        }
                    }
                    is kotlinx.serialization.json.JsonArray -> {
                        // Comma-join scalar arrays so e.g. tags=["a","b"] → "a,b"
                        // (callers already split on commas). Object arrays fall
                        // back to their JSON string form.
                        if (v.all { it is kotlinx.serialization.json.JsonPrimitive }) {
                            v.joinToString(",") { (it as kotlinx.serialization.json.JsonPrimitive).contentOrNull ?: it.toString() }
                        } else v.toString()
                    }
                    is kotlinx.serialization.json.JsonObject -> v.toString()
                }
                k to value
            } catch (e: Exception) {
                Timber.w(e, "parseArgs: dropping bad arg '$k'")
                null
            }
        }.toMap()
    }

    // ─── Phase Q helpers ─────────────────────────────────────────────────────

    private fun controlList(): String = buildString {
        appendLine("Capabilities (${controlPlane.capabilities.size}):")
        controlPlane.capabilities.groupBy { it.category }.forEach { (cat, list) ->
            appendLine("  • $cat")
            list.forEach { spec ->
                val on = controlPlane.isEnabled(spec.id)
                val flag = if (on) "ON " else "off"
                val lock = if (spec.requiresConsent) "🔒" else "  "
                appendLine("      $lock $flag  ${spec.id}  — ${spec.title}  [danger=${spec.danger}]")
            }
        }
    }

    private fun controlDescribe(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        val spec = controlPlane.spec(id) ?: return "Unknown capability: $id"
        val state = controlPlane.stateOf(id)
        return buildString {
            appendLine("${spec.id}  [${spec.category}]  danger=${spec.danger}")
            appendLine("  ${spec.title}")
            appendLine("  ${spec.description}")
            appendLine("  default=${spec.defaultEnabled}  requiresConsent=${spec.requiresConsent}")
            appendLine("  current=${state?.enabled}  lastChangedBy=${state?.updatedBy}")
            if (spec.requiresConsent) {
                appendLine("  consentGranted=${consentLedger.isGranted(id)}")
            }
        }
    }

    private fun controlSet(args: Map<String, Any>, source: String): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        val enabled = args["enabled"]?.toString()?.equals("true", ignoreCase = true)
            ?: return "Error: enabled required (true|false)"
        val requestedBy = if (source == "user" || source == "ui") "user" else "agent"
        val result = if (requestedBy == "user") controlPlane.setByUser(id, enabled, source = source)
                     else controlPlane.set(id, enabled, requestedBy = "agent", source = source)
        return when (result) {
            is com.forge.os.domain.control.AgentControlPlane.SetResult.Ok ->
                "✓ ${id} -> ${if (enabled) "ON" else "off"}"
            is com.forge.os.domain.control.AgentControlPlane.SetResult.UnknownCapability ->
                "Error: unknown capability ${result.id}"
            is com.forge.os.domain.control.AgentControlPlane.SetResult.ConsentRequired ->
                "❌ Consent required for '${result.spec.id}' (${result.spec.title}). " +
                "Ask the user to call control_grant id=${result.spec.id} first."
        }
    }

    private fun controlGrant(args: Map<String, Any>, source: String): String {
        if (source != "user" && source != "ui") {
            return "❌ control_grant can only be called from a user-initiated context (source=user|ui)"
        }
        val id = args["id"]?.toString() ?: return "Error: id required"
        val ttl = (args["ttl_seconds"]?.toString()?.toLongOrNull() ?: 0L) * 1000L
        val note = args["note"]?.toString().orEmpty()
        consentLedger.grantFromUser(id, ttlMs = ttl, source = source, note = note)
        return "✓ Consent granted for $id (ttl_ms=$ttl)"
    }

    private fun controlRevoke(args: Map<String, Any>, source: String): String {
        if (source != "user" && source != "ui") {
            return "❌ control_revoke can only be called from a user-initiated context"
        }
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (consentLedger.revoke(id)) "✓ Consent revoked for $id" else "(no grant existed for $id)"
    }

    private fun pluginExportAll(): String {
        val ids = pluginManager.reExportAll()
        return if (ids.isEmpty()) "(no user plugins exported)"
               else "✓ Exported ${ids.size}: ${ids.joinToString()}"
    }

    private fun pluginExportList(): String {
        val files = pluginExporter.listExports()
        if (files.isEmpty()) return "(no exported plugin bundles)"
        return files.joinToString("\n") { "  ${it.name}  (${it.length()} bytes)" }
    }

    private fun pluginRestoreMissing(): String {
        val ids = pluginExporter.restoreMissingPlugins(pluginRepository)
        return if (ids.isEmpty()) "(nothing to restore)" else "✓ Restored: ${ids.joinToString()}"
    }

    private suspend fun notifySend(args: Map<String, Any>): String {
        val title = args["title"]?.toString() ?: return "Error: title required"
        val body = args["body"]?.toString() ?: return "Error: body required"
        val channel = args["channel"]?.toString() ?: "forge_agent"
        val actionsJson = args["actions_json"]?.toString().orEmpty()
        val actions = parseActionSpecs(actionsJson)
        val id = agentNotifier.postWithActions(title, body, channel, actions)
        return if (id < 0) "❌ notification failed" else "✓ notification posted (id=$id, ${actions.size} actions)"
    }

    private fun parseActionSpecs(raw: String): List<com.forge.os.domain.notifications.AgentNotificationBuilder.ActionSpec> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val label = obj["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val kind = obj["kind"]?.jsonPrimitive?.content ?: "chat_message"
                val payload = obj["payload_json"]?.toString() ?: "{}"
                com.forge.os.domain.notifications.AgentNotificationBuilder.ActionSpec(label, kind, payload)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun webScreenshot(args: Map<String, Any>): String {
        val url = args["url"]?.toString() ?: return "Error: url required"
        val rel = args["out_path"]?.toString()
            ?: "screenshots/web_${System.currentTimeMillis()}.png"
        val width = args["width"]?.toString()?.toIntOrNull() ?: 1280
        val height = args["height"]?.toString()?.toIntOrNull() ?: 1600
        val full = args["full_page"]?.toString()?.equals("false", ignoreCase = true) != true
        val waitMs = args["wait_ms"]?.toString()?.toLongOrNull() ?: 1500L
        val outFile = java.io.File(appContext.filesDir, "workspace/$rel")
        val res = webScreenshotter.capture(
            com.forge.os.data.web.WebScreenshotter.Spec(
                url = url, widthPx = width, heightPx = height,
                fullPage = full, waitMs = waitMs, outputPath = outFile,
            )
        )
        return res.fold(
            onSuccess = { f -> "✓ saved ${f.length()} bytes -> workspace/${rel}" },
            onFailure = { e -> "❌ ${e.message}" },
        )
    }

    private fun browserHistoryList(args: Map<String, Any>): String {
        val sid = args["session_id"]?.toString()?.takeIf { it.isNotBlank() }
        val limit = args["limit"]?.toString()?.toIntOrNull() ?: 100
        val entries = browserHistory.list(sid, limit)
        if (entries.isEmpty()) return "(no history${if (sid != null) " for $sid" else ""})"
        return entries.joinToString("\n") {
            "  [${it.sessionId}] ${java.util.Date(it.ts)} · ${it.url}${if (it.title.isNotBlank()) " — ${it.title}" else ""}"
        }
    }

    private fun browserHistoryClear(args: Map<String, Any>): String {
        val sid = args["session_id"]?.toString()?.takeIf { it.isNotBlank() }
        browserHistory.clear(sid)
        return "✓ cleared history${if (sid != null) " for $sid" else ""}"
    }

    private fun browserSessionNew(args: Map<String, Any>): String {
        val sid = args["session_id"]?.toString() ?: return "Error: session_id required"
        val note = args["note"]?.toString().orEmpty()
        browserHistory.record(com.forge.os.data.browser.BrowserHistoryEntry(
            ts = System.currentTimeMillis(), sessionId = sid, url = "about:session",
            title = "New session: $sid", source = "marker"))
        return "✓ session marker recorded: $sid${if (note.isNotBlank()) "  — $note" else ""}"
    }

    private fun projectServe(args: Map<String, Any>): String {
        val rel = args["path"]?.toString() ?: return "Error: path required"
        val port = args["port"]?.toString()?.toIntOrNull() ?: 0
        val root = java.io.File(appContext.filesDir, "workspace/$rel")
        return runCatching {
            val s = projectServer.start(root, port)
            "✓ serving ${s.root.absolutePath} on ${s.url}  (id=${s.id})"
        }.getOrElse { "❌ ${it.message}" }
    }

    private fun projectUnserve(args: Map<String, Any>): String {
        val id = args["id"]?.toString() ?: return "Error: id required"
        return if (projectServer.stop(id)) "✓ stopped $id" else "(no server with id $id)"
    }

    private fun projectServeList(): String {
        val list = projectServer.list()
        if (list.isEmpty()) return "(no project servers running)"
        return list.joinToString("\n") { "  ${it.id}  port=${it.port}  ${it.url}  root=${it.root.absolutePath}" }
    }

    private fun proactiveStatus(): String {
        val cap = controlPlane.isEnabled(com.forge.os.domain.control.AgentControlPlane.PROACTIVE_SUGGEST)
        return "proactive_suggestions=${if (cap) "ON" else "off"}"
    }

    private fun proactiveSchedule(): String {
        proactiveScheduler.ensureScheduled()
        return "✓ proactive worker enqueued (will run every ~30 minutes; gated by capability)"
    }

    private fun proactiveCancel(): String {
        proactiveScheduler.cancel()
        return "✓ proactive worker cancelled"
    }

    // ─── Tool definitions ────────────────────────────────────────────────────

    private val ALL_TOOLS = listOf(
        // Config
        tool("config_read",
            "Read the current agent configuration (provider, model, behavior rules, " +
            "enabled tools, etc). Use this BEFORE proposing a config change so you " +
            "know the current values.",
            params()),
        tool("config_write",
            "Modify the agent's runtime configuration via a free-text request. " +
            "WHEN TO USE: the user asked to change agent name, switch provider/model, " +
            "enable or disable a tool, change auto-confirm, set max iterations, " +
            "change heartbeat interval, change route for a task type, etc. Pass the " +
            "user's request VERBATIM. The engine recognises patterns like: 'change " +
            "agent name to X', 'disable tool shell_exec', 'set auto confirm on', " +
            "'set max iterations to 25', 'route code tasks to claude', 'set default " +
            "provider to anthropic', 'set heartbeat to 60 seconds', 'enable verbose " +
            "logging'. Always quote tool names exactly. The change is auto-versioned " +
            "and rollback-able via config_rollback.",
            params("request" to "string:The user's natural-language config change request, verbatim")),
        tool("config_rollback",
            "Restore the configuration to a previous saved version (every config_write " +
            "creates a snapshot). Pass the version string from config_read.",
            params("version" to "string:Version string e.g. 1.0.2")),
        // System
        tool("heartbeat_check", "Run a full system health check", params()),
        // Memory
        tool("memory_store", "Store a fact in long-term memory",
            params("key" to "string:Unique key", "content" to "string:Content to remember",
                   "tags" to "string:Comma-separated tags"),
            required = listOf("key", "content")),
        tool("memory_recall", "Semantic search across all memory tiers",
            params("query" to "string:Natural language search query"), required = listOf("query")),
        tool("semantic_recall_facts", "Vector search across long-term facts",
            params("query" to "string:Natural language query", "k" to "string:Max results (1-25, default 5)"),
            required = listOf("query")),
        tool("memory_store_skill", "Store a reusable Python skill in skill memory",
            params("name" to "string:Skill name", "description" to "string:What it does",
                   "code" to "string:Python code", "tags" to "string:Comma-separated tags"),
            required = listOf("name", "description", "code")),
        tool("memory_get_skill", "Retrieve the full source code of a stored skill by name",
            params("name" to "string:Skill name"), required = listOf("name")),
        tool("memory_list_skills", "List all stored skills and their descriptions", emptyMap()),
        tool("memory_summary", "Get a summary of all memory tiers", params()),
        tool("memory_store_image",
            "Tag a workspace image and index it in semantic memory so it can be " +
            "recalled by description later. Pass a workspace-relative `path` and " +
            "optional comma-separated `tags` (e.g. 'screenshot,ui,dark-mode'). " +
            "Use this after web_screenshot or file_download to make the image " +
            "searchable via memory_recall.",
            params("path" to "string:Workspace-relative image path",
                   "tags" to "string:Comma-separated tags (optional)"),
            required = listOf("path")),
        // Cron
        tool("cron_add",
            "Schedule a recurring or one-shot background job. WHEN TO USE: the user " +
            "says 'every X minutes/hours/day at HH:MM, do Y'. Schedule strings the " +
            "engine accepts: 'every 30 seconds', 'every 5 minutes', 'every 2 hours', " +
            "'daily at 09:00', 'every monday at 18:30', 'in 90 seconds' (one-shot), " +
            "or a 5-field cron expression like '*/15 * * * *'. The `task_type` " +
            "controls how `payload` is interpreted: PYTHON runs `payload` as Python " +
            "code, SHELL runs it as a shell command, PROMPT feeds it back to the " +
            "agent as a fresh user message. Always supply `name`, `schedule`, and " +
            "`payload` as plain strings.",
            params("name" to "string:Short human label, e.g. 'morning briefing'",
                   "schedule" to "string:e.g. 'every 30 minutes', 'daily at 09:00', '*/15 * * * *'",
                   "payload" to "string:Python code, shell command, or natural-language prompt",
                   "task_type" to "string:PYTHON | SHELL | PROMPT (default PROMPT)",
                   "model" to "string:Optional model override (e.g. 'gpt-4o', 'claude-3-5-sonnet')",
                   "tags" to "string:Optional comma-separated tags"),
            required = listOf("name", "schedule", "payload")),
        tool("cron_list", "List all scheduled cron jobs", params()),
        tool("cron_remove", "Remove a scheduled cron job by id", params("id" to "string:Job id")),
        tool("cron_run_now", "Execute a cron job immediately", params("id" to "string:Job id")),
        tool("cron_history", "Show recent cron job execution history", params()),
        // Plugins
        tool("plugin_list", "List all installed plugins", params()),
        tool("plugin_install", "Install a plugin from manifest + entrypoint code",
            params("manifest" to "string:Plugin manifest JSON", "code" to "string:Python entrypoint"),
            required = listOf("manifest", "code")),
        tool("plugin_uninstall", "Uninstall a user plugin by id", params("id" to "string:Plugin id")),
        // NOTE: `plugin_execute` is intentionally NOT exposed in the schema.
        // Plugin tools are surfaced as first-class tools (their declared name
        // shows up directly in this list, prefixed with "[plugin]" in the
        // description) and the dispatcher in `runTool` invokes them by name.
        // The legacy dispatcher case for "plugin_execute" is kept only so old
        // models / cached histories that still emit it don't error out.
        // Delegation
        tool("delegate_task", "Spawn a sub-agent for a focused task",
            params("goal" to "string:Concrete goal", 
                   "context" to "string:Extra context",
                   "model" to "string:Optional model override (e.g. 'gpt-4o', 'claude-3-5-sonnet')",
                   "tags" to "string:Comma-separated tags"),
            required = listOf("goal")),
        tool("delegate_ghost", "Spawn an isolated ghost agent with its own ephemeral workspace",
            params("goal" to "string:Concrete goal", 
                   "context" to "string:Extra context"),
            required = listOf("goal")),
        tool("delegate_batch", "Spawn multiple sub-agents",
            params("goals" to "string:Newline-separated goals",
                   "strategy" to "string:sequential | parallel | best_of",
                   "context" to "string:Shared context"),
            required = listOf("goals")),
        tool("agents_list", "List all spawned sub-agents", params()),
        tool("agent_status", "Inspect a single sub-agent", params("id" to "string:Sub-agent id")),
        tool("agent_cancel", "Cancel a running sub-agent", params("id" to "string:Sub-agent id")),
        // Snapshots
        tool("snapshot_create", "Take a snapshot of the workspace",
            params("label" to "string:Optional human-readable label")),
        tool("snapshot_list", "List all workspace snapshots", params()),
        tool("snapshot_restore", "Restore workspace from a snapshot",
            params("id" to "string:Snapshot id"), required = listOf("id")),
        tool("snapshot_delete", "Delete a workspace snapshot",
            params("id" to "string:Snapshot id"), required = listOf("id")),
        // Model catalog
        tool("model_cache_refresh", 
            "Force refresh the model catalog for all configured providers. " +
            "By default, model lists are cached for 24 hours. Use this to fetch " +
            "the latest available models immediately (e.g. after adding a new API key).",
            params()),
        // MCP
        tool("mcp_refresh", "Refresh MCP server tool catalog", params()),
        tool("mcp_list_tools", "List cached MCP tools", params()),
        tool("mcp_call_tool",
            "Invoke a specific tool exposed by a connected MCP server. WHEN TO " +
            "USE: any time you've discovered a tool via mcp_list_tools and need " +
            "to execute it without constructing the dotted `mcp.<server>.<tool>` " +
            "name yourself. Pass the server name, the tool name, and the " +
            "tool's arguments as a JSON object (or a JSON string).",
            params("server" to "string:MCP server name (see mcp_list_tools)",
                   "tool"   to "string:Tool name on that server",
                   "args"   to "object:Arguments object for the tool (JSON)"),
            required = listOf("server", "tool")),
        // Vision: analyse a workspace image via a vision-capable LLM.
        tool("image_analyze",
            "Send an image stored in the workspace to a vision-capable LLM (GPT-4o, " +
            "Claude 3.5, or Gemini) and return its textual analysis. WHEN TO USE: " +
            "a user uploaded a photo (e.g. workspace/uploads/...), you took a " +
            "screenshot (web_screenshot), or you downloaded an image and need to " +
            "know what's in it. Supported types: jpg, png, webp. No secret required " +
            "as it uses your configured AI providers.",
            params("path"   to "string:Workspace-relative image path",
                   "prompt" to "string:What you want the model to do with the image",
                   "model"  to "string:Optional specific model id to use"),
            required = listOf("path")),
        // ─── NEW: HTTP / Fetch ─────────────────────────────────────────────────
        tool("http_fetch",
            "Plain HTTP request returning the response body as text. WHEN TO USE: " +
            "calling REST APIs (GET to read, POST to create/update), downloading a " +
            "JSON document, hitting a webhook. No API key required by Forge. The " +
            "response is truncated to `max_chars` to avoid blowing context. For " +
            "complex requests (PUT/PATCH, custom Content-Type, binary), prefer " +
            "curl_exec. Runs off the main thread, so it will NOT throw " +
            "NetworkOnMainThreadException.",
            params("url" to "string:Full URL including scheme, e.g. 'https://api.github.com/users/foo'",
                   "method" to "string:GET (default) | POST | DELETE",
                   "body" to "string:Request body for POST (typically a JSON string)",
                   "headers" to "string:One 'Key: Value' per line, e.g. 'Authorization: Bearer xyz'",
                   "max_chars" to "string:Truncate response to this many chars (default 8000)"),
            required = listOf("url")),
        // ─── Phase T: Named secrets ──────────────────────────────────────────
        tool("secret_list",
            "List the user-registered named secrets that you can use with " +
            "secret_request. RETURNS ONLY names + auth style + description — " +
            "you NEVER see the raw secret value. WHEN TO USE: at the start of " +
            "any task that involves calling a third-party API the user has " +
            "wired up (e.g. their GitHub PAT, a private webhook key, a paid " +
            "search API). If the secret you need isn't listed, tell the user " +
            "what name/description to add in Settings → Custom API Keys.",
            params()),
        tool("secret_request",
            "Perform an HTTP request where Forge attaches a registered named " +
            "secret to the outgoing call. You pass the secret's NAME (not its " +
            "value); Forge looks up how the secret should be attached " +
            "(Authorization: Bearer …, custom header, or query parameter) " +
            "from its registration and adds it before sending. Use this " +
            "instead of http_fetch / curl_exec whenever an API requires " +
            "authentication — it's the only way to call those APIs without " +
            "the raw secret value entering the model context.",
            params("name" to "string:Registered secret name, e.g. 'github_pat' (call secret_list to see options)",
                   "url" to "string:Full request URL (scheme required)",
                   "method" to "string:GET (default) | POST | PUT | PATCH | DELETE | HEAD",
                   "body" to "string:Request body for write methods (already serialised)",
                   "content_type" to "string:Content-Type header value (default 'application/json')",
                   "headers" to "string:Extra non-auth headers, one 'Key: Value' per line",
                   "max_chars" to "string:Truncate response body to this many chars (default 8000)",
                   "save_as" to "string:Optional path to save binary response (e.g. 'downloads/audio.mp3')",
                   "upload_file" to "string:Optional path to a workspace file to upload as binary body"),
            required = listOf("name", "url")),
        tool("curl_exec",
            "Full HTTP request with control over method, headers, body, and " +
            "content-type. WHEN TO USE: anything more involved than http_fetch — " +
            "PUT/PATCH/HEAD, file uploads with explicit Content-Type, sending " +
            "form-encoded bodies. Returns status code + headers + truncated body.",
            params("url" to "string:Full URL including scheme",
                   "method" to "string:GET (default) | POST | PUT | PATCH | DELETE | HEAD",
                   "body" to "string:Request body string (already serialized)",
                   "content_type" to "string:Content-Type header value (default 'application/json')",
                   "headers" to "string:Extra headers, one 'Key: Value' per line",
                   "max_chars" to "string:Truncate response to this many chars (default 8000)"),
            required = listOf("url")),
        // ─── NEW: Search ──────────────────────────────────────────────────────
        tool("ddg_search",
            "Search the open web via DuckDuckGo's HTML endpoint and return ranked " +
            "results (title, url, snippet). WHEN TO USE: any time the user asks for " +
            "current information, references, links, or 'find me a page about X'. " +
            "No API key required. After searching, follow up with browser_navigate " +
            "+ browser_get_html (or http_fetch) on the most relevant result to read " +
            "the actual content — the snippet alone is rarely enough.",
            params("query" to "string:Search query, e.g. 'jetpack compose lazycolumn snapping'",
                   "max_results" to "string:Maximum results to return, 1..25 (default 10)"),
            required = listOf("query")),
        // ─── NEW: Browser control (agent-owned headless WebView) ──────────────
        // All browser_* tools below drive the AGENT's own off-screen WebView.
        // They run completely in the background — the user does NOT need to
        // open the on-screen Browser tab. State (cookies, scroll, current URL),
        // persists across calls. Cookies are shared with the on-screen Browser
        // tab via Android's global cookie jar, so logging into a site once on
        // the visible tab automatically gives the agent the same session.
        tool("browser_navigate",
            "Load a URL in the agent's persistent off-screen browser and wait " +
            "for the page to finish loading. WHEN TO USE: any time you need to " +
            "read or interact with a real web page. This does NOT require the " +
            "on-screen Browser tab to be open. The session (cookies, login " +
            "state) persists across calls and is shared with the user's visible " +
            "Browser tab. After navigating, call browser_get_html to read the " +
            "page, or browser_click / browser_fill_field / browser_eval_js to " +
            "interact with it.",
            params("url" to "string:URL to navigate to. Scheme optional — 'example.com' becomes 'https://example.com'"),
            required = listOf("url")),
        tool("browser_get_html",
            "Return the visible text of the currently-loaded page (scripts and " +
            "styles stripped, whitespace collapsed, capped at ~6000 chars). " +
            "Optionally pass `url` to navigate AND read in one call. The agent's " +
            "browser is fully off-screen — this works even if the user is on a " +
            "completely different app screen.",
            params("url" to "string:Optional URL — if given, navigate there first, then read")),
        tool("browser_eval_js",
            "Run a JavaScript expression in the agent's off-screen page and " +
            "return its result. Wrap multi-statement code in a `(function(){ " +
            "...; return value; })()` IIFE so it has a return value. Useful for " +
            "querying DOM (e.g. `document.title`), reading state, or driving " +
            "the page when the helper tools below aren't enough.",
            params("script" to "string:JavaScript expression that evaluates to a value"),
            required = listOf("script")),
        tool("browser_fill_field",
            "Set the value of a form field in the agent's off-screen page by " +
            "CSS selector, dispatching `input` and `change` events so React/Vue " +
            "frameworks notice. Returns NOT_FOUND if the selector matches " +
            "nothing on the current page.",
            params("selector" to "string:CSS selector for the input (e.g. '#email', 'input[name=q]')",
                   "value" to "string:Value to set"),
            required = listOf("selector", "value")),
        tool("browser_click",
            "Click an element in the agent's off-screen page by CSS selector. " +
            "Falls back to a synthetic MouseEvent if the element doesn't expose " +
            "a native .click().",
            params("selector" to "string:CSS selector (e.g. 'button[type=submit]', 'a.next-page')"),
            required = listOf("selector")),
        tool("browser_scroll",
            "Scroll the agent's off-screen page to absolute pixel coordinates.",
            params("x" to "string:Horizontal scroll position in pixels (default 0)",
                   "y" to "string:Vertical scroll position in pixels (default 500)")),
        tool("browser_set_viewport",
            "Configure the off-screen browser's viewport size and User-Agent. " +
            "Pass `device` for a preset (desktop|laptop|tablet|mobile) or " +
            "explicit `width`/`height`/`user_agent`. Affects subsequent " +
            "browser_navigate calls — useful for forcing the desktop site of " +
            "a mobile-detecting page or vice versa.",
            params("device" to "string:Optional preset: desktop|laptop|tablet|mobile",
                   "width" to "string:Optional CSS pixel width",
                   "height" to "string:Optional CSS pixel height",
                   "user_agent" to "string:Optional UA override")),
        tool("browser_screenshot_region",
            "Capture a PNG of either a CSS-selector region or an explicit " +
            "(x,y,width,height) box from the current page and save it under " +
            "the workspace. Returns the saved path. Use after a browser_navigate.",
            params("save_as" to "string:Workspace path for the PNG (default: screenshots/region-<ts>.png)",
                   "selector" to "string:CSS selector — captures that element's bounding box",
                   "x" to "string:Optional left in CSS px (used when selector omitted)",
                   "y" to "string:Optional top in CSS px",
                   "width" to "string:Optional width in CSS px",
                   "height" to "string:Optional height in CSS px")),
        tool("browser_wait_for_selector",
            "Poll the off-screen page until at least one element matches " +
            "[selector] or the timeout expires. Use this to wait for " +
            "dynamic content (XHR/JS-rendered) before reading or clicking.",
            params("selector" to "string:CSS selector to wait for",
                   "timeout_ms" to "string:Optional timeout in ms (default 8000)"),
            required = listOf("selector")),
        tool("browser_get_text",
            "Return the visible innerText of the first element matching " +
            "[selector] (truncated at 2000 chars).",
            params("selector" to "string:CSS selector"),
            required = listOf("selector")),
        tool("browser_get_attribute",
            "Return an HTML attribute value (e.g. href, src, value, " +
            "data-*) from the first element matching [selector].",
            params("selector" to "string:CSS selector",
                   "attribute" to "string:Attribute name"),
            required = listOf("selector", "attribute")),
        tool("browser_list_links",
            "List up to [limit] anchor links on the current page as " +
            "`href<TAB>visible-text` lines. Useful for crawling.",
            params("limit" to "string:Optional cap (default 100)")),
        tool("browser_click_at",
            "Click at specific (x, y) screen coordinates in CSS pixels. " +
            "Use when CSS selectors are missing or unreliable.",
            params("x" to "integer:X coordinate", "y" to "integer:Y coordinate"),
            required = listOf("x", "y")),
        tool("browser_type",
            "Type text into the currently focused element. Usually called after " +
            "clicking an input field.",
            params("text" to "string:Text to type"),
            required = listOf("text")),
        tool("file_upload_to_browser",
            "Trigger the on-screen Browser tab's file-input chooser at " +
            "[selector] and tell the user which workspace file to pick. The " +
            "Browser tab routes the chooser to the workspace picker so the " +
            "agent's sandboxed file becomes the upload target.",
            params("selector" to "string:CSS selector for the <input type=file>",
                   "workspace_path" to "string:Path to the workspace file to upload"),
            required = listOf("selector", "workspace_path")),
        tool("plugin_create",
            "Scaffold a brand-new Forge plugin from inside the agent loop. " +
            "Generates a minimal entrypoint.py exposing one tool whose body is " +
            "[python_code] (or a friendly stub) and registers it immediately. " +
            "Use this when you need a capability that doesn't exist yet — the " +
            "new tool is callable on the next iteration of the loop.",
            params("id" to "string:Plugin id (lowercase, alphanumerics/underscores)",
                   "name" to "string:Display name",
                   "description" to "string:What the plugin does",
                   "tool_name" to "string:Tool name to expose (defaults to id)",
                   "tool_description" to "string:Description of the tool",
                   "python_code" to "string:Optional Python body for the tool — runs with `args: dict` in scope and must `return` a string"),
            required = listOf("id")),
        // ─── NEW: Temp / upload folder ────────────────────────────────────────
        tool("temp_list",
            "List all files in the temp/ and uploads/ workspace folders. These are for processing user-provided content.",
            params()),
        tool("temp_clear",
            "Clear all files from the temp/ folder to free up space.",
            params()),
        // ─── NEW: Mid-run user input ──────────────────────────────────────────
        tool("request_user_input",
            "Pause the current task and ask the user a clarifying question. The user's answer will be delivered as a tool result. Use when you need specific information to proceed.",
            params("question" to "string:The specific question to ask the user",
                   "context" to "string:Optional brief context explaining why this info is needed"),
            required = listOf("question")),
        // ─── NEW: Composio ────────────────────────────────────────────────────
        tool("composio_call",
            "Call a Composio action (e.g. GitHub, Google, Slack, Notion, Linear integrations). Requires a Composio API key stored in memory as 'composio_api_key' or passed directly.",
            params("action" to "string:Composio action name e.g. GITHUB_CREATE_AN_ISSUE",
                   "params" to "string:Action parameters as a JSON object string",
                   "entity_id" to "string:Composio entity ID (default: 'default')",
                   "api_key" to "string:Composio API key (optional if stored in memory)"),
            required = listOf("action")),
        // ─── NEW: Alarms ─────────────────────────────────────────────────────
        tool("alarm_set",
            "Schedule an exact system alarm. Payload runs when it fires (NOTIFY, RUN_TOOL, RUN_PYTHON, PROMPT_AGENT).",
            params("label" to "string:Human label",
                   "in_seconds" to "string:Fire in N seconds (use either this or at_millis)",
                   "at_millis" to "string:Absolute epoch millis trigger time",
                   "action" to "string:NOTIFY | RUN_TOOL | RUN_PYTHON | PROMPT_AGENT (default NOTIFY)",
                   "payload" to "string:For RUN_TOOL use 'tool_name|{json_args}'; for RUN_PYTHON, code; for PROMPT_AGENT, prompt",
                   "model" to "string:Optional model override (e.g. 'gpt-4o', 'claude-3-opus')",
                   "repeat_ms" to "string:Optional repeat interval in ms"),
            required = listOf("label")),
        tool("alarm_list", "List all scheduled alarms.", params()),
        tool("alarm_cancel", "Cancel a scheduled alarm by id.",
            params("id" to "string:Alarm id"), required = listOf("id")),
        // ─── NEW: Android device control (read-only + volume + launch) ───────
        tool("android_device_info", "Model, manufacturer, Android version, uptime.", params()),
        tool("android_battery", "Current battery percent, charging state, temperature.", params()),
        tool("android_volume", "Current volume levels for music/ring/notification/system.", params()),
        tool("android_set_volume",
            "Set a volume stream level in percent.",
            params("stream" to "string:music | ring | notification | system | alarm | voice_call",
                   "percent" to "string:0..100"),
            required = listOf("stream", "percent")),
        tool("android_network", "Active network type, IP, Wi-Fi SSID (if available).", params()),
        tool("android_storage", "Internal + external storage bytes free/total.", params()),
        tool("android_list_apps", "List installed apps (pkg + label).",
            params("user_only" to "string:true to exclude system apps (default true)",
                   "limit" to "string:Max results (default 200)")),
        tool("android_launch_app", "Launch an installed app by package name.",
            params("package" to "string:Package e.g. com.android.settings"),
            required = listOf("package")),
        tool("android_screen", "Screen size, density, orientation.", params()),
        tool("android_snapshot", "One-shot JSON blob combining device/battery/volume/network/storage/screen.", params()),
        // ─── NEW: Local HTTP server ──────────────────────────────────────────
        tool("server_start",
            "Start the local HTTP API server (foreground service). All tools are callable via POST /tool with Bearer key.",
            params("port" to "string:TCP port (default 8789)")),
        tool("server_stop", "Stop the local HTTP API server.", params()),
        tool("server_status", "Show server status, port, and key prefix.", params()),
        tool("server_rotate_key", "Rotate the Bearer API key.", params()),
        // ─── NEW: Doctor ─────────────────────────────────────────────────────
        tool("doctor_check", "Run diagnostic checks and report ok/fail for each subsystem.", params()),
        tool("doctor_fix", "Attempt an automated fix for a specific check id.",
            params("id" to "string:Check id from doctor_check"), required = listOf("id")),
        // ─── NEW: Channels ───────────────────────────────────────────────────
        tool("channel_list", "List configured messaging channels.", params()),
        tool("channel_send",
            "Send an outbound text message through a channel (Telegram). " +
            "Telegram messages are sent in the channel's configured parse_mode " +
            "(HTML by default — Markdown in `text` is auto-converted). NOTE: when " +
            "the agent is replying to an inbound chat message you do NOT need to " +
            "call this — the auto-reply pipeline sends your final assistant text " +
            "back to the chat for you. Only call channel_send to push an unsolicited " +
            "message to a different chat (e.g. a notification).",
            params("channel" to "string:Channel display name (case-insensitive)",
                   "channel_id" to "string:Channel id (alternative to channel)",
                   "to" to "string:Recipient id (chat_id for Telegram)",
                   "text" to "string:Message body (Markdown is OK in HTML mode)"),
            required = listOf("to", "text")),
        tool("telegram_send_voice",
            "Send an OGG/Opus voice note through a Telegram channel. `path` is " +
            "either a workspace-relative path (e.g. `downloads/note.ogg`) or an " +
            "absolute path. Optional `caption` is shown under the bubble.",
            params("channel" to "string:Channel display name (case-insensitive)",
                   "channel_id" to "string:Channel id (alternative to channel)",
                   "to" to "string:Recipient chat id",
                   "path" to "string:Workspace-relative or absolute audio file path",
                   "caption" to "string:Optional caption (HTML allowed)"),
            required = listOf("to", "path")),
        tool("telegram_send_file",
            "Send a photo, video, or generic document through a Telegram channel. " +
            "Automatically detects whether to use sendPhoto, sendVideo, or sendDocument " +
            "based on the file extension. `path` is workspace-relative or absolute.",
            params("channel" to "string:Channel display name (optional)",
                   "channel_id" to "string:Channel id (optional)",
                   "to" to "string:Recipient chat id",
                   "path" to "string:File path to send",
                   "caption" to "string:Optional caption (HTML allowed)"),
            required = listOf("to", "path")),
        tool("telegram_react",
            "Add a reaction (emoji like 👍, 🔥, ❤️) to a specific message in a " +
            "Telegram chat. `message_id` is required.",
            params("channel" to "string:Channel display name (optional)",
                   "channel_id" to "string:Channel id (optional)",
                   "to" to "string:Recipient chat id",
                   "message_id" to "string:The message id to react to",
                   "reaction" to "string:Emoji reaction (default 👍)"),
            required = listOf("to", "message_id")),
        tool("telegram_reply",
            "Reply to a specific message in a Telegram chat. Use this instead of " +
            "channel_send when you want to quote a user's question or message.",
            params("channel" to "string:Channel display name (optional)",
                   "channel_id" to "string:Channel id (optional)",
                   "to" to "string:Recipient chat id",
                   "reply_to_id" to "string:The message id to reply to",
                   "text" to "string:Reply body (Markdown OK)"),
            required = listOf("to", "reply_to_id", "text")),
        tool("telegram_get_history",
            "Return the recent conversation history (user + agent turns) for a " +
            "specific Telegram chat. Use this to recall what was said earlier in " +
            "a conversation when the context window no longer contains it.",
            params("chat_id"    to "string:Telegram chat id to query",
                   "limit"     to "integer:Max messages to return (1-80, default 20)",
                   "channel_id" to "string:Channel id (optional)",
                   "channel"    to "string:Channel display name (optional)"),
            required = listOf("chat_id")),
        tool("channel_toggle", "Enable or disable a channel.",
            params("id" to "string:Channel id", "enabled" to "string:true/false"),
            required = listOf("id")),
        tool("telegram_main_chat",
            "Return the chat id of the Telegram conversation that is currently " +
            "talking to you. Use this BEFORE channel_send / telegram_send_voice " +
            "when you need to reach 'this user / this chat' but don't have a chat " +
            "id in hand. Falls back to the most-recent inbound chat for the " +
            "channel, then to the channel's defaultChatId. With no args it picks " +
            "the most recently active Telegram channel.",
            params("channel_id" to "string:Specific channel id (optional)",
                   "channel"    to "string:Channel display name (optional)"),
            required = emptyList()),
        tool("telegram_list_chats",
            "List recent Telegram chats the agent has seen, most recent first. " +
            "Useful when the user says 'who's been messaging me' or you need to " +
            "pick a chat id to reply to.",
            params("channel_id" to "string:Restrict to one channel id (optional)",
                   "limit"      to "integer:Max chats to return (default 20)"),
            required = emptyList()),
        tool("telegram_get_allowed_chats",
            "Show the per-channel allow-list of Telegram chat ids. An empty list " +
            "means every chat that messages the bot is allowed.",
            params("channel_id" to "string:Specific channel id (optional)",
                   "channel"    to "string:Channel display name (optional)"),
            required = emptyList()),
        tool("telegram_set_allowed_chats",
            "Replace the allow-list for a Telegram channel with the given CSV. " +
            "Pass an empty string to clear it (= allow everyone).",
            params("chat_ids"   to "string:Comma-separated chat ids; empty = allow all",
                   "channel_id" to "string:Specific channel id (optional)",
                   "channel"    to "string:Channel display name (optional)"),
            required = listOf("chat_ids")),
        tool("telegram_allow_chat",
            "Append one chat id to the allow-list for a Telegram channel. Use " +
            "after telegram_main_chat / telegram_list_chats to lock the bot to " +
            "specific people.",
            params("chat_id"    to "string:Chat id to allow",
                   "channel_id" to "string:Specific channel id (optional)",
                   "channel"    to "string:Channel display name (optional)"),
            required = listOf("chat_id")),
        tool("telegram_deny_chat",
            "Remove one chat id from the allow-list for a Telegram channel. If " +
            "the list becomes empty the channel reverts to allow-all.",
            params("chat_id"    to "string:Chat id to remove",
                   "channel_id" to "string:Specific channel id (optional)",
                   "channel"    to "string:Channel display name (optional)"),
            required = listOf("chat_id")),
        tool("app_describe",
            "Describe the host app (Forge OS): what it is, what it can do, what " +
            "tools you have, and how external programs can drive you via the " +
            "built-in HTTP API and the bound AIDL service. Call this when the " +
            "user asks 'what is this app?', 'what can you do?', 'do you have an " +
            "API?', or whenever you need to remind yourself of the environment.",
            params(),
            required = emptyList()),
        tool("channel_add_telegram", "Register a new Telegram bot channel.",
            params("name" to "string:Display name",
                   "bot_token" to "string:Telegram bot token from @BotFather",
                   "default_chat_id" to "string:Optional default chat id"),
            required = listOf("bot_token")),
        // ─── Phase Q: Agent Control Plane ────────────────────────────────────
        tool("control_list",
            "List every toggleable agent capability with its current ON/OFF state, " +
            "category, and whether changing it requires explicit user consent.", params()),
        tool("control_describe",
            "Show the full description, category, and risk level of one capability.",
            params("id" to "string:Capability id (see control_list)"),
            required = listOf("id")),
        tool("control_set",
            "Turn a capability ON or OFF. The agent itself can only flip a " +
            "consent-required capability if there is a fresh user grant for it.",
            params("id" to "string:Capability id",
                   "enabled" to "string:true | false"),
            required = listOf("id", "enabled")),
        tool("control_grant",
            "Record a user consent grant for a capability. This is normally " +
            "called from the chat parser or settings UI when the user explicitly " +
            "tells Forge to take control of something. Optional ttl_seconds.",
            params("id" to "string:Capability id",
                   "ttl_seconds" to "string:Grant TTL in seconds (0 = permanent)",
                   "note" to "string:Optional note describing why the grant was given"),
            required = listOf("id")),
        tool("control_revoke",
            "Revoke an existing consent grant.",
            params("id" to "string:Capability id"),
            required = listOf("id")),
        tool("control_status",
            "Render a human-readable summary of the entire control plane.", params()),
        // ─── Phase Q: Plugin persistence ─────────────────────────────────────
        tool("plugin_export_all",
            "Export every installed user plugin to the upgrade-survivable folder " +
            "so they can be restored after uninstall + reinstall.", params()),
        tool("plugin_export_list",
            "List the .fp bundles currently sitting in the export folder.", params()),
        tool("plugin_restore_missing",
            "Reinstall any exported plugin that is not currently present on disk. " +
            "Runs automatically on app start; this tool re-runs it on demand.", params()),
        // ─── Phase Q: Notifications with actions ─────────────────────────────
        tool("notify_send",
            "Post a notification with up to three clickable action buttons that " +
            "route back into the agent (tool_call / chat_message / open_screen).",
            params("title" to "string:Notification title",
                   "body" to "string:Body text",
                   "channel" to "string:Channel id (default forge_agent)",
                   "actions_json" to "string:JSON array of {label, kind, payload_json}"),
            required = listOf("title", "body")),
        // ─── Phase Q: Web screenshot ─────────────────────────────────────────
        tool("web_screenshot",
            "Render a URL in an offscreen WebView and save the result as a PNG " +
            "under the workspace. Returns the saved file path.",
            params("url" to "string:URL to render",
                   "out_path" to "string:Workspace-relative output path (default screenshots/<ts>.png)",
                   "width" to "string:Viewport width px (default 1280)",
                   "height" to "string:Viewport height px (default 1600)",
                   "full_page" to "string:true to capture full content height (default true)",
                   "wait_ms" to "string:Settling delay after onPageFinished (default 1500)"),
            required = listOf("url")),
        // ─── Phase Q: Browser history & sessions ─────────────────────────────
        tool("browser_history_list",
            "List recent in-app browser navigations. Optional session_id filter " +
            "(\"user\", \"agent\", or any custom id).",
            params("session_id" to "string:Optional session filter",
                   "limit" to "string:Max entries (default 100)")),
        tool("browser_history_clear",
            "Clear the in-app browser history. Without session_id, clears everything; " +
            "otherwise clears only that session.",
            params("session_id" to "string:Optional session id to clear")),
        tool("browser_session_new",
            "Record a marker entry that begins a new browser session id (the next " +
            "navigations under that id can be filtered separately).",
            params("session_id" to "string:Session id (e.g. 'agent-research-2')",
                   "note" to "string:Optional human note")),
        // ─── Phase Q: LAN project server ─────────────────────────────────────
        tool("project_serve",
            "Start a small static HTTP server bound to the device's Wi-Fi IP and " +
            "expose a workspace folder over LAN. Returns a `http://<lan-ip>:<port>/` " +
            "URL other devices on the same Wi-Fi can browse to. " +
            "WHEN TO USE: the user says 'serve', 'host', 'preview in browser', " +
            "'open this on my laptop', 'share this folder', or anything that " +
            "implies running an HTTP server for a folder. Default to serving the " +
            "folder containing the most recently written project (e.g. " +
            "`projects/<name>`). If the user just says 'serve the project', call " +
            "file_list first to find it. Use port=0 to let the OS pick a free port. " +
            "Stop the server with project_unserve when done.",
            params("path" to "string:Workspace-relative folder to serve, e.g. 'projects/site'",
                   "port" to "string:TCP port (0 = auto-pick, default 0)"),
            required = listOf("path")),
        tool("project_unserve",
            "Stop a running project server by id (from project_serve_list).",
            params("id" to "string:Server id"),
            required = listOf("id")),
        tool("project_serve_list",
            "List all currently running project servers (id, port, root, URL).", params()),
        // ─── Phase Q: Proactive ──────────────────────────────────────────────
        tool("proactive_status",
            "Show whether the proactive scheduler is enabled and whether the " +
            "underlying capability is ON.", params()),
        tool("proactive_schedule",
            "(Re-)enqueue the proactive worker. Runs every ~30 minutes; the worker " +
            "is a no-op while the proactive_suggestions capability is OFF.", params()),
        tool("proactive_cancel",
            "Cancel the periodic proactive worker.", params()),
        // ─── Phase S: Git ──────────────────────────────────────────────────────
        tool("git_init",
            "Initialise a new git repo at the given workspace path. Idempotent — " +
            "safe to call on a folder that already has .git.",
            params("path" to "string:Workspace-relative repo dir, e.g. 'projects/myapp'")),
        tool("git_status",
            "Show staged, modified and untracked files (porcelain-style summary).",
            params("path" to "string:Workspace-relative repo dir")),
        tool("git_add",
            "Stage files matching a pattern (default '.' = everything).",
            params("path" to "string:Workspace-relative repo dir",
                   "pattern" to "string:Pathspec to add (default '.')"),
            required = listOf("path")),
        tool("git_commit",
            "Create a commit. Always pass `message`. Optional author/email default to " +
            "'Forge Agent <agent@forge.local>'.",
            params("path" to "string:Workspace-relative repo dir",
                   "message" to "string:Commit message",
                   "author" to "string:Author name (optional)",
                   "email" to "string:Author email (optional)"),
            required = listOf("path", "message")),
        tool("git_log",
            "Last N commits (default 20). Shows hash, author, date, summary.",
            params("path" to "string:Workspace-relative repo dir",
                   "limit" to "integer:Max commits to return")),
        tool("git_diff",
            "Diff against HEAD. Pass `file` for a single-file diff, omit for full diff.",
            params("path" to "string:Workspace-relative repo dir",
                   "file" to "string:Optional file to diff")),
        tool("git_branch",
            "List branches, or create a new one when `name` is given.",
            params("path" to "string:Workspace-relative repo dir",
                   "name" to "string:New branch name (optional — list-only when omitted)")),
        tool("git_checkout",
            "Check out an existing branch, or create one if `create=true`.",
            params("path" to "string:Workspace-relative repo dir",
                   "name" to "string:Branch name",
                   "create" to "boolean:Create the branch if it does not exist"),
            required = listOf("path", "name")),
        tool("git_remote_set",
            "Set or replace a remote URL (default name='origin').",
            params("path" to "string:Workspace-relative repo dir",
                   "url" to "string:Remote URL (https or ssh)",
                   "name" to "string:Remote name (default 'origin')"),
            required = listOf("path", "url")),
        tool("git_clone",
            "Clone a repo into a workspace path. For private repos pass `token` " +
            "(personal access token) or store one at memory key " +
            "'git_credentials/<host>'.",
            params("url" to "string:Repository URL",
                   "dest" to "string:Workspace-relative destination dir",
                   "token" to "string:Optional PAT for private repos"),
            required = listOf("url", "dest")),
        tool("git_push",
            "Push the current branch to a remote. For private repos, use `secret_request` " +
            "to authenticate with a GitHub PAT (Personal Access Token). WORKFLOW: " +
            "1) Call `secret_list` to see registered secrets. 2) If no GitHub PAT is registered, " +
            "tell the user to add one in Settings → Custom API Keys (name: 'github_pat', " +
            "auth style: 'bearer'). 3) Use `secret_request {name: 'github_pat', url: 'https://api.github.com/user', " +
            "method: 'GET'}` to verify the token works. 4) Pass the token to git_push via the `token` parameter. " +
            "Alternatively, store credentials in memory via `memory_store_secret` for reuse.",
            params("path" to "string:Workspace-relative repo dir",
                   "remote" to "string:Remote name (default 'origin')",
                   "branch" to "string:Branch (default = current)",
                   "token" to "string:Optional PAT (GitHub, GitLab, etc.)")),
        tool("git_pull",
            "Pull updates from a remote. Same token resolution as git_push.",
            params("path" to "string:Workspace-relative repo dir",
                   "remote" to "string:Remote name (default 'origin')",
                   "branch" to "string:Branch (default = current)",
                   "token" to "string:Optional PAT")),
        // ─── Phase S: Downloads ────────────────────────────────────────────────
        tool("file_download",
            "Stream a URL to disk inside the workspace. Honours blocked-host and " +
            "blocked-extension lists. Returns the relative path, byte count, sniffed " +
            "MIME and SHA-256. If `save_as` is omitted, the file lands in 'downloads/'.",
            params("url" to "string:URL to download",
                   "save_as" to "string:Workspace-relative destination (optional)",
                   "headers" to "object:Optional request headers as a JSON object",
                   "max_bytes" to "integer:Cap on bytes written (default 200 MiB)"),
            required = listOf("url")),
        tool("browser_download",
            "Like file_download, but reuses the headless browser's cookie jar so " +
            "authenticated downloads work after browser_navigate. Use this when the " +
            "asset lives behind a login.",
            params("url" to "string:URL to download (typically a direct asset link)",
                   "save_as" to "string:Workspace-relative destination (optional)",
                   "max_bytes" to "integer:Cap on bytes written (default 200 MiB)"),
            required = listOf("url")),
        // ─── Phase 3: Message Bus ──────────────────────────────────────────────
        tool("message_bus_publish",
            "Publish a message to a named topic on the agent message bus. " +
            "Other sub-agents or the orchestrator can read these messages. " +
            "Use this for multi-agent collaboration — e.g. agent A publishes " +
            "research findings, agent B reads them to build a report.",
            params("topic" to "string:Topic name (e.g. 'research_findings')",
                   "message" to "string:Message content to publish",
                   "sender_id" to "string:Sender identifier (default 'agent')"),
            required = listOf("topic", "message")),
        tool("message_bus_read",
            "Read messages from a named topic on the agent message bus. " +
            "Returns the latest messages (up to limit) published to that topic.",
            params("topic" to "string:Topic name to read from",
                   "limit" to "string:Max messages to return (default 20)"),
            required = listOf("topic")),
        tool("message_bus_topics",
            "List all active topics on the agent message bus with message counts.",
            params()),
        // ─── Phase 3: Hybrid Execution ─────────────────────────────────────────
        tool("python_run_remote",
            "Execute a Python script on the configured remote GPU worker instead " +
            "of running locally via Chaquopy. Useful for heavy ML workloads " +
            "(torch, tensorflow, transformers, etc.). Requires " +
            "hybridExecution.remotePythonWorkerUrl to be configured in Settings. " +
            "If no remote worker is configured, this falls back to local python_run.",
            params("code" to "string:Python source code to execute",
                   "timeout" to "string:Execution timeout in seconds (default 120)"),
            required = listOf("code")),
        tool("telegram_send_file",
            "Send a file, photo, or video to a Telegram chat.",
            params("to" to "string:Chat or user ID",
                   "path" to "string:Workspace-relative path to the file",
                   "caption" to "string:Optional caption",
                   "channel" to "string:Optional channel display name"),
            required = listOf("to", "path")),
        // ─── Phase 3: Backup & Recovery ───────────────────────────────────────
        tool("system_backup_export",
            "Create a full encrypted ZIP backup of the entire Forge OS environment " +
            "(all projects, all memory, all snapshots). The ZIP is saved to the " +
            "'exports/' folder. Returns the file path.",
            params("filename" to "string:Optional custom backup name")),
        tool("system_backup_import",
            "Restore the entire Forge OS environment from a backup ZIP. WARNING: " +
            "This wipes the current workspace and replaces it with the backup content.",
            params("path" to "string:Workspace-relative path to the backup ZIP file"),
            required = listOf("path")),
        // ─── Phase 2: Project Python Execution ─────────────────────────────────
        tool("project_python_run_file",
            "Execute a Python file from a project. The script runs in the project's " +
            "context with access to all pre-installed packages. If the project " +
            "requires packages that are not pre-installed, you'll get a helpful " +
            "error message with instructions to add them to build.gradle and rebuild.",
            params("slug" to "string:Project slug",
                   "script_path" to "string:Path to Python file relative to project root (e.g. 'main.py')",
                   "args" to "array:Optional command-line arguments to pass to the script"),
            required = listOf("slug", "script_path")),
        tool("project_python_run_code",
            "Execute Python code directly within a project context. The code runs " +
            "with access to all pre-installed packages and the project directory " +
            "in sys.path. Use this for quick Python snippets or testing.",
            params("slug" to "string:Project slug",
                   "code" to "string:Python source code to execute",
                   "args" to "array:Optional command-line arguments (accessible via sys.argv)"),
            required = listOf("slug", "code")),
        // ─── Task 4: Agent Learning & Personalization ──────────────────────
        tool("reflection_get_context",
            "Retrieve context from past executions and learned patterns. Use this " +
            "when starting a new task to understand what similar tasks were done " +
            "before and what patterns the agent has learned. Returns previous " +
            "session context, similar past executions, and applicable patterns.",
            params("goal" to "string:Current task goal (used to find similar past executions)",
                   "limit" to "string:Max similar executions to return (default 5)"),
            required = listOf("goal")),
        tool("history_show",
            "Display the execution history of the current session. Shows all steps " +
            "taken so far, their success/failure status, and any errors. Use this " +
            "when the user says 'continue' after a failure to understand what " +
            "happened and where to resume.",
            params("session_id" to "string:Optional specific session id (default: current session)")),
        tool("personality_update",
            "Update the agent's personality configuration. Allows customizing the " +
            "agent's name, system prompt, traits, communication style, and custom " +
            "instructions. Changes take effect immediately.",
            params("name" to "string:Agent name (e.g. 'Forge', 'Claude')",
                   "system_prompt" to "string:Custom system prompt describing how the agent should behave",
                   "traits" to "string:Comma-separated personality traits (e.g. 'helpful,direct,technical')",
                   "communication_style" to "string:How the agent should communicate (e.g. 'concise,technical,warm')",
                   "custom_instructions" to "string:Additional custom instructions for this agent")),
        tool("personality_list",
            "List all saved personality profiles. Shows which one is currently active. " +
            "Use this before personality_switch to see what profiles are available.",
            params()),
        tool("personality_save",
            "Save the current personality as a named profile so it can be switched " +
            "back to later. Use this before switching to a new personality.",
            params("name" to "string:Profile name (e.g. 'Work Mode', 'Creative', 'Formal')"),
            required = listOf("name")),
        tool("personality_switch",
            "Switch to a previously saved personality profile. The new personality " +
            "takes effect on the NEXT message. Use personality_list first to see " +
            "available profiles. Use personality_save to save the current one before switching.",
            params("name" to "string:Profile name to switch to (from personality_list)"),
            required = listOf("name")),
        tool("personality_reset",
            "Reset the active personality back to the built-in Forge defaults. " +
            "Use this when the user says 'reset personality', 'go back to default', " +
            "'restore default personality', or similar. Saved profiles are not affected.",
            params()),
        tool("preferences_show",
            "Display the user's saved preferences including UI settings, remembered " +
            "projects, interaction patterns, and custom shortcuts. Use this to " +
            "understand what the user has configured.",
            params()),
        // ─── Wishlist Features ─────────────────────────────────────────────────
        // Feature 2: Voice Input
        tool("voice_start_listening",
            "Start listening for voice input using Android's speech recognition. " +
            "Perfect for hands-free control while cooking, driving, or coding. " +
            "The agent will receive voice commands that can be parsed and executed.",
            params()),
        tool("voice_stop_listening",
            "Stop listening for voice input.",
            params()),
        tool("voice_speak",
            "Speak text using Android's text-to-speech engine. Use this to provide " +
            "audio feedback to the user.",
            params("text" to "string:Text to speak"),
            required = listOf("text")),
        // Feature 4: Multi-Device Sync
        tool("sync_export",
            "Export a sync package containing projects, memory, config, and preferences. " +
            "The package can be imported on another device for seamless workflow. " +
            "Supports selective sync - choose what to include.",
            params("include_config" to "string:Include config (default true)",
                   "include_projects" to "string:Include projects metadata (default true)",
                   "include_memory" to "string:Include memory metadata (default true)",
                   "include_preferences" to "string:Include user preferences (default true)")),
        tool("sync_import",
            "Import a sync package from another device. Merges the synced data with " +
            "current device state. Use this to sync flashcard progress, projects, " +
            "and preferences between phone and laptop.",
            params("path" to "string:Workspace-relative path to sync package JSON file",
                   "include_config" to "string:Import config (default true)",
                   "include_projects" to "string:Import projects (default true)",
                   "include_memory" to "string:Import memory (default true)",
                   "include_preferences" to "string:Import preferences (default true)"),
            required = listOf("path")),
        tool("sync_init_device",
            "Initialize this device for multi-device sync. Sets device name and ID " +
            "for tracking sync operations.",
            params("device_name" to "string:Human-readable device name (e.g. 'My Phone', 'Laptop')",
                   "device_id" to "string:Optional unique device ID (auto-generated if omitted)"),
            required = listOf("device_name")),
        // Feature 5: AI-Powered Code Review
        tool("code_review_project",
            "Review all code files in a project and suggest improvements. Checks for " +
            "code quality, security issues, performance problems, and documentation. " +
            "Elevates project quality without manual effort.",
            params("slug" to "string:Project slug",
                   "check_quality" to "string:Check code quality (default true)",
                   "check_security" to "string:Check security issues (default true)",
                   "check_performance" to "string:Check performance (default true)",
                   "check_documentation" to "string:Check documentation (default true)",
                   "use_ai" to "string:Use AI-powered review (default false)"),
            required = listOf("slug")),
        tool("code_review_file",
            "Review a single code file and suggest improvements. Returns detailed " +
            "issues with line numbers and suggestions.",
            params("path" to "string:Workspace-relative path to file",
                   "check_quality" to "string:Check code quality (default true)",
                   "check_security" to "string:Check security issues (default true)",
                   "check_performance" to "string:Check performance (default true)",
                   "check_documentation" to "string:Check documentation (default true)",
                   "use_ai" to "string:Use AI-powered review (default false)"),
            required = listOf("path")),
        // Feature 7: Project Health Dashboard
        tool("project_health",
            "Get comprehensive health status for a project including test results, " +
            "build status, git commits, code quality score, dependencies, and memory usage. " +
            "Quick overview of what's working and what's not.",
            params("slug" to "string:Project slug"),
            required = listOf("slug")),
        tool("project_health_all",
            "Get health status for all projects. Shows a summary dashboard of all " +
            "projects with their status, test results, and code quality scores.",
            params()),
    )

    private fun humanAgo(ms: Long): String {
        val mins = ms / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }

}
