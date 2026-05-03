package com.forge.os.domain.channels

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SessionModelOverride(val provider: String = "", val model: String = "")

@Serializable
private data class OverrideFile(val byKey: Map<String, SessionModelOverride> = emptyMap())

/**
 * Phase U — per-session (channelId + chatId) provider/model overrides for the
 * channels auto-reply pipeline. Persists to disk so the user's choice for a
 * particular Telegram chat survives process death. Empty values clear the
 * override for that session, which then falls back to the channel-level
 * override and finally to the global default + fallback chain.
 */
@Singleton
class ChannelSessionModelOverrides @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/channel_session_models.json")
            .apply { parentFile?.mkdirs() }
    }

    private fun load(): OverrideFile = try {
        if (file.exists()) json.decodeFromString(file.readText()) else OverrideFile()
    } catch (e: Exception) { Timber.w(e, "load overrides"); OverrideFile() }

    private fun store(of: OverrideFile) {
        try { file.writeText(json.encodeToString(of)) }
        catch (e: Exception) { Timber.w(e, "save overrides") }
    }

    private val _state = MutableStateFlow(load().byKey)
    val state: StateFlow<Map<String, SessionModelOverride>> = _state.asStateFlow()

    private fun key(channelId: String, chatId: String) = "$channelId:$chatId"

    @Synchronized
    fun get(channelId: String, chatId: String): SessionModelOverride? =
        _state.value[key(channelId, chatId)]

    @Synchronized
    fun set(channelId: String, chatId: String, provider: String, model: String) {
        val k = key(channelId, chatId)
        val next = _state.value.toMutableMap()
        if (provider.isBlank() && model.isBlank()) next.remove(k)
        else next[k] = SessionModelOverride(provider, model)
        _state.value = next
        store(OverrideFile(next))
    }

    @Synchronized
    fun clear(channelId: String, chatId: String) = set(channelId, chatId, "", "")
}
