package com.forge.os.data.api

import com.forge.os.domain.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val BRIDGE_BASE = "http://127.0.0.1:8745"
private const val HANDSHAKE_PATH = "/api/v1/forge/handshake"
private const val PROVIDERS_PATH = "/api/v1/providers"
private const val CACHE_TTL_MS = 30_000L

data class BridgeHandshake(
    val available: Boolean,
    val version: String = "",
    val connectedProviders: List<BridgeProviderInfo> = emptyList(),
    val uptimeSeconds: Int = 0,
    val capabilities: List<String> = emptyList(),
)

data class BridgeProviderInfo(
    val id: String,
    val name: String,
    val status: String,
    val isDefault: Boolean,
    val models: List<String> = emptyList(),
)

/**
 * Discovers whether forge-bridge-android is running on localhost:8745 and
 * manages its activation state in [SecureKeyStore].
 *
 * - Call [probe] once at startup (ForgeApplication.onCreate) to auto-activate.
 * - Call [isAvailable] for a cached fast-path check (refreshes every 30 s).
 * - Call [connectedProviders] to enumerate what the bridge currently has connected.
 */
@Singleton
class ForgeBridgeDiscovery @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastHandshake: BridgeHandshake = BridgeHandshake(available = false)
    private val lastProbeAt = AtomicLong(0L)
    private val probeInFlight = AtomicBoolean(false)

    /**
     * Cached availability — true if the last probe within the TTL window succeeded.
     * Triggers a background refresh if the cache is stale.
     */
    val isAvailable: Boolean
        get() = lastHandshake.available

    /**
     * Returns the most recently cached list of connected providers.
     * Call [probe] to refresh.
     */
    fun connectedProviders(): List<BridgeProviderInfo> = lastHandshake.connectedProviders

    /**
     * Actively pings forge-bridge-android's handshake endpoint.
     * On success: activates FORGE_BRIDGE in SecureKeyStore so autoRoute picks it up.
     * On failure: deactivates FORGE_BRIDGE so ForgeOS falls back to direct providers.
     *
     * Idempotent — safe to call multiple times; respects CACHE_TTL_MS.
     */
    suspend fun probe(): BridgeHandshake = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastProbeAt.get() < CACHE_TTL_MS && lastHandshake.available) {
            return@withContext lastHandshake
        }
        if (!probeInFlight.compareAndSet(false, true)) return@withContext lastHandshake
        try {
            val handshake = fetchHandshake()
            lastHandshake = handshake
            lastProbeAt.set(now)
            if (handshake.available) {
                secureKeyStore.activateForgeBridge()
                Timber.i("ForgeBridge discovered — v${handshake.version} — " +
                    "${handshake.connectedProviders.size} provider(s) connected")
            } else {
                secureKeyStore.deactivateForgeBridge()
                Timber.d("ForgeBridge not reachable — disabling provider")
            }
            handshake
        } catch (e: Exception) {
            Timber.d("ForgeBridge probe failed: ${e.message}")
            val result = BridgeHandshake(available = false)
            lastHandshake = result
            lastProbeAt.set(now)
            secureKeyStore.deactivateForgeBridge()
            result
        } finally {
            probeInFlight.set(false)
        }
    }

    /**
     * Invalidates the cache so the next [isAvailable] check triggers a fresh probe.
     * Call this when the user toggles the bridge setting.
     */
    fun invalidateCache() {
        lastProbeAt.set(0L)
    }

    /**
     * Fetches the list of providers from the bridge and returns available model IDs
     * across all connected providers. Used by AiApiManager.listModels(FORGE_BRIDGE).
     */
    suspend fun fetchProviderModels(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder().url("$BRIDGE_BASE$PROVIDERS_PATH").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val obj = json.parseToJsonElement(body).jsonObject
                val providers = obj["providers"]?.jsonArray ?: return@withContext emptyList()
                val models = mutableListOf<String>()
                for (p in providers) {
                    val pObj = p.jsonObject
                    if (pObj["status"]?.jsonPrimitive?.contentOrNull != "connected") continue
                    val mStr = pObj["models"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (mStr.isNotBlank() && mStr != "[]") {
                        mStr.trim().removePrefix("[").removeSuffix("]")
                            .split(",")
                            .map { it.trim().removeSurrounding("\"") }
                            .filter { it.isNotBlank() }
                            .forEach { models.add(it) }
                    }
                }
                if (models.isEmpty()) listOf("auto") else models.distinct().sorted()
            }
        } catch (e: Exception) {
            Timber.w("ForgeBridge.fetchProviderModels: ${e.message}")
            listOf("auto")
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun fetchHandshake(): BridgeHandshake {
        val req = Request.Builder().url("$BRIDGE_BASE$HANDSHAKE_PATH").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return BridgeHandshake(available = false)
            val body = resp.body?.string() ?: return BridgeHandshake(available = false)
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrNull() ?: return BridgeHandshake(available = false)

            val isBridge = obj["forgeBridge"]?.jsonPrimitive?.boolean ?: false
            if (!isBridge) return BridgeHandshake(available = false)

            val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: ""
            val uptimeSeconds = obj["uptimeSeconds"]?.jsonPrimitive?.intOrNull ?: 0
            val caps = obj["capabilities"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

            val providers = obj["providers"]?.jsonArray?.mapNotNull { p ->
                runCatching {
                    val pObj = p.jsonObject
                    BridgeProviderInfo(
                        id = pObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        name = pObj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = pObj["status"]?.jsonPrimitive?.contentOrNull ?: "disconnected",
                        isDefault = pObj["isDefault"]?.jsonPrimitive?.boolean ?: false,
                    )
                }.getOrNull()
            } ?: emptyList()

            return BridgeHandshake(
                available = true,
                version = version,
                connectedProviders = providers,
                uptimeSeconds = uptimeSeconds,
                capabilities = caps,
            )
        }
    }
}
