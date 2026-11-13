package com.beamcast.receiver.server

import com.beamcast.protocol.BeamProtocol
import com.beamcast.protocol.ControlAction
import com.beamcast.protocol.ControlRequest
import com.beamcast.protocol.DeviceInfo
import com.beamcast.protocol.ErrorResponse
import com.beamcast.protocol.KeyRequest
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.protocol.RemoteKey
import com.beamcast.protocol.RemoteResult
import com.beamcast.protocol.TextRequest
import kotlinx.serialization.KSerializer

/** What the HTTP layer needs from the player. Implementations must be thread-safe (called from server threads). */
interface PlayerController {
    fun status(): PlaybackStatus
    fun load(request: LoadRequest): PlaybackStatus
    fun control(request: ControlRequest): PlaybackStatus
}

/** TV-wide remote control (accessibility-backed on device). Implementations must be thread-safe. */
interface RemoteController {
    fun enabled(): Boolean
    fun key(key: String): RemoteResult
    fun text(req: TextRequest): RemoteResult
}

data class HttpResult(val status: Int, val body: String, val allow: String? = null)

/** Android-free routing and JSON handling for the BeamCast protocol. */
class RequestRouter(
    private val controller: PlayerController,
    private val remote: RemoteController,
    private val deviceInfo: () -> DeviceInfo,
) {
    fun handle(method: String, rawPath: String, body: String?): HttpResult {
        val path = rawPath.substringBefore('?').let { if (it.length > 1) it.trimEnd('/') else it }
        val verb = method.uppercase()
        return try {
            when (path) {
                BeamProtocol.PATH_INFO -> get(verb) { ok(DeviceInfo.serializer(), deviceInfo().copy(remoteEnabled = remote.enabled())) }
                BeamProtocol.PATH_STATUS -> get(verb) { ok(PlaybackStatus.serializer(), controller.status()) }
                BeamProtocol.PATH_LOAD -> post(verb) { load(body) }
                BeamProtocol.PATH_CONTROL -> post(verb) { control(body) }
                BeamProtocol.PATH_KEY -> post(verb) { key(body) }
                BeamProtocol.PATH_TEXT -> post(verb) { text(body) }
                else -> error(404, "Unknown path: $path")
            }
        } catch (e: IllegalArgumentException) {
            error(400, e.message ?: "Bad request")
        } catch (e: Exception) {
            error(500, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun load(body: String?): HttpResult {
        val req = parse(LoadRequest.serializer(), body)
        require(req.url.startsWith("http://", true) || req.url.startsWith("https://", true)) {
            "url must be an http(s) URL"
        }
        require(req.startPositionMs >= 0) { "startPositionMs must be >= 0" }
        return ok(PlaybackStatus.serializer(), controller.load(req))
    }

    private fun control(body: String?): HttpResult {
        val req = parse(ControlRequest.serializer(), body)
        when (req.action) {
            ControlAction.PLAY, ControlAction.PAUSE, ControlAction.STOP -> Unit
            ControlAction.SEEK, ControlAction.SEEK_RELATIVE, ControlAction.VOLUME ->
                requireNotNull(req.value) { "action '${req.action}' requires a value" }
            ControlAction.QUALITY -> {
                val v = requireNotNull(req.value) { "action 'quality' requires a value" }
                require(v == 0L || v == 1L) { "quality value must be 0 (auto) or 1 (max)" }
            }
            else -> throw IllegalArgumentException("Unknown action: ${req.action}")
        }
        return ok(PlaybackStatus.serializer(), controller.control(req))
    }

    private fun key(body: String?): HttpResult {
        val req = parse(KeyRequest.serializer(), body)
        require(req.key in RemoteKey.ALL) { "Unknown key: ${req.key}" }
        return ok(RemoteResult.serializer(), remote.key(req.key))
    }

    private fun text(body: String?): HttpResult {
        val req = parse(TextRequest.serializer(), body)
        return ok(RemoteResult.serializer(), remote.text(req))
    }

    private fun <T> parse(s: KSerializer<T>, body: String?): T {
        require(!body.isNullOrBlank()) { "Empty body" }
        return try {
            BeamProtocol.json.decodeFromString(s, body)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    private inline fun get(verb: String, block: () -> HttpResult) =
        if (verb == "GET") block() else notAllowed("GET")

    private inline fun post(verb: String, block: () -> HttpResult) =
        if (verb == "POST") block() else notAllowed("POST")

    private fun notAllowed(allow: String) = error(405, "Method not allowed", allow)

    private fun <T> ok(s: KSerializer<T>, v: T) = HttpResult(200, BeamProtocol.json.encodeToString(s, v))

    fun badRequest(msg: String) = error(400, msg)

    private fun error(code: Int, msg: String, allow: String? = null) =
        HttpResult(code, BeamProtocol.json.encodeToString(ErrorResponse.serializer(), ErrorResponse(msg)), allow)
}
