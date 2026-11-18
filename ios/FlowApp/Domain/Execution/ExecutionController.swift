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

    internal init(
        clock: ExecutionClock,
        initialState: ExecutionSession,
        initialRevision: Int64 = 0
    ) {
        self.clock = clock
        self.session = initialState
        self.revisionValue = initialRevision
    }

    func snapshot() -> ExecutionControllerSnapshot {
        ExecutionControllerSnapshot(session: session, revision: revisionValue)
    }

    func start(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }
        guard session.status == .ready else {
            return failure(.sessionNotReady)
        }

        let now = clock.now
        guard now >= 0 else { return failure(.invalidTimestamp) }
        return runTransition { try self.session.start(at: now) }
    }

    func incrementCount(expectedRevision: Int64) -> CountIncrementResult {
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

        do {
            let next = try session.incrementCountAndMaybeComplete(completion)
            session = next
            revisionValue += 1
            if willComplete {
                return .roundCompleted(session: next, revision: revisionValue)
            }
            return .incremented(
                session: next,
                revision: revisionValue,
                currentCount: current + 1,
                target: target
            )
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
    }

    func confirmComplete(expectedRevision: Int64) -> StepActionResult {
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
        do {
            let next = try session.confirmComplete(completion)
            session = next
            revisionValue += 1
            return .completed(session: next, revision: revisionValue)
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
    }

    func skipCurrentRound(expectedRevision: Int64) -> StepActionResult {
        if expectedRevision != revisionValue {
            return .failure(error: .staleOperation, revision: revisionValue)
        }
        if session.status == .completed || session.status == .cancelled {
            return .failure(error: .terminalSession, revision: revisionValue)
        }
        guard session.status == .running else {
            return .failure(error: .sessionNotRunning, revision: revisionValue)
        }
        guard let roundStart = session.currentRoundStartedAt else {
            return .failure(error: .invalidSessionState, revision: revisionValue)
        }
        let now = clock.now
        guard now >= 0 else {
            return .failure(error: .invalidTimestamp, revision: revisionValue)
        }

        let completion = StepRoundResult(
            outcome: .skipped,
            startedAt: roundStart,
            endedAt: now
        )
        do {
            let next = try session.skipCurrentRound(completion)
            session = next
            revisionValue += 1
            return .skipped(session: next, revision: revisionValue)
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
    }

    func pause(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }
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
        return runTransition { try self.session.resume() }
    }

    func cancel(expectedRevision: Int64) -> ExecutionControllerResult {
        if let stale = staleFailure(expectedRevision) { return stale }
        if let terminal = terminalFailure() { return terminal }

        let now = clock.now
        guard now >= 0 else { return failure(.invalidTimestamp) }
        return runTransition { try self.session.cancel(at: now) }
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
