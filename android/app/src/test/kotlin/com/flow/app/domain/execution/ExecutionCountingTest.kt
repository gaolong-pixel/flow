package com.flow.app.domain.execution

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionCountingTest {

    @Test
    fun incrementFromZeroUpdatesOnlyCountAndRevision() {
        val clock = FakeClock(1_000L)
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 3))),
            clock = clock
        )
        val started = controller.start(0L) as ExecutionControllerResult.Success

        val result = controller.incrementCount(started.revision) as CountIncrementResult.Incremented

        assertEquals(1, result.currentCount)
        assertEquals(3, result.target)
        assertEquals(1, result.session.progress.currentCount)
        assertEquals(0, result.session.records.size)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
        assertEquals(started.session.startedAt, result.session.startedAt)
        assertEquals(started.session.currentRoundStartedAt, result.session.currentRoundStartedAt)
        assertEquals(started.revision + 1, result.revision)
        assertEquals(2L, controller.snapshot().revision)
    }

    @Test
    fun continuousIncrementsBelowTargetKeepRecordsEmpty() {
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 4)))
        )
        controller.start(0L)

        var revision = controller.snapshot().revision
        repeat(3) {
            val result = controller.incrementCount(revision) as CountIncrementResult.Incremented
            revision = result.revision
        }

        val snapshot = controller.snapshot()
        assertEquals(3, snapshot.session.progress.currentCount)
        assertEquals(0, snapshot.session.records.size)
        assertEquals(4L, snapshot.revision)
        assertEquals(ExecutionStatus.RUNNING, snapshot.session.status)
    }

    @Test
    fun reachingTargetCreatesOneRecordAndAdvances() {
        val clock = FakeClock(1_000L)
        val controller = controller(
            routine = routine(
                steps = listOf(countStep("a", "A", 2, repeatCount = 2))
            ),
            clock = clock
        )
        controller.start(0L)
        controller.incrementCount(1L)

        clock.current = 1_500L
        val result = controller.incrementCount(2L) as CountIncrementResult.RoundCompleted

        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, result.session.records.single().outcome)
        assertEquals(1, result.session.records.single().stepIteration)
        assertEquals(0, result.session.progress.currentCount)
        assertEquals(2, result.session.progress.stepIteration)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
        assertEquals(3L, result.revision)
    }

    @Test
    fun targetOneCompletesImmediately() {
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 1)))
        )
        controller.start(0L)

        val result = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted

        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(1, result.session.records.size)
        assertEquals(null, result.session.currentRoundStartedAt)
        assertEquals(2L, result.revision)
    }

    @Test
    fun completionMovesToNextStepWithCountReset() {
        val controller = controller(
            routine = routine(
                steps = listOf(
                    countStep("a", "A", 1),
                    countStep("b", "B", 2)
                )
            )
        )
        controller.start(0L)

        val first = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted
        assertEquals("b", first.session.progress.currentStepId)
        assertEquals(0, first.session.progress.currentCount)

        val second = controller.incrementCount(first.revision) as CountIncrementResult.Incremented
        assertEquals(1, second.session.progress.currentCount)
        assertEquals(1, second.session.records.size)
    }

    @Test
    fun completionMovesToNextRoutineWithCountReset() {
        val controller = controller(
            routine = routine(
                steps = listOf(countStep("a", "A", 1)),
                repeatCount = 2
            )
        )
        controller.start(0L)

        val result = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted

        assertEquals(2, result.session.progress.routineIteration)
        assertEquals(0, result.session.progress.currentCount)
        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
    }

    @Test
    fun multipleStepsAndRoundsFinishWithCompleteRecords() {
        val controller = controller(
            routine = routine(
                steps = listOf(
                    countStep("a", "A", 1, repeatCount = 2),
                    countStep("b", "B", 1)
                ),
                repeatCount = 2
            )
        )
        controller.start(0L)
        var revision = 1L
        repeat(6) {
            val result = controller.incrementCount(revision)
            if (result is CountIncrementResult.RoundCompleted) {
                revision = result.revision
            } else {
                error("Expected completion: $result")
            }
        }

        val snapshot = controller.snapshot()
        assertEquals(ExecutionStatus.COMPLETED, snapshot.session.status)
        assertEquals(6, snapshot.session.records.size)
        assertEquals(6, snapshot.session.completedCount)
        assertEquals(7L, snapshot.revision)
    }

    @Test
    fun targetMinusOneToTargetCompletes() {
        val session = runningCountSession(
            target = 5,
            currentCount = 4,
            repeatCount = 1
        )
        val controller = ExecutionController(FakeClock(2_000L), session, 0L)

        val result = controller.incrementCount(0L) as CountIncrementResult.RoundCompleted

        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(1, result.session.records.size)
        assertEquals(1L, result.revision)
    }

    @Test
    fun largeTargetDoesNotOverflow() {
        val session = runningCountSession(
            target = Int.MAX_VALUE,
            currentCount = Int.MAX_VALUE - 1,
            repeatCount = 1
        )
        val controller = ExecutionController(FakeClock(2_000L), session, 0L)

        val result = controller.incrementCount(0L) as CountIncrementResult.RoundCompleted

        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(1, result.session.records.size)
    }

    @Test
    fun sameExpectedRevisionAllowsOnlyOneCount() {
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 3)))
        )
        controller.start(0L)

        val first = controller.incrementCount(1L)
        val second = controller.incrementCount(1L)

        assertTrue(first is CountIncrementResult.Incremented)
        val failure = second as CountIncrementResult.Failure
        assertEquals(ExecutionControllerError.STALE_OPERATION, failure.error)
        assertEquals(2L, failure.revision)
        assertEquals(1, controller.snapshot().session.progress.currentCount)
        assertEquals(2L, controller.snapshot().revision)
    }

    @Test
    fun pausedCountIsRejectedWithoutStateChange() {
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 3)))
        )
        controller.start(0L)
        controller.pause(1L)

        val failure = controller.incrementCount(2L) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.SESSION_NOT_RUNNING, failure.error)
        assertEquals(2L, failure.revision)
        assertEquals(ExecutionStatus.PAUSED, controller.snapshot().session.status)
        assertEquals(0, controller.snapshot().session.progress.currentCount)
    }

    @Test
    fun cancelledCountIsRejected() {
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 3)))
        )
        controller.start(0L)
        controller.cancel(1L)

        val failure = controller.incrementCount(2L) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.TERMINAL_SESSION, failure.error)
        assertEquals(2L, failure.revision)
        assertEquals(ExecutionStatus.CANCELLED, controller.snapshot().session.status)
    }

    @Test
    fun completedCountIsRejected() {
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 1)))
        )
        controller.start(0L)
        val completed = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted

        val failure = controller.incrementCount(completed.revision) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.TERMINAL_SESSION, failure.error)
        assertEquals(2L, failure.revision)
    }

    @Test
    fun timerStepIncrementIsRejected() {
        val controller = controller(
            routine = routine(steps = listOf(timerStep("t", "T", 30_000L)))
        )
        controller.start(0L)

        val failure = controller.incrementCount(1L) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.NOT_COUNT_STEP, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(0, controller.snapshot().session.records.size)
    }

    @Test
    fun completeModeIncrementIsRejected() {
        val controller = controller(
            routine = routine(
                steps = listOf(
                    countStep("a", "A", 3, mode = CountMode.COMPLETE)
                )
            )
        )
        controller.start(0L)

        val failure = controller.incrementCount(1L) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.COUNT_MODE_NOT_INCREMENT, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(0, controller.snapshot().session.progress.currentCount)
    }

    @Test
    fun countAlreadyAtTargetIsRejected() {
        val session = runningCountSession(
            target = 2,
            currentCount = 2,
            repeatCount = 1
        )
        val controller = ExecutionController(FakeClock(2_000L), session, 0L)

        val failure = controller.incrementCount(0L) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.COUNT_ALREADY_AT_TARGET, failure.error)
        assertEquals(0L, failure.revision)
        assertEquals(2, controller.snapshot().session.progress.currentCount)
    }

    @Test
    fun targetReachedSchedulingFailureLeavesSessionAndRevisionUntouched() {
        val clock = FakeClock(1_000L)
        val controller = controller(
            routine = routine(steps = listOf(countStep("a", "A", 1))),
            clock = clock
        )
        controller.start(0L)
        clock.current = 500L

        val failure = controller.incrementCount(1L) as CountIncrementResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, failure.error)
        assertEquals(1L, failure.revision)
        val snapshot = controller.snapshot()
        assertEquals(ExecutionStatus.RUNNING, snapshot.session.status)
        assertEquals(0, snapshot.session.progress.currentCount)
        assertEquals(0, snapshot.session.records.size)
        assertEquals(1_000L, snapshot.session.currentRoundStartedAt)
        assertEquals(1L, snapshot.revision)
    }

    private fun controller(routine: Routine, clock: FakeClock = FakeClock()): ExecutionController {
        val result = ExecutionController.create(
            routine = routine,
            sessionId = "session-1",
            clock = clock
        )
        return (result as ExecutionControllerCreateResult.Success).controller
    }

    private fun runningCountSession(
        target: Int,
        currentCount: Int,
        repeatCount: Int
    ): ExecutionSession = ExecutionSession(
        sessionId = "session-1",
        routineSnapshot = routine(
            steps = listOf(countStep("a", "A", target, repeatCount = repeatCount))
        ),
        status = ExecutionStatus.RUNNING,
        progress = ExecutionProgress(
            routineIteration = 1,
            currentStepIndex = 0,
            currentStepId = "a",
            stepIteration = 1,
            currentCount = currentCount
        ),
        timerState = TimerState(0L, 0L, null, TimerStatus.IDLE),
        records = emptyList(),
        startedAt = 1_000L,
        currentRoundStartedAt = 1_000L
    )

    private fun routine(
        steps: List<RoutineStep>,
        repeatCount: Int = 1
    ): Routine = Routine(
        id = "routine-1",
        name = "Test",
        repeatCount = repeatCount,
        steps = steps,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun countStep(
        id: String,
        name: String,
        target: Int,
        repeatCount: Int = 1,
        mode: CountMode = CountMode.INCREMENT
    ): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.COUNT,
        target = target,
        countMode = mode,
        repeatCount = repeatCount
    )

    private fun timerStep(id: String, name: String, durationMs: Long): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.TIMER,
        durationMs = durationMs,
        category = TimerCategory.NORMAL
    )

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }

    private class FakeClock(var current: Long = 1_000L) : ExecutionClock {
        override fun now(): Long = current
    }
}
