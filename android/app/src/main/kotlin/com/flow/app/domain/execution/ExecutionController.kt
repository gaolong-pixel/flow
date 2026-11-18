package com.flow.app.domain.execution

import com.flow.app.domain.model.Routine
import java.util.UUID

fun interface ExecutionClock {
    fun now(): Long
}

object SystemExecutionClock : ExecutionClock {
    override fun now(): Long = System.currentTimeMillis()
}

enum class ExecutionControllerError {
    EMPTY_ROUTINE,
    SESSION_NOT_READY,
    SESSION_NOT_RUNNING,
    SESSION_NOT_PAUSED,
    TERMINAL_SESSION,
    STALE_OPERATION,
    INVALID_TIMESTAMP,
    INVALID_SESSION_STATE
}

data class ExecutionControllerSnapshot(
    val session: ExecutionSession,
    val revision: Long
)

sealed interface ExecutionControllerResult {
    data class Success(
        val session: ExecutionSession,
        val revision: Long
    ) : ExecutionControllerResult

    data class Failure(
        val error: ExecutionControllerError,
        val revision: Long
    ) : ExecutionControllerResult
}

sealed interface ExecutionControllerCreateResult {
    data class Success(val controller: ExecutionController) : ExecutionControllerCreateResult
    data class Failure(val error: ExecutionControllerError) : ExecutionControllerCreateResult
}

class ExecutionController internal constructor(
    private val clock: ExecutionClock,
    initialState: ExecutionSession,
    initialRevision: Long = 0L
) {
    private val lock = Any()
    private var session: ExecutionSession = initialState
    private var revisionValue: Long = initialRevision

    fun snapshot(): ExecutionControllerSnapshot = synchronized(lock) {
        ExecutionControllerSnapshot(session = session, revision = revisionValue)
    }

    fun start(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        when (session.status) {
            ExecutionStatus.READY -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_READY)
        }

        val now = clock.now()
        if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
        return runTransition {
            session.start(now)
        }
    }

    fun pause(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        when (session.status) {
            ExecutionStatus.RUNNING -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_RUNNING)
        }

        return runTransition {
            session.pause()
        }
    }

    fun resume(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        when (session.status) {
            ExecutionStatus.PAUSED -> Unit
            else -> return failure(ExecutionControllerError.SESSION_NOT_PAUSED)
        }

        return runTransition {
            session.resume()
        }
    }

    fun cancel(expectedRevision: Long): ExecutionControllerResult = synchronized(lock) {
        staleOrNull(expectedRevision)?.let { return it }
        terminalOrNull()?.let { return it }

        val now = clock.now()
        if (now < 0L) return failure(ExecutionControllerError.INVALID_TIMESTAMP)
        return runTransition {
            session.cancel(now)
        }
    }

    private fun staleOrNull(expectedRevision: Long): ExecutionControllerResult.Failure? =
        if (expectedRevision != revisionValue) {
            ExecutionControllerResult.Failure(
                error = ExecutionControllerError.STALE_OPERATION,
                revision = revisionValue
            )
        } else {
            null
        }

    private fun terminalOrNull(): ExecutionControllerResult.Failure? =
        if (session.status == ExecutionStatus.COMPLETED ||
            session.status == ExecutionStatus.CANCELLED
        ) {
            ExecutionControllerResult.Failure(
                error = ExecutionControllerError.TERMINAL_SESSION,
                revision = revisionValue
            )
        } else {
            null
        }

    private fun runTransition(block: () -> ExecutionSession): ExecutionControllerResult =
        try {
            val next = block()
            session = next
            revisionValue += 1L
            ExecutionControllerResult.Success(
                session = next,
                revision = revisionValue
            )
        } catch (error: ExecutionValidationException) {
            failure(
                if (error.code == ExecutionValidationError.INVALID_TIMESTAMP) {
                    ExecutionControllerError.INVALID_TIMESTAMP
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }

    private fun failure(error: ExecutionControllerError): ExecutionControllerResult.Failure =
        ExecutionControllerResult.Failure(error = error, revision = revisionValue)

    companion object {
        fun create(
            routine: Routine,
            sessionId: String = UUID.randomUUID().toString(),
            clock: ExecutionClock = SystemExecutionClock
        ): ExecutionControllerCreateResult = try {
            val initial = ExecutionSession.create(
                routine = routine,
                sessionId = sessionId
            )
            ExecutionControllerCreateResult.Success(
                ExecutionController(clock = clock, initialState = initial)
            )
        } catch (error: ExecutionValidationException) {
            ExecutionControllerCreateResult.Failure(
                if (error.code == ExecutionValidationError.EMPTY_STEPS) {
                    ExecutionControllerError.EMPTY_ROUTINE
                } else {
                    ExecutionControllerError.INVALID_SESSION_STATE
                }
            )
        }
    }
}
