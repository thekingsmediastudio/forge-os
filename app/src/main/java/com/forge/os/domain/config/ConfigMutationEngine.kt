package com.forge.os.domain.config

import com.forge.os.domain.model.UserRole
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ParsedMutation(
    val path: String,
    val operation: String,       // set | add | remove | toggle
    val value: String,
    val reason: String = ""
)

sealed class ConfigMutationResult {
    data class Success(
        val change: ParsedMutation,
        val backupVersion: String,
        val message: String,
        val rollbackCommand: String
    ) : ConfigMutationResult()

    data class Error(val reason: String) : ConfigMutationResult()
}

@Singleton
class ConfigMutationEngine @Inject constructor(
    private val configRepository: ConfigRepository
) {
    /**
     * Parses natural-language config requests with case-preserving rule
     * matching. We recognise a broad set of phrasings; the agent (via the
     * `config_write` tool) usually just hands the user's verbatim request to
     * this engine, so being forgiving on phrasing matters more than being
     * elegant.
     *
     * Recognised intents (any reasonable wording works):
     *   - rename agent             — "change/set/rename agent name to X"
     *   - switch provider          — "use openai", "switch to anthropic", "set default provider to gemini"
     *   - switch model             — "use model gpt-4o", "switch model to claude-sonnet-4-20250514"
     *   - disable / enable tool    — "disable tool X", "turn off tool X", "enable tools A B"
     *   - auto-confirm             — "set auto confirm on/off"
     *   - max iterations           — "set max iterations to N"
     *   - route task type          — "route code to claude", "route chat to gpt-4o"
     *   - heartbeat interval       — "set heartbeat to 60 seconds" (or just "to 60")
     *   - verbose logging          — "enable/disable verbose logging"
     *   - greeting                 — "set greeting to ..."
     */
    suspend fun processConfigRequest(
        userRequest: String,
        userRole: UserRole = UserRole.ADMIN
    ): ConfigMutationResult {
        val raw = userRequest.trim()
        val input = raw.lowercase()
        Timber.d("Config request: $raw")

        val snapshot = configRepository.createSnapshot(note = "pre-user-change")

        return try {
            when {
                // ─── Agent name (preserve original case for the value) ───────
                Regex("""(?i)\b(?:change|set|rename|update|make)\b.*?\bagent\b.*?\bname\b.*?\bto\b\s+(.+)""")
                    .containsMatchIn(raw) ||
                Regex("""(?i)\brename\b.*?\bto\b\s+(.+)""").containsMatchIn(raw) ||
                Regex("""(?i)\bcall\b\s+(?:the|my)\s+agent\s+(.+)""").containsMatchIn(raw) -> {
                    val name = extractAgentName(raw)
                        ?: return ConfigMutationResult.Error("Couldn't parse the new agent name from: \"$raw\"")
                    configRepository.update { it.copy(agentIdentity = it.agentIdentity.copy(name = name)) }
                    success("agentIdentity.name", "set", name, snapshot.version)
                }

                // ─── Greeting ─────────────────────────────────────────────────
                Regex("""(?i)\b(?:set|change|update)\b.*?\bgreeting\b.*?\bto\b\s+(.+)""").containsMatchIn(raw) -> {
                    val msg = raw.substringAfter("to ", "").trim().trimEnd('.').ifBlank { return ConfigMutationResult.Error("Couldn't parse greeting text.") }
                    configRepository.update { it.copy(agentIdentity = it.agentIdentity.copy(defaultGreeting = msg)) }
                    success("agentIdentity.defaultGreeting", "set", msg.take(60), snapshot.version)
                }

                // ─── Disable tool(s) ─────────────────────────────────────────
                Regex("""(?i)\b(?:disable|turn\s*off|block|kill)\b.*?\btool[s]?\b\s+(.+)""").containsMatchIn(raw) -> {
                    val tools = extractToolNames(raw)
                    if (tools.isEmpty()) return ConfigMutationResult.Error("No recognized tool name in: \"$raw\"")
                    configRepository.update { config ->
                        config.copy(
                            toolRegistry = config.toolRegistry.copy(
                                disabledTools = (config.toolRegistry.disabledTools + tools).distinct(),
                                enabledTools = config.toolRegistry.enabledTools - tools.toSet()
                            )
                        )
                    }
                    success("toolRegistry.disabledTools", "add", tools.joinToString(), snapshot.version)
                }

                // ─── Enable tool(s) ──────────────────────────────────────────
                Regex("""(?i)\b(?:enable|turn\s*on|allow|unblock)\b.*?\btool[s]?\b\s+(.+)""").containsMatchIn(raw) -> {
                    val tools = extractToolNames(raw)
                    if (tools.isEmpty()) return ConfigMutationResult.Error("No recognized tool name in: \"$raw\"")
                    configRepository.update { config ->
                        config.copy(
                            toolRegistry = config.toolRegistry.copy(
                                disabledTools = config.toolRegistry.disabledTools - tools.toSet(),
                                enabledTools = (config.toolRegistry.enabledTools + tools).distinct()
                            )
                        )
                    }
                    success("toolRegistry.enabledTools", "add", tools.joinToString(), snapshot.version)
                }

                // ─── Switch default provider ─────────────────────────────────
                Regex("""(?i)\b(?:use|switch\s*to|set\s+(?:default\s+)?provider\s+(?:to|=))\b.*?(openai|anthropic|gemini|google|openrouter|deepseek|mistral|ollama|forge.?bridge)\b""").containsMatchIn(input) -> {
                    val raw = Regex("""(?i)(openai|anthropic|gemini|google|openrouter|deepseek|mistral|ollama|forge[_\s-]?bridge)""").find(input)?.value ?: ""
                    val prov = when {
                        raw.equals("google", ignoreCase = true) -> "GEMINI"
                        raw.contains("bridge", ignoreCase = true) -> "FORGE_BRIDGE"
                        else -> raw.uppercase()
                    }.ifBlank { return ConfigMutationResult.Error("Couldn't parse provider.") }
                    configRepository.update { it.copy(modelRouting = it.modelRouting.copy(defaultProvider = prov)) }
                    success("modelRouting.defaultProvider", "set", prov, snapshot.version)
                }

                // ─── Switch default model ────────────────────────────────────
                Regex("""(?i)\b(?:use|switch|set)\s+(?:default\s+)?model\b.*?(?:to|=)?\s*([A-Za-z0-9._\-:/]{3,})""").containsMatchIn(raw) -> {
                    val m = Regex("""(?i)\b(?:use|switch|set)\s+(?:default\s+)?model\b.*?(?:to|=)?\s*([A-Za-z0-9._\-:/]{3,})""").find(raw)
                    val model = m?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.')
                        ?: return ConfigMutationResult.Error("Couldn't parse model name.")
                    configRepository.update { it.copy(modelRouting = it.modelRouting.copy(defaultModel = model)) }
                    success("modelRouting.defaultModel", "set", model, snapshot.version)
                }

                // ─── Auto-confirm tool calls ─────────────────────────────────
                Regex("""(?i)\bauto[-\s]?confirm\b""").containsMatchIn(input) -> {
                    val enable = !(input.contains(" off") || input.contains("disable") || input.contains("false") || input.contains(" no"))
                    configRepository.update { it.copy(behaviorRules = it.behaviorRules.copy(autoConfirmToolCalls = enable)) }
                    success("behaviorRules.autoConfirmToolCalls", "set", enable.toString(), snapshot.version)
                }

                // ─── Max iterations ──────────────────────────────────────────
                Regex("""(?i)\bmax\b.*?\biterations?\b.*?(\d+)""").containsMatchIn(input) -> {
                    val n = Regex("""(\d+)""").find(input.substringAfter("iteration"))?.value?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(input)?.value?.toIntOrNull() ?: 15
                    configRepository.update { it.copy(behaviorRules = it.behaviorRules.copy(maxIterations = n)) }
                    success("behaviorRules.maxIterations", "set", n.toString(), snapshot.version)
                }

                // ─── Route a task type to a model/provider ───────────────────
                Regex("""(?i)\broute\b\s+(\w+)\b.*?\bto\b\s+(.+)""").containsMatchIn(raw) -> {
                    val m = Regex("""(?i)\broute\b\s+(\w+)\b.*?\bto\b\s+(.+)""").find(raw)!!
                    val taskWord = m.groupValues[1].lowercase()
                    val target = m.groupValues[2].trim().trimEnd('.')
                    val (provider, model) = parseProviderModel(target)
                    
                    when {
                        taskWord.startsWith("vision") -> {
                            configRepository.update { it.copy(modelRouting = it.modelRouting.copy(visionProvider = provider, visionModel = model)) }
                            success("modelRouting.visionProvider", "set", "$provider/$model", snapshot.version)
                        }
                        taskWord.startsWith("reason") -> {
                            configRepository.update { it.copy(modelRouting = it.modelRouting.copy(reasoningProvider = provider, reasoningModel = model)) }
                            success("modelRouting.reasoningProvider", "set", "$provider/$model", snapshot.version)
                        }
                        taskWord.startsWith("reflect") || taskWord.startsWith("learn") -> {
                            configRepository.update { it.copy(modelRouting = it.modelRouting.copy(reflectionProvider = provider, reflectionModel = model)) }
                            success("modelRouting.reflectionProvider", "set", "$provider/$model", snapshot.version)
                        }
                        else -> {
                            val taskType = when {
                                taskWord.startsWith("code") -> "code_generation"
                                taskWord.startsWith("chat") -> "chat"
                                else -> "${taskWord}_tasks"
                            }
                            val rules = configRepository.get().modelRouting.routingRules.map {
                                if (it.taskType == taskType) it.copy(provider = provider, model = model) else it
                            }
                            configRepository.update { it.copy(modelRouting = it.modelRouting.copy(routingRules = rules)) }
                            success("modelRouting.routingRules[$taskType]", "set", "$provider/$model", snapshot.version)
                        }
                    }
                }

                // ─── Intelligence Upgrades Toggles ──────────────────────────
                Regex("""(?i)\b(?:enable|disable|turn\s*(?:on|off))\b.*?\b(reflection|learning|memory\s*rag|conversation\s*rag|rag|minimal\s*catalog|vision|reasoning)\b""").containsMatchIn(input) -> {
                    val enable = !(input.contains("disable") || input.contains(" off") || input.contains("false"))
                    val feature = Regex("""(reflection|learning|memory\s*rag|conversation\s*rag|rag|minimal\s*catalog|vision|reasoning)""").find(input)?.value ?: ""
                    
                    configRepository.update { config ->
                        val upgrades = config.intelligenceUpgrades
                        val newUpgrades = when {
                            feature == "reflection" || feature == "learning" -> upgrades.copy(reflectionEnabled = enable)
                            feature == "memory rag" -> upgrades.copy(memoryRagEnabled = enable)
                            feature == "conversation rag" -> upgrades.copy(conversationRagEnabled = enable)
                            feature == "rag" -> upgrades.copy(memoryRagEnabled = enable, conversationRagEnabled = enable)
                            feature == "minimal catalog" -> upgrades.copy(minimalToolCatalog = enable)
                            feature == "vision" -> upgrades.copy(visionEnabled = enable)
                            feature == "reasoning" -> upgrades.copy(reasoningEnabled = enable)
                            else -> upgrades
                        }
                        config.copy(intelligenceUpgrades = newUpgrades)
                    }
                    success("intelligenceUpgrades.$feature", "set", enable.toString(), snapshot.version)
                }

                // ─── Heartbeat interval ──────────────────────────────────────
                Regex("""(?i)\bheartbeat\b.*?(\d+)""").containsMatchIn(input) -> {
                    val n = Regex("""(\d+)""").find(input.substringAfter("heartbeat"))?.value?.toIntOrNull() ?: 60
                    configRepository.update { it.copy(heartbeatSettings = it.heartbeatSettings.copy(intervalSeconds = n)) }
                    success("heartbeatSettings.intervalSeconds", "set", n.toString(), snapshot.version)
                }

                // ─── Verbose logging ─────────────────────────────────────────
                input.contains("verbose") -> {
                    val enable = !(input.contains(" off") || input.contains("disable") || input.contains("false"))
                    configRepository.update { it.copy(behaviorRules = it.behaviorRules.copy(verboseLogging = enable)) }
                    success("behaviorRules.verboseLogging", "set", enable.toString(), snapshot.version)
                }

                else -> ConfigMutationResult.Error(
                    "I couldn't parse that config request. Examples that work:\n" +
                    "• \"change agent name to Forge Mk2\"\n" +
                    "• \"disable tool shell_exec\"\n" +
                    "• \"enable tools file_write file_read\"\n" +
                    "• \"use anthropic\" / \"switch to openai\" / \"use forge-bridge\"\n" +
                    "• \"use model claude-sonnet-4-20250514\"\n" +
                    "• \"set auto confirm on\"\n" +
                    "• \"set max iterations to 25\"\n" +
                    "• \"route code to claude\"\n" +
                    "• \"set heartbeat to 60 seconds\"\n" +
                    "• \"enable verbose logging\""
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Config mutation failed")
            ConfigMutationResult.Error("Config change failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun success(path: String, op: String, value: String, backupVersion: String): ConfigMutationResult.Success {
        val current = configRepository.get()
        return ConfigMutationResult.Success(
            change = ParsedMutation(path, op, value),
            backupVersion = backupVersion,
            message = "✅ Config updated: $path = $value\nVersion: ${current.version}",
            rollbackCommand = "rollback config to $backupVersion"
        )
    }

    /**
     * Pull the proposed agent name out of [raw], preserving its original
     * casing. Strips trailing punctuation and a leading article ("the ").
     */
    private fun extractAgentName(raw: String): String? {
        val candidate = raw.substringAfterLast(" to ", missingDelimiterValue = "")
            .ifBlank { raw.substringAfterLast(" ", missingDelimiterValue = raw) }
            .trim()
            .trimEnd('.', ',', '!', '?')
        return candidate.removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .ifBlank { null }
    }

    /** Parse "anthropic claude-sonnet-4-20250514" or just "claude-sonnet-4-20250514". */
    private fun parseProviderModel(target: String): Pair<String, String> {
        val parts = target.split(Regex("\\s+"), limit = 2)
        val first = parts.firstOrNull()?.lowercase()
        val provider = when (first) {
            "openai" -> "OPENAI"
            "anthropic", "claude" -> "ANTHROPIC"
            "gemini", "google" -> "GEMINI"
            "openrouter" -> "OPENROUTER"
            "deepseek" -> "DEEPSEEK"
            "mistral" -> "MISTRAL"
            "ollama" -> "OLLAMA"
            "forge_bridge", "forgebridge", "forge-bridge", "bridge" -> "FORGE_BRIDGE"
            else -> null
        }
        return when {
            provider != null && parts.size > 1 -> provider to parts[1].trim()
            provider != null -> provider to defaultModelFor(provider)
            target.startsWith("claude", ignoreCase = true) -> "ANTHROPIC" to target
            target.startsWith("gpt", ignoreCase = true) -> "OPENAI" to target
            target.startsWith("gemini", ignoreCase = true) -> "GEMINI" to target
            else -> "OPENAI" to target
        }
    }

    private fun defaultModelFor(provider: String): String = when (provider) {
        "ANTHROPIC" -> "claude-sonnet-4-20250514"
        "OPENAI" -> "gpt-4o"
        "GEMINI" -> "gemini-2.0-flash"
        "FORGE_BRIDGE" -> "auto"
        else -> "default"
    }

    private fun extractToolNames(raw: String): List<String> {
        val known = setOf(
            "file_read", "file_write", "file_list", "file_delete",
            "shell_exec", "python_run", "workspace_info",
            "git_init", "git_commit", "git_status",
            "memory_store", "memory_recall", "memory_summary",
            "cron_add", "cron_list", "cron_remove", "cron_run_now", "cron_history",
            "config_read", "config_write", "config_rollback",
            "delegate_task", "delegate_batch", "agents_list", "agent_status", "agent_cancel",
            "heartbeat_check",
            "http_fetch", "curl_exec", "ddg_search",
            "browser_navigate", "browser_get_html", "browser_eval_js",
            "browser_fill_field", "browser_click", "browser_scroll",
            "alarm_set", "alarm_list", "alarm_cancel",
            "project_serve", "project_unserve", "project_serve_list",
            "composio_call", "request_user_input",
            "snapshot_create", "snapshot_list", "snapshot_restore", "snapshot_delete",
            "mcp_refresh", "mcp_list_tools",
        )
        // First try exact word matches against the known list
        val tail = raw.substringAfterLast(" tool", missingDelimiterValue = raw)
            .substringAfterLast(" tools", missingDelimiterValue = raw)
            .substringAfter(" ", missingDelimiterValue = raw)
        val tokens = tail.split(Regex("[,\\s]+")).map { it.trim().trimEnd('.', ',') }.filter { it.isNotBlank() }
        val matched = tokens.filter { it in known }
        if (matched.isNotEmpty()) return matched
        // Fallback: substring scan
        return known.filter { raw.contains(it, ignoreCase = false) }
    }

    fun rollbackToVersion(version: String): ForgeConfig {
        return configRepository.restoreSnapshot(version)
    }

    fun listVersions() = configRepository.listSnapshots()
}
