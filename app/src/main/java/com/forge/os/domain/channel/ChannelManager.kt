package com.forge.os.domain.channel

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages memory channels for scoped AI conversations.
 * Channels are optional and can be enabled/disabled in settings.
 */
@Singleton
class ChannelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    private val gson = Gson()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _currentChannel = MutableStateFlow(Channel.GENERAL)
    val currentChannel: StateFlow<Channel> = _currentChannel.asStateFlow()

    private val _channelsEnabled = MutableStateFlow(false)
    val channelsEnabled: StateFlow<Boolean> = _channelsEnabled.asStateFlow()

    companion object {
        private const val PREFS_NAME = "forge_channel_prefs"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_CURRENT_CHANNEL_ID = "current_channel_id"
        private const val KEY_CHANNELS_ENABLED = "channels_enabled"
    }

    init {
        loadChannels()
        _channelsEnabled.value = prefs.getBoolean(KEY_CHANNELS_ENABLED, false)
    }

    // ── Channel CRUD ──────────────────────────────────────────────────────────

    /** Create a new channel */
    fun createChannel(
        name: String,
        icon: String = "💬",
        color: String = "#FF6B3D"
    ): Channel {
        val channel = Channel(name = name, icon = icon, color = color)
        val updated = _channels.value + channel
        saveChannels(updated)
        return channel
    }

    /** Update an existing channel */
    fun updateChannel(channel: Channel) {
        val updated = _channels.value.map {
            if (it.id == channel.id) channel.copy(updatedAt = System.currentTimeMillis()) else it
        }
        saveChannels(updated)
    }

    /** Delete a channel (cannot delete default) */
    fun deleteChannel(channelId: String): Boolean {
        val channel = _channels.value.find { it.id == channelId }
        if (channel?.isDefault == true) return false

        val updated = _channels.value.filter { it.id != channelId }
        saveChannels(updated)

        // Switch to general if current channel was deleted
        if (_currentChannel.value.id == channelId) {
            switchChannel(Channel.GENERAL.id)
        }
        return true
    }

    /** Get a channel by ID */
    fun getChannel(channelId: String): Channel? {
        if (channelId == Channel.GENERAL.id) return Channel.GENERAL
        return _channels.value.find { it.id == channelId }
    }

    // ── Channel Switching ─────────────────────────────────────────────────────

    /** Switch to a different channel */
    fun switchChannel(channelId: String) {
        val channel = getChannel(channelId) ?: Channel.GENERAL
        _currentChannel.value = channel
        prefs.edit().putString(KEY_CURRENT_CHANNEL_ID, channelId).apply()
    }

    /** Get the current channel ID for scoping */
    fun getCurrentChannelId(): String {
        return if (_channelsEnabled.value) _currentChannel.value.id else Channel.GENERAL.id
    }

    // ── Enable/Disable ────────────────────────────────────────────────────────

    /** Enable or disable channels feature */
    fun setChannelsEnabled(enabled: Boolean) {
        _channelsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_CHANNELS_ENABLED, enabled).apply()
        
        // Reset to general when disabling
        if (!enabled) {
            _currentChannel.value = Channel.GENERAL
        }
    }

    /** Check if channels are enabled */
    fun isChannelsEnabled(): Boolean = _channelsEnabled.value

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadChannels() {
        val json = prefs.getString(KEY_CHANNELS, null)
        if (json != null) {
            val type = object : TypeToken<List<Channel>>() {}.type
            _channels.value = gson.fromJson(json, type) ?: emptyList()
        }

        // Restore current channel
        val currentId = prefs.getString(KEY_CURRENT_CHANNEL_ID, Channel.GENERAL.id)
        _currentChannel.value = getChannel(currentId ?: Channel.GENERAL.id) ?: Channel.GENERAL
    }

    private fun saveChannels(channels: List<Channel>) {
        _channels.value = channels
        val json = gson.toJson(channels)
        prefs.edit().putString(KEY_CHANNELS, json).apply()
    }

    // ── Export/Purge ──────────────────────────────────────────────────────────

    /** Export channel data (for backup) */
    fun exportChannel(channelId: String): String? {
        val channel = getChannel(channelId) ?: return null
        return gson.toJson(channel)
    }

    /** Purge all data for a channel */
    fun purgeChannel(channelId: String) {
        // Note: ConversationRepository integration is handled by the caller
        // (e.g., MemoriesScreen or ChannelViewModel) to avoid circular dependency
    }
    
    /** Check if a channel exists */
    fun channelExists(channelId: String): Boolean {
        return channelId == Channel.GENERAL.id || _channels.value.any { it.id == channelId }
    }
}
