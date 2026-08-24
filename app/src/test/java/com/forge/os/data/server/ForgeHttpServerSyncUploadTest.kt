package com.forge.os.data.server

import android.content.Context
import com.forge.os.data.api.FileUploadResponse
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
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest

/**
 * Integration tests for POST /api/sync/upload endpoint with multipart support
 */
class ForgeHttpServerSyncUploadTest {
    
    private lateinit var server: ForgeHttpServer
    private lateinit var syncService: SyncService
    private lateinit var sandboxManager: SandboxManager
    private lateinit var tempWorkspace: File
    private val json = Json { ignoreUnknownKeys = true }
    private val testPort = 8791 // Use different port to avoid conflicts
    
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
    fun `POST sync upload with single chunk file succeeds`() {
        val fileContent = "Hello, this is a test file!"
        val checksum = calculateSHA256(fileContent.toByteArray())
        
        val response = uploadChunk(
            path = "test.txt",
            chunk = 0,
            totalChunks = 1,
            checksum = checksum,
            data = fileContent.toByteArray()
        )
        
        assertEquals(200, response.statusCode)
        
        val uploadResponse = json.decodeFromString(FileUploadResponse.serializer(), response.body)
        assertTrue(uploadResponse.uploaded)
        assertEquals(listOf(0), uploadResponse.receivedChunks)
        assertTrue(uploadResponse.complete)
        
        // Verify file was written
        val targetFile = File(tempWorkspace, "test.txt")
        assertTrue(targetFile.exists())
        assertEquals(fileContent, targetFile.readText())
    }
    
    @Test
    fun `POST sync upload with multiple chunks succeeds`() {
        val chunk0 = "Part 1 of the file. ".toByteArray()
        val chunk1 = "Part 2 of the file. ".toByteArray()
        val chunk2 = "Part 3 of the file.".toByteArray()
        
        val fullContent = chunk0 + chunk1 + chunk2
        val checksum = calculateSHA256(fullContent)
        
        // Upload chunk 0
        val response0 = uploadChunk(
            path = "multipart.txt",
            chunk = 0,
            totalChunks = 3,
            checksum = checksum,
            data = chunk0
        )
        assertEquals(200, response0.statusCode)
        val upload0 = json.decodeFromString(FileUploadResponse.serializer(), response0.body)
        assertTrue(upload0.uploaded)
        assertEquals(listOf(0), upload0.receivedChunks)
        assertFalse(upload0.complete)
        
        // Upload chunk 1
        val response1 = uploadChunk(
            path = "multipart.txt",
            chunk = 1,
            totalChunks = 3,
            checksum = checksum,
            data = chunk1
        )
        assertEquals(200, response1.statusCode)
        val upload1 = json.decodeFromString(FileUploadResponse.serializer(), response1.body)
        assertTrue(upload1.uploaded)
        assertEquals(listOf(0, 1), upload1.receivedChunks)
        assertFalse(upload1.complete)
        
        // Upload chunk 2 (final)
        val response2 = uploadChunk(
            path = "multipart.txt",
            chunk = 2,
            totalChunks = 3,
            checksum = checksum,
            data = chunk2
        )
        assertEquals(200, response2.statusCode)
        val upload2 = json.decodeFromString(FileUploadResponse.serializer(), response2.body)
        assertTrue(upload2.uploaded)
        assertEquals(listOf(0, 1, 2), upload2.receivedChunks)
        assertTrue(upload2.complete)
        
        // Verify file was assembled correctly
        val targetFile = File(tempWorkspace, "multipart.txt")
        assertTrue(targetFile.exists())
        assertArrayEquals(fullContent, targetFile.readBytes())
    }
    
    @Test
    fun `POST sync upload with chunks in non-sequential order succeeds`() {
        val chunk0 = "First ".toByteArray()
        val chunk1 = "Second ".toByteArray()
        val chunk2 = "Third".toByteArray()
        
        val fullContent = chunk0 + chunk1 + chunk2
        val checksum = calculateSHA256(fullContent)
        
        // Upload in order: 1, 0, 2
        uploadChunk("out-of-order.txt", 1, 3, checksum, chunk1)
        uploadChunk("out-of-order.txt", 0, 3, checksum, chunk0)
        val finalResponse = uploadChunk("out-of-order.txt", 2, 3, checksum, chunk2)
        
        assertEquals(200, finalResponse.statusCode)
        val uploadResponse = json.decodeFromString(FileUploadResponse.serializer(), finalResponse.body)
        assertTrue(uploadResponse.complete)
        
        // Verify file content is correct despite out-of-order upload
        val targetFile = File(tempWorkspace, "out-of-order.txt")
        assertTrue(targetFile.exists())
        assertArrayEquals(fullContent, targetFile.readBytes())
    }
    
    @Test
    fun `POST sync upload with binary data succeeds`() {
        // Create binary data with various byte values
        val binaryData = ByteArray(256) { it.toByte() }
        val checksum = calculateSHA256(binaryData)
        
        val response = uploadChunk(
            path = "binary.dat",
            chunk = 0,
            totalChunks = 1,
            checksum = checksum,
            data = binaryData
        )
        
        assertEquals(200, response.statusCode)
        
        val uploadResponse = json.decodeFromString(FileUploadResponse.serializer(), response.body)
        assertTrue(uploadResponse.complete)
        
        // Verify binary data integrity
        val targetFile = File(tempWorkspace, "binary.dat")
        assertTrue(targetFile.exists())
        assertArrayEquals(binaryData, targetFile.readBytes())
    }
    
    @Test
    fun `POST sync upload to subdirectory creates parent directories`() {
        val fileContent = "File in subdirectory".toByteArray()
        val checksum = calculateSHA256(fileContent)
        
        val response = uploadChunk(
            path = "subdir/nested/file.txt",
            chunk = 0,
            totalChunks = 1,
            checksum = checksum,
            data = fileContent
        )
        
        assertEquals(200, response.statusCode)
        
        // Verify file exists in subdirectory
        val targetFile = File(tempWorkspace, "subdir/nested/file.txt")
        assertTrue(targetFile.exists())
        assertArrayEquals(fileContent, targetFile.readBytes())
    }
    
    @Test
    fun `POST sync upload with incorrect checksum fails`() {
        val fileContent = "Test content".toByteArray()
        val wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"
        
        val response = uploadChunk(
            path = "bad-checksum.txt",
            chunk = 0,
            totalChunks = 1,
            checksum = wrongChecksum,
            data = fileContent
        )
        
        // Should return 500 error due to checksum mismatch
        assertEquals(500, response.statusCode)
        assertTrue(response.body.contains("error"))
        assertTrue(response.body.contains("Checksum verification failed") || response.body.contains("checksum"))
        
        // File should not exist
        val targetFile = File(tempWorkspace, "bad-checksum.txt")
        assertFalse(targetFile.exists())
    }
    
    @Test
    fun `POST sync upload requires authentication`() {
        val fileContent = "Test".toByteArray()
        val checksum = calculateSHA256(fileContent)
        
        val response = uploadChunk(
            path = "test.txt",
            chunk = 0,
            totalChunks = 1,
            checksum = checksum,
            data = fileContent,
            authenticated = false
        )
        
        assertEquals(401, response.statusCode)
        assertTrue(response.body.contains("unauthorized"))
    }
    
    @Test
    fun `POST sync upload without multipart content type fails`() {
        val response = makePostRequest(
            path = "/api/sync/upload",
            body = """{"path": "test.txt"}""",
            contentType = "application/json"
        )
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Content-Type must be multipart/form-data"))
    }
    
    @Test
    fun `POST sync upload with missing boundary fails`() {
        Socket("localhost", testPort).use { socket ->
            val output = socket.getOutputStream()
            
            val request = buildString {
                append("POST /api/sync/upload HTTP/1.1\r\n")
                append("Host: localhost:$testPort\r\n")
                append("Authorization: Bearer ${server.apiKey()}\r\n")
                append("Content-Type: multipart/form-data\r\n") // Missing boundary
                append("Content-Length: 0\r\n")
                append("\r\n")
            }
            
            output.write(request.toByteArray(Charsets.UTF_8))
            output.flush()
            
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
            val statusCode = statusLine.split(" ")[1].toInt()
            
            assertEquals(400, statusCode)
        }
    }
    
    @Test
    fun `POST sync upload with missing fields fails`() {
        // Create a multipart request but omit required fields
        val boundary = "----Boundary${System.currentTimeMillis()}"
        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"path\"\r\n\r\n")
            append("test.txt\r\n")
            append("--$boundary--\r\n")
        }
        
        val response = makeMultipartRequest(boundary, multipartBody.toByteArray(Charsets.ISO_8859_1))
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Missing required fields"))
    }
    
    @Test
    fun `POST sync upload with negative chunk index fails`() {
        val fileContent = "Test".toByteArray()
        val checksum = calculateSHA256(fileContent)
        
        val response = uploadChunk(
            path = "test.txt",
            chunk = -1,
            totalChunks = 1,
            checksum = checksum,
            data = fileContent
        )
        
        assertEquals(500, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST sync upload with chunk index exceeding total chunks fails`() {
        val fileContent = "Test".toByteArray()
        val checksum = calculateSHA256(fileContent)
        
        val response = uploadChunk(
            path = "test.txt",
            chunk = 5,
            totalChunks = 3,
            checksum = checksum,
            data = fileContent
        )
        
        assertEquals(500, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST sync upload with mismatched totalChunks fails`() {
        val chunk0 = "Part 1".toByteArray()
        val chunk1 = "Part 2".toByteArray()
        val fullContent = chunk0 + chunk1
        val checksum = calculateSHA256(fullContent)
        
        // Upload first chunk with totalChunks=2
        val response0 = uploadChunk("mismatch.txt", 0, 2, checksum, chunk0)
        assertEquals(200, response0.statusCode)
        
        // Try to upload second chunk with totalChunks=3 (mismatch)
        val response1 = uploadChunk("mismatch.txt", 1, 3, checksum, chunk1)
        
        assertEquals(500, response1.statusCode)
        assertTrue(response1.body.contains("Total chunks mismatch"))
    }
    
    @Test
    fun `POST sync upload with mismatched checksum fails`() {
        val chunk0 = "Part 1".toByteArray()
        val chunk1 = "Part 2".toByteArray()
        val checksum1 = calculateSHA256(chunk0)
        val checksum2 = calculateSHA256(chunk1)
        
        // Upload first chunk with checksum1
        val response0 = uploadChunk("mismatch.txt", 0, 2, checksum1, chunk0)
        assertEquals(200, response0.statusCode)
        
        // Try to upload second chunk with different checksum
        val response1 = uploadChunk("mismatch.txt", 1, 2, checksum2, chunk1)
        
        assertEquals(500, response1.statusCode)
        assertTrue(response1.body.contains("Checksum mismatch"))
    }
    
    @Test
    fun `POST sync upload with large chunk succeeds`() {
        // Create a 1MB chunk (typical chunk size)
        val largeChunk = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val checksum = calculateSHA256(largeChunk)
        
        val response = uploadChunk(
            path = "large.dat",
            chunk = 0,
            totalChunks = 1,
            checksum = checksum,
            data = largeChunk
        )
        
        assertEquals(200, response.statusCode)
        
        val uploadResponse = json.decodeFromString(FileUploadResponse.serializer(), response.body)
        assertTrue(uploadResponse.complete)
        
        // Verify file size
        val targetFile = File(tempWorkspace, "large.dat")
        assertTrue(targetFile.exists())
        assertEquals(1024 * 1024, targetFile.length())
    }
    
    // Helper functions
    
    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )
    
    private fun uploadChunk(
        path: String,
        chunk: Int,
        totalChunks: Int,
        checksum: String,
        data: ByteArray,
        authenticated: Boolean = true
    ): HttpResponse {
        val boundary = "----Boundary${System.currentTimeMillis()}"
        val multipartBody = buildMultipartBody(
            boundary,
            mapOf(
                "path" to path.toByteArray(Charsets.UTF_8),
                "chunk" to chunk.toString().toByteArray(Charsets.UTF_8),
                "totalChunks" to totalChunks.toString().toByteArray(Charsets.UTF_8),
                "checksum" to checksum.toByteArray(Charsets.UTF_8),
                "data" to data
            )
        )
        
        return makeMultipartRequest(boundary, multipartBody, authenticated)
    }
    
    private fun buildMultipartBody(boundary: String, parts: Map<String, ByteArray>): ByteArray {
        val output = mutableListOf<Byte>()
        
        parts.forEach { (name, value) ->
            output.addAll("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1).toList())
            output.addAll("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.ISO_8859_1).toList())
            output.addAll(value.toList())
            output.addAll("\r\n".toByteArray(Charsets.ISO_8859_1).toList())
        }
        
        output.addAll("--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1).toList())
        
        return output.toByteArray()
    }
    
    private fun makeMultipartRequest(
        boundary: String,
        body: ByteArray,
        authenticated: Boolean = true
    ): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val output = socket.getOutputStream()
            
            // Write headers
            val headers = buildString {
                append("POST /api/sync/upload HTTP/1.1\r\n")
                append("Host: localhost:$testPort\r\n")
                if (authenticated) {
                    append("Authorization: Bearer ${server.apiKey()}\r\n")
                }
                append("Content-Type: multipart/form-data; boundary=$boundary\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
            
            // Read response
            return readHttpResponse(socket)
        }
    }
    
    private fun makePostRequest(
        path: String,
        body: String,
        contentType: String = "application/json",
        authenticated: Boolean = true
    ): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val output = socket.getOutputStream()
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            
            val headers = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: localhost:$testPort\r\n")
                if (authenticated) {
                    append("Authorization: Bearer ${server.apiKey()}\r\n")
                }
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("\r\n")
            }
            
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(bodyBytes)
            output.flush()
            
            return readHttpResponse(socket)
        }
    }
    
    private fun readHttpResponse(socket: Socket): HttpResponse {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        
        // Read status line
        val statusLine = reader.readLine()
        val statusCode = statusLine.split(" ")[1].toInt()
        
        // Read headers
        var line: String?
        var contentLength = 0
        while (reader.readLine().also { line = it } != null) {
            val ln = line ?: break
            if (ln.isEmpty()) break
            if (ln.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = ln.substringAfter(":").trim().toInt()
            }
        }
        
        // Read body
        val responseBody = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buffer, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(buffer, 0, read)
        } else {
            ""
        }
        
        return HttpResponse(statusCode, responseBody)
    }
    
    private fun calculateSHA256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
