package com.forge.os.domain.agent.providers

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media playback control tools via AudioManager key events.
 *
 * These tools send system key events that are broadcast to whatever media
 * app is currently active (Spotify, YouTube, Podcasts, etc.) — no extra
 * permissions required.
 *
 * Tools:
 *   media_play_pause — toggle play/pause
 *   media_next       — skip to next track
 *   media_previous   — go to previous track
 *   media_stop       — stop playback
 *   media_volume_up  — raise media volume
 *   media_volume_down — lower media volume
 */
@Singleton
class MediaControlToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool("media_play_pause",  "Toggle play/pause on the active media app (Spotify, YouTube, etc.).", emptyMap(), emptyList()),
        tool("media_next",        "Skip to the next track/chapter in the active media app.",             emptyMap(), emptyList()),
        tool("media_previous",    "Go to the previous track/chapter in the active media app.",            emptyMap(), emptyList()),
        tool("media_stop",        "Stop playback in the active media app.",                               emptyMap(), emptyList()),
        tool("media_volume_up",   "Raise the media volume by one step.",                                  emptyMap(), emptyList()),
        tool("media_volume_down", "Lower the media volume by one step.",                                  emptyMap(), emptyList()),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? = when (toolName) {
        "media_play_pause"  -> pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        "media_next"        -> pressKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        "media_previous"    -> pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "media_stop"        -> pressKey(KeyEvent.KEYCODE_MEDIA_STOP)
        "media_volume_up"   -> adjustVolume(AudioManager.ADJUST_RAISE)
        "media_volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
        else -> null
    }

    private fun pressKey(keyCode: Int): String = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up   = KeyEvent(KeyEvent.ACTION_UP,   keyCode)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
        """{"ok":true,"key":$keyCode}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun adjustVolume(direction: Int): String = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max     = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        """{"ok":true,"level":$current,"max":$max,"percent":${(current * 100) / max.coerceAtLeast(1)}}"""
    }.getOrElse { """{"ok":false,"error":"${it.message}"}""" }

    private fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String>) =
        ToolDefinition(function = FunctionDefinition(name = name, description = description,
            parameters = FunctionParameters(
                properties = params.mapValues { (_, v) -> ParameterProperty(type = v.first, description = v.second) },
                required = required,
            )))
}
