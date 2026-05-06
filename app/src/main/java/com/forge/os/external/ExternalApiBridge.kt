package com.forge.os.external

import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.plugins.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single chokepoint: every external API surface (AIDL service, ContentProvider, Intent activity)
 * goes through here so policy checks and audit live in one place.
 */
@Singleton
class ExternalApiBridge @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val pluginManager: PluginManager,
    private val memoryManager: MemoryManager,
    private val agentProvider: javax.inject.Provider<ReActAgent>,
    private val registry: ExternalCallerRegistry,
    private val audit: ExternalAuditLog,
    private val configRepository: ConfigRepository,
    // Enhanced Integration: Connect with other systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val securityPolicy: com.forge.os.data.sandbox.SecurityPolicy,
    private val doctorService: com.forge.os.domain.doctor.DoctorService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    // pkg -> rolling list of call timestamps (ms)
    private val callTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    // pkg -> rolling daily token usage (yyyy-mm-dd -> tokens)
    private val tokenUse = ConcurrentHashMap<String, Pair<String, Int>>()

    // ─── Master switch ───────────────────────────────────────────────────────

    fun masterEnabled(): Boolean = configRepository.get().externalApi.enabled

    // ─── Authorisation helpers ───────────────────────────────────────────────

    sealed class Decision {
        data class Allow(val caller: ExternalCaller) : Decision()
        data class Deny(val code: Int, val reason: String) : Decision()
    }

    fun authorize(uid: Int, op: String, target: String = ""): Decision {
        if (!masterEnabled()) return Decision.Deny(403, "External API is disabled in Forge OS settings")
        val caller = registry.observe(uid) ?: return Decision.Deny(403, "Caller package not resolvable")
        if (caller.status != GrantStatus.GRANTED) {
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = op,
                target = target, outcome = "deny", message = "status=${caller.status}"))
            return Decision.Deny(403, "Not granted (status=${caller.status})")
        }
        
        // Enhanced Integration: Security policy validation
        try {
            // Security policy validation - simplified for now
            // TODO: Implement proper security policy checks
            val isAllowed = true // Allow all operations for now
            
            if (!isAllowed) {
                audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = op,
                    target = target, outcome = "deny", message = "security_policy_blocked"))
                return Decision.Deny(403, "Operation blocked by security policy")
            }
            
            // Record security validation patterns
            scope.launch {
                try {
                    reflectionManager.recordPattern(
                        pattern = "External API access: $op by ${caller.packageName}",
                        description = "External app ${caller.packageName} accessed $op operation",
                        applicableTo = listOf("external_api", "security", caller.packageName),
                        tags = listOf("external_access", "security_validation", op, caller.packageName)
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record external API access pattern")
                }
            }
            
        } catch (e: Exception) {
            Timber.w(e, "Security policy check failed for ${caller.packageName}")
        }
        
        // Capability check
        val caps = caller.capabilities
        val ok = when (op) {
            "listTools" -> caps.listTools
            "invokeTool", "invokeToolAsync" ->
                caps.invokeTools && (caps.toolAllowlist.contains("*") || caps.toolAllowlist.contains(target))
            "askAgent" -> caps.askAgent
            "getMemory" -> caps.readMemory
            "putMemory" -> caps.writeMemory
            "runSkill" ->
                caps.runSkills && (caps.skillAllowlist.contains("*") || caps.skillAllowlist.contains(target))
            else -> false
        }
        if (!ok) {
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = op,
                target = target, outcome = "deny", message = "capability_missing"))
            return Decision.Deny(403, "Capability denied: $op${if (target.isNotEmpty()) " ($target)" else ""}")
        }
        // Rate limit
        val now = System.currentTimeMillis()
        val window = callTimestamps.getOrPut(caller.packageName) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() > 60_000L) window.removeFirst()
            if (window.size >= caller.rateLimit.callsPerMinute) {
                audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = op,
                    target = target, outcome = "rate_limited"))
                return Decision.Deny(429, "Rate limit exceeded (${caller.rateLimit.callsPerMinute}/min)")
            }
            window.addLast(now)
        }
        registry.touch(caller.packageName)
        return Decision.Allow(caller)
    }

    fun chargeTokens(pkg: String, tokens: Int) {
        if (tokens <= 0) return
        val today = java.time.LocalDate.now().toString()
        tokenUse.compute(pkg) { _, prev ->
            if (prev == null || prev.first != today) today to tokens
            else today to (prev.second + tokens)
        }
    }

    fun tokenBudgetLeft(caller: ExternalCaller): Int {
        val today = java.time.LocalDate.now().toString()
        val used = tokenUse[caller.packageName]?.takeIf { it.first == today }?.second ?: 0
        return (caller.rateLimit.tokensPerDay - used).coerceAtLeast(0)
    }

    // ─── Operations (called from AIDL service / provider / intent activity) ──

    fun listToolsFor(caller: ExternalCaller): String {
        val all = toolRegistry.getDefinitions()
        val allowed = if (caller.capabilities.toolAllowlist.contains("*")) all
            else all.filter { it.function.name in caller.capabilities.toolAllowlist }
        val arr = buildString {
            append("[")
            allowed.forEachIndexed { i, t ->
                if (i > 0) append(",")
                append("""{"name":"${t.function.name}","description":"${escape(t.function.description)}"}""")
            }
            append("]")
        }
        audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "listTools",
            outcome = "ok", outputBytes = arr.length))
        return arr
    }

    fun invokeToolSync(caller: ExternalCaller, toolName: String, jsonArgs: String): String {
        val started = System.currentTimeMillis()
        return try {
            // Enhanced Integration: Pre-execution health check
            try {
                val healthReport = doctorService.runChecks()
                if (healthReport.hasFailures) {
                    Timber.w("External API tool execution with health issues: ${healthReport.checks.filter { it.status == com.forge.os.domain.doctor.CheckStatus.FAIL }.map { it.id }}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Health check failed during external API call")
            }
            
            val result = runBlocking {
                toolRegistry.dispatch(toolName, jsonArgs.ifBlank { "{}" }, "ext_${started}")
            }
            val payload = buildJsonObject {
                put("ok", !result.isError)
                put("output", result.output)
                if (result.isError) put("error", result.output)
            }.toString()
            
            // Enhanced Integration: Learn from external API usage
            scope.launch {
                try {
                    reflectionManager.recordPattern(
                        pattern = "External tool execution: $toolName by ${caller.packageName}",
                        description = "External app ${caller.packageName} executed $toolName with result: ${if (result.isError) "error" else "success"}",
                        applicableTo = listOf("external_tool_usage", toolName, caller.packageName),
                        tags = listOf("external_api", "tool_execution", toolName, caller.packageName, if (result.isError) "error" else "success")
                    )
                    
                    if (result.isError) {
                        reflectionManager.recordFailureAndRecovery(
                            taskId = "ext_${started}",
                            failureReason = "External tool execution failed: ${result.output}",
                            recoveryStrategy = "Check tool parameters, validate external app permissions, or try alternative approach",
                            tags = listOf("external_api_failure", toolName, caller.packageName)
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record external API patterns")
                }
            }
            
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "invokeTool",
                target = toolName, outcome = if (result.isError) "error" else "ok",
                durationMs = System.currentTimeMillis() - started, outputBytes = payload.length))
            payload
        } catch (e: Exception) {
            Timber.e(e, "invokeToolSync failed")
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "invokeTool",
                target = toolName, outcome = "error", message = e.message ?: e.javaClass.simpleName))
            """{"ok":false,"error":${q(e.message ?: "internal")}}"""
        }
    }

    /** Streams agent events to [onChunk] / [onResult] / [onError]. */
    suspend fun askAgent(
        caller: ExternalCaller, prompt: String, optsJson: String,
        onChunk: (String) -> Unit, onResult: (String) -> Unit, onError: (Int, String) -> Unit,
    ) {
        val started = System.currentTimeMillis()
        try {
            val full = StringBuilder()
            agentProvider.get().run(prompt).collect { ev ->
                when (ev) {
                    is AgentEvent.Thinking -> { /* internal */ }
                    is AgentEvent.ToolCall -> onChunk("[tool:${ev.name}]")
                    is AgentEvent.ToolResult -> { /* internal */ }
                    is AgentEvent.Response -> { full.append(ev.text); onChunk(ev.text) }
                    is AgentEvent.Error -> { onError(500, ev.message); return@collect }
                    is AgentEvent.CostApprovalRequired -> { /* handled internally by agent loop if eco-mode is on */ }
                    AgentEvent.Done -> { /* final emitted below */ }
                }
            }
            chargeTokens(caller.packageName, full.length / 4) // rough heuristic
            val payload = buildJsonObject {
                put("ok", true)
                put("text", full.toString())
            }.toString()
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "askAgent",
                outcome = "ok", durationMs = System.currentTimeMillis() - started, outputBytes = payload.length))
            onResult(payload)
        } catch (e: Exception) {
            Timber.e(e, "askAgent failed")
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "askAgent",
                outcome = "error", message = e.message ?: e.javaClass.simpleName))
            onError(500, e.message ?: "internal")
        }
    }

    fun getMemory(caller: ExternalCaller, key: String): String {
        val hit = runCatching { memoryManager.recallByKey(key) }.getOrNull()
        val tagFilter = caller.capabilities.memoryTagFilter
        if (hit != null && tagFilter.isNotBlank() && tagFilter !in hit.tags) {
            audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "getMemory",
                target = key, outcome = "deny", message = "tag_filter"))
            return ""
        }
        val out = hit?.content.orEmpty()
        audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "getMemory",
            target = key, outcome = "ok", outputBytes = out.length))
        return out
    }

    fun putMemory(caller: ExternalCaller, key: String, value: String, tagsCsv: String) {
        val tags = tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        memoryManager.store(key = key, content = value, tags = tags + "external:${caller.packageName}")
        audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "putMemory",
            target = key, outcome = "ok", outputBytes = value.length))
    }

    fun runSkill(caller: ExternalCaller, skillId: String, jsonArgs: String): String {
        // Skills are surfaced as plugin tools whose names match the skill id.
        val argsMap = runCatching {
            json.parseToJsonElement(jsonArgs.ifBlank { "{}" }).let { it as? JsonObject }
                ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } ?: emptyMap()
        }.getOrDefault(emptyMap())
        val r = runBlocking { pluginManager.executeTool(skillId, argsMap) }
        val payload = buildJsonObject {
            put("ok", r.success)
            put("output", r.output)
            if (!r.success) put("error", r.error ?: "unknown")
        }.toString()
        audit.record(ExternalAuditEntry(packageName = caller.packageName, operation = "runSkill",
            target = skillId, outcome = if (r.success) "ok" else "error",
            durationMs = r.durationMs, outputBytes = payload.length))
        return payload
    }

    private fun q(s: String) = "\"" + escape(s) + "\""
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
