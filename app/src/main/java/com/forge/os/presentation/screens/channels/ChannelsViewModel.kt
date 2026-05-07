package com.forge.os.presentation.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.channels.ChannelConfig
import com.forge.os.domain.channels.ChannelManager
import com.forge.os.domain.channels.ChannelSession
import com.forge.os.domain.channels.IncomingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val manager: ChannelManager,
) : ViewModel() {

    private val _channels = MutableStateFlow<List<ChannelConfig>>(emptyList())
    val channels: StateFlow<List<ChannelConfig>> = _channels
    val recent: StateFlow<List<IncomingMessage>> = manager.recent
    val sessions: StateFlow<Map<String, ChannelSession>> = manager.sessions

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _channels.value = manager.list()
        }
    }

    fun addTelegram(
        displayName: String,
        botToken: String,
        defaultChatId: String,
        autoReply: Boolean = true,
        parseMode: String = "HTML",
        allowedChatIds: String = "",
        purpose: String = "personal",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.createTelegram(
                displayName, botToken, defaultChatId,
                autoReply, parseMode, allowedChatIds, purpose,
            )
            refresh()
        }
    }

    fun toggle(cfg: ChannelConfig) {
        manager.setEnabled(cfg.id, !cfg.enabled)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200); refresh()
        }
    }

    fun setAutoReply(cfg: ChannelConfig, autoReply: Boolean) {
        manager.setAutoReply(cfg.id, autoReply)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200); refresh()
        }
    }

    fun setParseMode(cfg: ChannelConfig, parseMode: String) {
        manager.setParseMode(cfg.id, parseMode)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200); refresh()
        }
    }

    fun setAllowedChatIds(cfg: ChannelConfig, csv: String) {
        manager.setAllowedChatIds(cfg.id, csv)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200); refresh()
        }
    }

    fun remove(id: String) {
        manager.remove(id)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200); refresh()
        }
    }

    fun sendTo(channelId: String, to: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.send(channelId, to, text)
        }
    }

    fun sendVoice(channelId: String, to: String, audioPath: String, caption: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.sendVoice(channelId, to, audioPath, caption)
        }
    }

    /** Used from the live-session screen "manual reply" box. */
    fun sendToSession(sessionKey: String, text: String) {
        val (channelId, chatId) = sessionKey.split(':', limit = 2)
            .takeIf { it.size == 2 } ?: return
        sendTo(channelId, chatId, text)
    }

    // ─── Phase U2: per-session model override (Telegram session view) ─────────

    /** Providers that have a key on file and can be used as a per-session
     *  override. Triples of (providerKey, displayLabel, kind) where
     *  providerKey is the enum name for built-ins or `"custom:<id>"` for
     *  custom endpoints, and kind is `"builtin"` or `"custom"`. */
    fun availableProviders(): List<Triple<String, String, String>> = manager.availableProviders()

    /** Default model id used as a starting value for the picker when the
     *  live model catalog hasn't been loaded yet. */
    fun defaultModelFor(provider: String): String = manager.defaultModelFor(provider)

    /** Live (provider, model) pairs across both built-in providers and custom
     *  endpoints, fetched from each provider's `/models` endpoint and cached
     *  for 24h. The picker calls this from a LaunchedEffect. */
    suspend fun availableModels(): List<com.forge.os.data.api.AiApiManager.Quad> = manager.availableModels()

    /** Current per-session override, or null if not set. */
    fun getSessionModel(sessionKey: String): Pair<String, String>? {
        val parts = sessionKey.split(':', limit = 2)
        if (parts.size != 2) return null
        return manager.getSessionModel(parts[0], parts[1])
    }

    /** Set/clear the per-session model. Pass blank provider to revert to
     *  the channel-level / global fallback chain. The provider value should
     *  be either an [ApiKeyProvider] enum name or `"custom:<id>"`. */
    fun setSessionModel(sessionKey: String, provider: String, model: String) {
        val parts = sessionKey.split(':', limit = 2)
        if (parts.size != 2) return
        manager.setSessionModel(parts[0], parts[1], provider, model)
    }
}
