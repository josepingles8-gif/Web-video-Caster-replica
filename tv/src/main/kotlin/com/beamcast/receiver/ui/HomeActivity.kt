package com.beamcast.receiver.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.beamcast.receiver.R
import com.beamcast.receiver.ReceiverService
import com.beamcast.receiver.discovery.DevicePrefs
import com.beamcast.receiver.discovery.ReceiverState
import com.beamcast.receiver.discovery.Visibility
import com.beamcast.receiver.remote.RemoteSetup
import kotlinx.coroutines.launch

/** Idle screen: shows how to connect from the phone. */
@OptIn(UnstableApi::class)
class HomeActivity : AppCompatActivity() {
    companion object {
        /** The resumed instance, so the remote controller can show the setup prompt on screen. */
        @Volatile
        var current: HomeActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ContextCompat.startForegroundService(this, Intent(this, ReceiverService::class.java))
        ReceiverState.update { copy(name = DevicePrefs.name(this@HomeActivity)) }

        val deviceName = findViewById<TextView>(R.id.device_name)
        val address = findViewById<TextView>(R.id.address)
        val status = findViewById<TextView>(R.id.status)
        findViewById<View>(R.id.rename).setOnClickListener { askName() }
        findViewById<View>(R.id.remote_enable).setOnClickListener {
            RemoteSetup.ensure(this, userRequested = true) { refreshRemoteStatus() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ReceiverState.state.collect { s ->
                    deviceName.text = s.name
                    address.text = if (s.ip != null && s.port > 0) "${s.ip}:${s.port}" else getString(R.string.address_none)
                    status.setText(
                        when (s.visibility) {
                            Visibility.STARTING -> R.string.status_starting
                            Visibility.VISIBLE -> R.string.status_visible
                            Visibility.NO_NETWORK -> R.string.status_no_network
                            Visibility.FAILED -> R.string.status_failed
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        current = this
        RemoteSetup.ensure(this, userRequested = false) { refreshRemoteStatus() }
    }

    override fun onPause() {
        if (current === this) current = null
        RemoteSetup.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        RemoteSetup.dismiss()
        super.onDestroy()
    }

    private fun refreshRemoteStatus() {
        val on = RemoteSetup.isEnabled(this)
        findViewById<TextView>(R.id.remote_status).setText(if (on) R.string.remote_enabled else R.string.remote_disabled)
        findViewById<View>(R.id.remote_enable).visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun askName() {
        val input = EditText(this).apply {
            setText(DevicePrefs.name(this@HomeActivity))
            setSelection(text.length)
            setSingleLine()
            hint = getString(R.string.name_hint)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    DevicePrefs.setName(this, n)
                    ReceiverState.update { copy(name = n) }
                    startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_RENAME))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
