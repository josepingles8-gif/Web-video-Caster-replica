package com.beamcast.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EnhanceEffectTest {
    private fun size(w: Int, h: Int, sw: Int = 1920, sh: Int = 1080) =
        EnhanceEffect.outputSize(w, h, sw, sh).let { it.width to it.height }

    @Test fun upscalesToTheScreenKeepingAspect() {
        assertEquals(1920 to 1080, size(1280, 720))
        assertEquals(1920 to 1080, size(640, 360))
        assertEquals(1440 to 1080, size(640, 480))
        assertEquals(1920 to 800, size(1280, 534))
    }

    @Test fun neverDownscalesOrExceedsFullHd() {
        assertEquals(1920 to 1080, size(1920, 1080))
        assertEquals(3840 to 2160, size(3840, 2160, 3840, 2160))
        assertEquals(1920 to 1080, size(1280, 720, 3840, 2160))
        assertEquals(1280 to 720, size(1280, 720, 1280, 720))
    }
}
