import Darwin
import Foundation

/// Production implementations must be nondecreasing. The Engine rejects
/// observed regressions, but a pure prepare cannot observe a forward movement
/// that is reversed before commit. Tests that violate monotonicity are not
/// production-supported.
protocol MonotonicClock {
    var nowMs: Int64 { get }
}

struct SystemMonotonicClock: MonotonicClock {
    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    var nowMs: Int64 {
        let ticks = mach_continuous_time()
        let (scaled, overflowed) = ticks.multipliedReportingOverflow(
            by: UInt64(Self.timebase.numer)
        )
        if overflowed {
            return Int64.max
        }
        let nanos = scaled / UInt64(Self.timebase.denom)
        let millis = nanos / 1_000_000
        if millis > UInt64(Int64.max) {
            return Int64.max
        }
        return Int64(millis)
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
    case invalidPlan
    case planStale
    case planEngineMismatch
    case planExpired
    case versionOverflow
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
    let stateVersion: Int64
}

/// Opaque reference handle. Execution parameters live only in
/// Engine.pendingStart. It exposes two read-only projections - targetRunId and
/// deadlineMonotonicMs - solely for Controller candidate construction.
/// commitStart uses the authoritative PendingStart values and never trusts
/// these projections.
internal final class TimerPlan {
    internal let targetRunId: TimerRunId
    internal let deadlineMonotonicMs: Int64?

    internal init(targetRunId: TimerRunId, deadlineMonotonicMs: Int64?) {
        self.targetRunId = targetRunId
        self.deadlineMonotonicMs = deadlineMonotonicMs
    }
}

internal enum TimerPlanResult {
    case success(TimerPlan)
    case failure(TimerEngineError)
}

internal protocol TimerLifecyclePlan: AnyObject {}
internal final class PausePlan: TimerLifecyclePlan {
    internal let remainingDurationMs: Int64
    internal let fallbackRunId: TimerRunId?
    internal let fallbackDeadlineMonotonicMs: Int64?
    internal init(
        remainingDurationMs: Int64,
        fallbackRunId: TimerRunId? = nil,
        fallbackDeadlineMonotonicMs: Int64? = nil
    ) {
        self.remainingDurationMs = remainingDurationMs
        self.fallbackRunId = fallbackRunId
        self.fallbackDeadlineMonotonicMs = fallbackDeadlineMonotonicMs
    }
}
internal final class ResumePlan: TimerLifecyclePlan {
    internal let deadlineMonotonicMs: Int64
    internal let fallbackRunId: TimerRunId?
    internal let fallbackDeadlineMonotonicMs: Int64?
    internal init(
        deadlineMonotonicMs: Int64,
        fallbackRunId: TimerRunId? = nil,
        fallbackDeadlineMonotonicMs: Int64? = nil
    ) {
        self.deadlineMonotonicMs = deadlineMonotonicMs
        self.fallbackRunId = fallbackRunId
        self.fallbackDeadlineMonotonicMs = fallbackDeadlineMonotonicMs
    }
}
internal final class CompletionPlan: TimerLifecyclePlan {
    internal let nextRunId: TimerRunId?
    internal let nextDeadlineMonotonicMs: Int64?
    internal init(nextRunId: TimerRunId?, nextDeadlineMonotonicMs: Int64?) {
        self.nextRunId = nextRunId
        self.nextDeadlineMonotonicMs = nextDeadlineMonotonicMs
    }
}
internal final class CancelPlan: TimerLifecyclePlan {
    internal let remainingDurationMs: Int64
    internal init(remainingDurationMs: Int64) { self.remainingDurationMs = remainingDurationMs }
}
internal final class PollPlan: TimerLifecyclePlan {
    internal init() {}
}

internal enum TimerPrepareResult {
    case ready(TimerLifecyclePlan)
    case expired
    case noChange
    case failure(TimerEngineError)
}

internal enum TimerCommitResult {
    case paused(TimerSnapshot)
    case resumed(TimerSnapshot)
    case cancelled(TimerSnapshot)
    case completed(TimerSnapshot)
    case expired(TimerSnapshot)
    case failure(TimerEngineError)
}

private struct PendingStart {
    let plan: TimerPlan
    let baseVersion: Int64
    let expectedRunId: TimerRunId?
    let expectedStatus: TimerRunStatus
    let targetRunId: TimerRunId
    let targetStatus: TimerRunStatus
    let totalDurationMs: Int64
    let remainingDurationMs: Int64
    let deadlineMonotonicMs: Int64
    let preparedAtMonotonicMs: Int64
}

private struct PendingPause {
    let plan: PausePlan
    let baseVersion: Int64
    let expectedRunId: TimerRunId?
    let expectedStatus: TimerRunStatus
    let preparedAtMonotonicMs: Int64
    let remainingDurationMs: Int64
    let originalDeadlineMonotonicMs: Int64
    let fallbackRunId: TimerRunId?
    let fallbackDeadlineMonotonicMs: Int64?
    let fallbackTotalDurationMs: Int64?
}
private struct PendingResume {
    let plan: ResumePlan
    let baseVersion: Int64
    let expectedRunId: TimerRunId?
    let expectedStatus: TimerRunStatus
    let preparedAtMonotonicMs: Int64
    let remainingDurationMs: Int64
    let resumeDeadlineMonotonicMs: Int64
    let fallbackRunId: TimerRunId?
    let fallbackDeadlineMonotonicMs: Int64?
    let fallbackTotalDurationMs: Int64?
}
private struct PendingCancel {
    let plan: CancelPlan
    let baseVersion: Int64
    let expectedRunId: TimerRunId?
    let expectedStatus: TimerRunStatus
    let preparedAtMonotonicMs: Int64
    let remainingDurationMs: Int64
    let expiryAtMonotonicMs: Int64?
}
private struct PendingPoll {
    let plan: PollPlan
    let baseVersion: Int64
    let expectedRunId: TimerRunId?
    let expectedStatus: TimerRunStatus
    let preparedAtMonotonicMs: Int64
    let expiryAtMonotonicMs: Int64
}
private struct PendingCompletion {
    let plan: CompletionPlan
    let baseVersion: Int64
    let expectedRunId: TimerRunId?
    let expectedStatus: TimerRunStatus
    let preparedAtMonotonicMs: Int64
    let nextRunId: TimerRunId?
    let nextDeadlineMonotonicMs: Int64?
    let nextTotalDurationMs: Int64?
    let expirySensitive: Bool
    let expiryAtMonotonicMs: Int64?
}
private enum PendingLifecycle {
    case pause(PendingPause)
    case resume(PendingResume)
    case cancel(PendingCancel)
    case poll(PendingPoll)
    case completion(PendingCompletion)
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
    private var stateVersion: Int64 = 0
    private var pendingStart: PendingStart?
    private var pendingLifecycle: PendingLifecycle?
    private var resumeDeadlineAnchorMonotonicMs: Int64?

    init(
        clock: MonotonicClock,
        idGenerator: @escaping () -> String = { UUID().uuidString }
    ) {
        self.clock = clock
        self.idGenerator = idGenerator
    }

    func start(totalDurationMs: Int64) -> TimerOperationResult {
        let plan: TimerPlan
        switch prepareStart(totalDurationMs: totalDurationMs) {
        case let .success(value):
            plan = value
        case let .failure(error):
            return .failure(error)
        }
        return commitStart(plan)
    }

    func prepareStart(totalDurationMs: Int64) -> TimerPlanResult {
        guard totalDurationMs > 0 else {
            return .failure(.invalidDuration)
        }
        guard status != .running, status != .paused else {
            return .failure(.invalidState)
        }

        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard now <= Int64.max - totalDurationMs else {
            return .failure(.deadlineOverflow)
        }

        let plannedRunId = TimerRunId(value: idGenerator())
        let deadline = now + totalDurationMs
        let plan = TimerPlan(
            targetRunId: plannedRunId,
            deadlineMonotonicMs: deadline
        )
        pendingStart = PendingStart(
            plan: plan,
            baseVersion: stateVersion,
            expectedRunId: runId,
            expectedStatus: status,
            targetRunId: plannedRunId,
            targetStatus: .running,
            totalDurationMs: totalDurationMs,
            remainingDurationMs: totalDurationMs,
            deadlineMonotonicMs: deadline,
            preparedAtMonotonicMs: now
        )
        return .success(plan)
    }

    func commitStart(_ plan: TimerPlan) -> TimerOperationResult {
        guard let pending = pendingStart, plan === pending.plan else {
            return .failure(.invalidPlan)
        }
        guard stateVersion < Int64.max else {
            return .failure(.versionOverflow)
        }
        guard pending.baseVersion == stateVersion else {
            return .failure(.planStale)
        }
        guard pending.expectedRunId == runId,
              pending.expectedStatus == status else {
            return .failure(.planStale)
        }
        guard [.idle, .completed, .cancelled].contains(pending.expectedStatus) else {
            return .failure(.invalidPlan)
        }
        guard pending.targetStatus == .running,
              !pending.targetRunId.value.isEmpty,
              pending.targetRunId != pending.expectedRunId,
              pending.totalDurationMs > 0,
              pending.remainingDurationMs == pending.totalDurationMs,
              pending.preparedAtMonotonicMs >= 0,
              pending.deadlineMonotonicMs > 0 else {
            return .failure(.invalidPlan)
        }
        guard pending.preparedAtMonotonicMs <= Int64.max - pending.totalDurationMs,
              pending.deadlineMonotonicMs ==
                pending.preparedAtMonotonicMs + pending.totalDurationMs else {
            return .failure(.invalidPlan)
        }

        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard now >= pending.preparedAtMonotonicMs else {
            return .failure(.clockRegression)
        }
        guard now < pending.deadlineMonotonicMs else {
            return .failure(.planExpired)
        }

        runId = pending.targetRunId
        status = .running
        totalDurationMs = pending.totalDurationMs
        remainingDurationMs = pending.remainingDurationMs
        deadlineMonotonicMs = pending.deadlineMonotonicMs
        lastObservedMs = now
        stateVersion += 1
        pendingStart = nil
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        return .success(snapshotWithoutClock())
    }

    func preparePause(nextTimerDurationMs: Int64? = nil) -> TimerPrepareResult {
        guard status == .running else { return .failure(.invalidState) }
        guard let deadline = deadlineMonotonicMs else { return .failure(.invalidState) }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        if now >= deadline { return .expired }
        guard stateVersion < Int64.max else { return .failure(.versionOverflow) }
        let fallback: (TimerRunId, Int64)?
        if let duration = nextTimerDurationMs {
            guard let target = fallbackTarget(now: now, duration: duration) else {
                return .failure(fallbackError(duration, now: now))
            }
            fallback = target
        } else {
            fallback = nil
        }
        let remaining = deadline - now
        let plan = PausePlan(
            remainingDurationMs: remaining,
            fallbackRunId: fallback?.0,
            fallbackDeadlineMonotonicMs: fallback?.1
        )
        pendingLifecycle = .pause(
            PendingPause(
                plan: plan,
                baseVersion: stateVersion,
                expectedRunId: runId,
                expectedStatus: status,
                preparedAtMonotonicMs: now,
                remainingDurationMs: remaining,
                originalDeadlineMonotonicMs: deadline,
                fallbackRunId: fallback?.0,
                fallbackDeadlineMonotonicMs: fallback?.1,
                fallbackTotalDurationMs: nextTimerDurationMs
            )
        )
        return .ready(plan)
    }

    func prepareResume(nextTimerDurationMs: Int64? = nil) -> TimerPrepareResult {
        guard status == .paused else { return .failure(.invalidState) }
        guard stateVersion < Int64.max else { return .failure(.versionOverflow) }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        if nextTimerDurationMs != nil &&
            (nextTimerDurationMs! <= 0 || now > Int64.max - nextTimerDurationMs!) {
            return .failure(fallbackError(nextTimerDurationMs, now: now))
        }
        let deadline: Int64
        if let existing = resumeDeadlineAnchorMonotonicMs {
            deadline = existing
        } else {
            guard now <= Int64.max - remainingDurationMs else {
                return .failure(.deadlineOverflow)
            }
            deadline = now + remainingDurationMs
            resumeDeadlineAnchorMonotonicMs = deadline
        }
        let fallback = fallbackTarget(now: now, duration: nextTimerDurationMs)
        let plan = ResumePlan(
            deadlineMonotonicMs: deadline,
            fallbackRunId: fallback?.0,
            fallbackDeadlineMonotonicMs: fallback?.1
        )
        pendingLifecycle = .resume(
            PendingResume(
                plan: plan,
                baseVersion: stateVersion,
                expectedRunId: runId,
                expectedStatus: status,
                preparedAtMonotonicMs: now,
                remainingDurationMs: remainingDurationMs,
                resumeDeadlineMonotonicMs: deadline,
                fallbackRunId: fallback?.0,
                fallbackDeadlineMonotonicMs: fallback?.1,
                fallbackTotalDurationMs: nextTimerDurationMs
            )
        )
        return .ready(plan)
    }

    func prepareCancel() -> TimerPrepareResult {
        guard status == .running || status == .paused else {
            return .failure(.invalidState)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        let remaining: Int64
        let expiryAt: Int64?
        if status == .running {
            guard let deadline = deadlineMonotonicMs else {
                return .failure(.invalidState)
            }
            if now >= deadline { return .expired }
            remaining = deadline - now
            expiryAt = deadline
        } else {
            remaining = remainingDurationMs
            expiryAt = resumeDeadlineAnchorMonotonicMs
            if let expiry = expiryAt, now >= expiry { return .expired }
        }
        guard stateVersion < Int64.max else { return .failure(.versionOverflow) }
        let plan = CancelPlan(remainingDurationMs: remaining)
        pendingLifecycle = .cancel(
            PendingCancel(
                plan: plan,
                baseVersion: stateVersion,
                expectedRunId: runId,
                expectedStatus: status,
                preparedAtMonotonicMs: now,
                remainingDurationMs: remaining,
                expiryAtMonotonicMs: expiryAt
            )
        )
        return .ready(plan)
    }

    func preparePoll() -> TimerPrepareResult {
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        let expiryAt: Int64
        switch status {
        case .running:
            guard let deadline = deadlineMonotonicMs else {
                return .failure(.invalidState)
            }
            if now < deadline { return .noChange }
            expiryAt = deadline
        case .paused:
            guard let anchor = resumeDeadlineAnchorMonotonicMs, now >= anchor else {
                return .noChange
            }
            expiryAt = anchor
        default:
            return .noChange
        }
        guard stateVersion < Int64.max else { return .failure(.versionOverflow) }
        let plan = PollPlan()
        pendingLifecycle = .poll(
            PendingPoll(
                plan: plan,
                baseVersion: stateVersion,
                expectedRunId: runId,
                expectedStatus: status,
                preparedAtMonotonicMs: now,
                expiryAtMonotonicMs: expiryAt
            )
        )
        return .ready(plan)
    }

    func prepareCompletion(
        nextTimerDurationMs: Int64?,
        expirySensitive: Bool = false
    ) -> TimerPrepareResult {
        guard status == .running || status == .paused else {
            return .failure(.invalidState)
        }
        guard stateVersion < Int64.max else {
            return .failure(.versionOverflow)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        let fallback: (TimerRunId, Int64)?
        if let duration = nextTimerDurationMs {
            guard let target = fallbackTarget(now: now, duration: duration) else {
                return .failure(fallbackError(duration, now: now))
            }
            fallback = target
        } else {
            fallback = nil
        }
        let expiryAt: Int64?
        if expirySensitive {
            switch status {
            case .running:
                expiryAt = deadlineMonotonicMs
            case .paused:
                expiryAt = resumeDeadlineAnchorMonotonicMs
            default:
                expiryAt = nil
            }
            guard expiryAt != nil else { return .failure(.invalidState) }
        } else {
            expiryAt = nil
        }
        let plan = CompletionPlan(
            nextRunId: fallback?.0,
            nextDeadlineMonotonicMs: fallback?.1
        )
        pendingLifecycle = .completion(
            PendingCompletion(
                plan: plan,
                baseVersion: stateVersion,
                expectedRunId: runId,
                expectedStatus: status,
                preparedAtMonotonicMs: now,
                nextRunId: fallback?.0,
                nextDeadlineMonotonicMs: fallback?.1,
                nextTotalDurationMs: nextTimerDurationMs,
                expirySensitive: expirySensitive,
                expiryAtMonotonicMs: expiryAt
            )
        )
        return .ready(plan)
    }

    func commitCompletion(_ plan: CompletionPlan) -> TimerCommitResult {
        guard case let .completion(pending) = pendingLifecycle,
              pending.plan === plan else {
            return .failure(.invalidPlan)
        }
        if let error = lifecycleCommitGuard(
            baseVersion: pending.baseVersion,
            expectedRunId: pending.expectedRunId,
            expectedStatus: pending.expectedStatus
        ) {
            return .failure(error)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        let expired = pending.expirySensitive &&
            pending.expiryAtMonotonicMs != nil &&
            now >= pending.expiryAtMonotonicMs!
        applyFallbackOrComplete(
            nextRunId: pending.nextRunId,
            nextDeadline: pending.nextDeadlineMonotonicMs,
            nextTotalDurationMs: pending.nextTotalDurationMs,
            now: now
        )
        stateVersion += 1
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        return expired ? .expired(snapshotWithoutClock()) : .completed(snapshotWithoutClock())
    }

    func commitPause(_ plan: PausePlan) -> TimerCommitResult {
        guard case let .pause(pending) = pendingLifecycle,
              pending.plan === plan else {
            return .failure(.invalidPlan)
        }
        if let error = lifecycleCommitGuard(
            baseVersion: pending.baseVersion,
            expectedRunId: pending.expectedRunId,
            expectedStatus: pending.expectedStatus
        ) {
            return .failure(error)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        if now >= pending.originalDeadlineMonotonicMs {
            applyFallbackOrComplete(
                nextRunId: pending.fallbackRunId,
                nextDeadline: pending.fallbackDeadlineMonotonicMs,
                nextTotalDurationMs: pending.fallbackTotalDurationMs,
                now: now
            )
            stateVersion += 1
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            return .expired(snapshotWithoutClock())
        }
        status = .paused
        remainingDurationMs = pending.remainingDurationMs
        deadlineMonotonicMs = nil
        lastObservedMs = now
        stateVersion += 1
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        return .paused(snapshotWithoutClock())
    }

    func commitResume(_ plan: ResumePlan) -> TimerCommitResult {
        guard case let .resume(pending) = pendingLifecycle,
              pending.plan === plan else {
            return .failure(.invalidPlan)
        }
        if let error = lifecycleCommitGuard(
            baseVersion: pending.baseVersion,
            expectedRunId: pending.expectedRunId,
            expectedStatus: pending.expectedStatus
        ) {
            return .failure(error)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        if now >= pending.resumeDeadlineMonotonicMs {
            applyFallbackOrComplete(
                nextRunId: pending.fallbackRunId,
                nextDeadline: pending.fallbackDeadlineMonotonicMs,
                nextTotalDurationMs: pending.fallbackTotalDurationMs,
                now: now
            )
            stateVersion += 1
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            return .expired(snapshotWithoutClock())
        }
        status = .running
        deadlineMonotonicMs = pending.resumeDeadlineMonotonicMs
        lastObservedMs = now
        stateVersion += 1
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        return .resumed(snapshotWithoutClock())
    }

    func commitCancel(_ plan: CancelPlan) -> TimerCommitResult {
        guard case let .cancel(pending) = pendingLifecycle,
              pending.plan === plan else {
            return .failure(.invalidPlan)
        }
        if let error = lifecycleCommitGuard(
            baseVersion: pending.baseVersion,
            expectedRunId: pending.expectedRunId,
            expectedStatus: pending.expectedStatus
        ) {
            return .failure(error)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        if let expiryAt = pending.expiryAtMonotonicMs, now >= expiryAt {
            complete()
            lastObservedMs = now
            stateVersion += 1
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            return .expired(snapshotWithoutClock())
        }
        remainingDurationMs = pending.remainingDurationMs
        deadlineMonotonicMs = nil
        status = .cancelled
        lastObservedMs = now
        stateVersion += 1
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        return .cancelled(snapshotWithoutClock())
    }

    func commitPoll(_ plan: PollPlan) -> TimerCommitResult {
        guard case let .poll(pending) = pendingLifecycle,
              pending.plan === plan else {
            return .failure(.invalidPlan)
        }
        if let error = lifecycleCommitGuard(
            baseVersion: pending.baseVersion,
            expectedRunId: pending.expectedRunId,
            expectedStatus: pending.expectedStatus
        ) {
            return .failure(error)
        }
        let now: Int64
        do {
            now = try peekClock()
        } catch let error as TimerClockFailure {
            return .failure(error == .invalidClock ? .invalidClock : .clockRegression)
        } catch {
            return .failure(.invalidClock)
        }
        guard now >= pending.expiryAtMonotonicMs else {
            return .failure(.planStale)
        }
        complete()
        lastObservedMs = now
        stateVersion += 1
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        return .completed(snapshotWithoutClock())
    }

    private func fallbackTarget(
        now: Int64,
        duration: Int64?
    ) -> (TimerRunId, Int64)? {
        guard let duration else { return nil }
        guard duration > 0, now <= Int64.max - duration else { return nil }
        return (TimerRunId(value: idGenerator()), now + duration)
    }

    private func fallbackError(_ duration: Int64?, now: Int64) -> TimerEngineError {
        guard let duration else { return .invalidState }
        if duration <= 0 { return .invalidDuration }
        if now > Int64.max - duration { return .deadlineOverflow }
        return .invalidState
    }

    private func applyFallbackOrComplete(
        nextRunId: TimerRunId?,
        nextDeadline: Int64?,
        nextTotalDurationMs: Int64?,
        now: Int64
    ) {
        if let nextRunId, let nextDeadline, let nextTotalDurationMs {
            runId = nextRunId
            status = .running
            totalDurationMs = nextTotalDurationMs
            remainingDurationMs = nextTotalDurationMs
            deadlineMonotonicMs = nextDeadline
            lastObservedMs = now
        } else {
            complete()
            lastObservedMs = now
        }
    }

    private func lifecycleCommitGuard(
        baseVersion: Int64,
        expectedRunId: TimerRunId?,
        expectedStatus: TimerRunStatus
    ) -> TimerEngineError? {
        if stateVersion >= Int64.max { return .versionOverflow }
        if baseVersion != stateVersion ||
            expectedRunId != runId ||
            expectedStatus != status {
            return .planStale
        }
        return nil
    }

    func pause() -> TimerOperationResult {
        guard status == .running else {
            return .failure(.invalidState)
        }
        guard stateVersion < Int64.max else {
            return .failure(.versionOverflow)
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
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            bumpVersion()
            return .expired(snapshotWithoutClock())
        }

        remainingDurationMs = deadline - now
        deadlineMonotonicMs = nil
        status = .paused
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        bumpVersion()
        return .success(snapshotWithoutClock())
    }

    func resume() -> TimerOperationResult {
        guard status == .paused else {
            return .failure(.invalidState)
        }
        guard stateVersion < Int64.max else {
            return .failure(.versionOverflow)
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
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        bumpVersion()
        return .success(snapshotWithoutClock())
    }

    func cancel() -> TimerOperationResult {
        guard status == .running || status == .paused else {
            return .failure(.invalidState)
        }
        guard stateVersion < Int64.max else {
            return .failure(.versionOverflow)
        }

        if status == .paused {
            deadlineMonotonicMs = nil
            status = .cancelled
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            bumpVersion()
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
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            bumpVersion()
            return .expired(snapshotWithoutClock())
        }
        remainingDurationMs = deadline - now
        deadlineMonotonicMs = nil
        status = .cancelled
        pendingLifecycle = nil
        resumeDeadlineAnchorMonotonicMs = nil
        bumpVersion()
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
        guard stateVersion < Int64.max else {
            return .failure(.versionOverflow)
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
            pendingLifecycle = nil
            resumeDeadlineAnchorMonotonicMs = nil
            bumpVersion()
            return .expired(snapshotWithoutClock())
        }
        remainingDurationMs = deadline - now
        bumpVersion()
        return .success(snapshotWithoutClock())
    }

    internal func setStateVersionForTesting(_ version: Int64) {
        stateVersion = version
    }

    internal func debugState() -> TimerInternalState {
        TimerInternalState(
            runId: runId,
            status: status,
            totalDurationMs: totalDurationMs,
            remainingDurationMs: remainingDurationMs,
            deadlineMonotonicMs: deadlineMonotonicMs,
            lastObservedMs: lastObservedMs,
            stateVersion: stateVersion
        )
    }

    private func bumpVersion() {
        stateVersion += 1
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
