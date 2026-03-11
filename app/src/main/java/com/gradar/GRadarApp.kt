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
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            
            // VPN Channel
            val vpnChannel = NotificationChannel(
                CHANNEL_VPN_SERVICE,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Packet capture service"
                setShowBadge(false)
            }
            
            // Overlay Channel
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY_SERVICE,
                "Radar Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Radar display service"
                setShowBadge(false)
            }
            
            try {
                nm.createNotificationChannels(listOf(vpnChannel, overlayChannel))
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
