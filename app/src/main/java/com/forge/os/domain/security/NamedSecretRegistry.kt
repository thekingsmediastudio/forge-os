package com.forge.os.domain.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Metadata for a user-registered named secret. The actual secret value
 * lives in [SecureKeyStore] under the synthetic key `named_<name>` —
 * this object is safe to serialise / show in the UI / hand to the agent.
 *
 * @param authStyle one of `bearer`, `header`, `query`. Drives how the value
 *                  is attached to outbound HTTP requests in [secret_request].
 * @param headerName header name for `header` style (default `Authorization`).
 * @param queryParam query parameter name for `query` style (default `key`).
 */
@Serializable
data class NamedSecret(
    val name: String,
    val description: String = "",
    val authStyle: String = "bearer",
    val headerName: String = "Authorization",
    val queryParam: String = "key",
)

/**
 * Phase T addition — a small registry of user-supplied API keys that the
 * agent can reference *by name* without ever seeing the raw secret. The
 * `secret_list` tool returns names + descriptions only, and `secret_request`
 * performs an HTTP call where Forge attaches the value at send time.
 *
 * Storage layout:
 *  - `<filesDir>/workspace/system/named_secrets.json` — JSON list of
 *    [NamedSecret] metadata objects.
 *  - The secret value itself is encrypted via [SecureKeyStore] under
 *    `named_<sanitisedName>` so it inherits the same Android-Keystore
 *    AES-256-GCM treatment as the built-in API keys.
 */
@Singleton
class NamedSecretRegistry @Inject constructor(
    @ApplicationContext context: Context,
    private val keyStore: SecureKeyStore,
) {
    private val file: File =
        File(context.filesDir, "workspace/system/named_secrets.json").apply {
            parentFile?.mkdirs()
        }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<NamedSecret>> = _items.asStateFlow()

    /** True iff [name] (after sanitisation) is currently registered. */
    fun has(name: String): Boolean = get(name) != null

    fun get(name: String): NamedSecret? {
        val key = sanitize(name)
        return _items.value.firstOrNull { it.name == key }
    }

    /** Returns the raw secret value, or null if missing. Callers should
     *  treat the result as sensitive — never log it, never echo it. */
    fun getValue(name: String): String? = keyStore.getCustomKey(storageKey(name))

    /** Insert or update a named secret + its value. Names are normalised
     *  to `[a-z0-9_]+`; whitespace and punctuation become underscores. */
    fun save(secret: NamedSecret, value: String) {
        val normalisedName = sanitize(secret.name)
        if (normalisedName.isBlank()) return
        val cleaned = secret.copy(
            name = normalisedName,
            authStyle = when (secret.authStyle.lowercase()) {
                "bearer", "header", "query" -> secret.authStyle.lowercase()
                else -> "bearer"
            },
        )
        val updated = (_items.value.filter { it.name != normalisedName } + cleaned)
            .sortedBy { it.name }
        _items.value = updated
        persist()
        if (value.isNotBlank()) keyStore.saveCustomKey(storageKey(normalisedName), value)
    }

    fun delete(name: String) {
        val normalised = sanitize(name)
        _items.value = _items.value.filter { it.name != normalised }
        persist()
        keyStore.deleteCustomKey(storageKey(normalised))
    }

    fun list(): List<NamedSecret> = _items.value

    private fun storageKey(name: String) = "named_${sanitize(name)}"

    private fun sanitize(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')

    private fun load(): List<NamedSecret> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<NamedSecret>>(file.readText())
        }.onFailure { Timber.w(it, "NamedSecretRegistry: failed to read $file") }
            .getOrDefault(emptyList())
    }

    private fun persist() {
        runCatching {
            file.writeText(json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(NamedSecret.serializer()),
                _items.value,
            ))
        }.onFailure { Timber.w(it, "NamedSecretRegistry: failed to write $file") }
    }
}
