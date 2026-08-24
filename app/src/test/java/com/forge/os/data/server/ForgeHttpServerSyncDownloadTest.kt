package com.forge.os.data.server

import android.content.Context
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.service.PairingService
import com.forge.os.service.SyncService
import com.forge.os.service.ToolExecutionManager
import dagger.Lazy
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

/**
 * Integration tests for GET /api/sync/download endpoint with range support
 */
class ForgeHttpServerSyncDownloadTest {
    
    private lateinit var server: ForgeHttpServer
    private lateinit var syncService: SyncService
    private lateinit var sandboxManager: SandboxManager
    private lateinit var tempWorkspace: File
    private val json = Json { ignoreUnknownKeys = true }
    private val testPort = 8792 // Use different port to avoid conflicts
    
    @Before
    fun setUp() {
        // Create temp workspace directory
        tempWorkspace = File.createTempFile("test_workspace", "").apply {
            delete()
            mkdirs()
        }
        
        // Create mock Context
        val context = mock(Context::class.java)
        val filesDir = File.createTempFile("test_files", "").apply {
            delete()
            mkdirs()
        }
        `when`(context.filesDir).thenReturn(filesDir)
        
        // Create real SandboxManager
        sandboxManager = mock(SandboxManager::class.java)
        `when`(sandboxManager.getWorkspacePath()).thenReturn(tempWorkspace.absolutePath)
        
        // Create real SyncService
        syncService = SyncService(context, sandboxManager)
        
        // Mock other dependencies
        val toolRegistry = mock(ToolRegistry::class.java)
        val keyStore = mock(SecureKeyStore::class.java)
        `when`(keyStore.getCustomKey(any())).thenReturn("test-api-key")
        @Suppress("UNCHECKED_CAST")
        val reActAgent = mock(Lazy::class.java) as Lazy<ReActAgent>
        val pairingService = mock(PairingService::class.java)
        val toolExecutionManager = mock(ToolExecutionManager::class.java)
        
        server = ForgeHttpServer(
            toolRegistry = toolRegistry,
            keyStore = keyStore,
            reActAgent = reActAgent,
            pairingService = pairingService,
            toolExecutionManager = toolExecutionManager,
            syncService = syncService
        )
        
        // Start server
        server.start(testPort)
        
        // Give server time to start
        Thread.sleep(100)
    }
    
    @After
    fun tearDown() {
        server.stop()
        Thread.sleep(100)
        
        // Cleanup temp directories
        tempWorkspace.deleteRecursively()
    }
    
    @Test
    fun `GET sync download returns full file when no range specified`() {
        // Create test file
        val fileContent = "Hello, this is a test file for downloading!"
        val testFile = File(tempWorkspace, "test.txt")
        testFile.writeText(fileContent)
        
        val response = downloadFile("test.txt")
        
        assertEquals(200, response.statusCode)
        assertEquals("application/octet-stream", response.contentType)
        assertEquals(fileContent.length, response.contentLength)
        assertEquals(fileContent, String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Accept-Ranges: bytes"))
    }
    
    @Test
    fun `GET sync download with range returns partial content`() {
        // Create test file
        val fileContent = "0123456789ABCDEFGHIJ"
        val testFile = File(tempWorkspace, "range-test.txt")
        testFile.writeText(fileContent)
        
        // Request bytes 5-14 (10 bytes: "56789ABCDE")
        val response = downloadFile("range-test.txt", rangeStart = 5, rangeEnd = 14)
        
        assertEquals(206, response.statusCode)
        assertEquals("application/octet-stream", response.contentType)
        assertEquals(10, response.contentLength)
        assertEquals("56789ABCDE", String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Content-Range: bytes 5-14/20"))
    }
    
    @Test
    fun `GET sync download with range from start to middle`() {
        // Create test file
        val fileContent = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val testFile = File(tempWorkspace, "partial.txt")
        testFile.writeText(fileContent)
        
        // Request first 10 bytes (bytes 0-9)
        val response = downloadFile("partial.txt", rangeStart = 0, rangeEnd = 9)
        
        assertEquals(206, response.statusCode)
        assertEquals(10, response.contentLength)
        assertEquals("ABCDEFGHIJ", String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Content-Range: bytes 0-9/26"))
    }
    
    @Test
    fun `GET sync download with range from middle to end`() {
        // Create test file
        val fileContent = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val testFile = File(tempWorkspace, "partial.txt")
        testFile.writeText(fileContent)
        
        // Request last 10 bytes (bytes 16-25)
        val response = downloadFile("partial.txt", rangeStart = 16, rangeEnd = 25)
        
        assertEquals(206, response.statusCode)
        assertEquals(10, response.contentLength)
        assertEquals("QRSTUVWXYZ", String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Content-Range: bytes 16-25/26"))
    }
    
    @Test
    fun `GET sync download with range only start byte`() {
        // Create test file
        val fileContent = "0123456789"
        val testFile = File(tempWorkspace, "start-only.txt")
        testFile.writeText(fileContent)
        
        // Request from byte 5 to end
        val response = downloadFile("start-only.txt", rangeStart = 5, rangeEnd = null)
        
        assertEquals(206, response.statusCode)
        assertEquals(5, response.contentLength)
        assertEquals("56789", String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Content-Range: bytes 5-9/10"))
    }
    
    @Test
    fun `GET sync download with binary data succeeds`() {
        // Create binary file with various byte values
        val binaryData = ByteArray(256) { it.toByte() }
        val testFile = File(tempWorkspace, "binary.dat")
        testFile.writeBytes(binaryData)
        
        val response = downloadFile("binary.dat")
        
        assertEquals(200, response.statusCode)
        assertEquals(256, response.contentLength)
        assertArrayEquals(binaryData, response.body)
    }
    
    @Test
    fun `GET sync download from subdirectory succeeds`() {
        // Create file in subdirectory
        val subdir = File(tempWorkspace, "subdir/nested")
        subdir.mkdirs()
        val fileContent = "File in subdirectory"
        val testFile = File(subdir, "file.txt")
        testFile.writeText(fileContent)
        
        val response = downloadFile("subdir/nested/file.txt")
        
        assertEquals(200, response.statusCode)
        assertEquals(fileContent, String(response.body, Charsets.UTF_8))
    }
    
    @Test
    fun `GET sync download with large file succeeds`() {
        // Create a 1MB file
        val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val testFile = File(tempWorkspace, "large.dat")
        testFile.writeBytes(largeData)
        
        val response = downloadFile("large.dat")
        
        assertEquals(200, response.statusCode)
        assertEquals(1024 * 1024, response.contentLength)
        assertArrayEquals(largeData, response.body)
    }
    
    @Test
    fun `GET sync download with range on large file succeeds`() {
        // Create a 1MB file
        val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val testFile = File(tempWorkspace, "large-range.dat")
        testFile.writeBytes(largeData)
        
        // Request 1KB chunk from middle (bytes 500000-500999)
        val response = downloadFile("large-range.dat", rangeStart = 500000, rangeEnd = 500999)
        
        assertEquals(206, response.statusCode)
        assertEquals(1000, response.contentLength)
        
        // Verify content matches expected range
        val expectedChunk = largeData.sliceArray(500000..500999)
        assertArrayEquals(expectedChunk, response.body)
    }
    
    @Test
    fun `GET sync download with single byte range succeeds`() {
        // Create test file
        val fileContent = "ABCDEFGHIJ"
        val testFile = File(tempWorkspace, "single-byte.txt")
        testFile.writeText(fileContent)
        
        // Request single byte at position 5
        val response = downloadFile("single-byte.txt", rangeStart = 5, rangeEnd = 5)
        
        assertEquals(206, response.statusCode)
        assertEquals(1, response.contentLength)
        assertEquals("F", String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Content-Range: bytes 5-5/10"))
    }
    
    @Test
    fun `GET sync download with empty file returns empty content`() {
        // Create empty file
        val testFile = File(tempWorkspace, "empty.txt")
        testFile.writeText("")
        
        val response = downloadFile("empty.txt")
        
        assertEquals(200, response.statusCode)
        assertEquals(0, response.contentLength)
        assertEquals(0, response.body.size)
    }
    
    @Test
    fun `GET sync download with nonexistent file returns 404`() {
        val response = downloadFile("nonexistent.txt")
        
        assertEquals(404, response.statusCode)
        assertTrue(String(response.body, Charsets.UTF_8).contains("File not found"))
    }
    
    @Test
    fun `GET sync download without path parameter returns 400`() {
        val response = makeGetRequest("/api/sync/download")
        
        assertEquals(400, response.statusCode)
        assertTrue(String(response.body, Charsets.UTF_8).contains("Missing 'path' query parameter"))
    }
    
    @Test
    fun `GET sync download with empty path parameter returns 400`() {
        val response = makeGetRequest("/api/sync/download?path=")
        
        assertEquals(400, response.statusCode)
        assertTrue(String(response.body, Charsets.UTF_8).contains("Missing 'path' query parameter"))
    }
    
    @Test
    fun `GET sync download requires authentication`() {
        // Create test file
        val testFile = File(tempWorkspace, "auth-test.txt")
        testFile.writeText("Test content")
        
        val response = downloadFile("auth-test.txt", authenticated = false)
        
        assertEquals(401, response.statusCode)
        assertTrue(String(response.body, Charsets.UTF_8).contains("unauthorized"))
    }
    
    @Test
    fun `GET sync download with invalid range returns 416`() {
        // Create test file
        val fileContent = "0123456789"
        val testFile = File(tempWorkspace, "invalid-range.txt")
        testFile.writeText(fileContent)
        
        // Request range beyond file size (start at byte 20, file is only 10 bytes)
        val response = downloadFile("invalid-range.txt", rangeStart = 20, rangeEnd = 30)
        
        assertEquals(416, response.statusCode)
        assertTrue(String(response.body, Charsets.UTF_8).contains("Range not satisfiable") ||
                  String(response.body, Charsets.UTF_8).contains("Invalid range"))
    }
    
    @Test
    fun `GET sync download with start greater than end returns 416`() {
        // Create test file
        val fileContent = "0123456789"
        val testFile = File(tempWorkspace, "bad-range.txt")
        testFile.writeText(fileContent)
        
        // Request invalid range where start > end
        val response = downloadFile("bad-range.txt", rangeStart = 8, rangeEnd = 5)
        
        assertEquals(416, response.statusCode)
        assertTrue(String(response.body, Charsets.UTF_8).contains("Range not satisfiable") ||
                  String(response.body, Charsets.UTF_8).contains("Invalid range"))
    }
    
    @Test
    fun `GET sync download with range end beyond file size uses file size`() {
        // Create test file
        val fileContent = "0123456789"
        val testFile = File(tempWorkspace, "clamp-range.txt")
        testFile.writeText(fileContent)
        
        // Request range with end beyond file size
        val response = downloadFile("clamp-range.txt", rangeStart = 5, rangeEnd = 999)
        
        assertEquals(206, response.statusCode)
        assertEquals("56789", String(response.body, Charsets.UTF_8))
        assertTrue(response.headers.contains("Content-Range: bytes 5-9/10"))
    }
    
    @Test
    fun `GET sync download resumable transfer scenario`() {
        // Create test file
        val fileContent = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val testFile = File(tempWorkspace, "resumable.txt")
        testFile.writeText(fileContent)
        
        // Simulate resumable download: download in 3 chunks
        
        // First chunk: bytes 0-11
        val chunk1 = downloadFile("resumable.txt", rangeStart = 0, rangeEnd = 11)
        assertEquals(206, chunk1.statusCode)
        assertEquals("0123456789AB", String(chunk1.body, Charsets.UTF_8))
        
        // Second chunk: bytes 12-23
        val chunk2 = downloadFile("resumable.txt", rangeStart = 12, rangeEnd = 23)
        assertEquals(206, chunk2.statusCode)
        assertEquals("CDEFGHIJKLMN", String(chunk2.body, Charsets.UTF_8))
        
        // Third chunk: bytes 24-35 (to end)
        val chunk3 = downloadFile("resumable.txt", rangeStart = 24, rangeEnd = 35)
        assertEquals(206, chunk3.statusCode)
        assertEquals("OPQRSTUVWXYZ", String(chunk3.body, Charsets.UTF_8))
        
        // Verify reassembled content
        val reassembled = String(chunk1.body + chunk2.body + chunk3.body, Charsets.UTF_8)
        assertEquals(fileContent, reassembled)
    }
    
    @Test
    fun `GET sync download with special characters in filename`() {
        // Create file with special characters (URL-encoded in query)
        val fileName = "test file (1).txt"
        val fileContent = "Content with spaces"
        val testFile = File(tempWorkspace, fileName)
        testFile.writeText(fileContent)
        
        // URL encode the path
        val encodedPath = java.net.URLEncoder.encode(fileName, "UTF-8")
        val response = makeGetRequest("/api/sync/download?path=$encodedPath", authenticated = true)
        
        assertEquals(200, response.statusCode)
        assertEquals(fileContent, String(response.body, Charsets.UTF_8))
    }
    
    @Test
    fun `GET sync download multiple range requests on same file`() {
        // Create test file
        val fileContent = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val testFile = File(tempWorkspace, "multi-range.txt")
        testFile.writeText(fileContent)
        
        // Request multiple ranges in sequence
        val range1 = downloadFile("multi-range.txt", rangeStart = 0, rangeEnd = 4)
        assertEquals("ABCDE", String(range1.body, Charsets.UTF_8))
        
        val range2 = downloadFile("multi-range.txt", rangeStart = 10, rangeEnd = 14)
        assertEquals("KLMNO", String(range2.body, Charsets.UTF_8))
        
        val range3 = downloadFile("multi-range.txt", rangeStart = 20, rangeEnd = 25)
        assertEquals("UVWXYZ", String(range3.body, Charsets.UTF_8))
    }
    
    // Helper functions
    
    private data class HttpResponse(
        val statusCode: Int,
        val contentType: String?,
        val contentLength: Int,
        val headers: String,
        val body: ByteArray
    )
    
    private fun downloadFile(
        path: String,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        authenticated: Boolean = true
    ): HttpResponse {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val url = "/api/sync/download?path=$encodedPath"
        
        return makeGetRequest(
            path = url,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            authenticated = authenticated
        )
    }
    
    private fun makeGetRequest(
        path: String,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        authenticated: Boolean = true
    ): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val output = socket.getOutputStream()
            
            val headers = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: localhost:$testPort\r\n")
                if (authenticated) {
                    append("Authorization: Bearer ${server.apiKey()}\r\n")
                }
                if (rangeStart != null) {
                    val rangeHeader = if (rangeEnd != null) {
                        "bytes=$rangeStart-$rangeEnd"
                    } else {
                        "bytes=$rangeStart-"
                    }
                    append("Range: $rangeHeader\r\n")
                }
                append("\r\n")
            }
            
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()
            
            // Read response
            return readHttpResponse(socket)
        }
    }
    
    private fun readHttpResponse(socket: Socket): HttpResponse {
        val inputStream = socket.getInputStream()
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        
        // Read status line
        val statusLine = reader.readLine()
        val statusCode = statusLine.split(" ")[1].toInt()
        
        // Read headers
        val headerLines = mutableListOf<String>()
        var line: String?
        var contentLength = 0
        var contentType: String? = null
        
        while (reader.readLine().also { line = it } != null) {
            val ln = line ?: break
            if (ln.isEmpty()) break
            
            headerLines.add(ln)
            
            if (ln.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = ln.substringAfter(":").trim().toInt()
            }
            if (ln.startsWith("Content-Type:", ignoreCase = true)) {
                contentType = ln.substringAfter(":").trim().split(";")[0]
            }
        }
        
        // Read body as binary
        val bodyBytes = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = inputStream.read(buffer, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            buffer.sliceArray(0 until read)
        } else {
            ByteArray(0)
        }
        
        return HttpResponse(
            statusCode = statusCode,
            contentType = contentType,
            contentLength = contentLength,
            headers = headerLines.joinToString("\n"),
            body = bodyBytes
        )
    }
}
