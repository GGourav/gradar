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
 * VPN Service with proper Split-Tunneling and NAT Forwarding
 * KEY: Uses allowBypass() and non-blocking IO to mirror traffic without blocking
 */
class RadarVpnService : VpnService() {

    companion object {
        const val TAG = "RadarVpn"
        const val ACTION_START = "com.gradar.action.START"
        const val ACTION_STOP = "com.gradar.action.STOP"
        const val NOTIFICATION_ID = 1001
        
        private const val MTU = 1500
        private const val ALBION_PORT = 5056
        
        @Volatile
        private var isRunning = false
        
        fun isRunning() = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    
    private val photonParser = PhotonParser()
    private val eventHandler = EventHandler()
    private var discoveryLogger: DiscoveryLogger? = null
    
    // NAT Session Manager - Maps source ports to destination
    private val natTable = mutableMapOf<Short, NatSession>()
    
    // Non-blocking selector for UDP channels
    private var selector: Selector? = null
    
    // Output queue for packets going back to VPN
    private val outputQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer>()
    
    private var packetsCaptured = 0L
    private var eventsProcessed = 0L

    data class NatSession(
        val sourcePort: Short,
        val destIp: ByteArray,
        val destPort: Short,
        var channel: DatagramChannel? = null
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            discoveryLogger = DiscoveryLogger(applicationContext)
            selector = Selector.open()
        } catch (e: Exception) {
            Log.e(TAG, "Init error: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        
        Log.d(TAG, "=== STARTING VPN ===")
        
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Foreground failed: $e")
        }
        
        vpnInterface = establishSplitTunnelVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null - stopping")
            stopSelf()
            return
        }
        
        isRunning = true
        vpnThread = Thread { runVpnLoop() }.apply {
            name = "GRadar-VPN-NAT"
            start()
        }
        
        Log.d(TAG, "=== VPN STARTED ===")
    }

    /**
     * KEY FIX: Split-Tunneling with allowBypass()
     * This allows traffic to flow normally if our logic stalls
     */
    private fun establishSplitTunnelVpn(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
            
            // Standard MTU
            builder.setMtu(MTU)
            
            // Local VPN address
            builder.addAddress("10.0.0.2", 24)
            
            // Route all traffic through VPN (we'll filter with allowed apps)
            builder.addRoute("0.0.0.0", 0)
            
            // DNS servers
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("8.8.4.4")
            
            // =============================================
            // KEY FIX 1: allowBypass() - CRITICAL!
            // This allows apps to bypass VPN if needed
            // Prevents "Connection Problem" when our logic has issues
            // =============================================
            builder.allowBypass()
            
            // =============================================
            // KEY FIX 2: Split-Tunneling
            // Only "invite" specific apps to the VPN
            // Other apps use normal internet
            // =============================================
            try {
                // Albion Online package
                builder.addAllowedApplication("com.albiononline")
                Log.d(TAG, "Added Albion to VPN tunnel")
            } catch (e: Exception) {
                Log.w(TAG, "Albion package not found: $e")
            }
            
            try {
                // Our radar app
                builder.addAllowedApplication(packageName)
                Log.d(TAG, "Added self to VPN tunnel")
            } catch (e: Exception) {
                Log.w(TAG, "Self package failed: $e")
            }
            
            builder.setSession("G Radar - Split Tunnel")
            
            val result = builder.establish()
            Log.d(TAG, "VPN established: ${result != null}")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "establishSplitTunnelVpn failed: $e")
            null
        }
    }

    /**
     * Main VPN Loop with Non-Blocking IO
     * KEY: Reads packets, analyzes them, AND FORWARDS them to destination
     */
    private fun runVpnLoop() {
        Log.d(TAG, "=== VPN LOOP STARTED ===")
        
        val fd = vpnInterface?.fileDescriptor
        if (fd == null) {
            Log.e(TAG, "File descriptor is null")
            return
        }
        
        val vpnInput = FileInputStream(fd)
        val vpnOutput = FileOutputStream(fd)
        val buffer = ByteBuffer.allocate(MTU)
        
        try {
            while (isRunning && vpnInterface != null) {
                // =============================================
                // STEP 1: Read outgoing packet from VPN
                // =============================================
                buffer.clear()
                val size = vpnInput.read(buffer.array())
                
                if (size > 0) {
                    packetsCaptured++
                    val data = buffer.array()
                    
                    // Analyze packet for radar (READ-ONLY, doesn't consume)
                    analyzePacket(data, size)
                    
                    // Forward packet to real destination (CRITICAL!)
                    forwardPacketToDestination(data, size)
                }
                
                // =============================================
                // STEP 2: Check for incoming responses (Non-Blocking)
                // =============================================
                processIncomingResponses(vpnOutput)
                
                // =============================================
                // STEP 3: Write any queued responses back to VPN
                // =============================================
                while (true) {
                    val queuedPacket = outputQueue.poll() ?: break
                    try {
                        vpnOutput.write(queuedPacket.array(), 0, queuedPacket.limit())
                    } catch (e: Exception) {
                        Log.v(TAG, "Write error: $e")
                    }
                }
                
                // Small sleep to prevent CPU spinning
                Thread.sleep(1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN loop ended: $e")
        } finally {
            // Cleanup all NAT sessions
            for (session in natTable.values) {
                try {
                    session.channel?.close()
                } catch (e: Exception) {}
            }
            natTable.clear()
        }
        
        Log.d(TAG, "=== VPN LOOP ENDED ===")
    }

    /**
     * KEY FIX: Forward packet to actual destination
     * This is what was missing - we read but never forwarded!
     */
    private fun forwardPacketToDestination(data: ByteArray, size: Int) {
        try {
            if (size < 20) return
            
            // Check IPv4
            val version = (data[0].toInt() shr 4) and 0x0F
            if (version != 4) return
            
            val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
            
            // Check protocol
            val protocol = data[9].toInt() and 0xFF
            if (protocol != 17) return // UDP only for Albion
            
            if (size < ipHeaderLen + 8) return
            
            // Extract addresses
            val srcPort = ((data[ipHeaderLen].toInt() and 0xFF) shl 8) or
                          (data[ipHeaderLen + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                          (data[ipHeaderLen + 3].toInt() and 0xFF)
            
            // Destination IP
            val dstIp = InetAddress.getByAddress(byteArrayOf(
                (data[16].toInt() and 0xFF).toByte(),
                (data[17].toInt() and 0xFF).toByte(),
                (data[18].toInt() and 0xFF).toByte(),
                (data[19].toInt() and 0xFF).toByte()
            ))
            
            // Get or create NAT session with UDP channel
            val sessionKey = srcPort.toShort()
            var session = natTable[sessionKey]
            
            if (session == null || session.channel?.isOpen != true) {
                // Create new UDP channel
                val channel = DatagramChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(dstIp, dstPort.toInt()))
                
                // CRITICAL: Protect this socket from VPN loop!
                protect(channel.socket())
                
                // Register with selector for reading responses
                selector?.wakeup()
                channel.register(selector, SelectionKey.OP_READ, sessionKey)
                
                session = NatSession(
                    sourcePort = sessionKey,
                    destIp = dstIp.address,
                    destPort = dstPort.toShort(),
                    channel = channel
                )
                natTable[sessionKey] = session
                
                Log.d(TAG, "New NAT session: $srcPort -> ${dstIp.hostAddress}:$dstPort")
            }
            
            // Extract UDP payload and send through protected channel
            val payloadLen = size - ipHeaderLen - 8
            if (payloadLen > 0) {
                val payload = ByteBuffer.wrap(data, ipHeaderLen + 8, payloadLen)
                session?.channel?.write(payload)
            }
            
        } catch (e: Exception) {
            Log.v(TAG, "Forward error: $e")
        }
    }

    /**
     * Process incoming responses from game server
     */
    private fun processIncomingResponses(vpnOutput: FileOutputStream) {
        try {
            if (selector == null) return
            
            // Non-blocking select
            selector!!.selectNow()
            
            val keys = selector!!.selectedKeys().iterator()
            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()
                
                if (!key.isValid || !key.isReadable) continue
                
                val sessionKey = key.attachment() as? Short ?: continue
                val session = natTable[sessionKey] ?: continue
                val channel = session.channel ?: continue
                
                // Read response
                val responseBuffer = ByteBuffer.allocate(MTU)
                val readLen = channel.read(responseBuffer)
                
                if (readLen > 0) {
                    // Analyze response for radar
                    analyzePacket(responseBuffer.array(), readLen + 28)
                    
                    // Build IP+UDP packet for response
                    val packet = buildUdpResponsePacket(
                        session.destIp,
                        session.destPort.toInt(),
                        session.sourcePort.toInt(),
                        responseBuffer.array(),
                        readLen
                    )
                    
                    // Write directly to VPN output
                    try {
                        vpnOutput.write(packet, 0, packet.size)
                    } catch (e: Exception) {
                        // Queue for later
                        responseBuffer.limit(readLen + 28)
                        outputQueue.offer(responseBuffer)
                    }
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Response process error: $e")
        }
    }

    /**
     * Build a proper IP+UDP response packet
     */
    private fun buildUdpResponsePacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val totalLen = 20 + 8 + payloadLen // IP + UDP + payload
        val packet = ByteArray(totalLen)
        
        // IP Header
        packet[0] = 0x45.toByte() // IPv4, 20 byte header
        packet[1] = 0 // TOS
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0 // ID
        packet[5] = 0
        packet[6] = 0 // Flags
        packet[7] = 0 // Fragment offset
        packet[8] = 64 // TTL
        packet[9] = 17 // UDP
        
        // Source IP (game server)
        packet[12] = srcIp[0]
        packet[13] = srcIp[1]
        packet[14] = srcIp[2]
        packet[15] = srcIp[3]
        
        // Destination IP (our VPN client)
        packet[16] = 10.toByte()
        packet[17] = 0.toByte()
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
        packet[26] = 0 // Checksum (optional for UDP)
        packet[27] = 0
        
        // Payload
        System.arraycopy(payload, 0, packet, 28, payloadLen)
        
        return packet
    }

    /**
     * Analyze packet for radar (READ-ONLY operation)
     */
    private fun analyzePacket(data: ByteArray, size: Int) {
        try {
            if (size < 28) return
            
            val version = (data[0].toInt() shr 4) and 0x0F
            if (version != 4) return
            
            val protocol = data[9].toInt() and 0xFF
            if (protocol != 17) return
            
            val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
            if (size < ipHeaderLen + 8) return
            
            val srcPort = ((data[ipHeaderLen].toInt() and 0xFF) shl 8) or
                          (data[ipHeaderLen + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                          (data[ipHeaderLen + 3].toInt() and 0xFF)
            
            // Only Albion traffic
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
                    
                    EventBus.getDefault().post(EntityUpdateEvent(entity))
                    
                    if (entity.uniqueName == null || entity.typeId == 0) {
                        discoveryLogger?.logUnknownEntity(entity)
                    }
                }
            }
        } catch (e: Exception) {
            // Silent fail for analysis
        }
    }

    data class EntityUpdateEvent(val entity: GameEntity)

    private fun stopVpn() {
        Log.d(TAG, "=== STOPPING VPN ===")
        
        isRunning = false
        
        vpnThread?.interrupt()
        vpnThread = null
        
        // Close all NAT channels
        for (session in natTable.values) {
            try {
                session.channel?.close()
            } catch (e: Exception) {}
        }
        natTable.clear()
        
        try {
            selector?.close()
        } catch (e: Exception) {}
        selector = null
        
        vpnInterface?.close()
        vpnInterface = null
        
        eventHandler.clear()
        
        Log.d(TAG, "Stats: $packetsCaptured packets, $eventsProcessed events")
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
    }
}
