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
import okhttp3.FormBody
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

/**
 * Slack Bot adapter using Socket Mode WebSocket for inbound events
 * and the Web API for all outbound operations.
 *
 * Config JSON shape:
 * {
 *   "botToken":  "xoxb-...",   // Bot User OAuth Token
 *   "appToken":  "xapp-..."    // App-Level Token (for Socket Mode)
 * }
 *
 * Required OAuth scopes:
 *   chat:write, files:write, reactions:write, users:read,
 *   channels:read, channels:history
 *
 * Socket Mode must be enabled in the Slack App settings.
 */
class SlackChannel(
    override val config: ChannelConfig,
    private val context: Context,
) : Channel {

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socketWs: WebSocket? = null
    private val running = AtomicBoolean(false)

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

    private fun appToken(): String =
        (configObj()?.get("appToken") as? JsonPrimitive)?.content.orEmpty()

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun start() {
        if (running.getAndSet(true)) return
        val bot = botToken()
        val app = appToken()
        if (bot.isBlank() || app.isBlank()) {
            Timber.w("SlackChannel: missing botToken or appToken in configJson")
            running.set(false)
            return
        }
        connectSocketMode(app)
    }

    override suspend fun stop() {
        running.set(false)
        socketWs?.close(1000, "stop")
        socketWs = null
    }

    fun shutdown() {
        running.set(false)
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Socket Mode WebSocket
    // ─────────────────────────────────────────────────────────────────────

    private fun connectSocketMode(appToken: String) {
        // Step 1: get the WSS URL from apps.connections.open
        val wsUrl = openConnection(appToken) ?: run {
            Timber.w("SlackChannel: failed to open Socket Mode connection")
            if (running.get()) {
                scope.launch { delay(10_000); if (running.get()) connectSocketMode(appToken) }
            }
            return
        }

        val req = Request.Builder().url(wsUrl).build()
        socketWs = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleSocketMessage(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Timber.w(t, "SlackChannel: Socket Mode failure")
                if (running.get()) {
                    scope.launch { delay(5_000); if (running.get()) connectSocketMode(appToken) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.i("SlackChannel: Socket Mode closed $code $reason")
                if (running.get() && code != 1000) {
                    scope.launch { delay(3_000); if (running.get()) connectSocketMode(appToken) }
                }
            }
        })
    }

    private fun openConnection(appToken: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://slack.com/api/apps.connections.open")
                .header("Authorization", "Bearer $appToken")
                .post(FormBody.Builder().build())
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val ok = root?.get("ok")?.jsonPrimitive?.content?.toBoolean() ?: false
                if (!ok) {
                    Timber.w("SlackChannel: apps.connections.open failed: $body")
                    return null
                }
                root?.get("url")?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            Timber.w(e, "SlackChannel: openConnection failed")
            null
        }
    }

    private suspend fun handleSocketMessage(ws: WebSocket, raw: String) {
        val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val type = (payload["type"] as? JsonPrimitive)?.content ?: return
        val envelopeId = (payload["envelope_id"] as? JsonPrimitive)?.content

        // Always ACK immediately to prevent Slack from retrying
        if (envelopeId != null) {
            ws.send("""{"envelope_id":"$envelopeId"}""")
        }

        when (type) {
            "hello" -> Timber.i("SlackChannel: Socket Mode connected")
            "disconnect" -> {
                Timber.i("SlackChannel: disconnect requested")
                ws.close(1000, "disconnect")
                if (running.get()) {
                    delay(2_000)
                    connectSocketMode(appToken())
                }
            }
            "events_api" -> {
                val event = payload["payload"]?.jsonObject?.get("event")?.jsonObject ?: return
                handleEvent(event)
            }
        }
    }

    private suspend fun handleEvent(event: JsonObject) {
        val eventType = (event["type"] as? JsonPrimitive)?.content ?: return
        if (eventType != "message") return

        // Ignore bot messages and message subtypes (edits, deletes, etc.)
        val subtype = (event["subtype"] as? JsonPrimitive)?.content
        if (subtype != null) return
        val botId = (event["bot_id"] as? JsonPrimitive)?.content
        if (botId != null) return

        val channelId = (event["channel"] as? JsonPrimitive)?.content.orEmpty()
        val userId = (event["user"] as? JsonPrimitive)?.content.orEmpty()
        val text = (event["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val ts = (event["ts"] as? JsonPrimitive)?.content.orEmpty()
        val threadTs = (event["thread_ts"] as? JsonPrimitive)?.contentOrNull
        val messageId = ts.replace(".", "").toLongOrNull()

        // Allow-list check
        val allow = config.allowedChatIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (allow.isNotEmpty() && channelId !in allow && userId !in allow) {
            Timber.i("SlackChannel: ignoring channel $channelId (not in allow-list)")
            return
        }

        // Resolve username
        val username = resolveUsername(userId)

        // Attachments (files)
        var attachmentKind: String? = null
        var attachmentPath: String? = null
        val files = event["files"]?.jsonArray
        if (!files.isNullOrEmpty()) {
            val first = files.first().jsonObject
            val mimeType = (first["mimetype"] as? JsonPrimitive)?.content ?: ""
            val name = (first["name"] as? JsonPrimitive)?.content ?: "file"
            val url = (first["url_private_download"] as? JsonPrimitive)?.content
            attachmentKind = when {
                mimeType.startsWith("image/") -> "photo"
                mimeType.startsWith("video/") -> "video"
                mimeType.startsWith("audio/") -> "audio"
                else -> "document"
            }
            if (url != null) attachmentPath = downloadFile(url, channelId, name)
        }

        val effectiveText = when {
            text.isNotBlank() -> text
            attachmentKind != null -> "[user sent a $attachmentKind: $attachmentPath]"
            else -> return
        }

        // Store thread_ts in fromId so replies go to the right thread
        val replyTarget = if (threadTs != null) "$channelId|$threadTs" else channelId

        _incoming.tryEmit(
            IncomingMessage(
                channelId = config.id,
                channelType = ChannelType.SLACK,
                fromName = username,
                fromId = replyTarget,
                text = effectiveText,
                messageId = messageId,
                attachmentKind = attachmentKind,
                attachmentPath = attachmentPath,
            )
        )
    }

    private fun resolveUsername(userId: String): String {
        return try {
            val req = Request.Builder()
                .url("https://slack.com/api/users.info?user=$userId")
                .header("Authorization", "Bearer ${botToken()}")
                .get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                root?.get("user")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: userId
            }
        } catch (e: Exception) {
            userId
        }
    }

    private fun downloadFile(url: String, channelId: String, filename: String): String? {
        return try {
            val safeChannel = channelId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val targetDir = File(context.filesDir,
                "workspace/downloads/slack/$safeChannel").apply { mkdirs() }
            val out = File(targetDir, "${System.currentTimeMillis()}_$filename")
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${botToken()}").get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            }
            "downloads/slack/$safeChannel/${out.name}"
        } catch (e: Exception) {
            Timber.w(e, "SlackChannel: file download failed")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Outgoing — Web API
    // ─────────────────────────────────────────────────────────────────────

    /** Parse `to` — may be "channelId|threadTs" for thread replies. */
    private fun parseTarget(to: String): Pair<String, String?> {
        val parts = to.split('|', limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else to to null
    }

    private fun apiPost(method: String, jsonBody: String): Pair<Boolean, String> {
        return try {
            val req = Request.Builder()
                .url("https://slack.com/api/$method")
                .header("Authorization", "Bearer ${botToken()}")
                .header("Content-Type", "application/json; charset=utf-8")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val ok = runCatching {
                    json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.content?.toBoolean()
                }.getOrNull() ?: resp.isSuccessful
                ok to body
            }
        } catch (e: Exception) {
            false to (e.message ?: "request failed")
        }
    }

    override suspend fun send(to: String, text: String, guestQueryId: String?, businessConnectionId: String?): OutgoingResult {
        val (channel, threadTs) = parseTarget(to)
        val body = buildString {
            append("""{"channel":"${channel.jsonEscape()}","text":"${text.jsonEscape()}"""")
            if (threadTs != null) append(""","thread_ts":"$threadTs"""")
            append("}")
        }
        val (ok, resp) = apiPost("chat.postMessage", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Slack error: ${resp.take(200)}")
    }

    override suspend fun sendFormatted(to: String, text: String, parseMode: String, guestQueryId: String?, businessConnectionId: String?): OutgoingResult {
        // Slack uses mrkdwn — convert from LLM markdown
        val converted = markdownToSlack(text)
        val (channel, threadTs) = parseTarget(to)
        val chunks = splitForSlack(converted, 3800)
        var last = OutgoingResult(true, "ok")
        for (chunk in chunks) {
            val body = buildString {
                append("""{"channel":"${channel.jsonEscape()}","text":"${chunk.jsonEscape()}","mrkdwn":true""")
                if (threadTs != null) append(""","thread_ts":"$threadTs"""")
                append("}")
            }
            val (ok, resp) = apiPost("chat.postMessage", body)
            last = if (ok) OutgoingResult(true, "ok")
                   else OutgoingResult(false, "Slack error: ${resp.take(200)}")
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
        val (channel, threadTs) = parseTarget(to)
        var lastTs: String? = null
        var lastText = ""
        var lastEditTime = 0L
        val throttleMs = 500L
        return try {
            textFlow.collect { raw ->
                val converted = markdownToSlack(raw).take(3800)
                if (converted == lastText) return@collect
                lastText = converted
                if (lastTs == null) {
                    val body = buildString {
                        append("""{"channel":"${channel.jsonEscape()}","text":"${converted.jsonEscape()}","mrkdwn":true""")
                        if (threadTs != null) append(""","thread_ts":"$threadTs"""")
                        append("}")
                    }
                    val (ok, resp) = apiPost("chat.postMessage", body)
                    if (ok) {
                        lastTs = runCatching {
                            json.parseToJsonElement(resp).jsonObject["ts"]?.jsonPrimitive?.content
                        }.getOrNull()
                        lastEditTime = System.currentTimeMillis()
                    }
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastEditTime >= throttleMs) {
                        val body = """{"channel":"${channel.jsonEscape()}","ts":"$lastTs","text":"${converted.jsonEscape()}","mrkdwn":true}"""
                        apiPost("chat.update", body)
                        lastEditTime = now
                    }
                }
            }
            // Final update
            if (lastTs != null && lastText.isNotBlank()) {
                apiPost("chat.update", """{"channel":"${channel.jsonEscape()}","ts":"$lastTs","text":"${lastText.jsonEscape()}","mrkdwn":true}""")
            }
            OutgoingResult(true, "ok")
        } catch (e: Exception) {
            Timber.w(e, "SlackChannel: streaming failed")
            OutgoingResult(false, e.message ?: "streaming failed")
        }
    }

    override suspend fun sendChatAction(to: String, action: String) {
        // Slack doesn't have a typing indicator in the same way — no-op
    }

    override suspend fun sendFile(to: String, path: String, caption: String?): OutgoingResult {
        val file = resolveFile(path) ?: return OutgoingResult(false, "File not found: $path")
        val (channel, threadTs) = parseTarget(to)
        return try {
            // Step 1: Get upload URL
            val urlResp = run {
                val req = Request.Builder()
                    .url("https://slack.com/api/files.getUploadURLExternal?filename=${file.name}&length=${file.length()}")
                    .header("Authorization", "Bearer ${botToken()}")
                    .get().build()
                http.newCall(req).execute().use { r -> r.body?.string().orEmpty() }
            }
            val urlRoot = runCatching { json.parseToJsonElement(urlResp).jsonObject }.getOrNull()
                ?: return OutgoingResult(false, "Slack: failed to get upload URL")
            val uploadUrl = urlRoot["upload_url"]?.jsonPrimitive?.content
                ?: return OutgoingResult(false, "Slack: no upload_url in response")
            val fileId = urlRoot["file_id"]?.jsonPrimitive?.content
                ?: return OutgoingResult(false, "Slack: no file_id in response")

            // Step 2: Upload the file
            val uploadReq = Request.Builder()
                .url(uploadUrl)
                .post(file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            http.newCall(uploadReq).execute().use { r ->
                if (!r.isSuccessful) return OutgoingResult(false, "Slack: upload failed ${r.code}")
            }

            // Step 3: Complete the upload
            val completeBody = buildString {
                append("""{"files":[{"id":"$fileId"}],"channel_id":"${channel.jsonEscape()}"""")
                if (!caption.isNullOrBlank()) append(""","initial_comment":"${caption.jsonEscape()}"""")
                if (threadTs != null) append(""","thread_ts":"$threadTs"""")
                append("}")
            }
            val (ok, resp) = apiPost("files.completeUploadExternal", completeBody)
            if (ok) OutgoingResult(true, "ok")
            else OutgoingResult(false, "Slack error: ${resp.take(200)}")
        } catch (e: Exception) {
            OutgoingResult(false, e.message ?: "sendFile failed")
        }
    }

    override suspend fun reactToMessage(to: String, messageId: Long, reaction: String): OutgoingResult {
        val (channel, _) = parseTarget(to)
        // Convert messageId back to Slack ts format (stored as long with decimal stripped)
        // We store the ts as a long by stripping the dot — reverse that
        val ts = messageId.toString().let {
            if (it.length > 10) "${it.substring(0, 10)}.${it.substring(10)}" else it
        }
        val body = """{"channel":"${channel.jsonEscape()}","name":"${reaction.jsonEscape()}","timestamp":"$ts"}"""
        val (ok, resp) = apiPost("reactions.add", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Slack error: ${resp.take(200)}")
    }

    override suspend fun replyToMessage(to: String, replyToId: Long, text: String, parseMode: String): OutgoingResult {
        val (channel, _) = parseTarget(to)
        val ts = replyToId.toString().let {
            if (it.length > 10) "${it.substring(0, 10)}.${it.substring(10)}" else it
        }
        val converted = markdownToSlack(text)
        val body = """{"channel":"${channel.jsonEscape()}","text":"${converted.jsonEscape()}","thread_ts":"$ts","mrkdwn":true}"""
        val (ok, resp) = apiPost("chat.postMessage", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Slack error: ${resp.take(200)}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Slack-specific extras (called from ToolRegistry)
    // ─────────────────────────────────────────────────────────────────────

    /** Send an ephemeral message visible only to one user. */
    fun sendEphemeral(channel: String, userId: String, text: String): OutgoingResult {
        val body = """{"channel":"${channel.jsonEscape()}","user":"${userId.jsonEscape()}","text":"${text.jsonEscape()}"}"""
        val (ok, resp) = apiPost("chat.postEphemeral", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Slack error: ${resp.take(200)}")
    }

    /** Delete a message. */
    fun deleteMessage(channel: String, ts: String): OutgoingResult {
        val body = """{"channel":"${channel.jsonEscape()}","ts":"$ts"}"""
        val (ok, resp) = apiPost("chat.delete", body)
        return if (ok) OutgoingResult(true, "ok")
        else OutgoingResult(false, "Slack error: ${resp.take(200)}")
    }

    /** List channels the bot is in. */
    fun listChannels(limit: Int = 100): String {
        return try {
            val req = Request.Builder()
                .url("https://slack.com/api/conversations.list?limit=${limit.coerceIn(1, 200)}&types=public_channel,private_channel")
                .header("Authorization", "Bearer ${botToken()}")
                .get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val channels = root?.get("channels")?.jsonArray ?: return "[]"
                buildString {
                    appendLine("${channels.size} channel(s):")
                    channels.forEach { el ->
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: "?"
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "?"
                        appendLine("• #$name — id=$id")
                    }
                }.trimEnd()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** Get channel history. */
    fun getHistory(channel: String, limit: Int = 20): String {
        return try {
            val req = Request.Builder()
                .url("https://slack.com/api/conversations.history?channel=$channel&limit=${limit.coerceIn(1, 100)}")
                .header("Authorization", "Bearer ${botToken()}")
                .get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val messages = root?.get("messages")?.jsonArray ?: return "(no messages)"
                buildString {
                    messages.reversed().forEach { el ->
                        val obj = el.jsonObject
                        val user = obj["user"]?.jsonPrimitive?.contentOrNull ?: "bot"
                        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val ts = obj["ts"]?.jsonPrimitive?.contentOrNull ?: ""
                        appendLine("[$ts] $user: $text")
                    }
                }.trimEnd()
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
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
        fun splitForSlack(text: String, limit: Int): List<String> {
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
         * Convert LLM markdown to Slack mrkdwn.
         * Slack: *bold*, _italic_, ~strike~, `code`, ```blocks```, <url|text>
         */
        fun markdownToSlack(input: String): String {
            var out = input
            // **bold** / __bold__ → *bold*
            out = out.replace(Regex("\\*\\*(.+?)\\*\\*"), "*$1*")
            out = out.replace(Regex("__(.+?)__"), "*$1*")
            // *italic* / _italic_ → _italic_  (already compatible)
            // ~~strike~~ → ~strike~
            out = out.replace(Regex("~~(.+?)~~"), "~$1~")
            // [text](url) → <url|text>
            out = out.replace(Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)"), "<$2|$1>")
            // Strip HTML tags
            out = out.replace(Regex("<[^>]+>"), "")
            return out
        }
    }
}
