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
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * VPN Service - Proper tunnel implementation based on QRadar architecture
 * Key: Use protect() to bypass VPN for the actual network socket
 */
class RadarVpnService : VpnService() {

    companion object {
        const val TAG = "RadarVpnService"
        const val ACTION_START = "com.gradar.action.START"
        const val ACTION_STOP = "com.gradar.action.STOP"
        const val NOTIFICATION_ID = 1001
        
        private const val MTU = 2048
        private const val VPN_ADDRESS = "10.8.0.2"
        private const val VPN_PREFIX = 24
        private const val DNS_PRIMARY = "8.8.8.8"
        private const val DNS_SECONDARY = "8.8.4.4"
        private const val ALBION_PORT = 5056
        
        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    
    private val photonParser = PhotonParser()
    private val eventHandler = EventHandler()
    private lateinit var discoveryLogger: DiscoveryLogger
    
    private var packetsCaptured = 0L
    private var eventsProcessed = 0L

    override fun onCreate() {
        super.onCreate()
        discoveryLogger = DiscoveryLogger(this)
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
        vpnThread = Thread { runVpnLoop() }.apply { start() }
        Log.d(TAG, "VPN started successfully")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_PRIMARY)
                .addDnsServer(DNS_SECONDARY)
                .setSession(getString(R.string.vpn_session_name))
                .apply {
                    // Only intercept Albion Online traffic
                    try {
                        addAllowedApplication(GRadarApp.ALBION_PACKAGE)
                    } catch (e: Exception) {
                        Log.w(TAG, "Albion not installed: ${e.message}")
                    }
                    // Allow our app
                    try {
                        addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot add self: ${e.message}")
                    }
                }
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}", e)
            null
        }
    }

    private fun runVpnLoop() {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteBuffer.allocate(MTU)
        
        // UDP channels map (destination -> channel)
        val udpChannels = mutableMapOf<String, DatagramChannel>()
        
        try {
            while (isRunning && vpnInterface != null) {
                // 1. Read packet from VPN interface (outgoing from app)
                buffer.clear()
                val size = vpnInput.read(buffer.array())
                
                if (size > 0) {
                    packetsCaptured++
                    
                    val data = buffer.array()
                    
                    // Process for radar (analyze only)
                    processPacketForRadar(data, size)
                    
                    // Handle UDP packets (Albion uses UDP)
                    if (size >= 28 && data[9].toInt() == 17) { // UDP protocol
                        val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
                        
                        val dstIp = String.format("%d.%d.%d.%d",
                            data[16].toInt() and 0xFF,
                            data[17].toInt() and 0xFF,
                            data[18].toInt() and 0xFF,
                            data[19].toInt() and 0xFF)
                        
                        val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                                      (data[ipHeaderLen + 3].toInt() and 0xFF)
                        val srcPort = ((data[ipHeaderLen].toInt() and 0xFF) shl 8) or
                                      (data[ipHeaderLen + 1].toInt() and 0xFF)
                        
                        val key = "$dstIp:$dstPort"
                        
                        // Get or create UDP channel
                        var channel = udpChannels[key]
                        if (channel == null || !channel.isOpen) {
                            try {
                                channel = DatagramChannel.open()
                                channel.configureBlocking(false)
                                channel.connect(InetSocketAddress(dstIp, dstPort))
                                
                                // CRITICAL: Protect socket from VPN loop!
                                protect(channel.socket())
                                
                                udpChannels[key] = channel
                                Log.d(TAG, "Created UDP channel to $key")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to create channel: ${e.message}")
                                continue
                            }
                        }
                        
                        // Extract UDP payload and send
                        val payloadSize = size - ipHeaderLen - 8
                        if (payloadSize > 0) {
                            try {
                                val payload = ByteBuffer.wrap(data, ipHeaderLen + 8, payloadSize)
                                channel?.write(payload)
                            } catch (e: Exception) {
                                Log.v(TAG, "Send error: ${e.message}")
                            }
                        }
                    }
                }
                
                // 2. Check for incoming responses
                for ((key, channel) in udpChannels.toList()) {
                    if (!channel.isOpen) continue
                    
                    val responseBuffer = ByteBuffer.allocate(MTU)
                    try {
                        val readSize = channel.read(responseBuffer)
                        if (readSize > 0) {
                            // Process for radar
                            processPacketForRadar(responseBuffer.array(), readSize)
                            
                            // Write raw response to VPN (the app will handle it)
                            vpnOutput.write(responseBuffer.array(), 0, readSize)
                        }
                    } catch (e: Exception) {
                        // Non-blocking, might not have data
                    }
                }
                
                Thread.sleep(1) // Small delay to prevent CPU spinning
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN loop ended: ${e.message}")
        } finally {
            // Close all channels
            udpChannels.values.forEach { 
                try { it.close() } catch (e: Exception) {}
            }
            udpChannels.clear()
        }
    }

    private fun processPacketForRadar(data: ByteArray, size: Int) {
        try {
            if (size < 28) return
            
            val ipVersion = (data[0].toInt() shr 4) and 0x0F
            if (ipVersion != 4) return
            
            val ipHeaderLength = (data[0].toInt() and 0x0F) * 4
            val protocol = data[9].toInt() and 0xFF
            
            if (protocol != 17) return // Only UDP
            
            if (size < ipHeaderLength + 8) return
            
            val srcPort = ((data[ipHeaderLength].toInt() and 0xFF) shl 8) or 
                          (data[ipHeaderLength + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                          (data[ipHeaderLength + 3].toInt() and 0xFF)
            
            // Only Albion traffic
            if (srcPort != ALBION_PORT && dstPort != ALBION_PORT) return
            
            val udpPayloadStart = ipHeaderLength + 8
            if (size <= udpPayloadStart) return
            
            val payload = data.copyOfRange(udpPayloadStart, size)
            
            val photonPacket = photonParser.parsePacket(payload, payload.size) ?: return
            
            for (command in photonPacket.commands) {
                if (command.commandType == PhotonProtocol.COMMAND_RELIABLE ||
                    command.commandType == PhotonProtocol.COMMAND_UNRELIABLE) {
                    
                    val event = photonParser.parseEvent(command.data) ?: continue
                    eventsProcessed++
                    
                    discoveryLogger.logParsedEvent(event.eventCode, event.parameters)
                    
                    val entity = eventHandler.processEvent(event) ?: continue
                    
                    EventBus.getDefault().post(EntityUpdateEvent(entity))
                    
                    if (entity.uniqueName == null || entity.typeId == 0) {
                        discoveryLogger.logUnknownEntity(entity)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet: ${e.message}")
        }
    }

    data class EntityUpdateEvent(val entity: GameEntity)

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        
        vpnThread?.interrupt()
        vpnThread = null
        
        vpnInterface?.close()
        vpnInterface = null
        
        eventHandler.clear()
        
        Log.d(TAG, "Stats: $packetsCaptured packets, $eventsProcessed events")
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
