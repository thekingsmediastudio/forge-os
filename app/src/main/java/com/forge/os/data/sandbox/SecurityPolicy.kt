package com.forge.os.data.sandbox

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
class SecurityPolicy @Inject constructor() {

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
        // Improve regex to catch obfuscated paths like /"etc"/passwd or /\.\./
        for (pattern in blockedShellPatterns) {
            if (pattern.containsMatchIn(command)) {
                throw SecurityException("Blocked command pattern detected in: $command")
            }
        }
    }

    fun validateUrl(url: String) {
        val uri = try { java.net.URI(url) } catch (e: Exception) { throw SecurityException("Invalid URL: $url") }
        val host = uri.host?.lowercase() ?: throw SecurityException("URL missing host: $url")
        
        // 1. Block known local API port if host is local
        if ((host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") && uri.port == 8789) {
            throw SecurityException("Access to local API server is prohibited via tools")
        }

        // 2. Block private IP ranges (basic check, could be improved with proper IP parsing)
        if (blockedIpRanges.any { host.startsWith(it) }) {
            throw SecurityException("Access to private/local network addresses is prohibited: $host")
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
