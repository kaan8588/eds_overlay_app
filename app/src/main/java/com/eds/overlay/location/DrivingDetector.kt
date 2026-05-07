package com.eds.overlay.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

class DrivingDetector(private val context: Context) {

    interface DrivingDetectorListener {
        fun onDrivingStateChanged(state: DrivingState)
    }

    enum class DrivingState {
        DRIVING, STATIONARY, UNKNOWN
    }

    private var listener: DrivingDetectorListener? = null
    private val client = ActivityRecognition.getClient(context)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ACTIVITY) {
                val result = ActivityRecognitionResult.extractResult(intent)
                result?.let { handleActivityResult(it) }
            }
        }
    }

    companion object {
        private const val TAG = "DrivingDetector"
        private const val ACTION_ACTIVITY = "com.eds.overlay.ACTION_ACTIVITY_DETECTION"
        private const val DETECTION_INTERVAL = 30000L // 30 seconds
        private const val CONFIDENCE_THRESHOLD = 75
    }

    fun startMonitoring(listener: DrivingDetectorListener) {
        this.listener = listener
        
        val filter = IntentFilter(ACTION_ACTIVITY)
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            client.requestActivityUpdates(DETECTION_INTERVAL, getPendingIntent())
                .addOnSuccessListener { Log.d(TAG, "Activity updates requested") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to request activity updates", e) }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Missing activity recognition permission", e)
        }
    }

    fun stopMonitoring() {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered or not registered
        }
        
        client.removeActivityUpdates(getPendingIntent())
        listener = null
    }

    private fun handleActivityResult(result: ActivityRecognitionResult) {
        val mostProbable = result.mostProbableActivity
        Log.d(TAG, "Activity: ${mostProbable.type}, Confidence: ${mostProbable.confidence}")

        if (mostProbable.confidence < CONFIDENCE_THRESHOLD) return

        val state = when (mostProbable.type) {
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE -> DrivingState.DRIVING
            DetectedActivity.STILL,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING -> DrivingState.STATIONARY
            else -> DrivingState.UNKNOWN
        }

        if (state != DrivingState.UNKNOWN) {
            listener?.onDrivingStateChanged(state)
        }
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_ACTIVITY)
        // Set package to make intent explicit
        intent.`package` = context.packageName
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
