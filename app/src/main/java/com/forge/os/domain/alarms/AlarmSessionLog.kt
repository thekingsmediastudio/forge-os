package com.forge.os.domain.alarms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase R — durable log of every time an alarm fires. Lets the Alarms screen
 * show users a "Sessions" tab so they can verify the alarm actually ran (and
 * see what happened — tool output, agent response, errors), instead of having
 * to trust that a notification meant the action succeeded.
 *
 * Stored at `workspace/alarms/sessions.json` (capped to 200 most recent).
 */
@Serializable
data class AlarmSession(
    val id: String,
    val alarmId: String,
    val label: String,
    val action: AlarmAction,
    val firedAtMs: Long,
    val durationMs: Long,
    val success: Boolean,
    val output: String,
    val error: String? = null,
)

@Serializable
private data class SessionFile(val sessions: List<AlarmSession> = emptyList())

@Singleton
class AlarmSessionLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val cap = 200

    private val file: File
        get() = context.filesDir.resolve("workspace/alarms/sessions.json").apply {
            parentFile?.mkdirs()
        }

    @Synchronized
    fun all(): List<AlarmSession> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString<SessionFile>(file.readText()).sessions
    }.getOrElse { emptyList() }

    @Synchronized
    fun append(s: AlarmSession) {
        val existing = all().toMutableList()
        existing.add(0, s)
        val trimmed = existing.take(cap)
        runCatching { file.writeText(json.encodeToString(SessionFile(trimmed))) }
            .onFailure { Timber.w(it, "AlarmSessionLog: write failed") }
    }

    @Synchronized
    fun forAlarm(alarmId: String, limit: Int = 50): List<AlarmSession> =
        all().filter { it.alarmId == alarmId }.take(limit)

    @Synchronized
    fun clear() {
        runCatching { file.writeText(json.encodeToString(SessionFile(emptyList()))) }
    }
}
