import Foundation
import UIKit
import UserNotifications

/// Testable seam over UNUserNotificationCenter. The production scheduler is
/// the only component that talks to the system notification center.
protocol LocalTimerNotificationScheduler: AnyObject {
    func requestAuthorization()
    func schedule(runId: String, nodeName: String, seconds: Int)
    func cancel(runId: String)
    func clearStale()
}

final class UserNotificationCenterScheduler: LocalTimerNotificationScheduler {
    static let identifierPrefix = "flow.run."

    private let center: UNUserNotificationCenter
    private var authorizationState: Bool?
    private var authorizationRequested = false
    private var pending: [String: (nodeName: String, seconds: Int)] = [:]

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func requestAuthorization() {
        guard !authorizationRequested else { return }
        authorizationRequested = true
        center.getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                self?.apply(settings.authorizationStatus)
            }
        }
    }

    private func apply(_ status: UNAuthorizationStatus) {
        switch status {
        case .authorized, .provisional, .ephemeral:
            authorizationState = true
            rescheduleAll()
        case .denied:
            authorizationState = false
            pending.removeAll()
        case .notDetermined:
            center.requestAuthorization(options: [.alert, .sound]) { [weak self] granted, _ in
                DispatchQueue.main.async {
                    self?.authorizationState = granted
                    if granted {
                        self?.rescheduleAll()
                    } else {
                        self?.pending.removeAll()
                    }
                }
            }
        @unknown default:
            authorizationState = false
        }
    }

    func schedule(runId: String, nodeName: String, seconds: Int) {
        pending[runId] = (nodeName: nodeName, seconds: max(1, seconds))
        removePending(runId: runId)

        switch authorizationState {
        case .some(true):
            add(runId: runId, nodeName: nodeName, seconds: max(1, seconds))
        case .some(false):
            return
        case .none:
            requestAuthorization()
        }
    }

    private func add(runId: String, nodeName: String, seconds: Int) {
        let content = UNMutableNotificationContent()
        content.title = "Flow · 计时结束"
        content.body = "“\(nodeName)”的计时已结束"
        content.sound = .default
        content.threadIdentifier = "flow.run"
        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: TimeInterval(max(1, seconds)),
            repeats: false
        )
        let request = UNNotificationRequest(
            identifier: Self.identifierPrefix + runId,
            content: content,
            trigger: trigger
        )
        center.add(request)
    }

    private func rescheduleAll() {
        for (runId, value) in pending {
            add(runId: runId, nodeName: value.nodeName, seconds: value.seconds)
        }
    }

    func cancel(runId: String) {
        pending[runId] = nil
        removePending(runId: runId)
    }

    private func removePending(runId: String) {
        let identifier = Self.identifierPrefix + runId
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    /// Removes notifications that can no longer belong to a live run (for
    /// example after the process was killed while a timer notification was
    /// already scheduled or delivered).
    func clearStale() {
        let prefix = Self.identifierPrefix
        pending.removeAll()
        center.getPendingNotificationRequests { requests in
            let identifiers = requests.map(\.identifier).filter { $0.hasPrefix(prefix) }
            if !identifiers.isEmpty {
                UNUserNotificationCenter.current()
                    .removePendingNotificationRequests(withIdentifiers: identifiers)
            }
        }
        center.getDeliveredNotifications { delivered in
            let identifiers = delivered.map(\.request.identifier).filter {
                $0.hasPrefix(prefix)
            }
            if !identifiers.isEmpty {
                UNUserNotificationCenter.current()
                    .removeDeliveredNotifications(withIdentifiers: identifiers)
            }
        }
    }
}

/// Maps run lifecycle events to local notification scheduling/cancellation.
/// Authorization denial never blocks the in-app timer.
final class UserNotificationRunSink: RunNotificationSink {
    private let scheduler: LocalTimerNotificationScheduler

    init(scheduler: LocalTimerNotificationScheduler = UserNotificationCenterScheduler()) {
        self.scheduler = scheduler
    }

    func onRunStarted(_ snapshot: PlanRunSnapshot) {
        scheduler.clearStale()
        scheduler.requestAuthorization()
    }

    func onProgress(_ snapshot: PlanRunSnapshot) {
        // iOS has no ongoing progress notification; the scheduled local
        // notification is the only background artifact.
    }

    func onTimerStarted(_ snapshot: PlanRunSnapshot) {
        schedule(snapshot)
    }

    func onTimerPaused(_ snapshot: PlanRunSnapshot) {
        scheduler.cancel(runId: snapshot.runId)
    }

    func onTimerResumed(_ snapshot: PlanRunSnapshot) {
        schedule(snapshot)
    }

    func onTimerCompleted(_ snapshot: PlanRunSnapshot) {
        scheduler.cancel(runId: snapshot.runId)
    }

    func onTimerExpired(_ snapshot: PlanRunSnapshot) {
        scheduler.cancel(runId: snapshot.runId)
    }

    func onRunFinished(_ summary: RunSummary) {
        scheduler.cancel(runId: summary.runId)
    }

    func onRunExited(_ summary: RunSummary) {
        scheduler.cancel(runId: summary.runId)
    }

    func clearStaleNotifications() {
        scheduler.clearStale()
    }

    private func schedule(_ snapshot: PlanRunSnapshot) {
        guard snapshot.nodeState == .running,
              snapshot.node?.type == .timer,
              let remaining = snapshot.remainingMs,
              remaining > 0 else {
            return
        }
        let seconds = Int((remaining + 999) / 1_000)
        scheduler.schedule(
            runId: snapshot.runId,
            nodeName: snapshot.node?.name ?? "当前节点",
            seconds: max(1, seconds)
        )
    }
}

final class UIKitRunHaptics: RunHaptics {
    private let impact = UIImpactFeedbackGenerator(style: .medium)
    private let notification = UINotificationFeedbackGenerator()

    func timerWarning() {
        impact.prepare()
        impact.impactOccurred()
    }

    func timerExpired() {
        notification.prepare()
        notification.notificationOccurred(.success)
    }
}
