package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.AndroidMonotonicClock
import com.flow.app.domain.execution.timer.ExecutionTimerEngine
import com.flow.app.domain.execution.timer.MonotonicClock
import com.flow.app.domain.execution.timer.CancelPlan
import com.flow.app.domain.execution.timer.CompletionPlan
import com.flow.app.domain.execution.timer.PausePlan
import com.flow.app.domain.execution.timer.ResumePlan
import com.flow.app.domain.execution.timer.TimerCommitResult
import com.flow.app.domain.execution.timer.TimerEngineError
import com.flow.app.domain.execution.timer.TimerPlanResult
import com.flow.app.domain.execution.timer.TimerPrepareResult
import com.flow.app.domain.execution.timer.TimerRunId
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.StepType
import java.util.UUID

fun interface ExecutionClock {
    fun now(): Long
}

object SystemExecutionClock : ExecutionClock {
    override fun now(): Long = System.currentTimeMillis()
}

enum class ExecutionControllerError {
    EMPTY_ROUTINE,
    SESSION_NOT_READY,
    SESSION_NOT_RUNNING,
    SESSION_NOT_PAUSED,
    TERMINAL_SESSION,
    STALE_OPERATION,
    INVALID_TIMESTAMP,
    INVALID_SESSION_STATE,
    NOT_COUNT_STEP,
    COUNT_MODE_NOT_INCREMENT,
    COUNT_ALREADY_AT_TARGET,
    INVALID_COUNT_STATE,
    COUNT_MODE_NOT_COMPLETE,
    COMPLETE_ALREADY_CONFIRMED,
    INVALID_TIMER_STATE,
    REVISION_OVERFLOW
}

data class ExecutionControllerSnapshot(
    val session: ExecutionSession,
    val revision: Long
)

sealed interface ExecutionControllerResult {
    data class Success(
        val session: ExecutionSession,
        val revision: Long
    ) : ExecutionControllerResult

    data class Failure(
        val error: ExecutionControllerError,
        val revision: Long
    ) : ExecutionControllerResult
}

sealed interface ExecutionControllerCreateResult {
    data class Success(val controller: ExecutionController) : ExecutionControllerCreateResult
    data class Failure(val error: ExecutionControllerError) : ExecutionControllerCreateResult
}

sealed interface StepActionResult {
    data class Completed(
        val session: ExecutionSession,
        val revision: Long
    ) : StepActionResult

    data class Skipped(
        val session: ExecutionSession,
        val revision: Long
    ) : StepActionResult

    data class Failure(
        val error: ExecutionControllerError,
        val revision: Long
    ) : StepActionResult
}

internal data class ActiveTimerStepKey(
    val sessionId: String,
    val routineIteration: Int,
    val stepId: String,
    val stepIteration: Int
)

internal data class ActiveTimerReference(
    val runId: TimerRunId,
    val stepKey: ActiveTimerStepKey
)

sealed interface CountIncrementResult {
    data class Incremented(
        val session: ExecutionSession,
        val revision: Long,
        val currentCount: Int,
        val target: Int
    ) : CountIncrementResult

    data class RoundCompleted(
        val session: ExecutionSession,
        val revision: Long
    ) : CountIncrementResult

    data class Failure(
        val error: ExecutionControllerError,
        val revision: Long
    ) : CountIncrementResult
}

class ExecutionController internal constructor(
    private val clock: ExecutionClock,
    initialState: ExecutionSession,
    initialRevision: Long = 0L,
    monotonicClock: MonotonicClock = AndroidMonotonicClock
) {
    private val lock = Any()
    private var session: ExecutionSession = initialState
    private var revisionValue: Long = initialRevision
    private val timerEngine: ExecutionTimerEngine = ExecutionTimerEngine(monotonicClock)
    private var activeTimerReference: ActiveTimerReference? = null

    internal val activeTimerRunId: TimerRunId?
        get() = synchronized(lock) { activeTimerReference?.runId }

    internal val activeTimerStepKey: ActiveTimerStepKey?
        get() = synchronized(lock) { activeTimerReference?.stepKey }

    /**
     * Internal identity check for future Timer operations. This adds no public
     * timer API and does not mutate the Engine.
     */
    internal fun isActiveTimerIdentityValid(
        sessionId: String,
        routineIteration: Int,
        stepId: String,
        stepIteration: Int,
        timerRunId: TimerRunId,
        allowedStatuses: Set<ExecutionStatus> = setOf(
            ExecutionStatus.RUNNING,
            ExecutionStatus.PAUSED
        )
    ): Boolean = synchronized(lock) {
        val reference = activeTimerReference ?: return@synchronized false
        if (session.status !in allowedStatuses) return@synchronized false
        if (session.sessionId != sessionId) return@synchronized false
        if (session.progress.routineIteration != routineIteration) return@synchronized false
        if (session.progress.currentStepId != stepId) return@synchronized false
        if (session.progress.stepIteration != stepIteration) return@synchronized false
        val currentKey = currentTimerStepKeyLocked() ?: return@synchronized false
        val requestedKey = ActiveTimerStepKey(
            sessionId = sessionId,
            routineIteration = routineIteration,
            stepId = stepId,
            stepIteration = stepIteration
        )
        reference.runId == timerRunId &&
            reference.stepKey == currentKey &&
            requestedKey == currentKey
    }

    private fun validateActiveTimerReference(
        allowedStatuses: Set<ExecutionStatus>
    ): ExecutionControllerError? {
        val reference = activeTimerReference ?: return ExecutionControllerError.INVALID_TIMER_STATE
        if (session.status !in allowedStatuses) {
            return ExecutionControllerError.INVALID_TIMER_STATE
        }
        val currentKey = currentTimerStepKeyLocked()
            ?: return ExecutionControllerError.INVALID_TIMER_STATE
        if (reference.stepKey != currentKey) {
            return ExecutionControllerError.INVALID_TIMER_STATE
        }
        val engine = timerEngine.debugState()
        if (engine.runId != reference.runId) {
            return ExecutionControllerError.INVALID_TIMER_STATE
        }
        return null
    }

    internal fun currentTimerStepKey(): ActiveTimerStepKey? = synchronized(lock) {
        currentTimerStepKeyLocked()
    }

    private fun currentTimerStepKeyLocked(): ActiveTimerStepKey? {
        val step = session.routineSnapshot.steps.getOrNull(
            session.progress.currentStepIndex
        ) ?: return null
        if (step.type != StepType.TIMER) return null
        return ActiveTimerStepKey(
            sessionId = session.sessionId,
            routineIteration = session.progress.routineIteration,
            stepId = session.progress.currentStepId,
            stepIteration = session.progress.stepIteration
        )
    }

    internal fun timerEngineForTesting(): ExecutionTimerEngine = timerEngine

    internal fun activeTimerReferenceForTesting(): ActiveTimerReference? =
        synchronized(lock) { activeTimerReference }

    internal fun installActiveTimerForTesting(
        runId: TimerRunId,
        stepKey: ActiveTimerStepKey
    ) {
        synchronized(lock) {
            activeTimerReference = ActiveTimerReference(runId = runId, stepKey = stepKey)
        }
    }

    internal fun clearActiveTimerForTesting() {
        synchronized(lock) { activeTimerReference = null }
    }

    fun snapshot(): ExecutionControllerSnapshot = synchronized(lock) {
        ExecutionControllerSnapshot(session = session, revision = revisionValue)
    }

    private data class TimerStartApplied(
        val session: ExecutionSession,
        val reference: ActiveTimerReference?
    )

    private sealed interface TimerStartApplyResult {
        data class Ready(val value: TimerStartApplied) : TimerStartApplyResult
        data class Failure(val error: ExecutionControllerError) : TimerStartApplyResult
    }

    private data class CompletionApplied(
        val plan: CompletionPlan,
        val session: ExecutionSession,
        val reference: ActiveTimerReference?
    )

    private sealed interface CompletionApplyResult {
        data class Ready(val value: CompletionApplied) : CompletionApplyResult
        data class Failure(val error: ExecutionControllerError) : CompletionApplyResult
    }

    private fun timerStepKeyFor(session: ExecutionSession): ActiveTimerStepKey? {
        val step = session.routineSnapshot.steps.getOrNull(
            session.progress.currentStepIndex
        ) ?: return null
        if (step.type != StepType.TIMER) return null
        return ActiveTimerStepKey(
            sessionId = session.sessionId,
            routineIteration = session.progress.routineIteration,
            stepId = session.progress.currentStepId,
            stepIteration = session.progress.stepIteration
        )
    }

    private fun applyTimerStart(candidate: ExecutionSession): TimerStartApplyResult {
        val step = candidate.routineSnapshot.steps.getOrNull(
            candidate.progress.currentStepIndex
        ) ?: return TimerStartApplyResult.Failure(
            ExecutionControllerError.INVALID_TIMER_STATE
        )
        if (step.type != StepType.TIMER) {
            return TimerStartApplyResult.Ready(
                TimerStartApplied(candidate, null)
            )
        }
        if (candidate.timerState.timerStatus != TimerStatus.IDLE ||
            activeTimerReference != null
        ) {
            return TimerStartApplyResult.Failure(
                ExecutionControllerError.INVALID_TIMER_STATE
            )
        }
        val duration = step.durationMs
        if (duration == null || duration <= 0L) {
            return TimerStartApplyResult.Failure(
                ExecutionControllerError.INVALID_TIMER_STATE
            )
        }
        val key = timerStepKeyFor(candidate)
            ?: return TimerStartApplyResult.Failure(
                ExecutionControllerError.INVALID_TIMER_STATE
            )
        val plan = when (val prepared = timerEngine.prepareStart(duration)) {
            is TimerPlanResult.Success -> prepared.plan
            is TimerPlanResult.Failure -> return TimerStartApplyResult.Failure(
                controllerErrorForTimer(prepared.error)
            )
        }
        val deadline = plan.deadlineMonotonicMs
            ?: return TimerStartApplyResult.Failure(
                ExecutionControllerError.INVALID_TIMER_STATE
            )
        val next = try {
            candidate.startTimer(deadline)
        } catch (error: ExecutionValidationException) {
            return TimerStartApplyResult.Failure(
                ExecutionControllerError.INVALID_TIMER_STATE
            )
        }
        val reference = ActiveTimerReference(plan.targetRunId, key)
        when (val committed = timerEngine.commitStart(plan)) {
            is com.flow.app.domain.execution.timer.TimerOperationResult.Success -> Unit
            is com.flow.app.domain.execution.timer.TimerOperationResult.Expired ->
                return TimerStartApplyResult.Failure(ExecutionControllerError.INVALID_TIMESTAMP)
            is com.flow.app.domain.execution.timer.TimerOperationResult.Failure ->
                return TimerStartApplyResult.Failure(
                    controllerErrorForTimer(committed.error)
                )
        }
        return TimerStartApplyResult.Ready(
            TimerStartApplied(next, reference)
        )
    }

    private fun prepareCompletionTransaction(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        outcome: ExecutionStepOutcome,
        wallNow: Long
    ): CompletionApplyResult {
        val roundStart = base.currentRoundStartedAt
            ?: return CompletionApplyResult.Failure(
                ExecutionControllerError.INVALID_SESSION_STATE
            )
        val result = StepRoundResult(
            outcome = outcome,
            startedAt = roundStart,
            endedAt = wallNow
        )
        val advanced = try {
            completionSession.commitStepResult(result)
        } catch (error: ExecutionValidationException) {
            return CompletionApplyResult.Failure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            return CompletionApplyResult.Failure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
        val nextStep = advanced.routineSnapshot.steps.getOrNull(
            advanced.progress.currentStepIndex
        )
        val nextDuration = if (advanced.status != ExecutionStatus.COMPLETED &&
            nextStep?.type == StepType.TIMER
        ) {
            nextStep.durationMs
        } else {
            null
        }
        if (advanced.status != ExecutionStatus.COMPLETED &&
            nextStep?.type == StepType.TIMER &&
            (nextDuration == null || nextDuration <= 0L)
        ) {
            return CompletionApplyResult.Failure(
                ExecutionControllerError.INVALID_TIMER_STATE
            )
        }
        val plan = when (val prepared = timerEngine.prepareCompletion(nextDuration)) {
            is TimerPrepareResult.Ready -> prepared.plan as? CompletionPlan
                ?: return CompletionApplyResult.Failure(
                    ExecutionControllerError.INVALID_SESSION_STATE
                )
            is TimerPrepareResult.Failure -> return CompletionApplyResult.Failure(
                controllerErrorForTimer(prepared.error)
            )
            else -> return CompletionApplyResult.Failure(
                ExecutionControllerError.INVALID_SESSION_STATE
            )
        }
        if (nextDuration != null && nextStep?.type == StepType.TIMER) {
            val deadline = plan.nextDeadlineMonotonicMs
                ?: return CompletionApplyResult.Failure(
                    ExecutionControllerError.INVALID_TIMER_STATE
                )
            val final = try {
                advanced.startTimer(deadline)
            } catch (error: ExecutionValidationException) {
                return CompletionApplyResult.Failure(
                    ExecutionControllerError.INVALID_TIMER_STATE
                )
            }
            val key = timerStepKeyFor(final)
                ?: return CompletionApplyResult.Failure(
                    ExecutionControllerError.INVALID_TIMER_STATE
                )
            val runId = plan.nextRunId
                ?: return CompletionApplyResult.Failure(
                    ExecutionControllerError.INVALID_TIMER_STATE
                )
            return CompletionApplyResult.Ready(
                CompletionApplied(
                    plan = plan,
                    session = final,
                    reference = ActiveTimerReference(runId, key)
                )
            )
        }
        return CompletionApplyResult.Ready(
            CompletionApplied(plan = plan, session = advanced, reference = null)
        )
    }

    private data class AdvancedCompletion(
        val session: ExecutionSession,
        val nextTimerDurationMs: Long?
    )

    private fun buildAdvancedCompletion(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        outcome: ExecutionStepOutcome,
        wallNow: Long
    ): Any {
        val roundStart = base.currentRoundStartedAt
            ?: return ExecutionControllerError.INVALID_SESSION_STATE
        val result = StepRoundResult(outcome, roundStart, wallNow)
        val advanced = try {
            completionSession.commitStepResult(result)
        } catch (error: ExecutionValidationException) {
            return if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                ExecutionControllerError.INVALID_TIMESTAMP
            } else {
                ExecutionControllerError.INVALID_SESSION_STATE
            }
        } catch (error: ExecutionSchedulingException) {
            return if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                ExecutionControllerError.INVALID_TIMESTAMP
            } else {
                ExecutionControllerError.INVALID_SESSION_STATE
            }
        }
        val step = advanced.routineSnapshot.steps.getOrNull(
            advanced.progress.currentStepIndex
        )
        val duration = if (advanced.status != ExecutionStatus.COMPLETED &&
            step?.type == StepType.TIMER
        ) {
            step.durationMs
        } else {
            null
        }
        if (advanced.status != ExecutionStatus.COMPLETED &&
            step?.type == StepType.TIMER &&
            (duration == null || duration <= 0L)
        ) {
            return ExecutionControllerError.INVALID_TIMER_STATE
        }
        return AdvancedCompletion(advanced, duration)
    }

    private fun controllerErrorForTimer(error: TimerEngineError): ExecutionControllerError =
        when (error) {
            TimerEngineError.PLAN_EXPIRED,
            TimerEngineError.CLOCK_REGRESSION,
            TimerEngineError.INVALID_CLOCK -> ExecutionControllerError.INVALID_TIMESTAMP

            else -> ExecutionControllerError.INVALID_SESSION_STATE
        }

    /**
     * Atomically starts the current TIMER using an Engine plan. The Engine
     * commit is deliberately the last fallible step before publication.
     */
    internal fun startCurrentTimer(expectedRevision: Long): ExecutionControllerResult =
        synchronized(lock) {
            if (revisionValue == Long.MAX_VALUE) {
                return failure(ExecutionControllerError.REVISION_OVERFLOW)
            }
            if (expectedRevision != revisionValue) {
                return failure(ExecutionControllerError.STALE_OPERATION)
            }
            if (session.status == ExecutionStatus.COMPLETED ||
                session.status == ExecutionStatus.CANCELLED
            ) {
                return failure(ExecutionControllerError.TERMINAL_SESSION)
            }
            if (session.status != ExecutionStatus.RUNNING) {
                return failure(ExecutionControllerError.SESSION_NOT_RUNNING)
            }
            val step = session.routineSnapshot.steps.getOrNull(
                session.progress.currentStepIndex
            ) ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            if (step.type != StepType.TIMER) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            if (session.timerState.timerStatus != TimerStatus.IDLE ||
                activeTimerReference != null
            ) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val duration = step.durationMs
            if (duration == null || duration <= 0L) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val stepKey = currentTimerStepKeyLocked()
                ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)

            val plan = when (val prepared = timerEngine.prepareStart(duration)) {
                is TimerPlanResult.Success -> prepared.plan
                is TimerPlanResult.Failure -> return timerFailure(prepared.error)
            }
            val deadline = plan.deadlineMonotonicMs
                ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            val candidate = try {
                session.startTimer(deadline)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val reference = ActiveTimerReference(
                runId = plan.targetRunId,
                stepKey = stepKey
            )
            val nextRevision = revisionValue + 1L

            when (val committed = timerEngine.commitStart(plan)) {
                is com.flow.app.domain.execution.timer.TimerOperationResult.Success -> Unit
                is com.flow.app.domain.execution.timer.TimerOperationResult.Expired ->
                    return failure(ExecutionControllerError.INVALID_TIMESTAMP)
                is com.flow.app.domain.execution.timer.TimerOperationResult.Failure ->
                    return timerFailure(committed.error)
            }

            session = candidate
            activeTimerReference = reference
            revisionValue = nextRevision
            ExecutionControllerResult.Success(
                session = candidate,
                revision = revisionValue
            )
        }

    fun start(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        when (session.status) {
            ExecutionStatus.READY -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_READY)
        }

        val now = clock.now()
        if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
        val started = session.start(now)
        return when (val applied = applyTimerStart(started)) {
            is TimerStartApplyResult.Failure -> failure(applied.error)
            is TimerStartApplyResult.Ready -> {
                session = applied.value.session
                activeTimerReference = applied.value.reference
                revisionValue += 1L
                ExecutionControllerResult.Success(
                    session = session,
                    revision = revisionValue
                )
            }
        }
    }

    fun incrementCount(expectedRevision: Long): CountIncrementResult = synchronized(lock) {
        if (revisionValue == Long.MAX_VALUE) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.REVISION_OVERFLOW,
                revision = revisionValue
            )
        }
        if (expectedRevision != revisionValue) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.STALE_OPERATION,
                revision = revisionValue
            )
        }
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED
        ) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.TERMINAL_SESSION,
                revision = revisionValue
            )
        }
        if (session.status != ExecutionStatus.RUNNING) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.SESSION_NOT_RUNNING,
                revision = revisionValue
            )
        }

        val step = session.routineSnapshot.steps[session.progress.currentStepIndex]
        if (step.type != StepType.COUNT) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.NOT_COUNT_STEP,
                revision = revisionValue
            )
        }
        if (activeTimerReference != null) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.INVALID_TIMER_STATE,
                revision = revisionValue
            )
        }
        if (step.countMode != CountMode.INCREMENT) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.COUNT_MODE_NOT_INCREMENT,
                revision = revisionValue
            )
        }
        val target = step.target
        if (target == null || target <= 0) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.INVALID_COUNT_STATE,
                revision = revisionValue
            )
        }
        val current = session.progress.currentCount
        if (current < 0 || current > target) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.INVALID_COUNT_STATE,
                revision = revisionValue
            )
        }
        if (current == target) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.COUNT_ALREADY_AT_TARGET,
                revision = revisionValue
            )
        }

        val now = clock.now()
        if (now < 0L) {
            return CountIncrementResult.Failure(
                error = ExecutionControllerError.INVALID_TIMESTAMP,
                revision = revisionValue
            )
        }
        val roundStart = session.currentRoundStartedAt
            ?: return CountIncrementResult.Failure(
                error = ExecutionControllerError.INVALID_SESSION_STATE,
                revision = revisionValue
            )
        val willComplete = current + 1 == target
        val completion = StepRoundResult(
            outcome = ExecutionStepOutcome.COMPLETED,
            startedAt = roundStart,
            endedAt = now
        )

        val next = try {
            session.incrementCountAndMaybeComplete(completion)
        } catch (error: ExecutionValidationException) {
            return countFailure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            return countFailure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
        val applied = when (val start = applyTimerStart(next)) {
            is TimerStartApplyResult.Failure -> return countFailure(start.error)
            is TimerStartApplyResult.Ready -> start.value
        }
        session = applied.session
        activeTimerReference = applied.reference
        revisionValue += 1L
        if (willComplete) {
            CountIncrementResult.RoundCompleted(
                session = session,
                revision = revisionValue
            )
        } else {
            CountIncrementResult.Incremented(
                session = session,
                revision = revisionValue,
                currentCount = current + 1,
                target = target
            )
        }
    }

    fun confirmComplete(expectedRevision: Long): StepActionResult = synchronized(lock) {
        if (revisionValue == Long.MAX_VALUE) {
            return stepFailure(ExecutionControllerError.REVISION_OVERFLOW)
        }
        if (expectedRevision != revisionValue) {
            return stepFailure(ExecutionControllerError.STALE_OPERATION)
        }
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED
        ) {
            return stepFailure(ExecutionControllerError.TERMINAL_SESSION)
        }
        if (session.status != ExecutionStatus.RUNNING) {
            return stepFailure(ExecutionControllerError.SESSION_NOT_RUNNING)
        }

        val step = session.routineSnapshot.steps[session.progress.currentStepIndex]
        if (step.type != StepType.COUNT) {
            return stepFailure(ExecutionControllerError.NOT_COUNT_STEP)
        }
        if (activeTimerReference != null) {
            return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        if (step.countMode != CountMode.COMPLETE) {
            return stepFailure(ExecutionControllerError.COUNT_MODE_NOT_COMPLETE)
        }
        if (session.progress.completeConfirmed) {
            return stepFailure(ExecutionControllerError.COMPLETE_ALREADY_CONFIRMED)
        }
        val roundStart = session.currentRoundStartedAt
            ?: return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        val now = clock.now()
        if (now < 0L) {
            return stepFailure(ExecutionControllerError.INVALID_TIMESTAMP)
        }

        val completion = StepRoundResult(
            outcome = ExecutionStepOutcome.COMPLETED,
            startedAt = roundStart,
            endedAt = now
        )
        val next = try {
            session.confirmComplete(completion)
        } catch (error: ExecutionValidationException) {
            return stepFailure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            return stepFailure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
        val applied = when (val start = applyTimerStart(next)) {
            is TimerStartApplyResult.Failure -> return stepFailure(start.error)
            is TimerStartApplyResult.Ready -> start.value
        }
        session = applied.session
        activeTimerReference = applied.reference
        revisionValue += 1L
        StepActionResult.Completed(session = session, revision = revisionValue)
    }

    fun skipCurrentRound(expectedRevision: Long): StepActionResult = synchronized(lock) {
        if (revisionValue == Long.MAX_VALUE) {
            return stepFailure(ExecutionControllerError.REVISION_OVERFLOW)
        }
        if (expectedRevision != revisionValue) {
            return stepFailure(ExecutionControllerError.STALE_OPERATION)
        }
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED
        ) {
            return stepFailure(ExecutionControllerError.TERMINAL_SESSION)
        }
        if (session.status != ExecutionStatus.RUNNING) {
            return stepFailure(ExecutionControllerError.SESSION_NOT_RUNNING)
        }

        val step = session.routineSnapshot.steps.getOrNull(
            session.progress.currentStepIndex
        ) ?: return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        val roundStart = session.currentRoundStartedAt
            ?: return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        val now = clock.now()
        if (now < 0L) return stepFailure(ExecutionControllerError.INVALID_TIMESTAMP)

        if (step.type == StepType.TIMER) {
            validateActiveTimerReference(setOf(ExecutionStatus.RUNNING))?.let {
                return stepFailure(it)
            }
            val poll = timerEngine.preparePoll()
            if (poll is TimerPrepareResult.Failure) {
                return stepFailure(controllerErrorForTimer(poll.error))
            }
            if (poll is TimerPrepareResult.Ready) {
                // Expiry wins: discard SKIP and complete naturally.
                val completed = try {
                    session.completeTimer()
                } catch (error: ExecutionValidationException) {
                    return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
                }
                return finishTimerTransaction(
                    base = session,
                    completionSession = completed,
                    outcome = ExecutionStepOutcome.COMPLETED,
                    wallNow = now,
                    success = { finalSession, reference -> StepActionResult.Completed(finalSession, revisionValue) }
                )
            }
            val cleared = try {
                session.clearTimerForSkip()
            } catch (error: ExecutionValidationException) {
                return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val completed = try {
                session.completeTimer()
            } catch (error: ExecutionValidationException) {
                return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            return finishSkipTimerTransaction(
                base = session,
                skippedSession = cleared,
                completedSession = completed,
                wallNow = now
            )
        }

        if (step.type == StepType.COUNT && activeTimerReference != null) {
            return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        val result = StepRoundResult(
            outcome = ExecutionStepOutcome.SKIPPED,
            startedAt = roundStart,
            endedAt = now
        )
        val next = try {
            session.skipCurrentRound(result)
        } catch (error: ExecutionValidationException) {
            return stepFailure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            return stepFailure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
        val applied = when (val start = applyTimerStart(next)) {
            is TimerStartApplyResult.Failure -> return stepFailure(start.error)
            is TimerStartApplyResult.Ready -> start.value
        }
        session = applied.session
        activeTimerReference = applied.reference
        revisionValue += 1L
        StepActionResult.Skipped(session = session, revision = revisionValue)
    }

    fun pause(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }
        val step = session.routineSnapshot.steps.getOrNull(session.progress.currentStepIndex)
        if (step?.type != StepType.TIMER && activeTimerReference != null) {
            return failure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        if (step?.type == StepType.TIMER) {
            if (session.status != ExecutionStatus.RUNNING) {
                return failure(ExecutionControllerError.SESSION_NOT_RUNNING)
            }
            validateActiveTimerReference(setOf(ExecutionStatus.RUNNING))?.let {
                return failure(it)
            }
            val now = clock.now()
            if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
            val completed = try {
                session.completeTimer()
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val advanced = when (
                val result = buildAdvancedCompletion(
                    session, completed, ExecutionStepOutcome.COMPLETED, now
                )
            ) {
                is AdvancedCompletion -> result
                is ExecutionControllerError -> return failure(result)
                else -> return failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
            val plan = when (val prepared = timerEngine.preparePause(advanced.nextTimerDurationMs)) {
                is TimerPrepareResult.Ready -> prepared.plan as? PausePlan
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                is TimerPrepareResult.Expired -> {
                    val completion = when (
                        val result = prepareCompletionTransaction(
                            base = session,
                            completionSession = completed,
                            outcome = ExecutionStepOutcome.COMPLETED,
                            wallNow = now
                        )
                    ) {
                        is CompletionApplyResult.Ready -> result.value
                        is CompletionApplyResult.Failure -> return failure(result.error)
                    }
                    when (val committed = timerEngine.commitCompletion(completion.plan)) {
                        is TimerCommitResult.Completed -> Unit
                        is TimerCommitResult.Failure -> return failure(
                            controllerErrorForTimer(committed.error)
                        )
                        else -> return failure(ExecutionControllerError.INVALID_SESSION_STATE)
                    }
                    session = completion.session
                    activeTimerReference = completion.reference
                    revisionValue += 1L
                    return ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerPrepareResult.Failure -> return timerFailure(prepared.error)
                else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val paused = try {
                session.pauseTimer(plan.remainingDurationMs)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val fallbackSession = if (advanced.nextTimerDurationMs != null) {
                val deadline = plan.fallbackDeadlineMonotonicMs
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                try {
                    advanced.session.startTimer(deadline)
                } catch (error: ExecutionValidationException) {
                    return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                }
            } else {
                advanced.session
            }
            val fallbackReference = if (advanced.nextTimerDurationMs != null) {
                val runId = plan.fallbackRunId
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                val key = timerStepKeyFor(fallbackSession)
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                ActiveTimerReference(runId, key)
            } else {
                null
            }
            val committed = timerEngine.commitPause(plan)
            return when (committed) {
                is TimerCommitResult.Paused -> {
                    session = paused
                    revisionValue += 1L
                    ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerCommitResult.Expired -> {
                    session = fallbackSession
                    activeTimerReference = fallbackReference
                    revisionValue += 1L
                    ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerCommitResult.Failure -> timerFailure(committed.error)
                else -> failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
        }
        when (session.status) {
            ExecutionStatus.RUNNING -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_RUNNING)
        }
        return runTransition { session.pause() }
    }

    fun resume(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }
        if (session.status != ExecutionStatus.PAUSED) {
            return failure(ExecutionControllerError.SESSION_NOT_PAUSED)
        }
        val step = session.routineSnapshot.steps.getOrNull(session.progress.currentStepIndex)
        if (step?.type != StepType.TIMER && activeTimerReference != null) {
            return failure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        if (step?.type == StepType.TIMER) {
            validateActiveTimerReference(setOf(ExecutionStatus.PAUSED))?.let {
                return failure(it)
            }
            val now = clock.now()
            if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
            val first = when (val prepared = timerEngine.prepareResume()) {
                is TimerPrepareResult.Ready -> prepared.plan as? ResumePlan
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                is TimerPrepareResult.Failure -> return timerFailure(prepared.error)
                else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val primary = try {
                session.resumeTimer(first.deadlineMonotonicMs)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val completed = try {
                primary.completeTimer()
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val advanced = when (
                val result = buildAdvancedCompletion(
                    session, completed, ExecutionStepOutcome.COMPLETED, now
                )
            ) {
                is AdvancedCompletion -> result
                is ExecutionControllerError -> return failure(result)
                else -> return failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
            val plan = when (val prepared = timerEngine.prepareResume(advanced.nextTimerDurationMs)) {
                is TimerPrepareResult.Ready -> prepared.plan as? ResumePlan
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                is TimerPrepareResult.Failure -> return timerFailure(prepared.error)
                else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val fallbackSession = if (advanced.nextTimerDurationMs != null) {
                val deadline = plan.fallbackDeadlineMonotonicMs
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                try {
                    advanced.session.startTimer(deadline)
                } catch (error: ExecutionValidationException) {
                    return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                }
            } else {
                advanced.session
            }
            val fallbackReference = if (advanced.nextTimerDurationMs != null) {
                val runId = plan.fallbackRunId
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                val key = timerStepKeyFor(fallbackSession)
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                ActiveTimerReference(runId, key)
            } else {
                null
            }
            val finalPrimary = try {
                session.resumeTimer(plan.deadlineMonotonicMs)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            return when (val committed = timerEngine.commitResume(plan)) {
                is TimerCommitResult.Resumed -> {
                    session = finalPrimary
                    revisionValue += 1L
                    ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerCommitResult.Expired -> {
                    session = fallbackSession
                    activeTimerReference = fallbackReference
                    revisionValue += 1L
                    ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerCommitResult.Failure -> timerFailure(committed.error)
                else -> failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
        }
        return runTransition { session.resume() }
    }

    /**
     * Polls the active TIMER. Not-expired is a no-op; expired completes and
     * advances atomically, including an optional next TIMER.
     */
    fun pollTimer(
        expectedRevision: Long,
        expectedRunId: TimerRunId
    ): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED ||
            session.status == ExecutionStatus.PAUSED
        ) {
            return ExecutionControllerResult.Success(session, revisionValue)
        }
        val current = session.routineSnapshot.steps.getOrNull(session.progress.currentStepIndex)
        val reference = activeTimerReference
        if (reference == null) {
            return if (current?.type == StepType.TIMER) {
                failure(ExecutionControllerError.INVALID_TIMER_STATE)
            } else {
                ExecutionControllerResult.Success(session, revisionValue)
            }
        }
        if (reference.runId != expectedRunId) {
            return ExecutionControllerResult.Success(session, revisionValue)
        }
        if (session.status != ExecutionStatus.RUNNING) {
            return ExecutionControllerResult.Success(session, revisionValue)
        }
        val step = session.routineSnapshot.steps.getOrNull(session.progress.currentStepIndex)
        if (step?.type != StepType.TIMER) {
            return failure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        validateActiveTimerReference(setOf(ExecutionStatus.RUNNING))?.let {
            return failure(it)
        }
        when (val poll = timerEngine.preparePoll()) {
            is TimerPrepareResult.NoChange ->
                return ExecutionControllerResult.Success(session, revisionValue)
            is TimerPrepareResult.Failure -> return timerFailure(poll.error)
            is TimerPrepareResult.Ready -> Unit
            else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        val now = clock.now()
        if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
        val completed = try {
            session.completeTimer()
        } catch (error: ExecutionValidationException) {
            return failure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        return finishTimerTransactionForResult(session, completed, now)
    }

    private fun finishTimerTransactionForResult(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        wallNow: Long
    ): ExecutionControllerResult {
        val prepared = when (
            val result = prepareCompletionTransaction(
                base, completionSession, ExecutionStepOutcome.COMPLETED, wallNow
            )
        ) {
            is CompletionApplyResult.Ready -> result.value
            is CompletionApplyResult.Failure -> return failure(result.error)
        }
        when (val committed = timerEngine.commitCompletion(prepared.plan)) {
            is TimerCommitResult.Completed -> Unit
            is TimerCommitResult.Failure -> return timerFailure(committed.error)
            else -> return failure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
        session = prepared.session
        activeTimerReference = prepared.reference
        revisionValue += 1L
        return ExecutionControllerResult.Success(session, revisionValue)
    }

    fun cancel(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }
        val now = clock.now()
        if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
        val step = session.routineSnapshot.steps.getOrNull(session.progress.currentStepIndex)
        if ((session.status == ExecutionStatus.RUNNING ||
                session.status == ExecutionStatus.PAUSED) &&
            step?.type == StepType.TIMER
        ) {
            validateActiveTimerReference(
                setOf(ExecutionStatus.RUNNING, ExecutionStatus.PAUSED)
            )?.let { return failure(it) }
            if (session.status == ExecutionStatus.RUNNING) {
                when (val poll = timerEngine.preparePoll()) {
                    is TimerPrepareResult.Ready -> return cancelAfterExpiry(now)
                    is TimerPrepareResult.Failure -> return timerFailure(poll.error)
                    is TimerPrepareResult.NoChange -> Unit
                    else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                }
            }
            val plan = when (val prepared = timerEngine.prepareCancel()) {
                is TimerPrepareResult.Ready -> prepared.plan as? CancelPlan
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                is TimerPrepareResult.Expired -> return cancelAfterExpiry(now)
                is TimerPrepareResult.Failure -> return timerFailure(prepared.error)
                else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val cancelledTimer = TimerState(
                totalDurationMs = session.timerState.totalDurationMs,
                remainingDurationMs = plan.remainingDurationMs,
                deadline = null,
                timerStatus = TimerStatus.CANCELLED
            )
            val cancelled = try {
                session.cancelWithTimerState(now, cancelledTimer)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
            val fallback = when (
                val result = buildCancelFallbackSession(now)
            ) {
                is ExecutionSession -> result
                is ExecutionControllerError -> return failure(result)
                else -> return failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
            return when (val committed = timerEngine.commitCancel(plan)) {
                is TimerCommitResult.Cancelled -> {
                    session = cancelled
                    activeTimerReference = null
                    revisionValue += 1L
                    ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerCommitResult.Expired -> {
                    session = fallback
                    activeTimerReference = null
                    revisionValue += 1L
                    return ExecutionControllerResult.Success(session, revisionValue)
                }
                is TimerCommitResult.Failure -> timerFailure(committed.error)
                else -> failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
        }
        return runTransition { session.cancel(now) }
    }

    private fun buildCancelFallbackSession(wallNow: Long): Any {
        val completed = if (session.status == ExecutionStatus.PAUSED) {
            try {
                session.resumeTimer(Long.MAX_VALUE).completeTimer()
            } catch (error: ExecutionValidationException) {
                return ExecutionControllerError.INVALID_TIMER_STATE
            }
        } else {
            try {
                session.completeTimer()
            } catch (error: ExecutionValidationException) {
                return ExecutionControllerError.INVALID_TIMER_STATE
            }
        }
        val advanced = when (
            val result = buildAdvancedCompletion(
                session, completed, ExecutionStepOutcome.COMPLETED, wallNow
            )
        ) {
            is AdvancedCompletion -> result
            is ExecutionControllerError -> return result
            else -> return ExecutionControllerError.INVALID_SESSION_STATE
        }
        return if (advanced.session.status == ExecutionStatus.COMPLETED) {
            advanced.session
        } else {
            try {
                advanced.session.cancelWithTimerState(wallNow, advanced.session.timerState)
            } catch (error: ExecutionValidationException) {
                ExecutionControllerError.INVALID_SESSION_STATE
            }
        }
    }

    private fun cancelAfterExpiry(wallNow: Long): ExecutionControllerResult {
        val completed = if (session.status == ExecutionStatus.PAUSED) {
            val resume = when (val prepared = timerEngine.prepareResume()) {
                is TimerPrepareResult.Ready -> prepared.plan as? ResumePlan
                    ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
                is TimerPrepareResult.Failure -> return timerFailure(prepared.error)
                else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            val resumed = try {
                session.resumeTimer(resume.deadlineMonotonicMs)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
            try {
                resumed.completeTimer()
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
        } else {
            try {
                session.completeTimer()
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            }
        }
        val advanced = when (
            val result = buildAdvancedCompletion(
                session, completed, ExecutionStepOutcome.COMPLETED, wallNow
            )
        ) {
            is AdvancedCompletion -> result
            is ExecutionControllerError -> return failure(result)
            else -> return failure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
        val finalSession = if (advanced.session.status == ExecutionStatus.COMPLETED) {
            advanced.session
        } else {
            try {
                advanced.session.cancelWithTimerState(wallNow, advanced.session.timerState)
            } catch (error: ExecutionValidationException) {
                return failure(ExecutionControllerError.INVALID_SESSION_STATE)
            }
        }
        val plan = when (val prepared = timerEngine.prepareCompletion(null)) {
            is TimerPrepareResult.Ready -> prepared.plan as? CompletionPlan
                ?: return failure(ExecutionControllerError.INVALID_TIMER_STATE)
            is TimerPrepareResult.Failure -> return timerFailure(prepared.error)
            else -> return failure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        return when (val committed = timerEngine.commitCompletion(plan)) {
            is TimerCommitResult.Completed -> {
                session = finalSession
                activeTimerReference = null
                revisionValue += 1L
                ExecutionControllerResult.Success(session, revisionValue)
            }
            is TimerCommitResult.Failure -> timerFailure(committed.error)
            else -> failure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
    }

    private fun staleOrNull(expectedRevision: Long): ExecutionControllerResult.Failure? =
        if (revisionValue == Long.MAX_VALUE) {
            ExecutionControllerResult.Failure(
                error = ExecutionControllerError.REVISION_OVERFLOW,
                revision = revisionValue
            )
        } else if (expectedRevision != revisionValue) {
            ExecutionControllerResult.Failure(
                error = ExecutionControllerError.STALE_OPERATION,
                revision = revisionValue
            )
        } else {
            null
        }

    private fun terminalOrNull(): ExecutionControllerResult.Failure? =
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED
        ) {
            ExecutionControllerResult.Failure(
                error = ExecutionControllerError.TERMINAL_SESSION,
                revision = revisionValue
            )
        } else {
            null
        }

    private fun runTransition(block: () -> ExecutionSession): ExecutionControllerResult =
        try {
            val next = block()
            session = next
            revisionValue += 1L
            ExecutionControllerResult.Success(
                session = next,
                revision = revisionValue
            )
        } catch (error: ExecutionValidationException) {
            failure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }

    private data class CompletionFinalized(
        val session: ExecutionSession,
        val reference: ActiveTimerReference?
    )

    private fun finalizeCompletionPlan(
        advanced: AdvancedCompletion,
        plan: CompletionPlan
    ): CompletionFinalized? {
        if (advanced.nextTimerDurationMs == null) {
            return CompletionFinalized(advanced.session, null)
        }
        val deadline = plan.nextDeadlineMonotonicMs ?: return null
        val runId = plan.nextRunId ?: return null
        val final = try {
            advanced.session.startTimer(deadline)
        } catch (error: ExecutionValidationException) {
            return null
        }
        val key = timerStepKeyFor(final) ?: return null
        return CompletionFinalized(final, ActiveTimerReference(runId, key))
    }

    private fun finishSkipTimerTransaction(
        base: ExecutionSession,
        skippedSession: ExecutionSession,
        completedSession: ExecutionSession,
        wallNow: Long
    ): StepActionResult {
        val skippedAdvanced = when (
            val result = buildAdvancedCompletion(
                base, skippedSession, ExecutionStepOutcome.SKIPPED, wallNow
            )
        ) {
            is AdvancedCompletion -> result
            is ExecutionControllerError -> return stepFailure(result)
            else -> return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
        val completedAdvanced = when (
            val result = buildAdvancedCompletion(
                base, completedSession, ExecutionStepOutcome.COMPLETED, wallNow
            )
        ) {
            is AdvancedCompletion -> result
            is ExecutionControllerError -> return stepFailure(result)
            else -> return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
        if (skippedAdvanced.nextTimerDurationMs != completedAdvanced.nextTimerDurationMs) {
            return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
        val plan = when (
            val prepared = timerEngine.prepareCompletion(
                skippedAdvanced.nextTimerDurationMs,
                expirySensitive = true
            )
        ) {
            is TimerPrepareResult.Ready -> prepared.plan as? CompletionPlan
                ?: return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
            is TimerPrepareResult.Failure ->
                return stepFailure(controllerErrorForTimer(prepared.error))
            else -> return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
        }
        val skippedFinal = finalizeCompletionPlan(skippedAdvanced, plan)
            ?: return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
        val completedFinal = finalizeCompletionPlan(completedAdvanced, plan)
            ?: return stepFailure(ExecutionControllerError.INVALID_TIMER_STATE)
        return when (val committed = timerEngine.commitCompletion(plan)) {
            is TimerCommitResult.Completed -> {
                session = skippedFinal.session
                activeTimerReference = skippedFinal.reference
                revisionValue += 1L
                StepActionResult.Skipped(session, revisionValue)
            }
            is TimerCommitResult.Expired -> {
                session = completedFinal.session
                activeTimerReference = completedFinal.reference
                revisionValue += 1L
                StepActionResult.Completed(session, revisionValue)
            }
            is TimerCommitResult.Failure ->
                stepFailure(controllerErrorForTimer(committed.error))
            else -> stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
    }

    private fun finishTimerTransaction(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        outcome: ExecutionStepOutcome,
        wallNow: Long,
        success: (ExecutionSession, ActiveTimerReference?) -> StepActionResult
    ): StepActionResult {
        val prepared = when (
            val result = prepareCompletionTransaction(
                base = base,
                completionSession = completionSession,
                outcome = outcome,
                wallNow = wallNow
            )
        ) {
            is CompletionApplyResult.Failure -> return stepFailure(result.error)
            is CompletionApplyResult.Ready -> result.value
        }
        when (val committed = timerEngine.commitCompletion(prepared.plan)) {
            is TimerCommitResult.Completed -> Unit
            is TimerCommitResult.Failure -> return stepFailure(
                controllerErrorForTimer(committed.error)
            )
            else -> return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        }
        session = prepared.session
        activeTimerReference = prepared.reference
        revisionValue += 1L
        return success(session, activeTimerReference)
    }

    private fun timerFailure(error: TimerEngineError): ExecutionControllerResult.Failure =
        failure(controllerErrorForTimer(error))

    private fun failure(error: ExecutionControllerError): ExecutionControllerResult.Failure =
        ExecutionControllerResult.Failure(error = error, revision = revisionValue)

    private fun countFailure(error: ExecutionControllerError): CountIncrementResult.Failure =
        CountIncrementResult.Failure(error = error, revision = revisionValue)

    private fun stepFailure(error: ExecutionControllerError): StepActionResult.Failure =
        StepActionResult.Failure(error = error, revision = revisionValue)

    companion object {
        fun create(
            routine: Routine,
            sessionId: String = UUID.randomUUID().toString(),
            clock: ExecutionClock = SystemExecutionClock,
            monotonicClock: MonotonicClock = AndroidMonotonicClock
        ): ExecutionControllerCreateResult = try {
            val initial = ExecutionSession.create(
                routine = routine,
                sessionId = sessionId
            )
            ExecutionControllerCreateResult.Success(
                ExecutionController(
                    clock = clock,
                    initialState = initial,
                    monotonicClock = monotonicClock
                )
            )
        } catch (error: ExecutionValidationException) {
            ExecutionControllerCreateResult.Failure(
                if (error.code == ExecutionValidationError.EMPTY_STEPS) {
                    ExecutionControllerError.EMPTY_ROUTINE
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
    }
}
