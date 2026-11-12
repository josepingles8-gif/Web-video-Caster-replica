package com.beamcast.sender.browser

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.beamcast.sender.data.UrlInput
import com.beamcast.sender.media.MediaClassifier
import com.beamcast.sender.media.MediaKind

data class DetectedMedia(val url: String, val kind: MediaKind, val headers: Map<String, String>, val mime: String?)

/** Collects media URLs seen by the WebView (thread-safe; notifies on the main thread). */
class MediaDetector(private val onChange: (List<DetectedMedia>) -> Unit) {
    private val items = LinkedHashMap<String, DetectedMedia>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var pageUrl: String? = null
    @Volatile var userAgent: String = ""

    fun reset() {
        synchronized(items) { items.clear() }
        notifyChange()
    }

    fun onRequest(url: String, reqHeaders: Map<String, String>?, mainFrame: Boolean) {
        if (mainFrame) { reset(); return }
        if (MediaClassifier.isSegment(url)) return
        val kind = MediaClassifier.classify(url) ?: return
        add(url, kind, reqHeaders)
    }

    /** URL reported by the injected script from a <video>/<audio>/<source> element. */
    fun onReported(url: String, tag: String) {
        if (!MediaClassifier.isHttp(url) || MediaClassifier.isSegment(url)) return
        val kind = MediaClassifier.classify(url) ?: if (tag == "audio") MediaKind.AUDIO else MediaKind.VIDEO
        add(url, kind, null)
    }

    private fun add(url: String, kind: MediaKind, req: Map<String, String>?) {
        val headers = LinkedHashMap<String, String>()
        fun pick(n: String) = req?.entries?.firstOrNull { it.key.equals(n, true) }?.value
        headers["User-Agent"] = pick("User-Agent") ?: userAgent
        (pick("Referer") ?: pageUrl)?.let { headers["Referer"] = it }
        (pick("Origin") ?: UrlInput.origin(pageUrl))?.let { headers["Origin"] = it }
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        val added = synchronized(items) {
            if (items.containsKey(url)) false else { items[url] = DetectedMedia(url, kind, headers, MediaClassifier.guessMime(url)); true }
        }
        if (added) notifyChange()
    }

    private fun notifyChange() {
        val snapshot = synchronized(items) { items.values.toList() }
        main.post { onChange(snapshot) }
    }
}
