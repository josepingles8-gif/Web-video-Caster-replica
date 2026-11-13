package com.beamcast.sender.cast.chromecast

import android.content.Context
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.beamcast.protocol.ControlAction
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackState
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.sender.R
import com.beamcast.sender.cast.CastException
import com.beamcast.sender.cast.CastSession
import com.beamcast.sender.cast.CastTarget
import com.beamcast.sender.cast.Device
import com.beamcast.sender.cast.DeviceType
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession as GmsCastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Lazy, defensive access to the Cast SDK. Every method here must run on (or hops to) the main thread. */
object ChromecastSupport {
    private var appContext: Context? = null
    private var castContext: CastContext? = null
    private var initTried = false
    private var endListenerAdded = false

    private fun str(id: Int, fallback: String): String = appContext?.getString(id) ?: fallback

    /** Returns the CastContext, or null when Play services are missing or init failed (Chromecast is then hidden). */
    fun context(ctx: Context): CastContext? {
        appContext = ctx.applicationContext
        if (castContext != null || initTried) return castContext
        initTried = true
        castContext = try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) != ConnectionResult.SUCCESS) null
            else CastContext.getSharedInstance(ctx.applicationContext)
        } catch (e: Throwable) {
            null
        }
        castContext?.let(::watchSessionEnd)
        return castContext
    }

    private fun watchSessionEnd(cc: CastContext) {
        if (endListenerAdded) return
        endListenerAdded = true
        cc.sessionManager.addSessionManagerListener(object : SessionManagerListener<GmsCastSession> {
            override fun onSessionEnded(session: GmsCastSession, error: Int) = ended()
            override fun onSessionStartFailed(session: GmsCastSession, error: Int) {}
            override fun onSessionStarted(session: GmsCastSession, sessionId: String) {}
            override fun onSessionStarting(session: GmsCastSession) {}
            override fun onSessionResumed(session: GmsCastSession, wasSuspended: Boolean) {}
            override fun onSessionResumeFailed(session: GmsCastSession, error: Int) {}
            override fun onSessionResuming(session: GmsCastSession, sessionId: String) {}
            override fun onSessionSuspended(session: GmsCastSession, reason: Int) {}
            override fun onSessionEnding(session: GmsCastSession) {}
            private fun ended() {
                if (CastSession.device.value?.type == DeviceType.CHROMECAST) CastSession.device.value = null
            }
        }, GmsCastSession::class.java)
    }

    fun selector(ctx: Context): MediaRouteSelector? = context(ctx)?.mergedSelector

    /** Ends the Cast session (if any) and returns the media route to the default one. */
    fun disconnect() {
        runCatching {
            castContext?.sessionManager?.endCurrentSession(true)
        }
    }

    /** Selects the route for [d] and waits until the Cast session is up. */
    suspend fun connect(ctx: Context, d: Device) = withContext(Dispatchers.Main) {
        val cc = context(ctx) ?: throw CastException(str(R.string.chromecast_unavailable, "Chromecast is not available on this device"))
        val router = MediaRouter.getInstance(ctx.applicationContext)
        val route = router.routes.firstOrNull { it.id == d.routeId }
            ?: throw CastException(str(R.string.chromecast_not_found, "Chromecast not found"))
        val sm = cc.sessionManager
        sm.currentCastSession?.takeIf { it.isConnected && it.castDevice?.friendlyName == d.name }?.let { return@withContext }
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val l = object : SessionManagerListener<GmsCastSession> {
                        fun done(ok: Boolean) {
                            sm.removeSessionManagerListener(this, GmsCastSession::class.java)
                            if (cont.isActive) cont.resumeWith(
                                if (ok) kotlin.Result.success(Unit)
                                else kotlin.Result.failure(CastException(str(R.string.chromecast_session_failed, "Could not start the Chromecast session"))),
                            )
                        }
                        override fun onSessionStarted(session: GmsCastSession, sessionId: String) = done(true)
                        override fun onSessionResumed(session: GmsCastSession, wasSuspended: Boolean) = done(true)
                        override fun onSessionStartFailed(session: GmsCastSession, error: Int) = done(false)
                        override fun onSessionResumeFailed(session: GmsCastSession, error: Int) = done(false)
                        override fun onSessionStarting(session: GmsCastSession) {}
                        override fun onSessionResuming(session: GmsCastSession, sessionId: String) {}
                        override fun onSessionEnding(session: GmsCastSession) {}
                        override fun onSessionEnded(session: GmsCastSession, error: Int) {}
                        override fun onSessionSuspended(session: GmsCastSession, reason: Int) {}
                    }
                    sm.addSessionManagerListener(l, GmsCastSession::class.java)
                    cont.invokeOnCancellation { sm.removeSessionManagerListener(l, GmsCastSession::class.java) }
                    router.selectRoute(route)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw CastException(str(R.string.chromecast_timeout, "Timed out connecting to the Chromecast"))
        }
    }

    internal fun session(): GmsCastSession {
        check(Looper.myLooper() == Looper.getMainLooper())
        return castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }
            ?: throw CastException(str(R.string.chromecast_not_connected, "Not connected to a Chromecast"))
    }

    /** Callback-based PendingResult to suspend. */
    internal suspend fun <R : Result> PendingResult<R>.await(): R = suspendCancellableCoroutine { cont ->
        setResultCallback { if (cont.isActive) cont.resume(it) }
        cont.invokeOnCancellation { cancel() }
    }

    internal fun client(): RemoteMediaClient =
        session().remoteMediaClient ?: throw CastException(str(R.string.chromecast_not_connected, "Not connected to a Chromecast"))
}

/** Discovers Cast routes through MediaRouter. Create, start and stop on the main thread. */
class ChromecastDiscovery(private val context: Context) {
    val devices = MutableStateFlow<List<Device>>(emptyList())
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    fun start() {
        if (router != null) return
        val sel = ChromecastSupport.selector(context) ?: return
        try {
            val r = MediaRouter.getInstance(context.applicationContext)
            selector = sel
            router = r
            r.addCallback(sel, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            refresh()
        } catch (e: Throwable) {
            router = null
        }
    }

    fun stop() {
        router?.let { runCatching { it.removeCallback(callback) } }
        router = null
    }

    private fun refresh() {
        val r = router ?: return
        val sel = selector ?: return
        devices.value = r.routes
            .filter { !it.isDefault && it.matchesSelector(sel) }
            .map { Device(it.name, "", 0, DeviceType.CHROMECAST, routeId = it.id) }
    }
}

object ChromecastTarget : CastTarget {
    private fun guessMime(url: String, mime: String?): String {
        val m = mime?.lowercase()
        return when {
            m == "application/vnd.apple.mpegurl" || m == "application/x-mpegurl" -> "application/x-mpegURL"
            m != null -> m
            url.substringBefore('?').endsWith(".m3u8", true) -> "application/x-mpegURL"
            url.substringBefore('?').endsWith(".mpd", true) -> "application/dash+xml"
            else -> "video/mp4"
        }
    }

    override suspend fun load(device: Device, req: LoadRequest): PlaybackStatus = withContext(Dispatchers.Main) {
        val client = ChromecastSupport.client()
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC)
        req.title?.let { meta.putString(MediaMetadata.KEY_TITLE, it) }
        val info = MediaInfo.Builder(req.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(guessMime(req.url, req.mimeType))
            .setMetadata(meta)
            .build()
        val data = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(req.startPositionMs)
            .build()
        with(ChromecastSupport) {
            val res = client.load(data).await()
            if (!res.status.isSuccess) throw CastException(res.status.statusMessage ?: "Cast load failed (${res.status.statusCode})")
        }
        buildStatus()
    }

    override suspend fun control(device: Device, action: String, value: Long?): PlaybackStatus = withContext(Dispatchers.Main) {
        val client = ChromecastSupport.client()
        when (action) {
            ControlAction.PLAY -> client.play()
            ControlAction.PAUSE -> client.pause()
            ControlAction.STOP -> client.stop()
            ControlAction.SEEK -> client.seek(MediaSeekOptions.Builder().setPosition(value ?: 0).build())
            ControlAction.SEEK_RELATIVE ->
                client.seek(MediaSeekOptions.Builder().setPosition((client.approximateStreamPosition + (value ?: 0)).coerceAtLeast(0)).build())
            ControlAction.VOLUME -> try {
                ChromecastSupport.session().volume = (value ?: 0).coerceIn(0, 100) / 100.0
            } catch (e: java.io.IOException) {
                throw CastException(e.message ?: "Volume failed", e)
            }
        }
        buildStatus()
    }

    override suspend fun status(device: Device): PlaybackStatus = withContext(Dispatchers.Main) { buildStatus() }

    private fun buildStatus(): PlaybackStatus {
        val session = ChromecastSupport.session()
        val client = session.remoteMediaClient ?: return PlaybackStatus()
        val state = when (client.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> PlaybackState.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> PlaybackState.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING -> PlaybackState.BUFFERING
            MediaStatus.PLAYER_STATE_LOADING -> PlaybackState.LOADING
            MediaStatus.PLAYER_STATE_IDLE -> when (client.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> PlaybackState.ENDED
                MediaStatus.IDLE_REASON_ERROR -> PlaybackState.ERROR
                else -> PlaybackState.IDLE
            }
            else -> PlaybackState.IDLE
        }
        val info = client.mediaInfo
        return PlaybackStatus(
            state = state,
            positionMs = client.approximateStreamPosition.coerceAtLeast(0),
            durationMs = client.streamDuration.coerceAtLeast(0),
            volume = (session.volume * 100).toInt().coerceIn(0, 100),
            title = info?.metadata?.getString(MediaMetadata.KEY_TITLE),
            url = info?.contentId,
            error = if (state == PlaybackState.ERROR) "Playback error" else null,
        )
    }
}
