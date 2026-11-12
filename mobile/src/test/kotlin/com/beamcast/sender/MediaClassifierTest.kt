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
}
