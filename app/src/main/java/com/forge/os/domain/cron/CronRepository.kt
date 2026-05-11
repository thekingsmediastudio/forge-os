package com.forge.os.domain.cron

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Persistence for cron jobs and execution history.
 *
 * - Active queue: workspace/cron/queue/jobs.json   (full snapshot — small, <50 jobs)
 * - History:      workspace/cron/history/YYYY-MM-DD.jsonl  (append-only, daily files)
 */
@Singleton
class CronRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val queueDir: File   get() = context.filesDir.resolve("workspace/cron/queue").apply { mkdirs() }
    private val historyDir: File get() = context.filesDir.resolve("workspace/cron/history").apply { mkdirs() }
    private val queueFile: File  get() = queueDir.resolve("jobs.json")

    private var cache: MutableList<CronJob> = mutableListOf()

    init { load() }

    private fun load() {
        cache = try {
            if (queueFile.exists()) {
                json.decodeFromString<List<CronJob>>(queueFile.readText()).toMutableList()
            } else mutableListOf()
        } catch (e: Exception) {
            Timber.e(e, "CronRepository: load failed")
            mutableListOf()
        }
    }

    @Synchronized
    fun all(): List<CronJob> = cache.toList()

    @Synchronized
    fun byId(id: String): CronJob? = cache.find { it.id == id }

    @Synchronized
    fun add(job: CronJob) {
        cache.removeIf { it.id == job.id }
        cache.add(job)
        persist()
    }

    @Synchronized
    fun update(job: CronJob) {
        val idx = cache.indexOfFirst { it.id == job.id }
        if (idx >= 0) {
            cache[idx] = job
            persist()
        }
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = cache.removeIf { it.id == id }
        if (removed) persist()
        return removed
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val idx = cache.indexOfFirst { it.id == id }
        if (idx < 0) return false
        cache[idx] = cache[idx].copy(enabled = enabled)
        persist()
        return true
    }

    /** Jobs whose nextRunAt is in the past or within the grace window. */
    fun dueJobs(now: Long = System.currentTimeMillis()): List<CronJob> =
        cache.filter { CronScheduler.isDue(it, now) }

    @Synchronized
    private fun persist() {
        try {
            queueFile.writeText(json.encodeToString(cache as List<CronJob>))
        } catch (e: Exception) {
            Timber.e(e, "CronRepository: persist failed")
        }
    }

    // ─── History ─────────────────────────────────────────────────────────────

    fun recordExecution(execution: CronExecution) {
        try {
            val today = dateFormat.format(Date())
            val file = historyDir.resolve("$today.jsonl")
            file.appendText(json.encodeToString(execution) + "\n")
        } catch (e: Exception) {
            Timber.e(e, "CronRepository: recordExecution failed")
        }
    }

    fun recentHistory(days: Int = 3, limit: Int = 50): List<CronExecution> {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return historyDir.listFiles()
            ?.filter { it.extension == "jsonl" }
            ?.sortedDescending()
            ?.take(days)
            ?.flatMap { f ->
                f.readLines()
                    .mapNotNull { runCatching { json.decodeFromString<CronExecution>(it) }.getOrNull() }
                    .filter { it.startedAt >= cutoff }
            }
            ?.sortedByDescending { it.startedAt }
            ?.take(limit)
            ?: emptyList()
    }

    fun historyForJob(jobId: String, limit: Int = 20): List<CronExecution> =
        recentHistory(days = 14, limit = 200)
            .filter { it.jobId == jobId }
            .take(limit)

    /**
     * Wipes every per-day history file. Returns the number of files deleted.
     * Active scheduled jobs are NOT touched.
     */
    @Synchronized
    fun clearAllHistory(): Int {
        val files = historyDir.listFiles()?.filter { it.extension == "jsonl" } ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        Timber.i("CronRepository: cleared $n history file(s)")
        return n
    }

    /**
     * Dumps the recent history (last 30 days, up to 1000 records) as a single
     * pretty-printed JSON array under workspace/cron/exports/. Returns the
     * relative workspace path of the new file, or null on failure.
     */
    fun exportHistoryAsJson(): String? = try {
        val list = recentHistory(days = 30, limit = 1000)
        val exportsDir = context.filesDir.resolve("workspace/cron/exports").apply { mkdirs() }
        val name = "cron-history-${System.currentTimeMillis()}.json"
        val file = exportsDir.resolve(name)
        file.writeText(json.encodeToString(list))
        Timber.i("CronRepository: exported ${list.size} record(s) → ${file.absolutePath}")
        "workspace/cron/exports/$name"
    } catch (e: Exception) {
        Timber.e(e, "CronRepository: exportHistoryAsJson failed")
        null
    }
}
