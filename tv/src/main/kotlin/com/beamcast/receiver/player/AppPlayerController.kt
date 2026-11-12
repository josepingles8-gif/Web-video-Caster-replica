package com.beamcast.receiver.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.beamcast.protocol.ControlRequest
import com.beamcast.protocol.LoadRequest
import com.beamcast.protocol.PlaybackStatus
import com.beamcast.receiver.server.PlayerController
import com.beamcast.receiver.ui.PlayerActivity

/** Bridges the HTTP server threads to [PlaybackManager] on the main thread. */
@OptIn(UnstableApi::class)
class AppPlayerController(context: Context) : PlayerController {
    private val app = context.applicationContext

    init { PlaybackManager.init(app) }

    override fun status(): PlaybackStatus = onMain { PlaybackManager.status() }

    override fun load(request: LoadRequest): PlaybackStatus = onMain {
        val st = PlaybackManager.load(request)
        app.startActivity(
            Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        st
    }

    override fun control(request: ControlRequest): PlaybackStatus = onMain { PlaybackManager.control(request) }
}
