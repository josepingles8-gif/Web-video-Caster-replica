package com.beamcast.sender.cast

import com.beamcast.protocol.BeamProtocol
import com.beamcast.protocol.ControlRequest
import com.beamcast.protocol.DeviceInfo
import com.beamcast.protocol.ErrorResponse
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.KeyRequest
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.protocol.RemoteResult
import com.beamcast.protocol.TextRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CastException(message: String, cause: Throwable? = null) : Exception(message, cause)

object CastClient {
    private val json = BeamProtocol.json

    private suspend fun call(d: Device, method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val conn = URL(d.baseUrl + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 4000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", BeamProtocol.CONTENT_TYPE)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull()
                    throw CastException(msg ?: "HTTP $code")
                }
                text
            } catch (e: CastException) {
                throw e
            } catch (e: IOException) {
                throw CastException(e.message ?: e.javaClass.simpleName, e)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw CastException("Invalid response", e)
            } finally {
                conn.disconnect()
            }
        }

    /** Uploads a receiver APK; the TV then shows its install prompt. */
    suspend fun update(d: Device, size: Long, open: () -> java.io.InputStream) = withContext(Dispatchers.IO) {
        val conn = URL(d.baseUrl + BeamProtocol.PATH_UPDATE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 4000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(size)
            conn.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
            open().use { input -> conn.outputStream.use { input.copyTo(it) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw CastException(runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull() ?: "HTTP $code")
            }
        } catch (e: IOException) {
            throw CastException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun info(d: Device): DeviceInfo = json.decodeFromString(call(d, "GET", BeamProtocol.PATH_INFO))
    suspend fun status(d: Device): PlaybackStatus = json.decodeFromString(call(d, "GET", BeamProtocol.PATH_STATUS))

    suspend fun load(d: Device, req: LoadRequest): PlaybackStatus =
        json.decodeFromString(call(d, "POST", BeamProtocol.PATH_LOAD, json.encodeToString(req)))

    suspend fun control(d: Device, action: String, value: Long? = null): PlaybackStatus =
        json.decodeFromString(call(d, "POST", BeamProtocol.PATH_CONTROL, json.encodeToString(ControlRequest(action, value))))

    suspend fun key(d: Device, key: String): RemoteResult =
        json.decodeFromString(call(d, "POST", BeamProtocol.PATH_KEY, json.encodeToString(KeyRequest(key))))

    suspend fun text(d: Device, text: String, replace: Boolean = false): RemoteResult =
        json.decodeFromString(call(d, "POST", BeamProtocol.PATH_TEXT, json.encodeToString(TextRequest(text, replace))))
}
