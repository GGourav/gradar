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
        
        // Albion Online server port
        private const val ALBION_PORT = 5056
        
        @Volatile
        private var isRunning = false
        
        fun isRunning(): Boolean = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val packetBuffer = ByteBuffer.allocate(MTU)
    
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
        vpnThread = Thread { capturePackets() }.apply { start() }
        Log.d(TAG, "VPN started successfully")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(DNS_PRIMARY)
                .addDnsServer(DNS_SECONDARY)
                .setSession(getString(R.string.vpn_session_name))
            
            // Try to add Albion Online app
            try {
                builder.addAllowedApplication(GRadarApp.ALBION_PACKAGE)
            } catch (e: Exception) {
                Log.w(TAG, "Albion Online not installed, capturing all traffic")
            }
            
            // Always allow our own app
            try {
                builder.addAllowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not add self to allowed apps")
            }
            
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}", e)
            null
        }
    }

    private fun capturePackets() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        
        try {
            while (isRunning && vpnInterface != null) {
                val size = input.read(packetBuffer.array())
                if (size > 0) {
                    packetsCaptured++
                    processPacket(packetBuffer.array(), size)
                }
                packetBuffer.clear()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Packet capture stopped: ${e.message}")
        }
    }

    /**
     * Process a captured packet
     */
    private fun processPacket(data: ByteArray, size: Int) {
        try {
            // Parse IP header to find UDP packets
            if (size < 20) return // Minimum IP header size
            
            val ipVersion = (data[0].toInt() shr 4) and 0x0F
            if (ipVersion != 4) return // Only IPv4 for now
            
            val ipHeaderLength = (data[0].toInt() and 0x0F) * 4
            val protocol = data[9].toInt() and 0xFF
            
            // Check if UDP
            if (protocol != 17) return
            
            // Parse UDP header
            if (size < ipHeaderLength + 8) return
            
            val srcPort = ((data[ipHeaderLength].toInt() and 0xFF) shl 8) or (data[ipHeaderLength + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or (data[ipHeaderLength + 3].toInt() and 0xFF)
            
            // Check if this is Albion traffic (port 5056)
            if (srcPort != ALBION_PORT && dstPort != ALBION_PORT) return
            
            // Extract UDP payload
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
                    
                    // Log all events for discovery
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
        
        // Clear entities
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
