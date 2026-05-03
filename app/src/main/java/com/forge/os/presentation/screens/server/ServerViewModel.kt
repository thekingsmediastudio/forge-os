package com.forge.os.presentation.screens.server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.server.ForgeHttpServer
import com.forge.os.data.server.ForgeHttpService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class ServerUiState(
    val running: Boolean = false,
    val port: Int = ForgeHttpServer.DEFAULT_PORT,
    val apiKey: String = "",
    val lanIps: List<String> = emptyList(),
)

@HiltViewModel
class ServerViewModel @Inject constructor(
    app: Application,
    private val server: ForgeHttpServer,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = ServerUiState(
                running = server.isRunning(),
                port = server.port(),
                apiKey = server.apiKey(),
                lanIps = detectLanIps(),
            )
        }
    }

    fun start() {
        ForgeHttpService.start(getApplication(), _state.value.port)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500)
            refresh()
        }
    }

    fun stop() {
        ForgeHttpService.stop(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            refresh()
        }
    }

    fun rotateKey() {
        viewModelScope.launch(Dispatchers.IO) {
            server.rotateKey()
            refresh()
        }
    }

    private fun detectLanIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { ni ->
            if (!ni.isUp || ni.isLoopback) emptyList()
            else ni.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
