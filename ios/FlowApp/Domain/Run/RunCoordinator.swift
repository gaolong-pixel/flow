import Combine
import Foundation

/// Platform notification hooks. iOS schedules and cancels local notifications;
/// Android keeps a foreground-service notification. Both receive the same
/// lifecycle events so notification state always follows the run state.
protocol RunNotificationSink: AnyObject {
    func onRunStarted(_ snapshot: PlanRunSnapshot)
    func onProgress(_ snapshot: PlanRunSnapshot)
    func onTimerStarted(_ snapshot: PlanRunSnapshot)
    func onTimerPaused(_ snapshot: PlanRunSnapshot)
    func onTimerResumed(_ snapshot: PlanRunSnapshot)
    func onTimerCompleted(_ snapshot: PlanRunSnapshot)
    func onTimerExpired(_ snapshot: PlanRunSnapshot)
    func onRunFinished(_ summary: RunSummary)
    func onRunExited(_ summary: RunSummary)
    func clearStaleNotifications()
}

extension RunNotificationSink {
    func onRunStarted(_ snapshot: PlanRunSnapshot) {}
    func onProgress(_ snapshot: PlanRunSnapshot) {}
    func onTimerStarted(_ snapshot: PlanRunSnapshot) {}
    func onTimerPaused(_ snapshot: PlanRunSnapshot) {}
    func onTimerResumed(_ snapshot: PlanRunSnapshot) {}
    func onTimerCompleted(_ snapshot: PlanRunSnapshot) {}
    func onTimerExpired(_ snapshot: PlanRunSnapshot) {}
    func onRunFinished(_ summary: RunSummary) {}
    func onRunExited(_ summary: RunSummary) {}
    func clearStaleNotifications() {}
}

protocol RunHaptics {
    func timerWarning()
    func timerExpired()
}

final class NoopRunNotificationSink: RunNotificationSink {}

struct NoopRunHaptics: RunHaptics {
    func timerWarning() {}
    func timerExpired() {}
}

enum RunStartError: Equatable {
    case alreadyRunning
    case emptyPlan
}

enum RunStartResult: Equatable {
    case started(PlanRunSnapshot)
    case rejected(RunStartError)
}

enum RunRuntimeState: Equatable {
    case idle
    case active(PlanRunSnapshot)
    case finished(RunSummary)
}

/// App/process-scoped owner of the single active run instance.
///
/// At most one run exists at a time. The coordinator forwards notification and
/// haptic lifecycle events, clears resources on finish/exit and never writes to
/// PlanStore. There is no persistence or crash recovery: a new process starts
/// idle.
@MainActor
final class RunCoordinator: ObservableObject {
    @Published private(set) var runtime: RunRuntimeState = .idle
    @Published private(set) var lastExitSummary: RunSummary?

    /// One-shot UI expiry feedback token. It is process-scoped (not stored in
    /// the view) so a recomposition or a return from background cannot replay
    /// the shake for an already-consumed TIMER expiry.
    @Published private(set) var expiryFeedbackToken: Int64?

    private let monotonic: RunMonotonicClock
    private let wallClock: RunWallClock
    private let notifications: RunNotificationSink
    private let haptics: RunHaptics
    private var controller: PlanRunController?
    private var alertedTimerRunID: Int64?

    init(
        monotonic: RunMonotonicClock = SystemRunMonotonicClock(),
        wallClock: RunWallClock = SystemRunWallClock(),
        notifications: RunNotificationSink = NoopRunNotificationSink(),
        haptics: RunHaptics = NoopRunHaptics()
    ) {
        self.monotonic = monotonic
        self.wallClock = wallClock
        self.notifications = notifications
        self.haptics = haptics
        // A fresh process must not revive a previous run or its notifications.
        notifications.clearStaleNotifications()
    }

    var isActive: Bool {
        if case .active = runtime { return true }
        return false
    }

    var currentSnapshot: PlanRunSnapshot? {
        controller?.snapshot()
    }

    func start(plan: Routine, initialLoop: Bool = false) -> RunStartResult {
        guard runtime == .idle, controller == nil else {
            return .rejected(.alreadyRunning)
        }
        guard !plan.steps.isEmpty else {
            return .rejected(.emptyPlan)
        }
        notifications.clearStaleNotifications()
        let fresh = PlanRunController(
            plan: plan,
            initialLoop: initialLoop,
            monotonic: monotonic,
            wallClock: wallClock
        )
        controller = fresh
        alertedTimerRunID = nil
        expiryFeedbackToken = nil
        lastExitSummary = nil
        let snapshot = fresh.snapshot()
        runtime = .active(snapshot)
        notifications.onRunStarted(snapshot)
        notifications.onProgress(snapshot)
        return .started(snapshot)
    }

    @discardableResult
    func startNode() -> Bool {
        guard let active = controller, active.startNode() else { return false }
        let snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        if snapshot.node?.type == .timer, snapshot.nodeState == .running {
            notifications.onTimerStarted(snapshot)
        }
        drainHaptics(active)
        return true
    }

    /// Switches the execution target. The controller freezes a running TIMER
    /// and marks the previous node DEFERRED. Timer notifications are cancelled
    /// before the new node is published so a deferred timer can never fire an
    /// out-of-date alert.
    @discardableResult
    func selectNode(_ targetIndex: Int) -> Bool {
        guard let active = controller else { return false }
        let before = active.snapshot()
        guard active.selectNode(targetIndex) else { return false }
        if before.node?.type == .timer, before.nodeState != .ready {
            notifications.onTimerPaused(before)
        }
        drainHaptics(active)
        let snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        return true
    }

    @discardableResult
    func pause() -> Bool {
        guard let active = controller else { return false }
        let paused = active.pause()
        let snapshot = active.snapshot()
        handlePossibleExpiry(snapshot)
        drainHaptics(active)
        guard paused else {
            if snapshot.nodeState == .expired { publish(snapshot) }
            return false
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        notifications.onTimerPaused(snapshot)
        return true
    }

    @discardableResult
    func resume() -> Bool {
        guard let active = controller else { return false }
        let resumed = active.resume()
        let snapshot = active.snapshot()
        handlePossibleExpiry(snapshot)
        drainHaptics(active)
        guard resumed else {
            if snapshot.nodeState == .expired { publish(snapshot) }
            return false
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        notifications.onTimerResumed(snapshot)
        return true
    }

    @discardableResult
    func completeNode() -> Bool {
        guard let active = controller else { return false }
        let before = active.snapshot()
        guard active.completeNode() else { return false }
        if before.node?.type == .timer {
            notifications.onTimerCompleted(before)
        }
        drainHaptics(active)
        let snapshot = active.snapshot()
        if snapshot.finalized {
            guard let summary = active.finishedSummary() else { return false }
            controller = nil
            alertedTimerRunID = nil
            expiryFeedbackToken = nil
            notifications.onRunFinished(summary)
            runtime = .finished(summary)
            return true
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        return true
    }

    @discardableResult
    func setLoop(_ enabled: Bool) -> Bool {
        guard let active = controller, active.setLoop(enabled) else { return false }
        let snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        return true
    }

    @discardableResult
    func toggleLoop() -> Bool {
        guard let active = controller, active.toggleLoop() else { return false }
        let snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        return true
    }

    /// Recomputes timer remaining and publishes a fresh snapshot. The SwiftUI
    /// ticker only calls this; real remaining time always comes from the
    /// monotonic deadline inside the controller.
    @discardableResult
    func refresh() -> Bool {
        guard let active = controller else { return false }
        active.refresh()
        let snapshot = active.snapshot()
        handlePossibleExpiry(snapshot)
        drainHaptics(active)
        publish(snapshot)
        notifications.onProgress(snapshot)
        return true
    }

    /// Called when the app returns to the foreground, so remaining time and
    /// expired state are recomputed from the monotonic clock.
    func onSceneBecameActive() {
        refresh()
    }

    func exitCurrent() -> RunSummary? {
        guard let active = controller, let summary = active.exit() else { return nil }
        controller = nil
        alertedTimerRunID = nil
        expiryFeedbackToken = nil
        notifications.onRunExited(summary)
        lastExitSummary = summary
        runtime = .idle
        return summary
    }

    func clearExitSummary() {
        lastExitSummary = nil
    }

    func dismissResult() {
        if case .finished = runtime {
            runtime = .idle
        }
    }

    private func publish(_ snapshot: PlanRunSnapshot) {
        runtime = .active(snapshot)
    }

    private func handlePossibleExpiry(_ snapshot: PlanRunSnapshot) {
        guard snapshot.nodeState == .expired, let timerId = snapshot.timerRunId else {
            return
        }
        guard alertedTimerRunID != timerId else { return }
        alertedTimerRunID = timerId
        expiryFeedbackToken = timerId
        notifications.onTimerExpired(snapshot)
    }

    /// Marks the expiry feedback token consumed after the UI has played it
    /// once. Calling this for an older token never clears a newer expiry.
    func consumeExpiryFeedback(_ token: Int64) {
        if expiryFeedbackToken == token {
            expiryFeedbackToken = nil
        }
    }

    private func drainHaptics(_ active: PlanRunController) {
        for event in active.consumeHapticEvents() {
            switch event {
            case .timerWarning:
                haptics.timerWarning()
            case .timerExpired:
                haptics.timerExpired()
            }
        }
    }
}
