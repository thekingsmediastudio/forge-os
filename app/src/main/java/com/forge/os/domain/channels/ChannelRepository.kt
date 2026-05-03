package com.forge.os.domain.channels

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/system").apply { mkdirs() }
            .resolve("channels.json")
    }

    @Synchronized
    fun all(): List<ChannelConfig> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString<List<ChannelConfig>>(file.readText())
    }.getOrElse {
        Timber.w(it, "ChannelRepository: load failed")
        emptyList()
    }

    @Synchronized
    fun save(list: List<ChannelConfig>) {
        runCatching { file.writeText(json.encodeToString(list)) }
            .onFailure { Timber.e(it, "ChannelRepository: save failed") }
    }

    fun upsert(cfg: ChannelConfig) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == cfg.id }
        if (idx >= 0) list[idx] = cfg else list.add(cfg)
        save(list)
    }

    fun remove(id: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) save(list)
        return removed
    }

    fun find(id: String): ChannelConfig? = all().firstOrNull { it.id == id }
}
