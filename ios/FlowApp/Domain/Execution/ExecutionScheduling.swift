import Foundation

enum ScheduleKind: String, Equatable {
    case nextStepRound = "NEXT_STEP_ROUND"
    case nextStep = "NEXT_STEP"
    case nextRoutine = "NEXT_ROUTINE"
    case complete = "COMPLETE"
}

enum SchedulingError: Error, Equatable {
    case sessionNotRunning
    case recordsNotPrefix
    case currentRoundAlreadyRecorded
    case progressMismatch
    case noRemainingIterations
    case invalidResultTimestamp
}

/// Result of the current round supplied by the verified execution control layer.
///
/// The scheduler validates ordering and timestamps only. It does not verify
/// COUNT target completion, COMPLETE confirmation, or TIMER completion.
/// UI code must never construct this type and submit it directly.
struct StepRoundResult: Equatable {
    let outcome: ExecutionStepOutcome
    let startedAt: Int64
    let endedAt: Int64
}

enum SchedulePlanValidationError: Error, Equatable {
    case invalidCompleteness
}

struct SchedulePlan: Equatable {
    let kind: ScheduleKind
    let record: ExecutionStepRecord
    let nextProgress: ExecutionProgress?

    init(
        kind: ScheduleKind,
        record: ExecutionStepRecord,
        nextProgress: ExecutionProgress?
    ) throws {
        guard (kind == .complete) == (nextProgress == nil) else {
            throw SchedulePlanValidationError.invalidCompleteness
        }
        self.kind = kind
        self.record = record
        self.nextProgress = nextProgress
    }
}

struct CanonicalStepIteration: Equatable {
    let stepIndex: Int
    let stepID: String
    let routineIteration: Int
    let stepIteration: Int
}

func initialTimerStateForStep(_ step: RoutineStep) -> TimerState {
    switch step.type {
    case .count:
        return TimerState(
            totalDurationMs: 0,
            remainingDurationMs: 0,
            deadline: nil,
            timerStatus: .idle
        )
    case .timer:
        let duration = step.durationMs ?? 0
        return TimerState(
            totalDurationMs: duration,
            remainingDurationMs: duration,
            deadline: nil,
            timerStatus: .idle
        )
    }
}

enum ExecutionScheduler {
    /// Pure scheduling preview. This does not validate type-specific execution
    /// completion; that belongs to the execution control layer.
    static func planNext(
        session: ExecutionSession,
        result: StepRoundResult
    ) throws -> SchedulePlan {
        guard session.status == .running || session.status == .paused else {
            throw SchedulingError.sessionNotRunning
        }

        let canonical = canonicalStepIterations(session: session)
        try validateOrderedState(session: session, canonical: canonical)

        guard session.status == .running else {
            throw SchedulingError.sessionNotRunning
        }

        guard let sessionStart = session.startedAt else {
            throw SchedulingError.sessionNotRunning
        }
        if result.startedAt < sessionStart || result.endedAt < result.startedAt {
            throw SchedulingError.invalidResultTimestamp
        }

        if session.records.count == canonical.count {
            throw SchedulingError.noRemainingIterations
        }

        let current = canonical[session.records.count]
        let step = session.routineSnapshot.steps[current.stepIndex]
        let record = ExecutionStepRecord(
            stepID: current.stepID,
            routineIteration: current.routineIteration,
            stepIteration: current.stepIteration,
            outcome: result.outcome,
            startedAt: result.startedAt,
            endedAt: result.endedAt
        )

        let (kind, nextProgress) = nextPosition(session: session, step: step)
        return try SchedulePlan(kind: kind, record: record, nextProgress: nextProgress)
    }

    private static func validateOrderedState(
        session: ExecutionSession,
        canonical: [CanonicalStepIteration]
    ) throws {
        let records = session.records
        if records.count > canonical.count {
            throw SchedulingError.recordsNotPrefix
        }
        for (index, record) in records.enumerated() {
            if !record.matches(canonical[index]) {
                throw SchedulingError.recordsNotPrefix
            }
        }

        let progress = session.progress
        if records.count < canonical.count {
            if records.contains(where: { $0.matches(progress) }) {
                throw SchedulingError.currentRoundAlreadyRecorded
            }
            let expected = canonical[records.count]
            if !progress.matches(expected) {
                throw SchedulingError.progressMismatch
            }
        } else if !progress.matches(canonical[canonical.count - 1]) {
            throw SchedulingError.progressMismatch
        }
    }

    private static func nextPosition(
        session: ExecutionSession,
        step: RoutineStep
    ) -> (ScheduleKind, ExecutionProgress?) {
        let progress = session.progress
        let snapshot = session.routineSnapshot

        if progress.stepIteration < step.repeatCount {
            return (
                .nextStepRound,
                ExecutionProgress(
                    routineIteration: progress.routineIteration,
                    currentStepIndex: progress.currentStepIndex,
                    currentStepID: progress.currentStepID,
                    stepIteration: progress.stepIteration + 1
                )
            )
        }

        if progress.currentStepIndex < snapshot.steps.count - 1 {
            let nextStep = snapshot.steps[progress.currentStepIndex + 1]
            return (
                .nextStep,
                ExecutionProgress(
                    routineIteration: progress.routineIteration,
                    currentStepIndex: progress.currentStepIndex + 1,
                    currentStepID: nextStep.id,
                    stepIteration: 1
                )
            )
        }

        if progress.routineIteration < snapshot.repeatCount {
            let firstStep = snapshot.steps[0]
            return (
                .nextRoutine,
                ExecutionProgress(
                    routineIteration: progress.routineIteration + 1,
                    currentStepIndex: 0,
                    currentStepID: firstStep.id,
                    stepIteration: 1
                )
            )
        }

        return (.complete, nil)
    }

    private static func canonicalStepIterations(
        session: ExecutionSession
    ) -> [CanonicalStepIteration] {
        let snapshot = session.routineSnapshot
        var keys: [CanonicalStepIteration] = []
        for routineIteration in 1...snapshot.repeatCount {
            for (stepIndex, step) in snapshot.steps.enumerated() {
                for stepIteration in 1...step.repeatCount {
                    keys.append(
                        CanonicalStepIteration(
                            stepIndex: stepIndex,
                            stepID: step.id,
                            routineIteration: routineIteration,
                            stepIteration: stepIteration
                        )
                    )
                }
            }
        }
        return keys
    }
}

private extension ExecutionStepRecord {
    func matches(_ iteration: CanonicalStepIteration) -> Bool {
        stepID == iteration.stepID &&
            routineIteration == iteration.routineIteration &&
            stepIteration == iteration.stepIteration
    }

    func matches(_ progress: ExecutionProgress) -> Bool {
        stepID == progress.currentStepID &&
            routineIteration == progress.routineIteration &&
            stepIteration == progress.stepIteration
    }
}


private extension ExecutionProgress {
    func matches(_ iteration: CanonicalStepIteration) -> Bool {
        currentStepIndex == iteration.stepIndex &&
            currentStepID == iteration.stepID &&
            routineIteration == iteration.routineIteration &&
            stepIteration == iteration.stepIteration
    }
}
