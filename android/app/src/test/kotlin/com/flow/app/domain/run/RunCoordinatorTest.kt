package com.flow.app.domain.run

import com.flow.app.data.PlanStore
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val clock = FakeRunClock()
    private val wall = FakeWallClock()
    private val notifications = RecordingNotifications()
    private val background = RecordingBackground()
    private val haptics = RecordingHaptics()

    private fun count(id: String = "c1", name: String = "俯卧撑", target: Int = 15) =
        RoutineStep(
            id = id,
            name = name,
            type = StepType.COUNT,
            target = target,
            countMode = CountMode.COMPLETE,
            repeatCount = 1
        )

    private fun timer(id: String = "t1", name: String = "阅读", ms: Long = 10_000L) =
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

    private fun coordinator() = RunCoordinator(
        monotonic = clock,
        wallClock = wall,
        notifications = notifications,
        background = background,
        haptics = haptics
    )

    private fun start(result: StartRunResult): PlanRunSnapshot {
        assertTrue("Expected start success but was $result", result is StartRunResult.Started)
        return (result as StartRunResult.Started).snapshot
    }

    @Test
    fun staleResourcesAreClearedWhenCoordinatorIsCreated() {
        coordinator()
        assertEquals(1, background.staleClears)
        assertEquals(1, notifications.staleClears)
    }

    @Test
    fun startExposesActiveSnapshotAndRejectsSecondRun() {
        val coordinator = coordinator()
        val first = start(coordinator.start(plan(count()), initialLoop = false))
        assertEquals("plan-1", first.plan.id)
        assertTrue(coordinator.hasActiveRun())
        assertEquals(1, background.starts)

        val second = coordinator.start(plan(count(id = "other", name = "别的")), false)
        assertTrue(second is StartRunResult.Rejected)
        assertEquals(
            RunStartError.ALREADY_RUNNING,
            (second as StartRunResult.Rejected).error
        )
        assertEquals(1, background.starts)
    }

    @Test
    fun newRunDefaultsToLoopOff() {
        val coordinator = coordinator()
        val snapshot = start(coordinator.start(plan(count())))
        assertFalse(snapshot.loopCurrentNode)
        assertFalse(coordinator.currentSnapshot()?.loopCurrentNode ?: true)
    }

    @Test
    fun emptyPlanCannotStart() {
        val coordinator = coordinator()
        val result = coordinator.start(plan(), false)
        assertTrue(result is StartRunResult.Rejected)
        assertEquals(RunStartError.EMPTY_PLAN, (result as StartRunResult.Rejected).error)
        assertEquals(RunRuntimeState.Idle, coordinator.runtime.value)
        assertEquals(0, background.starts)
    }

    @Test
    fun timerLifecycleDrivesNotificationEvents() {
        val coordinator = coordinator()
        clock.now = 0L
        start(coordinator.start(plan(timer(ms = 10_000L)), false))
        assertTrue(notifications.events.contains("started"))
        assertTrue(notifications.events.contains("progress"))

        assertTrue(coordinator.startNode())
        assertTrue(notifications.events.contains("timerStarted"))

        clock.advance(2_000L)
        coordinator.refresh()
        assertTrue(coordinator.pause())
        assertTrue(notifications.events.contains("timerPaused"))

        assertTrue(coordinator.resume())
        assertTrue(notifications.events.contains("timerResumed"))

        assertTrue(coordinator.completeNode())
        assertTrue(notifications.events.contains("timerCompleted"))
        assertTrue(notifications.events.contains("finished"))
        assertEquals(1, background.stops)
        assertEquals(0, background.starts - background.stops)
        assertTrue(coordinator.runtime.value is RunRuntimeState.Finished)
    }

    @Test
    fun countLifecycleDoesNotUseTimerNotifications() {
        val coordinator = coordinator()
        start(coordinator.start(plan(count()), false))
        coordinator.startNode()
        coordinator.completeNode()
        assertFalse(notifications.events.contains("timerStarted"))
        assertFalse(notifications.events.contains("timerPaused"))
        assertTrue(notifications.events.contains("finished"))
    }

    @Test
    fun foregroundExpiryUsesDirectHapticsAndDoesNotAutoComplete() {
        val coordinator = coordinator()
        clock.now = 0L
        start(coordinator.start(plan(timer(ms = 5_000L)), false))
        coordinator.startNode()
        coordinator.setUiVisible(true)
        clock.advance(5_000L)

        assertTrue(coordinator.refresh())
        val snapshot = (coordinator.runtime.value as RunRuntimeState.Active).snapshot
        assertEquals(NodeRunState.EXPIRED, snapshot.nodeState)
        assertEquals(0, snapshot.completedNodes)
        // Foreground expiry is haptic-only; the high-importance alert is for
        // background delivery only.
        assertEquals(0, notifications.expired)
        assertEquals(1, haptics.expiries)

        coordinator.refresh()
        coordinator.refresh()
        assertEquals(0, notifications.expired)
        assertEquals(1, haptics.expiries)
        assertEquals(0, snapshot.completedNodes)
    }

    @Test
    fun backgroundExpiryUsesNotificationAlertWithoutDirectHaptics() {
        val coordinator = coordinator()
        clock.now = 0L
        start(coordinator.start(plan(timer(ms = 5_000L)), false))
        coordinator.startNode()
        coordinator.setUiVisible(false)
        clock.advance(5_000L)

        assertTrue(coordinator.refreshFromBackground())
        val snapshot = (coordinator.runtime.value as RunRuntimeState.Active).snapshot
        assertEquals(NodeRunState.EXPIRED, snapshot.nodeState)
        assertEquals(0, snapshot.completedNodes)
        assertEquals(1, notifications.expired)
        assertEquals(0, haptics.expiries)

        coordinator.refreshFromBackground()
        assertEquals(1, notifications.expired)
        assertEquals(0, haptics.expiries)
    }

    @Test
    fun warningHapticFiresOnce() {
        val coordinator = coordinator()
        clock.now = 0L
        start(coordinator.start(plan(timer(ms = 10_000L)), false))
        coordinator.startNode()
        clock.advance(7_000L)
        coordinator.refresh()
        coordinator.refresh()
        assertEquals(1, haptics.warnings)
        assertEquals(0, haptics.expiries)

        clock.advance(3_000L)
        coordinator.refresh()
        assertEquals(1, haptics.warnings)
        assertEquals(1, haptics.expiries)
    }

    @Test
    fun uiVisibleBackgroundRefreshIsNoop() {
        val coordinator = coordinator()
        start(coordinator.start(plan(timer(ms = 10_000L)), false))
        coordinator.startNode()
        coordinator.setUiVisible(true)
        assertFalse(coordinator.refreshFromBackground())

        coordinator.setUiVisible(false)
        assertTrue(coordinator.refreshFromBackground())
    }

    @Test
    fun returningToForegroundCatchUpDetectsExpiry() {
        val coordinator = coordinator()
        clock.now = 0L
        start(coordinator.start(plan(timer(ms = 2_000L)), false))
        coordinator.startNode()
        coordinator.setUiVisible(false)
        clock.advance(5_000L)

        // The background service detects expiry and posts the notification.
        assertTrue(coordinator.refreshFromBackground())
        assertEquals(1, notifications.expired)
        assertEquals(0, haptics.expiries)

        // Returning to foreground refreshes again without a duplicate alert.
        coordinator.setUiVisible(true)
        val snapshot = (coordinator.runtime.value as RunRuntimeState.Active).snapshot
        assertEquals(NodeRunState.EXPIRED, snapshot.nodeState)
        assertEquals(1, notifications.expired)
        assertEquals(0, haptics.expiries)
        assertEquals(0, snapshot.completedNodes)
    }

    @Test
    fun exitClearsRuntimeStopsBackgroundAndReportsProgress() {
        val coordinator = coordinator()
        start(coordinator.start(plan(count(), count(id = "c2", name = "深蹲"), timer()), false))
        coordinator.startNode()
        coordinator.completeNode()
        coordinator.startNode()

        val summary = coordinator.exitCurrent()
        assertNotNull(summary)
        assertEquals(1, summary!!.completedNodes)
        assertEquals(3, summary.totalNodes)
        assertFalse(summary.completedNormally)
        assertEquals(RunRuntimeState.Idle, coordinator.runtime.value)
        assertFalse(coordinator.hasActiveRun())
        assertEquals(1, background.stops)
        assertTrue(notifications.events.contains("exited"))
        assertNull(coordinator.exitCurrent())
    }

    @Test
    fun finishedResultCanBeDismissedAndAnotherPlanStarted() {
        val coordinator = coordinator()
        start(coordinator.start(plan(count(id = "a", name = "A")), false))
        coordinator.startNode()
        coordinator.completeNode()
        assertTrue(coordinator.runtime.value is RunRuntimeState.Finished)
        assertFalse(coordinator.hasActiveRun())

        // Starting while the result screen is up is rejected until dismissed.
        val blocked = coordinator.start(plan(count(id = "b", name = "B")), false)
        assertTrue(blocked is StartRunResult.Rejected)

        coordinator.dismissResult()
        assertEquals(RunRuntimeState.Idle, coordinator.runtime.value)
        val started = start(coordinator.start(plan(count(id = "b", name = "B")), false))
        assertEquals("B", started.node?.name)
        assertEquals(2, background.starts)
    }

    @Test
    fun loopCompletionKeepsRunActiveForLastNode() {
        val coordinator = coordinator()
        start(coordinator.start(plan(count()), initialLoop = true))
        coordinator.startNode()
        coordinator.completeNode()

        val snapshot = (coordinator.runtime.value as RunRuntimeState.Active).snapshot
        assertEquals(NodeRunState.READY, snapshot.nodeState)
        assertEquals(0, snapshot.completedNodes)
        assertFalse(snapshot.finalized)
        assertFalse(notifications.events.contains("finished"))

        assertTrue(coordinator.setLoop(false))
        coordinator.startNode()
        coordinator.completeNode()
        assertTrue(coordinator.runtime.value is RunRuntimeState.Finished)
        assertTrue(notifications.events.contains("finished"))
    }

    @Test
    fun loopToggleDoesNotAdvanceOrCount() {
        val coordinator = coordinator()
        start(coordinator.start(plan(count(), count(id = "c2", name = "深蹲")), false))
        coordinator.startNode()
        assertTrue(coordinator.setLoop(true))
        val snapshot = (coordinator.runtime.value as RunRuntimeState.Active).snapshot
        assertEquals(0, snapshot.nodeIndex)
        assertEquals(0, snapshot.completedNodes)
        assertEquals(NodeRunState.RUNNING, snapshot.nodeState)
    }

    @Test
    fun refreshAfterFinishDoesNothing() {
        val coordinator = coordinator()
        start(coordinator.start(plan(count()), false))
        coordinator.startNode()
        coordinator.completeNode()
        val runtimeBefore = coordinator.runtime.value
        assertFalse(coordinator.refresh())
        assertEquals(runtimeBefore, coordinator.runtime.value)
    }

    @Test
    fun runStateIsNotPersistedToPlanStore() {
        val file = File(temp.root, "plans.json")
        val store = PlanStore(file)
        val savedPlan = plan(count(), timer())
        assertTrue(store.save(savedPlan))
        val before = file.readText()

        val coordinator = coordinator()
        val snapshot = start(coordinator.start(savedPlan, false))
        coordinator.startNode()
        coordinator.completeNode()
        coordinator.setUiVisible(false)
        coordinator.refreshFromBackground()

        val after = file.readText()
        assertEquals(before, after)
        assertFalse(after.contains(snapshot.runId))
        assertEquals(listOf(savedPlan), store.loadAll())
    }

    @Test
    fun processRestartHasNoOldRun() {
        val first = coordinator()
        start(first.start(plan(count()), false))
        first.startNode()

        // A new coordinator models a fresh process: no run, no result.
        val second = coordinator()
        assertEquals(RunRuntimeState.Idle, second.runtime.value)
        assertFalse(second.hasActiveRun())
        assertNull(second.currentSnapshot())
        assertNull(second.exitCurrent())
    }

    @Test
    fun backgroundServiceIsStartedOnceAndStoppedOnce() {
        val coordinator = coordinator()
        start(coordinator.start(plan(timer(ms = 10_000L)), false))
        coordinator.startNode()
        coordinator.refresh()
        coordinator.refresh()
        assertEquals(1, background.starts)

        coordinator.exitCurrent()
        assertEquals(1, background.stops)
    }

    @Test
    fun timerPausedThenCompletedAdvances() {
        val coordinator = coordinator()
        start(coordinator.start(plan(timer(ms = 30_000L), count(id = "c2", name = "深蹲")), false))
        coordinator.startNode()
        clock.advance(5_000L)
        coordinator.pause()
        assertEquals(
            NodeRunState.PAUSED,
            (coordinator.runtime.value as RunRuntimeState.Active).snapshot.nodeState
        )

        assertTrue(coordinator.completeNode())
        val snapshot = (coordinator.runtime.value as RunRuntimeState.Active).snapshot
        assertEquals(1, snapshot.nodeIndex)
        assertEquals(1, snapshot.completedNodes)
        assertTrue(notifications.events.contains("timerCompleted"))
    }

    private class RecordingNotifications : RunNotificationSink {
        val events = mutableListOf<String>()
        var expanded = mutableListOf<PlanRunSnapshot>()
        var expired = 0
        var staleClears = 0

        override fun onRunStarted(snapshot: PlanRunSnapshot) {
            events += "started"
        }

        override fun onProgress(snapshot: PlanRunSnapshot) {
            events += "progress"
        }

        override fun onTimerStarted(snapshot: PlanRunSnapshot) {
            events += "timerStarted"
            expanded += snapshot
        }

        override fun onTimerPaused(snapshot: PlanRunSnapshot) {
            events += "timerPaused"
        }

        override fun onTimerResumed(snapshot: PlanRunSnapshot) {
            events += "timerResumed"
        }

        override fun onTimerCompleted(snapshot: PlanRunSnapshot) {
            events += "timerCompleted"
        }

        override fun onTimerExpired(snapshot: PlanRunSnapshot) {
            events += "expired"
            expired += 1
        }

        override fun onRunFinished(summary: RunSummary) {
            events += "finished"
        }

        override fun onRunExited(summary: RunSummary) {
            events += "exited"
        }

        override fun clearStaleNotifications() {
            staleClears += 1
        }
    }

    private class RecordingBackground : RunBackgroundRunner {
        var starts = 0
        var stops = 0
        var staleClears = 0

        override fun start(snapshot: PlanRunSnapshot) {
            starts += 1
        }

        override fun stop(runId: String) {
            stops += 1
        }

        override fun clearStale() {
            staleClears += 1
        }
    }

    private class RecordingHaptics : RunHaptics {
        var warnings = 0
        var expiries = 0

        override fun timerWarning() {
            warnings += 1
        }

        override fun timerExpired() {
            expiries += 1
        }
    }
}
