package com.beamcast.sender.cast.dlna

import com.beamcast.protocol.ControlAction
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.sender.cast.CastException
import com.beamcast.sender.cast.CastTarget
import com.beamcast.sender.cast.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object DlnaTarget : CastTarget {
    private class Loaded(val title: String?, val url: String)
    private val loaded = HashMap<String, Loaded>()

    private suspend fun soap(url: String, service: String, action: String, args: List<Pair<String, String>>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPACTION", DlnaProtocol.soapActionHeader(service, action))
                conn.outputStream.use { it.write(DlnaProtocol.soapEnvelope(service, action, args).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw CastException(DlnaProtocol.parseFault(text) ?: "HTTP $code")
                DlnaProtocol.parseSoapResponse(text)
            } catch (e: CastException) {
                throw e
            } catch (e: IOException) {
                throw CastException(e.message ?: e.javaClass.simpleName, e)
            } finally {
                conn.disconnect()
            }
        }

    private fun av(d: Device) = d.avTransportUrl ?: throw CastException("Missing AVTransport URL")

    private suspend fun avCall(d: Device, action: String, vararg args: Pair<String, String>) =
        soap(av(d), DlnaProtocol.AVTRANSPORT, action, listOf("InstanceID" to "0") + args)

    private suspend fun position(d: Device): Long =
        DlnaProtocol.parseTime(avCall(d, "GetPositionInfo")["RelTime"])

    override suspend fun load(device: Device, req: LoadRequest): PlaybackStatus {
        val mime = req.mimeType ?: "video/mp4"
        val title = req.title ?: req.url
        avCall(device, "SetAVTransportURI", "CurrentURI" to req.url, "CurrentURIMetaData" to DlnaProtocol.didl(title, req.url, mime))
        avCall(device, "Play", "Speed" to "1")
        synchronized(loaded) { loaded[av(device)] = Loaded(req.title, req.url) }
        if (req.startPositionMs > 0) {
            runCatching { avCall(device, "Seek", "Unit" to "REL_TIME", "Target" to DlnaProtocol.formatTime(req.startPositionMs)) }
        }
        return status(device)
    }

    override suspend fun control(device: Device, action: String, value: Long?): PlaybackStatus {
        when (action) {
            ControlAction.PLAY -> avCall(device, "Play", "Speed" to "1")
            ControlAction.PAUSE -> avCall(device, "Pause")
            ControlAction.STOP -> avCall(device, "Stop")
            ControlAction.SEEK -> seek(device, value ?: 0)
            ControlAction.SEEK_RELATIVE -> seek(device, position(device) + (value ?: 0))
            ControlAction.VOLUME -> {
                val url = device.renderingControlUrl ?: throw CastException("Volume not supported")
                soap(
                    url, DlnaProtocol.RENDERING_CONTROL, "SetVolume",
                    listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (value ?: 0).coerceIn(0, 100).toString()),
                )
            }
            else -> {}
        }
        return status(device)
    }

    private suspend fun seek(d: Device, ms: Long) {
        avCall(d, "Seek", "Unit" to "REL_TIME", "Target" to DlnaProtocol.formatTime(ms))
    }

    override suspend fun status(device: Device): PlaybackStatus {
        val state = DlnaProtocol.mapState(avCall(device, "GetTransportInfo")["CurrentTransportState"])
        val pos = avCall(device, "GetPositionInfo")
        val volume = device.renderingControlUrl?.let { url ->
            runCatching {
                soap(url, DlnaProtocol.RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))["CurrentVolume"]?.trim()?.toIntOrNull()
            }.getOrNull()
        } ?: 100
        val l = synchronized(loaded) { loaded[av(device)] }
        return PlaybackStatus(
            state = state,
            positionMs = DlnaProtocol.parseTime(pos["RelTime"]),
            durationMs = DlnaProtocol.parseTime(pos["TrackDuration"]),
            volume = volume,
            title = l?.title,
            url = l?.url,
        )
    }
}
