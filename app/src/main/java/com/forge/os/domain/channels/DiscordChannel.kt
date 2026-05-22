package com.forge.os.domain.channels

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Discord Bot adapter using the Gateway WebSocket (v10) for inbound events
 * and the REST API for all outbound operations.
 *
 * Config JSON shape:
 * {
 *   "botToken": "Bot <token>",
 *   "guildId":  "optional — restricts to one server"
 * }
 *
 * Required Gateway Intents (decimal 33281):
 *   GUILDS (1), GUILD_MESSAGES (512), MESSAGE_CONTENT (32768), DIRECT_MESSAGES (4096)
 *
 * Capabilities:
 *   send / sendFormatted / sendFile / reactToMessage / replyToMessage
 *   createThread / manageRoles / deleteMessage / pinMessage
 *   Streaming via editMessage (word-by-word)
 *   Typing indicator via POST /channels/{id}/typing
 */
class DiscordChannel(
    override val config: ChannelConfig,
    private val context: Context,
) : Channel {

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var gatewayWs: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val running = AtomicBoolean(false)
    private val lastSeq = AtomicLong(-1)
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null

    override val isRunning: Boolean get() = running.get()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun configObj(): JsonObject? = runCatching {
        json.parseToJsonElement(config.configJson).jsonObject
    }.getOrNull()

    private fun botToken(): String =
        (configObj()?.get("botToken") as? JsonPrimitive)?.content.orEmpty()

    private fun authHeader(): String {
        val tok = botToken()
        return if (tok.startsWith("Bot ")) tok else "Bot $tok"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun start() {
        if (running.getAndSet(true)) return
        val token = botToken()
        if (token.isBlank()) {
            Timber.w("DiscordChannel: missing botToken in configJson")
            running.set(false)
            return
        }
        connectGateway()
    }

    override suspend fun stop() {
        running.set(false)
        heartbeatJob?.cancel()
        gatewayWs?.close(1000, "stop")
        gatewayWs = null
    }

    fun shutdown() {
        running.set(false)
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Gateway WebSocket
    // ─────────────────────────────────────────────────────────────────────

    private fun connectGateway() {
        val url = resumeGatewayUrl ?: "wss://gateway.discord.gg/?v=10&encoding=json"
        val req = Request.Builder().url(url).build()
        gatewayWs = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleGatewayMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Timber.w(t, "DiscordChannel: WebSocket failure")
                if (running.get()) {
                    scope.launch {
                        delay(5_000)
                        if (running.get()) connectGateway()
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.i("DiscordChannel: WebSocket closed $code $reason")
                if (running.get() && code != 1000) {
                    scope.launch {
                        delay(3_000)
                        if (running.get()) connectGateway()
                    }
                }
            }
        })
    }

    private suspend fun handleGatewayMessage(raw: String) {
        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val op = (payload["op"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
        val seq = (payload["s"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (seq != null) lastSeq.set(seq)

        when (op) {
            10 -> { // Hello — start heartbeat + identify
                val interval = payload["d"]?.jsonObject?.get("heartbeat_interval")
                    ?.jsonPrimitive?.content?.toLongOrNull() ?: 41250L
                startHeartbeat(interval)
                if (sessionId != null) sendResume() else sendIdentify()
            }
            0  -> handleDispatch(payload) // Dispatch
            1  -> sendHeartbeat()         // Heartbeat request
            7  -> { // Reconnect
                gatewayWs?.close(1000, "reconnect")
                delay(1_000)
                if (running.get()) connectGateway()
            }
            9  -> { // Invalid session
                sessionId = null
                resumeGatewayUrl = null
                delay(5_000)
                if (running.get()) connectGateway()
            }
            11 -> { /* Heartbeat ACK — no-op */ }
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running.get()) {
                delay(intervalMs)
                sendHeartbeat()
            }
        }
    }

    private fun sendHeartbeat() {
        val seq = lastSeq.get().let { if (it < 0) "null" else it.toString() }
        gatewayWs?.send("""{"op":1,"d":$seq}""")
    }

    private fun sendIdentify() {
        // Intents: GUILDS(1) + GUILD_MESSAGES(512) + MESSAGE_CONTENT(32768) + DIRECT_MESSAGES(4096) = 37377
        val payload = """
            {"op":2,"d":{"token":"${botToken()}","intents":37377,
            "properties":{"os":"android","browser":"forge-os","device":"forge-os"}}}
        """.trimIndent()
        gatewayWs?.send(payload)
    }

    private fun sendResume() {
        val payload = """
            {"op":6,"d":{"token":"${botToken()}","session_id":"$sessionId","seq":${lastSeq.get()}}}
        """.trimIndent()
        gatewayWs?.send(payload)
    }

    private suspend fun handleDispatch(payload: JsonObject) {
        val t = (payload["t"] as? JsonPrimitive)?.content ?: return
        val d = payload["d"]?.jsonObject ?: return

        when (t) {
            "READY" -> {
                sessionId = (d["session_id"] as? JsonPrimitive)?.content
                resumeGatewayUrl = (d["resume_gateway_url"] as? JsonPrimitive)?.content
                Timber.i("DiscordChannel: READY session=$sessionId")
            }
            "MESSAGE_CREATE" -> handleMessageCreate(d)
        }
    }

    private suspend fun handleMessageCreate(msg: JsonObject) {
        // Ignore bot messages (including our own)
        val author = msg["author"]?.jsonObject
        val isBot = (author?.get("bot") as? JsonPrimitive)?.content?.toBoolean() ?: false
        if (isBot) return

        val channelId = (msg["channel_id"] as? JsonPrimitive)?.content.orEmpty()
        val messageId = (msg["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        val content = (msg["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val authorId = (author?.get("id") as? JsonPrimitive)?.content.orEmpty()
        val username = (author?.get("username") as? JsonPrimitive)?.contentOrNull
            ?: (author?.get("global_name") as? JsonPrimitive)?.contentOrNull
            ?: "unknown"

        // Allow-list check (same pattern as Telegram)
        val allow = config.allowedChatIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (allow.isNotEmpty() && channelId !in allow && authorId !in allow) {
            Timber.i("DiscordChannel: ignoring channel $channelId (not in allow-list)")
            return
        }

        // Attachments
        var attachmentKind: String? = null
        var attachmentPath: String? = null
        val attachments = msg["attachments"]?.jsonArray
        if (!attachments.isNullOrEmpty()) {
            val first = attachments.first().jsonObject
            val url = (first["url"] as? JsonPrimitive)?.content
            val filename = (first["filename"] as? JsonPrimitive)?.content ?: "file"
            val contentType = (first["content_type"] as? JsonPrimitive)?.content ?: ""
            attachmentKind = when {
                contentType.startsWith("image/") -> "photo"
                contentType.startsWith("video/") -> "video"
                contentType.startsWith("audio/") -> "audio"
                else -> "document"
            }
            if (url != null) {
                attachmentPath = downloadAttachment(url, channelId, filename)
            }
        }

        val effectiveText = when {
            content.isNotBlank() -> content
            attachmentKind != null -> "[user sent a $attachmentKind: $attachmentPath]"
            else -> return
        }

        _incoming.tryEmit(
            IncomingMessage(
                channelId = config.id,
                channelType = ChannelType.DISCORD,
                fromName = username,
                fromId = channelId,   // Discord: reply to the same channel
                text = effectiveText,
                messageId = messageId,
                attachmentKind = attachmentKind,
                attachmentPath = attachmentPath,
            )
        )
    }

    private fun downloadAttachment(url: String, channelId: String, filename: String): String? {
        return try {
            val safeChannel = channelId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val targetDir = File(context.filesDir,
                "workspace/downloads/discord/$safeChannel").apply { mkdirs() }
            val out = File(targetDir, "${System.currentTimeMillis()}_$filename")
            val req = Request.Builder().url(url)
                .header("Authorization", authHeader()).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            }
            "downloads/discord/$safeChannel/${out.name}"
        } catch (e: Exception) {
            Timber.w(e, "DiscordChannel: attachment download failed")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Outgoing — REST
    // ─────────────────────────────────────────────────────────────────────

    private fun restPost(path: String, jsonBody: String): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10$path")
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                (resp.isSuccessful) to body
            }
        } catch (e: Exception) {
            false to (e.message ?: "request failed")
        }
    }

    private fun restPatch(path: String, jsonBody: String): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10$path")
                .header("Authorization", authHeader())
                .header("Content-Type", "application/json")
                .patch(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                (resp.isSuccessful) to body
            }
        } catch (e: Exception) {
            false to (e.message ?: "request failed")
        }
    }

    private fun restDelete(path: String): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10$path")
                .header("Authorization", authHeader())
                .delete()
                .build()
            http.newCall(req).execute().use { resp ->
                (resp.isSuccessful) to (resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            false to (e.message ?: "request failed")
        }
    }

    private fun restPut(path: String, jsonBody: String = "{}"): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10$path")
                .header("Authorization", authHeader())
                .put(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                (resp.isSuccessful) to (resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            false to (e.message ?: "request failed")
        }
    }

    override suspend fun send(to: String, text: String, guestQueryId: String?, businessConnectionId: String?): OutgoingResult {
        val escaped = text.jsonEscape()
        val (ok, body) = restPost("/channels/$to/messages", """{"content":"$escaped"}""")
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${body.take(200)}")
    }

    override suspend fun sendFormatted(to: String, text: String, parseMode: String, guestQueryId: String?, businessConnectionId: String?): OutgoingResult {
        // Discord uses its own markdown — convert from common LLM markdown
        val converted = markdownToDiscord(text)
        // Discord message limit is 2000 chars
        val chunks = splitForDiscord(converted, 1900)
        var last = OutgoingResult(true, "ok")
        for (chunk in chunks) {
            val escaped = chunk.jsonEscape()
            val (ok, body) = restPost("/channels/$to/messages", """{"content":"$escaped"}""")
            last = if (ok) OutgoingResult(true, "ok")
                   else OutgoingResult(false, "Discord error: ${body.take(200)}")
            if (!last.success) return last
        }
        return last
    }

    override suspend fun streamFormatted(
        to: String,
        textFlow: kotlinx.coroutines.flow.Flow<String>,
        parseMode: String,
        guestQueryId: String?,
        businessConnectionId: String?,
    ): OutgoingResult {
        if (!config.streamingEnabled) {
            return sendFormatted(to, textFlow.lastOrNull() ?: "", parseMode)
        }
        var lastMessageId: String? = null
        var lastText = ""
        var lastEditTime = 0L
        val throttleMs = 500L
        return try {
            textFlow.collect { raw ->
                val converted = markdownToDiscord(raw).take(1900)
                if (converted == lastText) return@collect
                lastText = converted
                val escaped = converted.jsonEscape()
                if (lastMessageId == null) {
                    val (ok, body) = restPost("/channels/$to/messages", """{"content":"$escaped"}""")
                    if (ok) {
                        lastMessageId = runCatching {
                            json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
                        }.getOrNull()
                        lastEditTime = System.currentTimeMillis()
                    }
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastEditTime >= throttleMs) {
                        restPatch("/channels/$to/messages/$lastMessageId", """{"content":"$escaped"}""")
                        lastEditTime = now
                    }
                }
            }
            // Final edit to flush last content
            if (lastMessageId != null && lastText.isNotBlank()) {
                restPatch("/channels/$to/messages/$lastMessageId", """{"content":"${lastText.jsonEscape()}"}""")
            }
            OutgoingResult(true, "ok")
        } catch (e: Exception) {
            Timber.w(e, "DiscordChannel: streaming failed")
            OutgoingResult(false, e.message ?: "streaming failed")
        }
    }

    override suspend fun sendChatAction(to: String, action: String) {
        // Discord typing indicator
        runCatching {
            restPost("/channels/$to/typing", "{}")
        }
    }

    override suspend fun sendFile(to: String, path: String, caption: String?): OutgoingResult {
        val file = resolveFile(path) ?: return OutgoingResult(false, "File not found: $path")
        return try {
            val mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                else -> "application/octet-stream"
            }
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .apply {
                    if (!caption.isNullOrBlank()) addFormDataPart("content", caption.take(2000))
                }.build()
            val req = Request.Builder()
                .url("https://discord.com/api/v10/channels/$to/messages")
                .header("Authorization", authHeader())
                .post(multipart).build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) OutgoingResult(true, "ok")
                else OutgoingResult(false, "Discord error: ${resp.body?.string()?.take(200)}")
            }
        } catch (e: Exception) {
            OutgoingResult(false, e.message ?: "sendFile failed")
        }
    }

    override suspend fun reactToMessage(to: String, messageId: Long, reaction: String): OutgoingResult {
        // URL-encode the emoji
        val encoded = java.net.URLEncoder.encode(reaction, "UTF-8")
        val (ok, body) = restPut("/channels/$to/messages/$messageId/reactions/$encoded/@me")
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${body.take(200)}")
    }

    override suspend fun replyToMessage(to: String, replyToId: Long, text: String, parseMode: String): OutgoingResult {
        val converted = markdownToDiscord(text)
        val escaped = converted.take(1900).jsonEscape()
        val body = """{"content":"$escaped","message_reference":{"message_id":"$replyToId"}}"""
        val (ok, resp) = restPost("/channels/$to/messages", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${resp.take(200)}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Discord-specific extras (called from ToolRegistry)
    // ─────────────────────────────────────────────────────────────────────

    /** Send a rich embed card. */
    fun sendEmbed(to: String, title: String, description: String, color: Int = 0xFF4500): OutgoingResult {
        val body = """{"embeds":[{"title":"${title.jsonEscape()}","description":"${description.take(4000).jsonEscape()}","color":$color}]}"""
        val (ok, resp) = restPost("/channels/$to/messages", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${resp.take(200)}")
    }

    /** Create a thread from a message. Returns the thread channel id. */
    fun createThread(channelId: String, messageId: Long, name: String, autoArchiveMinutes: Int = 1440): Pair<String?, OutgoingResult> {
        val body = """{"name":"${name.take(100).jsonEscape()}","auto_archive_duration":$autoArchiveMinutes}"""
        val (ok, resp) = restPost("/channels/$channelId/messages/$messageId/threads", body)
        if (!ok) return null to OutgoingResult(false, "Discord error: ${resp.take(200)}")
        val threadId = runCatching {
            json.parseToJsonElement(resp).jsonObject["id"]?.jsonPrimitive?.content
        }.getOrNull()
        return threadId to OutgoingResult(true, "ok")
    }

    /** Add or remove a role from a guild member. */
    fun manageRole(guildId: String, userId: String, roleId: String, add: Boolean): OutgoingResult {
        val (ok, resp) = if (add)
            restPut("/guilds/$guildId/members/$userId/roles/$roleId")
        else
            restDelete("/guilds/$guildId/members/$userId/roles/$roleId")
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${resp.take(200)}")
    }

    /** List members of a guild (up to 100). */
    fun listMembers(guildId: String, limit: Int = 100): String {
        return try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10/guilds/$guildId/members?limit=${limit.coerceIn(1, 100)}")
                .header("Authorization", authHeader()).get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Discord error: ${body.take(200)}"
                val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
                    ?: return "[]"
                buildString {
                    appendLine("${arr.size} member(s):")
                    arr.forEach { el ->
                        val obj = el.jsonObject
                        val user = obj["user"]?.jsonObject
                        val id = user?.get("id")?.jsonPrimitive?.content ?: "?"
                        val name = user?.get("username")?.jsonPrimitive?.content ?: "?"
                        val nick = obj["nick"]?.jsonPrimitive?.contentOrNull
                        appendLine("• $name${if (nick != null) " ($nick)" else ""} — id=$id")
                    }
                }.trimEnd()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** List channels in a guild. */
    fun listChannels(guildId: String): String {
        return try {
            val req = Request.Builder()
                .url("https://discord.com/api/v10/guilds/$guildId/channels")
                .header("Authorization", authHeader()).get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Discord error: ${body.take(200)}"
                val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
                    ?: return "[]"
                buildString {
                    appendLine("${arr.size} channel(s):")
                    arr.forEach { el ->
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: "?"
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val type = obj["type"]?.jsonPrimitive?.content ?: "?"
                        appendLine("• #$name — id=$id type=$type")
                    }
                }.trimEnd()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** Pin a message in a channel. */
    fun pinMessage(channelId: String, messageId: Long): OutgoingResult {
        val (ok, resp) = restPut("/channels/$channelId/pins/$messageId")
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${resp.take(200)}")
    }

    /** Delete a message. */
    fun deleteMessage(channelId: String, messageId: Long): OutgoingResult {
        val (ok, resp) = restDelete("/channels/$channelId/messages/$messageId")
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Discord error: ${resp.take(200)}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun resolveFile(path: String): File? {
        val direct = File(path)
        if (direct.isAbsolute && direct.exists()) return direct
        val ws = File(context.filesDir, "workspace").resolve(path.trimStart('/'))
        return if (ws.exists()) ws else null
    }

    private fun String.jsonEscape(): String =
        this.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t")

    companion object {
        fun splitForDiscord(text: String, limit: Int): List<String> {
            if (text.length <= limit) return listOf(text)
            val out = mutableListOf<String>()
            var remaining = text
            while (remaining.length > limit) {
                val cut = remaining.lastIndexOf("\n\n", limit).takeIf { it > limit / 2 }
                    ?: remaining.lastIndexOf('\n', limit).takeIf { it > limit / 2 }
                    ?: limit
                out.add(remaining.substring(0, cut))
                remaining = remaining.substring(cut).trimStart('\n')
            }
            if (remaining.isNotEmpty()) out.add(remaining)
            return out
        }

        /**
         * Convert LLM markdown to Discord markdown.
         * Discord uses **bold**, *italic*, ~~strike~~, `code`, ```blocks```, [text](url).
         * Most of this is already compatible — we just normalise __bold__ → **bold**
         * and strip HTML tags that Telegram uses.
         */
        fun markdownToDiscord(input: String): String {
            var out = input
            // __bold__ → **bold**
            out = out.replace(Regex("__(.+?)__"), "**$1**")
            // Strip any HTML tags that might have leaked in
            out = out.replace(Regex("<[^>]+>"), "")
            return out
        }
    }
}
