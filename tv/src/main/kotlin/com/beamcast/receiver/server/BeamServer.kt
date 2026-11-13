package com.beamcast.receiver.server

import com.beamcast.protocol.BeamProtocol
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException

/** NanoHTTPD front-end: delegates everything to [RequestRouter]. */
class BeamServer(
    port: Int,
    private val router: RequestRouter,
    /** Receives an uploaded receiver APK (a temp file deleted after the call) and returns an error message or null. */
    private val onUpdate: (File) -> String?,
) : NanoHTTPD(port) {

    private class Status(private val code: Int, private val text: String) : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code $text"
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Status(204, "No Content"), MIME_PLAINTEXT, ""))
        if (session.uri.trimEnd('/') == BeamProtocol.PATH_UPDATE) return update(session)
        var body: String? = null
        if (session.method == Method.POST || session.method == Method.PUT) {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                body = files["postData"] ?: session.queryParameterString
            } catch (e: IOException) {
                return respond(router.badRequest("Could not read body"))
            } catch (e: ResponseException) {
                return respond(router.badRequest("Could not read body"))
            }
        }
        return respond(router.handle(session.method.name, session.uri, body))
    }

    private fun update(session: IHTTPSession): Response {
        if (session.method != Method.PUT) return respond(HttpResult(405, "{}", "PUT"))
        val files = HashMap<String, String>()
        val path = try {
            session.parseBody(files)
            files["content"]
        } catch (e: IOException) {
            null
        } catch (e: ResponseException) {
            null
        } ?: return respond(router.badRequest("Could not read the update"))
        val error = onUpdate(File(path))
        return respond(if (error == null) HttpResult(200, "{}") else router.badRequest(error))
    }

    private fun respond(r: HttpResult): Response {
        val resp = newFixedLengthResponse(Status(r.status, "Status"), BeamProtocol.CONTENT_TYPE, r.body)
        r.allow?.let { resp.addHeader("Allow", it) }
        return cors(resp)
    }

    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        r.addHeader("Access-Control-Max-Age", "600")
        return r
    }

    companion object {
        /** Starts on the default port, or on any free port if it is taken. */
        fun startWithFallback(router: RequestRouter, onUpdate: (File) -> String?): BeamServer {
            for (port in listOf(BeamProtocol.DEFAULT_PORT, 0)) {
                val s = BeamServer(port, router, onUpdate)
                try {
                    s.start(SOCKET_READ_TIMEOUT, true)
                    return s
                } catch (_: IOException) {
                    s.stop()
                }
            }
            throw IOException("Could not bind any port")
        }
    }
}
