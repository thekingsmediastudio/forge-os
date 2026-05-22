package com.forge.os.domain.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.config.SafeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class GeofenceManager @Inject constructor(
    private val context: Context,
    private val configRepository: ConfigRepository
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    private val _isInSafeZone = MutableStateFlow(false)
    val isInSafeZone: StateFlow<Boolean> = _isInSafeZone

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _currentLocation.value = location
            checkSafeZones(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                locationManager.requestLocationUpdates(
                    provider,
                    10000L, // 10 seconds
                    10f,    // 10 meters
                    locationListener
                )
            }
        } catch (e: Exception) {
            // Silently fail if permissions are missing; the TrustManager will handle the lack of data
        }
    }

    fun stopMonitoring() {
        locationManager.removeUpdates(locationListener)
    }

    private fun checkSafeZones(location: Location) {
        val zones = configRepository.get().environmentCalibration.safeZones
        var matched = false
        
        for (zone in zones) {
            val distance = calculateDistance(
                location.latitude, location.longitude,
                zone.latitude, zone.longitude
            )
            if (distance <= zone.radiusMeters) {
                matched = true
                break
            }
        }
        _isInSafeZone.value = matched
    }

    /**
     * Haversine formula to calculate distance between two points in meters.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val phi1 = lat1 * PI / 180
        val phi2 = lat2 * PI / 180
        val deltaPhi = (lat2 - lat1) * PI / 180
        val deltaLon = (lon2 - lon1) * PI / 180

        val a = sin(deltaPhi / 2).pow(2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}
