package com.forge.os.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.forge.os.domain.plugins.PluginManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Handles `ACTION_VIEW` for `*.fp` (Forge Plugin) and `*.zip` files chosen from a file
 * manager / share sheet. Reads the archive on a background dispatcher, installs the
 * plugin, then exits with a Toast result.
 *
 * `.fp` is just a plain ZIP with a `manifest.json` + entrypoint `.py` at the root.
 */
@AndroidEntryPoint
class PluginInstallActivity : ComponentActivity() {

    @Inject lateinit var pluginManager: PluginManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent?.data
        }
        if (uri == null) {
            toast("No plugin file provided"); finish(); return
        }
        toast("Installing plugin…")
        scope.launch {
            val parsed = withContext(Dispatchers.IO) { extract(uri) }
            if (parsed == null) {
                toast("❌ Plugin archive must contain manifest.json + a Python entrypoint"); finish(); return@launch
            }
            val (manifestJson, code) = parsed
            val r = pluginManager.install(manifestJson, code, source = "user")
            r.fold(
                onSuccess = { toast("✓ Installed ${it.name} v${it.version}") },
                onFailure = { Timber.e(it, "PluginInstallActivity: install failed"); toast("❌ ${it.message}") }
            )
            finish()
        }
    }

    private fun extract(uri: Uri): Pair<String, String>? = runCatching {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        var manifestText: String? = null
        val pyFiles = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    val data = zis.readBytes()
                    when {
                        name.equals("manifest.json", ignoreCase = true) -> manifestText = String(data)
                        name.endsWith(".py") -> pyFiles[name] = String(data)
                    }
                }
                entry = zis.nextEntry
            }
        }
        val mf = manifestText ?: return null
        // Choose entrypoint declared in the manifest if present, else the first .py.
        val declared = Regex("\"entrypoint\"\\s*:\\s*\"([^\"]+)\"").find(mf)?.groupValues?.get(1)
        val entryName = declared?.substringAfterLast('/') ?: pyFiles.keys.firstOrNull() ?: return null
        val code = pyFiles[entryName] ?: pyFiles.values.firstOrNull() ?: return null
        mf to code
    }.getOrNull()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}