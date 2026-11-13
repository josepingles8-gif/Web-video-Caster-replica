package com.beamcast.sender.media

enum class MediaKind { HLS, DASH, VIDEO, AUDIO }

/** Pure URL classification and MIME guessing (no Android dependencies). */
object MediaClassifier {
    private val kinds = mapOf(
        "m3u8" to MediaKind.HLS, "mpd" to MediaKind.DASH,
        "mp4" to MediaKind.VIDEO, "webm" to MediaKind.VIDEO, "mkv" to MediaKind.VIDEO, "mov" to MediaKind.VIDEO,
        "m4v" to MediaKind.VIDEO, "avi" to MediaKind.VIDEO, "3gp" to MediaKind.VIDEO,
        "mp3" to MediaKind.AUDIO, "m4a" to MediaKind.AUDIO, "aac" to MediaKind.AUDIO, "flac" to MediaKind.AUDIO,
        "ogg" to MediaKind.AUDIO, "opus" to MediaKind.AUDIO, "wav" to MediaKind.AUDIO,
    )
    private val mimes = mapOf(
        "m3u8" to "application/x-mpegURL", "mpd" to "application/dash+xml",
        "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
        "m4v" to "video/mp4", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "ogg" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav",
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

    /** Media kind for a plain file name such as "clip.mp4" (no URL needed), or null. */
    fun kindOfName(name: String): MediaKind? = kinds[extensionOf(name.trim())]

    private val driveFile = Regex("^https?://(?:drive|docs)\\.google\\.com/(?:.*/)?file/d/([\\w-]{10,})", RegexOption.IGNORE_CASE)
    private val driveQuery = Regex("^https?://drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([\\w-]{10,})", RegexOption.IGNORE_CASE)

    /** File id of a Google Drive file page, or null. Drive plays through blob/adaptive streams that cannot be cast as-is. */
    fun driveFileId(pageUrl: String): String? =
        (driveFile.find(pageUrl) ?: driveQuery.find(pageUrl))?.groupValues?.get(1)

    /** Direct download URL of a Drive file: the original file, with Range support. */
    fun driveDirectUrl(id: String) = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"

    /**
     * True for a request that carries a Range header but has no media extension: this is how the
     * browser fetches a <video>/<audio> file served from an extension-less URL.
     */
    fun looksLikeRangedMedia(url: String, hasRange: Boolean): Boolean {
        if (!hasRange || !isHttp(url) || isSegment(url)) return false
        if (url.contains("videoplayback")) return false // chunked adaptive streams, not castable
        return extensionOf(url) !in nonMedia
    }

    private val nonMedia = setOf("js", "css", "json", "html", "htm", "pdf", "png", "jpg", "jpeg", "gif", "webp", "svg", "woff", "woff2", "ttf", "wasm", "zip", "vtt", "srt")

    fun guessMime(urlOrName: String): String? = mimes[extensionOf(urlOrName)]

    private val qualityRegex = Regex("(?<![0-9])(4320|2160|1440|1080|720|576|480|360|240|144)p?(?![0-9])", RegexOption.IGNORE_CASE)
    private val masterRegex = Regex("master|manifest|playlist|index", RegexOption.IGNORE_CASE)

    /** Vertical resolution mentioned in the URL path (e.g. 1080 for ".../video_1080p.mp4"), or null. */
    fun qualityHint(url: String): Int? {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        return qualityRegex.findAll(path).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
    }

    /** True for adaptive manifests that are not pinned to one rendition, so the TV can pick the best one. */
    fun isAdaptiveMaster(url: String): Boolean {
        val kind = classify(url)
        if (kind != MediaKind.HLS && kind != MediaKind.DASH) return false
        if (kind == MediaKind.DASH) return true
        val name = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return qualityHint(url) == null || masterRegex.containsMatchIn(name)
    }

    /** Best-quality candidates first: adaptive masters, then higher resolution hints; stable otherwise. */
    fun <T> sortByQuality(items: List<T>, url: (T) -> String): List<T> =
        items.sortedWith(
            compareByDescending<T> { isAdaptiveMaster(url(it)) }
                .thenByDescending { qualityHint(url(it)) ?: 0 },
        )
}
