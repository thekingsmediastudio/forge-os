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
            val sb = StringBuilder("<!doctype html><meta charset=utf-8><title>$rel/</title>")
            sb.append("<h1>Index of /$rel</h1><ul>")
            if (rel.isNotEmpty()) sb.append("<li><a href=\"../\">../</a></li>")
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                val n = f.name + if (f.isDirectory) "/" else ""
                sb.append("<li><a href=\"$n\">$n</a></li>")
            }
            sb.append("</ul><hr><small>Forge OS project server · $url</small>")
            respond(out, 200, "OK", "text/html; charset=utf-8", sb.toString().toByteArray())
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
