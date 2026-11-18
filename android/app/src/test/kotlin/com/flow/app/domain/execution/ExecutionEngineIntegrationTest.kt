package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.TimerRunStatus
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEngineIntegrationTest {

    @Test
    fun mixedCountAndTimerFlowCompletesWithCanonicalRecords() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(
                steps = listOf(
                    count("count-a", 2, CountMode.INCREMENT),
                    timer("timer-a", 10_000L, TimerCategory.NORMAL),
                    count("count-b", 2, CountMode.COMPLETE),
                    timer("rest-a", 5_000L, TimerCategory.REST),
                    count("count-c", 2, CountMode.INCREMENT)
                )
            ),
            wall, mono
        )
        assertEquals(1L, (controller.start(0L) as ExecutionControllerResult.Success).revision)

        wall.current = 1_100L
        controller.incrementCount(1L)
        wall.current = 1_200L
        val countDone = controller.incrementCount(2L) as CountIncrementResult.RoundCompleted
        assertEquals(3L, countDone.revision)
        assertEquals("timer-a", countDone.session.progress.currentStepId)
        assertEquals(TimerStatus.RUNNING, countDone.session.timerState.timerStatus)
        assertNotNull(controller.activeTimerRunId)

        wall.current = 11_000L
        mono.current = 11_000L
        val timerDone = controller.pollTimer(
            3L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(4L, timerDone.revision)
        assertEquals("count-b", timerDone.session.progress.currentStepId)
        assertNull(controller.activeTimerRunId)

        wall.current = 11_200L
        val completeDone = controller.confirmComplete(4L) as StepActionResult.Completed
        assertEquals(5L, completeDone.revision)
        assertEquals("rest-a", completeDone.session.progress.currentStepId)
        assertEquals(TimerStatus.RUNNING, completeDone.session.timerState.timerStatus)
        assertNotNull(controller.activeTimerRunId)

        wall.current = 16_000L
        mono.current = 16_000L
        val restDone = controller.pollTimer(
            5L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(6L, restDone.revision)
        assertEquals("count-c", restDone.session.progress.currentStepId)
        assertNull(controller.activeTimerRunId)

        wall.current = 16_100L
        controller.incrementCount(6L)
        wall.current = 16_200L
        val final = controller.incrementCount(7L) as CountIncrementResult.RoundCompleted

        assertEquals(8L, final.revision)
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(16_200L, final.session.completedAt)
        assertEquals(5, final.session.records.size)
        assertEquals(
            listOf("count-a", "timer-a", "count-b", "rest-a", "count-c"),
            final.session.records.map { it.stepId }
        )
        assertTrue(final.session.records.all { it.outcome == ExecutionStepOutcome.COMPLETED })
        assertNull(controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun stepRepeatCreatesOneRecordPerRoundAndResetsCount() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val repeatedCount = count("count", 2, CountMode.INCREMENT).copy(repeatCount = 2)
        val controller = controller(
            routine(steps = listOf(repeatedCount, timer("timer", 10_000L, TimerCategory.NORMAL))),
            wall, mono
        )
        controller.start(0L)

        wall.current = 1_100L
        controller.incrementCount(1L)
        wall.current = 1_200L
        val first = controller.incrementCount(2L) as CountIncrementResult.RoundCompleted
        assertEquals(2, first.session.progress.stepIteration)
        assertEquals(0, first.session.progress.currentCount)
        assertEquals(1, first.session.records.size)

        wall.current = 1_300L
        controller.incrementCount(3L)
        wall.current = 1_400L
        val second = controller.incrementCount(4L) as CountIncrementResult.RoundCompleted
        assertEquals("timer", second.session.progress.currentStepId)
        assertEquals(1, second.session.progress.stepIteration)
        assertEquals(TimerStatus.RUNNING, second.session.timerState.timerStatus)
        assertEquals(2, second.session.records.size)
        assertTrue(second.session.records.all { it.stepId == "count" })
        assertTrue(second.session.records.all { it.outcome == ExecutionStepOutcome.COMPLETED })

        wall.current = 11_000L
        mono.current = 11_000L
        val final = controller.pollTimer(
            5L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(3, final.session.records.size)
        assertEquals(listOf(1, 2), final.session.records.take(2).map { it.stepIteration })
        assertInvariants(controller)
    }

    @Test
    fun routineRepeatRestartsAtFirstStepAndCompletesCanonically() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(
                repeatCount = 2,
                steps = listOf(
                    count("count", 1, CountMode.INCREMENT),
                    timer("timer", 5_000L, TimerCategory.NORMAL)
                )
            ),
            wall, mono
        )
        controller.start(0L)

        wall.current = 1_100L
        val firstCount = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted
        assertEquals(1, firstCount.session.progress.routineIteration)
        assertEquals("timer", firstCount.session.progress.currentStepId)

        wall.current = 6_000L
        mono.current = 6_000L
        val firstTimer = controller.pollTimer(
            2L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(2, firstTimer.session.progress.routineIteration)
        assertEquals("count", firstTimer.session.progress.currentStepId)

        wall.current = 6_100L
        val secondCount = controller.incrementCount(3L) as CountIncrementResult.RoundCompleted
        assertEquals(2, secondCount.session.progress.routineIteration)
        assertEquals("timer", secondCount.session.progress.currentStepId)

        wall.current = 11_000L
        mono.current = 11_000L
        val final = controller.pollTimer(
            4L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(11_000L, final.session.completedAt)
        assertEquals(4, final.session.records.size)
        assertEquals(
            listOf("count", "timer", "count", "timer"),
            final.session.records.map { it.stepId }
        )
        assertEquals(listOf(1, 1, 2, 2), final.session.records.map { it.routineIteration })
        assertTrue(final.session.records.all { it.outcome == ExecutionStepOutcome.COMPLETED })
        assertInvariants(controller)
    }

    @Test
    fun skipRepeatedTimerRoundsAndFinalCountCompletes() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val repeatedTimer = timer("timer", 10_000L, TimerCategory.NORMAL).copy(repeatCount = 2)
        val controller = controller(
            routine(steps = listOf(repeatedTimer, count("count", 1, CountMode.INCREMENT))),
            wall, mono
        )
        controller.start(0L)

        wall.current = 1_100L
        val firstSkip = controller.skipCurrentRound(1L) as StepActionResult.Skipped
        assertEquals("timer", firstSkip.session.progress.currentStepId)
        assertEquals(2, firstSkip.session.progress.stepIteration)
        assertEquals(1, firstSkip.session.records.size)
        assertEquals(ExecutionStepOutcome.SKIPPED, firstSkip.session.records.single().outcome)
        assertNotNull(controller.activeTimerRunId)

        wall.current = 1_200L
        val secondSkip = controller.skipCurrentRound(2L) as StepActionResult.Skipped
        assertEquals("count", secondSkip.session.progress.currentStepId)
        assertEquals(1, secondSkip.session.progress.stepIteration)
        assertEquals(2, secondSkip.session.records.size)
        assertNull(controller.activeTimerRunId)

        wall.current = 1_300L
        val final = controller.incrementCount(3L) as CountIncrementResult.RoundCompleted
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(3, final.session.records.size)
        assertEquals(
            listOf(ExecutionStepOutcome.SKIPPED, ExecutionStepOutcome.SKIPPED,
                ExecutionStepOutcome.COMPLETED),
            final.session.records.map { it.outcome }
        )
        assertEquals(1, final.session.completedCount)
        assertEquals(2, final.session.skippedCount)
        assertInvariants(controller)
    }

    @Test
    fun pauseResumePreservesCountAndTimerRemainingTime() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(
                steps = listOf(
                    count("count", 2, CountMode.INCREMENT),
                    timer("timer", 10_000L, TimerCategory.NORMAL)
                )
            ),
            wall, mono
        )
        controller.start(0L)
        wall.current = 1_100L
        controller.incrementCount(1L)

        wall.current = 1_200L
        val pausedCount = controller.pause(2L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.PAUSED, pausedCount.session.status)
        assertEquals(1, pausedCount.session.progress.currentCount)
        assertNull(controller.activeTimerReferenceForTesting())

        wall.current = 1_300L
        val resumedCount = controller.resume(3L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.RUNNING, resumedCount.session.status)
        assertEquals(1, resumedCount.session.progress.currentCount)

        wall.current = 1_400L
        val timerStarted = controller.incrementCount(4L) as CountIncrementResult.RoundCompleted
        assertEquals(5L, timerStarted.revision)
        assertEquals(TimerStatus.RUNNING, timerStarted.session.timerState.timerStatus)

        wall.current = 3_000L
        mono.current = 3_000L
        val pausedTimer = controller.pause(5L) as ExecutionControllerResult.Success
        assertEquals(8_000L, pausedTimer.session.timerState.remainingDurationMs)
        assertEquals(TimerStatus.PAUSED, pausedTimer.session.timerState.timerStatus)
        assertNotNull(controller.activeTimerRunId)

        wall.current = 6_000L
        mono.current = 6_000L
        val resumedTimer = controller.resume(6L) as ExecutionControllerResult.Success
        assertEquals(14_000L, resumedTimer.session.timerState.deadline)
        assertEquals(TimerStatus.RUNNING, resumedTimer.session.timerState.timerStatus)

        wall.current = 14_000L
        mono.current = 14_000L
        val final = controller.pollTimer(
            7L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(2, final.session.records.size)
        assertEquals(8L, final.revision)
        assertInvariants(controller)
    }

    @Test
    fun cancelRunningCountAndRunningTimerPreservesNoFalseRecords() {
        val countController = controller(
            routine(steps = listOf(count("count", 2, CountMode.INCREMENT)))
        )
        countController.start(0L)
        val countCancel = countController.cancel(1L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.CANCELLED, countCancel.session.status)
        assertEquals(1_000L, countCancel.session.cancelledAt)
        assertNull(countCancel.session.completedAt)
        assertEquals(0, countCancel.session.records.size)
        assertNull(countController.activeTimerRunId)

        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val timerController = controller(
            routine(steps = listOf(timer("timer", 10_000L, TimerCategory.NORMAL))),
            wall, mono
        )
        timerController.start(0L)
        wall.current = 1_100L
        val timerCancel = timerController.cancel(1L) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.CANCELLED, timerCancel.session.status)
        assertEquals(0, timerCancel.session.records.size)
        assertEquals(TimerStatus.CANCELLED, timerCancel.session.timerState.timerStatus)
        assertNull(timerController.activeTimerRunId)
        assertEquals(
            TimerRunStatus.CANCELLED,
            timerController.timerEngineForTesting().debugState().status
        )
        assertInvariants(timerController)
    }

    @Test
    fun combinedRoutineRepeatSkipPauseAndMixCompletesAllRounds() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(
                repeatCount = 2,
                steps = listOf(
                    count("count-inc", 2, CountMode.INCREMENT).copy(repeatCount = 2),
                    timer("timer", 10_000L, TimerCategory.NORMAL),
                    count("count-complete", 2, CountMode.COMPLETE)
                )
            ),
            wall, mono
        )
        controller.start(0L)

        wall.current = 1_100L
        controller.incrementCount(1L)
        wall.current = 1_200L
        controller.skipCurrentRound(2L) // skip count-inc round 1

        wall.current = 1_300L
        controller.incrementCount(3L)
        wall.current = 1_400L
        val firstTimer = controller.incrementCount(4L) as CountIncrementResult.RoundCompleted
        assertEquals("timer", firstTimer.session.progress.currentStepId)

        wall.current = 3_000L
        mono.current = 3_000L
        controller.pause(5L)
        wall.current = 5_000L
        mono.current = 5_000L
        controller.resume(6L)
        wall.current = 13_000L
        mono.current = 13_000L
        val firstComplete = controller.pollTimer(
            7L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals("count-complete", firstComplete.session.progress.currentStepId)
        assertEquals(1, firstComplete.session.progress.routineIteration)

        wall.current = 13_100L
        val routine2 = controller.confirmComplete(8L) as StepActionResult.Completed
        assertEquals(2, routine2.session.progress.routineIteration)
        assertEquals("count-inc", routine2.session.progress.currentStepId)

        wall.current = 13_200L
        controller.incrementCount(9L)
        wall.current = 13_300L
        controller.skipCurrentRound(10L) // skip count-inc round 1 of routine 2
        wall.current = 13_400L
        controller.incrementCount(11L)
        wall.current = 13_500L
        val secondTimer = controller.incrementCount(12L) as CountIncrementResult.RoundCompleted
        assertEquals("timer", secondTimer.session.progress.currentStepId)

        wall.current = 23_000L
        mono.current = 23_000L
        val lastCount = controller.pollTimer(
            13L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals("count-complete", lastCount.session.progress.currentStepId)
        assertEquals(2, lastCount.session.progress.routineIteration)

        wall.current = 23_100L
        val final = controller.confirmComplete(14L) as StepActionResult.Completed

        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(23_100L, final.session.completedAt)
        assertEquals(8, final.session.records.size)
        assertEquals(
            listOf(
                "count-inc", "count-inc", "timer", "count-complete",
                "count-inc", "count-inc", "timer", "count-complete"
            ),
            final.session.records.map { it.stepId }
        )
        assertEquals(
            listOf(
                ExecutionStepOutcome.SKIPPED, ExecutionStepOutcome.COMPLETED,
                ExecutionStepOutcome.COMPLETED, ExecutionStepOutcome.COMPLETED,
                ExecutionStepOutcome.SKIPPED, ExecutionStepOutcome.COMPLETED,
                ExecutionStepOutcome.COMPLETED, ExecutionStepOutcome.COMPLETED
            ),
            final.session.records.map { it.outcome }
        )
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2, 2),
            final.session.records.map { it.routineIteration })
        assertEquals(15L, final.revision)
        assertNull(controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun repeatedTimerNaturallyExpiresEachRound() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val repeatedTimer = timer("timer", 10_000L, TimerCategory.NORMAL).copy(repeatCount = 2)
        val controller = controller(
            routine(steps = listOf(repeatedTimer, count("count", 1, CountMode.INCREMENT))),
            wall, mono
        )
        controller.start(0L)

        wall.current = 11_000L
        mono.current = 11_000L
        val first = controller.pollTimer(
            1L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(2L, first.revision)
        assertEquals(1, first.session.records.size)
        assertEquals(2, first.session.progress.stepIteration)
        assertEquals("timer", first.session.progress.currentStepId)
        assertEquals(TimerStatus.RUNNING, first.session.timerState.timerStatus)
        assertNotNull(controller.activeTimerRunId)

        wall.current = 21_000L
        mono.current = 21_000L
        val second = controller.pollTimer(
            2L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(3L, second.revision)
        assertEquals("count", second.session.progress.currentStepId)
        assertEquals(1, second.session.progress.stepIteration)
        assertNull(controller.activeTimerRunId)
        assertEquals(2, second.session.records.size)

        wall.current = 21_100L
        val final = controller.incrementCount(3L) as CountIncrementResult.RoundCompleted
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(3, final.session.completedCount)
        assertEquals(0, final.session.skippedCount)
        assertEquals(listOf(1, 2), final.session.records.take(2).map { it.stepIteration })
        assertInvariants(controller)
    }

    @Test
    fun multiplePauseResumeCyclesPreserveOnlyUnconsumedTime() {
        val wall = IntegrationWallClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(steps = listOf(timer("timer", 10_000L, TimerCategory.NORMAL))),
            wall, mono
        )
        controller.start(0L)

        wall.current = 3_000L
        mono.current = 3_000L
        val firstPause = controller.pause(1L) as ExecutionControllerResult.Success
        assertEquals(8_000L, firstPause.session.timerState.remainingDurationMs)

        wall.current = 5_000L
        mono.current = 5_000L
        val firstResume = controller.resume(2L) as ExecutionControllerResult.Success
        assertEquals(13_000L, firstResume.session.timerState.deadline)

        wall.current = 7_000L
        mono.current = 7_000L
        val secondPause = controller.pause(3L) as ExecutionControllerResult.Success
        assertEquals(6_000L, secondPause.session.timerState.remainingDurationMs)
        assertEquals(ExecutionStatus.PAUSED, secondPause.session.status)

        wall.current = 9_000L
        mono.current = 9_000L
        val secondResume = controller.resume(4L) as ExecutionControllerResult.Success
        assertEquals(15_000L, secondResume.session.timerState.deadline)
        assertEquals(TimerStatus.RUNNING, secondResume.session.timerState.timerStatus)

        wall.current = 15_000L
        mono.current = 15_000L
        val final = controller.pollTimer(
            5L, controller.activeTimerRunId!!
        ) as ExecutionControllerResult.Success
        assertEquals(ExecutionStatus.COMPLETED, final.session.status)
        assertEquals(1, final.session.records.size)
        assertEquals(1, final.session.completedCount)
        assertEquals(0, final.session.skippedCount)
        assertEquals(6L, final.revision)
        assertInvariants(controller)
    }

    private fun assertInvariants(controller: ExecutionController) {
        val session = controller.snapshot().session
        val step = session.routineSnapshot.steps.getOrNull(session.progress.currentStepIndex)
        val reference = controller.activeTimerReferenceForTesting()
        val engine = controller.timerEngineForTesting().debugState()
        if (step?.type == StepType.COUNT) {
            assertNull(reference)
        }
        if (session.timerState.timerStatus == TimerStatus.RUNNING ||
            session.timerState.timerStatus == TimerStatus.PAUSED
        ) {
            assertNotNull(reference)
            assertEquals(step?.type, StepType.TIMER)
            assertEquals(reference?.stepKey, controller.currentTimerStepKey())
            assertEquals(reference?.runId, engine.runId)
        }
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED
        ) {
            assertNull(reference)
        }
        val keys = session.records.map {
            "${it.routineIteration}/${it.stepId}/${it.stepIteration}"
        }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(session.records.all { it.endedAt >= it.startedAt })
    }

    private fun controller(
        routine: Routine,
        wall: IntegrationWallClock = IntegrationWallClock(1_000L),
        mono: ExecutionFakeMonotonicClock = ExecutionFakeMonotonicClock(1_000L)
    ): ExecutionController = ExecutionController(
        clock = wall,
        initialState = ExecutionSession.create(routine, "integration-session"),
        initialRevision = 0L,
        monotonicClock = mono
    )

    private fun routine(
        steps: List<RoutineStep>,
        repeatCount: Int = 1
    ) = Routine(
        id = "integration",
        name = "Integration",
        repeatCount = repeatCount,
        steps = steps,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun timer(id: String, duration: Long, category: TimerCategory) = RoutineStep(
        id = id,
        name = id,
        type = StepType.TIMER,
        durationMs = duration,
        category = category
    )

    private fun count(id: String, target: Int, mode: CountMode) = RoutineStep(
        id = id,
        name = id,
        type = StepType.COUNT,
        target = target,
        countMode = mode
    )

    private class IntegrationWallClock(var current: Long) : ExecutionClock {
        override fun now(): Long = current
    }
}
