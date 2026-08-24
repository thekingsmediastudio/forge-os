# GET /api/tool/{opId}/status Endpoint Implementation

## Overview

This document describes the implementation of the GET /api/tool/{opId}/status endpoint for the Forge Desktop Integration feature.

## Implementation Summary

### Files Created

1. **ToolExecutionManager.kt** (`app/src/main/java/com/forge/os/service/ToolExecutionManager.kt`)
   - Singleton service for tracking asynchronous tool operations
   - Thread-safe using ConcurrentHashMap
   - Supports operation lifecycle: pending → running → completed/failed/cancelled
   - Tracks progress, output, errors, and resource usage
   - Automatic cleanup of operations older than 1 hour

2. **ToolExecutionManagerTest.kt** (`app/src/test/java/com/forge/os/service/ToolExecutionManagerTest.kt`)
   - Comprehensive unit tests for ToolExecutionManager
   - 20+ test cases covering all functionality
   - Tests operation registration, status updates, progress tracking, cancellation, and cleanup

3. **ForgeHttpServerToolStatusTest.kt** (`app/src/test/java/com/forge/os/data/server/ForgeHttpServerToolStatusTest.kt`)
   - Integration tests for the GET /api/tool/{opId}/status endpoint
   - 15+ test cases covering all endpoint behaviors
   - Tests authentication, error handling, and various operation states

### Files Modified

1. **ForgeHttpServer.kt** (`app/src/main/java/com/forge/os/data/server/ForgeHttpServer.kt`)
   - Added ToolExecutionManager dependency injection
   - Implemented GET /api/tool/{opId}/status endpoint
   - Added import for ToolStatusResponse model

## Endpoint Specification

### Request

```
GET /api/tool/{opId}/status
Authorization: Bearer <token>
```

**Path Parameters:**
- `opId` (string, required): Operation identifier

### Response

**Success (200 OK):**
```json
{
  "op_id": "uuid",
  "tool_name": "file_read",
  "status": "running",
  "start_time": 1703001000000,
  "end_time": null,
  "progress": {
    "percent": 45,
    "message": "Reading file..."
  },
  "output": null,
  "error": null,
  "resource_usage": null
}
```

**Operation Not Found (404):**
```json
{
  "error": "operation not found"
}
```

**Missing Operation ID (400):**
```json
{
  "error": "missing operation id"
}
```

**Unauthorized (401):**
```json
{
  "error": "unauthorized"
}
```

## ToolExecutionManager API

### Methods

#### registerOperation(opId: String, toolName: String): String
Registers a new tool operation with "pending" status.

```kotlin
val opId = "my-operation-123"
toolExecutionManager.registerOperation(opId, "file_read")
```

#### updateStatus(opId: String, status: String)
Updates the operation status. Terminal statuses ("completed", "failed", "cancelled") automatically set endTime.

```kotlin
toolExecutionManager.updateStatus(opId, "running")
```

#### updateProgress(opId: String, percent: Int, message: String?)
Updates operation progress. Automatically transitions from "pending" to "running" if needed.

```kotlin
toolExecutionManager.updateProgress(opId, 50, "Processing...")
```

#### setOutput(opId: String, output: String)
Sets the operation output and marks it as "completed".

```kotlin
toolExecutionManager.setOutput(opId, "Operation completed successfully")
```

#### setError(opId: String, error: ToolError)
Sets the operation error and marks it as "failed".

```kotlin
toolExecutionManager.setError(opId, ToolError(
    code = "ERR_FILE_NOT_FOUND",
    message = "File not found",
    stackTrace = "at line 42"
))
```

#### setResourceUsage(opId: String, cpuMs: Long, memoryBytes: Long)
Sets resource usage statistics for the operation.

```kotlin
toolExecutionManager.setResourceUsage(opId, 1500, 1024000)
```

#### getStatus(opId: String): ToolStatusResponse?
Retrieves the current status of an operation. Returns null if not found.

```kotlin
val status = toolExecutionManager.getStatus(opId)
```

#### cancelOperation(opId: String): Boolean
Cancels an in-progress operation. Returns false if already completed/failed/cancelled.

```kotlin
val cancelled = toolExecutionManager.cancelOperation(opId)
```

#### cleanup()
Removes operations older than 1 hour. Should be called periodically.

```kotlin
toolExecutionManager.cleanup()
```

## Operation Lifecycle

```
1. PENDING   → registerOperation(opId, toolName)
   ↓
2. RUNNING   → updateProgress(opId, percent, message)
   ↓
3. Terminal State:
   - COMPLETED → setOutput(opId, output)
   - FAILED    → setError(opId, error)
   - CANCELLED → cancelOperation(opId)
```

## Integration Example

### Async Tool Execution

```kotlin
class MyToolExecutor @Inject constructor(
    private val toolExecutionManager: ToolExecutionManager
) {
    suspend fun executeToolAsync(toolName: String, args: Map<String, Any>): String {
        val opId = UUID.randomUUID().toString()
        
        // Register operation
        toolExecutionManager.registerOperation(opId, toolName)
        
        // Execute in background
        scope.launch {
            try {
                // Start execution
                toolExecutionManager.updateStatus(opId, "running")
                
                // Report progress
                toolExecutionManager.updateProgress(opId, 25, "Step 1/4")
                val step1Result = performStep1(args)
                
                toolExecutionManager.updateProgress(opId, 50, "Step 2/4")
                val step2Result = performStep2(step1Result)
                
                toolExecutionManager.updateProgress(opId, 75, "Step 3/4")
                val step3Result = performStep3(step2Result)
                
                toolExecutionManager.updateProgress(opId, 100, "Step 4/4")
                val finalResult = performStep4(step3Result)
                
                // Complete
                toolExecutionManager.setOutput(opId, finalResult)
                
                // Set resource usage
                toolExecutionManager.setResourceUsage(opId, 1500, 1024000)
            } catch (e: Exception) {
                // Handle error
                toolExecutionManager.setError(opId, ToolError(
                    code = "ERR_EXECUTION_FAILED",
                    message = e.message ?: "Unknown error",
                    stackTrace = e.stackTraceToString()
                ))
            }
        }
        
        // Return operation ID immediately
        return opId
    }
}
```

### Desktop Client Usage

```typescript
// Start an async tool operation
const response = await fetch('http://device:8789/api/tool', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    name: 'file_search',
    args: { pattern: '*.txt', directory: '/workspace' }
  })
});

const { op_id } = await response.json();

// Poll for status
const pollStatus = async (opId: string) => {
  while (true) {
    const statusResponse = await fetch(
      `http://device:8789/api/tool/${opId}/status`,
      {
        headers: { 'Authorization': `Bearer ${token}` }
      }
    );
    
    const status = await statusResponse.json();
    
    console.log(`Status: ${status.status}`);
    
    if (status.progress) {
      console.log(`Progress: ${status.progress.percent}%`);
      console.log(`Message: ${status.progress.message}`);
    }
    
    if (status.status === 'completed') {
      console.log('Output:', status.output);
      break;
    }
    
    if (status.status === 'failed') {
      console.error('Error:', status.error);
      break;
    }
    
    if (status.status === 'cancelled') {
      console.log('Operation was cancelled');
      break;
    }
    
    // Wait before polling again
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
};

pollStatus(op_id);
```

## Testing

### Running Unit Tests

```bash
./gradlew test --tests "com.forge.os.service.ToolExecutionManagerTest"
./gradlew test --tests "com.forge.os.data.server.ForgeHttpServerToolStatusTest"
```

### Test Coverage

#### ToolExecutionManagerTest
- ✅ Operation registration
- ✅ Status updates
- ✅ Progress tracking
- ✅ Output setting
- ✅ Error handling
- ✅ Resource usage tracking
- ✅ Operation cancellation
- ✅ Cleanup of old operations
- ✅ Multiple concurrent operations
- ✅ Complete operation lifecycle

#### ForgeHttpServerToolStatusTest
- ✅ Pending operation status
- ✅ Running operation with progress
- ✅ Completed operation with output
- ✅ Failed operation with error
- ✅ Cancelled operation
- ✅ Non-existent operation (404)
- ✅ Authentication requirement (401)
- ✅ Operation lifecycle tracking
- ✅ Special characters in opId
- ✅ UUID-style opIds
- ✅ Multiple operations
- ✅ Progress preservation after completion

## Authentication

The endpoint requires Bearer token authentication, consistent with other ForgeHttpServer endpoints:

```
Authorization: Bearer <api_key>
```

The API key is managed by SecureKeyStore and can be retrieved via `ForgeHttpServer.apiKey()`.

## Error Handling

### Client-Side Errors (4xx)
- **400 Bad Request**: Missing or empty operation ID in path
- **401 Unauthorized**: Missing or invalid Bearer token
- **404 Not Found**: Operation ID not found in ToolExecutionManager

### Server-Side Errors (5xx)
- Handled by ForgeHttpServer's general error handling mechanism
- Returns JSON error with message and stack trace (if available)

## Future Enhancements

### Phase 2 (POST /api/tool/{opId}/cancel)
The next sub-task will implement operation cancellation via HTTP endpoint.

### Phase 3 (WebSocket Event Broadcasting)
Real-time progress updates will be broadcast via WebSocket `/api/events` for subscribed clients.

### Phase 4 (Modify POST /api/tool)
The POST /api/tool endpoint should be updated to:
1. Return an operation ID immediately
2. Execute the tool asynchronously
3. Register the operation with ToolExecutionManager

Current implementation is synchronous and returns the result directly.

## Validation

All files have been validated with no compilation errors:
- ✅ ToolExecutionManager.kt - No diagnostics
- ✅ ForgeHttpServer.kt - No diagnostics
- ✅ ToolExecutionManagerTest.kt - No diagnostics
- ✅ ForgeHttpServerToolStatusTest.kt - No diagnostics

## Acceptance Criteria Status

From the task description:

- ✅ GET /api/tool/{opId}/status endpoint implemented
- ✅ Returns ToolStatusResponse with operation details
- ✅ Proper authentication with Bearer token
- ✅ Unit tests for the endpoint

All acceptance criteria have been met.

## Notes

1. **ToolExecutionManager is thread-safe**: Uses ConcurrentHashMap for operation storage
2. **Memory management**: Operations are automatically cleaned up after 1 hour
3. **Flexible operation IDs**: Supports any string format (UUIDs, custom strings, etc.)
4. **Progress tracking**: Optional progress updates with percentage and message
5. **Resource usage**: Optional tracking of CPU and memory usage
6. **Terminal states**: completed, failed, and cancelled states automatically set endTime
7. **Null safety**: All optional fields properly handle null values

## Dependencies

The implementation integrates seamlessly with existing Forge OS infrastructure:
- **SecureKeyStore**: For API key management
- **Dagger**: For dependency injection
- **Timber**: For logging
- **kotlinx.serialization**: For JSON serialization
- **JUnit & Mockito**: For testing
