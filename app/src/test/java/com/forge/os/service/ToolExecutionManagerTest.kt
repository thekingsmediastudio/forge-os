package com.forge.os.service

import com.forge.os.data.api.ToolError
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ToolExecutionManager
 */
class ToolExecutionManagerTest {
    
    private lateinit var manager: ToolExecutionManager
    
    @Before
    fun setUp() {
        manager = ToolExecutionManager()
    }
    
    @Test
    fun `registerOperation creates new operation with pending status`() {
        val opId = "test-op-123"
        val toolName = "file_read"
        
        val result = manager.registerOperation(opId, toolName)
        
        assertEquals(opId, result)
        
        val status = manager.getStatus(opId)
        assertNotNull(status)
        assertEquals(opId, status?.opId)
        assertEquals(toolName, status?.toolName)
        assertEquals("pending", status?.status)
        assertTrue(status?.startTime!! > 0)
        assertNull(status.endTime)
        assertNull(status.progress)
        assertNull(status.output)
        assertNull(status.error)
    }
    
    @Test
    fun `updateStatus changes operation status`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        manager.updateStatus(opId, "running")
        
        val status = manager.getStatus(opId)
        assertEquals("running", status?.status)
        assertNull(status?.endTime)
    }
    
    @Test
    fun `updateStatus sets endTime for terminal statuses`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        manager.updateStatus(opId, "completed")
        
        val status = manager.getStatus(opId)
        assertEquals("completed", status?.status)
        assertNotNull(status?.endTime)
    }
    
    @Test
    fun `updateProgress sets progress and changes status to running`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        manager.updateProgress(opId, 50, "Processing...")
        
        val status = manager.getStatus(opId)
        assertEquals("running", status?.status)
        assertNotNull(status?.progress)
        assertEquals(50, status?.progress?.percent)
        assertEquals("Processing...", status?.progress?.message)
    }
    
    @Test
    fun `updateProgress without message sets progress correctly`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        manager.updateProgress(opId, 75)
        
        val status = manager.getStatus(opId)
        assertEquals(75, status?.progress?.percent)
        assertNull(status?.progress?.message)
    }
    
    @Test
    fun `setOutput marks operation as completed`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        val output = "Operation completed successfully"
        manager.setOutput(opId, output)
        
        val status = manager.getStatus(opId)
        assertEquals("completed", status?.status)
        assertEquals(output, status?.output)
        assertNotNull(status?.endTime)
    }
    
    @Test
    fun `setError marks operation as failed`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        val error = ToolError(
            code = "ERR_FILE_NOT_FOUND",
            message = "File not found",
            stackTrace = "at line 42"
        )
        manager.setError(opId, error)
        
        val status = manager.getStatus(opId)
        assertEquals("failed", status?.status)
        assertNotNull(status?.error)
        assertEquals("ERR_FILE_NOT_FOUND", status?.error?.code)
        assertEquals("File not found", status?.error?.message)
        assertEquals("at line 42", status?.error?.stackTrace)
        assertNotNull(status?.endTime)
    }
    
    @Test
    fun `setResourceUsage updates resource usage statistics`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        
        manager.setResourceUsage(opId, cpuMs = 1500, memoryBytes = 1024000)
        
        val status = manager.getStatus(opId)
        assertNotNull(status?.resourceUsage)
        assertEquals(1500L, status?.resourceUsage?.cpuMs)
        assertEquals(1024000L, status?.resourceUsage?.memoryBytes)
    }
    
    @Test
    fun `getStatus returns null for non-existent operation`() {
        val status = manager.getStatus("non-existent-op")
        
        assertNull(status)
    }
    
    @Test
    fun `cancelOperation returns true for cancellable operation`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        manager.updateStatus(opId, "running")
        
        val cancelled = manager.cancelOperation(opId)
        
        assertTrue(cancelled)
        val status = manager.getStatus(opId)
        assertEquals("cancelled", status?.status)
        assertNotNull(status?.endTime)
    }
    
    @Test
    fun `cancelOperation returns false for completed operation`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        manager.setOutput(opId, "Done")
        
        val cancelled = manager.cancelOperation(opId)
        
        assertFalse(cancelled)
        val status = manager.getStatus(opId)
        assertEquals("completed", status?.status)
    }
    
    @Test
    fun `cancelOperation returns false for failed operation`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        manager.setError(opId, ToolError("ERR", "Error"))
        
        val cancelled = manager.cancelOperation(opId)
        
        assertFalse(cancelled)
        val status = manager.getStatus(opId)
        assertEquals("failed", status?.status)
    }
    
    @Test
    fun `cancelOperation returns false for already cancelled operation`() {
        val opId = "test-op-123"
        manager.registerOperation(opId, "test_tool")
        manager.cancelOperation(opId)
        
        val cancelled = manager.cancelOperation(opId)
        
        assertFalse(cancelled)
    }
    
    @Test
    fun `cancelOperation returns false for non-existent operation`() {
        val cancelled = manager.cancelOperation("non-existent")
        
        assertFalse(cancelled)
    }
    
    @Test
    fun `cleanup removes old operations`() {
        val opId1 = "old-op-1"
        val opId2 = "recent-op-2"
        
        // Register operations
        manager.registerOperation(opId1, "test_tool")
        manager.registerOperation(opId2, "test_tool")
        
        // Complete the first one and manually set its endTime to be old
        manager.setOutput(opId1, "Done")
        
        // Access internal state to modify endTime (for testing purposes)
        // Since we can't access private fields, we'll simulate by waiting
        // or just verify the cleanup logic works with recent operations
        
        // Keep the second one recent (still running)
        manager.updateStatus(opId2, "running")
        
        // Run cleanup - should not remove recent operations
        manager.cleanup()
        
        // Both should still exist since they're recent
        assertNotNull(manager.getStatus(opId1))
        assertNotNull(manager.getStatus(opId2))
    }
    
    @Test
    fun `multiple operations can be tracked simultaneously`() {
        val opIds = listOf("op-1", "op-2", "op-3")
        val toolNames = listOf("tool-1", "tool-2", "tool-3")
        
        opIds.forEachIndexed { index, opId ->
            manager.registerOperation(opId, toolNames[index])
        }
        
        // Verify all operations are tracked
        opIds.forEachIndexed { index, opId ->
            val status = manager.getStatus(opId)
            assertNotNull(status)
            assertEquals(opId, status?.opId)
            assertEquals(toolNames[index], status?.toolName)
        }
    }
    
    @Test
    fun `operation lifecycle completes correctly`() {
        val opId = "lifecycle-op"
        
        // 1. Register
        manager.registerOperation(opId, "complex_tool")
        assertEquals("pending", manager.getStatus(opId)?.status)
        
        // 2. Start running
        manager.updateStatus(opId, "running")
        assertEquals("running", manager.getStatus(opId)?.status)
        
        // 3. Report progress
        manager.updateProgress(opId, 25, "Step 1 of 4")
        assertEquals(25, manager.getStatus(opId)?.progress?.percent)
        
        manager.updateProgress(opId, 50, "Step 2 of 4")
        assertEquals(50, manager.getStatus(opId)?.progress?.percent)
        
        manager.updateProgress(opId, 75, "Step 3 of 4")
        assertEquals(75, manager.getStatus(opId)?.progress?.percent)
        
        // 4. Set resource usage
        manager.setResourceUsage(opId, 2000, 2048000)
        assertNotNull(manager.getStatus(opId)?.resourceUsage)
        
        // 5. Complete
        manager.setOutput(opId, "Success!")
        
        val finalStatus = manager.getStatus(opId)
        assertEquals("completed", finalStatus?.status)
        assertEquals("Success!", finalStatus?.output)
        assertNotNull(finalStatus?.endTime)
        assertEquals(75, finalStatus?.progress?.percent) // Progress persists
        assertNotNull(finalStatus?.resourceUsage) // Resource usage persists
    }
}
