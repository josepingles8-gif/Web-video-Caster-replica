package com.beamcast.receiver.player

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.beamcast.protocol.ControlAction
import com.beamcast.protocol.ControlRequest
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackState
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.protocol.VideoQuality
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Owns the ExoPlayer / image state. MAIN THREAD ONLY. */
@UnstableApi
object PlaybackManager {
    interface Ui {
        fun onLoaded()
        fun onChanged()
        fun onStopped()
    }

    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L

    private val imageExt = Regex("\\.(jpe?g|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)
    private val main = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private lateinit var app: Context

    var ui: Ui? = null
    var player: ExoPlayer? = null
        private set
    var title: String? = null
        private set
    var url: String? = null
        private set
    var error: String? = null
        private set
    var image: Bitmap? = null
        private set
    var isImage = false
        private set
    private var imageLoading = false
    private var hasMedia = false
    private var started = false
    private var serial = 0
    private var fixedVolume = 100
    private var quality = VideoQuality.MAX

    val active get() = hasMedia

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun load(req: LoadRequest): PlaybackStatus {
        release()
        serial++
        hasMedia = true
        started = false
        error = null
        quality = normalizeQuality(req.quality)
        title = req.title ?: req.url.substringBefore('?').substringAfterLast('/').ifBlank { null }
        url = req.url
        isImage = isImage(req)
        if (isImage) loadImage(req) else playMedia(req)
        ui?.onLoaded()
        return status()
    }

    private fun isImage(req: LoadRequest) =
        req.mimeType?.startsWith("image/", true) == true ||
            (req.mimeType.isNullOrBlank() && imageExt.containsMatchIn(req.url.substringBefore('?').substringBefore('#')))

    private fun loadImage(req: LoadRequest) {
        imageLoading = true
        val mine = serial
        imageExecutor.execute {
            val result = runCatching { ImageLoader.load(req.url, req.headers) }
            main.post {
                if (mine != serial) return@post
                imageLoading = false
                result.onSuccess { image = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
                ui?.onChanged()
            }
        }
    }

    private fun playMedia(req: LoadRequest) {
        val headers = req.headers.toMutableMap()
        val uaKey = headers.keys.firstOrNull { it.equals("User-Agent", true) }
        val ua = uaKey?.let { headers.remove(it) }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers)
        if (ua != null) http.setUserAgent(ua)

        val item = MediaItem.Builder().setUri(req.url).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title).build(),
        )
        mimeFor(req)?.let { item.setMimeType(it) }
        req.subtitleUrl?.takeIf { it.isNotBlank() }?.let {
            val sub = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it))
                .setMimeType(if (it.substringBefore('?').endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            item.setSubtitleConfigurations(listOf(sub))
        }
        val p = player ?: buildPlayer().also { player = it }
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item.build()), req.startPositionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        applyFixedVolume()
    }

    /** Explicit mime wins; otherwise sniff the URL. Null lets ExoPlayer treat it as progressive. */
    private fun mimeFor(req: LoadRequest): String? {
        val m = req.mimeType?.lowercase().orEmpty()
        val path = req.url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            "mpegurl" in m -> MimeTypes.APPLICATION_M3U8
            "dash+xml" in m -> MimeTypes.APPLICATION_MPD
            m.isBlank() && path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            m.isBlank() && path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private fun buildPlayer(): ExoPlayer {
        // Quality first: on a TV always pick the best rendition the decoder supports instead of
        // adapting down to the bandwidth estimate or to a small viewport, and buffer further ahead.
        val selector = DefaultTrackSelector(app)
        selector.setParameters(qualityParams(selector.buildUponParameters(), quality))
        val meter = DefaultBandwidthMeter.Builder(app).setInitialBitrateEstimate(25_000_000L).build()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        val p = ExoPlayer.Builder(app)
            .setTrackSelector(selector)
            .setBandwidthMeter(meter)
            .setLoadControl(loadControl)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) started = true
                ui?.onChanged()
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { ui?.onChanged() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { ui?.onChanged() }
            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}${e.cause?.message?.let { ": $it" } ?: ""}"
                ui?.onChanged()
            }
        })
        return p
    }

    private fun normalizeQuality(q: String) = if (q == VideoQuality.AUTO) VideoQuality.AUTO else VideoQuality.MAX

    private fun qualityParams(b: TrackSelectionParameters.Builder, q: String) =
        b.setForceHighestSupportedBitrate(q == VideoQuality.MAX)
            .clearViewportSizeConstraints()
            .clearVideoSizeConstraints()
            .build()

    private fun setQuality(q: String) {
        quality = q
        player?.let { p ->
            p.trackSelectionParameters = qualityParams(p.trackSelectionParameters.buildUpon(), q)
        }
    }

    fun status(): PlaybackStatus {
        val p = player
        var state = PlaybackState.IDLE
        var pos = 0L
        var dur = 0L
        var height = 0
        if (hasMedia) {
            state = when {
                error != null -> PlaybackState.ERROR
                isImage -> if (imageLoading) PlaybackState.LOADING else PlaybackState.PLAYING
                p == null -> PlaybackState.LOADING
                else -> when (p.playbackState) {
                    Player.STATE_IDLE -> PlaybackState.LOADING
                    Player.STATE_BUFFERING -> if (started) PlaybackState.BUFFERING else PlaybackState.LOADING
                    Player.STATE_READY -> if (p.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
                    else -> PlaybackState.ENDED
                }
            }
            if (p != null && !isImage) {
                pos = p.currentPosition.coerceAtLeast(0)
                height = minOf(p.videoSize.width, p.videoSize.height).takeIf { it > 0 } ?: p.videoSize.height
                dur = if (p.duration == C.TIME_UNSET || p.isCurrentMediaItemLive) 0 else p.duration.coerceAtLeast(0)
            }
        }
        return PlaybackStatus(state, pos, dur, volume(), if (hasMedia) title else null, if (hasMedia) url else null, error, height, quality)
    }

    fun control(req: ControlRequest): PlaybackStatus {
        val p = player
        when (req.action) {
            ControlAction.PLAY -> p?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() }
            ControlAction.PAUSE -> p?.pause()
            ControlAction.STOP -> stop()
            ControlAction.SEEK -> p?.seekTo((req.value ?: 0).coerceAtLeast(0))
            ControlAction.SEEK_RELATIVE -> seekBy(req.value ?: 0)
            ControlAction.VOLUME -> setVolume((req.value ?: 0).coerceIn(0, 100).toInt())
            ControlAction.QUALITY -> setQuality(if (req.value == 0L) VideoQuality.AUTO else VideoQuality.MAX)
        }
        return status()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying || p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause() else p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        var target = p.currentPosition + deltaMs
        if (p.duration != C.TIME_UNSET) target = target.coerceAtMost(p.duration)
        p.seekTo(target.coerceAtLeast(0))
    }

    fun stop() {
        serial++
        release()
        hasMedia = false
        error = null
        title = null
        url = null
        ui?.onStopped()
    }

    private fun release() {
        player?.release()
        player = null
        image = null
        imageLoading = false
        isImage = false
    }

    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun applyFixedVolume() {
        if (audio.isVolumeFixed) player?.volume = fixedVolume / 100f
    }

    fun volume(): Int {
        val am = audio
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (am.isVolumeFixed || max <= 0) return fixedVolume
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    fun setVolume(percent: Int) {
        fixedVolume = percent
        val am = audio
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (!am.isVolumeFixed && max > 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100f).roundToInt(), 0)
                return
            } catch (_: SecurityException) { /* fall through to player volume */ }
        }
        player?.volume = percent / 100f
    }
}
