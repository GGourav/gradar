package com.gradar.handler

import android.util.Log
import com.gradar.model.GameEntity
import com.gradar.protocol.PhotonProtocol

/**
 * Handles game events from parsed packets
 */
class EventHandler {

    companion object {
        private const val TAG = "EventHandler"
    }

    // Current tracked entities
    private val entities = mutableMapOf<Long, GameEntity>()
    
    // Player position reference
    private var playerX: Float = 0f
    private var playerY: Float = 0f

    /**
     * Process a game event
     */
    fun processEvent(event: PhotonParser.GameEvent): GameEntity? {
        val eventCode = event.eventCode
        val params = event.parameters

        return when (eventCode) {
            PhotonProtocol.EventCode.NEW_CHARACTER -> handleNewCharacter(params)
            PhotonProtocol.EventCode.NEW_MOB -> handleNewMob(params)
            PhotonProtocol.EventCode.NEW_RESOURCE -> handleNewResource(params)
            PhotonProtocol.EventCode.NEW_SIMPLE_HARVESTABLE -> handleHarvestable(params)
            PhotonProtocol.EventCode.NEW_MIST -> handleMist(params)
            PhotonProtocol.EventCode.MOVE -> handleMove(params)
            PhotonProtocol.EventCode.HEALTH_UPDATE -> handleHealthUpdate(params)
            PhotonProtocol.EventCode.LEAVE -> handleLeave(params)
            PhotonProtocol.EventCode.DEATH -> handleDeath(params)
            else -> {
                Log.v(TAG, "Unhandled event code: $eventCode")
                null
            }
        }
    }

    private fun handleNewCharacter(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = (params[PhotonProtocol.ParameterKey.TYPE_ID] as? Number)?.toInt() ?: 0,
            uniqueName = params[PhotonProtocol.ParameterKey.UNIQUE_NAME] as? String,
            posX = (params[PhotonProtocol.ParameterKey.POSITION_X] as? Number)?.toFloat() ?: 0f,
            posY = (params[PhotonProtocol.ParameterKey.POSITION_Y] as? Number)?.toFloat() ?: 0f,
            entityType = PhotonProtocol.EntityType.PLAYER
        )
        
        entities[id] = entity
        Log.d(TAG, "New player: ${entity.uniqueName} at (${entity.posX}, ${entity.posY})")
        return entity
    }

    private fun handleNewMob(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = (params[PhotonProtocol.ParameterKey.TYPE_ID] as? Number)?.toInt() ?: 0,
            uniqueName = params[PhotonProtocol.ParameterKey.UNIQUE_NAME] as? String,
            posX = (params[PhotonProtocol.ParameterKey.POSITION_X] as? Number)?.toFloat() ?: 0f,
            posY = (params[PhotonProtocol.ParameterKey.POSITION_Y] as? Number)?.toFloat() ?: 0f,
            tier = (params[PhotonProtocol.ParameterKey.TIER] as? Number)?.toInt() ?: 0,
            enchantment = (params[PhotonProtocol.ParameterKey.ENCHANTMENT] as? Number)?.toInt() ?: 0,
            entityType = PhotonProtocol.EntityType.MOB
        )
        
        entities[id] = entity
        Log.d(TAG, "New mob: ${entity.uniqueName} ${entity.getTierString()} at (${entity.posX}, ${entity.posY})")
        return entity
    }

    private fun handleNewResource(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = (params[PhotonProtocol.ParameterKey.TYPE_ID] as? Number)?.toInt() ?: 0,
            uniqueName = params[PhotonProtocol.ParameterKey.UNIQUE_NAME] as? String,
            posX = (params[PhotonProtocol.ParameterKey.POSITION_X] as? Number)?.toFloat() ?: 0f,
            posY = (params[PhotonProtocol.ParameterKey.POSITION_Y] as? Number)?.toFloat() ?: 0f,
            tier = (params[PhotonProtocol.ParameterKey.TIER] as? Number)?.toInt() ?: 0,
            enchantment = (params[PhotonProtocol.ParameterKey.ENCHANTMENT] as? Number)?.toInt() ?: 0,
            entityType = PhotonProtocol.EntityType.RESOURCE
        )
        
        entities[id] = entity
        Log.d(TAG, "New resource: ${entity.uniqueName} ${entity.getTierString()} at (${entity.posX}, ${entity.posY})")
        return entity
    }

    private fun handleHarvestable(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = (params[PhotonProtocol.ParameterKey.TYPE_ID] as? Number)?.toInt() ?: 0,
            uniqueName = params[PhotonProtocol.ParameterKey.UNIQUE_NAME] as? String,
            posX = (params[PhotonProtocol.ParameterKey.POSITION_X] as? Number)?.toFloat() ?: 0f,
            posY = (params[PhotonProtocol.ParameterKey.POSITION_Y] as? Number)?.toFloat() ?: 0f,
            tier = (params[PhotonProtocol.ParameterKey.TIER] as? Number)?.toInt() ?: 0,
            enchantment = (params[PhotonProtocol.ParameterKey.ENCHANTMENT] as? Number)?.toInt() ?: 0,
            entityType = PhotonProtocol.EntityType.HARVESTABLE
        )
        
        entities[id] = entity
        return entity
    }

    private fun handleMist(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = GameEntity(
            id = id,
            typeId = (params[PhotonProtocol.ParameterKey.TYPE_ID] as? Number)?.toInt() ?: 0,
            uniqueName = params[PhotonProtocol.ParameterKey.UNIQUE_NAME] as? String,
            posX = (params[PhotonProtocol.ParameterKey.POSITION_X] as? Number)?.toFloat() ?: 0f,
            posY = (params[PhotonProtocol.ParameterKey.POSITION_Y] as? Number)?.toFloat() ?: 0f,
            entityType = PhotonProtocol.EntityType.MIST
        )
        
        entities[id] = entity
        Log.d(TAG, "New mist: ${entity.uniqueName} at (${entity.posX}, ${entity.posY})")
        return entity
    }

    private fun handleMove(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = entities[id] ?: return null
        
        entity.posX = (params[PhotonProtocol.ParameterKey.POSITION_X] as? Number)?.toFloat() ?: entity.posX
        entity.posY = (params[PhotonProtocol.ParameterKey.POSITION_Y] as? Number)?.toFloat() ?: entity.posY
        entity.lastUpdate = System.currentTimeMillis()
        
        return entity
    }

    private fun handleHealthUpdate(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        
        val entity = entities[id] ?: return null
        
        entity.health = (params[PhotonProtocol.ParameterKey.HEALTH] as? Number)?.toInt() ?: entity.health
        entity.maxHealth = (params[PhotonProtocol.ParameterKey.MAX_HEALTH] as? Number)?.toInt() ?: entity.maxHealth
        entity.lastUpdate = System.currentTimeMillis()
        
        return entity
    }

    private fun handleLeave(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        val entity = entities.remove(id)
        
        if (entity != null) {
            Log.d(TAG, "Entity left: ${entity.uniqueName}")
        }
        return entity
    }

    private fun handleDeath(params: Map<Int, Any>): GameEntity? {
        val id = (params[PhotonProtocol.ParameterKey.PLAYER_ID] as? Number)?.toLong() ?: return null
        val entity = entities.remove(id)
        
        if (entity != null) {
            Log.d(TAG, "Entity died: ${entity.uniqueName}")
        }
        return entity
    }

    /**
     * Get all tracked entities
     */
    fun getEntities(): Map<Long, GameEntity> = entities.toMap()

    /**
     * Get entities within range
     */
    fun getEntitiesInRange(range: Float): List<GameEntity> {
        return entities.values.filter { entity ->
            val dx = entity.posX - playerX
            val dy = entity.posY - playerY
            (dx * dx + dy * dy) <= range * range
        }
    }

    /**
     * Clear all entities
     */
    fun clear() {
        entities.clear()
    }

    /**
     * Set player position reference
     */
    fun setPlayerPosition(x: Float, y: Float) {
        playerX = x
        playerY = y
    }
}
