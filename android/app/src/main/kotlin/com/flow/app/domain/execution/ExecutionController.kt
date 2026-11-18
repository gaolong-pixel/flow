package com.flow.app.domain.execution

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
    COMPLETE_ALREADY_CONFIRMED
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
    initialRevision: Long = 0L
) {
    private val lock = Any()
    private var session: ExecutionSession = initialState
    private var revisionValue: Long = initialRevision

    fun snapshot(): ExecutionControllerSnapshot = synchronized(lock) {
        ExecutionControllerSnapshot(session = session, revision = revisionValue)
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
        return runTransition {
            session.start(now)
        }
    }

    fun incrementCount(expectedRevision: Long): CountIncrementResult = synchronized(lock) {
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

        return try {
            val next = session.incrementCountAndMaybeComplete(completion)
            session = next
            revisionValue += 1L
            if (willComplete) {
                CountIncrementResult.RoundCompleted(
                    session = next,
                    revision = revisionValue
                )
            } else {
                CountIncrementResult.Incremented(
                    session = next,
                    revision = revisionValue,
                    currentCount = current + 1,
                    target = target
                )
            }
        } catch (error: ExecutionValidationException) {
            countFailure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            countFailure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
    }

    fun confirmComplete(expectedRevision: Long): StepActionResult = synchronized(lock) {
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
        return try {
            val next = session.confirmComplete(completion)
            session = next
            revisionValue += 1L
            StepActionResult.Completed(session = next, revision = revisionValue)
        } catch (error: ExecutionValidationException) {
            stepFailure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            stepFailure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
    }

    fun skipCurrentRound(expectedRevision: Long): StepActionResult = synchronized(lock) {
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

        val roundStart = session.currentRoundStartedAt
            ?: return stepFailure(ExecutionControllerError.INVALID_SESSION_STATE)
        val now = clock.now()
        if (now < 0L) {
            return stepFailure(ExecutionControllerError.INVALID_TIMESTAMP)
        }

        val completion = StepRoundResult(
            outcome = ExecutionStepOutcome.SKIPPED,
            startedAt = roundStart,
            endedAt = now
        )
        return try {
            val next = session.skipCurrentRound(completion)
            session = next
            revisionValue += 1L
            StepActionResult.Skipped(session = next, revision = revisionValue)
        } catch (error: ExecutionValidationException) {
            stepFailure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        } catch (error: ExecutionSchedulingException) {
            stepFailure(
                if (error.code == SchedulingError.INVALID_RESULT_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
    }

    fun pause(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        when (session.status) {
            ExecutionStatus.RUNNING -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_RUNNING)
        }

        return runTransition {
            session.pause()
        }
    }

    fun resume(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        when (session.status) {
            ExecutionStatus.PAUSED -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_PAUSED)
        }

        return runTransition {
            session.resume()
        }
    }

    fun cancel(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        val now = clock.now()
        if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
        return runTransition {
            session.cancel(now)
        }
    }

    private fun staleOrNull(expectedRevision: Long): ExecutionControllerResult.Failure? =
        if (expectedRevision != revisionValue) {
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
            clock: ExecutionClock = SystemExecutionClock
        ): ExecutionControllerCreateResult = try {
            val initial = ExecutionSession.create(
                routine = routine,
                sessionId = sessionId
            )
            ExecutionControllerCreateResult.Success(
                ExecutionController(clock = clock, initialState = initial)
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
