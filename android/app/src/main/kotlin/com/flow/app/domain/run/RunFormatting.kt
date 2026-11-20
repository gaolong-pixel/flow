package com.flow.app.domain.run

import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import java.util.Locale

/** Countdown text. Rounds up so a running timer never shows 00:00 early. */
fun formatCountdown(remainingMs: Long?): String {
    val totalMs = (remainingMs ?: 0L).coerceAtLeast(0L)
    val totalSeconds = if (totalMs <= 0L) 0L else (totalMs + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = seconds / 60L
    val rest = seconds % 60L
    return when {
        minutes >= 60L -> {
            val hours = minutes / 60L
            String.format(Locale.US, "%d 小时 %d 分", hours, minutes % 60L)
        }
        minutes > 0L -> String.format(Locale.US, "%d 分 %d 秒", minutes, rest)
        else -> "$seconds 秒"
    }
}

fun formatNodeTarget(step: RoutineStep): String = when (step.type) {
    StepType.COUNT -> "目标 ${step.target ?: 0} 次"
    StepType.TIMER -> "计时 ${formatDurationLabel(step.durationMs)}"
}

fun formatDurationLabel(durationMs: Long?): String {
    val ms = durationMs ?: 0L
    val seconds = ms / 1_000L
    return when {
        seconds > 0L && seconds % 3_600L == 0L -> "${seconds / 3_600L} 小时"
        seconds > 0L && seconds % 60L == 0L -> "${seconds / 60L} 分钟"
        else -> "$seconds 秒"
    }
}

fun nodeStateLabel(state: NodeRunState): String = when (state) {
    NodeRunState.READY -> "待开始"
    NodeRunState.RUNNING -> "进行中"
    NodeRunState.PAUSED -> "已暂停"
    NodeRunState.EXPIRED -> "已到期"
}
