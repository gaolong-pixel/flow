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
}
