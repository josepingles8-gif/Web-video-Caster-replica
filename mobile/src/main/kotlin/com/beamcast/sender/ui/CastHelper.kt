package com.beamcast.sender.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beamcast.protocol.LoadRequest
import com.beamcast.sender.R
import com.beamcast.sender.cast.CastTargets
import com.beamcast.sender.cast.DeviceType
import com.beamcast.sender.cast.CastSession
import com.beamcast.sender.cast.Device
import com.beamcast.sender.cast.RemoteActivity
import com.beamcast.sender.data.Store
import com.beamcast.sender.cast.TvRemoteActivity
import com.beamcast.sender.media.LocalServer
import com.beamcast.sender.media.MediaServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun AppCompatActivity.load(device: Device, req: LoadRequest): Boolean {
    Toast.makeText(this, getString(R.string.cast_sending, device.name), Toast.LENGTH_SHORT).show()
    if (device.type != DeviceType.BEAM && req.headers.keys.any { it.equals("referer", true) || it.equals("cookie", true) }) {
        Toast.makeText(this, R.string.cast_headers_notice, Toast.LENGTH_LONG).show()
    }
    return try {
        val r = if (device.type == DeviceType.BEAM) req.copy(quality = Store.get(this).quality, enhance = Store.get(this).enhance) else req
        CastTargets.forDevice(device).load(device, r)
        startActivity(Intent(this, RemoteActivity::class.java))
        true
    } catch (e: Exception) {
        Toast.makeText(this, getString(R.string.cast_failed, e.message), Toast.LENGTH_LONG).show()
        false
    }
}

/** Sends a remote URL to the connected receiver (asking the user to pick one first if needed). */
fun AppCompatActivity.castRequest(req: LoadRequest) {
    val device = CastSession.device.value
    if (device == null) {
        DevicePicker.show(this) { castRequest(req) }
        return
    }
    lifecycleScope.launch { load(device, req) }
}

/** Serves a local document over HTTP from this phone and casts that URL. */
fun AppCompatActivity.castLocalFile(uri: Uri) {
    val device = CastSession.device.value
    if (device == null) {
        DevicePicker.show(this) { castLocalFile(uri) }
        return
    }
    lifecycleScope.launch {
        val served = try {
            withContext(Dispatchers.IO) { LocalServer.publish(applicationContext, uri) }
        } catch (e: LocalServer.NoNetwork) {
            Toast.makeText(this@castLocalFile, R.string.local_no_wifi, Toast.LENGTH_LONG).show()
            return@launch
        } catch (e: Exception) {
            Toast.makeText(this@castLocalFile, getString(R.string.local_error, e.message), Toast.LENGTH_LONG).show()
            return@launch
        }
        MediaServerService.start(this@castLocalFile, served.name)
        val ok = load(device, LoadRequest(url = served.url, title = served.name, mimeType = served.mime))
        if (!ok) MediaServerService.stop(this@castLocalFile)
    }
}

/** Opens the TV remote screen, asking the user to pick a device first if none is connected. */
fun AppCompatActivity.openTvRemote() {
    fun open(d: Device) {
        if (d.type == DeviceType.BEAM) startActivity(Intent(this, TvRemoteActivity::class.java))
        else Toast.makeText(this, R.string.tv_remote_beam_only, Toast.LENGTH_LONG).show()
    }
    val current = CastSession.device.value
    if (current == null) DevicePicker.show(this) { open(it) } else open(current)
}

private const val TV_APK = "beamcast-tv.apk"
private var updateOffered = false

private fun AppCompatActivity.ownVersionCode(): Int =
    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0)).toInt()

/** Pushes the receiver APK bundled in this app to the TV, which then asks the user to install it. */
private suspend fun AppCompatActivity.sendTvUpdate(device: Device) {
    Toast.makeText(this, R.string.tvupdate_sending, Toast.LENGTH_SHORT).show()
    val message = try {
        val size = withContext(Dispatchers.IO) { assets.open(TV_APK).use { it.skip(Long.MAX_VALUE) } }
        com.beamcast.sender.cast.CastClient.update(device, size) { assets.open(TV_APK) }
        getString(R.string.tvupdate_sent)
    } catch (e: Exception) {
        getString(R.string.tvupdate_failed, e.message)
    }
    if (!isFinishing) androidx.appcompat.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
}

/** Menu action: update the receiver, explaining why when it cannot be done. */
fun AppCompatActivity.updateTvApp() {
    val device = CastSession.device.value
    if (device == null) {
        DevicePicker.show(this) { updateTvApp() }
        return
    }
    if (device.type != DeviceType.BEAM) {
        Toast.makeText(this, R.string.tv_remote_beam_only, Toast.LENGTH_LONG).show()
        return
    }
    lifecycleScope.launch {
        val info = try {
            com.beamcast.sender.cast.CastClient.info(device)
        } catch (e: Exception) {
            Toast.makeText(this@updateTvApp, getString(R.string.tvupdate_failed, e.message), Toast.LENGTH_LONG).show()
            return@launch
        }
        when {
            info.versionCode == 0 -> Toast.makeText(this@updateTvApp, R.string.tvupdate_unsupported, Toast.LENGTH_LONG).show()
            info.versionCode >= ownVersionCode() -> Toast.makeText(this@updateTvApp, R.string.tvupdate_current, Toast.LENGTH_LONG).show()
            else -> sendTvUpdate(device)
        }
    }
}

/** Once per app run, offers the update when the connected receiver is older than the bundled one. */
fun AppCompatActivity.offerTvUpdate(device: Device?) {
    if (device == null || device.type != DeviceType.BEAM || updateOffered) return
    lifecycleScope.launch {
        val code = runCatching { com.beamcast.sender.cast.CastClient.info(device).versionCode }.getOrNull() ?: return@launch
        if (updateOffered || isFinishing || code == 0 || code >= ownVersionCode()) return@launch
        updateOffered = true
        androidx.appcompat.app.AlertDialog.Builder(this@offerTvUpdate)
            .setMessage(R.string.tvupdate_available)
            .setPositiveButton(R.string.tvupdate_send) { _, _ -> lifecycleScope.launch { sendTvUpdate(device) } }
            .setNegativeButton(R.string.tvupdate_later, null)
            .show()
    }
}
