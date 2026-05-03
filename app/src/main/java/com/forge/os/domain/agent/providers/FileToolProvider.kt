package com.forge.os.domain.agent.providers

import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.data.api.ToolDefinition
import com.forge.os.domain.agent.ToolProvider
import com.forge.os.domain.agent.params
import com.forge.os.domain.agent.tool
import com.forge.os.domain.workspace.WorkspaceLayout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileToolProvider @Inject constructor(
    private val sandboxManager: SandboxManager
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
            "Execute Python 3.11 code in the workspace sandbox. Returns stdout/stderr.",
            params("code" to "string:Python source code")),
        tool("workspace_info",
            "Get summary of files, directories, and disk usage in the sandbox.",
            emptyMap(), required = emptyList()),
        tool("workspace_describe",
            "Describe the canonical Forge OS workspace layout and folder purposes.",
            emptyMap(), required = emptyList()),
        tool("python_packages",
            "List installed Python packages in the local Chaquopy environment. " +
            "Use this to check if a library is available before trying to import it.",
            emptyMap(), required = emptyList()),
        tool("python_pip_install",
            "Install one or more Python packages into the local Chaquopy sandbox via pip. " +
            "Pass a space-separated list of package names (e.g. 'requests numpy'). " +
            "Returns pip's output. Note: only packages that are compatible with the " +
            "device's Chaquopy runtime can be installed at runtime.",
            params("packages" to "string:Space-separated package name(s), e.g. 'requests pillow'"),
            required = listOf("packages"))
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
            "python_pip_install" -> pipInstall(args)
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
        val code = args["code"]?.toString() ?: return "Error: code required"
        return sandboxManager.executePython(code).getOrElse { "❌ python failed: ${it.message}" }
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
        // Use importlib.metadata (stdlib, not blocked) to list installed distributions.
        // subprocess and pip are blocked by the AST security check, so we must avoid them.
        val code = """
import sys
try:
    import importlib.metadata as _meta
    dists = sorted(_meta.distributions(), key=lambda d: (d.metadata.get('Name') or '').lower())
    seen = set()
    for d in dists:
        name = d.metadata.get('Name') or ''
        version = d.metadata.get('Version') or ''
        key = name.lower()
        if name and key not in seen:
            seen.add(key)
            print(f"{name}=={version}")
    if not seen:
        print("(no distributions found via importlib.metadata)")
except Exception as e:
    print(f"importlib.metadata unavailable: {e}")
    print("Loaded modules (sys.modules):")
    for m in sorted(sys.modules.keys()):
        print(f"  {m}")
""".trimIndent()
        return sandboxManager.executePython(code).getOrElse { "❌ python failed: ${it.message}" }
    }

    private suspend fun pipInstall(args: Map<String, Any>): String {
        val packages = args["packages"]?.toString()?.trim() ?: return "Error: packages required"
        if (packages.isBlank()) return "Error: packages must not be blank"
        // Sanitise: only allow package-name characters to prevent shell injection.
        // Valid pip package specs: alphanumerics, hyphens, underscores, dots, '>=', '<=',
        // '==', '!=', '~=', '[', ']', spaces (separating multiple packages), and digits.
        val sanitised = packages.replace(Regex("[^A-Za-z0-9_.\\-\\[\\]=><!~, ]"), "")
        if (sanitised.isBlank()) return "❌ Error: no valid package names after sanitisation"
        // Build a minimal Python snippet that calls pip via its internal API.
        // Using pip._internal avoids subprocess (which is blocked by the AST security check
        // on user-supplied python_run code). This code is trusted tool-generated code, not
        // user-supplied, but we stay safe by not importing subprocess at all.
        val pkgList = sanitised.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(", ") { "\"${it.replace("\"", "")}\"" }
        val code = """
import sys
_pkgs = [$pkgList]
try:
    from pip._internal.cli.main import main as _pip_main
    _rc = _pip_main(['install', '--quiet'] + _pkgs)
    if _rc == 0:
        print("✅ Installed: " + ", ".join(_pkgs))
    else:
        print("❌ pip exited with code " + str(_rc))
except ImportError:
    print("❌ pip is not available in this Python environment.")
    print("💡 In Chaquopy, packages must be declared in build.gradle under pip { install '...' }.")
    print("   Runtime pip install is only possible if pip was included in the Chaquopy build config.")
except Exception as _e:
    print("❌ pip install failed: " + str(_e))
""".trimIndent()
        return sandboxManager.executePython(code).getOrElse { "❌ python failed: ${it.message}" }
    }
}
