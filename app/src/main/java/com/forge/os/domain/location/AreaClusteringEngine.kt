package com.forge.os.domain.location

import com.forge.os.data.location.AreaCluster
import com.forge.os.data.location.LocationFix
import kotlin.math.*

/**
 * DBSCAN clustering engine for detecting visited areas from location fixes.
 *
 * Groups nearby location points into clusters representing distinct areas
 * the user has visited (home, work, gym, etc.).
 */
class AreaClusteringEngine(
    private val epsMeters: Double = 100.0, // Max distance between points in a cluster
    private val minPoints: Int = 3, // Minimum points to form a cluster
) {

    data class ClusterResult(
        val clusters: List<AreaCluster>,
        val noisePoints: Int,
        val totalPoints: Int,
    )

    /**
     * Run DBSCAN clustering on location fixes.
     */
    fun cluster(fixes: List<LocationFix>): ClusterResult {
        if (fixes.isEmpty()) {
            return ClusterResult(emptyList(), 0, 0)
        }

        val points = fixes.map { Point(it.latitude, it.longitude, it.timestamp) }
        val labels = IntArray(points.size) { UNCLASSIFIED }

        var clusterId = 0
        for (i in points.indices) {
            if (labels[i] != UNCLASSIFIED) continue

            val neighbors = findNeighbors(points, i)
            if (neighbors.size < minPoints) {
                labels[i] = NOISE
                continue
            }

            clusterId++
            expandCluster(points, labels, i, neighbors, clusterId)
        }

        // Build cluster results
        val clusters = (1..clusterId).mapNotNull { id ->
            val clusterPoints = points.filterIndexed { index, _ -> labels[index] == id }
            if (clusterPoints.isEmpty()) return@mapNotNull null

            val centerLat = clusterPoints.map { it.lat }.average()
            val centerLng = clusterPoints.map { it.lng }.average()
            val radius = clusterPoints.maxOf { haversine(centerLat, centerLng, it.lat, it.lng) }

            AreaCluster(
                name = null,
                centerLatitude = centerLat,
                centerLongitude = centerLng,
                radiusMeters = radius.toFloat(),
                visitCount = 1,
                firstVisit = clusterPoints.minOf { it.timestamp },
                lastVisit = clusterPoints.maxOf { it.timestamp },
                pointCount = clusterPoints.size,
            )
        }

        val noiseCount = labels.count { it == NOISE }
        return ClusterResult(clusters, noiseCount, points.size)
    }

    /**
     * Merge new clusters with existing ones (update visit counts, expand boundaries).
     */
    fun mergeWithExisting(
        newClusters: List<AreaCluster>,
        existingClusters: List<AreaCluster>,
        mergeDistanceMeters: Double = 150.0,
    ): List<AreaCluster> {
        val result = existingClusters.toMutableList()

        for (newCluster in newClusters) {
            val matchIndex = result.indexOfFirst { existing ->
                haversine(
                    existing.centerLatitude, existing.centerLongitude,
                    newCluster.centerLatitude, newCluster.centerLongitude
                ) < mergeDistanceMeters
            }

            if (matchIndex >= 0) {
                // Merge with existing cluster
                val existing = result[matchIndex]
                val totalPoints = existing.pointCount + newCluster.pointCount
                val weightExisting = existing.pointCount.toDouble() / totalPoints
                val weightNew = newCluster.pointCount.toDouble() / totalPoints

                result[matchIndex] = existing.copy(
                    centerLatitude = existing.centerLatitude * weightExisting + newCluster.centerLatitude * weightNew,
                    centerLongitude = existing.centerLongitude * weightExisting + newCluster.centerLongitude * weightNew,
                    radiusMeters = maxOf(existing.radiusMeters, newCluster.radiusMeters),
                    visitCount = existing.visitCount + 1,
                    firstVisit = minOf(existing.firstVisit, newCluster.firstVisit),
                    lastVisit = maxOf(existing.lastVisit, newCluster.lastVisit),
                    pointCount = totalPoints,
                )
            } else {
                // Add as new cluster
                result.add(newCluster)
            }
        }

        return result
    }

    /**
     * Find which area a location belongs to.
     */
    fun findArea(lat: Double, lng: Double, areas: List<AreaCluster>): AreaCluster? {
        return areas.firstOrNull { area ->
            haversine(area.centerLatitude, area.centerLongitude, lat, lng) <= area.radiusMeters
        }
    }

    // ── DBSCAN Implementation ────────────────────────────────────────────────

    private data class Point(val lat: Double, val lng: Double, val timestamp: Long)

    private fun findNeighbors(points: List<Point>, index: Int): List<Int> {
        val neighbors = mutableListOf<Int>()
        val p = points[index]
        for (i in points.indices) {
            if (haversine(p.lat, p.lng, points[i].lat, points[i].lng) <= epsMeters) {
                neighbors.add(i)
            }
        }
        return neighbors
    }

    private fun expandCluster(
        points: List<Point>,
        labels: IntArray,
        index: Int,
        neighbors: List<Int>,
        clusterId: Int,
    ) {
        labels[index] = clusterId
        val queue = ArrayDeque(neighbors)

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (labels[i] == NOISE) {
                labels[i] = clusterId
            }
            if (labels[i] != UNCLASSIFIED) continue

            labels[i] = clusterId
            val newNeighbors = findNeighbors(points, i)
            if (newNeighbors.size >= minPoints) {
                queue.addAll(newNeighbors.filter { labels[it] == UNCLASSIFIED || labels[it] == NOISE })
            }
        }
    }

    /**
     * Calculate distance between two points in meters using Haversine formula.
     */
    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    companion object {
        private const val UNCLASSIFIED = -1
        private const val NOISE = 0
    }
}
