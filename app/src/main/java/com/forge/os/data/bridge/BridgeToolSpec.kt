package com.forge.os.data.bridge

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Parses the JSON tool manifest returned by [IForgeBridgeService.getToolManifest()]
 * into Forge OS [ToolDefinition] objects that the agent can use directly.
 *
 * Manifest JSON array schema (each element):
 * {
 *   "name":        "tool_name",
 *   "description": "What this tool does",
 *   "params": {
 *     "param_key": {
 *       "type":        "string|integer|number|boolean|array|object",
 *       "description": "What it means",
 *       "required":    true          // optional — false if absent
 *     }
 *   }
 * }
 *
 * Bridge info JSON schema:
 * {
 *   "id":          "com.example.app",
 *   "name":        "App Name",
 *   "version":     "1.0.0",
 *   "description": "Short description",
 *   "icon_uri":    "content://..."    // optional
 * }
 */
data class BridgeInfo(
    val id:          String,
    val name:        String,
    val version:     String = "?",
    val description: String = "",
    val iconUri:     String? = null,
)

data class BridgeToolParam(
    val type:        String,
    val description: String,
    val required:    Boolean = false,
    val enumValues:  List<String>? = null,
)

data class BridgeToolEntry(
    val name:        String,
    val description: String,
    val params:      Map<String, BridgeToolParam>,
)

object BridgeManifestParser {

    fun parseBridgeInfo(json: String): BridgeInfo? = runCatching {
        val o = JSONObject(json)
        BridgeInfo(
            id          = o.optString("id", "unknown"),
            name        = o.optString("name", "Unknown Bridge"),
            version     = o.optString("version", "?"),
            description = o.optString("description", ""),
            iconUri     = o.optString("icon_uri").takeIf { it.isNotBlank() },
        )
    }.onFailure { Timber.w(it, "parseBridgeInfo failed") }.getOrNull()

    fun parseToolManifest(json: String): List<BridgeToolEntry> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val desc = obj.optString("description", "")
                val params = mutableMapOf<String, BridgeToolParam>()
                val paramsObj = obj.optJSONObject("params")
                if (paramsObj != null) {
                    for (key in paramsObj.keys()) {
                        val p = paramsObj.getJSONObject(key)
                        val type = p.optString("type", "string").let {
                            when (it) {
                                "string", "integer", "number", "boolean", "array", "object" -> it
                                else -> "string"
                            }
                        }
                        val enumArr = p.optJSONArray("enum")
                        val enumVals = if (enumArr != null) {
                            (0 until enumArr.length()).map { j -> enumArr.getString(j) }
                        } else null
                        params[key] = BridgeToolParam(
                            type        = type,
                            description = p.optString("description", ""),
                            required    = p.optBoolean("required", false),
                            enumValues  = enumVals,
                        )
                    }
                }
                BridgeToolEntry(name, desc, params)
            }.onFailure { Timber.w(it, "parse tool entry $i failed") }.getOrNull()
        }
    }.onFailure { Timber.w(it, "parseToolManifest failed") }.getOrElse { emptyList() }

    fun toToolDefinition(entry: BridgeToolEntry, bridgeId: String): ToolDefinition {
        val required = entry.params.filter { (_, p) -> p.required }.keys.toList()
        return ToolDefinition(
            function = FunctionDefinition(
                name = entry.name,
                description = "[bridge:$bridgeId] ${entry.description}",
                parameters = FunctionParameters(
                    properties = entry.params.mapValues { (_, p) ->
                        ParameterProperty(type = p.type, description = p.description, enum = p.enumValues)
                    },
                    required = required,
                ),
            )
        )
    }
}
