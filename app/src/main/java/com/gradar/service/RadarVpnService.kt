package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.gradar.GRadarApp
import com.gradar.MainActivity
import com.gradar.R
import java.io.FileInputStream
import java.nio.ByteBuffer

class RadarVpnService : VpnService() {

    companion object {
        private const val TAG = "RadarVpn"
        private const val MTU = 2048
        private const val NOTIFICATION_ID = 1001
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP" -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        vpnInterface = Builder()
            .setMtu(MTU)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addAllowedApplication(GRadarApp.ALBION_PACKAGE)
            .addAllowedApplication(packageName)
            .setSession("G Radar VPN")
            .establish()

        if (vpnInterface != null) {
            isRunning = true
            Log.d(TAG, "VPN Started")
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "VPN Stopped")
    }

    private fun createNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, GRadarApp.CHANNEL_VPN)
            .setContentTitle("G Radar")
            .setContentText("VPN Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
