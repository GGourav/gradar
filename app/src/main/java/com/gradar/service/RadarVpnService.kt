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
 * VPN Service - Robust implementation with proper error handling
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
        try {
            discoveryLogger = DiscoveryLogger(this)
            Log.d(TAG, "VPN Service created")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        try {
            when (intent?.action) {
                ACTION_START -> startVpn()
                ACTION_STOP -> { stopVpn(); stopSelf() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error: ${e.message}")
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return
        }
        
        Log.d(TAG, "Starting VPN...")
        
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
        }
        
        vpnInterface = establishVpn()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            stopSelf()
            return
        }
        
        isRunning = true
        vpnThread = Thread {
            try {
                runVpnLoop()
            } catch (e: Exception) {
                Log.e(TAG, "VPN thread error: ${e.message}")
            }
        }.apply { 
            name = "GRadar-VPN"
            start() 
        }
        Log.d(TAG, "VPN started successfully")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setMtu(MTU)
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setSession("G Radar VPN")
            
            // Only intercept Albion Online
            try {
                builder.addAllowedApplication(GRadarApp.ALBION_PACKAGE)
                Log.d(TAG, "Added Albion to allowed apps")
            } catch (e: Exception) {
                Log.w(TAG, "Albion not installed: ${e.message}")
            }
            
            // Allow our app
            try {
                builder.addAllowedApplication(packageName)
                Log.d(TAG, "Added self to allowed apps")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot add self: ${e.message}")
            }
            
            val result = builder.establish()
            Log.d(TAG, "VPN interface established: ${result != null}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}", e)
            null
        }
    }

    private fun runVpnLoop() {
        Log.d(TAG, "VPN loop started")
        
        var vpnInput: FileInputStream? = null
        var vpnOutput: FileOutputStream? = null
        
        try {
            vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open VPN streams: ${e.message}")
            return
        }
        
        val buffer = ByteBuffer.allocate(MTU)
        val udpChannels = mutableMapOf<String, DatagramChannel>()
        
        try {
            while (isRunning && vpnInterface != null) {
                // 1. Read outgoing packet
                buffer.clear()
                val size: Int
                try {
                    size = vpnInput.read(buffer.array())
                } catch (e: Exception) {
                    if (isRunning) Log.d(TAG, "Read error: ${e.message}")
                    break
                }
                
                if (size > 0) {
                    packetsCaptured++
                    val data = buffer.array()
                    
                    // Process for radar
                    try {
                        processPacketForRadar(data, size)
                    } catch (e: Exception) {
                        Log.v(TAG, "Radar process error: ${e.message}")
                    }
                    
                    // Handle UDP
                    try {
                        handleUdpPacket(data, size, udpChannels)
                    } catch (e: Exception) {
                        Log.v(TAG, "UDP handle error: ${e.message}")
                    }
                }
                
                // 2. Check incoming responses
                try {
                    checkIncomingResponses(udpChannels, vpnOutput)
                } catch (e: Exception) {
                    Log.v(TAG, "Response check error: ${e.message}")
                }
                
                Thread.sleep(1)
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN loop ended: ${e.message}")
        } finally {
            // Close all channels
            for (channel in udpChannels.values) {
                try { channel.close() } catch (e: Exception) {}
            }
            udpChannels.clear()
            
            try { vpnInput?.close() } catch (e: Exception) {}
            try { vpnOutput?.close() } catch (e: Exception) {}
        }
        
        Log.d(TAG, "VPN loop exited")
    }
    
    private fun handleUdpPacket(data: ByteArray, size: Int, udpChannels: MutableMap<String, DatagramChannel>) {
        if (size < 28) return
        if (data[9].toInt() != 17) return // Not UDP
        
        val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
        if (size < ipHeaderLen + 8) return
        
        val dstIp = String.format("%d.%d.%d.%d",
            data[16].toInt() and 0xFF,
            data[17].toInt() and 0xFF,
            data[18].toInt() and 0xFF,
            data[19].toInt() and 0xFF)
        
        val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                      (data[ipHeaderLen + 3].toInt() and 0xFF)
        
        val key = "$dstIp:$dstPort"
        
        // Get or create channel
        var channel = udpChannels[key]
        if (channel == null || !channel.isOpen) {
            try {
                channel = DatagramChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(dstIp, dstPort))
                protect(channel.socket()) // CRITICAL!
                udpChannels[key] = channel
                Log.d(TAG, "Created UDP channel: $key")
            } catch (e: Exception) {
                Log.w(TAG, "Failed channel to $key: ${e.message}")
                return
            }
        }
        
        // Send payload
        val payloadSize = size - ipHeaderLen - 8
        if (payloadSize > 0 && channel != null) {
            try {
                val payload = ByteBuffer.wrap(data, ipHeaderLen + 8, payloadSize)
                channel.write(payload)
            } catch (e: Exception) {
                Log.v(TAG, "Send error: ${e.message}")
            }
        }
    }
    
    private fun checkIncomingResponses(udpChannels: Map<String, DatagramChannel>, vpnOutput: FileOutputStream) {
        val responseBuffer = ByteBuffer.allocate(MTU)
        
        for ((key, channel) in udpChannels) {
            if (!channel.isOpen) continue
            
            try {
                responseBuffer.clear()
                val readSize = channel.read(responseBuffer)
                
                if (readSize > 0) {
                    // Process for radar
                    try {
                        processPacketForRadar(responseBuffer.array(), readSize)
                    } catch (e: Exception) {}
                    
                    // Write to VPN
                    try {
                        vpnOutput.write(responseBuffer.array(), 0, readSize)
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                // Non-blocking, ignore
            }
        }
    }

    private fun processPacketForRadar(data: ByteArray, size: Int) {
        if (size < 28) return
        
        val ipVersion = (data[0].toInt() shr 4) and 0x0F
        if (ipVersion != 4) return
        
        val ipHeaderLength = (data[0].toInt() and 0x0F) * 4
        val protocol = data[9].toInt() and 0xFF
        
        if (protocol != 17) return
        if (size < ipHeaderLength + 8) return
        
        val srcPort = ((data[ipHeaderLength].toInt() and 0xFF) shl 8) or 
                      (data[ipHeaderLength + 1].toInt() and 0xFF)
        val dstPort = ((data[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or 
                      (data[ipHeaderLength + 3].toInt() and 0xFF)
        
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
    }

    data class EntityUpdateEvent(val entity: GameEntity)

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        
        try {
            vpnThread?.interrupt()
        } catch (e: Exception) {}
        vpnThread = null
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
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
