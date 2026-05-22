package com.forge.os.domain.channels

import android.content.Context
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.conversations.StoredApiMessage
import com.forge.os.data.conversations.toApi
import com.forge.os.data.conversations.toStored
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ChannelHistoryMap(
    val histories: Map<String, List<StoredApiMessage>>
)

/**
 * Persists per-chat conversation history for external channels (Telegram, etc.).
 * This ensures that if the app is killed or restarted, the agent doesn't lose
 * context of the current conversation.
 */
@Singleton
class ChannelHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/system").apply { mkdirs() }
            .resolve("channel_history.json")
    }

    @Synchronized
    fun loadAll(): Map<String, List<ApiMessage>> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        val data = json.decodeFromString<ChannelHistoryMap>(file.readText())
        data.histories.mapValues { (_, msgs) -> msgs.map { it.toApi() } }
    }.getOrElse {
        Timber.w(it, "ChannelHistoryRepository: load failed")
        emptyMap()
    }

    @Synchronized
    fun saveAll(histories: Map<String, List<ApiMessage>>) {
        runCatching {
            val data = ChannelHistoryMap(
                histories = histories.mapValues { (_, msgs) -> msgs.map { it.toStored() } }
            )
            file.writeText(json.encodeToString(data))
        }.onFailure {
            Timber.e(it, "ChannelHistoryRepository: save failed")
        }
    }
}
