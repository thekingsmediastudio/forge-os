package com.forge.os.data.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.File
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A single completed/enqueued browser download. */
data class BrowserDownloadEntry(
    val id: Long,             // DownloadManager id
    val fileName: String,
    val url: String,
    val mimeType: String = "",
    val totalBytes: Long = 0L,
    val timestamp: Long = System.currentTimeMillis())

/**
 * Registry of files downloaded through the in-app browser. Downloads are
 * delegated to the system [DownloadManager] (so they survive the app and
 * show in the system Downloads UI) and recorded in a small JSON file so the
 * browser can show its own downloads sheet.
 */
@Singleton
class BrowserDownloads @Inject constructor(
    @ApplicationContext private val context: Context) {

    private val gson = Gson()
    private val storeFile by lazy { File(context.filesDir, "browser_downloads.json") }

    private val _entries = MutableStateFlow<List<BrowserDownloadEntry>>(emptyList())
    val entries: StateFlow<List<BrowserDownloadEntry>> = _entries

    init {
        _entries.value = load()
    }

    /** Enqueue a download via the system DownloadManager. Returns the entry, or null on failure. */
    fun enqueue(url: String, contentDisposition: String?, mimeType: String?): BrowserDownloadEntry? {
        return runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription(url)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                // Forward the WebView session cookies so authenticated downloads work
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                // Some servers reject downloads without a UA header
                addRequestHeader("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            val entry = BrowserDownloadEntry(
                id = id,
                fileName = fileName,
                url = url,
                mimeType = mimeType ?: "")
            _entries.value = listOf(entry) + _entries.value
            persist()
            Timber.d("Download enqueued: $fileName ← $url")
            entry
        }.onFailure { Timber.e(it, "Download enqueue failed for $url") }
            .getOrNull()
    }

    fun remove(id: Long) {
        _entries.value = _entries.value.filter { it.id != id }
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    private data class DownloadsFile(val items: List<BrowserDownloadEntry> = emptyList())

    private fun persist() {
        runCatching {
            storeFile.writeText(gson.toJson(DownloadsFile(_entries.value.take(100))))
        }.onFailure { Timber.w(it, "Failed to persist downloads") }
    }

    private fun load(): List<BrowserDownloadEntry> = runCatching {
        if (!storeFile.exists()) return emptyList()
        gson.fromJson(storeFile.readText(), DownloadsFile::class.java)?.items ?: emptyList()
    }.getOrElse { emptyList() }
}
