package com.forge.os.domain.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.config.SafeZone
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Manages geofence-based security and trust scoring.
 * Tracks whether the device is in a known safe location.
 */
@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    /**
     * Check if the device is currently in a safe zone.
     * Returns true if location matches any configured safe zone.
     */
    fun isInSafeZone(): Boolean {
        val safeZones = configRepository.get().environmentCalibration.safeZones
        if (safeZones.isEmpty()) {
            Timber.d("GeofenceManager: No safe zones configured")
            return false
        }
        
        val currentLocation = getCurrentLocation() ?: run {
            Timber.w("GeofenceManager: Unable to get current location")
            return false
        }
        
        return safeZones.any { zone ->
            val distance = calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                zone.latitude,
                zone.longitude
            )
            distance <= zone.radiusMeters
        }
    }
    
    /**
     * Get the name of the current safe zone, if any.
     * Returns null if not in a safe zone.
     */
    fun getCurrentSafeZone(): String? {
        val safeZones = configRepository.get().environmentCalibration.safeZones
        if (safeZones.isEmpty()) {
            return null
        }
        
        val currentLocation = getCurrentLocation() ?: return null
        
        return safeZones.firstOrNull { zone ->
            val distance = calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                zone.latitude,
                zone.longitude
            )
            distance <= zone.radiusMeters
        }?.name
    }
    
    /**
     * Calculate distance to nearest safe zone in meters.
     * Returns null if no safe zones configured or location unavailable.
     */
    fun getDistanceToNearestSafeZone(): Double? {
        val safeZones = configRepository.get().environmentCalibration.safeZones
        if (safeZones.isEmpty()) {
            return null
        }
        
        val currentLocation = getCurrentLocation() ?: return null
        
        return safeZones.minOfOrNull { zone ->
            calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                zone.latitude,
                zone.longitude
            )
        }
    }
    
    /**
     * Get current device location if permissions are granted.
     * Returns null if location unavailable or permissions denied.
     */
    private fun getCurrentLocation(): Location? {
        // Check for location permissions
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasFineLocation && !hasCoarseLocation) {
            Timber.w("GeofenceManager: Location permissions not granted")
            return null
        }
        
        return try {
            // Try GPS first (most accurate)
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLocation != null && isLocationRecent(gpsLocation)) {
                return gpsLocation
            }
            
            // Fall back to network location
            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (networkLocation != null && isLocationRecent(networkLocation)) {
                return networkLocation
            }
            
            // Return whichever is available, even if not recent
            gpsLocation ?: networkLocation
        } catch (e: SecurityException) {
            Timber.e(e, "GeofenceManager: Security exception getting location")
            null
        } catch (e: Exception) {
            Timber.e(e, "GeofenceManager: Error getting location")
            null
        }
    }
    
    /**
     * Check if a location is recent (within last 5 minutes).
     */
    private fun isLocationRecent(location: Location): Boolean {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        return location.time >= fiveMinutesAgo
    }
    
    /**
     * Calculate distance between two coordinates using Haversine formula.
     * Returns distance in meters.
     */
    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadiusMeters = 6371000.0 // Earth's radius in meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadiusMeters * c
    }
}
