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
 *   POST /key      <- KeyRequest      -> RemoteResult   (TV remote: navigate the TV itself)
 *   POST /text     <- TextRequest     -> RemoteResult   (type into the focused text field on the TV)
 *
 *   PUT  /update   <- receiver APK bytes               (the TV then asks the user to install it)
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
    const val PATH_KEY = "/key"
    const val PATH_TEXT = "/text"
    const val PATH_UPDATE = "/update"
    const val CONTENT_TYPE = "application/json; charset=utf-8"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
}

@Serializable
data class DeviceInfo(
    val name: String,
    val appVersion: String,
    val protocolVersion: Int = BeamProtocol.VERSION,
    /** True when the receiver's system remote service (accessibility) is enabled, so /key can navigate the whole TV. */
    val remoteEnabled: Boolean = false,
    /** Receiver build number; 0 for receivers that cannot be updated from the phone. */
    val versionCode: Int = 0,
    /** Stack trace of the receiver's last crash, if any, so the phone can show it. */
    val lastCrash: String? = null,
    /** False when picture enhancement crashed this receiver and is therefore disabled on it. */
    val enhanceSupported: Boolean = true,
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
    /** One of [VideoQuality]. Only matters for adaptive (HLS/DASH) streams. */
    val quality: String = VideoQuality.MAX,
    /** Upscale and sharpen the picture on the TV's GPU. The TV turns it off by itself if it cannot keep up. */
    val enhance: Boolean = false,
)

object VideoQuality {
    /** Adapt to the measured bandwidth (fewer stalls, may drop resolution). */
    const val AUTO = "auto"
    /** Always the best rendition the TV can decode. */
    const val MAX = "max"
}

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
    /** value = 0 for [VideoQuality.AUTO], 1 for [VideoQuality.MAX]; applies to the current playback immediately. */
    const val QUALITY = "quality"
    /** value = 1 to turn picture enhancement on, 0 to turn it off; applies to the current playback. */
    const val ENHANCE = "enhance"
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
    /** Resolution actually being decoded, as the short side in pixels (1080 for 1080p). 0 = unknown / no video. */
    val videoHeight: Int = 0,
    /** One of [VideoQuality]: the mode currently applied. */
    val quality: String = VideoQuality.MAX,
    /** Whether picture enhancement is currently applied. */
    val enhance: Boolean = false,
)

/** Keys accepted by POST /key. */
object RemoteKey {
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val OK = "ok"
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val VOLUME_UP = "volumeUp"
    const val VOLUME_DOWN = "volumeDown"
    const val MUTE = "mute"
    const val PLAY_PAUSE = "playPause"
    const val REWIND = "rewind"
    const val FAST_FORWARD = "fastForward"
    const val BACKSPACE = "backspace"
    val ALL = setOf(UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, RECENTS, VOLUME_UP, VOLUME_DOWN, MUTE, PLAY_PAUSE, REWIND, FAST_FORWARD, BACKSPACE)
}

@Serializable
data class KeyRequest(val key: String)

/** replace = true sets the whole field, false appends to what is already there. */
@Serializable
data class TextRequest(val text: String, val replace: Boolean = false)

@Serializable
data class RemoteResult(
    /** False when the key could not be delivered (see [message]). */
    val ok: Boolean,
    /** Mirrors [DeviceInfo.remoteEnabled] so the phone can prompt the user to enable the service on the TV. */
    val remoteEnabled: Boolean,
    val message: String? = null,
)

@Serializable
data class ErrorResponse(val error: String)
