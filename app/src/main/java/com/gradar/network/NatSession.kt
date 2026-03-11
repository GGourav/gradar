package com.gradar.network

/**
 * NAT Session - Maps local port to remote endpoint
 */
data class NatSession(
    var localPort: Short = 0,
    var remoteIP: Int = 0,
    var remotePort: Short = 0,
    var remoteHost: String? = null,
    var lastRefreshTime: Long = 0,
    var bytesSent: Long = 0,
    var bytesReceived: Long = 0,
    var packetsSent: Int = 0,
    var packetsReceived: Int = 0,
    var type: String = "UDP"
) {
    companion object {
        const val SESSION_TIMEOUT_MS = 60_000L
    }

    fun refresh() {
        lastRefreshTime = System.currentTimeMillis()
    }

    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastRefreshTime > SESSION_TIMEOUT_MS
    }

    fun getUniqueName(): String {
        return "${remoteHost}_${remotePort}_$localPort"
    }
}

/**
 * NAT Session Manager - Handles session creation and cleanup
 */
object NatSessionManager {
    private const val MAX_SESSION_COUNT = 64
    private val sessions = mutableMapOf<Short, NatSession>()

    @Synchronized
    fun getSession(portKey: Short): NatSession? {
        return sessions[portKey]
    }

    @Synchronized
    fun createSession(portKey: Short, remoteIP: Int, remotePort: Short, type: String = "UDP"): NatSession {
        if (sessions.size >= MAX_SESSION_COUNT) {
            clearExpiredSessions()
        }

        val session = NatSession(
            localPort = portKey,
            remoteIP = remoteIP,
            remotePort = remotePort,
            type = type,
            lastRefreshTime = System.currentTimeMillis()
        )

        session.remoteHost = intToIp(remoteIP)
        sessions[portKey] = session
        return session
    }

    @Synchronized
    fun removeSession(portKey: Short) {
        sessions.remove(portKey)
    }

    @Synchronized
    fun clearExpiredSessions() {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isExpired()) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clearAllSessions() {
        sessions.clear()
    }

    @Synchronized
    fun getSessionCount(): Int = sessions.size

    @Synchronized
    fun getAllSessions(): List<NatSession> = sessions.values.toList()

    fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        var result = 0
        for (i in 0..3) {
            result = result or (parts[i].toInt() shl (i * 8))
        }
        return result
    }
}
