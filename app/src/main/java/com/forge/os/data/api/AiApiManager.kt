package com.forge.os.data.api

import android.content.Context
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.security.ApiKeyProvider
import com.forge.os.domain.security.CustomEndpoint
import com.forge.os.domain.security.CustomEndpointRepository
import com.forge.os.domain.security.ProviderSchema
import com.forge.os.domain.security.ProviderSpec
import com.forge.os.domain.security.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiApiManager @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
    private val configRepository: ConfigRepository,
    private val customEndpoints: CustomEndpointRepository,
    private val callLog: ApiCallLog,
    private val costMeter: CostMeter,
    @ApplicationContext private val appContext: Context,
    private val bridgeDiscovery: ForgeBridgeDiscovery,
) {
    private val json = Json {
        ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // ─── Main entry point ────────────────────────────────────────────────────

    /**
     * Phase R — chat() with the global fallback chain applied on any
     * [ApiCallException]. Tries the primary provider first, then walks
     * `config.modelRouting.fallbackChain` until one succeeds or the chain
     * is exhausted. Foreground callers (ReActAgent) and background callers
     * (cron, alarms, sub-agents, proactive) both go through this so a dead
     * primary key/quota doesn't kill the whole turn.
     */
    suspend fun chatWithFallback(
        messages: List<ApiMessage>,
        tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null,
        spec: ProviderSpec? = null,
        mode: com.forge.os.domain.companion.Mode = com.forge.os.domain.companion.Mode.AGENT,
    ): UnifiedResponse {
        val primaryError: ApiCallException = try {
            return chat(messages, tools, systemPrompt, spec, mode)
        } catch (ace: ApiCallException) { ace }

        val chain = configRepository.get().modelRouting.fallbackChain
        if (chain.isEmpty()) throw primaryError
        Timber.w("Primary provider failed (${primaryError.error.userFacing()}); trying fallback chain (${chain.size})")
        var lastError: ApiCallException = primaryError
        for (link in chain) {
            val provider = runCatching { ApiKeyProvider.valueOf(link.provider) }.getOrNull() ?: continue
            if (!secureKeyStore.hasKey(provider)) {
                Timber.d("Fallback skip: no key for ${link.provider}")
                continue
            }
            try {
                Timber.i("Fallback attempt: ${link.provider}/${link.model}")
                return chat(messages, tools, systemPrompt, ProviderSpec.Builtin(provider, link.model), mode)
            } catch (ace: ApiCallException) {
                lastError = ace
                Timber.w("Fallback ${link.provider} failed: ${ace.error.userFacing()}")
            }
        }
        throw lastError
    }

    /**
     * Phase 3 — Build a [ProviderSpec] from a saved override pair (e.g. from 
     * ChannelConfig or CronJob). Returns null if key is missing or endpoint 
     * is deleted.
     */
    fun resolveSpec(providerKey: String?, modelId: String?): ProviderSpec? {
        if (providerKey.isNullOrBlank() || modelId.isNullOrBlank()) return null
        if (providerKey.startsWith("custom:")) {
            val id = providerKey.removePrefix("custom:")
            val ep = customEndpoints.get(id) ?: return null
            if (!secureKeyStore.hasCustomKey(id)) return null
            return ProviderSpec.Custom(ep, modelId)
        }
        val enum = runCatching { ApiKeyProvider.valueOf(providerKey) }.getOrNull() ?: return null
        if (!secureKeyStore.hasKey(enum)) return null
        return ProviderSpec.Builtin(enum, modelId)
    }

    /** Tiny tuple used by the model picker; data class instead of
     *  `Quadruple` since stdlib doesn't ship one. */
    data class Quad(
        val providerKey: String,
        val providerLabel: String,
        val model: String,
        val kind: String,
    )

    /**
     * @param spec  Optional explicit (provider, model) selection. If null, falls back
     *              to the auto-routed provider from config + saved keys.
     */
    suspend fun chat(
        messages: List<ApiMessage>,
        tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null,
        spec: ProviderSpec? = null,
        mode: com.forge.os.domain.companion.Mode = com.forge.os.domain.companion.Mode.AGENT,
    ): UnifiedResponse = withContext(Dispatchers.IO) {
        val resolved = spec
            ?: (if (mode != com.forge.os.domain.companion.Mode.AGENT)
                pickProviderForMode(mode) else null)
            ?: autoRoute()
            ?: throw ApiCallException(ApiError(
                httpCode = 0, message = "No API keys configured. Add one in Settings.",
                provider = "none", model = "none"
            ))
        // Phase M-3 — stash the mode for cost tagging in the call paths below.
        currentCallMode = mode

          // Phase O — Compact Mode ────────────────────────────────────────────
          val compact = configRepository.get().modelRouting.compactMode
          val effectiveMessages = if (compact.enabled && messages.size > compact.maxContextMessages) {
              val intentSize = 3
              if (messages.size <= intentSize) messages
              else {
                  val head = messages.take(intentSize)
                  val tail = messages.takeLast((compact.maxContextMessages - intentSize).coerceAtLeast(1))
                  head + tail
              }
          } else messages
          val maxTokensOverride: Int? = if (compact.enabled) compact.maxTokensPerRequest else null
          val compactSpec: ProviderSpec? = if (compact.enabled && spec == null) {
              runCatching { ApiKeyProvider.valueOf(compact.preferProvider) }.getOrNull()
                  ?.takeIf { secureKeyStore.hasKey(it) }
                  ?.let { ProviderSpec.Builtin(it, compact.preferModel) }
          } else null
          val finalResolved = compactSpec ?: resolved

          // Phase 3 — Eco-Mode (Dynamic Token Budgeting) ──────────────────────
          val budget = configRepository.get().costBudget
          val finalEffectiveResolved = if (budget.enabled && spec == null) {
              val dailySpend = costMeter.getDailyTotal()
              if (dailySpend >= budget.dailyLimitUsd) {
                  val ecoProvider = runCatching { ApiKeyProvider.valueOf(budget.ecoProvider) }.getOrNull()
                  if (ecoProvider != null && secureKeyStore.hasKey(ecoProvider)) {
                      Timber.w("Daily budget exceeded ($${"%.2f".format(dailySpend)} >= $${"%.2f".format(budget.dailyLimitUsd)}). Downshifting to ECO-MODE.")
                      ProviderSpec.Builtin(ecoProvider, budget.ecoModel)
                  } else finalResolved
              } else finalResolved
          } else finalResolved
          // ─────────────────────────────────────────────────────────────────────

          Timber.d("chat → ${finalEffectiveResolved.displayLabel}, msgs=${effectiveMessages.size}, tools=${tools.size}, compact=${compact.enabled}")

          when (finalEffectiveResolved.schema) {
              ProviderSchema.OPENAI       -> callOpenAi(finalEffectiveResolved, effectiveMessages, tools, systemPrompt, maxTokensOverride)
              ProviderSchema.ANTHROPIC    -> callAnthropic(finalEffectiveResolved, effectiveMessages, tools, systemPrompt, maxTokensOverride)
              ProviderSchema.FORGE_BRIDGE -> callForgeBridge(finalEffectiveResolved, effectiveMessages, tools, systemPrompt, maxTokensOverride)
          }
      }

    // ─── Phase J2 — Embeddings ────────────────────────────────────────────────

    /**
     * Resolves the embedding (provider, model) the user has configured under
     * `modelRouting.routingRules["memory_embedding"]` (falls back to the
     * `OPENAI` provider with `MemorySettings.embeddingModel`).
     *
     * @return null when no usable provider has a key OR when the resolved
     *         provider doesn't speak the OpenAI embeddings schema. In that
     *         case the caller should use a local fallback.
     */
    private fun resolveEmbeddingSpec(): ProviderSpec? {
        val cfg = configRepository.get()
        val rule = cfg.modelRouting.routingRules
            .firstOrNull { it.taskType == "memory_embedding" }
        val providerName = rule?.provider ?: cfg.memorySettings.embeddingProvider
        val model = rule?.model ?: cfg.memorySettings.embeddingModel

        // 1) preferred built-in provider, if it's OpenAI-schema and has a key
        val provider = runCatching { ApiKeyProvider.valueOf(providerName) }.getOrNull()
        if (provider != null && secureKeyStore.hasKey(provider) &&
            provider.schema == ProviderSchema.OPENAI) {
            return ProviderSpec.Builtin(provider, model)
        }
        // 2) preferred custom endpoint by name (rule.provider may match a
        //    custom endpoint name/id rather than a built-in enum)
        val preferredCustom = customEndpoints.list().firstOrNull {
            it.schema == ProviderSchema.OPENAI &&
                secureKeyStore.hasCustomKey(it.id) &&
                (it.id == providerName || it.name.equals(providerName, ignoreCase = true))
        }
        if (preferredCustom != null) {
            return ProviderSpec.Custom(preferredCustom, model)
        }
        // 3) fallback: any OpenAI-schema built-in with a key
        val anyBuiltin = ApiKeyProvider.entries.firstOrNull {
            it.schema == ProviderSchema.OPENAI && secureKeyStore.hasKey(it)
        }
        if (anyBuiltin != null) return ProviderSpec.Builtin(anyBuiltin, model)
        // 4) fallback: any OpenAI-schema custom endpoint with a key
        val anyCustom = customEndpoints.list().firstOrNull {
            it.schema == ProviderSchema.OPENAI && secureKeyStore.hasCustomKey(it.id)
        } ?: return null
        return ProviderSpec.Custom(anyCustom, model)
    }

    private fun resolveVisionSpec(): ProviderSpec? {
        val cfg = configRepository.get().modelRouting
        resolveSpec(cfg.visionProvider, cfg.visionModel)?.let { return it }
        // Fallback: search for any key that supports vision (gpt-4o, claude-3-5, etc.)
        return autoRoute() // autoRoute already picks a "good" model usually
    }

    private fun resolveReasoningSpec(): ProviderSpec? {
        val cfg = configRepository.get().modelRouting
        resolveSpec(cfg.reasoningProvider, cfg.reasoningModel)?.let { return it }
        return autoRoute()
    }

    private fun resolveReflectionSpec(): ProviderSpec? {
        val cfg = configRepository.get().modelRouting
        resolveSpec(cfg.reflectionProvider, cfg.reflectionModel)?.let { return it }
        // Reflection is often better on a faster/cheaper model if not specified.
        return resolveSpec("GROQ", "llama-3.3-70b-versatile") ?: autoRoute()
    }

    /** Best-effort label for whatever embedding model we're currently using. */
    fun embeddingModelLabel(): String {
        val s = resolveEmbeddingSpec() ?: return "local"
        return "${s.displayLabel}"
    }

    /** Returns true if the model ID suggests it's an embedding model. */
    fun isEmbeddingModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("embed") || id.contains("text-embedding") || 
               id.contains("all-minilm") || id.contains("gte-") || id.contains("ada-002")
    }

    fun isVisionModel(model: String): Boolean {
        val m = model.lowercase()
        return m.contains("vision") || m.contains("claude-3") || m.contains("gpt-4o") || m.contains("gemini")
    }

    /**
     * Compute embeddings for one or more input strings. Uses the OpenAI
     * `/embeddings` schema (which Groq, OpenRouter, Ollama, Together,
     * Cerebras, DeepSeek, Mistral and OpenAI itself all speak).
     *
     * @return one FloatArray per input, or null if no key is available.
     * @throws ApiCallException on HTTP errors.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val spec = resolveEmbeddingSpec() ?: return@withContext null
        val (apiKey, baseUrl, authHeader, authPrefix, providerLabel) = resolveAuth(spec)
        val model = spec.effectiveModel

        // Hand-rolled JSON body — the embeddings endpoint takes a tiny shape we
        // don't already model in ApiModels.kt and adding it there isn't worth
        // the noise.
        val payload = buildString {
            append("{\"model\":\"").append(jsonEscape(model)).append("\",\"input\":[")
            texts.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append('"').append(jsonEscape(t.take(8000))).append('"')
            }
            append("]}")
        }
        val body = payload.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("${baseUrl}embeddings")
            .header(authHeader, "$authPrefix$apiKey")
            .header("Content-Type", "application/json")
            .post(body).build()

        val (response, responseBody, durationMs, attempt) =
            executeWithRetry(request, providerLabel, model)

        if (!response.isSuccessful) {
            val err = parseError(response, responseBody, providerLabel, model)
            callLog.record(ApiCallLogEntry(
                System.currentTimeMillis(), providerLabel, model,
                request.url.toString(), response.code, durationMs,
                errorMessage = err.userFacing(), attempt = attempt
            ))
            throw ApiCallException(err)
        }
        callLog.record(ApiCallLogEntry(
            System.currentTimeMillis(), providerLabel, model,
            request.url.toString(), response.code, durationMs, attempt = attempt
        ))

        // Parse `{ "data": [ { "embedding": [ ... ] }, ... ] }`. We do this by
        // hand to avoid declaring yet another @Serializable class.
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        val data = parsed["data"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return@withContext null
        return@withContext data.map { item ->
            val arr = item.jsonObject["embedding"] as? kotlinx.serialization.json.JsonArray
                ?: return@withContext null
            FloatArray(arr.size) { i -> arr[i].jsonPrimitive.content.toFloat() }
        }
    }

    private fun jsonEscape(s: String): String = buildString(s.length + 2) {
        for (c in s) when (c) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /** List every (provider, default-model) pair the user can pick from right now. */
    fun availableSpecs(): List<ProviderSpec> {
        val builtins = ApiKeyProvider.entries
            .filter { secureKeyStore.hasKey(it) }
            .map { ProviderSpec.Builtin(it) }
        val customs = customEndpoints.list()
            .filter { secureKeyStore.hasCustomKey(it.id) }
            .map { ProviderSpec.Custom(it) }
        return builtins + customs
    }

    /**
     * Like [availableSpecs] but returns ONE [ProviderSpec.Builtin] per actual
     * model the provider offers — fetched live from `/models` (cached 24h).
     * If a provider's catalog can't be fetched, it falls back to its single
     * hard-coded default. Custom endpoints still surface as their saved spec.
     */
    suspend fun availableSpecsExpanded(): List<ProviderSpec> = withContext(Dispatchers.IO) {
        val builtins = ApiKeyProvider.entries
            .filter { secureKeyStore.hasKey(it) }
            .flatMap { provider ->
                val ids = runCatching { listModels(provider) }
                    .getOrElse { listOf(provider.defaultModel) }
                    .ifEmpty { listOf(provider.defaultModel) }
                ids.map { ProviderSpec.Builtin(provider, it) }
            }
        // Expand custom endpoints the same way: probe `/models` so the user
        // can pick from the live catalog instead of being stuck on the single
        // hard-coded `defaultModel`. Falls back to defaultModel if the probe
        // fails (network error, gateway not exposing /models, etc.).
        val customs = customEndpoints.list()
            .filter { secureKeyStore.hasCustomKey(it.id) }
            .flatMap { ep ->
                val ids = runCatching { listCustomModels(ep) }
                    .getOrElse { listOf(ep.defaultModel) }
                    .ifEmpty { listOf(ep.defaultModel) }
                ids.map { mid -> ProviderSpec.Custom(ep, mid) }
            }
        builtins + customs
    }

    /** Phase U2 — all selectable (provider, model) pairs across both built-in
     *  providers and custom endpoints. Wraps [availableSpecsExpanded]. */
    suspend fun availableModels(): List<Quad> {
        val specs = runCatching { availableSpecsExpanded() }
            .getOrElse { emptyList() }
        return specs.map { spec ->
            when (spec) {
                is ProviderSpec.Builtin -> Quad(
                    providerKey = spec.provider.name,
                    providerLabel = spec.provider.displayName,
                    model = spec.effectiveModel,
                    kind = "builtin",
                )
                is ProviderSpec.Custom -> Quad(
                    providerKey = "custom:${spec.endpoint.id}",
                    providerLabel = spec.endpoint.name,
                    model = spec.effectiveModel,
                    kind = "custom",
                )
            }
        }
    }

    /** Same caching/fallback semantics as [listModels], but for a user-defined
     *  custom endpoint. Anthropic-schema customs hit `/v1/models`; the rest hit
     *  the OpenAI-style `/models`. The cache key uses the endpoint id so two
     *  custom endpoints to the same gateway URL don't collide. */
    suspend fun listCustomModels(
        endpoint: CustomEndpoint,
        forceRefresh: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        val cacheKey = "custom:${endpoint.id}"
        val now = System.currentTimeMillis()
        val cached = loadCatalogFile().entries[cacheKey]
        if (!forceRefresh && cached != null && (now - cached.fetchedAt) < catalogTtlMs) {
            return@withContext cached.ids
        }
        val key = secureKeyStore.getCustomKey(endpoint.id)
            ?: return@withContext listOf(endpoint.defaultModel)
        val ids = runCatching {
            when (endpoint.schema) {
                ProviderSchema.OPENAI       -> fetchCustomOpenAiModels(endpoint, key)
                ProviderSchema.ANTHROPIC    -> fetchAnthropicModels(endpoint.baseUrl, key)
                ProviderSchema.FORGE_BRIDGE -> emptyList()
            }
        }.getOrElse {
            Timber.w(it, "listCustomModels failed for ${endpoint.name}")
            emptyList()
        }
        val resolved = if (ids.isEmpty()) listOf(endpoint.defaultModel) else ids
        val updated = loadCatalogFile().let { f ->
            f.copy(entries = f.entries + (cacheKey to CachedCatalog(now, resolved)))
        }
        saveCatalogFile(updated)
        resolved
    }

    private fun fetchCustomOpenAiModels(endpoint: CustomEndpoint, apiKey: String): List<String> {
        val url = endpoint.baseUrl.trimEnd('/') + "/models"
        val req = Request.Builder().url(url)
            .header(endpoint.authHeader, "${endpoint.authPrefix}$apiKey")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val parsed = json.decodeFromString<JsonObject>(body)
            val data = parsed["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull {
                (it as? JsonObject)?.get("id")?.jsonPrimitive?.content
            }.distinct().sorted()
        }
    }

    // ─── OpenAI / Groq / OpenRouter / Gemini-OAI / xAI / DeepSeek / Mistral … ───

    private suspend fun callOpenAi(
        spec: ProviderSpec,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        maxTokensOverride: Int? = null
    ): UnifiedResponse {
        val (apiKey, baseUrl, authHeader, authPrefix, providerLabel) = resolveAuth(spec)
        val model = spec.effectiveModel
        val allMessages = if (systemPrompt != null) {
            listOf(ApiMessage(role = "system", content = systemPrompt)) + messages
        } else messages

        // ── Vision Support ──────────────────────────────────────────────────
        // Some models require the 'content' field to be an array of objects
        // when images are present.
        val finalMessages = allMessages.map { msg ->
            if (msg.contentParts != null) msg
            else if (msg.content != null) msg
            else msg
        }

        // ── Gemini compatibility ─────────────────────────────────────────────
        // Gemini's OpenAI-compat endpoint chokes on:
        //   1. tool_choice: "auto"  → returns 400 on several model versions
        //   2. additionalProperties in tool JSON schemas → unsupported keyword
        // Detect Gemini and sanitize the request before sending.
        val isGemini = spec is ProviderSpec.Builtin && spec.provider == ApiKeyProvider.GEMINI
        val safeToolChoice = when {
            tools.isEmpty() -> null
            isGemini -> null          // Gemini auto-detects; explicit "auto" breaks
            else -> "auto"
        }
        val safeTools = if (isGemini && tools.isNotEmpty()) sanitizeToolsForGemini(tools) else tools

        val reqBody = OpenAiRequest(
            model = model, messages = finalMessages,
            tools = safeTools.takeIf { it.isNotEmpty() },
            toolChoice = safeToolChoice,
            maxTokens = maxTokensOverride ?: 4096
        )
        // Gemini may also reject 'max_tokens' in favour of 'max_output_tokens'.
        // We post-process the serialized JSON to swap the key when targeting Gemini.
        val rawJson = json.encodeToString(reqBody)
        val finalJson = if (isGemini) rawJson.replace("\"max_tokens\":", "\"max_output_tokens\":") else rawJson
        val body = finalJson.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("${baseUrl}chat/completions")
            .header(authHeader, "$authPrefix$apiKey")
            .header("Content-Type", "application/json")
            .post(body).build()

        val (response, responseBody, durationMs, attempt) =
            executeWithRetry(request, providerLabel, model)

        if (!response.isSuccessful) {
            val err = parseError(response, responseBody, providerLabel, model)
            callLog.record(ApiCallLogEntry(
                System.currentTimeMillis(), providerLabel, model,
                request.url.toString(), response.code, durationMs,
                errorMessage = err.userFacing(), attempt = attempt
            ))
            // Groq (and some other OpenAI-schema providers) return HTTP 400
            // `tool_use_failed` when the model emits a malformed tool call —
            // commonly because an MCP/plugin tool exposes a schema the model
            // doesn't quite understand. Retry once without tools so the user
            // still gets a useful answer instead of a hard failure.
            val isToolUseFailed = response.code == 400 &&
                (err.providerCode == "tool_use_failed" ||
                 err.message.contains("tool_use_failed", ignoreCase = true) ||
                 err.message.contains("Failed to call a function", ignoreCase = true) ||
                 // Gemini-specific: rejects unsupported schema keywords or tool formats
                 (isGemini && (err.message.contains("INVALID_ARGUMENT", ignoreCase = true) ||
                               err.message.contains("is not valid", ignoreCase = true) ||
                               err.message.contains("properties", ignoreCase = true))))
            if (isToolUseFailed && safeTools.isNotEmpty()) {
                Timber.w("$providerLabel returned tool_use_failed; retrying without tools")
                return callOpenAi(spec, messages, emptyList(), systemPrompt, maxTokensOverride)
            }
            throw ApiCallException(err)
        }

        val parsed = json.decodeFromString<OpenAiResponse>(responseBody)
        val choice = parsed.choices.firstOrNull()
            ?: throw ApiCallException(ApiError(
                httpCode = response.code, message = "No choices in response",
                provider = providerLabel, model = model, raw = responseBody.take(400)
            ))

        val inTok = parsed.usage?.promptTokens ?: 0
        val outTok = parsed.usage?.completionTokens ?: 0
        callLog.record(ApiCallLogEntry(
            System.currentTimeMillis(), providerLabel, model,
            request.url.toString(), response.code, durationMs,
            inputTokens = inTok, outputTokens = outTok, attempt = attempt
        ))
        costMeter.record(model, inTok, outTok, currentCallMode)

        return UnifiedResponse(
            content = choice.message.content,
            toolCalls = choice.message.toolCalls ?: emptyList(),
            finishReason = choice.finishReason,
            inputTokens = parsed.usage?.promptTokens ?: 0,
            outputTokens = parsed.usage?.completionTokens ?: 0,
            provider = providerLabel, model = model
        )
    }

    // ─── Anthropic ───────────────────────────────────────────────────────────

    private suspend fun callAnthropic(
        spec: ProviderSpec,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        maxTokensOverride: Int? = null
    ): UnifiedResponse {
        val (apiKey, baseUrl, _, _, providerLabel) = resolveAuth(spec)
        val model = spec.effectiveModel

        val anthropicMessages = messages.filter { it.role != "system" }.map { msg ->
            when {
                msg.toolCalls != null -> AnthropicMessage(
                    role = "assistant",
                    content = msg.toolCalls.map { tc ->
                        AnthropicContent(
                            type = "tool_use", id = tc.id, name = tc.function.name,
                            input = parseJsonToMap(tc.function.arguments)
                        )
                    }
                )
                msg.role == "tool" -> AnthropicMessage(
                    role = "user",
                    content = listOf(AnthropicContent(
                        type = "tool_result", toolUseId = msg.toolCallId, content = msg.content
                    ))
                )
                msg.contentParts != null -> AnthropicMessage(
                    role = msg.role,
                    content = msg.contentParts.map { part ->
                        if (part.type == "image_url" && part.imageUrl != null) {
                            val data = part.imageUrl.url.substringAfter("base64,")
                            val mime = part.imageUrl.url.substringAfter("data:").substringBefore(";base64")
                            AnthropicContent(
                                type = "image",
                                source = AnthropicImageSource(mediaType = mime, data = data)
                            )
                        } else {
                            AnthropicContent(type = "text", text = part.text ?: "")
                        }
                    }
                )
                else -> AnthropicMessage(
                    role = msg.role,
                    content = listOf(AnthropicContent(type = "text", text = msg.content ?: ""))
                )
            }
        }

        val anthropicTools = tools.map {
            AnthropicTool(it.function.name, it.function.description, it.function.parameters)
        }

        val reqBody = AnthropicRequest(
            model = model, system = systemPrompt, messages = anthropicMessages,
            tools = anthropicTools.takeIf { it.isNotEmpty() },
            maxTokens = maxTokensOverride ?: 4096
        )
        val body = json.encodeToString(reqBody).toRequestBody(mediaType)
        val request = Request.Builder()
            .url("${baseUrl}v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body).build()

        val (response, responseBody, durationMs, attempt) =
            executeWithRetry(request, providerLabel, model)

        if (!response.isSuccessful) {
            val err = parseError(response, responseBody, providerLabel, model)
            callLog.record(ApiCallLogEntry(
                System.currentTimeMillis(), providerLabel, model,
                request.url.toString(), response.code, durationMs,
                errorMessage = err.userFacing(), attempt = attempt
            ))
            throw ApiCallException(err)
        }

        val parsed = json.decodeFromString<AnthropicResponse>(responseBody)
        val textContent = parsed.content
            .filter { it.type == "text" }.mapNotNull { it.text }
            .joinToString("\n").takeIf { it.isNotBlank() }

        val toolCalls = parsed.content.filter { it.type == "tool_use" }.map { block ->
            ToolCallResponse(
                id = block.id ?: "tc_${System.currentTimeMillis()}",
                function = FunctionCall(
                    name = block.name ?: "",
                    arguments = json.encodeToString(block.input ?: emptyMap<String, String>())
                )
            )
        }

        val inTok = parsed.usage?.inputTokens ?: 0
        val outTok = parsed.usage?.outputTokens ?: 0
        callLog.record(ApiCallLogEntry(
            System.currentTimeMillis(), providerLabel, model,
            request.url.toString(), response.code, durationMs,
            inputTokens = inTok, outputTokens = outTok, attempt = attempt
        ))
        costMeter.record(model, inTok, outTok, currentCallMode)

        return UnifiedResponse(
            content = textContent, toolCalls = toolCalls,
            finishReason = parsed.stopReason,
            inputTokens = parsed.usage?.inputTokens ?: 0,
            outputTokens = parsed.usage?.outputTokens ?: 0,
            provider = providerLabel, model = model
        )
    }

    // ─── Forge Bridge ─────────────────────────────────────────────────────────

    /**
     * Routes a chat request through the locally running forge-bridge-android server
     * at http://127.0.0.1:8745/api/v1/generate. The bridge handles provider selection,
     * credential management, and request formatting internally — ForgeOS just sends
     * messages + tools and gets back a unified response.
     *
     * Tool calls are fully supported: tools are forwarded in OpenAI format (the bridge
     * adapters convert to the appropriate provider format), and tool_calls are returned
     * in OpenAI format regardless of the underlying provider.
     */
    private suspend fun callForgeBridge(
        spec: ProviderSpec,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        maxTokensOverride: Int? = null,
    ): UnifiedResponse = withContext(Dispatchers.IO) {
        val providerLabel = "Forge Bridge"
        val requestedModel = spec.effectiveModel.let { if (it == "auto" || it.isBlank()) null else it }

        // Build the bridge request body. Messages are serialized in OpenAI wire
        // format (the existing kotlinx.serialization @SerialName annotations already
        // produce the right snake_case keys).
        val messagesJson = json.encodeToString(messages)
        val bodyJson = buildString {
            append("""{"messages":$messagesJson""")
            if (!systemPrompt.isNullOrBlank()) {
                append(""","systemPrompt":${json.encodeToString(systemPrompt)}""")
            }
            if (requestedModel != null) {
                append(""","model":${json.encodeToString(requestedModel)}""")
            }
            if (tools.isNotEmpty()) {
                val toolsJson = json.encodeToString(tools)
                append(""","tools":$toolsJson,"toolChoice":"auto"""")
            }
            if (maxTokensOverride != null) {
                append(""","maxTokens":$maxTokensOverride""")
            }
            append("}")
        }

        val body = bodyJson.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("${configRepository.get().forgeBridge.url}/api/v1/generate")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val started = System.currentTimeMillis()
        val resp = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw ApiCallException(ApiError(
                httpCode = 0,
                message = "Forge Bridge unreachable — is forge-bridge-android running? (${e.message})",
                provider = providerLabel, model = spec.effectiveModel,
            ))
        }

        val responseBody = resp.body?.string() ?: ""
        val durationMs = System.currentTimeMillis() - started

        if (!resp.isSuccessful) {
            val err = parseError(resp, responseBody, providerLabel, spec.effectiveModel)
            callLog.record(ApiCallLogEntry(
                System.currentTimeMillis(), providerLabel, spec.effectiveModel,
                request.url.toString(), resp.code, durationMs,
                errorMessage = err.userFacing(), attempt = 1,
            ))
            throw ApiCallException(err)
        }

        val parsed = json.parseToJsonElement(responseBody).jsonObject
        val content = parsed["content"]?.jsonPrimitive?.contentOrNull
        val resolvedModel = parsed["model"]?.jsonPrimitive?.contentOrNull ?: spec.effectiveModel
        val inTok = parsed["usage"]?.jsonObject?.get("inputTokens")?.jsonPrimitive?.intOrNull ?: 0
        val outTok = parsed["usage"]?.jsonObject?.get("outputTokens")?.jsonPrimitive?.intOrNull ?: 0

        // Tool calls come back from the bridge in OpenAI format regardless of the
        // underlying provider (AnthropicAdapter converts tool_use → OpenAI format).
        val toolCallsRaw = parsed["toolCalls"]?.toString()
        val toolCalls: List<ToolCallResponse> = if (!toolCallsRaw.isNullOrBlank() && toolCallsRaw != "null") {
            runCatching { json.decodeFromString<List<ToolCallResponse>>(toolCallsRaw) }
                .getOrElse { emptyList() }
        } else emptyList()

        callLog.record(ApiCallLogEntry(
            System.currentTimeMillis(), providerLabel, resolvedModel,
            request.url.toString(), resp.code, durationMs,
            inputTokens = inTok, outputTokens = outTok, attempt = 1,
        ))
        costMeter.record(resolvedModel, inTok, outTok, currentCallMode)

        UnifiedResponse(
            content = content,
            toolCalls = toolCalls,
            finishReason = if (toolCalls.isNotEmpty()) "tool_calls" else "stop",
            inputTokens = inTok,
            outputTokens = outTok,
            provider = providerLabel,
            model = resolvedModel,
        )
    }

    // ─── Ping (heartbeat) ────────────────────────────────────────────────────

    fun ping(provider: ApiKeyProvider): Boolean {
        // FORGE_BRIDGE is a localhost server — probe its handshake endpoint instead.
        if (provider == ApiKeyProvider.FORGE_BRIDGE) {
            return try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:8745/api/v1/forge/handshake")
                    .get().build()
                val resp = client.newCall(req).execute()
                resp.close(); resp.isSuccessful
            } catch (_: Exception) { false }
        }
        val key = secureKeyStore.getKey(provider) ?: return false
        return try {
            val request = Request.Builder()
                .url(provider.baseUrl)
                .header("Authorization", "Bearer $key")
                .head().build()
            val resp = client.newCall(request).execute()
            resp.close(); resp.code < 500
        } catch (_: Exception) { false }
    }

    // ─── Auto-routing ────────────────────────────────────────────────────────

    /**
     * Phase M-1 / M-2 — pick a provider for a given mode.
     *
     *  - AGENT mode delegates to [autoRoute] (unchanged behaviour).
     *  - COMPANION mode honours [CompanionRouting.provider]/[model] if set,
     *    otherwise walks the warm-stack `preferredOrder` (Anthropic → Gemini
     *    → OpenAI → Groq by default), picking the first one with a stored
     *    key. Falls back to [autoRoute] if nothing in the preferred list has
     *    a key, so the user never gets stranded with "no provider".
     */
    fun pickProviderForMode(mode: com.forge.os.domain.companion.Mode): ProviderSpec? {
        val routing = configRepository.get().modelRouting
        when (mode) {
            com.forge.os.domain.companion.Mode.AGENT -> return autoRoute()
            com.forge.os.domain.companion.Mode.COMPANION -> {
                val cfg = routing.companion
                if (!cfg.provider.isNullOrBlank()) {
                    val p = runCatching { ApiKeyProvider.valueOf(cfg.provider) }.getOrNull()
                    if (p != null && secureKeyStore.hasKey(p)) return ProviderSpec.Builtin(p, cfg.model)
                    val cep = customEndpoints.list().firstOrNull {
                        secureKeyStore.hasCustomKey(it.id) &&
                                (it.id == cfg.provider || it.name.equals(cfg.provider, ignoreCase = true))
                    }
                    if (cep != null) return ProviderSpec.Custom(cep, cfg.model)
                }
                for (name in cfg.preferredOrder) {
                    val p = runCatching { ApiKeyProvider.valueOf(name) }.getOrNull() ?: continue
                    if (secureKeyStore.hasKey(p)) return ProviderSpec.Builtin(p, cfg.model)
                }
            }
            com.forge.os.domain.companion.Mode.SUMMARIZATION -> {
                val pKey = routing.summarizationProvider
                val model = routing.summarizationModel
                if (!pKey.isNullOrBlank() && !model.isNullOrBlank()) {
                    resolveSpec(pKey, model)?.let { return it }
                }
            }
            com.forge.os.domain.companion.Mode.SYSTEM -> {
                val pKey = routing.systemProvider
                val model = routing.systemModel
                if (!pKey.isNullOrBlank() && !model.isNullOrBlank()) {
                    resolveSpec(pKey, model)?.let { return it }
                }
            }
            com.forge.os.domain.companion.Mode.REFLECTION -> return resolveReflectionSpec()
            com.forge.os.domain.companion.Mode.VISION     -> return resolveVisionSpec()
            com.forge.os.domain.companion.Mode.REASONING  -> return resolveReasoningSpec()
            com.forge.os.domain.companion.Mode.PLANNER    -> {
                val pKey = routing.plannerProvider
                val model = routing.plannerModel
                if (!pKey.isNullOrBlank() && !model.isNullOrBlank()) {
                    resolveSpec(pKey, model)?.let { return it }
                }
            }
        }
        return autoRoute()
    }

    /** Last mode passed to [chat]; read by the call-path cost tagging. */
    @Volatile
    private var currentCallMode: com.forge.os.domain.companion.Mode =
        com.forge.os.domain.companion.Mode.AGENT

    private fun autoRoute(): ProviderSpec? {
        val config = configRepository.get()

        // ── Forge Bridge priority ─────────────────────────────────────────────
        // If the user hasn't pinned a different provider and bridge is active,
        // prefer it as the unified local provider backend.
        val bridgeEnabled = config.forgeBridge.preferBridge &&
            secureKeyStore.hasKey(ApiKeyProvider.FORGE_BRIDGE)
        val userPinnedProvider = try {
            ApiKeyProvider.valueOf(config.modelRouting.defaultProvider)
        } catch (_: Exception) { null }
        if (bridgeEnabled && (userPinnedProvider == null || userPinnedProvider == ApiKeyProvider.FORGE_BRIDGE)) {
            return ProviderSpec.Builtin(ApiKeyProvider.FORGE_BRIDGE, "auto")
        }
        // ─────────────────────────────────────────────────────────────────────

        val preferred = userPinnedProvider
        val provider = when {
            preferred != null && secureKeyStore.hasKey(preferred) -> preferred
            else -> secureKeyStore.getActiveProvider()
        } ?: return null

        val model = if (config.modelRouting.defaultProvider == provider.name)
            config.modelRouting.defaultModel
        else
            config.modelRouting.routingRules
                .firstOrNull { it.provider == provider.name }?.model
                ?: provider.defaultModel
        return ProviderSpec.Builtin(provider, model)
    }

    // ─── Auth resolution ─────────────────────────────────────────────────────

    private data class AuthInfo(
        val key: String, val baseUrl: String,
        val authHeader: String, val authPrefix: String, val label: String
    )

    private fun resolveAuth(spec: ProviderSpec): AuthInfo = when (spec) {
        is ProviderSpec.Builtin -> {
            val k = secureKeyStore.getKey(spec.provider) ?: throw ApiCallException(ApiError(
                httpCode = 0, message = "Key for ${spec.provider.displayName} is missing.",
                provider = spec.provider.displayName, model = spec.effectiveModel
            ))
            val base = if (spec.provider.baseUrl.endsWith('/')) spec.provider.baseUrl
                       else spec.provider.baseUrl + "/"
            // Anthropic native uses x-api-key, but resolveAuth's header fields aren't used
            // by callAnthropic (which sets headers directly). Fine to default.
            AuthInfo(k, base, "Authorization", "Bearer ", spec.provider.displayName)
        }
        is ProviderSpec.Custom -> {
            val k = secureKeyStore.getCustomKey(spec.endpoint.id) ?: throw ApiCallException(ApiError(
                httpCode = 0, message = "Key for custom endpoint '${spec.endpoint.name}' is missing.",
                provider = spec.endpoint.name, model = spec.effectiveModel
            ))
            val base = if (spec.endpoint.baseUrl.endsWith('/')) spec.endpoint.baseUrl
                       else spec.endpoint.baseUrl + "/"
            AuthInfo(k, base, spec.endpoint.authHeader, spec.endpoint.authPrefix, spec.endpoint.name)
        }
    }

    // ─── Retry + execute ─────────────────────────────────────────────────────

    private data class HttpExec(
        val response: Response, val body: String, val durationMs: Long, val attempt: Int
    )

    /** Executes the request once; retries once on 5xx / 429 with 800ms backoff. */
    private suspend fun executeWithRetry(
        request: Request, provider: String, model: String
    ): HttpExec {
        var attempt = 0
        var lastException: Exception? = null
        while (attempt < 2) {
            attempt++
            val started = System.currentTimeMillis()
            try {
                val resp = client.newCall(request).execute()
                val body = resp.body?.string() ?: ""
                val dur = System.currentTimeMillis() - started
                val retriable = resp.code == 429 || resp.code in 500..599
                if (!retriable || attempt >= 2) {
                    return HttpExec(resp, body, dur, attempt)
                }
                Timber.w("Retriable HTTP ${resp.code} from $provider, retrying…")
                resp.close()
                delay(800L * attempt)
            } catch (e: Exception) {
                lastException = e
                callLog.record(ApiCallLogEntry(
                    System.currentTimeMillis(), provider, model,
                    request.url.toString(), 0,
                    System.currentTimeMillis() - started,
                    errorMessage = "Network: ${e.message}", attempt = attempt
                ))
                if (attempt >= 2) {
                    throw ApiCallException(ApiError(
                        httpCode = 0,
                        message = "Network error: ${e.message ?: e::class.simpleName}",
                        provider = provider, model = model
                    ))
                }
                delay(800L * attempt)
            }
        }
        throw ApiCallException(ApiError(
            httpCode = 0,
            message = "Unreachable retry path: ${lastException?.message ?: "unknown"}",
            provider = provider, model = model
        ))
    }

    private fun parseError(
        response: Response, body: String, provider: String, model: String
    ): ApiError {
        val (msg, code) = try {
            val obj = json.decodeFromString<JsonObject>(body)
            val errObj = obj["error"]?.jsonObject
            val msg = errObj?.get("message")?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
                ?: body.take(300)
            val code = errObj?.get("code")?.jsonPrimitive?.content
                ?: errObj?.get("type")?.jsonPrimitive?.content
            msg to code
        } catch (_: Exception) { body.take(300) to null }

        return ApiError(
            httpCode = response.code,
            providerCode = code,
            message = msg,
            provider = provider, model = model,
            requestId = response.header("x-request-id"),
            raw = body.take(400)
        )
    }

    private fun parseJsonToMap(jsonStr: String): Map<String, String> = try {
        val obj = json.decodeFromString<JsonObject>(jsonStr)
        obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
    } catch (_: Exception) { emptyMap() }

    // ─── Live model catalog ──────────────────────────────────────────────────
    //
    // The model picker used to hard-code one model per provider. We now hit the
    // provider's /models endpoint on demand so the dropdown always reflects the
    // user's actual entitlements. Results are cached on disk for 24h to avoid
    // hammering APIs every time the picker opens.

    @Serializable
    private data class CachedCatalog(
        val fetchedAt: Long,
        val ids: List<String>,
    )

    @Serializable
    private data class CatalogFile(
        val entries: Map<String, CachedCatalog> = emptyMap(),
    )

    private val catalogFile: File by lazy {
        File(appContext.filesDir, "workspace/model_catalog.json").apply { parentFile?.mkdirs() }
    }
    private val catalogTtlMs = 24L * 60L * 60L * 1000L

    private fun loadCatalogFile(): CatalogFile = try {
        if (catalogFile.exists()) json.decodeFromString(catalogFile.readText())
        else CatalogFile()
    } catch (_: Exception) { CatalogFile() }

    private fun saveCatalogFile(file: CatalogFile) {
        try { catalogFile.writeText(json.encodeToString(file)) }
        catch (e: Exception) { Timber.w(e, "model catalog save") }
    }

    /**
     * Returns model IDs available for the given provider. Hits the live
     * `/models` (OpenAI-schema) or `/v1/models` (Anthropic) endpoint and
     * caches for 24h. Falls back to the provider's hard-coded default model
     * id if the network call fails or no API key is configured.
     */
    suspend fun listModels(
        provider: ApiKeyProvider,
        forceRefresh: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        // ── Forge Bridge: live fetch from /api/v1/providers ──────────────────
        if (provider == ApiKeyProvider.FORGE_BRIDGE) {
            return@withContext bridgeDiscovery.fetchProviderModels()
        }
        // ────────────────────────────────────────────────────────────────────

        val cacheKey = "builtin:${provider.name}"
        val now = System.currentTimeMillis()
        val cached = loadCatalogFile().entries[cacheKey]
        if (!forceRefresh && cached != null && (now - cached.fetchedAt) < catalogTtlMs) {
            return@withContext cached.ids
        }
        val key = secureKeyStore.getKey(provider) ?: return@withContext listOf(provider.defaultModel)
        val ids = runCatching {
            when (provider.schema) {
                ProviderSchema.OPENAI       -> fetchOpenAiModels(provider.baseUrl, key)
                ProviderSchema.ANTHROPIC    -> fetchAnthropicModels(provider.baseUrl, key)
                ProviderSchema.FORGE_BRIDGE -> emptyList() // handled above
            }
        }.getOrElse {
            Timber.w(it, "listModels failed for ${provider.name}")
            emptyList()
        }
        val resolved = if (ids.isEmpty()) listOf(provider.defaultModel) else ids
        val updated = loadCatalogFile().let { f ->
            f.copy(entries = f.entries + (cacheKey to CachedCatalog(now, resolved)))
        }
        saveCatalogFile(updated)
        resolved
    }

    private fun fetchOpenAiModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val parsed = json.decodeFromString<JsonObject>(body)
            val data = parsed["data"] as? JsonArray ?: return emptyList()
            // Some upstream catalogs (Mistral, OpenRouter aliases, fine-tunes)
            // return the same model id more than once. The model picker uses
            // the id as a LazyColumn key, so duplicates crash the UI with
            // "Key was already used". distinct() removes the duplicates.
            return data.mapNotNull {
                (it as? JsonObject)?.get("id")?.jsonPrimitive?.content
            }.distinct().sorted()
        }
    }

    private fun fetchAnthropicModels(baseUrl: String, apiKey: String): List<String> {
        val url = baseUrl.trimEnd('/') + "/v1/models"
        val req = Request.Builder().url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val parsed = json.decodeFromString<JsonObject>(body)
            val data = parsed["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull {
                (it as? JsonObject)?.get("id")?.jsonPrimitive?.content
            }.distinct().sorted()
        }
    }

    // ─── Gemini tool schema sanitization ─────────────────────────────────────

    /**
     * Gemini's OpenAI-compat layer rejects several JSON-Schema keywords that
     * OpenAI itself happily ignores. We strip them from the serialized tool
     * definitions before sending.
     *
     * Known unsupported keywords:
     *   - additionalProperties
     *   - strict
     *   - $schema
     *   - default (in some contexts)
     */
    private fun sanitizeToolsForGemini(tools: List<ToolDefinition>): List<ToolDefinition> {
        // Rather than deep-walking the Kotlin objects, we serialize → strip → deserialize.
        // This catches nested schemas from MCP/plugin tools that may embed these keywords
        // at any depth.
        return try {
            val serialized = json.encodeToString(tools)
            val cleaned = serialized
                .replace(Regex(""""additionalProperties"\s*:\s*(true|false)\s*,?"""), "")
                .replace(Regex(""""strict"\s*:\s*(true|false)\s*,?"""), "")
                .replace(Regex(""""${'$'}schema"\s*:\s*"[^"]*"\s*,?"""), "")
                // Clean up trailing commas that may have been left behind
                .replace(Regex(",\\s*}"), "}")
                .replace(Regex(",\\s*]"), "]")
            json.decodeFromString<List<ToolDefinition>>(cleaned)
        } catch (e: Exception) {
            Timber.w(e, "sanitizeToolsForGemini: fallback to original tools")
            tools
        }
    }
}
