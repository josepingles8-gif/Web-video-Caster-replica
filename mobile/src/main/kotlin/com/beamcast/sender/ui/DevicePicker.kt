package com.beamcast.sender.ui

import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.beamcast.protocol.BeamProtocol
import com.beamcast.sender.R
import com.beamcast.sender.cast.CastClient
import com.beamcast.sender.cast.CastSession
import com.beamcast.sender.cast.Device
import com.beamcast.sender.cast.DeviceDiscovery
import com.beamcast.sender.data.Store
import com.beamcast.sender.data.UrlInput
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

object DevicePicker {
    fun show(activity: AppCompatActivity, onPicked: (Device) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_devices, null)
        val list = view.findViewById<ListView>(R.id.list)
        val empty = view.findViewById<TextView>(R.id.empty)
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        list.emptyView = empty
        var shown: List<Device> = emptyList()
        val saved = Store.get(activity).lastDevice
        val discovery = DeviceDiscovery(activity)
        val job = activity.lifecycleScope.launch {
            discovery.devices.collect { found ->
                val all = if (saved != null && found.none { it.host == saved.host && it.port == saved.port }) listOf(saved) + found else found
                shown = all
                adapter.clear()
                all.forEach { d ->
                    adapter.add(if (d === saved) activity.getString(R.string.saved_device, d.label) else d.label)
                }
            }
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.devices_title)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.devices_add_ip) { _, _ -> addByIp(activity, onPicked) }
        if (CastSession.device.value != null) {
            builder.setPositiveButton(R.string.devices_disconnect) { _, _ -> CastSession.select(activity, null) }
        }
        val dialog = builder.create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val d = shown.getOrNull(pos) ?: return@setOnItemClickListener
            dialog.dismiss()
            CastSession.select(activity, d)
            Toast.makeText(activity, activity.getString(R.string.connected_to, d.name), Toast.LENGTH_SHORT).show()
            onPicked(d)
        }
        dialog.setOnDismissListener { job.cancel(); discovery.stop() }
        dialog.show()
        discovery.start()
    }

    fun addByIp(activity: AppCompatActivity, onPicked: (Device) -> Unit) {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.ip_hint)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.devices_add_ip)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                val hp = UrlInput.parseHostPort(input.text.toString(), BeamProtocol.DEFAULT_PORT)
                if (hp == null) {
                    Toast.makeText(activity, R.string.ip_invalid, Toast.LENGTH_SHORT).show()
                } else activity.lifecycleScope.launch {
                    val probe = Device("", hp.first, hp.second)
                    try {
                        val info = CastClient.info(probe)
                        val d = probe.copy(name = info.name)
                        CastSession.select(activity, d)
                        Toast.makeText(activity, activity.getString(R.string.connected_to, d.name), Toast.LENGTH_SHORT).show()
                        onPicked(d)
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.ip_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }
}
