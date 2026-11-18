package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.MonotonicClock

internal class ExecutionFakeMonotonicClock(var current: Long = 1_000L) : MonotonicClock {
    override fun nowMs(): Long = current
}
