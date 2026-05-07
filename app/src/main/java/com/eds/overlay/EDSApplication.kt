package com.eds.overlay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.eds.overlay.data.EdsDatabase
import com.eds.overlay.data.EdsRepository
import java.util.Locale

class EDSApplication : Application() {

    val database: EdsDatabase by lazy {
        EdsDatabase.getInstance(this)
    }

    val repository: EdsRepository by lazy {
        EdsRepository(database.edsDao())
    }

    override fun attachBaseContext(base: Context) {
        val prefs = base.getSharedPreferences("muavin_prefs", Context.MODE_PRIVATE)
        
        // Eğer kullanıcı henüz dil seçmediyse, sistem dilini kontrol et ve kaydet
        if (!prefs.contains("app_lang")) {
            val systemLang = Locale.getDefault().language
            val initialLang = if (systemLang == "tr") "tr" else "en"
            prefs.edit().putString("app_lang", initialLang).apply()
        }
        
        val lang = prefs.getString("app_lang", "tr") ?: "tr"
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    // Locale is re-applied via attachBaseContext on Activity/Service creation.

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EDS Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for EDS speed camera overlay"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "EDS Speed Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Speed camera proximity alerts"
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "eds_overlay_service"
        const val ALERT_CHANNEL_ID = "eds_alerts"
    }
}
