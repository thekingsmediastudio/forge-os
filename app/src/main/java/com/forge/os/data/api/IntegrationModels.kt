package com.forge.os.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data models for Forge Desktop Integration API
 * These extend the existing API to support enhanced features like
 * pairing, async tool execution, file sync, clipboard, and configuration.
 */

// ─── Pairing ─────────────────────────────────────────────────────────────────

@Serializable
data class PairingInitiateRequest(
    @SerialName("desktop_name") val desktopName: String
)

@Serializable
data class PairingInitiateResponse(
    @SerialName("pairing_code") val pairingCode: String,
    @SerialName("expires_in") val expiresIn: Int // seconds
)

@Serializable
data class PairingConfirmRequest(
    @SerialName("pairing_code") val pairingCode: String,
    @SerialName("desktop_id") val desktopId: String
)

@Serializable
data class DeviceMetadata(
    val model: String,
    @SerialName("android_version") val androidVersion: String,
    @SerialName("forge_os_version") val forgeOsVersion: String,
    val capabilities: List<String>
)

@Serializable
data class PairingConfirmResponse(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_metadata") val deviceMetadata: DeviceMetadata
)

// ─── Tool Operations ─────────────────────────────────────────────────────────

@Serializable
data class ToolOperationRequest(
    val name: String,
    val args: Map<String, kotlinx.serialization.json.JsonElement>
)

@Serializable
data class ToolOperationResponse(
    @SerialName("op_id") val opId: String,
    val status: String // "pending" | "queued"
)

@Serializable
data class ToolProgressInfo(
    val percent: Int,
    val message: String? = null
)

@Serializable
data class ResourceUsage(
    @SerialName("cpu_ms") val cpuMs: Long,
    @SerialName("memory_bytes") val memoryBytes: Long
)

@Serializable
data class ToolError(
    val code: String,
    val message: String,
    @SerialName("stack_trace") val stackTrace: String? = null
)

@Serializable
data class ToolStatusResponse(
    @SerialName("op_id") val opId: String,
    @SerialName("tool_name") val toolName: String,
    val status: String, // "pending" | "running" | "completed" | "failed" | "cancelled"
    @SerialName("start_time") val startTime: Long,
    @SerialName("end_time") val endTime: Long? = null,
    val progress: ToolProgressInfo? = null,
    val output: String? = null,
    val error: ToolError? = null,
    @SerialName("resource_usage") val resourceUsage: ResourceUsage? = null
)

@Serializable
data class ToolCancelResponse(
    @SerialName("op_id") val opId: String,
    val cancelled: Boolean
)

// ─── File Sync ───────────────────────────────────────────────────────────────

@Serializable
data class FileUploadMetadata(
    val path: String,
    val chunk: Int,
    @SerialName("total_chunks") val totalChunks: Int,
    val checksum: String // SHA-256
)

@Serializable
data class FileUploadResponse(
    val uploaded: Boolean,
    @SerialName("received_chunks") val receivedChunks: List<Int>,
    val complete: Boolean
)

@Serializable
data class FileDownloadRequest(
    val path: String,
    val chunk: Int? = null
)

// ─── Clipboard ───────────────────────────────────────────────────────────────

@Serializable
data class ClipboardUpdateRequest(
    val type: String, // "text" | "image" | "file"
    val content: String? = null,
    @SerialName("image_data") val imageData: String? = null, // Base64
    @SerialName("file_name") val fileName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ClipboardUpdateResponse(
    val updated: Boolean
)

@Serializable
data class NotificationActionRequest(
    @SerialName("notification_id") val notificationId: String,
    @SerialName("action_id") val actionId: String
)

// ─── Desktop Tool Bridge ─────────────────────────────────────────────────────

@Serializable
data class FileStatResponse(
    val exists: Boolean,
    val size: Long? = null,
    @SerialName("last_modified") val lastModified: Long? = null,
    val checksum: String? = null
)

@Serializable
data class DesktopToolInvokeRequest(
    @SerialName("tool_name") val toolName: String,
    val args: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val timeout: Int = 30
)

@Serializable
data class DesktopToolRegisterMessage(
    val type: String = "desktop_tool_register",
    val name: String,
    val description: String = "",
    val schema: String = "{}"
)

// ─── Configuration ────────────────────────────────────────────────────────────

@Serializable
data class ConfigResponse(
    val theme: String? = null,
    @SerialName("sync_enabled") val syncEnabled: Boolean = true,
    @SerialName("clipboard_enabled") val clipboardEnabled: Boolean = true,
    @SerialName("notification_filters") val notificationFilters: List<String> = emptyList(),
    val custom: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)

@Serializable
data class ConfigUpdateRequest(
    val theme: String? = null,
    @SerialName("sync_enabled") val syncEnabled: Boolean? = null,
    @SerialName("clipboard_enabled") val clipboardEnabled: Boolean? = null,
    @SerialName("notification_filters") val notificationFilters: List<String>? = null,
    val custom: Map<String, kotlinx.serialization.json.JsonElement>? = null
)

@Serializable
data class ConfigUpdateResponse(
    val updated: Boolean
)

// ─── WebSocket Events ─────────────────────────────────────────────────────────

@Serializable
data class EventMessage(
    val type: String, // "tool_start", "tool_progress", "tool_complete", etc.
    val timestamp: Long,
    val payload: kotlinx.serialization.json.JsonElement
)

@Serializable
data class ToolStartEvent(
    @SerialName("op_id") val opId: String,
    @SerialName("tool_name") val toolName: String,
    val args: Map<String, kotlinx.serialization.json.JsonElement>
)

@Serializable
data class ToolProgressEvent(
    @SerialName("op_id") val opId: String,
    val percent: Int,
    val message: String? = null
)

@Serializable
data class ToolCompleteEvent(
    @SerialName("op_id") val opId: String,
    val output: String,
    val duration: Long,
    @SerialName("resource_usage") val resourceUsage: ResourceUsage
)

@Serializable
data class ToolErrorEvent(
    @SerialName("op_id") val opId: String,
    val error: ToolError
)

@Serializable
data class NotificationEvent(
    val id: String,
    @SerialName("package_name") val packageName: String,
    val title: String,
    val body: String,
    val icon: String? = null, // Base64
    val actions: List<NotificationAction> = emptyList()
)

@Serializable
data class NotificationAction(
    val id: String,
    val label: String
)

@Serializable
data class DesktopToolInvokeEvent(
    @SerialName("invoke_id") val invokeId: String,
    @SerialName("tool_name") val toolName: String,
    val args: Map<String, kotlinx.serialization.json.JsonElement>,
    val timeout: Int
)

@Serializable
data class DesktopToolResultMessage(
    val type: String = "desktop_tool_result",
    @SerialName("invoke_id") val invokeId: String,
    val success: Boolean,
    val output: String? = null,
    val error: String? = null
)

@Serializable
data class EventSubscriptionMessage(
    val type: String = "subscribe",
    val events: List<String>
)

@Serializable
data class EventAuthMessage(
    val type: String = "auth",
    val token: String
)
