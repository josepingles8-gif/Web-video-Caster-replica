package com.beamcast.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * BeamCast wire protocol (v1). The TV receiver runs a plain HTTP server on the LAN and
 * advertises it over mDNS/NSD; the phone sender discovers it and sends JSON commands.
 *
 *   GET  /info     -> DeviceInfo
 *   GET  /status   -> PlaybackStatus
 *   POST /load     <- LoadRequest     -> PlaybackStatus
 *   POST /control  <- ControlRequest  -> PlaybackStatus
 *
 * Errors: non-2xx with an ErrorResponse body. All bodies are UTF-8 JSON.
 */
object BeamProtocol {
    const val VERSION = 1
    /** NSD service type (NsdManager wants it without the trailing dot on register). */
    const val SERVICE_TYPE = "_beamcast._tcp"
    const val DEFAULT_PORT = 8765
    const val PATH_INFO = "/info"
    const val PATH_STATUS = "/status"
    const val PATH_LOAD = "/load"
    const val PATH_CONTROL = "/control"
    const val CONTENT_TYPE = "application/json; charset=utf-8"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
}

@Serializable
data class DeviceInfo(
    val name: String,
    val appVersion: String,
    val protocolVersion: Int = BeamProtocol.VERSION,
)

@Serializable
data class LoadRequest(
    val url: String,
    val title: String? = null,
    /** e.g. application/x-mpegURL, application/dash+xml, video/mp4, audio/mpeg, image/jpeg. Null = sniff from URL. */
    val mimeType: String? = null,
    /** HTTP headers the receiver must send when fetching the media (User-Agent, Referer, Cookie...). */
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0,
    val subtitleUrl: String? = null,
)

object ControlAction {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val STOP = "stop"
    /** value = absolute position in ms */
    const val SEEK = "seek"
    /** value = signed delta in ms */
    const val SEEK_RELATIVE = "seekRelative"
    /** value = 0..100 */
    const val VOLUME = "volume"
}

@Serializable
data class ControlRequest(val action: String, val value: Long? = null)

object PlaybackState {
    const val IDLE = "idle"
    const val LOADING = "loading"
    const val BUFFERING = "buffering"
    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val ENDED = "ended"
    const val ERROR = "error"
}

@Serializable
data class PlaybackStatus(
    val state: String = PlaybackState.IDLE,
    val positionMs: Long = 0,
    /** 0 when unknown or live. */
    val durationMs: Long = 0,
    val volume: Int = 100,
    val title: String? = null,
    val url: String? = null,
    val error: String? = null,
)

@Serializable
data class ErrorResponse(val error: String)
