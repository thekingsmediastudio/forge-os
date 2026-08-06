package com.forge.os.domain.companion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase P-6 — lightweight mood check-in log.
 *
 * The companion home shows a small check-in card once per day (mood scale +
 * optional note). Entries are appended to
 * `workspace/companion/mood_checkins.json` (capped at [MAX_ENTRIES]) and the
 * latest one is exposed so the chat UI can collapse the card once the user
 * has checked in today.
 *
 * Deliberately separate from [CheckInScheduler] (proactive notifications) —
 * this store only records user-initiated in-chat check-ins.
 */
@Serializable
data class MoodCheckIn(
    val ts: Long,
    /** 1..5 — rough .. great. */
    val mood: Int,
    val note: String = "",
) {
    fun label(): String = when (mood.coerceIn(1, 5)) {
        1 -> "rough"
        2 -> "not great"
        3 -> "okay"
        4 -> "good"
        else -> "great"
    }
}

private const val MAX_ENTRIES = 200

@Singleton
class MoodCheckInStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File by lazy {
        File(context.filesDir, "workspace/companion").apply { mkdirs() }
            .resolve("mood_checkins.json")
    }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<MoodCheckIn>> = _entries

    /** Most recent [limit] entries, newest first. */
    fun recent(limit: Int = 14): List<MoodCheckIn> = _entries.value.takeLast(limit).asReversed()

    /** Today's check-in if one has already been recorded, else null. */
    fun latestToday(now: Long = System.currentTimeMillis()): MoodCheckIn? =
        _entries.value.lastOrNull { sameDay(it.ts, now) }

    fun record(mood: Int, note: String, now: Long = System.currentTimeMillis()) {
        val entry = MoodCheckIn(ts = now, mood = mood.coerceIn(1, 5), note = note.trim())
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        persist()
    }

    private fun load(): List<MoodCheckIn> = try {
        if (file.exists())
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MoodCheckIn.serializer()), file.readText())
        else emptyList()
    } catch (e: Exception) {
        Timber.w(e, "MoodCheckInStore: corrupt file, resetting")
        emptyList()
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(MoodCheckIn.serializer()),
                _entries.value))
        } catch (e: Exception) {
            Timber.e(e, "MoodCheckInStore: persist failed")
        }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
