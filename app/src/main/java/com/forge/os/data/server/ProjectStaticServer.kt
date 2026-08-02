package com.forge.os.data.server

import android.content.Context
import android.net.wifi.WifiManager
import com.forge.os.domain.control.AgentControlPlane
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase Q — minimal static-file HTTP server bound to the device's Wi-Fi IP so
 * the project can be browsed from any other device on the local network.
 *
 * Started/stopped via the `project_serve` / `project_unserve` tools. Multiple
 * roots can be served at once (each on its own port). Gated by
 * [AgentControlPlane.PROJECT_SERVE_LAN].
 */
@Singleton
class ProjectStaticServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlPlane: AgentControlPlane,
) {
    data class Server(
        val id: String,
        val root: File,
        val port: Int,
        val url: String,
        val startedAt: Long,
    )

    private val executor = Executors.newCachedThreadPool()
    private val running = mutableMapOf<String, RunningServer>()

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers

    private fun publish() {
        _servers.value = running.values.map { it.publicView }
    }

    @Synchronized
    fun start(root: File, requestedPort: Int = 0): Server {
        if (!controlPlane.isEnabled(AgentControlPlane.PROJECT_SERVE_LAN)) {
            throw SecurityException("project_serve_lan capability disabled")
        }
        if (!root.exists() || !root.isDirectory) {
            throw IllegalArgumentException("Root not a directory: ${root.absolutePath}")
        }
        val server = ServerSocket(requestedPort)
        val port = server.localPort
        val ip = wifiIp() ?: "0.0.0.0"
        val url = "http://$ip:$port/"
        val id = "srv_${System.currentTimeMillis()}_$port"
        val running = RunningServer(id, root, port, server, url)
        this.running[id] = running
        publish()
        executor.submit { running.acceptLoop() }
        Timber.i("ProjectStaticServer: serving ${root.absolutePath} on $url")
        return running.publicView
    }

    @Synchronized
    fun stop(id: String): Boolean {
        val s = running.remove(id) ?: return false
        s.shutdown()
        publish()
        return true
    }

    @Synchronized
    fun stopAll() {
        running.values.forEach { it.shutdown() }
        running.clear()
        publish()
    }

    fun list(): List<Server> = running.values.map { it.publicView }

    fun wifiIp(): String? {
        // Prefer the WifiManager IP. If that's 0 (e.g. on tethering), fall
        // back to the first non-loopback IPv4 of any active interface.
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.ipAddress?.takeIf { it != 0 }?.let {
            val bytes = byteArrayOf(
                (it and 0xff).toByte(),
                (it shr 8 and 0xff).toByte(),
                (it shr 16 and 0xff).toByte(),
                (it shr 24 and 0xff).toByte(),
            )
            return InetAddress.getByAddress(bytes).hostAddress
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
                ?.hostAddress
        }.getOrNull()
    }

    private inner class RunningServer(
        val id: String,
        val root: File,
        val port: Int,
        val socket: ServerSocket,
        val url: String,
    ) {
        val startedAt = System.currentTimeMillis()
        val publicView get() = Server(id, root, port, url, startedAt)
        @Volatile private var open = true

        fun shutdown() {
            open = false
            runCatching { socket.close() }
        }

        fun acceptLoop() {
            while (open && !socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) {
                    if (open) Timber.w(e, "ProjectStaticServer accept failed"); break
                }
                executor.submit { handle(client) }
            }
        }

        private fun handle(client: Socket) {
            try {
                client.use { c ->
                    val input = c.getInputStream().bufferedReader()
                    val requestLine = input.readLine() ?: return
                    // drain headers
                    while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break }
                    val parts = requestLine.split(" ")
                    if (parts.size < 2 || parts[0] != "GET") {
                        respond(c.getOutputStream(), 405, "Method Not Allowed", "text/plain", "405".toByteArray())
                        return
                    }
                    val rawPath = parts[1].substringBefore('?')
                    val decoded = java.net.URLDecoder.decode(rawPath, "UTF-8").trimStart('/')
                    val target = if (decoded.isEmpty()) root else File(root, decoded)
                    val canonical = target.canonicalFile
                    if (!canonical.absolutePath.startsWith(root.canonicalFile.absolutePath)) {
                        respond(c.getOutputStream(), 403, "Forbidden", "text/plain", "403".toByteArray())
                        return
                    }
                    if (canonical.isDirectory) {
                        val index = File(canonical, "index.html")
                        if (index.exists()) writeFile(c.getOutputStream(), index)
                        else writeListing(c.getOutputStream(), canonical, decoded)
                    } else if (canonical.isFile) {
                        writeFile(c.getOutputStream(), canonical)
                    } else {
                        respond(c.getOutputStream(), 404, "Not Found", "text/plain",
                            "404 ${canonical.name}".toByteArray())
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "ProjectStaticServer handle failed")
            }
        }

        private fun writeFile(out: OutputStream, f: File) {
            val mime = mimeFor(f.name)
            val bytes = f.readBytes()
            respond(out, 200, "OK", mime, bytes)
        }

        private fun writeListing(out: OutputStream, dir: File, rel: String) {
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            val isProjectRoot = File(dir, "project.json").exists()
            val pyFiles = files.filter { it.isFile && it.extension.lowercase() == "py" }
            val isPythonProject = pyFiles.isNotEmpty() ||
                File(dir, "requirements.txt").exists() ||
                (isProjectRoot && readProjectField(dir, "mainScript") != null)

            val sb = StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
            sb.append("<title>${if (rel.isEmpty()) dir.name else rel}/</title><style>")
            sb.append(
                "body{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#0f1115;color:#e6e6e6;line-height:1.5}" +
                "h1{font-size:20px;margin:0 0 4px}a{color:#ff8c42;text-decoration:none}a:hover{text-decoration:underline}" +
                ".badge{display:inline-block;background:#1f2430;border:1px solid #2c3342;border-radius:6px;padding:2px 8px;font-size:12px;color:#9aa4b2;margin-right:6px}" +
                ".card{background:#161a22;border:1px solid #232a38;border-radius:10px;padding:16px;margin:14px 0}" +
                "ul{list-style:none;padding:0;margin:8px 0}li{padding:6px 4px;border-bottom:1px solid #1c2230}" +
                ".meta{color:#9aa4b2;font-size:13px}.readme{white-space:pre-wrap;background:#0b0e13;border:1px solid #1c2230;border-radius:8px;padding:12px;font-size:13px;color:#c7ceda;max-height:320px;overflow:auto}" +
                "footer{color:#5b6472;font-size:12px;margin-top:18px}code{background:#0b0e13;padding:1px 5px;border-radius:4px}"
            )
            sb.append("</style>")

            val title = if (rel.isEmpty()) dir.name else rel
            sb.append("<h1>📁 $title/</h1>")
            if (isPythonProject) sb.append("<span class=badge>🐍 Python project</span>")
            if (isProjectRoot) sb.append("<span class=badge>Forge project</span>")

            // Project metadata card (from project.json if present)
            if (isProjectRoot) {
                readProjectMeta(dir)?.let { sb.append(it) }
            }

            // README preview
            val readme = files.firstOrNull {
                it.isFile && it.name.lowercase() in listOf("readme.md", "readme.txt", "readme")
            }
            if (readme != null) {
                val text = runCatching { readme.readText().take(4000) }.getOrNull()
                if (!text.isNullOrBlank()) {
                    sb.append("<div class=card><div class=meta>README</div><div class=readme>")
                    sb.append(escapeHtml(text))
                    sb.append("</div></div>")
                }
            }

            // Python note
            if (isPythonProject) {
                sb.append("<div class=card><div class=meta>")
                sb.append("This is a Python project. Files are served for browsing/download; ")
                sb.append("Python code is not executed by this static server.")
                sb.append("</div></div>")
            }

            // File listing
            sb.append("<div class=card><ul>")
            if (rel.isNotEmpty()) sb.append("<li><a href=\"../\">../</a></li>")
            files.forEach { f ->
                if (f.name == "project.json") return@forEach
                val n = f.name + if (f.isDirectory) "/" else ""
                val icon = when {
                    f.isDirectory -> "📁"
                    f.extension.lowercase() == "py" -> "🐍"
                    f.extension.lowercase() == "md" -> "📝"
                    else -> "📄"
                }
                val size = if (f.isFile) " <span class=meta>· ${formatSize(f.length())}</span>" else ""
                sb.append("<li>$icon <a href=\"$n\">$n</a>$size</li>")
            }
            sb.append("</ul></div>")
            sb.append("<footer>Forge OS project server · $url</footer>")
            respond(out, 200, "OK", "text/html; charset=utf-8", sb.toString().toByteArray())
        }

        private fun readProjectField(dir: File, field: String): String? {
            val f = File(dir, "project.json")
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull() ?: return null
            val m = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"|\"$field\"\\s*:\\s*(\\[[^]]*\\])|\"$field\"\\s*:\\s*([0-9.]+)")
                .find(text) ?: return null
            return m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value
        }

        private fun readProjectMeta(dir: File): String? {
            val name = readProjectField(dir, "name")
            val desc = readProjectField(dir, "description")
            val main = readProjectField(dir, "mainScript")
            val reqs = readProjectField(dir, "requirements")
            if (name == null && desc == null && main == null) return null
            val sb = StringBuilder("<div class=card>")
            if (name != null) sb.append("<div style=\"font-size:16px;font-weight:600\">${escapeHtml(name)}</div>")
            if (desc != null && desc != "(none)") sb.append("<div class=meta>${escapeHtml(desc)}</div>")
            if (main != null) sb.append("<div class=meta>Main script: <code>${escapeHtml(main)}</code></div>")
            if (reqs != null) sb.append("<div class=meta>Requirements: ${escapeHtml(reqs)}</div>")
            sb.append("</div>")
            return sb.toString()
        }

        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }

        private fun respond(out: OutputStream, code: Int, msg: String, mime: String, body: ByteArray) {
            val header = StringBuilder()
                .append("HTTP/1.1 $code $msg\r\n")
                .append("Content-Type: $mime\r\n")
                .append("Content-Length: ${body.size}\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n\r\n")
            out.write(header.toString().toByteArray())
            out.write(body)
            out.flush()
        }

        private fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "js", "mjs" -> "application/javascript; charset=utf-8"
                "json" -> "application/json; charset=utf-8"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                "txt", "md", "log" -> "text/plain; charset=utf-8"
                "wasm" -> "application/wasm"
                "ico" -> "image/x-icon"
                else -> "application/octet-stream"
            }
        }
    }
}
