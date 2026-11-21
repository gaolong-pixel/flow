package com.flow.app.domain.run

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRunControllerTest {
    private val clock = FakeRunClock()
    private val wall = FakeWallClock()

    private fun count(id: String = "c1", name: String = "俯卧撑", target: Int = 15) =
        RoutineStep(
            id = id,
            name = name,
            type = StepType.COUNT,
            target = target,
            countMode = CountMode.COMPLETE,
            repeatCount = 1
        )

    private fun timer(id: String = "t1", name: String = "阅读", ms: Long = 60_000L) =
        RoutineStep(
            id = id,
            name = name,
            type = StepType.TIMER,
            durationMs = ms,
            category = TimerCategory.NORMAL,
            repeatCount = 1
        )

    private fun plan(vararg steps: RoutineStep) = Routine(
        id = "plan-1",
        name = "测试计划",
        version = 1,
        repeatCount = 1,
        steps = steps.toList(),
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun controller(
        plan: Routine,
        initialLoop: Boolean = false
    ) = PlanRunController(plan, initialLoop, clock, wall, runId = "run-1")

    @Test
    fun initialSnapshotIsReadyWithoutProgress() {
        val run = controller(plan(count(), timer()))
        val snapshot = run.snapshot()
        assertEquals(NodeRunState.READY, snapshot.nodeState)
        assertEquals(0, snapshot.completedNodes)
        assertEquals(0, snapshot.nodeIndex)
        assertEquals(2, snapshot.totalNodes)
        assertFalse(snapshot.loopCurrentNode)
        assertNull(snapshot.remainingMs)
        assertFalse(snapshot.finalized)
        assertEquals("run-1", snapshot.runId)
    }

    @Test
    fun timerReadySnapshotShowsFullDuration() {
        val run = controller(plan(timer(ms = 90_000L)))
        assertEquals(90_000L, run.snapshot().remainingMs)
    }

    @Test
    fun startCountMovesToRunningAndCompleteAdvances() {
        val run = controller(plan(count(), timer()))
        assertTrue(run.startNode())
        assertEquals(NodeRunState.RUNNING, run.snapshot().nodeState)

        assertTrue(run.completeNode())
        val advanced = run.snapshot()
        assertEquals(1, advanced.nodeIndex)
        assertEquals(1, advanced.completedNodes)
        assertEquals(NodeRunState.READY, advanced.nodeState)
        assertEquals(1, advanced.completedNodes)
    }

    @Test
    fun countCannotCompleteBeforeStart() {
        val run = controller(plan(count()))
        assertFalse(run.completeNode())
        val snapshot = run.snapshot()
        assertEquals(NodeRunState.READY, snapshot.nodeState)
        assertEquals(0, snapshot.completedNodes)
        assertEquals(0, snapshot.nodeIndex)
    }

    @Test
    fun startIsRejectedWhenAlreadyRunning() {
        val run = controller(plan(count()))
        assertTrue(run.startNode())
        assertFalse(run.startNode())
        assertEquals(NodeRunState.RUNNING, run.snapshot().nodeState)
    }

    @Test
    fun timerStartUsesFullDurationAndMonotonicClock() {
        val run = controller(plan(timer(ms = 60_000L)))
        clock.now = 5_000L
        assertTrue(run.startNode())
        val started = run.snapshot()
        assertEquals(NodeRunState.RUNNING, started.nodeState)
        assertEquals(60_000L, started.remainingMs)
        assertNotNull(started.timerRunId)

        clock.advance(15_000L)
        // snapshot derives remaining from the deadline even without refresh
        assertEquals(45_000L, run.snapshot().remainingMs)
        assertFalse(run.refresh())
        assertEquals(45_000L, run.snapshot().remainingMs)
    }

    @Test
    fun pauseFreezesRemainingAndResumeContinuesFromIt() {
        val run = controller(plan(timer(ms = 60_000L)))
        clock.now = 0L
        run.startNode()
        clock.advance(20_000L)

        assertTrue(run.pause())
        val paused = run.snapshot()
        assertEquals(NodeRunState.PAUSED, paused.nodeState)
        assertEquals(40_000L, paused.remainingMs)

        clock.advance(300_000L)
        assertEquals(40_000L, run.snapshot().remainingMs)

        assertTrue(run.resume())
        assertEquals(NodeRunState.RUNNING, run.snapshot().nodeState)
        assertEquals(40_000L, run.snapshot().remainingMs)

        clock.advance(10_000L)
        assertFalse(run.refresh())
        assertEquals(30_000L, run.snapshot().remainingMs)
    }

    @Test
    fun pauseIsOnlyAllowedForRunningTimer() {
        val run = controller(plan(timer()))
        assertFalse(run.pause()) // READY
        run.startNode()
        assertTrue(run.pause()) // RUNNING -> PAUSED
        assertFalse(run.pause()) // PAUSED cannot pause again
        assertTrue(run.resume())
        assertTrue(run.pause()) // RUNNING can pause again
    }

    @Test
    fun timerExpiresWithoutCompletingNode() {
        val run = controller(plan(timer(ms = 10_000L)))
        clock.now = 0L
        run.startNode()
        clock.advance(10_000L)

        assertTrue(run.refresh())
        val expired = run.snapshot()
        assertEquals(NodeRunState.EXPIRED, expired.nodeState)
        assertEquals(0L, expired.remainingMs)
        assertEquals(0, expired.completedNodes)
        assertEquals(0, expired.nodeIndex)
        assertFalse(expired.finalized)
        assertFalse(run.isFinalized)

        // repeated refresh is idempotent and never completes
        assertFalse(run.refresh())
        assertEquals(NodeRunState.EXPIRED, run.snapshot().nodeState)
        assertEquals(0, run.snapshot().completedNodes)
    }

    @Test
    fun expiredTimerRequiresExplicitCompletion() {
        val run = controller(plan(timer(ms = 5_000L), count()))
        clock.now = 0L
        run.startNode()
        clock.advance(6_000L)
        run.refresh()

        assertTrue(run.completeNode())
        val advanced = run.snapshot()
        assertEquals(1, advanced.nodeIndex)
        assertEquals(1, advanced.completedNodes)
        assertEquals(NodeRunState.READY, advanced.nodeState)
        assertEquals(StepType.COUNT, advanced.node?.type)
    }

    @Test
    fun pauseAtExpiryTransitionsToExpiredWithoutPausing() {
        val run = controller(plan(timer(ms = 5_000L)))
        clock.now = 0L
        run.startNode()
        clock.advance(5_000L)
        assertFalse(run.pause())
        assertEquals(NodeRunState.EXPIRED, run.snapshot().nodeState)
        assertEquals(0L, run.snapshot().remainingMs)
    }

    @Test
    fun resumeAtZeroTransitionsToExpired() {
        val run = controller(plan(timer(ms = 5_000L)))
        clock.now = 0L
        run.startNode()
        clock.advance(2_000L)
        run.pause()
        // Force the remaining budget to zero (model-level edge case).
        clock.advance(2_000L)
        assertTrue(run.resume())
        clock.advance(3_000L)
        assertTrue(run.refresh())
        assertEquals(NodeRunState.EXPIRED, run.snapshot().nodeState)
    }

    @Test
    fun loopResetsCurrentCountAndDoesNotCountProgress() {
        val run = controller(plan(count(), timer()), initialLoop = true)
        assertTrue(run.startNode())
        assertTrue(run.completeNode())

        val looped = run.snapshot()
        assertEquals(NodeRunState.READY, looped.nodeState)
        assertEquals(0, looped.nodeIndex)
        assertEquals(0, looped.completedNodes)
        assertTrue(looped.loopCurrentNode)
        assertFalse(looped.finalized)

        // A second loop iteration still does not count progress.
        run.startNode()
        run.completeNode()
        assertEquals(0, run.snapshot().completedNodes)
        assertEquals(0, run.snapshot().nodeIndex)
    }

    @Test
    fun loopResetDoesNotAutoStartNextIteration() {
        val run = controller(plan(count()), initialLoop = true)
        run.startNode()
        run.completeNode()
        assertEquals(NodeRunState.READY, run.snapshot().nodeState)
        // Must require an explicit start.
        assertEquals(NodeRunState.READY, run.snapshot().nodeState)
        assertTrue(run.startNode())
        assertEquals(NodeRunState.RUNNING, run.snapshot().nodeState)
    }

    @Test
    fun turningLoopOffDoesNotAdvanceButCompletionDoes() {
        val run = controller(plan(count(), count(id = "c2", name = "深蹲")), initialLoop = true)
        run.startNode()
        assertTrue(run.setLoop(false))

        val afterToggle = run.snapshot()
        assertEquals(0, afterToggle.nodeIndex)
        assertEquals(0, afterToggle.completedNodes)
        assertEquals(NodeRunState.RUNNING, afterToggle.nodeState)
        assertFalse(afterToggle.loopCurrentNode)

        assertTrue(run.completeNode())
        assertEquals(1, run.snapshot().nodeIndex)
        assertEquals(1, run.snapshot().completedNodes)
        assertFalse(run.snapshot().loopCurrentNode)
    }

    @Test
    fun loopAppliesOnlyToCurrentNodeAndResetsOnAdvance() {
        val run = controller(plan(count(), count(id = "c2", name = "深蹲")), initialLoop = true)
        run.startNode()
        run.completeNode() // loop iteration stays on node 0
        assertTrue(run.snapshot().loopCurrentNode)

        run.setLoop(false)
        run.startNode()
        run.completeNode() // advances to node 1
        assertEquals(1, run.snapshot().nodeIndex)
        assertFalse(run.snapshot().loopCurrentNode)
    }

    @Test
    fun loopTimerResetsToFullDurationWithoutAutoStart() {
        val run = controller(plan(timer(ms = 30_000L)), initialLoop = true)
        clock.now = 0L
        run.startNode()
        clock.advance(10_000L)
        run.refresh()
        assertEquals(20_000L, run.snapshot().remainingMs)

        assertTrue(run.completeNode())
        val reset = run.snapshot()
        assertEquals(NodeRunState.READY, reset.nodeState)
        assertEquals(30_000L, reset.remainingMs)
        assertEquals(0, reset.completedNodes)
        assertNull(reset.timerRunId)

        run.startNode()
        assertEquals(NodeRunState.RUNNING, run.snapshot().nodeState)
        assertEquals(30_000L, run.snapshot().remainingMs)
    }

    @Test
    fun loopOnLastNodeDoesNotFinishUntilLoopIsOff() {
        val run = controller(plan(count()), initialLoop = true)
        run.startNode()
        run.completeNode()
        assertFalse(run.snapshot().finalized)
        assertEquals(0, run.snapshot().completedNodes)

        run.setLoop(false)
        run.startNode()
        run.completeNode()
        assertTrue(run.snapshot().finalized)
        assertEquals(1, run.snapshot().completedNodes)
    }

    @Test
    fun lastNodeCompletionFinalizesAndProducesNormalSummary() {
        val run = controller(plan(count(), timer(ms = 5_000L)))
        clock.now = 0L
        run.startNode()
        run.completeNode()
        run.startNode()
        clock.advance(5_000L)
        run.refresh()
        run.completeNode()

        val snapshot = run.snapshot()
        assertTrue(snapshot.finalized)
        assertEquals(2, snapshot.completedNodes)

        val summary = run.finishedSummary()
        assertNotNull(summary)
        assertEquals(2, summary!!.completedNodes)
        assertEquals(2, summary.totalNodes)
        assertTrue(summary.completedNormally)
        assertNull(run.exit())
    }

    @Test
    fun exitReportsProgressAndClearsRuntime() {
        val run = controller(plan(count(), count(id = "c2", name = "深蹲"), timer()))
        run.startNode()
        run.completeNode() // 1 completed
        run.startNode() // second node in progress

        val summary = run.exit()
        assertNotNull(summary)
        assertEquals(1, summary!!.completedNodes)
        assertEquals(3, summary.totalNodes)
        assertFalse(summary.completedNormally)
        assertNull(run.exit())
        assertFalse(run.startNode())
        assertFalse(run.completeNode())
        assertFalse(run.setLoop(true))
    }

    @Test
    fun timerWarningAndExpiryHapticsFireOnce() {
        val run = controller(plan(timer(ms = 10_000L)))
        clock.now = 0L
        run.startNode()

        clock.advance(7_000L) // remaining 3s
        run.refresh()
        assertEquals(listOf(RunHapticEvent.TIMER_WARNING), run.consumeHapticEvents())

        clock.advance(500L)
        run.refresh()
        assertTrue(run.consumeHapticEvents().isEmpty())

        clock.advance(2_600L) // expired
        run.refresh()
        assertEquals(listOf(RunHapticEvent.TIMER_EXPIRED), run.consumeHapticEvents())

        run.refresh()
        assertTrue(run.consumeHapticEvents().isEmpty())
    }

    @Test
    fun warningIsNotRepeatedAfterPauseAndResume() {
        val run = controller(plan(timer(ms = 10_000L)))
        clock.now = 0L
        run.startNode()
        clock.advance(7_000L)
        run.refresh()
        assertEquals(listOf(RunHapticEvent.TIMER_WARNING), run.consumeHapticEvents())

        assertTrue(run.pause())
        clock.advance(60_000L)
        assertTrue(run.resume())
        clock.advance(500L)
        run.refresh()
        assertTrue(run.consumeHapticEvents().isEmpty())

        clock.advance(3_000L)
        run.refresh()
        assertEquals(listOf(RunHapticEvent.TIMER_EXPIRED), run.consumeHapticEvents())
    }

    @Test
    fun loopRestartGetsFreshWarningBudget() {
        val run = controller(plan(timer(ms = 5_000L)), initialLoop = true)
        clock.now = 0L
        run.startNode()
        clock.advance(5_000L)
        run.refresh()
        assertEquals(listOf(RunHapticEvent.TIMER_EXPIRED), run.consumeHapticEvents())

        assertTrue(run.completeNode()) // loop reset
        run.startNode()
        clock.advance(2_500L)
        run.refresh()
        assertEquals(listOf(RunHapticEvent.TIMER_WARNING), run.consumeHapticEvents())
    }

    @Test
    fun elapsedSummaryUsesWallClock() {
        val run = controller(plan(count()))
        wall.now = 100_000L
        run.startNode()
        wall.now = 112_500L
        run.completeNode()
        val summary = run.finishedSummary()
        assertEquals(12_500L, summary!!.elapsedMs)
    }

    @Test
    fun remainingSurvivesWallClockChanges() {
        val run = controller(plan(timer(ms = 60_000L)))
        clock.now = 0L
        run.startNode()
        clock.advance(10_000L)
        wall.now += 3_600_000L
        assertEquals(50_000L, run.snapshot().remainingMs)
    }

    @Test
    fun invalidPlanIsRejectedAtConstruction() {
        try {
            PlanRunController(plan(), false, clock, wall)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun initialSchedulingStatusExposesFirstNodeAsCurrent() {
        val run = controller(plan(count(), timer(), count(id = "c2", name = "深蹲")))
        val snapshot = run.snapshot()

        assertEquals(
            listOf(NodeStatus.CURRENT, NodeStatus.PENDING, NodeStatus.PENDING),
            snapshot.nodeStatuses
        )
        assertEquals(listOf(0, 1, 2), snapshot.visibleNodeIndices)
        assertTrue(snapshot.isFinalNode.not())
    }

    @Test
    fun switchingFromRunningCountDefersItWithoutLosingRunningState() {
        val run = controller(plan(count(target = 5), timer()))
        run.startNode()

        assertTrue(run.selectNode(1))
        val switched = run.snapshot()
        assertEquals(1, switched.nodeIndex)
        assertEquals(NodeStatus.DEFERRED, switched.nodeStatuses[0])
        assertEquals(0, switched.completedNodes)

        assertTrue(run.selectNode(0))
        val restored = run.snapshot()
        assertEquals(0, restored.nodeIndex)
        assertEquals(NodeRunState.RUNNING, restored.nodeState)
        assertFalse(run.startNode()) // it is already running; no artificial restart
    }

    @Test
    fun switchingFromRunningTimerFreezesRemainingAndRestoresPausedState() {
        val run = controller(plan(timer(ms = 60_000L), count()))
        clock.now = 0L
        run.startNode()
        clock.advance(20_000L)

        assertTrue(run.selectNode(1))
        val switched = run.snapshot()
        assertEquals(1, switched.nodeIndex)
        assertEquals(NodeStatus.DEFERRED, switched.nodeStatuses[0])
        assertNull(switched.remainingMs) // the COUNT target is current now

        clock.advance(300_000L)
        assertTrue(run.selectNode(0))
        val restored = run.snapshot()
        assertEquals(0, restored.nodeIndex)
        assertEquals(NodeRunState.PAUSED, restored.nodeState)
        assertEquals(40_000L, restored.remainingMs)

        assertTrue(run.resume())
        clock.advance(10_000L)
        run.refresh()
        assertEquals(30_000L, run.snapshot().remainingMs)
    }

    @Test
    fun switchingAwayFromExpiredTimerKeepsExpiredStateForLaterCompletion() {
        val run = controller(plan(timer(ms = 5_000L), count()))
        clock.now = 0L
        run.startNode()
        clock.advance(5_000L)
        run.refresh()
        assertEquals(NodeRunState.EXPIRED, run.snapshot().nodeState)

        assertTrue(run.selectNode(1))
        assertEquals(NodeStatus.DEFERRED, run.snapshot().nodeStatuses[0])
        assertTrue(run.selectNode(0))
        assertEquals(NodeRunState.EXPIRED, run.snapshot().nodeState)
        assertEquals(0L, run.snapshot().remainingMs)
        assertTrue(run.completeNode())
        assertEquals(NodeStatus.COMPLETED, run.snapshot().nodeStatuses[0])
        assertEquals(1, run.snapshot().completedNodes)
    }

    @Test
    fun completedNodeLeavesRollerAndNextSelectionWrapsAround() {
        val run = controller(
            plan(
                count(id = "c1", name = "一"),
                count(id = "c2", name = "二"),
                count(id = "c3", name = "三")
            )
        )
        run.startNode()
        run.completeNode() // c1 completed, current c2
        assertEquals(1, run.snapshot().nodeIndex)

        assertTrue(run.selectNode(2)) // switch to c3 without starting c2
        assertEquals(NodeStatus.PENDING, run.snapshot().nodeStatuses[1])
        assertEquals(NodeStatus.CURRENT, run.snapshot().nodeStatuses[2])

        run.startNode()
        run.completeNode() // c3 completed; c2 is the only selectable node left
        val after = run.snapshot()
        assertEquals(1, after.nodeIndex)
        assertEquals(NodeStatus.COMPLETED, after.nodeStatuses[0])
        assertEquals(NodeStatus.COMPLETED, after.nodeStatuses[2])
        assertEquals(NodeStatus.CURRENT, after.nodeStatuses[1])
        assertEquals(listOf(1), after.visibleNodeIndices)
        assertEquals(2, after.completedNodes)

        run.startNode()
        run.completeNode()
        assertTrue(run.snapshot().finalized)
        assertEquals(3, run.snapshot().completedNodes)
    }

    @Test
    fun loopResetKeepsNodeCurrent() {
        val run = controller(plan(count(target = 3), timer()), initialLoop = true)
        run.startNode()

        assertTrue(run.completeNode())
        val reset = run.snapshot()
        assertEquals(0, reset.nodeIndex)
        assertEquals(NodeRunState.READY, reset.nodeState)
        assertEquals(NodeStatus.CURRENT, reset.currentNodeStatus)
        assertEquals(0, reset.completedNodes)
        assertTrue(reset.loopCurrentNode)
    }

    @Test
    fun selectNodeRejectsInvalidAndCompletedTargets() {
        val run = controller(plan(count(id = "c1"), count(id = "c2"), count(id = "c3")))
        assertFalse(run.selectNode(0)) // current
        assertFalse(run.selectNode(-1))
        assertFalse(run.selectNode(99))

        run.startNode()
        run.completeNode() // c1 completed, c2 current
        assertEquals(1, run.snapshot().nodeIndex)
        assertFalse(run.selectNode(0)) // completed
        assertFalse(run.selectNode(1)) // current
    }

    @Test
    fun switchingAcrossMultipleNodesPreservesProgressAndNeverCompletes() {
        val run = controller(plan(count(id = "c1", target = 5), timer(ms = 10_000L), count(id = "c2")))
        run.startNode() // c1 RUNNING
        assertTrue(run.selectNode(1))
        assertTrue(run.selectNode(2))
        assertTrue(run.selectNode(0))

        val snapshot = run.snapshot()
        assertEquals(0, snapshot.completedNodes)
        assertEquals(0, snapshot.nodeIndex)
        assertEquals(NodeStatus.CURRENT, snapshot.nodeStatuses[0])
        assertEquals(NodeStatus.PENDING, snapshot.nodeStatuses[1])
        assertEquals(NodeStatus.PENDING, snapshot.nodeStatuses[2])
        assertEquals(NodeRunState.RUNNING, snapshot.nodeState)
        assertEquals(3, snapshot.visibleNodeIndices.size)
    }

    @Test
    fun switchingResetsLoopSettingForTheTargetNode() {
        val run = controller(plan(timer(ms = 10_000L), count()), initialLoop = true)
        run.startNode()
        assertTrue(run.snapshot().loopCurrentNode)
        assertTrue(run.selectNode(1))
        assertFalse(run.snapshot().loopCurrentNode)
    }

    @Test
    fun exitSummaryCarriesCompletedCountAndIsNotNormal() {
        val run = controller(plan(count(id = "c1"), count(id = "c2")))
        run.startNode()
        run.completeNode()
        val summary = run.exit()
        assertEquals(1, summary!!.completedNodes)
        assertEquals(2, summary.totalNodes)
        assertFalse(summary.completedNormally)
    }
}
