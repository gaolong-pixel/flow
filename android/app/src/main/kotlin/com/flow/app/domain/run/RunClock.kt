package com.flow.app.domain.run

/**
 * Monotonic milliseconds used by a plan run. The clock never jumps when the
 * device wall time changes; Android maps it to elapsedRealtime and iOS to
 * mach_continuous_time.
 */
fun interface RunMonotonicClock {
    fun nowMs(): Long
}

object AndroidRunMonotonicClock : RunMonotonicClock {
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}

/** Wall-clock milliseconds, used only for display and elapsed summaries. */
fun interface RunWallClock {
    fun nowEpochMs(): Long
}

object SystemRunWallClock : RunWallClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
