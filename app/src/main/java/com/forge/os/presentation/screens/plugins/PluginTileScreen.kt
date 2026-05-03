package com.forge.os.presentation.screens.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.plugins.HubTile
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginTileViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    private val toolRegistry: ToolRegistry,
) : ViewModel() {

    private val _output = MutableStateFlow<String>("Tap RUN to execute the plugin tool.")
    val output: StateFlow<String> = _output

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun findTile(pluginId: String, toolName: String): HubTile? =
        pluginManager.getPlugin(pluginId)?.uiContributions?.hubTiles
            ?.firstOrNull { it.toolName == toolName }

    fun run(pluginId: String, toolName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            val tile = findTile(pluginId, toolName)
            val args = tile?.args?.ifBlank { "{}" } ?: "{}"
            val res = toolRegistry.dispatch(toolName, args, "plugin_tile_${System.currentTimeMillis()}")
            _output.value = if (res.isError) "❌ ${res.output}" else res.output
            _busy.value = false
        }
    }
}

@Composable
fun PluginTileScreen(
    pluginId: String,
    toolName: String,
    onBack: () -> Unit,
    viewModel: PluginTileViewModel = hiltViewModel(),
) {
    val output by viewModel.output.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val tile = remember(pluginId, toolName) { viewModel.findTile(pluginId, toolName) }

    ModuleScaffold(title = (tile?.title ?: toolName).uppercase(), onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("plugin: $pluginId", color = forgePalette.textMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text("tool: $toolName", color = forgePalette.textMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.run(pluginId, toolName) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = forgePalette.orange),
            ) { Text(if (busy) "RUNNING..." else "RUN", fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(forgePalette.surface, RoundedCornerShape(6.dp))
                    .border(1.dp, forgePalette.border, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text("OUTPUT", color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(output, color = forgePalette.textPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
