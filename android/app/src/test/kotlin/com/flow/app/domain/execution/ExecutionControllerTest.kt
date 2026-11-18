package com.flow.app.domain.execution

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutionControllerTest {

    @Test
    fun createsReadyControllerSession() {
        val clock = FakeClock()
        val controller = controller(clock)

        val snapshot = controller.snapshot()

        assertEquals(ExecutionStatus.READY, snapshot.session.status)
        assertEquals(null, snapshot.session.currentRoundStartedAt)
        assertEquals(0L, snapshot.revision)
    }

    @Test
    fun rejectsEmptyRoutineWithoutCreatingSession() {
        val result = ExecutionController.create(
            routine = routine(steps = emptyList()),
            sessionId = "session-1",
            clock = FakeClock()
        )

        assertEquals(
            ExecutionControllerError.EMPTY_ROUTINE,
            (result as ExecutionControllerCreateResult.Failure).error
        )
    }

    @Test
    fun startInitializesSessionAndRoundTimestamps() {
        val clock = FakeClock(5_000L)
        val controller = controller(clock)

        val result = controller.start(0L)

        val success = result as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.RUNNING, success.session.status)
        assertEquals(5_000L, success.session.startedAt)
        assertEquals(5_000L, success.session.currentRoundStartedAt)
        assertEquals(1L, success.revision)
        assertEquals(1L, controller.snapshot().revision)
    }

    @Test
    fun pauseRetainsRoundStartAndResumeRetainsIt() {
        val clock = FakeClock(5_000L)
        val controller = controller(clock)
        controller.start(0L)
        clock.current = 6_000L

        val paused = controller.pause(1L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.PAUSED, paused.session.status)
        assertEquals(5_000L, paused.session.currentRoundStartedAt)
        assertEquals(5_000L, paused.session.startedAt)

        clock.current = 7_000L
        val resumed = controller.resume(2L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.RUNNING, resumed.session.status)
        assertEquals(5_000L, resumed.session.currentRoundStartedAt)
        assertEquals(5_000L, resumed.session.startedAt)
    }

    @Test
    fun cancelFromReadyKeepsNoRoundStart() {
        val clock = FakeClock(5_000L)
        val controller = controller(clock)

        val cancelled = controller.cancel(0L) as ExecutionControllerResult.Success

        assertEquals(ExecutionStatus.CANCELLED, cancelled.session.status)
        assertEquals(null, cancelled.session.currentRoundStartedAt)
        assertEquals(1L, cancelled.revision)
    }

    @Test
    fun cancelFromRunningClearsRoundStartAndPreservesRecords() {
        val clock = FakeClock(5_000L)
        val controller = controller(clock)
        controller.start(0L)

        val cancelled = controller.cancel(1L) as ExecutionControllerResult.Success

        assertEquals(ExecutionStatus.CANCELLED, cancelled.session.status)
        assertEquals(null, cancelled.session.currentRoundStartedAt)
        assertEquals(5_000L, cancelled.session.startedAt)
        assertEquals(2L, cancelled.revision)
        assertEquals(0, cancelled.session.records.size)
    }

    @Test
    fun cancelFromPausedClearsRoundStart() {
        val clock = FakeClock(5_000L)
        val controller = controller(clock)
        controller.start(0L)
        controller.pause(1L)

        val cancelled = controller.cancel(2L) as ExecutionControllerResult.Success

        assertEquals(ExecutionStatus.CANCELLED, cancelled.session.status)
        assertEquals(null, cancelled.session.currentRoundStartedAt)
        assertEquals(3L, cancelled.revision)
    }

    @Test
    fun completedSessionCannotBeCancelled() {
        val completed = completedSession()
        val controller = ExecutionController(FakeClock(3_000L), completed, 1L)

        val result = controller.cancel(1L)

        val failure = result as ExecutionControllerResult.Failure
        assertEquals(ExecutionControllerError.TERMINAL_SESSION, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(ExecutionStatus.COMPLETED, controller.snapshot().session.status)
    }

    @Test
    fun cancelledSessionCannotBeCancelledAgain() {
        val controller = controller(FakeClock())
        controller.cancel(0L)

        val failure = controller.cancel(1L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.TERMINAL_SESSION, failure.error)
        assertEquals(1L, failure.revision)
    }

    @Test
    fun readySessionCannotPause() {
        val controller = controller(FakeClock())

        val failure = controller.pause(0L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.SESSION_NOT_RUNNING, failure.error)
        assertEquals(0L, failure.revision)
    }

    @Test
    fun readySessionCannotResume() {
        val controller = controller(FakeClock())

        val failure = controller.resume(0L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.SESSION_NOT_PAUSED, failure.error)
        assertEquals(0L, failure.revision)
    }

    @Test
    fun runningSessionCannotResume() {
        val controller = controller(FakeClock())
        controller.start(0L)

        val failure = controller.resume(1L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.SESSION_NOT_PAUSED, failure.error)
        assertEquals(1L, failure.revision)
    }

    @Test
    fun pausedSessionCannotPauseAgain() {
        val controller = controller(FakeClock())
        controller.start(0L)
        controller.pause(1L)

        val failure = controller.pause(2L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.SESSION_NOT_RUNNING, failure.error)
        assertEquals(2L, failure.revision)
    }

    @Test
    fun eachSuccessfulLifecycleOperationIncrementsRevisionOnce() {
        val controller = controller(FakeClock())

        controller.start(0L)
        controller.pause(1L)
        controller.resume(2L)
        controller.cancel(3L)

        assertEquals(4L, controller.snapshot().revision)
    }

    @Test
    fun failedOperationKeepsRevisionAndSessionUnchanged() {
        val controller = controller(FakeClock())
        val before = controller.snapshot()

        val failure = controller.pause(0L) as ExecutionControllerResult.Failure

        val after = controller.snapshot()
        assertEquals(ExecutionControllerError.SESSION_NOT_RUNNING, failure.error)
        assertEquals(before.revision, after.revision)
        assertEquals(before.session, after.session)
    }

    @Test
    fun staleExpectedRevisionIsRejected() {
        val controller = controller(FakeClock())
        controller.start(0L)

        val failure = controller.pause(0L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.STALE_OPERATION, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(ExecutionStatus.RUNNING, controller.snapshot().session.status)
    }

    @Test
    fun queryDoesNotChangeRevision() {
        val controller = controller(FakeClock())
        val first = controller.snapshot()
        val second = controller.snapshot()

        assertEquals(0L, first.revision)
        assertEquals(0L, second.revision)
    }

    @Test
    fun fakeClockControlsStartAndCancelTime() {
        val clock = FakeClock(9_000L)
        val controller = controller(clock)
        controller.start(0L)
        clock.current = 12_000L

        val cancelled = controller.cancel(1L) as ExecutionControllerResult.Success

        assertEquals(9_000L, cancelled.session.startedAt)
        assertEquals(12_000L, cancelled.session.cancelledAt)
    }

    @Test
    fun clockGoingBackwardsDoesNotCancelOrChangeState() {
        val clock = FakeClock(5_000L)
        val controller = controller(clock)
        controller.start(0L)
        clock.current = 4_000L
        val before = controller.snapshot()

        val failure = controller.cancel(1L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, failure.error)
        assertEquals(before.revision, failure.revision)
        assertEquals(before.revision, controller.snapshot().revision)
        assertEquals(ExecutionStatus.RUNNING, controller.snapshot().session.status)
        assertEquals(5_000L, controller.snapshot().session.currentRoundStartedAt)
    }

    @Test
    fun negativeClockDoesNotChangeStateOrRevision() {
        val clock = FakeClock(-1L)
        val controller = controller(clock)

        val failure = controller.start(0L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, failure.error)
        assertEquals(0L, controller.snapshot().revision)
        assertEquals(ExecutionStatus.READY, controller.snapshot().session.status)
    }

    @Test
    fun sameExpectedRevisionOnlyAllowsOneSequentialStart() {
        val controller = controller(FakeClock(5_000L))

        val first = controller.start(0L)
        val second = controller.start(0L)

        assertTrue(first is ExecutionControllerResult.Success)
        val failure = second as ExecutionControllerResult.Failure
        assertEquals(ExecutionControllerError.STALE_OPERATION, failure.error)
        assertEquals(1L, failure.revision)
        assertEquals(1L, controller.snapshot().revision)
    }

    @Test
    fun sameExpectedRevisionOnlyAllowsOneConcurrentStart() {
        val controller = controller(FakeClock(5_000L))
        val ready = CountDownLatch(1)
        val successCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)

        val results = (1..2).map {
            executor.submit<ExecutionControllerResult> {
                ready.await()
                controller.start(0L)
            }
        }
        ready.countDown()
        results.forEach { result ->
            if (result.get(2, TimeUnit.SECONDS) is ExecutionControllerResult.Success) {
                successCount.incrementAndGet()
            }
        }
        executor.shutdownNow()

        assertEquals(1, successCount.get())
        assertEquals(1L, controller.snapshot().revision)
        assertEquals(ExecutionStatus.RUNNING, controller.snapshot().session.status)
    }

    private fun controller(clock: ExecutionClock): ExecutionController =
        (ExecutionController.create(
            routine = routine(steps = listOf(countStep("a", "A", 2))),
            sessionId = "session-1",
            clock = clock
        ) as ExecutionControllerCreateResult.Success).controller

    private fun completedSession(): ExecutionSession {
        val initial = ExecutionSession.create(
            routine = routine(steps = listOf(countStep("a", "A", 1))),
            sessionId = "session-1"
        )
        val running = initial.start(1_000L)
        val record = ExecutionStepRecord(
            stepId = "a",
            routineIteration = 1,
            stepIteration = 1,
            outcome = ExecutionStepOutcome.COMPLETED,
            startedAt = 1_000L,
            endedAt = 1_500L
        )
        return running.appendRecord(record).complete(2_000L)
    }

    private fun routine(steps: List<RoutineStep>): Routine = Routine(
        id = "routine-1",
        name = "Test",
        steps = steps,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun countStep(id: String, name: String, target: Int): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.COUNT,
        target = target,
        countMode = CountMode.INCREMENT
    )

    private class FakeClock(var current: Long = 1_000L) : ExecutionClock {
        override fun now(): Long = current
    }
}
