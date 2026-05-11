package com.forge.os.domain.agent.providers

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.forge.os.data.api.FunctionDefinition
import com.forge.os.data.api.FunctionParameters
import com.forge.os.data.api.ParameterProperty
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage tools — surface disk usage, volumes, downloads, and cache management.
 *
 * ## Tools
 *
 * | Tool                     | What it does                                              |
 * |--------------------------|-----------------------------------------------------------|
 * | storage_overview         | Internal storage totals: used, free, total (MB)           |
 * | storage_volumes          | All mounted storage volumes (internal + SD card etc.)     |
 * | storage_app_cache        | Forge OS app cache size, and option to clear it           |
 * | storage_list_downloads   | Files in the Downloads folder via MediaStore              |
 * | storage_list_dir         | List files/folders in any directory the agent can access  |
 *
 * ## Permissions
 * * storage_overview / storage_volumes / storage_list_dir (app dirs) — none required.
 * * storage_list_downloads — uses MediaStore, no permission on API 29+.
 * * storage_list_dir on external paths — needs READ_EXTERNAL_STORAGE (≤ API 32)
 *   or READ_MEDIA_* (API 33+), declared in the manifest below.
 * * storage_app_cache (clear) — no permission, only touches Forge OS's own cache.
 */
@Singleton
class StorageToolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool(
            name        = "storage_overview",
            description = "Get the internal storage overview: total size, used space, free space, " +
                          "and percentage used — all in megabytes. Also reports whether the system " +
                          "considers the device to be in a low-storage state.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "storage_volumes",
            description = "List all mounted storage volumes on the device: internal storage and " +
                          "any removable SD cards or USB drives. For each volume shows the path, " +
                          "total size, free space, state (mounted/ejected/etc.), and whether it " +
                          "is the primary storage or removable.",
            params      = emptyMap(),
            required    = emptyList(),
        ),
        tool(
            name        = "storage_app_cache",
            description = "Report Forge OS's own cache and data sizes. Pass clear=true to delete " +
                          "all cached files (thumbnails, temp downloads, HTTP cache) without " +
                          "affecting user data or settings. Useful when the agent needs to free " +
                          "space before a large file operation.",
            params      = mapOf(
                "clear" to ("boolean" to "If true, delete all cache files (default false)"),
            ),
            required    = emptyList(),
        ),
        tool(
            name        = "storage_list_downloads",
            description = "List files in the device's Downloads folder via the system MediaStore. " +
                          "Returns file name, size (KB), MIME type, and date added for each file. " +
                          "Results are sorted newest-first. Works on Android 10+ without extra " +
                          "permissions; on older versions READ_EXTERNAL_STORAGE is required.",
            params      = mapOf(
                "limit"        to ("integer" to "Max files to return (default 30, max 100)"),
                "filter"       to ("string"  to "Optional text to filter by file name (case-insensitive)"),
                "mime_prefix"  to ("string"  to "Filter by MIME type prefix e.g. 'image', 'video', 'application/pdf'"),
            ),
            required    = emptyList(),
        ),
        tool(
            name        = "storage_list_dir",
            description = "List the contents of a directory path — files and sub-folders. " +
                          "Shows name, size (KB for files), last-modified timestamp, and type " +
                          "(file/dir). Works for Forge OS's own data dirs without any permission. " +
                          "For external/SD paths READ_EXTERNAL_STORAGE must be granted. " +
                          "Common paths: /sdcard/Download, /sdcard/DCIM, /sdcard/Documents.",
            params      = mapOf(
                "path"  to ("string"  to "Absolute directory path to list"),
                "limit" to ("integer" to "Max entries to return (default 50, max 200)"),
            ),
            required    = listOf("path"),
        ),
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
        "storage_overview"       -> storageOverview()
        "storage_volumes"        -> storageVolumes()
        "storage_app_cache"      -> appCache(args["clear"]?.toString()?.toBooleanStrictOrNull() ?: false)
        "storage_list_downloads" -> {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                !hasStorageReadPermission())
                return err("READ_EXTERNAL_STORAGE permission not granted. Grant it in Settings → Apps → Forge OS → Permissions.")
            listDownloads(
                limit      = args["limit"]?.toString()?.toIntOrNull() ?: 30,
                filter     = args["filter"]?.toString() ?: "",
                mimePrefix = args["mime_prefix"]?.toString() ?: "",
            )
        }
        "storage_list_dir"       -> {
            val path = args["path"]?.toString() ?: ""
            if (isExternalPath(path) && !hasStorageReadPermission())
                return err("Storage read permission not granted for external path. Grant READ_MEDIA_* (API 33+) or READ_EXTERNAL_STORAGE in Settings → Apps → Forge OS → Permissions.")
            listDir(path = path, limit = args["limit"]?.toString()?.toIntOrNull() ?: 50)
        }
        else -> null
        }
    }

    private fun hasStorageReadPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ||
            hasPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ||
            hasPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun isExternalPath(path: String): Boolean {
        val externalRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        return path.startsWith(externalRoot) || path.startsWith("/sdcard") || path.startsWith("/storage")
    }

    private fun hasPermission(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── storage_overview ──────────────────────────────────────────────────────

    private fun storageOverview(): String = runCatching {
        val path  = Environment.getDataDirectory()
        val stat  = StatFs(path.absolutePath)
        val total = stat.totalBytes  / MB
        val free  = stat.availableBytes / MB
        val used  = total - free
        val pct   = if (total > 0) (used * 100 / total) else 0
        val low   = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED_READ_ONLY ||
                    free < 512  // heuristic: < 512 MB free = low

        buildString {
            appendLine("{")
            appendLine("  \"ok\": true,")
            appendLine("  \"total_mb\": $total,")
            appendLine("  \"used_mb\": $used,")
            appendLine("  \"free_mb\": $free,")
            appendLine("  \"used_pct\": $pct,")
            appendLine("  \"low_storage\": $low")
            append("}")
        }
    }.getOrElse { err(it.message ?: "Failed to read storage overview") }

    // ── storage_volumes ───────────────────────────────────────────────────────

    private fun storageVolumes(): String = runCatching {
        val sm      = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes = sm.storageVolumes

        val arr = volumes.joinToString(",\n  ") { vol ->
            val path  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory?.absolutePath else null
            val state = vol.state
            val isPrimary   = vol.isPrimary
            val isRemovable = vol.isRemovable

            val (total, free) = if (path != null) {
                runCatching {
                    val s = StatFs(path)
                    s.totalBytes / MB to s.availableBytes / MB
                }.getOrElse { -1L to -1L }
            } else { -1L to -1L }

            buildString {
                append("{")
                append("\"state\":${jsonStr(state)}")
                append(",\"primary\":$isPrimary")
                append(",\"removable\":$isRemovable")
                if (path != null) append(",\"path\":${jsonStr(path)}")
                if (total >= 0)   append(",\"total_mb\":$total,\"free_mb\":$free")
                append("}")
            }
        }
        """{"ok":true,"count":${volumes.size},"volumes":[$arr]}"""
    }.getOrElse { err(it.message ?: "Failed to read volumes") }

    // ── storage_app_cache ─────────────────────────────────────────────────────

    private fun appCache(clear: Boolean): String = runCatching {
        val cacheDir = context.cacheDir
        val externalCacheDir = context.externalCacheDir

        val cacheBytes   = dirSize(cacheDir)
        val extBytes     = dirSize(externalCacheDir)
        val totalKb      = (cacheBytes + extBytes) / 1024

        if (clear) {
            deleteDir(cacheDir)
            deleteDir(externalCacheDir)
            val freed = totalKb
            """{"ok":true,"action":"cleared","freed_kb":$freed}"""
        } else {
            buildString {
                appendLine("{")
                appendLine("  \"ok\": true,")
                appendLine("  \"cache_kb\": ${cacheBytes / 1024},")
                appendLine("  \"external_cache_kb\": ${extBytes / 1024},")
                appendLine("  \"total_cache_kb\": $totalKb,")
                appendLine("  \"cache_path\": ${jsonStr(cacheDir.absolutePath)}")
                append("}")
            }
        }
    }.getOrElse { err(it.message ?: "Failed to read app cache") }

    // ── storage_list_downloads ────────────────────────────────────────────────

    private fun listDownloads(limit: Int, filter: String, mimePrefix: String): String = runCatching {
        val cap  = limit.coerceIn(1, 100)
        val uri  = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED,
        )

        val selParts = mutableListOf<String>()
        val selArgs  = mutableListOf<String>()
        if (filter.isNotBlank()) {
            selParts.add("${MediaStore.Downloads.DISPLAY_NAME} LIKE ?")
            selArgs.add("%$filter%")
        }
        if (mimePrefix.isNotBlank()) {
            selParts.add("${MediaStore.Downloads.MIME_TYPE} LIKE ?")
            selArgs.add("$mimePrefix%")
        }
        val sel  = if (selParts.isEmpty()) null else selParts.joinToString(" AND ")
        val args = if (selArgs.isEmpty()) null else selArgs.toTypedArray()

        val cursor: Cursor? = context.contentResolver.query(
            uri, proj, sel, args,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )

        val files = mutableListOf<String>()
        cursor?.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            var count = 0
            while (c.moveToNext() && count < cap) {
                val id      = c.getLong(idCol)
                val name    = c.getString(nameCol) ?: "Unknown"
                val sizeKb  = c.getLong(sizeCol) / 1024
                val mime    = c.getString(mimeCol) ?: "application/octet-stream"
                val epochMs = c.getLong(dateCol) * 1000L
                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
                val fileUri = ContentUris.withAppendedId(uri, id).toString()
                files.add("""{"name":${jsonStr(name)},"size_kb":$sizeKb,"mime":${jsonStr(mime)},"date":${jsonStr(dateStr)},"uri":${jsonStr(fileUri)}}""")
                count++
            }
        }

        """{"ok":true,"count":${files.size},"files":[${files.joinToString(",\n  ")}]}"""
    }.getOrElse {
        if ("permission" in (it.message ?: "").lowercase() || it is SecurityException)
            err("READ_EXTERNAL_STORAGE permission required on Android < 10. On Android 10+, this should work automatically.")
        else
            err(it.message ?: "Failed to list downloads")
    }

    // ── storage_list_dir ──────────────────────────────────────────────────────

    private fun listDir(path: String, limit: Int): String = runCatching {
        if (path.isBlank()) return err("path is required")
        val cap = limit.coerceIn(1, 200)
        val dir = File(path)
        if (!dir.exists()) return err("Path does not exist: $path")
        if (!dir.isDirectory) return err("Path is a file, not a directory: $path")

        val entries = dir.listFiles()?.take(cap) ?: emptyList()
        val fmt     = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val arr = entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString(",\n  ") { f ->
                val type    = if (f.isDirectory) "dir" else "file"
                val sizeKb  = if (f.isFile) f.length() / 1024 else 0L
                val modified = fmt.format(Date(f.lastModified()))
                buildString {
                    append("{\"name\":${jsonStr(f.name)}")
                    append(",\"type\":${jsonStr(type)}")
                    if (f.isFile) append(",\"size_kb\":$sizeKb")
                    append(",\"modified\":${jsonStr(modified)}")
                    append("}")
                }
            }

        """{"ok":true,"path":${jsonStr(path)},"count":${entries.size},"entries":[$arr]}"""
    }.getOrElse {
        if (it is SecurityException)
            err("Permission denied: ${it.message}. External storage may require READ_EXTERNAL_STORAGE or READ_MEDIA_* permission.")
        else
            err(it.message ?: "Failed to list directory")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteDir(dir: File?) {
        dir?.walkTopDown()?.toList()?.asReversed()?.forEach { it.delete() }
    }

    private fun jsonStr(s: String) =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun err(msg: String) = """{"ok":false,"error":"${msg.replace("\"", "'")}"}"""

    private fun tool(
        name: String,
        description: String,
        params: Map<String, Pair<String, String>>,
        required: List<String>,
    ) = ToolDefinition(
        function = FunctionDefinition(
            name        = name,
            description = description,
            parameters  = FunctionParameters(
                properties = params.mapValues { (_, v) ->
                    ParameterProperty(type = v.first, description = v.second)
                },
                required   = required,
            ),
        ),
    )

    companion object {
        private const val MB = 1024L * 1024L
    }
}
