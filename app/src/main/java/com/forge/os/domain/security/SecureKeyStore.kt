package com.forge.os.domain.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Built-in providers. New entries follow the OpenAI Chat Completions schema unless noted.
 * Gemini is exposed through Google's OpenAI-compatible endpoint, so it works with the same code path.
 */
enum class ApiKeyProvider(
    val displayName: String,
    val prefKey: String,
    val baseUrl: String,
    val schema: ProviderSchema,
    val defaultModel: String
) {
    OPENAI     ("OpenAI",        "key_openai",     "https://api.openai.com/v1/",                              ProviderSchema.OPENAI,    "gpt-4o-mini"),
    ANTHROPIC  ("Anthropic",     "key_anthropic",  "https://api.anthropic.com/",                              ProviderSchema.ANTHROPIC, "claude-3-5-sonnet-latest"),
    GROQ       ("Groq",          "key_groq",       "https://api.groq.com/openai/v1/",                         ProviderSchema.OPENAI,    "llama-3.3-70b-versatile"),
    OPENROUTER ("OpenRouter",    "key_openrouter", "https://openrouter.ai/api/v1/",                           ProviderSchema.OPENAI,    "openrouter/auto"),
    OLLAMA     ("Ollama (Local)","key_ollama_url", "http://localhost:11434/v1/",                              ProviderSchema.OPENAI,    "llama3.1"),
    GEMINI     ("Google Gemini", "key_gemini",     "https://generativelanguage.googleapis.com/v1beta/openai/",ProviderSchema.OPENAI,    "gemini-2.0-flash"),
    XAI        ("xAI (Grok)",    "key_xai",        "https://api.x.ai/v1/",                                    ProviderSchema.OPENAI,    "grok-2-latest"),
    DEEPSEEK   ("DeepSeek",      "key_deepseek",   "https://api.deepseek.com/v1/",                            ProviderSchema.OPENAI,    "deepseek-chat"),
    MISTRAL    ("Mistral",       "key_mistral",    "https://api.mistral.ai/v1/",                              ProviderSchema.OPENAI,    "mistral-small-latest"),
    TOGETHER   ("Together AI",   "key_together",   "https://api.together.xyz/v1/",                            ProviderSchema.OPENAI,    "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    CEREBRAS   ("Cerebras",      "key_cerebras",   "https://api.cerebras.ai/v1/",                             ProviderSchema.OPENAI,    "llama3.1-70b"),
    FORGE_BRIDGE("Forge Bridge", "key_forge_bridge","http://127.0.0.1:8745/api/v1/",                           ProviderSchema.FORGE_BRIDGE, "auto");
}

enum class ProviderSchema { OPENAI, ANTHROPIC, FORGE_BRIDGE }

data class KeyStatus(
    val provider: ApiKeyProvider,
    val hasKey: Boolean,
    val maskedKey: String = "",
    val isValid: Boolean = false
)

@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(false)
            .build()
    }

    private val prefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "forge_secure_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "EncryptedSharedPreferences failed, falling back to regular prefs")
            context.getSharedPreferences("forge_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    fun saveKey(provider: ApiKeyProvider, key: String) {
        if (key.isBlank()) { deleteKey(provider); return }
        prefs.edit().putString(provider.prefKey, key.trim()).apply()
        Timber.i("Key saved for ${provider.displayName}")
    }

    fun getKey(provider: ApiKeyProvider): String? =
        prefs.getString(provider.prefKey, null)?.takeIf { it.isNotBlank() }

    fun deleteKey(provider: ApiKeyProvider) {
        prefs.edit().remove(provider.prefKey).apply()
        Timber.i("Key deleted for ${provider.displayName}")
    }

    fun hasKey(provider: ApiKeyProvider): Boolean = getKey(provider) != null

    fun getMaskedKey(provider: ApiKeyProvider): String {
        val key = getKey(provider) ?: return ""
        // FORGE_BRIDGE uses a sentinel "local" — show a human-readable status instead.
        if (provider == ApiKeyProvider.FORGE_BRIDGE) return "localhost:8745 (connected)"
        return if (key.length > 8) "${key.take(4)}...${key.takeLast(4)}" else "****"
    }

    /** Custom-endpoint keys are stored under a synthetic pref key. */
    fun saveCustomKey(endpointId: String, key: String) {
        if (key.isBlank()) { deleteCustomKey(endpointId); return }
        prefs.edit().putString(customPrefKey(endpointId), key.trim()).apply()
    }
    fun getCustomKey(endpointId: String): String? =
        prefs.getString(customPrefKey(endpointId), null)?.takeIf { it.isNotBlank() }
    fun deleteCustomKey(endpointId: String) {
        prefs.edit().remove(customPrefKey(endpointId)).apply()
    }
    fun hasCustomKey(endpointId: String): Boolean = getCustomKey(endpointId) != null
    private fun customPrefKey(id: String) = "key_custom_$id"

    fun getAllKeyStatuses(): List<KeyStatus> = ApiKeyProvider.entries.map { p ->
        val has = hasKey(p)
        KeyStatus(p, has, if (has) getMaskedKey(p) else "", has)
    }

    /** Priority order for auto-routing when the user hasn't picked. */
    fun getActiveProvider(): ApiKeyProvider? = listOf(
        ApiKeyProvider.FORGE_BRIDGE,
        ApiKeyProvider.ANTHROPIC, ApiKeyProvider.OPENAI, ApiKeyProvider.GEMINI,
        ApiKeyProvider.GROQ, ApiKeyProvider.DEEPSEEK, ApiKeyProvider.XAI,
        ApiKeyProvider.MISTRAL, ApiKeyProvider.TOGETHER, ApiKeyProvider.CEREBRAS,
        ApiKeyProvider.OPENROUTER, ApiKeyProvider.OLLAMA
    ).firstOrNull { hasKey(it) }

    /** Activate Forge Bridge — stores the sentinel so [hasKey] returns true. */
    fun activateForgeBridge() = prefs.edit()
        .putString(ApiKeyProvider.FORGE_BRIDGE.prefKey, "local").apply()

    /** Deactivate Forge Bridge — removes the sentinel. */
    fun deactivateForgeBridge() = prefs.edit()
        .remove(ApiKeyProvider.FORGE_BRIDGE.prefKey).apply()

    /** True when Forge Bridge is the active provider sentinel is stored. */
    fun isForgeBridgeEnabled(): Boolean = hasKey(ApiKeyProvider.FORGE_BRIDGE)

    fun clearAll() {
        ApiKeyProvider.entries.forEach { deleteKey(it) }
        Timber.w("All API keys cleared")
    }
}
