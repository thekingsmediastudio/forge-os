package com.forge.os.domain.directives

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectivesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storageDir = File(context.filesDir, "forge/directives")
    private val storageFile = File(storageDir, "directives.json")

    init {
        if (!storageDir.exists()) storageDir.mkdirs()
    }

    @Synchronized
    fun getAll(): List<AgentDirective> {
        if (!storageFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<AgentDirective>>(storageFile.readText())
        } catch (e: Exception) {
            Timber.w(e, "Failed to load directives")
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(directives: List<AgentDirective>) {
        try {
            storageFile.writeText(json.encodeToString(directives))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save directives")
        }
    }

    fun add(directive: AgentDirective) {
        val all = getAll().filter { it.id != directive.id } + directive
        saveAll(all)
    }

    fun remove(id: String) {
        saveAll(getAll().filter { it.id != id })
    }

    fun toggle(id: String, enabled: Boolean) {
        val all = getAll().map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveAll(all)
    }
}
