package com.forge.os.data.conversations

import android.content.Context
import com.forge.os.data.api.ApiMessage
import com.forge.os.presentation.screens.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val isError: Boolean = false,
    val attachmentPath: String? = null,
    val attachmentMime: String? = null,
)

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastProviderLabel: String? = null,
    val lastProviderName: String? = null,
    val lastModel: String? = null,
    val messages: List<StoredChatMessage> = emptyList(),
    val apiHistory: List<StoredApiMessage> = emptyList()
)

@Serializable
data class StoredApiMessage(
    val role: String,
    val content: String? = null
)

/**
 * Phase E2 — Conversation persistence.
 * One JSON file per conversation under workspace/conversations/<id>.json.
 * "current.txt" stores the active conversation id so the chat reopens to the
 * last session on app restart.
 */
@Singleton
class ConversationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val dir = File(context.filesDir, "workspace/conversations").apply { mkdirs() }
    private val currentFile = File(dir, "current.txt")

    private val _currentIdFlow = MutableStateFlow(currentId())
    val currentIdFlow: StateFlow<String?> = _currentIdFlow

    private val _listFlow = MutableStateFlow(list())
    val listFlow: StateFlow<List<StoredConversation>> = _listFlow

    fun list(): List<StoredConversation> = dir.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { runCatching { json.decodeFromString<StoredConversation>(it.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun rename(id: String, newTitle: String): Boolean {
        val conv = load(id) ?: return false
        save(conv.copy(title = newTitle.ifBlank { conv.title }, updatedAt = System.currentTimeMillis()))
        return true
    }

    fun load(id: String): StoredConversation? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try { json.decodeFromString(f.readText()) }
        catch (e: Exception) { Timber.w(e, "ConvRepo load $id"); null }
    }

    fun save(conv: StoredConversation) {
        try {
            File(dir, "${conv.id}.json").writeText(json.encodeToString(conv))
            _listFlow.value = list()
        } catch (e: Exception) { Timber.w(e, "ConvRepo save ${conv.id}") }
    }

    fun delete(id: String): Boolean {
        val ok = File(dir, "$id.json").delete()
        if (currentId() == id) clearCurrent()
        _listFlow.value = list()
        return ok
    }

    fun newConversation(): StoredConversation {
        val id = "conv-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val conv = StoredConversation(
            id = id, title = "New conversation",
            createdAt = now, updatedAt = now
        )
        save(conv)
        setCurrent(id)
        return conv
    }

    fun currentId(): String? = if (currentFile.exists()) currentFile.readText().trim().ifEmpty { null } else null

    fun setCurrent(id: String) {
        try { currentFile.writeText(id); _currentIdFlow.value = id } catch (e: Exception) { Timber.w(e) }
    }

    fun clearCurrent() {
        try { currentFile.delete() } catch (_: Exception) {}
        _currentIdFlow.value = null
    }

    /** Open the active conversation, creating one if none exists. */
    fun loadOrCreateCurrent(): StoredConversation {
        val id = currentId()
        if (id != null) load(id)?.let { return it }
        return newConversation()
    }
}

// ─── Mapping helpers (UI <-> persistence) ───────────────────────────────────

fun ChatMessage.toStored() = StoredChatMessage(
    id = id, role = role, content = content,
    toolName = toolName, isError = isError,
    attachmentPath = attachmentPath, attachmentMime = attachmentMime,
)

fun StoredChatMessage.toUi() = ChatMessage(
    id = id, role = role, content = content,
    toolName = toolName, isError = isError, isStreaming = false,
    attachmentPath = attachmentPath, attachmentMime = attachmentMime,
)

fun ApiMessage.toStored() = StoredApiMessage(role = role, content = content)
fun StoredApiMessage.toApi() = ApiMessage(role = role, content = content)
