package com.forge.os.domain.sentinel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Sentinel triggers to the workspace system directory.
 */
@Singleton
class SentinelRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val folder = File(context.filesDir, "workspace/system/sentinels")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        if (!folder.exists()) folder.mkdirs()
    }

    fun all(): List<SentinelTrigger> {
        return folder.listFiles { _, name -> name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<SentinelTrigger>(file.readText()) }.getOrNull()
            } ?: emptyList()
    }

    fun byId(id: String): SentinelTrigger? {
        val file = File(folder, "$id.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<SentinelTrigger>(file.readText()) }.getOrNull()
    }

    fun save(trigger: SentinelTrigger) {
        val file = File(folder, "${trigger.id}.json")
        file.writeText(json.encodeToString(trigger))
    }

    fun remove(id: String): Boolean {
        val file = File(folder, "$id.json")
        return file.delete()
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val trigger = byId(id) ?: return false
        save(trigger.copy(enabled = enabled))
        return true
    }
}
