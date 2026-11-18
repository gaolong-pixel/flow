package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.TimerOperationResult
import com.flow.app.domain.execution.timer.TimerRunId
import com.flow.app.domain.execution.timer.TimerRunStatus
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionControllerTimerStartTest {

    @Test
    fun startAutomaticallyStartsFirstTimer() {
        val wall = FakeExecutionClock(1_000L)
        val monotonic = FakeMonotonicClock(1_000L)
        val controller = timerController(wall, monotonic)

        val result = controller.start(0L) as ExecutionControllerResult.Success
        val session = result.session
        val engine = (controller.timerEngineForTesting().snapshot() as TimerOperationResult.Success).snapshot

        assertEquals(1L, result.revision)
        assertEquals(1L, controller.snapshot().revision)
        assertEquals(ExecutionStatus.RUNNING, session.status)
        assertEquals(TimerStatus.RUNNING, session.timerState.timerStatus)
        assertEquals(30_000L, session.timerState.totalDurationMs)
        assertEquals(31_000L, session.timerState.deadline)
        assertEquals(0, session.records.size)
        assertEquals(TimerRunStatus.RUNNING, engine.status)
        assertEquals(session.timerState.deadline, engine.deadlineMonotonicMs)
        assertNotNull(controller.activeTimerRunId)
        assertEquals(controller.activeTimerRunId, engine.runId)
        assertEquals(controller.currentTimerStepKey(), controller.activeTimerStepKey)
    }

    @Test
    fun countStartDoesNotCreateTimerReference() {
        val count = countController()
        count.start(0L)
        assertNull(count.activeTimerRunId)
        assertNull(count.activeTimerStepKey)
        assertEquals(
            TimerRunStatus.IDLE,
            (count.timerEngineForTesting().snapshot() as TimerOperationResult.Success)
                .snapshot.status
        )
    }

    @Test
    fun duplicateStartCurrentTimerIsRejectedAfterAutomaticStart() {
        val controller = timerController()
        controller.start(0L)

        val duplicate = controller.startCurrentTimer(1L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMER_STATE, duplicate.error)
        assertEquals(1L, controller.snapshot().revision)
        assertEquals(
            TimerRunStatus.RUNNING,
            (controller.timerEngineForTesting().snapshot() as TimerOperationResult.Success)
                .snapshot.status
        )
    }

    @Test
    fun staleRevisionDoesNotStartEngine() {
        val controller = timerController()
        controller.start(0L)

        val result = controller.startCurrentTimer(0L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.STALE_OPERATION, result.error)
        assertEquals(1L, controller.snapshot().revision)
    }

    @Test
    fun expiredAutomaticStartLeavesControllerAndEngineIdle() {
        val monotonic = SequenceMonotonicClock(1_000L, 31_000L)
        val controller = timerController(FakeExecutionClock(1_000L), monotonic)

        val result = controller.start(0L) as ExecutionControllerResult.Failure

        assertEquals(ExecutionControllerError.INVALID_TIMESTAMP, result.error)
        assertEquals(0L, controller.snapshot().revision)
        assertEquals(ExecutionStatus.READY, controller.snapshot().session.status)
        assertNull(controller.activeTimerRunId)
        assertNull(controller.activeTimerStepKey)
    }

    @Test
    fun rejectsCountStepAndNonRunningSession() {
        val count = countController()
        count.start(0L)
        assertEquals(
            ExecutionControllerError.INVALID_TIMER_STATE,
            (count.startCurrentTimer(1L) as ExecutionControllerResult.Failure).error
        )

        val ready = timerController()
        assertEquals(
            ExecutionControllerError.SESSION_NOT_RUNNING,
            (ready.startCurrentTimer(0L) as ExecutionControllerResult.Failure).error
        )
    }

    private fun timerController(
        wall: FakeExecutionClock = FakeExecutionClock(1_000L),
        monotonic: com.flow.app.domain.execution.timer.MonotonicClock =
            FakeMonotonicClock(1_000L)
    ): ExecutionController {
        val session = ExecutionSession.create(
            routine = timerRoutine(),
            sessionId = "session-1"
        )
        return ExecutionController(
            clock = wall,
            initialState = session,
            initialRevision = 0L,
            monotonicClock = monotonic
        )
    }

    private fun countController(): ExecutionController {
        val session = ExecutionSession.create(
            routine = countRoutine(),
            sessionId = "session-count"
        )
        return ExecutionController(
            clock = FakeExecutionClock(1_000L),
            initialState = session,
            initialRevision = 0L,
            monotonicClock = FakeMonotonicClock(1_000L)
        )
    }

    private fun timerRoutine(): Routine = Routine(
        id = "routine-1",
        name = "Timer",
        repeatCount = 1,
        steps = listOf(
            RoutineStep(
                id = "timer",
                name = "Plank",
                type = StepType.TIMER,
                durationMs = 30_000L,
                category = TimerCategory.NORMAL
            )
        ),
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun countRoutine(): Routine = Routine(
        id = "routine-count",
        name = "Count",
        repeatCount = 1,
        steps = listOf(
            RoutineStep(
                id = "count",
                name = "Pushups",
                type = StepType.COUNT,
                target = 2,
                countMode = CountMode.INCREMENT
            )
        ),
        createdAt = 1L,
        updatedAt = 1L
    )

    private class FakeExecutionClock(var current: Long) : ExecutionClock {
        override fun now(): Long = current
    }

    private class FakeMonotonicClock(var current: Long) :
        com.flow.app.domain.execution.timer.MonotonicClock {
        override fun nowMs(): Long = current
    }

    private class SequenceMonotonicClock(vararg values: Long) :
        com.flow.app.domain.execution.timer.MonotonicClock {
        private val values = values.toList()
        private var index = 0

        override fun nowMs(): Long {
            val value = values[index.coerceAtMost(values.lastIndex)]
            index += 1
            return value
        }
    }
}
