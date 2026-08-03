package com.forge.os.data.api

import android.content.Context
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.ProviderSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves model capabilities (vision, tools, etc.) dynamically using:
 * 1. API metadata (OpenRouter, Ollama)
 * 2. Static database (bundled LiteLLM-derived map)
 * 3. Name heuristic (fallback)
 * 4. Negative caching (on runtime errors)
 */
@Singleton
class ModelCapabilityResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ModelCapabilityResolver"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    @Serializable
    data class ModelCapability(
        val vision: Boolean,
        val source: String, // "api" | "static_db" | "heuristic" | "user" | "probe_fail"
        val fetchedAt: Long,
    )

    @Serializable
    private data class CapabilityCache(
        val capabilities: Map<String, ModelCapability> = emptyMap(),
    )

    private val cacheFile = File(context.filesDir, "workspace/system/model_capabilities.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, ModelCapability> = mutableMapOf()

    init {
        loadCache()
    }

    /**
     * Check if a model supports vision. Results are cached for 24h.
     */
    suspend fun supportsVision(spec: ProviderSpec): Boolean = withContext(Dispatchers.IO) {
        val key = cacheKey(spec)
        
        // Check cache
        getCached(key)?.let { 
            Timber.d("$TAG: Using cached capability for $key: vision=${it.vision} (source=${it.source})")
            return@withContext it.vision 
        }

        // Resolve capability
        val capability = resolveCapability(spec)
        
        // Cache and return
        cache[key] = capability
        saveCache()
        
        Timber.d("$TAG: Resolved capability for $key: vision=${capability.vision} (source=${capability.source})")
        capability.vision
    }

    /**
     * Mark a model as non-vision (negative caching on runtime error).
     */
    fun markAsNonVision(spec: ProviderSpec) {
        val key = cacheKey(spec)
        cache[key] = ModelCapability(
            vision = false,
            source = "probe_fail",
            fetchedAt = System.currentTimeMillis(),
        )
        saveCache()
        Timber.w("$TAG: Marked $key as non-vision (probe_fail)")
    }

    /**
     * Clear the cache (for testing or manual refresh).
     */
    fun clearCache() {
        cache.clear()
        saveCache()
    }

    // ─── Resolution Logic ────────────────────────────────────────────────────

    private suspend fun resolveCapability(spec: ProviderSpec): ModelCapability {
        val now = System.currentTimeMillis()
        
        return when {
            // 1. OpenRouter API
            spec is ProviderSpec.Builtin && spec.provider == ApiKeyProvider.OPENROUTER -> {
                fetchOpenRouterVision(spec)?.let { vision ->
                    ModelCapability(vision, "api", now)
                } ?: fallbackResolution(spec, now)
            }
            
            // 2. Ollama API
            spec is ProviderSpec.Builtin && spec.provider == ApiKeyProvider.OLLAMA -> {
                fetchOllamaVision(spec)?.let { vision ->
                    ModelCapability(vision, "api", now)
                } ?: fallbackResolution(spec, now)
            }
            
            // 3. Fallback: static DB → heuristic
            else -> fallbackResolution(spec, now)
        }
    }

    private fun fallbackResolution(spec: ProviderSpec, now: Long): ModelCapability {
        val model = spec.effectiveModel
        
        // Try static DB first
        staticDbVision(model)?.let { vision ->
            return ModelCapability(vision, "static_db", now)
        }
        
        // Fall back to heuristic
        val vision = isVisionModelHeuristic(model)
        return ModelCapability(vision, "heuristic", now)
    }

    // ─── OpenRouter API ──────────────────────────────────────────────────────

    private suspend fun fetchOpenRouterVision(spec: ProviderSpec): Boolean? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .build()
            
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: OpenRouter /models failed: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val data = jsonObj["data"]?.jsonArray ?: return@withContext null
                
                // Find the model
                val modelId = spec.effectiveModel
                for (element in data) {
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: continue
                    
                    if (id == modelId) {
                        val architecture = obj["architecture"]?.jsonObject
                        val inputModalities = architecture?.get("input_modalities")?.jsonArray
                        
                        val hasVision = inputModalities?.any { 
                            it.jsonPrimitive.content == "image" 
                        } ?: false
                        
                        Timber.d("$TAG: OpenRouter $modelId vision=$hasVision")
                        return@withContext hasVision
                    }
                }
                
                Timber.w("$TAG: Model $modelId not found in OpenRouter catalog")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to fetch OpenRouter capabilities")
            null
        }
    }

    // ─── Ollama API ──────────────────────────────────────────────────────────

    private suspend fun fetchOllamaVision(spec: ProviderSpec): Boolean? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = (spec as? ProviderSpec.Builtin)?.provider?.baseUrl 
                ?: "http://localhost:11434"
            val model = spec.effectiveModel
            
            val requestBody = """{"model":"$model"}"""
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/api/show")
                .post(requestBody)
                .build()
            
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("$TAG: Ollama /api/show failed: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val jsonObj = json.parseToJsonElement(body).jsonObject
                val capabilities = jsonObj["capabilities"]?.jsonArray
                
                val hasVision = capabilities?.any { 
                    it.jsonPrimitive.content == "vision" 
                } ?: false
                
                Timber.d("$TAG: Ollama $model vision=$hasVision")
                hasVision
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to fetch Ollama capabilities")
            null
        }
    }

    // ─── Static Database ─────────────────────────────────────────────────────

    /**
     * Static database of known vision models (LiteLLM-derived).
     * This is a fallback for providers that don't expose capability metadata.
     */
    private fun staticDbVision(model: String): Boolean? {
        val m = model.lowercase()
        
        // Known vision models (definitive)
        val knownVision = setOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4.1", "gpt-5",
            "claude-3-opus", "claude-3-sonnet", "claude-3-haiku", "claude-3-5-sonnet",
            "claude-4-opus", "claude-4-sonnet",
            "gemini-2.0-flash", "gemini-2.5-pro", "gemini-2.5-flash",
            "grok-2-vision", "grok-vision",
            "pixtral-12b", "pixtral-large",
        )
        
        if (knownVision.any { m.contains(it) }) return true
        
        // Known text-only models (definitive)
        val knownTextOnly = setOf(
            "gpt-3.5-turbo", "gpt-4", "claude-2", "claude-instant",
            "deepseek-chat", "deepseek-coder",
            "llama-3.1", "llama-3.3", "mistral-small", "mistral-large",
        )
        
        if (knownTextOnly.any { m.contains(it) }) return false
        
        // Unknown — return null to fall through to heuristic
        return null
    }

    // ─── Heuristic ───────────────────────────────────────────────────────────

    private fun isVisionModelHeuristic(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("vision") || m.contains("llava") || m.contains("bakllava") ||
               m.contains("pixtral") || m.contains("gpt-4o") || m.contains("gpt-4.1") ||
               m.contains("gpt-5") || m.contains("claude-3") || m.contains("claude-4") ||
               m.contains("claude-opus") || m.contains("claude-sonnet") || 
               m.contains("claude-haiku") || m.contains("gemini") || 
               m.contains("gpt-4-turbo") || m.contains("qwen2.5vl") || 
               m.contains("qwen3-vl") || m.contains("-vl") || 
               m.contains("minicpm") || m.contains("moondream") || 
               m.contains("gemma3") || m.contains("mistral-small3") ||
               m.contains("llama3.2-vision") || m.contains("granite") ||
               m.contains("deepseek-ocr") || m.contains("grok-vision") ||
               m.contains("grok-2-vision")
    }

    // ─── Cache Management ────────────────────────────────────────────────────

    private fun cacheKey(spec: ProviderSpec): String {
        return when (spec) {
            is ProviderSpec.Builtin -> "${spec.provider.name}:${spec.effectiveModel}"
            is ProviderSpec.Custom -> "custom:${spec.endpoint.baseUrl}:${spec.effectiveModel}"
        }
    }

    private fun getCached(key: String): ModelCapability? {
        val cached = cache[key] ?: return null
        val age = System.currentTimeMillis() - cached.fetchedAt
        
        return if (age < CACHE_TTL_MS) {
            cached
        } else {
            cache.remove(key)
            null
        }
    }

    private fun loadCache() {
        try {
            if (!cacheFile.exists()) return
            val json = cacheFile.readText()
            val loaded = this.json.decodeFromString<CapabilityCache>(json)
            cache = loaded.capabilities.toMutableMap()
            Timber.d("$TAG: Loaded ${cache.size} cached capabilities")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to load capability cache")
        }
    }

    private fun saveCache() {
        try {
            cacheFile.parentFile?.mkdirs()
            val json = this.json.encodeToString(CapabilityCache.serializer(), CapabilityCache(cache))
            cacheFile.writeText(json)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save capability cache")
        }
    }
}
