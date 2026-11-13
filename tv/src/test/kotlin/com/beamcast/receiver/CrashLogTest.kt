package com.beamcast.receiver

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test fun describesTheErrorWithinTheSizeLimit() {
        val text = CrashLog.describe("GlThread", IllegalStateException("boom", RuntimeException("x".repeat(10_000))))
        assertTrue(text.startsWith("v${BuildConfig.VERSION_CODE} thread=GlThread\njava.lang.IllegalStateException: boom"))
        assertTrue(text.length <= 3000)
    }
}
