package com.beamcast.receiver.remote

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import com.beamcast.protocol.RemoteKey
import com.beamcast.protocol.RemoteResult
import com.beamcast.protocol.TextRequest
import com.beamcast.receiver.player.PlaybackManager
import com.beamcast.receiver.player.onMain
import com.beamcast.receiver.server.RemoteController
import com.beamcast.receiver.ui.HomeActivity

/** Runs remote keys on the main thread and returns the result synchronously to the server thread. */
@OptIn(UnstableApi::class)
class AppRemoteController(context: Context) : RemoteController {
    private val app = context.applicationContext
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun enabled() = RemoteAccessibilityService.instance != null

    override fun key(key: String): RemoteResult = onMain {
        when (key) {
            RemoteKey.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            RemoteKey.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            RemoteKey.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            RemoteKey.PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            RemoteKey.REWIND -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            RemoteKey.FAST_FORWARD -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            else -> {
                val svc = RemoteAccessibilityService.instance
                when {
                    svc != null -> navigate(svc, key)
                    PlaybackManager.ui != null && playerKey(key) -> result(true)
                    else -> notEnabled()
                }
            }
        }
    }

    override fun text(req: TextRequest): RemoteResult = onMain {
        val svc = RemoteAccessibilityService.instance ?: return@onMain notEnabled()
        editText(svc) { old -> if (req.replace) req.text else old + req.text }
    }

    private fun result(ok: Boolean, message: String? = null) = RemoteResult(ok, enabled(), message)

    /**
     * The service is off. Try to turn it on once (the service starts asynchronously, so this request
     * still reports "not enabled"); if that is not possible, ask on the resumed home screen.
     */
    private fun notEnabled(): RemoteResult {
        if (!RemoteSetup.tryAutoEnable(app)) {
            Handler(Looper.getMainLooper()).post {
                HomeActivity.current?.let { RemoteSetup.ensure(it, userRequested = true) {} }
            }
        }
        return result(false, "Remote control is not enabled on the TV")
    }

    private fun volume(direction: Int): RemoteResult {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return result(true)
    }

    private fun media(code: Int): RemoteResult {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return result(true)
    }

    /** Fallback when the service is off but BeamCast's own player is on screen. */
    private fun playerKey(key: String): Boolean {
        when (key) {
            RemoteKey.OK -> PlaybackManager.togglePlayPause()
            RemoteKey.LEFT -> PlaybackManager.seekBy(-PlaybackManager.SEEK_BACK_MS)
            RemoteKey.RIGHT -> PlaybackManager.seekBy(PlaybackManager.SEEK_FORWARD_MS)
            RemoteKey.UP -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            RemoteKey.DOWN -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            RemoteKey.BACK -> PlaybackManager.stop()
            else -> return false
        }
        return true
    }

    private fun navigate(svc: AccessibilityService, key: String): RemoteResult {
        val global = when (key) {
            RemoteKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            RemoteKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            RemoteKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            RemoteKey.BACKSPACE -> return editText(svc) { it.dropLast(1) }
            else -> if (Build.VERSION.SDK_INT >= 33) dpadGlobal(key) else return dpadLegacy(svc, key)
        }
        return if (global != null && svc.performGlobalAction(global)) result(true) else result(false, "Action failed")
    }

    private fun dpadGlobal(key: String): Int? = when (key) {
        RemoteKey.UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
        RemoteKey.DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
        RemoteKey.LEFT -> AccessibilityService.GLOBAL_ACTION_DPAD_LEFT
        RemoteKey.RIGHT -> AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
        RemoteKey.OK -> AccessibilityService.GLOBAL_ACTION_DPAD_CENTER
        else -> null
    }

    /** API < 33: no D-pad global actions, so move focus (or click) through the node tree. */
    private fun dpadLegacy(svc: AccessibilityService, key: String): RemoteResult {
        val root = svc.rootInActiveWindow ?: return result(false, "No active window")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstFocusable(root)
            ?: return result(false, "Nothing focusable on screen")
        if (key == RemoteKey.OK) {
            var n: AccessibilityNodeInfo? = focused
            while (n != null && !n.isClickable) n = n.parent
            return if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) result(true) else result(false, "Nothing to click")
        }
        val dir = when (key) {
            RemoteKey.UP -> View.FOCUS_UP
            RemoteKey.DOWN -> View.FOCUS_DOWN
            RemoteKey.LEFT -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val next = focused.focusSearch(dir)
        return if (next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) result(true) else result(false, "Cannot move focus")
    }

    private fun firstFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocusable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { firstFocusable(it) }
            if (found != null) return found
        }
        return null
    }

    private fun editText(svc: AccessibilityService, transform: (String) -> String): RemoteResult {
        val node = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable) return result(false, "No text field is focused on the TV")
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val old = if (showingHint) "" else node.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, transform(old))
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) result(true) else result(false, "Could not set text")
    }
}
