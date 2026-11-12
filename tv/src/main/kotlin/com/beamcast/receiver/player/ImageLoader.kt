package com.beamcast.receiver.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Blocking image download + downsample. Call from a background thread. */
object ImageLoader {
    private const val MAX_BYTES = 40 * 1024 * 1024
    private const val TARGET_W = 1920
    private const val TARGET_H = 1080

    fun load(url: String, headers: Map<String, String>): Bitmap {
        var conn = open(url, headers)
        try {
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects++ < 8) {
                val loc = conn.getHeaderField("Location") ?: break
                val next = URL(URL(conn.url.toString()), loc).toString()
                conn.disconnect()
                conn = open(next, headers)
            }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw java.io.IOException("Image too large")
                }
            }
            return decode(out.toByteArray())
        } finally {
            conn.disconnect()
        }
    }

    internal fun decode(data: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_W && bounds.outHeight / (sample * 2) >= TARGET_H) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            ?: throw java.io.IOException("Unsupported image")
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }
}
