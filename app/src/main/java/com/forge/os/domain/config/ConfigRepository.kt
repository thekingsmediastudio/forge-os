package com.forge.os.domain.config

import android.content.Context
import com.forge.os.presentation.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ConfigVersion(
    val version: String,
    val timestamp: Long,
    val changeNote: String
)

@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val systemDir = File(context.filesDir, "workspace/system").apply { mkdirs() }
    private val versionsDir = File(systemDir, "versions").apply { mkdirs() }
    private val configFile = File(systemDir, "config.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile private var cached: ForgeConfig? = null

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _configFlow = MutableStateFlow(ForgeConfig())
    val configFlow: StateFlow<ForgeConfig> = _configFlow.asStateFlow()

    init {
        // Prime the in-memory cache and theme flow on construction.
        val initial = get()
        _themeMode.value = initial.appearance.themeMode
        _configFlow.value = initial
    }

    fun get(): ForgeConfig {
        cached?.let { return it }
        return if (configFile.exists()) {
            try {
                json.decodeFromString<ForgeConfig>(configFile.readText()).also { cached = it }
            } catch (e: Exception) {
                Timber.w("Config corrupted, resetting: ${e.message}")
                ForgeConfig().also { save(it) }
            }
        } else {
            ForgeConfig().also { save(it) }
        }
    }

    fun save(config: ForgeConfig): ForgeConfig {
        configFile.writeText(json.encodeToString(config))
        cached = config
        _themeMode.value = config.appearance.themeMode
        _configFlow.value = config
        return config
    }

    fun setThemeMode(mode: ThemeMode) {
        val current = get()
        if (current.appearance.themeMode == mode) return
        save(current.copy(appearance = current.appearance.copy(themeMode = mode)))
    }

    suspend fun update(mutation: (ForgeConfig) -> ForgeConfig): ForgeConfig =
        withContext(Dispatchers.IO) {
            val current = get()
            createSnapshot(current, "pre-update")
            val updated = mutation(current).copy(version = bumpPatch(current.version))
            save(updated)
            Timber.i("Config ${current.version} → ${updated.version}")
            updated
        }

    fun createSnapshot(config: ForgeConfig = get(), note: String = "manual"): ConfigVersion {
        File(versionsDir, "config.v${config.version}.json")
            .writeText(json.encodeToString(config))
        return ConfigVersion(config.version, System.currentTimeMillis(), note)
    }

    fun listSnapshots(): List<ConfigVersion> =
        versionsDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                try {
                    val cfg = json.decodeFromString<ForgeConfig>(file.readText())
                    ConfigVersion(cfg.version, file.lastModified(), file.name)
                } catch (e: Exception) { null }
            } ?: emptyList()

    fun restoreSnapshot(version: String): ForgeConfig {
        val file = File(versionsDir, "config.v${version}.json")
        if (!file.exists()) throw IllegalArgumentException("Snapshot v$version not found")
        return json.decodeFromString<ForgeConfig>(file.readText()).also { save(it) }
    }

    private fun bumpPatch(v: String): String = try {
        val p = v.split(".").map { it.toInt() }.toMutableList()
        p[2]++; p.joinToString(".")
    } catch (e: Exception) { "1.0.1" }
}
