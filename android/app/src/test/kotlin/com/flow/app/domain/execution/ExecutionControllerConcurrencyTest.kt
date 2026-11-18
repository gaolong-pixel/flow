package com.flow.app.domain.execution

import com.flow.app.domain.execution.timer.TimerRunStatus
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionControllerConcurrencyTest {

    @Test
    fun duplicateIncrementAtSameRevisionHasSingleWinner() {
        val controller = controller(routine(listOf(count("c", 3))))
        controller.start(0L)
        val results = race(
            { controller.incrementCount(1L) },
            { controller.incrementCount(1L) }
        )
        assertEquals(1, results.count { it is CountIncrementResult.Incremented })
        assertEquals(1, results.count { it is CountIncrementResult.Failure })
        assertEquals(1, controller.snapshot().session.progress.currentCount)
        assertEquals(0, controller.snapshot().session.records.size)
        assertEquals(2L, controller.snapshot().revision)
        assertInvariants(controller)
    }

    @Test
    fun duplicateConfirmAtSameRevisionHasSingleWinner() {
        val controller = controller(routine(listOf(count("c", 2, CountMode.COMPLETE))))
        controller.start(0L)
        val results = race(
            { controller.confirmComplete(1L) },
            { controller.confirmComplete(1L) }
        )
        assertEquals(1, results.count { it is StepActionResult.Completed })
        assertEquals(1, results.count { it is StepActionResult.Failure })
        assertEquals(1, controller.snapshot().session.records.size)
        assertEquals(2L, controller.snapshot().revision)
        assertInvariants(controller)
    }

    @Test
    fun duplicateSkipAtSameRevisionHasSingleWinner() {
        val controller = controller(routine(listOf(count("a", 2), count("b", 2))))
        controller.start(0L)
        val results = race(
            { controller.skipCurrentRound(1L) },
            { controller.skipCurrentRound(1L) }
        )
        assertEquals(1, results.count { it is StepActionResult.Skipped })
        assertEquals(1, results.count { it is StepActionResult.Failure })
        assertEquals(1, controller.snapshot().session.records.size)
        assertEquals("b", controller.snapshot().session.progress.currentStepId)
        assertEquals(2L, controller.snapshot().revision)
        assertInvariants(controller)
    }

    @Test
    fun duplicatePauseAtSameRevisionHasSingleWinner() {
        val controller = controller(routine(listOf(timer("t", 10_000L))))
        controller.start(0L)
        val results = race(
            { controller.pause(1L) },
            { controller.pause(1L) }
        )
        assertEquals(1, results.count { it is ExecutionControllerResult.Success })
        assertEquals(1, results.count { it is ExecutionControllerResult.Failure })
        assertEquals(ExecutionStatus.PAUSED, controller.snapshot().session.status)
        assertEquals(TimerStatus.PAUSED, controller.snapshot().session.timerState.timerStatus)
        assertEquals(2L, controller.snapshot().revision)
        assertInvariants(controller)
    }

    @Test
    fun duplicateCancelAtSameRevisionHasSingleWinner() {
        val controller = controller(routine(listOf(timer("t", 10_000L))))
        controller.start(0L)
        val results = race(
            { controller.cancel(1L) },
            { controller.cancel(1L) }
        )
        assertEquals(1, results.count { it is ExecutionControllerResult.Success })
        assertEquals(1, results.count { it is ExecutionControllerResult.Failure })
        assertEquals(ExecutionStatus.CANCELLED, controller.snapshot().session.status)
        assertEquals(2L, controller.snapshot().revision)
        assertNull(controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun expiryRacingSkipAndCancelProducesOneCompletionRecord() {
        val wall = ConcurrencyClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(routine(listOf(timer("t", 10_000L))), wall, mono)
        controller.start(0L)
        wall.current = 11_000L
        mono.current = 11_000L

        val results = race(
            { controller.skipCurrentRound(1L) },
            { controller.cancel(1L) }
        )
        val successes = results.count {
            it is StepActionResult.Completed ||
                it is StepActionResult.Skipped ||
                it is ExecutionControllerResult.Success
        }
        assertEquals(1, successes)
        assertEquals(1, controller.snapshot().session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, controller.snapshot().session.records.single().outcome)
        assertEquals(ExecutionStatus.COMPLETED, controller.snapshot().session.status)
        assertEquals(2L, controller.snapshot().revision)
        assertNull(controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun expiryRacingPauseAndPollCompletesOnlyOnce() {
        val wall = ConcurrencyClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(routine(listOf(timer("t", 10_000L))), wall, mono)
        controller.start(0L)
        val run = controller.activeTimerRunId!!
        wall.current = 11_000L
        mono.current = 11_000L

        val results = race(
            { controller.pause(1L) },
            { controller.pollTimer(1L, run) }
        )
        assertEquals(1, results.count { it is ExecutionControllerResult.Success })
        assertEquals(1, results.count { it is ExecutionControllerResult.Failure })
        assertEquals(ExecutionStatus.COMPLETED, controller.snapshot().session.status)
        assertEquals(1, controller.snapshot().session.records.size)
        assertEquals(2L, controller.snapshot().revision)
        assertInvariants(controller)
    }

    @Test
    fun skipChecksExpiryAtEngineCommitTime() {
        val clock = CommitCrossingClock(1_000L, 9_000L, 9_000L, 10_000L)
        val controller = controller(
            routine(listOf(timer("t", 9_000L))),
            ConcurrencyClock(9_000L),
            clock
        )
        controller.start(0L)

        val result = controller.skipCurrentRound(1L) as StepActionResult.Completed

        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, result.session.records.single().outcome)
        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(2L, result.revision)
        assertNull(controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun skipBeforeDeadlineStillProducesSkippedAtCommit() {
        val clock = CommitCrossingClock(1_000L, 9_000L, 9_000L, 9_999L)
        val controller = controller(
            routine(listOf(timer("t", 9_000L))),
            ConcurrencyClock(9_000L),
            clock
        )
        controller.start(0L)

        val result = controller.skipCurrentRound(1L) as StepActionResult.Skipped

        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.SKIPPED, result.session.records.single().outcome)
        assertEquals(ExecutionStatus.COMPLETED, result.session.status)
        assertEquals(2L, result.revision)
        assertInvariants(controller)
    }

    @Test
    fun skipCrossingDeadlineStartsNextTimerAsCompleted() {
        val clock = CommitCrossingClock(1_000L, 9_000L, 9_000L, 10_000L)
        val controller = controller(
            routine(listOf(timer("a", 9_000L), timer("b", 20_000L))),
            ConcurrencyClock(9_000L),
            clock
        )
        controller.start(0L)
        val oldRun = controller.activeTimerRunId

        val result = controller.skipCurrentRound(1L) as StepActionResult.Completed

        assertEquals(1, result.session.records.size)
        assertEquals(ExecutionStepOutcome.COMPLETED, result.session.records.single().outcome)
        assertEquals("b", result.session.progress.currentStepId)
        assertEquals(TimerStatus.RUNNING, result.session.timerState.timerStatus)
        assertEquals(2L, result.revision)
        assertNotEquals(oldRun, controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun oldRunPollCannotAdvanceNewTimer() {
        val wall = ConcurrencyClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(listOf(timer("a", 10_000L), timer("b", 20_000L))),
            wall, mono
        )
        controller.start(0L)
        val oldRun = controller.activeTimerRunId!!
        wall.current = 11_000L
        mono.current = 11_000L
        val first = controller.pollTimer(1L, oldRun) as ExecutionControllerResult.Success
        val newRun = controller.activeTimerRunId!!
        assertNotEquals(oldRun, newRun)
        assertEquals(2L, first.revision)

        val latePoll = controller.pollTimer(2L, oldRun) as ExecutionControllerResult.Success
        assertEquals(2L, latePoll.revision)
        assertEquals(2L, controller.snapshot().revision)
        assertEquals("b", controller.snapshot().session.progress.currentStepId)
        assertEquals(TimerStatus.RUNNING, controller.snapshot().session.timerState.timerStatus)
        assertEquals(newRun, controller.activeTimerRunId)
        assertInvariants(controller)
    }

    @Test
    fun staleRevisionCannotPauseOrCancelNewTimer() {
        val wall = ConcurrencyClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(listOf(timer("a", 10_000L), timer("b", 20_000L))),
            wall, mono
        )
        controller.start(0L)
        val oldRun = controller.activeTimerRunId!!
        wall.current = 11_000L
        mono.current = 11_000L
        controller.pollTimer(1L, oldRun)
        val newDeadline = controller.snapshot().session.timerState.deadline

        assertEquals(
            ExecutionControllerError.STALE_OPERATION,
            (controller.pause(1L) as ExecutionControllerResult.Failure).error
        )
        assertEquals(
            ExecutionControllerError.STALE_OPERATION,
            (controller.cancel(1L) as ExecutionControllerResult.Failure).error
        )
        assertEquals(newDeadline, controller.snapshot().session.timerState.deadline)
        assertEquals(2L, controller.snapshot().revision)
        assertInvariants(controller)
    }

    @Test
    fun timerToCountLatePollAndOldCountOperationAreIsolated() {
        val wall = ConcurrencyClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(listOf(timer("a", 10_000L), count("c", 2))),
            wall, mono
        )
        controller.start(0L)
        val oldRun = controller.activeTimerRunId!!
        wall.current = 11_000L
        mono.current = 11_000L
        controller.pollTimer(1L, oldRun)
        assertNull(controller.activeTimerRunId)
        assertEquals(StepType.COUNT, controller.snapshot().session.routineSnapshot.steps[
            controller.snapshot().session.progress.currentStepIndex
        ].type)

        val latePoll = controller.pollTimer(2L, oldRun) as ExecutionControllerResult.Success
        assertEquals(2L, latePoll.revision)
        assertEquals(ExecutionStatus.RUNNING, controller.snapshot().session.status)
        assertInvariants(controller)
    }

    @Test
    fun countToTimerLateCountOperationCannotAffectNewTimer() {
        val wall = ConcurrencyClock(1_000L)
        val mono = ExecutionFakeMonotonicClock(1_000L)
        val controller = controller(
            routine(listOf(count("c", 1), timer("t", 10_000L))),
            wall, mono
        )
        controller.start(0L)
        val result = controller.incrementCount(1L) as CountIncrementResult.RoundCompleted
        assertEquals(2L, result.revision)
        val run = controller.activeTimerRunId
        assertNotNull(run)

        val late = controller.incrementCount(1L) as CountIncrementResult.Failure
        assertEquals(ExecutionControllerError.STALE_OPERATION, late.error)
        assertEquals(2L, controller.snapshot().revision)
        assertEquals(run, controller.activeTimerRunId)
        assertInvariants(controller)
    }

    private fun assertInvariants(controller: ExecutionController) {
        val snapshot = controller.snapshot()
        val session = snapshot.session
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
        val signatures = session.records.map {
            listOf(it.stepId, it.routineIteration, it.stepIteration, it.outcome)
        }
        assertEquals(signatures.size, signatures.distinct().size)
        assertTrue(snapshot.revision >= 0)
        assertTrue(
            engine.status == TimerRunStatus.IDLE ||
                engine.status == TimerRunStatus.RUNNING ||
                engine.status == TimerRunStatus.PAUSED ||
                engine.status == TimerRunStatus.COMPLETED ||
                engine.status == TimerRunStatus.CANCELLED
        )
    }

    private fun controller(
        routine: Routine,
        wall: ConcurrencyClock = ConcurrencyClock(1_000L),
        mono: com.flow.app.domain.execution.timer.MonotonicClock = ExecutionFakeMonotonicClock(1_000L)
    ): ExecutionController = ExecutionController(
        clock = wall,
        initialState = ExecutionSession.create(routine, "concurrency-session"),
        initialRevision = 0L,
        monotonicClock = mono
    )

    private fun race(first: () -> Any, second: () -> Any): List<Any> {
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Any> {
                start.await(5, TimeUnit.SECONDS)
                try { first() } finally { done.countDown() }
            }
            val b = pool.submit<Any> {
                start.await(5, TimeUnit.SECONDS)
                try { second() } finally { done.countDown() }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            return listOf(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun routine(steps: List<RoutineStep>) = Routine(
        id = "concurrency-routine",
        name = "Concurrency",
        repeatCount = 1,
        steps = steps,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun timer(id: String, duration: Long) = RoutineStep(
        id = id,
        name = id,
        type = StepType.TIMER,
        durationMs = duration,
        category = TimerCategory.NORMAL
    )

    private fun count(id: String, target: Int, mode: CountMode = CountMode.INCREMENT) =
        RoutineStep(
            id = id,
            name = id,
            type = StepType.COUNT,
            target = target,
            countMode = mode
        )

    private class CommitCrossingClock(
        private val startPrepare: Long,
        private val startCommit: Long,
        private val skipPrepare: Long,
        private val skipCommit: Long
    ) : com.flow.app.domain.execution.timer.MonotonicClock {
        private val values = listOf(
            startPrepare,
            startCommit,
            skipPrepare,
            skipPrepare,
            skipCommit
        )
        private var index = 0
        override fun nowMs(): Long {
            val value = values[index.coerceAtMost(values.lastIndex)]
            index += 1
            return value
        }
    }

    private class ConcurrencyClock(var current: Long) : ExecutionClock {
        override fun now(): Long = current
    }
}
