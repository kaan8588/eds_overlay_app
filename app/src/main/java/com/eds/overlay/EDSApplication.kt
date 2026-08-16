package com.eds.overlay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.eds.overlay.data.EdsDatabase
import com.eds.overlay.data.EdsRepository
import com.eds.overlay.util.LocaleHelper

class EDSApplication : Application() {

    val database: EdsDatabase by lazy {
        EdsDatabase.getInstance(this)
    }

    val repository: EdsRepository by lazy {
        EdsRepository(database.edsDao())
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

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
