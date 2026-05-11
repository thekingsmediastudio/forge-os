package com.forge.os.domain.companion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase L — small JSON-persisted ledger of proactive check-ins.
 *
 * Stored at `workspace/companion/checkin_state.json`. Tracks:
 *  - per-day notification count (for the daily rate limit);
 *  - the last-seen morning-check-in date (so we only fire one per day);
 *  - the set of episode IDs we've already used for follow-ups / anniversaries
 *    (so we never re-notify the same episode).
 */
@Serializable
data class CheckInLedger(
    val countByDay: Map<String, Int> = emptyMap(),
    val lastMorningDay: String? = null,
    val firedFollowUps: Set<String> = emptySet(),
    val firedAnniversaries: Set<String> = emptySet(),
)

@Singleton
class CheckInState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val file: File by lazy {
        File(context.filesDir, "workspace/companion").apply { mkdirs() }
            .resolve("checkin_state.json")
    }

    @Volatile private var cached: CheckInLedger = load()

    private fun load(): CheckInLedger = try {
        if (file.exists()) json.decodeFromString(CheckInLedger.serializer(), file.readText())
        else CheckInLedger()
    } catch (e: Exception) {
        Timber.w(e, "CheckInState: corrupt ledger, resetting")
        CheckInLedger()
    }

    private fun persist() {
        try { file.writeText(json.encodeToString(cached)) }
        catch (e: Exception) { Timber.e(e, "CheckInState: persist failed") }
    }

    @Synchronized
    fun snapshot(): CheckInLedger = cached

    @Synchronized
    fun firedToday(now: Long = System.currentTimeMillis()): Int =
        cached.countByDay[dayKey(now)] ?: 0

    @Synchronized
    fun recordFired(now: Long = System.currentTimeMillis()) {
        val today = dayKey(now)
        // Garbage-collect anything older than 7 days while we're here.
        val cutoff = today.let { dayKey(now - 7L * 86_400_000L) }
        val pruned = cached.countByDay.filterKeys { it >= cutoff }.toMutableMap()
        pruned[today] = (pruned[today] ?: 0) + 1
        cached = cached.copy(countByDay = pruned)
        persist()
    }

    @Synchronized
    fun markMorning(now: Long = System.currentTimeMillis()) {
        cached = cached.copy(lastMorningDay = dayKey(now))
        persist()
    }

    @Synchronized
    fun markFollowUp(episodeId: String) {
        cached = cached.copy(firedFollowUps = cached.firedFollowUps + episodeId)
        persist()
    }

    @Synchronized
    fun markAnniversary(episodeId: String) {
        cached = cached.copy(firedAnniversaries = cached.firedAnniversaries + episodeId)
        persist()
    }

    companion object {
        private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun dayKey(ms: Long): String = DAY_FMT.format(Date(ms))
    }
}
