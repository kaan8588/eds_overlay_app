package com.eds.overlay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.eds.overlay.EDSApplication
import com.eds.overlay.MainActivity
import com.eds.overlay.R
import com.eds.overlay.algorithm.SpatialEngine
import com.eds.overlay.algorithm.Threat
import com.eds.overlay.data.EdsRepository
import com.eds.overlay.location.DrivingDetector
import com.eds.overlay.location.LocationEngine
import com.eds.overlay.ui.OverlayView
import com.eds.overlay.util.LocaleHelper
import kotlinx.coroutines.*

class OverlayService : Service(), LocationEngine.LocationListener,
    DrivingDetector.DrivingDetectorListener {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        // Query radius is 1.5× the alert radius to leave slack at the bounding-box boundary
        private const val ALERT_RADIUS_M = 1200.0
        private const val QUERY_RADIUS_KM = (ALERT_RADIUS_M * 1.5) / 1000.0   // 1.8 km
        private const val PREFS_NAME = LocaleHelper.PREFS_NAME
        private const val KEY_LAST_ALERT_TIME = "last_alert_time"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        
        // Speed noise threshold in km/h. Speeds below this are treated as zero.
        private const val SPEED_NOISE_THRESHOLD_KMH = 5.0
        // Maximum acceptable GPS accuracy in meters. Fixes worse than this are
        // considered unreliable and their speed is treated as zero.
        private const val MAX_ACCURACY_M = 50f

        /** Accessible flag for UI to check service liveness without deprecated APIs. */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var repository: EdsRepository
    private lateinit var locationEngine: LocationEngine
    private lateinit var drivingDetector: DrivingDetector
    private lateinit var windowManager: WindowManager

    private var overlayView: OverlayView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isDriving = true

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Cooldown is persisted to SharedPreferences so an OS-restarted sticky
    // service doesn't immediately re-alert the user after a brief interruption.
    private var lastAlertTime: Long = 0L
    private val alertCooldownMs = 10_000L
    // Tracks which radar point was last alerted so DİKKAT fires only once per radar
    private var lastAlertedPointId: Long = -1L
    // Last spoken severity for that point; WARNING→DANGER must speak again
    private var lastAlertedLevel: Threat.Level? = null

    private var computationJob: Job? = null

    // Apply locale override for the service context — AppCompat's per-app
    // locale API only covers activities on Android 12 and below.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as EDSApplication
        repository = app.repository
        locationEngine = LocationEngine(this)
        locationEngine.setListener(this)
        drivingDetector = DrivingDetector(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Restore persisted cooldown timestamp so the service survives OS restarts
        lastAlertTime = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_LAST_ALERT_TIME, 0L)

        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
        locationEngine.startTracking()
        drivingDetector.startMonitoring(this)
        isRunning = true
        // Bootstrap: run an immediate scan with the last known location
        // so the overlay doesn't stay empty while waiting for the first GPS fix
        bootstrapWithLastLocation()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        locationEngine.stopTracking()
        drivingDetector.stopMonitoring()
        removeOverlay()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationUpdate(location: Location) {
        if (!isDriving) return
        computationJob?.cancel()
        computationJob = serviceScope.launch {
            try {
                val lat = location.latitude
                val lng = location.longitude
                // Location.bearing defaults to 0.0 (due north) when the fix
                // carries no bearing — pass a negative sentinel instead so the
                // engine skips directional filtering rather than filtering
                // against a bogus northward heading.
                val bearing = if (location.hasBearing()) location.bearing.toDouble() else -1.0
                
                // ── GPS speed filtering pipeline ──────────────────────────
                // 1. Guard: only use speed if the fix actually carries one
                val rawSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
                // 2. Reject fixes with poor accuracy (likely indoor / cold-start)
                val accuracyOk = !location.hasAccuracy() || location.accuracy < MAX_ACCURACY_M
                // 3. Noise gate: treat very low speeds as zero
                //    (spike filter removed — FusedLocationProvider already
                //    applies Kalman filtering; our old spike filter caused
                //    speed to stick at 0 after brief GPS dropouts)
                val speedKmh = if (!accuracyOk || rawSpeedKmh < SPEED_NOISE_THRESHOLD_KMH)
                    0.0 else rawSpeedKmh

                val (currentSpeed, threats) = withContext(Dispatchers.Default) {
                    val candidates = repository.getNearbyPoints(lat, lng, QUERY_RADIUS_KM)
                    val detectedThreats = SpatialEngine.findThreats(
                        userLat = lat, userLng = lng, userBearing = bearing,
                        userSpeedKmh = speedKmh, candidates = candidates,
                        radiusM = ALERT_RADIUS_M
                    )
                    speedKmh to detectedThreats
                }

                // UI operations must run on the Main dispatcher — guard explicitly
                // so this remains safe even if serviceScope's dispatcher changes.
                withContext(Dispatchers.Main) {
                    updateOverlay(currentSpeed, threats)
                    checkTtsAlert(threats.firstOrNull())
                }
            } catch (e: CancellationException) {
                // Intentionally suppressed — job cancelled by next location update
            } catch (e: Exception) {
                Log.e(TAG, "Error processing location update", e)
            }
        }
    }

    override fun onDrivingStateChanged(state: DrivingDetector.DrivingState) {
        isDriving = state == DrivingDetector.DrivingState.DRIVING
        if (isDriving) {
            locationEngine.startTracking()
            overlayView?.show()
        } else {
            locationEngine.stopTracking()
            overlayView?.hide()
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_widget, null)
        overlayView = OverlayView(view, params, windowManager)
        windowManager.addView(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it.rootView) } catch (_: Exception) { }
        }
        overlayView = null
    }

    /**
     * Uses the last known GPS location to immediately populate the overlay
     * with nearby radar data, avoiding the blank-overlay delay while
     * waiting for the first fresh GPS fix (which can take 5-30s on cold start).
     */
    private fun bootstrapWithLastLocation() {
        locationEngine.getLastLocation { location ->
            if (location != null) {
                // Feed it through the normal pipeline
                onLocationUpdate(location)
            }
        }
    }

    private fun updateOverlay(speedKmh: Double, threats: List<Threat>) {
        overlayView?.update(speedKmh, threats.firstOrNull())
    }

    private fun checkTtsAlert(nearest: Threat?) {
        if (nearest == null) {
            // No threat → reset per-point tracking so re-entry triggers a fresh alert
            lastAlertedPointId = -1L
            lastAlertedLevel = null
            return
        }
        // Alert on WARNING (approaching) and DANGER (speeding)
        if (nearest.level == Threat.Level.SAFE) return
        // Only speak within 500m — farther cameras stay visual-only on the HUD
        if (nearest.distanceM >= 500) return

        // Sound toggle: respect user preference
        val soundEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SOUND_ENABLED, true)
        if (!soundEnabled) return

        val isSamePoint = nearest.point.id == lastAlertedPointId
        val isEscalation = isSamePoint &&
            lastAlertedLevel != Threat.Level.DANGER &&
            nearest.level == Threat.Level.DANGER
        // One-shot per radar unless severity rose from WARNING to DANGER
        if (isSamePoint && !isEscalation) return

        val now = System.currentTimeMillis()
        // Escalation skips the 10s cooldown so a speed-limit breach after
        // an approach warning is still spoken.
        if (!isEscalation && now - lastAlertTime <= alertCooldownMs) return

        lastAlertTime = now
        lastAlertedPointId = nearest.point.id
        lastAlertedLevel = nearest.level
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putLong(KEY_LAST_ALERT_TIME, now).apply()

        val msg = if (nearest.level == Threat.Level.DANGER) {
            getString(R.string.tts_alert_danger, nearest.point.speedLimit)
        } else {
            buildWarningMessage(nearest)
        }
        speak(msg)
    }

    /**
     * Builds a TTS warning message that includes the radar description
     * when available, so the driver knows which camera is ahead.
     */
    private fun buildWarningMessage(threat: Threat): String {
        val desc = threat.point.description.trim()
        val distText = if (threat.distanceM < 1000) {
            "${threat.distanceM.toInt()} metre"
        } else {
            "${"%.1f".format(threat.distanceM / 1000)} kilometre"
        }
        return if (desc.isNotEmpty()) {
            getString(R.string.tts_alert_warning_desc, distText, threat.point.speedLimit, desc)
        } else {
            getString(R.string.tts_alert_warning, distText, threat.point.speedLimit)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = resources.configuration.locales[0]
                ttsReady = true
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eds_alert")
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, EDSApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_muavin_mono)
            .setContentIntent(tapIntent)
            .setOngoing(true).build()
    }
}
