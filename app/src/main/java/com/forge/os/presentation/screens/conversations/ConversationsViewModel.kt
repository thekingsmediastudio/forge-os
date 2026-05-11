package com.forge.os.presentation.screens.conversations

import androidx.lifecycle.ViewModel
import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.data.conversations.StoredConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class ConversationsState(
    val items: List<StoredConversation> = emptyList(),
    val currentId: String? = null,
    val message: String? = null,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repo: ConversationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ConversationsState> = _state

    private fun load() = ConversationsState(items = repo.list(), currentId = repo.currentId())

    fun refresh() { _state.value = load() }

    fun switchTo(id: String) {
        repo.setCurrent(id)
        _state.value = load().copy(message = "Opened conversation")
    }

    fun startNew() {
        val conv = repo.newConversation()
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
