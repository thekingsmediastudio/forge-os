package com.forge.os.data.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Shared ─────────────────────────────────────────────────────────────────

@Serializable
data class ApiMessage(
    val role: String,           // system | user | assistant | tool
    val content: String? = null,
    @SerialName("content_parts") val contentParts: List<ContentPart>? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallResponse>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class ContentPart(
    val type: String, // text | image_url
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null
)

@Serializable
data class ImageUrl(
    val url: String, // data:image/jpeg;base64,xxxx
    val detail: String = "auto"
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ToolDefinition(
    @EncodeDefault val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class FunctionParameters(
    @EncodeDefault val type: String = "object",
    val properties: Map<String, ParameterProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ParameterProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ToolCallResponse(
    val id: String,
    @EncodeDefault val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String    // JSON string
)

// ─── OpenAI / Groq (same schema) ─────────────────────────────────────────────

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false
)

@Serializable
data class OpenAiResponse(
    val id: String,
    val choices: List<OpenAiChoice>,
    val usage: UsageStats? = null
)

@Serializable
data class OpenAiChoice(
    val index: Int,
    val message: ApiMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class UsageStats(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

// ─── Anthropic ────────────────────────────────────────────────────────────────

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicTool>? = null,
    val temperature: Double = 0.7
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContent>
)

@Serializable
data class AnthropicContent(
    val type: String,           // text | tool_use | tool_result
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, String>? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null,
    val source: AnthropicImageSource? = null
)

@Serializable
data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String, // image/jpeg, etc
    val data: String // base64
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: FunctionParameters
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContent>,
    val model: String,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

// ─── Unified response ─────────────────────────────────────────────────────────

data class UnifiedResponse(
    val content: String?,
    val toolCalls: List<ToolCallResponse> = emptyList(),
    val finishReason: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val provider: String = "",
    val model: String = ""
)
