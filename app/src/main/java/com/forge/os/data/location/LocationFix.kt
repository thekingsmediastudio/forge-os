package com.forge.os.data.location

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single GPS location fix recorded by the background sampler.
 */
@Entity(tableName = "location_fixes")
data class LocationFix(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double? = null,
    val speed: Float? = null,
    val provider: String, // "gps", "network", "fused"
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * A WiFi fingerprint recorded at a location.
 */
@Entity(tableName = "wifi_fingerprints")
data class WifiFingerprint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bssid: String, // MAC address of access point
    val ssid: String?,
    val rssi: Int, // Signal strength in dBm
    val frequency: Int?, // MHz
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * A BLE device sighting.
 */
@Entity(tableName = "ble_sightings")
data class BleSighting(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val macAddress: String,
    val name: String?,
    val rssi: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * A detected area cluster (result of DBSCAN clustering).
 */
@Entity(tableName = "area_clusters")
data class AreaCluster(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String?, // User-assigned name
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Float,
    val visitCount: Int = 1,
    val firstVisit: Long,
    val lastVisit: Long,
    val pointCount: Int, // Number of location fixes in this cluster
)
