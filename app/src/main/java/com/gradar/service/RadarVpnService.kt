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
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Simple VPN Service - Packet sniffing only
 * For best results: Start AFTER entering the game
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
    
    // UDP sockets for forwarding
    private val udpSockets = mutableMapOf<String, DatagramSocket>()
    
    private var packetsCaptured = 0L
    private var eventsProcessed = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            discoveryLogger = DiscoveryLogger(this)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}")
        }
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
        
        Log.d(TAG, "Starting VPN...")
        
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {}
        
        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN")
            stopSelf()
            return
        }
        
        isRunning = true
        vpnThread = Thread { runVpn() }.apply { start() }
        Log.d(TAG, "VPN started")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setMtu(MTU)
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("G Radar")
                .apply {
                    try {
                        addAllowedApplication(GRadarApp.ALBION_PACKAGE)
                    } catch (e: Exception) {}
                    try {
                        addAllowedApplication(packageName)
                    } catch (e: Exception) {}
                }
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Establish failed: ${e.message}")
            null
        }
    }

    private fun runVpn() {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteBuffer.allocate(MTU)
        
        try {
            while (isRunning && vpnInterface != null) {
                buffer.clear()
                val size = vpnInput.read(buffer.array())
                
                if (size > 0) {
                    packetsCaptured++
                    val data = buffer.array()
                    
                    // Process for radar
                    processPacket(data, size)
                    
                    // Forward the packet
                    forwardPacket(data, size, vpnOutput)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN ended: ${e.message}")
        } finally {
            for (socket in udpSockets.values) {
                try { socket.close() } catch (e: Exception) {}
            }
            udpSockets.clear()
        }
    }

    private fun forwardPacket(data: ByteArray, size: Int, vpnOutput: FileOutputStream) {
        if (size < 20) return
        
        val protocol = data[9].toInt() and 0xFF
        val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
        
        if (protocol == 17 && size >= ipHeaderLen + 8) { // UDP
            val srcPort = ((data[ipHeaderLen].toInt() and 0xFF) shl 8) or (data[ipHeaderLen + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (data[ipHeaderLen + 3].toInt() and 0xFF)
            
            val dstIp = InetAddress.getByAddress(byteArrayOf(
                (data[16].toInt() and 0xFF).toByte(),
                (data[17].toInt() and 0xFF).toByte(),
                (data[18].toInt() and 0xFF).toByte(),
                (data[19].toInt() and 0xFF).toByte()
            ))
            
            // Get or create socket
            val key = "$dstIp:$dstPort"
            var socket = udpSockets[key]
            
            if (socket == null) {
                try {
                    socket = DatagramSocket()
                    protect(socket) // CRITICAL: bypass VPN
                    udpSockets[key] = socket
                } catch (e: Exception) {
                    return
                }
            }
            
            // Send UDP payload
            val payloadSize = size - ipHeaderLen - 8
            if (payloadSize > 0) {
                try {
                    val payload = data.copyOfRange(ipHeaderLen + 8, size)
                    val packet = java.net.DatagramPacket(payload, payloadSize, dstIp, dstPort)
                    socket?.send(packet)
                } catch (e: Exception) {}
            }
            
            // Check for response (non-blocking)
            try {
                socket?.soTimeout = 1
                val responseBuffer = ByteArray(MTU)
                val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                socket?.receive(responsePacket)
                
                if (responsePacket.length > 0) {
                    // Build response IP/UDP packet
                    val response = buildUdpPacket(
                        responsePacket.address,
                        responsePacket.port,
                        dstPort, // src port becomes dst port
                        responsePacket.data,
                        responsePacket.length
                    )
                    vpnOutput.write(response)
                    
                    // Process response for radar
                    processPacket(response, response.size)
                }
            } catch (e: Exception) {
                // No response yet, continue
            }
        }
    }
    
    private fun buildUdpPacket(srcIp: InetAddress, srcPort: Int, dstPort: Int, payload: ByteArray, payloadLen: Int): ByteArray {
        // Simple IP + UDP header + payload
        val totalLen = 20 + 8 + payloadLen
        val packet = ByteArray(totalLen)
        
        // IP Header (simplified)
        packet[0] = (4 shl 4 or 5).toByte() // IPv4, 20 byte header
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[8] = 64 // TTL
        packet[9] = 17 // UDP
        
        // Source IP
        val srcBytes = srcIp.address
        packet[12] = srcBytes[0]
        packet[13] = srcBytes[1]
        packet[14] = srcBytes[2]
        packet[15] = srcBytes[3]
        
        // Destination IP (our VPN address)
        packet[16] = 10.toByte()
        packet[17] = 8.toByte()
        packet[18] = 0.toByte()
        packet[19] = 2.toByte()
        
        // UDP Header
        packet[20] = (srcPort shr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (dstPort shr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payloadLen
        packet[24] = (udpLen shr 8).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        
        // Payload
        System.arraycopy(payload, 0, packet, 28, payloadLen)
        
        return packet
    }

    private fun processPacket(data: ByteArray, size: Int) {
        try {
            if (size < 28) return
            
            val ipVersion = (data[0].toInt() shr 4) and 0x0F
            if (ipVersion != 4) return
            
            val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
            if (data[9].toInt() != 17) return // UDP only
            
            val srcPort = ((data[ipHeaderLen].toInt() and 0xFF) shl 8) or (data[ipHeaderLen + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (data[ipHeaderLen + 3].toInt() and 0xFF)
            
            // Only Albion traffic (port 5056)
            if (srcPort != ALBION_PORT && dstPort != ALBION_PORT) return
            
            val payloadStart = ipHeaderLen + 8
            if (size <= payloadStart) return
            
            val payload = data.copyOfRange(payloadStart, size)
            val packet = photonParser.parsePacket(payload, payload.size) ?: return
            
            for (cmd in packet.commands) {
                if (cmd.commandType == PhotonProtocol.COMMAND_RELIABLE ||
                    cmd.commandType == PhotonProtocol.COMMAND_UNRELIABLE) {
                    
                    val event = photonParser.parseEvent(cmd.data) ?: continue
                    eventsProcessed++
                    
                    discoveryLogger?.logParsedEvent(event.eventCode, event.parameters)
                    
                    val entity = eventHandler.processEvent(event) ?: continue
                    
                    try {
                        EventBus.getDefault().post(EntityUpdateEvent(entity))
                    } catch (e: Exception) {}
                    
                    if (entity.uniqueName == null || entity.typeId == 0) {
                        discoveryLogger?.logUnknownEntity(entity)
                    }
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Process error: ${e.message}")
        }
    }

    data class EntityUpdateEvent(val entity: GameEntity)

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
        eventHandler.clear()
        Log.d(TAG, "Stats: $packetsCaptured packets, $eventsProcessed events")
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
