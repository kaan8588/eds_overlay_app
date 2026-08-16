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

    /**
     * Camera detection-cone half-angle (degrees).
     * For cameras with a known orientation, the detection zone is a cone
     * extending in the *approach* direction (opposite of camera.direction).
     * 60° is wide enough to cover multi-lane roads and gentle curves.
     */
    private const val CAMERA_CONE_HALF_DEG = 60.0

    /**
     * Minimum speed (km/h) at which GPS bearing is considered reliable.
     * Below this, directional filtering is skipped entirely — a stationary
     * or crawling user gets distance-only results.
     */
    private const val MIN_SPEED_FOR_BEARING_KMH = 15.0

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
     *  2. The camera lies within [FORWARD_CONE_DEG]° of the user's
     *     travel direction (i.e. ahead of the user).
     *  3. If camera orientation is known: the user must additionally be
     *     inside the camera's detection cone ([CAMERA_CONE_HALF_DEG]°
     *     around the approach direction).
     */
    fun isApproaching(
        userLat: Double,
        userLng: Double,
        userBearing: Double,
        point: EdsPoint,
        radiusM: Double = 1200.0
    ): Boolean {
        val distance = haversineDistance(userLat, userLng, point.latitude, point.longitude)
        if (distance > radiusM) return false

        // Bearing from user towards the camera
        val bearingToTarget = calculateBearing(userLat, userLng, point.latitude, point.longitude)
        val relAngle = bearingDifference(userBearing, bearingToTarget)

        if (point.direction >= 0) {
            // Known camera direction → check if user is inside the camera's
            // detection cone. The cone extends opposite to camera.direction
            // (the approach zone where vehicles drive towards the camera).
            val approachDir = (point.direction + 180.0) % 360.0
            val bearingCamToUser = (bearingToTarget + 180.0) % 360.0
            val coneAngle = bearingDifference(bearingCamToUser, approachDir)
            if (coneAngle > CAMERA_CONE_HALF_DEG) return false
            // Also ensure camera is ahead of user
            return relAngle <= FORWARD_CONE_DEG
        }

        // Unknown direction: camera must be ahead of user
        return relAngle <= FORWARD_CONE_DEG
    }

    /**
     * Full threat detection pipeline.
     *
     * @param userLat       user latitude
     * @param userLng       user longitude
     * @param userBearing   user heading in degrees [0, 360), or a negative
     *                      value if the GPS fix carries no bearing
     * @param userSpeedKmh  user speed in km/h
     * @param candidates    pre-filtered EDS points (from bounding-box query)
     * @param radiusM       alert radius in meters
     *
     * @return list of [Threat] objects sorted by distance (nearest first)
     */
    fun findThreats(
        userLat: Double,
        userLng: Double,
        userBearing: Double,
        userSpeedKmh: Double,
        candidates: List<EdsPoint>,
        radiusM: Double = 1200.0
    ): List<Threat> {
        // Pre-compute user trig values (reused for every candidate)
        val rUserLat = userLat.toRadians()
        val cosUserLat = cos(rUserLat)
        val sinUserLat = sin(rUserLat)

        // Directional filtering requires a trustworthy heading: the user must
        // be moving fast enough for GPS bearing to stabilize AND the fix must
        // actually carry a bearing (negative = sentinel for "no bearing").
        val useDirectionalFilter =
            userSpeedKmh >= MIN_SPEED_FOR_BEARING_KMH && userBearing >= 0.0

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

                // ── Directional filtering ───────────────────────────────
                // Skipped entirely when the heading is unreliable (slow speed
                // or missing bearing) — distance-only results in that case.
                if (useDirectionalFilter) {
                    val relativeAngleToTarget = bearingDifference(userBearing, bearingToTarget)

                    // Forward cone: camera must lie ahead of the user.
                    // Eliminates cameras behind, on parallel roads, or on
                    // the opposite lane.
                    if (relativeAngleToTarget > FORWARD_CONE_DEG) return@mapNotNull null

                    if (point.direction >= 0) {
                        // Known camera direction → detection cone check.
                        // The cone extends from the camera in the approach
                        // direction (opposite of camera.direction). If the
                        // user is inside this cone, the camera can see them.
                        val approachDir = (point.direction + 180.0) % 360.0
                        val bearingCamToUser = (bearingToTarget + 180.0) % 360.0
                        val coneAngle = bearingDifference(bearingCamToUser, approachDir)
                        if (coneAngle > CAMERA_CONE_HALF_DEG) return@mapNotNull null
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
