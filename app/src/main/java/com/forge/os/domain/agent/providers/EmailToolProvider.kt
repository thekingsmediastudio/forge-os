package com.forge.os.domain.agent.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.domain.config.ConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Authenticator
import javax.mail.Folder
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Email tools.
 *
 * Two sending paths:
 *   email_compose — opens the device email app with a pre-filled message via an
 *                   Android Intent. NO credentials needed; the user reviews and
 *                   taps Send. Can attach files from the workspace.
 *   email_send    — sends directly via SMTP (needs `config.email.*` credentials,
 *                   e.g. a Gmail App Password). Fully automated.
 *
 * Reading (IMAP) requires credentials:
 *   email_list    — list recent INBOX messages
 *   email_read    — read a specific message by index (from email_list)
 */
@Singleton
class EmailToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
) : ToolProvider {

    /** Rate limiter: max 50 SMTP sends per hour. */
    private val sendTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxSendsPerHour = 50

    private fun checkRateLimit(): String? {
        val now = System.currentTimeMillis()
        val hourAgo = now - 3600_000L
        val timestamps = sendTimestamps.getOrPut("email") { mutableListOf() }
        timestamps.removeAll { it < hourAgo }
        if (timestamps.size >= maxSendsPerHour) {
            return """{"ok":false,"error":"Rate limit: max $maxSendsPerHour emails per hour."}"""
        }
        timestamps.add(now)
        return null
    }

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name = "email_compose",
            description = "Open the device email app with a pre-filled message (to/subject/body, optional file attachments). No credentials needed — the user reviews and taps Send. Returns ok once the compose window is open.",
            params = mapOf(
                "to"          to ("string"  to "Recipient email address"),
                "subject"     to ("string"  to "Subject line"),
                "body"        to ("string"  to "Plain-text email body"),
                "attachments" to ("string"  to "Optional comma-separated absolute file paths to attach (workspace files)"),
            ),
            required = listOf("to"),
        ),
        tool(
            name = "email_send",
            description = "Send an email directly via the configured SMTP account (no user interaction). Requires email credentials in config. Optionally attach files by absolute path.",
            params = mapOf(
                "to"          to ("string"  to "Recipient email address"),
                "subject"     to ("string"  to "Subject line"),
                "body"        to ("string"  to "Plain-text email body"),
                "attachments" to ("string"  to "Optional comma-separated absolute file paths to attach"),
            ),
            required = listOf("to"),
        ),
        tool(
            name = "email_list",
            description = "List recent inbox messages. Returns index, sender, subject, and date. Requires email credentials (IMAP).",
            params = mapOf("limit" to ("integer" to "Max messages to return (default 10, max 50)")),
            required = emptyList(),
        ),
        tool(
            name = "email_read",
            description = "Read a specific inbox message by its index (as shown by email_list). Requires email credentials (IMAP).",
            params = mapOf("index" to ("integer" to "Message index from email_list")),
            required = listOf("index"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "email_compose" -> compose(args)
        "email_send"    -> send(args)
        "email_list"    -> list(args)
        "email_read"    -> read(args)
        else -> null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun configured(): com.forge.os.domain.config.EmailSettings? {
        val cfg = configRepository.get().email
        if (!cfg.enabled) return null
        if (cfg.smtpUser.isBlank() || cfg.smtpPassword.isBlank()) return null
        return cfg
    }

    private fun smtpSession(cfg: com.forge.os.domain.config.EmailSettings): Session {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", cfg.smtpPort.toString())
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(cfg.smtpUser, cfg.smtpPassword)
        })
    }

    private fun imapSession(cfg: com.forge.os.domain.config.EmailSettings): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", cfg.imapHost)
            put("mail.imaps.port", cfg.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
        }
        return Session.getInstance(props)
    }

    private fun isProbablyValidEmail(addr: String): Boolean {
        if (addr.isBlank()) return false
        val at = addr.lastIndexOf('@')
        if (at <= 0 || at == addr.length - 1) return false
        return !addr.any { it.isWhitespace() || it == '<' || it == '>' || it == '\'' || it == '"' }
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    // ── Tool implementations ───────────────────────────────────────────────

    /**
     * Intent-based compose. No credentials — builds a mailto/SEND_MULTIPLE
     * intent and hands it to the system email app for the user to confirm.
     * Attachments are served through the app's FileProvider, so only files
     * under the FileProvider's configured roots (the workspace) can attach.
     */
    private fun compose(args: Map<String, Any>): String {
        val to = args["to"]?.toString()?.trim() ?: return """{"ok":false,"error":"'to' is required"}"""
        val subject = args["subject"]?.toString() ?: ""
        val body = args["body"]?.toString() ?: ""

        if (!isProbablyValidEmail(to)) return """{"ok":false,"error":"Invalid recipient address '$to'."}"""

        val attachmentPaths = args["attachments"]?.toString()
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val authority = "${context.packageName}.fileprovider"
        val uris = attachmentPaths.mapNotNull { path ->
            val f = java.io.File(path)
            if (f.exists() && f.isFile) {
                runCatching { FileProvider.getUriForFile(context, authority, f) }.getOrNull()
            } else null
        }

        return runCatching {
            val intent = if (uris.isEmpty()) {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            """{"ok":true,"composed":true,"to":"$to","subject":"${jsonEscape(subject)}","attachments":${uris.size}}"""
        }.getOrElse { t ->
            if (t is ActivityNotFoundException) {
                """{"ok":false,"error":"No email app is installed to handle this."}"""
            } else {
                """{"ok":false,"error":"${t.message}"}"""
            }
        }
    }

    private suspend fun send(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val cfg = configured()
            ?: return@withContext """{"ok":false,"error":"Email is not configured. Enable it and set SMTP host/user/password in Settings → Email, or use email_compose instead."}"""

        val to = args["to"]?.toString()?.trim() ?: return@withContext """{"ok":false,"error":"'to' is required"}"""
        val subject = args["subject"]?.toString() ?: ""
        val body = args["body"]?.toString() ?: ""

        if (!isProbablyValidEmail(to)) return@withContext """{"ok":false,"error":"Invalid recipient address '$to'."}"""

        checkRateLimit()?.let { return@withContext it }

        val attachmentPaths = args["attachments"]?.toString()
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val from = cfg.fromAddress.ifBlank { cfg.smtpUser }

        runCatching {
            val session = smtpSession(cfg)
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                this.subject = subject
            }

            val validAttachments = attachmentPaths.filter { p ->
                val f = java.io.File(p)
                f.exists() && f.isFile
            }
            if (validAttachments.isEmpty()) {
                msg.setText(body)
            } else {
                val multipart = MimeMultipart()
                multipart.addBodyPart(MimeBodyPart().apply { setText(body) })
                for (path in validAttachments) {
                    multipart.addBodyPart(MimeBodyPart().apply { attachFile(java.io.File(path)) })
                }
                msg.setContent(multipart)
            }

            Transport.send(msg)
            """{"ok":true,"to":"$to","subject":"${jsonEscape(subject)}","attachments":${validAttachments.size}}"""
        }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }
    }

    private suspend fun list(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val cfg = configured()
            ?: return@withContext """{"ok":false,"error":"Email is not configured. Reading mail requires IMAP credentials."}"""
        val limit = (args["limit"]?.toString()?.toIntOrNull() ?: 10).coerceIn(1, 50)

        runCatching {
            val store: Store = imapSession(cfg).getStore("imaps")
            store.connect(cfg.imapHost, cfg.smtpUser, cfg.smtpPassword)
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val msgs = inbox.messages
            val out = mutableListOf<String>()
            val start = (msgs.size - limit).coerceAtLeast(0)
            var shown = 0
            for (i in msgs.size - 1 downTo start) {
                val m = msgs[i]
                val from = m.from?.joinToString(", ") { it.toString() } ?: "(unknown)"
                val date = m.receivedDate?.toString() ?: m.sentDate?.toString() ?: ""
                out += "[$shown] $from | ${m.subject ?: "(no subject)"} | $date"
                shown++
            }
            inbox.close(false)
            store.close()
            if (out.isEmpty()) "Inbox is empty." else out.joinToString("\n")
        }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }
    }

    private suspend fun read(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val cfg = configured()
            ?: return@withContext """{"ok":false,"error":"Email is not configured. Reading mail requires IMAP credentials."}"""
        val index = args["index"]?.toString()?.toIntOrNull()
            ?: return@withContext """{"ok":false,"error":"'index' is required"}"""

        runCatching {
            val store: Store = imapSession(cfg).getStore("imaps")
            store.connect(cfg.imapHost, cfg.smtpUser, cfg.smtpPassword)
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val msgs = inbox.messages
            // email_read indexes are 0-based from newest (matching email_list)
            val target = msgs.size - 1 - index
            if (target < 0 || target >= msgs.size) {
                inbox.close(false)
                store.close()
                return@runCatching """{"ok":false,"error":"Index $index out of range (inbox has ${msgs.size} messages)."}"""
            }
            val m = msgs[target]
            val from = m.from?.joinToString(", ") { it.toString() } ?: "(unknown)"
            val subject = m.subject ?: "(no subject)"
            val date = m.receivedDate?.toString() ?: m.sentDate?.toString() ?: ""
            val body = extractBody(m)
            inbox.close(false)
            store.close()
            """{"ok":true,"from":"${jsonEscape(from)}","subject":"${jsonEscape(subject)}","date":"${jsonEscape(date)}","body":"${jsonEscape(body)}"}"""
        }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }
    }

    /** Best-effort plain-text extraction from a MimeMessage. */
    private fun extractBody(m: Message): String = runCatching {
        when (val content = m.content) {
            is String -> content
            is MimeMultipart -> {
                val sb = StringBuilder()
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    if (part.isMimeType("text/plain")) {
                        sb.append(part.content?.toString() ?: "")
                    } else if (part.isMimeType("text/html")) {
                        val html = part.content?.toString() ?: ""
                        sb.append(html.replace(Regex("<[^>]+>"), " ").trim())
                    }
                }
                sb.toString().ifBlank { "(no text content)" }
            }
            else -> content?.toString() ?: "(no content)"
        }
    }.getOrElse { "(could not read body: ${it.message})" }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
