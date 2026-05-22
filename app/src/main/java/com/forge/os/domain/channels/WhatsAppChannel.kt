package com.forge.os.domain.channels

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WhatsApp Cloud API adapter.
 *
 * Inbound: webhook POST from Meta servers, received via ForgeHttpServer
 * and delivered here via [deliverWebhook].
 *
 * Outbound: WhatsApp Cloud API (graph.facebook.com/v19.0/{phone_number_id}/messages)
 *
 * Config JSON shape:
 * {
 *   "accessToken":   "EAAx...",   // Meta access token
 *   "phoneNumberId": "123...",    // WhatsApp Business phone number ID
 *   "verifyToken":   "forge_wa"   // Webhook verification token (user-chosen)
 * }
 *
 * Setup:
 *   1. Create a Meta Developer app with WhatsApp product.
 *   2. Register a phone number → receive verification code → verify.
 *   3. Configure the webhook URL to point at ForgeHttpServer /webhook/whatsapp.
 *   4. Paste the access token and phone number ID into the channel config.
 *
 * Note: WhatsApp does not support streaming (no edit-message API).
 * All replies are sent as a single message.
 */
class WhatsAppChannel(
    override val config: ChannelConfig,
    private val context: Context,
) : Channel {

    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    override val isRunning: Boolean get() = running.get()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun configObj(): JsonObject? = runCatching {
        json.parseToJsonElement(config.configJson).jsonObject
    }.getOrNull()

    private fun accessToken(): String =
        (configObj()?.get("accessToken") as? JsonPrimitive)?.content.orEmpty()

    private fun phoneNumberId(): String =
        (configObj()?.get("phoneNumberId") as? JsonPrimitive)?.content.orEmpty()

    fun verifyToken(): String =
        (configObj()?.get("verifyToken") as? JsonPrimitive)?.content ?: "forge_wa"

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle — WhatsApp is webhook-driven, no polling needed
    // ─────────────────────────────────────────────────────────────────────

    override suspend fun start() {
        if (running.getAndSet(true)) return
        val token = accessToken()
        val phoneId = phoneNumberId()
        if (token.isBlank() || phoneId.isBlank()) {
            Timber.w("WhatsAppChannel: missing accessToken or phoneNumberId in configJson")
            running.set(false)
            return
        }
        Timber.i("WhatsAppChannel: started (webhook mode) phoneNumberId=$phoneId")
    }

    override suspend fun stop() {
        running.set(false)
        Timber.i("WhatsAppChannel: stopped")
    }

    fun shutdown() {
        running.set(false)
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inbound — called by ForgeHttpServer when a webhook POST arrives
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parse and emit an incoming WhatsApp webhook payload.
     * ForgeHttpServer calls this after verifying the request signature.
     */
    fun deliverWebhook(rawJson: String) {
        if (!running.get()) return
        scope.launch {
            try {
                parseWebhook(rawJson)
            } catch (e: Exception) {
                Timber.w(e, "WhatsAppChannel: webhook parse error")
            }
        }
    }

    private suspend fun parseWebhook(rawJson: String) {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return
        val entry = root["entry"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
        val changes = entry["changes"]?.jsonArray ?: return

        for (change in changes) {
            val value = change.jsonObject["value"]?.jsonObject ?: continue
            val messages = value["messages"]?.jsonArray ?: continue

            for (msgEl in messages) {
                val msg = msgEl.jsonObject
                val msgType = (msg["type"] as? JsonPrimitive)?.content ?: continue
                val from = (msg["from"] as? JsonPrimitive)?.content.orEmpty() // phone number
                val msgId = (msg["id"] as? JsonPrimitive)?.content

                // Allow-list check
                val allow = config.allowedChatIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (allow.isNotEmpty() && from !in allow) {
                    Timber.i("WhatsAppChannel: ignoring $from (not in allow-list)")
                    continue
                }

                // Resolve display name from contacts
                val contacts = value["contacts"]?.jsonArray
                val displayName = contacts?.firstOrNull()?.jsonObject
                    ?.get("profile")?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull ?: from

                var text = ""
                var attachmentKind: String? = null
                var attachmentPath: String? = null

                when (msgType) {
                    "text" -> {
                        text = msg["text"]?.jsonObject?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    "image" -> {
                        attachmentKind = "photo"
                        val mediaId = msg["image"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                        val caption = msg["image"]?.jsonObject?.get("caption")?.jsonPrimitive?.contentOrNull
                        if (mediaId != null) attachmentPath = downloadMedia(mediaId, from, ".jpg")
                        text = caption ?: "[user sent a photo: $attachmentPath]"
                    }
                    "video" -> {
                        attachmentKind = "video"
                        val mediaId = msg["video"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                        val caption = msg["video"]?.jsonObject?.get("caption")?.jsonPrimitive?.contentOrNull
                        if (mediaId != null) attachmentPath = downloadMedia(mediaId, from, ".mp4")
                        text = caption ?: "[user sent a video: $attachmentPath]"
                    }
                    "audio" -> {
                        attachmentKind = "audio"
                        val mediaId = msg["audio"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                        if (mediaId != null) attachmentPath = downloadMedia(mediaId, from, ".ogg")
                        text = "[user sent an audio: $attachmentPath]"
                    }
                    "document" -> {
                        attachmentKind = "document"
                        val mediaId = msg["document"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                        val filename = msg["document"]?.jsonObject?.get("filename")?.jsonPrimitive?.contentOrNull ?: "file"
                        val ext = ".${filename.substringAfterLast('.', "bin")}"
                        if (mediaId != null) attachmentPath = downloadMedia(mediaId, from, ext)
                        text = "[user sent a document: $attachmentPath]"
                    }
                    "sticker" -> {
                        attachmentKind = "photo"
                        val mediaId = msg["sticker"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                        if (mediaId != null) attachmentPath = downloadMedia(mediaId, from, ".webp")
                        text = "[user sent a sticker: $attachmentPath]"
                    }
                    "location" -> {
                        val lat = msg["location"]?.jsonObject?.get("latitude")?.jsonPrimitive?.content
                        val lon = msg["location"]?.jsonObject?.get("longitude")?.jsonPrimitive?.content
                        val name = msg["location"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                        text = "[user sent location: ${name ?: ""}  lat=$lat lon=$lon]"
                    }
                    "contacts" -> {
                        val contactName = msg["contacts"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("name")?.jsonObject?.get("formatted_name")?.jsonPrimitive?.contentOrNull
                        text = "[user sent a contact card: ${contactName ?: "unknown"}]"
                    }
                    "reaction" -> {
                        val emoji = msg["reaction"]?.jsonObject?.get("emoji")?.jsonPrimitive?.contentOrNull
                        val reactedMsgId = msg["reaction"]?.jsonObject?.get("message_id")?.jsonPrimitive?.contentOrNull
                        text = "[user reacted $emoji to message $reactedMsgId]"
                    }
                    else -> continue
                }

                if (text.isBlank()) continue

                // Mark message as read
                markRead(msgId)

                _incoming.tryEmit(
                    IncomingMessage(
                        channelId = config.id,
                        channelType = ChannelType.WHATSAPP,
                        fromName = displayName,
                        fromId = from,
                        text = text,
                        messageId = msgId?.hashCode()?.toLong(),
                        attachmentKind = attachmentKind,
                        attachmentPath = attachmentPath,
                    )
                )
            }
        }
    }

    private fun downloadMedia(mediaId: String, from: String, ext: String): String? {
        return try {
            // Step 1: get media URL
            val urlReq = Request.Builder()
                .url("https://graph.facebook.com/v19.0/$mediaId")
                .header("Authorization", "Bearer ${accessToken()}")
                .get().build()
            val mediaUrl = http.newCall(urlReq).execute().use { r ->
                val body = r.body?.string().orEmpty()
                runCatching { json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()
            } ?: return null

            // Step 2: download
            val safeFrom = from.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val targetDir = File(context.filesDir,
                "workspace/downloads/whatsapp/$safeFrom").apply { mkdirs() }
            val out = File(targetDir, "${System.currentTimeMillis()}_$mediaId$ext")
            val dlReq = Request.Builder()
                .url(mediaUrl)
                .header("Authorization", "Bearer ${accessToken()}")
                .get().build()
            http.newCall(dlReq).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            }
            "downloads/whatsapp/$safeFrom/${out.name}"
        } catch (e: Exception) {
            Timber.w(e, "WhatsAppChannel: media download failed")
            null
        }
    }

    private fun markRead(messageId: String?) {
        if (messageId == null) return
        runCatching {
            val body = """{"messaging_product":"whatsapp","status":"read","message_id":"$messageId"}"""
            val req = Request.Builder()
                .url("https://graph.facebook.com/v19.0/${phoneNumberId()}/messages")
                .header("Authorization", "Bearer ${accessToken()}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { /* fire-and-forget */ }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Outgoing — Cloud API
    // ─────────────────────────────────────────────────────────────────────

    private fun sendMessage(jsonBody: String): OutgoingResult {
        return try {
            val req = Request.Builder()
                .url("https://graph.facebook.com/v19.0/${phoneNumberId()}/messages")
                .header("Authorization", "Bearer ${accessToken()}")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) OutgoingResult(true, "ok")
                else OutgoingResult(false, "WhatsApp error: ${body.take(200)}")
            }
        } catch (e: Exception) {
            OutgoingResult(false, e.message ?: "send failed")
        }
    }

    override suspend fun send(to: String, text: String, guestQueryId: String?, businessConnectionId: String?): OutgoingResult {
        val body = """
            {"messaging_product":"whatsapp","to":"${to.jsonEscape()}",
            "type":"text","text":{"body":"${text.jsonEscape()}"}}
        """.trimIndent()
        return sendMessage(body)
    }

    override suspend fun sendFormatted(to: String, text: String, parseMode: String, guestQueryId: String?, businessConnectionId: String?): OutgoingResult {
        // WhatsApp supports *bold*, _italic_, ~strike~, ```code``` in text messages
        val converted = markdownToWhatsApp(text)
        // WhatsApp message limit is 4096 chars
        val chunks = splitForWhatsApp(converted, 4000)
        var last = OutgoingResult(true, "ok")
        for (chunk in chunks) {
            val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"text","text":{"body":"${chunk.jsonEscape()}","preview_url":false}}"""
            last = sendMessage(body)
            if (!last.success) return last
        }
        return last
    }

    override suspend fun sendFile(to: String, path: String, caption: String?): OutgoingResult {
        val file = resolveFile(path) ?: return OutgoingResult(false, "File not found: $path")
        val ext = file.extension.lowercase()
        val mediaId = uploadMedia(file) ?: return OutgoingResult(false, "WhatsApp: media upload failed")

        val (type, mediaKey) = when {
            ext in listOf("jpg", "jpeg", "png", "webp") -> "image" to "image"
            ext in listOf("mp4", "mov") -> "video" to "video"
            ext in listOf("mp3", "ogg", "aac", "m4a") -> "audio" to "audio"
            else -> "document" to "document"
        }

        val captionPart = if (!caption.isNullOrBlank() && type != "audio")
            ""","caption":"${caption.jsonEscape()}"""" else ""
        val filenamePart = if (type == "document") ""","filename":"${file.name.jsonEscape()}"""" else ""

        val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"$type","$mediaKey":{"id":"$mediaId"$captionPart$filenamePart}}"""
        return sendMessage(body)
    }

    override suspend fun sendVoice(to: String, audioPath: String, caption: String?): OutgoingResult {
        return sendFile(to, audioPath, caption)
    }

    private fun uploadMedia(file: File): String? {
        return try {
            val mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("messaging_product", "whatsapp")
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://graph.facebook.com/v19.0/${phoneNumberId()}/media")
                .header("Authorization", "Bearer ${accessToken()}")
                .post(multipart).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                runCatching { json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
            }
        } catch (e: Exception) {
            Timber.w(e, "WhatsAppChannel: media upload failed")
            null
        }
    }

    override suspend fun reactToMessage(to: String, messageId: Long, reaction: String): OutgoingResult {
        // WhatsApp reactions use the original message_id string, not a long
        // We can't recover the original string from a long hash, so this is best-effort
        val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"reaction","reaction":{"message_id":"$messageId","emoji":"${reaction.jsonEscape()}"}}"""
        return sendMessage(body)
    }

    // ─────────────────────────────────────────────────────────────────────
    // WhatsApp-specific extras (called from ToolRegistry)
    // ─────────────────────────────────────────────────────────────────────

    /** Send a location message. */
    fun sendLocation(to: String, latitude: Double, longitude: Double, name: String = "", address: String = ""): OutgoingResult {
        val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"location","location":{"latitude":$latitude,"longitude":$longitude,"name":"${name.jsonEscape()}","address":"${address.jsonEscape()}"}}"""
        return sendMessage(body)
    }

    /** Send a contact card. */
    fun sendContact(to: String, name: String, phone: String): OutgoingResult {
        val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"contacts","contacts":[{"name":{"formatted_name":"${name.jsonEscape()}","first_name":"${name.jsonEscape()}"},"phones":[{"phone":"${phone.jsonEscape()}","type":"CELL"}]}]}"""
        return sendMessage(body)
    }

    /** Send an interactive message with buttons (up to 3). */
    fun sendInteractive(to: String, bodyText: String, buttons: List<Pair<String, String>>): OutgoingResult {
        val buttonsJson = buttons.take(3).mapIndexed { i, (id, title) ->
            """{"type":"reply","reply":{"id":"${id.jsonEscape()}","title":"${title.take(20).jsonEscape()}"}}"""
        }.joinToString(",")
        val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"interactive","interactive":{"type":"button","body":{"text":"${bodyText.jsonEscape()}"},"action":{"buttons":[$buttonsJson]}}}"""
        return sendMessage(body)
    }

    /** Send a template message. */
    fun sendTemplate(to: String, templateName: String, languageCode: String = "en_US", components: String = "[]"): OutgoingResult {
        val body = """{"messaging_product":"whatsapp","to":"${to.jsonEscape()}","type":"template","template":{"name":"${templateName.jsonEscape()}","language":{"code":"$languageCode"},"components":$components}}"""
        return sendMessage(body)
    }

    /** Get the WhatsApp Business profile. */
    fun getProfile(): String {
        return try {
            val req = Request.Builder()
                .url("https://graph.facebook.com/v19.0/${phoneNumberId()}/whatsapp_business_profile?fields=about,address,description,email,profile_picture_url,websites,vertical")
                .header("Authorization", "Bearer ${accessToken()}")
                .get().build()
            http.newCall(req).execute().use { resp ->
                resp.body?.string() ?: "(empty response)"
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
        fun splitForWhatsApp(text: String, limit: Int): List<String> {
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
         * Convert LLM markdown to WhatsApp formatting.
         * WhatsApp: *bold*, _italic_, ~strike~, ```code```
         */
        fun markdownToWhatsApp(input: String): String {
            var out = input
            // **bold** / __bold__ → *bold*
            out = out.replace(Regex("\\*\\*(.+?)\\*\\*"), "*$1*")
            out = out.replace(Regex("__(.+?)__"), "*$1*")
            // ~~strike~~ → ~strike~
            out = out.replace(Regex("~~(.+?)~~"), "~$1~")
            // [text](url) → text (url) — WhatsApp doesn't support hyperlinks in text
            out = out.replace(Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)"), "$1 ($2)")
            // Strip HTML tags
            out = out.replace(Regex("<[^>]+>"), "")
            return out
        }
    }
}

