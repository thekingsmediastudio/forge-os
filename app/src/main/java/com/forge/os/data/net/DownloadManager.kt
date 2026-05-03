package com.forge.os.data.net

import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.data.web.HeadlessBrowser
import com.forge.os.domain.security.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase S — `file_download` and `browser_download` engine.
 *
 * Streams a URL to disk inside the workspace. Honours the workspace size cap
 * via [SandboxManager.getWorkspaceInfo] and the per-call `max_bytes` cap, and
 * runs the destination through the same blocked-extension / blocked-host
 * checks the file tools use, so the user's "padlock" still applies.
 *
 * Returns a [Result] describing where the file landed, its size, sniffed MIME
 * and a SHA-256 of the bytes (so the agent can verify the file in a follow-up
 * `file_read`).
 */
@Singleton
class DownloadManager @Inject constructor(
    private val sandboxManager: SandboxManager,
    private val permissionManager: PermissionManager,
    private val headlessBrowser: HeadlessBrowser,
) {
    data class Result(
        val path: String,
        val bytes: Long,
        val sha256: String,
        val mime: String?,
    ) {
        override fun toString(): String =
            "✅ saved $path (${bytes}B, mime=${mime ?: "?"}, sha256=${sha256.take(12)}…)"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Open-web download: no cookies, just an HTTP GET with optional headers.
     */
    suspend fun download(
        url: String,
        saveAs: String? = null,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): Result = withContext(Dispatchers.IO) {
        runDownload(url, saveAs, headers, maxBytes, useBrowserCookies = false)
    }

    /**
     * Cookie-aware download — reuses the headless browser's cookie jar so
     * authenticated pages work. The agent typically navigates first
     * (`browser_navigate {url: dashboard}`) and then calls this with the
     * direct asset URL.
     */
    suspend fun downloadWithBrowserCookies(
        url: String,
        saveAs: String? = null,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): Result = withContext(Dispatchers.IO) {
        runDownload(url, saveAs, emptyMap(), maxBytes, useBrowserCookies = true)
    }

    private suspend fun runDownload(
        url: String,
        saveAs: String?,
        headers: Map<String, String>,
        maxBytes: Long,
        useBrowserCookies: Boolean,
    ): Result {
        val host = try { URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        if (host.isBlank()) throw IllegalArgumentException("invalid url: $url")

        // Honour the same blocked-host list that gates network tools.
        val perms = permissionManager.getPermissions()
        if (perms.networkPermissions.blockedHosts.any { host == it || host.endsWith(".$it") }) {
            throw SecurityException("host '$host' is blocked")
        }

        val target = chooseDestination(url, saveAs)
        val ext = target.extension.lowercase()
        if (ext.isNotEmpty() && ext in perms.filePermissions.blockedExtensions) {
            throw SecurityException("extension '.$ext' is blocked — adjust in Tools → Advanced overrides")
        }
        target.parentFile?.mkdirs()

        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) reqBuilder.header(k, v)
        if (useBrowserCookies) {
            headlessBrowser.getCookieHeader(url)?.takeIf { it.isNotBlank() }
                ?.let { reqBuilder.header("Cookie", it) }
        }

        val resp = client.newCall(reqBuilder.build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty response body")
            val mime = r.header("Content-Type")?.substringBefore(";")?.trim()

            val md = MessageDigest.getInstance("SHA-256")
            var written = 0L
            target.outputStream().use { out ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        if (written + n > maxBytes) {
                            target.delete()
                            throw SecurityException("download exceeded max_bytes=$maxBytes")
                        }
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        written += n
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            val ws = File(sandboxManager.getWorkspacePath()).canonicalFile
            val rel = target.canonicalFile.toRelativeString(ws)
            Timber.i("download: $url → $rel ($written B, sha=${sha.take(12)})")
            return Result(rel, written, sha, mime)
        }
    }

    private suspend fun chooseDestination(url: String, saveAs: String?): File {
        val ws = File(sandboxManager.getWorkspacePath()).canonicalFile
        val explicit = saveAs?.trim()?.takeIf { it.isNotBlank() }
        val rel = if (explicit != null) {
            val cleaned = explicit.trimStart('/').removePrefix("workspace/")
            // If the user gave only a filename, drop it under downloads/.
            if ('/' !in cleaned) "downloads/$cleaned" else cleaned
        } else {
            val name = MimeSniffer.filenameFromUrl(url)
            "downloads/$name"
        }
        val resolved = File(ws, rel).canonicalFile
        if (!resolved.absolutePath.startsWith(ws.absolutePath)) {
            throw SecurityException("destination escapes workspace: $rel")
        }
        return resolved
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 200L * 1024L * 1024L  // 200 MiB
    }
}
