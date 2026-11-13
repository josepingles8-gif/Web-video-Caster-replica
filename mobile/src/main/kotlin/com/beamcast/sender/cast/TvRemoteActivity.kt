package com.beamcast.sender.cast

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beamcast.protocol.RemoteKey
import com.beamcast.protocol.RemoteResult
import com.beamcast.sender.R
import com.beamcast.sender.databinding.ActivityTvRemoteBinding
import com.beamcast.sender.ui.applySystemBarsPadding
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** The phone as the TV's remote control. Requests go out strictly in order through a single queue. */
class TvRemoteActivity : AppCompatActivity() {
    private lateinit var b: ActivityTvRemoteBinding
    private var device: Device? = null
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pending = 0
    private var swipeMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTvRemoteBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.applySystemBarsPadding()
        b.toolbar.setNavigationOnClickListener { finish() }
        val d = CastSession.device.value
        if (d == null) { finish(); return }
        device = d
        b.toolbar.subtitle = d.name

        lifecycleScope.launch {
            for (job in queue) {
                try { job() } finally { pending-- }
            }
        }

        val repeating = mapOf(
            b.keyUp to RemoteKey.UP, b.keyDown to RemoteKey.DOWN, b.keyLeft to RemoteKey.LEFT,
            b.keyRight to RemoteKey.RIGHT, b.keyVolDown to RemoteKey.VOLUME_DOWN, b.keyVolUp to RemoteKey.VOLUME_UP,
        )
        repeating.forEach { (v, key) -> v.setRepeatingKey(key) }
        mapOf(
            b.keyOk to RemoteKey.OK, b.keyBack to RemoteKey.BACK, b.keyHome to RemoteKey.HOME,
            b.keyRecents to RemoteKey.RECENTS, b.keyMute to RemoteKey.MUTE, b.keyPlayPause to RemoteKey.PLAY_PAUSE,
            b.keyRewind to RemoteKey.REWIND, b.keyFastForward to RemoteKey.FAST_FORWARD,
            b.keyBackspace to RemoteKey.BACKSPACE,
        ).forEach { (v, key) ->
            v.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                sendKey(key, false)
            }
        }

        b.modeToggle.setOnClickListener {
            swipeMode = !swipeMode
            b.dpad.visibility = if (swipeMode) View.GONE else View.VISIBLE
            b.swipePad.visibility = if (swipeMode) View.VISIBLE else View.GONE
            b.modeToggle.setText(if (swipeMode) R.string.tvremote_dpad_mode else R.string.tvremote_swipe_mode)
        }
        setupSwipePad()

        b.textSend.setOnClickListener { sendText() }
        b.textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
        }
    }

    override fun onResume() {
        super.onResume()
        val d = device ?: return
        lifecycleScope.launch {
            try { showRemoteEnabled(CastClient.info(d).remoteEnabled) } catch (e: CastException) { showStatus(e.message) }
        }
    }

    private fun showRemoteEnabled(enabled: Boolean) {
        b.banner.visibility = if (enabled || viaEShare) View.GONE else View.VISIBLE
    }

    private fun showStatus(msg: String?) {
        b.status.text = if (msg.isNullOrEmpty()) "" else getString(R.string.tvremote_error, msg)
    }

    private fun handle(r: RemoteResult) {
        showRemoteEnabled(r.remoteEnabled)
        showStatus(if (r.ok) null else r.message ?: "")
    }

    private fun enqueue(repeat: Boolean, job: suspend (Device) -> Unit) {
        val d = device ?: return
        if (repeat && pending > 3) return
        pending++
        if (queue.trySend { job(d) }.isFailure) pending--
    }

    /** True once the TV's built-in EShare service has accepted a key: navigation then goes through it. */
    private var viaEShare = false

    private fun sendKey(key: String, repeat: Boolean) = enqueue(repeat) { d ->
        val code = EShareRemote.keyCode(key)
        // Volume as a real key press changes the TV's own volume; the receiver can only change
        // Android's media volume, which many TVs ignore.
        val volume = key == RemoteKey.VOLUME_UP || key == RemoteKey.VOLUME_DOWN || key == RemoteKey.MUTE
        if ((viaEShare || volume) && code != null && EShareRemote.send(d.host, code)) return@enqueue
        try {
            val r = CastClient.key(d, key)
            // BeamCast Receiver cannot press keys on TVs without Accessibility or ADB; their EShare service can.
            if (!r.ok && code != null && EShareRemote.send(d.host, code)) {
                viaEShare = true
                showRemoteEnabled(true)
                showStatus(null)
            } else {
                handle(r)
            }
        } catch (e: CastException) {
            showStatus(e.message)
        }
    }

    private fun sendText() {
        val text = b.textInput.text?.toString().orEmpty()
        if (text.isEmpty()) return
        b.textInput.text?.clear()
        enqueue(false) { d ->
            try { handle(CastClient.text(d, text, false)) } catch (e: CastException) { showStatus(e.message) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setRepeatingKey(key: String) {
        val tick = object : Runnable {
            override fun run() {
                sendKey(key, true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sendKey(key, false)
                    v.postDelayed(tick, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(tick)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipePad() {
        val density = resources.displayMetrics.density
        val swipe = SwipeKeys(24 * density, 64 * density)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var sx = 0f
        var sy = 0f
        var sent = 0
        b.swipePad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; sent = 0 }
                MotionEvent.ACTION_MOVE -> {
                    // Fire keys live while the finger travels so long swipes feel responsive.
                    val keys = swipe.keysFor(e.x - sx, e.y - sy)
                    for (i in sent until keys.size) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sendKey(keys[i], false)
                    }
                    sent = maxOf(sent, keys.size)
                }
                MotionEvent.ACTION_UP -> {
                    if (sent == 0 && Math.hypot((e.x - sx).toDouble(), (e.y - sy).toDouble()) <= slop) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sendKey(RemoteKey.OK, false)
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    companion object {
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 120L
    }
}
