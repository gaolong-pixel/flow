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
}
