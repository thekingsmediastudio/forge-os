package com.forge.os.domain.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CustomEndpoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,           // must end with `/`
    val schema: ProviderSchema = ProviderSchema.OPENAI,
    val defaultModel: String,
    val authHeader: String = "Authorization",   // some gateways use a different header
    val authPrefix: String = "Bearer "
)

/** JSON-file persistence for user-defined endpoints. Keys live in [SecureKeyStore]. */
@Singleton
class CustomEndpointRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(context.filesDir, "workspace/system/custom_endpoints.json").apply {
        parentFile?.mkdirs()
    }

    private val json = Json {
        prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true
    }

    private val _endpoints = MutableStateFlow(load())
    val endpoints: StateFlow<List<CustomEndpoint>> = _endpoints

    private fun load(): List<CustomEndpoint> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    } catch (e: Exception) {
        Timber.w(e, "custom_endpoints.json corrupted, ignoring")
        emptyList()
    }

    private fun persist() {
        try { file.writeText(json.encodeToString(_endpoints.value)) }
        catch (e: Exception) { Timber.e(e, "Failed to persist custom endpoints") }
    }

    fun add(e: CustomEndpoint): CustomEndpoint {
        val normalized = e.copy(baseUrl = if (e.baseUrl.endsWith('/')) e.baseUrl else e.baseUrl + "/")
        _endpoints.value = _endpoints.value + normalized
        persist(); return normalized
    }

    fun update(e: CustomEndpoint) {
        _endpoints.value = _endpoints.value.map { if (it.id == e.id) e else it }
        persist()
    }

    fun delete(id: String) {
        _endpoints.value = _endpoints.value.filter { it.id != id }
        persist()
    }

    fun get(id: String): CustomEndpoint? = _endpoints.value.firstOrNull { it.id == id }

    fun list(): List<CustomEndpoint> = _endpoints.value
}
