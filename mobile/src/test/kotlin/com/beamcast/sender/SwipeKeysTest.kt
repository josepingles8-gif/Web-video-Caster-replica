package com.beamcast.sender

import com.beamcast.protocol.RemoteKey
import com.beamcast.sender.cast.SwipeKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeKeysTest {
    private val s = SwipeKeys(24f, 64f)

    @Test fun belowThresholdIsNothing() = assertEquals(emptyList<String>(), s.keysFor(10f, -20f))

    @Test fun exactThresholdIsOneKey() = assertEquals(listOf(RemoteKey.RIGHT), s.keysFor(24f, 0f))

    @Test fun directions() {
        assertEquals(listOf(RemoteKey.LEFT), s.keysFor(-40f, 10f))
        assertEquals(listOf(RemoteKey.UP), s.keysFor(5f, -40f))
        assertEquals(listOf(RemoteKey.DOWN), s.keysFor(-5f, 40f))
    }

    @Test fun dominantAxisWins() = assertEquals(listOf(RemoteKey.DOWN), s.keysFor(30f, 31f))

    @Test fun longSwipeRepeats() {
        assertEquals(1, s.keysFor(87f, 0f).size)
        assertEquals(2, s.keysFor(88f, 0f).size)
        assertEquals(List(3) { RemoteKey.UP }, s.keysFor(0f, -160f))
    }
}
