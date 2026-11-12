package com.beamcast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.beamcast.protocol.DeviceInfo
import com.beamcast.receiver.discovery.DevicePrefs
import com.beamcast.receiver.discovery.NsdAdvertiser
import com.beamcast.receiver.discovery.ReceiverState
import com.beamcast.receiver.discovery.Visibility
import com.beamcast.receiver.player.AppPlayerController
import com.beamcast.receiver.player.PlaybackManager
import com.beamcast.receiver.server.BeamServer
import com.beamcast.receiver.server.RequestRouter
import com.beamcast.receiver.ui.HomeActivity
import java.io.IOException

/** Foreground service hosting the HTTP server and the NSD registration. */
@OptIn(UnstableApi::class)
class ReceiverService : Service() {
    private var server: BeamServer? = null
    private var nsd: NsdAdvertiser? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val router = RequestRouter(AppPlayerController(this)) {
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            DeviceInfo(DevicePrefs.name(this), version ?: "1.0")
        }
        try {
            val s = BeamServer.startWithFallback(router)
            server = s
            nsd = NsdAdvertiser(this).also { it.start(DevicePrefs.name(this), s.listeningPort) }
        } catch (e: IOException) {
            ReceiverState.update { copy(visibility = Visibility.FAILED) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_RENAME) nsd?.rename(DevicePrefs.name(this))
        return START_STICKY
    }

    override fun onDestroy() {
        nsd?.stop()
        server?.stop()
        PlaybackManager.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    companion object {
        const val ACTION_RENAME = "com.beamcast.receiver.RENAME"
        private const val CHANNEL = "receiver"
    }
}
