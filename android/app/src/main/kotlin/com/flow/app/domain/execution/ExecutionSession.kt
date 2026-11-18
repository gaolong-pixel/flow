package com.flow.app.domain.execution

import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.StepType
import java.util.Collections
import java.util.UUID

class ExecutionSession(
    val sessionId: String,
    val routineSnapshot: Routine,
    val status: ExecutionStatus,
    val progress: ExecutionProgress,
    val timerState: TimerState,
    records: List<ExecutionStepRecord>,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val currentRoundStartedAt: Long? = null
) {
    val routineId: String = routineSnapshot.id
    val records: List<ExecutionStepRecord> =
        Collections.unmodifiableList(ArrayList(records))

    val completedCount: Int
        get() = records.count { it.outcome == ExecutionStepOutcome.COMPLETED }

    val skippedCount: Int
        get() = records.count { it.outcome == ExecutionStepOutcome.SKIPPED }

    init {
        validate()
    }

    fun start(at: Long): ExecutionSession {
        requireStatus(ExecutionValidationError.INVALID_STATUS, ExecutionStatus.READY)
        return copy(
            status = ExecutionStatus.RUNNING,
            startedAt = at,
            currentRoundStartedAt = at
        )
    }

    fun pause(): ExecutionSession {
        requireStatus(ExecutionValidationError.INVALID_STATUS, ExecutionStatus.RUNNING)
        return copy(status = ExecutionStatus.PAUSED)
    }

    fun resume(): ExecutionSession {
        requireStatus(ExecutionValidationError.INVALID_STATUS, ExecutionStatus.PAUSED)
        return copy(status = ExecutionStatus.RUNNING)
    }

    /**
     * Atomically increments the current COUNT/INCREMENT round.
     *
     * Below target this returns a new Session with only currentCount changed.
     * At target it builds an internal target-count candidate and calls
     * [commitStepResult]; the intermediate target-count Session is never
     * returned. This method is only for the verified execution controller.
     */
    internal fun incrementCountAndMaybeComplete(
        completion: StepRoundResult
    ): ExecutionSession {
        rejectTerminal()
        requireStatus(ExecutionValidationError.INVALID_STATUS, ExecutionStatus.RUNNING)

        val step = routineSnapshot.steps[progress.currentStepIndex]
        if (step.type != StepType.COUNT ||
            step.countMode != CountMode.INCREMENT
        ) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_STATUS)
        }
        val target = step.target
            ?: throw ExecutionValidationException(ExecutionValidationError.INVALID_COUNT)
        if (target <= 0) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_COUNT)
        }
        val current = progress.currentCount
        if (current < 0 || current > target) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_COUNT)
        }
        if (current == target) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_COUNT)
        }

        val newCount = current + 1
        if (newCount < target) {
            return copy(progress = progress.copy(currentCount = newCount))
        }

        val targetSession = copy(progress = progress.copy(currentCount = target))
        return targetSession.commitStepResult(completion)
    }

    /**
     * Atomically confirms the current COUNT + COMPLETE round.
     *
     * The completeConfirmed candidate is internal and never returned directly;
     * only the final Session produced by [commitStepResult] is exposed.
     */
    internal fun confirmComplete(completion: StepRoundResult): ExecutionSession {
        rejectTerminal()
        requireStatus(ExecutionValidationError.INVALID_STATUS, ExecutionStatus.RUNNING)
        if (completion.outcome != ExecutionStepOutcome.COMPLETED) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_RECORD)
        }

        val step = routineSnapshot.steps[progress.currentStepIndex]
        if (step.type != StepType.COUNT || step.countMode != CountMode.COMPLETE) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_STATUS)
        }
        if (progress.completeConfirmed) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_COMPLETE_CONFIRMED)
        }

        val confirmed = copy(
            progress = progress.copy(completeConfirmed = true)
        )
        return confirmed.commitStepResult(completion)
    }

    /**
     * Atomically skips the current round for any step type.
     */
    internal fun skipCurrentRound(completion: StepRoundResult): ExecutionSession {
        rejectTerminal()
        requireStatus(ExecutionValidationError.INVALID_STATUS, ExecutionStatus.RUNNING)
        if (completion.outcome != ExecutionStepOutcome.SKIPPED) {
            throw ExecutionValidationException(ExecutionValidationError.INVALID_RECORD)
        }
        return commitStepResult(completion)
    }

    /**
     * Low-level state operation for tests and internal model composition.
     * It appends a record without advancing progress and is not a scheduling
     * entry point. Normal execution must use [commitStepResult].
     */
    fun appendRecord(record: ExecutionStepRecord): ExecutionSession {
        rejectTerminal()
        requireStatus(
            ExecutionValidationError.INVALID_STATUS,
            ExecutionStatus.RUNNING,
            ExecutionStatus.PAUSED
        )
        if (records.any { it.sameIteration(record) }) {
            throw ExecutionValidationException(ExecutionValidationError.DUPLICATE_RECORD)
        }
        return copy(records = records + record)
    }

    fun cancel(at: Long): ExecutionSession {
        rejectTerminal()
        return copy(
            status = ExecutionStatus.CANCELLED,
            cancelledAt = at,
            currentRoundStartedAt = null
        )
    }

    /**
     * Low-level terminal operation for tests and internal model composition.
     * Normal execution must transition through [commitStepResult].
     */
    fun complete(at: Long): ExecutionSession {
        rejectTerminal()
        requireStatus(
            ExecutionValidationError.INVALID_STATUS,
            ExecutionStatus.RUNNING,
            ExecutionStatus.PAUSED
        )
        return copy(
            status = ExecutionStatus.COMPLETED,
            completedAt = at,
            currentRoundStartedAt = null
        )
    }

    /**
     * Atomically commits the result for the current scheduled round.
     *
     * The result position is derived exclusively from this session's snapshot
     * and progress. The scheduler is recomputed here; no externally supplied
     * SchedulePlan is trusted. Records, progress, and a possible terminal
     * status are committed by one new validated ExecutionSession.
     *
     * This method does not validate COUNT target completion, COMPLETE
     * confirmation, or TIMER completion. Only a verified execution control
     * layer may call it; UI code must not construct StepRoundResult and submit
     * it directly.
     */
    fun commitStepResult(result: StepRoundResult): ExecutionSession {
        val plan = ExecutionScheduler.planNext(this, result)
        val nextStatus = if (plan.kind == ScheduleKind.COMPLETE) {
            ExecutionStatus.COMPLETED
        } else {
            ExecutionStatus.RUNNING
        }
        val nextProgress = plan.nextProgress ?: progress
        val nextTimerState = plan.nextProgress?.let { next ->
            initialTimerState(routineSnapshot.steps[next.currentStepIndex])
        } ?: timerState
        return ExecutionSession(
            sessionId = sessionId,
            routineSnapshot = routineSnapshot,
            status = nextStatus,
            progress = nextProgress,
            timerState = nextTimerState,
            records = records + plan.record,
            startedAt = startedAt,
            completedAt = if (plan.kind == ScheduleKind.COMPLETE) {
                plan.record.endedAt
            } else {
                null
            },
            cancelledAt = null,
            currentRoundStartedAt = if (plan.nextProgress != null) {
                plan.record.endedAt
            } else {
                null
            }
        )
    }

    private fun copy(
        status: ExecutionStatus = this.status,
        progress: ExecutionProgress = this.progress,
        timerState: TimerState = this.timerState,
        records: List<ExecutionStepRecord> = this.records,
        startedAt: Long? = this.startedAt,
        completedAt: Long? = this.completedAt,
        cancelledAt: Long? = this.cancelledAt,
        currentRoundStartedAt: Long? = this.currentRoundStartedAt
    ): ExecutionSession = ExecutionSession(
        sessionId = sessionId,
        routineSnapshot = routineSnapshot,
        status = status,
        progress = progress,
        timerState = timerState,
        records = records,
        startedAt = startedAt,
        completedAt = completedAt,
        cancelledAt = cancelledAt,
        currentRoundStartedAt = currentRoundStartedAt
    )

    private fun requireStatus(
        code: ExecutionValidationError,
        vararg allowed: ExecutionStatus
    ) {
        if (status !in allowed) {
            throw ExecutionValidationException(code)
        }
    }

    private fun rejectTerminal() {
        if (status == ExecutionStatus.COMPLETED || status == ExecutionStatus.CANCELLED) {
            throw ExecutionValidationException(ExecutionValidationError.TERMINAL_SESSION)
        }
    }

    private fun validate() {
        val steps = routineSnapshot.steps
        if (steps.isEmpty()) {
            fail(ExecutionValidationError.EMPTY_STEPS)
        }
        if (routineSnapshot.repeatCount < 1) {
            fail(ExecutionValidationError.INVALID_ROUTINE_REPEAT)
        }
        steps.forEach { step ->
            if (step.repeatCount < 1) {
                fail(ExecutionValidationError.INVALID_STEP_REPEAT)
            }
        }

        validateProgress(steps)
        validateTimerState(steps)
        validateRecords(steps)
        validateStatusAndTimestamps()
        validateCompletedResults()
    }

    private fun validateProgress(steps: List<RoutineStep>) {
        if (progress.routineIteration !in 1..routineSnapshot.repeatCount) {
            fail(ExecutionValidationError.INVALID_ROUTINE_ITERATION)
        }
        if (progress.currentStepIndex !in steps.indices) {
            fail(ExecutionValidationError.INVALID_STEP_INDEX)
        }

        val step = steps[progress.currentStepIndex]
        if (progress.currentStepId != step.id) {
            fail(ExecutionValidationError.STEP_ID_MISMATCH)
        }
        if (progress.stepIteration !in 1..step.repeatCount) {
            fail(ExecutionValidationError.INVALID_STEP_ITERATION)
        }

        when (step.type) {
            StepType.COUNT -> {
                val target = step.target
                    ?: fail(ExecutionValidationError.INVALID_COUNT)
                if (target <= 0) {
                    fail(ExecutionValidationError.INVALID_COUNT)
                }
                if (progress.currentCount !in 0..target) {
                    fail(
                        if (progress.currentCount > target) {
                            ExecutionValidationError.COUNT_EXCEEDS_TARGET
                        } else {
                            ExecutionValidationError.INVALID_COUNT
                        }
                    )
                }
                if (progress.completeConfirmed &&
                    step.countMode != com.flow.app.domain.model.CountMode.COMPLETE
                ) {
                    fail(ExecutionValidationError.INVALID_COMPLETE_CONFIRMED)
                }
            }

            StepType.TIMER -> {
                if (progress.currentCount != 0 || progress.completeConfirmed) {
                    fail(ExecutionValidationError.INVALID_COUNT)
                }
            }
        }
    }

    private fun validateTimerState(steps: List<RoutineStep>) {
        val step = steps[progress.currentStepIndex]
        val timer = timerState

        if (timer.totalDurationMs < 0L ||
            timer.remainingDurationMs < 0L ||
            timer.remainingDurationMs > timer.totalDurationMs
        ) {
            fail(ExecutionValidationError.INVALID_TIMER_STATE)
        }
        if (timer.deadline != null && timer.deadline < 0L) {
            fail(ExecutionValidationError.INVALID_TIMER_STATE)
        }

        if (status == ExecutionStatus.READY && timer.timerStatus != TimerStatus.IDLE) {
            fail(ExecutionValidationError.INVALID_TIMER_STATE)
        }

        when (timer.timerStatus) {
            TimerStatus.IDLE -> {
                if (timer.deadline != null ||
                    timer.remainingDurationMs != timer.totalDurationMs
                ) {
                    fail(ExecutionValidationError.INVALID_TIMER_STATE)
                }
            }

            TimerStatus.RUNNING -> {
                if (timer.deadline == null ||
                    timer.deadline <= 0L ||
                    timer.remainingDurationMs <= 0L
                ) {
                    fail(ExecutionValidationError.INVALID_TIMER_STATE)
                }
            }

            TimerStatus.PAUSED -> {
                if (timer.deadline != null || timer.remainingDurationMs <= 0L) {
                    fail(ExecutionValidationError.INVALID_TIMER_STATE)
                }
            }

            TimerStatus.COMPLETED -> {
                if (timer.deadline != null || timer.remainingDurationMs != 0L) {
                    fail(ExecutionValidationError.INVALID_TIMER_STATE)
                }
            }
        }

        when (step.type) {
            StepType.COUNT -> {
                if (timer.totalDurationMs != 0L ||
                    timer.remainingDurationMs != 0L ||
                    timer.deadline != null ||
                    timer.timerStatus != TimerStatus.IDLE
                ) {
                    fail(ExecutionValidationError.INVALID_TIMER_STATE)
                }
            }

            StepType.TIMER -> {
                if (timer.totalDurationMs != step.durationMs ||
                    timer.totalDurationMs <= 0L
                ) {
                    fail(ExecutionValidationError.INVALID_TIMER_STATE)
                }
            }
        }
    }

    private fun validateRecords(steps: List<RoutineStep>) {
        val stepsById = steps.associateBy { it.id }
        val seen = HashSet<StepIterationKey>()

        records.forEach { record ->
            val step = stepsById[record.stepId]
                ?: fail(ExecutionValidationError.INVALID_RECORD)
            if (record.routineIteration !in 1..routineSnapshot.repeatCount ||
                record.stepIteration !in 1..step.repeatCount
            ) {
                fail(ExecutionValidationError.INVALID_RECORD)
            }
            if (record.startedAt < 0L || record.endedAt < record.startedAt) {
                fail(ExecutionValidationError.INVALID_RECORD)
            }
            if (!seen.add(record.key())) {
                fail(ExecutionValidationError.DUPLICATE_RECORD)
            }
        }

        if (status == ExecutionStatus.READY && records.isNotEmpty()) {
            fail(ExecutionValidationError.INVALID_STATUS)
        }
    }

    private fun validateStatusAndTimestamps() {
        if (startedAt != null && startedAt < 0L) {
            fail(ExecutionValidationError.INVALID_TIMESTAMP)
        }
        if (completedAt != null && completedAt < 0L) {
            fail(ExecutionValidationError.INVALID_TIMESTAMP)
        }
        if (cancelledAt != null && cancelledAt < 0L) {
            fail(ExecutionValidationError.INVALID_TIMESTAMP)
        }
        if (currentRoundStartedAt != null && currentRoundStartedAt < 0L) {
            fail(ExecutionValidationError.INVALID_TIMESTAMP)
        }

        when (status) {
            ExecutionStatus.READY -> {
                if (startedAt != null || completedAt != null || cancelledAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
                if (currentRoundStartedAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
            }

            ExecutionStatus.RUNNING, ExecutionStatus.PAUSED -> {
                if (startedAt == null || completedAt != null || cancelledAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
                if (currentRoundStartedAt == null ||
                    currentRoundStartedAt < startedAt
                ) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
            }

            ExecutionStatus.COMPLETED -> {
                if (startedAt == null || completedAt == null || cancelledAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
                if (completedAt < startedAt) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
                if (currentRoundStartedAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
            }

            ExecutionStatus.CANCELLED -> {
                if (cancelledAt == null || completedAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
                if (startedAt != null && cancelledAt < startedAt) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
                if (currentRoundStartedAt != null) {
                    fail(ExecutionValidationError.INVALID_TIMESTAMP)
                }
            }
        }
    }

    private fun validateCompletedResults() {
        if (status != ExecutionStatus.COMPLETED) return
        val expected = routineSnapshot.repeatCount.toLong() *
            routineSnapshot.steps.sumOf { it.repeatCount.toLong() }
        if (records.size.toLong() != expected) {
            fail(ExecutionValidationError.INCOMPLETE_RESULTS)
        }
    }

    private fun fail(code: ExecutionValidationError): Nothing =
        throw ExecutionValidationException(code)

    private data class StepIterationKey(
        val stepId: String,
        val routineIteration: Int,
        val stepIteration: Int
    )

    private fun ExecutionStepRecord.key(): StepIterationKey = StepIterationKey(
        stepId = stepId,
        routineIteration = routineIteration,
        stepIteration = stepIteration
    )

    private fun ExecutionStepRecord.sameIteration(other: ExecutionStepRecord): Boolean =
        stepId == other.stepId &&
            routineIteration == other.routineIteration &&
            stepIteration == other.stepIteration

    companion object {
        fun create(
            routine: Routine,
            sessionId: String = UUID.randomUUID().toString()
        ): ExecutionSession {
            if (routine.steps.isEmpty()) {
                throw ExecutionValidationException(ExecutionValidationError.EMPTY_STEPS)
            }
            val snapshot = routine.asExecutionSnapshot()
            val firstStep = snapshot.steps.first()
            return ExecutionSession(
                sessionId = sessionId,
                routineSnapshot = snapshot,
                status = ExecutionStatus.READY,
                progress = ExecutionProgress(
                    routineIteration = 1,
                    currentStepIndex = 0,
                    currentStepId = firstStep.id,
                    stepIteration = 1,
                    currentCount = 0,
                    completeConfirmed = false
                ),
                timerState = initialTimerState(firstStep),
                records = emptyList()
            )
        }
    }
}
