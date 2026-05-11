package com.forge.os.presentation.screens.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.mcp.McpClient
import com.forge.os.data.mcp.McpServer
import com.forge.os.data.mcp.McpServerRepository
import com.forge.os.data.mcp.McpToolSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class McpUiState(
    val servers: List<McpServer> = emptyList(),
    val tools: List<Pair<McpServer, McpToolSpec>> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val repo: McpServerRepository,
    private val client: McpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(McpUiState(
        servers = repo.list(),
        tools = client.cachedToolsWithServer(),
    ))
    val state: StateFlow<McpUiState> = _state

    fun add(name: String, url: String, token: String?) {
        if (url.isBlank()) {
            _state.value = _state.value.copy(message = "URL required"); return
        }
        repo.add(name, url, token)
        _state.value = _state.value.copy(servers = repo.list(), message = "Added $name")
    }

    fun toggle(id: String, enabled: Boolean) {
        repo.setEnabled(id, enabled)
        _state.value = _state.value.copy(servers = repo.list())
    }

    fun remove(id: String) {
        repo.remove(id)
        _state.value = _state.value.copy(
            servers = repo.list(),
            tools = client.cachedToolsWithServer(),
            message = "Removed",
        )
    }

    fun refreshTools() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val tools = try { client.refreshTools() } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = "❌ ${e.message}")
                return@launch
            }
            _state.value = _state.value.copy(
                busy = false,
                tools = client.cachedToolsWithServer(),
                message = "Discovered ${tools.size} tool(s)",
            )
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
