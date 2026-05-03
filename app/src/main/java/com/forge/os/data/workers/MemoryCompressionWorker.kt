package com.forge.os.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forge.os.domain.memory.DailyEvent
import com.forge.os.domain.memory.DailyMemory
import com.forge.os.domain.memory.LongtermMemory
import com.forge.os.domain.memory.MemoryManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Daily memory compression cron job.
 *
 * Runs once per day via WorkManager.
 * 1. Reads daily JSONL files older than 7 days
 * 2. Extracts key facts and summarizes them into long-term memory
 * 3. Deletes the compressed daily files
 * 4. Prunes long-term facts with accessCount == 0 older than 30 days
 * 5. Rebuilds the semantic index
 */
@HiltWorker
class MemoryCompressionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dailyMemory: DailyMemory,
    private val longtermMemory: LongtermMemory,
    private val memoryManager: MemoryManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("MemoryCompressionWorker: starting compression run")
        return try {
            val oldFiles = dailyMemory.oldFiles(olderThanDays = 7)
            if (oldFiles.isEmpty()) {
                Timber.i("MemoryCompressionWorker: nothing to compress")
                return Result.success()
            }

            var compressedFiles = 0
            var extractedFacts = 0

            for (file in oldFiles) {
                val dateLabel = file.nameWithoutExtension
                val events: List<DailyEvent> = file.readLines()
                    .mapNotNull { line ->
                        runCatching {
                            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                                .decodeFromString<DailyEvent>(line)
                        }.getOrNull()
                    }

                if (events.isEmpty()) {
                    file.delete()
                    continue
                }

                // Build a compressed summary
                val userMessages = events.filter { it.role == "user" }.map { it.content }
                val assistantMessages = events.filter { it.role == "assistant" }.map { it.content }
                val toolCalls = events.filter { it.role == "tool_call" }.map { it.content }

                val summary = buildString {
                    appendLine("Session $dateLabel summary:")
                    appendLine("  ${userMessages.size} user messages, ${assistantMessages.size} agent responses, ${toolCalls.size} tool calls")
                    if (userMessages.isNotEmpty()) {
                        appendLine("  Topics discussed:")
                        userMessages.take(5).forEach { msg ->
                            appendLine("    - ${msg.take(100)}")
                        }
                    }
                }

                longtermMemory.store(
                    key = "session_summary_$dateLabel",
                    content = summary.trim(),
                    tags = listOf("session", "compressed", dateLabel),
                    source = "compression"
                )
                extractedFacts++

                // Extract any explicitly tagged facts
                events.filter { "fact" in it.tags || "remember" in it.tags }.forEach { event ->
                    val key = "fact_${dateLabel}_${event.timestamp}"
                    longtermMemory.store(
                        key = key,
                        content = event.content.take(500),
                        tags = event.tags + listOf("extracted"),
                        source = "compression"
                    )
                    extractedFacts++
                }

                file.delete()
                compressedFiles++
                Timber.i("MemoryCompressionWorker: compressed $dateLabel → $extractedFacts facts")
            }

            // Prune stale long-term facts (accessCount == 0, older than 30 days)
            val thirtyDaysAgo = System.currentTimeMillis() - 30L * 86_400_000L
            val staleFacts = longtermMemory.getAll().values.filter {
                it.accessCount == 0 &&
                it.timestamp < thirtyDaysAgo &&
                it.source != "compression"
            }
            staleFacts.forEach { longtermMemory.delete(it.key) }
            if (staleFacts.isNotEmpty()) {
                Timber.i("MemoryCompressionWorker: pruned ${staleFacts.size} stale facts")
            }

            longtermMemory.persist()
            memoryManager.rebuildIndex()

            Timber.i("MemoryCompressionWorker: done — $compressedFiles files compressed, $extractedFacts facts stored")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "MemoryCompressionWorker: failed")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "forge_memory_compression"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<MemoryCompressionWorker>(1, TimeUnit.DAYS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("MemoryCompressionWorker: scheduled daily compression")
        }

        fun scheduleNow(workManager: WorkManager) {
            val request = androidx.work.OneTimeWorkRequestBuilder<MemoryCompressionWorker>()
                .build()
            workManager.enqueue(request)
        }
    }
}
