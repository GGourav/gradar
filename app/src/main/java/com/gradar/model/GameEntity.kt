package com.gradar.model

import com.gradar.protocol.PhotonProtocol

/**
 * Represents a game entity in the radar
 */
data class GameEntity(
    val id: Long,
    val typeId: Int,
    var uniqueName: String? = null,
    var posX: Float = 0f,
    var posY: Float = 0f,
    var tier: Int = 0,
    var enchantment: Int = 0,
    var health: Int = 100,
    var maxHealth: Int = 100,
    val entityType: Int = PhotonProtocol.EntityType.UNKNOWN,
    var lastUpdate: Long = System.currentTimeMillis()
) {
    
    /**
     * Check if entity is a resource
     */
    fun isResource(): Boolean {
        return entityType == PhotonProtocol.EntityType.RESOURCE ||
               entityType == PhotonProtocol.EntityType.HARVESTABLE
    }
    
    /**
     * Check if entity is a mob
     */
    fun isMob(): Boolean {
        return entityType == PhotonProtocol.EntityType.MOB
    }
    
    /**
     * Check if entity is a player
     */
    fun isPlayer(): Boolean {
        return entityType == PhotonProtocol.EntityType.PLAYER
    }
    
    /**
     * Check if entity is a mist
     */
    fun isMist(): Boolean {
        return entityType == PhotonProtocol.EntityType.MIST
    }
    
    /**
     * Get tier display string (e.g., "T4", "T6.2")
     */
    fun getTierString(): String {
        return if (enchantment > 0) {
            "T$tier.$enchantment"
        } else {
            "T$tier"
        }
    }
    
    /**
     * Check if entity is enchanted
     */
    fun isEnchanted(): Boolean = enchantment > 0
    
    /**
     * Check if entity is a boss
     */
    fun isBoss(): Boolean {
        return uniqueName?.contains("BOSS", ignoreCase = true) == true ||
               uniqueName?.contains("_CHEST_", ignoreCase = true) == true ||
               uniqueName?.contains("ABYSS", ignoreCase = true) == true
    }
}
