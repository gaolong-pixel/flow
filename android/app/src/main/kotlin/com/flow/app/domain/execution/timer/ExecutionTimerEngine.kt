package com.flow.app.domain.execution.timer

import android.os.SystemClock
import java.util.UUID

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
    DEADLINE_OVERFLOW
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
    val lastObservedMs: Long?
)

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

    fun start(totalDurationMs: Long): TimerOperationResult = synchronized(lock) {
        if (totalDurationMs <= 0L) {
            return failure(TimerEngineError.INVALID_DURATION)
        }
        if (status == TimerRunStatus.RUNNING || status == TimerRunStatus.PAUSED) {
            return failure(TimerEngineError.INVALID_STATE)
        }

        val now = try {
            readClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        if (now > Long.MAX_VALUE - totalDurationMs) {
            return failure(TimerEngineError.DEADLINE_OVERFLOW)
        }

        runId = TimerRunId(idGenerator())
        status = TimerRunStatus.RUNNING
        this.totalDurationMs = totalDurationMs
        remainingDurationMs = totalDurationMs
        deadlineMonotonicMs = now + totalDurationMs
        return success()
    }

    fun pause(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING) {
            return failure(TimerEngineError.INVALID_STATE)
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
            return expired()
        }

        remainingDurationMs = deadline - now
        deadlineMonotonicMs = null
        status = TimerRunStatus.PAUSED
        return success()
    }

    fun resume(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.PAUSED) {
            return failure(TimerEngineError.INVALID_STATE)
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
        return success()
    }

    fun cancel(): TimerOperationResult = synchronized(lock) {
        if (status != TimerRunStatus.RUNNING && status != TimerRunStatus.PAUSED) {
            return failure(TimerEngineError.INVALID_STATE)
        }

        if (status == TimerRunStatus.PAUSED) {
            deadlineMonotonicMs = null
            status = TimerRunStatus.CANCELLED
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
            return expired()
        }
        remainingDurationMs = deadline - now
        deadlineMonotonicMs = null
        status = TimerRunStatus.CANCELLED
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

        val now = try {
            readClock()
        } catch (error: TimerClockException) {
            return failure(error.error)
        }
        val deadline = deadlineMonotonicMs
            ?: return failure(TimerEngineError.INVALID_STATE)
        if (now >= deadline) {
            complete()
            return expired()
        }
        remainingDurationMs = deadline - now
        return success()
    }

    internal fun debugState(): TimerInternalState = synchronized(lock) {
        TimerInternalState(
            runId = runId,
            status = status,
            totalDurationMs = totalDurationMs,
            remainingDurationMs = remainingDurationMs,
            deadlineMonotonicMs = deadlineMonotonicMs,
            lastObservedMs = lastObservedMs
        )
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
