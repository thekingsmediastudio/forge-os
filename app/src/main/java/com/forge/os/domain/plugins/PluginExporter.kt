package com.forge.os.domain.plugins

import android.content.Context
import com.forge.os.domain.control.AgentControlPlane
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — keeps an upgrade-survivable copy of every installed user plugin.
 *
 * On install / uninstall, [PluginManager] calls [exportPlugin] / [removeExport]
 * which write a `.fp` zip bundle (manifest.json + entrypoint + data/) to
 *   <externalFilesDir>/forge_plugins/<id>.fp
 *
 * `getExternalFilesDir` survives APK upgrades for free. On first launch after
 * a fresh install, [restoreMissingPlugins] is called which scans that folder
 * and reinstalls anything not currently present in the internal plugin store.
 *
 * The behaviour is gated by two control-plane capabilities so the user can
 * disable persistent install or auto-restore at any time:
 *   - [AgentControlPlane.PLUGIN_PERSIST]
 *   - [AgentControlPlane.PLUGIN_AUTO_RESTORE]
 */
@Singleton
class PluginExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlPlane: AgentControlPlane,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private fun exportRoot(): File {
        val ext = context.getExternalFilesDir("forge_plugins")
            ?: context.filesDir.resolve("forge_plugins_fallback").also { it.mkdirs() }
        if (!ext.exists()) ext.mkdirs()
        return ext
    }

    fun exportFile(id: String): File = exportRoot().resolve("$id.fp")

    fun listExports(): List<File> = exportRoot().listFiles { f -> f.isFile && f.name.endsWith(".fp") }
        ?.toList().orEmpty()

    /** Write a `.fp` zip for the plugin currently on disk under [pluginDir]. */
    fun exportPlugin(manifest: PluginManifest, pluginDir: File): Result<File> {
        if (!controlPlane.isEnabled(AgentControlPlane.PLUGIN_PERSIST)) {
            return Result.failure(IllegalStateException("plugin_persist disabled"))
        }
        if (manifest.source == "builtin") {
            return Result.failure(IllegalStateException("refusing to export builtin plugin"))
        }
        val target = exportFile(manifest.id)
        return runCatching {
            ZipOutputStream(FileOutputStream(target)).use { zos ->
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(json.encodeToString(manifest).toByteArray())
                zos.closeEntry()
                pluginDir.walkTopDown()
                    .filter { it.isFile && !it.path.contains("/.bak/") && it.name != "manifest.json" }
                    .forEach { f ->
                        val rel = f.relativeTo(pluginDir).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
            Timber.i("PluginExporter: exported ${manifest.id} -> ${target.absolutePath}")
            target
        }
    }

    fun removeExport(id: String): Boolean {
        val f = exportFile(id)
        return if (f.exists()) f.delete() else false
    }

    data class RestoredBundle(
        val manifest: PluginManifest,
        val entrypointCode: String,
    )

    /** Read a `.fp` bundle and return its manifest + entrypoint code. */
    fun readBundle(bundle: File): Result<RestoredBundle> = runCatching {
        var manifest: PluginManifest? = null
        var entrypointCode: String? = null
        ZipInputStream(bundle.inputStream()).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry ->
                val data = zis.readBytes()
                if (entry.name == "manifest.json") {
                    manifest = json.decodeFromString(PluginManifest.serializer(), data.toString(Charsets.UTF_8))
                }
            }
        }
        val mf = manifest ?: error("missing manifest.json in ${bundle.name}")
        // Re-open to extract the entrypoint by name (we now know it).
        ZipInputStream(bundle.inputStream()).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry ->
                val data = zis.readBytes()
                if (entry.name == mf.entrypoint) entrypointCode = data.toString(Charsets.UTF_8)
            }
        }
        RestoredBundle(mf, entrypointCode ?: error("missing entrypoint ${mf.entrypoint}"))
    }

    /**
     * Reinstall any exported plugin that is not currently present on disk.
     * Returns the ids of restored plugins.
     */
    fun restoreMissingPlugins(repository: PluginRepository): List<String> {
        if (!controlPlane.isEnabled(AgentControlPlane.PLUGIN_AUTO_RESTORE)) return emptyList()
        val installed = repository.listManifests().map { it.id }.toSet()
        val restored = mutableListOf<String>()
        for (bundle in listExports()) {
            val readResult = readBundle(bundle)
            val read = readResult.getOrNull()
            if (read == null) {
                Timber.w(readResult.exceptionOrNull(), "PluginExporter: bad bundle ${bundle.name}")
                continue
            }
            if (read.manifest.id in installed) continue
            if (PluginCompatibility.reasonIfIncompatible(read.manifest) != null) continue
            repository.save(read.manifest, read.entrypointCode)
            restored += read.manifest.id
            Timber.i("PluginExporter: restored ${read.manifest.id} from ${bundle.name}")
        }
        return restored
    }
}
