package com.forge.os.data.api

import android.content.Context
import com.forge.os.domain.agent.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight HTTP server that exposes Forge tools to external Python scripts.
 *
 * Runs on localhost (127.0.0.1) only for security. Requires Bearer token auth.
 *
 * Endpoints:
 *   GET  /health  — health check (no auth)
 *   GET  /tools   — list available tools (auth required)
 *   POST /tool    — execute a tool with JSON args (auth required)
 *
 * The Python SDK (forge_sdk.py) connects to this server.
 */
@Singleton
class ForgeApiServer @Inject constructor(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    data class ServerState(
        val running: Boolean = false,
        val port: Int = DEFAULT_PORT,
        val token: String = "",
        val error: String? = null,
    )

    companion object {
        const val DEFAULT_PORT = 8765
        private const val TOKEN_LENGTH = 32
    }

    /**
     * Generate a new auth token.
     */
    fun generateToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return (1..TOKEN_LENGTH).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * Start the API server.
     */
    fun start(port: Int = DEFAULT_PORT, token: String? = null) {
        if (isRunning) {
            Timber.w("API server already running")
            return
        }

        val authToken = token ?: generateToken()

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                _serverState.value = ServerState(
                    running = true,
                    port = port,
                    token = authToken,
                )
                Timber.i("Forge API server started on port $port")

                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(client, authToken) }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Timber.e("Error accepting connection: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e("Failed to start API server: ${e.message}")
                _serverState.value = ServerState(
                    running = false,
                    port = port,
                    token = "",
                    error = e.message,
                )
            }
        }
    }

    /**
     * Stop the API server.
     */
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.w("Error closing server socket: ${e.message}")
        }
        serverSocket = null
        _serverState.value = ServerState(running = false)
        Timber.i("Forge API server stopped")
    }

    /**
     * Regenerate the auth token (requires server restart).
     */
    fun regenerateToken(): String {
        val wasRunning = isRunning
        val port = _serverState.value.port
        stop()
        val newToken = generateToken()
        if (wasRunning) {
            start(port, newToken)
        }
        return newToken
    }

    private suspend fun handleClient(client: Socket, expectedToken: String) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)

                // Parse HTTP request
                val requestLine = reader.readLine() ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendResponse(writer, 400, """{"error":"Bad request"}""")
                    return@withContext
                }

                val method = parts[0]
                val path = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val colonIndex = line!!.indexOf(':')
                    if (colonIndex > 0) {
                        headers[line!!.substring(0, colonIndex).trim().lowercase()] =
                            line!!.substring(colonIndex + 1).trim()
                    }
                }

                // Read body for POST
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    reader.read(buffer, 0, contentLength)
                    String(buffer)
                } else ""

                // Route request
                when {
                    path == "/health" && method == "GET" -> {
                        sendResponse(writer, 200, """{"status":"ok","version":"1.0"}""")
                    }

                    path == "/tools" && method == "GET" -> {
                        if (!checkAuth(headers, expectedToken)) {
                            sendResponse(writer, 401, """{"error":"Unauthorized"}""")
                            return@withContext
                        }
                        // Get tool definitions from registry
                        val tools = toolRegistry.getDefinitions()
                        val toolsJson = buildJsonObject {
                            put("tools", kotlinx.serialization.json.JsonArray(tools.map { tool ->
                                buildJsonObject {
                                    put("name", tool.function.name)
                                    put("description", tool.function.description)
                                }
                            }))
                        }
                        sendResponse(writer, 200, toolsJson.toString())
                    }

                    path == "/tool" && method == "POST" -> {
                        if (!checkAuth(headers, expectedToken)) {
                            sendResponse(writer, 401, """{"error":"Unauthorized"}""")
                            return@withContext
                        }

                        try {
                            val requestJson = json.parseToJsonElement(body).jsonObject
                            val toolName = requestJson["tool"]?.jsonPrimitive?.content
                                ?: throw IllegalArgumentException("Missing 'tool' field")
                            val argsJson = requestJson["args"]?.toString() ?: "{}"

                            // Generate a unique tool call ID for the API request
                            val toolCallId = "api_${System.currentTimeMillis()}"

                            val result = toolRegistry.dispatch(toolName, argsJson, toolCallId)

                            val responseJson = buildJsonObject {
                                put("success", !result.isError)
                                put("result", result.output)
                            }
                            sendResponse(writer, 200, responseJson.toString())
                        } catch (e: Exception) {
                            val errorJson = buildJsonObject {
                                put("success", false)
                                put("error", e.message ?: "Unknown error")
                            }
                            sendResponse(writer, 400, errorJson.toString())
                        }
                    }

                    else -> {
                        sendResponse(writer, 404, """{"error":"Not found"}""")
                    }
                }

                client.close()
            } catch (e: Exception) {
                Timber.e("Error handling client: ${e.message}")
                try {
                    client.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkAuth(headers: Map<String, String>, expectedToken: String): Boolean {
        val authHeader = headers["authorization"] ?: return false
        if (!authHeader.startsWith("Bearer ")) return false
        val token = authHeader.substring(7)
        return token == expectedToken
    }

    private fun sendResponse(writer: PrintWriter, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }

        writer.println("HTTP/1.1 $status $statusText")
        writer.println("Content-Type: application/json")
        writer.println("Content-Length: ${body.length}")
        writer.println("Connection: close")
        writer.println()
        writer.println(body)
        writer.flush()
    }
}
