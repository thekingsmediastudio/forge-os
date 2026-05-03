package com.forge.os.presentation.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class FileKind { TEXT, IMAGE, BINARY }

data class FileViewerState(
    val path: String = "",
    val name: String = "",
    val kind: FileKind = FileKind.TEXT,
    val absolutePath: String = "",
    val size: Long = 0L,
    val text: String = "",
    val originalText: String = "",
    val hexPreview: String = "",
    val mimeType: String = "application/octet-stream",
    val outsideSandbox: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class FileViewerViewModel @Inject constructor(
    private val sandboxManager: SandboxManager,
) : ViewModel() {

    private val _state = MutableStateFlow(FileViewerState())
    val state: StateFlow<FileViewerState> = _state.asStateFlow()

    val isDirty: Boolean get() = _state.value.kind == FileKind.TEXT && _state.value.text != _state.value.originalText

    fun load(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = sandboxManager.resolveSafe(path)
                if (!file.exists() || file.isDirectory) {
                    _state.value = FileViewerState(path = path, error = "File not found", loading = false)
                    return@launch
                }
                val kind = detectKind(file)
                val mime = mimeFor(file.extension.lowercase())
                val outside = !file.canonicalPath.startsWith(File(sandboxManager.getWorkspacePath()).canonicalPath)
                val base = FileViewerState(
                    path = path,
                    name = file.name,
                    kind = kind,
                    absolutePath = file.absolutePath,
                    size = file.length(),
                    mimeType = mime,
                    outsideSandbox = outside,
                    loading = false,
                )
                _state.value = when (kind) {
                    FileKind.TEXT -> {
                        val txt = sandboxManager.readFile(path).getOrElse { return@launch fail(path, it) }
                        base.copy(text = txt, originalText = txt)
                    }
                    FileKind.IMAGE -> base
                    FileKind.BINARY -> {
                        val bytes = sandboxManager.readBytes(path, 4096).getOrElse { return@launch fail(path, it) }
                        base.copy(hexPreview = renderHex(bytes))
                    }
                }
            } catch (t: Throwable) {
                fail(path, t)
            }
        }
    }

    fun edit(newText: String) {
        _state.value = _state.value.copy(text = newText)
    }

    fun save(force: Boolean = false) {
        viewModelScope.launch {
            val cur = _state.value
            if (cur.kind != FileKind.TEXT) return@launch
            if (cur.outsideSandbox && !force) {
                _state.value = cur.copy(error = "File is outside the sandbox. Confirm to save.")
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    sandboxManager.writeFile(cur.path, cur.text).getOrThrow()
                }
                _state.value = _state.value.copy(originalText = cur.text, message = "Saved")
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: "Save failed")
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun fail(path: String, t: Throwable) {
        _state.value = _state.value.copy(path = path, loading = false, error = t.message ?: "Could not open file")
    }

    private fun detectKind(file: File): FileKind {
        val ext = file.extension.lowercase()
        if (ext in IMAGE_EXTS) return FileKind.IMAGE
        if (ext in TEXT_EXTS) return FileKind.TEXT
        // Fall back: sniff first few bytes for NULs / non-printables.
        // NB: java.io.InputStream.readNBytes(int) is API 33+; do it manually.
        val bytes = file.inputStream().use { ins ->
            val buf = ByteArray(512)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        }
        val nonText = bytes.count { b -> b.toInt() == 0 || (b.toInt() in 1..8) || (b.toInt() in 14..31) }
        return if (bytes.isNotEmpty() && nonText * 100 / bytes.size > 5) FileKind.BINARY else FileKind.TEXT
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "json" -> "application/json"
        "md" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "py" -> "text/x-python"
        "kt" -> "text/x-kotlin"
        "yaml", "yml" -> "application/yaml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun renderHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val end = (i + 16).coerceAtMost(bytes.size)
            sb.append("%08x  ".format(i))
            for (j in i until i + 16) {
                if (j < end) sb.append("%02x ".format(bytes[j].toInt() and 0xff)) else sb.append("   ")
                if (j == i + 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (bytes.isEmpty()) sb.append("(empty)\n")
        return sb.toString()
    }

    companion object {
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val TEXT_EXTS = setOf(
            "txt", "md", "json", "yaml", "yml", "py", "kt", "kts", "java", "xml",
            "html", "htm", "css", "js", "ts", "tsx", "jsx", "csv", "tsv", "log",
            "ini", "toml", "properties", "conf", "sh", "gradle", "sql",
        )
    }
}
