package com.forge.os.presentation.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.domain.channel.Channel
import com.forge.os.domain.channel.ChannelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val channelManager: ChannelManager,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    val channels: StateFlow<List<Channel>> = channelManager.channels
    val currentChannel: StateFlow<Channel> = channelManager.currentChannel
    val channelsEnabled: StateFlow<Boolean> = channelManager.channelsEnabled
    
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setChannelsEnabled(enabled: Boolean) {
        channelManager.setChannelsEnabled(enabled)
    }

    fun createChannel(name: String, icon: String = "💬", color: String = "#FF6B3D") {
        channelManager.createChannel(name, icon, color)
        _message.value = "Channel \"$name\" created"
    }

    fun updateChannel(channel: Channel) {
        channelManager.updateChannel(channel)
        _message.value = "Channel updated"
    }

    fun deleteChannel(channelId: String, moveConversationsToGeneral: Boolean = true) {
        val channel = channelManager.getChannel(channelId) ?: return
        
        if (moveConversationsToGeneral) {
            // Move conversations to General channel
            val moved = conversationRepository.moveToChannel(channelId, Channel.GENERAL.id)
            if (moved > 0) {
                _message.value = "Moved $moved conversation(s) to General"
            }
        } else {
            // Delete all conversations in this channel
            val deleted = conversationRepository.deleteByChannel(channelId)
            if (deleted > 0) {
                _message.value = "Deleted $deleted conversation(s)"
            }
        }
        
        channelManager.deleteChannel(channelId)
        _message.value = "Channel \"${channel.name}\" deleted"
    }

    fun switchChannel(channelId: String) {
        channelManager.switchChannel(channelId)
        val channel = channelManager.getChannel(channelId)
        _message.value = "Switched to ${channel?.name ?: "General"}"
    }
    
    fun getCurrentChannelId(): String {
        return channelManager.getCurrentChannelId()
    }
    
    fun clearMessage() {
        _message.value = null
    }
}
