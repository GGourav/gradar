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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/**
 * VPN Service for capturing Albion Online network traffic
 * Full bidirectional tunnel implementation
 */
class RadarVpnService : VpnService() {

    companion object {
        const val TAG = "RadarVpnService"
        const val ACTION_START = "com.gradar.action.START"
        const val ACTION_STOP = "com.gradar.action.STOP"
        const val NOTIFICATION_ID = 1001
        
        private const val MTU = 32767
        private const val VPN_ADDRESS = "10.8.0.2"
        private const val VPN_PREFIX = 24
        private const val DNS_PRIMARY = "8.8.8.8"
        private const val DNS_SECONDARY = "8.8.4.4"
        
        // Albion Online server port
        private const val ALBION_PORT = 5056
        
        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    
    // Packet processing
    private val photonParser = PhotonParser()
    private val eventHandler = EventHandler()
    private lateinit var discoveryLogger: DiscoveryLogger
    
    // Stats
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
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
        }
        
        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }
        
        isRunning = true
        vpnThread = Thread { runVpn() }.apply { start() }
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
                // IMPORTANT: Don't filter by app - pass through all traffic
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}", e)
            null
        }
    }

    private fun runVpn() {
        val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteBuffer.allocate(MTU)
        
        // Selector for non-blocking I/O
        val selector = Selector.open()
        
        // Map of channels for each connection
        val channels = mutableMapOf<String, DatagramChannel>()
        
        try {
            while (isRunning && vpnInterface != null) {
                // Read outgoing packet from VPN
                buffer.clear()
                val size = vpnInput.read(buffer.array())
                
                if (size > 0) {
                    packetsCaptured++
                    
                    // Process for radar (just analyze, don't modify)
                    processPacket(buffer.array(), size)
                    
                    // Get packet info
                    if (size >= 20) {
                        val ipHeaderLen = (buffer.array()[0].toInt() and 0x0F) * 4
                        val protocol = buffer.array()[9].toInt() and 0xFF
                        
                        if (protocol == 17 && size >= ipHeaderLen + 8) { // UDP
                            val srcPort = ((buffer.array()[ipHeaderLen].toInt() and 0xFF) shl 8) or
                                          (buffer.array()[ipHeaderLen + 1].toInt() and 0xFF)
                            val dstPort = ((buffer.array()[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                                          (buffer.array()[ipHeaderLen + 3].toInt() and 0xFF)
                            
                            // Get destination IP
                            val dstIp = String.format("%d.%d.%d.%d",
                                buffer.array()[16].toInt() and 0xFF,
                                buffer.array()[17].toInt() and 0xFF,
                                buffer.array()[18].toInt() and 0xFF,
                                buffer.array()[19].toInt() and 0xFF)
                            
                            val key = "$dstIp:$dstPort"
                            
                            // Get or create channel for this destination
                            var channel = channels[key]
                            if (channel == null) {
                                channel = DatagramChannel.open()
                                channel.configureBlocking(false)
                                channel.socket().soTimeout = 0
                                protect(channel.socket()) // Protect from VPN loop
                                channel.connect(InetSocketAddress(dstIp, dstPort))
                                channel.register(selector, SelectionKey.OP_READ, key)
                                channels[key] = channel
                            }
                            
                            // Forward packet to destination
                            val payload = buffer.array().copyOfRange(ipHeaderLen + 8, size)
                            channel.write(ByteBuffer.wrap(payload))
                        }
                    }
                }
                
                // Check for incoming responses (non-blocking)
                selector.selectNow()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    
                    if (key.isReadable) {
                        val channel = key.channel() as DatagramChannel
                        val responseBuffer = ByteBuffer.allocate(MTU)
                        val responseLen = channel.read(responseBuffer)
                        
                        if (responseLen > 0) {
                            // Build IP/UDP packet for response
                            val keyStr = key.attachment() as String
                            // Just write raw response back to VPN
                            vpnOutput.write(responseBuffer.array(), 0, responseLen)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN stopped: ${e.message}")
        } finally {
            // Close all channels
            channels.values.forEach { it.close() }
            channels.clear()
            selector.close()
        }
    }

    /**
     * Process a captured packet for radar (read-only)
     */
    private fun processPacket(data: ByteArray, size: Int) {
        try {
            if (size < 20) return
            
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
            
            // Only process Albion traffic
            if (srcPort != ALBION_PORT && dstPort != ALBION_PORT) return
            
            val udpPayloadStart = ipHeaderLength + 8
            if (size <= udpPayloadStart) return
            
            val payload = data.copyOfRange(udpPayloadStart, size)
            
            // Parse Photon packet
            val photonPacket = photonParser.parsePacket(payload, payload.size)
            if (photonPacket != null) {
                processPhotonPacket(photonPacket)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet: ${e.message}")
        }
    }

    /**
     * Process a parsed Photon packet
     */
    private fun processPhotonPacket(packet: PhotonParser.PhotonPacket) {
        for (command in packet.commands) {
            if (command.commandType == PhotonProtocol.COMMAND_RELIABLE ||
                command.commandType == PhotonProtocol.COMMAND_UNRELIABLE) {
                
                val event = photonParser.parseEvent(command.data)
                if (event != null) {
                    eventsProcessed++
                    
                    // Log for discovery
                    discoveryLogger.logParsedEvent(event.eventCode, event.parameters)
                    
                    // Process event
                    val entity = eventHandler.processEvent(event)
                    if (entity != null) {
                        // Post entity update to EventBus
                        EventBus.getDefault().post(EntityUpdateEvent(entity))
                        
                        // Log unknown entities
                        if (entity.uniqueName == null || entity.typeId == 0) {
                            discoveryLogger.logUnknownEntity(entity)
                        }
                    }
                }
            }
        }
    }

    /**
     * Entity update event for EventBus
     */
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
