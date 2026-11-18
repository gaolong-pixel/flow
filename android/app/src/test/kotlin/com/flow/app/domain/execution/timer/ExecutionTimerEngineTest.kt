package com.flow.app.domain.execution.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun prepareStartIsPureWithRespectToTimerStateAndVersion() {
        val engine = engine(FakeClock(1_000L))
        val before = engine.debugState()

        engine.prepareStart(500L)
        val afterPrepare = engine.debugState()

        assertEquals(before, afterPrepare)
        assertEquals(TimerRunStatus.IDLE, engine.debugState().status)
    }

    @Test
    fun commitUsesOriginalPreparedValues() {
        val engine = engine(FakeClock(1_000L))
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()

        val committed = engine.commitStart(plan) as TimerOperationResult.Success

        assertEquals(TimerRunStatus.RUNNING, committed.snapshot.status)
        assertEquals(500L, committed.snapshot.totalDurationMs)
        assertEquals(500L, committed.snapshot.remainingDurationMs)
        assertEquals(1_500L, committed.snapshot.deadlineMonotonicMs)
        assertEquals("run-1", committed.snapshot.runId?.value)
        assertEquals(before.stateVersion + 1L, engine.debugState().stateVersion)
    }

    @Test
    fun unregisteredPlanIsRejected() {
        val engine = engine(FakeClock(1_000L))
        val before = engine.debugState()
        val fake = TimerPlan(TimerRunId("fake"), 1L)

        assertFailure(TimerEngineError.INVALID_PLAN, engine.commitStart(fake))
        assertEquals(before, engine.debugState())
    }

    @Test
    fun prepareFailureLeavesStateAndVersionUnchanged() {
        val engine = engine(FakeClock(1_000L))
        val before = engine.debugState()

        assertFailure(TimerEngineError.INVALID_DURATION, engine.start(0L))
        assertEquals(before, engine.debugState())
        val planFailure = engine.prepareStart(0L) as TimerPlanResult.Failure
        assertEquals(TimerEngineError.INVALID_DURATION, planFailure.error)
        assertEquals(before, engine.debugState())
    }

    @Test
    fun planCannotBeCommittedToAnotherEngine() {
        val first = engine(FakeClock(1_000L))
        val second = engine(FakeClock(1_000L))
        val plan = (first.prepareStart(500L) as TimerPlanResult.Success).plan

        val result = second.commitStart(plan) as TimerOperationResult.Failure
        assertEquals(TimerEngineError.INVALID_PLAN, result.error)
        assertEquals(TimerRunStatus.IDLE, second.debugState().status)
    }

    @Test
    fun newerPrepareInvalidatesOlderPlan() {
        val engine = engine(FakeClock(1_000L))
        val first = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val second = (engine.prepareStart(700L) as TimerPlanResult.Success).plan
        val before = engine.debugState()

        assertFailure(TimerEngineError.INVALID_PLAN, engine.commitStart(first))
        assertEquals(before, engine.debugState())

        val committed = engine.commitStart(second) as TimerOperationResult.Success
        assertEquals(700L, committed.snapshot.totalDurationMs)
        assertEquals("run-2", committed.snapshot.runId?.value)
    }

    @Test
    fun planIsInvalidatedByStateVersionChange() {
        val engine = engine(FakeClock(1_000L))
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        engine.setStateVersionForTesting(1L)
        val before = engine.debugState()

        val result = engine.commitStart(plan) as TimerOperationResult.Failure

        assertEquals(TimerEngineError.PLAN_STALE, result.error)
        assertEquals(before, engine.debugState())
    }

    @Test
    fun duplicatePlanCommitIsRejected() {
        val engine = engine(FakeClock(1_000L))
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        engine.commitStart(plan)
        val afterFirst = engine.debugState()

        val result = engine.commitStart(plan) as TimerOperationResult.Failure

        assertEquals(TimerEngineError.INVALID_PLAN, result.error)
        assertEquals(afterFirst, engine.debugState())
    }

    @Test
    fun expiredPlanCannotBeCommittedAsRunning() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()
        clock.current = 1_500L

        val result = engine.commitStart(plan) as TimerOperationResult.Failure

        assertEquals(TimerEngineError.PLAN_EXPIRED, result.error)
        assertEquals(before, engine.debugState())
    }

    @Test
    fun planCommitFailureDoesNotChangeVersion() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()
        clock.current = 1_500L

        engine.commitStart(plan)

        assertEquals(before, engine.debugState())
    }

    @Test
    fun prepareStartDoesNotUpdateObservedClock() {
        val engine = engine(FakeClock(1_000L))
        val before = engine.debugState()
        engine.prepareStart(500L)
        val after = engine.debugState()

        assertEquals(before.lastObservedMs, after.lastObservedMs)
        assertEquals(before.stateVersion, after.stateVersion)
    }

    @Test
    fun commitBeforePreparedTimeIsRejected() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()
        clock.current = 900L

        val result = engine.commitStart(plan) as TimerOperationResult.Failure

        assertEquals(TimerEngineError.CLOCK_REGRESSION, result.error)
        assertEquals(before, engine.debugState())
    }

    @Test
    fun versionOverflowRejectsStartWithoutStateChange() {
        val engine = engine(FakeClock(1_000L))
        engine.setStateVersionForTesting(Long.MAX_VALUE)
        val before = engine.debugState()

        assertFailure(TimerEngineError.VERSION_OVERFLOW, engine.start(500L))
        assertEquals(before, engine.debugState())
    }

    @Test
    fun versionOverflowRejectsPauseResumeCancelAndPoll() {
        val pauseEngine = engine(FakeClock(1_000L))
        pauseEngine.start(500L)
        pauseEngine.setStateVersionForTesting(Long.MAX_VALUE)
        val pauseBefore = pauseEngine.debugState()
        assertFailure(TimerEngineError.VERSION_OVERFLOW, pauseEngine.pause())
        assertEquals(pauseBefore, pauseEngine.debugState())

        val resumeEngine = engine(FakeClock(1_000L))
        resumeEngine.start(500L)
        resumeEngine.pause()
        resumeEngine.setStateVersionForTesting(Long.MAX_VALUE)
        val resumeBefore = resumeEngine.debugState()
        assertFailure(TimerEngineError.VERSION_OVERFLOW, resumeEngine.resume())
        assertEquals(resumeBefore, resumeEngine.debugState())

        val cancelEngine = engine(FakeClock(1_000L))
        cancelEngine.start(500L)
        cancelEngine.setStateVersionForTesting(Long.MAX_VALUE)
        val cancelBefore = cancelEngine.debugState()
        assertFailure(TimerEngineError.VERSION_OVERFLOW, cancelEngine.cancel())
        assertEquals(cancelBefore, cancelEngine.debugState())

        val pollEngine = engine(FakeClock(1_000L))
        pollEngine.start(500L)
        pollEngine.setStateVersionForTesting(Long.MAX_VALUE)
        val pollBefore = pollEngine.debugState()
        assertFailure(TimerEngineError.VERSION_OVERFLOW, pollEngine.poll())
        assertEquals(pollBefore, pollEngine.debugState())
    }

    @Test
    fun versionCanAdvanceUpToMaximum() {
        val engine = engine(FakeClock(1_000L))
        engine.setStateVersionForTesting(Long.MAX_VALUE - 1L)

        val result = engine.start(500L) as TimerOperationResult.Success

        assertEquals(Long.MAX_VALUE, engine.debugState().stateVersion)
        assertEquals(TimerRunStatus.RUNNING, result.snapshot.status)
    }

    @Test
    fun failedPrepareKeepsPriorPlanValid() {
        val engine = engine(FakeClock(1_000L))
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()

        val failed = engine.prepareStart(0L) as TimerPlanResult.Failure
        assertEquals(TimerEngineError.INVALID_DURATION, failed.error)
        assertEquals(before, engine.debugState())

        val committed = engine.commitStart(plan) as TimerOperationResult.Success
        assertEquals(500L, committed.snapshot.totalDurationMs)
    }

    @Test
    fun expiredCommitFailureIsRetryableOnlyAfterNewPrepare() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        val oldPlan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        clock.current = 1_500L
        val afterExpiry = engine.debugState()

        assertFailure(TimerEngineError.PLAN_EXPIRED, engine.commitStart(oldPlan))
        assertEquals(afterExpiry, engine.debugState())
        assertFailure(TimerEngineError.PLAN_EXPIRED, engine.commitStart(oldPlan))
        assertEquals(afterExpiry, engine.debugState())

        val newPlan = (engine.prepareStart(700L) as TimerPlanResult.Success).plan
        val committed = engine.commitStart(newPlan) as TimerOperationResult.Success
        assertEquals(700L, committed.snapshot.totalDurationMs)

        assertFailure(TimerEngineError.INVALID_PLAN, engine.commitStart(oldPlan))
    }

    @Test
    fun versionInvalidatedPlanCannotRecoverAndNewPrepareCanStart() {
        val engine = engine(FakeClock(1_000L))
        val oldPlan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        engine.setStateVersionForTesting(1L)

        assertFailure(TimerEngineError.PLAN_STALE, engine.commitStart(oldPlan))
        assertFailure(TimerEngineError.PLAN_STALE, engine.commitStart(oldPlan))

        val newPlan = (engine.prepareStart(700L) as TimerPlanResult.Success).plan
        val committed = engine.commitStart(newPlan) as TimerOperationResult.Success
        assertEquals(700L, committed.snapshot.totalDurationMs)

        assertFailure(TimerEngineError.INVALID_PLAN, engine.commitStart(oldPlan))
    }

    @Test
    fun actualStartInvalidatesPriorPlan() {
        val engine = engine(FakeClock(1_000L))
        val oldPlan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan

        engine.start(700L)

        assertFailure(TimerEngineError.INVALID_PLAN, engine.commitStart(oldPlan))
        assertEquals(TimerRunStatus.RUNNING, engine.debugState().status)
        assertEquals(700L, engine.debugState().totalDurationMs)
    }

    @Test
    fun pollOnIdleDoesNotInvalidatePlan() {
        val engine = engine(FakeClock(1_000L))
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()

        engine.poll()
        assertEquals(before, engine.debugState())

        val committed = engine.commitStart(plan) as TimerOperationResult.Success
        assertEquals(500L, committed.snapshot.totalDurationMs)
    }

    @Test
    fun pauseResumeCancelAreIllegalBeforeRunningAndKeepPlanValid() {
        val engine = engine(FakeClock(1_000L))
        val plan = (engine.prepareStart(500L) as TimerPlanResult.Success).plan
        val before = engine.debugState()

        assertFailure(TimerEngineError.INVALID_STATE, engine.pause())
        assertFailure(TimerEngineError.INVALID_STATE, engine.resume())
        assertFailure(TimerEngineError.INVALID_STATE, engine.cancel())
        assertEquals(before, engine.debugState())

        val committed = engine.commitStart(plan) as TimerOperationResult.Success
        assertEquals(500L, committed.snapshot.totalDurationMs)
    }

    @Test
    fun pausePlanCommitsPausedState() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L

        val plan = (engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan
        val committed = engine.commitPause(plan) as TimerCommitResult.Paused

        assertEquals(800L, plan.remainingDurationMs)
        assertEquals(TimerRunStatus.PAUSED, committed.snapshot.status)
        assertEquals(800L, committed.snapshot.remainingDurationMs)
        assertEquals(null, committed.snapshot.deadlineMonotonicMs)
        assertEquals("run-1", committed.snapshot.runId?.value)
    }

    @Test
    fun pauseCommitAfterDeadlineCompletesInstead() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        val plan = (engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan
        clock.current = 2_000L

        val committed = engine.commitPause(plan) as TimerCommitResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, committed.snapshot.status)
        assertEquals(0L, committed.snapshot.remainingDurationMs)
        assertEquals(null, committed.snapshot.deadlineMonotonicMs)
    }

    @Test
    fun preparePauseAfterDeadlineReturnsExpiredWithoutPlan() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 2_000L

        assertEquals(TimerPrepareResult.Expired, engine.preparePause())
    }

    @Test
    fun resumePlanPreservesRunIdAndUsesAnchor() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        engine.commitPause((engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        clock.current = 1_300L

        val plan = (engine.prepareResume() as TimerPrepareResult.Ready).plan as ResumePlan

        assertEquals(2_100L, plan.deadlineMonotonicMs)
        val committed = engine.commitResume(plan) as TimerCommitResult.Resumed
        assertEquals(TimerRunStatus.RUNNING, committed.snapshot.status)
        assertEquals(2_100L, committed.snapshot.deadlineMonotonicMs)
        assertEquals("run-1", committed.snapshot.runId?.value)
    }

    @Test
    fun resumeCommitAfterAnchorCompletes() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        engine.commitPause((engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        clock.current = 1_300L
        val plan = (engine.prepareResume() as TimerPrepareResult.Ready).plan as ResumePlan
        clock.current = 2_100L

        val committed = engine.commitResume(plan) as TimerCommitResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, committed.snapshot.status)
        assertEquals(0L, committed.snapshot.remainingDurationMs)
    }

    @Test
    fun resumeAnchorIsNotExtendedByPendingReplacement() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        engine.commitPause((engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        clock.current = 1_300L
        val first = (engine.prepareResume() as TimerPrepareResult.Ready).plan as ResumePlan

        clock.current = 1_400L
        val cancel = (engine.prepareCancel() as TimerPrepareResult.Ready).plan as CancelPlan
        clock.current = 1_500L
        val second = (engine.prepareResume() as TimerPrepareResult.Ready).plan as ResumePlan

        assertEquals(first.deadlineMonotonicMs, second.deadlineMonotonicMs)
        assertEquals(2_100L, second.deadlineMonotonicMs)
        assertEquals(TimerCommitResult.Failure::class, engine.commitCancel(cancel)::class)
        assertEquals(TimerCommitResult.Resumed::class, engine.commitResume(second)::class)
    }

    @Test
    fun resumeAnchorIsClearedAfterSuccessfulResume() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        engine.commitPause((engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        clock.current = 1_300L
        engine.commitResume((engine.prepareResume() as TimerPrepareResult.Ready).plan as ResumePlan)

        clock.current = 1_400L
        engine.commitPause((engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        clock.current = 1_500L
        val nextResume = (engine.prepareResume() as TimerPrepareResult.Ready).plan as ResumePlan

        assertEquals(2_200L, nextResume.deadlineMonotonicMs)
    }

    @Test
    fun cancelPlanCommitsRunningAndPausedStates() {
        val runningClock = FakeClock(1_000L)
        val running = engine(runningClock)
        running.start(1_000L)
        runningClock.current = 1_200L
        val runningPlan = (running.prepareCancel() as TimerPrepareResult.Ready).plan as CancelPlan
        val runningCommit = running.commitCancel(runningPlan) as TimerCommitResult.Cancelled
        assertEquals(TimerRunStatus.CANCELLED, runningCommit.snapshot.status)

        val pausedClock = FakeClock(1_000L)
        val paused = engine(pausedClock)
        paused.start(1_000L)
        pausedClock.current = 1_200L
        paused.commitPause((paused.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        pausedClock.current = 1_300L
        val pausedPlan = (paused.prepareCancel() as TimerPrepareResult.Ready).plan as CancelPlan
        val pausedCommit = paused.commitCancel(pausedPlan) as TimerCommitResult.Cancelled
        assertEquals(TimerRunStatus.CANCELLED, pausedCommit.snapshot.status)
    }

    @Test
    fun cancelCommitAfterDeadlineCompletes() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        val plan = (engine.prepareCancel() as TimerPrepareResult.Ready).plan as CancelPlan
        clock.current = 2_000L

        val committed = engine.commitCancel(plan) as TimerCommitResult.Expired

        assertEquals(TimerRunStatus.COMPLETED, committed.snapshot.status)
        assertEquals(0L, committed.snapshot.remainingDurationMs)
    }

    @Test
    fun pollAndPausedExpiryCompleteOnce() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_500L
        assertEquals(TimerPrepareResult.NoChange, engine.preparePoll())

        clock.current = 2_000L
        val plan = (engine.preparePoll() as TimerPrepareResult.Ready).plan as PollPlan
        val completed = engine.commitPoll(plan) as TimerCommitResult.Completed
        assertEquals(TimerRunStatus.COMPLETED, completed.snapshot.status)
        assertEquals(TimerPrepareResult.NoChange, engine.preparePoll())

        val pausedClock = FakeClock(1_000L)
        val paused = engine(pausedClock)
        paused.start(1_000L)
        pausedClock.current = 1_200L
        paused.commitPause((paused.preparePause() as TimerPrepareResult.Ready).plan as PausePlan)
        pausedClock.current = 1_300L
        paused.prepareResume()
        pausedClock.current = 2_100L
        val pausedPoll = (paused.preparePoll() as TimerPrepareResult.Ready).plan as PollPlan
        val pausedCompleted = paused.commitPoll(pausedPoll) as TimerCommitResult.Completed
        assertEquals(TimerRunStatus.COMPLETED, pausedCompleted.snapshot.status)
    }

    @Test
    fun pendingLifecycleReplacementRejectsOldPlan() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        val pause = (engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan
        val cancel = (engine.prepareCancel() as TimerPrepareResult.Ready).plan as CancelPlan

        assertEquals(
            TimerCommitResult.Failure::class,
            engine.commitPause(pause)::class
        )
        assertEquals(
            TimerCommitResult.Cancelled::class,
            engine.commitCancel(cancel)::class
        )
    }

    @Test
    fun failedPreparePreservesExistingLifecyclePlan() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        clock.current = 1_200L
        val plan = (engine.preparePause() as TimerPrepareResult.Ready).plan as PausePlan
        val before = engine.debugState()

        val resumeFailure = engine.prepareResume() as TimerPrepareResult.Failure
        assertEquals(TimerEngineError.INVALID_STATE, resumeFailure.error)
        assertEquals(before, engine.debugState())

        assertEquals(TimerCommitResult.Paused::class, engine.commitPause(plan)::class)
    }

    @Test
    fun lifecyclePlanCannotCrossEngineAndFakePlanRejected() {
        val first = engine(FakeClock(1_000L))
        first.start(1_000L)
        val plan = (first.preparePause() as TimerPrepareResult.Ready).plan as PausePlan
        val second = engine(FakeClock(1_000L))
        second.start(1_000L)

        assertEquals(
            TimerCommitResult.Failure::class,
            second.commitPause(plan)::class
        )
        assertEquals(
            TimerCommitResult.Failure::class,
            first.commitPause(PausePlan(1L))::class
        )
    }

    @Test
    fun lifecyclePreparationsRespectVersionOverflow() {
        val clock = FakeClock(1_000L)
        val engine = engine(clock)
        engine.start(1_000L)
        engine.setStateVersionForTesting(Long.MAX_VALUE)

        val result = engine.preparePause() as TimerPrepareResult.Failure
        assertEquals(TimerEngineError.VERSION_OVERFLOW, result.error)
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
