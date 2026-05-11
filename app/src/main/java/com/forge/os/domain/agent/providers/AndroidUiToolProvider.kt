package com.forge.os.domain.agent.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale

@Singleton
class AndroidUiToolProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolProvider, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    init {
        // Initialize on main thread just in case Context requires it for TTS
        Handler(Looper.getMainLooper()).post {
            tts = TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsInitialized = true
        }
    }

    override fun getTools(): List<ToolDefinition> = listOf(
        tool("android_toast", "Show a short popup text message on the device screen.", mapOf("text" to ("string" to "Message to show")), listOf("text")),
        tool("android_vibrate", "Vibrate the device for a specified number of milliseconds.", mapOf("duration_ms" to ("integer" to "Duration in milliseconds (e.g. 500)")), listOf("duration_ms")),
        tool("android_tts_speak", "Speak out text using Android's Text-To-Speech engine.", mapOf("text" to ("string" to "Text to spoken out loud")), listOf("text")),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "android_toast"     -> showToast(args["text"]?.toString() ?: "")
        "android_vibrate"   -> vibrate((args["duration_ms"] as? Number)?.toLong()
                                    ?: args["duration_ms"]?.toString()?.toLongOrNull() ?: 500L)
        "android_tts_speak" -> speakText(args["text"]?.toString() ?: "")
        else -> null
    }

    private suspend fun showToast(text: String): String = withContext(Dispatchers.Main) {
        runCatching {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            """{"ok":true,"message":"Toast shown"}"""
        }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }
    }

    private suspend fun vibrate(durationMs: Long): String = withContext(Dispatchers.Main) {
        runCatching {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return@runCatching """{"ok":false,"error":"Device has no vibrator"}"""

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
            """{"ok":true,"duration_ms":$durationMs}"""
        }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }
    }

    private fun speakText(text: String): String = runCatching {
        if (!isTtsInitialized || tts == null) {
            return """{"ok":false,"error":"TTS engine is not yet initialized or unavailable"}"""
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "forge_tts_${System.currentTimeMillis()}")
        """{"ok":true,"chars":${text.length}}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
