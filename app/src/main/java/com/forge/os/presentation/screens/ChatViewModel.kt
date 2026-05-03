package com.forge.os.presentation.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiError
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.api.CostMeter
import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.data.conversations.StoredConversation
import com.forge.os.data.conversations.toApi
import com.forge.os.data.conversations.toStored
import com.forge.os.data.conversations.toUi
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ExecutionPlanner
import com.forge.os.domain.agent.InputRoute
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.UserInputBroker
import com.forge.os.domain.agent.SkillRecorder
import com.forge.os.domain.agents.DelegationManager
import com.forge.os.domain.config.ConfigMutationEngine
import com.forge.os.domain.config.ConfigMutationResult
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.domain.security.PermissionManager
import com.forge.os.domain.security.ProviderSpec
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val toolName: String? = null,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val errorDetail: ApiError? = null
)

/** A mid-run clarification request from the agent to the user. */
data class InputRequest(val question: String, val requestId: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val reActAgent: ReActAgent,
    private val configRepository: ConfigRepository,
    private val configMutationEngine: ConfigMutationEngine,
    private val permissionManager: PermissionManager,
    private val secureKeyStore: SecureKeyStore,
    private val memoryManager: MemoryManager,
    private val cronManager: CronManager,
    private val pluginManager: PluginManager,
    private val delegationManager: DelegationManager,
    private val apiManager: AiApiManager,
    private val costMeter: CostMeter,
    private val conversationRepo: ConversationRepository,
    private val skillRecorder: SkillRecorder,
    private val userInputBroker: UserInputBroker,
    private val hapticManager: com.forge.os.domain.haptic.HapticFeedbackManager,
    heartbeatMonitor: HeartbeatMonitor
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _availableSpecs = MutableStateFlow<List<ProviderSpec>>(emptyList())
    val availableSpecs: StateFlow<List<ProviderSpec>> = _availableSpecs

    private val _selectedSpec = MutableStateFlow<ProviderSpec?>(null)
    val selectedSpec: StateFlow<ProviderSpec?> = _selectedSpec

    private val _autoRoute = MutableStateFlow(true)
    val autoRoute: StateFlow<Boolean> = _autoRoute

    val costSnapshot = costMeter.snapshot
    val systemStatus = heartbeatMonitor.status

    /** Non-null when the agent is paused waiting for user input mid-run. */
    private val _pendingInputRequest = MutableStateFlow<InputRequest?>(null)
    val pendingInputRequest: StateFlow<InputRequest?> = _pendingInputRequest
    
    /** Phase 3 — non-null when the agent is paused at the budget gate. */
    private val _pendingCostApproval = MutableStateFlow<ExecutionPlanner.CostEstimate?>(null)
    val pendingCostApproval: StateFlow<ExecutionPlanner.CostEstimate?> = _pendingCostApproval


    private val apiHistory = mutableListOf<ApiMessage>()
    private var currentConversation: StoredConversation = conversationRepo.loadOrCreateCurrent()

    /** Messages typed while the agent is busy. Drained FIFO when the
     *  current run completes — replaces the earlier "silent drop" that
     *  cancelled or ignored the user's input mid-turn. */
    private val pendingSends = ArrayDeque<String>()

    init {
        refreshAvailableSpecs()
        // Listen for mid-run input requests from the agent. We only react
        // to questions destined for the in-app chat ("ui" route) — Telegram
        // and other channels handle their own routes via ChannelManager.
        viewModelScope.launch {
            userInputBroker.questions.collect { q ->
                if (q.routeKey != InputRoute.UI) return@collect
                val requestId = "ireq_${System.currentTimeMillis()}"
                _pendingInputRequest.value = InputRequest(q.question, requestId)
            }
        }

        viewModelScope.launch {
            conversationRepo.currentIdFlow.drop(1).collect { id ->
                if (id != null && id != currentConversation.id) reloadCurrent()
            }
        }

        val restored = currentConversation.messages.map { it.toUi() }
        apiHistory += currentConversation.apiHistory.map { it.toApi() }

        if (restored.isNotEmpty()) {
            _messages.value = restored
            currentConversation.lastModel?.let { savedModel ->
                _availableSpecs.value
                    .firstOrNull {
                        it.effectiveModel == savedModel &&
                            (currentConversation.lastProviderName == null ||
                             it.displayLabel.contains(currentConversation.lastProviderName!!, ignoreCase = true))
                    }
                    ?.let { _selectedSpec.value = it; _autoRoute.value = false }
            }
        } else {
            val identity = configRepository.get().agentIdentity
            val hasKey = secureKeyStore.getActiveProvider() != null
            val keyNote = if (!hasKey) "\n\n⚠️ No API key found. Tap ⚙ Settings to add one." else ""
            val memorySummary = try {
                "\n\n" + memoryManager.fullSummary().lines().take(3).joinToString("\n")
            } catch (_: Exception) { "" }
            addMsg(ChatMessage(
                role = "system",
                content = "${identity.defaultGreeting}\n\nKernel v${configRepository.get().version} online.$keyNote$memorySummary"
            ))
            persistCurrent()
        }
    }

    fun refreshAvailableSpecs() {
        val quick = apiManager.availableSpecs()
        if (_availableSpecs.value.isEmpty()) _availableSpecs.value = quick
        if (_selectedSpec.value == null && quick.isNotEmpty()) _selectedSpec.value = quick.first()
        viewModelScope.launch {
            val expanded = runCatching { apiManager.availableSpecsExpanded() }.getOrNull()
            if (!expanded.isNullOrEmpty()) {
                _availableSpecs.value = expanded
                if (_selectedSpec.value == null) _selectedSpec.value = expanded.first()
            }
        }
    }

    fun selectSpec(spec: ProviderSpec) { _selectedSpec.value = spec; _autoRoute.value = false; persistCurrent() }
    fun setAutoRoute(enabled: Boolean) { _autoRoute.value = enabled; persistCurrent() }

    /** Called from the UI when the user submits their response to a mid-run input request. */
    fun submitInputResponse(response: String) {
        val req = _pendingInputRequest.value ?: return
        _pendingInputRequest.value = null
        addMsg(ChatMessage(role = "user", content = "↩ $response"))
        viewModelScope.launch { userInputBroker.submitResponse(InputRoute.UI, response) }
    }

    fun send(userText: String) {
        if (userText.isBlank()) return
        val input = userText.trim()
        // If the agent is mid-run, queue instead of cancelling or dropping
        // it. The current turn finishes first and then drains the queue.
        if (_isLoading.value) {
            pendingSends.addLast(input)
            addMsg(ChatMessage(
                role = "user",
                content = "$input\n\n⏳ queued — will run after the current turn finishes",
            ))
            return
        }

        // Handle slash commands
        if (input.startsWith("/")) {
            handleSlashCommand(input)
            return
        }

        addMsg(ChatMessage(role = "user", content = input))
        hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.LIGHT_TICK)
        skillRecorder.noteUserRequest(input)
        if (handleLocalCommand(input)) { persistCurrent(); return }

        val spec = if (_autoRoute.value) null else _selectedSpec.value

        viewModelScope.launch {
            _isLoading.value = true
            val streamId = java.util.UUID.randomUUID().toString()
            var streamBuffer = ""

            reActAgent.run(input, apiHistory.toList(), spec, currentChannel = "main").collect { event ->
                when (event) {
                    is AgentEvent.Thinking -> {
                        if (streamBuffer.isEmpty()) {
                            hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.THINKING_START)
                        }
                        streamBuffer = event.text
                        upsertMsg(ChatMessage(id = streamId, role = "assistant", content = streamBuffer, isStreaming = true))
                    }
                    is AgentEvent.ToolCall -> {
                        if (streamBuffer.isNotBlank()) {
                            upsertMsg(ChatMessage(id = streamId, role = "assistant", content = streamBuffer))
                            streamBuffer = ""
                        }
                        // request_user_input: just add a visual bubble; the agent suspends
                        // until the user responds via UserInputBroker
                        if (event.name == "request_user_input") {
                            val question = try {
                                val obj = kotlinx.serialization.json.Json.parseToJsonElement(event.args)
                                    .let { it as? kotlinx.serialization.json.JsonObject }
                                obj?.get("question")?.let {
                                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                                } ?: event.args
                            } catch (_: Exception) { event.args }
                            addMsg(ChatMessage(role = "input_request", content = question, toolName = "request_user_input"))
                        } else {
                            addMsg(ChatMessage(role = "tool_call", content = event.args, toolName = event.name))
                        }
                    }
                    is AgentEvent.ToolResult -> {
                        if (event.isError) {
                            hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.ERROR)
                        } else {
                            hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.SUCCESS)
                        }
                        addMsg(ChatMessage(role = "tool_result", content = event.result,
                            toolName = event.name, isError = event.isError))
                        val lastCall = _messages.value.lastOrNull {
                            it.role == "tool_call" && it.toolName == event.name
                        }
                        if (lastCall != null) {
                            skillRecorder.recordToolUsage(event.name, lastCall.content, event.isError)
                        }
                    }
                    is AgentEvent.Response -> {
                        upsertMsg(ChatMessage(id = streamId, role = "assistant", content = event.text, isStreaming = false))
                        apiHistory.add(ApiMessage(role = "user", content = input))
                        apiHistory.add(ApiMessage(role = "assistant", content = event.text))
                        while (apiHistory.size > 40) apiHistory.removeAt(0)
                    }
                    is AgentEvent.CostApprovalRequired -> {
                        _pendingCostApproval.value = event.estimate
                    }
                    is AgentEvent.Error -> {
                        _isLoading.value = false
                        hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.ERROR)
                        addMsg(ChatMessage(role = "assistant", content = event.message, isError = true, errorDetail = event.error))
                    }
                    is AgentEvent.Done -> { _isLoading.value = false }
                }
            }
            _isLoading.value = false
            _pendingInputRequest.value = null
            _pendingCostApproval.value = null
            persistCurrent()
            // Drain anything the user typed while we were busy (FIFO).
            val next = pendingSends.removeFirstOrNull()
            if (next != null) send(next)
        }
    }

    private fun handleSlashCommand(input: String) {
        val cmd = input.lowercase().split(" ").first()
        val args = input.removePrefix(cmd).trim()
        when (cmd) {
            "/help" -> { addMsg(ChatMessage(role = "assistant", content = buildHelpText())); persistCurrent() }
            "/clear" -> clearMessages()
            "/new" -> startNewConversation()
            "/config" -> {
                val c = configRepository.get()
                addMsg(ChatMessage(role = "assistant", content = """
⚙️ Config v${c.version}
• Agent: ${c.agentIdentity.name}
• Provider: ${c.modelRouting.defaultProvider} / ${c.modelRouting.defaultModel}
• Auto-confirm: ${c.behaviorRules.autoConfirmToolCalls}
• Max iterations: ${c.behaviorRules.maxIterations}
• Enabled tools: ${c.toolRegistry.enabledTools.size}
• Enable all tools: ${c.toolRegistry.enableAllTools}
                """.trimIndent()))
                persistCurrent()
            }
            "/memory", "/mem" -> { addMsg(ChatMessage(role = "assistant", content = memoryManager.fullSummary())); persistCurrent() }
            "/cron" -> {
                val jobs = cronManager.listJobs()
                val text = if (jobs.isEmpty()) "${cronManager.summary()}\n\nNo jobs scheduled."
                else cronManager.summary() + "\n\n" + jobs.joinToString("\n") { j ->
                    "${if (j.enabled) "●" else "○"} ${j.name} — ${j.schedule.pretty()} (next ${java.util.Date(j.nextRunAt)})"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); persistCurrent()
            }
            "/agents" -> {
                val all = delegationManager.listAll()
                val text = if (all.isEmpty()) "${delegationManager.summary()}\n\nNo sub-agents spawned yet."
                else delegationManager.summary() + "\n\n" + all.take(15).joinToString("\n") { a ->
                    "[${a.status}] ${a.id} d=${a.depth} — ${a.goal.take(80)}"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); persistCurrent()
            }
            "/plugins" -> {
                val plugins = pluginManager.listPlugins()
                val text = if (plugins.isEmpty()) "${pluginManager.summary()}\n\nNo plugins installed."
                else pluginManager.summary() + "\n\n" + plugins.joinToString("\n") { p ->
                    "${if (p.enabled) "●" else "○"} ${p.id} v${p.version} — ${p.name}"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); persistCurrent()
            }
            "/cost", "/spending" -> {
                val s = costSnapshot.value
                addMsg(ChatMessage(role = "assistant", content = """
💰 Cost meter
• Last call: ${"%.4f".format(s.lastCallUsd)} USD (in ${s.lastInputTokens} / out ${s.lastOutputTokens} tok)
• Session: ${"%.4f".format(s.sessionUsd)} USD across ${s.sessionCalls} calls
• Lifetime: ${"%.4f".format(s.lifetimeUsd)} USD across ${s.callCount} calls
                """.trimIndent())); persistCurrent()
            }
            "/history" -> {
                val history = cronManager.recentHistory(limit = 10)
                val text = if (history.isEmpty()) "No cron executions recorded yet."
                else history.joinToString("\n") {
                    "${if (it.success) "✓" else "✗"} ${java.util.Date(it.startedAt)} ${it.jobName} (${it.durationMs}ms)"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); persistCurrent()
            }
            "/tools" -> {
                val c = configRepository.get()
                val enabled = c.toolRegistry.enabledTools
                addMsg(ChatMessage(role = "assistant", content = buildString {
                    appendLine("🔧 Tools (${enabled.size} enabled, enableAllTools=${c.toolRegistry.enableAllTools}):")
                    enabled.forEach { appendLine("• $it") }
                    appendLine("\nUse 'enable tool X' / 'disable tool X' to manage, or toggle enableAllTools in config.")
                })); persistCurrent()
            }
            "/upload" -> {
                addMsg(ChatMessage(role = "assistant", content = """
📁 Upload / Temp Folder
Agents can read and write files to these workspace paths:
• temp/     — temporary working files (cleared by agent or user)
• uploads/  — user-uploaded files for processing

Use file_write to create files, file_read to read them.
The agent sees these as relative paths: e.g., 'temp/myfile.txt'

Tip: Use snapshot_create before processing large uploads.
                """.trimIndent())); persistCurrent()
            }
            else -> {
                // Try as regular message without the slash prefix
                send(input.removePrefix("/"))
            }
        }
    }

    fun approveCost() {
        val est = _pendingCostApproval.value ?: return
        _pendingCostApproval.value = null
        viewModelScope.launch {
            userInputBroker.submitResponse(InputRoute.UI, "approve")
        }
    }

    fun rejectCost() {
        _pendingCostApproval.value = null
        viewModelScope.launch {
            userInputBroker.submitResponse(InputRoute.UI, "reject")
        }
    }

    private fun handleLocalCommand(input: String): Boolean {
        val lower = input.lowercase()
        return when {
            lower == "help" || lower == "?" -> {
                addMsg(ChatMessage(role = "assistant", content = buildHelpText())); true
            }
            lower.contains("show config") || lower.contains("current config") -> {
                val c = configRepository.get()
                addMsg(ChatMessage(role = "assistant", content = """
⚙️ Config v${c.version}
• Agent: ${c.agentIdentity.name}
• Provider: ${c.modelRouting.defaultProvider} / ${c.modelRouting.defaultModel}
• Fallback: ${c.modelRouting.fallbackProvider} / ${c.modelRouting.fallbackModel}
• Auto-confirm: ${c.behaviorRules.autoConfirmToolCalls}
• Max iterations: ${c.behaviorRules.maxIterations}
• Enabled tools: ${c.toolRegistry.enabledTools.size}
• Enable all tools: ${c.toolRegistry.enableAllTools}
                """.trimIndent())); true
            }
            lower.contains("memory status") || lower.contains("memory summary") -> {
                addMsg(ChatMessage(role = "assistant", content = memoryManager.fullSummary())); true
            }
            lower == "cron status" || lower == "cron summary" || lower == "list jobs" -> {
                val jobs = cronManager.listJobs()
                val text = if (jobs.isEmpty()) "${cronManager.summary()}\n\nNo jobs scheduled."
                else cronManager.summary() + "\n\n" + jobs.joinToString("\n") { j ->
                    "${if (j.enabled) "●" else "○"} ${j.name} — ${j.schedule.pretty()} (next ${java.util.Date(j.nextRunAt)})"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); true
            }
            lower == "agents" || lower == "agents list" || lower == "list agents" -> {
                val all = delegationManager.listAll()
                val text = if (all.isEmpty()) "${delegationManager.summary()}\n\nNo sub-agents spawned yet."
                else delegationManager.summary() + "\n\n" + all.take(15).joinToString("\n") { a ->
                    "[${a.status}] ${a.id} d=${a.depth} — ${a.goal.take(80)}"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); true
            }
            lower == "plugin list" || lower == "plugins" || lower == "list plugins" -> {
                val plugins = pluginManager.listPlugins()
                val text = if (plugins.isEmpty()) "${pluginManager.summary()}\n\nNo plugins installed."
                else pluginManager.summary() + "\n\n" + plugins.joinToString("\n") { p ->
                    "${if (p.enabled) "●" else "○"} ${p.name} v${p.version} (${p.source}) — ${p.tools.size} tools"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); true
            }
            lower == "cron history" -> {
                val history = cronManager.recentHistory(limit = 10)
                val text = if (history.isEmpty()) "No cron executions recorded yet."
                else history.joinToString("\n") {
                    "${if (it.success) "✓" else "✗"} ${java.util.Date(it.startedAt)} ${it.jobName} (${it.durationMs}ms)"
                }
                addMsg(ChatMessage(role = "assistant", content = text)); true
            }
            lower.contains("what can") || lower.contains("permission") -> {
                addMsg(ChatMessage(role = "assistant", content = permissionManager.getPermissionSummary())); true
            }
            lower.startsWith("rollback config to") -> {
                val version = lower.removePrefix("rollback config to").trim()
                viewModelScope.launch {
                    val result = try {
                        configMutationEngine.rollbackToVersion(version); "✅ Config rolled back to v$version"
                    } catch (e: Exception) { "❌ ${e.message}" }
                    addMsg(ChatMessage(role = "assistant", content = result))
                }
                true
            }
            lower == "clear" -> { clearMessages(); true }
            lower == "new chat" || lower == "new conversation" -> { startNewConversation(); true }
            lower == "cost" || lower == "spending" || lower == "tokens" -> {
                val s = costSnapshot.value
                addMsg(ChatMessage(role = "assistant", content = """
💰 Cost meter
• Last call: ${"%.4f".format(s.lastCallUsd)} USD (in ${s.lastInputTokens} / out ${s.lastOutputTokens} tok)
• Session: ${"%.4f".format(s.sessionUsd)} USD across ${s.sessionCalls} calls
• Lifetime: ${"%.4f".format(s.lifetimeUsd)} USD across ${s.callCount} calls
                """.trimIndent())); true
            }
            lower.startsWith("save skill ") -> {
                val name = input.removePrefix("save skill").trim().ifBlank { "skill_${System.currentTimeMillis()}" }
                val saved = skillRecorder.commit(name)
                val msg = if (saved != null) "✅ Saved skill `$name` — $saved"
                          else "ℹ️ No recent successful Python/shell call to capture."
                addMsg(ChatMessage(role = "assistant", content = msg)); true
            }
            // NOTE: configuration requests like "change agent name to X",
            // "disable tool foo", "set max iterations to 25", etc. used to
            // be intercepted here and routed straight to ConfigMutationEngine.
            // That bypassed the LLM, missed any phrasing the brittle keyword
            // check didn't recognize, and made the agent appear to "ignore"
            // the request. We now let the agent handle these messages itself
            // — it has a `config_write` tool whose description tells it
            // exactly when to call it.
            else -> false
        }
    }

    private fun buildHelpText() = """
🛠 Forge OS

AGENT (uses your API key):
  Just type any task — Forge will reason, remember, schedule, delegate, and act.

SLASH COMMANDS (instant, no API):
  /help    /clear    /new      /config   /memory
  /cron    /agents   /plugins  /cost     /history
  /tools   /upload

CONFIG (chat-driven):
  change agent name to X
  disable/enable tool X
  rollback config to 1.0.2

MODEL PICKER: tap the model chip to switch providers.
BROWSER: tap 🌐 in the header to open the in-app browser.
SETTINGS: tap ⚙ to add API keys & custom endpoints.
    """.trimIndent()

    private fun addMsg(msg: ChatMessage) { _messages.value = _messages.value + msg }

    private fun upsertMsg(msg: ChatMessage) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfLast { it.id == msg.id }
        if (idx >= 0) current[idx] = msg else current.add(msg)
        _messages.value = current
    }

    fun clearMessages() {
        _messages.value = emptyList()
        apiHistory.clear()
        val identity = configRepository.get().agentIdentity
        addMsg(ChatMessage(role = "system", content = identity.defaultGreeting))
        persistCurrent()
    }

    fun startNewConversation() {
        currentConversation = conversationRepo.newConversation()
        _messages.value = emptyList()
        apiHistory.clear()
        val identity = configRepository.get().agentIdentity
        addMsg(ChatMessage(role = "system", content = "${identity.defaultGreeting}\n\n(new conversation started)"))
        persistCurrent()
    }

    fun reloadCurrent() {
        currentConversation = conversationRepo.loadOrCreateCurrent()
        apiHistory.clear()
        apiHistory += currentConversation.apiHistory.map { it.toApi() }
        _messages.value = currentConversation.messages.map { it.toUi() }.ifEmpty {
            val identity = configRepository.get().agentIdentity
            listOf(ChatMessage(role = "system", content = identity.defaultGreeting))
        }
        currentConversation.lastModel?.let { savedModel ->
            _availableSpecs.value.firstOrNull { it.effectiveModel == savedModel }?.let {
                _selectedSpec.value = it; _autoRoute.value = false
            }
        }
    }

    private fun persistCurrent() {
        val spec = _selectedSpec.value
        val title = _messages.value.firstOrNull { it.role == "user" }?.content?.take(60)
            ?: currentConversation.title
        currentConversation = currentConversation.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            lastProviderLabel = spec?.displayLabel,
            lastProviderName = when (spec) {
                is ProviderSpec.Builtin -> spec.provider.name
                is ProviderSpec.Custom -> spec.endpoint.name
                null -> null
            },
            lastModel = spec?.effectiveModel,
            messages = _messages.value.map { it.toStored() },
            apiHistory = apiHistory.map { it.toStored() }
        )
        conversationRepo.save(currentConversation)
    }
}
