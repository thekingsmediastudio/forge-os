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
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Telegram Bot API adapter (long-polling).
 *
 * The config JSON holds:
 *   {
 *     "botToken": "123:abc...",
 *     "defaultChatId": "optional"
 *   }
 *
 * Phase T additions:
 *   • `sendFormatted(...)` for HTML/Markdown parse modes (with chunking +
 *     fallback to plain on Telegram 400).
 *   • `sendChatAction(...)` for "typing" / "record_voice" indicators.
 *   • `sendVoice(...)` for uploading OGG/Opus voice notes.
 *   • Incoming `voice` / `audio` / `photo` / `document` attachments are
 *     downloaded into `workspace/downloads/telegram/<chatId>/...` and
 *     surfaced on [IncomingMessage.attachmentPath].
 *   • Tracks `update_id` AND `messageId` so the agent can quote-reply.
 */
class TelegramChannel(
    override val config: ChannelConfig,
    private val context: Context,
) : Channel {

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    @Volatile private var offset: Long = 0
    @Volatile private var running: Boolean = false

    override val isRunning: Boolean get() = running

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun configObj(): JsonObject? = runCatching {
        json.parseToJsonElement(config.configJson).jsonObject
    }.getOrNull()

    private fun botToken(): String =
        (configObj()?.get("botToken") as? JsonPrimitive)?.content.orEmpty()

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun start() {
        if (running) return
        val token = botToken()
        if (token.isBlank()) {
            Timber.w("TelegramChannel: missing botToken in configJson")
            return
        }
        running = true
        pollJob = scope.launch { pollLoop(token) }
    }

    override suspend fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
    }

    fun shutdown() {
        running = false
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Polling / incoming
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun pollLoop(token: String) {
        while (scope.isActive && running) {
            try {
                val url = "https://api.telegram.org/bot$token/getUpdates" +
                    "?timeout=25&offset=$offset" +
                    "&allowed_updates=%5B%22message%22%5D"
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Timber.w("TelegramChannel: HTTP ${resp.code} — ${body.take(200)}")
                        delay(5_000); return@use
                    }
                    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    val results = (root?.get("result") as? JsonArray) ?: return@use
                    for (updateEl in results) handleUpdate(updateEl.jsonObject, token)
                }
            } catch (e: Exception) {
                Timber.w(e, "TelegramChannel: poll error")
                delay(5_000)
            }
        }
    }

    private suspend fun handleUpdate(update: JsonObject, token: String) {
        val updateId = (update["update_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        offset = maxOf(offset, updateId + 1)

        val msg = (update["message"] as? JsonObject) ?: return
        val messageId = (msg["message_id"] as? JsonPrimitive)?.content?.toLongOrNull()
        val chat = (msg["chat"] as? JsonObject)
        val chatId = (chat?.get("id") as? JsonPrimitive)?.content.orEmpty()
        if (chatId.isBlank()) return

        // Allow-list (poor-man's auth). Empty list = allow everyone.
        val allow = config.allowedChatIds
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (allow.isNotEmpty() && chatId !in allow) {
            Timber.i("TelegramChannel: ignoring chat $chatId (not in allow-list)")
            return
        }

        val fromObj = (msg["from"] as? JsonObject)
        val fromName = (fromObj?.get("username") as? JsonPrimitive)?.contentOrNull
            ?: (fromObj?.get("first_name") as? JsonPrimitive)?.contentOrNull
            ?: "unknown"

        val text = (msg["text"] as? JsonPrimitive)?.contentOrNull
        val caption = (msg["caption"] as? JsonPrimitive)?.contentOrNull

        // ─── Attachments ──────────────────────────────────────────────────
        // We download voice/audio/photo/video/document into the workspace
        // so the agent can reach them. For voice we also surface a
        // placeholder text so the agent has something to react to even
        // though we don't transcribe on-device.
        var attachmentKind: String? = null
        var attachmentPath: String? = null
        val voice = msg["voice"] as? JsonObject
        val audio = msg["audio"] as? JsonObject
        val photoArr = msg["photo"] as? JsonArray
        val document = msg["document"] as? JsonObject
        val video = msg["video"] as? JsonObject
        val videoNote = msg["video_note"] as? JsonObject

        when {
            voice != null -> {
                attachmentKind = "voice"
                val fileId = (voice["file_id"] as? JsonPrimitive)?.content
                attachmentPath = fileId?.let { downloadFile(token, it, chatId, ".ogg") }
            }
            audio != null -> {
                attachmentKind = "audio"
                val fileId = (audio["file_id"] as? JsonPrimitive)?.content
                val ext = (audio["mime_type"] as? JsonPrimitive)?.content
                    ?.substringAfterLast('/')?.let { ".$it" } ?: ".bin"
                attachmentPath = fileId?.let { downloadFile(token, it, chatId, ext) }
            }
            videoNote != null -> {
                attachmentKind = "video_note"
                val fileId = (videoNote["file_id"] as? JsonPrimitive)?.content
                attachmentPath = fileId?.let { downloadFile(token, it, chatId, ".mp4") }
            }
            video != null -> {
                attachmentKind = "video"
                val fileId = (video["file_id"] as? JsonPrimitive)?.content
                attachmentPath = fileId?.let { downloadFile(token, it, chatId, ".mp4") }
            }
            photoArr != null && photoArr.isNotEmpty() -> {
                attachmentKind = "photo"
                // Largest photo is the last one in the size array.
                val biggest = photoArr.last().jsonObject
                val fileId = (biggest["file_id"] as? JsonPrimitive)?.content
                attachmentPath = fileId?.let { downloadFile(token, it, chatId, ".jpg") }
            }
            document != null -> {
                attachmentKind = "document"
                val fileId = (document["file_id"] as? JsonPrimitive)?.content
                val name = (document["file_name"] as? JsonPrimitive)?.content
                val ext = name?.substringAfterLast('.', "")?.let { if (it.isBlank()) ".bin" else ".$it" }
                    ?: ".bin"
                attachmentPath = fileId?.let { downloadFile(token, it, chatId, ext) }
            }
        }

        // Compose the prompt the agent will see.
        val effectiveText = when {
            !text.isNullOrBlank()    -> text
            !caption.isNullOrBlank() -> caption
            attachmentKind == "voice"      -> "[user sent a voice note: $attachmentPath]"
            attachmentKind == "video_note" -> "[user sent a video note: $attachmentPath]"
            attachmentKind == "photo"      -> "[user sent a photo: $attachmentPath]"
            attachmentKind == "audio"      -> "[user sent an audio file: $attachmentPath]"
            attachmentKind == "video"      -> "[user sent a video: $attachmentPath]"
            attachmentKind == "document"   -> "[user sent a document: $attachmentPath]"
            else -> return  // nothing actionable
        }

        _incoming.tryEmit(
            IncomingMessage(
                channelId = config.id,
                channelType = "telegram",
                fromName = fromName,
                fromId = chatId,
                text = effectiveText,
                messageId = messageId,
                attachmentKind = attachmentKind,
                attachmentPath = attachmentPath,
                caption = caption,
            )
        )
    }

    /** Calls `getFile` then downloads the bytes into the workspace.
     *  Returns the workspace-relative path on success, null on failure. */
    private fun downloadFile(token: String, fileId: String, chatId: String, ext: String): String? {
        return try {
            // 1. getFile → file_path on Telegram's CDN
            val getReq = Request.Builder()
                .url("https://api.telegram.org/bot$token/getFile?file_id=$fileId")
                .get().build()
            val filePath: String = http.newCall(getReq).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val res = (root?.get("result") as? JsonObject) ?: return@use null
                (res["file_path"] as? JsonPrimitive)?.content
            } ?: return null

            // 2. GET the binary, stream to disk.
            val safeChat = chatId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val targetDir = File(context.filesDir,
                "workspace/downloads/telegram/$safeChat").apply { mkdirs() }
            val basename = "${System.currentTimeMillis()}_${fileId.takeLast(10)}$ext"
            val out = File(targetDir, basename)

            val cdnReq = Request.Builder()
                .url("https://api.telegram.org/file/bot$token/$filePath").get().build()
            http.newCall(cdnReq).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            }
            // Workspace-relative path (workspace root = ${filesDir}/workspace/).
            "downloads/telegram/$safeChat/$basename"
        } catch (e: Exception) {
            Timber.w(e, "TelegramChannel: file download failed")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Outgoing
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun send(to: String, text: String): OutgoingResult =
        sendInternal(to, text, parseMode = "")

    override suspend fun sendFormatted(to: String, text: String, parseMode: String): OutgoingResult {
        val converted = if (parseMode.equals("HTML", ignoreCase = true))
            markdownToTelegramHtml(text) else text
        // Telegram caps message bodies at 4096 chars; chunk to be safe.
        val chunks = splitForTelegram(converted, 3800)
        var last: OutgoingResult = OutgoingResult(true, "ok")
        for (chunk in chunks) {
            last = sendInternal(to, chunk, parseMode)
            if (!last.success) {
                // Most common failure: "Bad Request: can't parse entities".
                // Retry as plain text — chunk the original too in case it
                // exceeds Telegram's 4096-char limit without formatting.
                Timber.w("TelegramChannel: parse-mode send failed (${last.detail}); retrying plain")
                val plainChunks = splitForTelegram(text, 3800)
                var plainLast: OutgoingResult = OutgoingResult(true, "ok")
                for (plainChunk in plainChunks) {
                    plainLast = sendInternal(to, plainChunk, parseMode = "")
                    if (!plainLast.success) return plainLast
                }
                return plainLast
            }
        }
        return last
    }

    private fun sendInternal(to: String, text: String, parseMode: String): OutgoingResult {
        val token = botToken()
        if (token.isBlank()) return OutgoingResult(false, "Missing botToken")
        return try {
            val builder = FormBody.Builder()
                .add("chat_id", to)
                .add("text", text)
                .add("disable_web_page_preview", "true")
            if (parseMode.isNotBlank()) builder.add("parse_mode", parseMode)
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(builder.build()).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use OutgoingResult(false, "HTTP ${resp.code}: ${body.take(200)}")
                }
                // Telegram may return HTTP 200 with ok=false for logical errors
                // (e.g. bad chat_id, flood-wait). Always verify the JSON body.
                val ok = runCatching {
                    json.parseToJsonElement(body).jsonObject["ok"]
                        ?.jsonPrimitive?.content?.toBoolean()
                }.getOrNull() ?: true
                if (ok) OutgoingResult(true, "ok")
                else {
                    val desc = runCatching {
                        json.parseToJsonElement(body).jsonObject["description"]
                            ?.jsonPrimitive?.content
                    }.getOrNull() ?: body.take(200)
                    OutgoingResult(false, "Telegram error: $desc")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "TelegramChannel: send failed")
            OutgoingResult(false, e.message ?: "send failed")
        }
    }

    override suspend fun sendChatAction(to: String, action: String) {
        val token = botToken()
        if (token.isBlank() || to.isBlank()) return
        runCatching {
            val body = FormBody.Builder()
                .add("chat_id", to).add("action", action).build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendChatAction")
                .post(body).build()
            http.newCall(req).execute().use { /* fire-and-forget */ }
        }.onFailure { Timber.v(it, "TelegramChannel: chat action failed") }
    }

    override suspend fun sendVoice(to: String, audioPath: String, caption: String?): OutgoingResult {
        val file = resolveFile(audioPath) ?: return OutgoingResult(false, "Audio file not found: $audioPath")
        // Show "recording voice..." status in Telegram while we upload.
        sendChatAction(to, "record_voice")
        return sendMultipart(to, "voice", file, "audio/ogg", caption)
    }

    override suspend fun sendFile(to: String, path: String, caption: String?): OutgoingResult {
        val token = botToken()
        if (token.isBlank()) return OutgoingResult(false, "Missing botToken")
        val file = resolveFile(path) ?: return OutgoingResult(false, "File not found: $path")
        val ext = file.extension.lowercase()
        
        return when {
            ext in listOf("jpg", "jpeg", "png", "webp") -> sendPhotoInternal(to, file, caption)
            ext in listOf("mp4", "mov", "m4v") -> sendVideoInternal(to, file, caption)
            else -> sendDocumentInternal(to, file, caption)
        }
    }

    private fun sendPhotoInternal(to: String, file: File, caption: String?): OutgoingResult {
        return sendMultipart(to, "photo", file, "image/jpeg", caption)
    }

    private fun sendVideoInternal(to: String, file: File, caption: String?): OutgoingResult {
        return sendMultipart(to, "video", file, "video/mp4", caption)
    }

    private fun sendDocumentInternal(to: String, file: File, caption: String?): OutgoingResult {
        return sendMultipart(to, "document", file, "application/octet-stream", caption)
    }

    private fun sendMultipart(to: String, type: String, file: File, mime: String, caption: String?): OutgoingResult {
        val token = botToken()
        return try {
            val endpoint = when(type) {
                "photo" -> "sendPhoto"
                "video" -> "sendVideo"
                "document" -> "sendDocument"
                "voice" -> "sendVoice"
                else -> "sendDocument"
            }
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", to)
                .addFormDataPart(type, file.name, file.asRequestBody(mime.toMediaType()))
                .apply {
                    if (!caption.isNullOrBlank()) {
                        addFormDataPart("caption", caption.take(1024))
                        addFormDataPart("parse_mode", "HTML")
                    }
                }.build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/$endpoint")
                .post(multipart).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use OutgoingResult(false, "HTTP ${resp.code}: ${body.take(200)}")
                }
                // Verify Telegram's ok field — HTTP 200 does not guarantee success.
                val ok = runCatching {
                    json.parseToJsonElement(body).jsonObject["ok"]
                        ?.jsonPrimitive?.content?.toBoolean()
                }.getOrNull() ?: true
                if (ok) OutgoingResult(true, "ok")
                else {
                    val desc = runCatching {
                        json.parseToJsonElement(body).jsonObject["description"]
                            ?.jsonPrimitive?.content
                    }.getOrNull() ?: body.take(200)
                    OutgoingResult(false, "Telegram error: $desc")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "TelegramChannel: send$type failed")
            OutgoingResult(false, e.message ?: "send failed")
        }
    }

    override suspend fun reactToMessage(to: String, messageId: Long, reaction: String): OutgoingResult {
        val token = botToken()
        if (token.isBlank()) return OutgoingResult(false, "Missing botToken")
        return try {
            // Telegram expects a JSON array of ReactionType objects.
            // Properly escape the reaction emoji so JSON stays valid even for
            // emoji that contain backslash or double-quote characters.
            val safeReaction = reaction
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val reactionJson = """[{"type":"emoji","emoji":"$safeReaction"}]"""
            val body = FormBody.Builder()
                .add("chat_id", to)
                .add("message_id", messageId.toString())
                .add("reaction", reactionJson)
                .build()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/setMessageReaction")
                .post(body).build()
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use OutgoingResult(false, "HTTP ${resp.code}: ${bodyStr.take(200)}")
                }
                // Telegram can return HTTP 200 with ok=false for logical errors
                // (e.g. reaction not supported, message too old). Always verify.
                val ok = runCatching {
                    json.parseToJsonElement(bodyStr).jsonObject["ok"]
                        ?.jsonPrimitive?.content?.toBoolean()
                }.getOrNull() ?: true
                if (ok) OutgoingResult(true, "ok")
                else {
                    val desc = runCatching {
                        json.parseToJsonElement(bodyStr).jsonObject["description"]
                            ?.jsonPrimitive?.content
                    }.getOrNull() ?: bodyStr.take(200)
                    OutgoingResult(false, "Telegram error: $desc")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "TelegramChannel: react failed")
            OutgoingResult(false, e.message ?: "react failed")
        }
    }

    override suspend fun replyToMessage(to: String, replyToId: Long, text: String, parseMode: String): OutgoingResult {
        val token = botToken()
        if (token.isBlank()) return OutgoingResult(false, "Missing botToken")
        return try {
            val converted = if (parseMode.equals("HTML", ignoreCase = true))
                markdownToTelegramHtml(text) else text
            
            // reply_parameters expects a JSON object
            val replyParams = """{"message_id":$replyToId}"""
            
            val builder = FormBody.Builder()
                .add("chat_id", to)
                .add("text", converted)
                .add("reply_parameters", replyParams)
                .add("disable_web_page_preview", "true")
            if (parseMode.isNotBlank()) builder.add("parse_mode", parseMode)
            
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(builder.build()).build()
            // Capture code + body outside `use {}` so we can call the suspend
            // retry function (`replyToMessage`) after the response is closed —
            // `use {}` takes a non-suspend lambda and calling a suspend function
            // inside it is a compile error.
            val (statusCode, bodyStr) = http.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }

            // On HTML parse failure Telegram returns 400 — retry plain text.
            if (statusCode == 400 && parseMode.isNotBlank()) {
                Timber.w("TelegramChannel: reply parse-mode failed; retrying plain")
                return replyToMessage(to, replyToId, text, parseMode = "")
            }
            if (statusCode !in 200..299) {
                return OutgoingResult(false, "HTTP $statusCode: ${bodyStr.take(200)}")
            }
            // Telegram can return HTTP 200 with ok=false.
            val ok = runCatching {
                json.parseToJsonElement(bodyStr).jsonObject["ok"]
                    ?.jsonPrimitive?.content?.toBoolean()
            }.getOrNull() ?: true
            if (ok) OutgoingResult(true, "ok")
            else {
                val desc = runCatching {
                    json.parseToJsonElement(bodyStr).jsonObject["description"]
                        ?.jsonPrimitive?.content
                }.getOrNull() ?: bodyStr.take(200)
                OutgoingResult(false, "Telegram error: $desc")
            }
        } catch (e: Exception) {
            Timber.w(e, "TelegramChannel: reply failed")
            OutgoingResult(false, e.message ?: "reply failed")
        }
    }

    /** Accepts either an absolute path or a workspace-relative one. */
    private fun resolveFile(path: String): File? {
        val direct = File(path)
        if (direct.isAbsolute && direct.exists()) return direct
        val ws = File(context.filesDir, "workspace").resolve(path.trimStart('/'))
        return if (ws.exists()) ws else null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Markdown → Telegram HTML conversion
    // ─────────────────────────────────────────────────────────────────────

    companion object {
        /** Splits long messages on paragraph / line boundaries so we don't
         *  cut HTML tags in half. Each chunk is <= [limit] chars. */
        fun splitForTelegram(text: String, limit: Int): List<String> {
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

        /** Best-effort conversion from CommonMark-ish text (the kind LLMs
         *  emit) to the subset of HTML Telegram understands.
         *
         *  We escape `&<>` first, then transform fenced + inline code,
         *  bold (`**...**` / `__...__`), italics (`*...*` / `_..._`),
         *  strike-through (`~~...~~`), and links (`[t](url)`). Unknown
         *  markdown is left alone. */
        fun markdownToTelegramHtml(input: String): String {
            // 1. Pull code fences out so we don't mangle their contents.
            val placeholders = mutableListOf<String>()
            var working = input.replace(Regex("```([a-zA-Z0-9_+\\-]*)\\n([\\s\\S]*?)```")) { m ->
                val lang = m.groupValues[1]
                val raw = m.groupValues[2]
                val esc = htmlEscape(raw)
                val html = if (lang.isNotBlank())
                    "<pre><code class=\"language-$lang\">$esc</code></pre>"
                else "<pre>$esc</pre>"
                placeholders += html
                "\u0000PRE${placeholders.size - 1}\u0000"
            }
            working = working.replace(Regex("`([^`\\n]+?)`")) { m ->
                val esc = htmlEscape(m.groupValues[1])
                placeholders += "<code>$esc</code>"
                "\u0000CODE${placeholders.size - 1}\u0000"
            }
            // 2. Escape the rest (links handled below by re-inserting).
            working = htmlEscape(working)
            // 3. Inline transforms.
            working = working.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            working = working.replace(Regex("__(.+?)__"), "<b>$1</b>")
            working = working.replace(Regex("(?<![*\\w])\\*([^*\\n]+?)\\*(?![*\\w])"), "<i>$1</i>")
            working = working.replace(Regex("(?<![_\\w])_([^_\\n]+?)_(?![_\\w])"), "<i>$1</i>")
            working = working.replace(Regex("~~(.+?)~~"), "<s>$1</s>")
            working = working.replace(Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)"))
                { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
            // 4. Re-insert preserved code blocks.
            working = working.replace(Regex("\u0000PRE(\\d+)\u0000")) { placeholders[it.groupValues[1].toInt()] }
            working = working.replace(Regex("\u0000CODE(\\d+)\u0000")) { placeholders[it.groupValues[1].toInt()] }
            return working
        }

        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
