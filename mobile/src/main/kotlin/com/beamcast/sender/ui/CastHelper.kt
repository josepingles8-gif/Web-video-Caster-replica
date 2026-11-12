package com.beamcast.sender.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beamcast.protocol.LoadRequest
import com.beamcast.sender.R
import com.beamcast.sender.cast.CastClient
import com.beamcast.sender.cast.CastSession
import com.beamcast.sender.cast.Device
import com.beamcast.sender.cast.RemoteActivity
import com.beamcast.sender.media.LocalServer
import com.beamcast.sender.media.MediaServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun AppCompatActivity.load(device: Device, req: LoadRequest): Boolean {
    Toast.makeText(this, getString(R.string.cast_sending, device.name), Toast.LENGTH_SHORT).show()
    return try {
        CastClient.load(device, req)
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
