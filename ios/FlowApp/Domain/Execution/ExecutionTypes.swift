import Foundation

enum ExecutionStatus: String, Equatable {
    case ready = "READY"
    case running = "RUNNING"
    case paused = "PAUSED"
    case completed = "COMPLETED"
    case cancelled = "CANCELLED"
}

enum ExecutionStepOutcome: String, Equatable {
    case completed = "COMPLETED"
    case skipped = "SKIPPED"
}

enum TimerStatus: String, Equatable {
    case idle = "IDLE"
    case running = "RUNNING"
    case paused = "PAUSED"
    case completed = "COMPLETED"
}

enum ExecutionValidationError: Error, Equatable {
    case emptySteps
    case invalidRoutineRepeat
    case invalidStepRepeat
    case invalidRoutineIteration
    case invalidStepIndex
    case stepIDMismatch
    case invalidStepIteration
    case invalidCount
    case countExceedsTarget
    case invalidCompleteConfirmed
    case invalidTimerState
    case invalidRecord
    case duplicateRecord
    case invalidStatus
    case incompleteResults
    case invalidTimestamp
    case terminalSession
}

struct ExecutionProgress: Equatable {
    let routineIteration: Int
    let currentStepIndex: Int
    let currentStepID: String
    let stepIteration: Int
    let currentCount: Int
    let completeConfirmed: Bool

    init(
        routineIteration: Int,
        currentStepIndex: Int,
        currentStepID: String,
        stepIteration: Int,
        currentCount: Int = 0,
        completeConfirmed: Bool = false
    ) {
        self.routineIteration = routineIteration
        self.currentStepIndex = currentStepIndex
        self.currentStepID = currentStepID
        self.stepIteration = stepIteration
        self.currentCount = currentCount
        self.completeConfirmed = completeConfirmed
    }
}

struct TimerState: Equatable {
    let totalDurationMs: Int64
    let remainingDurationMs: Int64
    let deadline: Int64?
    let timerStatus: TimerStatus
}

struct ExecutionStepRecord: Equatable {
    let stepID: String
    let routineIteration: Int
    let stepIteration: Int
    let outcome: ExecutionStepOutcome
    let startedAt: Int64
    let endedAt: Int64
}
