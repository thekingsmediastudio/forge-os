package com.forge.os.domain.notifications

enum class AppAlertType {
    INFO, WARNING, SUCCESS, ERROR
}

data class AppAlert(
    val title: String,
    val body: String,
    val timestampMs: Long,
    val type: AppAlertType
)
