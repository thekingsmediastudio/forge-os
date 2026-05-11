package com.forge.os.presentation.screens.skills

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.memory.SkillEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class SkillsUiState(
    val skills: List<SkillEntry> = emptyList(),
    val query: String = "",
    val testingName: String? = null,
    val testOutput: String? = null,
    val message: String? = null,
)

@HiltViewModel
class SkillsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryManager: MemoryManager,
    private val sandboxManager: SandboxManager,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val q = _state.value.query
        val skills = if (q.isBlank()) memoryManager.skill.getAll() else memoryManager.skill.search(q)
        _state.value = _state.value.copy(skills = skills.sortedByDescending { it.useCount })
    }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q); refresh() }

    fun upsert(name: String, description: String, code: String, tags: List<String>) {
        memoryManager.storeSkill(name, description, code, tags); refresh()
    }

    fun delete(name: String) {
        memoryManager.skill.delete(name); memoryManager.rebuildIndex(); refresh()
    }

    fun runTest(name: String) {
        viewModelScope.launch {
            val skill = memoryManager.skill.recall(name) ?: return@launch
            _state.value = _state.value.copy(testingName = name, testOutput = "running…")
            val r = withContext(Dispatchers.IO) { sandboxManager.executePython(skill.code) }
            _state.value = _state.value.copy(
                testingName = null,
                testOutput = r.fold({ "✅\n$it" }, { "❌ ${it.message}" }),
            )
            refresh()
        }
    }

    fun clearTestOutput() { _state.value = _state.value.copy(testOutput = null) }

    fun exportSkill(uri: Uri, skill: SkillEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.encodeToString(skill).toByteArray())
                }
                _state.value = _state.value.copy(message = "Exported ${skill.name}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "❌ export: ${e.message}")
            }
        }
    }

    fun importSkill(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@launch
                val s = json.decodeFromString<SkillEntry>(text)
                memoryManager.storeSkill(s.name, s.description, s.code, s.tags)
                _state.value = _state.value.copy(message = "Imported ${s.name}")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "❌ import: ${e.message}")
            }
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
