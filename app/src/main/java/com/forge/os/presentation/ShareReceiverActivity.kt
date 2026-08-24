package com.forge.os.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.presentation.screens.chat.PendingShareSeed
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Share-sheet entry point: "Share → Forge OS" from WhatsApp, Files, Photos, etc.
 *
 * Copies shared text/files into the workspace `uploads/` folder, then opens the
 * chat with a pre-seeded prompt so the agent can act on them (e.g. email_compose).
 * No credentials or external-API grant required — the user explicitly chose
 * Forge OS in the share sheet, so this is treated as a first-party entry point.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var sandbox: SandboxManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> handleProcessText(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleSend(intent, multiple = true)
            Intent.ACTION_SEND -> handleSend(intent, multiple = false)
            else -> finish()
        }
    }

    /** Text-selection toolbar "…" → Forge OS. */
    private fun handleProcessText(intent: Intent?) {
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        seedAndOpen(text, emptyList())
    }

    private fun handleSend(intent: Intent?, multiple: Boolean) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val uris: List<Uri> = if (multiple) {
            intent?.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        } else {
            listOfNotNull(intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        seedAndOpen(text, uris)
    }

    private fun seedAndOpen(text: String, uris: List<Uri>) {
        scope.launch {
            val saved = uris.mapNotNull { copyIntoWorkspace(it) }
            val prompt = buildList {
                if (saved.isNotEmpty()) {
                    add("I shared ${saved.size} file${if (saved.size == 1) "" else "s"} with you:")
                    saved.forEach { add("  - $it") }
                }
                if (text.isNotBlank()) add(text)
            }.joinToString("\n").ifBlank { "I shared something with you." }

            PendingShareSeed.set(prompt)
            openChat()
        }
    }

    /** Copy a shared content:// Uri into workspace/uploads; return the relative path. */
    private suspend fun copyIntoWorkspace(uri: Uri): String? {
        return try {
            val raw = resolveDisplayName(uri) ?: "shared_${System.currentTimeMillis()}"
            val rel = "uploads/${sanitizeName(raw)}"
            val ins = contentResolver.openInputStream(uri) ?: return null
            ins.use { stream ->
                val result = sandbox.importStream(rel, stream)
                if (result.isSuccess) rel else null
            }
        } catch (t: Throwable) {
            Timber.w(t, "ShareReceiver: failed to import %s", uri)
            null
        }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return cleaned.ifBlank { "shared_${System.currentTimeMillis()}" }
    }

    private fun openChat() {
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra("nav", "chat")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(i) }
        finish()
    }
}
