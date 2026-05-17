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
 *
 * ## Directional Filtering Strategy
 *
 * Many cameras in the dataset have `direction = -1` (unknown orientation).
 * For these, the **bearing-from-user-to-camera** is used as a forward-cone
 * check: only cameras that lie roughly ahead of the user's travel direction
 * are reported. This prevents false alarms for cameras behind the user,
 * on parallel roads, or on the opposite lane.
 *
 * For cameras with a known direction, an additional check ensures the
 * user's heading aligns with the camera's orientation.
 */
object SpatialEngine {

    /** Earth radius in meters */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Forward-cone half-angle for unknown-direction cameras (degrees).
     * A radar must lie within this angle of the user's travel direction
     * to be considered a threat. 75° is wide enough to catch cameras
     * on gentle curves but narrow enough to reject opposite-lane cameras.
     */
    private const val FORWARD_CONE_DEG = 75.0

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
     *  2. If camera orientation is known: user's heading aligns with
     *     the camera's orientation within [angleTolerance] degrees.
     *  3. If camera orientation is unknown: the camera must lie within
     *     [FORWARD_CONE_DEG]° of the user's travel direction (i.e. ahead).
     */
    fun isApproaching(
        userLat: Double,
        userLng: Double,
        userBearing: Double,
        point: EdsPoint,
        radiusM: Double = 1200.0,
        angleTolerance: Double = 45.0
    ): Boolean {
        val distance = haversineDistance(userLat, userLng, point.latitude, point.longitude)
        if (distance > radiusM) return false

        // Bearing from user towards the camera
        val bearingToTarget = calculateBearing(userLat, userLng, point.latitude, point.longitude)
        val relAngle = bearingDifference(userBearing, bearingToTarget)

        if (point.direction >= 0) {
            // Known camera direction: user heading must match camera orientation
            return bearingDifference(userBearing, point.direction) <= angleTolerance
                    && relAngle <= FORWARD_CONE_DEG
        }

        // Unknown direction: camera must be ahead of user
        return relAngle <= FORWARD_CONE_DEG
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
        radiusM: Double = 1200.0,
        angleTolerance: Double = 45.0
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

                // ── Bearing to target (reuses pre-computed trig) ────────
                val x = sin(dLng) * cosPointLat
                val y = cosUserLat * sin(rPointLat) -
                        sinUserLat * cosPointLat * cos(dLng)
                val bearingToTarget = (atan2(x, y) * (180.0 / PI) + 360) % 360

                val relativeAngleToTarget = bearingDifference(userBearing, bearingToTarget)

                // ── Directional filtering ───────────────────────────────
                // If the user is stationary or moving very slowly, GPS bearing is unreliable.
                // In this case, we skip directional filtering and just show the nearest radar.
                val isMoving = userSpeedKmh >= 5.0

                if (point.direction >= 0) {
                    // Known camera direction:
                    //  a) User heading must roughly match camera orientation
                    //  b) Camera must be ahead (not behind)
                    if (isMoving) {
                        val cameraBearingDiff = bearingDifference(userBearing, point.direction)
                        if (cameraBearingDiff > angleTolerance) return@mapNotNull null
                        if (relativeAngleToTarget > FORWARD_CONE_DEG) return@mapNotNull null
                    }
                } else {
                    // Unknown direction: camera must lie within forward cone.
                    // This is the ONLY directional check — it eliminates
                    // cameras behind the user, on parallel roads, or on
                    // the opposite lane.
                    if (isMoving) {
                        if (relativeAngleToTarget > FORWARD_CONE_DEG) return@mapNotNull null
                    }
                }

                val overSpeed = point.speedLimit > 0 && userSpeedKmh > point.speedLimit

                Threat(
                    point = point,
                    distanceM = distance,
                    bearingToTarget = bearingToTarget,
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
    /** Bearing from user to this camera in degrees [0, 360) */
    val bearingToTarget: Double = 0.0,
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
