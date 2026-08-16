package com.eds.overlay.algorithm

import com.eds.overlay.data.EdsPoint
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SpatialEngine].
 * Runs on JVM — no Android device required.
 */
class SpatialEngineTest {

    // ────────────────────────────────────────────────────────────────
    // Haversine Distance
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `haversine - Istanbul to Ankara is approximately 351 km`() {
        val dist = SpatialEngine.haversineDistance(
            lat1 = 41.0082, lng1 = 28.9784,  // Istanbul
            lat2 = 39.9334, lng2 = 32.8597   // Ankara
        )
        // Expected: ~351 km (±5 km tolerance for formula precision)
        assertEquals(351_000.0, dist, 5_000.0)
    }

    @Test
    fun `haversine - same point returns 0`() {
        val dist = SpatialEngine.haversineDistance(41.0, 29.0, 41.0, 29.0)
        assertEquals(0.0, dist, 0.01)
    }

    @Test
    fun `haversine - short distance about 100m`() {
        // Two points ~100m apart on the same street
        val dist = SpatialEngine.haversineDistance(
            41.00820, 28.97840,
            41.00910, 28.97840    // ~0.0009° latitude ≈ 100m
        )
        assertTrue("Distance should be roughly 100m, was ${dist}m", dist in 80.0..120.0)
    }

    // ────────────────────────────────────────────────────────────────
    // Bearing
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `bearing - due north is 0 degrees`() {
        val b = SpatialEngine.calculateBearing(40.0, 29.0, 41.0, 29.0)
        assertEquals(0.0, b, 1.0)
    }

    @Test
    fun `bearing - due east is approximately 90 degrees`() {
        val b = SpatialEngine.calculateBearing(41.0, 28.0, 41.0, 29.0)
        assertEquals(90.0, b, 2.0)  // slight curvature at 41°N
    }

    @Test
    fun `bearing - due south is 180 degrees`() {
        val b = SpatialEngine.calculateBearing(41.0, 29.0, 40.0, 29.0)
        assertEquals(180.0, b, 1.0)
    }

    // ────────────────────────────────────────────────────────────────
    // Bearing Difference
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `bearingDifference - 10 and 350 should be 20`() {
        assertEquals(20.0, SpatialEngine.bearingDifference(10.0, 350.0), 0.01)
    }

    @Test
    fun `bearingDifference - same bearing is 0`() {
        assertEquals(0.0, SpatialEngine.bearingDifference(90.0, 90.0), 0.01)
    }

    @Test
    fun `bearingDifference - opposite bearings is 180`() {
        assertEquals(180.0, SpatialEngine.bearingDifference(0.0, 180.0), 0.01)
    }

    @Test
    fun `bearingDifference - wraparound 5 and 355 is 10`() {
        assertEquals(10.0, SpatialEngine.bearingDifference(5.0, 355.0), 0.01)
    }

    // ────────────────────────────────────────────────────────────────
    // isApproaching
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `isApproaching - user heading toward camera within range`() {
        // Camera is ~111m SOUTH of the user; user heads south (180°);
        // camera monitors southbound traffic (direction = 180).
        val camera = EdsPoint(
            latitude = 41.007, longitude = 28.978,
            direction = 180.0, speedLimit = 50
        )
        val result = SpatialEngine.isApproaching(
            userLat = 41.008, userLng = 28.978,
            userBearing = 180.0,
            point = camera,
            radiusM = 500.0
        )
        assertTrue("Should detect approaching camera", result)
    }

    @Test
    fun `isApproaching - camera behind user is rejected`() {
        // Camera is ~111m NORTH of the user, but the user heads south —
        // the camera is behind and must be filtered by the forward cone.
        val camera = EdsPoint(
            latitude = 41.009, longitude = 28.978,
            direction = 180.0, speedLimit = 50
        )
        val result = SpatialEngine.isApproaching(
            userLat = 41.008, userLng = 28.978,
            userBearing = 180.0,
            point = camera,
            radiusM = 500.0
        )
        assertFalse("Camera behind the user should NOT be detected", result)
    }

    @Test
    fun `isApproaching - user heading away from camera`() {
        val camera = EdsPoint(
            latitude = 41.009, longitude = 28.978,
            direction = 180.0, speedLimit = 50
        )
        val result = SpatialEngine.isApproaching(
            userLat = 41.008, userLng = 28.978,
            userBearing = 0.0,   // heading north, camera faces south
            point = camera,
            radiusM = 500.0
        )
        assertFalse("Should NOT detect when heading away", result)
    }

    @Test
    fun `isApproaching - user out of range`() {
        val camera = EdsPoint(
            latitude = 42.0, longitude = 29.0,    // ~111 km away
            direction = 180.0, speedLimit = 50
        )
        val result = SpatialEngine.isApproaching(
            userLat = 41.0, userLng = 29.0,
            userBearing = 180.0,
            point = camera,
            radiusM = 1000.0
        )
        assertFalse("Should NOT detect camera >1km away", result)
    }

    @Test
    fun `isApproaching - unknown camera direction passes when camera is ahead`() {
        // Camera is directly south of the user; user heads south →
        // camera lies in the forward cone, so unknown direction passes.
        val camera = EdsPoint(
            latitude = 41.0082, longitude = 28.9784,
            direction = -1.0,    // unknown
            speedLimit = 50
        )
        val result = SpatialEngine.isApproaching(
            userLat = 41.0083, userLng = 28.9784,
            userBearing = 180.0,
            point = camera,
            radiusM = 500.0
        )
        assertTrue("Unknown direction should pass when camera is ahead", result)
    }

    // ────────────────────────────────────────────────────────────────
    // findThreats
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `findThreats - filters and sorts correctly`() {
        // User heads south (180°), so "ahead" means smaller latitude.
        val cameras = listOf(
            // Ahead, monitoring southbound traffic → should match
            EdsPoint(latitude = 41.0077, longitude = 28.978, direction = 180.0, speedLimit = 50),
            // Ahead, but monitoring the opposite (northbound) lane → should NOT match
            EdsPoint(latitude = 41.0076, longitude = 28.978, direction = 0.0, speedLimit = 50),
            // Behind the user → should NOT match
            EdsPoint(latitude = 41.0084, longitude = 28.978, direction = 180.0, speedLimit = 50),
            // Far away → should NOT match
            EdsPoint(latitude = 42.0, longitude = 29.0, direction = 180.0, speedLimit = 50),
        )

        val threats = SpatialEngine.findThreats(
            userLat = 41.008, userLng = 28.978,
            userBearing = 180.0,
            userSpeedKmh = 60.0,    // above 50 limit
            candidates = cameras,
            radiusM = 1000.0
        )

        assertEquals("Only 1 camera should match", 1, threats.size)
        assertTrue("Should be over speed", threats[0].isOverSpeed)
        assertEquals(Threat.Level.DANGER, threats[0].level)
    }

    @Test
    fun `findThreats - stationary user gets distance-only results`() {
        // Below 15 km/h the GPS bearing is unreliable → directional
        // filtering must be skipped and even a camera "behind" is reported.
        val cameras = listOf(
            EdsPoint(latitude = 41.0084, longitude = 28.978, direction = 180.0, speedLimit = 50)
        )

        val threats = SpatialEngine.findThreats(
            userLat = 41.008, userLng = 28.978,
            userBearing = 180.0,
            userSpeedKmh = 5.0,
            candidates = cameras,
            radiusM = 1000.0
        )

        assertEquals("Stationary user should still see nearby radar", 1, threats.size)
    }

    @Test
    fun `findThreats - missing bearing skips directional filtering`() {
        // Negative bearing = sentinel for "GPS fix carries no bearing".
        // Directional filtering must be skipped even at driving speed.
        val cameras = listOf(
            EdsPoint(latitude = 41.0084, longitude = 28.978, direction = 180.0, speedLimit = 50)
        )

        val threats = SpatialEngine.findThreats(
            userLat = 41.008, userLng = 28.978,
            userBearing = -1.0,
            userSpeedKmh = 60.0,
            candidates = cameras,
            radiusM = 1000.0
        )

        assertEquals("Missing bearing should not filter out radars", 1, threats.size)
    }

    @Test
    fun `findThreats - returns empty when no cameras in radius`() {
        val cameras = listOf(
            EdsPoint(latitude = 42.0, longitude = 30.0, direction = 0.0, speedLimit = 50)
        )

        val threats = SpatialEngine.findThreats(
            userLat = 41.0, userLng = 29.0,
            userBearing = 0.0,
            userSpeedKmh = 50.0,
            candidates = cameras
        )

        assertTrue("No threats expected", threats.isEmpty())
    }

    @Test
    fun `findThreats - under speed limit returns SAFE or WARNING level`() {
        // Camera ahead of the southbound user, monitoring southbound traffic
        val cameras = listOf(
            EdsPoint(latitude = 41.0077, longitude = 28.978, direction = 180.0, speedLimit = 100)
        )

        val threats = SpatialEngine.findThreats(
            userLat = 41.008, userLng = 28.978,
            userBearing = 180.0,
            userSpeedKmh = 60.0,     // well under 100 limit
            candidates = cameras,
            radiusM = 1000.0
        )

        assertEquals(1, threats.size)
        assertFalse("Should NOT be over speed", threats[0].isOverSpeed)
        assertNotEquals(Threat.Level.DANGER, threats[0].level)
    }
}
