package com.beamcast.sender

import com.beamcast.protocol.RemoteKey
import com.beamcast.sender.cast.EShareRemote
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class EShareRemoteTest {
    @Test fun mapsNavigationKeysOnly() {
        assertEquals(19, EShareRemote.keyCode(RemoteKey.UP))
        assertEquals(4, EShareRemote.keyCode(RemoteKey.BACK))
        assertNull(EShareRemote.keyCode(RemoteKey.VOLUME_UP))
    }

    @Test fun sendsHelloThenKeyEvents() {
        val server = ServerSocket(0)
        var received = ""
        val t = thread { server.accept().use { received = it.getInputStream().readBytes().toString(Charsets.UTF_8) } }
        runBlocking {
            assertTrue(EShareRemote.send("127.0.0.1", 19, listOf(server.localPort)))
            assertTrue(EShareRemote.send("127.0.0.1", 23, listOf(server.localPort)))
        }
        EShareRemote.close()
        t.join(5000)
        server.close()
        assertEquals("sayHello\r\nBeamCast\r\n\r\nKEYEVENT\r\n19\r\n\r\nKEYEVENT\r\n23\r\n\r\n", received)
    }

    @Test fun reportsMissingServer() {
        val free = ServerSocket(0).use { it.localPort }
        runBlocking { assertFalse(EShareRemote.send("127.0.0.1", 19, listOf(free))) }
    }
}
