package com.forge.os.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for EventBroadcaster service
 * Tests event broadcasting, queuing, and filtering
 */
class EventBroadcasterTest {
    
    private lateinit var eventBroadcaster: EventBroadcaster
    
    @Before
    fun setUp() {
        eventBroadcaster = EventBroadcaster()
    }
    
    @After
    fun tearDown() {
        eventBroadcaster.shutdown()
    }
    
    @Test
    fun `emitToolStart broadcasts tool_start event`() = runBlocking {
        val opId = "test-op-123"
        val toolName = "test_tool"
        val args = mapOf("param1" to "value1")
        
        // Collect first event
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_START, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains(toolName))
        }
        
        // Give collector time to subscribe
        delay(100)
        
        // Emit event
        eventBroadcaster.emitToolStart(opId, toolName, args)
        
        // Wait for assertion
        job.join()
    }
    
    @Test
    fun `emitToolProgress broadcasts tool_progress event`() = runBlocking {
        val opId = "test-op-456"
        val percent = 50
        val message = "Processing..."
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_PROGRESS, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains("50"))
            assertTrue(event.payload.contains(message))
        }
        
        delay(100)
        eventBroadcaster.emitToolProgress(opId, percent, message)
        job.join()
    }
    
    @Test
    fun `emitToolComplete broadcasts tool_complete event with resource usage`() = runBlocking {
        val opId = "test-op-789"
        val output = "Test output"
        val duration = 1500L
        val resourceUsage = ResourceUsagePayload(cpuMs = 100, memoryBytes = 2048)
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_COMPLETE, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains(output))
            assertTrue(event.payload.contains("1500"))
            assertTrue(event.payload.contains("100")) // cpuMs
            assertTrue(event.payload.contains("2048")) // memoryBytes
        }
        
        delay(100)
        eventBroadcaster.emitToolComplete(opId, output, duration, resourceUsage)
        job.join()
    }
    
    @Test
    fun `emitToolError broadcasts tool_error event`() = runBlocking {
        val opId = "test-op-error"
        val error = ToolErrorPayload(
            code = "TEST_ERROR",
            message = "Test error message",
            stackTrace = "stack trace here"
        )
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_ERROR, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains("TEST_ERROR"))
            assertTrue(event.payload.contains("Test error message"))
        }
        
        delay(100)
        eventBroadcaster.emitToolError(opId, error)
        job.join()
    }
    
    @Test
    fun `emitFileModified broadcasts file_modified event`() = runBlocking {
        val path = "/workspace/test.txt"
        val checksum = "abc123"
        val size = 1024L
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.FILE_MODIFIED, event.type)
            assertTrue(event.payload.contains(path))
            assertTrue(event.payload.contains(checksum))
            assertTrue(event.payload.contains("1024"))
        }
        
        delay(100)
        eventBroadcaster.emitFileModified(path, checksum, size)
        job.join()
    }
    
    @Test
    fun `emitNotification broadcasts notification event`() = runBlocking {
        val id = "notif-123"
        val packageName = "com.example.app"
        val title = "Test Notification"
        val body = "This is a test"
        val actions = listOf(
            NotificationAction("action1", "Reply"),
            NotificationAction("action2", "Dismiss")
        )
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.NOTIFICATION, event.type)
            assertTrue(event.payload.contains(id))
            assertTrue(event.payload.contains(packageName))
            assertTrue(event.payload.contains(title))
            assertTrue(event.payload.contains(body))
        }
        
        delay(100)
        eventBroadcaster.emitNotification(id, packageName, title, body, null, actions)
        job.join()
    }
    
    @Test
    fun `event queue respects 1000 event limit`() = runBlocking {
        // Emit more than 1000 events
        repeat(1500) { i ->
            eventBroadcaster.broadcast(EventMessage(
                type = EventType.TOOL_START,
                timestamp = System.currentTimeMillis(),
                payload = """{"opId":"op-$i","toolName":"test","args":{}}"""
            ))
        }
        
        // Give time for events to be processed
        delay(500)
        
        // Check that queue size is limited to 1000
        val recentEvents = eventBroadcaster.getRecentEvents(2000)
        assertTrue(recentEvents.size <= 1000)
    }
    
    @Test
    fun `getRecentEvents returns last N events`() = runBlocking {
        // Emit 20 events
        repeat(20) { i ->
            eventBroadcaster.broadcast(EventMessage(
                type = EventType.TOOL_START,
                timestamp = System.currentTimeMillis(),
                payload = """{"opId":"op-$i","toolName":"test","args":{}}"""
            ))
        }
        
        delay(200)
        
        // Get last 10 events
        val recentEvents = eventBroadcaster.getRecentEvents(10)
        assertEquals(10, recentEvents.size)
        
        // Verify they are the most recent ones
        assertTrue(recentEvents.last().payload.contains("op-19"))
    }
    
    @Test
    fun `multiple subscribers receive the same event`() = runBlocking {
        val receivedEvents = mutableListOf<EventMessage>()
        
        // Create two subscribers
        val job1 = kotlinx.coroutines.launch {
            eventBroadcaster.eventFlow.take(1).collect { event ->
                receivedEvents.add(event)
            }
        }
        
        val job2 = kotlinx.coroutines.launch {
            eventBroadcaster.eventFlow.take(1).collect { event ->
                receivedEvents.add(event)
            }
        }
        
        delay(100)
        
        // Emit one event
        eventBroadcaster.emitToolStart("test-op", "test_tool", emptyMap())
        
        // Wait for both subscribers
        job1.join()
        job2.join()
        
        // Both should have received the event
        assertEquals(2, receivedEvents.size)
        assertEquals(EventType.TOOL_START, receivedEvents[0].type)
        assertEquals(EventType.TOOL_START, receivedEvents[1].type)
    }
    
    @Test
    fun `events include timestamp`() = runBlocking {
        val beforeTime = System.currentTimeMillis()
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            val afterTime = System.currentTimeMillis()
            
            // Verify timestamp is within reasonable range
            assertTrue(event.timestamp >= beforeTime)
            assertTrue(event.timestamp <= afterTime)
        }
        
        delay(100)
        eventBroadcaster.emitToolStart("test-op", "test_tool", emptyMap())
        job.join()
    }
    
    @Test
    fun `emitAgentTurn broadcasts agent_turn event`() = runBlocking {
        val sessionId = "session-123"
        val message = "Hello, how can I help?"
        val role = "assistant"
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.AGENT_TURN, event.type)
            assertTrue(event.payload.contains(sessionId))
            assertTrue(event.payload.contains(message))
            assertTrue(event.payload.contains(role))
        }
        
        delay(100)
        eventBroadcaster.emitAgentTurn(sessionId, message, role)
        job.join()
    }
    
    @Test
    fun `emitClipboard broadcasts clipboard event`() = runBlocking {
        val type = "text"
        val content = "Hello from clipboard"
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.CLIPBOARD, event.type)
            assertTrue(event.payload.contains(type))
            assertTrue(event.payload.contains(content))
        }
        
        delay(100)
        eventBroadcaster.emitClipboard(type, content)
        job.join()
    }
    
    @Test
    fun `emitConfigChanged broadcasts config_changed event`() = runBlocking {
        val config = mapOf(
            "theme" to "dark",
            "sync_enabled" to true
        )
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.CONFIG_CHANGED, event.type)
            assertTrue(event.payload.contains("theme"))
            assertTrue(event.payload.contains("dark"))
        }
        
        delay(100)
        eventBroadcaster.emitConfigChanged(config)
        job.join()
    }
}
