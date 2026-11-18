package com.flow.app.domain.execution.timer

import android.os.SystemClock
import java.util.UUID

/**
 * Production implementations must be nondecreasing. The Engine rejects
 * observed regressions, but a pure prepare cannot observe a forward movement
 * that is reversed before commit. Tests that violate monotonicity are not
 * production-supported.
 */
fun interface MonotonicClock {
    fun nowMs(): Long
}

object AndroidMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

@JvmInline
value class TimerRunId(val value: String)

enum class TimerRunStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED
}

data class TimerSnapshot(
    val runId: TimerRunId?,
    val status: TimerRunStatus,
    val totalDurationMs: Long,
    val remainingDurationMs: Long,
    val deadlineMonotonicMs: Long?
)

enum class TimerEngineError {
    INVALID_DURATION,
    INVALID_STATE,
    INVALID_CLOCK,
    CLOCK_REGRESSION,
    DEADLINE_OVERFLOW,
    INVALID_PLAN,
    PLAN_STALE,
    PLAN_ENGINE_MISMATCH,
    PLAN_EXPIRED,
    VERSION_OVERFLOW
}

sealed interface TimerOperationResult {
    data class Success(val snapshot: TimerSnapshot) : TimerOperationResult
    data class Expired(val snapshot: TimerSnapshot) : TimerOperationResult
    data class Failure(val error: TimerEngineError) : TimerOperationResult
}

internal data class TimerInternalState(
    val runId: TimerRunId?,
    val status: TimerRunStatus,
    val totalDurationMs: Long,
    val remainingDurationMs: Long,
    val deadlineMonotonicMs: Long?,
    val lastObservedMs: Long?,
    val stateVersion: Long
)

/**
 * Opaque reference handle. Execution parameters live only in
 * Engine.pendingStart. It exposes two read-only projections - targetRunId and
 * deadlineMonotonicMs - solely for Controller candidate construction.
 * commitStart uses the authoritative PendingStart values and never trusts
 * these projections.
 */
internal class TimerPlan internal constructor(
    internal val targetRunId: TimerRunId,
    internal val deadlineMonotonicMs: Long?
)

internal sealed interface TimerPlanResult {
    data class Success(val plan: TimerPlan) : TimerPlanResult
    data class Failure(val error: TimerEngineError) : TimerPlanResult
}

internal sealed interface TimerLifecyclePlan
internal class PausePlan internal constructor(
    internal val remainingDurationMs: Long,
    internal val fallbackRunId: TimerRunId? = null,
    internal val fallbackDeadlineMonotonicMs: Long? = null
) : TimerLifecyclePlan
internal class ResumePlan internal constructor(
    internal val deadlineMonotonicMs: Long,
    internal val fallbackRunId: TimerRunId? = null,
    internal val fallbackDeadlineMonotonicMs: Long? = null
) : TimerLifecyclePlan
internal class CompletionPlan internal constructor(
    internal val nextRunId: TimerRunId?,
    internal val nextDeadlineMonotonicMs: Long?
) : TimerLifecyclePlan
internal class CancelPlan internal constructor(
    internal val remainingDurationMs: Long
) : TimerLifecyclePlan
internal class PollPlan internal constructor() : TimerLifecyclePlan

internal sealed interface TimerPrepareResult {
    data class Ready(val plan: TimerLifecyclePlan) : TimerPrepareResult
    object Expired : TimerPrepareResult
    object NoChange : TimerPrepareResult
    data class Failure(val error: TimerEngineError) : TimerPrepareResult
}

internal sealed interface TimerCommitResult {
    data class Paused(val snapshot: TimerSnapshot) : TimerCommitResult
    data class Resumed(val snapshot: TimerSnapshot) : TimerCommitResult
    data class Cancelled(val snapshot: TimerSnapshot) : TimerCommitResult
    data class Completed(val snapshot: TimerSnapshot) : TimerCommitResult
    data class Expired(val snapshot: TimerSnapshot) : TimerCommitResult
    data class Failure(val error: TimerEngineError) : TimerCommitResult
}

private data class PendingStart(
    val plan: TimerPlan,
    val baseVersion: Long,
    val expectedRunId: TimerRunId?,
    val expectedStatus: TimerRunStatus,
    val targetRunId: TimerRunId,
    val targetStatus: TimerRunStatus,
    val totalDurationMs: Long,
    val remainingDurationMs: Long,
    val deadlineMonotonicMs: Long,
    val preparedAtMonotonicMs: Long
)

private sealed interface PendingLifecycle {
    val plan: TimerLifecyclePlan
    val baseVersion: Long
    val expectedRunId: TimerRunId?
    val expectedStatus: TimerRunStatus
    val preparedAtMonotonicMs: Long
}

private data class PendingPause(
    override val plan: PausePlan,
    override val baseVersion: Long,
    override val expectedRunId: TimerRunId?,
    override val expectedStatus: TimerRunStatus,
    override val preparedAtMonotonicMs: Long,
    val remainingDurationMs: Long,
    val originalDeadlineMonotonicMs: Long,
    val fallbackRunId: TimerRunId?,
    val fallbackDeadlineMonotonicMs: Long?,
    val fallbackTotalDurationMs: Long?
) : PendingLifecycle

private data class PendingResume(
    override val plan: ResumePlan,
    override val baseVersion: Long,
    override val expectedRunId: TimerRunId?,
    override val expectedStatus: TimerRunStatus,
    override val preparedAtMonotonicMs: Long,
    val remainingDurationMs: Long,
    val resumeDeadlineMonotonicMs: Long,
    val fallbackRunId: TimerRunId?,
    val fallbackDeadlineMonotonicMs: Long?,
    val fallbackTotalDurationMs: Long?
) : PendingLifecycle

private data class PendingCancel(
    override val plan: CancelPlan,
    override val baseVersion: Long,
    override val expectedRunId: TimerRunId?,
    override val expectedStatus: TimerRunStatus,
    override val preparedAtMonotonicMs: Long,
    val remainingDurationMs: Long,
    val expiryAtMonotonicMs: Long?
) : PendingLifecycle

private data class PendingCompletion(
    override val plan: CompletionPlan,
    override val baseVersion: Long,
    override val expectedRunId: TimerRunId?,
    override val expectedStatus: TimerRunStatus,
    override val preparedAtMonotonicMs: Long,
    val nextRunId: TimerRunId?,
    val nextDeadlineMonotonicMs: Long?,
    val nextTotalDurationMs: Long?,
    val expirySensitive: Boolean,
    val expiryAtMonotonicMs: Long?
) : PendingLifecycle

private data class PendingPoll(
    override val plan: PollPlan,
    override val baseVersion: Long,
    override val expectedRunId: TimerRunId?,
    override val expectedStatus: TimerRunStatus,
    override val preparedAtMonotonicMs: Long,
    val expiryAtMonotonicMs: Long
) : PendingLifecycle

private class TimerClockException(val error: TimerEngineError) : RuntimeException()

/**
 * Independent monotonic timer run. It never knows about ExecutionSession,
 * ExecutionProgress, ExecutionScheduler, records, or Controllers.
 */
class ExecutionTimerEngine(
    private val clock: MonotonicClock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()

    private var runId: TimerRunId? = null
    private var status: TimerRunStatus = TimerRunStatus.IDLE
    private var totalDurationMs: Long = 0L
    private var remainingDurationMs: Long = 0L
    private var deadlineMonotonicMs: Long? = null
    private var lastObservedMs: Long? = null
    private var stateVersion: Long = 0L
    private var pendingStart: PendingStart? = null
    private var pendingLifecycle: PendingLifecycle? = null
    private var resumeDeadlineAnchorMonotonicMs: Long? = null

    fun start(totalDurationMs: Long): TimerOperationResult {
        val plan = when (val result = prepareStart(totalDurationMs)) {
            is TimerPlanResult.Success -> result.plan
            is TimerPlanResult.Failure -> return failure(result.error)
        }
        return commitStart(plan)
    }

    internal fun prepareStart(totalDurationMs: Long): TimerPlanResult =
        synchronized(lock) {
            if (totalDurationMs <= 0L) {
                return@synchronized TimerPlanResult.Failure(
                    TimerEngineError.INVALID_DURATION
                )
            }
            if (status == TimerRunStatus.RUNNING || status == TimerRunStatus.PAUSED) {
                return@synchronized TimerPlanResult.Failure(
                    TimerEngineError.INVALID_STATE
                )
            }
            val now = try {
                peekClock()
            } catch (error: TimerClockException) {
                return@synchronized TimerPlanResult.Failure(error.error)
            }
            if (now > Long.MAX_VALUE - totalDurationMs) {
                return@synchronized TimerPlanResult.Failure(
                    TimerEngineError.DEADLINE_OVERFLOW
                )
            }
            val plannedRunId = TimerRunId(idGenerator())
            val deadline = now + totalDurationMs
            val plan = TimerPlan(
                targetRunId = plannedRunId,
                deadlineMonotonicMs = deadline
            )
            pendingStart = PendingStart(
                plan = plan,
                baseVersion = stateVersion,
                expectedRunId = runId,
                expectedStatus = status,
                targetRunId = plannedRunId,
                targetStatus = TimerRunStatus.RUNNING,
                totalDurationMs = totalDurationMs,
                remainingDurationMs = totalDurationMs,
                deadlineMonotonicMs = deadline,
                preparedAtMonotonicMs = now
            )
            TimerPlanResult.Success(plan)
        }

    internal fun commitStart(plan: TimerPlan): TimerOperationResult =
        synchronized(lock) {
            val pending = pendingStart
            if (pending == null || plan !== pending.plan) {
                return@synchronized failure(TimerEngineError.INVALID_PLAN)
            }
            if (stateVersion == Long.MAX_VALUE) {
                return@synchronized failure(TimerEngineError.VERSION_OVERFLOW)
            }
            if (pending.baseVersion != stateVersion) {
                return@synchronized failure(TimerEngineError.PLAN_STALE)
            }
            if (pending.expectedRunId != runId || pending.expectedStatus != status) {
                return@synchronized failure(TimerEngineError.PLAN_STALE)
            }
            if (pending.expectedStatus != TimerRunStatus.IDLE &&
                pending.expectedStatus != TimerRunStatus.COMPLETED &&
                pending.expectedStatus != TimerRunStatus.CANCELLED
            ) {
                return@synchronized failure(TimerEngineError.INVALID_PLAN)
            }
            if (pending.targetStatus != TimerRunStatus.RUNNING ||
                pending.targetRunId.value.isBlank() ||
                pending.targetRunId == pending.expectedRunId ||
                pending.totalDurationMs <= 0L ||
                pending.remainingDurationMs != pending.totalDurationMs ||
                pending.preparedAtMonotonicMs < 0L ||
                pending.deadlineMonotonicMs <= 0L
            ) {
                return@synchronized failure(TimerEngineError.INVALID_PLAN)
            }
            if (pending.preparedAtMonotonicMs > Long.MAX_VALUE - pending.totalDurationMs ||
                pending.deadlineMonotonicMs !=
                pending.preparedAtMonotonicMs + pending.totalDurationMs
            ) {
                return@synchronized failure(TimerEngineError.INVALID_PLAN)
            }
            val now = try {
                peekClock()
            } catch (error: TimerClockException) {
                return@synchronized failure(error.error)
            }
            if (now < pending.preparedAtMonotonicMs) {
                return@synchronized failure(TimerEngineError.CLOCK_REGRESSION)
            }
            if (now >= pending.deadlineMonotonicMs) {
                return@synchronized failure(TimerEngineError.PLAN_EXPIRED)
            }
            runId = pending.targetRunId
            status = TimerRunStatus.RUNNING
            totalDurationMs = pending.totalDurationMs
            remainingDurationMs = pending.remainingDurationMs
            deadlineMonotonicMs = pending.deadlineMonotonicMs
            lastObservedMs = now
            stateVersion += 1L
            pendingStart = null
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            success()
        }

    internal fun preparePause(nextTimerDurationMs: Long? = null): TimerPrepareResult =
        synchronized(lock) {
        if (status != TimerRunStatus.RUNNING) {
            return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
        }
        val deadline = deadlineMonotonicMs
            ?: return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerPrepareResult.Failure(error.error)
        }
        if (now >= deadline) {
            return TimerPrepareResult.Expired
        }
        if (stateVersion == Long.MAX_VALUE) {
            return TimerPrepareResult.Failure(TimerEngineError.VERSION_OVERFLOW)
        }
        val fallback = prepareFallbackTarget(now, nextTimerDurationMs)
            ?: if (nextTimerDurationMs != null &&
                (nextTimerDurationMs <= 0L || now > Long.MAX_VALUE - nextTimerDurationMs)
            ) {
                return TimerPrepareResult.Failure(
                    if (nextTimerDurationMs <= 0L) {
                        TimerEngineError.INVALID_DURATION
                    } else {
                        TimerEngineError.DEADLINE_OVERFLOW
                    }
                )
            } else {
                null
            }
        val remaining = deadline - now
        val plan = PausePlan(
            remainingDurationMs = remaining,
            fallbackRunId = fallback?.first,
            fallbackDeadlineMonotonicMs = fallback?.second
        )
        pendingLifecycle = PendingPause(
            plan = plan,
            baseVersion = stateVersion,
            expectedRunId = runId,
            expectedStatus = status,
            preparedAtMonotonicMs = now,
            remainingDurationMs = remaining,
            originalDeadlineMonotonicMs = deadline,
            fallbackRunId = fallback?.first,
            fallbackDeadlineMonotonicMs = fallback?.second,
            fallbackTotalDurationMs = nextTimerDurationMs
        )
        return TimerPrepareResult.Ready(plan)
    }

    internal fun prepareResume(nextTimerDurationMs: Long? = null): TimerPrepareResult =
        synchronized(lock) {
        if (status != TimerRunStatus.PAUSED) {
            return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
        }
        if (stateVersion == Long.MAX_VALUE) {
            return TimerPrepareResult.Failure(TimerEngineError.VERSION_OVERFLOW)
        }
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerPrepareResult.Failure(error.error)
        }
        if (nextTimerDurationMs != null &&
            (nextTimerDurationMs <= 0L ||
                now > Long.MAX_VALUE - nextTimerDurationMs)
        ) {
            return TimerPrepareResult.Failure(
                if (nextTimerDurationMs <= 0L) {
                    TimerEngineError.INVALID_DURATION
                } else {
                    TimerEngineError.DEADLINE_OVERFLOW
                }
            )
        }
        val deadline = resumeDeadlineAnchorMonotonicMs ?: run {
            if (now > Long.MAX_VALUE - remainingDurationMs) {
                return TimerPrepareResult.Failure(TimerEngineError.DEADLINE_OVERFLOW)
            }
            (now + remainingDurationMs).also {
                resumeDeadlineAnchorMonotonicMs = it
            }
        }
        val fallback = prepareFallbackTarget(now, nextTimerDurationMs)
            ?: if (nextTimerDurationMs != null &&
                (nextTimerDurationMs <= 0L || now > Long.MAX_VALUE - nextTimerDurationMs)
            ) {
                return TimerPrepareResult.Failure(
                    if (nextTimerDurationMs <= 0L) {
                        TimerEngineError.INVALID_DURATION
                    } else {
                        TimerEngineError.DEADLINE_OVERFLOW
                    }
                )
            } else {
                null
            }
        val plan = ResumePlan(
            deadlineMonotonicMs = deadline,
            fallbackRunId = fallback?.first,
            fallbackDeadlineMonotonicMs = fallback?.second
        )
        pendingLifecycle = PendingResume(
            plan = plan,
            baseVersion = stateVersion,
            expectedRunId = runId,
            expectedStatus = status,
            preparedAtMonotonicMs = now,
            remainingDurationMs = remainingDurationMs,
            resumeDeadlineMonotonicMs = deadline,
            fallbackRunId = fallback?.first,
            fallbackDeadlineMonotonicMs = fallback?.second,
            fallbackTotalDurationMs = nextTimerDurationMs
        )
        return TimerPrepareResult.Ready(plan)
    }

    internal fun prepareCancel(): TimerPrepareResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING && status != TimerRunStatus.PAUSED) {
            return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
        }
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerPrepareResult.Failure(error.error)
        }
        val expiryAt: Long?
        val remaining: Long
        if (status == TimerRunStatus.RUNNING) {
            val deadline = deadlineMonotonicMs
                ?: return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
            if (now >= deadline) {
                return TimerPrepareResult.Expired
            }
            remaining = deadline - now
            expiryAt = deadline
        } else {
            remaining = remainingDurationMs
            expiryAt = resumeDeadlineAnchorMonotonicMs
            if (expiryAt != null && now >= expiryAt) {
                return TimerPrepareResult.Expired
            }
        }
        if (stateVersion == Long.MAX_VALUE) {
            return TimerPrepareResult.Failure(TimerEngineError.VERSION_OVERFLOW)
        }
        val plan = CancelPlan(remaining)
        pendingLifecycle = PendingCancel(
            plan = plan,
            baseVersion = stateVersion,
            expectedRunId = runId,
            expectedStatus = status,
            preparedAtMonotonicMs = now,
            remainingDurationMs = remaining,
            expiryAtMonotonicMs = expiryAt
        )
        return TimerPrepareResult.Ready(plan)
    }

    internal fun preparePoll(): TimerPrepareResult = synchronized(lock) {
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerPrepareResult.Failure(error.error)
        }
        val expiryAt: Long
        when (status) {
            TimerRunStatus.RUNNING -> {
                val deadline = deadlineMonotonicMs
                    ?: return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
                if (now < deadline) {
                    return TimerPrepareResult.NoChange
                }
                expiryAt = deadline
            }

            TimerRunStatus.PAUSED -> {
                val anchor = resumeDeadlineAnchorMonotonicMs
                    ?: return TimerPrepareResult.NoChange
                if (now < anchor) {
                    return TimerPrepareResult.NoChange
                }
                expiryAt = anchor
            }

            else -> return TimerPrepareResult.NoChange
        }
        if (stateVersion == Long.MAX_VALUE) {
            return TimerPrepareResult.Failure(TimerEngineError.VERSION_OVERFLOW)
        }
        val plan = PollPlan()
        pendingLifecycle = PendingPoll(
            plan = plan,
            baseVersion = stateVersion,
            expectedRunId = runId,
            expectedStatus = status,
            preparedAtMonotonicMs = now,
            expiryAtMonotonicMs = expiryAt
        )
        return TimerPrepareResult.Ready(plan)
    }

    internal fun prepareCompletion(
        nextTimerDurationMs: Long?,
        expirySensitive: Boolean = false
    ): TimerPrepareResult =
        synchronized(lock) {
            if (status != TimerRunStatus.RUNNING && status != TimerRunStatus.PAUSED) {
                return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
            }
            if (stateVersion == Long.MAX_VALUE) {
                return TimerPrepareResult.Failure(TimerEngineError.VERSION_OVERFLOW)
            }
            val now = try {
                peekClock()
            } catch (error: TimerClockException) {
                return TimerPrepareResult.Failure(error.error)
            }
            if (nextTimerDurationMs != null &&
                (nextTimerDurationMs <= 0L ||
                    now > Long.MAX_VALUE - nextTimerDurationMs)
            ) {
                return TimerPrepareResult.Failure(
                    if (nextTimerDurationMs <= 0L) {
                        TimerEngineError.INVALID_DURATION
                    } else {
                        TimerEngineError.DEADLINE_OVERFLOW
                    }
                )
            }
            val expiryAt = if (expirySensitive) {
                when (status) {
                    TimerRunStatus.RUNNING -> deadlineMonotonicMs
                    TimerRunStatus.PAUSED -> resumeDeadlineAnchorMonotonicMs
                    else -> null
                } ?: return TimerPrepareResult.Failure(TimerEngineError.INVALID_STATE)
            } else {
                null
            }
            val nextRunId = nextTimerDurationMs?.let { TimerRunId(idGenerator()) }
            val nextDeadline = nextTimerDurationMs?.let { now + it }
            val plan = CompletionPlan(nextRunId, nextDeadline)
            pendingLifecycle = PendingCompletion(
                plan = plan,
                baseVersion = stateVersion,
                expectedRunId = runId,
                expectedStatus = status,
                preparedAtMonotonicMs = now,
                nextRunId = nextRunId,
                nextDeadlineMonotonicMs = nextDeadline,
                nextTotalDurationMs = nextTimerDurationMs,
                expirySensitive = expirySensitive,
                expiryAtMonotonicMs = expiryAt
            )
            TimerPrepareResult.Ready(plan)
        }

    internal fun commitCompletion(plan: CompletionPlan): TimerCommitResult =
        synchronized(lock) {
            val pending = pendingLifecycle as? PendingCompletion
            if (pending == null || pending.plan !== plan) {
                return TimerCommitResult.Failure(TimerEngineError.INVALID_PLAN)
            }
            val guard = lifecycleCommitGuard(
                pending.baseVersion,
                pending.expectedRunId,
                pending.expectedStatus
            )
            if (guard != null) return TimerCommitResult.Failure(guard)
            val now = try {
                peekClock()
            } catch (error: TimerClockException) {
                return TimerCommitResult.Failure(error.error)
            }
            val expired = pending.expirySensitive &&
                pending.expiryAtMonotonicMs != null &&
                now >= pending.expiryAtMonotonicMs
            applyFallbackOrComplete(
                pending.nextRunId,
                pending.nextDeadlineMonotonicMs,
                pending.nextTotalDurationMs,
                now
            )
            stateVersion += 1L
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            if (expired) {
                TimerCommitResult.Expired(snapshotWithoutClock())
            } else {
                TimerCommitResult.Completed(snapshotWithoutClock())
            }
        }

    internal fun commitPause(plan: PausePlan): TimerCommitResult = synchronized(lock) {
        val pending = pendingLifecycle as? PendingPause
        if (pending == null || pending.plan !== plan) {
            return TimerCommitResult.Failure(TimerEngineError.INVALID_PLAN)
        }
        val guard = lifecycleCommitGuard(
            pending.baseVersion,
            pending.expectedRunId,
            pending.expectedStatus
        )
        if (guard != null) return TimerCommitResult.Failure(guard)
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerCommitResult.Failure(error.error)
        }
        if (now >= pending.originalDeadlineMonotonicMs) {
            applyFallbackOrComplete(
                pending.fallbackRunId,
                pending.fallbackDeadlineMonotonicMs,
                pending.fallbackTotalDurationMs,
                now
            )
            stateVersion += 1L
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            return TimerCommitResult.Expired(snapshotWithoutClock())
        }
        status = TimerRunStatus.PAUSED
        remainingDurationMs = pending.remainingDurationMs
        deadlineMonotonicMs = null
        lastObservedMs = now
        stateVersion += 1L
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        TimerCommitResult.Paused(snapshotWithoutClock())
    }

    internal fun commitResume(plan: ResumePlan): TimerCommitResult = synchronized(lock) {
        val pending = pendingLifecycle as? PendingResume
        if (pending == null || pending.plan !== plan) {
            return TimerCommitResult.Failure(TimerEngineError.INVALID_PLAN)
        }
        val guard = lifecycleCommitGuard(
            pending.baseVersion,
            pending.expectedRunId,
            pending.expectedStatus
        )
        if (guard != null) return TimerCommitResult.Failure(guard)
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerCommitResult.Failure(error.error)
        }
        if (now >= pending.resumeDeadlineMonotonicMs) {
            applyFallbackOrComplete(
                pending.fallbackRunId,
                pending.fallbackDeadlineMonotonicMs,
                pending.fallbackTotalDurationMs,
                now
            )
            stateVersion += 1L
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            return TimerCommitResult.Expired(snapshotWithoutClock())
        }
        status = TimerRunStatus.RUNNING
        deadlineMonotonicMs = pending.resumeDeadlineMonotonicMs
        lastObservedMs = now
        stateVersion += 1L
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        TimerCommitResult.Resumed(snapshotWithoutClock())
    }

    internal fun commitCancel(plan: CancelPlan): TimerCommitResult = synchronized(lock) {
        val pending = pendingLifecycle as? PendingCancel
        if (pending == null || pending.plan !== plan) {
            return TimerCommitResult.Failure(TimerEngineError.INVALID_PLAN)
        }
        val guard = lifecycleCommitGuard(
            pending.baseVersion,
            pending.expectedRunId,
            pending.expectedStatus
        )
        if (guard != null) return TimerCommitResult.Failure(guard)
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerCommitResult.Failure(error.error)
        }
        val expiryAt = pending.expiryAtMonotonicMs
        if (expiryAt != null && now >= expiryAt) {
            complete()
            lastObservedMs = now
            stateVersion += 1L
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            return TimerCommitResult.Expired(snapshotWithoutClock())
        }
        remainingDurationMs = pending.remainingDurationMs
        deadlineMonotonicMs = null
        status = TimerRunStatus.CANCELLED
        lastObservedMs = now
        stateVersion += 1L
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        TimerCommitResult.Cancelled(snapshotWithoutClock())
    }

    internal fun commitPoll(plan: PollPlan): TimerCommitResult = synchronized(lock) {
        val pending = pendingLifecycle as? PendingPoll
        if (pending == null || pending.plan !== plan) {
            return TimerCommitResult.Failure(TimerEngineError.INVALID_PLAN)
        }
        val guard = lifecycleCommitGuard(
            pending.baseVersion,
            pending.expectedRunId,
            pending.expectedStatus
        )
        if (guard != null) return TimerCommitResult.Failure(guard)
        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return TimerCommitResult.Failure(error.error)
        }
        if (now < pending.expiryAtMonotonicMs) {
            return TimerCommitResult.Failure(TimerEngineError.PLAN_STALE)
        }
        complete()
        lastObservedMs = now
        stateVersion += 1L
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        TimerCommitResult.Completed(snapshotWithoutClock())
    }

    private fun applyFallbackOrComplete(
        nextRunId: TimerRunId?,
        nextDeadline: Long?,
        nextTotalDurationMs: Long?,
        now: Long
    ) {
        if (nextRunId != null && nextDeadline != null && nextTotalDurationMs != null) {
            runId = nextRunId
            status = TimerRunStatus.RUNNING
            totalDurationMs = nextTotalDurationMs
            remainingDurationMs = nextTotalDurationMs
            deadlineMonotonicMs = nextDeadline
            lastObservedMs = now
        } else {
            complete()
            lastObservedMs = now
        }
    }

    private fun prepareFallbackTarget(
        now: Long,
        nextTimerDurationMs: Long?
    ): Pair<TimerRunId, Long>? {
        if (nextTimerDurationMs == null) return null
        if (nextTimerDurationMs <= 0L || now > Long.MAX_VALUE - nextTimerDurationMs) {
            return null
        }
        return TimerRunId(idGenerator()) to (now + nextTimerDurationMs)
    }

    private fun lifecycleCommitGuard(
        baseVersion: Long,
        expectedRunId: TimerRunId?,
        expectedStatus: TimerRunStatus
    ): TimerEngineError? {
        if (stateVersion == Long.MAX_VALUE) {
            return TimerEngineError.VERSION_OVERFLOW
        }
        if (baseVersion != stateVersion ||
            expectedRunId != runId ||
            expectedStatus != status
        ) {
            return TimerEngineError.PLAN_STALE
        }
        return null
    }

    fun pause(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING) {
            return failure(TimerEngineError.INVALID_STATE)
        }
        if (stateVersion == Long.MAX_VALUE) {
            return failure(TimerEngineError.VERSION_OVERFLOW)
        }

        val now = try {
            readClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        val deadline = deadlineMonotonicMs
            ?: return failure(TimerEngineError.INVALID_STATE)
        if (now >= deadline) {
            complete()
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            bumpVersion()
            return expired()
        }

        remainingDurationMs = deadline - now
        deadlineMonotonicMs = null
        status = TimerRunStatus.PAUSED
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        bumpVersion()
        return success()
    }

    fun resume(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.PAUSED) {
            return failure(TimerEngineError.INVALID_STATE)
        }
        if (stateVersion == Long.MAX_VALUE) {
            return failure(TimerEngineError.VERSION_OVERFLOW)
        }

        val now = try {
            readClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        if (now > Long.MAX_VALUE - remainingDurationMs) {
            return failure(TimerEngineError.DEADLINE_OVERFLOW)
        }
        deadlineMonotonicMs = now + remainingDurationMs
        status = TimerRunStatus.RUNNING
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        bumpVersion()
        return success()
    }

    fun cancel(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING && status != TimerRunStatus.PAUSED) {
            return failure(TimerEngineError.INVALID_STATE)
        }
        if (stateVersion == Long.MAX_VALUE) {
            return failure(TimerEngineError.VERSION_OVERFLOW)
        }

        if (status == TimerRunStatus.PAUSED) {
            deadlineMonotonicMs = null
            status = TimerRunStatus.CANCELLED
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            bumpVersion()
            return success()
        }

        val now = try {
            readClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        val deadline = deadlineMonotonicMs
            ?: return failure(TimerEngineError.INVALID_STATE)
        if (now >= deadline) {
            complete()
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            bumpVersion()
            return expired()
        }
        remainingDurationMs = deadline - now
        deadlineMonotonicMs = null
        status = TimerRunStatus.CANCELLED
        pendingLifecycle = null
        resumeDeadlineAnchorMonotonicMs = null
        bumpVersion()
        return success()
    }

    /**
     * Pure query. It reads the current monotonic clock for RUNNING projection
     * but never writes any internal field, including lastObservedMs.
     */
    fun snapshot(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING) {
            return success()
        }

        val now = try {
            peekClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        val deadline = deadlineMonotonicMs
            ?: return failure(TimerEngineError.INVALID_STATE)
        val remaining = (deadline - now).coerceAtLeast(0L)
        return TimerOperationResult.Success(
            TimerSnapshot(
                runId = runId,
                status = TimerRunStatus.RUNNING,
                totalDurationMs = totalDurationMs,
                remainingDurationMs = remaining,
                deadlineMonotonicMs = deadline
            )
        )
    }

    /**
     * The only query-like operation that may transition RUNNING to COMPLETED.
     * Repeated calls after completion never produce another Expired result.
     */
    fun poll(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING) {
            return success()
        }
        if (stateVersion == Long.MAX_VALUE) {
            return failure(TimerEngineError.VERSION_OVERFLOW)
        }

        val now = try {
            readClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        val deadline = deadlineMonotonicMs
            ?: return failure(TimerEngineError.INVALID_STATE)
        if (now >= deadline) {
            complete()
            pendingLifecycle = null
            resumeDeadlineAnchorMonotonicMs = null
            bumpVersion()
            return expired()
        }
        remainingDurationMs = deadline - now
        bumpVersion()
        return success()
    }

    internal fun setStateVersionForTesting(version: Long) {
        synchronized(lock) { stateVersion = version }
    }

    internal fun debugState(): TimerInternalState = synchronized(lock) {
        TimerInternalState(
            runId = runId,
            status = status,
            totalDurationMs = totalDurationMs,
            remainingDurationMs = remainingDurationMs,
            deadlineMonotonicMs = deadlineMonotonicMs,
            lastObservedMs = lastObservedMs,
            stateVersion = stateVersion
        )
    }

    private fun bumpVersion() {
        stateVersion += 1L
    }

    private fun success(): TimerOperationResult =
        TimerOperationResult.Success(snapshotWithoutClock())

    private fun expired(): TimerOperationResult =
        TimerOperationResult.Expired(snapshotWithoutClock())

    private fun snapshotWithoutClock(): TimerSnapshot = TimerSnapshot(
        runId = runId,
        status = status,
        totalDurationMs = totalDurationMs,
        remainingDurationMs = remainingDurationMs,
        deadlineMonotonicMs = deadlineMonotonicMs
    )

    private fun complete() {
        status = TimerRunStatus.COMPLETED
        remainingDurationMs = 0L
        deadlineMonotonicMs = null
    }

    private fun peekClock(): Long {
        val now = clock.nowMs()
        if (now < 0L) {
            throw TimerClockException(TimerEngineError.INVALID_CLOCK)
        }
        val previous = lastObservedMs
        if (previous != null && now < previous) {
            throw TimerClockException(TimerEngineError.CLOCK_REGRESSION)
        }
        return now
    }

    private fun readClock(): Long {
        val now = clock.nowMs()
        if (now < 0L) {
            throw TimerClockException(TimerEngineError.INVALID_CLOCK)
        }
        val previous = lastObservedMs
        if (previous != null && now < previous) {
            throw TimerClockException(TimerEngineError.CLOCK_REGRESSION)
        }
        lastObservedMs = now
        return now
    }

    private fun failure(error: TimerEngineError): TimerOperationResult =
        TimerOperationResult.Failure(error)
}
