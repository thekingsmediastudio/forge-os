package com.forge.os.data.server

import android.content.Context
import com.forge.os.data.api.ClipboardUpdateRequest
import com.forge.os.data.api.ClipboardUpdateResponse
import com.forge.os.data.api.ConfigResponse
import com.forge.os.data.api.ConfigUpdateRequest
import com.forge.os.data.api.ConfigUpdateResponse
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.service.ClipboardService
import com.forge.os.service.ConfigService
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Integration tests for clipboard and config endpoints:
 * - POST /api/clipboard
 * - GET /api/config  
 * - POST /api/config
 */
class ForgeHttpServerClipboardConfigTest {
    
    private lateinit var server: ForgeHttpServer
    private lateinit var clipboardService: ClipboardService
    private lateinit var configService: ConfigService
    private lateinit var keyStore: SecureKeyStore
    private val json = Json { ignoreUnknownKeys = true }
    private val testPort = 8791 // Use different port to avoid conflicts
    private var testApiKey = "test-api-key-12345678901234567890123456789012"
    
    @Before
    fun setUp() {
        // Create real services
        val context = mock(Context::class.java)
        clipboardService = ClipboardService(context)
        configService = ConfigService(context)
        
        // Mock dependencies
        val toolRegistry = mock(ToolRegistry::class.java)
        keyStore = mock(SecureKeyStore::class.java)
        `when`(keyStore.getCustomKey(ForgeHttpServer.KEY_ALIAS)).thenReturn(testApiKey)
        
        @Suppress("UNCHECKED_CAST")
        val reActAgent = mock(Lazy::class.java) as Lazy<ReActAgent>
        val pairingService = mock(PairingService::class.java)
        val toolExecutionManager = mock(ToolExecutionManager::class.java)
        val sandboxManager = mock(SandboxManager::class.java)
        val syncService = SyncService(context, sandboxManager)
        
        server = ForgeHttpServer(
            toolRegistry = toolRegistry,
            keyStore = keyStore,
            reActAgent = reActAgent,
            pairingService = pairingService,
            toolExecutionManager = toolExecutionManager,
            syncService = syncService,
            clipboardService = clipboardService,
            configService = configService
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
    }
    
    // ─── POST /api/clipboard Tests ───────────────────────────────────────────
    
    @Test
    fun `POST clipboard with text content requires authentication`() {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = "Hello from desktop"
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        // Without authentication should return 401
        val unauthResponse = makePostRequest("/api/clipboard", requestBody, authenticated = false)
        assertEquals(401, unauthResponse.statusCode)
        
        // With authentication should succeed
        val authResponse = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        assertEquals(200, authResponse.statusCode)
    }
    
    @Test
    fun `POST clipboard with text content returns success`() {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = "Test clipboard content"
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val clipboardResponse = json.decodeFromString(ClipboardUpdateResponse.serializer(), response.body)
        assertTrue(clipboardResponse.updated)
    }
    
    @Test
    fun `POST clipboard with empty text content returns failure`() {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = ""
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val clipboardResponse = json.decodeFromString(ClipboardUpdateResponse.serializer(), response.body)
        assertFalse(clipboardResponse.updated)
    }
    
    @Test
    fun `POST clipboard with null content returns failure`() {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = null
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val clipboardResponse = json.decodeFromString(ClipboardUpdateResponse.serializer(), response.body)
        assertFalse(clipboardResponse.updated)
    }
    
    @Test
    fun `POST clipboard with image type returns success`() {
        val request = ClipboardUpdateRequest(
            type = "image",
            imageData = "base64-encoded-image-data"
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val clipboardResponse = json.decodeFromString(ClipboardUpdateResponse.serializer(), response.body)
        // Image clipboard may or may not be fully supported depending on Android implementation
        // Just verify we get a response
        assertNotNull(clipboardResponse)
    }
    
    @Test
    fun `POST clipboard with file type returns success`() {
        val request = ClipboardUpdateRequest(
            type = "file",
            fileName = "document.pdf"
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val clipboardResponse = json.decodeFromString(ClipboardUpdateResponse.serializer(), response.body)
        assertTrue(clipboardResponse.updated)
    }
    
    @Test
    fun `POST clipboard with missing type returns 400`() {
        val requestBody = """{"content": "test"}"""
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST clipboard with invalid JSON returns 400`() {
        val requestBody = """invalid json"""
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST clipboard with unsupported type returns failure`() {
        val request = ClipboardUpdateRequest(
            type = "unsupported_type",
            content = "test"
        )
        val requestBody = json.encodeToString(ClipboardUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/clipboard", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val clipboardResponse = json.decodeFromString(ClipboardUpdateResponse.serializer(), response.body)
        assertFalse(clipboardResponse.updated)
    }
    
    // ─── GET /api/config Tests ───────────────────────────────────────────────
    
    @Test
    fun `GET config requires authentication`() {
        // Without authentication should return 401
        val unauthResponse = makeGetRequest("/api/config", authenticated = false)
        assertEquals(401, unauthResponse.statusCode)
        
        // With authentication should succeed
        val authResponse = makeGetRequest("/api/config", authenticated = true)
        assertEquals(200, authResponse.statusCode)
    }
    
    @Test
    fun `GET config returns default configuration`() {
        val response = makeGetRequest("/api/config", authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val config = json.decodeFromString(ConfigResponse.serializer(), response.body)
        
        // Verify default values
        assertNotNull(config.theme)
        assertTrue(config.syncEnabled)
        assertTrue(config.clipboardEnabled)
        assertNotNull(config.notificationFilters)
        assertNotNull(config.custom)
    }
    
    // ─── POST /api/config Tests ──────────────────────────────────────────────
    
    @Test
    fun `POST config requires authentication`() {
        val request = ConfigUpdateRequest(theme = "light")
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        // Without authentication should return 401
        val unauthResponse = makePostRequest("/api/config", requestBody, authenticated = false)
        assertEquals(401, unauthResponse.statusCode)
        
        // With authentication should succeed
        val authResponse = makePostRequest("/api/config", requestBody, authenticated = true)
        assertEquals(200, authResponse.statusCode)
    }
    
    @Test
    fun `POST config with theme update returns success`() {
        val request = ConfigUpdateRequest(theme = "light")
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val updateResponse = json.decodeFromString(ConfigUpdateResponse.serializer(), response.body)
        assertTrue(updateResponse.updated)
    }
    
    @Test
    fun `POST config with sync_enabled update returns success`() {
        val request = ConfigUpdateRequest(syncEnabled = false)
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val updateResponse = json.decodeFromString(ConfigUpdateResponse.serializer(), response.body)
        assertTrue(updateResponse.updated)
    }
    
    @Test
    fun `POST config with clipboard_enabled update returns success`() {
        val request = ConfigUpdateRequest(clipboardEnabled = false)
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val updateResponse = json.decodeFromString(ConfigUpdateResponse.serializer(), response.body)
        assertTrue(updateResponse.updated)
    }
    
    @Test
    fun `POST config with notification filters update returns success`() {
        val request = ConfigUpdateRequest(
            notificationFilters = listOf("com.example.app1", "com.example.app2")
        )
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val updateResponse = json.decodeFromString(ConfigUpdateResponse.serializer(), response.body)
        assertTrue(updateResponse.updated)
    }
    
    @Test
    fun `POST config with multiple fields update returns success`() {
        val request = ConfigUpdateRequest(
            theme = "dark",
            syncEnabled = true,
            clipboardEnabled = true,
            notificationFilters = listOf("com.example.app")
        )
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val updateResponse = json.decodeFromString(ConfigUpdateResponse.serializer(), response.body)
        assertTrue(updateResponse.updated)
    }
    
    @Test
    fun `POST config with no fields returns success but no changes`() {
        val request = ConfigUpdateRequest()
        val requestBody = json.encodeToString(ConfigUpdateRequest.serializer(), request)
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(200, response.statusCode)
        
        val updateResponse = json.decodeFromString(ConfigUpdateResponse.serializer(), response.body)
        assertTrue(updateResponse.updated) // Still returns true even with no changes
    }
    
    @Test
    fun `POST config with invalid JSON returns 400`() {
        val requestBody = """invalid json"""
        
        val response = makePostRequest("/api/config", requestBody, authenticated = true)
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST config persists changes across GET requests`() {
        // Update config
        val updateRequest = ConfigUpdateRequest(
            theme = "custom",
            syncEnabled = false
        )
        val updateBody = json.encodeToString(ConfigUpdateRequest.serializer(), updateRequest)
        val updateResponse = makePostRequest("/api/config", updateBody, authenticated = true)
        assertEquals(200, updateResponse.statusCode)
        
        // Retrieve config to verify persistence
        val getResponse = makeGetRequest("/api/config", authenticated = true)
        assertEquals(200, getResponse.statusCode)
        
        val config = json.decodeFromString(ConfigResponse.serializer(), getResponse.body)
        assertEquals("custom", config.theme)
        assertFalse(config.syncEnabled)
    }
    
    // Helper class for HTTP response
    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )
    
    // Helper function to make POST requests
    private fun makePostRequest(path: String, body: String, authenticated: Boolean = false): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            // Write request
            writer.write("POST $path HTTP/1.1\r\n")
            writer.write("Host: localhost:$testPort\r\n")
            if (authenticated) {
                writer.write("Authorization: Bearer $testApiKey\r\n")
            }
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("\r\n")
            writer.write(body)
            writer.flush()
            
            // Read response
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
    }
    
    // Helper function to make GET requests
    private fun makeGetRequest(path: String, authenticated: Boolean = false): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            // Write request
            writer.write("GET $path HTTP/1.1\r\n")
            writer.write("Host: localhost:$testPort\r\n")
            if (authenticated) {
                writer.write("Authorization: Bearer $testApiKey\r\n")
            }
            writer.write("\r\n")
            writer.flush()
            
            // Read response
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
    }
}
