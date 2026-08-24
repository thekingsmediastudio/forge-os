package com.forge.os.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EventBroadcaster manages real-time event streaming to connected WebSocket clients.
 * 
 * Supports event types:
 * - tool_start: Tool execution begins
 * - tool_progress: Progress updates during execution
 * - tool_complete: Tool execution completes successfully
 * - tool_error: Tool execution fails
 * - agent_turn: Agent conversation turn
 * - file_modified: File sync event
 * - notification: Android notification forwarded
 * - clipboard: Clipboard sync event
 * - config_changed: Configuration update
 * - desktop_tool_invoke: Request to invoke desktop tool
 * 
 * Requirements: 8.3, 8.4, 8.5
 */
@Singleton
class EventBroadcaster @Inject constructor() {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    
    // Event buffer with 1000 event limit
    private val eventQueue = ConcurrentLinkedQueue<EventMessage>()
    private val maxQueueSize = 1000
    
    // SharedFlow for broadcasting events to subscribers
    private val _eventFlow = MutableSharedFlow<EventMessage>(
        replay = 0,
        extraBufferCapacity = 100
    )
    val eventFlow = _eventFlow.asSharedFlow()
    
    /**
     * Broadcast an event to all connected clients matching subscription filters.
     * Events are delivered within 500ms.
     */
    fun broadcast(event: EventMessage) {
        scope.launch {
            try {
                // Add to event queue with size limit
                eventQueue.offer(event)
                while (eventQueue.size > maxQueueSize) {
                    eventQueue.poll()
                }
                
                // Emit to all subscribers
                _eventFlow.emit(event)
                
                Timber.d("EventBroadcaster: Broadcasted ${event.type} event at ${event.timestamp}")
            } catch (e: Exception) {
                Timber.e(e, "EventBroadcaster: Failed to broadcast event ${event.type}")
            }
        }
    }
    
    /**
     * Emit tool_start event when tool execution begins
     * Requirement: 4.4
     */
    fun emitToolStart(opId: String, toolName: String, args: Map<String, Any>) {
        val payload = buildString {
            append("{\"opId\":\"$opId\",\"toolName\":\"$toolName\",\"args\":")
            append(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.buildJsonObject {
                    args.forEach { (key, value) ->
                        put(key, kotlinx.serialization.json.JsonPrimitive(value.toString()))
                    }
                }))
            append("}")
        }
        broadcast(EventMessage(
            type = EventType.TOOL_START,
            timestamp = System.currentTimeMillis(),
            payload = payload
        ))
    }
    
    /**
     * Emit tool_progress event during execution
     * Requirement: 4.4
     */
    fun emitToolProgress(opId: String, percent: Int, message: String? = null) {
        val payload = ToolProgressPayload(
            opId = opId,
            percent = percent,
            message = message
        )
        broadcast(EventMessage(
            type = EventType.TOOL_PROGRESS,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit tool_complete event on successful completion
     * Requirement: 4.8
     */
    fun emitToolComplete(
        opId: String,
        output: String,
        duration: Long,
        resourceUsage: ResourceUsagePayload
    ) {
        val payload = ToolCompletePayload(
            opId = opId,
            output = output,
            duration = duration,
            resourceUsage = resourceUsage
        )
        broadcast(EventMessage(
            type = EventType.TOOL_COMPLETE,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit tool_error event on failure
     * Requirement: 4.8
     */
    fun emitToolError(opId: String, error: ToolErrorPayload) {
        val payload = ToolErrorEventPayload(
            opId = opId,
            error = error
        )
        broadcast(EventMessage(
            type = EventType.TOOL_ERROR,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit agent_turn event
     */
    fun emitAgentTurn(sessionId: String, message: String, role: String) {
        val payload = AgentTurnPayload(
            sessionId = sessionId,
            message = message,
            role = role
        )
        broadcast(EventMessage(
            type = EventType.AGENT_TURN,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit file_modified event
     */
    fun emitFileModified(path: String, checksum: String, size: Long) {
        val payload = FileModifiedPayload(
            path = path,
            checksum = checksum,
            size = size
        )
        broadcast(EventMessage(
            type = EventType.FILE_MODIFIED,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit notification event
     */
    fun emitNotification(
        id: String,
        packageName: String,
        title: String,
        body: String,
        icon: String? = null,
        actions: List<NotificationAction> = emptyList(),
        removed: Boolean = false
    ) {
        val payload = NotificationPayload(
            id = id,
            packageName = packageName,
            title = title,
            body = body,
            icon = icon,
            actions = actions,
            removed = removed
        )
        broadcast(EventMessage(
            type = EventType.NOTIFICATION,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit notification removal (Task 11.4): mirrors are dismissed on desktop.
     */
    fun emitNotificationRemoved(id: String) {
        broadcast(EventMessage(
            type = EventType.NOTIFICATION,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(NotificationPayload(
                id = id,
                packageName = "",
                title = "",
                body = "",
                removed = true
            ))
        ))
    }

    /**
     * Emit clipboard event
     */
    fun emitClipboard(type: String, content: String) {
        val payload = ClipboardPayload(
            type = type,
            content = content
        )
        broadcast(EventMessage(
            type = EventType.CLIPBOARD,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToString(payload)
        ))
    }
    
    /**
     * Emit config_changed event
     */
    fun emitConfigChanged(config: Map<String, Any>) {
        val payload = buildString {
            append("{\"config\":")
            append(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.buildJsonObject {
                    config.forEach { (key, value) ->
                        put(key, kotlinx.serialization.json.JsonPrimitive(value.toString()))
                    }
                }))
            append("}")
        }
        broadcast(EventMessage(
            type = EventType.CONFIG_CHANGED,
            timestamp = System.currentTimeMillis(),
            payload = payload
        ))
    }
    
    /**
     * Emit desktop_tool_invoke event
     */
    fun emitDesktopToolInvoke(
        invokeId: String,
        toolName: String,
        args: Map<String, Any>,
        timeout: Int
    ) {
        val payload = buildString {
            append("{\"invokeId\":\"$invokeId\",\"toolName\":\"$toolName\",\"timeout\":$timeout,\"args\":")
            append(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.buildJsonObject {
                    args.forEach { (key, value) ->
                        put(key, kotlinx.serialization.json.JsonPrimitive(value.toString()))
                    }
                }))
            append("}")
        }
        broadcast(EventMessage(
            type = EventType.DESKTOP_TOOL_INVOKE,
            timestamp = System.currentTimeMillis(),
            payload = payload
        ))
    }
    
    /**
     * Get recent events from the buffer
     */
    fun getRecentEvents(count: Int = 10): List<EventMessage> {
        return eventQueue.toList().takeLast(count)
    }
    
    fun shutdown() {
        scope.cancel()
    }
}

/**
 * Event type enumeration
 */
enum class EventType(val value: String) {
    TOOL_START("tool_start"),
    TOOL_PROGRESS("tool_progress"),
    TOOL_COMPLETE("tool_complete"),
    TOOL_ERROR("tool_error"),
    AGENT_TURN("agent_turn"),
    FILE_MODIFIED("file_modified"),
    NOTIFICATION("notification"),
    CLIPBOARD("clipboard"),
    CONFIG_CHANGED("config_changed"),
    DESKTOP_TOOL_INVOKE("desktop_tool_invoke");
    
    override fun toString(): String = value
}

/**
 * Main event message structure
 */
@Serializable
data class EventMessage(
    val type: EventType,
    val timestamp: Long,
    val payload: String
)

// Payload data classes - these are for reference, actual serialization is done manually

@Serializable
data class ToolProgressPayload(
    val opId: String,
    val percent: Int,
    val message: String? = null
)

@Serializable
data class ToolCompletePayload(
    val opId: String,
    val output: String,
    val duration: Long,
    val resourceUsage: ResourceUsagePayload
)

@Serializable
data class ResourceUsagePayload(
    val cpuMs: Long,
    val memoryBytes: Long
)

@Serializable
data class ToolErrorPayload(
    val code: String,
    val message: String,
    val stackTrace: String? = null
)

@Serializable
data class ToolErrorEventPayload(
    val opId: String,
    val error: ToolErrorPayload
)

@Serializable
data class AgentTurnPayload(
    val sessionId: String,
    val message: String,
    val role: String
)

@Serializable
data class FileModifiedPayload(
    val path: String,
    val checksum: String,
    val size: Long
)

@Serializable
data class NotificationPayload(
    val id: String,
    val packageName: String,
    val title: String,
    val body: String,
    val icon: String? = null,
    val actions: List<NotificationAction> = emptyList(),
    val removed: Boolean = false
)

@Serializable
data class NotificationAction(
    val id: String,
    val label: String
)

@Serializable
data class ClipboardPayload(
    val type: String,
    val content: String
)

// ConfigChangedPayload and DesktopToolInvokePayload are manually serialized
// due to Map<String, Any> which isn't directly serializable
