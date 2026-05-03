package com.forge.os.external

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the list of external callers and exposes a flow for the UI.
 *
 * Concurrency: synchronous read/write under a monitor; this is small JSON, not hot.
 */
@Singleton
class ExternalCallerRegistry @Inject constructor(
    private val context: Context,
) {
    private val file: File by lazy {
        File(context.filesDir, "workspace/system").apply { mkdirs() }
            .resolve("external_callers.json")
    }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _callers = MutableStateFlow(load())
    val callers: StateFlow<List<ExternalCaller>> = _callers.asStateFlow()

    fun list(): List<ExternalCaller> = _callers.value
    fun get(pkg: String): ExternalCaller? = _callers.value.firstOrNull { it.packageName == pkg }

    @Synchronized
    fun upsert(caller: ExternalCaller) {
        val updated = (_callers.value.filterNot { it.packageName == caller.packageName }) + caller
        _callers.value = updated.sortedBy { it.packageName }
        persist()
    }

    @Synchronized
    fun remove(pkg: String) {
        _callers.value = _callers.value.filterNot { it.packageName == pkg }
        persist()
    }

    @Synchronized
    fun setStatus(pkg: String, status: GrantStatus) {
        get(pkg)?.let { upsert(it.copy(status = status)) }
    }

    @Synchronized
    fun setCapabilities(pkg: String, caps: Capabilities) {
        get(pkg)?.let { upsert(it.copy(capabilities = caps)) }
    }

    @Synchronized
    fun setRateLimit(pkg: String, rl: RateLimit) {
        get(pkg)?.let { upsert(it.copy(rateLimit = rl)) }
    }

    @Synchronized
    fun touch(pkg: String) {
        get(pkg)?.let { upsert(it.copy(lastUsed = System.currentTimeMillis())) }
    }

    /** Build a fresh PENDING entry from a UID, looking up package + signing cert. */
    fun observe(uid: Int): ExternalCaller? {
        val pm = context.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return null
        val pkg = pkgs.firstOrNull() ?: return null
        val existing = get(pkg)
        if (existing != null) return existing
        val display = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val cert = readSigningCertSha256(pkg)
        val caller = ExternalCaller(
            packageName = pkg,
            displayName = display,
            signingCertSha256 = cert,
            status = GrantStatus.PENDING,
        )
        upsert(caller)
        return caller
    }

    private fun readSigningCertSha256(pkg: String): String = runCatching {
        val pm = context.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            } ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        val first = sigs.firstOrNull() ?: return@runCatching ""
        val md = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        md.joinToString(":") { "%02X".format(it) }
    }.getOrDefault("")

    private fun load(): List<ExternalCaller> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString(ListSerializer(ExternalCaller.serializer()), file.readText())
    }.onFailure { Timber.w(it, "ExternalCallerRegistry: load failed") }
        .getOrDefault(emptyList())

    private fun persist() {
        runCatching {
            file.writeText(json.encodeToString(ListSerializer(ExternalCaller.serializer()), _callers.value))
        }.onFailure { Timber.e(it, "ExternalCallerRegistry: persist failed") }
    }
}
