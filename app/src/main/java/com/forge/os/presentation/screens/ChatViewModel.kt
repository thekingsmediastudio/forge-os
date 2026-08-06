package com.forge.os.presentation.screens

import android.content.Context
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
import com.forge.os.domain.agent.ImageAttachment
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val toolName: String? = null,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val errorDetail: ApiError? = null,
    /** Absolute path to a file the agent produced (image, audio, download, etc.) */
    val attachmentPath: String? = null,
    /** MIME type of the attachment, e.g. "image/png", "audio/mpeg", "application/pdf" */
    val attachmentMime: String? = null,
    /** All attachments on this message (multi-image support). Legacy single fields
     *  above are kept in sync from the first entry for backward compatibility. */
    val attachments: List<com.forge.os.domain.agent.FileAttachment> = emptyList())

/** A mid-run clarification request from the agent to the user. */
data class InputRequest(val question: String, val requestId: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
    private val channelManager: com.forge.os.domain.channel.ChannelManager,
    private val capabilityResolver: com.forge.os.data.api.ModelCapabilityResolver,
    heartbeatMonitor: HeartbeatMonitor
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Reference to the current agent job for cancellation. */
    private var currentAgentJob: kotlinx.coroutines.Job? = null

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

    /** Non-null when a destructive tool is paused awaiting user confirmation. */
    private val _pendingConfirmation = MutableStateFlow<com.forge.os.domain.agent.PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<com.forge.os.domain.agent.PendingConfirmation?> = _pendingConfirmation

    /** Multimodal support — pending image attachments for the next message. */
    private val _pendingImages = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val pendingImages: StateFlow<List<ImageAttachment>> = _pendingImages

    /** Whether the currently selected model supports vision. */
    private val _supportsVision = MutableStateFlow(false)
    val supportsVision: StateFlow<Boolean> = _supportsVision


    private val apiHistory = mutableListOf<ApiMessage>()
    private var currentConversation: StoredConversation = conversationRepo.loadOrCreateCurrent()

    /** Current persisted conversation id for surfaces that need to continue this chat. */
    val currentConversationId: String
        get() = currentConversation.id

    /** Messages typed while the agent is busy. Drained FIFO when the
     *  current run completes — replaces the earlier "silent drop" that
     *  cancelled or ignored the user's input mid-turn. */
    private val pendingSends = ArrayDeque<String>()

    init {
        refreshAvailableSpecs()
        updateVisionCapability(_selectedSpec.value)
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

        // Listen for destructive-tool confirmation requests destined for the UI.
        viewModelScope.launch {
            userInputBroker.confirmations.collect { c ->
                if (c.routeKey != InputRoute.UI) return@collect
                _pendingConfirmation.value = c
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

    fun selectSpec(spec: ProviderSpec) { 
        _selectedSpec.value = spec
        _autoRoute.value = false
        persistCurrent()
        updateVisionCapability(spec)
    }
    
    private fun updateVisionCapability(spec: ProviderSpec?) {
        viewModelScope.launch {
            _supportsVision.value = if (spec != null) {
                capabilityResolver.supportsVision(spec)
            } else {
                // Auto-route — assume vision is available (will be checked at send time)
                true
            }
        }
    }
    fun setAutoRoute(enabled: Boolean) { _autoRoute.value = enabled; persistCurrent() }

    /** Called from the UI when the user submits their response to a mid-run input request. */
    fun submitInputResponse(response: String) {
        val req = _pendingInputRequest.value ?: return
        _pendingInputRequest.value = null
        addMsg(ChatMessage(role = "user", content = "↩ $response"))
        viewModelScope.launch { userInputBroker.submitResponse(InputRoute.UI, response) }
    }

    /** Add an image attachment to the pending message. */
    fun addImageAttachment(attachment: ImageAttachment) {
        _pendingImages.value = _pendingImages.value + attachment
    }

    /** Remove an image attachment from the pending message. */
    fun removeImageAttachment(attachment: ImageAttachment) {
        _pendingImages.value = _pendingImages.value - attachment
    }

    /** Clear all pending image attachments. */
    fun clearImageAttachments() {
        _pendingImages.value = emptyList()
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
                content = "$input\n\n⏳ queued — will run after the current turn finishes"))
            return
        }

        // Handle slash commands
        if (input.startsWith("/")) {
            handleSlashCommand(input)
            return
        }

        // Capture pending images before clearing
        val images = _pendingImages.value
        _pendingImages.value = emptyList()

        // Attach all files to the user message for multi-image rendering
        val firstAttachment = images.firstOrNull()
        addMsg(ChatMessage(
            role = "user",
            content = input,
            attachmentPath = firstAttachment?.filePath,
            attachmentMime = firstAttachment?.mimeType,
            attachments = images,
        ))
        hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.LIGHT_TICK)
        skillRecorder.noteUserRequest(input)
        if (handleLocalCommand(input)) { persistCurrent(); return }

        var spec = if (_autoRoute.value) null else _selectedSpec.value

        // Auto-route to a vision-capable model when images are attached but
        // the current model doesn't support vision.
        if (images.isNotEmpty() && spec != null && !_supportsVision.value) {
            val visionSpec = _availableSpecs.value.firstOrNull { s ->
                runCatching { capabilityResolver.supportsVision(s) }.getOrDefault(false)
            }
            if (visionSpec != null) {
                addMsg(ChatMessage(role = "system", content = "🔄 Routed to vision model: ${visionSpec.displayLabel}"))
                spec = visionSpec
            } else {
                addMsg(ChatMessage(role = "system", content = "⚠️ Current model may not support image analysis. Sending anyway."))
            }
        }

        currentAgentJob = viewModelScope.launch {
            _isLoading.value = true
            val streamId = java.util.UUID.randomUUID().toString()
            var streamBuffer = ""
            val toolHistory = mutableListOf<String>()

            reActAgent.run(input, apiHistory.toList(), spec, currentChannel = "main", imageAttachments = images).collect { event ->
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
                        toolHistory.add("CALL ${event.name} args=${event.args.take(500)}")
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
                        val resultStatus = if (event.isError) "ERROR" else "OK"
                        toolHistory.add("RESULT ${event.name} $resultStatus output=${event.result.take(1200)}")
                        // Check if this tool result produced a file the user can view/play/download
                        val (attachPath, attachMime) = resolveAttachment(event.name, event.result)
                        addMsg(ChatMessage(
                            role = "tool_result",
                            content = event.result,
                            toolName = event.name,
                            isError = event.isError,
                            attachmentPath = attachPath,
                            attachmentMime = attachMime))
                        val lastCall = _messages.value.lastOrNull {
                            it.role == "tool_call" && it.toolName == event.name
                        }
                        if (lastCall != null) {
                            skillRecorder.recordToolUsage(event.name, lastCall.content, event.isError)
                        }
                    }
                    is AgentEvent.Verification -> {
                        // Show verification result as a subtle message
                        val icon = if (event.passed) "✅" else "❌"
                        val status = if (event.passed) "verified" else "verification failed"
                        addMsg(ChatMessage(
                            role = "verification",
                            content = "$icon $status: ${event.detail}",
                            toolName = event.toolName,
                            isError = !event.passed
                        ))
                    }
                    is AgentEvent.Response -> {
                        upsertMsg(ChatMessage(id = streamId, role = "assistant", content = event.text, isStreaming = false))
                        apiHistory.add(ApiMessage(role = "user", content = input))
                        if (toolHistory.isNotEmpty()) {
                            apiHistory.add(ApiMessage(
                                role = "assistant",
                                content = buildString {
                                    appendLine("Tool execution history for the previous request:")
                                    toolHistory.takeLast(20).forEach { appendLine("- $it") }
                                }.trimEnd()
                            ))
                        }
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
            _pendingConfirmation.value = null
            persistCurrent()
            // Drain anything the user typed while we were busy (FIFO).
            val next = pendingSends.removeFirstOrNull()
            if (next != null) send(next)
        }
    }

    /**
     * Stop the currently running agent generation.
     * Cancels the job and resets loading state.
     */
    fun stopGeneration() {
        currentAgentJob?.cancel()
        currentAgentJob = null
        _isLoading.value = false
        pendingSends.clear()
        addMsg(ChatMessage(role = "assistant", content = "⏹ Generation stopped by user."))
        hapticManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.LIGHT_TICK)
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

    /** User allowed the pending destructive tool. */
    fun confirmTool() {
        val c = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        addMsg(ChatMessage(role = "system", content = "🛡️ Allowed: ${c.toolName}"))
        viewModelScope.launch { userInputBroker.submitConfirmation(InputRoute.UI, true) }
    }

    /** User declined the pending destructive tool. */
    fun denyTool() {
        val c = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        addMsg(ChatMessage(role = "system", content = "🚫 Declined: ${c.toolName}"))
        viewModelScope.launch { userInputBroker.submitConfirmation(InputRoute.UI, false) }
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

    fun retryLast() {
        // Find the last user message and re-send it
        val lastUser = _messages.value.lastOrNull { it.role == "user" }?.content ?: return
        // Remove the last assistant/error response so it gets replaced
        val trimmed = _messages.value.dropLastWhile { it.role == "assistant" || it.role == "tool_call" || it.role == "tool_result" }
        _messages.value = trimmed
        send(lastUser)
    }

    /**
     * Inspect a tool result to see if it produced a file the user can view/play/download.
     * Returns (absolutePath, mimeType) or (null, null) if no file was produced.
     */
    private fun resolveAttachment(toolName: String, result: String): Pair<String?, String?> {
        // Special case: chat_send_file explicitly sends a file
        if (toolName == "chat_send_file") {
            if (result.contains("\"action\":\"chat_file\"")) {
                val pathRegex = Regex(""""path"\s*:\s*"([^"]+)"""")
                val mimeRegex = Regex(""""mime"\s*:\s*"([^"]+)"""")
                val path = pathRegex.find(result)?.groupValues?.get(1)
                val mime = mimeRegex.find(result)?.groupValues?.get(1)
                if (path != null && mime != null) {
                    return path to mime
                }
            }
            return null to null
        }
        
        // Tools that produce files: file_write, file_download, python_run (output path), autophone_screenshot
        val fileProducingTools = setOf(
            "file_write", "file_download", "python_run",
            "autophone_screenshot", "browser_screenshot_region")
        if (toolName !in fileProducingTools) return null to null
        if (result.contains("\"ok\":false") || result.contains("error")) return null to null

        // Try to extract a path from the JSON result
        val pathRegex = Regex(""""(?:path|file|saved_to|output_path|screenshot)"\s*:\s*"([^"]+)"""")
        val match = pathRegex.find(result) ?: return null to null
        val relativePath = match.groupValues[1]

        // Resolve to absolute path inside the sandbox
        val sandboxRoot = File(context.filesDir, "workspace")
        val absFile = if (relativePath.startsWith("/")) File(relativePath)
                      else File(sandboxRoot, relativePath)
        if (!absFile.exists()) return null to null

        val mime = guessMime(absFile.name)
        return absFile.absolutePath to mime
    }

    private fun guessMime(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image/$ext"
            "mp3"  -> "audio/mpeg"
            "wav"  -> "audio/wav"
            "ogg"  -> "audio/ogg"
            "m4a"  -> "audio/mp4"
            "mp4"  -> "video/mp4"
            "webm" -> "video/webm"
            "pdf"  -> "application/pdf"
            "zip"  -> "application/zip"
            "json" -> "application/json"
            "txt", "md", "py", "kt", "js", "html", "css" -> "text/plain"
            else   -> "application/octet-stream"
        }
    }

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
        val channelId = channelManager.getCurrentChannelId()
        currentConversation = conversationRepo.newConversation(channelId)
        _messages.value = emptyList()
        apiHistory.clear()
        val identity = configRepository.get().agentIdentity
        val channelName = channelManager.currentChannel.value.name
        val channelNote = if (channelManager.isChannelsEnabled()) "\n\n(channel: $channelName)" else ""
        addMsg(ChatMessage(role = "system", content = "${identity.defaultGreeting}\n\n(new conversation started)$channelNote"))
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
