package com.forge.os.domain.sensor

import com.forge.os.domain.agent.AgentTool
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.config.SafeZone
import javax.inject.Inject

class MarkSafeZoneTool @Inject constructor(
    private val geofenceManager: GeofenceManager,
    private val configRepository: ConfigRepository
) : AgentTool {
    override val name = "mark_safe_zone"
    override val description = "Marks the current GPS location as a 'Sanctuary' (Safe Zone). Forge will boost Trust and relax security here. Parameters: name (e.g. 'Home'), radius (optional, default 100m)."

    override suspend fun execute(args: Map<String, String>): String {
        val name = args["name"] ?: return "Error: 'name' parameter required."
        val radius = args["radius"]?.toFloatOrNull() ?: 100f
        
        val location = geofenceManager.currentLocation.value 
            ?: return "Error: GPS location not available. Ensure GPS is ON and you are outdoors/near a window."

        val newZone = SafeZone(
            name = name,
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = radius
        )

        configRepository.update { 
            it.copy(environmentCalibration = it.environmentCalibration.copy(
                safeZones = it.environmentCalibration.safeZones + newZone
            ))
        }

        return "✅ Sanctuary established: '$name' at [${location.latitude}, ${location.longitude}]. Forge now recognizes this perimeter as safe."
    }
}
