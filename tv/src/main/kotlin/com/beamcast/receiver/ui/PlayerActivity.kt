package com.beamcast.receiver.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.beamcast.receiver.R
import com.beamcast.receiver.player.PlaybackManager as PM

/** Full-screen playback (video/audio via PlayerView, images via ImageView). D-pad driven. */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), PM.Ui {
    private lateinit var playerView: PlayerView
    private lateinit var imageView: ImageView
    private lateinit var titleText: TextView
    private lateinit var errorText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideTitle = Runnable { titleText.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        imageView = findViewById(R.id.image_view)
        titleText = findViewById(R.id.title_text)
        errorText = findViewById(R.id.error_text)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                PM.stop()
                finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        PM.ui = this
        if (!PM.active) { finish(); return }
        sync()
        showTitleBriefly()
    }

    override fun onStop() {
        if (PM.ui === this) PM.ui = null
        playerView.player = null
        handler.removeCallbacks(hideTitle)
        super.onStop()
    }

    override fun onLoaded() {
        sync()
        showTitleBriefly()
        if (!PM.isImage) playerView.showController()
    }

    override fun onChanged() = sync()

    override fun onStopped() { finish() }

    private fun sync() {
        val showImage = PM.isImage
        playerView.player = if (showImage) null else PM.player
        playerView.visibility = if (showImage) View.INVISIBLE else View.VISIBLE
        imageView.visibility = if (showImage && PM.image != null) View.VISIBLE else View.GONE
        imageView.setImageBitmap(PM.image)
        titleText.text = PM.title.orEmpty()
        val err = PM.error
        errorText.visibility = if (err != null) View.VISIBLE else View.GONE
        if (err != null) errorText.text = getString(R.string.playback_error, err)
    }

    private fun showTitleBriefly() {
        if (PM.title.isNullOrBlank()) return
        titleText.visibility = View.VISIBLE
        handler.removeCallbacks(hideTitle)
        handler.postDelayed(hideTitle, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down && event.repeatCount == 0) PM.togglePlayPause(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) PM.player?.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) PM.player?.pause(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) PM.seekBy(-PM.SEEK_BACK_MS); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) PM.seekBy(PM.SEEK_FORWARD_MS); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { if (down) { PM.stop(); finish() }; true }
            else -> false
        }
        if (handled && down && !PM.isImage) playerView.showController()
        return handled || super.dispatchKeyEvent(event)
    }
}
