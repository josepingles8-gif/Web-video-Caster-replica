package com.beamcast.sender.cast

import com.beamcast.protocol.RemoteKey

/** Maps a finished swipe gesture to the arrow keys it should produce. Units are arbitrary but consistent (px). */
class SwipeKeys(private val minDistance: Float, private val repeatStep: Float) {
    fun keysFor(dx: Float, dy: Float): List<String> {
        val ax = Math.abs(dx)
        val ay = Math.abs(dy)
        val horizontal = ax >= ay
        val dist = if (horizontal) ax else ay
        if (dist < minDistance) return emptyList()
        val key = if (horizontal) {
            if (dx > 0) RemoteKey.RIGHT else RemoteKey.LEFT
        } else {
            if (dy > 0) RemoteKey.DOWN else RemoteKey.UP
        }
        val count = 1 + ((dist - minDistance) / repeatStep).toInt()
        return List(count) { key }
    }
}
