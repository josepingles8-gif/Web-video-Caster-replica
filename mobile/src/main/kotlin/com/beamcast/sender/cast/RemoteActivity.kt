package com.beamcast.sender.cast

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.beamcast.protocol.ControlAction
import com.beamcast.protocol.PlaybackState
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.sender.R
import com.beamcast.sender.databinding.ActivityRemoteBinding
import com.beamcast.sender.media.MediaServerService
import com.beamcast.sender.media.TimeFormat
import com.beamcast.sender.ui.applySystemBarsPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RemoteActivity : AppCompatActivity() {
    private lateinit var b: ActivityRemoteBinding
    private var device: Device? = null
    private var playing = false
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()
        b.toolbar.setNavigationOnClickListener { finish() }
        device = CastSession.device.value
        if (device == null) { finish(); return }
        b.toolbar.subtitle = device?.name
        startPolling(device!!)

        b.play.setOnClickListener { send(if (playing) ControlAction.PAUSE else ControlAction.PLAY) }
        b.back10.setOnClickListener { send(ControlAction.SEEK_RELATIVE, -10_000) }
        b.fwd30.setOnClickListener { send(ControlAction.SEEK_RELATIVE, 30_000) }
        b.stop.setOnClickListener {
            send(ControlAction.STOP)
            MediaServerService.stop(this)
            finish()
        }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) b.position.text = TimeFormat.format(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                send(ControlAction.SEEK, s.progress.toLong())
            }
        })
        b.volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { volDragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                volDragging = false
                send(ControlAction.VOLUME, s.progress.toLong())
            }
        })
    }

    private var volDragging = false

    private fun startPolling(d: Device) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try { render(CastClient.status(d)) } catch (e: Exception) { showError(e.message) }
                    delay(1000)
                }
            }
        }
    }

    private fun send(action: String, value: Long? = null) {
        val d = device ?: return
        lifecycleScope.launch {
            try { render(CastClient.control(d, action, value)) } catch (e: Exception) { showError(e.message) }
        }
    }

    private fun showError(msg: String?) {
        b.error.text = msg ?: getString(R.string.state_error)
        b.error.visibility = android.view.View.VISIBLE
    }

    private fun render(s: PlaybackStatus) {
        b.title.text = s.title ?: s.url ?: getString(R.string.remote_no_title)
        b.state.text = getString(
            when (s.state) {
                PlaybackState.LOADING -> R.string.state_loading
                PlaybackState.BUFFERING -> R.string.state_buffering
                PlaybackState.PLAYING -> R.string.state_playing
                PlaybackState.PAUSED -> R.string.state_paused
                PlaybackState.ENDED -> R.string.state_ended
                PlaybackState.ERROR -> R.string.state_error
                else -> R.string.state_idle
            },
        )
        if (s.error != null) showError(s.error) else b.error.visibility = android.view.View.GONE
        playing = s.state == PlaybackState.PLAYING || s.state == PlaybackState.BUFFERING
        b.play.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.play.contentDescription = getString(if (playing) R.string.btn_pause else R.string.btn_play)
        val dur = s.durationMs.coerceAtMost(Int.MAX_VALUE.toLong())
        b.seek.isEnabled = dur > 0
        if (!dragging) {
            b.seek.max = dur.toInt()
            b.seek.progress = s.positionMs.coerceIn(0, dur).toInt()
            b.position.text = TimeFormat.format(s.positionMs)
        }
        b.duration.text = if (dur > 0) TimeFormat.format(dur) else ""
        if (!volDragging) b.volume.progress = s.volume.coerceIn(0, 100)
    }
}
