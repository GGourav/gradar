package com.gradar.radar

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Entity Processor - Manages all radar entities
 * Thread-safe singleton for entity tracking
 */
object EntityProcessor {
    private const val TAG = "EntityProcessor"

    // Entity types
    object EntityType {
        const val PLAYER = 0
        const val MOB = 1
        const val HARVESTABLE = 2
        const val CHEST = 3
        const val FISHING_ZONE = 4
        const val FLOAT_OBJECT = 5
    }

    // Entity data class
    data class Entity(
        val id: Int,
        val type: Int,
        var posX: Float = 0f,
        var posY: Float = 0f,
        var name: String = "",
        var guildName: String = "",
        var allianceName: String = "",
        var subType: Int = 0,
        var tier: Int = 1,
        var enchant: Int = 0,
        var isMounted: Boolean = false,
        var lastUpdate: Long = System.currentTimeMillis()
    )

    // Entity storage
    private val entities = ConcurrentHashMap<Int, Entity>()

    // Local player info
    private var localPlayerId: Int = -1
    private var localPlayerName: String = ""
    private var localPlayerX: Float = 0f
    private var localPlayerY: Float = 0f

    // Statistics
    private var packetCount: Long = 0
    private var lastPacketTime: Long = 0

    /**
     * Process incoming Photon data
     */
    fun processPhotonData(data: ByteArray) {
        packetCount++
        lastPacketTime = System.currentTimeMillis()
        PhotonParser.parse(data)
    }

    /**
     * Get all entities
     */
    fun getEntities(): List<Entity> = entities.values.toList()

    /**
     * Get entity count
     */
    fun getEntityCount(): Int = entities.size

    /**
     * Get entities by type
     */
    fun getEntitiesByType(type: Int): List<Entity> =
        entities.values.filter { it.type == type }

    /**
     * Get statistics
     */
    fun getStats(): String {
        val players = getEntitiesByType(EntityType.PLAYER).size
        val mobs = getEntitiesByType(EntityType.MOB).size
        val harvestables = getEntitiesByType(EntityType.HARVESTABLE).size
        val chests = getEntitiesByType(EntityType.CHEST).size
        val fishing = getEntitiesByType(EntityType.FISHING_ZONE).size

        return "Players: $players, Mobs: $mobs, Resources: $harvestables, Chests: $chests, Fishing: $fishing"
    }

    /**
     * Get packet statistics
     */
    fun getPacketStats(): String {
        return "Packets: $packetCount, Last: ${System.currentTimeMillis() - lastPacketTime}ms ago"
    }

    // Local player methods

    fun setLocalPlayer(id: Int, name: String) {
        localPlayerId = id
        localPlayerName = name
        Log.d(TAG, "Local player: $name ($id)")
    }

    fun updateLocalPlayerPosition(x: Float, y: Float) {
        localPlayerX = x
        localPlayerY = y
    }

    fun getLocalPlayerPosition(): Pair<Float, Float> = Pair(localPlayerX, localPlayerY)

    // Position update (move event)

    fun updatePosition(id: Int, x: Float, y: Float) {
        entities[id]?.let {
            it.posX = x
            it.posY = y
            it.lastUpdate = System.currentTimeMillis()
        }
    }

    // Add player

    fun addPlayer(id: Int, name: String, guild: String, alliance: String, x: Float, y: Float) {
        if (id == localPlayerId) return // Skip local player

        entities[id] = Entity(
            id = id,
            type = EntityType.PLAYER,
            name = name,
            guildName = guild,
            allianceName = alliance,
            posX = x,
            posY = y
        )
        Log.d(TAG, "Added player: $name at ($x, $y)")
    }

    // Add mob

    fun addMob(id: Int, mobType: Int, x: Float, y: Float) {
        entities[id] = Entity(
            id = id,
            type = EntityType.MOB,
            subType = mobType,
            posX = x,
            posY = y
        )
        Log.d(TAG, "Added mob: $mobType at ($x, $y)")
    }

    // Add harvestable (resource node)

    fun addHarvestable(id: Int, resType: Int, tier: Int, enchant: Int, x: Float, y: Float) {
        entities[id] = Entity(
            id = id,
            type = EntityType.HARVESTABLE,
            subType = resType,
            tier = tier,
            enchant = enchant,
            posX = x,
            posY = y
        )
        Log.d(TAG, "Added harvestable: T$tier.$enchant at ($x, $y)")
    }

    // Add chest

    fun addChest(id: Int, chestType: Int, x: Float, y: Float) {
        entities[id] = Entity(
            id = id,
            type = EntityType.CHEST,
            subType = chestType,
            posX = x,
            posY = y
        )
        Log.d(TAG, "Added chest at ($x, $y)")
    }

    // Add fishing zone

    fun addFishingZone(id: Int, x: Float, y: Float) {
        entities[id] = Entity(
            id = id,
            type = EntityType.FISHING_ZONE,
            posX = x,
            posY = y
        )
    }

    // Add float object (salvage, etc)

    fun addFloatObject(id: Int, objType: Int, x: Float, y: Float) {
        entities[id] = Entity(
            id = id,
            type = EntityType.FLOAT_OBJECT,
            subType = objType,
            posX = x,
            posY = y
        )
    }

    // Set mounted state

    fun setMounted(id: Int, mounted: Boolean) {
        entities[id]?.let {
            it.isMounted = mounted
        }
    }

    // Remove entity

    fun removeEntity(id: Int) {
        entities.remove(id)
        Log.d(TAG, "Removed entity: $id")
    }

    // Clear all entities

    fun clearAll() {
        entities.clear()
        Log.d(TAG, "Cleared all entities")
    }

    // Remove entities out of range

    fun removeNotInRange(centerX: Float, centerY: Float, range: Float) {
        val iterator = entities.entries.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next().value
            val dx = entity.posX - centerX
            val dy = entity.posY - centerY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            if (distance > range) {
                iterator.remove()
            }
        }
    }

    /**
     * Get entities within range
     */
    fun getEntitiesInRange(x: Float, y: Float, range: Float): List<Entity> {
        return entities.values.filter {
            val dx = it.posX - x
            val dy = it.posY - y
            kotlin.math.sqrt(dx * dx + dy * dy) <= range
        }
    }
}
