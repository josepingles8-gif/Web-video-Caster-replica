package com.beamcast.receiver.server

import com.beamcast.protocol.BeamProtocol
import com.beamcast.protocol.ControlAction
import com.beamcast.protocol.ControlRequest
import com.beamcast.protocol.DeviceInfo
import com.beamcast.protocol.ErrorResponse
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackState
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.protocol.RemoteResult
import com.beamcast.protocol.TextRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRouterTest {
    private class Fake : PlayerController {
        var loaded: LoadRequest? = null
        var controlled: ControlRequest? = null
        var fail: Exception? = null
        var current = PlaybackStatus(state = PlaybackState.PAUSED, positionMs = 5, durationMs = 100, title = "t")
        override fun status() = current
        override fun load(request: LoadRequest): PlaybackStatus {
            fail?.let { throw it }
            loaded = request
            current = PlaybackStatus(state = PlaybackState.LOADING, url = request.url, title = request.title)
            return current
        }
        override fun control(request: ControlRequest): PlaybackStatus {
            controlled = request
            return current
        }
    }

    private class FakeRemote : RemoteController {
        var on = true
        var lastKey: String? = null
        var lastText: TextRequest? = null
        override fun enabled() = on
        override fun key(key: String): RemoteResult {
            lastKey = key
            return if (on) RemoteResult(true, true) else RemoteResult(false, false, "disabled")
        }
        override fun text(req: TextRequest): RemoteResult {
            lastText = req
            return RemoteResult(on, on)
        }
    }

    private val fake = Fake()
    private val remote = FakeRemote()
    private val router = RequestRouter(fake, remote) { DeviceInfo("Living room", "1.0") }
    private fun err(r: HttpResult) = BeamProtocol.json.decodeFromString(ErrorResponse.serializer(), r.body)
    private fun st(r: HttpResult) = BeamProtocol.json.decodeFromString(PlaybackStatus.serializer(), r.body)

    @Test fun info() {
        val r = router.handle("GET", "/info", null)
        assertEquals(200, r.status)
        val d = BeamProtocol.json.decodeFromString(DeviceInfo.serializer(), r.body)
        assertEquals("Living room", d.name)
        assertEquals(BeamProtocol.VERSION, d.protocolVersion)
    }

    @Test fun statusSnapshot() {
        val r = router.handle("GET", "/status?x=1", null)
        assertEquals(200, r.status)
        assertEquals(fake.current, st(r))
    }

    @Test fun loadOk() {
        val body = """{"url":"https://e.com/a.m3u8","title":"A","headers":{"Referer":"x"},"startPositionMs":1500,"subtitleUrl":"https://e.com/a.srt","unknown":1}"""
        val r = router.handle("POST", "/load", body)
        assertEquals(200, r.status)
        assertEquals("https://e.com/a.m3u8", fake.loaded?.url)
        assertEquals(1500L, fake.loaded?.startPositionMs)
        assertEquals("x", fake.loaded?.headers?.get("Referer"))
        assertEquals(PlaybackState.LOADING, st(r).state)
    }

    @Test fun loadBadJson() {
        val r = router.handle("POST", "/load", "{nope")
        assertEquals(400, r.status)
        assertTrue(err(r).error.isNotBlank())
        assertNull(fake.loaded)
    }

    @Test fun loadEmptyMissingUrlAndBadScheme() {
        assertEquals(400, router.handle("POST", "/load", null).status)
        assertEquals(400, router.handle("POST", "/load", "{}").status)
        assertEquals(400, router.handle("POST", "/load", """{"url":"file:///x"}""").status)
        assertNull(fake.loaded)
    }

    @Test fun controllerFailureIs500() {
        fake.fail = IllegalStateException("boom")
        assertEquals(500, router.handle("POST", "/load", """{"url":"http://a/b.mp4"}""").status)
    }

    @Test fun controlActions() {
        for (a in listOf(ControlAction.PLAY, ControlAction.PAUSE, ControlAction.STOP)) {
            assertEquals(200, router.handle("POST", "/control", """{"action":"$a"}""").status)
            assertEquals(a, fake.controlled?.action)
        }
        val seek = router.handle("POST", "/control", """{"action":"seek","value":42000}""")
        assertEquals(200, seek.status)
        assertEquals(42000L, fake.controlled?.value)
        assertEquals(200, router.handle("POST", "/control", """{"action":"seekRelative","value":-10000}""").status)
        assertEquals(200, router.handle("POST", "/control", """{"action":"volume","value":30}""").status)
        assertEquals(30L, fake.controlled?.value)
    }

    @Test fun controlRejections() {
        fake.controlled = null
        val unknown = router.handle("POST", "/control", """{"action":"explode"}""")
        assertEquals(400, unknown.status)
        assertTrue(err(unknown).error.contains("explode"))
        assertEquals(400, router.handle("POST", "/control", """{"action":"seek"}""").status)
        assertEquals(400, router.handle("POST", "/control", "not json").status)
        assertEquals(400, router.handle("POST", "/control", "").status)
        assertNull(fake.controlled)
    }

    @Test fun unknownPathAndMethod() {
        val nf = router.handle("GET", "/nope", null)
        assertEquals(404, nf.status)
        assertNotNull(err(nf).error)
        val m = router.handle("GET", "/load", null)
        assertEquals(405, m.status)
        assertEquals("POST", m.allow)
        assertEquals(405, router.handle("POST", "/info", "{}").status)
        assertEquals(200, router.handle("GET", "/info/", null).status)
    }

    private fun rr(r: HttpResult) = BeamProtocol.json.decodeFromString(RemoteResult.serializer(), r.body)

    @Test fun keyOk() {
        val r = router.handle("POST", "/key", """{"key":"up"}""")
        assertEquals(200, r.status)
        assertTrue(rr(r).ok)
        assertEquals("up", remote.lastKey)
    }

    @Test fun keyUnknownIs400() {
        remote.lastKey = null
        assertEquals(400, router.handle("POST", "/key", """{"key":"banana"}""").status)
        assertNull(remote.lastKey)
    }

    @Test fun keyServiceDisabled() {
        remote.on = false
        val r = router.handle("POST", "/key", """{"key":"home"}""")
        assertEquals(200, r.status)
        assertEquals(false, rr(r).ok)
        assertEquals(false, rr(r).remoteEnabled)
        assertEquals("disabled", rr(r).message)
    }

    @Test fun textRequest() {
        val r = router.handle("POST", "/text", """{"text":"hola","replace":true}""")
        assertEquals(200, r.status)
        assertTrue(rr(r).ok)
        assertEquals(TextRequest("hola", true), remote.lastText)
        assertEquals(400, router.handle("POST", "/text", """{}""").status)
    }

    @Test fun qualityControl() {
        assertEquals(200, router.handle("POST", "/control", """{"action":"quality","value":0}""").status)
        assertEquals(0L, fake.controlled?.value)
        assertEquals(200, router.handle("POST", "/control", """{"action":"quality","value":1}""").status)
        fake.controlled = null
        assertEquals(400, router.handle("POST", "/control", """{"action":"quality","value":2}""").status)
        assertEquals(400, router.handle("POST", "/control", """{"action":"quality"}""").status)
        assertNull(fake.controlled)
    }

    @Test fun infoReportsRemoteEnabled() {
        fun enabled() = BeamProtocol.json.decodeFromString(DeviceInfo.serializer(), router.handle("GET", "/info", null).body).remoteEnabled
        assertTrue(enabled())
        remote.on = false
        assertEquals(false, enabled())
    }
}
