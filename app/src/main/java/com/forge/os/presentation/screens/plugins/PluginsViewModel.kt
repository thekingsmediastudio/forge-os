package com.forge.os.presentation.screens.plugins

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.domain.plugins.PluginManifest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.zip.ZipInputStream
import javax.inject.Inject

data class PluginsUiState(
    val plugins: List<PluginManifest> = emptyList(),
    val message: String? = null,
    val installing: Boolean = false,
    val pendingPermissionGrant: PluginManifest? = null,  // post-install confirm
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
) : ViewModel() {

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(plugins = pluginManager.listPlugins())
    }

    fun setEnabled(id: String, enabled: Boolean) {
        pluginManager.setEnabled(id, enabled)
        refresh()
    }

    fun uninstall(id: String) {
        if (pluginManager.uninstall(id)) {
            _state.value = _state.value.copy(message = "Uninstalled $id")
        } else {
            _state.value = _state.value.copy(message = "Cannot uninstall built-in")
        }
        refresh()
    }

    fun installFromText(manifestJson: String, code: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(installing = true)
            val r = pluginManager.install(manifestJson, code, source = "user")
            _state.value = _state.value.copy(installing = false)
            r.fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Installed ${it.name} v${it.version}")
                    refresh()
                },
                onFailure = {
                    _state.value = _state.value.copy(message = "❌ ${it.message}")
                },
            )
        }
    }

    fun installFromZip(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(installing = true)
            val parsed = withContext(Dispatchers.IO) { extractZip(uri) }
            _state.value = _state.value.copy(installing = false)
            if (parsed == null) {
                _state.value = _state.value.copy(message = "❌ ZIP must contain manifest.json + entrypoint .py")
                return@launch
            }
            val (manifest, code) = parsed
            installFromText(manifest, code)
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    /**
     * Extracts manifest.json + the manifest.entrypoint .py file from a user-supplied
     * .zip archive. Returns Pair(manifestJson, entrypointCode) or null on failure.
     */
    private fun extractZip(uri: Uri): Pair<String, String>? {
        return try {
            val resolver = context.contentResolver
            val zipBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null

            var manifestText: String? = null
            val pyFiles = mutableMapOf<String, String>()

            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        val bytes = zis.readBytes()
                        when {
                            name.equals("manifest.json", ignoreCase = true) ->
                                manifestText = String(bytes)
                            name.endsWith(".py") -> pyFiles[name] = String(bytes)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val manifest = manifestText ?: return null
            val entrypointName = Regex(""""entrypoint"\s*:\s*"([^"]+)"""")
                .find(manifest)?.groupValues?.get(1) ?: "main.py"
            val code = pyFiles[entrypointName.substringAfterLast('/')]
                ?: pyFiles.values.firstOrNull()
                ?: return null
            manifest to code
        } catch (e: Exception) {
            Timber.e(e, "PluginsViewModel: zip extract failed")
            null
        }
    }
}
