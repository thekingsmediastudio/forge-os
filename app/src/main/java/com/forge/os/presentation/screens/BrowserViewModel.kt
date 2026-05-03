package com.forge.os.presentation.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.browser.Bookmark
import com.forge.os.data.browser.BrowserBookmarks
import com.forge.os.data.browser.BrowserHistory
import com.forge.os.data.browser.BrowserHistoryEntry
import com.forge.os.data.browser.BrowserSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A single browser tab. We keep state minimal — the active tab actually
 *  drives the WebView; switching tabs reloads the URL into the same WebView. */
data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    val sessionManager: BrowserSessionManager,
    val bookmarksStore: BrowserBookmarks,
    val historyStore: BrowserHistory,
) : ViewModel() {

    val currentUrl: StateFlow<String> = sessionManager.currentUrl
    val pageTitle: StateFlow<String> = sessionManager.pageTitle
    val isLoading: StateFlow<Boolean> = sessionManager.isLoading

    val bookmarks: StateFlow<List<Bookmark>> = bookmarksStore.items
    val history: StateFlow<List<BrowserHistoryEntry>> = historyStore.entries

    // ── Tabs ────────────────────────────────────────────────────────────────
    private val _tabs = MutableStateFlow(
        listOf(BrowserTab(id = "tab-${System.currentTimeMillis()}", url = "about:blank"))
    )
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    fun newTab(url: String = "about:blank") {
        val id = "tab-${System.currentTimeMillis()}"
        _tabs.value = _tabs.value + BrowserTab(id = id, url = url)
        _activeTabId.value = id
        if (url != "about:blank") navigateTo(url)
    }

    fun closeTab(id: String) {
        val list = _tabs.value.filter { it.id != id }
        if (list.isEmpty()) {
            // Always keep at least one tab.
            val fresh = BrowserTab(id = "tab-${System.currentTimeMillis()}", url = "about:blank")
            _tabs.value = listOf(fresh)
            _activeTabId.value = fresh.id
            sessionManager.updateUrl("about:blank")
        } else {
            _tabs.value = list
            if (id == _activeTabId.value) {
                val next = list.last()
                _activeTabId.value = next.id
                if (next.url.isNotBlank() && next.url != "about:blank") {
                    navigateTo(next.url)
                } else {
                    sessionManager.updateUrl("about:blank")
                }
            }
        }
    }

    fun switchTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _activeTabId.value = id
        if (tab.url.isNotBlank() && tab.url != "about:blank") navigateTo(tab.url)
        else sessionManager.updateUrl("about:blank")
    }

    /** Update the active tab's bookkeeping when the WebView lands on a new URL. */
    fun rememberActiveTabUrl(url: String, title: String) {
        if (url.isBlank()) return
        _tabs.value = _tabs.value.map {
            if (it.id == _activeTabId.value) it.copy(url = url, title = title) else it
        }
    }

    // ── Bookmarks ───────────────────────────────────────────────────────────
    fun toggleBookmark(url: String, title: String) {
        if (bookmarksStore.isBookmarked(url)) bookmarksStore.remove(url)
        else bookmarksStore.add(url, title)
    }

    fun isBookmarked(url: String): Boolean = bookmarksStore.isBookmarked(url)

    // ── Navigation passthroughs ────────────────────────────────────────────
    fun navigateTo(url: String) {
        viewModelScope.launch { sessionManager.navigate(url) }
    }
    fun goBack() { viewModelScope.launch { sessionManager.goBack() } }
    fun goForward() { viewModelScope.launch { sessionManager.goForward() } }
    fun reload() { viewModelScope.launch { sessionManager.reload() } }
    fun clearAll() { sessionManager.clearAll() }
    fun clearHistory() { historyStore.clear() }
}
