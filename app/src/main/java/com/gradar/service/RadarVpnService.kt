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
import com.gradar.network.NatSessionManager
import com.gradar.network.Packet
import com.gradar.network.UdpTunnel
import com.gradar.radar.EntityProcessor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * VPN Service for capturing Albion Online network traffic
 * Implements full NAT forwarding to allow game traffic while capturing packets
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
        private const val ALBION_PORT = 5056

        @Volatile
        private var isRunning = false

        fun isRunning(): Boolean = isRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnReadThread: Thread? = null
    private var vpnWriteThread: Thread? = null
    private var tunnelCheckThread: Thread? = null

    private val outputQueue = ConcurrentLinkedQueue<Packet>()
    private val udpTunnels = ConcurrentHashMap<Short, UdpTunnel>()

    private var packetsRead = 0L
    private var packetsWritten = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
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

        // Clear any previous state
        NatSessionManager.clearAllSessions()
        EntityProcessor.clearAll()

        // Start VPN read thread (captures packets from apps)
        vpnReadThread = Thread({ readFromVpn() }, "VPN-Read").apply { start() }

        // Start VPN write thread (writes responses back to apps)
        vpnWriteThread = Thread({ writeToVpn() }, "VPN-Write").apply { start() }

        // Start tunnel check thread (reads responses from servers)
        tunnelCheckThread = Thread({ checkTunnels() }, "Tunnel-Check").apply { start() }

        Log.d(TAG, "VPN started successfully")
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .apply {
                    // Only capture Albion traffic
                    try {
                        addAllowedApplication(GRadarApp.ALBION_PACKAGE)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add Albion package: ${e.message}")
                    }
                }
                .setSession(getString(R.string.vpn_session_name))
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}")
            null
        }
    }

    /**
     * Read packets from VPN interface (from apps)
     */
    private fun readFromVpn() {
        val input = FileInputStream(vpnInterface?.fileDescriptor)
        val buffer = ByteBuffer.allocate(MTU)

        Log.d(TAG, "VPN read thread started")

        try {
            while (isRunning && vpnInterface != null) {
                buffer.clear()
                val size = input.read(buffer.array())

                if (size > 0) {
                    buffer.limit(size)

                    try {
                        val packet = Packet(buffer)
                        packetsRead++

                        when {
                            packet.isUDP -> processUdpPacket(packet)
                            packet.isTCP -> processTcpPacket(packet)
                        }

                    } catch (e: Exception) {
                        // Invalid packet, skip
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN read stopped: ${e.message}")
        }

        Log.d(TAG, "VPN read thread ended, total packets: $packetsRead")
    }

    /**
     * Process UDP packet - forward to real server via tunnel
     */
    private fun processUdpPacket(packet: Packet) {
        val destPort = packet.udpHeader?.destinationPort ?: return
        val srcPort = packet.udpHeader?.sourcePort ?: return

        // Get or create tunnel for this source port
        val portKey = srcPort.toShort()
        var tunnel = udpTunnels[portKey]

        if (tunnel == null) {
            // Create new tunnel
            val remoteIP = packet.ip4Header.destinationAddress?.address?.let {
                var ip = 0
                for (i in 0..3) {
                    ip = ip or ((it[i].toInt() and 0xFF) shl (i * 8))
                }
                ip
            } ?: return

            val remotePort = destPort.toShort()

            // Create NAT session
            NatSessionManager.createSession(portKey, remoteIP, remotePort, "UDP")

            // Create tunnel
            tunnel = UdpTunnel(this, outputQueue, packet, portKey)
            udpTunnels[portKey] = tunnel

            // Initialize connection
            tunnel.initConnection()

            Log.d(TAG, "Created UDP tunnel for port $srcPort -> $destPort")
        } else {
            // Forward packet through existing tunnel
            tunnel.processPacket(packet)
        }
    }

    /**
     * Process TCP packet - for now, we ignore TCP
     */
    private fun processTcpPacket(packet: Packet) {
        // TCP not used by Albion game protocol
    }

    /**
     * Write packets back to VPN interface (to apps)
     */
    private fun writeToVpn() {
        val output = FileOutputStream(vpnInterface?.fileDescriptor)

        Log.d(TAG, "VPN write thread started")

        try {
            while (isRunning && vpnInterface != null) {
                val packet = outputQueue.poll()

                if (packet != null) {
                    output.write(packet.backingBuffer.array(), 0, packet.backingBuffer.limit())
                    packetsWritten++
                } else {
                    Thread.sleep(1)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VPN write stopped: ${e.message}")
        }

        Log.d(TAG, "VPN write thread ended, total packets: $packetsWritten")
    }

    /**
     * Check tunnels for incoming data from servers
     */
    private fun checkTunnels() {
        Log.d(TAG, "Tunnel check thread started")

        try {
            while (isRunning) {
                var hasActivity = false

                for ((_, tunnel) in udpTunnels) {
                    try {
                        if (!tunnel.processReceived()) {
                            // Tunnel closed
                        }
                        hasActivity = true
                    } catch (e: Exception) {
                        // Tunnel error
                    }
                }

                for ((_, tunnel) in udpTunnels) {
                    if (tunnel.hasDataToSend()) {
                        hasActivity = true
                        break
                    }
                }

                if (!hasActivity) {
                    Thread.sleep(5)
                }

                if (packetsRead % 100 == 0L && packetsRead > 0) {
                    Log.d(TAG, "Stats: read=$packetsRead, write=$packetsWritten, tunnels=${udpTunnels.size}")
                    Log.d(TAG, EntityProcessor.getStats())
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Tunnel check stopped: ${e.message}")
        }

        Log.d(TAG, "Tunnel check thread ended")
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false

        for ((_, tunnel) in udpTunnels) {
            tunnel.close()
        }
        udpTunnels.clear()

        vpnReadThread?.interrupt()
        vpnWriteThread?.interrupt()
        tunnelCheckThread?.interrupt()

        vpnReadThread = null
        vpnWriteThread = null
        tunnelCheckThread = null

        vpnInterface?.close()
        vpnInterface = null

        NatSessionManager.clearAllSessions()

        Log.d(TAG, "VPN stopped")
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
