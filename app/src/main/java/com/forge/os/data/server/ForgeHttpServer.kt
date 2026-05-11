package com.forge.os.data.server

import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.security.SecureKeyStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimalist on-device HTTP API so the user can build external tools (bash,
 * Tasker, a desktop app, a browser extension) that call into Forge OS.
 *
 * Endpoints (all require `Authorization: Bearer <api_key>`):
 *   GET  /api/status              — server health
 *   GET  /api/tools               — list all tools
 *   POST /api/tool                — { "name": "...", "args": { ... } }
 *
 * The key lives in SecureKeyStore under [KEY_ALIAS]. Call [rotateKey] to
 * generate a fresh one if it's ever leaked.
 */
@Singleton
class ForgeHttpServer @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val keyStore: SecureKeyStore,
) {
    companion object {
        const val DEFAULT_PORT = 8789
        const val KEY_ALIAS = "forge_http_api_key"
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private var currentPort: Int = DEFAULT_PORT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isRunning(): Boolean = running.get()
    fun port(): Int = currentPort

    fun apiKey(): String {
        val existing = keyStore.getCustomKey(KEY_ALIAS)
        if (!existing.isNullOrBlank()) return existing
        return rotateKey()
    }

    fun rotateKey(): String {
        val key = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        keyStore.saveCustomKey(KEY_ALIAS, key)
        return key
    }

    @Synchronized
    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = ServerSocket(port)
            currentPort = port
            running.set(true)
            apiKey() // ensure key exists
            job = scope.launch { acceptLoop() }
            Timber.i("ForgeHttpServer: listening on $port")
            true
        } catch (e: Exception) {
            Timber.e(e, "ForgeHttpServer: failed to bind on $port")
            running.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running.get()) return
        running.set(false)
        runCatching { serverSocket?.close() }
        job?.cancel()
        serverSocket = null
        Timber.i("ForgeHttpServer: stopped")
    }

    private suspend fun acceptLoop() {
        val sock = serverSocket ?: return
        while (running.get() && !sock.isClosed) {
            val client = try { sock.accept() } catch (_: Exception) { return }
            scope.launch { handle(client) }
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { sock ->
            try {
                val input = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                val headers = mutableMapOf<String, String>()
                var line: String?
                var contentLength = 0
                while (input.readLine().also { line = it } != null) {
                    val ln = line ?: break
                    if (ln.isEmpty()) break
                    val idx = ln.indexOf(':')
                    if (idx > 0) {
                        val k = ln.substring(0, idx).trim().lowercase()
                        val v = ln.substring(idx + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                // Auth
                val auth = headers["authorization"].orEmpty()
                val token = auth.removePrefix("Bearer ").trim()
                if (token != apiKey()) {
                    respond(sock, 401, "application/json",
                        """{"error":"unauthorized"}""")
                    return
                }

                val response: String = when {
                    method == "GET" && path == "/api/status" -> {
                        buildJsonObject {
                            put("status", "ok")
                            put("port", currentPort)
                            put("running", true)
                            put("server", "Forge OS HTTP")
                        }.toString()
                    }
                    method == "GET" && path == "/api/tools" -> {
                        val tools = toolRegistry.getDefinitions()
                        buildJsonObject {
                            put("tools", buildJsonArray {
                                tools.forEach { t ->
                                    add(buildJsonObject {
                                        put("name", t.function.name)
                                        put("description", t.function.description)
                                    })
                                }
                            })
                        }.toString()
                    }
                    method == "POST" && path == "/api/tool" -> {
                        val obj = runCatching { Json.parseToJsonElement(body).let { it as JsonObject } }
                            .getOrNull()
                        val name = (obj?.get("name") as? JsonPrimitive)?.content
                        val args = obj?.get("args")?.toString() ?: "{}"
                        if (name.isNullOrBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing 'name'"}""")
                            return
                        }
                        val result = toolRegistry.dispatch(name, args, "http_${System.currentTimeMillis()}")
                        buildJsonObject {
                            put("ok", !result.isError)
                            put("output", result.output)
                            if (result.isError) put("error", result.output)
                        }.toString()
                    }
                    else -> {
                        respond(sock, 404, "application/json", """{"error":"not found"}""")
                        return
                    }
                }
                respond(sock, 200, "application/json", response)
            } catch (e: Exception) {
                Timber.w(e, "ForgeHttpServer: handler error")
                runCatching {
                    val safeMsg = (e.message ?: "internal error")
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                    respond(sock, 500, "application/json", """{"error":"$safeMsg"}""")
                }
            }
        }
    }

    private fun respond(sock: Socket, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
