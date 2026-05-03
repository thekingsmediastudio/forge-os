package com.example.forgeclient

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.forge.os.api.IForgeOsCallback
import com.forge.os.api.IForgeOsService

/**
 * One-screen sample: bind, listTools, ask agent. No frameworks, on purpose —
 * it's the smallest possible reference integration.
 */
class MainActivity : Activity() {

    private var svc: IForgeOsService? = null
    private lateinit var output: TextView

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = IForgeOsService.Stub.asInterface(binder)
            output.append("Connected. API ${svc?.apiVersion}\n\n")
            runCatching { output.append("Tools:\n${svc?.listTools()}\n\n") }
                .onFailure { output.append("listTools failed: ${it.message}\n") }
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        output = TextView(this).apply { setTextIsSelectable(true) }
        val prompt = EditText(this).apply { hint = "Ask Forge…" }
        val ask = Button(this).apply { text = "askAgent" }
        ask.setOnClickListener { askAgent(prompt.text.toString()) }
        root.addView(ask); root.addView(prompt); root.addView(output)
        setContentView(root)

        bindService(
            Intent("com.forge.os.api.IForgeOsService").setPackage("com.forge.os"),
            conn, Context.BIND_AUTO_CREATE,
        )
    }

    private fun askAgent(prompt: String) {
        val s = svc ?: return
        output.append("> $prompt\n")
        s.askAgent(prompt, "{}", object : IForgeOsCallback.Stub() {
            override fun onChunk(text: String) = runOnUiThread { output.append(text) }
            override fun onResult(jsonResult: String) = runOnUiThread { output.append("\n[done] $jsonResult\n\n") }
            override fun onError(code: Int, message: String) = runOnUiThread { output.append("\n[err $code] $message\n\n") }
        })
    }

    override fun onDestroy() { runCatching { unbindService(conn) }; super.onDestroy() }
}
