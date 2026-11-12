package com.beamcast.sender.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.beamcast.sender.R
import com.beamcast.sender.cast.RemoteActivity

/** Keeps the process alive (foreground, mediaPlayback) while the local file server is in use. */
class MediaServerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LocalServer.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, RemoteActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MediaServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(intent?.getStringExtra(EXTRA_NAME).orEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, 1, n, type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocalServer.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "local_server"
        private const val ACTION_STOP = "com.beamcast.sender.STOP"
        private const val EXTRA_NAME = "name"

        fun start(ctx: Context, name: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MediaServerService::class.java).putExtra(EXTRA_NAME, name))
        }

        fun stop(ctx: Context) {
            LocalServer.stop()
            ctx.stopService(Intent(ctx, MediaServerService::class.java))
        }
    }
}
