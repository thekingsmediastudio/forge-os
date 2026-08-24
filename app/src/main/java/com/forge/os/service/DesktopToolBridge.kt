package com.forge.os.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Device-side registry for desktop tools (Task 12).
 *
 * - [registerTool]: stores tool metadata announced by the desktop over the
 *   WebSocket (`desktop_tool_register` message).
 * - [storeResult] / [getResult]: results returned by the desktop
 *   (`desktop_tool_result` message), fetched via
 *   GET /api/desktop/tool/{invokeId}/result.
 */
object DesktopToolBridge {

    data class RegisteredTool(
        val name: String,
        val description: String,
        val schema: String
    )

    data class ToolResult(
        val success: Boolean,
        val output: String?,
        val error: String?,
        val timestamp: Long
    )

    private val tools = ConcurrentHashMap<String, RegisteredTool>()
    private val results = ConcurrentHashMap<String, ToolResult>()

    fun registerTool(name: String, description: String, schema: String) {
        tools[name] = RegisteredTool(name, description, schema)
    }

    fun listTools(): List<RegisteredTool> = tools.values.sortedBy { it.name }

    fun storeResult(invokeId: String, success: Boolean, output: String?, error: String?) {
        results[invokeId] = ToolResult(success, output, error, System.currentTimeMillis())
    }

    fun getResult(invokeId: String): ToolResult? = results[invokeId]
}