package com.forge.os.domain.alarms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/system").apply { mkdirs() }
            .resolve("alarms.json")
    }

    @Synchronized
    fun all(): List<AlarmItem> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString<List<AlarmItem>>(file.readText())
    }.getOrElse {
        Timber.w(it, "AlarmRepository: load failed")
        emptyList()
    }

    @Synchronized
    fun save(items: List<AlarmItem>) {
        runCatching { file.writeText(json.encodeToString(items)) }
            .onFailure { Timber.e(it, "AlarmRepository: save failed") }
    }

    fun upsert(item: AlarmItem) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(item)
        save(list)
    }

    fun remove(id: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) save(list)
        return removed
    }

    fun find(id: String): AlarmItem? = all().firstOrNull { it.id == id }
}
