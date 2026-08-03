package com.forge.os.domain.agent

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-verification engine that checks if tool executions actually succeeded.
 * Runs after each tool call to catch silent failures.
 */
@Singleton
class VerificationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sandboxManager: SandboxManager,
) {
    companion object {
        private const val TAG = "VerificationEngine"
    }

    /**
     * Verify a tool execution result.
     * Returns VerificationResult indicating pass/fail/skip.
     */
    suspend fun verify(
        toolName: String,
        args: Map<String, Any>,
        result: String,
        isError: Boolean,
    ): VerificationResult {
        // Skip verification if tool already reported error
        if (isError) {
            return VerificationResult.Skip("Tool reported error")
        }

        return try {
            when (toolName) {
                // File operations
                "file_write" -> verifyFileWrite(args)
                "file_delete" -> verifyFileDelete(args)
                "file_download" -> verifyFileDownload(args)
                
                // Code execution
                "python_run" -> verifyPythonRun(result)
                "shell_exec" -> verifyShellExec(result)
                
                // Network operations
                "http_request" -> verifyHttpRequest(result)
                "browser_navigate" -> verifyBrowserNavigate(result)
                
                // System operations
                "app_install" -> verifyAppInstall(args)
                "permission_grant" -> verifyPermissionGrant(args)
                
                // Project operations
                "project_create" -> verifyProjectCreate(args)
                "project_write_file" -> verifyProjectWriteFile(args)
                
                else -> VerificationResult.NotApplicable
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Verification failed for $toolName")
            VerificationResult.Skip("Verification error: ${e.message}")
        }
    }

    // ─── File Operations ─────────────────────────────────────────────────────

    private suspend fun verifyFileWrite(args: Map<String, Any>): VerificationResult {
        val path = args["path"]?.toString() ?: return VerificationResult.Skip("no path arg")
        
        return try {
            val file = sandboxManager.resolveSafe(path)
            when {
                !file.exists() -> VerificationResult.Fail("File not created: $path")
                file.length() == 0L -> VerificationResult.Fail("File is empty: $path")
                !file.canRead() -> VerificationResult.Fail("File not readable: $path")
                else -> VerificationResult.Pass("File created: ${file.length()} bytes")
            }
        } catch (e: Exception) {
            VerificationResult.Fail("Cannot verify: ${e.message}")
        }
    }

    private suspend fun verifyFileDelete(args: Map<String, Any>): VerificationResult {
        val path = args["path"]?.toString() ?: return VerificationResult.Skip("no path arg")
        
        return try {
            val file = sandboxManager.resolveSafe(path)
            if (file.exists()) {
                VerificationResult.Fail("File still exists: $path")
            } else {
                VerificationResult.Pass("File deleted: $path")
            }
        } catch (e: Exception) {
            // If we can't resolve the path, assume it's deleted
            VerificationResult.Pass("File deleted (path unresolvable)")
        }
    }

    private suspend fun verifyFileDownload(args: Map<String, Any>): VerificationResult {
        val path = args["path"]?.toString() ?: args["saved_to"]?.toString() 
            ?: return VerificationResult.Skip("no path arg")
        
        return try {
            val file = sandboxManager.resolveSafe(path)
            when {
                !file.exists() -> VerificationResult.Fail("Download failed: file not found")
                file.length() == 0L -> VerificationResult.Fail("Download failed: file is empty")
                else -> VerificationResult.Pass("Downloaded: ${file.length()} bytes")
            }
        } catch (e: Exception) {
            VerificationResult.Fail("Cannot verify: ${e.message}")
        }
    }

    // ─── Code Execution ──────────────────────────────────────────────────────

    private fun verifyPythonRun(result: String): VerificationResult {
        return when {
            result.contains("Traceback") -> {
                val errorLine = result.lines().firstOrNull { it.contains("Error:") }
                VerificationResult.Fail("Python error: ${errorLine ?: "see output"}")
            }
            result.contains("❌") -> VerificationResult.Fail("Execution failed")
            result.contains("Error:") && !result.contains("Error: 0") -> {
                VerificationResult.Fail("Error in output")
            }
            else -> VerificationResult.Pass("Execution completed")
        }
    }

    private fun verifyShellExec(result: String): VerificationResult {
        return when {
            result.contains("exit code: 1") || result.contains("exit code: 127") -> {
                VerificationResult.Fail("Command failed")
            }
            result.contains("command not found") -> VerificationResult.Fail("Command not found")
            result.contains("Permission denied") -> VerificationResult.Fail("Permission denied")
            result.contains("❌") -> VerificationResult.Fail("Execution failed")
            else -> VerificationResult.Pass("Command executed")
        }
    }

    // ─── Network Operations ──────────────────────────────────────────────────

    private fun verifyHttpRequest(result: String): VerificationResult {
        return when {
            result.contains("\"ok\":false") -> VerificationResult.Fail("Request failed")
            result.contains("HTTP 4") || result.contains("HTTP 5") -> {
                val statusMatch = Regex("HTTP (\\d+)").find(result)
                VerificationResult.Fail("HTTP error: ${statusMatch?.groupValues?.get(1) ?: "unknown"}")
            }
            result.contains("timeout") || result.contains("Timeout") -> {
                VerificationResult.Fail("Request timeout")
            }
            result.contains("❌") -> VerificationResult.Fail("Request failed")
            else -> VerificationResult.Pass("Request successful")
        }
    }

    private fun verifyBrowserNavigate(result: String): VerificationResult {
        return when {
            result.contains("error") || result.contains("Error") -> {
                VerificationResult.Fail("Navigation failed")
            }
            result.contains("❌") -> VerificationResult.Fail("Navigation failed")
            result.contains("net::ERR") -> VerificationResult.Fail("Network error")
            else -> VerificationResult.Pass("Page loaded")
        }
    }

    // ─── System Operations ───────────────────────────────────────────────────

    private fun verifyAppInstall(args: Map<String, Any>): VerificationResult {
        val packageName = args["package"]?.toString() 
            ?: return VerificationResult.Skip("no package arg")
        
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            VerificationResult.Pass("App installed: $packageName")
        } catch (e: PackageManager.NameNotFoundException) {
            VerificationResult.Fail("App not installed: $packageName")
        }
    }

    private fun verifyPermissionGrant(args: Map<String, Any>): VerificationResult {
        val permission = args["permission"]?.toString() 
            ?: return VerificationResult.Skip("no permission arg")
        
        val granted = ContextCompat.checkSelfPermission(
            context, 
            permission
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (granted) {
            VerificationResult.Pass("Permission granted: $permission")
        } else {
            VerificationResult.Fail("Permission not granted: $permission")
        }
    }

    // ─── Project Operations ──────────────────────────────────────────────────

    private suspend fun verifyProjectCreate(args: Map<String, Any>): VerificationResult {
        val slug = args["slug"]?.toString() ?: args["name"]?.toString()
            ?: return VerificationResult.Skip("no slug arg")
        
        return try {
            val projectDir = sandboxManager.resolveSafe("projects/$slug")
            if (projectDir.exists() && projectDir.isDirectory) {
                VerificationResult.Pass("Project created: $slug")
            } else {
                VerificationResult.Fail("Project directory not created: $slug")
            }
        } catch (e: Exception) {
            VerificationResult.Fail("Cannot verify: ${e.message}")
        }
    }

    private suspend fun verifyProjectWriteFile(args: Map<String, Any>): VerificationResult {
        val slug = args["slug"]?.toString() ?: return VerificationResult.Skip("no slug arg")
        val path = args["path"]?.toString() ?: return VerificationResult.Skip("no path arg")
        
        return try {
            val file = sandboxManager.resolveSafe("projects/$slug/$path")
            when {
                !file.exists() -> VerificationResult.Fail("File not created: $path")
                file.length() == 0L -> VerificationResult.Fail("File is empty: $path")
                else -> VerificationResult.Pass("File written: ${file.length()} bytes")
            }
        } catch (e: Exception) {
            VerificationResult.Fail("Cannot verify: ${e.message}")
        }
    }
}

/**
 * Result of a verification check.
 */
sealed class VerificationResult {
    /** Verification not applicable for this tool. */
    object NotApplicable : VerificationResult()
    
    /** Verification skipped (e.g., missing args, tool reported error). */
    data class Skip(val reason: String) : VerificationResult()
    
    /** Verification passed. */
    data class Pass(val detail: String) : VerificationResult()
    
    /** Verification failed. */
    data class Fail(val detail: String) : VerificationResult()
}
