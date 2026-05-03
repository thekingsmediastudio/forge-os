package com.forge.os.data.api

import android.content.Context
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

/**
 * Per-call USD cost estimation + lifetime + per-conversation totals.
 * Pricing is approximate, encoded as USD per 1M tokens (input, output).
 * The table is seeded with widely-used models; unknown models fall back to a
 * conservative default. Users can tune values at runtime via [setPrice].
 */
@Singleton
class CostMeter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Serializable
    data class PricePoint(val inputPerM: Double, val outputPerM: Double)

    @Serializable
    data class ModelStats(
        val calls: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val usd: Double = 0.0,
    )

    @Serializable
    data class CostSnapshot(
        val lifetimeUsd: Double = 0.0,
        val callCount: Long = 0,
        val lastCallUsd: Double = 0.0,
        val lastInputTokens: Int = 0,
        val lastOutputTokens: Int = 0,
        val sessionUsd: Double = 0.0,
        val sessionCalls: Long = 0,
        val perModel: Map<String, ModelStats> = emptyMap(),
        /** Phase M-3 — lifetime spend tagged by conversational mode. */
        val agentUsd: Double = 0.0,
        val companionUsd: Double = 0.0,
        val agentCalls: Long = 0,
        val companionCalls: Long = 0,
        /** Phase 3 — Eco-Mode: daily spend tracking. */
        val dailyUsd: Double = 0.0,
        val lastResetDay: String = "", // yyyy-MM-dd
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val totalsFile = File(context.filesDir, "workspace/system/cost_totals.json")
    private val pricesFile = File(context.filesDir, "workspace/system/prices.json")

    private val defaultPrices = mutableMapOf(
        // OpenAI
        "gpt-4o" to PricePoint(2.50, 10.00),
        "gpt-4o-mini" to PricePoint(0.15, 0.60),
        "gpt-4-turbo" to PricePoint(10.00, 30.00),
        "o1" to PricePoint(15.00, 60.00),
        "o1-mini" to PricePoint(3.00, 12.00),
        // Anthropic
        "claude-3-5-sonnet-20241022" to PricePoint(3.00, 15.00),
        "claude-3-5-sonnet" to PricePoint(3.00, 15.00),
        "claude-3-5-haiku-20241022" to PricePoint(0.80, 4.00),
        "claude-3-opus-20240229" to PricePoint(15.00, 75.00),
        // Groq (very cheap)
        "llama-3.3-70b-versatile" to PricePoint(0.59, 0.79),
        "llama-3.1-8b-instant" to PricePoint(0.05, 0.08),
        // Gemini
        "gemini-2.0-flash" to PricePoint(0.10, 0.40),
        "gemini-1.5-pro" to PricePoint(1.25, 5.00),
        // DeepSeek
        "deepseek-chat" to PricePoint(0.27, 1.10),
        // xAI
        "grok-2" to PricePoint(2.00, 10.00),
        // Mistral
        "mistral-large-latest" to PricePoint(2.00, 6.00)
    )
    private val unknownPrice = PricePoint(1.00, 3.00)

    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<CostSnapshot> = _snapshot

    private val _prices = MutableStateFlow<Map<String, PricePoint>>(loadPrices())
    val prices: StateFlow<Map<String, PricePoint>> = _prices

    private var sessionUsd: Double = 0.0
    private var sessionCalls: Long = 0

    /**
     * Return USD cost for one call. Always records into lifetime totals.
     *
     * Phase M-3 — `mode` tags the call so the cost screen can split spend
     * between AGENT and COMPANION. Default is AGENT to keep older callers
     * binary-compatible.
     */
    @Synchronized
    fun record(
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        mode: com.forge.os.domain.companion.Mode = com.forge.os.domain.companion.Mode.AGENT,
    ): Double {
        val today = today()
        val cur = _snapshot.value
        
        // Reset daily if date changed
        val snapshotWithReset = if (cur.lastResetDay != today) {
            cur.copy(dailyUsd = 0.0, lastResetDay = today)
        } else cur

        val pp = priceFor(model)
        val cost = (inputTokens / 1_000_000.0) * pp.inputPerM +
                   (outputTokens / 1_000_000.0) * pp.outputPerM
        sessionUsd += cost
        sessionCalls++
        
        val prev = snapshotWithReset.perModel[model] ?: ModelStats()
        val updatedPerModel = snapshotWithReset.perModel + (model to ModelStats(
            calls = prev.calls + 1,
            inputTokens = prev.inputTokens + inputTokens,
            outputTokens = prev.outputTokens + outputTokens,
            usd = prev.usd + cost,
        ))
        val isCompanion = mode == com.forge.os.domain.companion.Mode.COMPANION
        val next = snapshotWithReset.copy(
            lifetimeUsd = snapshotWithReset.lifetimeUsd + cost,
            dailyUsd = snapshotWithReset.dailyUsd + cost,
            callCount = snapshotWithReset.callCount + 1,
            lastCallUsd = cost,
            lastInputTokens = inputTokens,
            lastOutputTokens = outputTokens,
            sessionUsd = sessionUsd,
            sessionCalls = sessionCalls,
            perModel = updatedPerModel,
            agentUsd = snapshotWithReset.agentUsd + if (!isCompanion) cost else 0.0,
            companionUsd = snapshotWithReset.companionUsd + if (isCompanion) cost else 0.0,
            agentCalls = snapshotWithReset.agentCalls + if (!isCompanion) 1 else 0,
            companionCalls = snapshotWithReset.companionCalls + if (isCompanion) 1 else 0,
        )
        _snapshot.value = next
        persistSnapshot(next)
        return cost
    }

    fun getDailyTotal(): Double {
        val today = today()
        val cur = _snapshot.value
        return if (cur.lastResetDay == today) cur.dailyUsd else 0.0
    }

    private fun today(): String = 
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())


    fun resetSession() {
        sessionUsd = 0.0; sessionCalls = 0
        _snapshot.value = _snapshot.value.copy(sessionUsd = 0.0, sessionCalls = 0)
    }

    fun setPrice(model: String, input: Double, output: Double) {
        val updated = _prices.value.toMutableMap()
        updated[model] = PricePoint(input, output)
        _prices.value = updated
        persistPrices(updated)
    }

    fun removePrice(model: String) {
        val updated = _prices.value.toMutableMap()
        updated.remove(model)
        _prices.value = updated
        persistPrices(updated)
    }

    fun resetLifetime() {
        val empty = CostSnapshot()
        sessionUsd = 0.0; sessionCalls = 0
        _snapshot.value = empty
        persistSnapshot(empty)
    }

    fun priceFor(model: String): PricePoint {
        val map = _prices.value
        // Exact match first, then prefix match (e.g. "claude-3-5-sonnet-20240620").
        map[model]?.let { return it }
        map.entries.firstOrNull { (k, _) -> model.startsWith(k) }?.value?.let { return it }
        return unknownPrice
    }

    private fun loadSnapshot(): CostSnapshot = try {
        if (totalsFile.exists()) json.decodeFromString(totalsFile.readText())
        else CostSnapshot()
    } catch (e: Exception) {
        Timber.w(e, "CostMeter: snapshot load failed")
        CostSnapshot()
    }

    private fun loadPrices(): Map<String, PricePoint> = try {
        if (pricesFile.exists()) {
            val saved: Map<String, PricePoint> = json.decodeFromString(pricesFile.readText())
            defaultPrices.toMap() + saved
        } else defaultPrices.toMap()
    } catch (e: Exception) {
        Timber.w(e, "CostMeter: price load failed")
        defaultPrices.toMap()
    }

    private fun persistSnapshot(s: CostSnapshot) {
        try {
            totalsFile.parentFile?.mkdirs()
            totalsFile.writeText(json.encodeToString(s))
        } catch (e: Exception) { Timber.w(e, "CostMeter: persist snapshot failed") }
    }

    private fun persistPrices(p: Map<String, PricePoint>) {
        try {
            pricesFile.parentFile?.mkdirs()
            pricesFile.writeText(json.encodeToString(p))
        } catch (e: Exception) { Timber.w(e, "CostMeter: persist prices failed") }
    }
}
