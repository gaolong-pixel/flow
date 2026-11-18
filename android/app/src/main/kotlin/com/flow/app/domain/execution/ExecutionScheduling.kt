package com.flow.app.domain.execution

import com.flow.app.domain.model.RoutineStep

enum class ScheduleKind {
    NEXT_STEP_ROUND,
    NEXT_STEP,
    NEXT_ROUTINE,
    COMPLETE
}

enum class SchedulingError {
    SESSION_NOT_RUNNING,
    RECORDS_NOT_PREFIX,
    CURRENT_ROUND_ALREADY_RECORDED,
    PROGRESS_MISMATCH,
    NO_REMAINING_ITERATIONS,
    INVALID_RESULT_TIMESTAMP,
    INVALID_SESSION_STATE
}

class ExecutionSchedulingException(
    val code: SchedulingError,
    message: String = code.name
) : IllegalArgumentException(message)

/**
 * Result of the current round supplied by the verified execution control layer.
 *
 * The scheduler validates ordering and timestamps only. It does not verify
 * COUNT target completion, COMPLETE confirmation, or TIMER completion.
 * UI code must never construct this type and submit it directly.
 */
data class StepRoundResult(
    val outcome: ExecutionStepOutcome,
    val startedAt: Long,
    val endedAt: Long
)

data class SchedulePlan(
    val kind: ScheduleKind,
    val record: ExecutionStepRecord,
    val nextProgress: ExecutionProgress?
) {
    init {
        if ((kind == ScheduleKind.COMPLETE) != (nextProgress == null)) {
            throw IllegalArgumentException(
                "COMPLETE plans must not have nextProgress; other plans must have it."
            )
        }
    }
}

internal data class CanonicalStepIteration(
    val stepIndex: Int,
    val stepId: String,
    val routineIteration: Int,
    val stepIteration: Int
)

internal fun canonicalStepIterations(session: ExecutionSession): List<CanonicalStepIteration> {
    val snapshot = session.routineSnapshot
    return buildList {
        for (routineIteration in 1..snapshot.repeatCount) {
            snapshot.steps.forEachIndexed { stepIndex, step ->
                for (stepIteration in 1..step.repeatCount) {
                    add(
                        CanonicalStepIteration(
                            stepIndex = stepIndex,
                            stepId = step.id,
                            routineIteration = routineIteration,
                            stepIteration = stepIteration
                        )
                    )
                }
            }
        }
    }
}

object ExecutionScheduler {
    /**
     * Pure scheduling preview. This does not validate type-specific execution
     * completion; that belongs to the execution control layer.
     */
    fun planNext(session: ExecutionSession, result: StepRoundResult): SchedulePlan {
        if (session.status != ExecutionStatus.RUNNING &&
            session.status != ExecutionStatus.PAUSED
        ) {
            fail(SchedulingError.SESSION_NOT_RUNNING)
        }

        val canonical = canonicalStepIterations(session)
        validateOrderedState(session, canonical)

        if (session.status != ExecutionStatus.RUNNING) {
            fail(SchedulingError.SESSION_NOT_RUNNING)
        }

        val sessionStart = session.startedAt
            ?: fail(SchedulingError.SESSION_NOT_RUNNING)
        val currentRoundStart = session.currentRoundStartedAt
            ?: fail(SchedulingError.INVALID_SESSION_STATE)
        if (result.startedAt < sessionStart ||
            result.endedAt < result.startedAt ||
            result.startedAt < currentRoundStart ||
            result.endedAt < currentRoundStart
        ) {
            fail(SchedulingError.INVALID_RESULT_TIMESTAMP)
        }

        if (session.records.size == canonical.size) {
            fail(SchedulingError.NO_REMAINING_ITERATIONS)
        }

        val current = canonical[session.records.size]
        val step = session.routineSnapshot.steps[current.stepIndex]
        val record = ExecutionStepRecord(
            stepId = current.stepId,
            routineIteration = current.routineIteration,
            stepIteration = current.stepIteration,
            outcome = result.outcome,
            startedAt = result.startedAt,
            endedAt = result.endedAt
        )

        val (kind, nextProgress) = nextPosition(session, step)
        return SchedulePlan(
            kind = kind,
            record = record,
            nextProgress = nextProgress
        )
    }

    private fun validateOrderedState(
        session: ExecutionSession,
        canonical: List<CanonicalStepIteration>
    ) {
        val records = session.records
        if (records.size > canonical.size) {
            fail(SchedulingError.RECORDS_NOT_PREFIX)
        }
        records.forEachIndexed { index, record ->
            if (!record.matches(canonical[index])) {
                fail(SchedulingError.RECORDS_NOT_PREFIX)
            }
        }

        val progress = session.progress
        if (records.size < canonical.size) {
            if (records.any { it.matches(progress) }) {
                fail(SchedulingError.CURRENT_ROUND_ALREADY_RECORDED)
            }
            val expected = canonical[records.size]
            if (!progress.matches(expected)) {
                fail(SchedulingError.PROGRESS_MISMATCH)
            }
        } else if (!progress.matches(canonical.last())) {
            fail(SchedulingError.PROGRESS_MISMATCH)
        }
    }

    private fun nextPosition(
        session: ExecutionSession,
        step: RoutineStep
    ): Pair<ScheduleKind, ExecutionProgress?> {
        val progress = session.progress
        val snapshot = session.routineSnapshot

        return when {
            progress.stepIteration < step.repeatCount -> {
                ScheduleKind.NEXT_STEP_ROUND to ExecutionProgress(
                    routineIteration = progress.routineIteration,
                    currentStepIndex = progress.currentStepIndex,
                    currentStepId = progress.currentStepId,
                    stepIteration = progress.stepIteration + 1
                )
            }

            progress.currentStepIndex < snapshot.steps.lastIndex -> {
                val nextStep = snapshot.steps[progress.currentStepIndex + 1]
                ScheduleKind.NEXT_STEP to ExecutionProgress(
                    routineIteration = progress.routineIteration,
                    currentStepIndex = progress.currentStepIndex + 1,
                    currentStepId = nextStep.id,
                    stepIteration = 1
                )
            }

            progress.routineIteration < snapshot.repeatCount -> {
                val firstStep = snapshot.steps.first()
                ScheduleKind.NEXT_ROUTINE to ExecutionProgress(
                    routineIteration = progress.routineIteration + 1,
                    currentStepIndex = 0,
                    currentStepId = firstStep.id,
                    stepIteration = 1
                )
            }

            else -> ScheduleKind.COMPLETE to null
        }
    }

    private fun ExecutionStepRecord.matches(iteration: CanonicalStepIteration): Boolean =
        stepId == iteration.stepId &&
            routineIteration == iteration.routineIteration &&
            stepIteration == iteration.stepIteration

    private fun ExecutionStepRecord.matches(progress: ExecutionProgress): Boolean =
        stepId == progress.currentStepId &&
            routineIteration == progress.routineIteration &&
            stepIteration == progress.stepIteration

    private fun ExecutionProgress.matches(iteration: CanonicalStepIteration): Boolean =
        currentStepIndex == iteration.stepIndex &&
            currentStepId == iteration.stepId &&
            routineIteration == iteration.routineIteration &&
            stepIteration == iteration.stepIteration

    private fun fail(code: SchedulingError): Nothing =
        throw ExecutionSchedulingException(code)
}
