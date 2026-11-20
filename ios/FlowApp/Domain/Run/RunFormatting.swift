import Foundation
import CoreGraphics

/// Countdown text. Rounds up so a running timer never shows 00:00 early.
func formatRunCountdown(_ remainingMs: Int64?) -> String {
    let totalMs = max(0, remainingMs ?? 0)
    let totalSeconds = totalMs <= 0 ? 0 : (totalMs + 999) / 1_000
    let hours = totalSeconds / 3_600
    let minutes = (totalSeconds % 3_600) / 60
    let seconds = totalSeconds % 60
    if hours > 0 {
        return String(format: "%d:%02d:%02d", hours, minutes, seconds)
    }
    return String(format: "%02d:%02d", minutes, seconds)
}

func formatRunElapsed(_ elapsedMs: Int64) -> String {
    let seconds = (max(0, elapsedMs) + 999) / 1_000
    let minutes = seconds / 60
    let rest = seconds % 60
    if minutes >= 60 {
        return "\(minutes / 60) 小时 \(minutes % 60) 分"
    }
    if minutes > 0 {
        return "\(minutes) 分 \(rest) 秒"
    }
    return "\(seconds) 秒"
}

func formatRunDuration(_ durationMs: Int64?) -> String {
    let seconds = (durationMs ?? 0) / 1_000
    if seconds > 0, seconds % 3_600 == 0 {
        return "\(seconds / 3_600) 小时"
    }
    if seconds > 0, seconds % 60 == 0 {
        return "\(seconds / 60) 分钟"
    }
    return "\(seconds) 秒"
}

func formatRunNodeTarget(_ step: RoutineStep) -> String {
    switch step.type {
    case .count:
        return "目标 \(step.target ?? 0) 次"
    case .timer:
        return "计时 \(formatRunDuration(step.durationMs))"
    }
}

func runNodeStateLabel(_ state: NodeRunState) -> String {
    switch state {
    case .ready:
        return "待开始"
    case .running:
        return "进行中"
    case .paused:
        return "已暂停"
    case .expired:
        return "已到期"
    }
}

// ---------------------------------------------------------------------------
// Display-only helpers. They never mutate a run or advance a node; the run
// controller remains the only source of truth for progress and remaining time.
// ---------------------------------------------------------------------------

/// Node-based overall progress. This is exactly completedNodes / totalNodes.
func flowOverallProgress(completed: Int, total: Int) -> Double {
    guard total > 0 else { return 0 }
    return min(1, max(0, Double(completed) / Double(total)))
}

func flowOverallPercent(completed: Int, total: Int) -> Int {
    Int((flowOverallProgress(completed: completed, total: total) * 100).rounded(.down))
}

/// Remaining share of the current TIMER node, clamped for drawing only.
func flowTimerRemainingFraction(totalMs: Int64?, remainingMs: Int64?) -> Double {
    guard let totalMs, totalMs > 0 else { return 0 }
    let remaining = min(max(remainingMs ?? totalMs, 0), totalMs)
    return Double(remaining) / Double(totalMs)
}

/// Sum of the TIMER durations that are actually known. COUNT has no estimate.
func flowPlanTimerTotalMs(steps: [RoutineStep]) -> Int64 {
    steps.reduce(Int64(0)) { partial, step in
        partial + (step.type == .timer ? (step.durationMs ?? 0) : 0)
    }
}

/// Reliable duration copy for home/setup:
/// - every node is a TIMER -> total is a real execution estimate;
/// - mixed plan -> only the TIMER share is known, so it is labelled as such;
/// - no TIMER -> nil (never invent an estimate).
func flowPlanDurationLabel(steps: [RoutineStep]) -> String? {
    guard !steps.isEmpty else { return nil }
    let timerTotal = flowPlanTimerTotalMs(steps: steps)
    guard timerTotal > 0 else { return nil }
    let label = formatRunDuration(timerTotal)
    let allTimers = steps.allSatisfy { $0.type == .timer }
    return allTimers ? "预计 \(label)" : "计时 \(label)"
}

/// Base font size for the giant countdown inside the double ring.
func flowTimerNumberSize(characters: Int) -> CGFloat {
    if characters <= 5 { return 52 }
    if characters <= 7 { return 42 }
    return 34
}
