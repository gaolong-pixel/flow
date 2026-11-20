package com.flow.app.domain.run

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class RunFormattingTest {
    @Test
    fun countdownRoundsUpAndFormatsMinutes() {
        assertEquals("00:00", formatCountdown(0L))
        assertEquals("00:01", formatCountdown(1L))
        assertEquals("00:01", formatCountdown(999L))
        assertEquals("00:45", formatCountdown(45_000L))
        assertEquals("01:00", formatCountdown(59_500L))
        assertEquals("10:00", formatCountdown(600_000L))
        assertEquals("00:00", formatCountdown(null))
    }

    @Test
    fun countdownSupportsHours() {
        assertEquals("1:00:00", formatCountdown(3_600_000L))
        assertEquals("1:05:09", formatCountdown(3_909_000L))
    }

    @Test
    fun elapsedIsHumanReadable() {
        assertEquals("5 秒", formatElapsed(5_000L))
        assertEquals("1 分 5 秒", formatElapsed(65_000L))
        assertEquals("2 小时 0 分", formatElapsed(7_200_000L))
    }

    @Test
    fun durationAndTargetLabelsMatchPlanModel() {
        assertEquals(
            "计时 2 分钟",
            formatNodeTarget(
                RoutineStep(
                    id = "t",
                    name = "阅读",
                    type = StepType.TIMER,
                    durationMs = 120_000L,
                    category = TimerCategory.NORMAL
                )
            )
        )
        assertEquals(
            "目标 15 次",
            formatNodeTarget(
                RoutineStep(
                    id = "c",
                    name = "俯卧撑",
                    type = StepType.COUNT,
                    target = 15,
                    countMode = CountMode.COMPLETE
                )
            )
        )
        assertEquals("待开始", nodeStateLabel(NodeRunState.READY))
        assertEquals("已到期", nodeStateLabel(NodeRunState.EXPIRED))
    }

    @Test
    fun overallProgressUsesCompletedNodesOnly() {
        assertEquals(0f, flowOverallProgress(0, 5), 0.0001f)
        assertEquals(0.5f, flowOverallProgress(2, 4), 0.0001f)
        assertEquals(1f, flowOverallProgress(4, 4), 0.0001f)
        assertEquals(0f, flowOverallProgress(0, 0), 0.0001f)
        assertEquals(40, flowOverallPercent(2, 5))
    }

    @Test
    fun timerRemainingFractionIsClampedForDrawing() {
        assertEquals(1f, flowTimerRemainingFraction(120_000L, 120_000L), 0.0001f)
        assertEquals(0.5f, flowTimerRemainingFraction(120_000L, 60_000L), 0.0001f)
        assertEquals(0f, flowTimerRemainingFraction(120_000L, 0L), 0.0001f)
        assertEquals(1f, flowTimerRemainingFraction(120_000L, 180_000L), 0.0001f)
        assertEquals(0f, flowTimerRemainingFraction(null, 10_000L), 0.0001f)
    }

    @Test
    fun planDurationLabelNeverInventsAnEstimate() {
        val count = RoutineStep(
            id = "c",
            name = "俯卧撑",
            type = StepType.COUNT,
            target = 10,
            countMode = CountMode.COMPLETE
        )
        val timer = RoutineStep(
            id = "t",
            name = "阅读",
            type = StepType.TIMER,
            durationMs = 300_000L,
            category = TimerCategory.NORMAL
        )
        assertEquals(null, flowPlanDurationLabel(listOf(count)))
        assertEquals(null, flowPlanDurationLabel(emptyList()))
        assertEquals("预计 5 分钟", flowPlanDurationLabel(listOf(timer)))
        assertEquals("计时 5 分钟", flowPlanDurationLabel(listOf(count, timer)))
        assertEquals(300_000L, flowPlanTimerTotalMs(listOf(count, timer)))
    }

    @Test
    fun giantNumbersShrinkForLongValuesAndLargeFontScales() {
        val normal = flowDisplayNumberSizeSp(104f, 2, 1f)
        val long = flowDisplayNumberSizeSp(104f, 5, 1f)
        val largeType = flowDisplayNumberSizeSp(104f, 2, 2f)
        assert(normal > long)
        assert(normal >= largeType)
        assert(long >= 30f)
    }
}
