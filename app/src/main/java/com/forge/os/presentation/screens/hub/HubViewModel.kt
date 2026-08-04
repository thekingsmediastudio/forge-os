package com.forge.os.presentation.screens.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.api.CostMeter
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.plugins.HubTile
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.domain.snapshots.SnapshotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HubViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    private val cronManager: CronManager,
    private val snapshotManager: SnapshotManager,
    costMeter: CostMeter) : ViewModel() {

    private val _pluginTiles = MutableStateFlow<List<Pair<String, HubTile>>>(emptyList())
    val pluginTiles: StateFlow<List<Pair<String, HubTile>>> = _pluginTiles

    private val _enabledCronCount = MutableStateFlow(0)
    val enabledCronCount: StateFlow<Int> = _enabledCronCount

    private val _snapshotCount = MutableStateFlow(0)
    val snapshotCount: StateFlow<Int> = _snapshotCount

    val dailyUsd: StateFlow<Double> = MutableStateFlow(0.0)

    private val costSnapshot = costMeter.snapshot

    init {
        refresh()
        viewModelScope.launch {
            costSnapshot.collect { snap ->
                (dailyUsd as MutableStateFlow).value = snap.dailyUsd
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _pluginTiles.value = pluginManager.listHubTiles()
            _enabledCronCount.value = runCatching {
                cronManager.listJobs().count { it.enabled }
            }.getOrDefault(0)
            _snapshotCount.value = runCatching {
                snapshotManager.list().size
            }.getOrDefault(0)
        }
    }
}
