package com.eds.overlay.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.util.LruCache
import kotlin.math.cos

/**
 * Repository layer between the DAO and the rest of the app.
 *
 * Handles JSON import from assets, bounding-box spatial queries,
 * and provides the primary data access interface.
 */
class EdsRepository(private val dao: EdsDao) {

    companion object {
        private const val TAG = "EdsRepository"

        /**
         * Approximate degrees-per-km at mid-latitudes (~41° for Istanbul).
         * Used to convert a km radius into a lat/lng bounding box.
         */
        private const val DEG_PER_KM_LAT = 1.0 / 111.0       // ~0.009°
        private fun degPerKmLng(lat: Double): Double {
            val radLat = Math.toRadians(lat)
            return 1.0 / (111.0 * cos(radLat))
        }

        /** Singleton Gson instance — avoids reflection-heavy re-init per call */
        private val gson = Gson()

        private val OBFUSCATION_KEY = byteArrayOf(
            0x4D, 0x75, 0x61, 0x76, 0x69, 0x6E, 0x45, 0x44, 0x53, 0x32, 0x30, 0x32, 0x36
        )

        /**
         * Decodes raw asset bytes to string.
         * Transparently supports plain JSON UTF-8 or XOR-obfuscated payloads.
         */
        fun decodeAssetPayload(bytes: ByteArray): String {
            if (bytes.isEmpty()) return "[]"
            var firstByte: Byte = 0
            for (b in bytes) {
                if (b != 0x20.toByte() && b != 0x09.toByte() && b != 0x0A.toByte() && b != 0x0D.toByte()) {
                    firstByte = b
                    break
                }
            }
            if (firstByte == 0x5B.toByte() || firstByte == 0x7B.toByte()) {
                return String(bytes, Charsets.UTF_8)
            }
            val decoded = ByteArray(bytes.size)
            for (i in bytes.indices) {
                decoded[i] = (bytes[i].toInt() xor OBFUSCATION_KEY[i % OBFUSCATION_KEY.size].toInt()).toByte()
            }
            return String(decoded, Charsets.UTF_8)
        }
    }

    // L1 Cache: In-memory store to avoid repeated DB hits for the same location
    private val cache = LruCache<String, List<EdsPoint>>(20)

    // Serializes imports so concurrent calls can't race on deleteAll + insertAll
    private val importMutex = Mutex()

    // Serializes cache access to prevent TOCTOU races on get-then-put
    private val cacheMutex = Mutex()

    // ── JSON Import ─────────────────────────────────────────────────

    /**
     * Imports EDS data from `assets/eds_data.json` into Room.
     * Clears existing data first (full refresh strategy).
     * Protected by [importMutex] to prevent concurrent invocations from racing.
     */
    suspend fun importFromAssets(context: Context): Int = importMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val rawBytes = context.assets.open("eds_data.json").use { it.readBytes() }
                val json = decodeAssetPayload(rawBytes)

                val type = object : TypeToken<List<RawEdsEntry>>() {}.type
                val rawEntries: List<RawEdsEntry> = gson.fromJson(json, type)

                val points = rawEntries.mapNotNull { entry ->
                    // SEC-3: Validate coordinate and field bounds
                    if (entry.lat.isNaN() || entry.lat.isInfinite() ||
                        entry.lat < -90.0 || entry.lat > 90.0) return@mapNotNull null
                    if (entry.lng.isNaN() || entry.lng.isInfinite() ||
                        entry.lng < -180.0 || entry.lng > 180.0) return@mapNotNull null

                    val validDirection = (entry.direction ?: -1.0).let {
                        if (it.isNaN() || it.isInfinite() || it < -1.0 || it > 360.0) -1.0 else it
                    }
                    val validSpeed = (entry.speedLimit ?: 0).coerceIn(0, 300)

                    EdsPoint(
                        latitude = entry.lat,
                        longitude = entry.lng,
                        direction = validDirection,
                        speedLimit = validSpeed,
                        type = entry.type ?: "EDS",
                        lastUpdated = entry.lastUpdated ?: "",
                        description = entry.description ?: ""
                    )
                }

                dao.replaceAll(points)
                cache.evictAll()
                val count = dao.getCount()
                // Import complete
                count
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import EDS data", e)
                0
            }
        }
    }

    // ── Spatial Queries ─────────────────────────────────────────────

    /**
     * Returns all EDS points within approximately [radiusKm] of the given position.
     * Uses an in-memory cache and a bounding-box pre-filter in SQL.
     *
     * Cache key includes [radiusKm] so different radii at the same location
     * never collide and return stale results.
     */
    suspend fun getNearbyPoints(lat: Double, lng: Double, radiusKm: Double): List<EdsPoint> {
        // Quantize coordinates to ~11m for tight cache key — coarser keys cause
        // stale results that miss nearby radars after short movements.
        val queryKey = "%.4f,%.4f,%.1f".format(lat, lng, radiusKm)

        // Fast-path: check cache under short lock
        cacheMutex.withLock { cache.get(queryKey) }?.let { return it }

        val dLat = radiusKm * DEG_PER_KM_LAT
        val dLng = radiusKm * degPerKmLng(lat)

        val result = withContext(Dispatchers.IO) {
            dao.getPointsInBoundingBox(
                minLat = lat - dLat,
                maxLat = lat + dLat,
                minLng = lng - dLng,
                maxLng = lng + dLng
            )
        }

        cacheMutex.withLock { cache.put(queryKey, result) }

        return result
    }

    suspend fun getCount(): Int = dao.getCount()

    suspend fun getAll(): List<EdsPoint> = dao.getAll()

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
        cache.evictAll()
    }

    suspend fun insertAll(points: List<EdsPoint>) = withContext(Dispatchers.IO) {
        dao.insertAll(points)
    }

    // ── Internal JSON Model ─────────────────────────────────────────

    private data class RawEdsEntry(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val direction: Double? = null,
        val speedLimit: Int? = null,
        val type: String? = null,
        val lastUpdated: String? = null,
        val description: String? = null
    )
}
