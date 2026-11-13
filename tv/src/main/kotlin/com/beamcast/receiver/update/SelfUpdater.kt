package com.beamcast.receiver.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.beamcast.receiver.player.onMain
import com.beamcast.receiver.ui.HomeActivity
import java.io.File

/** Installs a receiver APK pushed by the phone, so the TV never needs a manual reinstall. */
object SelfUpdater {
    /**
     * Keeps a copy of [upload] and opens the system installer, which asks the user to confirm on
     * the TV. Returns an error message, or null when the prompt was launched.
     */
    fun install(context: Context, upload: File): String? {
        val app = context.applicationContext
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val apk = File(dir, "beamcast-tv.apk")
        try {
            upload.copyTo(apk, overwrite = true)
        } catch (e: Exception) {
            return "Could not store the update: ${e.message}"
        }
        // Android itself rejects an APK signed with another key; this only filters obvious mistakes.
        val info = app.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info?.packageName != app.packageName) {
            apk.delete()
            return "The file is not a BeamCast Receiver update"
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
        } else {
            apk.setReadable(true, false)
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            onMain { (HomeActivity.current ?: app).startActivity(intent) }
            null
        } catch (e: Exception) {
            "Could not open the installer on the TV: ${e.message}"
        }
    }
}
