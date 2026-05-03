package com.forge.os.domain.agent.providers

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.data.bridge.BridgeManifestParser
import com.forge.os.data.bridge.ForgeBridgeManager
import com.forge.os.domain.agent.ToolProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes all Forge Bridge app tools as first-class Forge OS agent tools.
 *
 * Any Android app that implements [IForgeBridgeService] and exports a service
 * with action "com.forge.os.bridge.TOOL_PROVIDER" automatically gets its
 * tools listed here — no Forge OS source changes needed.
 *
 * Also exposes management tools:
 *   bridge_list         — list all discovered bridge apps + their tools
 *   bridge_refresh      — re-scan for bridge apps and rebind
 *   bridge_status       — connection status of a specific bridge by package name
 *   bridge_tool_list    — list tools provided by a specific bridge
 */
@Singleton
class BridgeToolProvider @Inject constructor(
    private val bridgeManager: ForgeBridgeManager,
) : ToolProvider {

    // ── Static management tools ───────────────────────────────────────────────

    private val managementTools = listOf(
        tool(
            name = "bridge_list",
            description = "List all installed Forge Bridge apps, their connection status, and the tools each one provides. Bridge apps are third-party Android apps that extend Forge OS with new capabilities.",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "bridge_refresh",
            description = "Re-scan the device for installed Forge Bridge apps and rebind to any new or recovered ones. Use this after installing a new bridge app.",
            params = emptyMap(), required = emptyList(),
        ),
        tool(
            name = "bridge_status",
            description = "Get the connection status and tool manifest of a specific bridge app by its package name.",
            params = mapOf("package" to ("string" to "The Android package name of the bridge app (e.g. com.forge.autophone)")),
            required = listOf("package"),
        ),
        tool(
            name = "bridge_tool_list",
            description = "List all tools provided by a specific bridge app.",
            params = mapOf("package" to ("string" to "Package name of the bridge app")),
            required = listOf("package"),
        ),
    )

    // ── ToolProvider impl ─────────────────────────────────────────────────────

    override fun getTools(): List<ToolDefinition> {
        val bridgeTools = bridgeManager.allTools().map { (pkg, entry) ->
            BridgeManifestParser.toToolDefinition(entry, pkg)
        }
        return managementTools + bridgeTools
    }

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "bridge_list"      -> bridgeManager.summary()
        "bridge_refresh"   -> { bridgeManager.refresh(); "Bridge scan complete.\n" + bridgeManager.summary() }
        "bridge_status"    -> bridgeStatus(args["package"]?.toString() ?: "")
        "bridge_tool_list" -> bridgeToolList(args["package"]?.toString() ?: "")
        else -> {
            if (bridgeManager.handles(toolName)) {
                bridgeManager.dispatch(toolName, buildArgsJson(args))
            } else null
        }
    }

    // ── Management tool impls ─────────────────────────────────────────────────

    private fun bridgeStatus(pkg: String): String {
        if (pkg.isBlank()) return "Error: package required"
        val conn = bridgeManager.connections.value[pkg]
            ?: return "Bridge '$pkg' not found. Run bridge_refresh to re-scan."
        return buildString {
            appendLine("Bridge: ${conn.info?.name ?: pkg}")
            appendLine("Package: $pkg")
            appendLine("Version: ${conn.info?.version ?: "?"}")
            appendLine("Status:  ${if (conn.connected) "✓ Connected" else "✗ ${conn.error ?: "Disconnected"}"}")
            appendLine("Tools:   ${conn.tools.size}")
            conn.info?.description?.takeIf { it.isNotBlank() }?.let {
                appendLine("Desc:    $it")
            }
        }.trim()
    }

    private fun bridgeToolList(pkg: String): String {
        if (pkg.isBlank()) return "Error: package required"
        val conn = bridgeManager.connections.value[pkg]
            ?: return "Bridge '$pkg' not found."
        if (!conn.connected) return "Bridge '$pkg' is not currently connected."
        if (conn.tools.isEmpty()) return "Bridge '$pkg' provides no tools."
        return buildString {
            appendLine("Tools provided by ${conn.info?.name ?: pkg} (${conn.tools.size}):")
            conn.tools.forEach { t ->
                appendLine("  • ${t.name}")
                appendLine("    ${t.description.take(80)}")
                if (t.params.isNotEmpty()) {
                    val req = t.params.filter { (_, p) -> p.required }.keys
                    appendLine("    Params: ${t.params.keys.joinToString()} ${if (req.isNotEmpty()) "(required: ${req.joinToString()})" else ""}")
                }
            }
        }.trim()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildArgsJson(args: Map<String, Any>): String {
        if (args.isEmpty()) return "{}"
        val sb = StringBuilder("{")
        args.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            val valStr = when (v) {
                is String  -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                is Boolean -> v.toString()
                is Number  -> v.toString()
                else       -> "\"${v.toString().replace("\"", "\\\"")}\""
            }
            sb.append("\"$k\":$valStr")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun tool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>,
        required: List<String>,
    ) = ToolDefinition(
        function = FunctionDefinition(
            name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            ),
        )
    )
}
