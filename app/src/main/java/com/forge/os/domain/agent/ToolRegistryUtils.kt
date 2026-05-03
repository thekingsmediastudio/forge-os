package com.forge.os.domain.agent

import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition

/**
 * Helper to define a tool.
 */
fun tool(
    name: String, description: String,
    properties: Map<String, ParameterProperty>,
    required: List<String> = if (properties.isNotEmpty()) listOf(properties.keys.first()) else emptyList()
) = ToolDefinition(
    function = FunctionDefinition(
        name = name, description = description,
        parameters = FunctionParameters(properties = properties, required = required)
    )
)

/**
 * Helper to define tool parameters.
 * Format: "name" to "type:description"
 */
fun params(vararg entries: Pair<String, String>): Map<String, ParameterProperty> {
    return entries.associate { (name, spec) ->
        val parts = spec.split(":", limit = 2)
        name to ParameterProperty(type = parts[0], description = parts.getOrElse(1) { "" })
    }
}
