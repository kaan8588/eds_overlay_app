package com.eds.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.eds.overlay.data.EdsRepository
import com.eds.overlay.databinding.ActivityMainBinding
import com.eds.overlay.service.OverlayService
import kotlinx.coroutines.*
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "muavin_prefs"
        private const val KEY_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_DATA_IMPORTED = "data_imported"
        private const val KEY_LANG = "app_lang"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val QUOTE_DELAY = 60_000L // 60 seconds
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: EdsRepository
    private var isServiceRunning = false

    private var quoteJob: Job? = null
    private var onboardingStep = 1
    private var isDarkMode = true

    // Consolidated SharedPreferences access
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // Cached resources to avoid repeated lookups
    private var cachedQuotes: Array<String>? = null
    private var scanlineDarkDrawable: android.graphics.drawable.BitmapDrawable? = null
    private var scanlineLightDrawable: android.graphics.drawable.BitmapDrawable? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            requestBackgroundLocationOrFinish()
        } else {
            // Some permissions were denied — show a retry prompt so the user
            // is not stuck behind the dimmed onboarding overlay.
            binding.tvObTitle.text = getString(R.string.permission_promise_title)
            binding.tvObMsg.text = getString(R.string.permission_denied_msg)
            binding.btnObNext.text = getString(R.string.permission_retry)
            binding.btnObNext.setOnClickListener { requestPermissions() }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or denied, finish onboarding.
        // App works without background location, just less reliably.
        finishOnboarding()
    }

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            toggleService()
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

        // Load theme preference and apply
        isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        applyThemeColors()

        updateUI()
        loadPointCount()
        startQuoteTimer()
        checkFirstLaunch()
    }

    private fun setupListeners() {
        binding.btnToggle.setOnClickListener { toggleService() }
        binding.btnImport.setOnClickListener { importData() }
        binding.btnLanguage.setOnClickListener { switchLanguage() }
        binding.btnTheme.setOnClickListener { toggleTheme() }
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
        binding.layoutMainContent.alpha = 0.3f
        binding.layoutOnboarding.visibility = android.view.View.VISIBLE
        binding.layoutOnboarding.alpha = 0f
        binding.layoutOnboarding.translationY = 100f

        binding.layoutOnboarding.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .start()

        updateOnboardingUI()

        binding.btnObNext.setOnClickListener {
            onboardingStep++
            if (onboardingStep <= 3) {
                animateTransition { updateOnboardingUI() }
            } else if (onboardingStep == 4) {
                requestPermissions()
            }
        }
    }

    private fun updateOnboardingUI() = when (onboardingStep) {
        1 -> {
            binding.tvObTitle.text = getString(R.string.onboarding_title)
            binding.tvObMsg.text = getString(R.string.onboarding_msg)
            binding.btnObNext.text = getString(R.string.onboarding_next)
        }
        2 -> {
            binding.tvObTitle.text = getString(R.string.onboarding_orientation_title)
            binding.tvObMsg.text = getString(R.string.onboarding_orientation_msg)
            binding.btnObNext.text = getString(R.string.onboarding_next)
        }
        3 -> {
            binding.tvObTitle.text = getString(R.string.permission_promise_title)
            binding.tvObMsg.text = getString(R.string.permission_promise_msg)
            binding.btnObNext.text = getString(R.string.permission_grant)
        }
        else -> {}
    }

    private fun animateTransition(action: () -> Unit) {
        binding.tvObTitle.animate().alpha(0f).translationX(-50f).setDuration(300).start()
        binding.tvObMsg.animate().alpha(0f).translationX(-50f).setDuration(350).withEndAction {
            action()
            binding.tvObTitle.translationX = 50f
            binding.tvObMsg.translationX = 50f
            binding.tvObTitle.animate().alpha(1f).translationX(0f).setDuration(300).start()
            binding.tvObMsg.animate().alpha(1f).translationX(0f).setDuration(350).start()
        }.start()
    }

    private fun finishOnboarding() {
        binding.layoutOnboarding.animate()
            .alpha(0f)
            .translationY(-100f)
            .setDuration(600)
            .withEndAction {
                binding.layoutOnboarding.visibility = android.view.View.GONE
                binding.layoutMainContent.animate().alpha(1f).setDuration(500).start()
                prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
                autoImportOnFirstLaunch()
            }
            .start()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)

        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBackgroundLocationOrFinish() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Show rationale then request — system handles the Settings redirect on API 30+
            binding.tvObTitle.text = getString(R.string.permission_promise_title)
            binding.tvObMsg.text = getString(R.string.permission_background_msg)
            binding.btnObNext.text = getString(R.string.permission_grant)
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
                binding.tvQuote.animate().alpha(0f).setDuration(500).withEndAction {
                    binding.tvQuote.text = randomQuote
                    binding.tvQuote.animate().alpha(1f).setDuration(500).start()
                }.start()
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
        val btnBg = if (isServiceRunning) {
            if (isDarkMode) R.drawable.bg_button_red_glass_dark else R.drawable.bg_button_red_glass
        } else {
            if (isDarkMode) R.drawable.bg_button_green_glass_dark else R.drawable.bg_button_green_glass
        }
        
        if (isServiceRunning) {
            binding.tvServiceStatus.text = getString(R.string.status_active)
            binding.tvServiceStatus.setTextColor(
                if (isDarkMode) 0xFF4CAF50.toInt() else 0xFF388E3C.toInt()
            )
            binding.btnToggle.text = getString(R.string.stop_service)
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_inactive)
            binding.tvServiceStatus.setTextColor(0xFFFF5252.toInt())
            binding.btnToggle.text = getString(R.string.start_service)
        }
        binding.btnToggle.setBackgroundResource(btnBg)
        binding.btnToggle.backgroundTintList = null
        binding.btnToggle.setTextColor(0xFFFFFFFF.toInt())
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

    private fun switchLanguage() {
        val currentLang = prefs.getString(KEY_LANG, "tr")
        val newLang = if (currentLang == "tr") "en" else "tr"
        prefs.edit().putString(KEY_LANG, newLang).apply()

        if (isServiceRunning) {
            OverlayService.stop(this)
            OverlayService.start(this)
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun toggleTheme() {
        isDarkMode = !isDarkMode
        prefs.edit().putBoolean(KEY_DARK_MODE, isDarkMode).apply()

        // Smooth crossfade transition
        binding.mainRoot.animate().alpha(0f).setDuration(200).withEndAction {
            applyThemeColors()
            binding.mainRoot.animate().alpha(1f).setDuration(300).start()
        }.start()
    }



    private fun applyThemeColors() {
        // ── Color palette ───────────────────────────────────────────
        val primaryTextColor: Int
        val secondaryTextColor: Int
        val dimTextColor: Int
        val fadedTextColor: Int
        val ultraFadedTextColor: Int
        val statusBarColor: Int
        val buttonTextColor: Int
        val onboardingBgColor: Int
        val glowAlpha: Float

        if (isDarkMode) {
            primaryTextColor = 0xFFFFFFFF.toInt()
            secondaryTextColor = 0xB0FFFFFF.toInt()
            dimTextColor = 0x80FFFFFF.toInt()
            fadedTextColor = 0x4DFFFFFF.toInt()
            ultraFadedTextColor = 0x20FFFFFF.toInt()
            statusBarColor = 0xFF161616.toInt()
            buttonTextColor = 0x4DFFFFFF.toInt()
            onboardingBgColor = 0xF2121212.toInt()
            glowAlpha = 0.8f
        } else {
            primaryTextColor = 0xFF1A1A1A.toInt()
            secondaryTextColor = 0xB01A1A1A.toInt()
            dimTextColor = 0x801A1A1A.toInt()
            fadedTextColor = 0x4D1A1A1A.toInt()
            ultraFadedTextColor = 0x201A1A1A.toInt()
            statusBarColor = 0xFFD0D0D0.toInt()
            buttonTextColor = 0x4D1A1A1A.toInt()
            onboardingBgColor = 0xF2D0D0D0.toInt()
            glowAlpha = 0.5f
        }

        // ── Apply to views ──────────────────────────────────────────
        binding.mainRoot.setBackgroundResource(if (isDarkMode) R.drawable.bg_main_dark else R.drawable.bg_main_light)

        // Floating glow orbs — sync palette with theme
        binding.glowParticles.setDarkMode(isDarkMode)

        // Status bar & nav bar
        window.statusBarColor = statusBarColor
        window.navigationBarColor = statusBarColor
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode
        insetsController.isAppearanceLightNavigationBars = !isDarkMode

        // Quote text
        binding.tvQuote.setTextColor(fadedTextColor)

        // Top buttons
        binding.btnTheme.setTextColor(buttonTextColor)
        binding.btnLanguage.setTextColor(buttonTextColor)

        // Theme button label — show opposite mode name
        binding.btnTheme.text = if (isDarkMode)
            getString(R.string.theme_light) else getString(R.string.theme_dark)

        // Logo glow intensity
        binding.ivLogoGlow.alpha = glowAlpha

        // Logo filter is set once in onCreate — no per-theme change needed

        // Info text & import button
        binding.tvInfoText.setTextColor(fadedTextColor)
        binding.btnImport.setTextColor(ultraFadedTextColor)
        binding.tvPointCount.setTextColor(if (isDarkMode) 0x15FFFFFF.toInt() else 0x151A1A1A.toInt())

        // Feedback button — matches info text style
        binding.btnFeedback.setTextColor(fadedTextColor)

        // Status card label
        binding.tvStatusLabel.setTextColor(dimTextColor)

        // Onboarding colors
        binding.layoutOnboarding.setBackgroundColor(onboardingBgColor)
        binding.tvObTitle.setTextColor(primaryTextColor)
        binding.tvObMsg.setTextColor(secondaryTextColor)
        if (!isDarkMode) {
            binding.btnObNext.setTextColor(0xFFFFFFFF.toInt())
            binding.btnObNext.backgroundTintList = ColorStateList.valueOf(0xFF2E7D32.toInt())
        } else {
            binding.btnObNext.setTextColor(0xFF121212.toInt())
            binding.btnObNext.backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }

        // Scanline effect — cached to avoid bitmap leak on repeated theme toggles
        val scanline = if (isDarkMode) {
            scanlineDarkDrawable ?: buildScanlineDrawable(0x17000000).also { scanlineDarkDrawable = it }
        } else {
            scanlineLightDrawable ?: buildScanlineDrawable(0x11000000).also { scanlineLightDrawable = it }
        }
        binding.scanlineOverlay.background = scanline
        binding.mainRoot.foreground = null

        // Re-apply service status colors (they depend on theme)
        updateUI()
    }

    private fun buildScanlineDrawable(lineColor: Int): android.graphics.drawable.BitmapDrawable {
        val bmp = android.graphics.Bitmap.createBitmap(1, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, 0x00000000)
        bmp.setPixel(0, 1, 0x00000000)
        bmp.setPixel(0, 2, lineColor)
        bmp.setPixel(0, 3, lineColor)
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }

}