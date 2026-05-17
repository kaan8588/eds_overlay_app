package com.eds.overlay.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single EDS (Electronic Detection System) speed camera point.
 *
 * Indexed on latitude and longitude for fast bounding-box spatial queries.
 */
@Entity(
    tableName = "eds_points",
    indices = [
        // Composite index covers bounding-box queries (latitude + longitude).
        // The leading-column rule means latitude-only queries also benefit.
        // A separate longitude index is not needed since no query filters
        // on longitude alone.
        Index(value = ["latitude", "longitude"])
    ]
)
data class EdsPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Latitude in decimal degrees (WGS84) */
    val latitude: Double,

    /** Longitude in decimal degrees (WGS84) */
    val longitude: Double,

    /**
     * Camera orientation in degrees (0–360).
     * 0 = North, 90 = East, 180 = South, 270 = West.
     * -1 indicates orientation is unknown.
     */
    val direction: Double = -1.0,

    /** Speed limit enforced by this camera (km/h) */
    val speedLimit: Int = 0,

    /** Camera type label, e.g. "EDS", "RedLight", "Average" */
    val type: String = "EDS",

    /** ISO-8601 timestamp of last data update */
    val lastUpdated: String = "",

    /** Human-readable description / location name of the camera */
    val description: String = ""
)
