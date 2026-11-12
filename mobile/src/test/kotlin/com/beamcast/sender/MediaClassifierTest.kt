package com.beamcast.sender

import com.beamcast.sender.media.MediaClassifier
import com.beamcast.sender.media.MediaKind
import com.beamcast.sender.media.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {
    @Test fun classifiesByExtensionIgnoringQuery() {
        assertEquals(MediaKind.HLS, MediaClassifier.classify("https://a.com/v/master.M3U8?token=1#x"))
        assertEquals(MediaKind.DASH, MediaClassifier.classify("http://a.com/x.mpd"))
        assertEquals(MediaKind.VIDEO, MediaClassifier.classify("https://a.com/x.mp4"))
        assertEquals(MediaKind.AUDIO, MediaClassifier.classify("https://a.com/x.flac"))
    }

    @Test fun ignoresSegmentsAndNonMedia() {
        assertNull(MediaClassifier.classify("https://a.com/seg1.ts"))
        assertTrue(MediaClassifier.isSegment("https://a.com/seg1.m4s?x=1"))
        assertNull(MediaClassifier.classify("https://a.com/index.html"))
        assertNull(MediaClassifier.classify("https://a.com/watch"))
        assertNull(MediaClassifier.classify("blob:https://a.com/x.mp4"))
        assertFalse(MediaClassifier.isSegment("https://a.com/x.mp4"))
    }

    @Test fun guessesMime() {
        assertEquals("application/x-mpegURL", MediaClassifier.guessMime("https://a.com/a.m3u8"))
        assertEquals("video/x-matroska", MediaClassifier.guessMime("movie.mkv"))
        assertEquals("image/jpeg", MediaClassifier.guessMime("p.JPG"))
        assertNull(MediaClassifier.guessMime("https://a.com/stream"))
    }

    @Test fun formatsTime() {
        assertEquals("0:00", TimeFormat.format(-5))
        assertEquals("1:05", TimeFormat.format(65_000))
        assertEquals("1:01:01", TimeFormat.format(3_661_000))
    }

    @Test fun readsQualityHintFromPath() {
        assertEquals(1080, MediaClassifier.qualityHint("https://a.com/v/clip_1080p.mp4?x=360"))
        assertEquals(720, MediaClassifier.qualityHint("https://cdn1080.a.com/hls/720/index.m3u8"))
        assertNull(MediaClassifier.qualityHint("https://a.com/v/12345678/clip.mp4"))
    }

    @Test fun sortsMastersAndHigherQualityFirst() {
        val urls = listOf(
            "https://a.com/v/360p.mp4",
            "https://a.com/hls/480p/chunklist.m3u8",
            "https://a.com/v/1080p.mp4",
            "https://a.com/hls/master.m3u8",
        )
        assertEquals(
            listOf(urls[3], urls[2], urls[1], urls[0]),
            MediaClassifier.sortByQuality(urls) { it },
        )
        assertTrue(MediaClassifier.isAdaptiveMaster(urls[3]))
        assertFalse(MediaClassifier.isAdaptiveMaster(urls[1]))
    }
}
