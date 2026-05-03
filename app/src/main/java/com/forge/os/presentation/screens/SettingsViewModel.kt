package com.forge.os.presentation.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.CustomEndpoint
import com.forge.os.domain.security.CustomEndpointRepository
import com.forge.os.domain.security.KeyStatus
import com.forge.os.domain.security.NamedSecret
import com.forge.os.domain.security.NamedSecretRegistry
import com.forge.os.domain.security.ProviderSchema
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.presentation.theme.ThemeMode
import com.forge.os.data.system.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class CustomEndpointStatus(
    val endpoint: CustomEndpoint,
    val hasKey: Boolean,
    val maskedKey: String
)

/**
 * Row model for the Custom API Keys section. We never expose the raw value
 * to the UI — only a flag for whether one is stored.
 */
data class NamedSecretStatus(
    val secret: NamedSecret,
    val hasValue: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
    private val customEndpoints: CustomEndpointRepository,
    private val namedSecrets: NamedSecretRegistry,
    private val configRepository: ConfigRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = configRepository.themeMode

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { configRepository.setThemeMode(mode) }
            _saveMessage.value = "✅ Theme set to ${mode.displayName}"
        }
    }

    // ── Compact Mode ─────────────────────────────────────────────────────────

    private val _compactModeEnabled = MutableStateFlow(false)
    val compactModeEnabled: StateFlow<Boolean> = _compactModeEnabled

    private val _costThresholdUsd = MutableStateFlow(0.0)
    val costThresholdUsd: StateFlow<Double> = _costThresholdUsd

    private val _remotePythonWorkerUrl = MutableStateFlow("")
    val remotePythonWorkerUrl: StateFlow<String> = _remotePythonWorkerUrl

    private val _remotePythonWorkerAuthToken = MutableStateFlow("")
    val remotePythonWorkerAuthToken: StateFlow<String> = _remotePythonWorkerAuthToken

    private val _hapticFeedbackEnabled = MutableStateFlow(true)
    val hapticFeedbackEnabled: StateFlow<Boolean> = _hapticFeedbackEnabled

    private val _backupLoading = MutableStateFlow(false)
    val backupLoading: StateFlow<Boolean> = _backupLoading

    private val _prefetchEnabled = MutableStateFlow(true)
    val prefetchEnabled: StateFlow<Boolean> = _prefetchEnabled

    private val _prefetchAllowUnsafe = MutableStateFlow(false)
    val prefetchAllowUnsafe: StateFlow<Boolean> = _prefetchAllowUnsafe

    // ── Intelligence Upgrades ───────────────────────────────────────────────
    private val _reflectionEnabled = MutableStateFlow(true)
    val reflectionEnabled: StateFlow<Boolean> = _reflectionEnabled

    private val _memoryRagEnabled = MutableStateFlow(true)
    val memoryRagEnabled: StateFlow<Boolean> = _memoryRagEnabled

    private val _visionEnabled = MutableStateFlow(true)
    val visionEnabled: StateFlow<Boolean> = _visionEnabled

    private val _reasoningEnabled = MutableStateFlow(true)
    val reasoningEnabled: StateFlow<Boolean> = _reasoningEnabled

    // ── API Keys ──────────────────────────────────────────────────────────────
    // NOTE: these MutableStateFlow declarations MUST appear before the `init`
    // block below — `loadAll()` writes to them. Kotlin initialises properties
    // top-to-bottom, so any field declared after `init` is still null when the
    // init block runs and dereferencing it throws NullPointerException
    // (the original Settings-screen crash).

    private val _keyStatuses = MutableStateFlow<List<KeyStatus>>(emptyList())
    val keyStatuses: StateFlow<List<KeyStatus>> = _keyStatuses

    private val _customStatuses = MutableStateFlow<List<CustomEndpointStatus>>(emptyList())
    val customStatuses: StateFlow<List<CustomEndpointStatus>> = _customStatuses

    private val _namedSecretStatuses = MutableStateFlow<List<NamedSecretStatus>>(emptyList())
    val namedSecretStatuses: StateFlow<List<NamedSecretStatus>> = _namedSecretStatuses

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage

    init {
        viewModelScope.launch {
            val cfg = configRepository.get()
            _compactModeEnabled.value = cfg.modelRouting.compactMode.enabled
            _costThresholdUsd.value = cfg.behaviorRules.costThresholdUsd
            _remotePythonWorkerUrl.value = cfg.hybridExecution.remotePythonWorkerUrl
            _remotePythonWorkerAuthToken.value = cfg.hybridExecution.remotePythonWorkerAuthToken
            _hapticFeedbackEnabled.value = cfg.appearance.hapticFeedbackEnabled
            _prefetchEnabled.value = cfg.prefetchSettings.enabled
            _prefetchAllowUnsafe.value = cfg.prefetchSettings.allowUnsafeTools
            
            val intel = cfg.intelligenceUpgrades
            _reflectionEnabled.value = intel.reflectionEnabled
            _memoryRagEnabled.value = intel.memoryRagEnabled
            _visionEnabled.value = intel.visionEnabled
            _reasoningEnabled.value = intel.reasoningEnabled
        }
        loadAll()
    }

    fun setCompactModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = configRepository.get()
                val updated = cfg.copy(
                    modelRouting = cfg.modelRouting.copy(
                        compactMode = cfg.modelRouting.compactMode.copy(enabled = enabled)
                    )
                )
                configRepository.save(updated)
            }
            _compactModeEnabled.value = enabled
            _saveMessage.value = if (enabled)
                "✅ Compact Mode on — replies will be shorter and cheaper"
            else
                "✅ Compact Mode off — full replies restored"
        }
    }

    fun setCostThresholdUsd(threshold: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = configRepository.get()
                val updated = cfg.copy(
                    behaviorRules = cfg.behaviorRules.copy(costThresholdUsd = threshold)
                )
                configRepository.save(updated)
            }
            _costThresholdUsd.value = threshold
            _saveMessage.value = "✅ Cost threshold set to \$${"%.4f".format(threshold)}"
        }
    }

    fun setHybridExecution(url: String, token: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = configRepository.get()
                val updated = cfg.copy(
                    hybridExecution = cfg.hybridExecution.copy(
                        remotePythonWorkerUrl = url.trim(),
                        remotePythonWorkerAuthToken = token.trim()
                    )
                )
                configRepository.save(updated)
            }
            _remotePythonWorkerUrl.value = url.trim()
            _remotePythonWorkerAuthToken.value = token.trim()
            _saveMessage.value = if (url.isBlank()) "✅ Remote worker disabled" else "✅ Remote worker configured"
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = configRepository.get()
                val updated = cfg.copy(
                    appearance = cfg.appearance.copy(hapticFeedbackEnabled = enabled)
                )
                configRepository.save(updated)
            }
            _hapticFeedbackEnabled.value = enabled
            _saveMessage.value = if (enabled) "✅ Haptic feedback enabled" else "✅ Haptic feedback disabled"
        }
    }

    fun setPrefetchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = configRepository.get()
                val updated = cfg.copy(prefetchSettings = cfg.prefetchSettings.copy(enabled = enabled))
                configRepository.save(updated)
            }
            _prefetchEnabled.value = enabled
            _saveMessage.value = if (enabled) "✅ Predictive prefetch enabled" else "✅ Predictive prefetch disabled"
        }
    }

    fun setPrefetchAllowUnsafe(allowed: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cfg = configRepository.get()
                val updated = cfg.copy(prefetchSettings = cfg.prefetchSettings.copy(allowUnsafeTools = allowed))
                configRepository.save(updated)
            }
            _prefetchAllowUnsafe.value = allowed
            _saveMessage.value = if (allowed) "✅ Prefetch allowed to use unsafe tools" else "✅ Prefetch restricted to safe tools"
        }
    }

    fun setReflectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configRepository.update { it.copy(intelligenceUpgrades = it.intelligenceUpgrades.copy(reflectionEnabled = enabled)) }
            }
            _reflectionEnabled.value = enabled
            _saveMessage.value = if (enabled) "✅ Autonomous learning enabled" else "✅ Autonomous learning disabled"
        }
    }

    fun setMemoryRagEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configRepository.update { it.copy(intelligenceUpgrades = it.intelligenceUpgrades.copy(memoryRagEnabled = enabled)) }
            }
            _memoryRagEnabled.value = enabled
            _saveMessage.value = if (enabled) "✅ Long-term memory RAG enabled" else "✅ Long-term memory RAG disabled"
        }
    }

    fun setVisionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configRepository.update { it.copy(intelligenceUpgrades = it.intelligenceUpgrades.copy(visionEnabled = enabled)) }
            }
            _visionEnabled.value = enabled
            _saveMessage.value = if (enabled) "✅ Vision processing enabled" else "✅ Vision processing disabled"
        }
    }

    fun setReasoningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configRepository.update { it.copy(intelligenceUpgrades = it.intelligenceUpgrades.copy(reasoningEnabled = enabled)) }
            }
            _reasoningEnabled.value = enabled
            _saveMessage.value = if (enabled) "✅ Advanced reasoning enabled" else "✅ Advanced reasoning disabled"
        }
    }

    fun saveKey(provider: ApiKeyProvider, key: String) {
        viewModelScope.launch {
            secureKeyStore.saveKey(provider, key); loadAll()
            _saveMessage.value = if (key.isBlank()) "🗑 Key removed for ${provider.displayName}"
                                  else "✅ Key saved for ${provider.displayName}"
        }
    }

    fun deleteKey(provider: ApiKeyProvider) {
        viewModelScope.launch {
            secureKeyStore.deleteKey(provider); loadAll()
            _saveMessage.value = "🗑 Key removed for ${provider.displayName}"
        }
    }

    fun addCustomEndpoint(
        name: String, baseUrl: String, schema: ProviderSchema,
        defaultModel: String, apiKey: String
    ) {
        viewModelScope.launch {
            val ep = customEndpoints.add(
                CustomEndpoint(
                    name = name.trim(), baseUrl = baseUrl.trim(),
                    schema = schema, defaultModel = defaultModel.trim()
                )
            )
            if (apiKey.isNotBlank()) secureKeyStore.saveCustomKey(ep.id, apiKey)
            loadAll()
            _saveMessage.value = "✅ Endpoint ${ep.name} added"
        }
    }

    fun deleteCustomEndpoint(id: String) {
        viewModelScope.launch {
            secureKeyStore.deleteCustomKey(id); customEndpoints.delete(id); loadAll()
            _saveMessage.value = "🗑 Custom endpoint removed"
        }
    }

    fun setCustomKey(id: String, key: String) {
        viewModelScope.launch {
            secureKeyStore.saveCustomKey(id, key); loadAll()
            _saveMessage.value = if (key.isBlank()) "🗑 Custom key cleared" else "✅ Custom key saved"
        }
    }

    // ── Named API keys (extension mechanism) ─────────────────────────────────

    /**
     * Save (or update) a named secret + its raw value. The raw value goes
     * straight into [SecureKeyStore] under a synthetic key — the UI never
     * holds it after this call returns.
     */
    fun saveNamedSecret(
        name: String,
        description: String,
        authStyle: String,
        headerName: String,
        queryParam: String,
        value: String,
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                namedSecrets.save(
                    NamedSecret(
                        name = name, description = description,
                        authStyle = authStyle,
                        headerName = headerName.ifBlank { "Authorization" },
                        queryParam = queryParam.ifBlank { "key" },
                    ),
                    value = value,
                )
            }
            loadAll()
            _saveMessage.value = "✅ Custom API key '${name.trim()}' saved"
        }
    }

    fun deleteNamedSecret(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { namedSecrets.delete(name) }
            loadAll()
            _saveMessage.value = "🗑 Custom API key '$name' removed"
        }
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun performBackup(onReady: (File) -> Unit) {
        viewModelScope.launch {
            _backupLoading.value = true
            try {
                val file = backupManager.createBackup()
                onReady(file)
            } catch (e: Exception) {
                _saveMessage.value = "❌ Backup failed: ${e.message}"
            } finally {
                _backupLoading.value = false
            }
        }
    }

    private fun loadAll() {
        _keyStatuses.value = secureKeyStore.getAllKeyStatuses()
        _customStatuses.value = customEndpoints.list().map { ep ->
            val has = secureKeyStore.hasCustomKey(ep.id)
            val masked = if (has) {
                val k = secureKeyStore.getCustomKey(ep.id)!!
                if (k.length > 8) "${k.take(4)}...${k.takeLast(4)}" else "****"
            } else ""
            CustomEndpointStatus(ep, has, masked)
        }
        _namedSecretStatuses.value = namedSecrets.list().map { s ->
            NamedSecretStatus(s, namedSecrets.getValue(s.name) != null)
        }
    }
}
