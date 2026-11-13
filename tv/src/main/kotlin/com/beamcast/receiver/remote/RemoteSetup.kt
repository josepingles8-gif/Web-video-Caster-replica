package com.beamcast.receiver.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.beamcast.receiver.R

/** Guides the user to turn on [RemoteAccessibilityService], or turns it on when the app holds WRITE_SECURE_SETTINGS. */
object RemoteSetup {
    private const val PREFS = "beamcast_remote"
    private const val KEY_DISMISSED = "prompt_dismissed"

    @Volatile
    private var promptShown = false

    private var dialog: AlertDialog? = null

    private fun serviceName(context: Context): String =
        ComponentName(context, RemoteAccessibilityService::class.java).flattenToString()

    fun isEnabled(context: Context): Boolean {
        if (RemoteAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val me = serviceName(context)
        return enabled.orEmpty().split(':').any { it == me }
    }

    /** Needs WRITE_SECURE_SETTINGS, which only `adb shell pm grant` can give. Returns false otherwise. */
    fun tryAutoEnable(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val resolver = context.contentResolver
            val me = serviceName(context)
            val entries = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() }
            val servicesOk = if (entries.any { it == me }) {
                true
            } else {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (entries + me).joinToString(":"))
            }
            servicesOk && Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        } catch (_: SecurityException) {
            false
        }
    }

    fun openSettings(activity: Activity): Boolean {
        for (action in listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                activity.startActivity(Intent(action))
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next screen
            } catch (_: SecurityException) {
                // try the next screen
            }
        }
        return false
    }

    private fun canShow(activity: Activity) = !activity.isFinishing && !activity.isDestroyed

    /** The activity can die between the check and show(); never let that crash the app. */
    private fun safeShow(d: AlertDialog, onDone: () -> Unit) {
        try {
            d.show()
            dialog = d
        } catch (_: WindowManager.BadTokenException) {
            dialog = null
            onDone()
        }
    }

    fun showPrompt(activity: Activity, onDone: () -> Unit) {
        if (dialog?.isShowing == true) return
        if (!canShow(activity)) return onDone()
        promptShown = true
        val d = AlertDialog.Builder(activity)
            .setTitle(R.string.remote_prompt_title)
            .setMessage(R.string.remote_prompt_message)
            .setPositiveButton(R.string.remote_prompt_open) { _, _ ->
                if (!openSettings(activity)) showManualPath(activity, onDone) else onDone()
            }
            .setNegativeButton(R.string.remote_prompt_later) { _, _ -> onDone() }
            .setNeutralButton(R.string.remote_prompt_dont_show) { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DISMISSED, true).apply()
                onDone()
            }
            .setOnCancelListener { onDone() }
            .create()
        d.setOnShowListener { d.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus() }
        safeShow(d, onDone)
    }

    private fun showManualPath(activity: Activity, onDone: () -> Unit) {
        dialog?.dismiss()
        if (!canShow(activity)) return onDone()
        val d = AlertDialog.Builder(activity)
            .setTitle(R.string.remote_prompt_title)
            .setMessage(R.string.remote_manual_path)
            .setPositiveButton(R.string.remote_prompt_ok) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .create()
        d.setOnShowListener { d.getButton(DialogInterface.BUTTON_POSITIVE).requestFocus() }
        safeShow(d, onDone)
    }

    fun ensure(activity: Activity, userRequested: Boolean, onDone: () -> Unit) {
        if (isEnabled(activity)) {
            onDone()
            return
        }
        if (tryAutoEnable(activity)) {
            Toast.makeText(activity, R.string.remote_auto_enabled, Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        val dismissed = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISMISSED, false)
        if (userRequested || (!dismissed && !promptShown)) {
            showPrompt(activity, onDone)
        } else {
            onDone()
        }
    }

    /** Call from the owning activity's onPause/onDestroy so no dialog outlives its window. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
