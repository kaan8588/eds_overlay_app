package com.eds.overlay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import kotlinx.coroutines.*
import java.util.Locale

class OverlayService : Service(), LocationEngine.LocationListener,
    DrivingDetector.DrivingDetectorListener {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        // Query radius is 1.5× the alert radius to leave slack at the bounding-box boundary
        private const val ALERT_RADIUS_M = 1000.0
        private const val QUERY_RADIUS_KM = (ALERT_RADIUS_M * 1.5) / 1000.0   // 1.5 km
        private const val ANGLE_TOLERANCE = 25.0
        private const val PREFS_NAME = "muavin_prefs"
        private const val KEY_LAST_ALERT_TIME = "last_alert_time"
        
        // Speed noise threshold in km/h. Speeds below this are treated as zero.
        private const val SPEED_NOISE_THRESHOLD_KMH = 5.0
        // Maximum acceptable GPS accuracy in meters. Fixes worse than this are
        // considered unreliable and their speed is treated as zero.
        private const val MAX_ACCURACY_M = 50f
        // Maximum credible speed jump between consecutive fixes (km/h).
        // Anything larger is treated as a GPS glitch (e.g. 0 → 150 while parked).
        private const val MAX_SPEED_JUMP_KMH = 40.0

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
    // Tracks the last accepted speed so we can detect impossible jumps
    private var lastSpeedKmh: Double = 0.0
    private var ttsReady = false
    private var isDriving = true

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // Cooldown is persisted to SharedPreferences so an OS-restarted sticky
    // service doesn't immediately re-alert the user after a brief interruption.
    private var lastAlertTime: Long = 0L
    private val alertCooldownMs = 10_000L

    private var computationJob: Job? = null

    // Apply locale override for the service context
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lang = prefs.getString("app_lang", "tr") ?: "tr"
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
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

        // Reset speed state so stale values from a previous session
        // don't cause the spike filter to reject the first real reading
        lastSpeedKmh = 0.0

        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
        locationEngine.startTracking()
        drivingDetector.startMonitoring(this)
        isRunning = true
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
                val bearing = location.bearing.toDouble()
                
                // ── GPS speed filtering pipeline ──────────────────────────
                // 1. Guard: only use speed if the fix actually carries one
                val rawSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
                // 2. Reject fixes with poor accuracy (likely indoor / cold-start)
                val accuracyOk = !location.hasAccuracy() || location.accuracy < MAX_ACCURACY_M
                // 3. Spike filter: reject impossible jumps (e.g. 0→150 while parked)
                val spikeFiltered = if (rawSpeedKmh > lastSpeedKmh + MAX_SPEED_JUMP_KMH)
                    lastSpeedKmh else rawSpeedKmh
                // 4. Noise gate: treat very low speeds as zero
                val speedKmh = if (!accuracyOk || spikeFiltered < SPEED_NOISE_THRESHOLD_KMH)
                    0.0 else spikeFiltered
                lastSpeedKmh = speedKmh

                val (currentSpeed, threats) = withContext(Dispatchers.Default) {
                    val candidates = repository.getNearbyPoints(lat, lng, QUERY_RADIUS_KM)
                    val detectedThreats = SpatialEngine.findThreats(
                        userLat = lat, userLng = lng, userBearing = bearing,
                        userSpeedKmh = speedKmh, candidates = candidates,
                        radiusM = ALERT_RADIUS_M, angleTolerance = ANGLE_TOLERANCE
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

    private fun updateOverlay(speedKmh: Double, threats: List<Threat>) {
        overlayView?.update(speedKmh, threats.firstOrNull())
    }

    private fun checkTtsAlert(nearest: Threat?) {
        if (nearest != null && nearest.level == Threat.Level.DANGER) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > alertCooldownMs) {
                lastAlertTime = now
                // Persist so a sticky-service restart doesn't re-alert immediately
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_ALERT_TIME, now).apply()
                speak(getString(R.string.tts_alert_danger, nearest.point.speedLimit))
            }
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
