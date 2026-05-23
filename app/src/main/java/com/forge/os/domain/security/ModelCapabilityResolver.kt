package com.forge.os.domain.security

import com.forge.os.domain.config.ModelCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centrally resolves model capabilities (Vision, Audio, Tools) by combining 
 * hardcoded provider defaults, model-ID heuristics, and user-defined overrides.
 *
 * This replaces the fragile "rule-based" identification with a tiered resolution 
 * strategy that favors explicit configuration but degrades gracefully to 
 * intelligent guesswork.
 */
@Singleton
class ModelCapabilityResolver @Inject constructor() {

    /**
     * Resolves the full capability set for a given [ProviderSpec].
     */
    fun resolve(spec: ProviderSpec): ModelCapabilities {
        return when (spec) {
            is ProviderSpec.Builtin -> {
                val base = spec.provider.capabilities
                val modelId = spec.model ?: spec.provider.defaultModel
                
                // If the provider already says it supports everything, just trust it.
                if (base.hasVision && base.hasAudioInput && base.hasAudioOutput) return base
                
                // Otherwise, use heuristics based on the specific model ID.
                val heuristics = guessFromModelId(modelId)
                ModelCapabilities(
                    hasVision = base.hasVision || heuristics.hasVision,
                    hasAudioInput = base.hasAudioInput || heuristics.hasAudioInput,
                    hasAudioOutput = base.hasAudioOutput || heuristics.hasAudioOutput,
                    hasToolCalling = base.hasToolCalling && heuristics.hasToolCalling,
                    isLocal = base.isLocal
                )
            }
            is ProviderSpec.Custom -> {
                // High-fidelity: Prefer model-specific overrides if they exist for this model ID.
                val modelId = spec.model ?: spec.endpoint.defaultModel
                spec.endpoint.modelOverrides[modelId] ?: spec.endpoint.capabilities
            }
        }
    }

    /**
     * Heuristic-based capability detection for model IDs.
     * Updated frequently as new models are released.
     */
    fun guessFromModelId(modelId: String): ModelCapabilities {
        val mid = modelId.lowercase()
        
        val vision = mid.contains("vision") || 
                     mid.contains("gpt-4o") || 
                     mid.contains("gpt-4-turbo") ||
                     mid.contains("claude-3") || 
                     mid.contains("gemini") ||
                     mid.contains("pixtral") || 
                     mid.contains("llava") ||
                     mid.contains("bakllava") ||
                     mid.contains("moondream")
                     
        val audio = mid.contains("audio") || 
                     mid.contains("gpt-4o") || // gpt-4o natively supports audio
                     mid.contains("gemini")
                     
        // Some tiny/older models don't support tool calling even if the provider does.
        val toolCalling = !mid.contains("vision-only") && 
                          !mid.contains("base-model") &&
                          !mid.contains("gemini-nano") // Nano tool calling is handled via AICore specific logic
                          
        return ModelCapabilities(
            hasVision = vision,
            hasAudioInput = audio,
            hasAudioOutput = audio,
            hasToolCalling = toolCalling
        )
    }
}
