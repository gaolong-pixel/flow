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

// ---------------------------------------------------------------------------
// Display-only helpers. They never mutate a run or advance a node; the run
// controller remains the only source of truth for progress and remaining time.
// ---------------------------------------------------------------------------

/** Node-based overall progress. This is exactly completedNodes / totalNodes. */
fun flowOverallProgress(completedNodes: Int, totalNodes: Int): Float {
    if (totalNodes <= 0) return 0f
    return (completedNodes.toFloat() / totalNodes.toFloat()).coerceIn(0f, 1f)
}

fun flowOverallPercent(completedNodes: Int, totalNodes: Int): Int =
    (flowOverallProgress(completedNodes, totalNodes) * 100f).toInt()

/** Remaining share of the current TIMER node, clamped for drawing only. */
fun flowTimerRemainingFraction(totalMs: Long?, remainingMs: Long?): Float {
    val total = totalMs ?: return 0f
    if (total <= 0L) return 0f
    val remaining = (remainingMs ?: total).coerceIn(0L, total)
    return remaining.toFloat() / total.toFloat()
}

/** Sum of the TIMER durations that are actually known. COUNT has no estimate. */
fun flowPlanTimerTotalMs(steps: List<RoutineStep>): Long =
    steps.sumOf { step ->
        if (step.type == StepType.TIMER) (step.durationMs ?: 0L) else 0L
    }

/**
 * Reliable duration copy for home/setup:
 * - every node is a TIMER -> total is a real execution estimate;
 * - mixed plan -> only the TIMER share is known, so it is labelled as such;
 * - no TIMER -> null (never invent an estimate).
 */
fun flowPlanDurationLabel(steps: List<RoutineStep>): String? {
    if (steps.isEmpty()) return null
    val timerTotal = flowPlanTimerTotalMs(steps)
    if (timerTotal <= 0L) return null
    val label = formatDurationLabel(timerTotal)
    val allTimers = steps.all { it.type == StepType.TIMER }
    return if (allTimers) "预计 $label" else "计时 $label"
}

/** Giant display numbers shrink for long values and large font scales. */
fun flowDisplayNumberSizeSp(baseSp: Float, characters: Int, fontScale: Float): Float {
    val lengthFactor = when {
        characters <= 2 -> 1f
        characters == 3 -> 0.9f
        characters == 4 -> 0.76f
        characters == 5 -> 0.64f
        else -> 0.55f
    }
    val scaleCap = (fontScale.coerceAtLeast(1f)).let { scale -> (1.3f / scale).coerceIn(0.7f, 1f) }
    return (baseSp * lengthFactor * scaleCap).coerceAtLeast(30f)
}
