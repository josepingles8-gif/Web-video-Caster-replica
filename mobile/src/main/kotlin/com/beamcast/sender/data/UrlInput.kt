package com.beamcast.sender.data

import java.net.URI
import java.net.URLEncoder

/** Pure helpers for address-bar input and URL display. */
object UrlInput {
    const val SEARCH = "https://duckduckgo.com/?q="
    const val HOME = "https://duckduckgo.com/"

    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val local = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)
    private val host = Regex("^([\\p{L}\\p{N}-]+\\.)+\\p{L}{2,}(:\\d+)?([/?#].*)?$")
    private val urlInText = Regex("https?://[^\\s<>\"']+")

    /** Turns raw address-bar text into a URL to load; non-URL text becomes a search. Null when blank. */
    fun resolve(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (scheme.containsMatchIn(t)) {
            val s = t.substringBefore("://").lowercase()
            if (s == "http" || s == "https" || s == "file") return t
        } else if (!t.contains(' ')) {
            if (local.matches(t)) return "http://$t"
            if (host.matches(t)) return "https://$t"
        }
        return SEARCH + URLEncoder.encode(t, "UTF-8")
    }

    fun extractUrl(text: String): String? =
        urlInText.find(text)?.value?.trimEnd('.', ',', ')', ';', '!')

    fun host(url: String): String? = try { URI(url).host } catch (e: Exception) { null }

    fun origin(url: String?): String? {
        if (url == null) return null
        return try {
            val u = URI(url)
            if (u.scheme == null || u.host == null) null
            else u.scheme + "://" + u.host + (if (u.port > 0) ":${u.port}" else "")
        } catch (e: Exception) { null }
    }

    fun shorten(url: String, max: Int): String {
        if (url.length <= max || max < 8) return url
        val head = max * 2 / 3
        val tail = max - head - 1
        return url.take(head) + "…" + url.takeLast(tail)
    }

    /** Parses "host" or "host:port" (optionally with http:// and a path). */
    fun parseHostPort(text: String, defaultPort: Int): Pair<String, Int>? {
        var t = text.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (t.isEmpty()) return null
        var port = defaultPort
        if (t.count { it == ':' } == 1) {
            port = t.substringAfter(':').toIntOrNull() ?: return null
            t = t.substringBefore(':')
        }
        if (port !in 1..65535) return null
        if (!Regex("^[A-Za-z0-9.-]+$").matches(t)) return null
        return t to port
    }
}
