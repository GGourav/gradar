package com.gradar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * G Radar Application class
 * Handles app-wide initialization and notification channels
 */
class GRadarApp : Application() {

    companion object {
        const val TAG = "GRadar"
        
        // Notification channel IDs
        const val CHANNEL_VPN_SERVICE = "vpn_service"
        const val CHANNEL_OVERLAY_SERVICE = "overlay_service"
        
        // Albion Online package name
        const val ALBION_PACKAGE = "com.albiononline"
        const val ALBION_PORT = 5056
        
        // Discovery logger file
        const val UNKNOWN_ENTITIES_LOG = "unknown_entities.log"
        
        @Volatile
        private lateinit var instance: GRadarApp
        
        fun getInstance(): GRadarApp = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // VPN Service Channel
            val vpnChannel = NotificationChannel(
                CHANNEL_VPN_SERVICE,
                getString(R.string.channel_vpn_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_vpn_service_desc)
                setShowBadge(false)
            }
            
            // Overlay Service Channel
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY_SERVICE,
                getString(R.string.channel_overlay_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_overlay_service_desc)
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannels(listOf(vpnChannel, overlayChannel))
        }
    }
}
