package com.beamcast.receiver.remote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the BeamCast phone app act as the TV remote. It only performs actions requested over the
 * local network (global actions, focus moves, clicks, text in the focused field); it never reads
 * or stores what is on screen. The running instance is kept for [AppRemoteController].
 */
class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }
}
