package com.forge.os.external

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fire-and-forget intent surface for share-sheet / Tasker.
 *
 *   Intent("com.forge.os.action.ASK").putExtra("prompt", "summarise my day")
 *
 * Optional `replyTo` (PendingIntent): receives EXTRA_RESULT on completion.
 * Same caller-permission gate as the bound service.
 */
@AndroidEntryPoint
class IntentApiActivity : ComponentActivity() {

    @Inject lateinit var bridge: ExternalApiBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_ASK = "com.forge.os.action.ASK"
        const val ACTION_RUN_TOOL = "com.forge.os.action.RUN_TOOL"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_ARGS = "args"
        const val EXTRA_REPLY_TO = "replyTo"
        const val EXTRA_RESULT = "result"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = Binder.getCallingUid().takeIf { it > 0 }
            ?: callingActivity?.packageName?.let { packageManager.getPackageUid(it, 0) }
            ?: -1
        val replyTo = intent.getParcelableExtra<PendingIntent>(EXTRA_REPLY_TO)
        val action = intent.action
        when (action) {
            ACTION_ASK -> handleAsk(uid, intent.getStringExtra(EXTRA_PROMPT).orEmpty(), replyTo)
            ACTION_RUN_TOOL -> handleRunTool(uid,
                intent.getStringExtra(EXTRA_TOOL).orEmpty(),
                intent.getStringExtra(EXTRA_ARGS).orEmpty(), replyTo)
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                handleAsk(uid, "Process this:\n\n$text", replyTo)
            }
            else -> { Toast.makeText(this, "Unknown action: $action", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    private fun handleAsk(uid: Int, prompt: String, replyTo: PendingIntent?) {
        if (prompt.isBlank()) { reply(replyTo, """{"ok":false,"error":"empty prompt"}"""); finish(); return }
        val d = bridge.authorize(uid, "askAgent")
        if (d !is ExternalApiBridge.Decision.Allow) {
            reply(replyTo, """{"ok":false,"code":${(d as ExternalApiBridge.Decision.Deny).code},"error":${q(d.reason)}}""")
            finish(); return
        }
        Toast.makeText(this, "Forge: working…", Toast.LENGTH_SHORT).show()
        scope.launch {
            var payload = """{"ok":false,"error":"no result"}"""
            bridge.askAgent(
                caller = d.caller, prompt = prompt, optsJson = "{}",
                onChunk = { /* no-op for fire-and-forget */ },
                onResult = { payload = it },
                onError = { code, msg -> payload = """{"ok":false,"code":$code,"error":${q(msg)}}""" },
            )
            reply(replyTo, payload)
            finish()
        }
    }

    private fun handleRunTool(uid: Int, tool: String, args: String, replyTo: PendingIntent?) {
        val d = bridge.authorize(uid, "invokeTool", tool)
        val payload = if (d is ExternalApiBridge.Decision.Allow)
            bridge.invokeToolSync(d.caller, tool, args)
        else """{"ok":false,"code":${(d as ExternalApiBridge.Decision.Deny).code},"error":${q(d.reason)}}"""
        reply(replyTo, payload)
        finish()
    }

    private fun reply(replyTo: PendingIntent?, payload: String) {
        replyTo ?: return
        runCatching {
            val data = Intent().putExtra(EXTRA_RESULT, payload)
            replyTo.send(this, 0, data)
        }
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}