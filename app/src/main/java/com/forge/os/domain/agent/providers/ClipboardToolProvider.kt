package com.forge.os.domain.agent.providers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clipboard tools — read and write the device system clipboard.
 *
 * Note: Android 10+ restricts clipboard reads to the foreground app.
 * Reading may fail or return empty unless Forge OS is the focused app.
 * Writing always works.
 *
 * Tools:
 *   clipboard_read   — get current clipboard text
 *   clipboard_write  — set clipboard text
 *   clipboard_clear  — clear the clipboard
 */
@Singleton
class ClipboardToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool("clipboard_read",  "Read the current text content of the device clipboard. Returns the clipboard text or empty.", emptyMap(), emptyList()),
        tool("clipboard_write", "Write text to the device clipboard.", mapOf("text" to ("string" to "Text to place on clipboard"), "label" to ("string" to "Optional clipboard label")), listOf("text")),
        tool("clipboard_clear", "Clear the device clipboard.", emptyMap(), emptyList()),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "clipboard_read"  -> readClipboard()
        "clipboard_write" -> writeClipboard(args["text"]?.toString() ?: "", args["label"]?.toString() ?: "Forge")
        "clipboard_clear" -> clearClipboard()
        else -> null
    }

    private fun cm() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun readClipboard(): String = runCatching {
        val clip = cm().primaryClip
        if (clip == null || clip.itemCount == 0)
            return """{"ok":true,"text":"","note":"Clipboard is empty or restricted on Android 10+"}"""
        val text = clip.getItemAt(0).coerceToText(context).toString()
        """{"ok":true,"text":${jsonStr(text)}}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun writeClipboard(text: String, label: String): String = runCatching {
        cm().setPrimaryClip(ClipData.newPlainText(label, text))
        """{"ok":true,"chars":${text.length}}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun clearClipboard(): String = runCatching {
        cm().clearPrimaryClip()
        """{"ok":true}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun jsonStr(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
