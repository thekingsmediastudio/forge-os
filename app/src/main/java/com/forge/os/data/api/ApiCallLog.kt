package com.forge.os.data.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ApiCallLogEntry(
    val timestamp: Long,
    val provider: String,
    val model: String,
    val url: String,
    val httpCode: Int,           // 0 if exception before response
    val durationMs: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val errorMessage: String? = null,
    val attempt: Int = 1
) {
    val ok: Boolean get() = errorMessage == null && httpCode in 200..299
}

/** In-memory ring buffer of recent API calls for the diagnostics screen. */
@Singleton
class ApiCallLog @Inject constructor() {
    private val maxEntries = 200
    private val _entries = MutableStateFlow<List<ApiCallLogEntry>>(emptyList())
    val entries: StateFlow<List<ApiCallLogEntry>> = _entries

    fun record(entry: ApiCallLogEntry) {
        val current = _entries.value
        val next = (current + entry).takeLast(maxEntries)
        _entries.value = next
    }

    fun clear() { _entries.value = emptyList() }
}
