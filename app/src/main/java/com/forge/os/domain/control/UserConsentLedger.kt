package com.forge.os.domain.control

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — records explicit user grants that allow the agent to mutate
 * sensitive capabilities (security toggles, hardware powers, proactive mode,
 * etc.). The agent itself can never write to this ledger; entries are added
 * by [grantFromUser] which is only called from chat / UI paths after parsing
 * an unambiguous user instruction.
 *
 * Each grant has an optional TTL. A "permanent" grant has ttlMs == 0.
 */
@Serializable
data class ConsentGrant(
    val capabilityId: String,
    val grantedAtMs: Long,
    val ttlMs: Long,
    val source: String,
    val note: String = "",
)

@Serializable
private data class LedgerFile(val grants: List<ConsentGrant> = emptyList())

@Singleton
class UserConsentLedger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val file: File
        get() = context.filesDir.resolve("workspace/control/consent_ledger.json").apply {
            parentFile?.mkdirs()
        }

    @Volatile private var cache: List<ConsentGrant> = load()

    private fun load(): List<ConsentGrant> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<LedgerFile>(file.readText()).grants
    }.getOrDefault(emptyList())

    private fun persist(list: List<ConsentGrant>) {
        cache = list
        runCatching { file.writeText(json.encodeToString(LedgerFile(list))) }
            .onFailure { Timber.w(it, "ConsentLedger persist failed") }
    }

    @Synchronized
    fun grantFromUser(capabilityId: String, ttlMs: Long = 0L, source: String = "chat", note: String = ""): ConsentGrant {
        val g = ConsentGrant(capabilityId, System.currentTimeMillis(), ttlMs, source, note)
        persist((cache.filterNot { it.capabilityId == capabilityId } + g))
        Timber.i("ConsentLedger: granted $capabilityId (ttlMs=$ttlMs source=$source)")
        return g
    }

    @Synchronized
    fun revoke(capabilityId: String): Boolean {
        val before = cache.size
        persist(cache.filterNot { it.capabilityId == capabilityId })
        return cache.size != before
    }

    fun isGranted(capabilityId: String): Boolean {
        val g = cache.firstOrNull { it.capabilityId == capabilityId } ?: return false
        if (g.ttlMs <= 0L) return true
        return (System.currentTimeMillis() - g.grantedAtMs) < g.ttlMs
    }

    fun all(): List<ConsentGrant> = cache.toList()

    @Synchronized
    fun clearAll() { persist(emptyList()) }
}
