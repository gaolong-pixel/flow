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

/**
 * Scheduling status of a node inside one run.
 *
 * PENDING    - not reached yet, or left without progress.
 * CURRENT    - the active execution target. Only one node is current.
 * DEFERRED   - was started (or reached expiry) and the user deliberately
 *              switched away. It stays executable and keeps its progress.
 * COMPLETED  - counted as completed and removed from the execution roller.
 * SKIPPED    - legacy scheduling value from older builds. No new skips are
 *              created; it is treated as a deferred, selectable node.
 */
enum class NodeStatus { PENDING, CURRENT, DEFERRED, COMPLETED, SKIPPED }

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
    val finalized: Boolean,
    val nodeStatuses: List<NodeStatus> = emptyList(),
    val nodeRemainingMs: List<Long?> = emptyList()
) {
    val node: RoutineStep? get() = plan.steps.getOrNull(nodeIndex)
    val totalNodes: Int get() = plan.steps.size
    val isFinalNode: Boolean get() = !hasSelectableNode
    val totalTimerMs: Long? get() = node?.takeIf { it.type == StepType.TIMER }?.durationMs
    val hasNextNode: Boolean get() = hasSelectableNode
    val currentNodeStatus: NodeStatus
        get() = nodeStatuses.getOrNull(nodeIndex) ?: NodeStatus.CURRENT
    fun remainingFor(index: Int): Long? = nodeRemainingMs.getOrNull(index)

    val hasSelectableNode: Boolean
        get() = nodeStatuses.indices.any { index ->
            index != nodeIndex &&
                (nodeStatuses[index] == NodeStatus.PENDING ||
                    nodeStatuses[index] == NodeStatus.DEFERRED)
        }

    /**
     * Node indexes that remain in the execution roller, in plan order.
     * Completed nodes leave the roller; all other nodes stay visible.
     */
    val visibleNodeIndices: List<Int>
        get() = plan.steps.indices.filter { index ->
            nodeStatuses.getOrNull(index) != NodeStatus.COMPLETED
        }

    val completedNodeIndices: List<Int>
        get() = plan.steps.indices.filter {
            nodeStatuses.getOrNull(it) == NodeStatus.COMPLETED
        }

    val deferredNodeIndices: List<Int>
        get() = plan.steps.indices.filter {
            nodeStatuses.getOrNull(it) == NodeStatus.DEFERRED
        }
}

/** In-memory only result. Never written to PlanStore. */
data class RunSummary(
    val runId: String,
    val planName: String,
    val completedNodes: Int,
    val totalNodes: Int,
    val elapsedMs: Long,
    val completedNormally: Boolean,
    val skippedNodes: Int = 0
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
 *
 * Scheduling rules:
 * - Only one node is CURRENT. The execution order is a recommendation, not a
 *   constraint: selectNode() can switch to any PENDING or DEFERRED node.
 * - Switching away from a RUNNING TIMER freezes its remaining time (PAUSED)
 *   and marks it DEFERRED. Switching away from a RUNNING COUNT keeps its
 *   count and RUNNING state, but it is not the current target.
 * - Completing or skipping a node removes it from the roller and selects the
 *   next PENDING/DEFERRED node in plan order, wrapping around once.
 * - Timer deadlines, completion, notifications and summaries keep the exact
 *   semantics of the previous linear controller for the active node.
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
    private var loop = initialLoop
    private var completed = 0
    private var deadline: Long? = null
    private var finalized = false
    private var exited = false
    private var timerRunId = 0L

    private val statuses = MutableList(plan.steps.size) { position ->
        if (position == 0) NodeStatus.CURRENT else NodeStatus.PENDING
    }
    private val states = MutableList(plan.steps.size) { NodeRunState.READY }
    private val remainingByNode = MutableList<Long?>(plan.steps.size) { null }
    private val timerRunIds = MutableList<Long?>(plan.steps.size) { null }
    private val warnedByNode = MutableList(plan.steps.size) { false }
    private val expiryEventSentByNode = MutableList(plan.steps.size) { false }
    private val pendingEvents = mutableListOf<RunHapticEvent>()
    private val startedAtEpochMs = wallClock.nowEpochMs()

    init {
        require(plan.steps.isNotEmpty()) { "A plan run needs at least one node" }
    }

    private val node: RoutineStep?
        get() = plan.steps.getOrNull(index)

    private var state: NodeRunState
        get() = states[index]
        set(value) {
            states[index] = value
        }

    private var remaining: Long?
        get() = remainingByNode[index]
        set(value) {
            remainingByNode[index] = value
        }

    private var activeTimerRunId: Long?
        get() = timerRunIds[index]
        set(value) {
            timerRunIds[index] = value
        }

    private var warned: Boolean
        get() = warnedByNode[index]
        set(value) {
            warnedByNode[index] = value
        }

    private var expiryEventSent: Boolean
        get() = expiryEventSentByNode[index]
        set(value) {
            expiryEventSentByNode[index] = value
        }

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
            finalized = finalized,
            nodeStatuses = statuses.toList(),
            nodeRemainingMs = plan.steps.indices.map { position ->
                perNodeRemainingLocked(position, now)
            }
        )
    }

    /**
     * Starts the current READY node. COUNT becomes RUNNING; TIMER gets a fresh
     * monotonic deadline for its full duration.
     */
    fun startNode(): Boolean = synchronized(lock) {
        if (finalized || exited || state != NodeRunState.READY) return false
        if (statuses[index] != NodeStatus.CURRENT) return false
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
        if (statuses[index] != NodeStatus.CURRENT) return false
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
        if (statuses[index] != NodeStatus.CURRENT) return false
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
        if (statuses[index] != NodeStatus.CURRENT) return false
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
     * Makes [targetIndex] the current execution target.
     *
     * If the previous target was a RUNNING TIMER, its remaining time is frozen
     * and preserved. A RUNNING COUNT keeps its performed count. Nodes that had
     * already progressed become DEFERRED; untouched nodes return to PENDING.
     * Browsing the roller must never call this function by itself.
     */
    fun selectNode(targetIndex: Int): Boolean = synchronized(lock) {
        if (finalized || exited) return false
        if (targetIndex !in plan.steps.indices) return false
        if (targetIndex == index) return false
        val targetStatus = statuses[targetIndex]
        if (targetStatus != NodeStatus.PENDING &&
            targetStatus != NodeStatus.DEFERRED &&
            targetStatus != NodeStatus.SKIPPED
        ) {
            return false
        }
        suspendCurrentForSwitchLocked()
        index = targetIndex
        statuses[targetIndex] = NodeStatus.CURRENT
        deadline = null
        loop = false
        if (states[targetIndex] == NodeRunState.RUNNING &&
            plan.steps[targetIndex].type == StepType.TIMER
        ) {
            // Defensive: a saved TIMER can never legitimately be RUNNING while
            // it is not the current target. Freeze it instead of resuming a
            // deadline that does not exist.
            states[targetIndex] = NodeRunState.PAUSED
            if ((remainingByNode[targetIndex] ?: 0L) <= 0L) {
                states[targetIndex] = NodeRunState.EXPIRED
            }
        }
        true
    }

    /**
     * Completes the current node.
     *
     * With loop enabled the current node is reset to READY (TIMER back to its
     * full duration, COUNT back to zero) and no progress is counted. Otherwise
     * the node is marked COMPLETED, removed from the roller and the next
     * selectable node becomes current without starting automatically.
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
        statuses[index] = NodeStatus.COMPLETED
        states[index] = NodeRunState.READY
        remaining = null
        deadline = null
        activeTimerRunId = null
        warned = false
        expiryEventSent = false
        loop = false
        advanceAfterRemovalLocked()
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

    /**
     * Freezes or stops the previous current target before switching away.
     * A RUNNING TIMER loses its deadline but keeps the exact remaining budget.
     */
    private fun suspendCurrentForSwitchLocked() {
        val current = node ?: return
        if (state == NodeRunState.RUNNING && current.type == StepType.TIMER) {
            val due = deadline
            if (due != null) {
                val left = (due - monotonic.nowMs()).coerceAtLeast(0L)
                remaining = left
                deadline = null
                if (left == 0L) {
                    state = NodeRunState.EXPIRED
                    queueExpiryEventLocked()
                } else {
                    state = NodeRunState.PAUSED
                }
            }
        } else {
            deadline = null
        }
        statuses[index] = when {
            states[index] == NodeRunState.READY && statuses[index] != NodeStatus.DEFERRED ->
                NodeStatus.PENDING
            else -> NodeStatus.DEFERRED
        }
    }

    /**
     * Chooses the next PENDING/DEFERRED node in plan order (wrapping once) and
     * restores its retained runtime state. Finalizes when none remains.
     */
    private fun advanceAfterRemovalLocked() {
        deadline = null
        val next = (1..plan.steps.size)
            .map { offset -> (index + offset) % plan.steps.size }
            .firstOrNull { candidate ->
                statuses[candidate] == NodeStatus.PENDING ||
                    statuses[candidate] == NodeStatus.DEFERRED
            }
        if (next == null) {
            finalizeLocked()
            return
        }
        index = next
        statuses[next] = NodeStatus.CURRENT
        if (states[next] == NodeRunState.RUNNING &&
            plan.steps[next].type == StepType.TIMER
        ) {
            states[next] = NodeRunState.PAUSED
        }
    }

    private fun finalizeLocked() {
        finalized = true
        states[index] = NodeRunState.READY
        remaining = null
        activeTimerRunId = null
        warned = false
        expiryEventSent = false
    }

    private fun perNodeRemainingLocked(position: Int, now: Long): Long? {
        val step = plan.steps.getOrNull(position) ?: return null
        if (step.type != StepType.TIMER) return null
        if (position == index) return remainingLocked(now)
        return when (states[position]) {
            NodeRunState.READY -> step.durationMs
            NodeRunState.PAUSED, NodeRunState.EXPIRED, NodeRunState.RUNNING ->
                remainingByNode[position]
        }
    }

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

    private fun hasOtherSelectableNodeLocked(): Boolean =
        plan.steps.indices.any { candidate ->
            candidate != index &&
                (statuses[candidate] == NodeStatus.PENDING ||
                    statuses[candidate] == NodeStatus.DEFERRED)
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
        completedNormally = completedNormally,
        skippedNodes = 0
    )

    private fun safeAdd(base: Long, delta: Long): Long =
        if (delta > 0L && base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta

    companion object {
        const val WARNING_THRESHOLD_MS: Long = 3_000L
    }
}
