package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.TimerOperationResult
import com.flow.app.domain.execution.timer.TimerRunStatus
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionControllerTimerLifecycleTest {

    @Test
    fun countCompletionStartsNextTimerInSameRevision() {
        val controller = controller(
            routine(
                steps = listOf(count("c", target = 1), timer("t", 30_000L))
            )
        )
        controller.start(0L)
        val result = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted
        assertEquals(1, result.session.records.size)
        assertEquals(StepType.TIMER, step(result.session).type)
        assertEquals(TimerStatus.RUNNING, result.session.timerState.timerStatus)
        assertEquals(2L, result.revision)
        assertNotNull(controller.activeTimerRunId)
        assertEquals(
            TimerRunStatus.RUNNING,
            (controller.timerEngineForTesting().snapshot() as TimerOperationResult.Success).snapshot.status
        )
    }

    @Test
    fun timerExpiryAdvancesToNextTimerWithOneRevisionAndNewRun() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L), timer("b", 20_000L))),
            wall, mono
        )
        controller.start(0L)
        val firstRun = controller.activeTimerRunId
        mono.current = 11_000L
        wall.current = 11_000L

        val result = controller.pollTimer(1L, controller.activeTimerRunId!!) as ExecutionControllerResult.Success

        assertEquals(1, result.session.records.size)
        assertEquals(StepType.TIMER, step(result.session).type)
        assertEquals("b", step(result.session).id)
        assertEquals(TimerStatus.RUNNING, result.session.timerState.timerStatus)
        assertEquals(2L, result.revision)
        assertTrue(firstRun != null && firstRun != controller.activeTimerRunId)
        assertNotNull(controller.activeTimerRunId)
    }

    @Test
    fun timerExpiryAdvancesToCountAndClearsReference() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L), count("c", target = 2))),
            wall, mono
        )
        controller.start(0L)
        mono.current = 11_000L
        wall.current = 11_000L

        val result = controller.pollTimer(1L, controller.activeTimerRunId!!) as ExecutionControllerResult.Success

        assertEquals(StepType.COUNT, step(result.session).type)
        assertEquals(TimerStatus.IDLE, result.session.timerState.timerStatus)
        assertNull(controller.activeTimerRunId)
        assertEquals(2L, result.revision)
    }

    @Test
    fun timerSkipAdvancesAndStartsNextTimer() {
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L), timer("b", 20_000L)))
        )
        controller.start(0L)
        val firstRun = controller.activeTimerRunId

        val result = controller.skipCurrentRound(1L) as StepActionResult.Skipped

        assertEquals(ExecutionStepOutcome.SKIPPED, result.session.records.single().outcome)
        assertEquals("b", step(result.session).id)
        assertEquals(TimerStatus.RUNNING, result.session.timerState.timerStatus)
        assertEquals(2L, result.revision)
        assertTrue(firstRun != controller.activeTimerRunId)
    }

    @Test
    fun pauseAndResumeKeepAnchorAndDoNotExtendDeadline() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L))),
            wall, mono
        )
        controller.start(0L)
        val originalDeadline = controller.snapshot().session.timerState.deadline

        mono.current = 3_000L
        wall.current = 3_000L
        val paused = controller.pause(1L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.PAUSED, paused.session.status)
        assertEquals(TimerStatus.PAUSED, paused.session.timerState.timerStatus)
        assertEquals(8_000L, paused.session.timerState.remainingDurationMs)

        mono.current = 7_000L
        wall.current = 7_000L
        val resumed = controller.resume(2L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.RUNNING, resumed.session.status)
        // Resume uses the preserved remaining duration from the pause instant.
        assertEquals(15_000L, resumed.session.timerState.deadline)
    }

    @Test
    fun duplicatePollIsIdempotent() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L))),
            wall, mono
        )
        controller.start(0L)
        mono.current = 11_000L
        wall.current = 11_000L
        val eventRun = controller.activeTimerRunId!!
        val first = controller.pollTimer(1L, eventRun) as ExecutionControllerResult.Success
        val second = controller.pollTimer(first.revision, eventRun) as ExecutionControllerResult.Success
        assertEquals(first.session, second.session)
        assertEquals(first.revision, second.revision)
        assertNull(controller.activeTimerRunId)
    }

    @Test
    fun cancelAtExpiryCompletesFinalTimer() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L))), wall, mono
        )
        controller.start(0L)
        wall.current = 11_000L
        mono.current = 11_000L

        val result = controller.cancel(1L) as ExecutionControllerResult.Success

        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, result.session.records.single().outcome)
        assertNull(controller.activeTimerRunId)
        assertEquals(2L, result.revision)
    }

    @Test
    fun cancelAtExpiryCancelsRemainingFlowWithoutStartingNextTimer() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L), timer("b", 20_000L))), wall, mono
        )
        controller.start(0L)
        wall.current = 11_000L
        mono.current = 11_000L

        val result = controller.cancel(1L) as ExecutionControllerResult.Success

        assertEquals(ExecutionStatus.CANCELLED, result.session.status)
        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, result.session.records.single().outcome)
        assertEquals("b", result.session.progress.currentStepId)
        assertEquals(TimerStatus.IDLE, result.session.timerState.timerStatus)
        assertNull(controller.activeTimerRunId)
        assertEquals(2L, result.revision)
    }

    @Test
    fun resumeRetryReusesAnchorAfterPreCommitFailure() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L))), wall, mono
        )
        controller.start(0L)
        wall.current = 3_000L
        mono.current = 3_000L
        controller.pause(1L)

        // First resume: prepare creates anchor at 13_000, then wall clock is
        // rolled before currentRoundStartedAt so candidate construction fails.
        wall.current = 500L
        mono.current = 5_000L
        val first = controller.resume(2L) as ExecutionControllerResult.Failure
        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, first.error)
        assertEquals(2L, controller.snapshot().revision)
        assertEquals(ExecutionStatus.PAUSED, controller.snapshot().session.status)

        wall.current = 7_000L
        mono.current = 7_000L
        val second = controller.resume(2L) as ExecutionControllerResult.Success
        assertEquals(13_000L, second.session.timerState.deadline)
        assertEquals(3L, second.revision)
    }

    @Test
    fun cancelAfterFailedResumeBeforeAnchorStillCancels() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L))), wall, mono
        )
        controller.start(0L)
        wall.current = 3_000L
        mono.current = 3_000L
        controller.pause(1L)

        wall.current = 500L
        mono.current = 5_000L
        val failed = controller.resume(2L) as ExecutionControllerResult.Failure
        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, failed.error)

        // Anchor is 13_000; cancelling before it must still cancel.
        wall.current = 6_000L
        mono.current = 6_000L
        val cancelled = controller.cancel(2L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.CANCELLED, cancelled.session.status)
        assertEquals(3L, cancelled.revision)
        assertNull(controller.activeTimerRunId)
    }

    @Test
    fun revisionOverflowFailsBeforeEngineOrSessionChange() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val session = ExecutionSession.create(routine(steps = listOf(timer("a", 10_000L))), "session-max")
        val controller = ExecutionController(
            clock = wall,
            initialState = session,
            initialRevision = Long.MAX_VALUE,
            monotonicClock = mono
        )
        val result = controller.start(Long.MAX_VALUE) as ExecutionControllerResult.Failure
        assertEquals(ExecutionControllerError.REVISION_OVERFLOW, result.error)
        assertEquals(ExecutionStatus.READY, controller.snapshot().session.status)
        assertNull(controller.activeTimerRunId)
        assertEquals(Long.MAX_VALUE, controller.snapshot().revision)
        assertEquals(TimerRunStatus.IDLE,
            (controller.timerEngineForTesting().snapshot() as TimerOperationResult.Success).snapshot.status)
    }

    @Test
    fun cancelReadyTimerStepStillCancelsSession() {
        val controller = controller(routine(steps = listOf(timer("a", 10_000L))))
        val result = controller.cancel(0L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.CANCELLED, result.session.status)
        assertNull(controller.activeTimerRunId)
        assertEquals(1L, result.revision)
    }

    @Test
    fun pollPausedCancelledAndOldRunAreNoChange() {
        val wall = LifecycleWallClock(1_000L)
        val mono = LifecycleMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("a", 10_000L))), wall, mono
        )
        controller.start(0L)
        val run = controller.activeTimerRunId!!
        wall.current = 3_000L
        mono.current = 3_000L
        controller.pause(1L)
        val before = controller.snapshot()
        val paused = controller.pollTimer(2L, run) as ExecutionControllerResult.Success
        assertEquals(before.revision, paused.revision)
        assertEquals(before.session, paused.session)

        controller.resume(2L)
        val old = controller.pollTimer(3L, run) as ExecutionControllerResult.Success
        assertEquals(3L, old.revision)
        val freshRun = controller.activeTimerRunId!!
        wall.current = 5_000L
        mono.current = 5_000L
        controller.cancel(3L)
        val cancelled = controller.pollTimer(4L, freshRun) as ExecutionControllerResult.Success
        assertEquals(4L, cancelled.revision)
        assertEquals(ExecutionStatus.CANCELLED, cancelled.session.status)
    }

    private fun controller(
        routine: Routine,
        wall: LifecycleWallClock = LifecycleWallClock(1_000L),
        mono: LifecycleMonotonicClock = LifecycleMonotonicClock(1_000L)
    ): ExecutionController = ExecutionController(
        clock = wall,
        initialState = ExecutionSession.create(routine, "session-1"),
        initialRevision = 0L,
        monotonicClock = mono
    )

    private fun step(session: ExecutionSession): RoutineStep =
        session.routineSnapshot.steps[session.progress.currentStepIndex]

    private fun routine(steps: List<RoutineStep>) = Routine(
        id = "routine",
        name = "Routine",
        repeatCount = 1,
        steps = steps,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun timer(id: String, duration: Long) = RoutineStep(
        id = id,
        name = id,
        type = StepType.TIMER,
        durationMs = duration,
        category = TimerCategory.NORMAL
    )

    private fun count(id: String, target: Int) = RoutineStep(
        id = id,
        name = id,
        type = StepType.COUNT,
        target = target,
        countMode = CountMode.INCREMENT
    )

    private class LifecycleWallClock(var current: Long) : ExecutionClock {
        override fun now(): Long = current
    }

    private class LifecycleMonotonicClock(var current: Long) :
        com.flow.app.domain.execution.timer.MonotonicClock {
        override fun nowMs(): Long = current
    }
}
