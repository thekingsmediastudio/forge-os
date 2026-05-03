package com.forge.os.data.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Phase F5 — Model Context Protocol (MCP) wire format.
 * We only implement the HTTP transport (JSON-RPC over POST) since stdio is
 * impractical on Android. Two methods are enough to be useful:
 *   • tools/list  — discover the tools the server exposes
 *   • tools/call  — invoke a tool by name with a JSON args object
 */

@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val authToken: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class McpToolSpec(
    val name: String,
    val description: String = "",
    val inputSchema: JsonElement? = null,
    /** id of the originating server, set by the client when listing. */
    val serverId: String = "",
)

@Serializable
data class McpRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class McpRpcError(
    val code: Int,
    val message: String,
)

@Serializable
data class McpRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: McpRpcError? = null,
)
