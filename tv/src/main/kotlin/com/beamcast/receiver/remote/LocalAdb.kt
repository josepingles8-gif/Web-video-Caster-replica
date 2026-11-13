package com.beamcast.receiver.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Remote-control path for TVs without an Accessibility settings screen (common on Android 7):
 * talks to the TV's own adbd on localhost once "USB debugging" is on. It grants this app
 * WRITE_SECURE_SETTINGS so the accessibility service can be switched on without the Settings UI,
 * and can also inject keys directly with `input keyevent` while the connection is alive.
 */
object LocalAdb {
    private const val PORT = 5555
    private const val APPROVAL_TIMEOUT_MS = 60_000
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "local-adb").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var conn: AdbConnection? = null
    @Volatile private var connecting = false

    val ready: Boolean get() = conn != null

    /** Connects in the background if needed; [onConnected] runs on the main thread after a new connection. */
    fun connect(context: Context, onConnected: () -> Unit = {}) {
        if (ready || connecting) return
        connecting = true
        val app = context.applicationContext
        exec.execute {
            try {
                val socket = Socket()
                val c = try {
                    socket.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
                    socket.soTimeout = 5000
                    AdbConnection(socket, loadKey(app)).also { it.connect(APPROVAL_TIMEOUT_MS) }
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
                socket.soTimeout = 10_000
                conn = c
                runCatching { c.shell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS") }
                RemoteSetup.tryAutoEnable(app)
                main.post(onConnected)
            } catch (_: Exception) {
                // adbd is not listening on TCP, debugging is off, or the prompt was not accepted
            } finally {
                connecting = false
            }
        }
    }

    /** Sends an Android key code. Returns false when there is no connection. */
    fun key(code: Int): Boolean = run("input keyevent $code")

    fun text(text: String): Boolean = run("input text '" + text.replace("'", "'\\''").replace(" ", "%s") + "'")

    private fun run(command: String): Boolean {
        if (!ready) return false
        exec.execute {
            val c = conn ?: return@execute
            try {
                c.shell(command)
            } catch (_: Exception) {
                c.close()
                if (conn === c) conn = null
            }
        }
        return true
    }

    private fun loadKey(context: Context): java.security.KeyPair {
        val file = File(context.filesDir, "adbkey")
        if (file.exists()) runCatching { return AdbKeys.decodePrivate(file.readBytes()) }
        return AdbKeys.generate().also { file.writeBytes(AdbKeys.encodePrivate(it)) }
    }
}
