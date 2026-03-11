package com.gradar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GRadarApp : Application() {

    companion object {
        const val TAG = "GRadar"
        const val CHANNEL_VPN_SERVICE = "vpn_service"
        const val CHANNEL_OVERLAY_SERVICE = "overlay_service"
        const val ALBION_PACKAGE = "com.albiononline"
        const val ALBION_PORT = 5056
        const val UNKNOWN_ENTITIES_LOG = "unknown_entities.log"
        
        @Volatile
        private var instance: GRadarApp? = null
        
        fun getInstance(): GRadarApp = instance!!
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            val vpnChannel = NotificationChannel(
                CHANNEL_VPN_SERVICE,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required for packet capture"
                setShowBadge(false)
            }
            
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY_SERVICE,
                "Radar Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays radar on screen"
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannels(listOf(vpnChannel, overlayChannel))
        }
    }
}
