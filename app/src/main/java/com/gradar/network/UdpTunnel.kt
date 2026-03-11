package com.gradar.network

import android.net.VpnService
import android.util.Log
import com.gradar.radar.EntityProcessor
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * UDP Tunnel - Handles UDP packet forwarding between VPN and real server
 * Critical: Uses vpnService.protect() to prevent routing loop
 */
class UdpTunnel(
    private val vpnService: VpnService,
    private val outputQueue: java.util.Queue<Packet>,
    referencePacket: Packet,
    private val portKey: Short
) {
    companion object {
        private const val TAG = "UdpTunnel"
        const val ALBION_PORT = 5056
        const val HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE
    }

    private var channel: DatagramChannel? = null
    private val toNetworkPackets = java.util.concurrent.ConcurrentLinkedQueue<Packet>()
    private val referencePacket: Packet = referencePacket.duplicate()
    private val session: NatSession? = NatSessionManager.getSession(portKey)
    private var isRunning = true

    val ipAndPort: String = referencePacket.getIpAndPort()

    /**
     * Initialize UDP channel connection to real server
     */
    fun initConnection() {
        Log.d(TAG, "Init connection: $ipAndPort")

        try {
            val destAddress = referencePacket.ip4Header.destinationAddress
                ?: run {
                    Log.e(TAG, "No destination address")
                    return
                }
            val destPort = referencePacket.udpHeader?.destinationPort ?: run {
                Log.e(TAG, "No destination port")
                return
            }

            channel = DatagramChannel.open().apply {
                // CRITICAL: Protect socket from VPN routing loop
                vpnService.protect(socket())

                // Non-blocking mode
                configureBlocking(false)

                // Connect to real server
                connect(InetSocketAddress(destAddress, destPort))
            }

            Log.d(TAG, "UDP channel connected to $destAddress:$destPort")

            // Swap source/destination for response packets
            referencePacket.swapSourceAndDestination()

            // Send initial packet
            addToNetworkPacket(referencePacket)
            processSend()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to init UDP tunnel: ${e.message}")
            close()
        }
    }

    /**
     * Process incoming packet from VPN (to be forwarded to server)
     */
    fun processPacket(packet: Packet) {
        addToNetworkPacket(packet)
        processSend()
    }

    /**
     * Read response from server (called from selector loop)
     */
    fun processReceived(): Boolean {
        val buffer = ByteBuffer.allocate(2048)
        buffer.position(HEADER_SIZE)

        return try {
            val readBytes = channel?.read(buffer) ?: -1

            when {
                readBytes == -1 -> {
                    Log.d(TAG, "Channel closed: $ipAndPort")
                    false
                }
                readBytes == 0 -> true
                else -> {
                    session?.let {
                        it.bytesReceived += readBytes
                        it.packetsReceived++
                        it.refresh()
                    }

                    // Check if this is Albion traffic (port 5056)
                    val isAlbion = referencePacket.udpHeader?.sourcePort == ALBION_PORT ||
                                   referencePacket.udpHeader?.destinationPort == ALBION_PORT

                    if (isAlbion) {
                        // Parse Photon packets for radar
                        buffer.position(HEADER_SIZE)
                        val photonBuffer = buffer.duplicate()
                        photonBuffer.limit(HEADER_SIZE + readBytes)
                        processPhotonPacket(photonBuffer)
                    }

                    // Create response packet for VPN interface
                    val newPacket = referencePacket.duplicate()
                    newPacket.updateUDPBuffer(buffer, readBytes)
                    outputQueue.offer(newPacket)

                    true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error reading from channel: ${e.message}")
            false
        }
    }

    /**
     * Process Photon packet for radar data
     */
    private fun processPhotonPacket(buffer: ByteBuffer) {
        try {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            // Parse and process Photon events
            EntityProcessor.processPhotonData(data)

        } catch (e: Exception) {
            Log.d(TAG, "Error parsing Photon: ${e.message}")
        }
    }

    /**
     * Send packets to real server
     */
    private fun processSend() {
        while (isRunning) {
            val packet = toNetworkPackets.poll() ?: break

            try {
                val payloadBuffer = packet.backingBuffer

                session?.let {
                    val sendSize = payloadBuffer.limit() - payloadBuffer.position()
                    it.bytesSent += sendSize
                    it.packetsSent++
                    it.refresh()
                }

                // Check if this is Albion traffic
                val isAlbion = packet.udpHeader?.destinationPort == ALBION_PORT ||
                               packet.udpHeader?.sourcePort == ALBION_PORT

                if (isAlbion) {
                    // Parse outgoing Photon packets (for player position, etc.)
                    val bufferCopy = payloadBuffer.duplicate()
                    bufferCopy.position(HEADER_SIZE)
                    processPhotonPacket(bufferCopy)
                }

                // Forward to real server
                while (payloadBuffer.hasRemaining()) {
                    channel?.write(payloadBuffer)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet: ${e.message}")
                close()
                break
            }
        }
    }

    private fun addToNetworkPacket(packet: Packet) {
        toNetworkPackets.offer(packet)
    }

    /**
     * Close the tunnel
     */
    fun close() {
        isRunning = false
        try {
            channel?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing channel: ${e.message}")
        }
        channel = null
        NatSessionManager.removeSession(portKey)
        Log.d(TAG, "Tunnel closed: $ipAndPort")
    }

    fun getPortKey(): Short = portKey

    fun hasDataToSend(): Boolean = toNetworkPackets.isNotEmpty()
}
