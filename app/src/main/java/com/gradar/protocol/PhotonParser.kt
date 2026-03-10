package com.gradar.protocol

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photon Protocol Parser
 * Parses incoming UDP packets from Albion Online servers
 */
class PhotonParser {

    companion object {
        private const val TAG = "PhotonParser"
    }

    data class PhotonPacket(
        val peerId: Int,
        val flags: Int,
        val commandCount: Int,
        val timestamp: Int,
        val commands: List<PhotonCommand>
    )

    data class PhotonCommand(
        val commandType: Int,
        val channelId: Int,
        val sequence: Int,
        val data: ByteArray
    )

    data class GameEvent(
        val eventCode: Int,
        val parameters: Map<Int, Any>
    )

    /**
     * Parse raw UDP packet into Photon packet structure
     */
    fun parsePacket(data: ByteArray, length: Int): PhotonPacket? {
        if (length < PhotonProtocol.HEADER_SIZE) {
            return null
        }

        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)

        val peerId = buffer.short.toInt() and 0xFFFF
        val flags = buffer.get().toInt() and 0xFF
        val commandCount = buffer.get().toInt() and 0xFF
        val timestamp = buffer.int
        val challenge = buffer.int

        val commands = mutableListOf<PhotonCommand>()

        for (i in 0 until commandCount) {
            val command = parseCommand(buffer) ?: continue
            commands.add(command)
        }

        return PhotonPacket(peerId, flags, commandCount, timestamp, commands)
    }

    private fun parseCommand(buffer: ByteBuffer): PhotonCommand? {
        if (buffer.remaining() < 4) return null

        val commandType = buffer.get().toInt() and 0xFF
        val channelId = buffer.get().toInt() and 0xFF
        buffer.get() // reserved
        val dataLength = buffer.get().toInt() and 0xFF
        
        // Handle extended length
        val actualLength = if (dataLength == 255 && buffer.remaining() >= 4) {
            buffer.int
        } else {
            dataLength
        }

        if (buffer.remaining() < actualLength) return null

        val data = ByteArray(actualLength)
        buffer.get(data)

        return PhotonCommand(commandType, channelId, 0, data)
    }

    /**
     * Parse game event from command data
     */
    fun parseEvent(commandData: ByteArray): GameEvent? {
        if (commandData.size < 2) return null

        val buffer = ByteBuffer.wrap(commandData).order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            val eventCode = buffer.get().toInt() and 0xFF
            val parameters = parseParameters(buffer)
            
            return GameEvent(eventCode, parameters)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse event: ${e.message}")
            return null
        }
    }

    /**
     * Parse Photon parameter dictionary
     */
    private fun parseParameters(buffer: ByteBuffer): Map<Int, Any> {
        val parameters = mutableMapOf<Int, Any>()
        
        if (buffer.remaining() < 2) return parameters
        
        val count = buffer.short.toInt() and 0xFFFF
        
        for (i in 0 until count) {
            if (buffer.remaining() < 2) break
            
            val key = buffer.short.toInt()
            val value = parseValue(buffer) ?: continue
            parameters[key] = value
        }
        
        return parameters
    }

    private fun parseValue(buffer: ByteBuffer): Any? {
        if (!buffer.hasRemaining()) return null
        
        val typeCode = buffer.get().toInt() and 0xFF
        
        return when (typeCode) {
            PhotonProtocol.TypeCode.NULL -> null
            PhotonProtocol.TypeCode.INTEGER -> buffer.int
            PhotonProtocol.TypeCode.LONG -> buffer.long
            PhotonProtocol.TypeCode.DOUBLE -> buffer.double
            PhotonProtocol.TypeCode.BOOLEAN -> buffer.get() != 0.toByte()
            PhotonProtocol.TypeCode.STRING -> {
                val length = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() >= length) {
                    val bytes = ByteArray(length)
                    buffer.get(bytes)
                    String(bytes, Charsets.UTF_8)
                } else null
            }
            PhotonProtocol.TypeCode.BYTE_ARRAY -> {
                val length = buffer.int
                if (buffer.remaining() >= length) {
                    val bytes = ByteArray(length)
                    buffer.get(bytes)
                    bytes
                } else null
            }
            PhotonProtocol.TypeCode.INTEGER_ARRAY -> {
                val count = buffer.int
                val array = IntArray(count)
                for (i in 0 until count) {
                    if (buffer.hasRemaining()) array[i] = buffer.int
                }
                array
            }
            PhotonProtocol.TypeCode.DICTIONARY -> {
                val count = buffer.short.toInt() and 0xFFFF
                val dict = mutableMapOf<Any, Any>()
                for (i in 0 until count) {
                    val key = parseValue(buffer) ?: continue
                    val value = parseValue(buffer) ?: continue
                    dict[key] = value
                }
                dict
            }
            else -> {
                Log.w(TAG, "Unknown type code: $typeCode")
                null
            }
        }
    }
}
