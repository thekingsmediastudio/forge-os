package com.forge.os.data.api

/** Structured failure that carries enough info for the UI to render a useful message. */
data class ApiError(
    val httpCode: Int,                  // 0 = network/parse failure
    val providerCode: String? = null,   // e.g. "invalid_api_key"
    val message: String,
    val provider: String,
    val model: String,
    val requestId: String? = null,
    val raw: String? = null
) {
    fun userFacing(): String = buildString {
        append(provider)
        if (httpCode > 0) append(" · HTTP $httpCode")
        if (!providerCode.isNullOrBlank()) append(" · $providerCode")
        append("\n")
        append(message.take(400))
    }
}

class ApiCallException(val error: ApiError) : RuntimeException(error.userFacing())
