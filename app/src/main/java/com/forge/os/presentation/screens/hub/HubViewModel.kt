package com.forge.os.presentation.screens.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.plugins.HubTile
import com.forge.os.domain.plugins.PluginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HubViewModel @Inject constructor(
    private val pluginManager: PluginManager,
) : ViewModel() {

    private val _pluginTiles = MutableStateFlow<List<Pair<String, HubTile>>>(emptyList())
    val pluginTiles: StateFlow<List<Pair<String, HubTile>>> = _pluginTiles

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _pluginTiles.value = pluginManager.listHubTiles()
        }
    }
}
