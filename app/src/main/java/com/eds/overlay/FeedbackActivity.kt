package com.eds.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import com.eds.overlay.databinding.ActivityFeedbackBinding
import com.google.android.gms.location.LocationServices
import java.util.*

/**
 * Feedback screen that lets users report radar data issues.
 *
 * Privacy design:
 * - No INTERNET permission — emails are sent via the user's own email app
 *   using [Intent.ACTION_SENDTO]. The user sees and approves the full content
 *   before anything leaves the device.
 * - Location is only accessed when the user explicitly enables the toggle,
 *   and only the last-known fix is read (no continuous tracking).
 * - If location permission hasn't been granted yet, the system dialog is
 *   shown only when the toggle is turned ON.
 */
class FeedbackActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "muavin_prefs"
        private const val KEY_LANG = "app_lang"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val FEEDBACK_EMAIL = "tufanisli8@gmail.com"
    }

    private lateinit var binding: ActivityFeedbackBinding
    private var isDarkMode = true

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // Permission launcher for the location toggle — only triggered when the
    // user explicitly turns the switch ON.
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // User denied — turn the switch back off
            binding.switchSendLocation.isChecked = false
            Toast.makeText(this, getString(R.string.feedback_location_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lang = prefs.getString(KEY_LANG, "tr") ?: "tr"
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets so content doesn't hide behind status/nav bar.
        // Also respond to IME (keyboard) insets so the ScrollView shrinks and
        // the EditText + Send button stay visible on all screen sizes.
        ViewCompat.setOnApplyWindowInsetsListener(binding.feedbackRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            binding.layoutFeedbackContent.updatePadding(
                top = systemBars.top,
                left = systemBars.left,
                right = systemBars.right
            )

            // When keyboard is open, use the larger of nav-bar or IME bottom
            val bottomPad = maxOf(systemBars.bottom, ime.bottom)
            binding.scrollViewFeedback.updatePadding(bottom = bottomPad)

            insets
        }

        isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        applyThemeColors()
        setupListeners()

        // Auto-scroll to EditText when it receives focus (keyboard opens)
        binding.etMessage.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.post {
                    binding.scrollViewFeedback.smoothScrollTo(0, view.top - 100)
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnGoBack.setOnClickListener { finish() }

        // Only request location permission when the user actively turns
        // the toggle ON — never proactively.
        binding.switchSendLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasLocationPermission()) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        binding.btnSendFeedback.setOnClickListener { prepareFeedback() }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * If the location toggle is ON and permission is granted, fetch the
     * last known location and include it in the email. Otherwise send
     * immediately without location data.
     */
    private fun prepareFeedback() {
        if (binding.switchSendLocation.isChecked && hasLocationPermission()) {
            val client = LocationServices.getFusedLocationProviderClient(this)
            try {
                client.lastLocation.addOnSuccessListener { location ->
                    val locString = if (location != null) {
                        "%.6f, %.6f (±%.0f m)".format(
                            location.latitude, location.longitude, location.accuracy
                        )
                    } else {
                        getString(R.string.feedback_location_unavailable)
                    }
                    sendFeedbackEmail(locString)
                }.addOnFailureListener {
                    sendFeedbackEmail(getString(R.string.feedback_location_unavailable))
                }
            } catch (e: SecurityException) {
                sendFeedbackEmail(null)
            }
        } else {
            sendFeedbackEmail(null)
        }
    }

    private fun sendFeedbackEmail(locationString: String?) {
        val issues = mutableListOf<String>()

        if (binding.chipWrongRadar.isChecked) issues.add(getString(R.string.feedback_wrong_radar))
        if (binding.chipWrongLocation.isChecked) issues.add(getString(R.string.feedback_wrong_location))
        if (binding.chipWrongSpeed.isChecked) issues.add(getString(R.string.feedback_wrong_speed))
        if (binding.chipMissingRadar.isChecked) issues.add(getString(R.string.feedback_missing_radar))
        if (binding.chipWrongLimit.isChecked) issues.add(getString(R.string.feedback_wrong_limit))
        if (binding.chipAppCrash.isChecked) issues.add(getString(R.string.feedback_app_crash))

        val message = binding.etMessage.text.toString().trim()

        // Build email body
        val body = buildString {
            appendLine("── Muavin Geri Bildirim ──")
            appendLine()
            if (issues.isNotEmpty()) {
                appendLine("Seçilen Sorunlar:")
                issues.forEach { appendLine("  • $it") }
                appendLine()
            }
            if (message.isNotEmpty()) {
                appendLine("Kullanıcı Mesajı:")
                appendLine(message)
                appendLine()
            }
            if (locationString != null) {
                appendLine("Konum: $locationString")
            } else {
                appendLine("Konum: Paylaşılmadı")
            }
            appendLine()
            appendLine("── Cihaz Bilgisi ──")
            appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Uygulama Sürümü: ${packageManager.getPackageInfo(packageName, 0).versionName}")
        }

        val subject = if (issues.isNotEmpty()) {
            "Muavin Geri Bildirim: ${issues.first()}"
        } else {
            "Muavin Geri Bildirim"
        }

        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            startActivity(Intent.createChooser(emailIntent, getString(R.string.feedback_choose_app)))
            showSuccess()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.feedback_no_email_app), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSuccess() {
        binding.btnSendFeedback.animate().alpha(0f).setDuration(300).start()
        binding.layoutSuccess.visibility = View.VISIBLE
        binding.layoutSuccess.alpha = 0f
        binding.layoutSuccess.translationY = 20f
        binding.layoutSuccess.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .start()
    }

    private fun applyThemeColors() {
        val primaryTextColor: Int
        val secondaryTextColor: Int
        val dimTextColor: Int
        val fadedTextColor: Int
        val ultraFadedTextColor: Int
        val statusBarColor: Int

        if (isDarkMode) {
            primaryTextColor = 0xFFFFFFFF.toInt()
            secondaryTextColor = 0xB0FFFFFF.toInt()
            dimTextColor = 0x80FFFFFF.toInt()
            fadedTextColor = 0x60FFFFFF.toInt()
            ultraFadedTextColor = 0x30FFFFFF.toInt()
            statusBarColor = 0xFF161616.toInt()
        } else {
            primaryTextColor = 0xFF1A1A1A.toInt()
            secondaryTextColor = 0xB01A1A1A.toInt()
            dimTextColor = 0x801A1A1A.toInt()
            fadedTextColor = 0x601A1A1A.toInt()
            ultraFadedTextColor = 0x301A1A1A.toInt()
            statusBarColor = 0xFFD0D0D0.toInt()
        }

        binding.feedbackRoot.setBackgroundResource(
            if (isDarkMode) R.drawable.bg_main_dark else R.drawable.bg_main_light
        )

        binding.glowParticlesFeedback.setDarkMode(isDarkMode)

        window.statusBarColor = statusBarColor
        window.navigationBarColor = statusBarColor
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode
        insetsController.isAppearanceLightNavigationBars = !isDarkMode

        binding.tvFeedbackTitle.setTextColor(primaryTextColor)
        binding.tvFeedbackSubtitle.setTextColor(fadedTextColor)
        binding.btnBack.setTextColor(dimTextColor)
        binding.tvLocationToggleLabel.setTextColor(secondaryTextColor)
        binding.tvLocationToggleDesc.setTextColor(fadedTextColor)
        binding.tvQuickIssuesLabel.setTextColor(fadedTextColor)
        binding.tvMessageLabel.setTextColor(fadedTextColor)
        binding.tvPrivacyNotice.setTextColor(ultraFadedTextColor)

        binding.etMessage.setTextColor(if (isDarkMode) 0xDDFFFFFF.toInt() else 0xDD1A1A1A.toInt())
        binding.etMessage.setHintTextColor(if (isDarkMode) 0x35FFFFFF.toInt() else 0x351A1A1A.toInt())

        // Scanline effect
        val scanline = buildScanlineDrawable(if (isDarkMode) 0x1D000000 else 0x15000000)
        binding.scanlineOverlayFeedback.background = scanline
    }

    private fun buildScanlineDrawable(lineColor: Int): android.graphics.drawable.BitmapDrawable {
        val bmp = android.graphics.Bitmap.createBitmap(1, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, 0x00000000)
        bmp.setPixel(0, 1, 0x00000000)
        bmp.setPixel(0, 2, lineColor)
        bmp.setPixel(0, 3, lineColor)
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            setTileModeXY(
                android.graphics.Shader.TileMode.REPEAT,
                android.graphics.Shader.TileMode.REPEAT
            )
        }
    }
}
