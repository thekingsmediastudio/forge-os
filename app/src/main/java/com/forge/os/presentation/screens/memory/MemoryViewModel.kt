package com.forge.os.presentation.screens.memory

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agent.ReflectionStore
import com.forge.os.domain.agent.StoredPattern
import com.forge.os.domain.companion.EpisodicMemory
import com.forge.os.domain.companion.EpisodicMemoryStore
import com.forge.os.domain.memory.DailyEvent
import com.forge.os.domain.memory.FactEntry
import com.forge.os.domain.memory.MemoryArchive
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
import timber.log.Timber
import java.io.File
import javax.inject.Inject

enum class MemoryTab { DAILY, FACTS, SKILLS, EPISODES, REFLECTIONS }
enum class SearchMode { LEXICAL, SEMANTIC }

data class MemoryUiState(
    val tab: MemoryTab = MemoryTab.FACTS,
    val query: String = "",
    val searchMode: SearchMode = SearchMode.LEXICAL,
    val daily: List<DailyEvent> = emptyList(),
    val facts: List<FactEntry> = emptyList(),
    val skills: List<SkillEntry> = emptyList(),
    val episodes: List<EpisodicMemory> = emptyList(),
    val reflections: List<com.forge.os.domain.agent.StoredPattern> = emptyList(),
    /** Phase J2: filled when [searchMode] = SEMANTIC; key → cosine score. */
    val factScores: Map<String, Float> = emptyMap(),
    val semanticBusy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryManager: MemoryManager,
    private val episodicStore: EpisodicMemoryStore,
    private val reflectionStore: ReflectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val s = _state.value
        val q = s.query.lowercase()
        val daily = memoryManager.daily.readRecent(days = 7)
            .let { if (q.isBlank()) it else it.filter { e -> e.content.lowercase().contains(q) } }
        val factsLexical = memoryManager.longterm.getAll().values
            .let { if (q.isBlank()) it.toList() else it.filter { f ->
                f.key.lowercase().contains(q) || f.content.lowercase().contains(q) ||
                f.tags.any { t -> t.lowercase().contains(q) }
            } }
            .sortedByDescending { it.timestamp }
        val skills = memoryManager.skill.getAll()
            .let { if (q.isBlank()) it else it.filter { sk ->
                sk.name.lowercase().contains(q) || sk.description.lowercase().contains(q)
            } }
        val episodes = episodicStore.all()
            .let { if (q.isBlank()) it else it.filter { e ->
                e.summary.lowercase().contains(q) ||
                e.keyTopics.any { t -> t.lowercase().contains(q) } ||
                e.moodTrajectory.lowercase().contains(q)
            } }
        _state.value = s.copy(
            daily = daily, facts = factsLexical, skills = skills, episodes = episodes,
            factScores = if (s.searchMode == SearchMode.LEXICAL) emptyMap() else s.factScores,
        )
        // Load reflections async (suspend function)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reflections = if (q.isBlank()) {
                    reflectionStore.getAll()
                } else {
                    reflectionStore.getRelevant(q, limit = 50)
                }
                _state.value = _state.value.copy(reflections = reflections)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load reflections")
            }
        }
        // Phase J2: in semantic mode, kick off an async re-rank of the facts list.
        if (s.searchMode == SearchMode.SEMANTIC && s.tab == MemoryTab.FACTS && q.isNotBlank()) {
            runSemanticFactSearch(s.query)
        }
    }

    /** Phase J2 — toggles the FACTS-tab search mode between substring and vector. */
    fun toggleSearchMode() {
        val cur = _state.value.searchMode
        _state.value = _state.value.copy(
            searchMode = if (cur == SearchMode.LEXICAL) SearchMode.SEMANTIC else SearchMode.LEXICAL,
            factScores = emptyMap(),
        )
        refresh()
    }

    private fun runSemanticFactSearch(query: String) {
        _state.value = _state.value.copy(semanticBusy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hits = memoryManager.semanticRecallFacts(query, k = 25)
                val all = memoryManager.longterm.getAll()
                val ordered = hits.mapNotNull { all[it.key] }
                val scores = hits.associate { it.key to it.score }
                _state.value = _state.value.copy(
                    facts = ordered, factScores = scores, semanticBusy = false,
                )
            } catch (e: Exception) {
                Timber.w(e, "Semantic fact search failed")
                _state.value = _state.value.copy(
                    semanticBusy = false,
                    message = "❌ Semantic search failed: ${e.message}",
                )
            }
        }
    }

    fun deleteEpisode(id: String) {
        episodicStore.delete(id)
        refresh()
    }

    fun selectTab(t: MemoryTab) { _state.value = _state.value.copy(tab = t); refresh() }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q); refresh() }

    fun deleteFact(key: String) {
        memoryManager.forgetFact(key)
        memoryManager.rebuildIndex()
        refresh()
    }

    fun upsertFact(key: String, content: String, tags: List<String>) {
        memoryManager.store(key, content, tags)
        refresh()
    }

    fun deleteSkill(name: String) {
        memoryManager.skill.delete(name)
        memoryManager.rebuildIndex()
        refresh()
    }

    fun upsertSkill(name: String, description: String, code: String, tags: List<String>) {
        memoryManager.storeSkill(name, description, code, tags)
        refresh()
    }

    /** Wipes all three tiers. Plain confirmation in the UI; biometric gating
     *  is a documented follow-up — this drop ships an unencrypted wipe. */
    fun wipeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                memoryManager.longterm.getAll().keys.toList().forEach { memoryManager.longterm.delete(it) }
                memoryManager.skill.getAll().forEach { memoryManager.skill.delete(it.name) }
                File(context.filesDir, "workspace/memory/daily").listFiles()?.forEach { it.delete() }
                episodicStore.deleteAll()
                memoryManager.wipeSemantic()
                memoryManager.rebuildIndex()
                _state.value = _state.value.copy(message = "Memory wiped")
            } catch (e: Exception) {
                Timber.e(e, "wipe failed")
                _state.value = _state.value.copy(message = "❌ wipe failed: ${e.message}")
            }
            refresh()
        }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val archive = MemoryArchive(
                    facts = memoryManager.longterm.getAll(),
                    skills = memoryManager.skill.getAll(),
                    daily = memoryManager.daily.readRecent(days = 30),
                )
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(MemoryArchive.toJson(archive).toByteArray())
                }
                _state.value = _state.value.copy(message = "Exported ${archive.facts.size} facts / ${archive.skills.size} skills")
            } catch (e: Exception) {
                Timber.e(e, "export failed")
                _state.value = _state.value.copy(message = "❌ export failed: ${e.message}")
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@launch
                val archive = MemoryArchive.fromJson(text)
                archive.facts.forEach { (k, f) -> memoryManager.store(k, f.content, f.tags) }
                archive.skills.forEach { s -> memoryManager.storeSkill(s.name, s.description, s.code, s.tags) }
                memoryManager.rebuildIndex()
                _state.value = _state.value.copy(message = "Imported ${archive.facts.size} facts / ${archive.skills.size} skills")
                refresh()
            } catch (e: Exception) {
                Timber.e(e, "import failed")
                _state.value = _state.value.copy(message = "❌ import failed: ${e.message}")
            }
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
