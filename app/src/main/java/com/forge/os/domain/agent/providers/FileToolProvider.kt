package com.forge.os.domain.agent.providers

import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.domain.agent.params
import com.forge.os.domain.agent.tool
import com.forge.os.domain.sandbox.PythonPackageManager
import com.forge.os.domain.workspace.WorkspaceLayout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileToolProvider @Inject constructor(
    private val sandboxManager: SandboxManager,
    private val namedSecretRegistry: com.forge.os.domain.security.NamedSecretRegistry,
    private val pythonPackageManager: PythonPackageManager
) : ToolProvider {

    override fun getTools(): List<ToolDefinition> = listOf(
        tool("file_read",
            "Read the text content of a file. Supports pagination for large files to avoid context limits.",
            params("path" to "string:Workspace-relative path",
                   "start_line" to "integer:Optional 1-based start line",
                   "end_line" to "integer:Optional 1-based end line")),
        tool("file_write",
            "Create or overwrite a file in the workspace.",
            params("path" to "string:Workspace-relative path",
                   "content" to "string:Content to write")),
        tool("file_list",
            "List files in a workspace directory.",
            params("path" to "string:Workspace-relative directory (default '.')")),
        tool("file_delete",
            "Delete a file from the workspace.",
            params("path" to "string:Workspace-relative path")),
        tool("shell_exec",
            "Execute a shell command in the workspace root.",
            params("command" to "string:Shell command")),
        tool("python_run",
            "Execute Python 3.11 code in the workspace sandbox. Returns stdout/stderr. " +
            "Optionally inject named secrets as env vars (SECRET_<NAME>) via secret_names.",
            params("code" to "string:Python source code",
                   "secret_names" to "string:Comma-separated secret names to inject as env vars (optional)")),
        tool("workspace_info",
            "Get summary of files, directories, and disk usage in the sandbox.",
            emptyMap(), required = emptyList()),
        tool("workspace_describe",
            "Describe the canonical Forge OS workspace layout and folder purposes.",
            emptyMap(), required = emptyList()),
        tool("python_packages",
            "List installed Python packages in the local Chaquopy environment. " +
            "Use this to check if a library is available before trying to import it. " +
            "Note: Runtime pip install is NOT supported. Packages must be declared in build.gradle.",
            emptyMap(), required = emptyList())
    )

    override suspend fun dispatch(toolName: String, args: Map<String, Any>): String? {
        return when (toolName) {
            "file_read"          -> fileRead(args)
            "file_write"         -> fileWrite(args)
            "file_list"          -> fileList(args)
            "file_delete"        -> fileDelete(args)
            "shell_exec"         -> shellExec(args)
            "python_run"         -> pythonRun(args)
            "workspace_info"     -> workspaceInfo()
            "workspace_describe" -> WorkspaceLayout.describe()
            "python_packages"    -> pythonPackages()
            else -> null
        }
    }

    private suspend fun fileRead(args: Map<String, Any>): String {
        val path = args["path"]?.toString() ?: return "Error: path required"
        val startLine = args["start_line"]?.toString()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1)
        val endLine = args["end_line"]?.toString()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1)

        // Binary detection: sample the first 8 KB for null bytes before attempting
        // a full UTF-8 text read. readText() throws MalformedInputException on binary
        // files, which produces a cryptic error message. Catching it here gives the
        // agent a clear, actionable message instead.
        val isBinary = runCatching {
            val file = sandboxManager.resolveSafe(path)
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                val read = input.read(buf, 0, buf.size)
                (0 until read).any { buf[it] == 0.toByte() }
            }
        }.getOrElse { false }

        if (isBinary) {
            return "❌ '$path' appears to be a binary file and cannot be read as text.\n" +
                   "💡 Try: shell_exec('base64 $path') to read as base64, or " +
                   "shell_exec('file $path') to identify the file type."
        }

        return sandboxManager.readFile(path).fold(
            onSuccess = { content ->
                val lines = content.lines()
                val limit = 500

                // If no bounds specified and file is huge, truncate to save context limit
                if (startLine == null && endLine == null && lines.size > limit) {
                    val sliced = lines.take(limit)
                    return@fold "--- Lines 1 to $limit of ${lines.size} ---\n" +
                                "(File too large to read at once. Use start_line and end_line to read the rest!)\n\n" +
                                sliced.joinToString("\n")
                }

                val startIdx = (startLine ?: 1) - 1
                val endIdx = (endLine ?: lines.size).coerceAtMost(lines.size) - 1
                
                if (startIdx >= lines.size) return@fold "File only has ${lines.size} lines."
                if (startIdx > endIdx) return@fold "Invalid range: $startLine to $endLine"
                
                val sliced = lines.slice(startIdx..endIdx)
                val header = if (startLine != null || endLine != null) 
                    "--- Lines ${startIdx + 1} to ${endIdx + 1} of ${lines.size} ---\n"
                else ""
                    
                header + sliced.joinToString("\n")
            },
            onFailure = { err ->
                // Distinguish binary/encoding errors from other I/O failures
                val isMalformed = err.javaClass.name.contains("MalformedInput") ||
                    err.javaClass.name.contains("CharacterCoding") ||
                    err.message?.contains("Input length") == true
                if (isMalformed) {
                    "❌ '$path' contains binary or non-UTF-8 data and cannot be read as text.\n" +
                    "💡 Try: shell_exec('base64 $path') or shell_exec('file $path')"
                } else {
                    "❌ readFile failed: ${err.message}"
                }
            }
        )
    }

    private suspend fun fileWrite(args: Map<String, Any>): String {
        val path = args["path"]?.toString() ?: return "Error: path required"
        val content = args["content"]?.toString() ?: return "Error: content required"
        return sandboxManager.writeFile(path, content).fold(
            onSuccess = { "✅ Written ${content.length} chars to $path" },
            onFailure = { "❌ writeFile failed: ${it.message}" }
        )
    }

    private suspend fun fileList(args: Map<String, Any>): String {
        val path = args["path"]?.toString() ?: ""
        return sandboxManager.listFiles(path).fold(
            onSuccess = { files ->
                if (files.isEmpty()) "(empty)"
                else files.joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.name}  (${it.size}b)" }
            },
            onFailure = { "❌ listFiles failed: ${it.message}" }
        )
    }

    private suspend fun fileDelete(args: Map<String, Any>): String {
        val path = args["path"]?.toString() ?: return "Error: path required"
        return sandboxManager.deleteFile(path).fold(
            onSuccess = { "✅ Deleted $path" },
            onFailure = { "❌ deleteFile failed: ${it.message}" }
        )
    }

    private suspend fun shellExec(args: Map<String, Any>): String {
        val command = args["command"]?.toString() ?: return "Error: command required"
        return sandboxManager.executeShell(command).getOrElse { "❌ shell failed: ${it.message}" }
    }

    private suspend fun pythonRun(args: Map<String, Any>): String {
        val userCode = args["code"]?.toString() ?: return "Error: code required"
        val secretNamesRaw = args["secret_names"]?.toString() ?: ""
        val env = if (secretNamesRaw.isNotBlank()) {
            val names = secretNamesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val map = mutableMapOf<String, String>()
            val missing = mutableListOf<String>()
            for (name in names) {
                val value = namedSecretRegistry.getValue(name)
                if (value != null) {
                    val envKey = "SECRET_${name.uppercase().replace(Regex("[^A-Z0-9_]"), "_")}"
                    map[envKey] = value
                } else {
                    missing.add(name)
                }
            }
            if (missing.isNotEmpty()) {
                return "❌ Secrets not found: ${missing.joinToString(", ")}. Use secret_list to see available names."
            }
            map.ifEmpty { null }
        } else null

        // Prepend sys.path bootstrap so user-installed packages in python_packages/ are importable
        val bootstrap = pythonPackageManager.getSysPathBootstrap()
        val code = bootstrap + "\n\n" + userCode

        return sandboxManager.executePython(code, env = env).getOrElse { "❌ python failed: ${it.message}" }
    }

    private suspend fun workspaceInfo(): String {
        val info = sandboxManager.getWorkspaceInfo()
        val pct = "%.1f".format(info.usagePercent)
        return buildString {
            appendLine("📊 Workspace")
            appendLine("  files: ${info.totalFiles}")
            appendLine("  dirs: ${info.totalDirs}")
            appendLine("  size: ${info.totalSize}b / ${info.maxSize}b ($pct%)")
        }
    }

    private suspend fun pythonPackages(): String {
        // Use PythonPackageManager to generate code that shows both built-in and user-installed packages
        val code = pythonPackageManager.buildListPackagesCode()
        return sandboxManager.executePython(code).getOrElse { "❌ python failed: ${it.message}" }
    }
}
