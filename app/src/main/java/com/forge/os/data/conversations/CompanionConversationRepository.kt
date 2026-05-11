package com.forge.os.data.conversations

import android.content.Context
import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.companion.MessageTags
import com.forge.os.presentation.screens.companion.CompanionMessage
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
data class StoredCompanionMessage(
    val id: String,
    val role: String,
    val content: String,
    val tagsIntent: String? = null,
    val tagsEmotion: String? = null,
    val tagsUrgency: Int = 0,
    val isCrisisResponse: Boolean = false,
    val isSafetyBlocked: Boolean = false,
)

@Serializable
data class StoredCompanionConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredCompanionMessage> = emptyList(),
    val apiHistory: List<StoredApiMessage> = emptyList(),
)

@Singleton
class CompanionConversationRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val dir = File(context.filesDir, "workspace/companion/conversations").apply { mkdirs() }
    private val currentFile = File(dir, "current.txt")

    private val _currentIdFlow = MutableStateFlow(currentId())
    val currentIdFlow: StateFlow<String?> = _currentIdFlow

    private val _listFlow = MutableStateFlow(list())
    val listFlow: StateFlow<List<StoredCompanionConversation>> = _listFlow

    fun list(): List<StoredCompanionConversation> = dir.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { runCatching { json.decodeFromString<StoredCompanionConversation>(it.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun load(id: String): StoredCompanionConversation? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try { json.decodeFromString(f.readText()) }
        catch (e: Exception) { Timber.w(e, "CompanionRepo load $id"); null }
    }

    fun save(conv: StoredCompanionConversation) {
        try {
            File(dir, "${conv.id}.json").writeText(json.encodeToString(conv))
            _listFlow.value = list()
        } catch (e: Exception) { Timber.w(e, "CompanionRepo save ${conv.id}") }
    }

    fun rename(id: String, newTitle: String): Boolean {
        val conv = load(id) ?: return false
        save(conv.copy(title = newTitle.ifBlank { conv.title }, updatedAt = System.currentTimeMillis()))
        return true
    }

    fun delete(id: String): Boolean {
        val ok = File(dir, "$id.json").delete()
        if (currentId() == id) clearCurrent()
        _listFlow.value = list()
        return ok
    }

    fun newConversation(): StoredCompanionConversation {
        val id = "comp-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val conv = StoredCompanionConversation(
            id = id, title = "New conversation",
            createdAt = now, updatedAt = now,
        )
        save(conv); setCurrent(id)
        return conv
    }

    fun currentId(): String? = if (currentFile.exists())
        currentFile.readText().trim().ifEmpty { null } else null

    fun setCurrent(id: String) {
        try { currentFile.writeText(id); _currentIdFlow.value = id } catch (e: Exception) { Timber.w(e) }
    }

    fun clearCurrent() {
        try { currentFile.delete() } catch (_: Exception) {}
        _currentIdFlow.value = null
    }

    fun loadOrCreateCurrent(): StoredCompanionConversation {
        val id = currentId()
        if (id != null) load(id)?.let { return it }
        return newConversation()
    }
}

// ─── Mapping helpers ────────────────────────────────────────────────────────

fun CompanionMessage.toStored() = StoredCompanionMessage(
    id = id,
    role = role,
    content = content,
    tagsIntent = tags?.intent?.name,
    tagsEmotion = tags?.emotion,
    tagsUrgency = tags?.urgency ?: 0,
    isCrisisResponse = isCrisisResponse,
    isSafetyBlocked = isSafetyBlocked,
)

fun StoredCompanionMessage.toUi(): CompanionMessage {
    val tags = if (tagsIntent != null) {
        val intent = runCatching {
            com.forge.os.domain.companion.MessageIntent.valueOf(tagsIntent!!)
        }.getOrDefault(com.forge.os.domain.companion.MessageIntent.SHARE)
        MessageTags(intent = intent, emotion = tagsEmotion ?: "neutral", urgency = tagsUrgency)
    } else null
    return CompanionMessage(
        id = id, role = role, content = content,
        isStreaming = false,
        tags = tags,
        isCrisisResponse = isCrisisResponse,
        isSafetyBlocked = isSafetyBlocked,
    )
}