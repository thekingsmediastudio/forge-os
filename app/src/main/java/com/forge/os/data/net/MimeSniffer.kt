package com.forge.os.data.net

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase S — small URL → filename helper used by `DownloadManager` when the
 * agent didn't pass `save_as`. Picks a sensible name from the URL path,
 * with a timestamp fallback for paths that don't have one.
 */
object MimeSniffer {
    fun filenameFromUrl(url: String): String {
        val raw = try { URI(url).path?.substringAfterLast('/') ?: "" } catch (e: Exception) { "" }
        val cleaned = raw.substringBefore('?').substringBefore('#').trim()
        if (cleaned.isNotBlank() && cleaned != "/") return cleaned
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "download_$ts.bin"
    }
}
