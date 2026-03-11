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
    var rarity: Int = 0,
    var playerName: String? = null,
    var guildName: String? = null,
    var alliance: String? = null,
    var faction: Int = 0,
    var isHostile: Boolean = false,
    var lastUpdate: Long = System.currentTimeMillis()
) {
    
    fun isResource(): Boolean {
        return entityType == PhotonProtocol.EntityType.RESOURCE ||
               entityType == PhotonProtocol.EntityType.HARVESTABLE
    }
    
    fun isMob(): Boolean {
        return entityType == PhotonProtocol.EntityType.MOB
    }
    
    fun isPlayer(): Boolean {
        return entityType == PhotonProtocol.EntityType.PLAYER
    }
    
    fun isMist(): Boolean {
        return entityType == PhotonProtocol.EntityType.MIST
    }
    
    fun isDungeon(): Boolean {
        return entityType == PhotonProtocol.EntityType.DUNGEON
    }
    
    fun isChest(): Boolean {
        return entityType == PhotonProtocol.EntityType.CHEST
    }
    
    fun isFishing(): Boolean {
        return entityType == PhotonProtocol.EntityType.FISHING
    }
    
    fun getTierString(): String {
        return if (enchantment > 0) {
            "T$tier.$enchantment"
        } else {
            "T$tier"
        }
    }
    
    fun isEnchanted(): Boolean = enchantment > 0
    
    fun isBoss(): Boolean {
        val name = uniqueName?.uppercase() ?: return false
        return name.contains("BOSS") ||
               name.contains("_CHEST_") ||
               name.contains("ABYSS") ||
               name.contains("HELLGATE") ||
               name.contains("COMMANDER") ||
               name.contains("ARTHUR") ||
               name.contains("MERLIN") ||
               name.contains("MORGANA")
    }
    
    fun getResourceType(): String {
        val name = uniqueName?.uppercase() ?: return PhotonProtocol.ResourceType.UNKNOWN
        return when {
            name.contains("ORE") || name.contains("_ORE_") -> PhotonProtocol.ResourceType.ORE
            name.contains("ROCK") || name.contains("_ROCK_") -> PhotonProtocol.ResourceType.ROCK
            name.contains("WOOD") || name.contains("LOG") || name.contains("_WOOD_") -> PhotonProtocol.ResourceType.WOOD
            name.contains("HIDE") || name.contains("_HIDE_") -> PhotonProtocol.ResourceType.HIDE
            name.contains("FIBER") || name.contains("_FIBER_") -> PhotonProtocol.ResourceType.FIBER
            else -> PhotonProtocol.ResourceType.UNKNOWN
        }
    }
    
    fun getMistRarity(): Int = rarity
    
    fun getEnchantColor(): Int = enchantment.coerceIn(0, 4)
}
