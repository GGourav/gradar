package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
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

class RadarVpnService : VpnService() {

    companion object {
        const val TAG = "RadarVpn"
        const val ACTION_START = "com.gradar.action.START"
        const val ACTION_STOP = "com.gradar.action.STOP"
        const val NOTIFICATION_ID = 1001
        private const val MTU = 2048
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
    private var packetsCaptured = 0L
    private var eventsProcessed = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            discoveryLogger = DiscoveryLogger(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Logger init failed: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        try {
            when (intent?.action) {
                ACTION_START -> startVpnSafe()
                ACTION_STOP -> stopVpnSafe()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error: $e")
        }
        
        return START_STICKY
    }

    private fun startVpnSafe() {
        Log.d(TAG, "startVpnSafe begin")
        
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }
        
        // Create notification first
        try {
            val notification = createNotificationSafe()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR) {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Foreground started")
        } catch (e: Exception) {
            Log.e(TAG, "Foreground failed: $e")
        }
        
        // Establish VPN
        try {
            vpnInterface = establishVpnSafe()
        } catch (e: Exception) {
            Log.e(TAG, "establishVpn failed: $e")
        }
        
        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null")
            stopSelf()
            return
        }
        
        isRunning = true
        
        // Start packet capture thread
        vpnThread = Thread {
            try {
                captureLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop error: $e")
            }
        }.apply {
            name = "GRadar-Capture"
            start()
        }
        
        Log.d(TAG, "VPN started successfully")
    }

    private fun establishVpnSafe(): ParcelFileDescriptor? {
        Log.d(TAG, "establishVpnSafe")
        
        return try {
            val builder = Builder()
            builder.setMtu(MTU)
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("8.8.8.8")
            builder.setSession("G Radar")
            
            // Only intercept Albion
            try {
                builder.addAllowedApplication("com.albiononline")
                Log.d(TAG, "Added Albion package")
            } catch (e: Exception) {
                Log.w(TAG, "Albion package not found")
            }
            
            try {
                builder.addAllowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Self package failed")
            }
            
            val result = builder.establish()
            Log.d(TAG, "VPN established: ${result != null}")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "establish failed: $e")
            null
        }
    }

    private fun captureLoop() {
        Log.d(TAG, "Capture loop started")
        
        val fd = vpnInterface?.fileDescriptor
        if (fd == null) {
            Log.e(TAG, "File descriptor is null")
            return
        }
        
        val input = FileInputStream(fd)
        val buffer = ByteBuffer.allocate(MTU)
        
        try {
            while (isRunning && vpnInterface != null) {
                buffer.clear()
                
                val size = input.read(buffer.array())
                
                if (size > 0) {
                    packetsCaptured++
                    processPacketSafe(buffer.array(), size)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Capture ended: $e")
        }
        
        Log.d(TAG, "Capture loop ended")
    }

    private fun processPacketSafe(data: ByteArray, size: Int) {
        try {
            // Basic validation
            if (size < 28) return
            
            // Check IPv4
            val version = (data[0].toInt() shr 4) and 0x0F
            if (version != 4) return
            
            // Check UDP
            val protocol = data[9].toInt() and 0xFF
            if (protocol != 17) return
            
            // Get header length
            val ipHeaderLen = (data[0].toInt() and 0x0F) * 4
            if (size < ipHeaderLen + 8) return
            
            // Get ports
            val srcPort = ((data[ipHeaderLen].toInt() and 0xFF) shl 8) or 
                          (data[ipHeaderLen + 1].toInt() and 0xFF)
            val dstPort = ((data[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or 
                          (data[ipHeaderLen + 3].toInt() and 0xFF)
            
            // Only Albion traffic
            if (srcPort != ALBION_PORT && dstPort != ALBION_PORT) return
            
            // Extract payload
            val payloadStart = ipHeaderLen + 8
            if (size <= payloadStart) return
            
            val payload = data.copyOfRange(payloadStart, size)
            
            // Parse Photon
            val packet = photonParser.parsePacket(payload, payload.size) ?: return
            
            for (cmd in packet.commands) {
                if (cmd.commandType == PhotonProtocol.COMMAND_RELIABLE ||
                    cmd.commandType == PhotonProtocol.COMMAND_UNRELIABLE) {
                    
                    val event = photonParser.parseEvent(cmd.data) ?: continue
                    eventsProcessed++
                    
                    // Log event
                    try {
                        discoveryLogger?.logParsedEvent(event.eventCode, event.parameters)
                    } catch (e: Exception) {}
                    
                    // Process event
                    val entity = eventHandler.processEvent(event) ?: continue
                    
                    // Post to EventBus
                    try {
                        EventBus.getDefault().post(EntityUpdateEvent(entity))
                    } catch (e: Exception) {}
                    
                    // Log unknown
                    if (entity.uniqueName == null || entity.typeId == 0) {
                        try {
                            discoveryLogger?.logUnknownEntity(entity)
                        } catch (e: Exception) {}
                    }
                }
            }
            
        } catch (e: Exception) {
            // Ignore processing errors
        }
    }

    data class EntityUpdateEvent(val entity: GameEntity)

    private fun stopVpnSafe() {
        Log.d(TAG, "stopVpnSafe")
        
        isRunning = false
        
        try {
            vpnThread?.interrupt()
        } catch (e: Exception) {}
        vpnThread = null
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        
        try {
            eventHandler.clear()
        } catch (e: Exception) {}
        
        Log.d(TAG, "Stats: $packetsCaptured packets, $eventsProcessed events")
    }

    private fun createNotificationSafe(): Notification {
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
        Log.d(TAG, "onDestroy")
        stopVpnSafe()
        super.onDestroy()
    }
}
