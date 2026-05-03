package com.forge.os.domain.agent

import com.forge.os.data.api.CostMeter
import com.forge.os.domain.config.ConfigRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 — Cost-Aware Execution Planning.
 *
 * Before the ReAct loop begins, this planner estimates the worst-case USD cost
 * of the upcoming agent run based on:
 *   - System prompt size (tokens)
 *   - Conversation history size (tokens)
 *   - Max iterations × estimated tokens per iteration
 *   - Current model pricing from [CostMeter]
 *
 * If the estimate exceeds the user-configured threshold
 * ([BehaviorRules.costThresholdUsd]), the planner signals that the UI should
 * present an approval dialog before the loop starts.
 */
@Singleton
class ExecutionPlanner @Inject constructor(
    private val costMeter: CostMeter,
    private val configRepository: ConfigRepository,
) {
    data class CostEstimate(
        val estimatedInputTokens: Int,
        val estimatedOutputTokens: Int,
        val estimatedUsd: Double,
        val thresholdUsd: Double,
        val exceedsThreshold: Boolean,
        val model: String,
    )

    /**
     * Estimate the worst-case cost of a ReAct loop.
     *
     * @param systemPromptChars  character count of the system prompt
     * @param historyChars       character count of the existing conversation
     * @param userMessageChars   character count of the new user message
     * @param maxIterations      max ReAct loop iterations from config
     * @param model              model identifier for pricing lookup
     */
    fun estimate(
        systemPromptChars: Int,
        historyChars: Int,
        userMessageChars: Int,
        maxIterations: Int,
        model: String,
    ): CostEstimate {
        // Rough heuristic: 1 token ≈ 4 characters (English text).
        val charsPerToken = 4
        val baseInputTokens = (systemPromptChars + historyChars + userMessageChars) / charsPerToken

        // Each iteration: ~500 output tokens (tool call + reasoning) + the
        // growing context gets re-sent as input each turn. We estimate the
        // average context growth at ~300 tokens per iteration.
        val avgOutputPerIteration = 500
        val avgContextGrowthPerIteration = 300

        val totalOutputTokens = maxIterations * avgOutputPerIteration
        // Input tokens grow linearly: base + sum(i * growth) ≈ base*N + growth*N*(N-1)/2
        val totalInputTokens = (baseInputTokens * maxIterations) +
            (avgContextGrowthPerIteration * maxIterations * (maxIterations - 1) / 2)

        val price = costMeter.priceFor(model)
        val estimatedUsd = (totalInputTokens / 1_000_000.0) * price.inputPerM +
                           (totalOutputTokens / 1_000_000.0) * price.outputPerM

        val threshold = configRepository.get().behaviorRules.costThresholdUsd

        val exceeds = threshold > 0.0 && estimatedUsd > threshold

        Timber.d("ExecutionPlanner: model=$model est=${"%.4f".format(estimatedUsd)} USD " +
            "threshold=${"%.4f".format(threshold)} exceeds=$exceeds " +
            "(in=${totalInputTokens} out=${totalOutputTokens})")

        return CostEstimate(
            estimatedInputTokens = totalInputTokens,
            estimatedOutputTokens = totalOutputTokens,
            estimatedUsd = estimatedUsd,
            thresholdUsd = threshold,
            exceedsThreshold = exceeds,
            model = model,
        )
    }
}
