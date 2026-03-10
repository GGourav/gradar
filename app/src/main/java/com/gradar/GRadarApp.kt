package com.gradar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GRadarApp : Application() {

    companion object {
        const val CHANNEL_VPN = "vpn_service"
        const val CHANNEL_OVERLAY = "overlay_service"
        const val ALBION_PACKAGE = "com.albiononline"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val vpnChannel = NotificationChannel(
                CHANNEL_VPN,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            
            manager.createNotificationChannels(listOf(vpnChannel, overlayChannel))
        }
    }
}
