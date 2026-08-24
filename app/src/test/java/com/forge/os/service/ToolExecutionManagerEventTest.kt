package com.forge.os.service

import com.forge.os.data.api.ToolError
import dagger.Lazy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for ToolExecutionManager event broadcasting
 * Verifies that ToolExecutionManager correctly emits events via EventBroadcaster
 * Requirements: 4.4, 4.8
 */
class ToolExecutionManagerEventTest {
    
    private lateinit var eventBroadcaster: EventBroadcaster
    private lateinit var toolExecutionManager: ToolExecutionManager
    
    @Before
    fun setUp() {
        eventBroadcaster = EventBroadcaster()
        
        // Create a Lazy wrapper for EventBroadcaster
        val lazyBroadcaster = Lazy { eventBroadcaster }
        
        toolExecutionManager = ToolExecutionManager(lazyBroadcaster)
    }
    
    @After
    fun tearDown() {
        eventBroadcaster.shutdown()
    }
    
    @Test
    fun `registerOperation emits tool_start event`() = runBlocking {
        val opId = "op-123"
        val toolName = "test_tool"
        val args = mapOf("param1" to "value1", "param2" to 42)
        
        // Start listening for events
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_START, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains(toolName))
            assertTrue(event.payload.contains("param1"))
        }
        
        delay(100)
        
        // Register operation
        toolExecutionManager.registerOperation(opId, toolName, args)
        
        job.join()
    }
    
    @Test
    fun `updateProgress emits tool_progress event`() = runBlocking {
        val opId = "op-456"
        val toolName = "test_tool"
        
        // Register operation first
        toolExecutionManager.registerOperation(opId, toolName)
        
        // Wait for tool_start event to clear
        delay(100)
        
        // Start listening for progress event
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_PROGRESS, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains("75"))
            assertTrue(event.payload.contains("Almost done"))
        }
        
        delay(100)
        
        // Update progress
        toolExecutionManager.updateProgress(opId, 75, "Almost done")
        
        job.join()
        
        // Verify status was updated
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("running", status!!.status)
        assertEquals(75, status.progress?.percent)
        assertEquals("Almost done", status.progress?.message)
    }
    
    @Test
    fun `setOutput emits tool_complete event with duration and resource usage`() = runBlocking {
        val opId = "op-789"
        val toolName = "test_tool"
        val output = "Task completed successfully"
        
        // Register operation and set resource usage
        toolExecutionManager.registerOperation(opId, toolName)
        toolExecutionManager.setResourceUsage(opId, cpuMs = 250, memoryBytes = 4096)
        
        // Wait for tool_start event
        delay(100)
        
        // Start listening for complete event
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_COMPLETE, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains(output))
            assertTrue(event.payload.contains("250")) // cpuMs
            assertTrue(event.payload.contains("4096")) // memoryBytes
            // Duration should be present and > 0
            assertTrue(event.payload.contains("duration"))
        }
        
        delay(100)
        
        // Set output (completes operation)
        toolExecutionManager.setOutput(opId, output)
        
        job.join()
        
        // Verify status
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("completed", status!!.status)
        assertEquals(output, status.output)
        assertNotNull(status.endTime)
    }
    
    @Test
    fun `setError emits tool_error event`() = runBlocking {
        val opId = "op-error"
        val toolName = "failing_tool"
        val error = ToolError(
            code = "FILE_NOT_FOUND",
            message = "The requested file does not exist",
            stackTrace = "at ToolExecutor.execute(line 42)"
        )
        
        // Register operation
        toolExecutionManager.registerOperation(opId, toolName)
        
        // Wait for tool_start event
        delay(100)
        
        // Start listening for error event
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_ERROR, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains("FILE_NOT_FOUND"))
            assertTrue(event.payload.contains("The requested file does not exist"))
        }
        
        delay(100)
        
        // Set error
        toolExecutionManager.setError(opId, error)
        
        job.join()
        
        // Verify status
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("failed", status!!.status)
        assertEquals("FILE_NOT_FOUND", status.error?.code)
        assertEquals("The requested file does not exist", status.error?.message)
    }
    
    @Test
    fun `cancelOperation emits tool_error event with cancellation reason`() = runBlocking {
        val opId = "op-cancel"
        val toolName = "long_running_tool"
        
        // Register operation
        toolExecutionManager.registerOperation(opId, toolName)
        
        // Wait for tool_start event
        delay(100)
        
        // Start listening for error event
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_ERROR, event.type)
            assertTrue(event.payload.contains(opId))
            assertTrue(event.payload.contains("CANCELLED"))
            assertTrue(event.payload.contains("Operation cancelled by user"))
        }
        
        delay(100)
        
        // Cancel operation
        val cancelled = toolExecutionManager.cancelOperation(opId)
        assertTrue(cancelled)
        
        job.join()
        
        // Verify status
        val status = toolExecutionManager.getStatus(opId)
        assertNotNull(status)
        assertEquals("cancelled", status!!.status)
    }
    
    @Test
    fun `multiple progress updates emit multiple events`() = runBlocking {
        val opId = "op-multi-progress"
        val toolName = "batch_processor"
        
        // Register operation
        toolExecutionManager.registerOperation(opId, toolName)
        
        // Wait for tool_start
        delay(100)
        
        val progressEvents = mutableListOf<EventMessage>()
        
        // Collect progress events
        val job = kotlinx.coroutines.launch {
            eventBroadcaster.eventFlow.collect { event ->
                if (event.type == EventType.TOOL_PROGRESS) {
                    progressEvents.add(event)
                }
            }
        }
        
        delay(100)
        
        // Send multiple progress updates
        toolExecutionManager.updateProgress(opId, 25, "Processing batch 1")
        delay(50)
        toolExecutionManager.updateProgress(opId, 50, "Processing batch 2")
        delay(50)
        toolExecutionManager.updateProgress(opId, 75, "Processing batch 3")
        delay(50)
        toolExecutionManager.updateProgress(opId, 100, "Complete")
        
        delay(200)
        
        job.cancel()
        
        // Verify all progress events were received
        assertEquals(4, progressEvents.size)
        assertTrue(progressEvents[0].payload.contains("25"))
        assertTrue(progressEvents[1].payload.contains("50"))
        assertTrue(progressEvents[2].payload.contains("75"))
        assertTrue(progressEvents[3].payload.contains("100"))
    }
    
    @Test
    fun `complete operation workflow emits correct sequence of events`() = runBlocking {
        val opId = "op-workflow"
        val toolName = "complete_workflow_tool"
        val output = "Workflow completed"
        
        val events = mutableListOf<EventType>()
        
        // Collect all events
        val job = kotlinx.coroutines.launch {
            eventBroadcaster.eventFlow.collect { event ->
                events.add(event.type)
            }
        }
        
        delay(100)
        
        // Execute complete workflow
        toolExecutionManager.registerOperation(opId, toolName, mapOf("input" to "test"))
        delay(50)
        toolExecutionManager.updateProgress(opId, 50, "Halfway")
        delay(50)
        toolExecutionManager.setResourceUsage(opId, 100, 2048)
        toolExecutionManager.setOutput(opId, output)
        
        delay(200)
        
        job.cancel()
        
        // Verify event sequence
        assertTrue(events.size >= 3)
        assertEquals(EventType.TOOL_START, events[0])
        assertEquals(EventType.TOOL_PROGRESS, events[1])
        assertEquals(EventType.TOOL_COMPLETE, events[2])
    }
    
    @Test
    fun `setOutput with zero resource usage still emits complete event`() = runBlocking {
        val opId = "op-no-resources"
        val toolName = "lightweight_tool"
        val output = "Done"
        
        toolExecutionManager.registerOperation(opId, toolName)
        
        // Wait for tool_start
        delay(100)
        
        val job = kotlinx.coroutines.launch {
            val event = withTimeout(2000) {
                eventBroadcaster.eventFlow.first()
            }
            
            assertEquals(EventType.TOOL_COMPLETE, event.type)
            // Should have zero resource usage
            assertTrue(event.payload.contains("\"cpuMs\":0"))
            assertTrue(event.payload.contains("\"memoryBytes\":0"))
        }
        
        delay(100)
        
        // Complete without setting resource usage
        toolExecutionManager.setOutput(opId, output)
        
        job.join()
    }
}
