package com.forge.os.domain.plugins

import com.forge.os.domain.config.ConfigRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Warn(val reason: String) : ValidationResult()
    data class Rejected(val reason: String) : ValidationResult()
}

/**
 * Validates plugin manifests and entrypoint code before installation.
 *
 * Checks:
 *  - id is snake_case, ≤ 64 chars
 *  - version follows semver (loose: x.y.z)
 *  - all requested permissions are known
 *  - dangerous permissions are gated by config.pluginSettings.allowNetworkPlugins / allowUserPlugins
 *  - entrypoint code does not import banned modules
 *  - tool names don't collide with built-in tools
 */
@Singleton
class PluginValidator @Inject constructor(
    private val configRepository: ConfigRepository
) {
    private val idPattern = Regex("^[a-z][a-z0-9_]{2,63}$")
    private val versionPattern = Regex("^\\d+\\.\\d+(\\.\\d+)?(-[a-z0-9]+)?$")

    private val bannedImports = setOf(
        "ctypes", "subprocess", "socket.socketpair", "os.fork",
        "android.os.Debug", "java.lang.Runtime"
    )

    private val builtinToolNames = setOf(
        "file_read", "file_write", "file_list", "file_delete",
        "shell_exec", "python_run", "workspace_info",
        "config_read", "config_write", "config_rollback",
        "memory_store", "memory_recall", "memory_store_skill", "memory_summary",
        "cron_add", "cron_list", "cron_remove", "cron_run_now", "cron_history",
        "heartbeat_check",
        "plugin_list", "plugin_install", "plugin_uninstall", "plugin_execute"
    )

    fun validate(manifest: PluginManifest, entrypointCode: String): ValidationResult {
        val cfg = configRepository.get().pluginSettings

        if (!cfg.allowUserPlugins && manifest.source == "user") {
            return ValidationResult.Rejected("User plugins are disabled in config")
        }
        if (!idPattern.matches(manifest.id)) {
            return ValidationResult.Rejected("Plugin id must be snake_case (got '${manifest.id}')")
        }
        if (!versionPattern.matches(manifest.version)) {
            return ValidationResult.Rejected("Invalid version: ${manifest.version}")
        }
        if (manifest.tools.isEmpty()) {
            return ValidationResult.Rejected("Plugin declares no tools")
        }

        // Permissions
        val unknown = manifest.permissions - PluginPermissions.ALL
        if (unknown.isNotEmpty()) {
            return ValidationResult.Rejected("Unknown permission(s): ${unknown.joinToString()}")
        }
        if (PluginPermissions.NETWORK in manifest.permissions && !cfg.allowNetworkPlugins) {
            return ValidationResult.Rejected("Network permission denied (allowNetworkPlugins=false)")
        }

        // Tool name collisions
        manifest.tools.forEach { t ->
            if (t.name in builtinToolNames) {
                return ValidationResult.Rejected("Tool '${t.name}' collides with a built-in tool")
            }
            if (!Regex("^[a-z][a-z0-9_]{2,63}$").matches(t.name)) {
                return ValidationResult.Rejected("Invalid tool name: ${t.name}")
            }
        }

        // Code size limit
        val maxBytes = cfg.maxPluginSizeMb * 1024 * 1024
        if (entrypointCode.toByteArray().size > maxBytes) {
            return ValidationResult.Rejected("Plugin code exceeds ${cfg.maxPluginSizeMb}MB")
        }

        // Banned imports (cheap textual scan; not a full sandbox)
        bannedImports.forEach { banned ->
            if (Regex("(?m)^\\s*(?:import|from)\\s+${Regex.escape(banned)}").containsMatchIn(entrypointCode)) {
                return ValidationResult.Rejected("Banned import: $banned")
            }
        }

        // SHA-256 integrity (if declared in manifest)
        if (manifest.sha256.isNotBlank()) {
            val actual = sha256Hex(entrypointCode.toByteArray())
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                return ValidationResult.Rejected("SHA-256 mismatch (manifest=${manifest.sha256.take(10)}…, actual=${actual.take(10)}…)")
            }
        }

        // Ed25519 signature (if declared) — verified via JCA. If algorithm absent on device, skip.
        if (manifest.signature.isNotBlank() && manifest.publicKey.isNotBlank()) {
            val r = runCatching { verifyEd25519(entrypointCode.toByteArray(), manifest.signature, manifest.publicKey) }
            r.onFailure { return ValidationResult.Warn("Signature could not be verified: ${it.message}") }
            if (r.getOrDefault(false) == false) return ValidationResult.Rejected("Bad Ed25519 signature")
        } else if (manifest.signature.isBlank()) {
            return ValidationResult.Warn("Plugin is unsigned — install only if you trust the source.")
        }

        return ValidationResult.Ok
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun verifyEd25519(payload: ByteArray, sigB64: String, pubB64: String): Boolean {
        val sig = java.util.Base64.getDecoder().decode(sigB64)
        val pub = java.util.Base64.getDecoder().decode(pubB64)
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
        val pubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(pub))
        val verifier = java.security.Signature.getInstance("Ed25519")
        verifier.initVerify(pubKey)
        verifier.update(payload)
        return verifier.verify(sig)
    }
}
