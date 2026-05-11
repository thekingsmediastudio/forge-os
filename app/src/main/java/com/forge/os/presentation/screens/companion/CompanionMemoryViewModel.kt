package com.forge.os.presentation.screens.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.companion.EpisodicMemory
import com.forge.os.domain.companion.EpisodicMemoryStore
import com.forge.os.domain.memory.LongtermMemory
import com.forge.os.domain.memory.MemoryHit
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.memory.SemanticFactIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Phase O-5 — Memory transparency ViewModel.
 *
 * Surfaces all companion data the app holds about a user — episodes, semantic
 * facts, and long-term stored memories — so they can be inspected and deleted.
 * Provides a "Forget everything" nuclear option that wipes all three stores
 * after a double-confirm from the UI.
 *
 * Design note: every delete is immediate and total (no soft-delete for
 * companion data). The user owns their memory.
 */
@HiltViewModel
class CompanionMemoryViewModel @Inject constructor(
    private val episodicStore: EpisodicMemoryStore,
    private val memoryManager: MemoryManager,
    private val semanticFactIndex: SemanticFactIndex,
) : ViewModel() {

    // ── Episodes ─────────────────────────────────────────────────────────────

    val episodes: StateFlow<List<EpisodicMemory>> = episodicStore.episodes

    fun deleteEpisode(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            episodicStore.delete(id)
        }
    }

    // ── Long-term facts (semantic) ────────────────────────────────────────────

    private val _facts = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val facts: StateFlow<List<Pair<String, String>>> = _facts

    /** Tier-aware view: every fact, skill, and recent daily-log entry tagged
     *  with its source tier and a real timestamp. The Companion Memory screen
     *  uses this to render a unified timeline. */
    private val _allTiers = MutableStateFlow<List<MemoryHit>>(emptyList())
    val allTiers: StateFlow<List<MemoryHit>> = _allTiers

    fun loadFacts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _facts.value = memoryManager.recallAll()
                _allTiers.value = memoryManager.recallAllAcrossTiers()
            } catch (e: Exception) {
                Timber.w(e, "CompanionMemoryViewModel: loadFacts failed")
            }
        }
    }

    fun deleteFact(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryManager.forgetFact(key)
            loadFacts()
        }
    }

    // ── Wipe-all ─────────────────────────────────────────────────────────────

    /** Tracks whether the first confirm has been clicked. */
    private val _wipeConfirmStep = MutableStateFlow(0)
    val wipeConfirmStep: StateFlow<Int> = _wipeConfirmStep

    fun requestWipe() { _wipeConfirmStep.value = 1 }
    fun cancelWipe() { _wipeConfirmStep.value = 0 }

    fun confirmWipe() {
        if (_wipeConfirmStep.value < 1) return
        _wipeConfirmStep.value = 2
        viewModelScope.launch(Dispatchers.IO) {
            try {
                episodicStore.deleteAll()
                memoryManager.wipeAll()
                memoryManager.wipeSemantic()
                _facts.value = emptyList()
                Timber.i("CompanionMemoryViewModel: wipe-all complete")
            } catch (e: Exception) {
                Timber.e(e, "CompanionMemoryViewModel: wipe-all failed")
            } finally {
                _wipeConfirmStep.value = 0
            }
        }
    }

    // ── Summary stats ─────────────────────────────────────────────────────────

    data class MemorySummary(
        val episodeCount: Int,
        val factCount: Int,
        val localOnly: Boolean = true,
    )

    private val _summary = MutableStateFlow(MemorySummary(0, 0))
    val summary: StateFlow<MemorySummary> = _summary

    fun refreshSummary() {
        viewModelScope.launch(Dispatchers.IO) {
            val eps = episodicStore.episodes.value.size
            val fcts = runCatching { memoryManager.recallAll().size }.getOrDefault(0)
            _summary.value = MemorySummary(eps, fcts, localOnly = true)
        }
    }

    init { loadFacts(); refreshSummary() }
}
