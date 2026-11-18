package com.flow.app.domain.execution

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExecutionTimerStateTest {

    @Test
    fun startsIdleTimerAndPreservesEverythingElse() {
        val base = runningTimerSession()
        val updated = base.startTimer(5_000L)

        assertEquals(ExecutionStatus.RUNNING, updated.status)
        assertEquals(TimerStatus.RUNNING, updated.timerState.timerStatus)
        assertEquals(30_000L, updated.timerState.totalDurationMs)
        assertEquals(30_000L, updated.timerState.remainingDurationMs)
        assertEquals(5_000L, updated.timerState.deadline)
        assertOtherFieldsEqual(base, updated)
        assertEquals(TimerStatus.IDLE, base.timerState.timerStatus)
    }

    @Test
    fun pausesRunningTimerAndPreservesRunIdentityFields() {
        val base = runningTimerSession().startTimer(5_000L)
        val updated = base.pauseTimer(20_000L)

        assertEquals(ExecutionStatus.PAUSED, updated.status)
        assertEquals(TimerStatus.PAUSED, updated.timerState.timerStatus)
        assertEquals(20_000L, updated.timerState.remainingDurationMs)
        assertEquals(null, updated.timerState.deadline)
        assertOtherFieldsEqual(base, updated)
        assertEquals(TimerStatus.RUNNING, base.timerState.timerStatus)
    }

    @Test
    fun resumesPausedTimerWithNewDeadline() {
        val base = runningTimerSession().startTimer(5_000L).pauseTimer(20_000L)
        val updated = base.resumeTimer(8_000L)

        assertEquals(ExecutionStatus.RUNNING, updated.status)
        assertEquals(TimerStatus.RUNNING, updated.timerState.timerStatus)
        assertEquals(20_000L, updated.timerState.remainingDurationMs)
        assertEquals(8_000L, updated.timerState.deadline)
        assertOtherFieldsEqual(base, updated)
    }

    @Test
    fun completesRunningTimerWithoutAdvancingSession() {
        val base = runningTimerSession().startTimer(5_000L)
        val updated = base.completeTimer()

        assertEquals(ExecutionStatus.RUNNING, updated.status)
        assertEquals(TimerStatus.COMPLETED, updated.timerState.timerStatus)
        assertEquals(0L, updated.timerState.remainingDurationMs)
        assertEquals(null, updated.timerState.deadline)
        assertOtherFieldsEqual(base, updated)
    }

    @Test
    fun startTimerRejectsCountStep() {
        val count = countRunningSession()

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            count.startTimer(5_000L)
        }
    }

    @Test
    fun startTimerRejectsReadyAndTerminalSession() {
        val ready = readyTimerSession()
        assertValidation(ExecutionValidationError.INVALID_STATUS) {
            ready.startTimer(5_000L)
        }

        val completed = completedTimerSession()
        assertValidation(ExecutionValidationError.INVALID_STATUS) {
            completed.startTimer(5_000L)
        }
    }

    @Test
    fun pauseTimerRejectsIdleZeroAndOversizedRemaining() {
        val runningIdle = runningTimerSession()
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            runningIdle.pauseTimer(10_000L)
        }

        val started = runningIdle.startTimer(5_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            started.pauseTimer(0L)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            started.pauseTimer(-1L)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            started.pauseTimer(30_001L)
        }
    }

    @Test
    fun resumeTimerRejectsNonPausedStateAndInvalidDeadline() {
        val running = runningTimerSession().startTimer(5_000L)
        assertValidation(ExecutionValidationError.INVALID_STATUS) {
            running.resumeTimer(8_000L)
        }

        val paused = running.pauseTimer(20_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            paused.resumeTimer(0L)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            paused.resumeTimer(-1L)
        }
    }

    @Test
    fun completeTimerRejectsNonRunningState() {
        val paused = runningTimerSession().startTimer(5_000L).pauseTimer(20_000L)
        assertValidation(ExecutionValidationError.INVALID_STATUS) {
            paused.completeTimer()
        }
    }

    @Test
    fun existingPauseDoesNotPretendToPauseRunningTimer() {
        val running = runningTimerSession().startTimer(5_000L)

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            running.pause()
        }

        assertEquals(ExecutionStatus.RUNNING, running.status)
        assertEquals(TimerStatus.RUNNING, running.timerState.timerStatus)
    }

    @Test
    fun existingResumeDoesNotPretendToResumePausedTimer() {
        val paused = runningTimerSession().startTimer(5_000L).pauseTimer(20_000L)

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            paused.resume()
        }

        assertEquals(ExecutionStatus.PAUSED, paused.status)
        assertEquals(TimerStatus.PAUSED, paused.timerState.timerStatus)
        assertEquals(20_000L, paused.timerState.remainingDurationMs)
    }

    @Test
    fun genericResumeStillWorksForPausedCountSession() {
        val count = countRunningSession()
        val paused = count.pause()
        val resumed = paused.resume()

        assertEquals(ExecutionStatus.RUNNING, resumed.status)
        assertEquals(TimerStatus.IDLE, resumed.timerState.timerStatus)
    }

    @Test
    fun oldCancelRejectsRunningAndPausedTimer() {
        val running = runningTimerSession().startTimer(5_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            running.cancel(2_000L)
        }
        assertEquals(ExecutionStatus.RUNNING, running.status)

        val paused = running.pauseTimer(20_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            paused.cancel(2_000L)
        }
        assertEquals(ExecutionStatus.PAUSED, paused.status)
    }

    @Test
    fun oldCancelStillWorksForIdleTimerAndCount() {
        val idleTimer = runningTimerSession()
        val cancelledTimer = idleTimer.cancel(2_000L)
        assertEquals(ExecutionStatus.CANCELLED, cancelledTimer.status)
        assertEquals(TimerStatus.IDLE, cancelledTimer.timerState.timerStatus)

        val count = countRunningSession()
        val cancelledCount = count.cancel(2_000L)
        assertEquals(ExecutionStatus.CANCELLED, cancelledCount.status)
        assertEquals(TimerStatus.IDLE, cancelledCount.timerState.timerStatus)
    }

    @Test
    fun cancelWithTimerStateCancelsRunningTimer() {
        val running = runningTimerSession().startTimer(5_000L)
        val cancelledTimer = TimerState(
            totalDurationMs = 30_000L,
            remainingDurationMs = 20_000L,
            deadline = null,
            timerStatus = TimerStatus.CANCELLED
        )
        val cancelled = running.cancelWithTimerState(2_000L, cancelledTimer)

        assertEquals(ExecutionStatus.CANCELLED, cancelled.status)
        assertEquals(TimerStatus.CANCELLED, cancelled.timerState.timerStatus)
        assertEquals(20_000L, cancelled.timerState.remainingDurationMs)
        assertEquals(null, cancelled.timerState.deadline)
        assertEquals(2_000L, cancelled.cancelledAt)
        assertEquals(null, cancelled.currentRoundStartedAt)
        assertEquals(running.records, cancelled.records)
        assertEquals(running.progress, cancelled.progress)
        assertEquals(running.routineSnapshot, cancelled.routineSnapshot)
        assertEquals(ExecutionStatus.RUNNING, running.status)
        assertEquals(TimerStatus.RUNNING, running.timerState.timerStatus)
    }

    @Test
    fun cancelWithTimerStateAcceptsPausedTimerAndPreservesCompletedTimer() {
        val paused = runningTimerSession().startTimer(5_000L).pauseTimer(20_000L)
        val pausedCancelled = paused.cancelWithTimerState(
            2_000L,
            TimerState(30_000L, 20_000L, null, TimerStatus.CANCELLED)
        )
        assertEquals(ExecutionStatus.CANCELLED, pausedCancelled.status)
        assertEquals(TimerStatus.CANCELLED, pausedCancelled.timerState.timerStatus)

        val completed = runningTimerSession().startTimer(5_000L).completeTimer()
        val completedCancelled = completed.cancelWithTimerState(2_000L, completed.timerState)
        assertEquals(ExecutionStatus.CANCELLED, completedCancelled.status)
        assertEquals(TimerStatus.COMPLETED, completedCancelled.timerState.timerStatus)

        val idle = runningTimerSession()
        val idleCancelled = idle.cancelWithTimerState(2_000L, idle.timerState)
        assertEquals(ExecutionStatus.CANCELLED, idleCancelled.status)
        assertEquals(TimerStatus.IDLE, idleCancelled.timerState.timerStatus)
    }

    @Test
    fun cancelWithTimerStateRejectsInvalidFinalStates() {
        val running = runningTimerSession().startTimer(5_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            running.cancelWithTimerState(
                2_000L,
                TimerState(30_000L, 20_000L, null, TimerStatus.PAUSED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            running.cancelWithTimerState(2_000L, running.timerState.copy(timerStatus = TimerStatus.IDLE, deadline = null, remainingDurationMs = 30_000L))
        }

        val paused = running.pauseTimer(20_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            paused.cancelWithTimerState(2_000L, paused.timerState)
        }

        val completed = running.completeTimer()
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            completed.cancelWithTimerState(
                2_000L,
                TimerState(30_000L, 20_000L, null, TimerStatus.CANCELLED)
            )
        }
    }

    @Test
    fun cancelledTimerStateIsValidAndCountCannotUseIt() {
        val started = runningTimerSession().startTimer(5_000L)
        val cancelled = TimerState(30_000L, 10_000L, null, TimerStatus.CANCELLED)
        val cancelledSession = started.cancelWithTimerState(2_000L, cancelled)
        assertEquals(TimerStatus.CANCELLED, cancelledSession.timerState.timerStatus)

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            started.cancelWithTimerState(
                2_000L,
                TimerState(30_000L, 10_000L, 5_000L, TimerStatus.CANCELLED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            started.cancelWithTimerState(
                2_000L,
                TimerState(30_000L, -1L, null, TimerStatus.CANCELLED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            started.cancelWithTimerState(
                2_000L,
                TimerState(30_000L, 30_001L, null, TimerStatus.CANCELLED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            countRunningSession().cancelWithTimerState(
                2_000L,
                TimerState(0L, 0L, null, TimerStatus.CANCELLED)
            )
        }
    }

    @Test
    fun statusTimerCombinationsFollowAllowlist() {
        val runningIdle = runningTimerSession()
        val running = runningIdle.startTimer(5_000L)
        val paused = running.pauseTimer(20_000L)
        val cancelledTimer = TimerState(30_000L, 20_000L, null, TimerStatus.CANCELLED)

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(running, timerState = paused.timerState)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(running, timerState = cancelledTimer)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(paused, timerState = running.timerState)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(paused, timerState = TimerState(30_000L, 0L, null, TimerStatus.COMPLETED))
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(paused, timerState = cancelledTimer)
        }

        assertEquals(ExecutionStatus.RUNNING, running.status)
        assertEquals(TimerStatus.RUNNING, running.timerState.timerStatus)
        assertEquals(ExecutionStatus.PAUSED, paused.status)
        assertEquals(TimerStatus.PAUSED, paused.timerState.timerStatus)
        assertEquals(
            ExecutionStatus.RUNNING,
            runningTimerSession().startTimer(5_000L).completeTimer().status
        )
        assertEquals(
            ExecutionStatus.RUNNING,
            runningIdle.status
        )
        assertEquals(TimerStatus.IDLE, runningIdle.timerState.timerStatus)
    }

    @Test
    fun cancelledSessionCannotBeCancelledAgain() {
        val running = runningTimerSession().startTimer(5_000L)
        val cancelled = running.cancelWithTimerState(
            2_000L,
            TimerState(30_000L, 20_000L, null, TimerStatus.CANCELLED)
        )

        assertValidation(ExecutionValidationError.TERMINAL_SESSION) {
            cancelled.cancel(3_000L)
        }
        assertValidation(ExecutionValidationError.TERMINAL_SESSION) {
            cancelled.cancelWithTimerState(3_000L, cancelled.timerState)
        }
    }

    @Test
    fun pausedSessionCannotCarryRunningTimer() {
        val running = runningTimerSession().startTimer(5_000L)

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(running, status = ExecutionStatus.PAUSED)
        }
    }

    @Test
    fun terminalSessionCannotCarryRunningTimer() {
        val running = runningTimerSession().startTimer(5_000L)
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                running = running,
                status = ExecutionStatus.COMPLETED,
                completedAt = 2_000L,
                currentRoundStartedAt = null
            )
        }

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                running = running,
                status = ExecutionStatus.CANCELLED,
                cancelledAt = 2_000L,
                currentRoundStartedAt = null
            )
        }
    }

    @Test
    fun readySessionCannotCarryRunningTimer() {
        val running = runningTimerSession().startTimer(5_000L)

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                running,
                status = ExecutionStatus.READY,
                startedAt = null,
                currentRoundStartedAt = null
            )
        }
    }

    @Test
    fun invalidTimerStatesRemainRejected() {
        val base = runningTimerSession()

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(running = base, timerState = base.timerState.copy(remainingDurationMs = 30_001L))
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(running = base, timerState = base.timerState.copy(remainingDurationMs = -1L))
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.PAUSED,
                    remainingDurationMs = 0L,
                    deadline = null
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.COMPLETED,
                    remainingDurationMs = 1L,
                    deadline = null
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.RUNNING,
                    remainingDurationMs = 30_000L,
                    deadline = null
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            rebuild(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.RUNNING,
                    remainingDurationMs = 30_000L,
                    deadline = 0L
                )
            )
        }
    }

    @Test
    fun failedUpdateLeavesOriginalSessionUntouched() {
        val base = runningTimerSession()
        val beforeTimer = base.timerState

        assertValidation(ExecutionValidationError.INVALID_STATUS) {
            base.resumeTimer(5_000L)
        }

        assertSame(beforeTimer, base.timerState)
        assertEquals(TimerStatus.IDLE, base.timerState.timerStatus)
        assertEquals(ExecutionStatus.RUNNING, base.status)
    }

    private fun assertOtherFieldsEqual(
        before: ExecutionSession,
        after: ExecutionSession
    ) {
        assertEquals(before.sessionId, after.sessionId)
        assertEquals(before.routineId, after.routineId)
        assertEquals(before.routineSnapshot, after.routineSnapshot)
        assertEquals(before.progress, after.progress)
        assertEquals(before.records, after.records)
        assertEquals(before.startedAt, after.startedAt)
        assertEquals(before.currentRoundStartedAt, after.currentRoundStartedAt)
        assertEquals(before.completedAt, after.completedAt)
        assertEquals(before.cancelledAt, after.cancelledAt)
    }

    private fun rebuild(
        running: ExecutionSession,
        status: ExecutionStatus = running.status,
        timerState: TimerState = running.timerState,
        startedAt: Long? = running.startedAt,
        completedAt: Long? = running.completedAt,
        cancelledAt: Long? = running.cancelledAt,
        currentRoundStartedAt: Long? = running.currentRoundStartedAt
    ): ExecutionSession = ExecutionSession(
        sessionId = running.sessionId,
        routineSnapshot = running.routineSnapshot,
        status = status,
        progress = running.progress,
        timerState = timerState,
        records = running.records,
        startedAt = startedAt,
        completedAt = completedAt,
        cancelledAt = cancelledAt,
        currentRoundStartedAt = currentRoundStartedAt
    )

    private fun assertValidation(
        expected: ExecutionValidationError,
        block: () -> Unit
    ) {
        try {
            block()
            throw AssertionError("Expected $expected")
        } catch (error: ExecutionValidationException) {
            assertEquals(expected, error.code)
        }
    }

    private fun readyTimerSession(): ExecutionSession =
        ExecutionSession.create(routine = timerRoutine())

    private fun runningTimerSession(): ExecutionSession =
        readyTimerSession().start(1_000L)

    private fun countRunningSession(): ExecutionSession =
        ExecutionSession.create(routine = countRoutine()).start(1_000L)

    private fun completedTimerSession(): ExecutionSession {
        val running = runningTimerSession().startTimer(5_000L).completeTimer()
        val record = ExecutionStepRecord(
            stepId = "timer",
            routineIteration = 1,
            stepIteration = 1,
            outcome = ExecutionStepOutcome.COMPLETED,
            startedAt = 1_000L,
            endedAt = 1_500L
        )
        return running.appendRecord(record).complete(2_000L)
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
        id = "routine-2",
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
}
