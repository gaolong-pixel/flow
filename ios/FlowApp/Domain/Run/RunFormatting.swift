import Foundation

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
