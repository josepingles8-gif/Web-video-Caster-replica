package com.beamcast.receiver.player

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

private val mainHandler = Handler(Looper.getMainLooper())

/** Runs [block] on the main thread and waits for the result (used by server threads). */
fun <T> onMain(timeoutMs: Long = 5000, block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val task = FutureTask(block)
    mainHandler.post(task)
    try {
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}
