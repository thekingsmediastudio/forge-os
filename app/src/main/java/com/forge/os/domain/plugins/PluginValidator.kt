package com.forge.os.domain.plugins

import com.forge.os.domain.config.ConfigRepository
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val configRepository: ConfigRepository,
    // Enhanced Integration: Connect with learning systems (Lazy to break circular dependency)
    private val reflectionManager: dagger.Lazy<com.forge.os.domain.agent.ReflectionManager>,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
        val validationStart = System.currentTimeMillis()

        // Enhanced Integration: Learn plugin validation patterns
        try {
            userPreferencesManager.recordInteractionPattern("validates_plugins", 1)
            userPreferencesManager.recordInteractionPattern("plugin_source_${manifest.source}", 1)
            
            // Learn permission patterns
            manifest.permissions.forEach { permission ->
                userPreferencesManager.recordInteractionPattern("plugin_uses_$permission", 1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record plugin validation patterns")
        }

        if (!cfg.allowUserPlugins && manifest.source == "user") {
            recordValidationResult(manifest, ValidationResult.Rejected("User plugins are disabled in config"), validationStart)
            return ValidationResult.Rejected("User plugins are disabled in config")
        }
        if (!idPattern.matches(manifest.id)) {
            recordValidationResult(manifest, ValidationResult.Rejected("Plugin id must be snake_case (got '${manifest.id}')"), validationStart)
            return ValidationResult.Rejected("Plugin id must be snake_case (got '${manifest.id}')")
        }
        if (!versionPattern.matches(manifest.version)) {
            recordValidationResult(manifest, ValidationResult.Rejected("Invalid version: ${manifest.version}"), validationStart)
            return ValidationResult.Rejected("Invalid version: ${manifest.version}")
        }
        if (manifest.tools.isEmpty()) {
            recordValidationResult(manifest, ValidationResult.Rejected("Plugin declares no tools"), validationStart)
            return ValidationResult.Rejected("Plugin declares no tools")
        }

        // Permissions
        val unknown = manifest.permissions - PluginPermissions.ALL
        if (unknown.isNotEmpty()) {
            recordValidationResult(manifest, ValidationResult.Rejected("Unknown permission(s): ${unknown.joinToString()}"), validationStart)
            return ValidationResult.Rejected("Unknown permission(s): ${unknown.joinToString()}")
        }
        if (PluginPermissions.NETWORK in manifest.permissions && !cfg.allowNetworkPlugins) {
            recordValidationResult(manifest, ValidationResult.Rejected("Network permission denied (allowNetworkPlugins=false)"), validationStart)
            return ValidationResult.Rejected("Network permission denied (allowNetworkPlugins=false)")
        }

        // Tool name collisions
        manifest.tools.forEach { t ->
            if (t.name in builtinToolNames) {
                recordValidationResult(manifest, ValidationResult.Rejected("Tool '${t.name}' collides with a built-in tool"), validationStart)
                return ValidationResult.Rejected("Tool '${t.name}' collides with a built-in tool")
            }
            if (!Regex("^[a-z][a-z0-9_]{2,63}$").matches(t.name)) {
                recordValidationResult(manifest, ValidationResult.Rejected("Invalid tool name: ${t.name}"), validationStart)
                return ValidationResult.Rejected("Invalid tool name: ${t.name}")
            }
        }

        // Code size limit
        val maxBytes = cfg.maxPluginSizeMb * 1024 * 1024
        if (entrypointCode.toByteArray().size > maxBytes) {
            recordValidationResult(manifest, ValidationResult.Rejected("Plugin code exceeds ${cfg.maxPluginSizeMb}MB"), validationStart)
            return ValidationResult.Rejected("Plugin code exceeds ${cfg.maxPluginSizeMb}MB")
        }

        // Banned imports (cheap textual scan; not a full sandbox)
        bannedImports.forEach { banned ->
            if (Regex("(?m)^\\s*(?:import|from)\\s+${Regex.escape(banned)}").containsMatchIn(entrypointCode)) {
                recordValidationResult(manifest, ValidationResult.Rejected("Banned import: $banned"), validationStart)
                return ValidationResult.Rejected("Banned import: $banned")
            }
        }

        // SHA-256 integrity (if declared in manifest)
        if (manifest.sha256.isNotBlank()) {
            val actual = sha256Hex(entrypointCode.toByteArray())
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                recordValidationResult(manifest, ValidationResult.Rejected("SHA-256 mismatch (manifest=${manifest.sha256.take(10)}…, actual=${actual.take(10)}…)"), validationStart)
                return ValidationResult.Rejected("SHA-256 mismatch (manifest=${manifest.sha256.take(10)}…, actual=${actual.take(10)}…)")
            }
        }

        // Ed25519 signature (if declared) — verified via JCA. If algorithm absent on device, skip.
        if (manifest.signature.isNotBlank() && manifest.publicKey.isNotBlank()) {
            val r = runCatching { verifyEd25519(entrypointCode.toByteArray(), manifest.signature, manifest.publicKey) }
            r.onFailure { 
                recordValidationResult(manifest, ValidationResult.Warn("Signature could not be verified: ${it.message}"), validationStart)
                return ValidationResult.Warn("Signature could not be verified: ${it.message}") 
            }
            if (r.getOrDefault(false) == false) {
                recordValidationResult(manifest, ValidationResult.Rejected("Bad Ed25519 signature"), validationStart)
                return ValidationResult.Rejected("Bad Ed25519 signature")
            }
        } else if (manifest.signature.isBlank()) {
            recordValidationResult(manifest, ValidationResult.Warn("Plugin is unsigned — install only if you trust the source."), validationStart)
            return ValidationResult.Warn("Plugin is unsigned — install only if you trust the source.")
        }

        recordValidationResult(manifest, ValidationResult.Ok, validationStart)
        return ValidationResult.Ok
    }
    
    private fun recordValidationResult(manifest: PluginManifest, result: ValidationResult, startTime: Long) {
        scope.launch {
            try {
                val duration = System.currentTimeMillis() - startTime
                
                when (result) {
                    is ValidationResult.Ok -> {
                        reflectionManager.get().recordPattern(
                            pattern = "Plugin validation success",
                            description = "Successfully validated plugin '${manifest.id}' v${manifest.version} with ${manifest.tools.size} tools in ${duration}ms",
                            applicableTo = listOf("plugin_validation", "security", manifest.source),
                            tags = listOf("validation_success", "plugin_security", "code_review")
                        )
                    }
                    is ValidationResult.Warn -> {
                        reflectionManager.get().recordPattern(
                            pattern = "Plugin validation warning",
                            description = "Plugin '${manifest.id}' validated with warning: ${result.reason}",
                            applicableTo = listOf("plugin_validation", "security_warning", manifest.source),
                            tags = listOf("validation_warning", "plugin_security", "code_review")
                        )
                    }
                    is ValidationResult.Rejected -> {
                        reflectionManager.get().recordFailureAndRecovery(
                            taskId = "plugin_validation_${manifest.id}_${System.currentTimeMillis()}",
                            failureReason = "Plugin validation failed: ${result.reason}",
                            recoveryStrategy = "Fix plugin code, manifest, or configuration to meet security requirements",
                            tags = listOf("validation_failure", "plugin_security", "code_review", manifest.source)
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to record plugin validation result")
            }
        }
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
