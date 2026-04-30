package com.eds.overlay.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

/**
 * Wraps [FusedLocationProviderClient] with high-accuracy 1-second polling.
 *
 * The engine exposes a simple callback interface so the overlay service can
 * react to each new location fix without coupling to the GMS API directly.
 */
class LocationEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocationEngine"
        private const val INTERVAL_MS = 1_000L          // 1-second updates
        private const val FASTEST_INTERVAL_MS = 500L     // minimum interval
        private const val SMALLEST_DISPLACEMENT_M = 2f   // ignore micro-jitter
    }

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    private var listener: LocationListener? = null
    private var isTracking = false
    // Background thread so location callbacks don't burden the main looper
    private var handlerThread: HandlerThread? = null

    /** Callback interface for location updates */
    interface LocationListener {
        fun onLocationUpdate(location: Location)
    }

    fun setListener(listener: LocationListener) {
        this.listener = listener
    }

    /**
     * Starts high-accuracy location tracking.
     * Requires [Manifest.permission.ACCESS_FINE_LOCATION].
     */
    fun startTracking() {
        if (isTracking) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Missing ACCESS_FINE_LOCATION permission")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT_M)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    listener?.onLocationUpdate(location)
                }
            }
        }

        val thread = HandlerThread("LocationEngineThread").also {
            it.start()
            handlerThread = it
        }

        client.requestLocationUpdates(request, locationCallback!!, thread.looper)
        isTracking = true
        Log.i(TAG, "Location tracking started (1s high-accuracy)")
    }

    /** Stops location tracking and releases the callback. */
    fun stopTracking() {
        if (!isTracking) return
        locationCallback?.let { client.removeLocationUpdates(it) }
        locationCallback = null
        handlerThread?.quitSafely()
        handlerThread = null
        isTracking = false
        Log.i(TAG, "Location tracking stopped")
    }

    /** Returns the last known location synchronously (may be null). */
    fun getLastLocation(callback: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null)
            return
        }
        client.lastLocation.addOnSuccessListener { callback(it) }
    }

    fun isActive(): Boolean = isTracking
}
