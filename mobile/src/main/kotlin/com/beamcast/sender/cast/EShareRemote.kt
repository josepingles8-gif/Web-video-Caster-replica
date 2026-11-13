package com.beamcast.sender.cast

import com.beamcast.protocol.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Key injection through the "EShare" service that many TV makers preinstall as a system app. It is
 * the only way to navigate TVs that expose neither Accessibility nor ADB: BeamCast Receiver cannot
 * press keys there, but the TV's own EShare server can. Wire format (plain TCP, text):
 * `KEYEVENT\r\n<android key code>\r\n\r\n`.
 */
object EShareRemote {
    /** Control ports used by EShare servers, most common first. */
    val PORTS = listOf(2012, 8121)
    private const val IDLE_MS = 4000L
    private const val RETRY_MS = 60_000L

    private var socket: Socket? = null
    private var lastUse = 0L
    private var goodPort: Int? = null
    private var missingHost: String? = null
    private var missingSince = 0L

    /** Android key code for a [RemoteKey] this channel can deliver, or null. */
    fun keyCode(key: String): Int? = when (key) {
        RemoteKey.UP -> 19
        RemoteKey.DOWN -> 20
        RemoteKey.LEFT -> 21
        RemoteKey.RIGHT -> 22
        RemoteKey.OK -> 23
        RemoteKey.BACK -> 4
        RemoteKey.HOME -> 3
        RemoteKey.RECENTS -> 187
        RemoteKey.BACKSPACE -> 67
        RemoteKey.VOLUME_UP -> 24
        RemoteKey.VOLUME_DOWN -> 25
        RemoteKey.MUTE -> 164
        else -> null
    }

    fun hello(client: String) = "sayHello\r\n$client\r\n\r\n"

    fun keyEvent(code: Int) = "KEYEVENT\r\n$code\r\n\r\n"

    /** Sends one key press. Returns false when the TV has no reachable EShare server. */
    suspend fun send(host: String, code: Int, ports: List<Int> = PORTS): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@EShareRemote) {
            repeat(2) {
                try {
                    val s = connection(host, ports) ?: return@synchronized false
                    s.getOutputStream().apply { write(keyEvent(code).toByteArray()); flush() }
                    lastUse = System.currentTimeMillis()
                    return@synchronized true
                } catch (_: Exception) {
                    close()
                }
            }
            false
        }
    }

    /** Reuses the socket during a burst of keys; an idle one may have been dropped by the TV. */
    private fun connection(host: String, ports: List<Int>): Socket? {
        val current = socket
        if (current != null && current.inetAddress?.hostAddress == host && System.currentTimeMillis() - lastUse < IDLE_MS) return current
        close()
        // Do not stall every key press on a TV that has no EShare server.
        if (host == missingHost && System.currentTimeMillis() - missingSince < RETRY_MS) return null
        for (port in listOfNotNull(goodPort?.takeIf { it in ports }) + ports) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 1200)
                s.tcpNoDelay = true
                s.getOutputStream().apply { write(hello("BeamCast").toByteArray()); flush() }
                socket = s
                goodPort = port
                missingHost = null
                return s
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        missingHost = host
        missingSince = System.currentTimeMillis()
        return null
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    /** Drops the connection and forgets that a host had no server, so the next key tries again. */
    @Synchronized
    fun reset() {
        close()
        missingHost = null
    }
}
