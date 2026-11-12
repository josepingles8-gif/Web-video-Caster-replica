package com.beamcast.sender

import com.beamcast.sender.data.UrlInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {
    @Test fun resolvesUrlsAndSearches() {
        assertEquals("https://example.com", UrlInput.resolve("example.com"))
        assertEquals("https://example.com/a?b=1", UrlInput.resolve(" https://example.com/a?b=1 "))
        assertEquals("http://192.168.1.5:8080", UrlInput.resolve("192.168.1.5:8080"))
        assertEquals("http://localhost:3000/x", UrlInput.resolve("localhost:3000/x"))
        assertEquals(UrlInput.SEARCH + "gatos+graciosos", UrlInput.resolve("gatos graciosos"))
        assertEquals(UrlInput.SEARCH + "hola", UrlInput.resolve("hola"))
        assertNull(UrlInput.resolve("   "))
    }

    @Test fun extractsUrlFromSharedText() {
        assertEquals("https://x.com/v?id=3", UrlInput.extractUrl("Mira esto: https://x.com/v?id=3."))
        assertNull(UrlInput.extractUrl("sin enlace"))
    }

    @Test fun originAndShorten() {
        assertEquals("https://a.com:8443", UrlInput.origin("https://a.com:8443/x/y"))
        assertNull(UrlInput.origin(null))
        val s = UrlInput.shorten("https://example.com/" + "a".repeat(100), 40)
        assertEquals(40, s.length)
    }

    @Test fun parsesHostPort() {
        assertEquals("192.168.1.20" to 8765, UrlInput.parseHostPort("192.168.1.20", 8765))
        assertEquals("tv.local" to 9000, UrlInput.parseHostPort("http://tv.local:9000/", 8765))
        assertNull(UrlInput.parseHostPort("a b", 8765))
        assertNull(UrlInput.parseHostPort("1.2.3.4:99999", 8765))
    }
}
