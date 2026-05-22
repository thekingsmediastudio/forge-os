package com.forge.os.presentation.screens.python

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

data class PythonPackage(
    val name: String,
    val version: String
)

data class PythonPackageUiState(
    val packages: List<PythonPackage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PythonPackageListViewModel @Inject constructor(
    private val sandboxManager: SandboxManager
) : ViewModel() {

    private val _state = MutableStateFlow(PythonPackageUiState())
    val state: StateFlow<PythonPackageUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val code = """
import sys
try:
    import importlib.metadata as _meta
    dists = sorted(_meta.distributions(), key=lambda d: (d.metadata.get('Name') or '').lower())
    seen = set()
    result = []
    for d in dists:
        name = d.metadata.get('Name') or ''
        version = d.metadata.get('Version') or ''
        key = name.lower()
        if name and key not in seen:
            seen.add(key)
            result.append(f"{name}=={version}")
    if not result:
        print("NONE")
    else:
        for r in result:
            print(r)
except Exception as e:
    print(f"ERROR: {e}")
""".trimIndent()

            val result = sandboxManager.executePython(code)
            result.fold(
                onSuccess = { outputJson ->
                    try {
                        val element = Json.parseToJsonElement(outputJson).jsonObject
                        val success = element["success"]?.jsonPrimitive?.booleanOrNull ?: false
                        val output = element["output"]?.jsonPrimitive?.contentOrNull ?: ""
                        
                        if (success) {
                            if (output.trim() == "NONE") {
                                _state.value = _state.value.copy(packages = emptyList(), isLoading = false)
                            } else if (output.startsWith("ERROR:")) {
                                _state.value = _state.value.copy(error = output, isLoading = false)
                            } else {
                                val pkgs = output.lines()
                                    .filter { it.contains("==") }
                                    .map { 
                                        val parts = it.split("==")
                                        PythonPackage(parts[0], parts[1])
                                    }
                                    .sortedBy { it.name.lowercase() }
                                _state.value = _state.value.copy(packages = pkgs, isLoading = false)
                            }
                        } else {
                            val error = element["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                            _state.value = _state.value.copy(error = error, isLoading = false)
                        }
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(error = "Parse error: ${e.message}", isLoading = false)
                    }
                },
                onFailure = {
                    _state.value = _state.value.copy(error = it.message, isLoading = false)
                }
            )
        }
    }
}
