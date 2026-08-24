package com.forge.os.data.server

import com.forge.os.data.api.ToolCancelResponse
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.service.PairingService
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
 * Unit tests for the POST /api/tool/{opId}/cancel endpoint
 */
class ForgeHttpServerToolCancelTest {
    
    private lateinit var server: ForgeHttpServer
    private lateinit var toolExecutionManager: ToolExecutionManager
    private lateinit var keyStore: SecureKeyStore
    private val json = Json { ignoreUnknownKeys = true }
    private val testPort = 8791 // Use different port to avoid conflicts
    private val testApiKey = "test-api-key-12345"
    
    @Before
    fun setUp() {
        // Create real ToolExecutionManager
        toolExecutionManager = ToolExecutionManager()
        
        // Mock dependencies
        val toolRegistry = mock(ToolRegistry::class.java)
        keyStore = mock(SecureKeyStore::class.java)
        `when`(keyStore.getCustomKey(ForgeHttpServer.KEY_ALIAS)).thenReturn(testApiKey)
        
        @Suppress("UNCHECKED_CAST")
        val reActAgent = mock(Lazy::class.java) as Lazy<ReActAgent>
        val pairingService = mock(PairingService::class.java)
        
        server = ForgeHttpServer(
            toolRegistry = toolRegistry,
            keyStore = keyStore,
            reActAgent = reActAgent,
            pairingService = pairingService,
            toolExecutionManager = toolExecutionManager
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
    
    @Test
    fun `POST cancel with valid opId for pending operation returns cancelled true`() {
        // Register a pending operation
        val opId = "test-op-123"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertTrue(cancelResponse.cancelled)
        
        // Verify the operation status was updated
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("cancelled", status?.status)
    }
    
    @Test
    fun `POST cancel with valid opId for running operation returns cancelled true`() {
        // Register an operation and mark it as running
        val opId = "test-op-456"
        toolExecutionManager.registerOperation(opId, "test_tool")
        toolExecutionManager.updateProgress(opId, 50, "In progress")
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertTrue(cancelResponse.cancelled)
        
        // Verify the operation status was updated
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("cancelled", status?.status)
    }
    
    @Test
    fun `POST cancel with valid opId for completed operation returns cancelled false`() {
        // Register an operation and mark it as completed
        val opId = "test-op-789"
        toolExecutionManager.registerOperation(opId, "test_tool")
        toolExecutionManager.setOutput(opId, "Operation completed")
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertFalse(cancelResponse.cancelled) // Already completed
        
        // Verify the operation status remains completed
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("completed", status?.status)
    }
    
    @Test
    fun `POST cancel with valid opId for failed operation returns cancelled false`() {
        // Register an operation and mark it as failed
        val opId = "test-op-failed"
        toolExecutionManager.registerOperation(opId, "test_tool")
        toolExecutionManager.setError(opId, com.forge.os.data.api.ToolError(
            code = "ERROR",
            message = "Operation failed"
        ))
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertFalse(cancelResponse.cancelled) // Already failed
        
        // Verify the operation status remains failed
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("failed", status?.status)
    }
    
    @Test
    fun `POST cancel with valid opId for already cancelled operation returns cancelled false`() {
        // Register an operation and cancel it once
        val opId = "test-op-cancelled"
        toolExecutionManager.registerOperation(opId, "test_tool")
        toolExecutionManager.cancelOperation(opId)
        
        // Try to cancel again
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertFalse(cancelResponse.cancelled) // Already cancelled
    }
    
    @Test
    fun `POST cancel with non-existent opId returns cancelled false`() {
        val opId = "non-existent-op"
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertFalse(cancelResponse.cancelled) // Operation doesn't exist
    }
    
    @Test
    fun `POST cancel with blank opId returns 400`() {
        val response = makePostRequest("/api/tool//cancel")
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
        assertTrue(response.body.contains("missing operation id"))
    }
    
    @Test
    fun `POST cancel without authentication returns 401`() {
        val opId = "test-op-123"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makePostRequest("/api/tool/$opId/cancel", authenticated = false)
        
        assertEquals(401, response.statusCode)
        assertTrue(response.body.contains("unauthorized"))
    }
    
    @Test
    fun `POST cancel with invalid Bearer token returns 401`() {
        val opId = "test-op-123"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makePostRequest("/api/tool/$opId/cancel", token = "invalid-token")
        
        assertEquals(401, response.statusCode)
        assertTrue(response.body.contains("unauthorized"))
    }
    
    @Test
    fun `POST cancel with valid Bearer token succeeds`() {
        val opId = "test-op-123"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makePostRequest("/api/tool/$opId/cancel", token = testApiKey)
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertTrue(cancelResponse.cancelled)
    }
    
    @Test
    fun `POST cancel multiple operations with different opIds`() {
        // Register multiple operations
        val opIds = listOf("op-1", "op-2", "op-3")
        opIds.forEach { opId ->
            toolExecutionManager.registerOperation(opId, "test_tool")
        }
        
        // Cancel all operations
        opIds.forEach { opId ->
            val response = makePostRequest("/api/tool/$opId/cancel")
            assertEquals(200, response.statusCode)
            
            val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
            assertEquals(opId, cancelResponse.opId)
            assertTrue(cancelResponse.cancelled)
        }
        
        // Verify all operations are cancelled
        opIds.forEach { opId ->
            val status = toolExecutionManager.getStatus(opId)
            assertEquals("cancelled", status?.status)
        }
    }
    
    @Test
    fun `POST cancel with opId containing special characters`() {
        val opId = "test-op-with-special-chars_123-456"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        
        val cancelResponse = json.decodeFromString(ToolCancelResponse.serializer(), response.body)
        assertEquals(opId, cancelResponse.opId)
        assertTrue(cancelResponse.cancelled)
    }
    
    @Test
    fun `POST cancel returns JSON with correct content type`() {
        val opId = "test-op-123"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makePostRequestWithHeaders("/api/tool/$opId/cancel")
        
        assertEquals(200, response.statusCode)
        assertTrue(response.headers.any { it.contains("application/json", ignoreCase = true) })
    }
    
    @Test
    fun `POST cancel sets operation end time`() {
        val opId = "test-op-time"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val startTime = System.currentTimeMillis()
        Thread.sleep(50)
        
        val response = makePostRequest("/api/tool/$opId/cancel")
        assertEquals(200, response.statusCode)
        
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status?.endTime)
        assertTrue(status?.endTime!! > startTime)
    }
    
    // Helper class for HTTP response
    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
        val headers: List<String> = emptyList()
    )
    
    // Helper function to make POST requests
    private fun makePostRequest(path: String, authenticated: Boolean = true, token: String? = null): HttpResponse {
        return makePostRequestWithHeaders(path, authenticated, token)
    }
    
    // Helper function to make POST requests with header tracking
    private fun makePostRequestWithHeaders(path: String, authenticated: Boolean = true, token: String? = null): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            // Write request
            writer.write("POST $path HTTP/1.1\r\n")
            writer.write("Host: localhost:$testPort\r\n")
            if (authenticated) {
                val authToken = token ?: testApiKey
                writer.write("Authorization: Bearer $authToken\r\n")
            }
            writer.write("Content-Length: 0\r\n")
            writer.write("\r\n")
            writer.flush()
            
            // Read response
            val statusLine = reader.readLine()
            val statusCode = statusLine.split(" ")[1].toInt()
            
            // Read headers
            val headers = mutableListOf<String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                val ln = line ?: break
                if (ln.isEmpty()) break
                headers.add(ln)
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
            
            return HttpResponse(statusCode, responseBody, headers)
        }
    }
}
