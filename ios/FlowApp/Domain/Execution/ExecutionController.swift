import Foundation

protocol ExecutionClock {
    var now: Int64 { get }
}

struct SystemExecutionClock: ExecutionClock {
    var now: Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}

enum ExecutionControllerError: Error, Equatable {
    case emptyRoutine
    case sessionNotReady
    case sessionNotRunning
    case sessionNotPaused
    case terminalSession
    case staleOperation
    case invalidTimestamp
    case invalidSessionState
    case notCountStep
    case countModeNotIncrement
    case countAlreadyAtTarget
    case invalidCountState
    case countModeNotComplete
    case completeAlreadyConfirmed
    case invalidTimerState
    case revisionOverflow
}

internal struct ActiveTimerStepKey: Equatable {
    let sessionID: String
    let routineIteration: Int
    let stepID: String
    let stepIteration: Int
}

internal struct ActiveTimerReference: Equatable {
    let runId: TimerRunId
    let stepKey: ActiveTimerStepKey
}

struct ExecutionControllerSnapshot {
    let session: ExecutionSession
    let revision: Int64
}

enum ExecutionControllerResult {
    case success(session: ExecutionSession, revision: Int64)
    case failure(error: ExecutionControllerError, revision: Int64)
}

enum ExecutionControllerCreateResult {
    case success(ExecutionController)
    case failure(ExecutionControllerError)
}

enum StepActionResult {
    case completed(session: ExecutionSession, revision: Int64)
    case skipped(session: ExecutionSession, revision: Int64)
    case failure(error: ExecutionControllerError, revision: Int64)
}

enum CountIncrementResult {
    case incremented(
        session: ExecutionSession,
        revision: Int64,
        currentCount: Int,
        target: Int
    )
    case roundCompleted(session: ExecutionSession, revision: Int64)
    case failure(error: ExecutionControllerError, revision: Int64)
}

@MainActor
final class ExecutionController {
    private let clock: ExecutionClock
    private var session: ExecutionSession
    private var revisionValue: Int64
    private let timerEngine: ExecutionTimerEngine
    private var activeTimerReference: ActiveTimerReference?

    private struct TimerStartApplied {
        let session: ExecutionSession
        let reference: ActiveTimerReference?
    }

    private enum TimerStartApplyResult {
        case ready(TimerStartApplied)
        case failure(ExecutionControllerError)
    }

    private struct CompletionApplied {
        let plan: CompletionPlan
        let session: ExecutionSession
        let reference: ActiveTimerReference?
    }

    private enum CompletionApplyResult {
        case ready(CompletionApplied)
        case failure(ExecutionControllerError)
    }

    private struct AdvancedCompletion {
        let session: ExecutionSession
        let nextTimerDurationMs: Int64?
    }

    private enum AdvancedCompletionResult {
        case ready(AdvancedCompletion)
        case failure(ExecutionControllerError)
    }

    internal init(
        clock: ExecutionClock,
        initialState: ExecutionSession,
        initialRevision: Int64 = 0,
        monotonicClock: MonotonicClock = SystemMonotonicClock()
    ) {
        self.clock = clock
        self.session = initialState
        self.revisionValue = initialRevision
        self.timerEngine = ExecutionTimerEngine(clock: monotonicClock)
        self.activeTimerReference = nil
    }

    internal var activeTimerRunId: TimerRunId? {
        activeTimerReference?.runId
    }

    internal var activeTimerStepKey: ActiveTimerStepKey? {
        activeTimerReference?.stepKey
    }

    /// Internal identity check for future Timer operations. This adds no public
    /// timer API and does not mutate the Engine.
    internal func isActiveTimerIdentityValid(
        sessionID: String,
        routineIteration: Int,
        stepID: String,
        stepIteration: Int,
        timerRunId: TimerRunId,
        allowedStatuses: [ExecutionStatus] = [.running, .paused]
    ) -> Bool {
        guard let reference = activeTimerReference else { return false }
        guard allowedStatuses.contains(session.status) else { return false }
        guard session.sessionID == sessionID else { return false }
        guard session.progress.routineIteration == routineIteration else { return false }
        guard session.progress.currentStepID == stepID else { return false }
        guard session.progress.stepIteration == stepIteration else { return false }
        guard let currentKey = currentTimerStepKey() else { return false }
        let requestedKey = ActiveTimerStepKey(
            sessionID: sessionID,
            routineIteration: routineIteration,
            stepID: stepID,
            stepIteration: stepIteration
        )
        return reference.runId == timerRunId &&
            reference.stepKey == currentKey &&
            requestedKey == currentKey
    }

    private func validateActiveTimerReference(
        allowedStatuses: Set<ExecutionStatus>
    ) -> ExecutionControllerError? {
        guard let reference = activeTimerReference else { return .invalidTimerState }
        guard allowedStatuses.contains(session.status) else { return .invalidTimerState }
        guard let currentKey = currentTimerStepKey() else { return .invalidTimerState }
        guard reference.stepKey == currentKey else { return .invalidTimerState }
        guard timerEngine.debugState().runId == reference.runId else {
            return .invalidTimerState
        }
        return nil
    }

    private func revisionOverflowFailure() -> ExecutionControllerResult? {
        guard revisionValue == Int64.max else { return nil }
        return failure(.revisionOverflow)
    }

    internal func currentTimerStepKey() -> ActiveTimerStepKey? {
        timerStepKeyFor(session)
    }

    internal func timerEngineForTesting() -> ExecutionTimerEngine {
        timerEngine
    }

    internal func activeTimerReferenceForTesting() -> ActiveTimerReference? {
        activeTimerReference
    }

    internal func installActiveTimerForTesting(
        runId: TimerRunId,
        stepKey: ActiveTimerStepKey
    ) {
        activeTimerReference = ActiveTimerReference(runId: runId, stepKey: stepKey)
    }

    internal func clearActiveTimerForTesting() {
        activeTimerReference = nil
    }

    func snapshot() -> ExecutionControllerSnapshot {
        ExecutionControllerSnapshot(session: session, revision: revisionValue)
    }

    private func currentStep() -> RoutineStep? {
        let steps = session.routineSnapshot.steps
        guard steps.indices.contains(session.progress.currentStepIndex) else {
            return nil
        }
        return steps[session.progress.currentStepIndex]
    }

    private func timerStepKeyFor(_ session: ExecutionSession) -> ActiveTimerStepKey? {
        let steps = session.routineSnapshot.steps
        guard steps.indices.contains(session.progress.currentStepIndex) else {
            return nil
        }
        let step = steps[session.progress.currentStepIndex]
        guard step.type == .timer else { return nil }
        return ActiveTimerStepKey(
            sessionID: session.sessionID,
            routineIteration: session.progress.routineIteration,
            stepID: session.progress.currentStepID,
            stepIteration: session.progress.stepIteration
        )
    }

    /// Starts the current TIMER on the candidate Session, using an Engine plan.
    /// The Engine commit is the last fallible operation before publication.
    private func applyTimerStart(_ candidate: ExecutionSession) -> TimerStartApplyResult {
        let steps = candidate.routineSnapshot.steps
        guard steps.indices.contains(candidate.progress.currentStepIndex) else {
            return .failure(.invalidTimerState)
        }
        let step = steps[candidate.progress.currentStepIndex]
        guard step.type == .timer else {
            return .ready(TimerStartApplied(session: candidate, reference: nil))
        }
        guard candidate.timerState.timerStatus == .idle,
              activeTimerReference == nil,
              let duration = step.durationMs,
              duration > 0 else {
            return .failure(.invalidTimerState)
        }
        guard let key = timerStepKeyFor(candidate) else {
            return .failure(.invalidTimerState)
        }

        let plan: TimerPlan
        switch timerEngine.prepareStart(totalDurationMs: duration) {
        case let .success(value):
            plan = value
        case let .failure(error):
            return .failure(controllerErrorForTimer(error))
        }
        guard let deadline = plan.deadlineMonotonicMs else {
            return .failure(.invalidTimerState)
        }
        let next: ExecutionSession
        do {
            next = try candidate.startTimer(deadlineMonotonicMs: deadline)
        } catch {
            return .failure(.invalidTimerState)
        }
        let reference = ActiveTimerReference(runId: plan.targetRunId, stepKey: key)
        switch timerEngine.commitStart(plan) {
        case .success:
            break
        case .expired:
            return .failure(.invalidTimestamp)
        case let .failure(error):
            return .failure(controllerErrorForTimer(error))
        }
        return .ready(TimerStartApplied(session: next, reference: reference))
    }

    /// Commits the round result and prepares the completion transaction,
    /// including an optional next TIMER start on the candidate Session. The
    /// returned plan is committed by the caller as the final fallible step.
    private func prepareCompletionTransaction(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        outcome: ExecutionStepOutcome,
        wallNow: Int64
    ) -> CompletionApplyResult {
        guard let roundStart = base.currentRoundStartedAt else {
            return .failure(.invalidSessionState)
        }
        let result = StepRoundResult(
            outcome: outcome,
            startedAt: roundStart,
            endedAt: wallNow
        )
        let advanced: ExecutionSession
        do {
            advanced = try completionSession.commitStepResult(result)
        } catch let error as ExecutionValidationError {
            return .failure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch let error as SchedulingError {
            return .failure(
                error == .invalidResultTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return .failure(.invalidSessionState)
        }

        // A terminal Session has no next round to schedule. Progress keeps the
        // last step index, so the current step must not be treated as "next".
        let nextStep = advanced.status != .completed
            ? step(in: advanced, at: advanced.progress.currentStepIndex)
            : nil
        let nextDuration = nextStep?.type == .timer ? nextStep?.durationMs : nil
        if nextStep?.type == .timer && (nextDuration == nil || (nextDuration ?? 0) <= 0) {
            return .failure(.invalidTimerState)
        }

        let plan: CompletionPlan
        switch timerEngine.prepareCompletion(nextTimerDurationMs: nextDuration) {
        case let .ready(value):
            guard let completionPlan = value as? CompletionPlan else {
                return .failure(.invalidSessionState)
            }
            plan = completionPlan
        case let .failure(error):
            return .failure(controllerErrorForTimer(error))
        case .expired, .noChange:
            return .failure(.invalidSessionState)
        }

        if nextStep?.type == .timer {
            guard let deadline = plan.nextDeadlineMonotonicMs else {
                return .failure(.invalidTimerState)
            }
            let final: ExecutionSession
            do {
                final = try advanced.startTimer(deadlineMonotonicMs: deadline)
            } catch {
                return .failure(.invalidTimerState)
            }
            guard let key = timerStepKeyFor(final),
                  let runId = plan.nextRunId else {
                return .failure(.invalidTimerState)
            }
            return .ready(
                CompletionApplied(
                    plan: plan,
                    session: final,
                    reference: ActiveTimerReference(runId: runId, stepKey: key)
                )
            )
        }
        return .ready(
            CompletionApplied(plan: plan, session: advanced, reference: nil)
        )
    }

    /// Builds the advanced Session after timer completion without touching the
    /// Engine. TIMER steps carry their own duration validation.
    private func buildAdvancedCompletion(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        outcome: ExecutionStepOutcome,
        wallNow: Int64
    ) -> AdvancedCompletionResult {
        guard let roundStart = base.currentRoundStartedAt else {
            return .failure(.invalidSessionState)
        }
        let result = StepRoundResult(
            outcome: outcome,
            startedAt: roundStart,
            endedAt: wallNow
        )
        let advanced: ExecutionSession
        do {
            advanced = try completionSession.commitStepResult(result)
        } catch let error as ExecutionValidationError {
            return .failure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch let error as SchedulingError {
            return .failure(
                error == .invalidResultTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return .failure(.invalidSessionState)
        }

        // A terminal Session has no next round to schedule. Progress keeps the
        // last step index, so the current step must not be treated as "next".
        let nextStep = advanced.status != .completed
            ? step(in: advanced, at: advanced.progress.currentStepIndex)
            : nil
        let duration = nextStep?.type == .timer ? nextStep?.durationMs : nil
        if nextStep?.type == .timer && (duration == nil || (duration ?? 0) <= 0) {
            return .failure(.invalidTimerState)
        }
        return .ready(
            AdvancedCompletion(session: advanced, nextTimerDurationMs: duration)
        )
    }

    private func step(
        in session: ExecutionSession,
        at index: Int
    ) -> RoutineStep? {
        let steps = session.routineSnapshot.steps
        guard steps.indices.contains(index) else { return nil }
        return steps[index]
    }

    private func controllerErrorForTimer(
        _ error: TimerEngineError
    ) -> ExecutionControllerError {
        switch error {
        case .planExpired, .clockRegression, .invalidClock:
            return .invalidTimestamp
        default:
            return .invalidSessionState
        }
    }

    /// Atomically starts the current TIMER using an Engine plan. The Engine
    /// commit is deliberately the last fallible step before publication.
    internal func startCurrentTimer(expectedRevision: Int64) -> ExecutionControllerResult {
        if revisionValue == Int64.max { return failure(.revisionOverflow) }
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }
        guard session.status == .running else {
            return failure(.sessionNotRunning)
        }
        guard let step = currentStep() else {
            return failure(.invalidTimerState)
        }
        guard step.type == .timer else {
            return failure(.invalidTimerState)
        }
        guard session.timerState.timerStatus == .idle,
              activeTimerReference == nil,
              let duration = step.durationMs,
              duration > 0 else {
            return failure(.invalidTimerState)
        }
        guard let stepKey = currentTimerStepKey() else {
            return failure(.invalidTimerState)
        }

        let plan: TimerPlan
        switch timerEngine.prepareStart(totalDurationMs: duration) {
        case let .success(value):
            plan = value
        case let .failure(error):
            return timerFailure(error)
        }
        guard let deadline = plan.deadlineMonotonicMs else {
            return failure(.invalidTimerState)
        }
        let candidate: ExecutionSession
        do {
            candidate = try session.startTimer(deadlineMonotonicMs: deadline)
        } catch {
            return failure(.invalidTimerState)
        }
        let reference = ActiveTimerReference(
            runId: plan.targetRunId,
            stepKey: stepKey
        )
        let nextRevision = revisionValue + 1

        switch timerEngine.commitStart(plan) {
        case .success:
            break
        case .expired:
            return failure(.invalidTimestamp)
        case let .failure(error):
            return timerFailure(error)
        }

        session = candidate
        activeTimerReference = reference
        revisionValue = nextRevision
        return .success(session: candidate, revision: revisionValue)
    }

    func start(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }
        guard session.status == .ready else {
            return failure(.sessionNotReady)
        }

        let now = clock.now
        guard now >= 0 else { return failure(.invalidTimestamp) }
        let started: ExecutionSession
        do {
            started = try session.start(at: now)
        } catch let error as ExecutionValidationError {
            return failure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return failure(.invalidSessionState)
        }

        switch applyTimerStart(started) {
        case let .failure(error):
            return failure(error)
        case let .ready(applied):
            session = applied.session
            activeTimerReference = applied.reference
            revisionValue += 1
            return .success(session: session, revision: revisionValue)
        }
    }

    func incrementCount(expectedRevision: Int64) -> CountIncrementResult {
        if revisionValue == Int64.max { return countFailure(.revisionOverflow) }
        if expectedRevision != revisionValue {
            return .failure(error: .staleOperation, revision: revisionValue)
        }
        if session.status == .completed || session.status == .cancelled {
            return .failure(error: .terminalSession, revision: revisionValue)
        }
        guard session.status == .running else {
            return .failure(error: .sessionNotRunning, revision: revisionValue)
        }

        let step = session.routineSnapshot.steps[session.progress.currentStepIndex]
        guard step.type == .count else {
            return .failure(error: .notCountStep, revision: revisionValue)
        }
        guard activeTimerReference == nil else {
            return countFailure(.invalidTimerState)
        }
        guard step.countMode == .increment else {
            return .failure(error: .countModeNotIncrement, revision: revisionValue)
        }
        guard let target = step.target, target > 0 else {
            return .failure(error: .invalidCountState, revision: revisionValue)
        }
        let current = session.progress.currentCount
        guard current >= 0, current <= target else {
            return .failure(error: .invalidCountState, revision: revisionValue)
        }
        guard current < target else {
            return .failure(error: .countAlreadyAtTarget, revision: revisionValue)
        }

        let now = clock.now
        guard now >= 0 else {
            return .failure(error: .invalidTimestamp, revision: revisionValue)
        }
        guard let roundStart = session.currentRoundStartedAt else {
            return .failure(error: .invalidSessionState, revision: revisionValue)
        }

        let willComplete = current + 1 == target
        let completion = StepRoundResult(
            outcome: .completed,
            startedAt: roundStart,
            endedAt: now
        )

        let next: ExecutionSession
        do {
            next = try session.incrementCountAndMaybeComplete(completion)
        } catch let error as ExecutionValidationError {
            return countFailure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch let error as SchedulingError {
            return countFailure(
                error == .invalidResultTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return countFailure(.invalidSessionState)
        }

        let applied: TimerStartApplied
        switch applyTimerStart(next) {
        case let .failure(error):
            return countFailure(error)
        case let .ready(value):
            applied = value
        }

        session = applied.session
        activeTimerReference = applied.reference
        revisionValue += 1
        if willComplete {
            return .roundCompleted(session: session, revision: revisionValue)
        }
        return .incremented(
            session: session,
            revision: revisionValue,
            currentCount: current + 1,
            target: target
        )
    }

    func confirmComplete(expectedRevision: Int64) -> StepActionResult {
        if revisionValue == Int64.max { return stepFailure(.revisionOverflow) }
        if expectedRevision != revisionValue {
            return .failure(error: .staleOperation, revision: revisionValue)
        }
        if session.status == .completed || session.status == .cancelled {
            return .failure(error: .terminalSession, revision: revisionValue)
        }
        guard session.status == .running else {
            return .failure(error: .sessionNotRunning, revision: revisionValue)
        }

        let step = session.routineSnapshot.steps[session.progress.currentStepIndex]
        guard step.type == .count else {
            return .failure(error: .notCountStep, revision: revisionValue)
        }
        guard activeTimerReference == nil else {
            return stepFailure(.invalidTimerState)
        }
        guard step.countMode == .complete else {
            return .failure(error: .countModeNotComplete, revision: revisionValue)
        }
        guard !session.progress.completeConfirmed else {
            return .failure(error: .completeAlreadyConfirmed, revision: revisionValue)
        }
        guard let roundStart = session.currentRoundStartedAt else {
            return .failure(error: .invalidSessionState, revision: revisionValue)
        }
        let now = clock.now
        guard now >= 0 else {
            return .failure(error: .invalidTimestamp, revision: revisionValue)
        }

        let completion = StepRoundResult(
            outcome: .completed,
            startedAt: roundStart,
            endedAt: now
        )
        let next: ExecutionSession
        do {
            next = try session.confirmComplete(completion)
        } catch let error as ExecutionValidationError {
            return stepFailure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch let error as SchedulingError {
            return stepFailure(
                error == .invalidResultTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return stepFailure(.invalidSessionState)
        }

        let applied: TimerStartApplied
        switch applyTimerStart(next) {
        case let .failure(error):
            return stepFailure(error)
        case let .ready(value):
            applied = value
        }

        session = applied.session
        activeTimerReference = applied.reference
        revisionValue += 1
        return .completed(session: session, revision: revisionValue)
    }

    func skipCurrentRound(expectedRevision: Int64) -> StepActionResult {
        if revisionValue == Int64.max { return stepFailure(.revisionOverflow) }
        if expectedRevision != revisionValue {
            return .failure(error: .staleOperation, revision: revisionValue)
        }
        if session.status == .completed || session.status == .cancelled {
            return .failure(error: .terminalSession, revision: revisionValue)
        }
        guard session.status == .running else {
            return .failure(error: .sessionNotRunning, revision: revisionValue)
        }
        guard let step = currentStep() else {
            return .failure(error: .invalidSessionState, revision: revisionValue)
        }
        guard let roundStart = session.currentRoundStartedAt else {
            return .failure(error: .invalidSessionState, revision: revisionValue)
        }
        let now = clock.now
        guard now >= 0 else {
            return .failure(error: .invalidTimestamp, revision: revisionValue)
        }

        if step.type == .timer {
            if let error = validateActiveTimerReference(allowedStatuses: [.running]) {
                return stepFailure(error)
            }
            switch timerEngine.preparePoll() {
            case let .failure(error):
                return stepFailure(controllerErrorForTimer(error))
            case .ready:
                // Expiry wins: discard the SKIP and complete naturally.
                let completed: ExecutionSession
                do {
                    completed = try session.completeTimer()
                } catch {
                    return stepFailure(.invalidTimerState)
                }
                return finishTimerTransaction(
                    base: session,
                    completionSession: completed,
                    outcome: .completed,
                    wallNow: now
                ) { finalSession, _ in
                    .completed(session: finalSession, revision: self.revisionValue)
                }
            case .expired, .noChange:
                break
            }

            let cleared: ExecutionSession
            do {
                cleared = try session.clearTimerForSkip()
            } catch {
                return stepFailure(.invalidTimerState)
            }
            let completed: ExecutionSession
            do {
                completed = try session.completeTimer()
            } catch {
                return stepFailure(.invalidTimerState)
            }
            return finishSkipTimerTransaction(
                base: session,
                skippedSession: cleared,
                completedSession: completed,
                wallNow: now
            )
        }

        let completion = StepRoundResult(
            outcome: .skipped,
            startedAt: roundStart,
            endedAt: now
        )
        let next: ExecutionSession
        do {
            next = try session.skipCurrentRound(completion)
        } catch let error as ExecutionValidationError {
            return stepFailure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch let error as SchedulingError {
            return stepFailure(
                error == .invalidResultTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return stepFailure(.invalidSessionState)
        }

        let applied: TimerStartApplied
        switch applyTimerStart(next) {
        case let .failure(error):
            return stepFailure(error)
        case let .ready(value):
            applied = value
        }

        session = applied.session
        activeTimerReference = applied.reference
        revisionValue += 1
        return .skipped(session: session, revision: revisionValue)
    }

    func pause(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }
        let step = currentStep()
        if step?.type != .timer, activeTimerReference != nil {
            return failure(.invalidTimerState)
        }
        if step?.type == .timer {
            guard session.status == .running else {
                return failure(.sessionNotRunning)
            }
            if let error = validateActiveTimerReference(allowedStatuses: [.running]) {
                return failure(error)
            }
            let now = clock.now
            guard now >= 0 else { return failure(.invalidTimestamp) }
            let completed: ExecutionSession
            do {
                completed = try session.completeTimer()
            } catch {
                return failure(.invalidTimerState)
            }
            let advanced: AdvancedCompletion
            switch buildAdvancedCompletion(
                base: session,
                completionSession: completed,
                outcome: .completed,
                wallNow: now
            ) {
            case let .ready(value):
                advanced = value
            case let .failure(error):
                return failure(error)
            }

            let plan: PausePlan
            switch timerEngine.preparePause(
                nextTimerDurationMs: advanced.nextTimerDurationMs
            ) {
            case let .ready(value):
                guard let pausePlan = value as? PausePlan else {
                    return failure(.invalidTimerState)
                }
                plan = pausePlan
            case .expired:
                let completion: CompletionApplied
                switch prepareCompletionTransaction(
                    base: session,
                    completionSession: completed,
                    outcome: .completed,
                    wallNow: now
                ) {
                case let .ready(value):
                    completion = value
                case let .failure(error):
                    return failure(error)
                }
                switch timerEngine.commitCompletion(completion.plan) {
                case .completed:
                    break
                case let .failure(error):
                    return timerFailure(error)
                case .paused, .resumed, .cancelled, .expired:
                    return failure(.invalidSessionState)
                }
                session = completion.session
                activeTimerReference = completion.reference
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case let .failure(error):
                return timerFailure(error)
            case .noChange:
                return failure(.invalidTimerState)
            }

            let paused: ExecutionSession
            do {
                paused = try session.pauseTimer(
                    remainingDurationMs: plan.remainingDurationMs
                )
            } catch {
                return failure(.invalidTimerState)
            }

            var fallbackSession = advanced.session
            var fallbackReference: ActiveTimerReference?
            if advanced.nextTimerDurationMs != nil {
                guard let deadline = plan.fallbackDeadlineMonotonicMs else {
                    return failure(.invalidTimerState)
                }
                do {
                    fallbackSession = try advanced.session.startTimer(
                        deadlineMonotonicMs: deadline
                    )
                } catch {
                    return failure(.invalidTimerState)
                }
                guard let runId = plan.fallbackRunId,
                      let key = timerStepKeyFor(fallbackSession) else {
                    return failure(.invalidTimerState)
                }
                fallbackReference = ActiveTimerReference(runId: runId, stepKey: key)
            }

            switch timerEngine.commitPause(plan) {
            case .paused:
                session = paused
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case .expired:
                session = fallbackSession
                activeTimerReference = fallbackReference
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case let .failure(error):
                return timerFailure(error)
            case .resumed, .cancelled, .completed:
                return failure(.invalidSessionState)
            }
        }

        guard session.status == .running else {
            return failure(.sessionNotRunning)
        }
        return runTransition { try self.session.pause() }
    }

    func resume(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }
        guard session.status == .paused else {
            return failure(.sessionNotPaused)
        }
        let step = currentStep()
        if step?.type != .timer, activeTimerReference != nil {
            return failure(.invalidTimerState)
        }
        if step?.type == .timer {
            if let error = validateActiveTimerReference(allowedStatuses: [.paused]) {
                return failure(error)
            }
            let now = clock.now
            guard now >= 0 else { return failure(.invalidTimestamp) }

            let first: ResumePlan
            switch timerEngine.prepareResume() {
            case let .ready(value):
                guard let resumePlan = value as? ResumePlan else {
                    return failure(.invalidTimerState)
                }
                first = resumePlan
            case let .failure(error):
                return timerFailure(error)
            case .expired, .noChange:
                return failure(.invalidTimerState)
            }

            let primary: ExecutionSession
            do {
                primary = try session.resumeTimer(
                    deadlineMonotonicMs: first.deadlineMonotonicMs
                )
            } catch {
                return failure(.invalidTimerState)
            }
            let completed: ExecutionSession
            do {
                completed = try primary.completeTimer()
            } catch {
                return failure(.invalidTimerState)
            }
            let advanced: AdvancedCompletion
            switch buildAdvancedCompletion(
                base: session,
                completionSession: completed,
                outcome: .completed,
                wallNow: now
            ) {
            case let .ready(value):
                advanced = value
            case let .failure(error):
                return failure(error)
            }

            let plan: ResumePlan
            switch timerEngine.prepareResume(
                nextTimerDurationMs: advanced.nextTimerDurationMs
            ) {
            case let .ready(value):
                guard let resumePlan = value as? ResumePlan else {
                    return failure(.invalidTimerState)
                }
                plan = resumePlan
            case let .failure(error):
                return timerFailure(error)
            case .expired, .noChange:
                return failure(.invalidTimerState)
            }

            var fallbackSession = advanced.session
            var fallbackReference: ActiveTimerReference?
            if advanced.nextTimerDurationMs != nil {
                guard let deadline = plan.fallbackDeadlineMonotonicMs else {
                    return failure(.invalidTimerState)
                }
                do {
                    fallbackSession = try advanced.session.startTimer(
                        deadlineMonotonicMs: deadline
                    )
                } catch {
                    return failure(.invalidTimerState)
                }
                guard let runId = plan.fallbackRunId,
                      let key = timerStepKeyFor(fallbackSession) else {
                    return failure(.invalidTimerState)
                }
                fallbackReference = ActiveTimerReference(runId: runId, stepKey: key)
            }

            let finalPrimary: ExecutionSession
            do {
                finalPrimary = try session.resumeTimer(
                    deadlineMonotonicMs: plan.deadlineMonotonicMs
                )
            } catch {
                return failure(.invalidTimerState)
            }

            switch timerEngine.commitResume(plan) {
            case .resumed:
                session = finalPrimary
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case .expired:
                session = fallbackSession
                activeTimerReference = fallbackReference
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case let .failure(error):
                return timerFailure(error)
            case .paused, .cancelled, .completed:
                return failure(.invalidSessionState)
            }
        }
        return runTransition { try self.session.resume() }
    }

    /// Polls the active TIMER. Not-expired is a no-op; expired completes and
    /// advances atomically, including an optional next TIMER.
    func pollTimer(
        expectedRevision: Int64,
        expectedRunId: TimerRunId
    ) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if session.status == .completed ||
            session.status == .cancelled ||
            session.status == .paused {
            return .success(session: session, revision: revisionValue)
        }
        guard let reference = activeTimerReference else {
            if currentStep()?.type == .timer {
                return failure(.invalidTimerState)
            }
            return .success(session: session, revision: revisionValue)
        }
        guard reference.runId == expectedRunId else {
            return .success(session: session, revision: revisionValue)
        }
        guard session.status == .running else {
            return .success(session: session, revision: revisionValue)
        }
        guard let step = currentStep(), step.type == .timer else {
            return failure(.invalidTimerState)
        }
        if let error = validateActiveTimerReference(allowedStatuses: [.running]) {
            return failure(error)
        }

        switch timerEngine.preparePoll() {
        case .noChange:
            return .success(session: session, revision: revisionValue)
        case let .failure(error):
            return timerFailure(error)
        case .ready:
            break
        case .expired:
            return failure(.invalidTimerState)
        }

        let now = clock.now
        guard now >= 0 else { return failure(.invalidTimestamp) }
        let completed: ExecutionSession
        do {
            completed = try session.completeTimer()
        } catch {
            return failure(.invalidTimerState)
        }
        return finishTimerTransactionForResult(
            base: session,
            completionSession: completed,
            wallNow: now
        )
    }

    private func finishTimerTransactionForResult(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        wallNow: Int64
    ) -> ExecutionControllerResult {
        let prepared: CompletionApplied
        switch prepareCompletionTransaction(
            base: base,
            completionSession: completionSession,
            outcome: .completed,
            wallNow: wallNow
        ) {
        case let .ready(value):
            prepared = value
        case let .failure(error):
            return failure(error)
        }

        switch timerEngine.commitCompletion(prepared.plan) {
        case .completed:
            break
        case let .failure(error):
            return timerFailure(error)
        case .paused, .resumed, .cancelled, .expired:
            return failure(.invalidSessionState)
        }

        session = prepared.session
        activeTimerReference = prepared.reference
        revisionValue += 1
        return .success(session: session, revision: revisionValue)
    }

    func cancel(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }

        let now = clock.now
        guard now >= 0 else { return failure(.invalidTimestamp) }
        let step = currentStep()
        if (session.status == .running || session.status == .paused),
           step?.type == .timer {
            if let error = validateActiveTimerReference(
                allowedStatuses: [.running, .paused]
            ) {
                return failure(error)
            }
            if session.status == .running {
                switch timerEngine.preparePoll() {
                case .ready:
                    return cancelAfterExpiry(wallNow: now)
                case let .failure(error):
                    return timerFailure(error)
                case .noChange:
                    break
                case .expired:
                    return failure(.invalidTimerState)
                }
            }

            let plan: CancelPlan
            switch timerEngine.prepareCancel() {
            case let .ready(value):
                guard let cancelPlan = value as? CancelPlan else {
                    return failure(.invalidTimerState)
                }
                plan = cancelPlan
            case .expired:
                return cancelAfterExpiry(wallNow: now)
            case let .failure(error):
                return timerFailure(error)
            case .noChange:
                return failure(.invalidTimerState)
            }

            let cancelledTimer = TimerState(
                totalDurationMs: session.timerState.totalDurationMs,
                remainingDurationMs: plan.remainingDurationMs,
                deadline: nil,
                timerStatus: .cancelled
            )
            let cancelled: ExecutionSession
            do {
                cancelled = try session.cancelWithTimerState(
                    at: now,
                    timerState: cancelledTimer
                )
            } catch {
                return failure(.invalidSessionState)
            }
            let fallback: ExecutionSession
            switch buildCancelFallbackSession(wallNow: now) {
            case let .ready(value):
                fallback = value
            case let .failure(error):
                return failure(error)
            }

            switch timerEngine.commitCancel(plan) {
            case .cancelled:
                session = cancelled
                activeTimerReference = nil
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case .expired:
                session = fallback
                activeTimerReference = nil
                revisionValue += 1
                return .success(session: session, revision: revisionValue)
            case let .failure(error):
                return timerFailure(error)
            case .paused, .resumed, .completed:
                return failure(.invalidSessionState)
            }
        }

        return runTransition { try self.session.cancel(at: now) }
    }

    private enum CancelFallbackResult {
        case ready(ExecutionSession)
        case failure(ExecutionControllerError)
    }

    private func buildCancelFallbackSession(
        wallNow: Int64
    ) -> CancelFallbackResult {
        let completed: ExecutionSession
        do {
            if session.status == .paused {
                completed = try session.resumeTimer(
                    deadlineMonotonicMs: Int64.max
                ).completeTimer()
            } else {
                completed = try session.completeTimer()
            }
        } catch {
            return .failure(.invalidTimerState)
        }
        let advanced: AdvancedCompletion
        switch buildAdvancedCompletion(
            base: session,
            completionSession: completed,
            outcome: .completed,
            wallNow: wallNow
        ) {
        case let .ready(value):
            advanced = value
        case let .failure(error):
            return .failure(error)
        }
        do {
            if advanced.session.status == .completed {
                return .ready(advanced.session)
            }
            return .ready(
                try advanced.session.cancelWithTimerState(
                    at: wallNow,
                    timerState: advanced.session.timerState
                )
            )
        } catch {
            return .failure(.invalidSessionState)
        }
    }

    private func cancelAfterExpiry(wallNow: Int64) -> ExecutionControllerResult {
        let completed: ExecutionSession
        if session.status == .paused {
            let resume: ResumePlan
            switch timerEngine.prepareResume() {
            case let .ready(value):
                guard let resumePlan = value as? ResumePlan else {
                    return failure(.invalidTimerState)
                }
                resume = resumePlan
            case let .failure(error):
                return timerFailure(error)
            case .expired, .noChange:
                return failure(.invalidTimerState)
            }
            let resumed: ExecutionSession
            do {
                resumed = try session.resumeTimer(
                    deadlineMonotonicMs: resume.deadlineMonotonicMs
                )
            } catch {
                return failure(.invalidTimerState)
            }
            do {
                completed = try resumed.completeTimer()
            } catch {
                return failure(.invalidTimerState)
            }
        } else {
            do {
                completed = try session.completeTimer()
            } catch {
                return failure(.invalidTimerState)
            }
        }

        let advanced: AdvancedCompletion
        switch buildAdvancedCompletion(
            base: session,
            completionSession: completed,
            outcome: .completed,
            wallNow: wallNow
        ) {
        case let .ready(value):
            advanced = value
        case let .failure(error):
            return failure(error)
        }

        let finalSession: ExecutionSession
        if advanced.session.status == .completed {
            finalSession = advanced.session
        } else {
            do {
                finalSession = try advanced.session.cancelWithTimerState(
                    at: wallNow,
                    timerState: advanced.session.timerState
                )
            } catch {
                return failure(.invalidSessionState)
            }
        }

        let plan: CompletionPlan
        switch timerEngine.prepareCompletion(nextTimerDurationMs: nil) {
        case let .ready(value):
            guard let completionPlan = value as? CompletionPlan else {
                return failure(.invalidTimerState)
            }
            plan = completionPlan
        case let .failure(error):
            return timerFailure(error)
        case .expired, .noChange:
            return failure(.invalidTimerState)
        }

        switch timerEngine.commitCompletion(plan) {
        case .completed:
            session = finalSession
            activeTimerReference = nil
            revisionValue += 1
            return .success(session: session, revision: revisionValue)
        case let .failure(error):
            return timerFailure(error)
        case .paused, .resumed, .cancelled, .expired:
            return failure(.invalidSessionState)
        }
    }

    private struct CompletionFinalized {
        let session: ExecutionSession
        let reference: ActiveTimerReference?
    }

    private func finalizeCompletionPlan(
        advanced: AdvancedCompletion,
        plan: CompletionPlan
    ) -> CompletionFinalized? {
        guard advanced.nextTimerDurationMs != nil else {
            return CompletionFinalized(session: advanced.session, reference: nil)
        }
        guard let deadline = plan.nextDeadlineMonotonicMs,
              let runId = plan.nextRunId else {
            return nil
        }
        let final: ExecutionSession
        do {
            final = try advanced.session.startTimer(deadlineMonotonicMs: deadline)
        } catch {
            return nil
        }
        guard let key = timerStepKeyFor(final) else { return nil }
        return CompletionFinalized(
            session: final,
            reference: ActiveTimerReference(runId: runId, stepKey: key)
        )
    }

    private func finishSkipTimerTransaction(
        base: ExecutionSession,
        skippedSession: ExecutionSession,
        completedSession: ExecutionSession,
        wallNow: Int64
    ) -> StepActionResult {
        let skippedAdvanced: AdvancedCompletion
        switch buildAdvancedCompletion(
            base: base,
            completionSession: skippedSession,
            outcome: .skipped,
            wallNow: wallNow
        ) {
        case let .ready(value): skippedAdvanced = value
        case let .failure(error): return stepFailure(error)
        }
        let completedAdvanced: AdvancedCompletion
        switch buildAdvancedCompletion(
            base: base,
            completionSession: completedSession,
            outcome: .completed,
            wallNow: wallNow
        ) {
        case let .ready(value): completedAdvanced = value
        case let .failure(error): return stepFailure(error)
        }
        guard skippedAdvanced.nextTimerDurationMs ==
                completedAdvanced.nextTimerDurationMs else {
            return stepFailure(.invalidSessionState)
        }
        let plan: CompletionPlan
        switch timerEngine.prepareCompletion(
            nextTimerDurationMs: skippedAdvanced.nextTimerDurationMs,
            expirySensitive: true
        ) {
        case let .ready(value):
            guard let completionPlan = value as? CompletionPlan else {
                return stepFailure(.invalidTimerState)
            }
            plan = completionPlan
        case let .failure(error):
            return stepFailure(controllerErrorForTimer(error))
        case .expired, .noChange:
            return stepFailure(.invalidTimerState)
        }
        guard let skippedFinal = finalizeCompletionPlan(
            advanced: skippedAdvanced,
            plan: plan
        ), let completedFinal = finalizeCompletionPlan(
            advanced: completedAdvanced,
            plan: plan
        ) else {
            return stepFailure(.invalidTimerState)
        }
        switch timerEngine.commitCompletion(plan) {
        case .completed:
            session = skippedFinal.session
            activeTimerReference = skippedFinal.reference
            revisionValue += 1
            return .skipped(session: session, revision: revisionValue)
        case .expired:
            session = completedFinal.session
            activeTimerReference = completedFinal.reference
            revisionValue += 1
            return .completed(session: session, revision: revisionValue)
        case let .failure(error):
            return stepFailure(controllerErrorForTimer(error))
        case .paused, .resumed, .cancelled:
            return stepFailure(.invalidSessionState)
        }
    }

    private func finishTimerTransaction(
        base: ExecutionSession,
        completionSession: ExecutionSession,
        outcome: ExecutionStepOutcome,
        wallNow: Int64,
        success: (ExecutionSession, ActiveTimerReference?) -> StepActionResult
    ) -> StepActionResult {
        let prepared: CompletionApplied
        switch prepareCompletionTransaction(
            base: base,
            completionSession: completionSession,
            outcome: outcome,
            wallNow: wallNow
        ) {
        case let .failure(error):
            return stepFailure(error)
        case let .ready(value):
            prepared = value
        }

        switch timerEngine.commitCompletion(prepared.plan) {
        case .completed:
            break
        case let .failure(error):
            return stepFailure(controllerErrorForTimer(error))
        case .paused, .resumed, .cancelled, .expired:
            return stepFailure(.invalidSessionState)
        }

        session = prepared.session
        activeTimerReference = prepared.reference
        revisionValue += 1
        return success(session, activeTimerReference)
    }

    static func create(
        routine: Routine,
        sessionID: String = UUID().uuidString,
        clock: ExecutionClock = SystemExecutionClock()
    ) -> ExecutionControllerCreateResult {
        do {
            let initial = try ExecutionSession.create(
                routine: routine,
                sessionID: sessionID
            )
            return .success(
                ExecutionController(
                    clock: clock,
                    initialState: initial
                )
            )
        } catch let error as ExecutionValidationError {
            return .failure(
                error == .emptySteps ? .emptyRoutine : .invalidSessionState
            )
        } catch {
            return .failure(.invalidSessionState)
        }
    }

    private func staleFailure(_ expectedRevision: Int64) -> ExecutionControllerResult? {
        if revisionValue == Int64.max { return failure(.revisionOverflow) }
        guard expectedRevision != revisionValue else { return nil }
        return .failure(error: .staleOperation, revision: revisionValue)
    }

    private func terminalFailure() -> ExecutionControllerResult? {
        guard session.status == .completed || session.status == .cancelled else {
            return nil
        }
        return failure(.terminalSession)
    }

    private func runTransition(
        _ block: () throws -> ExecutionSession
    ) -> ExecutionControllerResult {
        do {
            let next = try block()
            session = next
            revisionValue += 1
            return .success(session: next, revision: revisionValue)
        } catch let error as ExecutionValidationError {
            return failure(
                error == .invalidTimestamp ? .invalidTimestamp : .invalidSessionState
            )
        } catch {
            return failure(.invalidSessionState)
        }
    }

    private func timerFailure(_ error: TimerEngineError) -> ExecutionControllerResult {
        failure(controllerErrorForTimer(error))
    }

    private func failure(_ error: ExecutionControllerError) -> ExecutionControllerResult {
        .failure(error: error, revision: revisionValue)
    }

    private func countFailure(_ error: ExecutionControllerError) -> CountIncrementResult {
        .failure(error: error, revision: revisionValue)
    }

    private func stepFailure(_ error: ExecutionControllerError) -> StepActionResult {
        .failure(error: error, revision: revisionValue)
    }
}
