package com.forge.os.data.mcp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's configured MCP servers as a JSON array at
 * `workspace/system/mcp_servers.json`.
 */
@Singleton
class McpServerRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = File(context.filesDir, "workspace/system/mcp_servers.json")

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<McpServer>> = _servers

    fun list(): List<McpServer> = _servers.value

    fun get(id: String): McpServer? = _servers.value.firstOrNull { it.id == id }

    fun upsert(server: McpServer) {
        val updated = _servers.value.toMutableList()
        val idx = updated.indexOfFirst { it.id == server.id }
        if (idx >= 0) updated[idx] = server else updated.add(server)
        persist(updated)
    }

    fun add(name: String, url: String, authToken: String?): McpServer {
        val s = McpServer(
            id = "mcp-${System.currentTimeMillis()}",
            name = name.ifBlank { url },
            url = url.trim(),
            authToken = authToken?.trim()?.takeIf { it.isNotBlank() },
            enabled = true,
        )
        upsert(s)
        return s
    }

    fun setEnabled(id: String, enabled: Boolean) {
        get(id)?.let { upsert(it.copy(enabled = enabled)) }
    }

    fun remove(id: String): Boolean {
        val updated = _servers.value.filterNot { it.id == id }
        if (updated.size == _servers.value.size) return false
        persist(updated)
        return true
    }

    private fun load(): List<McpServer> = try {
        if (file.exists()) json.decodeFromString<List<McpServer>>(file.readText()) else emptyList()
    } catch (e: Exception) { Timber.w(e, "McpServerRepository load"); emptyList() }

    private fun persist(list: List<McpServer>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(list))
            _servers.value = list
        } catch (e: Exception) { Timber.w(e, "McpServerRepository persist") }
    }
}
