import Foundation

/// Product run state for the current node.
///
/// READY    - node is shown, not started yet.
/// RUNNING  - COUNT is being performed / TIMER is counting down.
/// PAUSED   - TIMER countdown frozen; remaining time retained.
/// EXPIRED  - TIMER reached zero. The node is NOT completed automatically.
enum NodeRunState: String, Equatable {
    case ready
    case running
    case paused
    case expired
}

/// One-shot haptic milestones. Each timer instance emits at most one of each.
enum RunHapticEvent: Equatable {
    case timerWarning
    case timerExpired
}

struct PlanRunSnapshot: Equatable {
    let runId: String
    let plan: Routine
    let nodeIndex: Int
    let nodeState: NodeRunState
    let loopCurrentNode: Bool
    let completedNodes: Int
    let remainingMs: Int64?
    let timerRunId: Int64?
    let runStartedAtEpochMs: Int64
    let finalized: Bool

    var node: RoutineStep? {
        plan.steps.indices.contains(nodeIndex) ? plan.steps[nodeIndex] : nil
    }

    var totalNodes: Int { plan.steps.count }
    var isFinalNode: Bool { nodeIndex == plan.steps.count - 1 }
    var totalTimerMs: Int64? { node?.type == .timer ? node?.durationMs : nil }
    var hasNextNode: Bool { nodeIndex < plan.steps.count - 1 }
}

/// In-memory only result. Never written to PlanStore.
struct RunSummary: Equatable {
    let runId: String
    let planName: String
    let completedNodes: Int
    let totalNodes: Int
    let elapsedMs: Int64
    let completedNormally: Bool
}

/// Pure, in-memory state machine for one plan run. Runtime state is never
/// persisted.
///
/// Time rules:
/// - TIMER remaining time is always derived from the monotonic deadline.
/// - refresh() is the only transition to `.expired`; it is idempotent.
/// - `.expired` never completes the node automatically.
@MainActor
final class PlanRunController {
    static let warningThresholdMs: Int64 = 3_000

    let plan: Routine
    let runId: String

    private let monotonic: RunMonotonicClock
    private let wallClock: RunWallClock

    private var index = 0
    private var state: NodeRunState = .ready
    private var loop: Bool
    private var completed = 0
    private var remaining: Int64?
    private var deadline: Int64?
    private var finalized = false
    private var exited = false
    private var timerRunId: Int64 = 0
    private var activeTimerRunId: Int64?
    private var warned = false
    private var expiryEventSent = false
    private var pendingEvents: [RunHapticEvent] = []
    private let startedAtEpochMs: Int64

    init(
        plan: Routine,
        initialLoop: Bool = false,
        monotonic: RunMonotonicClock,
        wallClock: RunWallClock = SystemRunWallClock(),
        runId: String = UUID().uuidString
    ) {
        precondition(!plan.steps.isEmpty, "A plan run needs at least one node")
        self.plan = plan
        self.runId = runId
        self.monotonic = monotonic
        self.wallClock = wallClock
        self.loop = initialLoop
        self.startedAtEpochMs = wallClock.nowEpochMs
    }

    private var node: RoutineStep? {
        plan.steps.indices.contains(index) ? plan.steps[index] : nil
    }

    func snapshot() -> PlanRunSnapshot {
        PlanRunSnapshot(
            runId: runId,
            plan: plan,
            nodeIndex: index,
            nodeState: state,
            loopCurrentNode: loop,
            completedNodes: completed,
            remainingMs: remainingLocked(now: monotonic.nowMs),
            timerRunId: activeTimerRunId,
            runStartedAtEpochMs: startedAtEpochMs,
            finalized: finalized
        )
    }

    /// Starts the current READY node. COUNT becomes RUNNING; TIMER gets a
    /// fresh monotonic deadline for its full duration.
    @discardableResult
    func startNode() -> Bool {
        guard !finalized, !exited, state == .ready, let current = node else {
            return false
        }
        if current.type == .timer {
            guard let duration = current.durationMs else { return false }
            timerRunId += 1
            activeTimerRunId = timerRunId
            remaining = duration
            deadline = safeAdd(monotonic.nowMs, duration)
            warned = false
            expiryEventSent = false
        } else {
            remaining = nil
            deadline = nil
            activeTimerRunId = nil
        }
        state = .running
        return true
    }

    /// Recomputes TIMER remaining from the monotonic deadline. Detects expiry
    /// and queues the one-shot haptic events. Idempotent; never completes.
    @discardableResult
    func refresh() -> Bool {
        guard !finalized, !exited, state == .running, let current = node else {
            return false
        }
        guard current.type == .timer, let due = deadline else { return false }
        let left = max(0, due - monotonic.nowMs)
        remaining = left
        if left > 0, !warned, left <= Self.warningThresholdMs {
            warned = true
            pendingEvents.append(.timerWarning)
        }
        if left == 0 {
            state = .expired
            deadline = nil
            queueExpiryEventLocked()
            return true
        }
        return false
    }

    @discardableResult
    func pause() -> Bool {
        guard !finalized, !exited, state == .running, let current = node else {
            return false
        }
        guard current.type == .timer, let due = deadline else { return false }
        let left = max(0, due - monotonic.nowMs)
        remaining = left
        deadline = nil
        if left == 0 {
            state = .expired
            queueExpiryEventLocked()
            return false
        }
        state = .paused
        return true
    }

    @discardableResult
    func resume() -> Bool {
        guard !finalized, !exited, state == .paused else { return false }
        guard let left = remaining else { return false }
        if left <= 0 {
            state = .expired
            queueExpiryEventLocked()
            return false
        }
        deadline = safeAdd(monotonic.nowMs, left)
        state = .running
        return true
    }

    /// Completes the current node.
    ///
    /// With loop enabled the current node is reset to READY (TIMER back to its
    /// full duration) and no progress is counted. Otherwise progress advances;
    /// completing the last node finalizes the run.
    @discardableResult
    func completeNode() -> Bool {
        guard !finalized, !exited, let current = node else { return false }
        let allowed: Bool
        switch current.type {
        case .count:
            allowed = state == .running
        case .timer:
            allowed = state == .running || state == .paused || state == .expired
        }
        guard allowed else { return false }

        if loop {
            state = .ready
            remaining = nil
            deadline = nil
            activeTimerRunId = nil
            warned = false
            expiryEventSent = false
            return true
        }

        completed += 1
        remaining = nil
        deadline = nil
        activeTimerRunId = nil
        warned = false
        expiryEventSent = false
        if index == plan.steps.count - 1 {
            finalized = true
            state = .ready
        } else {
            index += 1
            state = .ready
            // Loop applies only to the node it was enabled on.
            loop = false
        }
        return true
    }

    /// Loop changes never advance, reset or count the node by themselves.
    @discardableResult
    func setLoop(_ enabled: Bool) -> Bool {
        guard !finalized, !exited else { return false }
        loop = enabled
        return true
    }

    @discardableResult
    func toggleLoop() -> Bool {
        guard !finalized, !exited else { return false }
        loop.toggle()
        return true
    }

    /// Ends the run early. Returns progress for exit feedback. Returns nil if
    /// the run already finished normally or was already exited.
    func exit() -> RunSummary? {
        guard !exited, !finalized else { return nil }
        exited = true
        deadline = nil
        remaining = nil
        activeTimerRunId = nil
        return summaryLocked(completedNormally: false)
    }

    /// Summary after normal completion. Nil if not finalized.
    func finishedSummary() -> RunSummary? {
        guard finalized, !exited else { return nil }
        return summaryLocked(completedNormally: true)
    }

    func consumeHapticEvents() -> [RunHapticEvent] {
        let events = pendingEvents
        pendingEvents.removeAll()
        return events
    }

    var isFinalized: Bool { finalized }

    private func remainingLocked(now: Int64) -> Int64? {
        guard let current = node, current.type == .timer else { return nil }
        switch state {
        case .ready:
            return current.durationMs
        case .running:
            guard let due = deadline else { return 0 }
            return max(0, due - now)
        case .paused:
            return remaining ?? 0
        case .expired:
            return 0
        }
    }

    private func queueExpiryEventLocked() {
        guard !expiryEventSent else { return }
        expiryEventSent = true
        pendingEvents.append(.timerExpired)
    }

    private func summaryLocked(completedNormally: Bool) -> RunSummary {
        RunSummary(
            runId: runId,
            planName: plan.name,
            completedNodes: completed,
            totalNodes: plan.steps.count,
            elapsedMs: max(0, wallClock.nowEpochMs - startedAtEpochMs),
            completedNormally: completedNormally
        )
    }

    private func safeAdd(_ base: Int64, _ delta: Int64) -> Int64 {
        if delta > 0, base > Int64.max - delta { return Int64.max }
        return base + delta
    }
}
