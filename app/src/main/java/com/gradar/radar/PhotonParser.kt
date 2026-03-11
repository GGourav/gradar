package com.gradar.radar

import android.util.Log
import java.nio.ByteBuffer

/**
 * Photon Protocol Parser
 * Parses Albion Online Photon packets for radar data
 */
object PhotonParser {
    private const val TAG = "PhotonParser"

    // Message types
    private const val MSG_TYPE_REQUEST = 2
    private const val MSG_TYPE_RESPONSE = 3
    private const val MSG_TYPE_EVENT = 4

    // Command types
    private const val CMD_SEND_RELIABLE = 6
    private const val CMD_SEND_UNRELIABLE = 7
    private const val CMD_DISCONNECT = 4

    // Event codes (from QRadar)
    object EventCodes {
        const val LEAVE = 1
        const val MOVE = 3
        const val NEW_CHARACTER = 33
        const val NEW_MOB = 123
        const val NEW_SIMPLE_HARVESTABLE_LIST = 41
        const val NEW_HARVESTABLE_OBJECT = 42
        const val NEW_TREASURE_CHEST = 117
        const val NEW_FISHING_ZONE = 350
        const val NEW_FLOAT_OBJECT = 349
        const val HARVESTABLE_CHANGE_STATE = 48
        const val MOB_CHANGE_STATE = 49
        const val MOUNTED = 206
        const val HEALTH_UPDATE = 7
        const val ATTACK = 18
    }

    // Operation codes
    object OpCodes {
        const val MOVE = 0
        const val CHANGE_CLUSTER = 7
        const val JOIN = 255
    }

    /**
     * Parse a Photon packet
     */
    fun parse(data: ByteArray) {
        if (data.size < 12) return

        try {
            val buffer = ByteBuffer.wrap(data)

            // Read Photon header
            val peerId = buffer.short.toInt() and 0xFFFF
            val crcEnabled = buffer.get().toInt() and 0xFF
            val commandCount = buffer.get().toInt() and 0xFF
            val timestamp = buffer.int
            val challenge = buffer.int

            // Parse commands
            for (i in 0 until commandCount) {
                val commandType = buffer.get().toInt() and 0xFF
                val channelId = buffer.get().toInt() and 0xFF
                val commandFlags = buffer.get().toInt() and 0xFF
                val unkBytes = buffer.get().toInt() and 0xFF
                val commandLength = buffer.int
                val sequenceNumber = buffer.int

                when (commandType) {
                    CMD_DISCONNECT -> {
                        // Handle disconnect
                    }

                    CMD_SEND_UNRELIABLE -> {
                        buffer.position(buffer.position() + 4)
                        parsePayload(buffer, commandLength - 12 - 4)
                    }

                    CMD_SEND_RELIABLE -> {
                        parsePayload(buffer, commandLength - 12 - 1)
                    }

                    else -> {
                        // Skip unknown command
                        buffer.position(buffer.position() + commandLength - 12)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Parse error: ${e.message}")
        }
    }

    private fun parsePayload(buffer: ByteBuffer, payloadLength: Int) {
        if (payloadLength < 2) return

        val messageType = buffer.get().toInt() and 0xFF

        val tempBytes = ByteArray(payloadLength - 1)
        buffer.get(tempBytes)
        val payload = ByteBuffer.wrap(tempBytes)

        when (messageType) {
            MSG_TYPE_EVENT -> {
                val eventCode = payload.get().toInt() and 0xFF
                val params = deserializeParams(payload)
                onEvent(eventCode, params)
            }

            MSG_TYPE_REQUEST -> {
                val opCode = payload.get().toInt() and 0xFF
                val params = deserializeParams(payload)
                onRequest(opCode, params)
            }

            MSG_TYPE_RESPONSE -> {
                val opCode = payload.get().toInt() and 0xFF
                val params = deserializeParams(payload)
                onResponse(opCode, params)
            }
        }
    }

    /**
     * Deserialize Protocol16 parameters
     */
    private fun deserializeParams(buffer: ByteBuffer): Map<Int, Any> {
        val params = mutableMapOf<Int, Any>()

        try {
            val paramCount = buffer.short.toInt() and 0xFFFF

            for (i in 0 until paramCount) {
                val key = buffer.get().toInt() and 0xFF
                val value = deserializeValue(buffer)
                params[key] = value
            }
        } catch (e: Exception) {
            Log.d(TAG, "Deserialize error: ${e.message}")
        }

        return params
    }

    private fun deserializeValue(buffer: ByteBuffer): Any {
        val type = buffer.get().toInt() and 0xFF

        return when (type) {
            0 -> { // Dictionary
                val keyType = buffer.get().toInt() and 0xFF
                val valueType = buffer.get().toInt() and 0xFF
                val count = buffer.short.toInt() and 0xFFFF
                val dict = mutableMapOf<Any, Any>()
                for (i in 0 until count) {
                    val key = deserializeValueOfType(buffer, keyType)
                    val value = deserializeValueOfType(buffer, valueType)
                    dict[key] = value
                }
                dict
            }
            1 -> { // String
                val length = buffer.short.toInt() and 0xFFFF
                val bytes = ByteArray(length)
                buffer.get(bytes)
                String(bytes, Charsets.UTF_8)
            }
            2 -> { // Integer
                buffer.int
            }
            3 -> { // Integer array
                val count = buffer.int
                val arr = IntArray(count)
                for (i in 0 until count) {
                    arr[i] = buffer.int
                }
                arr
            }
            4 -> { // Short
                buffer.short.toInt() and 0xFFFF
            }
            5 -> { // Long
                buffer.long
            }
            6 -> { // Float
                buffer.float
            }
            7 -> { // Double
                buffer.double
            }
            8 -> { // Boolean true
                true
            }
            9 -> { // Boolean false
                false
            }
            10 -> { // Null
                Unit
            }
            11 -> { // Byte
                buffer.get().toInt() and 0xFF
            }
            12 -> { // ByteArray
                val length = buffer.int
                val bytes = ByteArray(length)
                buffer.get(bytes)
                bytes
            }
            13 -> { // Short array
                val count = buffer.int
                val arr = ShortArray(count)
                for (i in 0 until count) {
                    arr[i] = buffer.short
                }
                arr
            }
            14 -> { // Float array
                val count = buffer.int
                val arr = FloatArray(count)
                for (i in 0 until count) {
                    arr[i] = buffer.float
                }
                arr
            }
            15 -> { // String array
                val count = buffer.int
                val arr = Array(count) { "" }
                for (i in 0 until count) {
                    val len = buffer.short.toInt() and 0xFFFF
                    val bytes = ByteArray(len)
                    buffer.get(bytes)
                    arr[i] = String(bytes, Charsets.UTF_8)
                }
                arr
            }
            16 -> { // Object array
                val count = buffer.int
                val arr = Array(count) { Any() }
                for (i in 0 until count) {
                    arr[i] = deserializeValue(buffer)
                }
                arr
            }
            17 -> { // Long array
                val count = buffer.int
                val arr = LongArray(count)
                for (i in 0 until count) {
                    arr[i] = buffer.long
                }
                arr
            }
            18 -> { // Custom type
                val customType = buffer.get().toInt() and 0xFF
                deserializeCustomType(buffer, customType)
            }
            else -> {
                Log.d(TAG, "Unknown type: $type")
                Unit
            }
        }
    }

    private fun deserializeValueOfType(buffer: ByteBuffer, type: Int): Any {
        return when (type) {
            2 -> buffer.int
            4 -> buffer.short.toInt() and 0xFFFF
            5 -> buffer.long
            6 -> buffer.float
            11 -> buffer.get().toInt() and 0xFF
            else -> deserializeValue(buffer)
        }
    }

    private fun deserializeCustomType(buffer: ByteBuffer, customType: Int): Any {
        val length = buffer.get().toInt() and 0xFF
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun onEvent(code: Int, params: Map<Int, Any>) {
        try {
            val eventCode = (params[252] as? Number)?.toInt() ?: code

            when (eventCode) {
                EventCodes.MOVE -> handleMoveEvent(params)
                EventCodes.LEAVE -> handleLeaveEvent(params)
                EventCodes.NEW_CHARACTER -> handleNewCharacter(params)
                EventCodes.NEW_MOB -> handleNewMob(params)
                EventCodes.NEW_SIMPLE_HARVESTABLE_LIST -> handleNewHarvestableList(params)
                EventCodes.NEW_HARVESTABLE_OBJECT -> handleNewHarvestableObject(params)
                EventCodes.NEW_TREASURE_CHEST -> handleNewTreasureChest(params)
                EventCodes.NEW_FISHING_ZONE -> handleNewFishingZone(params)
                EventCodes.NEW_FLOAT_OBJECT -> handleNewFloatObject(params)
                EventCodes.HARVESTABLE_CHANGE_STATE -> handleHarvestableChangeState(params)
                EventCodes.MOUNTED -> handleMounted(params)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Event error: ${e.message}")
        }
    }

    private fun onRequest(code: Int, params: Map<Int, Any>) {
        try {
            val opCode = (params[253] as? Number)?.toInt() ?: code

            when (opCode) {
                OpCodes.MOVE -> handleMoveRequest(params)
                OpCodes.CHANGE_CLUSTER -> EntityProcessor.clearAll()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Request error: ${e.message}")
        }
    }

    private fun onResponse(code: Int, params: Map<Int, Any>) {
        try {
            val opCode = (params[253] as? Number)?.toInt() ?: code

            when (opCode) {
                OpCodes.JOIN -> handleJoinResponse(params)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Response error: ${e.message}")
        }
    }

    // Event handlers

    private fun handleMoveEvent(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val bytes = params[1] as? ByteArray ?: return

        if (bytes.size >= 17) {
            val posX = Float.fromBits(
                ((bytes[9].toInt() and 0xFF)) or
                ((bytes[10].toInt() and 0xFF) shl 8) or
                ((bytes[11].toInt() and 0xFF) shl 16) or
                ((bytes[12].toInt() and 0xFF) shl 24)
            )
            val posY = Float.fromBits(
                ((bytes[13].toInt() and 0xFF)) or
                ((bytes[14].toInt() and 0xFF) shl 8) or
                ((bytes[15].toInt() and 0xFF) shl 16) or
                ((bytes[16].toInt() and 0xFF) shl 24)
            )

            EntityProcessor.updatePosition(id, posX, posY)
        }
    }

    private fun handleLeaveEvent(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        EntityProcessor.removeEntity(id)
    }

    private fun handleNewCharacter(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val name = params[1] as? String ?: return
        val guildName = params[8] as? String ?: ""
        val allianceName = params[27] as? String ?: ""

        val bytes = params[3] as? ByteArray ?: return
        if (bytes.size >= 17) {
            val posX = Float.fromBits(
                ((bytes[9].toInt() and 0xFF)) or
                ((bytes[10].toInt() and 0xFF) shl 8) or
                ((bytes[11].toInt() and 0xFF) shl 16) or
                ((bytes[12].toInt() and 0xFF) shl 24)
            )
            val posY = Float.fromBits(
                ((bytes[13].toInt() and 0xFF)) or
                ((bytes[14].toInt() and 0xFF) shl 8) or
                ((bytes[15].toInt() and 0xFF) shl 16) or
                ((bytes[16].toInt() and 0xFF) shl 24)
            )

            EntityProcessor.addPlayer(id, name, guildName, allianceName, posX, posY)
        }
    }

    private fun handleNewMob(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val type = (params[1] as? Number)?.toInt() ?: 0

        val bytes = params[5] as? ByteArray ?: return
        if (bytes.size >= 17) {
            val posX = Float.fromBits(
                ((bytes[9].toInt() and 0xFF)) or
                ((bytes[10].toInt() and 0xFF) shl 8) or
                ((bytes[11].toInt() and 0xFF) shl 16) or
                ((bytes[12].toInt() and 0xFF) shl 24)
            )
            val posY = Float.fromBits(
                ((bytes[13].toInt() and 0xFF)) or
                ((bytes[14].toInt() and 0xFF) shl 8) or
                ((bytes[15].toInt() and 0xFF) shl 16) or
                ((bytes[16].toInt() and 0xFF) shl 24)
            )

            EntityProcessor.addMob(id, type, posX, posY)
        }
    }

    private fun handleNewHarvestableList(params: Map<Int, Any>) {
        val harvestables = params[0] as? Array<*> ?: return
        for (item in harvestables) {
            val harvestable = item as? Map<*, *> ?: continue
            parseHarvestable(harvestable)
        }
    }

    private fun handleNewHarvestableObject(params: Map<Int, Any>) {
        parseHarvestable(params)
    }

    private fun parseHarvestable(params: Map<*, *>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val type = (params[1] as? Number)?.toInt() ?: 0
        val tier = (params[4] as? Number)?.toInt() ?: 1
        val enchant = (params[5] as? Number)?.toInt() ?: 0

        val posX = (params[6] as? Number)?.toFloat() ?: 0f
        val posY = (params[7] as? Number)?.toFloat() ?: 0f

        EntityProcessor.addHarvestable(id, type, tier, enchant, posX, posY)
    }

    private fun handleNewTreasureChest(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val type = (params[1] as? Number)?.toInt() ?: 0

        val posX = (params[6] as? Number)?.toFloat() ?: 0f
        val posY = (params[7] as? Number)?.toFloat() ?: 0f

        EntityProcessor.addChest(id, type, posX, posY)
    }

    private fun handleNewFishingZone(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return

        val posX = (params[6] as? Number)?.toFloat() ?: 0f
        val posY = (params[7] as? Number)?.toFloat() ?: 0f

        EntityProcessor.addFishingZone(id, posX, posY)
    }

    private fun handleNewFloatObject(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val type = (params[1] as? Number)?.toInt() ?: 0

        val posX = (params[6] as? Number)?.toFloat() ?: 0f
        val posY = (params[7] as? Number)?.toFloat() ?: 0f

        EntityProcessor.addFloatObject(id, type, posX, posY)
    }

    private fun handleHarvestableChangeState(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val state = (params[1] as? Number)?.toInt() ?: 0

        if (state == 0) {
            EntityProcessor.removeEntity(id)
        }
    }

    private fun handleMounted(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val mountId = (params[1] as? Number)?.toInt() ?: -1

        EntityProcessor.setMounted(id, mountId != -1)
    }

    private fun handleMoveRequest(params: Map<Int, Any>) {
        val posArray = params[1] as? FloatArray ?: return
        if (posArray.size >= 2) {
            EntityProcessor.updateLocalPlayerPosition(posArray[0], posArray[1])
        }
    }

    private fun handleJoinResponse(params: Map<Int, Any>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val name = params[1] as? String ?: "Local"

        EntityProcessor.setLocalPlayer(id, name)
    }
}
