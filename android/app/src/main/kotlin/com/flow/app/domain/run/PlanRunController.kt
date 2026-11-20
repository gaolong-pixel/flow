package com.flow.app.domain.run

import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import java.util.UUID

/**
 * Product run state for the current node.
 *
 * READY    - node is shown, not started yet.
 * RUNNING  - COUNT is being performed / TIMER is counting down.
 * PAUSED   - TIMER countdown frozen; remaining time retained.
 * EXPIRED  - TIMER reached zero. The node is NOT completed automatically.
 */
enum class NodeRunState { READY, RUNNING, PAUSED, EXPIRED }

/** One-shot haptic milestones. Each timer instance emits at most one of each. */
enum class RunHapticEvent { TIMER_WARNING, TIMER_EXPIRED }

data class PlanRunSnapshot(
    val runId: String,
    val plan: Routine,
    val nodeIndex: Int,
    val nodeState: NodeRunState,
    val loopCurrentNode: Boolean,
    val completedNodes: Int,
    val remainingMs: Long?,
    val timerRunId: Long?,
    val runStartedAtEpochMs: Long,
    val finalized: Boolean
) {
    val node: RoutineStep? get() = plan.steps.getOrNull(nodeIndex)
    val totalNodes: Int get() = plan.steps.size
    val isFinalNode: Boolean get() = nodeIndex == plan.steps.lastIndex
    val totalTimerMs: Long? get() = node?.takeIf { it.type == StepType.TIMER }?.durationMs
    val hasNextNode: Boolean get() = nodeIndex < plan.steps.lastIndex
}

/** In-memory only result. Never written to PlanStore. */
data class RunSummary(
    val runId: String,
    val planName: String,
    val completedNodes: Int,
    val totalNodes: Int,
    val elapsedMs: Long,
    val completedNormally: Boolean
)

/**
 * Pure, in-memory state machine for one plan run. It holds no Android
 * dependencies other than the injected monotonic clock so it can be tested on
 * the JVM. Runtime state is never persisted.
 *
 * Time rules:
 * - TIMER remaining time is always derived from the monotonic deadline.
 * - refresh() is the only transition to EXPIRED; it is idempotent.
 * - EXPIRED never completes the node automatically.
 */
class PlanRunController(
    val plan: Routine,
    initialLoop: Boolean = false,
    private val monotonic: RunMonotonicClock,
    private val wallClock: RunWallClock = SystemRunWallClock,
    val runId: String = UUID.randomUUID().toString()
) {
    private val lock = Any()

    private var index = 0
    private var state = NodeRunState.READY
    private var loop = initialLoop
    private var completed = 0
    private var remaining: Long? = null
    private var deadline: Long? = null
    private var finalized = false
    private var exited = false
    private var timerRunId = 0L
    private var activeTimerRunId: Long? = null
    private var warned = false
    private var expiryEventSent = false
    private val pendingEvents = mutableListOf<RunHapticEvent>()
    private val startedAtEpochMs = wallClock.nowEpochMs()

    init {
        require(plan.steps.isNotEmpty()) { "A plan run needs at least one node" }
    }

    private val node: RoutineStep?
        get() = plan.steps.getOrNull(index)

    fun snapshot(): PlanRunSnapshot = synchronized(lock) {
        val now = monotonic.nowMs()
        PlanRunSnapshot(
            runId = runId,
            plan = plan,
            nodeIndex = index,
            nodeState = state,
            loopCurrentNode = loop,
            completedNodes = completed,
            remainingMs = remainingLocked(now),
            timerRunId = activeTimerRunId,
            runStartedAtEpochMs = startedAtEpochMs,
            finalized = finalized
        )
    }

    /**
     * Starts the current READY node. COUNT becomes RUNNING; TIMER gets a fresh
     * monotonic deadline for its full duration.
     */
    fun startNode(): Boolean = synchronized(lock) {
        if (finalized || exited || state != NodeRunState.READY) return false
        val current = node ?: return false
        if (current.type == StepType.TIMER) {
            val duration = current.durationMs ?: return false
            val now = monotonic.nowMs()
            timerRunId += 1L
            activeTimerRunId = timerRunId
            remaining = duration
            deadline = safeAdd(now, duration)
            warned = false
            expiryEventSent = false
        } else {
            remaining = null
            deadline = null
            activeTimerRunId = null
        }
        state = NodeRunState.RUNNING
        true
    }

    /**
     * Recomputes TIMER remaining from the monotonic deadline. Detects expiry
     * and queues the one-shot haptic events. Idempotent and safe to call often;
     * it never completes a node.
     */
    fun refresh(): Boolean = synchronized(lock) {
        if (finalized || exited || state != NodeRunState.RUNNING) return false
        val current = node ?: return false
        if (current.type != StepType.TIMER) return false
        val due = deadline ?: return false
        val now = monotonic.nowMs()
        val left = (due - now).coerceAtLeast(0L)
        remaining = left
        if (left > 0L && !warned && left <= WARNING_THRESHOLD_MS) {
            warned = true
            pendingEvents += RunHapticEvent.TIMER_WARNING
        }
        if (left == 0L) {
            state = NodeRunState.EXPIRED
            deadline = null
            queueExpiryEventLocked()
            return true
        }
        false
    }

    fun pause(): Boolean = synchronized(lock) {
        if (finalized || exited || state != NodeRunState.RUNNING) return false
        val current = node ?: return false
        if (current.type != StepType.TIMER) return false
        val due = deadline ?: return false
        val now = monotonic.nowMs()
        val left = (due - now).coerceAtLeast(0L)
        remaining = left
        deadline = null
        if (left == 0L) {
            state = NodeRunState.EXPIRED
            queueExpiryEventLocked()
            return false
        }
        state = NodeRunState.PAUSED
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        if (finalized || exited || state != NodeRunState.PAUSED) return false
        val left = remaining ?: return false
        if (left <= 0L) {
            state = NodeRunState.EXPIRED
            queueExpiryEventLocked()
            return false
        }
        deadline = safeAdd(monotonic.nowMs(), left)
        state = NodeRunState.RUNNING
        true
    }

    /**
     * Completes the current node.
     *
     * With loop enabled the current node is reset to READY (TIMER back to its
     * full duration) and no progress is counted. Otherwise progress advances;
     * completing the last node finalizes the run.
     */
    fun completeNode(): Boolean = synchronized(lock) {
        if (finalized || exited) return false
        val current = node ?: return false
        val allowed = when (current.type) {
            StepType.COUNT -> state == NodeRunState.RUNNING
            StepType.TIMER -> state == NodeRunState.RUNNING ||
                state == NodeRunState.PAUSED ||
                state == NodeRunState.EXPIRED
        }
        if (!allowed) return false

        if (loop) {
            state = NodeRunState.READY
            remaining = null
            deadline = null
            activeTimerRunId = null
            warned = false
            expiryEventSent = false
            return true
        }

        completed += 1
        remaining = null
        deadline = null
        activeTimerRunId = null
        warned = false
        expiryEventSent = false
        if (index == plan.steps.lastIndex) {
            finalized = true
            state = NodeRunState.READY
        } else {
            index += 1
            state = NodeRunState.READY
            // Loop applies only to the node it was enabled on.
            loop = false
        }
        true
    }

    /** Loop changes never advance, reset or count the node by themselves. */
    fun setLoop(enabled: Boolean): Boolean = synchronized(lock) {
        if (finalized || exited) return false
        loop = enabled
        true
    }

    fun toggleLoop(): Boolean = synchronized(lock) {
        if (finalized || exited) return false
        loop = !loop
        true
    }

    /**
     * Ends the run early. Returns progress for the exit confirmation/feedback.
     * Returns null if the run already finished normally or was already exited.
     */
    fun exit(): RunSummary? = synchronized(lock) {
        if (exited || finalized) return null
        exited = true
        deadline = null
        remaining = null
        activeTimerRunId = null
        summaryLocked(completedNormally = false)
    }

    /** Summary after normal completion. Returns null if not finalized. */
    fun finishedSummary(): RunSummary? = synchronized(lock) {
        if (!finalized || exited) return null
        summaryLocked(completedNormally = true)
    }

    fun consumeHapticEvents(): List<RunHapticEvent> = synchronized(lock) {
        if (pendingEvents.isEmpty()) return emptyList()
        val copy = pendingEvents.toList()
        pendingEvents.clear()
        copy
    }

    val isFinalized: Boolean
        get() = synchronized(lock) { finalized }

    private fun remainingLocked(now: Long): Long? {
        val current = node ?: return null
        if (current.type != StepType.TIMER) return null
        return when (state) {
            NodeRunState.READY -> current.durationMs
            NodeRunState.RUNNING -> deadline?.let { (it - now).coerceAtLeast(0L) } ?: 0L
            NodeRunState.PAUSED -> remaining ?: 0L
            NodeRunState.EXPIRED -> 0L
        }
    }

    private fun queueExpiryEventLocked() {
        if (expiryEventSent) return
        expiryEventSent = true
        pendingEvents += RunHapticEvent.TIMER_EXPIRED
    }

    private fun summaryLocked(completedNormally: Boolean): RunSummary = RunSummary(
        runId = runId,
        planName = plan.name,
        completedNodes = completed,
        totalNodes = plan.steps.size,
        elapsedMs = (wallClock.nowEpochMs() - startedAtEpochMs).coerceAtLeast(0L),
        completedNormally = completedNormally
    )

    private fun safeAdd(base: Long, delta: Long): Long =
        if (delta > 0L && base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta

    companion object {
        const val WARNING_THRESHOLD_MS: Long = 3_000L
    }
}
