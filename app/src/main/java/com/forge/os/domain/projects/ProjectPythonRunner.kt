package com.forge.os.domain.projects

import com.forge.os.data.sandbox.SandboxManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2: Project Python Execution
 * 
 * Executes Python files and code within a project context.
 * Validates that required packages are available (pre-installed in build.gradle).
 * 
 * IMPORTANT: Chaquopy does NOT support runtime pip install. Packages must be
 * pre-declared in build.gradle at build time. This runner validates requirements
 * at runtime and provides helpful error messages.
 * 
 * Pre-installed packages (available in all projects):
 * - requests, beautifulsoup4, lxml, numpy, pandas, scikit-learn, matplotlib,
 *   pillow, python-dateutil, pyyaml, openpyxl, xlrd, xlwt, psutil
 */
@Singleton
class ProjectPythonRunner @Inject constructor(
    private val repository: ProjectsRepository,
    private val sandboxManager: SandboxManager,
) {
    // Pre-installed packages in Chaquopy (from build.gradle)
    private val preInstalledPackages = setOf(
        "requests", "beautifulsoup4", "lxml", "numpy", "pandas",
        "scikit-learn", "matplotlib", "pillow", "python-dateutil",
        "pyyaml", "openpyxl", "xlrd", "xlwt", "psutil",
        // Standard library modules
        "sys", "os", "json", "re", "math", "random", "datetime",
        "collections", "itertools", "functools", "operator",
        "string", "io", "pathlib", "shutil", "tempfile",
        "urllib", "http", "email", "base64", "hashlib",
        "csv", "xml", "html", "sqlite3", "pickle", "copy",
        "time", "calendar", "statistics", "decimal", "fractions",
        "typing", "dataclasses", "enum", "abc", "contextlib",
        "logging", "warnings", "traceback", "inspect", "dis",
        "importlib", "pkgutil", "modulefinder", "runpy",
        "ast", "symtable", "token", "keyword", "tokenize",
        "pydoc", "doctest", "unittest", "pdb", "cProfile",
        "timeit", "trace", "gc", "weakref", "array",
        "struct", "codecs", "unicodedata", "stringprep",
        "readline", "rlcompleter", "cmd", "shlex", "configparser",
        "netrc", "xdrlib", "plistlib", "zipfile", "tarfile",
        "gzip", "bz2", "lzma", "zlib", "hashlib", "hmac",
        "secrets", "ssl", "socket", "socketserver", "select",
        "selectors", "asyncio", "threading", "multiprocessing",
        "subprocess", "queue", "sched", "contextvars",
        "errno", "ctypes", "platform", "curses", "getpass",
        "getopt", "argparse", "optparse", "logging", "gettext",
        "locale", "codecs", "encodings", "unicodedata",
        "stringprep", "readline", "rlcompleter"
    )

    /**
     * Execute a Python file from a project.
     * 
     * @param slug Project slug
     * @param scriptPath Path to Python file relative to project root (e.g., "main.py")
     * @param args Command-line arguments to pass to the script
     * @return Execution result with output and success status
     */
    suspend fun executeFile(
        slug: String,
        scriptPath: String,
        args: List<String> = emptyList()
    ): String {
        val project = repository.get(slug)
            ?: return "❌ Project not found: $slug"

        // Validate requirements
        val validation = validateRequirements(project)
        if (!validation.isNotEmpty()) {
            return validation
        }

        // Read the script file
        val projectPath = "projects/$slug/$scriptPath"
        val scriptContent = sandboxManager.readFile(projectPath).fold(
            onSuccess = { it },
            onFailure = { err ->
                return "❌ Failed to read script: ${err.message}"
            }
        )

        // Execute via sandbox
        return executeCode(slug, scriptContent, args, scriptPath)
    }

    /**
     * Execute Python code directly within a project context.
     * 
     * @param slug Project slug
     * @param code Python code to execute
     * @param args Command-line arguments (accessible via sys.argv)
     * @param scriptName Optional script name for error messages
     * @return Execution output as string
     */
    suspend fun executeCode(
        slug: String,
        code: String,
        args: List<String> = emptyList(),
        scriptName: String = "<inline>"
    ): String {
        val project = repository.get(slug)
            ?: return "❌ Project not found: $slug"

        // Validate requirements
        val validation = validateRequirements(project)
        if (validation.isNotEmpty()) {
            return validation
        }

        return try {
            Timber.i("Executing Python in project $slug: $scriptName")
            
            // Execute via sandbox with project context
            // The sandbox will handle:
            // - Setting up sys.path to include project directory
            // - Setting sys.argv
            // - Capturing stdout/stderr
            // - Handling exceptions
            sandboxManager.executePython(
                code = code,
                profile = "default",
                timeoutSeconds = 30
            ).fold(
                onSuccess = { it },
                onFailure = { err ->
                    "❌ Execution failed: ${err.message}"
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Python execution error in project $slug")
            "❌ Error: ${e.message}"
        }
    }

    /**
     * Validate that all project requirements are available.
     * 
     * Returns empty string if all requirements are pre-installed.
     * Returns error message with helpful guidance if any are missing.
     */
    private fun validateRequirements(project: Project): String {
        if (project.requirements.isEmpty()) {
            return ""
        }

        val missing = project.requirements.filter { req ->
            // Parse requirement string (e.g., "numpy>=1.20", "requests")
            val packageName = req.split(Regex("[<>=!]")).first().trim().lowercase()
            !preInstalledPackages.contains(packageName)
        }

        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ")
            return buildString {
                appendLine("❌ Missing Python packages: $missingList")
                appendLine()
                appendLine("💡 Chaquopy does NOT support runtime pip install.")
                appendLine("   Packages must be pre-declared in build.gradle at build time.")
                appendLine()
                appendLine("To add packages:")
                appendLine("1. Edit app/build.gradle.kts")
                appendLine("2. Add to chaquopy.python block:")
                appendLine("   pip {")
                appendLine("       install \"${missing.joinToString("\", \"") { it.split(Regex("[<>=!]")).first().trim() }}\"")
                appendLine("   }")
                appendLine("3. Rebuild the app")
                appendLine()
                appendLine("Available pre-installed packages:")
                appendLine(preInstalledPackages.sorted().chunked(5).joinToString("\n") { row ->
                    row.joinToString(", ") { "  • $it" }
                })
            }
        }

        return ""
    }

    /**
     * Get list of available pre-installed packages.
     */
    fun getAvailablePackages(): List<String> = preInstalledPackages.sorted()

    /**
     * Check if a package is available.
     */
    fun isPackageAvailable(packageName: String): Boolean =
        preInstalledPackages.contains(packageName.lowercase())
}
