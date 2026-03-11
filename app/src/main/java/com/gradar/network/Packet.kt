package com.gradar.network

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * IP Packet representation with TCP/UDP header parsing
 * Based on QRadar implementation
 */
class Packet(buffer: ByteBuffer) {

    companion object {
        const val IP4_HEADER_SIZE = 20
        const val TCP_HEADER_SIZE = 20
        const val UDP_HEADER_SIZE = 8
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
    }

    var ip4Header: IP4Header
    var tcpHeader: TCPHeader? = null
    var udpHeader: UDPHeader? = null
    var backingBuffer: ByteBuffer
    var isTCP: Boolean = false
        private set
    var isUDP: Boolean = false
        private set
    var payloadSize: Int = 0
        private set

    init {
        ip4Header = IP4Header(buffer)

        when (ip4Header.protocol) {
            PROTOCOL_TCP -> {
                tcpHeader = TCPHeader(buffer)
                isTCP = true
            }
            PROTOCOL_UDP -> {
                udpHeader = UDPHeader(buffer)
                isUDP = true
            }
        }

        backingBuffer = buffer
        payloadSize = buffer.limit() - buffer.position()
    }

    private constructor()

    fun duplicate(): Packet {
        val packet = Packet()
        packet.ip4Header = ip4Header.duplicate()

        tcpHeader?.let { packet.tcpHeader = it.duplicate() }
        udpHeader?.let { packet.udpHeader = it.duplicate() }

        packet.isTCP = isTCP
        packet.isUDP = isUDP
        packet.backingBuffer = backingBuffer.duplicate()
        packet.payloadSize = payloadSize
        return packet
    }

    fun swapSourceAndDestination() {
        val newSourceAddress = ip4Header.destinationAddress
        ip4Header.destinationAddress = ip4Header.sourceAddress
        ip4Header.sourceAddress = newSourceAddress

        if (isUDP) {
            udpHeader?.let {
                val newSourcePort = it.destinationPort
                it.destinationPort = it.sourcePort
                it.sourcePort = newSourcePort
            }
        } else if (isTCP) {
            tcpHeader?.let {
                val newSourcePort = it.destinationPort
                it.destinationPort = it.sourcePort
                it.sourcePort = newSourcePort
            }
        }
    }

    fun updateUDPBuffer(buffer: ByteBuffer, newPayloadSize: Int) {
        buffer.position(0)
        fillHeader(buffer)
        backingBuffer = buffer

        val udpTotalLength = UDP_HEADER_SIZE + newPayloadSize
        backingBuffer.putShort(IP4_HEADER_SIZE + 4, udpTotalLength.toShort())
        udpHeader?.length = udpTotalLength

        // Disable UDP checksum
        backingBuffer.putShort(IP4_HEADER_SIZE + 6, 0.toShort())
        udpHeader?.checksum = 0

        val ip4TotalLength = IP4_HEADER_SIZE + udpTotalLength
        backingBuffer.putShort(2, ip4TotalLength.toShort())
        ip4Header.totalLength = ip4TotalLength

        updateIP4Checksum()
        payloadSize = newPayloadSize
    }

    private fun fillHeader(buffer: ByteBuffer) {
        ip4Header.fillHeader(buffer)

        if (isUDP) {
            udpHeader?.fillHeader(buffer)
        } else if (isTCP) {
            tcpHeader?.fillHeader(buffer)
        }
    }

    private fun updateIP4Checksum() {
        val buffer = backingBuffer.duplicate()
        buffer.position(0)

        // Clear previous checksum
        buffer.putShort(10, 0.toShort())

        val ipLength = ip4Header.headerLength
        var sum = 0

        for (i in 0 until ipLength step 2) {
            sum += buffer.short.toInt() and 0xFFFF
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        sum = sum.inv()
        ip4Header.headerChecksum = sum
        backingBuffer.putShort(10, sum.toShort())
    }

    fun getIpAndPort(): String {
        val destPort = if (isUDP) udpHeader?.destinationPort else tcpHeader?.destinationPort
        val srcPort = if (isUDP) udpHeader?.sourcePort else tcpHeader?.sourcePort
        val proto = if (isUDP) "UDP" else "TCP"

        return "$proto:${ip4Header.destinationAddress?.hostAddress}:$destPort src=$srcPort"
    }

    /**
     * IP4 Header
     */
    class IP4Header {
        var version: Byte = 0
        var ihl: Byte = 0
        var headerLength: Int = 0
        var typeOfService: Short = 0
        var totalLength: Int = 0
        var identificationAndFlagsAndFragmentOffset: Int = 0
        var ttl: Short = 0
        private var protocolNum: Byte = 0
        var protocol: Byte = 0
        var headerChecksum: Int = 0
        var sourceAddress: InetAddress? = null
        var destinationAddress: InetAddress? = null

        constructor(buffer: ByteBuffer) {
            val versionAndIHL = buffer.get()
            version = (versionAndIHL.toInt() shr 4).toByte()
            ihl = (versionAndIHL.toInt() and 0x0F).toByte()
            headerLength = ihl.toInt() shl 2

            typeOfService = (buffer.get().toInt() and 0xFF).toShort()
            totalLength = buffer.short.toInt() and 0xFFFF

            identificationAndFlagsAndFragmentOffset = buffer.int

            ttl = (buffer.get().toInt() and 0xFF).toShort()
            protocolNum = buffer.get()
            protocol = protocolNum
            headerChecksum = buffer.short.toInt() and 0xFFFF

            val addressBytes = ByteArray(4)
            buffer.get(addressBytes)
            sourceAddress = InetAddress.getByAddress(addressBytes)

            buffer.get(addressBytes)
            destinationAddress = InetAddress.getByAddress(addressBytes)
        }

        private constructor()

        fun duplicate(): IP4Header {
            val header = IP4Header()
            header.version = version
            header.ihl = ihl
            header.headerLength = headerLength
            header.typeOfService = typeOfService
            header.totalLength = totalLength
            header.identificationAndFlagsAndFragmentOffset = identificationAndFlagsAndFragmentOffset
            header.ttl = ttl
            header.protocolNum = protocolNum
            header.protocol = protocol
            header.headerChecksum = headerChecksum
            header.sourceAddress = sourceAddress
            header.destinationAddress = destinationAddress
            return header
        }

        fun fillHeader(buffer: ByteBuffer) {
            buffer.put(((version.toInt() shl 4) or ihl.toInt()).toByte())
            buffer.put(typeOfService.toByte())
            buffer.putShort(totalLength.toShort())
            buffer.putInt(identificationAndFlagsAndFragmentOffset)
            buffer.put(ttl.toByte())
            buffer.put(protocol)
            buffer.putShort(headerChecksum.toShort())
            buffer.put(sourceAddress?.address)
            buffer.put(destinationAddress?.address)
        }

        override fun toString(): String {
            return "IP4Header{src=${sourceAddress?.hostAddress}, dst=${destinationAddress?.hostAddress}, proto=$protocol}"
        }
    }

    /**
     * TCP Header
     */
    class TCPHeader {
        var sourcePort: Int = 0
        var destinationPort: Int = 0
        var sequenceNumber: Long = 0
        var acknowledgementNumber: Long = 0
        var dataOffsetAndReserved: Byte = 0
        var headerLength: Int = 0
        var flags: Byte = 0
        var window: Int = 0
        var checksum: Int = 0
        var urgentPointer: Int = 0

        constructor(buffer: ByteBuffer) {
            sourcePort = buffer.short.toInt() and 0xFFFF
            destinationPort = buffer.short.toInt() and 0xFFFF
            sequenceNumber = buffer.int.toLong() and 0xFFFFFFFFL
            acknowledgementNumber = buffer.int.toLong() and 0xFFFFFFFFL
            dataOffsetAndReserved = buffer.get()
            headerLength = (dataOffsetAndReserved.toInt() and 0xF0) shr 2
            flags = buffer.get()
            window = buffer.short.toInt() and 0xFFFF
            checksum = buffer.short.toInt() and 0xFFFF
            urgentPointer = buffer.short.toInt() and 0xFFFF

            val optionsLength = headerLength - TCP_HEADER_SIZE
            if (optionsLength > 0) {
                buffer.position(buffer.position() + optionsLength)
            }
        }

        private constructor()

        fun duplicate(): TCPHeader {
            val header = TCPHeader()
            header.sourcePort = sourcePort
            header.destinationPort = destinationPort
            header.sequenceNumber = sequenceNumber
            header.acknowledgementNumber = acknowledgementNumber
            header.dataOffsetAndReserved = dataOffsetAndReserved
            header.headerLength = headerLength
            header.flags = flags
            header.window = window
            header.checksum = checksum
            header.urgentPointer = urgentPointer
            return header
        }

        fun fillHeader(buffer: ByteBuffer) {
            buffer.putShort(sourcePort.toShort())
            buffer.putShort(destinationPort.toShort())
            buffer.putInt(sequenceNumber.toInt())
            buffer.putInt(acknowledgementNumber.toInt())
            buffer.put(dataOffsetAndReserved)
            buffer.put(flags)
            buffer.putShort(window.toShort())
            buffer.putShort(checksum.toShort())
            buffer.putShort(urgentPointer.toShort())
        }

        companion object {
            const val FIN: Byte = 0x01
            const val SYN: Byte = 0x02
            const val RST: Byte = 0x04
            const val PSH: Byte = 0x08
            const val ACK: Byte = 0x10
        }
    }

    /**
     * UDP Header
     */
    class UDPHeader {
        var sourcePort: Int = 0
        var destinationPort: Int = 0
        var length: Int = 0
        var checksum: Int = 0

        constructor(buffer: ByteBuffer) {
            sourcePort = buffer.short.toInt() and 0xFFFF
            destinationPort = buffer.short.toInt() and 0xFFFF
            length = buffer.short.toInt() and 0xFFFF
            checksum = buffer.short.toInt() and 0xFFFF
        }

        private constructor()

        fun duplicate(): UDPHeader {
            val header = UDPHeader()
            header.sourcePort = sourcePort
            header.destinationPort = destinationPort
            header.length = length
            header.checksum = checksum
            return header
        }

        fun fillHeader(buffer: ByteBuffer) {
            buffer.putShort(sourcePort.toShort())
            buffer.putShort(destinationPort.toShort())
            buffer.putShort(length.toShort())
            buffer.putShort(checksum.toShort())
        }

        override fun toString(): String {
            return "UDP{$sourcePort -> $destinationPort, len=$length}"
        }
    }
}
