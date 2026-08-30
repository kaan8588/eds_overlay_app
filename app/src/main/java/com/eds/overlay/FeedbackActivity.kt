package com.eds.overlay

import android.Manifest
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
import com.eds.overlay.ui.QuickIssuesSheet
import com.eds.overlay.util.LocaleHelper
import com.google.android.gms.location.LocationServices

/**
 * Feedback screen that lets users report radar data issues and app feedback.
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
        private const val PREFS_NAME = LocaleHelper.PREFS_NAME
        private const val KEY_DARK_MODE = "dark_mode"
        private const val FEEDBACK_EMAIL = "tufanisli8@gmail.com"
    }

    private lateinit var binding: ActivityFeedbackBinding
    private var isDarkMode = true

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val selectedIssueIndices = mutableSetOf<Int>()

    private val quickIssueStrings by lazy {
        listOf(
            getString(R.string.feedback_wrong_radar),
            getString(R.string.feedback_wrong_location),
            getString(R.string.feedback_wrong_speed),
            getString(R.string.feedback_missing_radar),
            getString(R.string.feedback_wrong_limit),
            getString(R.string.feedback_app_crash)
        )
    }

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

        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        applyThemeColors()
        setupListeners()
        updateQuickIssuesSummary()

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

        // Open BottomSheet dialog for selecting issue types
        binding.layoutQuickIssuesTrigger.setOnClickListener {
            QuickIssuesSheet.newInstance(quickIssueStrings, selectedIssueIndices) { confirmed ->
                selectedIssueIndices.clear()
                selectedIssueIndices.addAll(confirmed)
                updateQuickIssuesSummary()
            }.show(supportFragmentManager, QuickIssuesSheet.TAG)
        }

        // Only request location permission when the user actively turns
        // the toggle ON — never proactively.
        binding.switchSendLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasLocationPermission()) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        binding.btnSendFeedback.setOnClickListener { prepareFeedback() }
    }

    private fun updateQuickIssuesSummary() {
        binding.tvQuickIssuesSummary.text = if (selectedIssueIndices.isEmpty()) {
            getString(R.string.feedback_no_issue_selected)
        } else {
            getString(R.string.feedback_issues_selected, selectedIssueIndices.size)
        }
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
        // Guard against double taps while the location lookup is in flight —
        // re-enabled by sendFeedbackEmail() if no email app handles the intent.
        binding.btnSendFeedback.isEnabled = false

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
        val issues = selectedIssueIndices.map { quickIssueStrings[it] }
        val message = binding.etMessage.text.toString().trim()

        // Build email body — uses string resources so it respects the active locale
        val body = buildString {
            appendLine(getString(R.string.feedback_email_header))
            appendLine()
            if (issues.isNotEmpty()) {
                appendLine(getString(R.string.feedback_email_issues))
                issues.forEach { appendLine("  • $it") }
                appendLine()
            }
            if (message.isNotEmpty()) {
                appendLine(getString(R.string.feedback_email_user_msg))
                appendLine(message)
                appendLine()
            }
            if (locationString != null) {
                appendLine(getString(R.string.feedback_email_location, locationString))
            } else {
                appendLine(getString(R.string.feedback_email_no_location))
            }
            appendLine()
            appendLine(getString(R.string.feedback_email_device_header))
            appendLine(getString(R.string.feedback_email_model,
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
            appendLine(getString(R.string.feedback_email_android,
                android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT))
            appendLine(getString(R.string.feedback_email_app_version,
                packageManager.getPackageInfo(packageName, 0).versionName))
        }

        val subject = if (issues.isNotEmpty()) {
            getString(R.string.feedback_email_subject, issues.first())
        } else {
            getString(R.string.feedback_email_subject_default)
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
            binding.btnSendFeedback.isEnabled = true
            Toast.makeText(this, getString(R.string.feedback_no_email_app), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSuccess() {
        binding.btnSendFeedback.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE
    }

    private fun applyThemeColors() {
        // System bar icon contrast (bars themselves are transparent via edge-to-edge)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode
        insetsController.isAppearanceLightNavigationBars = !isDarkMode
    }
}
