package com.forge.os.domain.plugins

import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.memory.MemoryManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Façade for plugin discovery, install/uninstall, and execution.
 *
 * Plugin tools are exposed under a flat namespace; if multiple plugins declare
 * the same tool name (which the validator forbids for built-ins, but not for
 * cross-plugin collisions), the first one loaded wins and others are skipped
 * with a warning.
 */
@Singleton
class PluginManager @Inject constructor(
    private val repository: PluginRepository,
    private val validator: PluginValidator,
    private val sandboxManager: SandboxManager,
    private val configRepository: ConfigRepository,
    private val memoryManager: MemoryManager,
    private val exporter: PluginExporter,
    private val headlessBrowser: com.forge.os.data.web.HeadlessBrowser,
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // toolName -> (pluginId, toolDef) — rebuilt every list/install/uninstall
    @Volatile private var toolIndex: Map<String, Pair<String, PluginTool>> = emptyMap()

    init {
        // Phase Q — restore any plugins exported under the upgrade-survivable
        // folder before building the tool index, so they appear immediately on
        // first launch after a fresh install.
        runCatching { exporter.restoreMissingPlugins(repository) }
            .onFailure { Timber.w(it, "PluginManager: auto-restore failed") }
        rebuildIndex()
    }

    // ─── Discovery ───────────────────────────────────────────────────────────

    fun listPlugins(): List<PluginManifest> = repository.listManifests()

    fun getPlugin(id: String): PluginManifest? = repository.loadManifest(id)

    /** All tools exposed by all enabled plugins. */
    fun listAllTools(): List<Pair<String, PluginTool>> =
        toolIndex.values.toList()

    fun resolveTool(toolName: String): Pair<String, PluginTool>? = toolIndex[toolName]

    /**
     * All hub tiles contributed by enabled plugins. Each tile is paired with
     * the plugin id that declared it, so the Hub can show provenance.
     */
    fun listHubTiles(): List<Pair<String, HubTile>> =
        listPlugins()
            .filter { it.enabled }
            .flatMap { manifest ->
                manifest.uiContributions?.hubTiles?.map { manifest.id to it }.orEmpty()
            }

    // ─── Install / uninstall ─────────────────────────────────────────────────

    /**
     * Install a plugin from a raw manifest JSON string + entrypoint Python code.
     * Returns the saved manifest on success.
     */
    fun install(manifestJson: String, entrypointCode: String, source: String = "user"): Result<PluginManifest> {
        val manifest = runCatching {
            json.decodeFromString<PluginManifest>(manifestJson).copy(source = source)
        }.getOrElse { return Result.failure(IllegalArgumentException("Invalid manifest JSON: ${it.message}")) }

        // Storage cap check
        val cfg = configRepository.get().pluginSettings
        val capBytes = cfg.pluginStorageCapMb * 1024L * 1024L
        val currentBytes = repository.totalBytes()
        val incomingBytes = entrypointCode.toByteArray().size + manifestJson.toByteArray().size
        if (currentBytes + incomingBytes > capBytes) {
            return Result.failure(IllegalStateException(
                "Plugin storage cap exceeded (${(currentBytes + incomingBytes) / 1024} KB > ${cfg.pluginStorageCapMb} MB)"
            ))
        }

        // Permissions diff against installed version (warn-only at this layer; UI surfaces it)
        val previousPerms = repository.loadManifest(manifest.id)?.permissions?.toSet().orEmpty()
        val newPerms = manifest.permissions.toSet() - previousPerms
        if (newPerms.isNotEmpty()) {
            Timber.w("PluginManager: install '${manifest.id}' adds permissions: ${newPerms.joinToString()}")
        }

        PluginCompatibility.reasonIfIncompatible(manifest)?.let {
            return Result.failure(IllegalStateException(it))
        }

        return when (val v = validator.validate(manifest, entrypointCode)) {
            is ValidationResult.Rejected -> Result.failure(SecurityException(v.reason))
            is ValidationResult.Warn, is ValidationResult.Ok -> {
                if (cfg.keepRollback) repository.snapshotForRollback(manifest.id)
                repository.save(manifest, entrypointCode)
                runCatching {
                    val dir = repository.entrypointFile(manifest.id, manifest.entrypoint).parentFile
                    if (dir != null) exporter.exportPlugin(manifest, dir)
                }.onFailure { Timber.w(it, "PluginExporter export-on-install skipped") }
                rebuildIndex()
                val warn = (v as? ValidationResult.Warn)?.reason
                memoryManager.logEvent(
                    role = "system",
                    content = "Installed plugin '${manifest.name}' v${manifest.version} (${manifest.tools.size} tools)" +
                        (warn?.let { "  [warn: $it]" } ?: "") +
                        (if (newPerms.isNotEmpty()) "  [new perms: ${newPerms.joinToString()}]" else ""),
                    tags = listOf("plugin", "install", manifest.id)
                )
                Result.success(manifest)
            }
        }
    }

    /** Restore the previous version of a plugin (one generation back). */
    fun rollback(id: String): Boolean {
        val ok = repository.restoreFromRollback(id)
        if (ok) {
            rebuildIndex()
            memoryManager.logEvent("system", "Rolled back plugin '$id'", listOf("plugin", "rollback", id))
        }
        return ok
    }

    fun hasRollback(id: String): Boolean = repository.hasRollback(id)

    fun uninstall(id: String): Boolean {
        val manifest = repository.loadManifest(id) ?: return false
        if (manifest.source == "builtin") {
            Timber.w("PluginManager: refusing to uninstall builtin plugin $id")
            return false
        }
        val ok = repository.delete(id)
        if (ok) {
            runCatching { exporter.removeExport(id) }
            rebuildIndex()
            memoryManager.logEvent(
                role = "system",
                content = "Uninstalled plugin '${manifest.name}'",
                tags = listOf("plugin", "uninstall", id)
            )
        }
        return ok
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val manifest = repository.loadManifest(id) ?: return false
        repository.updateManifest(manifest.copy(enabled = enabled))
        rebuildIndex()
        return true
    }

    // ─── Execution ───────────────────────────────────────────────────────────

    /**
     * Invoke a plugin tool by name. The plugin's entrypoint is dynamically
     * loaded into the Chaquopy interpreter, the named function is called with
     * the supplied args (as a dict), and its return value is captured as a
     * string.
     */
    suspend fun executeTool(toolName: String, args: Map<String, Any?>): PluginExecutionResult {
        val started = System.currentTimeMillis()
        val resolved = toolIndex[toolName] ?: return PluginExecutionResult(
            pluginId = "?", toolName = toolName, success = false,
            output = "Unknown plugin tool: $toolName", durationMs = 0, error = "not_found"
        )
        val (pluginId, _) = resolved
        val manifest = repository.loadManifest(pluginId) ?: return PluginExecutionResult(
            pluginId = pluginId, toolName = toolName, success = false,
            output = "Plugin manifest missing for $pluginId", durationMs = 0, error = "missing_manifest"
        )
        if (!manifest.enabled) {
            return PluginExecutionResult(
                pluginId, toolName, false,
                "Plugin '$pluginId' is disabled", System.currentTimeMillis() - started, "disabled"
            )
        }

        val entrypointPath = repository.entrypointFile(pluginId, manifest.entrypoint).absolutePath
        val argsJson = buildString {
            append('{')
            var first = true
            for ((k, v) in args) {
                if (k == "tool") continue
                if (!first) append(',')
                first = false
                append('"').append(k.replace("\\", "\\\\").replace("\"", "\\\"")).append('"').append(':')
                append(jsonifyAny(v))
            }
            append('}')
        }

        if (manifest.language == "javascript") {
            val timeout = configRepository.get().toolRegistry.toolTimeouts["javascript_run"] ?: 30
            val jsCode = java.io.File(entrypointPath).readText()
            val wrapper = """
                (async function() {
                    const __forge_args__ = $argsJson;
                    $jsCode
                    if (typeof ${toolName} !== 'function') {
                        throw new Error("PLUGIN_ERROR: tool function '${toolName}' not defined in entrypoint");
                    }
                    return await ${toolName}(__forge_args__);
                })();
            """.trimIndent()
            
            return try {
                val output = headlessBrowser.evalJsAsync(wrapper, timeoutMs = timeout * 1000L)
                val finished = System.currentTimeMillis()
                val isErr = output.contains("JS Error:") || output.contains("JS Timeout") || output.contains("PLUGIN_ERROR:")
                PluginExecutionResult(
                    pluginId = pluginId, toolName = toolName,
                    success = !isErr, output = output.take(8000),
                    durationMs = finished - started,
                    error = if (isErr) "plugin_runtime_error" else null
                )
            } catch (e: Exception) {
                val finished = System.currentTimeMillis()
                PluginExecutionResult(
                    pluginId = pluginId, toolName = toolName, success = false,
                    output = "Browser error: ${e.message}",
                    durationMs = finished - started, error = e.message
                )
            }
        }

        // Python execution (default)
        val wrapper = """
            |import json, runpy, sys, traceback
            |__forge_args__ = json.loads(${pyQuote(argsJson)})
            |__forge_globals__ = runpy.run_path(${pyQuote(entrypointPath)})
            |__forge_fn__ = __forge_globals__.get(${pyQuote(toolName)})
            |if __forge_fn__ is None:
            |    print("PLUGIN_ERROR: tool function '$toolName' not defined in entrypoint")
            |else:
            |    try:
            |        try:
            |            # Try keyword arguments first (standard Forge style)
            |            __forge_result__ = __forge_fn__(**__forge_args__)
            |        except TypeError:
            |            # Fallback to single positional argument (dict)
            |            __forge_result__ = __forge_fn__(__forge_args__)
            |        
            |        if __forge_result__ is None:
            |            print("")
            |        elif isinstance(__forge_result__, str):
            |            print(__forge_result__)
            |        else:
            |            print(json.dumps(__forge_result__))
            |    except Exception as e:
            |        print("PLUGIN_RUNTIME_ERROR:")
            |        traceback.print_exc()
        """.trimMargin()

        val timeout = configRepository.get().toolRegistry.toolTimeouts["python_run"] ?: 30
        val result = sandboxManager.executePython(wrapper, profile = "plugin", timeoutSeconds = timeout)
        val finished = System.currentTimeMillis()

        return result.fold(
            onSuccess = { output ->
                val isErr = output.contains("PLUGIN_ERROR:") || output.contains("PLUGIN_RUNTIME_ERROR:")
                val executionResult = PluginExecutionResult(
                    pluginId = pluginId, toolName = toolName,
                    success = !isErr, output = output.take(8000),
                    durationMs = finished - started,
                    error = if (isErr) "plugin_runtime_error" else null
                )
                
                // Enhanced Integration: Learn plugin usage patterns
                try {
                    userPreferencesManager.recordInteractionPattern("uses_plugin_$pluginId", 1)
                    userPreferencesManager.recordInteractionPattern("uses_tool_$toolName", 1)
                    
                    if (executionResult.success) {
                        reflectionManager.recordPattern(
                            pattern = "Successful plugin execution: $toolName",
                            description = "Plugin '$pluginId' tool '$toolName' executed successfully in ${executionResult.durationMs}ms",
                            applicableTo = listOf("plugin_usage", toolName, pluginId),
                            tags = listOf("plugin_success", "tool_execution", "automation")
                        )
                    } else {
                        reflectionManager.recordFailureAndRecovery(
                            taskId = "plugin_${pluginId}_${toolName}_${System.currentTimeMillis()}",
                            failureReason = "Plugin execution failed: ${executionResult.error}",
                            recoveryStrategy = "Check plugin configuration, update plugin, or use alternative tool",
                            tags = listOf("plugin_failure", "tool_error", pluginId, toolName)
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record plugin execution patterns")
                }
                
                executionResult
            },
            onFailure = { e ->
                val executionResult = PluginExecutionResult(
                    pluginId = pluginId, toolName = toolName, success = false,
                    output = "Sandbox error: ${e.message}",
                    durationMs = finished - started, error = e.message
                )
                
                // Enhanced Integration: Record sandbox failures
                try {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "plugin_sandbox_${pluginId}_${System.currentTimeMillis()}",
                        failureReason = "Plugin sandbox error: ${e.message}",
                        recoveryStrategy = "Check sandbox configuration, restart sandbox, or use alternative execution method",
                        tags = listOf("sandbox_failure", "plugin_error", pluginId, toolName)
                    )
                } catch (e2: Exception) {
                    Timber.w(e2, "Failed to record sandbox failure")
                }
                
                executionResult
            }
        )
    }

    fun summary(): String {
        val plugins = listPlugins()
        val enabled = plugins.count { it.enabled }
        val toolCount = toolIndex.size
        return "🧩 Plugins: ${plugins.size} installed ($enabled enabled, $toolCount tools)"
    }

    // ─── Internals ───────────────────────────────────────────────────────────

    /**
     * Phase R — scaffold a brand-new user plugin from inside the agent loop.
     * Builds a minimal manifest exposing one tool whose body is [pythonCode]
     * (or a stub) and installs it via the normal validation/save path so it
     * shows up in the plugin index immediately.
     */
    fun createPlugin(
        id: String,
        name: String,
        description: String,
        toolName: String,
        toolDescription: String,
        pythonCode: String?,
    ): String {
        val safeId = id.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (safeId.isBlank()) return "❌ Invalid id (must be lowercase alphanumerics/underscores)"
        if (repository.loadManifest(safeId) != null) {
            return "❌ Plugin '$safeId' already exists. Uninstall first or pick a new id."
        }
        val safeToolName = toolName.lowercase().filter { it.isLetterOrDigit() || it == '_' }
            .ifBlank { safeId }
        val manifest = PluginManifest(
            id = safeId,
            name = name.ifBlank { safeId },
            description = description,
            entrypoint = "main.py",
            tools = listOf(
                PluginTool(
                    name = safeToolName,
                    description = toolDescription.ifBlank { description.ifBlank { "Auto-generated tool" } },
                    params = mapOf("input" to "string:Free-form input passed to the tool body"),
                )
            ),
            source = "user",
            minApiVersion = 1,
        )
        val manifestJson = json.encodeToString(PluginManifest.serializer(), manifest)
        val body = (pythonCode ?: "").trim()
        val entrypoint = if (body.contains("def $safeToolName") || body.contains("def ${safeId}")) {
            // Agent provided a full definition already
            "# User-supplied plugin implementation\n$body"
        } else {
            buildString {
                appendLine("# Auto-generated by Forge plugin_create on ${Date()}")
                appendLine("# Edit this file under workspace/.plugins/$safeId/main.py to customize.")
                appendLine("TOOL_NAME = ${pyQuote(safeToolName)}")
                appendLine()
                appendLine("def ${safeToolName}(**args):")
                if (body.isBlank()) {
                    // An empty body is a Python syntax error — use a safe default stub.
                    appendLine("    return f\"{TOOL_NAME!r} called with: {args!r}\"")
                } else {
                    // Indent every line of the supplied body by 4 spaces.
                    body.lineSequence().forEach { appendLine("    $it") }
                }
            }
        }
        return install(manifestJson, entrypoint, source = "user").fold(
            onSuccess = { "✅ Created plugin '${safeId}' with tool '${safeToolName}'. It is callable now." },
            onFailure = { "❌ Failed to install scaffolded plugin: ${it.message}" },
        )
    }

    /** Phase Q — explicit re-export of every installed user plugin. */
    fun reExportAll(): List<String> {
        val out = mutableListOf<String>()
        listPlugins().filter { it.source != "builtin" }.forEach { mf ->
            val dir = repository.entrypointFile(mf.id, mf.entrypoint).parentFile ?: return@forEach
            exporter.exportPlugin(mf, dir).onSuccess { out += mf.id }
        }
        return out
    }

    @Synchronized
    private fun rebuildIndex() {
        val map = LinkedHashMap<String, Pair<String, PluginTool>>()
        listPlugins()
            .filter { it.enabled }
            .filter {
                val why = PluginCompatibility.reasonIfIncompatible(it)
                if (why != null) Timber.w("PluginManager: skipping incompatible plugin: $why")
                why == null
            }
            .forEach { manifest ->
            manifest.tools.forEach { tool ->
                if (tool.name in map) {
                    Timber.w("PluginManager: tool '${tool.name}' already registered by ${map[tool.name]!!.first}; skipping ${manifest.id}")
                } else {
                    map[tool.name] = manifest.id to tool
                }
            }
        }
        toolIndex = map
    }

    private fun pyQuote(s: String): String {
        // Triple-quoted Python literal: escape backslashes, triple-quotes, and
        // newlines/carriage-returns (bare newlines inside a triple-quoted string
        // are legal but would produce a multi-line string constant for a value
        // like a tool name, which is always single-line — escaping them prevents
        // silent breakage if someone passes a name with an embedded newline).
        val safe = s
            .replace("\\", "\\\\")
            .replace("\"\"\"", "\\\"\\\"\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return "\"\"\"$safe\"\"\""
    }

    /**
     * Render an arbitrary Kotlin value as JSON. The args map fed to a plugin
     * comes from the agent's parsed JSON tool call, so values can already be
     * Numbers, Booleans, Lists, Maps or Strings. We must emit each as the
     * matching JSON token instead of stringifying everything — otherwise the
     * Python side receives "2" when it asked for 2 and `range(num_dice)`
     * blows up with TypeError. JsonElements (e.g. when callers pre-parsed)
     * are passed through unchanged.
     */
    private fun jsonifyAny(v: Any?): String = when (v) {
        null -> "null"
        is kotlinx.serialization.json.JsonElement -> v.toString()
        is Boolean -> v.toString()
        is Int, is Long, is Short, is Byte -> v.toString()
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Number -> v.toString()
        is Map<*, *> -> buildString {
            append('{')
            var first = true
            for ((k, vv) in v) {
                if (!first) append(',')
                first = false
                val ks = (k?.toString() ?: "")
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                append('"').append(ks).append('"').append(':')
                append(jsonifyAny(vv))
            }
            append('}')
        }
        is Collection<*> -> v.joinToString(",", "[", "]") { jsonifyAny(it) }
        is Array<*> -> v.joinToString(",", "[", "]") { jsonifyAny(it) }
        else -> {
            val s = v.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            "\"$s\""
        }
    }
}
