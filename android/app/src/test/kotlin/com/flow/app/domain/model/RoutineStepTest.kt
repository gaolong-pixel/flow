package com.flow.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineStepTest {

    @Test
    fun createsValidCountStepWithIncrementDefault() {
        val step = RoutineStep.count(
            id = "count-1",
            name = "Push-ups",
            target = 15
        )

        assertEquals("count-1", step.id)
        assertEquals(StepType.COUNT, step.type)
        assertEquals(15, step.target)
        assertEquals(CountMode.INCREMENT, step.countMode)
        assertEquals(null, step.durationMs)
        assertEquals(null, step.category)
        assertEquals(1, step.repeatCount)
        assertTrue(step.isValid)
    }

    @Test
    fun createsValidTimerStep() {
        val step = RoutineStep.timer(
            id = "timer-1",
            name = "Read",
            durationMs = 60_000L,
            category = TimerCategory.NORMAL
        )

        assertEquals(StepType.TIMER, step.type)
        assertEquals(60_000L, step.durationMs)
        assertEquals(TimerCategory.NORMAL, step.category)
        assertEquals(null, step.target)
        assertEquals(null, step.countMode)
        assertTrue(step.isValid)
    }

    @Test
    fun createsRestTimerStep() {
        val step = RoutineStep.timer(
            id = "rest-1",
            name = "Rest",
            durationMs = 30_000L,
            category = TimerCategory.REST
        )

        assertEquals(TimerCategory.REST, step.category)
        assertTrue(step.isValid)
    }

    @Test
    fun rejectsInvalidTimerDurations() {
        val invalidDurations = listOf(0L, 999L, 1_500L, 2_500L)

        invalidDurations.forEach { duration ->
            val step = RoutineStep.timer(
                id = "timer-$duration",
                name = "Timer",
                durationMs = duration
            )
            assertTrue(
                "duration $duration should be rejected",
                step.validationErrors().contains(ValidationError.INVALID_TIMER_DURATION)
            )
            assertFalse(step.isValid)
        }

        assertTrue(
            RoutineStep.timer(id = "ok", name = "Timer", durationMs = 1_000L).isValid
        )
    }

    @Test
    fun rejectsInvalidStepRepeatCount() {
        val step = RoutineStep.count(
            id = "count-repeat",
            name = "Squat",
            target = 10,
            repeatCount = 0
        )

        assertTrue(
            step.validationErrors().contains(ValidationError.INVALID_STEP_REPEAT_COUNT)
        )
    }

    @Test
    fun rejectsMissingNamesAndTargets() {
        val blankName = RoutineStep.count(id = "blank", name = "  ", target = 10)
        val zeroTarget = RoutineStep.count(id = "zero", name = "Push-ups", target = 0)
        val missingCountMode = RoutineStep(
            id = "missing-mode",
            name = "Push-ups",
            type = StepType.COUNT,
            target = 10
        )

        assertTrue(blankName.validationErrors().contains(ValidationError.EMPTY_STEP_NAME))
        assertTrue(zeroTarget.validationErrors().contains(ValidationError.INVALID_COUNT_TARGET))
        assertTrue(missingCountMode.validationErrors().contains(ValidationError.MISSING_COUNT_MODE))
    }

    @Test
    fun enforcesCountAndTimerFieldExclusivity() {
        val countWithTimerFields = RoutineStep.count(
            id = "bad-count",
            name = "Push-ups",
            target = 10
        ).copy(durationMs = 30_000L, category = TimerCategory.NORMAL)

        val timerWithCountFields = RoutineStep.timer(
            id = "bad-timer",
            name = "Read",
            durationMs = 30_000L
        ).copy(target = 10, countMode = CountMode.INCREMENT)

        assertTrue(
            countWithTimerFields.validationErrors()
                .contains(ValidationError.COUNT_HAS_TIMER_FIELDS)
        )
        assertTrue(
            timerWithCountFields.validationErrors()
                .contains(ValidationError.TIMER_HAS_COUNT_FIELDS)
        )
    }

    @Test
    fun rejectsTimerWithoutCategory() {
        val step = RoutineStep.timer(
            id = "no-category",
            name = "Read",
            durationMs = 60_000L
        ).copy(category = null)

        assertTrue(
            step.validationErrors().contains(ValidationError.MISSING_TIMER_CATEGORY)
        )
    }

    @Test
    fun usesExplicitUnicodeWhitespaceForStepNames() {
        val blankNames = listOf(
            "",
            " ",
            "\t",
            "\n",
            "\u00A0",
            "\u2007",
            "\u202F",
            "\u3000"
        )

        blankNames.forEachIndexed { index, blankName ->
            val step = RoutineStep.count(
                id = "blank-$index",
                name = blankName,
                target = 1
            )
            assertTrue(
                "name should be rejected at index $index",
                step.validationErrors().contains(ValidationError.EMPTY_STEP_NAME)
            )
        }

        val named = RoutineStep.count(
            id = "named",
            name = "  Push-ups  ",
            target = 1
        )
        assertTrue(named.isValid)
        assertEquals("Push-ups", named.name)
    }

    @Test
    fun trimsStepNameAtCreationButPreservesInternalWhitespace() {
        val ordinary = RoutineStep.count(
            id = "ordinary",
            name = "  Push-ups  ",
            target = 1
        )
        assertEquals("Push-ups", ordinary.name)

        val unicode = RoutineStep.count(
            id = "unicode",
            name = "\u00A0第 2  组 深蹲\u2007",
            target = 1
        )
        assertEquals("第 2  组 深蹲", unicode.name)
        assertTrue(unicode.isValid)

        val direct = RoutineStep(
            id = "direct",
            name = "\u3000Direct\u202F",
            type = StepType.COUNT,
            target = 1,
            countMode = CountMode.INCREMENT
        )
        assertEquals("Direct", direct.name)
        assertTrue(direct.isValid)
    }
}
