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

/// Scheduling status of a node inside one run.
///
/// PENDING    - not reached yet, or left without progress.
/// CURRENT    - the active execution target. Only one node is current.
/// DEFERRED   - was started (or reached expiry) and the user deliberately
///              switched away. It stays executable and keeps its progress.
/// COMPLETED  - counted as completed and removed from the execution roller.
/// SKIPPED    - explicitly not done in this run. Never counted as completed.
enum NodeStatus: String, Equatable {
    case pending
    case current
    case deferred
    case completed
    case skipped
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
    var nodeStatuses: [NodeStatus] = []
    var skippedNodes: Int = 0
    var nodeRemainingMs: [Int64?] = []

    var node: RoutineStep? {
        plan.steps.indices.contains(nodeIndex) ? plan.steps[nodeIndex] : nil
    }

    var totalNodes: Int { plan.steps.count }
    var isFinalNode: Bool { !hasSelectableNode }
    var totalTimerMs: Int64? { node?.type == .timer ? node?.durationMs : nil }
    var hasNextNode: Bool { hasSelectableNode }
    var currentNodeStatus: NodeStatus {
        nodeStatuses.indices.contains(nodeIndex) ? nodeStatuses[nodeIndex] : .current
    }
    func remainingFor(_ index: Int) -> Int64? {
        nodeRemainingMs.indices.contains(index) ? nodeRemainingMs[index] : nil
    }

    var hasSelectableNode: Bool {
        plan.steps.indices.contains { index in
            index != nodeIndex &&
                (nodeStatuses.indices.contains(index)
                    ? (nodeStatuses[index] == .pending || nodeStatuses[index] == .deferred)
                    : true)
        }
    }

    /// Node indexes that remain in the execution roller, in plan order.
    /// Completed nodes leave the roller; skipped nodes stay visible as a
    /// read-only record so the flow still reads as a whole.
    var visibleNodeIndices: [Int] {
        plan.steps.indices.filter { index in
            guard nodeStatuses.indices.contains(index) else { return true }
            return nodeStatuses[index] != .completed
        }
    }

    var completedNodeIndices: [Int] {
        plan.steps.indices.filter {
            nodeStatuses.indices.contains($0) && nodeStatuses[$0] == .completed
        }
    }

    var skippedNodeIndices: [Int] {
        plan.steps.indices.filter {
            nodeStatuses.indices.contains($0) && nodeStatuses[$0] == .skipped
        }
    }

    var deferredNodeIndices: [Int] {
        plan.steps.indices.filter {
            nodeStatuses.indices.contains($0) && nodeStatuses[$0] == .deferred
        }
    }
}

/// In-memory only result. Never written to PlanStore.
struct RunSummary: Equatable {
    let runId: String
    let planName: String
    let completedNodes: Int
    let totalNodes: Int
    let elapsedMs: Int64
    let completedNormally: Bool
    var skippedNodes: Int = 0
}

/// Pure, in-memory state machine for one plan run. Runtime state is never
/// persisted.
///
/// Time rules:
/// - TIMER remaining time is always derived from the monotonic deadline.
/// - refresh() is the only transition to `.expired`; it is idempotent.
/// - `.expired` never completes the node automatically.
///
/// Scheduling rules:
/// - Only one node is CURRENT. The execution order is a recommendation, not a
///   constraint: selectNode() can switch to any PENDING or DEFERRED node.
/// - Switching away from a RUNNING TIMER freezes its remaining time (PAUSED)
///   and marks it DEFERRED. Switching away from a RUNNING COUNT keeps its
///   count and RUNNING state, but it is not the current target.
/// - Completing or skipping a node removes it from the roller and selects the
///   next PENDING/DEFERRED node in plan order, wrapping around once.
/// - Timer deadlines, completion, notifications and summaries keep the exact
///   semantics of the previous linear controller for the active node.
@MainActor
final class PlanRunController {
    static let warningThresholdMs: Int64 = 3_000

    let plan: Routine
    let runId: String

    private let monotonic: RunMonotonicClock
    private let wallClock: RunWallClock

    private var index = 0
    private var loop: Bool
    private var completed = 0
    private var skipped = 0
    private var deadline: Int64?
    private var finalized = false
    private var exited = false
    private var timerRunId: Int64 = 0

    private var statuses: [NodeStatus]
    private var states: [NodeRunState]
    private var remainingByNode: [Int64?]
    private var timerRunIds: [Int64?]
    private var warnedByNode: [Bool]
    private var expiryEventSentByNode: [Bool]
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
        let count = plan.steps.count
        self.statuses = (0..<count).map { $0 == 0 ? .current : .pending }
        self.states = Array(repeating: .ready, count: count)
        self.remainingByNode = Array(repeating: nil, count: count)
        self.timerRunIds = Array(repeating: nil, count: count)
        self.warnedByNode = Array(repeating: false, count: count)
        self.expiryEventSentByNode = Array(repeating: false, count: count)
    }

    private var node: RoutineStep? {
        plan.steps.indices.contains(index) ? plan.steps[index] : nil
    }

    private var state: NodeRunState {
        get { states[index] }
        set { states[index] = newValue }
    }

    private var remaining: Int64? {
        get { remainingByNode[index] }
        set { remainingByNode[index] = newValue }
    }

    private var activeTimerRunId: Int64? {
        get { timerRunIds[index] }
        set { timerRunIds[index] = newValue }
    }

    private var warned: Bool {
        get { warnedByNode[index] }
        set { warnedByNode[index] = newValue }
    }

    private var expiryEventSent: Bool {
        get { expiryEventSentByNode[index] }
        set { expiryEventSentByNode[index] = newValue }
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
            finalized: finalized,
            nodeStatuses: statuses,
            skippedNodes: skipped,
            nodeRemainingMs: plan.steps.indices.map { position in
                perNodeRemaining(position, now: monotonic.nowMs)
            }
        )
    }

    /// Starts the current READY node. COUNT becomes RUNNING; TIMER gets a
    /// fresh monotonic deadline for its full duration.
    @discardableResult
    func startNode() -> Bool {
        guard !finalized, !exited, state == .ready, statuses[index] == .current,
              let current = node else {
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
        guard !finalized, !exited, state == .running,
              statuses[index] == .current, let current = node else {
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
        guard !finalized, !exited, state == .running,
              statuses[index] == .current, let current = node else {
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
        guard !finalized, !exited, state == .paused,
              statuses[index] == .current else {
            return false
        }
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

    /// Makes `targetIndex` the current execution target.
    ///
    /// If the previous target was a RUNNING TIMER, its remaining time is frozen
    /// and preserved. A RUNNING COUNT keeps its performed count. We never let
    /// scrolling or view updates call this function; only an explicit tap does.
    @discardableResult
    func selectNode(_ targetIndex: Int) -> Bool {
        guard !finalized, !exited, plan.steps.indices.contains(targetIndex),
              targetIndex != index else {
            return false
        }
        let targetStatus = statuses[targetIndex]
        guard targetStatus == .pending ||
              targetStatus == .deferred ||
              targetStatus == .skipped else {
            return false
        }
        if targetStatus == .skipped {
            // Selecting a skipped node resumes it; it is no longer "currently
            // skipped" from the flow's point of view.
            skipped = max(0, skipped - 1)
        }

        suspendCurrentForSwitch()
        index = targetIndex
        statuses[targetIndex] = .current
        deadline = nil
        loop = false
        if states[targetIndex] == .running && plan.steps[targetIndex].type == .timer {
            // Defensive: a saved TIMER can never legitimately be RUNNING while
            // it is not the current target.
            if (remainingByNode[targetIndex] ?? 0) <= 0 {
                states[targetIndex] = .expired
            } else {
                states[targetIndex] = .paused
            }
        }
        return true
    }

    /// Explicitly skips the current node in this run. It never counts towards
    /// completedNodes; the node stays in the roller as SKIPPED and remains
    /// selectable/resumable with its execution state and progress retained.
    /// A running TIMER is frozen as PAUSED; a running COUNT keeps RUNNING; a
    /// never-started node stays READY. The next PENDING/DEFERRED node becomes
    /// current. If no executable node remains, the run finalizes normally.
    @discardableResult
    func skipCurrent() -> Bool {
        guard !finalized, !exited, node != nil else { return false }
        // Skipping needs a different executable node to become current. If the
        // current node is the last PENDING/DEFERRED target, keep it instead of
        // ending the run and losing the skipped-node re-entry path.
        guard hasOtherSelectableNode else { return false }
        // Reuse the same suspend rules as an explicit target switch. This
        // freezes a running TIMER and preserves its remaining time, while a
        // COUNT keeps its RUNNING state.
        suspendCurrentForSwitch()
        statuses[index] = .skipped
        skipped += 1
        loop = false
        advanceAfterRemoval()
        return true
    }

    /// Completes the current node.
    ///
    /// With loop enabled the current node is reset to READY (TIMER back to its
    /// full duration, COUNT back to zero) and no progress is counted.
    /// Otherwise the node is COMPLETED, removed from the roller and the next
    /// selectable node becomes current without starting automatically.
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
        statuses[index] = .completed
        states[index] = .ready
        remaining = nil
        deadline = nil
        activeTimerRunId = nil
        warned = false
        expiryEventSent = false
        loop = false
        advanceAfterRemoval()
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

    /// Freezes or stops the previous current target before switching away.
    /// A RUNNING TIMER loses its deadline but keeps the exact remaining budget.
    private func suspendCurrentForSwitch() {
        guard let current = node else { return }
        if state == .running && current.type == .timer {
            if let due = deadline {
                let left = max(0, due - monotonic.nowMs)
                remaining = left
                deadline = nil
                if left == 0 {
                    state = .expired
                    queueExpiryEventLocked()
                } else {
                    state = .paused
                }
            }
        } else {
            deadline = nil
        }
        if states[index] == .ready && statuses[index] != .deferred {
            statuses[index] = .pending
        } else {
            statuses[index] = .deferred
        }
    }

    /// Chooses the next PENDING/DEFERRED node in plan order (wrapping once) and
    /// restores its retained runtime state. Finalizes when none remains.
    private func advanceAfterRemoval() {
        deadline = nil
        let next = (1...plan.steps.count)
            .map { offset in (index + offset) % plan.steps.count }
            .first { candidate in
                statuses[candidate] == .pending || statuses[candidate] == .deferred
            }
        guard let next = next else {
            finalized = true
            states[index] = .ready
            remaining = nil
            activeTimerRunId = nil
            warned = false
            expiryEventSent = false
            return
        }
        index = next
        statuses[next] = .current
        if states[next] == .running && plan.steps[next].type == .timer {
            states[next] = .paused
        }
    }

    private func perNodeRemaining(_ position: Int, now: Int64) -> Int64? {
        guard plan.steps.indices.contains(position) else { return nil }
        let step = plan.steps[position]
        guard step.type == .timer else { return nil }
        if position == index { return remainingLocked(now: now) }
        switch states[position] {
        case .ready:
            return step.durationMs
        case .paused, .expired, .running:
            return remainingByNode[position]
        }
    }

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

    private var hasOtherSelectableNode: Bool {
        plan.steps.indices.contains { candidate in
            candidate != index &&
                (statuses[candidate] == .pending || statuses[candidate] == .deferred)
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
            completedNormally: completedNormally,
            skippedNodes: skipped
        )
    }

    private func safeAdd(_ base: Int64, _ delta: Int64) -> Int64 {
        if delta > 0, base > Int64.max - delta { return Int64.max }
        return base + delta
    }
}
