package com.forge.os.presentation.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.channel.Channel
import com.forge.os.domain.channel.ChannelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val channelManager: ChannelManager
) : ViewModel() {

    val channels: StateFlow<List<Channel>> = channelManager.channels
    val currentChannel: StateFlow<Channel> = channelManager.currentChannel
    val channelsEnabled: StateFlow<Boolean> = channelManager.channelsEnabled

    fun setChannelsEnabled(enabled: Boolean) {
        channelManager.setChannelsEnabled(enabled)
    }

    fun createChannel(name: String, icon: String = "💬", color: String = "#FF6B3D") {
        channelManager.createChannel(name, icon, color)
    }

    fun updateChannel(channel: Channel) {
        channelManager.updateChannel(channel)
    }

    fun deleteChannel(channelId: String) {
        channelManager.deleteChannel(channelId)
    }

    fun switchChannel(channelId: String) {
        channelManager.switchChannel(channelId)
    }
}
