package com.forge.os.presentation.screens.conversations

import androidx.lifecycle.ViewModel
import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.data.conversations.StoredConversation
import com.forge.os.domain.channel.ChannelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class ConversationsState(
    val items: List<StoredConversation> = emptyList(),
    val currentId: String? = null,
    val currentChannelName: String = "General",
    val channelsEnabled: Boolean = false,
    val message: String? = null)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repo: ConversationRepository,
    private val channelManager: ChannelManager) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ConversationsState> = _state

    private fun load(): ConversationsState {
        val channelsEnabled = channelManager.isChannelsEnabled()
        val currentChannelId = channelManager.getCurrentChannelId()
        val currentChannel = channelManager.currentChannel.value
        
        val items = if (channelsEnabled) {
            repo.listByChannel(currentChannelId)
        } else {
            repo.list()
        }
        
        return ConversationsState(
            items = items,
            currentId = repo.currentId(),
            currentChannelName = currentChannel.name,
            channelsEnabled = channelsEnabled
        )
    }

    fun refresh() { _state.value = load() }

    fun switchTo(id: String) {
        repo.setCurrent(id)
        _state.value = load().copy(message = "Opened conversation")
    }

    fun startNew() {
        val channelId = channelManager.getCurrentChannelId()
        val conv = repo.newConversation(channelId)
        _state.value = load().copy(message = "Created ${conv.id}")
    }

    fun rename(id: String, title: String) {
        repo.rename(id, title)
        _state.value = load().copy(message = "Renamed")
    }

    fun delete(id: String) {
        repo.delete(id)
        _state.value = load().copy(message = "Deleted")
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
