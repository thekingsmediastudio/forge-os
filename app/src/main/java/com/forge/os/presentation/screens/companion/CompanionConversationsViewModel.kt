package com.forge.os.presentation.screens.companion

import androidx.lifecycle.ViewModel
import com.forge.os.data.conversations.CompanionConversationRepository
import com.forge.os.data.conversations.StoredCompanionConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class CompanionConversationsState(
    val items: List<StoredCompanionConversation> = emptyList(),
    val currentId: String? = null,
    val message: String? = null,
)

@HiltViewModel
class CompanionConversationsViewModel @Inject constructor(
    private val repo: CompanionConversationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<CompanionConversationsState> = _state

    private fun load() = CompanionConversationsState(
        items = repo.list(), currentId = repo.currentId(),
    )

    fun refresh() { _state.value = load() }

    fun switchTo(id: String) {
        repo.setCurrent(id)
        _state.value = load().copy(message = "Opened companion chat")
    }

    fun startNew() {
        val conv = repo.newConversation()
        _state.value = load().copy(message = "Started ${conv.id}")
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
