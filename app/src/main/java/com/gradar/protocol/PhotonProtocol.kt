package com.gradar.protocol

/**
 * Photon Protocol Constants
 * Based on Albion Online network protocol analysis
 */
object PhotonProtocol {
    
    // Photon Command Types
    const val COMMAND_DISCONNECT = 4
    const val COMMAND_RELIABLE = 6
    const val COMMAND_UNRELIABLE = 7
    
    // Photon Header Size
    const val HEADER_SIZE = 12
    
    // Albion Event Codes
    object EventCode {
        const val LEAVE = 1
        const val MOVE = 3
        const val NEW_CHARACTER = 33
        const val NEW_MOB = 123
        const val NEW_RESOURCE = 129
        const val NEW_SIMPLE_HARVESTABLE = 200
        const val NEW_FISHING = 207
        const val NEW_MIST = 250
        const val NEW_DUNGEON = 213
        const val NEW_CHEST = 215
        const val HEALTH_UPDATE = 50
        const val DEATH = 51
        const val PLAYER_APPEAR = 2
        const val PLAYER_DISAPPEAR = 4
    }
    
    // Albion Operation Codes
    object OperationCode {
        const val JOIN = 255
        const val LEAVE = 254
        const val MOVE = 3
    }
    
    // Parameter Keys
    object ParameterKey {
        const val PLAYER_ID = 251
        const val POSITION_X = 252
        const val POSITION_Y = 253
        const val TYPE_ID = 254
        const val UNIQUE_NAME = 255
        const val TIER = 250
        const val ENCHANTMENT = 249
        const val HEALTH = 248
        const val MAX_HEALTH = 247
        const val CLUSTER = 246
        const val PARAMETERS = 245
        const val SIZE = 244
        const val RARITY = 243
        const val CHARGE = 242
        const val ALLIANCE = 241
        const val GUILD_NAME = 240
        const val GUILD_ID = 239
        const val PLAYER_NAME = 238
        const val FACTION = 237
        const val ITEM_ID = 236
        const val COUNT = 235
    }
    
    // Entity Types
    object EntityType {
        const val PLAYER = 0
        const val MOB = 1
        const val RESOURCE = 2
        const val HARVESTABLE = 3
        const val MIST = 4
        const val DUNGEON = 5
        const val CHEST = 6
        const val FISHING = 7
        const val UNKNOWN = -1
    }
    
    // Photon Type Codes
    object TypeCode {
        const val NULL = 42
        const val DICTIONARY = 68
        const val STRING = 115
        const val INTEGER = 105
        const val LONG = 108
        const val DOUBLE = 100
        const val BOOLEAN = 98
        const val BYTE_ARRAY = 120
        const val INTEGER_ARRAY = 110
        const val OBJECT_ARRAY = 121
    }
    
    // Resource Types (for filtering)
    object ResourceType {
        const val ORE = "ORE"
        const val ROCK = "ROCK"
        const val WOOD = "WOOD"
        const val HIDE = "HIDE"
        const val FIBER = "FIBER"
        const val UNKNOWN = ""
    }
    
    // Mist Rarities
    object MistRarity {
        const val COMMON = 0      // White
        const val UNCOMMON = 1    // Green
        const val RARE = 2        // Blue
        const val LEGENDARY = 3   // Purple
        const val EPIC = 4        // Gold
    }
}
