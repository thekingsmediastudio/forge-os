package com.forge.os.presentation.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.config.BackgroundUsesFallback
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.config.ProviderModelPair
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutingChainLink(
    val provider: String,
    val model: String,
    val hasKey: Boolean,
)

data class EmbeddingSpecUi(
    val provider: String,
    val model: String,
    val display: String,
)

data class ModelRoutingUiState(
    val chain: List<RoutingChainLink> = emptyList(),
    val cronUsesFallback: Boolean = true,
    val alarmsUseFallback: Boolean = true,
    val subAgentsUseFallback: Boolean = true,
    val proactiveUsesFallback: Boolean = true,
    val embeddingProvider: String = "OPENAI",
    val embeddingModel: String = "text-embedding-3-small",
    val availableEmbeddingSpecs: List<EmbeddingSpecUi> = emptyList(),
    val isRefreshing: Boolean = false,
    
    val summarizationProvider: String? = null,
    val summarizationModel: String? = null,
    val systemProvider: String? = null,
    val systemModel: String? = null,
    val companionProvider: String? = null,
    val companionModel: String? = null,
    val plannerProvider: String? = null,
    val plannerModel: String? = null,
    
    val visionProvider: String? = null,
    val visionModel: String? = null,
    val reasoningProvider: String? = null,
    val reasoningModel: String? = null,
    val reflectionProvider: String? = null,
    val reflectionModel: String? = null,
    
    val ecoModeEnabled: Boolean = false,
    val dailyLimitUsd: Double = 1.0,
    val ecoProvider: String = "GROQ",
    val ecoModel: String = "llama-3.3-70b-versatile",
)

@HiltViewModel
class ModelRoutingViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val keyStore: SecureKeyStore,
    private val apiManager: com.forge.os.data.api.AiApiManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelRoutingUiState())
    val state: StateFlow<ModelRoutingUiState> = _state.asStateFlow()

    init { refresh() }

    private fun refresh() {
        val cfg = configRepository.get().modelRouting
        val bg = cfg.backgroundUsesFallback
        _state.value = ModelRoutingUiState(
            chain = cfg.fallbackChain.map { link ->
                val provider = runCatching { ApiKeyProvider.valueOf(link.provider) }.getOrNull()
                RoutingChainLink(
                    provider = link.provider,
                    model = link.model,
                    hasKey = provider != null && keyStore.hasKey(provider),
                )
            },
            cronUsesFallback = bg.cron,
            alarmsUseFallback = bg.alarms,
            subAgentsUseFallback = bg.subAgents,
            proactiveUsesFallback = bg.proactive,
            embeddingProvider = configRepository.get().memorySettings.embeddingProvider,
            embeddingModel = configRepository.get().memorySettings.embeddingModel,
            summarizationProvider = cfg.summarizationProvider,
            summarizationModel = cfg.summarizationModel,
            systemProvider = cfg.systemProvider,
            systemModel = cfg.systemModel,
            companionProvider = cfg.companion.provider,
            companionModel = cfg.companion.model,
            plannerProvider = cfg.plannerProvider,
            plannerModel = cfg.plannerModel,
            visionProvider = cfg.visionProvider,
            visionModel = cfg.visionModel,
            reasoningProvider = cfg.reasoningProvider,
            reasoningModel = cfg.reasoningModel,
            reflectionProvider = cfg.reflectionProvider,
            reflectionModel = cfg.reflectionModel,
            ecoModeEnabled = configRepository.get().costBudget.enabled,
            dailyLimitUsd = configRepository.get().costBudget.dailyLimitUsd,
            ecoProvider = configRepository.get().costBudget.ecoProvider,
            ecoModel = configRepository.get().costBudget.ecoModel,
        )
    }

    fun probeModels() = viewModelScope.launch {
        _state.value = _state.value.copy(isRefreshing = true)
        try {
            val expanded = apiManager.availableSpecsExpanded()
            val embeddingSpecs = expanded.filter { spec ->
                apiManager.isEmbeddingModel(spec.effectiveModel)
            }.map { spec ->
                EmbeddingSpecUi(
                    provider = when(spec) {
                        is com.forge.os.domain.security.ProviderSpec.Builtin -> spec.provider.name
                        is com.forge.os.domain.security.ProviderSpec.Custom -> spec.endpoint.id
                    },
                    model = spec.effectiveModel,
                    display = spec.displayLabel
                )
            }
            _state.value = _state.value.copy(
                availableEmbeddingSpecs = embeddingSpecs,
                isRefreshing = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isRefreshing = false)
        }
    }

    fun add(provider: String, model: String) = viewModelScope.launch {
        configRepository.update { c ->
            c.copy(
                modelRouting = c.modelRouting.copy(
                    fallbackChain = c.modelRouting.fallbackChain + ProviderModelPair(provider, model)
                )
            )
        }
        refresh()
    }

    fun remove(index: Int) = viewModelScope.launch {
        configRepository.update { c ->
            val chain = c.modelRouting.fallbackChain.toMutableList()
            if (index in chain.indices) chain.removeAt(index)
            c.copy(modelRouting = c.modelRouting.copy(fallbackChain = chain))
        }
        refresh()
    }

    fun move(index: Int, delta: Int) = viewModelScope.launch {
        configRepository.update { c ->
            val chain = c.modelRouting.fallbackChain.toMutableList()
            val target = index + delta
            if (index in chain.indices && target in chain.indices) {
                val tmp = chain[index]; chain[index] = chain[target]; chain[target] = tmp
            }
            c.copy(modelRouting = c.modelRouting.copy(fallbackChain = chain))
        }
        refresh()
    }

    fun setBackgroundUsage(caller: BackgroundCaller, enabled: Boolean) = viewModelScope.launch {
        configRepository.update { c ->
            val cur = c.modelRouting.backgroundUsesFallback
            val next = when (caller) {
                BackgroundCaller.CRON       -> cur.copy(cron = enabled)
                BackgroundCaller.ALARMS     -> cur.copy(alarms = enabled)
                BackgroundCaller.SUB_AGENTS -> cur.copy(subAgents = enabled)
                BackgroundCaller.PROACTIVE  -> cur.copy(proactive = enabled)
            }
            c.copy(modelRouting = c.modelRouting.copy(backgroundUsesFallback = next))
        }
        refresh()
    }

    fun setEmbeddingModel(provider: String, model: String) = viewModelScope.launch {
        configRepository.update { c ->
            c.copy(memorySettings = c.memorySettings.copy(
                embeddingProvider = provider.trim().uppercase(),
                embeddingModel = model.trim()
            ))
        }
        refresh()
    }

    fun setSystemRouting(mode: com.forge.os.domain.companion.Mode, provider: String?, model: String?) = viewModelScope.launch {
        configRepository.update { c ->
            val cur = c.modelRouting
            val next = when (mode) {
                com.forge.os.domain.companion.Mode.SUMMARIZATION -> cur.copy(summarizationProvider = provider, summarizationModel = model)
                com.forge.os.domain.companion.Mode.SYSTEM -> cur.copy(systemProvider = provider, systemModel = model)
                com.forge.os.domain.companion.Mode.PLANNER -> cur.copy(plannerProvider = provider, plannerModel = model)
                com.forge.os.domain.companion.Mode.COMPANION -> cur.copy(companion = cur.companion.copy(provider = provider, model = model))
                com.forge.os.domain.companion.Mode.VISION -> cur.copy(visionProvider = provider, visionModel = model)
                com.forge.os.domain.companion.Mode.REASONING -> cur.copy(reasoningProvider = provider, reasoningModel = model)
                com.forge.os.domain.companion.Mode.REFLECTION -> cur.copy(reflectionProvider = provider, reflectionModel = model)
                else -> cur
            }
            c.copy(modelRouting = next)
        }
        refresh()
    }

    suspend fun availableModels() = apiManager.availableModels()

    fun setEcoMode(enabled: Boolean? = null, limit: Double? = null, provider: String? = null, model: String? = null) = viewModelScope.launch {
        configRepository.update { c ->
            val cur = c.costBudget
            c.copy(costBudget = cur.copy(
                enabled = enabled ?: cur.enabled,
                dailyLimitUsd = limit ?: cur.dailyLimitUsd,
                ecoProvider = provider ?: cur.ecoProvider,
                ecoModel = model ?: cur.ecoModel
            ))
        }
        refresh()
    }
}
