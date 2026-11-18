package com.flow.app.domain.execution

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutionSchedulingTest {

    @Test
    fun schedulePlanRequiresCompletenessInvariant() {
        val record = record("a", 1, 1)
        val progress = progressForIndex(0, "a", 1)

        SchedulePlan(ScheduleKind.COMPLETE, record, null)
        SchedulePlan(ScheduleKind.NEXT_STEP_ROUND, record, progress)

        assertThrows(IllegalArgumentException::class.java) {
            SchedulePlan(ScheduleKind.COMPLETE, record, progress)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SchedulePlan(ScheduleKind.NEXT_STEP_ROUND, record, null)
        }
    }

    @Test
    fun emptyRecordsStartsAtFirstCanonicalRound() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 3))
        )

        val plan = planNext(session)

        assertEquals(ScheduleKind.NEXT_STEP_ROUND, plan.kind)
        assertEquals("a", plan.record.stepId)
        assertEquals(1, plan.record.routineIteration)
        assertEquals(1, plan.record.stepIteration)
        assertEquals("a", plan.nextProgress?.currentStepId)
        assertEquals(2, plan.nextProgress?.stepIteration)
        assertEquals(0, plan.nextProgress?.currentCount)
        assertEquals(false, plan.nextProgress?.completeConfirmed)
    }

    @Test
    fun completedRoundProducesCompletedRecord() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 3))
        )
        val result = result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)

        val plan = planNext(session, result)
        val committed = session.commitStepResult(result)

        assertEquals(ExecutionStepOutcome.COMPLETED, plan.record.outcome)
        assertEquals(1, committed.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, committed.records.single().outcome)
        assertEquals(1, committed.completedCount)
        assertEquals(0, committed.skippedCount)
        assertEquals(ExecutionStatus.RUNNING, committed.status)
        assertEquals(2, committed.progress.stepIteration)
    }

    @Test
    fun skippedRoundProducesSkippedRecordAndNotCompletedCount() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 3))
        )
        val result = result(ExecutionStepOutcome.SKIPPED, 1_000L, 1_500L)

        val committed = session.commitStepResult(result)

        assertEquals(ExecutionStepOutcome.SKIPPED, committed.records.single().outcome)
        assertEquals(0, committed.completedCount)
        assertEquals(1, committed.skippedCount)
        assertEquals(2, committed.progress.stepIteration)
    }

    @Test
    fun advancesToNextRoundOfSameStep() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 3))
        )

        val plan = planNext(session)

        assertEquals(ScheduleKind.NEXT_STEP_ROUND, plan.kind)
        assertEquals("a", plan.nextProgress?.currentStepId)
        assertEquals(2, plan.nextProgress?.stepIteration)
    }

    @Test
    fun advancesToNextStep() {
        val session = startedSession(
            steps = listOf(
                countStep("a", "A", 1),
                countStep("b", "B", 1)
            )
        )

        val plan = planNext(session)

        assertEquals(ScheduleKind.NEXT_STEP, plan.kind)
        assertEquals(1, plan.nextProgress?.currentStepIndex)
        assertEquals("b", plan.nextProgress?.currentStepId)
        assertEquals(1, plan.nextProgress?.stepIteration)
        assertEquals(0, plan.nextProgress?.currentCount)
        assertEquals(false, plan.nextProgress?.completeConfirmed)
    }

    @Test
    fun advancesToNextRoutine() {
        val session = runningSession(
            steps = listOf(
                countStep("a", "A", 1),
                countStep("b", "B", 1)
            ),
            repeatCount = 2,
            records = listOf(record("a", 1, 1)),
            progress = progressForIndex(1, "b", 1)
        )

        val plan = planNext(session)

        assertEquals(ScheduleKind.NEXT_ROUTINE, plan.kind)
        assertEquals(2, plan.nextProgress?.routineIteration)
        assertEquals(0, plan.nextProgress?.currentStepIndex)
        assertEquals("a", plan.nextProgress?.currentStepId)
        assertEquals(1, plan.nextProgress?.stepIteration)
        assertEquals(0, plan.nextProgress?.currentCount)
        assertEquals(false, plan.nextProgress?.completeConfirmed)
    }

    @Test
    fun finalRecordCommitsCompletedStatusAndTimestamp() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 1))
        )
        val result = result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)

        val plan = planNext(session, result)
        val committed = session.commitStepResult(result)

        assertEquals(ScheduleKind.COMPLETE, plan.kind)
        assertEquals(null, plan.nextProgress)
        assertEquals(ExecutionStatus.COMPLETED, committed.status)
        assertEquals(1_500L, committed.completedAt)
        assertEquals(1, committed.records.size)
        assertEquals("a", committed.progress.currentStepId)
        assertEquals(1, committed.progress.stepIteration)
        assertEquals(1, committed.completedCount)
    }

    @Test
    fun rejectsMissingMiddleRound() {
        val session = runningSession(
            steps = listOf(countStep("a", "A", 3)),
            records = listOf(
                record("a", 1, 1),
                record("a", 1, 3)
            ),
            progress = progressForIndex(0, "a", 2)
        )

        assertSchedulingError(SchedulingError.RECORDS_NOT_PREFIX) {
            planNext(session)
        }
    }

    @Test
    fun rejectsReversedRecordOrder() {
        val session = runningSession(
            steps = listOf(countStep("a", "A", 3)),
            records = listOf(
                record("a", 1, 2),
                record("a", 1, 1)
            ),
            progress = progressForIndex(0, "a", 3)
        )

        assertSchedulingError(SchedulingError.RECORDS_NOT_PREFIX) {
            planNext(session)
        }
    }

    @Test
    fun rejectsPrematureNextStepRecord() {
        val session = runningSession(
            steps = listOf(
                countStep("a", "A", 3),
                countStep("b", "B", 2)
            ),
            records = listOf(
                record("a", 1, 1),
                record("b", 1, 1)
            ),
            progress = progressForIndex(0, "a", 2)
        )

        assertSchedulingError(SchedulingError.RECORDS_NOT_PREFIX) {
            planNext(session)
        }
    }

    @Test
    fun rejectsSchedulingWithCompleteRecords() {
        val session = runningSession(
            steps = listOf(countStep("a", "A", 1)),
            records = listOf(record("a", 1, 1)),
            progress = progressForIndex(0, "a", 1)
        )

        assertSchedulingError(SchedulingError.NO_REMAINING_ITERATIONS) {
            planNext(session)
        }
        assertSchedulingError(SchedulingError.NO_REMAINING_ITERATIONS) {
            session.commitStepResult(result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L))
        }
    }

    @Test
    fun rejectsProgressThatDoesNotMatchRecords() {
        val session = runningSession(
            steps = listOf(countStep("a", "A", 3)),
            records = listOf(record("a", 1, 1)),
            progress = progressForIndex(0, "a", 3)
        )

        assertSchedulingError(SchedulingError.PROGRESS_MISMATCH) {
            planNext(session)
        }
    }

    @Test
    fun rejectsPausedSession() {
        val paused = startedSession(
            steps = listOf(countStep("a", "A", 1))
        ).pause()

        assertSchedulingError(SchedulingError.SESSION_NOT_RUNNING) {
            planNext(paused)
        }
        assertSchedulingError(SchedulingError.SESSION_NOT_RUNNING) {
            paused.commitStepResult(result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L))
        }
    }

    @Test
    fun pausedSessionMustAlsoHaveOrderedProgress() {
        val paused = ExecutionSession(
            sessionId = "session-1",
            routineSnapshot = routine(steps = listOf(countStep("a", "A", 3))),
            status = ExecutionStatus.PAUSED,
            progress = progressForIndex(0, "a", 3),
            timerState = TimerState(0L, 0L, null, TimerStatus.IDLE),
            records = listOf(record("a", 1, 1)),
            startedAt = 1_000L
        )

        assertSchedulingError(SchedulingError.PROGRESS_MISMATCH) {
            planNext(paused)
        }
    }

    @Test
    fun rejectsReadySession() {
        val ready = ExecutionSession.create(
            routine(steps = listOf(countStep("a", "A", 1)))
        )

        assertSchedulingError(SchedulingError.SESSION_NOT_RUNNING) {
            planNext(ready)
        }
        assertSchedulingError(SchedulingError.SESSION_NOT_RUNNING) {
            ready.commitStepResult(result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L))
        }
    }

    @Test
    fun rejectsDuplicateCurrentRoundResult() {
        val session = runningSession(
            steps = listOf(countStep("a", "A", 3)),
            records = listOf(record("a", 1, 1)),
            progress = progressForIndex(0, "a", 1)
        )

        assertSchedulingError(SchedulingError.CURRENT_ROUND_ALREADY_RECORDED) {
            planNext(session)
        }
        assertSchedulingError(SchedulingError.CURRENT_ROUND_ALREADY_RECORDED) {
            session.commitStepResult(result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L))
        }
    }

    @Test
    fun rejectsStartedAtBeforeSessionStart() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 1))
        )

        assertSchedulingError(SchedulingError.INVALID_RESULT_TIMESTAMP) {
            planNext(session, result(ExecutionStepOutcome.COMPLETED, 999L, 1_500L))
        }
    }

    @Test
    fun rejectsEndedAtBeforeStartedAt() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 1))
        )

        assertSchedulingError(SchedulingError.INVALID_RESULT_TIMESTAMP) {
            planNext(session, result(ExecutionStepOutcome.COMPLETED, 1_500L, 1_000L))
        }
    }

    @Test
    fun countToTimerTransitionInitializesNextTimerState() {
        val session = startedSession(
            steps = listOf(
                countStep("a", "A", 1),
                timerStep("t", "T", 30_000L)
            )
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals("t", committed.progress.currentStepId)
        assertEquals(30_000L, committed.timerState.totalDurationMs)
        assertEquals(30_000L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
    }

    @Test
    fun timerToCountTransitionInitializesIdleTimer() {
        val session = startedSession(
            steps = listOf(
                timerStep("t", "T", 30_000L),
                countStep("a", "A", 1)
            )
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals("a", committed.progress.currentStepId)
        assertEquals(0L, committed.timerState.totalDurationMs)
        assertEquals(0L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
    }

    @Test
    fun finalSkippedRoundAlsoCompletesAndKeepsSkippedOutcome() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 1))
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.SKIPPED, 1_000L, 1_500L)
        )

        assertEquals(ExecutionStatus.COMPLETED, committed.status)
        assertEquals(ExecutionStepOutcome.SKIPPED, committed.records.single().outcome)
        assertEquals(0, committed.completedCount)
        assertEquals(1, committed.skippedCount)
        assertEquals(1_500L, committed.completedAt)
    }

    @Test
    fun timerStepsUseTheSameSchedulingRules() {
        val session = startedSession(
            steps = listOf(timerStep("t", "T", 30_000L))
        )

        val plan = planNext(session)
        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals(ScheduleKind.COMPLETE, plan.kind)
        assertEquals("t", plan.record.stepId)
        assertEquals(ExecutionStatus.COMPLETED, committed.status)
    }

    @Test
    fun countToCountResetsTimerState() {
        val session = startedSession(
            steps = listOf(
                countStep("a", "A", 1),
                countStep("b", "B", 1)
            )
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals("b", committed.progress.currentStepId)
        assertEquals(0L, committed.timerState.totalDurationMs)
        assertEquals(0L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
    }

    @Test
    fun timerToTimerUsesNextStepFullDuration() {
        val session = startedSession(
            steps = listOf(
                timerStep("t1", "T1", 20_000L),
                timerStep("t2", "T2", 30_000L)
            )
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals("t2", committed.progress.currentStepId)
        assertEquals(30_000L, committed.timerState.totalDurationMs)
        assertEquals(30_000L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
    }

    @Test
    fun sameTimerStepNextRoundResetsRemaining() {
        val session = startedSession(
            steps = listOf(timerStep("t", "T", 20_000L, repeatCount = 2))
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals("t", committed.progress.currentStepId)
        assertEquals(2, committed.progress.stepIteration)
        assertEquals(20_000L, committed.timerState.totalDurationMs)
        assertEquals(20_000L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
    }

    @Test
    fun skippedCountToTimerInitializesFullDuration() {
        val session = startedSession(
            steps = listOf(
                countStep("a", "A", 1),
                timerStep("t", "T", 30_000L)
            )
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.SKIPPED, 1_000L, 1_500L)
        )

        assertEquals("t", committed.progress.currentStepId)
        assertEquals(30_000L, committed.timerState.totalDurationMs)
        assertEquals(30_000L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
        assertEquals(1, committed.skippedCount)
    }

    @Test
    fun nextTimerDoesNotInheritRemainingDuration() {
        val session = runningSession(
            steps = listOf(
                timerStep("t1", "T1", 20_000L),
                timerStep("t2", "T2", 30_000L)
            ),
            records = emptyList(),
            progress = progressForIndex(0, "t1", 1),
            timerState = TimerState(
                totalDurationMs = 20_000L,
                remainingDurationMs = 5_000L,
                deadline = 5_000L,
                timerStatus = TimerStatus.RUNNING
            )
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals(30_000L, committed.timerState.totalDurationMs)
        assertEquals(30_000L, committed.timerState.remainingDurationMs)
        assertEquals(TimerStatus.IDLE, committed.timerState.timerStatus)
    }

    @Test
    fun finalTimerCompletionRetainsCurrentTimerState() {
        val runningTimer = TimerState(
            totalDurationMs = 30_000L,
            remainingDurationMs = 12_000L,
            deadline = 5_000L,
            timerStatus = TimerStatus.RUNNING
        )
        val session = runningSession(
            steps = listOf(timerStep("t", "T", 30_000L)),
            records = emptyList(),
            progress = progressForIndex(0, "t", 1),
            timerState = runningTimer
        )

        val committed = session.commitStepResult(
            result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
        )

        assertEquals(ExecutionStatus.COMPLETED, committed.status)
        assertEquals(runningTimer, committed.timerState)
        assertEquals(1_500L, committed.completedAt)
    }

    @Test
    fun failedCommitDoesNotChangeOriginalSession() {
        val session = startedSession(
            steps = listOf(countStep("a", "A", 1))
        )
        val beforeProgress = session.progress
        val beforeRecords = session.records
        val beforeStatus = session.status

        val error = assertThrows(ExecutionSchedulingException::class.java) {
            session.commitStepResult(
                result(ExecutionStepOutcome.COMPLETED, 999L, 1_500L)
            )
        }
        assertEquals(SchedulingError.INVALID_RESULT_TIMESTAMP, error.code)

        assertEquals(beforeProgress, session.progress)
        assertEquals(beforeRecords, session.records)
        assertEquals(beforeStatus, session.status)
        assertEquals(0, session.records.size)
    }

    private fun startedSession(
        steps: List<RoutineStep>,
        repeatCount: Int = 1
    ): ExecutionSession = ExecutionSession.create(
        routine(steps = steps, repeatCount = repeatCount)
    ).start(1_000L)

    private fun runningSession(
        steps: List<RoutineStep>,
        repeatCount: Int = 1,
        records: List<ExecutionStepRecord>,
        progress: ExecutionProgress,
        timerState: TimerState? = null
    ): ExecutionSession = ExecutionSession(
        sessionId = "session-1",
        routineSnapshot = routine(steps = steps, repeatCount = repeatCount),
        status = ExecutionStatus.RUNNING,
        progress = progress,
        timerState = timerState ?: timerStateFor(steps[progress.currentStepIndex]),
        records = records,
        startedAt = 1_000L
    )

    private fun progressForIndex(
        index: Int,
        stepId: String,
        stepIteration: Int,
        routineIteration: Int = 1
    ): ExecutionProgress = ExecutionProgress(
        routineIteration = routineIteration,
        currentStepIndex = index,
        currentStepId = stepId,
        stepIteration = stepIteration
    )

    private fun planNext(
        session: ExecutionSession,
        result: StepRoundResult = result(ExecutionStepOutcome.COMPLETED, 1_000L, 1_500L)
    ): SchedulePlan = ExecutionScheduler.planNext(session, result)

    private fun result(
        outcome: ExecutionStepOutcome,
        startedAt: Long,
        endedAt: Long
    ): StepRoundResult = StepRoundResult(
        outcome = outcome,
        startedAt = startedAt,
        endedAt = endedAt
    )

    private fun record(
        stepId: String,
        routineIteration: Int,
        stepIteration: Int,
        outcome: ExecutionStepOutcome = ExecutionStepOutcome.COMPLETED
    ): ExecutionStepRecord = ExecutionStepRecord(
        stepId = stepId,
        routineIteration = routineIteration,
        stepIteration = stepIteration,
        outcome = outcome,
        startedAt = 1_000L,
        endedAt = 1_500L
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
        repeatCount: Int
    ): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = StepType.COUNT,
        target = 1,
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

    private fun timerStateFor(step: RoutineStep): TimerState = when (step.type) {
        StepType.COUNT -> TimerState(0L, 0L, null, TimerStatus.IDLE)
        StepType.TIMER -> TimerState(
            totalDurationMs = step.durationMs ?: 0L,
            remainingDurationMs = step.durationMs ?: 0L,
            deadline = null,
            timerStatus = TimerStatus.IDLE
        )
    }

    private fun assertSchedulingError(
        expected: SchedulingError,
        block: () -> Unit
    ) {
        val error = assertThrows(ExecutionSchedulingException::class.java, block)
        assertEquals(expected, error.code)
    }
}
