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

/**
 * VPN Service for capturing Albion Online network traffic
 */
class RadarVpnService : VpnService() {

    companion object {
        const val TAG = "RadarVpnService"
        const val ACTION_START = "com.gradar.action.START"
        const val ACTION_STOP = "com.gradar.action.STOP"
        const val NOTIFICATION_ID = 1001
        
        private const val MTU = 2048
        private const val VPN_ADDRESS = "10.8.0.2"
        private const val VPN_PREFIX = 32
        private const val VPN_ROUTE = "0.0.0.0"
        private const val DNS_PRIMARY = "8.8.8.8"
        private const val DNS_SECONDARY = "8.8.4.4"
        
        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val packetBuffer = ByteBuffer.allocate(MTU)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRunning) startVpn()
            ACTION_STOP -> { stopVpn(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        Log.d(TAG, "Starting VPN...")
        startForeground(NOTIFICATION_ID, createNotification())
        
        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }
        
        isRunning = true
        vpnThread = Thread { capturePackets() }.apply { start() }
        Log.d(TAG, "VPN started successfully")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(DNS_PRIMARY)
                .addDnsServer(DNS_SECONDARY)
                .addAllowedApplication(GRadarApp.ALBION_PACKAGE)
                .addAllowedApplication(packageName)
                .setSession(getString(R.string.vpn_session_name))
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}")
            null
        }
    }

    private fun capturePackets() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        try {
            while (isRunning && vpnInterface != null) {
                val size = input.read(packetBuffer.array())
                if (size > 0) {
                    // TODO: Implement packet parsing in Step 2
                }
                packetBuffer.clear()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Packet capture stopped: ${e.message}")
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, GRadarApp.CHANNEL_VPN_SERVICE)
            .setContentTitle(getString(R.string.notification_vpn_title))
            .setContentText(getString(R.string.notification_vpn_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
        Log.d(TAG, "VPN Service destroyed")
    }
}
