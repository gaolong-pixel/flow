package com.flow.app.domain.run

class FakeRunClock(var now: Long = 1_000L) : RunMonotonicClock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

class FakeWallClock(var now: Long = 100_000L) : RunWallClock {
    override fun nowEpochMs(): Long = now
}
