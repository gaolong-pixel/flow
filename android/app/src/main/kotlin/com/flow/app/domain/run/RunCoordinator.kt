package com.flow.app.domain.run

import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.StepType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform notification hooks. Android keeps an ongoing foreground-service
 * notification and posts a high-priority alert on expiry. iOS schedules and
 * cancels local notifications. Both implementations receive the same
 * lifecycle events so notification state always follows the run state.
 */
interface RunNotificationSink {
    fun onRunStarted(snapshot: PlanRunSnapshot) {}
    fun onProgress(snapshot: PlanRunSnapshot) {}
    fun onTimerStarted(snapshot: PlanRunSnapshot) {}
    fun onTimerPaused(snapshot: PlanRunSnapshot) {}
    fun onTimerResumed(snapshot: PlanRunSnapshot) {}
    fun onTimerCompleted(snapshot: PlanRunSnapshot) {}
    fun onTimerExpired(snapshot: PlanRunSnapshot) {}
    fun onRunFinished(summary: RunSummary) {}
    fun onRunExited(summary: RunSummary) {}
    fun clearStaleNotifications() {}
}

/** Platform background execution. Android starts one foreground service. */
interface RunBackgroundRunner {
    fun start(snapshot: PlanRunSnapshot) {}
    fun stop(runId: String) {}
    fun clearStale() {}
}

interface RunHaptics {
    fun timerWarning() {}
    fun timerExpired() {}
}

object NoopRunNotificationSink : RunNotificationSink
object NoopRunBackgroundRunner : RunBackgroundRunner
object NoopRunHaptics : RunHaptics

enum class RunStartError { ALREADY_RUNNING, EMPTY_PLAN }

sealed interface StartRunResult {
    data class Started(val snapshot: PlanRunSnapshot) : StartRunResult
    data class Rejected(val error: RunStartError) : StartRunResult
}

sealed interface RunRuntimeState {
    object Idle : RunRuntimeState
    data class Active(val snapshot: PlanRunSnapshot) : RunRuntimeState
    data class Finished(val summary: RunSummary) : RunRuntimeState
}

/**
 * App/process-scoped owner of the single active run instance.
 *
 * The coordinator is the only entry point for UI and background code. It
 * guarantees at most one run at a time, forwards notification/haptic
 * lifecycle events and clears every runtime resource on finish/exit. It never
 * writes to PlanStore and has no persistence or crash recovery.
 */
class RunCoordinator(
    private val monotonic: RunMonotonicClock,
    private val wallClock: RunWallClock = SystemRunWallClock,
    private val notifications: RunNotificationSink = NoopRunNotificationSink,
    private val background: RunBackgroundRunner = NoopRunBackgroundRunner,
    private val haptics: RunHaptics = NoopRunHaptics,
    private val controllerFactory: (Routine, Boolean) -> PlanRunController =
        { plan, loop -> PlanRunController(plan, loop, monotonic, wallClock) }
) {
    private val lock = Any()
    private var controller: PlanRunController? = null
    private var alertedTimerRunId: Long? = null

    @Volatile
    var uiVisible: Boolean = false
        private set

    private val _runtime = MutableStateFlow<RunRuntimeState>(RunRuntimeState.Idle)
    val runtime: StateFlow<RunRuntimeState> = _runtime.asStateFlow()

    // One-shot UI expiry feedback token. It is process-scoped (not stored in
    // the view) so a recomposition or a return from background cannot replay
    // the shake for an already-consumed TIMER expiry.
    private val _expiryFeedback = MutableStateFlow<Long?>(null)
    val expiryFeedback: StateFlow<Long?> = _expiryFeedback.asStateFlow()

    init {
        // A fresh process must not revive a previous run or its notifications.
        background.clearStale()
        notifications.clearStaleNotifications()
    }

    fun start(plan: Routine, initialLoop: Boolean = false): StartRunResult = synchronized(lock) {
        if (_runtime.value !is RunRuntimeState.Idle || controller != null) {
            return StartRunResult.Rejected(RunStartError.ALREADY_RUNNING)
        }
        if (plan.steps.isEmpty()) {
            return StartRunResult.Rejected(RunStartError.EMPTY_PLAN)
        }
        notifications.clearStaleNotifications()
        val fresh = controllerFactory(plan, initialLoop)
        controller = fresh
        alertedTimerRunId = null
        _expiryFeedback.value = null
        val snapshot = fresh.snapshot()
        _runtime.value = RunRuntimeState.Active(snapshot)
        notifications.onRunStarted(snapshot)
        notifications.onProgress(snapshot)
        background.start(snapshot)
        StartRunResult.Started(snapshot)
    }

    fun currentSnapshot(): PlanRunSnapshot? = synchronized(lock) {
        controller?.snapshot()
    }

    fun hasActiveRun(): Boolean = synchronized(lock) { controller != null }

    fun startNode(): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val started = active.startNode()
        if (!started) return false
        val snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        if (snapshot.node?.type == StepType.TIMER && snapshot.nodeState == NodeRunState.RUNNING) {
            notifications.onTimerStarted(snapshot)
        }
        drainHaptics(active, allowDirectHaptics = true)
        true
    }

    /**
     * Switches the execution target. The controller freezes a running TIMER and
     * marks the previous node DEFERRED. Timer notifications must be cancelled
     * before the new node is published so a deferred timer can never fire an
     * out-of-date alert.
     */
    fun selectNode(targetIndex: Int): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val before = active.snapshot()
        if (!active.selectNode(targetIndex)) return false
        if (before.node?.type == StepType.TIMER &&
            before.nodeState != NodeRunState.READY
        ) {
            notifications.onTimerPaused(before)
        }
        drainHaptics(active, allowDirectHaptics = true)
        val snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        true
    }

    /** Explicitly skips the current node through the scheduler extension. */
    fun skipCurrent(): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val before = active.snapshot()
        if (!active.skipCurrent()) return false
        if (before.node?.type == StepType.TIMER &&
            before.nodeState != NodeRunState.READY
        ) {
            notifications.onTimerPaused(before)
        }
        drainHaptics(active, allowDirectHaptics = true)
        val snapshot = active.snapshot()
        if (snapshot.finalized) {
            val summary = active.finishedSummary() ?: return false
            controller = null
            alertedTimerRunId = null
            _expiryFeedback.value = null
            notifications.onRunFinished(summary)
            background.stop(summary.runId)
            _runtime.value = RunRuntimeState.Finished(summary)
            return true
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        true
    }

    fun pause(): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val paused = active.pause()
        val snapshot = active.snapshot()
        handlePossibleExpiry(snapshot)
        drainHaptics(active, allowDirectHaptics = true)
        if (!paused) {
            if (snapshot.nodeState == NodeRunState.EXPIRED) publish(snapshot)
            return false
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        notifications.onTimerPaused(snapshot)
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val resumed = active.resume()
        val snapshot = active.snapshot()
        handlePossibleExpiry(snapshot)
        drainHaptics(active, allowDirectHaptics = true)
        if (!resumed) {
            if (snapshot.nodeState == NodeRunState.EXPIRED) publish(snapshot)
            return false
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        notifications.onTimerResumed(snapshot)
        true
    }

    fun completeNode(): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val before = active.snapshot()
        val completed = active.completeNode()
        if (!completed) return false
        if (before.node?.type == StepType.TIMER) {
            notifications.onTimerCompleted(before)
        }
        drainHaptics(active, allowDirectHaptics = true)
        val snapshot = active.snapshot()
        if (snapshot.finalized) {
            val summary = active.finishedSummary() ?: return false
            controller = null
            alertedTimerRunId = null
            _expiryFeedback.value = null
            notifications.onRunFinished(summary)
            background.stop(summary.runId)
            _runtime.value = RunRuntimeState.Finished(summary)
            return true
        }
        publish(snapshot)
        notifications.onProgress(snapshot)
        true
    }

    fun setLoop(enabled: Boolean): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val changed = active.setLoop(enabled)
        if (!changed) return false
        val snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        true
    }

    fun toggleLoop(): Boolean = synchronized(lock) {
        val active = controller ?: return false
        val changed = active.toggleLoop()
        if (!changed) return false
        val snapshot = active.snapshot()
        publish(snapshot)
        notifications.onProgress(snapshot)
        true
    }

    /**
     * Recomputes timer remaining and publishes a fresh snapshot. Called by the
     * foreground UI ticker and, in background, by the foreground service.
     * The system clock ticker is only a trigger; remaining time always comes
     * from the monotonic deadline inside the controller.
     */
    fun refresh(): Boolean = refreshInternal(allowDirectHaptics = true)

    /**
     * Background refresh. If the UI is visible it owns the ticker and haptics;
     * otherwise the foreground service keeps state and alerts current. The
     * service deliberately does not trigger direct vibration: the expiry
     * notification channel provides the platform-approved background haptic,
     * so the user is not alerted twice.
     */
    fun refreshFromBackground(): Boolean {
        if (uiVisible) return false
        return refreshInternal(allowDirectHaptics = false)
    }

    private fun refreshInternal(allowDirectHaptics: Boolean): Boolean {
        synchronized(lock) {
            val active = controller ?: return false
            active.refresh()
            val snapshot = active.snapshot()
            handlePossibleExpiry(snapshot)
            drainHaptics(active, allowDirectHaptics)
            publish(snapshot)
            notifications.onProgress(snapshot)
            return true
        }
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) refresh()
    }

    fun exitCurrent(): RunSummary? = synchronized(lock) {
        val active = controller ?: return null
        val summary = active.exit() ?: return null
        controller = null
        alertedTimerRunId = null
        _expiryFeedback.value = null
        notifications.onRunExited(summary)
        background.stop(summary.runId)
        _runtime.value = RunRuntimeState.Idle
        summary
    }

    fun dismissResult() {
        synchronized(lock) {
            if (_runtime.value is RunRuntimeState.Finished) {
                _runtime.value = RunRuntimeState.Idle
            }
        }
    }

    private fun publish(snapshot: PlanRunSnapshot) {
        _runtime.value = RunRuntimeState.Active(snapshot)
    }

    private fun handlePossibleExpiry(snapshot: PlanRunSnapshot) {
        val timerId = snapshot.timerRunId ?: return
        if (snapshot.nodeState != NodeRunState.EXPIRED) return
        if (alertedTimerRunId == timerId) return
        alertedTimerRunId = timerId
        _expiryFeedback.value = timerId
        // Foreground expiry is haptic-only; background expiry is delivered by
        // the notification sink. postTimerExpired is a no-op on iOS.
        if (!uiVisible) {
            notifications.onTimerExpired(snapshot)
        }
    }

    private fun drainHaptics(active: PlanRunController, allowDirectHaptics: Boolean) {
        for (event in active.consumeHapticEvents()) {
            if (!allowDirectHaptics) continue
            when (event) {
                RunHapticEvent.TIMER_WARNING -> haptics.timerWarning()
                RunHapticEvent.TIMER_EXPIRED -> haptics.timerExpired()
            }
        }
    }

    /**
     * Marks the expiry feedback token consumed after the UI has played it once.
     * Calling this for an older token never clears a newer expiry.
     */
    fun consumeExpiryFeedback(timerRunId: Long) {
        if (_expiryFeedback.value == timerRunId) _expiryFeedback.value = null
    }

    /** Test/UI helper: whether the current node is the last one. */
    fun isFinalNode(): Boolean = synchronized(lock) {
        controller?.snapshot()?.isFinalNode ?: false
    }
}
