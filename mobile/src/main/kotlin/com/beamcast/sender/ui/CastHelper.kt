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
        val r = if (device.type == DeviceType.BEAM) req.copy(quality = Store.get(this).quality) else req
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
