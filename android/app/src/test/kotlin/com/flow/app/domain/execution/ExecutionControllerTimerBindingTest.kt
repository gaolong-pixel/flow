package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.TimerOperationResult
import com.flow.app.domain.execution.timer.TimerRunId
import com.flow.app.domain.execution.timer.TimerRunStatus
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionControllerTimerBindingTest {

    @Test
    fun eachControllerOwnsAnIndependentEngine() {
        val first = timerController(FakeExecutionClock(1_000L), FakeMonotonicClock(1_000L))
        val second = timerController(FakeExecutionClock(1_000L), FakeMonotonicClock(1_000L))

        val firstEngine = first.timerEngineForTesting()
        val secondEngine = second.timerEngineForTesting()
        firstEngine.start(1_000L)

        val firstSnapshot = (firstEngine.snapshot() as TimerOperationResult.Success).snapshot
        val secondSnapshot = (secondEngine.snapshot() as TimerOperationResult.Success).snapshot
        assertEquals(TimerRunStatus.RUNNING, firstSnapshot.status)
        assertEquals(TimerRunStatus.IDLE, secondSnapshot.status)
    }

    @Test
    fun controllerEngineUsesInjectedMonotonicClock() {
        val monotonic = FakeMonotonicClock(1_000L)
        val controller = timerController(FakeExecutionClock(1_000L), monotonic)
        val engine = controller.timerEngineForTesting()
        engine.start(1_000L)
        monotonic.current = 1_300L

        val snapshot = (engine.snapshot() as TimerOperationResult.Success).snapshot

        assertEquals(2_000L, snapshot.deadlineMonotonicMs)
        assertEquals(700L, snapshot.remainingDurationMs)
        assertEquals(TimerRunStatus.RUNNING, snapshot.status)
    }

    @Test
    fun activeReferenceStartsEmpty() {
        val controller = timerController()
        assertNull(controller.activeTimerRunId)
        assertNull(controller.activeTimerStepKey)
        assertNull(controller.activeTimerReferenceForTesting())
    }

    @Test
    fun activeReferenceInstallReplaceAndClearAreAtomic() {
        val controller = timerController()
        controller.start(0L)
        val key = controller.currentTimerStepKey()!!
        val first = ActiveTimerReference(TimerRunId("run-1"), key)
        controller.installActiveTimerForTesting(first.runId, first.stepKey)

        assertEquals(first, controller.activeTimerReferenceForTesting())
        assertEquals(first.runId, controller.activeTimerRunId)
        assertEquals(first.stepKey, controller.activeTimerStepKey)

        val second = ActiveTimerReference(TimerRunId("run-2"), key)
        controller.installActiveTimerForTesting(second.runId, second.stepKey)
        assertEquals(second, controller.activeTimerReferenceForTesting())
        assertNotEquals(first.runId, controller.activeTimerRunId)

        controller.clearActiveTimerForTesting()
        assertNull(controller.activeTimerReferenceForTesting())
        assertNull(controller.activeTimerRunId)
        assertNull(controller.activeTimerStepKey)
    }

    @Test
    fun activeTimerStepKeyRequiresCurrentTimerStep() {
        val controller = countController()
        controller.start(0L)
        assertNull(controller.currentTimerStepKey())
    }

    @Test
    fun activeTimerStepKeyDistinguishesAllIdentityFields() {
        val base = ActiveTimerStepKey("session-1", 1, "timer", 1)
        assertEquals(base, ActiveTimerStepKey("session-1", 1, "timer", 1))
        assertNotEquals(base, ActiveTimerStepKey("session-2", 1, "timer", 1))
        assertNotEquals(base, ActiveTimerStepKey("session-1", 2, "timer", 1))
        assertNotEquals(base, ActiveTimerStepKey("session-1", 1, "timer-2", 1))
        assertNotEquals(base, ActiveTimerStepKey("session-1", 1, "timer", 2))
    }

    @Test
    fun activeIdentityAcceptsMatchingFields() {
        val controller = timerController()
        controller.start(0L)
        val key = controller.currentTimerStepKey()!!
        val runId = TimerRunId("run-1")
        controller.installActiveTimerForTesting(runId, key)

        assertTrue(controller.isActiveTimerIdentityValid(
            sessionId = key.sessionId,
            routineIteration = key.routineIteration,
            stepId = key.stepId,
            stepIteration = key.stepIteration,
            timerRunId = runId
        ))
    }

    @Test
    fun activeIdentityRejectsEveryMismatchedField() {
        val controller = timerController()
        controller.start(0L)
        val key = controller.currentTimerStepKey()!!
        val runId = TimerRunId("run-1")
        controller.installActiveTimerForTesting(runId, key)

        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = "other-session",
            routineIteration = key.routineIteration,
            stepId = key.stepId,
            stepIteration = key.stepIteration,
            timerRunId = runId
        ))
        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = key.sessionId,
            routineIteration = key.routineIteration + 1,
            stepId = key.stepId,
            stepIteration = key.stepIteration,
            timerRunId = runId
        ))
        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = key.sessionId,
            routineIteration = key.routineIteration,
            stepId = "other-step",
            stepIteration = key.stepIteration,
            timerRunId = runId
        ))
        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = key.sessionId,
            routineIteration = key.routineIteration,
            stepId = key.stepId,
            stepIteration = key.stepIteration + 1,
            timerRunId = runId
        ))
        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = key.sessionId,
            routineIteration = key.routineIteration,
            stepId = key.stepId,
            stepIteration = key.stepIteration,
            timerRunId = TimerRunId("other-run")
        ))
    }

    @Test
    fun activeIdentityRejectsTerminalAndNonTimerSessions() {
        val terminal = timerController()
        terminal.start(0L)
        val key = terminal.currentTimerStepKey()!!
        val runId = terminal.activeTimerRunId!!
        terminal.cancel(1L)
        terminal.installActiveTimerForTesting(runId, key)
        assertFalse(terminal.isActiveTimerIdentityValid(
            sessionId = key.sessionId,
            routineIteration = key.routineIteration,
            stepId = key.stepId,
            stepIteration = key.stepIteration,
            timerRunId = runId
        ))

        val count = countController()
        count.start(0L)
        val countKey = ActiveTimerStepKey(count.snapshot().session.sessionId, 1, "count", 1)
        count.installActiveTimerForTesting(TimerRunId("run-2"), countKey)
        assertFalse(count.isActiveTimerIdentityValid(
            sessionId = countKey.sessionId,
            routineIteration = 1,
            stepId = "count",
            stepIteration = 1,
            timerRunId = TimerRunId("run-2")
        ))
    }

    @Test
    fun oldStepAndRunCannotMatchNewTimerStep() {
        val controller = twoTimerController()
        controller.start(0L)
        val oldKey = controller.currentTimerStepKey()!!
        val oldRun = TimerRunId("run-old")
        controller.installActiveTimerForTesting(oldRun, oldKey)
        assertEquals(
            ExecutionControllerError.INVALID_TIMER_STATE,
            (controller.skipCurrentRound(1L) as StepActionResult.Failure).error
        )
        controller.installActiveTimerForTesting(
            controller.timerEngineForTesting().debugState().runId!!,
            oldKey
        )
        controller.skipCurrentRound(1L)

        val newKey = controller.currentTimerStepKey()!!
        assertNotEquals(oldKey, newKey)
        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = oldKey.sessionId,
            routineIteration = oldKey.routineIteration,
            stepId = oldKey.stepId,
            stepIteration = oldKey.stepIteration,
            timerRunId = oldRun
        ))

        val newRun = TimerRunId("run-new")
        controller.installActiveTimerForTesting(newRun, newKey)
        assertFalse(controller.isActiveTimerIdentityValid(
            sessionId = oldKey.sessionId,
            routineIteration = oldKey.routineIteration,
            stepId = oldKey.stepId,
            stepIteration = oldKey.stepIteration,
            timerRunId = oldRun
        ))
        assertTrue(controller.isActiveTimerIdentityValid(
            sessionId = newKey.sessionId,
            routineIteration = newKey.routineIteration,
            stepId = newKey.stepId,
            stepIteration = newKey.stepIteration,
            timerRunId = newRun
        ))
    }

    @Test
    fun controllerStartAutoStartsEngine() {
        val controller = timerController()
        controller.start(0L)

        val snapshot = (
            controller.timerEngineForTesting().snapshot() as TimerOperationResult.Success
            ).snapshot
        assertEquals(TimerRunStatus.RUNNING, snapshot.status)
        assertTrue(controller.activeTimerRunId != null)
    }

    private fun timerController(
        wall: FakeExecutionClock = FakeExecutionClock(1_000L),
        monotonic: FakeMonotonicClock = FakeMonotonicClock(1_000L)
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

    private fun countController(): ExecutionController =
        (ExecutionController.create(
            routine = countRoutine(),
            sessionId = "session-count",
            clock = FakeExecutionClock(1_000L)
        ) as ExecutionControllerCreateResult.Success).controller

    private fun twoTimerController(): ExecutionController =
        (ExecutionController.create(
            routine = Routine(
                id = "routine-two",
                name = "Two timers",
                repeatCount = 1,
                steps = listOf(timerStep("timer-1", "First"), timerStep("timer-2", "Second")),
                createdAt = 1L,
                updatedAt = 1L
            ),
            sessionId = "session-two",
            clock = FakeExecutionClock(1_000L),
            monotonicClock = ExecutionFakeMonotonicClock()
        ) as ExecutionControllerCreateResult.Success).controller

    private fun timerRoutine(): Routine = Routine(
        id = "routine-1",
        name = "Timer",
        repeatCount = 1,
        steps = listOf(timerStep("timer", "Plank")),
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
                countMode = com.flow.app.domain.model.CountMode.INCREMENT
            )
        ),
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun timerStep(id: String, name: String): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.TIMER,
        durationMs = 30_000L,
        category = TimerCategory.NORMAL
    )

    private class FakeExecutionClock(var current: Long) : ExecutionClock {
        override fun now(): Long = current
    }

    private class FakeMonotonicClock(var current: Long) : com.flow.app.domain.execution.timer.MonotonicClock {
        override fun nowMs(): Long = current
    }
}
