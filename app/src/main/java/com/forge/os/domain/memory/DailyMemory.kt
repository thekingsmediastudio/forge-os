package com.forge.os.domain.memory

import android.content.Context
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
 * Tier 1: Daily append-only JSONL event log.
 * One file per day under workspace/memory/daily/YYYY-MM-DD.jsonl.
 * Rolling 7-day retention; older files are consumed by MemoryCompressionWorker.
 */
@Singleton
class DailyMemory @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dailyDir: File get() = context.filesDir.resolve("workspace/memory/daily")

    private fun todayFile(): File {
        val today = dateFormat.format(Date())
        return dailyDir.resolve("$today.jsonl").also { dailyDir.mkdirs() }
    }

    fun append(event: DailyEvent) {
        try {
            todayFile().appendText(json.encodeToString(event) + "\n")
        } catch (e: Exception) {
            Timber.e(e, "DailyMemory: append failed")
        }
    }

    /** Read events from the last [days] days, newest-first. */
    fun readRecent(days: Int = 3): List<DailyEvent> {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return dailyDir.listFiles()
            ?.filter { it.extension == "jsonl" }
            ?.sortedDescending()
            ?.take(days)
            ?.flatMap { file ->
                file.readLines()
                    .mapNotNull { runCatching { json.decodeFromString<DailyEvent>(it) }.getOrNull() }
                    .filter { it.timestamp >= cutoff }
            }
            ?.reversed()
            ?: emptyList()
    }

    /** Returns all daily files older than [olderThanDays] days — for compression. */
    fun oldFiles(olderThanDays: Int = 7): List<File> {
        val cutoff = System.currentTimeMillis() - olderThanDays * 86_400_000L
        return dailyDir.listFiles()
            ?.filter { it.extension == "jsonl" && it.lastModified() < cutoff }
            ?: emptyList()
    }

    /** Summary stats for today's file. */
    fun todaySummary(): String {
        val events = todayFile().takeIf { it.exists() }
            ?.readLines()
            ?.mapNotNull { runCatching { json.decodeFromString<DailyEvent>(it) }.getOrNull() }
            ?: emptyList()
        val userCount = events.count { it.role == "user" }
        val assistantCount = events.count { it.role == "assistant" }
        val toolCount = events.count { it.role == "tool_call" }
        return "Today: $userCount user msgs, $assistantCount assistant msgs, $toolCount tool calls"
    }
}
