package com.forge.os.data.sandbox

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 0 hardcoded SecurityPolicy, refactored in Phase Q so that each guard
 * can be turned off via the [com.forge.os.domain.control.AgentControlPlane].
 *
 * The actual flag is read through a small [Flags] interface to avoid a
 * circular DI between sandbox and the control plane (the control plane
 * eventually depends on configuration which depends on sandbox).
 *
 * Default behaviour (no flags wired) is identical to the original Phase 0
 * implementation: all guards are ON.
 */
@Singleton
class SecurityPolicy @Inject constructor(
    // Enhanced Integration: Connect with learning systems
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    interface Flags {
        fun shellBlocklistEnabled(): Boolean
        fun pythonImportGuardEnabled(): Boolean
        fun fileProtectionEnabled(): Boolean
        fun fileSizeLimitEnabled(): Boolean
    }

    @Volatile private var flags: Flags = DEFAULT_ON

    /** Wired by the AppModule once the AgentControlPlane is constructed. */
    fun setFlagsProvider(flags: Flags) { this.flags = flags }

    private val blockedShellPatterns = listOf(
        Regex("""rm\s+-rf\s+/\s*""", RegexOption.IGNORE_CASE),
        Regex("""rm\s+-rf\s+/\.\.\s*""", RegexOption.IGNORE_CASE),
        Regex("""mkfs\.""", RegexOption.IGNORE_CASE),
        Regex("""dd\s+if=/dev/zero""", RegexOption.IGNORE_CASE),
        Regex("""curl\s+""", RegexOption.IGNORE_CASE),
        Regex("""wget\s+""", RegexOption.IGNORE_CASE),
        Regex("""nc\s+""", RegexOption.IGNORE_CASE),
        Regex("""netcat\s+""", RegexOption.IGNORE_CASE),
        Regex("""ssh\s+""", RegexOption.IGNORE_CASE),
        Regex("""su\s+""", RegexOption.IGNORE_CASE),
        Regex("""sudo\s+""", RegexOption.IGNORE_CASE),
        Regex("""chroot\s+""", RegexOption.IGNORE_CASE),
        Regex("""mknod\s+""", RegexOption.IGNORE_CASE),
        Regex(""">/dev/""", RegexOption.IGNORE_CASE),
        Regex("""\|\s*sh""", RegexOption.IGNORE_CASE),
        Regex("""\|\s*bash""", RegexOption.IGNORE_CASE),
        Regex("""base64\s+-d""", RegexOption.IGNORE_CASE),
        Regex("""eval\s*\(""", RegexOption.IGNORE_CASE),
        Regex(""":\(\)\{\s*:\|:\&\s*\};:""", RegexOption.IGNORE_CASE)
    )

    private val blockedPythonImports = setOf(
        "socket", "subprocess", "urllib.request", "http.client",
        "ftplib", "smtplib", "telnetlib", "ssl", "ctypes", "mmap",
        "multiprocessing", "concurrent.futures", "asyncio",
        "os.system", "os.popen", "platform", "sysconfig"
    )

    private val blockedFileNames = setOf(
        "AndroidManifest.xml", "build.gradle", "settings.gradle",
        "gradle.properties", "local.properties"
    )

    private val protectedDirectories = setOf(".snapshots", ".plugins", ".git")

    private val blockedIpRanges = listOf(
        "127.", "0.", "10.", "172.16.", "172.17.", "172.18.", "172.19.",
        "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
        "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
        "192.168.", "169.254.", "::1", "fe80:", "fc00:", "fd00:"
    )

    fun validateShellCommand(command: String) {
        if (!flags.shellBlocklistEnabled()) return
        
        // Enhanced Integration: Learn shell command patterns
        try {
            userPreferencesManager.recordInteractionPattern("uses_shell_commands", 1)
        } catch (e: Exception) {
            Timber.w(e, "Failed to record shell command patterns")
        }
        
        // Improve regex to catch obfuscated paths like /"etc"/passwd or /\.\./
        for (pattern in blockedShellPatterns) {
            if (pattern.containsMatchIn(command)) {
                // Enhanced Integration: Record security violations
                scope.launch {
                    try {
                        reflectionManager.recordFailureAndRecovery(
                            taskId = "security_violation_shell_${System.currentTimeMillis()}",
                            failureReason = "Blocked dangerous shell command: ${command.take(100)}",
                            recoveryStrategy = "Use safer alternatives or modify command to avoid blocked patterns",
                            tags = listOf("security_violation", "shell_command", "blocked_pattern", "safety")
                        )
                        
                        userPreferencesManager.recordInteractionPattern("triggers_security_blocks", 1)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to record security violation")
                    }
                }
                
                throw SecurityException("Blocked command pattern detected in: $command")
            }
        }
        
        // Enhanced Integration: Record safe shell command usage
        scope.launch {
            try {
                reflectionManager.recordPattern(
                    pattern = "Safe shell command executed",
                    description = "Shell command passed security validation: ${command.take(50)}",
                    applicableTo = listOf("shell_security", "command_validation", "safe_execution"),
                    tags = listOf("security_success", "shell_command", "safe_execution")
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record safe shell command pattern")
            }
        }
    }

    fun validateUrl(url: String) {
        // Enhanced Integration: Learn URL access patterns
        try {
            userPreferencesManager.recordInteractionPattern("accesses_urls", 1)
            
            val domain = try { 
                java.net.URI(url).host?.lowercase() 
            } catch (e: Exception) { 
                null 
            }
            if (domain != null) {
                userPreferencesManager.recordInteractionPattern("accesses_domain_$domain", 1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record URL access patterns")
        }
        
        val uri = try { java.net.URI(url) } catch (e: Exception) { 
            // Enhanced Integration: Record URL validation failures
            scope.launch {
                try {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "url_validation_${System.currentTimeMillis()}",
                        failureReason = "Invalid URL format: $url",
                        recoveryStrategy = "Provide a valid URL with proper protocol and format",
                        tags = listOf("url_validation", "format_error", "security")
                    )
                } catch (e2: Exception) {
                    Timber.w(e2, "Failed to record URL validation failure")
                }
            }
            throw SecurityException("Invalid URL: $url") 
        }
        val host = uri.host?.lowercase() ?: throw SecurityException("URL missing host: $url")
        
        // 1. Block known local API port if host is local
        if ((host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") && uri.port == 8789) {
            // Enhanced Integration: Record local API access attempts
            scope.launch {
                try {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "local_api_block_${System.currentTimeMillis()}",
                        failureReason = "Blocked access to local API server: $url",
                        recoveryStrategy = "Use external APIs or configure proper authentication",
                        tags = listOf("security_violation", "local_api_access", "blocked_access")
                    )
                    
                    userPreferencesManager.recordInteractionPattern("attempts_local_api_access", 1)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record local API access attempt")
                }
            }
            
            throw SecurityException("Access to local API server is prohibited via tools")
        }

        // 2. Block private IP ranges (basic check, could be improved with proper IP parsing)
        if (blockedIpRanges.any { host.startsWith(it) }) {
            // Enhanced Integration: Record private network access attempts
            scope.launch {
                try {
                    reflectionManager.recordFailureAndRecovery(
                        taskId = "private_network_block_${System.currentTimeMillis()}",
                        failureReason = "Blocked access to private network: $host",
                        recoveryStrategy = "Use public endpoints or configure proper network access",
                        tags = listOf("security_violation", "private_network_access", "blocked_access")
                    )
                    
                    userPreferencesManager.recordInteractionPattern("attempts_private_network_access", 1)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to record private network access attempt")
                }
            }
            
            throw SecurityException("Access to private/local network addresses is prohibited: $host")
        }
        
        // Enhanced Integration: Record safe URL access
        scope.launch {
            try {
                reflectionManager.recordPattern(
                    pattern = "Safe URL access validated",
                    description = "URL passed security validation: $host",
                    applicableTo = listOf("url_security", "network_access", "safe_browsing"),
                    tags = listOf("security_success", "url_validation", "safe_access")
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record safe URL access pattern")
            }
        }
    }

    fun validateWrite(file: File, size: Long) {
        if (flags.fileSizeLimitEnabled() && size > 10 * 1024 * 1024) {
            throw SecurityException("File size exceeds 10MB limit")
        }
        if (flags.fileProtectionEnabled() && file.name in blockedFileNames) {
            throw SecurityException("Cannot modify protected file: ${file.name}")
        }
    }

    fun validateDelete(file: File) {
        if (!file.exists()) throw NoSuchFileException(file)
        
        // Protect system directories
        val relPath = file.path // This should be workspace-relative if possible
        if (protectedDirectories.any { file.absolutePath.contains("/$it/") || file.absolutePath.endsWith("/$it") }) {
            throw SecurityException("Cannot delete protected system directory/file: ${file.name}")
        }

        val parent = file.parentFile
        if (parent == null || parent.parentFile == null) {
            throw SecurityException("Cannot delete protected directory")
        }
    }

    // Robust sanitization is now handled via Python AST check in python_runner.py
    fun sanitizePythonCode(code: String): String = code

    private companion object {
        val DEFAULT_ON = object : Flags {
            override fun shellBlocklistEnabled() = true
            override fun pythonImportGuardEnabled() = true
            override fun fileProtectionEnabled() = true
            override fun fileSizeLimitEnabled() = true
        }
    }
}
