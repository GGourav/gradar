package com.gradar.handler

import android.util.Log
import com.gradar.model.GameEntity
import com.gradar.protocol.PhotonParser
import com.gradar.protocol.PhotonProtocol

/**
 * Handles all game events from parsed packets
 */
class EventHandler {

    companion object {
        private const val TAG = "EventHandler"
    }

    private val entities = mutableMapOf<Long, GameEntity>()
    private var playerX: Float = 0f
    private var playerY: Float = 0f
    private var playerId: Long = 0

    fun processEvent(event: PhotonParser.GameEvent): GameEntity? {
        val eventCode = event.eventCode
        val params = event.parameters

        return when (eventCode) {
            PhotonProtocol.EventCode.NEW_CHARACTER -> handleNewCharacter(params)
            PhotonProtocol.EventCode.NEW_MOB -> handleNewMob(params)
            PhotonProtocol.EventCode.NEW_RESOURCE -> handleNewResource(params)
            PhotonProtocol.EventCode.NEW_SIMPLE_HARVESTABLE -> handleHarvestable(params)
            PhotonProtocol.EventCode.NEW_MIST -> handleMist(params)
            PhotonProtocol.EventCode.NEW_DUNGEON -> handleDungeon(params)
            PhotonProtocol.EventCode.NEW_CHEST -> handleChest(params)
            PhotonProtocol.EventCode.NEW_FISHING -> handleFishing(params)
            PhotonProtocol.EventCode.MOVE -> handleMove(params)
            PhotonProtocol.EventCode.HEALTH_UPDATE -> handleHealthUpdate(params)
            PhotonProtocol.EventCode.LEAVE -> handleLeave(params)
            PhotonProtocol.EventCode.DEATH -> handleDeath(params)
            PhotonProtocol.EventCode.PLAYER_APPEAR -> handlePlayerAppear(params)
            else -> {
                Log.v(TAG, "Unhandled event: $eventCode")
                null
            }
        }
    }

    private fun handleNewCharacter(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            entityType = PhotonProtocol.EntityType.PLAYER,
            playerName = extractString(params, PhotonProtocol.ParameterKey.PLAYER_NAME),
            guildName = extractString(params, PhotonProtocol.ParameterKey.GUILD_NAME),
            alliance = extractString(params, PhotonProtocol.ParameterKey.ALLIANCE),
            faction = extractInt(params, PhotonProtocol.ParameterKey.FACTION) ?: 0
        )
        
        entities[id] = entity
        Log.d(TAG, "Player: ${entity.playerName ?: entity.uniqueName} at (${entity.posX}, ${entity.posY})")
        return entity
    }

    private fun handleNewMob(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            tier = extractInt(params, PhotonProtocol.ParameterKey.TIER) ?: 0,
            enchantment = extractInt(params, PhotonProtocol.ParameterKey.ENCHANTMENT) ?: 0,
            health = extractInt(params, PhotonProtocol.ParameterKey.HEALTH) ?: 100,
            maxHealth = extractInt(params, PhotonProtocol.ParameterKey.MAX_HEALTH) ?: 100,
            entityType = PhotonProtocol.EntityType.MOB
        )
        
        entities[id] = entity
        Log.d(TAG, "Mob: ${entity.uniqueName} ${entity.getTierString()} ${if (entity.isBoss()) "[BOSS]" else ""}")
        return entity
    }

    private fun handleNewResource(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            tier = extractInt(params, PhotonProtocol.ParameterKey.TIER) ?: 0,
            enchantment = extractInt(params, PhotonProtocol.ParameterKey.ENCHANTMENT) ?: 0,
            entityType = PhotonProtocol.EntityType.RESOURCE
        )
        
        entities[id] = entity
        Log.d(TAG, "Resource: ${entity.getResourceType()} ${entity.getTierString()}")
        return entity
    }

    private fun handleHarvestable(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            tier = extractInt(params, PhotonProtocol.ParameterKey.TIER) ?: 0,
            enchantment = extractInt(params, PhotonProtocol.ParameterKey.ENCHANTMENT) ?: 0,
            entityType = PhotonProtocol.EntityType.HARVESTABLE
        )
        
        entities[id] = entity
        return entity
    }

    private fun handleMist(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            rarity = extractInt(params, PhotonProtocol.ParameterKey.RARITY) ?: 0,
            entityType = PhotonProtocol.EntityType.MIST
        )
        
        entities[id] = entity
        Log.d(TAG, "Mist: rarity ${entity.rarity}")
        return entity
    }

    private fun handleDungeon(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            entityType = PhotonProtocol.EntityType.DUNGEON
        )
        
        entities[id] = entity
        return entity
    }

    private fun handleChest(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            tier = extractInt(params, PhotonProtocol.ParameterKey.TIER) ?: 0,
            entityType = PhotonProtocol.EntityType.CHEST
        )
        
        entities[id] = entity
        return entity
    }

    private fun handleFishing(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            tier = extractInt(params, PhotonProtocol.ParameterKey.TIER) ?: 0,
            entityType = PhotonProtocol.EntityType.FISHING
        )
        
        entities[id] = entity
        return entity
    }

    private fun handleMove(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = entities[id] ?: return null
        
        entity.posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: entity.posX
        entity.posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: entity.posY
        entity.lastUpdate = System.currentTimeMillis()
        
        return entity
    }

    private fun handleHealthUpdate(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = entities[id] ?: return null
        
        entity.health = extractInt(params, PhotonProtocol.ParameterKey.HEALTH) ?: entity.health
        entity.maxHealth = extractInt(params, PhotonProtocol.ParameterKey.MAX_HEALTH) ?: entity.maxHealth
        entity.lastUpdate = System.currentTimeMillis()
        
        return entity
    }

    private fun handleLeave(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        return entities.remove(id)
    }

    private fun handleDeath(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        return entities.remove(id)
    }

    private fun handlePlayerAppear(params: Map<Int, Any>): GameEntity? {
        val id = extractLong(params, PhotonProtocol.ParameterKey.PLAYER_ID) ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = extractInt(params, PhotonProtocol.ParameterKey.TYPE_ID) ?: 0,
            uniqueName = extractString(params, PhotonProtocol.ParameterKey.UNIQUE_NAME),
            posX = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_X) ?: 0f,
            posY = extractFloat(params, PhotonProtocol.ParameterKey.POSITION_Y) ?: 0f,
            entityType = PhotonProtocol.EntityType.PLAYER,
            playerName = extractString(params, PhotonProtocol.ParameterKey.PLAYER_NAME),
            guildName = extractString(params, PhotonProtocol.ParameterKey.GUILD_NAME)
        )
        
        entities[id] = entity
        return entity
    }

    // Helper functions
    private fun extractLong(params: Map<Int, Any>, key: Int): Long? {
        return (params[key] as? Number)?.toLong()
    }

    private fun extractInt(params: Map<Int, Any>, key: Int): Int? {
        return (params[key] as? Number)?.toInt()
    }

    private fun extractFloat(params: Map<Int, Any>, key: Int): Float? {
        return (params[key] as? Number)?.toFloat()
    }

    private fun extractString(params: Map<Int, Any>, key: Int): String? {
        return params[key] as? String
    }

    fun getEntities(): Map<Long, GameEntity> = entities.toMap()

    fun getEntitiesInRange(range: Float): List<GameEntity> {
        return entities.values.filter { entity ->
            val dx = entity.posX - playerX
            val dy = entity.posY - playerY
            (dx * dx + dy * dy) <= range * range
        }
    }

    fun clear() {
        entities.clear()
    }

    fun setPlayerPosition(x: Float, y: Float) {
        playerX = x
        playerY = y
    }
    
    fun setPlayerId(id: Long) {
        playerId = id
    }
}
