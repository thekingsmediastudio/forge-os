package com.forge.os.data.browser

import android.content.Context
import com.forge.os.domain.control.AgentControlPlane
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — persistent browser history with per-session attribution.
 *
 * Each entry records who navigated where and when. The user can scroll the
 * history in [com.forge.os.presentation.screens.browser.BrowserHistoryScreen]
 * and the agent has read-only access via the `browser_history_*` tools.
 *
 * "Sessions" are arbitrary string ids:
 *   - "user" — entries triggered by a tap inside the in-app browser
 *   - "agent" — entries triggered by browser_navigate / agent code
 *   - any other string — caller-defined (e.g. a plugin id)
 *
 * Gated by [AgentControlPlane.BROWSER_HISTORY] (default ON).
 */
@Serializable
data class BrowserHistoryEntry(
    val ts: Long,
    val sessionId: String,
    val url: String,
    val title: String = "",
    val source: String = "user",
)

@Serializable
private data class HistoryFile(val entries: List<BrowserHistoryEntry> = emptyList())

@Singleton
class BrowserHistory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlPlane: AgentControlPlane,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
    private val file: File
        get() = context.filesDir.resolve("workspace/browser/history.json").apply { parentFile?.mkdirs() }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<BrowserHistoryEntry>> = _entries

    private val maxEntries = 5_000

    private fun load(): List<BrowserHistoryEntry> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<HistoryFile>(file.readText()).entries
    }.getOrDefault(emptyList())

    private fun persist(list: List<BrowserHistoryEntry>) {
        runCatching { file.writeText(json.encodeToString(HistoryFile(list))) }
            .onFailure { Timber.w(it, "BrowserHistory persist failed") }
    }

    @Synchronized
    fun record(entry: BrowserHistoryEntry) {
        if (!controlPlane.isEnabled(AgentControlPlane.BROWSER_HISTORY)) return
        val list = (_entries.value + entry).takeLast(maxEntries)
        _entries.value = list
        persist(list)
    }

    fun list(sessionId: String? = null, limit: Int = 200): List<BrowserHistoryEntry> {
        val src = _entries.value.asReversed()
        val filtered = if (sessionId == null) src else src.filter { it.sessionId == sessionId }
        return filtered.take(limit)
    }

    @Synchronized
    fun clear(sessionId: String? = null) {
        val keep = if (sessionId == null) emptyList()
                   else _entries.value.filter { it.sessionId != sessionId }
        _entries.value = keep
        persist(keep)
    }

    fun sessions(): List<String> = _entries.value.map { it.sessionId }.distinct()
}
