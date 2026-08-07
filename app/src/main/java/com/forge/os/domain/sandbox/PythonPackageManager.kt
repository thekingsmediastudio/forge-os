package com.forge.os.domain.sandbox

import android.content.Context
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
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

    // ── Runtime install (pure-Python wheels only) ────────────────────────────

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private sealed class InstallOutcome {
        data class Ok(val message: String) : InstallOutcome()
        data class Err(val message: String) : InstallOutcome()
    }

    /**
     * Install a package at runtime WITHOUT pip (Chaquopy doesn't ship pip).
     *
     * Strategy: query PyPI's JSON API, pick a pure-Python wheel
     * (`py3-none-any` / `py2.py3-none-any`), download it, and unzip it into
     * `workspace/python_packages/` — a wheel is just a zip, and the packages
     * dir is already prepended to sys.path before every python_run, so the
     * package becomes importable immediately.
     *
     * Only pure-Python wheels are accepted: native wheels (pydantic-core,
     * lxml, cryptography…) contain platform .so files that cannot load on
     * Android — those must still go through the build.gradle chaquopy block.
     */
    suspend fun install(name: String): String = withContext(Dispatchers.IO) {
        val pkg = name.trim().lowercase()
        if (pkg.isEmpty() || !pkg.matches(Regex("^[a-z0-9][a-z0-9._-]*$")))
            return@withContext "❌ Invalid package name: '$name'"

        // Catch-all: unexpected JSON shapes / IO surprises must surface as a
        // readable tool error, not a raw exception dump.
        val outcome = try {
            installOne(pkg, emptySet())
        } catch (e: Exception) {
            Timber.e(e, "python_install failed for $pkg")
            InstallOutcome.Err("❌ $pkg: install failed — ${e::class.simpleName}: ${e.message}")
        }
        when (outcome) {
            is InstallOutcome.Ok -> {
                recordInstalled(listOf(pkg))
                outcome.message
            }
            is InstallOutcome.Err -> outcome.message
        }
    }

    private suspend fun installOne(
        pkg: String,
        installed: Set<String>,
        depth: Int = 0,
    ): InstallOutcome {
        if (depth > 8) return InstallOutcome.Err("❌ $pkg: dependency depth exceeded")
        if (pkg in installed) return InstallOutcome.Ok("")

        // 1. Query PyPI JSON API
        val metaUrl = "https://pypi.org/pypi/$pkg/json"
        val meta = try {
            http.newCall(Request.Builder().url(metaUrl).build()).execute().use { r ->
                if (!r.isSuccessful)
                    return InstallOutcome.Err("❌ $pkg: not found on PyPI (HTTP ${r.code})")
                json.parseToJsonElement(r.body?.string() ?: return InstallOutcome.Err("❌ $pkg: empty PyPI response")).jsonObject
            }
        } catch (e: Exception) {
            return InstallOutcome.Err("❌ $pkg: PyPI lookup failed — ${e.message}")
        }

        val info = meta["info"]?.jsonObject
        // requires_dist entries can be JsonNull on PyPI — filter them out
        val requiresDist = info?.get("requires_dist")?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

        // 2. Collect release files across all versions (newest first), keep wheels
        val urls = meta["urls"]?.jsonArray
        val releases = meta["releases"]?.jsonObject
        val wheels = mutableListOf<Pair<String, String>>() // filename → url
        var sawSdist = false

        fun collectWheels(arr: kotlinx.serialization.json.JsonArray?) {
            arr?.forEach { el ->
                val o = el.jsonObject
                // PyPI returns JsonNull for some fields on yanked/old releases —
                // jsonPrimitive on JsonNull throws "Element class ...JsonNull is
                // not a JsonPrimitive", so use safe casts here.
                val filename = (o["filename"] as? JsonPrimitive)?.content ?: return@forEach
                if (filename.endsWith(".tar.gz") || filename.endsWith(".zip")) sawSdist = true
                if (!filename.endsWith(".whl")) return@forEach
                val url = (o["url"] as? JsonPrimitive)?.content ?: return@forEach
                wheels += filename to url
            }
        }
        collectWheels(urls)
        // Fallback: older releases if current has no pure wheel
        if (wheels.none { isPurePythonWheel(it.first) }) {
            releases?.keys?.sortedDescending()?.take(8)?.forEach { v ->
                collectWheels(releases[v]?.jsonArray)
            }
        }

        // 3. Pick the newest pure-Python wheel
        val pure = wheels.filter { isPurePythonWheel(it.first) }
        if (pure.isEmpty()) {
            val anyWheel = wheels.firstOrNull()?.first
            return InstallOutcome.Err(buildString {
                when {
                    anyWheel != null -> {
                        append("❌ $pkg has no pure-Python wheel")
                        append(" (found native wheel '$anyWheel' — contains platform code that can't run on Android). ")
                        append("Native packages must be declared in app/build.gradle under the chaquopy pip block.")
                    }
                    sawSdist -> {
                        append("❌ $pkg is published source-only (sdist, no wheel). ")
                        append("It's likely pure Python but can't be auto-installed; ")
                        append("ask the maintainer to publish a wheel, or vendor the source manually.")
                    }
                    else -> {
                        append("❌ $pkg has no installable files on PyPI (no wheel or sdist found).")
                    }
                }
            })
        }
        val (wheelFile, wheelUrl) = pure.first()

        // 4. Download the wheel
        val tmp = File(context.cacheDir, wheelFile)
        try {
            http.newCall(Request.Builder().url(wheelUrl).build()).execute().use { r ->
                if (!r.isSuccessful)
                    return InstallOutcome.Err("❌ $pkg: wheel download failed (HTTP ${r.code})")
                tmp.outputStream().use { out ->
                    r.body?.byteStream()?.copyTo(out)
                        ?: return InstallOutcome.Err("❌ $pkg: empty wheel body")
                }
            }
        } catch (e: Exception) {
            return InstallOutcome.Err("❌ $pkg: wheel download failed — ${e.message}")
        }

        // 5. Unzip into python_packages/ (skip dist-info metadata dirs)
        val dest = getPackagesDir()
        val extracted = try {
            var count = 0
            ZipFile(tmp).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    if (e.name.contains(".dist-info/") || e.name.contains(".data/")) continue
                    val target = File(dest, e.name)
                    if (!target.canonicalPath.startsWith(dest.canonicalPath)) continue // zip-slip guard
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { ins ->
                        target.outputStream().use { out -> ins.copyTo(out) }
                    }
                    count++
                }
            }
            count
        } catch (e: Exception) {
            return InstallOutcome.Err("❌ $pkg: failed to unpack wheel — ${e.message}")
        } finally {
            tmp.delete()
        }

        // 6. Best-effort: install pure-Python dependencies declared in metadata
        val depNotes = StringBuilder()
        val depNames = requiresDist.mapNotNull { parseDepName(it) }
            .filter { it !in installed && it != pkg }
            .distinct()
        val newInstalled = installed + pkg
        for (dep in depNames) {
            when (val res = installOne(dep, newInstalled, depth + 1)) {
                is InstallOutcome.Ok -> if (res.message.isNotBlank()) depNotes.append('\n').append(res.message)
                is InstallOutcome.Err -> depNotes.append("\n  ⚠️ dep '$dep' skipped: ${res.message.take(120)}")
            }
        }

        return InstallOutcome.Ok(
            "✅ $pkg installed → python_packages/ ($extracted files from $wheelFile)$depNotes" +
            "\nImport it in your next python_run — no restart needed.")
    }

    private fun isPurePythonWheel(filename: String): Boolean {
        // Wheel filename: {dist}-{version}(-{build})?-{python}-{abi}-{platform}.whl
        // Pure-Python wheels have abi == "none" and platform == "any", e.g.
        //   py3-none-any, py2.py3-none-any, py310-none-any, cp39-none-any
        val stem = filename.removeSuffix(".whl")
        val parts = stem.split('-')
        if (parts.size < 3) return false
        val abi = parts[parts.size - 2]
        val platform = parts[parts.size - 1]
        return abi == "none" && platform == "any"
    }

    /** Extract a bare dependency name from a requires_dist line, skipping extras/markers. */
    private fun parseDepName(line: String): String? {
        // e.g. "charset-normalizer (<4,>=2)" ; "idna ; extra == 'crypto'"
        if (line.contains(";") && line.substringAfter(';').contains("extra")) return null // skip optional extras
        val head = line.substringBefore(';').trim()
        val name = head.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        return name.lowercase().takeIf { it.isNotBlank() }
    }

    /** pip is unavailable under Chaquopy; kept only for API compatibility. */
    @Deprecated("Use install() — pip is not available under Chaquopy")
    suspend fun buildPipInstallCode(packages: List<String>): String {
        return "# pip is not available under Chaquopy.\n" +
            "# Use the python_install tool — it downloads pure-Python wheels into python_packages/.\n" +
            "print(\"Use python_install tool for: ${packages.joinToString(", ")}\")"
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
     *
     * Uses `importlib.metadata` (dist-name based, no code execution) instead of
     * `__import__` — the AST security guard in forge_sandbox/security.py blocks
     * `__import__()` calls, which made the previous version of this script fail
     * its own sandbox check every time.
     */
    suspend fun buildListPackagesCode(): String {
        val packagesDir = getPackagesDir().absolutePath.replace("\\", "/")
        val installed = listInstalled()
        val installedNames = installed.map { it.name }.joinToString(", ") { "\"$it\"" }

        return """
import sys
import os

# Make user-installed packages visible to metadata lookup too
_packages_dir = '$packagesDir'
if _packages_dir not in sys.path:
    sys.path.insert(0, _packages_dir)

from importlib.metadata import version, PackageNotFoundError

# Built-in packages (from Chaquopy) — checked by dist name, no code executed
print("=== Built-in Packages (Chaquopy) ===")
_builtin = ['numpy', 'pillow', 'requests', 'beautifulsoup4', 'pandas', 'lxml',
            'python-dateutil', 'pyyaml', 'openpyxl', 'xlrd', 'xlwt', 'psutil']
for pkg in _builtin:
    try:
        print(f"  ✅ {pkg} {version(pkg)}")
    except PackageNotFoundError:
        print(f"  ❌ {pkg} (not available)")
    except Exception as e:
        # Chaquopy wheels may omit dist-info metadata; note it instead of lying
        print(f"  ❔ {pkg} (installed but metadata missing: {type(e).__name__})")

# User-installed packages — union of the manifest and what's actually on disk
# (disk wins: wheels are unpacked without dist-info, so version() may miss them)
print()
print("=== User-Installed Packages (python_packages/) ===")
_manifest = [$installedNames]
_on_disk = []
if os.path.isdir(_packages_dir):
    for entry in sorted(os.listdir(_packages_dir)):
        if entry in ('manifest.json', '__pycache__'):
            continue
        full = os.path.join(_packages_dir, entry)
        if os.path.isdir(full) or entry.endswith('.py'):
            _on_disk.append(entry[:-3] if entry.endswith('.py') else entry)
_seen = set()
_merged = []
for _name in _manifest + _on_disk:
    _key = _name.lower().replace('-', '_')
    if _key not in _seen:
        _seen.add(_key)
        _merged.append(_name)
if _merged:
    for pkg in _merged:
        try:
            print(f"  📦 {pkg} {version(pkg)}")
        except Exception:
            print(f"  📦 {pkg}")
else:
    print("  (none yet — install with python_install)")

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
