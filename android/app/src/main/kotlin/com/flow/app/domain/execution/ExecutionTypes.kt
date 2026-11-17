package com.flow.app.domain.execution

import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType

enum class ExecutionStatus {
    READY,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED
}

enum class ExecutionStepOutcome {
    COMPLETED,
    SKIPPED
}

enum class TimerStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED
}

enum class ExecutionValidationError {
    EMPTY_STEPS,
    INVALID_ROUTINE_REPEAT,
    INVALID_STEP_REPEAT,
    INVALID_ROUTINE_ITERATION,
    INVALID_STEP_INDEX,
    STEP_ID_MISMATCH,
    INVALID_STEP_ITERATION,
    INVALID_COUNT,
    COUNT_EXCEEDS_TARGET,
    INVALID_COMPLETE_CONFIRMED,
    INVALID_TIMER_STATE,
    INVALID_RECORD,
    DUPLICATE_RECORD,
    INVALID_STATUS,
    INCOMPLETE_RESULTS,
    INVALID_TIMESTAMP,
    TERMINAL_SESSION
}

class ExecutionValidationException(
    val code: ExecutionValidationError,
    message: String = code.name
) : IllegalArgumentException(message)

data class ExecutionProgress(
    val routineIteration: Int,
    val currentStepIndex: Int,
    val currentStepId: String,
    val stepIteration: Int,
    val currentCount: Int = 0,
    val completeConfirmed: Boolean = false
)

data class TimerState(
    val totalDurationMs: Long,
    val remainingDurationMs: Long,
    val deadline: Long?,
    val timerStatus: TimerStatus
)

data class ExecutionStepRecord(
    val stepId: String,
    val routineIteration: Int,
    val stepIteration: Int,
    val outcome: ExecutionStepOutcome,
    val startedAt: Long,
    val endedAt: Long
)

internal fun Routine.asExecutionSnapshot(): Routine = Routine(
    id = id,
    name = name,
    version = version,
    repeatCount = repeatCount,
    steps = steps.toList(),
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun initialTimerState(step: RoutineStep): TimerState = when (step.type) {
    StepType.TIMER -> TimerState(
        totalDurationMs = step.durationMs ?: 0L,
        remainingDurationMs = step.durationMs ?: 0L,
        deadline = null,
        timerStatus = TimerStatus.IDLE
    )

    StepType.COUNT -> TimerState(
        totalDurationMs = 0L,
        remainingDurationMs = 0L,
        deadline = null,
        timerStatus = TimerStatus.IDLE
    )
}
