package com.eds.overlay

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.eds.overlay.data.EdsRepository
import com.eds.overlay.databinding.ActivityMainBinding
import com.eds.overlay.service.OverlayService
import com.eds.overlay.util.LocaleHelper
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = LocaleHelper.PREFS_NAME
        private const val KEY_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_DATA_IMPORTED = "data_imported"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val QUOTE_DELAY = 60_000L // 60 seconds

        /** Last onboarding page; tapping the button here requests permissions. */
        private const val LAST_ONBOARDING_STEP = 5
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: EdsRepository
    private var isServiceRunning = false
    private var isSoundEnabled = true

    private var quoteJob: Job? = null
    private var onboardingStep = 1
    private var isDarkMode = true

    // Consolidated SharedPreferences access
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // Cached resources to avoid repeated lookups
    private var cachedQuotes: Array<String>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            requestBackgroundLocationOrFinish()
        } else {
            // Check if any denied permission is permanently denied ("Don't ask again")
            val deniedPerms = permissions.filter { !it.value }.keys
            val permanentlyDenied = deniedPerms.any { perm ->
                !shouldShowRequestPermissionRationale(perm)
            }

            binding.tvObTitle.text = getString(R.string.permission_promise_title)
            binding.btnObSkip.visibility = View.GONE

            if (permanentlyDenied) {
                // User tapped "Don't ask again" — system won't show dialog anymore.
                // Redirect to app settings instead.
                binding.tvObMsg.text = getString(R.string.permission_denied_permanent_msg)
                binding.btnObNext.text = getString(R.string.permission_open_settings)
                binding.btnObNext.setOnClickListener { openAppSettings() }
            } else {
                // User just tapped "Deny" — can still re-request
                binding.tvObMsg.text = getString(R.string.permission_denied_msg)
                binding.btnObNext.text = getString(R.string.permission_retry)
                binding.btnObNext.setOnClickListener { requestPermissions() }
            }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or denied, finish onboarding.
        // App works without background location, just less reliably.
        finishOnboarding()
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // User returned from app settings — re-check permissions
        val allGranted = buildRequiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            requestBackgroundLocationOrFinish()
        }
        // If still not granted, user stays on the same screen and can try again
    }

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            toggleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Hand the stored language to AppCompat if it doesn't know it yet.
        // No-op once the two agree, so this can't loop on re-creation.
        LocaleHelper.syncWithStoredLanguage(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.btnLanguage.updatePadding(top = systemBars.top)
            binding.btnTheme.updatePadding(top = systemBars.top)
            binding.layoutMainContent.updatePadding(
                top = systemBars.top,
                bottom = systemBars.bottom,
                left = systemBars.left,
                right = systemBars.right
            )
            insets
        }

        val app = application as EDSApplication
        repository = app.repository

        setupListeners()

        // Logo is pre-posterized at build time — no runtime processing needed.

        // Check service state via the companion flag — avoids deprecated APIs.
        isServiceRunning = OverlayService.isRunning
        // Sync the pref so it stays accurate for next restart
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, isServiceRunning).apply()

        // Sync dark mode state directly with current active configuration
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        isSoundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        applyThemeColors()

        updateUI()
        updateSoundUI()
        loadPointCount()
        startQuoteTimer()
        checkFirstLaunch()
    }

    private fun setupListeners() {
        binding.btnToggle.setOnToggleRequest { toggleService() }
        binding.btnImport.setOnClickListener { importData() }
        binding.btnLanguage.setOnClickListener { switchLanguage() }
        binding.btnTheme.setOnClickListener { toggleTheme() }
        binding.btnSound.setOnClickListener { toggleSound() }
        binding.btnFeedback.setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }
    }


    private fun checkFirstLaunch() {
        if (prefs.getBoolean(KEY_FIRST_LAUNCH, true)) {
            startTransitiveOnboarding()
        } else {
            autoImportOnFirstLaunch()
        }
    }

    private fun startTransitiveOnboarding() {
        binding.layoutOnboarding.visibility = View.VISIBLE

        updateOnboardingUI()

        // Advance until the last page, then request permissions. The step is
        // never incremented past the last page, so repeated taps keep working
        // instead of leaving the button dead.
        binding.btnObNext.setOnClickListener {
            if (onboardingStep < LAST_ONBOARDING_STEP) {
                onboardingStep++
                animateTransition { updateOnboardingUI() }
            } else {
                requestPermissions()
            }
        }
        binding.btnObSkip.setOnClickListener {
            onboardingStep = LAST_ONBOARDING_STEP
            animateTransition { updateOnboardingUI() }
        }
    }

    private fun updateOnboardingUI() = when (onboardingStep) {
        1 -> {
            binding.tvObTitle.text = getString(R.string.onboarding_title)
            binding.tvObMsg.text = getString(R.string.onboarding_msg)
            binding.btnObNext.text = getString(R.string.onboarding_next)
            binding.btnObSkip.visibility = View.VISIBLE
        }
        2 -> {
            binding.tvObTitle.text = getString(R.string.onboarding_hud_title)
            binding.tvObMsg.text = getString(R.string.onboarding_hud_msg)
            binding.btnObNext.text = getString(R.string.onboarding_next)
            binding.btnObSkip.visibility = View.VISIBLE
        }
        3 -> {
            binding.tvObTitle.text = getString(R.string.onboarding_orientation_title)
            binding.tvObMsg.text = getString(R.string.onboarding_orientation_msg)
            binding.btnObNext.text = getString(R.string.onboarding_next)
            binding.btnObSkip.visibility = View.VISIBLE
        }
        4 -> {
            binding.tvObTitle.text = getString(R.string.onboarding_wifi_title)
            binding.tvObMsg.text = getString(R.string.onboarding_wifi_msg)
            binding.btnObNext.text = getString(R.string.onboarding_next)
            binding.btnObSkip.visibility = View.VISIBLE
        }
        5 -> {
            binding.tvObTitle.text = getString(R.string.permission_promise_title)
            binding.tvObMsg.text = getString(R.string.permission_promise_msg)
            binding.btnObNext.text = getString(R.string.permission_grant)
            binding.btnObSkip.visibility = View.GONE
        }
        else -> {}
    }

    private fun animateTransition(action: () -> Unit) {
        action()
    }

    private fun finishOnboarding() {
        binding.layoutOnboarding.visibility = android.view.View.GONE
        binding.layoutMainContent.alpha = 1f
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        autoImportOnFirstLaunch()
    }

    /**
     * Builds the list of runtime permissions required for core functionality.
     * Single source of truth — used by both requestPermissions() and settingsLauncher.
     */
    private fun buildRequiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    private fun requestPermissions() {
        permissionLauncher.launch(buildRequiredPermissions().toTypedArray())
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        settingsLauncher.launch(intent)
    }

    private fun requestBackgroundLocationOrFinish() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Show rationale then request — system handles the Settings redirect on API 30+
            binding.tvObTitle.text = getString(R.string.permission_promise_title)
            binding.tvObMsg.text = getString(R.string.permission_background_msg)
            binding.btnObNext.text = getString(R.string.permission_grant)
            binding.btnObSkip.visibility = View.GONE
            binding.btnObNext.setOnClickListener {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            finishOnboarding()
        }
    }

    private fun startQuoteTimer() {
        quoteJob?.cancel()
        quoteJob = lifecycleScope.launch {
            // Cache the array once instead of re-reading resources every 60s
            val quotes = cachedQuotes ?: resources.getStringArray(R.array.muavin_quotes).also { cachedQuotes = it }
            while (isActive) {
                val randomQuote = quotes.random()
                binding.tvQuote.text = randomQuote
                delay(QUOTE_DELAY)
            }
        }
    }

    private fun toggleService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayLauncher.launch(intent)
            return
        }
        if (isServiceRunning) {
            OverlayService.stop(this)
            setServiceRunning(false)
        } else {
            OverlayService.start(this)
            setServiceRunning(true)
        }
    }

    private fun setServiceRunning(running: Boolean) {
        isServiceRunning = running
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
        updateUI()
    }

    private fun updateUI() {
        if (isServiceRunning) {
            binding.tvServiceStatus.text = getString(R.string.status_active)
            binding.tvServiceStatus.setTextColor(getColor(R.color.accent_green))
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_inactive)
            binding.tvServiceStatus.setTextColor(getColor(R.color.accent_red))
        }
        binding.btnToggle.setRunning(isServiceRunning)
    }

    private fun autoImportOnFirstLaunch() {
        if (!prefs.getBoolean(KEY_DATA_IMPORTED, false)) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    repository.importFromAssets(this@MainActivity)
                }
                prefs.edit().putBoolean(KEY_DATA_IMPORTED, true).apply()
                loadPointCount()
            }
        }
    }

    private fun importData() {
        lifecycleScope.launch {
            binding.btnImport.isEnabled = false
            // Use localized string resource instead of hard-coded strings
            binding.btnImport.text = getString(R.string.importing)

            withContext(Dispatchers.IO) {
                repository.importFromAssets(this@MainActivity)
            }

            loadPointCount()
            binding.btnImport.text = getString(R.string.import_data)
            binding.btnImport.isEnabled = true
        }
    }

    private fun loadPointCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { repository.getCount() }
            binding.tvPointCount.text = getString(R.string.points_count, count)
        }
    }

    /**
     * Toggles between Turkish and English.
     */
    private fun switchLanguage() {
        val newLang = LocaleHelper.oppositeLanguage(LocaleHelper.storedLanguage(this))
        LocaleHelper.setLanguage(this, newLang)

        if (isServiceRunning) {
            OverlayService.stop(this)
            OverlayService.start(this)
        }
    }

    private fun toggleSound() {
        isSoundEnabled = !isSoundEnabled
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, isSoundEnabled).apply()
        updateSoundUI()
    }

    private fun updateSoundUI() {
        binding.btnSound.text = if (isSoundEnabled)
            getString(R.string.sound_on) else getString(R.string.sound_off)
    }

    /**
     * Toggles dark/light mode.
     */
    private fun toggleTheme() {
        isDarkMode = !isDarkMode
        prefs.edit().putBoolean(KEY_DARK_MODE, isDarkMode).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        applyThemeColors()
    }

    private fun applyThemeColors() {
        // System bar icon contrast (bars themselves are transparent via edge-to-edge)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode
        insetsController.isAppearanceLightNavigationBars = !isDarkMode

        // Theme button label — show opposite mode name
        binding.btnTheme.text = if (isDarkMode)
            getString(R.string.theme_light) else getString(R.string.theme_dark)

        // Re-apply service status colors (they depend on theme)
        updateUI()
    }

}