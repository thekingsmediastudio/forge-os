package com.forge.os.data.server

import com.forge.os.data.api.ToolError
import com.forge.os.data.api.ToolStatusResponse
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
import org.mockito.Mockito.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Integration tests for GET /api/tool/{opId}/status endpoint
 */
class ForgeHttpServerToolStatusTest {
    
    private lateinit var server: ForgeHttpServer
    private lateinit var toolExecutionManager: ToolExecutionManager
    private val json = Json { ignoreUnknownKeys = true }
    private val testPort = 8791
    
    @Before
    fun setUp() {
        // Create real ToolExecutionManager
        toolExecutionManager = ToolExecutionManager()
        
        // Mock other dependencies
        val toolRegistry = mock(ToolRegistry::class.java)
        val keyStore = mock(SecureKeyStore::class.java)
        val pairingService = mock(PairingService::class.java)
        @Suppress("UNCHECKED_CAST")
        val reActAgent = mock(Lazy::class.java) as Lazy<ReActAgent>
        
        // Mock apiKey
        `when`(keyStore.getCustomKey(ForgeHttpServer.KEY_ALIAS)).thenReturn("test-api-key-12345")
        
        server = ForgeHttpServer(
            toolRegistry = toolRegistry,
            keyStore = keyStore,
            reActAgent = reActAgent,
            pairingService = pairingService,
            toolExecutionManager = toolExecutionManager
        )
        
        server.start(testPort)
        Thread.sleep(100)
    }
    
    @After
    fun tearDown() {
        server.stop()
        Thread.sleep(100)
    }
    
    @Test
    fun `GET tool status returns pending operation correctly`() {
        // Register an operation
        val opId = "test-op-123"
        toolExecutionManager.registerOperation(opId, "file_read")
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals(opId, status.opId)
        assertEquals("file_read", status.toolName)
        assertEquals("pending", status.status)
        assertTrue(status.startTime > 0)
        assertNull(status.endTime)
        assertNull(status.progress)
        assertNull(status.output)
        assertNull(status.error)
    }
    
    @Test
    fun `GET tool status returns running operation with progress`() {
        val opId = "test-op-running"
        toolExecutionManager.registerOperation(opId, "web_search")
        toolExecutionManager.updateProgress(opId, 45, "Searching...")
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("running", status.status)
        assertNotNull(status.progress)
        assertEquals(45, status.progress?.percent)
        assertEquals("Searching...", status.progress?.message)
    }
    
    @Test
    fun `GET tool status returns completed operation with output`() {
        val opId = "test-op-completed"
        toolExecutionManager.registerOperation(opId, "calculate")
        toolExecutionManager.setOutput(opId, "Result: 42")
        toolExecutionManager.setResourceUsage(opId, 1500, 1024000)
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("completed", status.status)
        assertEquals("Result: 42", status.output)
        assertNotNull(status.endTime)
        assertNotNull(status.resourceUsage)
        assertEquals(1500L, status.resourceUsage?.cpuMs)
        assertEquals(1024000L, status.resourceUsage?.memoryBytes)
    }
    
    @Test
    fun `GET tool status returns failed operation with error`() {
        val opId = "test-op-failed"
        toolExecutionManager.registerOperation(opId, "file_write")
        toolExecutionManager.setError(opId, ToolError(
            code = "ERR_PERMISSION_DENIED",
            message = "Permission denied",
            stackTrace = "at FileWriter.write(line 42)"
        ))
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("failed", status.status)
        assertNotNull(status.error)
        assertEquals("ERR_PERMISSION_DENIED", status.error?.code)
        assertEquals("Permission denied", status.error?.message)
        assertEquals("at FileWriter.write(line 42)", status.error?.stackTrace)
        assertNotNull(status.endTime)
    }
    
    @Test
    fun `GET tool status returns cancelled operation`() {
        val opId = "test-op-cancelled"
        toolExecutionManager.registerOperation(opId, "long_running_task")
        toolExecutionManager.updateStatus(opId, "running")
        toolExecutionManager.cancelOperation(opId)
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("cancelled", status.status)
        assertNotNull(status.endTime)
    }
    
    @Test
    fun `GET tool status returns 404 for non-existent operation`() {
        val response = makeGetRequest("/api/tool/non-existent-op/status")
        
        assertEquals(404, response.statusCode)
        assertTrue(response.body.contains("error"))
        assertTrue(response.body.contains("operation not found"))
    }
    
    @Test
    fun `GET tool status requires authentication`() {
        val opId = "test-op-auth"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makeGetRequest("/api/tool/$opId/status", authenticated = false)
        
        assertEquals(401, response.statusCode)
        assertTrue(response.body.contains("unauthorized"))
    }
    
    @Test
    fun `GET tool status with empty opId returns 400`() {
        val response = makeGetRequest("/api/tool//status")
        
        // The path will not match the pattern, so it should return 404
        assertEquals(404, response.statusCode)
    }
    
    @Test
    fun `GET tool status tracks operation lifecycle`() {
        val opId = "lifecycle-op"
        
        // 1. Pending
        toolExecutionManager.registerOperation(opId, "multi_step_task")
        var response = makeGetRequest("/api/tool/$opId/status")
        var status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("pending", status.status)
        
        // 2. Running with progress
        toolExecutionManager.updateProgress(opId, 33, "Step 1/3")
        response = makeGetRequest("/api/tool/$opId/status")
        status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("running", status.status)
        assertEquals(33, status.progress?.percent)
        
        // 3. More progress
        toolExecutionManager.updateProgress(opId, 66, "Step 2/3")
        response = makeGetRequest("/api/tool/$opId/status")
        status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals(66, status.progress?.percent)
        
        // 4. Completed
        toolExecutionManager.setOutput(opId, "All steps completed")
        response = makeGetRequest("/api/tool/$opId/status")
        status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("completed", status.status)
        assertEquals("All steps completed", status.output)
        assertNotNull(status.endTime)
    }
    
    @Test
    fun `GET tool status handles special characters in opId`() {
        val opId = "op-with-dashes-123-abc"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals(opId, status.opId)
    }
    
    @Test
    fun `GET tool status handles UUID-style opIds`() {
        val opId = "550e8400-e29b-41d4-a716-446655440000"
        toolExecutionManager.registerOperation(opId, "test_tool")
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals(opId, status.opId)
    }
    
    @Test
    fun `GET tool status can query multiple different operations`() {
        val opIds = listOf("op-1", "op-2", "op-3")
        val toolNames = listOf("tool_1", "tool_2", "tool_3")
        
        // Register multiple operations
        opIds.forEachIndexed { index, opId ->
            toolExecutionManager.registerOperation(opId, toolNames[index])
        }
        
        // Query each operation
        opIds.forEachIndexed { index, opId ->
            val response = makeGetRequest("/api/tool/$opId/status")
            assertEquals(200, response.statusCode)
            
            val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
            assertEquals(opId, status.opId)
            assertEquals(toolNames[index], status.toolName)
        }
    }
    
    @Test
    fun `GET tool status preserves progress after completion`() {
        val opId = "progress-persist-op"
        toolExecutionManager.registerOperation(opId, "test_tool")
        toolExecutionManager.updateProgress(opId, 100, "Final step")
        toolExecutionManager.setOutput(opId, "Done")
        
        val response = makeGetRequest("/api/tool/$opId/status")
        
        assertEquals(200, response.statusCode)
        val status = json.decodeFromString(ToolStatusResponse.serializer(), response.body)
        assertEquals("completed", status.status)
        assertEquals(100, status.progress?.percent)
        assertEquals("Final step", status.progress?.message)
    }
    
    // Helper classes and methods
    
    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )
    
    private fun makeGetRequest(path: String, authenticated: Boolean = true): HttpResponse {
        Socket("localhost", testPort).use { socket ->
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            
            // Write request
            writer.write("GET $path HTTP/1.1\r\n")
            writer.write("Host: localhost:$testPort\r\n")
            if (authenticated) {
                writer.write("Authorization: Bearer test-api-key-12345\r\n")
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
