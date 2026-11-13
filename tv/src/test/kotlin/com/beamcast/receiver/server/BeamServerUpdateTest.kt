package com.beamcast.receiver.server

import com.beamcast.protocol.ControlRequest
import com.beamcast.protocol.DeviceInfo
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.protocol.RemoteResult
import com.beamcast.protocol.TextRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class BeamServerUpdateTest {
    private val player = object : PlayerController {
        override fun status() = PlaybackStatus()
        override fun load(request: LoadRequest) = PlaybackStatus()
        override fun control(request: ControlRequest) = PlaybackStatus()
    }
    private val remote = object : RemoteController {
        override fun enabled() = false
        override fun key(key: String) = RemoteResult(false, false)
        override fun text(req: TextRequest) = RemoteResult(false, false)
    }

    private fun put(port: Int, method: String, body: ByteArray): Int {
        val c = URL("http://127.0.0.1:$port/update").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        return c.responseCode.also { c.disconnect() }
    }

    @Test fun uploadReachesTheUpdaterIntact() {
        val sent = ByteArray(3_000_000) { (it * 31).toByte() }
        var received: ByteArray? = null
        var reject = false
        val server = BeamServer(0, RequestRouter(player, remote) { DeviceInfo("tv", "1.0") }) { file ->
            received = file.readBytes()
            if (reject) "bad file" else null
        }
        server.start(5000, true)
        try {
            assertEquals(200, put(server.listeningPort, "PUT", sent))
            assertArrayEquals(sent, received)
            reject = true
            assertEquals(400, put(server.listeningPort, "PUT", sent))
            assertEquals(405, put(server.listeningPort, "POST", ByteArray(4)))
        } finally {
            server.stop()
        }
    }
}
