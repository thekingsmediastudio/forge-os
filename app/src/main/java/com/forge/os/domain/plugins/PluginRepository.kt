package com.forge.os.domain.plugins

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filesystem layout (one directory per plugin):
 *
 *   workspace/plugins/<id>/manifest.json
 *   workspace/plugins/<id>/<entrypoint>.py
 *   workspace/plugins/<id>/data/...   (optional plugin-private data)
 *
 * The repository is a thin wrapper over the filesystem; PluginManager owns all
 * lifecycle policy.
 */
@Singleton
class PluginRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    val pluginsRoot: File
        get() = context.filesDir.resolve("workspace/plugins").apply { mkdirs() }

    fun pluginDir(id: String): File = pluginsRoot.resolve(id)
    fun manifestFile(id: String): File = pluginDir(id).resolve("manifest.json")
    fun entrypointFile(id: String, entrypoint: String): File = pluginDir(id).resolve(entrypoint)

    /** Scan the plugins directory and return all valid manifests. */
    fun listManifests(): List<PluginManifest> {
        val root = pluginsRoot
        if (!root.exists()) return emptyList()
        return root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val mf = dir.resolve("manifest.json")
                if (!mf.exists()) return@mapNotNull null
                runCatching { json.decodeFromString<PluginManifest>(mf.readText()) }
                    .onFailure { Timber.w(it, "Bad manifest in ${dir.name}") }
                    .getOrNull()
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun loadManifest(id: String): PluginManifest? {
        val mf = manifestFile(id)
        if (!mf.exists()) return null
        return runCatching { json.decodeFromString<PluginManifest>(mf.readText()) }.getOrNull()
    }

    fun loadEntrypointCode(manifest: PluginManifest): String? {
        val f = entrypointFile(manifest.id, manifest.entrypoint)
        return if (f.exists()) f.readText() else null
    }

    /** Persist manifest + entrypoint to disk. Overwrites any existing plugin with the same id. */
    fun save(manifest: PluginManifest, entrypointCode: String) {
        val dir = pluginDir(manifest.id).apply { mkdirs() }
        manifestFile(manifest.id).writeText(json.encodeToString(manifest))
        entrypointFile(manifest.id, manifest.entrypoint).writeText(entrypointCode)
        Timber.i("PluginRepository: saved plugin ${manifest.id} v${manifest.version}")
    }

    fun updateManifest(manifest: PluginManifest) {
        manifestFile(manifest.id).writeText(json.encodeToString(manifest))
    }

    fun delete(id: String): Boolean {
        val dir = pluginDir(id)
        if (!dir.exists()) return false
        val ok = dir.deleteRecursively()
        Timber.i("PluginRepository: deleted plugin $id (ok=$ok)")
        return ok
    }

    // ─── Storage cap + rollback (Phase G1) ───────────────────────────────────

    /** Total bytes used by all installed plugins (excluding rollback snapshots). */
    fun totalBytes(): Long {
        val root = pluginsRoot
        if (!root.exists()) return 0L
        return root.walkTopDown()
            .filter { it.isFile && !it.path.contains("/.bak/") }
            .sumOf { it.length() }
    }

    private fun rollbackDir(id: String): File = pluginDir(id).resolve(".bak")

    /** Move current plugin contents into `.bak/` (single generation, overwriting any prior). */
    fun snapshotForRollback(id: String): Boolean {
        val dir = pluginDir(id)
        if (!dir.exists()) return false
        val bak = rollbackDir(id)
        if (bak.exists()) bak.deleteRecursively()
        bak.mkdirs()
        dir.listFiles { f -> f.name != ".bak" }?.forEach { f ->
            f.copyRecursively(bak.resolve(f.name), overwrite = true)
        }
        return true
    }

    fun hasRollback(id: String): Boolean = rollbackDir(id).exists() && (rollbackDir(id).listFiles()?.isNotEmpty() == true)

    fun restoreFromRollback(id: String): Boolean {
        val bak = rollbackDir(id)
        if (!hasRollback(id)) return false
        val dir = pluginDir(id)
        // Wipe current (except .bak), then copy back.
        dir.listFiles { f -> f.name != ".bak" }?.forEach { it.deleteRecursively() }
        bak.listFiles()?.forEach { it.copyRecursively(dir.resolve(it.name), overwrite = true) }
        Timber.i("PluginRepository: restored rollback for $id")
        return true
    }
}
