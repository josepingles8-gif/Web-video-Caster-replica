package com.beamcast.sender.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Embedded HTTP server (Range-capable) that exposes one picked document to the TV under a random token. */
object LocalServer {
    class NoNetwork : Exception()

    class Served(val uri: Uri, val name: String, val mime: String, val size: Long, val url: String)

    private val files = ConcurrentHashMap<String, Served>()
    private var server: Http? = null
    private var boundIp: String? = null
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun publish(ctx: Context, uri: Uri): Served {
        val app = ctx.applicationContext
        appContext = app
        val ip = localIp() ?: throw NoNetwork()
        var s = server
        if (s == null || boundIp != ip) {
            s?.stop()
            s = Http(ip).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            server = s
            boundIp = ip
        }
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = app.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: MediaClassifier.guessMime(name) ?: "application/octet-stream"
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val served = Served(uri, name, mime, size, "http://$ip:${s.listeningPort}/$token")
        files.clear()
        files[token] = served
        return served
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        boundIp = null
        files.clear()
    }

    val isRunning: Boolean get() = server != null

    /** Site-local IPv4 of the Wi-Fi (or Ethernet/hotspot) interface. */
    fun localIp(): String? = try {
        val all = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { ni.name to it.hostAddress } }
        (all.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") || it.first.startsWith("ap") } ?: all.firstOrNull())?.second
    } catch (e: Exception) { null }

    private class Limited(private val inner: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val r = inner.read()
            if (r >= 0 && remaining > 0) remaining--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) return -1
            val n = inner.read(b, off, if (remaining < 0) len else minOf(len.toLong(), remaining).toInt())
            if (n > 0 && remaining > 0) remaining -= n
            return n
        }
        override fun available() = inner.available()
        override fun close() = inner.close()
    }

    private fun open(f: Served, start: Long, len: Long): InputStream {
        val cr = appContext!!.contentResolver
        try {
            val pfd = cr.openFileDescriptor(f.uri, "r")
            if (pfd != null) {
                val ins = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                try {
                    ins.channel.position(start)
                    return Limited(ins, len)
                } catch (e: IOException) {
                    ins.close()
                }
            }
        } catch (e: java.io.FileNotFoundException) { /* fall through */ }
        val ins = cr.openInputStream(f.uri) ?: throw IOException("Cannot open")
        var left = start
        while (left > 0) {
            val n = ins.skip(left)
            if (n <= 0) break
            left -= n
        }
        return Limited(ins, len)
    }

    private class Http(ip: String) : NanoHTTPD(ip, 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
            }
            val token = session.uri.trimStart('/').substringBefore('/')
            val f = files[token] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            val size = f.size
            var start = 0L
            var end = size - 1
            var partial = false
            val range = session.headers["range"]
            if (size >= 0 && range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=").substringBefore(',').trim()
                val a = spec.substringBefore('-')
                val b = spec.substringAfter('-', "")
                try {
                    if (a.isEmpty()) { start = maxOf(0L, size - b.toLong()) }
                    else { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
                    if (start >= size || start > end) {
                        return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                            addHeader("Content-Range", "bytes */$size")
                        }
                    }
                    partial = true
                } catch (e: NumberFormatException) {
                    start = 0; end = size - 1
                }
            }
            val len = if (size >= 0) end - start + 1 else -1L
            val stream = try { open(f, start, len) } catch (e: IOException) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Read error")
            }
            val resp = if (len >= 0) {
                newFixedLengthResponse(if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, f.mime, stream, len)
            } else newChunkedResponse(Response.Status.OK, f.mime, stream)
            resp.addHeader("Accept-Ranges", "bytes")
            resp.addHeader("Access-Control-Allow-Origin", "*")
            if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$size")
            return resp
        }
    }
}
