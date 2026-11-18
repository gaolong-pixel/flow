import Foundation

protocol MonotonicClock {
    var nowMs: Int64 { get }
}

struct SystemMonotonicClock: MonotonicClock {
    var nowMs: Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1_000)
    }
}

struct TimerRunId: Hashable {
    let value: String
}

enum TimerRunStatus: Equatable {
    case idle
    case running
    case paused
    case completed
    case cancelled
}

struct TimerSnapshot: Equatable {
    let runId: TimerRunId?
    let status: TimerRunStatus
    let totalDurationMs: Int64
    let remainingDurationMs: Int64
    let deadlineMonotonicMs: Int64?
}

enum TimerEngineError: Error, Equatable {
    case invalidDuration
    case invalidState
    case invalidClock
    case clockRegression
    case deadlineOverflow
}

enum TimerOperationResult: Equatable {
    case success(TimerSnapshot)
    case expired(TimerSnapshot)
    case failure(TimerEngineError)
}

internal struct TimerInternalState: Equatable {
    let runId: TimerRunId?
    let status: TimerRunStatus
    let totalDurationMs: Int64
    let remainingDurationMs: Int64
    let deadlineMonotonicMs: Int64?
    let lastObservedMs: Int64?
}

private enum TimerClockFailure: Error {
    case invalidClock
    case clockRegression
}

/// Independent monotonic timer run. It never knows about ExecutionSession,
/// ExecutionProgress, ExecutionScheduler, records, or Controllers.
@MainActor
final class ExecutionTimerEngine {
    private let clock: MonotonicClock
    private let idGenerator: () -> String

    private var runId: TimerRunId?
    private var status: TimerRunStatus = .idle
    private var totalDurationMs: Int64 = 0
    private var remainingDurationMs: Int64 = 0
    private var deadlineMonotonicMs: Int64?
    private var lastObservedMs: Int64?

    init(
        clock: MonotonicClock,
        idGenerator: @escaping () -> String = { UUID().uuidString }
    ) {
        self.clock = clock
        self.idGenerator = idGenerator
    }

    func start(totalDurationMs: Int64) -> TimerOperationResult {
        guard totalDurationMs > 0 else {
            return .failure(.invalidDuration)
        }
        guard status != .running, status != .paused else {
            return .failure(.invalidState)
        }

        let now: Int64
        do {
            now = try readClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard now <= Int64.max - totalDurationMs else {
            return .failure(.deadlineOverflow)
        }

        runId = TimerRunId(value: idGenerator())
        status = .running
        self.totalDurationMs = totalDurationMs
        remainingDurationMs = totalDurationMs
        deadlineMonotonicMs = now + totalDurationMs
        return .success(snapshotWithoutClock())
    }

    func pause() -> TimerOperationResult {
        guard status == .running else {
            return .failure(.invalidState)
        }

        let now: Int64
        do {
            now = try readClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard let deadline = deadlineMonotonicMs else {
            return .failure(.invalidState)
        }
        if now >= deadline {
            complete()
            return .expired(snapshotWithoutClock())
        }

        remainingDurationMs = deadline - now
        deadlineMonotonicMs = nil
        status = .paused
        return .success(snapshotWithoutClock())
    }

    func resume() -> TimerOperationResult {
        guard status == .paused else {
            return .failure(.invalidState)
        }

        let now: Int64
        do {
            now = try readClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard now <= Int64.max - remainingDurationMs else {
            return .failure(.deadlineOverflow)
        }

        deadlineMonotonicMs = now + remainingDurationMs
        status = .running
        return .success(snapshotWithoutClock())
    }

    func cancel() -> TimerOperationResult {
        guard status == .running || status == .paused else {
            return .failure(.invalidState)
        }

        if status == .paused {
            deadlineMonotonicMs = nil
            status = .cancelled
            return .success(snapshotWithoutClock())
        }

        let now: Int64
        do {
            now = try readClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard let deadline = deadlineMonotonicMs else {
            return .failure(.invalidState)
        }
        if now >= deadline {
            complete()
            return .expired(snapshotWithoutClock())
        }
        remainingDurationMs = deadline - now
        deadlineMonotonicMs = nil
        status = .cancelled
        return .success(snapshotWithoutClock())
    }

    /// Pure query. It reads the monotonic clock for RUNNING projection but
    /// never writes any internal field, including lastObservedMs.
    func snapshot() -> TimerOperationResult {
        guard status == .running else {
            return .success(snapshotWithoutClock())
        }

        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard let deadline = deadlineMonotonicMs else {
            return .failure(.invalidState)
        }
        let remaining = max(0, deadline - now)
        return .success(
            TimerSnapshot(
                runId: runId,
                status: .running,
                totalDurationMs: totalDurationMs,
                remainingDurationMs: remaining,
                deadlineMonotonicMs: deadline
            )
        )
    }

    /// The only query-like operation that may transition RUNNING to COMPLETED.
    /// Repeated calls after completion never produce another Expired result.
    func poll() -> TimerOperationResult {
        guard status == .running else {
            return .success(snapshotWithoutClock())
        }

        let now: Int64
        do {
            now = try readClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard let deadline = deadlineMonotonicMs else {
            return .failure(.invalidState)
        }
        if now >= deadline {
            complete()
            return .expired(snapshotWithoutClock())
        }
        remainingDurationMs = deadline - now
        return .success(snapshotWithoutClock())
    }

    internal func debugState() -> TimerInternalState {
        TimerInternalState(
            runId: runId,
            status: status,
            totalDurationMs: totalDurationMs,
            remainingDurationMs: remainingDurationMs,
            deadlineMonotonicMs: deadlineMonotonicMs,
            lastObservedMs: lastObservedMs
        )
    }

    private func snapshotWithoutClock() -> TimerSnapshot {
        TimerSnapshot(
            runId: runId,
            status: status,
            totalDurationMs: totalDurationMs,
            remainingDurationMs: remainingDurationMs,
            deadlineMonotonicMs: deadlineMonotonicMs
        )
    }

    private func complete() {
        status = .completed
        remainingDurationMs = 0
        deadlineMonotonicMs = nil
    }

    private func peekClock() throws -> Int64 {
        let now = clock.nowMs
        guard now >= 0 else {
            throw TimerClockFailure.invalidClock
        }
        if let previous = lastObservedMs, now < previous {
            throw TimerClockFailure.clockRegression
        }
        return now
    }

    private func readClock() throws -> Int64 {
        let now = clock.nowMs
        guard now >= 0 else {
            throw TimerClockFailure.invalidClock
        }
        if let previous = lastObservedMs, now < previous {
            throw TimerClockFailure.clockRegression
        }
        lastObservedMs = now
        return now
    }
}
