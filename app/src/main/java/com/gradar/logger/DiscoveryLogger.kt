package com.gradar.logger

import android.content.Context
import android.util.Log
import com.gradar.model.GameEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discovery Logger - Logs unknown entities for database updates
 * This helps identify new entity types that aren't in our local database
 */
class DiscoveryLogger(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryLogger"
        private const val LOG_FILE = "unknown_entities.log"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var logFile: File? = null

    init {
        initLogFile()
    }

    private fun initLogFile() {
        try {
            logFile = File(context.filesDir, LOG_FILE)
            if (!logFile!!.exists()) {
                logFile!!.createNewFile()
                appendLog("# G Radar Unknown Entities Log")
                appendLog("# Format: [timestamp] typeId|uniqueName|posX|posY|entityType")
                appendLog("# ========================================")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init log file: ${e.message}")
        }
    }

    /**
     * Log an unknown entity
     */
    fun logUnknownEntity(entity: GameEntity) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] ${entity.typeId}|${entity.uniqueName ?: "null"}|${entity.posX}|${entity.posY}|${entity.entityType}"
        
        Log.d(TAG, "Unknown entity: $line")
        appendLog(line)
    }

    /**
     * Log raw packet data for debugging
     */
    fun logRawPacket(data: ByteArray, length: Int, source: String) {
        val timestamp = dateFormat.format(Date())
        val hexPreview = data.take(minOf(32, length)).joinToString(" ") { 
            String.format("%02X", it) 
        }
        val line = "[$timestamp] RAW[$source] len=$length: $hexPreview..."
        
        appendLog(line)
    }

    /**
     * Log a parsed event for debugging
     */
    fun logParsedEvent(eventCode: Int, parameters: Map<Int, Any>) {
        val timestamp = dateFormat.format(Date())
        val paramsStr = parameters.entries.joinToString(", ") { (k, v) ->
            "$k=$v"
        }
        val line = "[$timestamp] EVENT[$eventCode]: $paramsStr"
        
        appendLog(line)
    }

    private fun appendLog(line: String) {
        try {
            logFile?.appendText("$line\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append log: ${e.message}")
        }
    }

    /**
     * Get the full log content
     */
    fun getLogContent(): String {
        return try {
            logFile?.readText() ?: "Log file not found"
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }

    /**
     * Clear the log file
     */
    fun clearLog() {
        try {
            logFile?.writeText("")
            appendLog("# G Radar Unknown Entities Log (Cleared)")
            appendLog("# Format: [timestamp] typeId|uniqueName|posX|posY|entityType")
            appendLog("# ========================================")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log: ${e.message}")
        }
    }

    /**
     * Get log file path
     */
    fun getLogFilePath(): String = logFile?.absolutePath ?: ""
}
