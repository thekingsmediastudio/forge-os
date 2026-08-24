package com.forge.os.data.server

import com.forge.os.data.api.PairingConfirmRequest
import com.forge.os.data.api.PairingConfirmResponse
import com.forge.os.data.api.PairingInitiateRequest
import com.forge.os.data.api.PairingInitiateResponse
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.service.PairingService
import dagger.Lazy
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Integration tests for the POST /api/pairing/initiate endpoint
 */
class ForgeHttpServerPairingTest {
    
    private lateinit var server: ForgeHttpServer
    private lateinit var pairingService: PairingService
    private val json = Json { ignoreUnknownKeys = true }
    private val testPort = 8790 // Use different port to avoid conflicts
    
    @Before
    fun setUp() {
        // Create real PairingService
        pairingService = PairingService()
        
        // Mock dependencies that aren't needed for pairing tests
        val toolRegistry = mock(ToolRegistry::class.java)
        val keyStore = mock(SecureKeyStore::class.java)
        @Suppress("UNCHECKED_CAST")
        val reActAgent = mock(Lazy::class.java) as Lazy<ReActAgent>
        
        server = ForgeHttpServer(
            toolRegistry = toolRegistry,
            keyStore = keyStore,
            reActAgent = reActAgent,
            pairingService = pairingService
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
    fun `POST pairing initiate returns valid pairing code`() {
        val request = PairingInitiateRequest(desktopName = "John's MacBook")
        val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
        
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(200, response.statusCode)
        
        val pairingResponse = json.decodeFromString(PairingInitiateResponse.serializer(), response.body)
        
        // Verify response structure
        assertEquals(6, pairingResponse.pairingCode.length)
        assertTrue(pairingResponse.pairingCode.all { it.isDigit() })
        assertEquals(300, pairingResponse.expiresIn)
    }
    
    @Test
    fun `POST pairing initiate without authentication succeeds`() {
        val request = PairingInitiateRequest(desktopName = "Test Desktop")
        val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
        
        // Should succeed without Bearer token
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(200, response.statusCode)
    }
    
    @Test
    fun `POST pairing initiate with missing desktop_name returns 400`() {
        val requestBody = """{"invalid_field": "value"}"""
        
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST pairing initiate with empty desktop_name returns 400`() {
        val request = PairingInitiateRequest(desktopName = "")
        val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
        
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST pairing initiate with blank desktop_name returns 400`() {
        val request = PairingInitiateRequest(desktopName = "   ")
        val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
        
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("error"))
    }
    
    @Test
    fun `POST pairing initiate generates unique codes for multiple requests`() {
        val codes = mutableSetOf<String>()
        
        repeat(5) { i ->
            val request = PairingInitiateRequest(desktopName = "Desktop $i")
            val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
            
            val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
            assertEquals(200, response.statusCode)
            
            val pairingResponse = json.decodeFromString(PairingInitiateResponse.serializer(), response.body)
            codes.add(pairingResponse.pairingCode)
        }
        
        // All codes should be unique
        assertEquals(5, codes.size)
    }
    
    @Test
    fun `POST pairing initiate with special characters in desktop_name succeeds`() {
        val request = PairingInitiateRequest(desktopName = "John's MacBook Pro (2021)")
        val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
        
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(200, response.statusCode)
    }
    
    @Test
    fun `POST pairing initiate with Unicode characters in desktop_name succeeds`() {
        val request = PairingInitiateRequest(desktopName = "José's 电脑")
        val requestBody = json.encodeToString(PairingInitiateRequest.serializer(), request)
        
        val response = makePostRequest("/api/pairing/initiate", requestBody, authenticated = false)
        
        assertEquals(200, response.statusCode)
    }
    
    // ─── POST /api/pairing/confirm Tests ─────────────────────────────────────
    
    @Test
    fun `POST pairing confirm with valid code returns token and device metadata`() {
        // First, generate a pairing code
        val initiateRequest = PairingInitiateRequest(desktopName = "Test Desktop")
        val initiateBody = json.encodeToString(PairingInitiateRequest.serializer(), initiateRequest)
        val initiateResponse = makePostRequest("/api/pairing/initiate", initiateBody, authenticated = false)
        val initiateData = json.decodeFromString(PairingInitiateResponse.serializer(), initiateResponse.body)
        val pairingCode = initiateData.pairingCode
        
        // Now confirm with that code
        val confirmRequest = PairingConfirmRequest(
            pairingCode = pairingCode,
            desktopId = "test-desktop-uuid"
        )
        val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(200, confirmResponse.statusCode)
        
        val confirmData = json.decodeFromString(PairingConfirmResponse.serializer(), confirmResponse.body)
        
        // Verify response structure
        assertTrue(confirmData.token.isNotBlank())
        assertTrue(confirmData.token.length >= 32) // UUID-based tokens are long
        assertTrue(confirmData.deviceId.isNotBlank())
        
        // Verify device metadata
        assertNotNull(confirmData.deviceMetadata)
        assertTrue(confirmData.deviceMetadata.model.isNotBlank())
        assertTrue(confirmData.deviceMetadata.androidVersion.isNotBlank())
        assertEquals("1.0.0", confirmData.deviceMetadata.forgeOsVersion)
        assertTrue(confirmData.deviceMetadata.capabilities.contains("tools"))
        assertTrue(confirmData.deviceMetadata.capabilities.contains("sync"))
        assertTrue(confirmData.deviceMetadata.capabilities.contains("clipboard"))
        assertTrue(confirmData.deviceMetadata.capabilities.contains("notifications"))
        assertTrue(confirmData.deviceMetadata.capabilities.contains("config"))
    }
    
    @Test
    fun `POST pairing confirm without authentication succeeds`() {
        // Generate a pairing code
        val initiateRequest = PairingInitiateRequest(desktopName = "Test Desktop")
        val initiateBody = json.encodeToString(PairingInitiateRequest.serializer(), initiateRequest)
        val initiateResponse = makePostRequest("/api/pairing/initiate", initiateBody, authenticated = false)
        val initiateData = json.decodeFromString(PairingInitiateResponse.serializer(), initiateResponse.body)
        
        // Confirm without Bearer token (should succeed)
        val confirmRequest = PairingConfirmRequest(
            pairingCode = initiateData.pairingCode,
            desktopId = "test-desktop-uuid"
        )
        val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(200, confirmResponse.statusCode)
    }
    
    @Test
    fun `POST pairing confirm with invalid code returns 400`() {
        val confirmRequest = PairingConfirmRequest(
            pairingCode = "999999", // Invalid code
            desktopId = "test-desktop-uuid"
        )
        val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(400, confirmResponse.statusCode)
        assertTrue(confirmResponse.body.contains("error"))
        assertTrue(confirmResponse.body.contains("invalid or expired"))
    }
    
    @Test
    fun `POST pairing confirm with expired code returns 400`() {
        // Generate a code and wait for it to expire is impractical
        // Instead test with a code that was already consumed
        val initiateRequest = PairingInitiateRequest(desktopName = "Test Desktop")
        val initiateBody = json.encodeToString(PairingInitiateRequest.serializer(), initiateRequest)
        val initiateResponse = makePostRequest("/api/pairing/initiate", initiateBody, authenticated = false)
        val initiateData = json.decodeFromString(PairingInitiateResponse.serializer(), initiateResponse.body)
        val pairingCode = initiateData.pairingCode
        
        // Use the code once
        val confirmRequest1 = PairingConfirmRequest(
            pairingCode = pairingCode,
            desktopId = "desktop-1"
        )
        val confirmBody1 = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest1)
        val confirmResponse1 = makePostRequest("/api/pairing/confirm", confirmBody1, authenticated = false)
        assertEquals(200, confirmResponse1.statusCode)
        
        // Try to use it again (should fail - single use)
        val confirmRequest2 = PairingConfirmRequest(
            pairingCode = pairingCode,
            desktopId = "desktop-2"
        )
        val confirmBody2 = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest2)
        val confirmResponse2 = makePostRequest("/api/pairing/confirm", confirmBody2, authenticated = false)
        
        assertEquals(400, confirmResponse2.statusCode)
        assertTrue(confirmResponse2.body.contains("error"))
        assertTrue(confirmResponse2.body.contains("invalid or expired"))
    }
    
    @Test
    fun `POST pairing confirm with missing pairing_code returns 400`() {
        val confirmBody = """{"desktop_id": "test-desktop-uuid"}"""
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(400, confirmResponse.statusCode)
        assertTrue(confirmResponse.body.contains("error"))
        assertTrue(confirmResponse.body.contains("missing"))
    }
    
    @Test
    fun `POST pairing confirm with missing desktop_id returns 400`() {
        val confirmBody = """{"pairing_code": "123456"}"""
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(400, confirmResponse.statusCode)
        assertTrue(confirmResponse.body.contains("error"))
        assertTrue(confirmResponse.body.contains("missing"))
    }
    
    @Test
    fun `POST pairing confirm with empty pairing_code returns 400`() {
        val confirmRequest = PairingConfirmRequest(
            pairingCode = "",
            desktopId = "test-desktop-uuid"
        )
        val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(400, confirmResponse.statusCode)
        assertTrue(confirmResponse.body.contains("error"))
    }
    
    @Test
    fun `POST pairing confirm with empty desktop_id returns 400`() {
        val confirmRequest = PairingConfirmRequest(
            pairingCode = "123456",
            desktopId = ""
        )
        val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
        val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
        
        assertEquals(400, confirmResponse.statusCode)
        assertTrue(confirmResponse.body.contains("error"))
    }
    
    @Test
    fun `POST pairing confirm generates unique tokens for different desktops`() {
        // Generate a pairing code
        val initiateRequest = PairingInitiateRequest(desktopName = "Test Desktop")
        val initiateBody = json.encodeToString(PairingInitiateRequest.serializer(), initiateRequest)
        
        val tokens = mutableSetOf<String>()
        
        repeat(3) { i ->
            // Get a new pairing code for each iteration
            val initResponse = makePostRequest("/api/pairing/initiate", initiateBody, authenticated = false)
            val initData = json.decodeFromString(PairingInitiateResponse.serializer(), initResponse.body)
            
            // Confirm with different desktop IDs
            val confirmRequest = PairingConfirmRequest(
                pairingCode = initData.pairingCode,
                desktopId = "desktop-$i"
            )
            val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
            val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
            
            assertEquals(200, confirmResponse.statusCode)
            
            val confirmData = json.decodeFromString(PairingConfirmResponse.serializer(), confirmResponse.body)
            tokens.add(confirmData.token)
        }
        
        // All tokens should be unique
        assertEquals(3, tokens.size)
    }
    
    @Test
    fun `POST pairing confirm generates unique device IDs`() {
        // Generate pairing codes
        val initiateRequest = PairingInitiateRequest(desktopName = "Test Desktop")
        val initiateBody = json.encodeToString(PairingInitiateRequest.serializer(), initiateRequest)
        
        val deviceIds = mutableSetOf<String>()
        
        repeat(3) { i ->
            val initResponse = makePostRequest("/api/pairing/initiate", initiateBody, authenticated = false)
            val initData = json.decodeFromString(PairingInitiateResponse.serializer(), initResponse.body)
            
            val confirmRequest = PairingConfirmRequest(
                pairingCode = initData.pairingCode,
                desktopId = "desktop-$i"
            )
            val confirmBody = json.encodeToString(PairingConfirmRequest.serializer(), confirmRequest)
            val confirmResponse = makePostRequest("/api/pairing/confirm", confirmBody, authenticated = false)
            
            assertEquals(200, confirmResponse.statusCode)
            
            val confirmData = json.decodeFromString(PairingConfirmResponse.serializer(), confirmResponse.body)
            deviceIds.add(confirmData.deviceId)
        }
        
        // All device IDs should be unique
        assertEquals(3, deviceIds.size)
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
                writer.write("Authorization: Bearer ${server.apiKey()}\r\n")
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
}
