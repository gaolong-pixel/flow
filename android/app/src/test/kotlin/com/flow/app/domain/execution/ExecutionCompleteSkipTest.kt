package com.flow.app.domain.execution

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCompleteSkipTest {

    @Test
    fun completeConfirmationCreatesRecordAndAdvances() {
        val controller = controller(
            routine(
                steps = listOf(
                    countStep("a", "A", 5, repeatCount = 2, mode = CountMode.COMPLETE)
                )
            )
        )
        controller.start(0L)

        val result = controller.confirmComplete(1L) as StepActionResult.Completed

        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, result.session.records.single().outcome)
        assertEquals(0, result.session.progress.currentCount)
        assertEquals(2, result.session.progress.stepIteration)
        assertEquals(false, result.session.progress.completeConfirmed)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
        assertEquals(2L, result.revision)
    }

    @Test
    fun completeConfirmationDoesNotFabricateCurrentCount() {
        val session = runningSession(
            step = countStep("a", "A", 5, mode = CountMode.COMPLETE),
            currentCount = 2,
            completeConfirmed = false
        )
        val controller = ExecutionController(FakeClock(2_000L), session, 0L)

        val result = controller.confirmComplete(0L) as StepActionResult.Completed

        assertEquals(2, result.session.progress.currentCount)
        assertEquals(true, result.session.progress.completeConfirmed)
        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(1, result.session.records.size)
    }

    @Test
    fun completeConfirmationMovesToNextStep() {
        val controller = controller(
            routine(
                steps = listOf(
                    countStep("a", "A", 3, mode = CountMode.COMPLETE),
                    countStep("b", "B", 3, mode = CountMode.COMPLETE)
                )
            )
        )
        controller.start(0L)

        val result = controller.confirmComplete(1L) as StepActionResult.Completed

        assertEquals("b", result.session.progress.currentStepId)
        assertEquals(1, result.session.progress.currentStepIndex)
        assertEquals(0, result.session.progress.currentCount)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
    }

    @Test
    fun completeConfirmationMovesToNextRoutine() {
        val controller = controller(
            routine(
                steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)),
                repeatCount = 2
            )
        )
        controller.start(0L)

        val result = controller.confirmComplete(1L) as StepActionResult.Completed

        assertEquals(2, result.session.progress.routineIteration)
        assertEquals(0, result.session.progress.currentCount)
        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
    }

    @Test
    fun completeConfirmationFinalizesSession() {
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 5, mode = CountMode.COMPLETE)))
        )
        controller.start(0L)

        val result = controller.confirmComplete(1L) as StepActionResult.Completed

        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(1, result.session.records.size)
        assertEquals(null, result.session.currentRoundStartedAt)
        assertEquals(true, result.session.progress.completeConfirmed)
        assertEquals(1_500L, result.session.completedAt)
        assertEquals(2L, result.revision)
    }

    @Test
    fun completeConfirmationRejectsIncrementMode() {
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.INCREMENT)))
        )
        controller.start(0L)

        val failure = controller.confirmComplete(1L) as StepActionResult.Failure

        assertEquals(ExecutionControllerError.COUNT_MODE_NOT_COMPLETE, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(0, controller.snapshot().session.records.size)
    }

    @Test
    fun completeConfirmationRejectsTimer() {
        val controller = controller(
            routine(steps = listOf(timerStep("t", "T", 30_000L)))
        )
        controller.start(0L)

        val failure = controller.confirmComplete(1L) as StepActionResult.Failure

        assertEquals(ExecutionControllerError.NOT_COUNT_STEP, failure.error)
        assertEquals(1L, failure.revision)
    }

    @Test
    fun duplicateCompleteConfirmationIsRejected() {
        val session = runningSession(
            step = countStep("a", "A", 3, mode = CountMode.COMPLETE),
            currentCount = 0,
            completeConfirmed = true
        )
        val controller = ExecutionController(FakeClock(2_000L), session, 0L)

        val failure = controller.confirmComplete(0L) as StepActionResult.Failure

        assertEquals(ExecutionControllerError.COMPLETE_ALREADY_CONFIRMED, failure.error)
        assertEquals(0L, failure.revision)
        assertEquals(0, controller.snapshot().session.records.size)
    }

    @Test
    fun completeConfirmationRejectsPausedCancelledAndCompleted() {
        val pausedController = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)))
        )
        pausedController.start(0L)
        pausedController.pause(1L)
        assertEquals(
            ExecutionControllerError.SESSION_NOT_RUNNING,
            (pausedController.confirmComplete(2L) as StepActionResult.Failure).error
        )

        val cancelledController = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)))
        )
        cancelledController.start(0L)
        cancelledController.cancel(1L)
        assertEquals(
            ExecutionControllerError.TERMINAL_SESSION,
            (cancelledController.confirmComplete(2L) as StepActionResult.Failure).error
        )

        val completedController = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)))
        )
        completedController.start(0L)
        completedController.confirmComplete(1L)
        assertEquals(
            ExecutionControllerError.TERMINAL_SESSION,
            (completedController.confirmComplete(2L) as StepActionResult.Failure).error
        )
    }

    @Test
    fun completeConfirmationRejectsStaleRevision() {
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)))
        )
        controller.start(0L)

        val failure = controller.confirmComplete(0L) as StepActionResult.Failure

        assertEquals(ExecutionControllerError.STALE_OPERATION, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(0, controller.snapshot().session.records.size)
    }

    @Test
    fun completeConfirmationFailureKeepsSessionUnchanged() {
        val clock = FakeClock(1_000L)
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE))),
            clock = clock
        )
        controller.start(0L)
        clock.current = 500L
        val before = controller.snapshot()

        val failure = controller.confirmComplete(1L) as StepActionResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, failure.error)
        assertUnchanged(before, controller.snapshot())
    }

    @Test
    fun skipCountIncrementCreatesSkippedRecordAndAdvancesRound() {
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 5, repeatCount = 2)))
        )
        controller.start(0L)

        val result = controller.skipCurrentRound(1L) as StepActionResult.Skipped

        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.SKIPPED, result.session.records.single().outcome)
        assertEquals(0, result.session.completedCount)
        assertEquals(1, result.session.skippedCount)
        assertEquals(2, result.session.progress.stepIteration)
        assertEquals(0, result.session.progress.currentCount)
        assertEquals(ExecutionStatus.RUNNING, result.session.status)
        assertEquals(2L, result.revision)
    }

    @Test
    fun skipCountCompleteAndTimerAreAllowed() {
        val completeController = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)))
        )
        completeController.start(0L)
        val completeSkip = completeController.skipCurrentRound(1L) as StepActionResult.Skipped
        assertEquals(ExecutionStepOutcome.SKIPPED, completeSkip.session.records.single().outcome)

        val timerController = controller(
            routine(steps = listOf(timerStep("t", "T", 30_000L)))
        )
        timerController.start(0L)
        val timerSkip = timerController.skipCurrentRound(1L) as StepActionResult.Skipped
        assertEquals(ExecutionStepOutcome.SKIPPED, timerSkip.session.records.single().outcome)
    }

    @Test
    fun skipMovesToNextStepAndNextRoutine() {
        val nextStep = controller(
            routine(
                steps = listOf(
                    countStep("a", "A", 3),
                    countStep("b", "B", 3)
                )
            )
        )
        nextStep.start(0L)
        val stepResult = nextStep.skipCurrentRound(1L) as StepActionResult.Skipped
        assertEquals("b", stepResult.session.progress.currentStepId)
        assertEquals(0, stepResult.session.progress.currentCount)

        val nextRoutine = controller(
            routine(
                steps = listOf(countStep("a", "A", 3)),
                repeatCount = 2
            )
        )
        nextRoutine.start(0L)
        val routineResult = nextRoutine.skipCurrentRound(1L) as StepActionResult.Skipped
        assertEquals(2, routineResult.session.progress.routineIteration)
        assertEquals(0, routineResult.session.progress.currentCount)
    }

    @Test
    fun finalSkipCompletesSessionWithoutCompletedCount() {
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 3)))
        )
        controller.start(0L)

        val result = controller.skipCurrentRound(1L) as StepActionResult.Skipped

        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(0, result.session.completedCount)
        assertEquals(1, result.session.skippedCount)
        assertEquals(null, result.session.currentRoundStartedAt)
        assertEquals(1_500L, result.session.completedAt)
    }

    @Test
    fun skipRejectsStalePausedCancelledAndCompleted() {
        val staleController = controller(
            routine(steps = listOf(countStep("a", "A", 3)))
        )
        staleController.start(0L)
        assertEquals(
            ExecutionControllerError.STALE_OPERATION,
            (staleController.skipCurrentRound(0L) as StepActionResult.Failure).error
        )

        val pausedController = controller(
            routine(steps = listOf(countStep("a", "A", 3)))
        )
        pausedController.start(0L)
        pausedController.pause(1L)
        assertEquals(
            ExecutionControllerError.SESSION_NOT_RUNNING,
            (pausedController.skipCurrentRound(2L) as StepActionResult.Failure).error
        )

        val cancelledController = controller(
            routine(steps = listOf(countStep("a", "A", 3)))
        )
        cancelledController.start(0L)
        cancelledController.cancel(1L)
        assertEquals(
            ExecutionControllerError.TERMINAL_SESSION,
            (cancelledController.skipCurrentRound(2L) as StepActionResult.Failure).error
        )

        val completedController = controller(
            routine(steps = listOf(countStep("a", "A", 3)))
        )
        completedController.start(0L)
        completedController.skipCurrentRound(1L)
        assertEquals(
            ExecutionControllerError.TERMINAL_SESSION,
            (completedController.skipCurrentRound(2L) as StepActionResult.Failure).error
        )
    }

    @Test
    fun skipFailureKeepsSessionUnchanged() {
        val clock = FakeClock(1_000L)
        val controller = controller(
            routine(steps = listOf(countStep("a", "A", 3))),
            clock = clock
        )
        controller.start(0L)
        clock.current = 500L
        val before = controller.snapshot()

        val failure = controller.skipCurrentRound(1L) as StepActionResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, failure.error)
        assertUnchanged(before, controller.snapshot())
    }

    @Test
    fun successfulCompleteAndSkipIncrementRevisionOnce() {
        val completeController = controller(
            routine(steps = listOf(countStep("a", "A", 3, mode = CountMode.COMPLETE)))
        )
        completeController.start(0L)
        val completed = completeController.confirmComplete(1L) as StepActionResult.Completed
        assertEquals(2L, completed.revision)
        assertEquals(2L, completeController.snapshot().revision)

        val skipController = controller(
            routine(steps = listOf(countStep("a", "A", 3)))
        )
        skipController.start(0L)
        val skipped = skipController.skipCurrentRound(1L) as StepActionResult.Skipped
        assertEquals(2L, skipped.revision)
        assertEquals(2L, skipController.snapshot().revision)
    }

    private fun assertUnchanged(
        before: ExecutionControllerSnapshot,
        after: ExecutionControllerSnapshot
    ) {
        assertEquals(before.revision, after.revision)
        assertEquals(before.session.status, after.session.status)
        assertEquals(before.session.records, after.session.records)
        assertEquals(before.session.progress, after.session.progress)
        assertEquals(before.session.timerState, after.session.timerState)
        assertEquals(before.session.currentRoundStartedAt, after.session.currentRoundStartedAt)
        assertEquals(before.session.completedAt, after.session.completedAt)
        assertEquals(before.session.cancelledAt, after.session.cancelledAt)
        assertEquals(before.session.startedAt, after.session.startedAt)
    }

    private fun controller(
        routine: Routine,
        clock: FakeClock = FakeClock()
    ): ExecutionController = (ExecutionController.create(
        routine = routine,
        sessionId = "session-1",
        clock = clock
    ) as ExecutionControllerCreateResult.Success).controller

    private fun runningSession(
        step: RoutineStep,
        currentCount: Int,
        completeConfirmed: Boolean
    ): ExecutionSession = ExecutionSession(
        sessionId = "session-1",
        routineSnapshot = routine(steps = listOf(step)),
        status = ExecutionStatus.RUNNING,
        progress = ExecutionProgress(
            routineIteration = 1,
            currentStepIndex = 0,
            currentStepId = step.id,
            stepIteration = 1,
            currentCount = currentCount,
            completeConfirmed = completeConfirmed
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

    private class FakeClock(var current: Long = 1_500L) : ExecutionClock {
        override fun now(): Long = current
    }
}
