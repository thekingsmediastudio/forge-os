package com.forge.os.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase F5 — HTTP transport for MCP. Calls each server via JSON-RPC 2.0 POST
 * to the configured URL. Methods supported: `tools/list`, `tools/call`.
 *
 * The discovered tools are cached so the agent's [ToolRegistry] can list and
 * dispatch them without round-tripping every time `getDefinitions()` runs.
 */
@Singleton
class McpClient @Inject constructor(
    private val repo: McpServerRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val nextId = AtomicLong(1)

    @Volatile private var cache: List<McpToolSpec> = emptyList()

    /** Returns the last cached tool list. Refresh with [refreshTools]. */
    fun cachedTools(): List<McpToolSpec> = cache

    /** Returns the cached tools with their owning server resolved. */
    fun cachedToolsWithServer(): List<Pair<McpServer, McpToolSpec>> {
        val byId = repo.list().associateBy { it.id }
        return cache.mapNotNull { spec ->
            val s = byId[spec.serverId] ?: return@mapNotNull null
            s to spec
        }
    }

    /** Look up a previously discovered tool by qualified name (`mcp.<server>.<tool>`). */
    fun resolveTool(qualifiedName: String): Pair<McpServer, String>? {
        if (!qualifiedName.startsWith("mcp.")) return null
        val parts = qualifiedName.removePrefix("mcp.").split(".", limit = 2)
        if (parts.size != 2) return null
        val server = repo.list().firstOrNull { sanitize(it.name) == parts[0] || it.id == parts[0] }
            ?: return null
        return server to parts[1]
    }

    suspend fun refreshTools(): List<McpToolSpec> = withContext(Dispatchers.IO) {
        val all = mutableListOf<McpToolSpec>()
        for (server in repo.list().filter { it.enabled }) {
            try {
                val tools = listTools(server)
                all += tools
            } catch (e: Exception) {
                Timber.w(e, "MCP server ${server.name} listTools failed")
            }
        }
        cache = all
        all
    }

    suspend fun listTools(server: McpServer): List<McpToolSpec> = withContext(Dispatchers.IO) {
        val resp = rpc(server, "tools/list", null)
        val toolsArr = resp.result?.jsonObject?.get("tools")?.jsonArray ?: return@withContext emptyList()
        toolsArr.map { el ->
            val obj = el.jsonObject
            McpToolSpec(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = obj["inputSchema"],
                serverId = server.id,
            )
        }.filter { it.name.isNotBlank() }
    }

    suspend fun callTool(
        server: McpServer,
        toolName: String,
        args: Map<String, Any?>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val params = buildJsonObject {
                put("name", toolName)
                put("arguments", JsonObject(args.mapValues { (_, v) -> toJsonElement(v) }))
            }
            val resp = rpc(server, "tools/call", params)
            if (resp.error != null) {
                throw RuntimeException("MCP error ${resp.error.code}: ${resp.error.message}")
            }
            val res = resp.result?.jsonObject
            // The MCP spec wraps tool output in a `content` array of typed parts.
            val content = res?.get("content")?.jsonArray
            if (content != null && content.isNotEmpty()) {
                content.joinToString("\n") { part ->
                    val po = part.jsonObject
                    val type = po["type"]?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "text" -> po["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        else -> po.toString()
                    }
                }
            } else {
                res?.toString() ?: ""
            }
        }
    }

    private fun rpc(server: McpServer, method: String, params: JsonElement?): McpRpcResponse {
        val req = McpRpcRequest(id = nextId.getAndIncrement(), method = method, params = params)
        val body = json.encodeToString(McpRpcRequest.serializer(), req).toRequestBody(jsonMedia)
        val builder = Request.Builder().url(server.url).post(body)
        server.authToken?.let { builder.header("Authorization", "Bearer $it") }
        builder.header("Accept", "application/json")
        http.newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("MCP HTTP ${resp.code}: ${raw.take(200)}")
            return json.decodeFromString(McpRpcResponse.serializer(), raw)
        }
    }

    /**
     * Convert a Kotlin value into the most natural [JsonElement] so MCP
     * servers receive numbers as numbers and booleans as booleans rather than
     * stringified blobs (which fail strict JSON-Schema validation server-side).
     */
    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonPrimitive("")
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Int, is Long, is Short, is Byte -> JsonPrimitive((v as Number).toLong())
        is Float, is Double -> JsonPrimitive((v as Number).toDouble())
        is Number -> JsonPrimitive(v)
        is String -> {
            // Strings that *look* like JSON values (objects/arrays/numbers/bools)
            // should be passed through with their real type so the LLM can hand
            // us "[1,2,3]" or "true" without us silently downgrading them.
            val trimmed = v.trim()
            val parsed = runCatching {
                if (trimmed.startsWith("{") || trimmed.startsWith("[") ||
                    trimmed == "true" || trimmed == "false" ||
                    trimmed.toDoubleOrNull() != null) Json.parseToJsonElement(trimmed) else null
            }.getOrNull()
            parsed ?: JsonPrimitive(v)
        }
        else -> JsonPrimitive(v.toString())
    }

    /** Sanitize a server name for use as a tool-name prefix. */
    private fun sanitize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "server" }

    fun qualifiedName(server: McpServer, toolName: String): String =
        "mcp.${sanitize(server.name)}.$toolName"
}
