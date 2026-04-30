package com.eds.overlay.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface EdsDao {

    /**
     * Fast bounding-box pre-filter.
     * Returns all points whose lat/lng fall within the given rectangle.
     * The caller is expected to apply Haversine for accurate radial filtering.
     */
    @Query(
        """
        SELECT * FROM eds_points
        WHERE latitude  BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLng AND :maxLng
        """
    )
    suspend fun getPointsInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<EdsPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<EdsPoint>)

    @Query("DELETE FROM eds_points")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(points: List<EdsPoint>) {
        deleteAll()
        insertAll(points)
    }

    @Query("SELECT COUNT(*) FROM eds_points")
    suspend fun getCount(): Int

    @Query("SELECT * FROM eds_points")
    suspend fun getAll(): List<EdsPoint>
}
