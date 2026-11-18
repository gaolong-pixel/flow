import Foundation

struct ExecutionSession {
    let sessionID: String
    let routineSnapshot: Routine
    let status: ExecutionStatus
    let progress: ExecutionProgress
    let timerState: TimerState
    let records: [ExecutionStepRecord]
    let startedAt: Int64?
    let completedAt: Int64?
    let cancelledAt: Int64?

    var routineID: String {
        routineSnapshot.id
    }

    var completedCount: Int {
        records.filter { $0.outcome == .completed }.count
    }

    var skippedCount: Int {
        records.filter { $0.outcome == .skipped }.count
    }

    init(
        sessionID: String,
        routineSnapshot: Routine,
        status: ExecutionStatus,
        progress: ExecutionProgress,
        timerState: TimerState,
        records: [ExecutionStepRecord],
        startedAt: Int64? = nil,
        completedAt: Int64? = nil,
        cancelledAt: Int64? = nil
    ) throws {
        self.sessionID = sessionID
        self.routineSnapshot = routineSnapshot
        self.status = status
        self.progress = progress
        self.timerState = timerState
        self.records = records
        self.startedAt = startedAt
        self.completedAt = completedAt
        self.cancelledAt = cancelledAt
        try validate()
    }

    func start(at: Int64) throws -> ExecutionSession {
        try requireStatus(.ready)
        return try copy(status: .running, startedAt: at)
    }

    func pause() throws -> ExecutionSession {
        try requireStatus(.running)
        return try copy(status: .paused)
    }

    func resume() throws -> ExecutionSession {
        try requireStatus(.paused)
        return try copy(status: .running)
    }

    /// Low-level state operation for tests and internal model composition.
    /// It appends a record without advancing progress and is not a scheduling
    /// entry point. Normal execution must use `commitStepResult`.
    func appendRecord(_ record: ExecutionStepRecord) throws -> ExecutionSession {
        try rejectTerminal()
        try requireStatus(.running, .paused)
        if records.contains(where: { $0.sameIteration(as: record) }) {
            throw ExecutionValidationError.duplicateRecord
        }
        return try copy(records: records + [record])
    }

    func cancel(at: Int64) throws -> ExecutionSession {
        try rejectTerminal()
        return try copy(status: .cancelled, cancelledAt: at)
    }

    /// Low-level terminal operation for tests and internal model composition.
    /// Normal execution must transition through `commitStepResult`.
    func complete(at: Int64) throws -> ExecutionSession {
        try rejectTerminal()
        try requireStatus(.running, .paused)
        return try copy(status: .completed, completedAt: at)
    }

    private func copy(
        status: ExecutionStatus? = nil,
        progress: ExecutionProgress? = nil,
        timerState: TimerState? = nil,
        records: [ExecutionStepRecord]? = nil,
        startedAt: Int64? = nil,
        completedAt: Int64? = nil,
        cancelledAt: Int64? = nil
    ) throws -> ExecutionSession {
        try ExecutionSession(
            sessionID: sessionID,
            routineSnapshot: routineSnapshot,
            status: status ?? self.status,
            progress: progress ?? self.progress,
            timerState: timerState ?? self.timerState,
            records: records ?? self.records,
            startedAt: startedAt ?? self.startedAt,
            completedAt: completedAt ?? self.completedAt,
            cancelledAt: cancelledAt ?? self.cancelledAt
        )
    }

    private func requireStatus(_ allowed: ExecutionStatus...) throws {
        if !allowed.contains(status) {
            throw ExecutionValidationError.invalidStatus
        }
    }

    private func rejectTerminal() throws {
        if status == .completed || status == .cancelled {
            throw ExecutionValidationError.terminalSession
        }
    }

    private func validate() throws {
        let steps = routineSnapshot.steps
        if steps.isEmpty {
            throw ExecutionValidationError.emptySteps
        }
        if routineSnapshot.repeatCount < 1 {
            throw ExecutionValidationError.invalidRoutineRepeat
        }
        if steps.contains(where: { $0.repeatCount < 1 }) {
            throw ExecutionValidationError.invalidStepRepeat
        }

        try validateProgress(steps: steps)
        try validateTimerState(steps: steps)
        try validateRecords(steps: steps)
        try validateStatusAndTimestamps()
        try validateCompletedResults()
    }

    private func validateProgress(steps: [RoutineStep]) throws {
        if !(1...routineSnapshot.repeatCount).contains(progress.routineIteration) {
            throw ExecutionValidationError.invalidRoutineIteration
        }
        if !steps.indices.contains(progress.currentStepIndex) {
            throw ExecutionValidationError.invalidStepIndex
        }

        let step = steps[progress.currentStepIndex]
        if progress.currentStepID != step.id {
            throw ExecutionValidationError.stepIDMismatch
        }
        if !(1...step.repeatCount).contains(progress.stepIteration) {
            throw ExecutionValidationError.invalidStepIteration
        }

        switch step.type {
        case .count:
            guard let target = step.target else {
                throw ExecutionValidationError.invalidCount
            }
            if target <= 0 {
                throw ExecutionValidationError.invalidCount
            }
            if progress.currentCount < 0 {
                throw ExecutionValidationError.invalidCount
            }
            if progress.currentCount > target {
                throw ExecutionValidationError.countExceedsTarget
            }
            if progress.completeConfirmed && step.countMode != .complete {
                throw ExecutionValidationError.invalidCompleteConfirmed
            }
        case .timer:
            if progress.currentCount != 0 || progress.completeConfirmed {
                throw ExecutionValidationError.invalidCount
            }
        }
    }

    private func validateTimerState(steps: [RoutineStep]) throws {
        let step = steps[progress.currentStepIndex]
        let timer = timerState

        if timer.totalDurationMs < 0 ||
            timer.remainingDurationMs < 0 ||
            timer.remainingDurationMs > timer.totalDurationMs
        {
            throw ExecutionValidationError.invalidTimerState
        }
        if let deadline = timer.deadline, deadline < 0 {
            throw ExecutionValidationError.invalidTimerState
        }

        if status == .ready && timer.timerStatus != .idle {
            throw ExecutionValidationError.invalidTimerState
        }

        switch timer.timerStatus {
        case .idle:
            if timer.deadline != nil ||
                timer.remainingDurationMs != timer.totalDurationMs
            {
                throw ExecutionValidationError.invalidTimerState
            }
        case .running:
            if timer.deadline == nil ||
                (timer.deadline ?? 0) <= 0 ||
                timer.remainingDurationMs <= 0
            {
                throw ExecutionValidationError.invalidTimerState
            }
        case .paused:
            if timer.deadline != nil || timer.remainingDurationMs <= 0 {
                throw ExecutionValidationError.invalidTimerState
            }
        case .completed:
            if timer.deadline != nil || timer.remainingDurationMs != 0 {
                throw ExecutionValidationError.invalidTimerState
            }
        }

        switch step.type {
        case .count:
            if timer.totalDurationMs != 0 ||
                timer.remainingDurationMs != 0 ||
                timer.deadline != nil ||
                timer.timerStatus != .idle
            {
                throw ExecutionValidationError.invalidTimerState
            }
        case .timer:
            if timer.totalDurationMs != (step.durationMs ?? -1) ||
                timer.totalDurationMs <= 0
            {
                throw ExecutionValidationError.invalidTimerState
            }
        }
    }

    private func validateRecords(steps: [RoutineStep]) throws {
        let stepsByID = Dictionary(uniqueKeysWithValues: steps.map { ($0.id, $0) })
        var seen = Set<StepIterationKey>()

        for record in records {
            guard let step = stepsByID[record.stepID] else {
                throw ExecutionValidationError.invalidRecord
            }
            if !(1...routineSnapshot.repeatCount).contains(record.routineIteration) ||
                !(1...step.repeatCount).contains(record.stepIteration)
            {
                throw ExecutionValidationError.invalidRecord
            }
            if record.startedAt < 0 || record.endedAt < record.startedAt {
                throw ExecutionValidationError.invalidRecord
            }
            let key = StepIterationKey(record)
            if seen.contains(key) {
                throw ExecutionValidationError.duplicateRecord
            }
            seen.insert(key)
        }

        if status == .ready && !records.isEmpty {
            throw ExecutionValidationError.invalidStatus
        }
    }

    private func validateStatusAndTimestamps() throws {
        if let startedAt = startedAt, startedAt < 0 {
            throw ExecutionValidationError.invalidTimestamp
        }
        if let completedAt = completedAt, completedAt < 0 {
            throw ExecutionValidationError.invalidTimestamp
        }
        if let cancelledAt = cancelledAt, cancelledAt < 0 {
            throw ExecutionValidationError.invalidTimestamp
        }

        switch status {
        case .ready:
            if startedAt != nil || completedAt != nil || cancelledAt != nil {
                throw ExecutionValidationError.invalidTimestamp
            }
        case .running, .paused:
            if startedAt == nil || completedAt != nil || cancelledAt != nil {
                throw ExecutionValidationError.invalidTimestamp
            }
        case .completed:
            guard let startedAt, let completedAt else {
                throw ExecutionValidationError.invalidTimestamp
            }
            if cancelledAt != nil || completedAt < startedAt {
                throw ExecutionValidationError.invalidTimestamp
            }
        case .cancelled:
            guard let cancelledAt else {
                throw ExecutionValidationError.invalidTimestamp
            }
            if completedAt != nil {
                throw ExecutionValidationError.invalidTimestamp
            }
            if let startedAt, cancelledAt < startedAt {
                throw ExecutionValidationError.invalidTimestamp
            }
        }
    }

    private func validateCompletedResults() throws {
        guard status == .completed else { return }
        let expected = Int64(routineSnapshot.repeatCount) *
            routineSnapshot.steps.reduce(0) { $0 + Int64($1.repeatCount) }
        if Int64(records.count) != expected {
            throw ExecutionValidationError.incompleteResults
        }
    }

    private struct StepIterationKey: Hashable {
        let stepID: String
        let routineIteration: Int
        let stepIteration: Int

        init(_ record: ExecutionStepRecord) {
            stepID = record.stepID
            routineIteration = record.routineIteration
            stepIteration = record.stepIteration
        }
    }

    static func create(
        routine: Routine,
        sessionID: String = UUID().uuidString
    ) throws -> ExecutionSession {
        if routine.steps.isEmpty {
            throw ExecutionValidationError.emptySteps
        }

        let snapshot = snapshot(of: routine)
        let firstStep = snapshot.steps[0]
        return try ExecutionSession(
            sessionID: sessionID,
            routineSnapshot: snapshot,
            status: .ready,
            progress: ExecutionProgress(
                routineIteration: 1,
                currentStepIndex: 0,
                currentStepID: firstStep.id,
                stepIteration: 1
            ),
            timerState: initialTimerState(for: firstStep),
            records: []
        )
    }

    private static func snapshot(of routine: Routine) -> Routine {
        Routine(
            id: routine.id,
            name: routine.name,
            version: routine.version,
            repeatCount: routine.repeatCount,
            steps: routine.steps,
            createdAt: routine.createdAt,
            updatedAt: routine.updatedAt
        )
    }

    private static func initialTimerState(for step: RoutineStep) -> TimerState {
        switch step.type {
        case .timer:
            let duration = step.durationMs ?? 0
            return TimerState(
                totalDurationMs: duration,
                remainingDurationMs: duration,
                deadline: nil,
                timerStatus: .idle
            )
        case .count:
            return TimerState(
                totalDurationMs: 0,
                remainingDurationMs: 0,
                deadline: nil,
                timerStatus: .idle
            )
        }
    }
}

private extension ExecutionStepRecord {
    func sameIteration(as other: ExecutionStepRecord) -> Bool {
        stepID == other.stepID &&
            routineIteration == other.routineIteration &&
            stepIteration == other.stepIteration
    }
}


extension ExecutionSession {
    /// Atomically commits the result for the current scheduled round.
    ///
    /// The position is derived exclusively from this session's snapshot and
    /// progress. The scheduler is recomputed here; no externally supplied
    /// SchedulePlan is trusted. Records, progress, and a possible terminal
    /// status are committed by one new validated ExecutionSession.
    ///
    /// This method does not validate COUNT target completion, COMPLETE
    /// confirmation, or TIMER completion. Only a verified execution control
    /// layer may call it; UI code must not construct StepRoundResult and submit
    /// it directly.
    func commitStepResult(_ result: StepRoundResult) throws -> ExecutionSession {
        let plan = try ExecutionScheduler.planNext(session: self, result: result)
        let nextStatus: ExecutionStatus = plan.kind == .complete ? .completed : .running
        let nextProgress = plan.nextProgress ?? progress
        let nextTimerState = plan.nextProgress.map {
            initialTimerStateForStep(routineSnapshot.steps[$0.currentStepIndex])
        } ?? timerState
        let completedAt = plan.kind == .complete ? plan.record.endedAt : nil
        return try ExecutionSession(
            sessionID: sessionID,
            routineSnapshot: routineSnapshot,
            status: nextStatus,
            progress: nextProgress,
            timerState: nextTimerState,
            records: records + [plan.record],
            startedAt: startedAt,
            completedAt: completedAt,
            cancelledAt: nil
        )
    }
}
