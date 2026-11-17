package com.flow.app.domain.execution

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutionSessionTest {

    @Test
    fun createsReadySessionForNonEmptyRoutine() {
        val routine = routine(
            steps = listOf(
                countStep("count-1", "Push-ups", 12),
                timerStep("timer-1", "Plank", 30_000L)
            )
        )

        val session = ExecutionSession.create(
            routine = routine,
            sessionId = "session-1"
        )

        assertEquals("session-1", session.sessionId)
        assertEquals("routine-1", session.routineId)
        assertEquals(ExecutionStatus.READY, session.status)
        assertEquals(1, session.progress.routineIteration)
        assertEquals(0, session.progress.currentStepIndex)
        assertEquals("count-1", session.progress.currentStepId)
        assertEquals(1, session.progress.stepIteration)
        assertEquals(0, session.progress.currentCount)
        assertEquals(false, session.progress.completeConfirmed)
        assertEquals(0L, session.timerState.totalDurationMs)
        assertEquals(0L, session.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, session.timerState.timerStatus)
        assertEquals(emptyList<ExecutionStepRecord>(), session.records)
        assertEquals(0, session.completedCount)
        assertEquals(0, session.skippedCount)
    }

    @Test
    fun timerFirstStepStartsWithItsDuration() {
        val session = ExecutionSession.create(
            routine = routine(steps = listOf(timerStep("timer-1", "Plank", 30_000L)))
        )

        assertEquals(30_000L, session.timerState.totalDurationMs)
        assertEquals(30_000L, session.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, session.timerState.timerStatus)
    }

    @Test
    fun rejectsEmptyRoutine() {
        assertValidation(ExecutionValidationError.EMPTY_STEPS) {
            ExecutionSession.create(routine(steps = emptyList()))
        }
    }

    @Test
    fun rejectsInvalidRoutineAndStepRepeatCounts() {
        assertValidation(ExecutionValidationError.INVALID_ROUTINE_REPEAT) {
            ExecutionSession.create(
                routine(
                    steps = listOf(countStep("a", "A", 1)),
                    repeatCount = 0
                )
            )
        }

        assertValidation(ExecutionValidationError.INVALID_STEP_REPEAT) {
            ExecutionSession.create(
                routine(
                    steps = listOf(countStep("a", "A", 1, repeatCount = 0))
                )
            )
        }
    }

    @Test
    fun snapshotIsIndependentFromOriginalRoutine() {
        val sourceSteps = mutableListOf(countStep("a", "A", 1))
        val original = routine(steps = sourceSteps)
        val session = ExecutionSession.create(original)

        sourceSteps.add(timerStep("b", "B", 10_000L))

        assertEquals(1, original.steps.size)
        assertEquals(1, session.routineSnapshot.steps.size)
        assertNotSame(original, session.routineSnapshot)
        assertEquals("a", session.routineSnapshot.steps.single().id)
    }

    @Test
    fun validatesProgressFields() {
        val base = validSession()
        val step = base.routineSnapshot.steps.single()

        assertValidation(ExecutionValidationError.INVALID_ROUTINE_ITERATION) {
            invalidSession(base, progress = base.progress.copy(routineIteration = 0))
        }
        assertValidation(ExecutionValidationError.INVALID_STEP_INDEX) {
            invalidSession(base, progress = base.progress.copy(currentStepIndex = 5))
        }
        assertValidation(ExecutionValidationError.STEP_ID_MISMATCH) {
            invalidSession(base, progress = base.progress.copy(currentStepId = "other"))
        }
        assertValidation(ExecutionValidationError.INVALID_STEP_ITERATION) {
            invalidSession(base, progress = base.progress.copy(stepIteration = 0))
        }
        assertValidation(ExecutionValidationError.INVALID_COUNT) {
            invalidSession(base, progress = base.progress.copy(currentCount = -1))
        }
        assertValidation(ExecutionValidationError.COUNT_EXCEEDS_TARGET) {
            invalidSession(
                base,
                progress = base.progress.copy(
                    currentCount = (step.target ?: 0) + 1
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_COMPLETE_CONFIRMED) {
            invalidSession(
                base,
                progress = base.progress.copy(completeConfirmed = true)
            )
        }
    }

    @Test
    fun validatesTimerProgress() {
        val base = ExecutionSession.create(
            routine = routine(steps = listOf(timerStep("timer", "Plank", 30_000L)))
        )

        assertValidation(ExecutionValidationError.INVALID_COUNT) {
            invalidSession(base, progress = base.progress.copy(currentCount = 1))
        }
        assertValidation(ExecutionValidationError.INVALID_COUNT) {
            invalidSession(base, progress = base.progress.copy(completeConfirmed = true))
        }
    }

    @Test
    fun validatesTimerState() {
        val base = validTimerSession()

        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            invalidSession(
                base,
                timerState = base.timerState.copy(remainingDurationMs = 40_000L)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            invalidSession(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.RUNNING,
                    deadline = null
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            invalidSession(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.PAUSED,
                    deadline = 5_000L
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            invalidSession(
                base,
                timerState = base.timerState.copy(
                    timerStatus = TimerStatus.COMPLETED,
                    remainingDurationMs = 1L
                )
            )
        }
        assertValidation(ExecutionValidationError.INVALID_TIMER_STATE) {
            invalidSession(
                validSession(),
                timerState = TimerState(1_000L, 1_000L, null, TimerStatus.IDLE)
            )
        }
    }

    @Test
    fun createsCompletedAndSkippedRecordsAndDerivesCounts() {
        val session = ExecutionSession.create(
            routine(
                steps = listOf(
                    countStep("a", "A", 2),
                    timerStep("b", "B", 30_000L)
                )
            )
        ).start(1_000L)
        val completed = record("a", 1, 1, ExecutionStepOutcome.COMPLETED)
        val skipped = record("b", 1, 1, ExecutionStepOutcome.SKIPPED)

        val withCompleted = session.appendRecord(completed)
        val withBoth = withCompleted.appendRecord(skipped)

        assertEquals(2, withBoth.records.size)
        assertEquals(1, withBoth.completedCount)
        assertEquals(1, withBoth.skippedCount)
        assertEquals(ExecutionStatus.RUNNING, withBoth.status)
    }

    @Test
    fun rejectsDuplicateAndConflictingRecords() {
        val session = startSession().appendRecord(
            record("a", 1, 1, ExecutionStepOutcome.COMPLETED)
        )

        assertValidation(ExecutionValidationError.DUPLICATE_RECORD) {
            session.appendRecord(record("a", 1, 1, ExecutionStepOutcome.SKIPPED))
        }
        assertValidation(ExecutionValidationError.DUPLICATE_RECORD) {
            session.appendRecord(record("a", 1, 1, ExecutionStepOutcome.COMPLETED))
        }
    }

    @Test
    fun rejectsInvalidRecordValues() {
        val session = startSession()

        assertValidation(ExecutionValidationError.INVALID_RECORD) {
            session.appendRecord(
                record("missing", 1, 1, ExecutionStepOutcome.COMPLETED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_RECORD) {
            session.appendRecord(
                record("a", 0, 1, ExecutionStepOutcome.COMPLETED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_RECORD) {
            session.appendRecord(
                record("a", 1, 0, ExecutionStepOutcome.COMPLETED)
            )
        }
        assertValidation(ExecutionValidationError.INVALID_RECORD) {
            session.appendRecord(
                record("a", 1, 1, ExecutionStepOutcome.COMPLETED, endedAt = 5L)
            )
        }
    }

    @Test
    fun completedSessionRequiresFullExecutionResults() {
        val session = startSession()

        assertValidation(ExecutionValidationError.INCOMPLETE_RESULTS) {
            session.complete(2_000L)
        }

        val full = session.appendRecord(
            record("a", 1, 1, ExecutionStepOutcome.COMPLETED)
        )
        val completed = full.complete(2_000L)

        assertEquals(ExecutionStatus.COMPLETED, completed.status)
        assertEquals(2_000L, completed.completedAt)
        assertEquals(1, completed.completedCount)
        assertValidation(ExecutionValidationError.TERMINAL_SESSION) {
            completed.appendRecord(record("a", 1, 1, ExecutionStepOutcome.COMPLETED))
        }
    }

    @Test
    fun completedSessionRequiresEveryRoutineAndStepIteration() {
        val session = ExecutionSession.create(
            routine(
                steps = listOf(countStep("a", "A", 1, repeatCount = 2)),
                repeatCount = 2
            )
        ).start(1_000L)

        val partial = session
            .appendRecord(record("a", 1, 1, ExecutionStepOutcome.COMPLETED))
            .appendRecord(record("a", 1, 2, ExecutionStepOutcome.SKIPPED))
            .appendRecord(record("a", 2, 1, ExecutionStepOutcome.COMPLETED))

        assertValidation(ExecutionValidationError.INCOMPLETE_RESULTS) {
            partial.complete(2_000L)
        }

        val completed = partial
            .appendRecord(record("a", 2, 2, ExecutionStepOutcome.COMPLETED))
            .complete(2_000L)

        assertEquals(4, completed.records.size)
        assertEquals(3, completed.completedCount)
        assertEquals(1, completed.skippedCount)
        assertEquals(ExecutionStatus.COMPLETED, completed.status)
    }

    @Test
    fun cancelledSessionRetainsRecordsAndRejectsFurtherResults() {
        val session = startSession().appendRecord(
            record("a", 1, 1, ExecutionStepOutcome.SKIPPED)
        )
        val cancelled = session.cancel(2_000L)

        assertEquals(ExecutionStatus.CANCELLED, cancelled.status)
        assertEquals(2_000L, cancelled.cancelledAt)
        assertEquals(1, cancelled.records.size)
        assertEquals(1, cancelled.skippedCount)
        assertValidation(ExecutionValidationError.TERMINAL_SESSION) {
            cancelled.appendRecord(record("a", 1, 2, ExecutionStepOutcome.COMPLETED))
        }
        assertValidation(ExecutionValidationError.TERMINAL_SESSION) {
            cancelled.complete(3_000L)
        }
    }

    @Test
    fun pausedSessionCanResumeButNotAppendAfterCompletion() {
        val paused = startSession().pause()
        assertEquals(ExecutionStatus.PAUSED, paused.status)

        val resumed = paused.resume()
        assertEquals(ExecutionStatus.RUNNING, resumed.status)
    }

    @Test
    fun validatesTimestamps() {
        val base = validSession()
        assertValidation(ExecutionValidationError.INVALID_TIMESTAMP) {
            invalidSession(base, startedAt = 100L)
        }
        assertValidation(ExecutionValidationError.INVALID_TIMESTAMP) {
            ExecutionSession(
                sessionId = base.sessionId,
                routineSnapshot = base.routineSnapshot,
                status = ExecutionStatus.COMPLETED,
                progress = base.progress,
                timerState = base.timerState,
                records = emptyList(),
                startedAt = 200L,
                completedAt = 100L
            )
        }
    }

    private fun startSession(): ExecutionSession =
        ExecutionSession.create(routine(steps = listOf(countStep("a", "A", 2))))
            .start(1_000L)

    private fun validSession(): ExecutionSession =
        ExecutionSession.create(routine(steps = listOf(countStep("a", "A", 2))))

    private fun validTimerSession(): ExecutionSession =
        ExecutionSession.create(
            routine = routine(steps = listOf(timerStep("timer", "Plank", 30_000L)))
        )

    private fun invalidSession(
        base: ExecutionSession,
        status: ExecutionStatus = base.status,
        progress: ExecutionProgress = base.progress,
        timerState: TimerState = base.timerState,
        records: List<ExecutionStepRecord> = base.records,
        startedAt: Long? = base.startedAt,
        completedAt: Long? = base.completedAt,
        cancelledAt: Long? = base.cancelledAt
    ): ExecutionSession = ExecutionSession(
        sessionId = base.sessionId,
        routineSnapshot = base.routineSnapshot,
        status = status,
        progress = progress,
        timerState = timerState,
        records = records,
        startedAt = startedAt,
        completedAt = completedAt,
        cancelledAt = cancelledAt
    )

    private fun assertValidation(
        expected: ExecutionValidationError,
        block: () -> Unit
    ) {
        val error = assertThrows(ExecutionValidationException::class.java, block)
        assertEquals(expected, error.code)
    }

    private fun record(
        stepId: String,
        routineIteration: Int,
        stepIteration: Int,
        outcome: ExecutionStepOutcome,
        startedAt: Long = 1_000L,
        endedAt: Long = 1_500L
    ): ExecutionStepRecord = ExecutionStepRecord(
        stepId = stepId,
        routineIteration = routineIteration,
        stepIteration = stepIteration,
        outcome = outcome,
        startedAt = startedAt,
        endedAt = endedAt
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
        repeatCount: Int = 1
    ): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.COUNT,
        target = target,
        countMode = CountMode.INCREMENT,
        repeatCount = repeatCount
    )

    private fun timerStep(
        id: String,
        name: String,
        durationMs: Long,
        repeatCount: Int = 1
    ): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.TIMER,
        durationMs = durationMs,
        category = TimerCategory.NORMAL,
        repeatCount = repeatCount
    )
}
