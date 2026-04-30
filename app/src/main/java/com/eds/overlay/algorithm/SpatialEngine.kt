package com.eds.overlay.algorithm

import com.eds.overlay.data.EdsPoint
import kotlin.math.*
import kotlin.math.PI

/**
 * Core spatial proximity and vector alignment engine.
 *
 * Implements the Haversine formula for distance and bearing-based
 * directional filtering to eliminate false-positive alerts
 * (e.g., cameras on parallel roads or facing the opposite direction).
 */
object SpatialEngine {

    /** Earth radius in meters */
    private const val EARTH_RADIUS_M = 6_371_000.0

    // ── Distance ────────────────────────────────────────────────────

    /**
     * Haversine great-circle distance between two WGS84 points.
     * @return distance in **meters**
     */
    fun haversineDistance(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLng = (lng2 - lng1).toRadians()
        val rLat1 = lat1.toRadians()
        val rLat2 = lat2.toRadians()

        val a = sin(dLat / 2).pow(2) +
                cos(rLat1) * cos(rLat2) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))

        return EARTH_RADIUS_M * c
    }

    // ── Bearing ─────────────────────────────────────────────────────

    /**
     * Initial bearing (forward azimuth) from point 1 to point 2.
     * @return bearing in **degrees** [0, 360)
     */
    fun calculateBearing(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val rLat1 = lat1.toRadians()
        val rLat2 = lat2.toRadians()
        val dLng = (lng2 - lng1).toRadians()

        val x = sin(dLng) * cos(rLat2)
        val y = cos(rLat1) * sin(rLat2) -
                sin(rLat1) * cos(rLat2) * cos(dLng)

        val bearing = atan2(x, y) * (180.0 / PI)
        return (bearing + 360) % 360
    }

    /**
     * Smallest angular difference between two bearings.
     * @return value in **degrees** [0, 180]
     */
    fun bearingDifference(bearing1: Double, bearing2: Double): Double {
        val diff = abs(bearing1 - bearing2) % 360
        return if (diff > 180) 360 - diff else diff
    }

    // ── Composite Checks ────────────────────────────────────────────

    /**
     * Determines if the user is approaching a specific EDS point.
     *
     * Conditions:
     *  1. User is within [radiusM] meters of the camera.
     *  2. User's heading aligns with the camera's orientation
     *     within [angleTolerance] degrees.
     *
     * If the camera orientation is unknown (direction < 0), only
     * the proximity check is applied.
     */
    fun isApproaching(
        userLat: Double,
        userLng: Double,
        userBearing: Double,
        point: EdsPoint,
        radiusM: Double = 1000.0,
        angleTolerance: Double = 25.0
    ): Boolean {
        val distance = haversineDistance(userLat, userLng, point.latitude, point.longitude)
        if (distance > radiusM) return false

        // If camera orientation unknown, rely on proximity only
        if (point.direction < 0) return true

        return bearingDifference(userBearing, point.direction) <= angleTolerance
    }

    /**
     * Full threat detection pipeline.
     *
     * @param userLat       user latitude
     * @param userLng       user longitude
     * @param userBearing   user heading in degrees (from GPS)
     * @param userSpeedKmh  user speed in km/h
     * @param candidates    pre-filtered EDS points (from bounding-box query)
     * @param radiusM       alert radius in meters
     * @param angleTolerance directional tolerance in degrees
     *
     * @return list of [Threat] objects sorted by distance (nearest first)
     */
    fun findThreats(
        userLat: Double,
        userLng: Double,
        userBearing: Double,
        userSpeedKmh: Double,
        candidates: List<EdsPoint>,
        radiusM: Double = 1000.0,
        angleTolerance: Double = 25.0
    ): List<Threat> {
        // Pre-compute user trig values (reused for every candidate)
        val rUserLat = userLat.toRadians()
        val cosUserLat = cos(rUserLat)
        val sinUserLat = sin(rUserLat)

        return candidates
            .mapNotNull { point ->
                // ── Distance (Haversine, stable atan2 form) ─────────────
                val rPointLat = point.latitude.toRadians()
                val dLat = rPointLat - rUserLat
                val dLng = (point.longitude - userLng).toRadians()
                val cosPointLat = cos(rPointLat)
                val sinDLatHalf = sin(dLat / 2)
                val sinDLngHalf = sin(dLng / 2)
                val a = sinDLatHalf * sinDLatHalf +
                        cosUserLat * cosPointLat * sinDLngHalf * sinDLngHalf
                val distance = EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1.0 - a))

                if (distance > radiusM) return@mapNotNull null

                // Directional filter (skip if orientation unknown)
                if (point.direction >= 0) {
                    val cameraBearingDiff = bearingDifference(userBearing, point.direction)
                    if (cameraBearingDiff > angleTolerance) return@mapNotNull null
                }

                // ── Bearing to target (reuses pre-computed trig) ────────
                // IMPORTANT: If camera is behind user (>85° relative to travel dir)
                // drop it immediately. Prevents alerts for passed radars / opposite lane.
                val x = sin(dLng) * cosPointLat
                val y = cosUserLat * sin(rPointLat) -
                        sinUserLat * cosPointLat * cos(dLng)
                val bearingToTarget = (atan2(x, y) * (180.0 / PI) + 360) % 360

                val relativeAngleToTarget = bearingDifference(userBearing, bearingToTarget)
                if (relativeAngleToTarget > 85.0) return@mapNotNull null

                val overSpeed = point.speedLimit > 0 && userSpeedKmh > point.speedLimit

                Threat(
                    point = point,
                    distanceM = distance,
                    isOverSpeed = overSpeed,
                    speedDeltaKmh = if (point.speedLimit > 0) userSpeedKmh - point.speedLimit else 0.0
                )
            }
            .sortedBy { it.distanceM }
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

/**
 * Represents a detected threat — an EDS camera the user is approaching.
 */
data class Threat(
    val point: EdsPoint,
    /** Distance from the user to this camera in meters */
    val distanceM: Double,
    /** True if the user's speed exceeds the camera's speed limit */
    val isOverSpeed: Boolean,
    /** Speed above limit in km/h (negative means below limit) */
    val speedDeltaKmh: Double
) {
    /** Severity levels for UI rendering */
    enum class Level { SAFE, WARNING, DANGER }

    val level: Level
        get() = when {
            isOverSpeed -> Level.DANGER
            distanceM < 500 -> Level.WARNING
            else -> Level.SAFE
        }
}
