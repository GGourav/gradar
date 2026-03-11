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
import com.gradar.handler.EventHandler
import com.gradar.logger.DiscoveryLogger
import com.gradar.model.GameEntity
import com.gradar.protocol.PhotonParser
import com.gradar.protocol.PhotonProtocol
import org.greenrobot.eventbus.EventBus
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * VPN Service - Passive sniffing mode
 * IMPORTANT: Works best when started AFTER entering the game
 */
class RadarVpnService : VpnService() {

    companion object {
        const val TAG = "RadarVpnService"
        const val ACTION_START = "com.gradar.action.START"
        const val ACTION_STOP = "com.gradar.action.STOP"
        const val NOTIFICATION_ID = 1001
        
        private const val MTU = 2048
        private const val ALBION_PORT = 5056
        
        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    
    private val photonParser = PhotonParser()
    private val eventHandler = EventHandler()
    private var discoveryLogger: DiscoveryLogger? = null
    
    private var packetsCaptured = 0L
    private var eventsProcessed = 0L

    override fun onCreate() {
        super.onCreate()
        discoveryLogger = DiscoveryLogger(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> { stopVpn(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            stopSelf()
            return
        }
        
        isRunning = true
        vpnThread = Thread { runVpnLoop() }.apply { start() }
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            // Create a transparent VPN that passes all traffic
            Builder()
                .setMtu(MTU)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("G Radar")
                .apply {
                    // Only intercept Albion
                    try { addAllowedApplication("com.albiononline") } catch (e: Exception) {}
                    try { addAllowedApplication(packageName) } catch (e: Exception) {}
                }
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "VPN establish failed: ${e.message}")
            null
        }
    }

    private fun runVpnLoop() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteBuffer.allocate(MTU)
        
        // Simple pass-through with packet analysis
        while (isRunning && vpnInterface != null) {
            try {
                buffer.clear()
                val size = input.read(buffer.array())
                
                if (size > 0) {
                    packetsCaptured++
                    
                    // Analyze packet for radar
                    analyzePacket(buffer.array(), size)
                    
                    // Pass through - write back to interface
                    // This is a "pass-through" VPN
                    output.write(buffer.array(), 0, size)
                }
            } catch (e: Exception) {
                if (isRunning) Log.d(TAG, "Loop error: ${e.message}")
                break
            }
        }
    }

    private fun analyzePacket(data: ByteArray, size: Int) {
        try {
            if (size < 28) return
            if ((data[0].toInt() shr 4) != 4) return // IPv4 only
            if (data[9].toInt() != 17) return // UDP only
            
            val ipLen = (data[0].toInt() and 0x0F) * 4
            val srcPort = ((data[ipLen].toInt() and 0xFF) shl 8) or (data[ipLen + 1].toInt() and 0xFF)
            val dstPort = ((data[ipLen + 2].toInt() and 0xFF) shl 8) or (data[ipLen + 3].toInt() and 0xFF)
            
            // Albion port check
            if (srcPort != ALBION_PORT && dstPort != ALBION_PORT) return
            
            val payload = data.copyOfRange(ipLen + 8, size)
            val packet = photonParser.parsePacket(payload, payload.size) ?: return
            
            for (cmd in packet.commands) {
                if (cmd.commandType == PhotonProtocol.COMMAND_RELIABLE ||
                    cmd.commandType == PhotonProtocol.COMMAND_UNRELIABLE) {
                    
                    val event = photonParser.parseEvent(cmd.data) ?: continue
                    eventsProcessed++
                    
                    discoveryLogger?.logParsedEvent(event.eventCode, event.parameters)
                    
                    val entity = eventHandler.processEvent(event) ?: continue
                    
                    EventBus.getDefault().post(EntityUpdateEvent(entity))
                    
                    if (entity.uniqueName == null || entity.typeId == 0) {
                        discoveryLogger?.logUnknownEntity(entity)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    data class EntityUpdateEvent(val entity: GameEntity)

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
        eventHandler.clear()
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, GRadarApp.CHANNEL_VPN_SERVICE)
            .setContentTitle(getString(R.string.notification_vpn_title))
            .setContentText(getString(R.string.notification_vpn_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
