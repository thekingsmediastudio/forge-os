package com.forge.os.presentation.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.agent.AgentPersonality
import com.forge.os.domain.agent.PersonalityConfig
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.control.AgentControlPlane
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.ProviderSchema
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.domain.user.UserPreferencesManager
import com.forge.os.domain.user.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import javax.inject.Inject

/** Autonomy presets — map to BehaviorRules + AgentControlPlane capability gates. */
enum class AutonomyLevel(val label: String, val emoji: String) {
    SUPERVISED("Supervised", "🔒"),
    BALANCED("Balanced", "⚖️"),
    FULL_TRUST("Full trust", "🛠️")
}

data class OnboardingState(
    // Page 3 — About You
    val userName: String = "",
    val pronouns: String = "",
    val occupation: String = "",
    val language: String = "en",
    val timezone: String = TimeZone.getDefault().id,
    val dailyRhythm: String = "",
    // Page 4 — Your Style
    val interests: Set<String> = emptySet(),
    val techLevel: String = "",
    val replyLength: String = "",
    val alwaysRemember: String = "",
    // Page 5 — Agent
    val agentName: String = "Forge",
    val agentRole: String = "",
    val traits: Set<String> = emptySet(),
    val chattiness: Float = 0.5f,
    val autonomy: AutonomyLevel = AutonomyLevel.BALANCED,
    // Page 6 — LLM Setup
    val provider: ApiKeyProvider = ApiKeyProvider.OPENAI,
    val apiKey: String = "",
    val keyValidating: Boolean = false,
    val keyValid: Boolean = false,
    val keyError: String? = null,
    val keySkipped: Boolean = false,
    // Global
    val busy: Boolean = false,
    val error: String? = null
) {
    val canFinish: Boolean
        get() = keyValid || keySkipped || provider == ApiKeyProvider.OLLAMA ||
                apiKey.trim().length >= 8
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore,
    private val agentPersonality: AgentPersonality,
    private val userPreferences: UserPreferencesManager,
    private val configRepository: ConfigRepository,
    private val controlPlane: AgentControlPlane
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // ── Field updaters ────────────────────────────────────────────────

    fun update(block: (OnboardingState) -> OnboardingState) {
        _state.value = block(_state.value)
    }

    fun toggleInterest(tag: String) = update {
        it.copy(interests = if (tag in it.interests) it.interests - tag else it.interests + tag)
    }

    fun toggleTrait(trait: String) = update {
        it.copy(traits = if (trait in it.traits) it.traits - trait else it.traits + trait)
    }

    fun selectProvider(p: ApiKeyProvider) = update {
        it.copy(provider = p, apiKey = "", keyValid = false, keyError = null, keySkipped = false)
    }

    fun updateKey(k: String) = update {
        it.copy(apiKey = k, keyValid = false, keyError = null)
    }

    fun skipKey() = update { it.copy(keySkipped = true, keyError = null) }

    // ── Key validation ────────────────────────────────────────────────

    fun validateKey() {
        val s = _state.value
        val key = s.apiKey.trim()
        if (s.provider == ApiKeyProvider.OLLAMA) {
            _state.value = s.copy(keyValid = true, keyError = null)
            return
        }
        if (key.length < 8) {
            _state.value = s.copy(keyError = "Key looks too short", keyValid = false)
            return
        }
        _state.value = s.copy(keyValidating = true, keyError = null, keyValid = false)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { testKey(s.provider, key) }
            _state.value = _state.value.copy(
                keyValidating = false,
                keyValid = result == null,
                keyError = result
            )
        }
    }

    /** Lightweight key check — GET /models (OpenAI schema) or /v1/models (Anthropic). Returns null on success, error message otherwise. */
    private fun testKey(provider: ApiKeyProvider, key: String): String? {
        return runCatching {
            val url = when (provider.schema) {
                ProviderSchema.ANTHROPIC -> URL(provider.baseUrl + "v1/models")
                else -> URL(provider.baseUrl + "models")
            }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                when (provider.schema) {
                    ProviderSchema.ANTHROPIC -> {
                        setRequestProperty("x-api-key", key)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    else -> setRequestProperty("Authorization", "Bearer $key")
                }
            }
            when (conn.responseCode) {
                in 200..299 -> null
                401, 403 -> "Invalid key — check and try again"
                429 -> null // rate-limited but key is real
                else -> "Unexpected response (${conn.responseCode})"
            }
        }.getOrElse { "Could not reach ${provider.displayName}: ${it.message}" }
    }

    // ── Finish — persist everything ───────────────────────────────────

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canFinish) {
            _state.value = s.copy(error = "Add an API key or tap Skip")
            return
        }
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 1. API key
                    if (!s.keySkipped && s.apiKey.trim().length >= 8) {
                        secureKeyStore.saveKey(s.provider, s.apiKey.trim())
                    }

                    // 2. User profile
                    userPreferences.updateProfile(
                        UserProfile(
                            name = s.userName.trim(),
                            pronouns = s.pronouns.trim(),
                            occupation = s.occupation.trim(),
                            language = s.language,
                            timezone = s.timezone,
                            dailyRhythm = s.dailyRhythm,
                            interests = s.interests.toList(),
                            techLevel = s.techLevel,
                            replyLength = s.replyLength,
                            alwaysRemember = s.alwaysRemember.trim()
                        )
                    )

                    // 3. Agent personality
                    val style = buildString {
                        if (s.replyLength.isNotBlank()) append("Reply length: $s.replyLength. ")
                        if (s.techLevel.isNotBlank()) append("User technical level: $s.techLevel. ")
                        if (s.agentRole.isNotBlank()) append("Role: $s.agentRole. ")
                        append("Chattiness: ${(s.chattiness * 100).toInt()}%.")
                    }
                    agentPersonality.updatePersonality(
                        PersonalityConfig(
                            name = s.agentName.trim().ifBlank { "Forge" },
                            traits = s.traits.toList(),
                            communicationStyle = style.trim(),
                            customInstructions = s.alwaysRemember.trim()
                        )
                    )

                    // 4. Autonomy preset → BehaviorRules + capability gates
                    applyAutonomy(s.autonomy)

                    // 5. Agent identity (name, language, timezone)
                    configRepository.update { cfg ->
                        cfg.copy(
                            agentIdentity = cfg.agentIdentity.copy(
                                name = s.agentName.trim().ifBlank { "Forge" },
                                language = s.language,
                                timezone = s.timezone
                            )
                        )
                    }

                    setHasOnboarded(true)
                }
                _state.value = _state.value.copy(busy = false)
                onDone()
            } catch (e: Exception) {
                Timber.e(e, "Onboarding finish failed")
                _state.value = _state.value.copy(busy = false, error = "Setup failed: ${e.message}")
            }
        }
    }

    private fun applyAutonomy(level: AutonomyLevel) {
        val fullConfirmList = listOf(
            "file_delete", "shell_exec", "git_push", "git_pull", "git_clone", "git_checkout",
            "file_download", "browser_download", "config_write", "config_rollback",
            "antitheft_enable", "antitheft_disable", "antitheft_lock", "antitheft_trigger",
            "antitheft_alert", "antitheft_add_contact", "antitheft_remove_contact", "antitheft_clear",
            "sms_send", "call_phone", "location_current", "contacts_list", "contacts_get"
        )
        configRepository.update { cfg ->
            val rules = when (level) {
                AutonomyLevel.SUPERVISED -> cfg.behaviorRules.copy(
                    autoConfirmToolCalls = false,
                    confirmDestructive = fullConfirmList
                )
                AutonomyLevel.BALANCED -> cfg.behaviorRules.copy(
                    autoConfirmToolCalls = false,
                    confirmDestructive = fullConfirmList
                )
                AutonomyLevel.FULL_TRUST -> cfg.behaviorRules.copy(
                    autoConfirmToolCalls = true,
                    confirmDestructive = fullConfirmList.filter { it.startsWith("antitheft") }
                )
            }
            cfg.copy(behaviorRules = rules)
        }
        // Capability gates
        fun gate(id: String, on: Boolean) = controlPlane.setByUser(id, on, source = "onboarding")
        when (level) {
            AutonomyLevel.SUPERVISED -> {
                gate(AgentControlPlane.PROACTIVE_AUTOACT, false)
                gate(AgentControlPlane.PROACTIVE_SUGGEST, false)
                gate(AgentControlPlane.PLUGIN_NETWORK, false)
                gate(AgentControlPlane.PROJECT_SERVE_LAN, false)
                gate(AgentControlPlane.BROWSER_AGENT_NAV, false)
                gate(AgentControlPlane.HW_LAUNCH_APPS, false)
            }
            AutonomyLevel.BALANCED -> {
                gate(AgentControlPlane.PROACTIVE_AUTOACT, false)
                gate(AgentControlPlane.PLUGIN_NETWORK, false)
                gate(AgentControlPlane.PROJECT_SERVE_LAN, false)
            }
            AutonomyLevel.FULL_TRUST -> {
                gate(AgentControlPlane.PROACTIVE_AUTOACT, false) // still off — dangerous
                gate(AgentControlPlane.PLUGIN_NETWORK, true)
                gate(AgentControlPlane.PROJECT_SERVE_LAN, true)
                gate(AgentControlPlane.BROWSER_AGENT_NAV, true)
                gate(AgentControlPlane.HW_LAUNCH_APPS, true)
            }
        }
    }

    private fun setHasOnboarded(value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, value).apply()
    }

    companion object {
        const val PREFS = "forge_onboarding"
        const val KEY_DONE = "has_onboarded"

        fun isOnboarded(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)
    }
}
