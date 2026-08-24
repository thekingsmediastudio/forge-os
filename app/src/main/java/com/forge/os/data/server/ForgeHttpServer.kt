package com.forge.os.data.server

import com.forge.os.data.api.ApiMessage
import com.forge.os.data.api.DeviceMetadata
import com.forge.os.data.api.PairingConfirmRequest
import com.forge.os.data.api.PairingConfirmResponse
import com.forge.os.data.api.PairingInitiateRequest
import com.forge.os.data.api.PairingInitiateResponse
import com.forge.os.data.api.ToolCancelResponse
import com.forge.os.data.api.ToolDefinition
import com.forge.os.data.api.ToolStatusResponse
import com.forge.os.domain.agent.AgentEvent
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.service.PairingService
import com.forge.os.service.DesktopToolBridge
import com.forge.os.service.SyncService
import com.forge.os.service.ToolExecutionManager
import com.forge.os.service.NotificationActionStore
import com.forge.os.service.ClipboardService
import com.forge.os.service.ConfigService
import com.forge.os.service.EventBroadcaster
import com.forge.os.data.api.NotificationActionRequest
import com.forge.os.data.api.ClipboardUpdateRequest
import com.forge.os.data.api.ClipboardUpdateResponse
import com.forge.os.data.api.ConfigResponse
import com.forge.os.data.api.ConfigUpdateRequest
import com.forge.os.data.api.ConfigUpdateResponse
import com.forge.os.data.api.FileStatResponse
import com.forge.os.data.api.DesktopToolInvokeRequest
import android.os.Build
import dagger.Lazy
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.NoSuchFileException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimalist on-device HTTP API so the user can build external tools (bash,
 * Tasker, a desktop app, a browser extension) that call into Forge OS.
 *
 * Endpoints (all require `Authorization: Bearer <api_key>`):
 *   GET  /api/status              — server health
 *   GET  /api/tools               — list all tools (full parameter schemas)
 *   POST /api/tool                — { "name": "...", "args": { ... } }
 *   POST /api/chat                — { "message": "...", "session_id": "..."? }
 *                                   → { "ok", "reply", "session_id" }
 *
 * The key lives in SecureKeyStore under [KEY_ALIAS]. Call [rotateKey] to
 * generate a fresh one if it's ever leaked.
 */
@Singleton
class ForgeHttpServer @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val keyStore: SecureKeyStore,
    private val reActAgent: Lazy<ReActAgent>,
    private val pairingService: PairingService,
    private val toolExecutionManager: ToolExecutionManager,
    private val syncService: SyncService,
    private val clipboardService: ClipboardService,
    private val configService: ConfigService,
    private val eventBroadcaster: EventBroadcaster,
    private val webSocketServer: Lazy<ForgeWebSocketServer>
) {
    companion object {
        const val DEFAULT_PORT = 8789
        const val KEY_ALIAS = "forge_http_api_key"
        const val FORGE_OS_VERSION = "1.0.0"

        /** Agent turns can take minutes when tools/LLMs are slow; cap the wait. */
        private const val CHAT_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private var currentPort: Int = DEFAULT_PORT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /** In-memory chat histories keyed by session id (desktop companion support). */
    private val chatSessions = ConcurrentHashMap<String, MutableList<ApiMessage>>()
    
    /** In-memory desktop tokens keyed by desktop_id */
    private val desktopTokens = ConcurrentHashMap<String, String>()

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
            
            // Start WebSocket server
            try {
                webSocketServer.get().start()
                Timber.i("ForgeHttpServer: WebSocket server started on port ${ForgeWebSocketServer.DEFAULT_WS_PORT}")
            } catch (e: Exception) {
                Timber.e(e, "ForgeHttpServer: Failed to start WebSocket server")
            }
            
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
        
        // Stop WebSocket server
        try {
            webSocketServer.get().shutdown()
            Timber.i("ForgeHttpServer: WebSocket server stopped")
        } catch (e: Exception) {
            Timber.e(e, "ForgeHttpServer: Error stopping WebSocket server")
        }
        
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

                val contentType = headers["content-type"] ?: ""
                val isMultipart = contentType.startsWith("multipart/form-data")
                
                // For multipart, read as binary; otherwise read as text
                val bodyBytes = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    val rawInput = sock.getInputStream()
                    while (read < contentLength) {
                        val n = rawInput.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    buf.sliceArray(0 until read)
                } else ByteArray(0)
                
                val body = if (!isMultipart) bodyBytes.toString(Charsets.UTF_8) else ""

                // Auth - pairing endpoints don't require authentication
                val requiresAuth = !(path.startsWith("/api/pairing/"))
                if (requiresAuth) {
                    val auth = headers["authorization"].orEmpty()
                    val token = auth.removePrefix("Bearer ").trim()
                    val authorized = token == apiKey() ||
                        desktopTokens.containsValue(token) ||
                        pairingService.isValidToken(token)
                    if (!authorized) {
                        respond(sock, 401, "application/json",
                            """{"error":"unauthorized"}""")
                        return
                    }
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
                            put("tools", kotlinx.serialization.json.Json.parseToJsonElement(
                                json.encodeToString(ListSerializer(ToolDefinition.serializer()), tools)
                            ))
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
                        // Async tool execution (Task 8.1): return opId immediately.
                        val opId = UUID.randomUUID().toString()
                        val argsMap = runCatching {
                            json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(args)
                        }.getOrDefault(emptyMap())
                        toolExecutionManager.registerOperation(opId, name, argsMap)
                        scope.launch {
                            toolExecutionManager.attachJob(opId, coroutineContext[Job]!!)
                            toolExecutionManager.updateStatus(opId, "running")
                            try {
                                val result = toolRegistry.dispatch(name, args, "http_$opId")
                                if (result.isError) {
                                    toolExecutionManager.setError(opId, com.forge.os.data.api.ToolError(
                                        code = "TOOL_FAILED",
                                        message = result.output,
                                        stackTrace = null
                                    ))
                                } else {
                                    toolExecutionManager.setOutput(opId, result.output)
                                }
                            } catch (e: Exception) {
                                toolExecutionManager.setError(opId, com.forge.os.data.api.ToolError(
                                    code = "INTERNAL",
                                    message = e.message ?: "tool execution failed",
                                    stackTrace = null
                                ))
                            }
                        }
                        buildJsonObject {
                            put("opId", opId)
                            put("ok", true)
                            put("output", "operation started")
                        }.toString()
                    }
                    method == "GET" && path.startsWith("/api/tool/") && path.endsWith("/status") -> {
                        // Extract opId from path: /api/tool/{opId}/status
                        val opId = path.removePrefix("/api/tool/").removeSuffix("/status")
                        if (opId.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing operation id"}""")
                            return
                        }
                        
                        val status = toolExecutionManager.getStatus(opId)
                        if (status == null) {
                            respond(sock, 404, "application/json",
                                """{"error":"operation not found"}""")
                            return
                        }
                        
                        json.encodeToString(ToolStatusResponse.serializer(), status)
                    }
                    method == "POST" && path.startsWith("/api/tool/") && path.endsWith("/cancel") -> {
                        // Extract opId from path: /api/tool/{opId}/cancel
                        val opId = path.removePrefix("/api/tool/").removeSuffix("/cancel")
                        if (opId.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing operation id"}""")
                            return
                        }
                        
                        val cancelled = toolExecutionManager.cancelOperation(opId)
                        val response = ToolCancelResponse(
                            opId = opId,
                            cancelled = cancelled
                        )
                        
                        json.encodeToString(ToolCancelResponse.serializer(), response)
                    }
                    method == "POST" && path == "/api/pairing/initiate" -> {
                        val request = runCatching { 
                            json.decodeFromString(PairingInitiateRequest.serializer(), body) 
                        }.getOrNull()
                        
                        if (request == null || request.desktopName.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing or invalid 'desktop_name'"}""")
                            return
                        }
                        
                        val (pairingCode, expiresIn) = pairingService.generatePairingCode(request.desktopName)
                        val response = PairingInitiateResponse(
                            pairingCode = pairingCode,
                            expiresIn = expiresIn
                        )
                        json.encodeToString(PairingInitiateResponse.serializer(), response)
                    }
                    method == "POST" && path == "/api/pairing/confirm" -> {
                        val request = runCatching { 
                            json.decodeFromString(PairingConfirmRequest.serializer(), body) 
                        }.getOrNull()
                        
                        if (request == null || request.pairingCode.isBlank() || request.desktopId.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing 'pairing_code' or 'desktop_id'"}""")
                            return
                        }
                        
                        // Validate the pairing code
                        val desktopName = pairingService.validateAndConsumePairingCode(request.pairingCode)
                        if (desktopName == null) {
                            respond(sock, 400, "application/json",
                                """{"error":"invalid or expired pairing code"}""")
                            return
                        }
                        
                        // Generate device metadata
                        val deviceId = UUID.randomUUID().toString()
                        
                        // Issue a signed JWT for this desktop client (Task 13.2)
                        val token = pairingService.issueToken(request.desktopId, deviceId)
                        
                        // Store the token mapping
                        desktopTokens[request.desktopId] = token
                        pairingService.saveToken(request.desktopId, token)
                        val deviceMetadata = DeviceMetadata(
                            model = Build.MODEL ?: "Unknown",
                            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
                            forgeOsVersion = FORGE_OS_VERSION,
                            capabilities = listOf("tools", "sync", "clipboard", "notifications", "config")
                        )
                        
                        val response = PairingConfirmResponse(
                            token = token,
                            deviceId = deviceId,
                            deviceMetadata = deviceMetadata
                        )
                        
                        Timber.i("ForgeHttpServer: Desktop '$desktopName' paired with ID ${request.desktopId}")
                        json.encodeToString(PairingConfirmResponse.serializer(), response)
                    }
                    method == "GET" && path == "/api/sync/download" -> {
                        // Extract query parameters
                        val queryString = parts[1].substringAfter('?', "")
                        val params = parseQueryParams(queryString)
                        val filePath = params["path"]
                        
                        if (filePath == null || filePath.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"Missing 'path' query parameter"}""")
                            return
                        }
                        
                        try {
                            // Parse Range header if present
                            val rangeHeader = headers["range"]
                            val (rangeStart, rangeEnd) = parseRangeHeader(rangeHeader)
                            
                            val result = syncService.downloadFile(
                                path = filePath,
                                rangeStart = rangeStart,
                                rangeEnd = rangeEnd
                            )
                            
                            // Respond with binary data
                            val statusCode = if (result.isPartial) 206 else 200
                            respondBinary(
                                sock = sock,
                                status = statusCode,
                                data = result.data,
                                totalSize = result.totalSize,
                                rangeStart = result.rangeStart,
                                rangeEnd = result.rangeEnd
                            )
                            return
                        } catch (e: NoSuchFileException) {
                            respond(sock, 404, "application/json",
                                """{"error":"File not found"}""")
                            return
                        } catch (e: com.forge.os.service.RangeNotSatisfiableException) {
                            respondRangeNotSatisfiable(sock, e.message ?: "Range not satisfiable")
                            return
                        } catch (e: Exception) {
                            Timber.e(e, "SyncDownload error")
                            val safeMsg = (e.message ?: "download failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "POST" && path == "/api/sync/upload" -> {
                        // Parse multipart/form-data
                        val contentTypeHeader = headers["content-type"] ?: ""
                        if (!contentTypeHeader.startsWith("multipart/form-data")) {
                            respond(sock, 400, "application/json",
                                """{"error":"Content-Type must be multipart/form-data"}""")
                            return
                        }
                        
                        val boundary = contentTypeHeader.substringAfter("boundary=").trim()
                        if (boundary.isEmpty()) {
                            respond(sock, 400, "application/json",
                                """{"error":"Missing boundary in Content-Type"}""")
                            return
                        }
                        
                        // Parse multipart parts
                        val parts = parseMultipartBody(bodyBytes, boundary)
                        val pathPart = parts["path"]?.decodeToString()
                        val chunkPart = parts["chunk"]?.decodeToString()?.toIntOrNull()
                        val totalChunksPart = parts["totalChunks"]?.decodeToString()?.toIntOrNull()
                        val checksumPart = parts["checksum"]?.decodeToString()
                        val dataPart = parts["data"]
                        val compressedPart = parts["compressed"]?.decodeToString()?.toBooleanStrictOrNull() ?: false
                        
                        if (pathPart == null || chunkPart == null || totalChunksPart == null || 
                            checksumPart == null || dataPart == null) {
                            respond(sock, 400, "application/json",
                                """{"error":"Missing required fields: path, chunk, totalChunks, checksum, data"}""")
                            return
                        }
                        
                        try {
                            val result = syncService.processChunk(
                                path = pathPart,
                                chunk = chunkPart,
                                totalChunks = totalChunksPart,
                                checksum = checksumPart,
                                data = dataPart,
                                compressed = compressedPart
                            )
                            
                            buildJsonObject {
                                put("uploaded", result.uploaded)
                                put("receivedChunks", Json.parseToJsonElement(json.encodeToString(result.receivedChunks)))
                                put("complete", result.complete)
                            }.toString()
                        } catch (e: Exception) {
                            Timber.e(e, "SyncUpload error")
                            val safeMsg = (e.message ?: "upload failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "POST" && path == "/api/clipboard" -> {
                        val request = runCatching { 
                            json.decodeFromString(ClipboardUpdateRequest.serializer(), body) 
                        }.getOrNull()
                        
                        if (request == null || request.type.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing or invalid request body"}""")
                            return
                        }
                        
                        try {
                            val updated = clipboardService.updateClipboard(request)
                            val response = ClipboardUpdateResponse(updated = updated)
                            json.encodeToString(ClipboardUpdateResponse.serializer(), response)
                        } catch (e: Exception) {
                            Timber.e(e, "Clipboard update error")
                            val safeMsg = (e.message ?: "clipboard update failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "POST" && path == "/api/notification/action" -> {
                        val request = runCatching {
                            json.decodeFromString(NotificationActionRequest.serializer(), body)
                        }.getOrNull()

                        if (request == null || request.notificationId.isBlank() || request.actionId.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing or invalid request body"}""")
                            return
                        }

                        try {
                            val triggered = NotificationActionStore.trigger(
                                request.notificationId,
                                request.actionId
                            )
                            """{"triggered":$triggered}"""
                        } catch (e: Exception) {
                            Timber.e(e, "Notification action trigger error")
                            val safeMsg = (e.message ?: "notification action failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }

                    method == "GET" && path == "/api/config" -> {
                        try {
                            val config = configService.getConfig()
                            json.encodeToString(ConfigResponse.serializer(), config)
                        } catch (e: Exception) {
                            Timber.e(e, "Config get error")
                            val safeMsg = (e.message ?: "failed to get config")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "POST" && path == "/api/config" -> {
                        val request = runCatching { 
                            json.decodeFromString(ConfigUpdateRequest.serializer(), body) 
                        }.getOrNull()
                        
                        if (request == null) {
                            respond(sock, 400, "application/json",
                                """{"error":"invalid request body"}""")
                            return
                        }
                        
                        try {
                            val updated = configService.updateConfig(request)
                            val response = ConfigUpdateResponse(updated = updated)
                            json.encodeToString(ConfigUpdateResponse.serializer(), response)
                        } catch (e: Exception) {
                            Timber.e(e, "Config update error")
                            val safeMsg = (e.message ?: "config update failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "GET" && path == "/api/sync/stat" -> {
                        val queryString = parts[1].substringAfter('?', "")
                        val params = parseQueryParams(queryString)
                        val filePath = params["path"]
                        if (filePath == null || filePath.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"Missing 'path' query parameter"}""")
                            return
                        }
                        try {
                            val stat = syncService.statFile(filePath)
                            json.encodeToString(FileStatResponse.serializer(), stat)
                        } catch (e: Exception) {
                            Timber.e(e, "SyncStat error")
                            val safeMsg = (e.message ?: "stat failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "GET" && path == "/api/desktop/tools" -> {
                        val tools = DesktopToolBridge.listTools().map {
                            buildJsonObject {
                                put("name", it.name)
                                put("description", it.description)
                                put("schema", it.schema)
                            }
                        }
                        buildJsonObject {
                            put("tools", Json.parseToJsonElement(json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(
                                    kotlinx.serialization.json.JsonObject.serializer()
                                ), tools
                            )))
                        }.toString()
                    }
                    method == "POST" && path == "/api/desktop/tool/invoke" -> {
                        val request = runCatching {
                            json.decodeFromString(DesktopToolInvokeRequest.serializer(), body)
                        }.getOrNull()
                        if (request == null || request.toolName.isBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing or invalid request body"}""")
                            return
                        }
                        try {
                            val invokeId = UUID.randomUUID().toString()
                            eventBroadcaster.emitDesktopToolInvoke(
                                invokeId = invokeId,
                                toolName = request.toolName,
                                args = request.args,
                                timeout = request.timeout
                            )
                            buildJsonObject {
                                put("invoke_id", invokeId)
                            }.toString()
                        } catch (e: Exception) {
                            Timber.e(e, "Desktop tool invoke error")
                            val safeMsg = (e.message ?: "desktop tool invoke failed")
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            respond(sock, 500, "application/json",
                                """{"error":"$safeMsg"}""")
                            return
                        }
                    }
                    method == "GET" && path.startsWith("/api/desktop/tool/") && path.endsWith("/result") -> {
                        val invokeId = path.removePrefix("/api/desktop/tool/").removeSuffix("/result")
                        val result = DesktopToolBridge.getResult(invokeId)
                        buildJsonObject {
                            if (result != null) {
                                put("found", true)
                                put("success", result.success)
                                put("output", result.output ?: "")
                                put("error", result.error ?: "")
                            } else {
                                put("found", false)
                            }
                        }.toString()
                    }

                    method == "POST" && path == "/api/chat" -> {
                        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
                        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
                        if (message.isNullOrBlank()) {
                            respond(sock, 400, "application/json",
                                """{"error":"missing 'message'"}""")
                            return
                        }
                        val sessionId = (obj["session_id"] as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: UUID.randomUUID().toString()
                        val history = chatSessions.getOrPut(sessionId) { mutableListOf() }
                        // Serialize turns within a session — interleaved agent
                        // runs would corrupt the shared history.
                        val reply = synchronized(history) { runBlocking { runChatTurn(message, history) } }
                        buildJsonObject {
                            put("ok", true)
                            put("reply", reply)
                            put("session_id", sessionId)
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

    /**
     * Runs one full agent turn and returns the assembled reply text.
     * Tool-call events are folded into the reply so remote clients see what
     * happened; the full history (user + assistant) is appended to [history].
     */
    private suspend fun runChatTurn(message: String, history: MutableList<ApiMessage>): String =
        withTimeout(CHAT_TIMEOUT_MS) {
            val reply = StringBuilder()
            var failed = false
            reActAgent.get().run(message, history.toList())
                .collect { event ->
                    when (event) {
                        is AgentEvent.Response -> {
                            if (reply.isNotEmpty()) reply.append("\n\n")
                            reply.append(event.text)
                        }
                        is AgentEvent.ToolCall ->
                            reply.append("\n\n⚙ ${event.name}")
                        is AgentEvent.ToolResult -> {
                            if (event.isError) {
                                failed = true
                                reply.append("\n❌ ${event.name}: ${event.result.take(300)}")
                            }
                        }
                        is AgentEvent.Error -> {
                            failed = true
                            reply.append("\n\n❌ ${event.message}")
                        }
                        else -> {} // Thinking / Verification / CostApprovalRequired / Done
                    }
                }
            val text = reply.toString().trim().ifBlank { "(no reply)" }
            synchronized(history) {
                history.add(ApiMessage(role = "user", content = message))
                history.add(ApiMessage(role = "assistant", content = text))
                // Keep sessions bounded — retain the most recent 40 messages.
                while (history.size > 40) history.removeAt(0)
            }
            if (failed && text.isBlank()) "❌ Agent turn failed" else text
        }

    /**
     * Parses multipart/form-data body into a map of field names to byte arrays.
     * Handles both text fields and binary data fields.
     */
    private fun parseMultipartBody(body: ByteArray, boundary: String): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        val boundaryBytes = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val endBoundaryBytes = "--$boundary--".toByteArray(Charsets.ISO_8859_1)
        
        var pos = 0
        
        // Find first boundary
        pos = body.indexOf(boundaryBytes, pos)
        if (pos == -1) return parts
        pos += boundaryBytes.size
        
        while (pos < body.size) {
            // Skip CRLF after boundary
            if (pos + 1 < body.size && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
                pos += 2
            }
            
            // Check for end boundary
            if (pos + endBoundaryBytes.size <= body.size && 
                body.sliceArray(pos until pos + endBoundaryBytes.size).contentEquals(endBoundaryBytes)) {
                break
            }
            
            // Parse headers until empty line
            val headers = mutableMapOf<String, String>()
            while (pos < body.size) {
                val lineEnd = body.indexOf("\r\n".toByteArray(Charsets.ISO_8859_1), pos)
                if (lineEnd == -1) break
                
                val line = body.sliceArray(pos until lineEnd).toString(Charsets.ISO_8859_1)
                pos = lineEnd + 2
                
                if (line.isEmpty()) break // Empty line marks end of headers
                
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }
            
            // Extract field name from Content-Disposition header
            val contentDisposition = headers["content-disposition"] ?: ""
            val nameMatch = Regex("""name="([^"]+)"""").find(contentDisposition)
            val fieldName = nameMatch?.groupValues?.get(1) ?: continue
            
            // Find next boundary to determine content length
            val nextBoundaryPos = body.indexOf(boundaryBytes, pos)
            if (nextBoundaryPos == -1) break
            
            // Content is between current pos and next boundary (minus trailing CRLF)
            var contentEnd = nextBoundaryPos
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r'.code.toByte() && 
                body[contentEnd - 1] == '\n'.code.toByte()) {
                contentEnd -= 2
            }
            
            val content = body.sliceArray(pos until contentEnd)
            parts[fieldName] = content
            
            pos = nextBoundaryPos + boundaryBytes.size
        }
        
        return parts
    }
    
    /**
     * Parses query parameters from a query string.
     */
    private fun parseQueryParams(queryString: String): Map<String, String> {
        if (queryString.isBlank()) return emptyMap()
        
        return queryString.split('&')
            .mapNotNull { param ->
                val parts = param.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                    val value = java.net.URLDecoder.decode(parts[1], "UTF-8")
                    key to value
                } else null
            }
            .toMap()
    }
    
    /**
     * Parses HTTP Range header (e.g., "bytes=0-1023").
     * Returns (start, end) or (null, null) if no range specified.
     */
    private fun parseRangeHeader(rangeHeader: String?): Pair<Long?, Long?> {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return null to null
        }
        
        val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
        val parts = rangeSpec.split('-', limit = 2)
        
        if (parts.isEmpty()) return null to null
        
        val start = parts[0].toLongOrNull()
        val end = if (parts.size > 1) parts[1].toLongOrNull() else null
        
        return start to end
    }
    
    /**
     * Helper to find a byte array pattern within another byte array.
     */
    private fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int = 0): Int {
        if (pattern.isEmpty()) return startIndex
        
        outer@ for (i in startIndex..this.size - pattern.size) {
            for (j in pattern.indices) {
                if (this[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
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
    
    /**
     * Sends binary file data response with proper headers for range requests.
     */
    private fun respondBinary(
        sock: Socket,
        status: Int,
        data: ByteArray,
        totalSize: Long,
        rangeStart: Long,
        rangeEnd: Long
    ) {
        val statusText = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            else -> "Error"
        }
        
        val out = sock.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ${data.size}\r\n")
            append("Accept-Ranges: bytes\r\n")
            
            if (status == 206) {
                append("Content-Range: bytes $rangeStart-$rangeEnd/$totalSize\r\n")
            }
            
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }
    
    /**
     * Sends 416 Range Not Satisfiable response.
     */
    private fun respondRangeNotSatisfiable(sock: Socket, message: String) {
        val body = """{"error":"$message"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = sock.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 416 Range Not Satisfiable\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
