package com.forge.os.domain.sandbox

import android.content.Context
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages user-installed Python packages in a dedicated workspace folder.
 *
 * Packages are installed to `workspace/python_packages/` via pip's `--target` flag.
 * This folder is automatically prepended to `sys.path` before every `python_run`,
 * making bundled packages importable without modifying Chaquopy's build config.
 *
 * A JSON manifest tracks installed packages for listing and uninstall.
 */
@Singleton
class PythonPackageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sandboxManager: SandboxManager,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Workspace-relative path to the packages folder */
    val packagesRelativePath = "python_packages"

    /** Manifest file tracking installed packages */
    private val manifestFileName = "python_packages/manifest.json"

    @Serializable
    data class InstalledPackage(
        val name: String,
        val version: String = "",
        val installedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class PackageManifest(
        val packages: MutableList<InstalledPackage> = mutableListOf(),
    )

    /**
     * Get the absolute path to the packages folder.
     * Creates it if it doesn't exist.
     */
    suspend fun getPackagesDir(): File = withContext(Dispatchers.IO) {
        val workspacePath = sandboxManager.getWorkspacePath()
        val dir = File(workspacePath, packagesRelativePath)
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /**
     * Generate the sys.path bootstrap code to prepend before user code.
     * This makes bundled packages importable.
     */
    suspend fun getSysPathBootstrap(): String {
        val packagesDir = getPackagesDir().absolutePath.replace("\\", "/")
        return """
import sys
if '$packagesDir' not in sys.path:
    sys.path.insert(0, '$packagesDir')
""".trimIndent()
    }

    /**
     * Generate pip install command that targets the packages folder.
     */
    suspend fun buildPipInstallCode(packages: List<String>): String {
        val packagesDir = getPackagesDir().absolutePath.replace("\\", "/")
        val pkgList = packages.joinToString(", ") { "\"${it.replace("\"", "")}\"" }
        return """
import sys
import os

# Ensure packages directory exists
_packages_dir = '$packagesDir'
os.makedirs(_packages_dir, exist_ok=True)

# Add to path so pip can check existing installs
if _packages_dir not in sys.path:
    sys.path.insert(0, _packages_dir)

_pkgs = [$pkgList]
try:
    from pip._internal.cli.main import main as _pip_main
    _rc = _pip_main(['install', '--quiet', '--target', _packages_dir] + _pkgs)
    if _rc == 0:
        print("✅ Installed to python_packages/: " + ", ".join(_pkgs))
    else:
        print("❌ pip exited with code " + str(_rc))
except ImportError:
    print("❌ pip is not available in this Python environment.")
    print("💡 Packages must be declared in build.gradle under chaquopy pip block.")
except Exception as _e:
    print("❌ pip install failed: " + str(_e))
""".trimIndent()
    }

    /**
     * Record installed packages in the manifest.
     */
    suspend fun recordInstalled(packageNames: List<String>) = withContext(Dispatchers.IO) {
        val manifest = loadManifest()
        packageNames.forEach { name ->
            // Remove existing entry if present (upgrade case)
            manifest.packages.removeAll { it.name.equals(name, ignoreCase = true) }
            manifest.packages.add(InstalledPackage(name = name))
        }
        saveManifest(manifest)
        Timber.i("Recorded ${packageNames.size} packages in manifest")
    }

    /**
     * Remove a package from the manifest (after uninstall).
     */
    suspend fun recordUninstalled(packageName: String) = withContext(Dispatchers.IO) {
        val manifest = loadManifest()
        manifest.packages.removeAll { it.name.equals(packageName, ignoreCase = true) }
        saveManifest(manifest)
    }

    /**
     * List all user-installed packages from the manifest.
     */
    suspend fun listInstalled(): List<InstalledPackage> = withContext(Dispatchers.IO) {
        loadManifest().packages.toList()
    }

    /**
     * Check if a package is installed (by name, case-insensitive).
     */
    suspend fun isInstalled(packageName: String): Boolean {
        return listInstalled().any { it.name.equals(packageName, ignoreCase = true) }
    }

    /**
     * Generate code to list both built-in and user-installed packages.
     */
    suspend fun buildListPackagesCode(): String {
        val packagesDir = getPackagesDir().absolutePath.replace("\\", "/")
        val installed = listInstalled()
        val installedNames = installed.map { it.name }.joinToString(", ") { "\"$it\"" }

        return """
import sys
import os

# Built-in packages (from Chaquopy)
print("=== Built-in Packages (Chaquopy) ===")
_builtin = ['numpy', 'pillow', 'requests', 'beautifulsoup4', 'pandas', 'lxml', 
            'python-dateutil', 'pyyaml', 'openpyxl', 'xlrd', 'xlwt', 'psutil']
for pkg in _builtin:
    try:
        __import__(pkg.replace('-', '_').replace('beautifulsoup4', 'bs4'))
        print(f"  ✅ {pkg}")
    except ImportError:
        print(f"  ❌ {pkg} (not available)")

# User-installed packages (from python_packages/)
print()
print("=== User-Installed Packages (python_packages/) ===")
_user_installed = [$installedNames]
if _user_installed:
    for pkg in _user_installed:
        print(f"  📦 {pkg}")
else:
    print("  (none yet)")

# Check if packages dir is in path
_packages_dir = '$packagesDir'
print()
if _packages_dir in sys.path:
    print(f"✅ python_packages/ is in sys.path")
else:
    print(f"⚠️ python_packages/ not in sys.path (will be added on next python_run)")
""".trimIndent()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private suspend fun loadManifest(): PackageManifest {
        return try {
            val content = sandboxManager.readFile(manifestFileName).getOrNull()
            if (content != null) {
                json.decodeFromString<PackageManifest>(content)
            } else {
                PackageManifest()
            }
        } catch (e: Exception) {
            Timber.w("Failed to load package manifest: ${e.message}")
            PackageManifest()
        }
    }

    private suspend fun saveManifest(manifest: PackageManifest) {
        try {
            val content = json.encodeToString(PackageManifest.serializer(), manifest)
            sandboxManager.writeFile(manifestFileName, content)
        } catch (e: Exception) {
            Timber.e("Failed to save package manifest: ${e.message}")
        }
    }
}
