package com.forge.os.data.browser

import android.content.Context
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
 * Persistent bookmark store for the in-app browser.
 * Backed by `workspace/browser/bookmarks.json`.
 */
@Serializable
data class Bookmark(
    val url: String,
    val title: String = "",
    val ts: Long = System.currentTimeMillis(),
)

@Serializable
private data class BookmarksFile(val items: List<Bookmark> = emptyList())

@Singleton
class BrowserBookmarks @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val file: File
        get() = context.filesDir.resolve("workspace/browser/bookmarks.json")
            .apply { parentFile?.mkdirs() }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Bookmark>> = _items

    private fun load(): List<Bookmark> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<BookmarksFile>(file.readText()).items
    }.getOrDefault(emptyList())

    private fun persist(list: List<Bookmark>) {
        runCatching { file.writeText(json.encodeToString(BookmarksFile(list))) }
            .onFailure { Timber.w(it, "BrowserBookmarks persist failed") }
    }

    @Synchronized
    fun add(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        if (_items.value.any { it.url == url }) return
        val list = _items.value + Bookmark(url, title)
        _items.value = list
        persist(list)
    }

    @Synchronized
    fun remove(url: String) {
        val list = _items.value.filter { it.url != url }
        _items.value = list
        persist(list)
    }

    fun isBookmarked(url: String): Boolean = _items.value.any { it.url == url }
}
