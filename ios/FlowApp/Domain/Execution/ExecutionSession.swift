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
    let currentRoundStartedAt: Int64?

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
        cancelledAt: Int64? = nil,
        currentRoundStartedAt: Int64? = nil
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
        self.currentRoundStartedAt = currentRoundStartedAt
        try validate()
    }

    func start(at: Int64) throws -> ExecutionSession {
        try requireStatus(.ready)
        return try copy(
            status: .running,
            startedAt: at,
            currentRoundStartedAt: at
        )
    }

    func pause() throws -> ExecutionSession {
        try requireStatus(.running)
        if currentStepIsTimer() && timerState.timerStatus == .running {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(status: .paused)
    }

    func resume() throws -> ExecutionSession {
        try requireStatus(.paused)
        if currentStepIsTimer() && timerState.timerStatus == .paused {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(status: .running)
    }

    /// Starts the current TIMER without changing progress or records.
    /// Deadline is monotonic milliseconds, never Unix epoch milliseconds.
    func startTimer(deadlineMonotonicMs: Int64) throws -> ExecutionSession {
        try requireStatus(.running)
        try requireCurrentTimer()
        guard timerState.timerStatus == .idle, deadlineMonotonicMs > 0 else {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(
            timerState: TimerState(
                totalDurationMs: timerState.totalDurationMs,
                remainingDurationMs: timerState.totalDurationMs,
                deadline: deadlineMonotonicMs,
                timerStatus: .running
            )
        )
    }

    /// Pauses the current RUNNING TIMER and records its remaining duration.
    func pauseTimer(remainingDurationMs: Int64) throws -> ExecutionSession {
        try requireStatus(.running)
        try requireCurrentTimer()
        guard timerState.timerStatus == .running,
              remainingDurationMs > 0,
              remainingDurationMs <= timerState.totalDurationMs else {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(
            status: .paused,
            timerState: TimerState(
                totalDurationMs: timerState.totalDurationMs,
                remainingDurationMs: remainingDurationMs,
                deadline: nil,
                timerStatus: .paused
            )
        )
    }

    /// Resumes the current PAUSED TIMER using a new monotonic deadline.
    func resumeTimer(deadlineMonotonicMs: Int64) throws -> ExecutionSession {
        try requireStatus(.paused)
        try requireCurrentTimer()
        guard timerState.timerStatus == .paused, deadlineMonotonicMs > 0 else {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(
            status: .running,
            timerState: TimerState(
                totalDurationMs: timerState.totalDurationMs,
                remainingDurationMs: timerState.remainingDurationMs,
                deadline: deadlineMonotonicMs,
                timerStatus: .running
            )
        )
    }

    /// Clears the active TIMER state to IDLE before committing a SKIPPED round.
    /// No timer completion is implied.
    func clearTimerForSkip() throws -> ExecutionSession {
        try requireStatus(.running)
        try requireCurrentTimer()
        guard timerState.timerStatus == .running ||
            timerState.timerStatus == .paused else {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(
            timerState: TimerState(
                totalDurationMs: timerState.totalDurationMs,
                remainingDurationMs: timerState.totalDurationMs,
                deadline: nil,
                timerStatus: .idle
            )
        )
    }

    /// Marks the current RUNNING TIMER completed without advancing the step.
    /// The caller must subsequently commit the round result through the Scheduler.
    func completeTimer() throws -> ExecutionSession {
        try requireStatus(.running)
        try requireCurrentTimer()
        guard timerState.timerStatus == .running else {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(
            timerState: TimerState(
                totalDurationMs: timerState.totalDurationMs,
                remainingDurationMs: 0,
                deadline: nil,
                timerStatus: .completed
            )
        )
    }

    /// Atomically increments the current COUNT/INCREMENT round.
    ///
    /// Below target this returns a new Session with only currentCount changed.
    /// At target it builds an internal target-count candidate and calls
    /// `commitStepResult`; the intermediate target-count Session is never
    /// returned. This method is only for the verified execution controller.
    func incrementCountAndMaybeComplete(
        _ completion: StepRoundResult
    ) throws -> ExecutionSession {
        try rejectTerminal()
        try requireStatus(.running)

        let step = routineSnapshot.steps[progress.currentStepIndex]
        guard step.type == .count, step.countMode == .increment else {
            throw ExecutionValidationError.invalidStatus
        }
        guard let target = step.target, target > 0 else {
            throw ExecutionValidationError.invalidCount
        }
        let current = progress.currentCount
        guard current >= 0, current <= target else {
            throw ExecutionValidationError.invalidCount
        }
        guard current < target else {
            throw ExecutionValidationError.invalidCount
        }

        let newCount = current + 1
        if newCount < target {
            return try copy(
                progress: ExecutionProgress(
                    routineIteration: progress.routineIteration,
                    currentStepIndex: progress.currentStepIndex,
                    currentStepID: progress.currentStepID,
                    stepIteration: progress.stepIteration,
                    currentCount: newCount,
                    completeConfirmed: progress.completeConfirmed
                )
            )
        }

        let targetSession = try copy(
            progress: ExecutionProgress(
                routineIteration: progress.routineIteration,
                currentStepIndex: progress.currentStepIndex,
                currentStepID: progress.currentStepID,
                stepIteration: progress.stepIteration,
                currentCount: target,
                completeConfirmed: progress.completeConfirmed
            )
        )
        return try targetSession.commitStepResult(completion)
    }

    /// Atomically confirms the current COUNT + COMPLETE round.
    ///
    /// The completeConfirmed candidate is internal and never returned directly;
    /// only the final Session produced by `commitStepResult` is exposed.
    func confirmComplete(_ completion: StepRoundResult) throws -> ExecutionSession {
        try rejectTerminal()
        try requireStatus(.running)
        guard completion.outcome == .completed else {
            throw ExecutionValidationError.invalidRecord
        }

        let step = routineSnapshot.steps[progress.currentStepIndex]
        guard step.type == .count, step.countMode == .complete else {
            throw ExecutionValidationError.invalidStatus
        }
        guard !progress.completeConfirmed else {
            throw ExecutionValidationError.invalidCompleteConfirmed
        }

        let confirmed = try copy(
            progress: ExecutionProgress(
                routineIteration: progress.routineIteration,
                currentStepIndex: progress.currentStepIndex,
                currentStepID: progress.currentStepID,
                stepIteration: progress.stepIteration,
                currentCount: progress.currentCount,
                completeConfirmed: true
            )
        )
        return try confirmed.commitStepResult(completion)
    }

    /// Atomically skips the current round for any step type.
    func skipCurrentRound(_ completion: StepRoundResult) throws -> ExecutionSession {
        try rejectTerminal()
        try requireStatus(.running)
        guard completion.outcome == .skipped else {
            throw ExecutionValidationError.invalidRecord
        }
        return try commitStepResult(completion)
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
        if currentStepIsTimer() &&
            (timerState.timerStatus == .running || timerState.timerStatus == .paused)
        {
            throw ExecutionValidationError.invalidTimerState
        }
        return try copy(
            status: .cancelled,
            cancelledAt: at,
            clearCurrentRound: true
        )
    }

    /// Cancels the session using a final TimerState already produced by the
    /// Controller/Timer cancellation path. Session never calls an Engine.
    func cancelWithTimerState(
        at: Int64,
        timerState: TimerState
    ) throws -> ExecutionSession {
        try rejectTerminal()
        let step = routineSnapshot.steps[progress.currentStepIndex]
        if step.type == .timer {
            switch self.timerState.timerStatus {
            case .running, .paused:
                guard timerState.timerStatus == .cancelled ||
                    timerState.timerStatus == .completed else {
                    throw ExecutionValidationError.invalidTimerState
                }
            case .idle:
                guard timerState.timerStatus == .idle else {
                    throw ExecutionValidationError.invalidTimerState
                }
            case .completed:
                guard timerState.timerStatus == .completed else {
                    throw ExecutionValidationError.invalidTimerState
                }
            case .cancelled:
                guard timerState.timerStatus == .cancelled else {
                    throw ExecutionValidationError.invalidTimerState
                }
            }
        }
        return try copy(
            status: .cancelled,
            timerState: timerState,
            cancelledAt: at,
            clearCurrentRound: true
        )
    }

    /// Low-level terminal operation for tests and internal model composition.
    /// Normal execution must transition through `commitStepResult`.
    func complete(at: Int64) throws -> ExecutionSession {
        try rejectTerminal()
        try requireStatus(.running, .paused)
        return try copy(
            status: .completed,
            completedAt: at,
            clearCurrentRound: true
        )
    }

    private func copy(
        status: ExecutionStatus? = nil,
        progress: ExecutionProgress? = nil,
        timerState: TimerState? = nil,
        records: [ExecutionStepRecord]? = nil,
        startedAt: Int64? = nil,
        completedAt: Int64? = nil,
        cancelledAt: Int64? = nil,
        currentRoundStartedAt: Int64? = nil,
        clearCurrentRound: Bool = false
    ) throws -> ExecutionSession {
        let roundStart = clearCurrentRound
            ? nil
            : (currentRoundStartedAt ?? self.currentRoundStartedAt)
        return try ExecutionSession(
            sessionID: sessionID,
            routineSnapshot: routineSnapshot,
            status: status ?? self.status,
            progress: progress ?? self.progress,
            timerState: timerState ?? self.timerState,
            records: records ?? self.records,
            startedAt: startedAt ?? self.startedAt,
            completedAt: completedAt ?? self.completedAt,
            cancelledAt: cancelledAt ?? self.cancelledAt,
            currentRoundStartedAt: roundStart
        )
    }

    private func currentStepIsTimer() -> Bool {
        routineSnapshot.steps[progress.currentStepIndex].type == .timer
    }

    private func requireCurrentTimer() throws {
        let step = routineSnapshot.steps[progress.currentStepIndex]
        guard step.type == .timer, timerState.totalDurationMs > 0 else {
            throw ExecutionValidationError.invalidTimerState
        }
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
        switch status {
        case .running:
            if timer.timerStatus == .paused || timer.timerStatus == .cancelled {
                throw ExecutionValidationError.invalidTimerState
            }
        case .paused:
            if timer.timerStatus != .idle && timer.timerStatus != .paused {
                throw ExecutionValidationError.invalidTimerState
            }
        case .completed, .cancelled:
            if timer.timerStatus == .running {
                throw ExecutionValidationError.invalidTimerState
            }
        case .ready:
            break
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
        case .cancelled:
            if timer.deadline != nil {
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
        if let currentRoundStartedAt = currentRoundStartedAt, currentRoundStartedAt < 0 {
            throw ExecutionValidationError.invalidTimestamp
        }

        switch status {
        case .ready:
            if startedAt != nil || completedAt != nil || cancelledAt != nil {
                throw ExecutionValidationError.invalidTimestamp
            }
            if currentRoundStartedAt != nil {
                throw ExecutionValidationError.invalidTimestamp
            }
        case .running, .paused:
            guard let startedAt, let currentRoundStartedAt else {
                throw ExecutionValidationError.invalidTimestamp
            }
            if completedAt != nil || cancelledAt != nil ||
                currentRoundStartedAt < startedAt
            {
                throw ExecutionValidationError.invalidTimestamp
            }
        case .completed:
            guard let startedAt, let completedAt else {
                throw ExecutionValidationError.invalidTimestamp
            }
            if cancelledAt != nil || completedAt < startedAt ||
                currentRoundStartedAt != nil
            {
                throw ExecutionValidationError.invalidTimestamp
            }
        case .cancelled:
            guard let cancelledAt else {
                throw ExecutionValidationError.invalidTimestamp
            }
            if completedAt != nil || currentRoundStartedAt != nil {
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
        let currentRoundStartedAt = plan.nextProgress != nil ? plan.record.endedAt : nil
        return try ExecutionSession(
            sessionID: sessionID,
            routineSnapshot: routineSnapshot,
            status: nextStatus,
            progress: nextProgress,
            timerState: nextTimerState,
            records: records + [plan.record],
            startedAt: startedAt,
            completedAt: completedAt,
            cancelledAt: nil,
            currentRoundStartedAt: currentRoundStartedAt
        )
    }
}
