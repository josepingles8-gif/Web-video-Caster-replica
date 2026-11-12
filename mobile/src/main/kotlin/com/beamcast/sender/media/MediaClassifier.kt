package com.beamcast.sender.media

enum class MediaKind { HLS, DASH, VIDEO, AUDIO }

/** Pure URL classification and MIME guessing (no Android dependencies). */
object MediaClassifier {
    private val kinds = mapOf(
        "m3u8" to MediaKind.HLS, "mpd" to MediaKind.DASH,
        "mp4" to MediaKind.VIDEO, "webm" to MediaKind.VIDEO, "mkv" to MediaKind.VIDEO, "mov" to MediaKind.VIDEO,
        "mp3" to MediaKind.AUDIO, "m4a" to MediaKind.AUDIO, "aac" to MediaKind.AUDIO, "flac" to MediaKind.AUDIO,
    )
    private val mimes = mapOf(
        "m3u8" to "application/x-mpegURL", "mpd" to "application/dash+xml",
        "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
    )
    private val segments = setOf("ts", "m4s")

    fun extensionOf(urlOrName: String): String? {
        val last = urlOrName.substringBefore('#').substringBefore('?').substringAfterLast('/')
        if (!last.contains('.')) return null
        return last.substringAfterLast('.').lowercase()
    }

    fun isHttp(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    fun isSegment(url: String) = extensionOf(url) in segments

    /** Returns the media kind for an http(s) URL that looks like playable media, or null. */
    fun classify(url: String): MediaKind? {
        if (!isHttp(url)) return null
        return kinds[extensionOf(url)]
    }

    fun guessMime(urlOrName: String): String? = mimes[extensionOf(urlOrName)]
}
