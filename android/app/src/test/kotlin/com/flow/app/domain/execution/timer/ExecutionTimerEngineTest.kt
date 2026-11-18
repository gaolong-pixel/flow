package com.flow.app.domain.execution.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTimerEngineTest {

    @Test
    fun startsWithInitialSnapshot() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)

        val result = engine.start(500L) as TimerOperationResult.Success
        val snapshot = result.snapshot

        assertEquals(TimerRunStatus.RUNNING, snapshot.status)
        assertEquals(1_500L, snapshot.deadlineMonotonicMs)
        assertEquals(500L, snapshot.totalDurationMs)
        assertEquals(500L, snapshot.remainingDurationMs)
        assertEquals("run-1", snapshot.runId?.value)
    }

    @Test
    fun remainingTimeUsesDeadlineAndMonotonicClock() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_300L

        val snapshot = (engine.snapshot() as TimerOperationResult.Success).snapshot

        assertEquals(200L, snapshot.remainingDurationMs)
        assertEquals(1_500L, snapshot.deadlineMonotonicMs)
        assertEquals(TimerRunStatus.RUNNING, snapshot.status)
    }

    @Test
    fun pauseSavesRemainingAndKeepsRunId() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val started = (engine.start(500L) as TimerOperationResult.Success).snapshot
        clock.current = 1_200L

        val paused = (engine.pause() as TimerOperationResult.Success).snapshot
        clock.current = 1_900L
        val stillPaused = (engine.snapshot() as TimerOperationResult.Success).snapshot

        assertEquals(TimerRunStatus.PAUSED, paused.status)
        assertEquals(300L, paused.remainingDurationMs)
        assertNull(paused.deadlineMonotonicMs)
        assertEquals(started.runId, paused.runId)
        assertEquals(300L, stillPaused.remainingDurationMs)
        assertEquals(TimerRunStatus.PAUSED, stillPaused.status)
    }

    @Test
    fun resumeBuildsNewDeadlineAndKeepsRunId() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_200L
        val paused = (engine.pause() as TimerOperationResult.Success).snapshot
        clock.current = 2_000L

        val resumed = (engine.resume() as TimerOperationResult.Success).snapshot

        assertEquals(TimerRunStatus.RUNNING, resumed.status)
        assertEquals(2_300L, resumed.deadlineMonotonicMs)
        assertEquals(300L, resumed.remainingDurationMs)
        assertEquals(paused.runId, resumed.runId)
    }

    @Test
    fun multiplePauseResumeCyclesRemainDeterministic() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        engine.pause()
        clock.current = 5_000L
        engine.resume()
        clock.current = 5_300L

        val snapshot = (engine.snapshot() as TimerOperationResult.Success).snapshot

        assertEquals(500L, snapshot.remainingDurationMs)
        assertEquals(5_800L, snapshot.deadlineMonotonicMs)
    }

    @Test
    fun pollBeforeDeadlineKeepsRunning() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_300L

        val result = engine.poll() as TimerOperationResult.Success

        assertEquals(TimerRunStatus.RUNNING, result.snapshot.status)
        assertEquals(200L, result.snapshot.remainingDurationMs)
        assertEquals(200L, engine.debugState().remainingDurationMs)
        assertEquals(1_300L, engine.debugState().lastObservedMs)
    }

    @Test
    fun pollAtExactDeadlineExpiresOnce() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_500L

        val first = engine.poll() as TimerOperationResult.Expired
        val second = engine.poll() as TimerOperationResult.Success

        assertEquals(TimerRunStatus.COMPLETED, first.snapshot.status)
        assertEquals(0L, first.snapshot.remainingDurationMs)
        assertNull(first.snapshot.deadlineMonotonicMs)
        assertEquals(TimerRunStatus.COMPLETED, second.snapshot.status)
        assertEquals(first.snapshot.runId, second.snapshot.runId)
    }

    @Test
    fun pollAfterDeadlineExpires() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_700L

        val result = engine.poll() as TimerOperationResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, result.snapshot.status)
        assertEquals(0L, result.snapshot.remainingDurationMs)
    }

    @Test
    fun snapshotDoesNotMutateRunningState() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        val before = engine.debugState()
        clock.current = 1_300L

        val projected = (engine.snapshot() as TimerOperationResult.Success).snapshot
        val after = engine.debugState()

        assertEquals(200L, projected.remainingDurationMs)
        assertEquals(TimerRunStatus.RUNNING, projected.status)
        assertEquals(before, after)
        assertEquals(500L, after.remainingDurationMs)
        assertEquals(1_500L, after.deadlineMonotonicMs)
        assertEquals(1_000L, after.lastObservedMs)
    }

    @Test
    fun snapshotAtExpiredDeadlineStaysRunningUntilPoll() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        val before = engine.debugState()
        clock.current = 1_500L

        val projected = (engine.snapshot() as TimerOperationResult.Success).snapshot
        val after = engine.debugState()

        assertEquals(TimerRunStatus.RUNNING, projected.status)
        assertEquals(0L, projected.remainingDurationMs)
        assertEquals(1_500L, projected.deadlineMonotonicMs)
        assertEquals(before, after)
        assertEquals(TimerRunStatus.RUNNING, after.status)
        assertEquals(500L, after.remainingDurationMs)
        assertEquals(1_500L, after.deadlineMonotonicMs)
        assertEquals(1_000L, after.lastObservedMs)

        val expired = engine.poll() as TimerOperationResult.Expired
        assertEquals(TimerRunStatus.COMPLETED, expired.snapshot.status)
    }

    @Test
    fun snapshotDoesNotMutateNonRunningStates() {
        val engine = engine(FakeClock(1_000L))
        val idleBefore = engine.debugState()
        engine.snapshot()
        assertEquals(idleBefore, engine.debugState())

        val pausedEngine = engine(FakeClock(1_000L))
        pausedEngine.start(500L)
        pausedEngine.pause()
        val pausedBefore = pausedEngine.debugState()
        pausedEngine.snapshot()
        assertEquals(pausedBefore, pausedEngine.debugState())

        val completedClock = FakeClock(1_000L)
        val doneEngine = engine(completedClock)
        doneEngine.start(500L)
        completedClock.current = 1_500L
        doneEngine.poll()
        val completedBefore = doneEngine.debugState()
        doneEngine.snapshot()
        assertEquals(completedBefore, doneEngine.debugState())

        val cancelledEngine = engine(FakeClock(1_000L))
        cancelledEngine.start(500L)
        cancelledEngine.cancel()
        val cancelledBefore = cancelledEngine.debugState()
        cancelledEngine.snapshot()
        assertEquals(cancelledBefore, cancelledEngine.debugState())
    }

    @Test
    fun pauseAfterDeadlineCompletes() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_600L

        val result = engine.pause() as TimerOperationResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, result.snapshot.status)
        assertFailure(TimerEngineError.INVALID_STATE, engine.pause())
    }

    @Test
    fun cancelBeforeDeadlineCancels() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_400L

        val result = engine.cancel() as TimerOperationResult.Success

        assertEquals(TimerRunStatus.CANCELLED, result.snapshot.status)
        assertNull(result.snapshot.deadlineMonotonicMs)
    }

    @Test
    fun cancelAtExactDeadlineCompletes() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_500L

        val result = engine.cancel() as TimerOperationResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, result.snapshot.status)
        assertNull(result.snapshot.deadlineMonotonicMs)
        assertFailure(TimerEngineError.INVALID_STATE, engine.cancel())
    }

    @Test
    fun cancelAfterDeadlineCompletesAndDoesNotBecomeCancelled() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_700L

        val result = engine.cancel() as TimerOperationResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, result.snapshot.status)
        assertEquals(TimerRunStatus.COMPLETED, (engine.snapshot() as TimerOperationResult.Success).snapshot.status)
        assertFailure(TimerEngineError.INVALID_STATE, engine.cancel())
    }

    @Test
    fun pauseAtExpiredDeadlineReturnsExpiredInsteadOfPaused() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_500L

        val result = engine.pause() as TimerOperationResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, result.snapshot.status)
        assertEquals(0L, result.snapshot.remainingDurationMs)
    }

    @Test
    fun cancelInvalidatesResumeAndNewStartUsesNewId() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val first = (engine.start(500L) as TimerOperationResult.Success).snapshot
        clock.current = 1_200L

        val cancelled = (engine.cancel() as TimerOperationResult.Success).snapshot
        assertEquals(TimerRunStatus.CANCELLED, cancelled.status)
        assertEquals(first.runId, cancelled.runId)
        assertFailure(TimerEngineError.INVALID_STATE, engine.resume())

        val second = (engine.start(700L) as TimerOperationResult.Success).snapshot
        assertEquals(TimerRunStatus.RUNNING, second.status)
        assertNotEquals(first.runId, second.runId)
    }

    @Test
    fun rejectsZeroAndNegativeDuration() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)

        assertFailure(TimerEngineError.INVALID_DURATION, engine.start(0L))
        assertFailure(TimerEngineError.INVALID_DURATION, engine.start(-1L))
        assertEquals(TimerRunStatus.IDLE, (engine.snapshot() as TimerOperationResult.Success).snapshot.status)
    }

    @Test
    fun rejectsIllegalStateOperations() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)

        assertFailure(TimerEngineError.INVALID_STATE, engine.pause())
        assertFailure(TimerEngineError.INVALID_STATE, engine.resume())
        assertFailure(TimerEngineError.INVALID_STATE, engine.cancel())

        engine.start(500L)
        assertFailure(TimerEngineError.INVALID_STATE, engine.start(500L))
        engine.pause()
        assertFailure(TimerEngineError.INVALID_STATE, engine.pause())
    }

    @Test
    fun pauseClockRegressionFailsWithoutMutatingState() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        val before = engine.debugState()
        clock.current = 900L

        assertFailure(TimerEngineError.CLOCK_REGRESSION, engine.pause())
        assertEquals(before, engine.debugState())
    }

    @Test
    fun cancelClockRegressionFailsWithoutMutatingState() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        val before = engine.debugState()
        clock.current = 900L

        assertFailure(TimerEngineError.CLOCK_REGRESSION, engine.cancel())
        assertEquals(before, engine.debugState())
    }

    @Test
    fun snapshotClockRegressionFailsWithoutMutatingState() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        val before = engine.debugState()
        clock.current = 900L

        assertFailure(TimerEngineError.CLOCK_REGRESSION, engine.snapshot())
        assertEquals(before, engine.debugState())
        assertFailure(TimerEngineError.CLOCK_REGRESSION, engine.snapshot())
        assertEquals(before, engine.debugState())
    }

    @Test
    fun pollClockRegressionFailsWithoutMutatingStateAndRecovers() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        val before = engine.debugState()
        clock.current = 900L

        assertFailure(TimerEngineError.CLOCK_REGRESSION, engine.poll())
        assertEquals(before, engine.debugState())

        clock.current = 1_100L
        val recovered = engine.poll() as TimerOperationResult.Success
        assertEquals(TimerRunStatus.RUNNING, recovered.snapshot.status)
        assertEquals(400L, recovered.snapshot.remainingDurationMs)
        assertEquals(1_100L, engine.debugState().lastObservedMs)
    }

    @Test
    fun rejectsNegativeClock() {
        val clock = FakeClock(-1L)
        val engine = engine(clock)

        assertFailure(TimerEngineError.INVALID_CLOCK, engine.start(500L))
    }

    @Test
    fun rejectsDeadlineOverflow() {
        val clock = FakeClock(Long.MAX_VALUE - 10L)
        val engine = engine(clock)

        assertFailure(TimerEngineError.DEADLINE_OVERFLOW, engine.start(100L))
    }

    @Test
    fun rejectsResumeDeadlineOverflow() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_500L
        engine.pause()
        clock.current = Long.MAX_VALUE - 100L

        assertFailure(TimerEngineError.DEADLINE_OVERFLOW, engine.resume())
    }

    @Test
    fun completedRunCanStartNewRunWithNewId() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val first = (engine.start(500L) as TimerOperationResult.Success).snapshot
        clock.current = 1_500L
        engine.poll()

        val second = (engine.start(500L) as TimerOperationResult.Success).snapshot

        assertEquals(TimerRunStatus.RUNNING, second.status)
        assertNotEquals(first.runId, second.runId)
    }

    @Test
    fun cancelFromPausedKeepsRemainingAndInvalidatesResume() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(500L)
        clock.current = 1_200L
        engine.pause()

        val cancelled = (engine.cancel() as TimerOperationResult.Success).snapshot

        assertEquals(TimerRunStatus.CANCELLED, cancelled.status)
        assertEquals(300L, cancelled.remainingDurationMs)
        assertFailure(TimerEngineError.INVALID_STATE, engine.resume())
    }

    @Test
    fun idleSnapshotIsStable() {
        val engine = engine(FakeClock(1_000L))
        val snapshot = (engine.snapshot() as TimerOperationResult.Success).snapshot

        assertEquals(TimerRunStatus.IDLE, snapshot.status)
        assertNull(snapshot.runId)
        assertEquals(0L, snapshot.totalDurationMs)
        assertEquals(0L, snapshot.remainingDurationMs)
    }

    private fun engine(clock: FakeClock): ExecutionTimerEngine {
        var nextId = 1
        return ExecutionTimerEngine(
            clock = clock,
            idGenerator = { "run-${nextId++}" }
        )
    }

    private fun assertFailure(
        expected: TimerEngineError,
        result: TimerOperationResult
    ) {
        val failure = result as TimerOperationResult.Failure
        assertEquals(expected, failure.error)
    }

    private class FakeClock(var current: Long) : MonotonicClock {
        override fun nowMs(): Long = current
    }
}
