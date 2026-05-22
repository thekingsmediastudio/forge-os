package com.forge.os.domain.telemetry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LedgerEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String, // "API_CALL", "COMPUTE", "STORAGE"
    val resource: String, // e.g., "gpt-4o", "python_run"
    val amount: Double, // USD or units
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class LedgerStats(
    val dailyTotal: Double = 0.0,
    val monthlyTotal: Double = 0.0,
    val lastResetDate: Int = 0
)

@Singleton
class LedgerService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val ledgerFile: File get() = context.filesDir.resolve("workspace/system/ledger.json")
    private val statsFile: File get() = context.filesDir.resolve("workspace/system/ledger_stats.json")

    init {
        ledgerFile.parentFile?.mkdirs()
    }

    fun record(entry: LedgerEntry) {
        synchronized(this) {
            val entries = loadEntries().toMutableList()
            entries.add(entry)
            // Keep last 1000 entries for audit
            val trimmed = entries.takeLast(1000)
            saveEntries(trimmed)
            updateStats(entry)
            Timber.i("Ledger record: ${entry.type} - ${entry.resource} - $${entry.amount}")
        }
    }

    fun getDailySpend(): Double = loadStats().dailyTotal
    fun getMonthlySpend(): Double = loadStats().monthlyTotal

    fun getRecentEntries(limit: Int = 50): List<LedgerEntry> {
        return loadEntries().reversed().take(limit)
    }

    private fun updateStats(entry: LedgerEntry) {
        if (entry.type != "API_CALL" && entry.type != "COMPUTE") return
        
        val stats = loadStats()
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_YEAR)
        val month = now.get(Calendar.MONTH)

        var newStats = stats
        if (stats.lastResetDate != today) {
            newStats = newStats.copy(dailyTotal = 0.0, lastResetDate = today)
        }
        
        // Reset monthly if month has changed (simplified)
        // Correct implementation would track month specifically
        
        newStats = newStats.copy(
            dailyTotal = newStats.dailyTotal + entry.amount,
            monthlyTotal = newStats.monthlyTotal + entry.amount
        )
        saveStats(newStats)
    }

    private fun loadEntries(): List<LedgerEntry> = runCatching {
        if (!ledgerFile.exists()) return emptyList()
        json.decodeFromString<List<LedgerEntry>>(ledgerFile.readText())
    }.getOrDefault(emptyList())

    private fun saveEntries(entries: List<LedgerEntry>) {
        runCatching { ledgerFile.writeText(json.encodeToString(entries)) }
    }

    private fun loadStats(): LedgerStats = runCatching {
        if (!statsFile.exists()) return LedgerStats()
        json.decodeFromString<LedgerStats>(statsFile.readText())
    }.getOrDefault(LedgerStats())

    private fun saveStats(stats: LedgerStats) {
        runCatching { statsFile.writeText(json.encodeToString(stats)) }
    }
}
