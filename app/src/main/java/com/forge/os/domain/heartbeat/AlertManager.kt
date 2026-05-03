package com.forge.os.domain.heartbeat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ForgeAlert(
    val id: String = System.currentTimeMillis().toString(),
    val component: String,
    val severity: AlertSeverity,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
    val autoHealed: Boolean = false
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }

@Singleton
class AlertManager @Inject constructor() {
    private val _alerts = MutableSharedFlow<ForgeAlert>(replay = 10)
    val alerts: SharedFlow<ForgeAlert> = _alerts

    private val activeAlerts = mutableListOf<ForgeAlert>()

    suspend fun send(component: String, message: String, severity: AlertSeverity = AlertSeverity.WARNING) {
        val alert = ForgeAlert(component = component, severity = severity, message = message)
        activeAlerts.add(alert)
        _alerts.emit(alert)
        Timber.w("[$severity] $component: $message")
    }

    suspend fun sendAlert(status: SystemStatus) {
        for (alert in status.alerts) {
            send(alert.component, alert.message, AlertSeverity.WARNING)
        }
        if (status.overallHealth == HealthLevel.CRITICAL) {
            send("system", "System health is CRITICAL — immediate attention needed", AlertSeverity.CRITICAL)
        }
    }

    fun resolve(alertId: String) {
        val idx = activeAlerts.indexOfFirst { it.id == alertId }
        if (idx >= 0) {
            activeAlerts[idx] = activeAlerts[idx].copy(resolved = true)
        }
    }

    fun getActive(): List<ForgeAlert> = activeAlerts.filter { !it.resolved }

    fun clearAll() = activeAlerts.clear()
}
