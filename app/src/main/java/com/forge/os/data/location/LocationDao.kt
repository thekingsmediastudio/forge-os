package com.forge.os.data.location

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    // ── Location Fixes ───────────────────────────────────────────────────────

    @Insert
    suspend fun insertLocationFix(fix: LocationFix): Long

    @Query("SELECT * FROM location_fixes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentFixes(limit: Int = 100): List<LocationFix>

    @Query("SELECT * FROM location_fixes WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getFixesSince(since: Long): List<LocationFix>

    @Query("SELECT * FROM location_fixes ORDER BY timestamp ASC")
    suspend fun getAllFixes(): List<LocationFix>

    @Query("SELECT COUNT(*) FROM location_fixes")
    suspend fun getFixCount(): Int

    @Query("DELETE FROM location_fixes WHERE timestamp < :before")
    suspend fun deleteOldFixes(before: Long)

    @Query("DELETE FROM location_fixes")
    suspend fun deleteAllFixes()

    // ── WiFi Fingerprints ────────────────────────────────────────────────────

    @Insert
    suspend fun insertWifiFingerprint(fingerprint: WifiFingerprint): Long

    @Query("SELECT * FROM wifi_fingerprints WHERE timestamp >= :since")
    suspend fun getWifiFingerprintsSince(since: Long): List<WifiFingerprint>

    @Query("DELETE FROM wifi_fingerprints WHERE timestamp < :before")
    suspend fun deleteOldWifiFingerprints(before: Long)

    // ── BLE Sightings ────────────────────────────────────────────────────────

    @Insert
    suspend fun insertBleSighting(sighting: BleSighting): Long

    @Query("SELECT * FROM ble_sightings WHERE timestamp >= :since")
    suspend fun getBleSightingsSince(since: Long): List<BleSighting>

    @Query("DELETE FROM ble_sightings WHERE timestamp < :before")
    suspend fun deleteOldBleSightings(before: Long)

    // ── Area Clusters ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAreaCluster(cluster: AreaCluster): Long

    @Update
    suspend fun updateAreaCluster(cluster: AreaCluster)

    @Delete
    suspend fun deleteAreaCluster(cluster: AreaCluster)

    @Query("SELECT * FROM area_clusters ORDER BY lastVisit DESC")
    fun getAllAreaClusters(): Flow<List<AreaCluster>>

    @Query("SELECT * FROM area_clusters ORDER BY visitCount DESC LIMIT :limit")
    suspend fun getTopAreas(limit: Int = 10): List<AreaCluster>

    @Query("SELECT * FROM area_clusters WHERE id = :id")
    suspend fun getAreaClusterById(id: Long): AreaCluster?

    @Query("UPDATE area_clusters SET name = :name WHERE id = :id")
    suspend fun renameAreaCluster(id: Long, name: String)

    @Query("DELETE FROM area_clusters")
    suspend fun deleteAllAreaClusters()

    @Query("SELECT COUNT(*) FROM area_clusters")
    suspend fun getAreaClusterCount(): Int
}
