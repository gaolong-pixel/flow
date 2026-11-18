import XCTest
@testable import Flow

final class ExecutionTimerStateTests: XCTestCase {
    func testStartsIdleTimerAndPreservesEverythingElse() throws {
        let base = try runningTimerSession()
        let updated = try base.startTimer(deadlineMonotonicMs: 5_000)

        XCTAssertEqual(updated.status, .running)
        XCTAssertEqual(updated.timerState.timerStatus, .running)
        XCTAssertEqual(updated.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(updated.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(updated.timerState.deadline, 5_000)
        assertOtherFieldsEqual(base, updated)
        XCTAssertEqual(base.timerState.timerStatus, .idle)
    }

    func testPausesRunningTimerAndPreservesRunIdentityFields() throws {
        let base = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        let updated = try base.pauseTimer(remainingDurationMs: 20_000)

        XCTAssertEqual(updated.status, .paused)
        XCTAssertEqual(updated.timerState.timerStatus, .paused)
        XCTAssertEqual(updated.timerState.remainingDurationMs, 20_000)
        XCTAssertNil(updated.timerState.deadline)
        assertOtherFieldsEqual(base, updated)
        XCTAssertEqual(base.timerState.timerStatus, .running)
    }

    func testResumesPausedTimerWithNewDeadline() throws {
        let base = try runningTimerSession()
            .startTimer(deadlineMonotonicMs: 5_000)
            .pauseTimer(remainingDurationMs: 20_000)
        let updated = try base.resumeTimer(deadlineMonotonicMs: 8_000)

        XCTAssertEqual(updated.status, .running)
        XCTAssertEqual(updated.timerState.timerStatus, .running)
        XCTAssertEqual(updated.timerState.remainingDurationMs, 20_000)
        XCTAssertEqual(updated.timerState.deadline, 8_000)
        assertOtherFieldsEqual(base, updated)
    }

    func testCompletesRunningTimerWithoutAdvancingSession() throws {
        let base = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        let updated = try base.completeTimer()

        XCTAssertEqual(updated.status, .running)
        XCTAssertEqual(updated.timerState.timerStatus, .completed)
        XCTAssertEqual(updated.timerState.remainingDurationMs, 0)
        XCTAssertNil(updated.timerState.deadline)
        assertOtherFieldsEqual(base, updated)
    }

    func testStartTimerRejectsCountStep() throws {
        let count = try countRunningSession()

        assertValidation(.invalidTimerState) {
            try count.startTimer(deadlineMonotonicMs: 5_000)
        }
    }

    func testStartTimerRejectsReadyAndTerminalSession() throws {
        let ready = try readyTimerSession()
        assertValidation(.invalidStatus) {
            try ready.startTimer(deadlineMonotonicMs: 5_000)
        }

        let completed = try completedTimerSession()
        assertValidation(.invalidStatus) {
            try completed.startTimer(deadlineMonotonicMs: 5_000)
        }
    }

    func testPauseTimerRejectsIdleZeroAndOversizedRemaining() throws {
        let runningIdle = try runningTimerSession()
        assertValidation(.invalidTimerState) {
            try runningIdle.pauseTimer(remainingDurationMs: 10_000)
        }

        let started = try runningIdle.startTimer(deadlineMonotonicMs: 5_000)
        assertValidation(.invalidTimerState) {
            try started.pauseTimer(remainingDurationMs: 0)
        }
        assertValidation(.invalidTimerState) {
            try started.pauseTimer(remainingDurationMs: -1)
        }
        assertValidation(.invalidTimerState) {
            try started.pauseTimer(remainingDurationMs: 30_001)
        }
    }

    func testResumeTimerRejectsNonPausedStateAndInvalidDeadline() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        assertValidation(.invalidStatus) {
            try running.resumeTimer(deadlineMonotonicMs: 8_000)
        }

        let paused = try running.pauseTimer(remainingDurationMs: 20_000)
        assertValidation(.invalidTimerState) {
            try paused.resumeTimer(deadlineMonotonicMs: 0)
        }
        assertValidation(.invalidTimerState) {
            try paused.resumeTimer(deadlineMonotonicMs: -1)
        }
    }

    func testCompleteTimerRejectsNonRunningState() throws {
        let paused = try runningTimerSession()
            .startTimer(deadlineMonotonicMs: 5_000)
            .pauseTimer(remainingDurationMs: 20_000)
        assertValidation(.invalidStatus) {
            try paused.completeTimer()
        }
    }

    func testExistingPauseDoesNotPretendToPauseRunningTimer() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)

        assertValidation(.invalidTimerState) {
            _ = try running.pause()
        }

        XCTAssertEqual(running.status, .running)
        XCTAssertEqual(running.timerState.timerStatus, .running)
    }

    func testExistingResumeDoesNotPretendToResumePausedTimer() throws {
        let paused = try runningTimerSession()
            .startTimer(deadlineMonotonicMs: 5_000)
            .pauseTimer(remainingDurationMs: 20_000)

        assertValidation(.invalidTimerState) {
            _ = try paused.resume()
        }

        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(paused.timerState.timerStatus, .paused)
        XCTAssertEqual(paused.timerState.remainingDurationMs, 20_000)
    }

    func testGenericResumeStillWorksForPausedCountSession() throws {
        let count = try countRunningSession()
        let paused = try count.pause()
        let resumed = try paused.resume()

        XCTAssertEqual(resumed.status, .running)
        XCTAssertEqual(resumed.timerState.timerStatus, .idle)
    }

    func testOldCancelRejectsRunningAndPausedTimer() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        assertValidation(.invalidTimerState) {
            _ = try running.cancel(at: 2_000)
        }
        XCTAssertEqual(running.status, .running)

        let paused = try running.pauseTimer(remainingDurationMs: 20_000)
        assertValidation(.invalidTimerState) {
            _ = try paused.cancel(at: 2_000)
        }
        XCTAssertEqual(paused.status, .paused)
    }

    func testOldCancelStillWorksForIdleTimerAndCount() throws {
        let idleTimer = try runningTimerSession()
        let cancelledTimer = try idleTimer.cancel(at: 2_000)
        XCTAssertEqual(cancelledTimer.status, .cancelled)
        XCTAssertEqual(cancelledTimer.timerState.timerStatus, .idle)

        let count = try countRunningSession()
        let cancelledCount = try count.cancel(at: 2_000)
        XCTAssertEqual(cancelledCount.status, .cancelled)
        XCTAssertEqual(cancelledCount.timerState.timerStatus, .idle)
    }

    func testCancelWithTimerStateCancelsRunningTimer() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        let cancelledTimer = TimerState(
            totalDurationMs: 30_000,
            remainingDurationMs: 20_000,
            deadline: nil,
            timerStatus: .cancelled
        )
        let cancelled = try running.cancelWithTimerState(
            at: 2_000,
            timerState: cancelledTimer
        )

        XCTAssertEqual(cancelled.status, .cancelled)
        XCTAssertEqual(cancelled.timerState.timerStatus, .cancelled)
        XCTAssertEqual(cancelled.timerState.remainingDurationMs, 20_000)
        XCTAssertNil(cancelled.timerState.deadline)
        XCTAssertEqual(cancelled.cancelledAt, 2_000)
        XCTAssertNil(cancelled.currentRoundStartedAt)
        XCTAssertEqual(cancelled.records, running.records)
        XCTAssertEqual(cancelled.progress, running.progress)
        XCTAssertEqual(cancelled.routineSnapshot, running.routineSnapshot)
        XCTAssertEqual(running.status, .running)
        XCTAssertEqual(running.timerState.timerStatus, .running)
    }

    func testCancelWithTimerStateAcceptsPausedAndCompletedTimer() throws {
        let paused = try runningTimerSession()
            .startTimer(deadlineMonotonicMs: 5_000)
            .pauseTimer(remainingDurationMs: 20_000)
        let pausedCancelled = try paused.cancelWithTimerState(
            at: 2_000,
            timerState: TimerState(
                totalDurationMs: 30_000,
                remainingDurationMs: 20_000,
                deadline: nil,
                timerStatus: .cancelled
            )
        )
        XCTAssertEqual(pausedCancelled.status, .cancelled)
        XCTAssertEqual(pausedCancelled.timerState.timerStatus, .cancelled)

        let completed = try runningTimerSession()
            .startTimer(deadlineMonotonicMs: 5_000)
            .completeTimer()
        let completedCancelled = try completed.cancelWithTimerState(
            at: 2_000,
            timerState: completed.timerState
        )
        XCTAssertEqual(completedCancelled.status, .cancelled)
        XCTAssertEqual(completedCancelled.timerState.timerStatus, .completed)

        let idle = try runningTimerSession()
        let idleCancelled = try idle.cancelWithTimerState(
            at: 2_000,
            timerState: idle.timerState
        )
        XCTAssertEqual(idleCancelled.status, .cancelled)
        XCTAssertEqual(idleCancelled.timerState.timerStatus, .idle)
    }

    func testCancelWithTimerStateRejectsInvalidFinalStates() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        assertValidation(.invalidTimerState) {
            _ = try running.cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: 20_000,
                    deadline: nil,
                    timerStatus: .paused
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try running.cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: 30_000,
                    deadline: nil,
                    timerStatus: .idle
                )
            )
        }

        let paused = try running.pauseTimer(remainingDurationMs: 20_000)
        assertValidation(.invalidTimerState) {
            _ = try paused.cancelWithTimerState(
                at: 2_000,
                timerState: paused.timerState
            )
        }

        let completed = try running.completeTimer()
        assertValidation(.invalidTimerState) {
            _ = try completed.cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: 20_000,
                    deadline: nil,
                    timerStatus: .cancelled
                )
            )
        }
    }

    func testCancelledTimerStateIsValidAndCountCannotUseIt() throws {
        let started = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        let cancelled = TimerState(
            totalDurationMs: 30_000,
            remainingDurationMs: 10_000,
            deadline: nil,
            timerStatus: .cancelled
        )
        let cancelledSession = try started.cancelWithTimerState(
            at: 2_000,
            timerState: cancelled
        )
        XCTAssertEqual(cancelledSession.timerState.timerStatus, .cancelled)

        assertValidation(.invalidTimerState) {
            _ = try started.cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: 10_000,
                    deadline: 5_000,
                    timerStatus: .cancelled
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try started.cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: -1,
                    deadline: nil,
                    timerStatus: .cancelled
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try started.cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: 30_001,
                    deadline: nil,
                    timerStatus: .cancelled
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try countRunningSession().cancelWithTimerState(
                at: 2_000,
                timerState: TimerState(
                    totalDurationMs: 0,
                    remainingDurationMs: 0,
                    deadline: nil,
                    timerStatus: .cancelled
                )
            )
        }
    }

    func testCancelledSessionCannotBeCancelledAgain() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)
        let cancelled = try running.cancelWithTimerState(
            at: 2_000,
            timerState: TimerState(
                totalDurationMs: 30_000,
                remainingDurationMs: 20_000,
                deadline: nil,
                timerStatus: .cancelled
            )
        )

        assertValidation(.terminalSession) {
            _ = try cancelled.cancel(at: 3_000)
        }
        assertValidation(.terminalSession) {
            _ = try cancelled.cancelWithTimerState(
                at: 3_000,
                timerState: cancelled.timerState
            )
        }
    }

    func testStatusTimerCombinationsFollowAllowlist() throws {
        let runningIdle = try runningTimerSession()
        let running = try runningIdle.startTimer(deadlineMonotonicMs: 5_000)
        let paused = try running.pauseTimer(remainingDurationMs: 20_000)
        let cancelledTimer = TimerState(
            totalDurationMs: 30_000,
            remainingDurationMs: 20_000,
            deadline: nil,
            timerStatus: .cancelled
        )

        assertValidation(.invalidTimerState) {
            _ = try rebuild(running, timerState: paused.timerState)
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(running, timerState: cancelledTimer)
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(paused, timerState: running.timerState)
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                paused,
                timerState: TimerState(
                    totalDurationMs: 30_000,
                    remainingDurationMs: 0,
                    deadline: nil,
                    timerStatus: .completed
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(paused, timerState: cancelledTimer)
        }

        XCTAssertEqual(running.status, .running)
        XCTAssertEqual(running.timerState.timerStatus, .running)
        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(paused.timerState.timerStatus, .paused)
        XCTAssertEqual(
            try runningTimerSession()
                .startTimer(deadlineMonotonicMs: 5_000)
                .completeTimer().status,
            .running
        )
        XCTAssertEqual(runningIdle.status, .running)
        XCTAssertEqual(runningIdle.timerState.timerStatus, .idle)
    }

    func testPausedSessionCannotCarryRunningTimer() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)

        assertValidation(.invalidTimerState) {
            _ = try rebuild(running, status: .paused)
        }
    }

    func testTerminalSessionCannotCarryRunningTimer() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)

        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                running,
                status: .completed,
                completedAt: 2_000,
                currentRoundStartedAt: nil
            )
        }

        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                running,
                status: .cancelled,
                cancelledAt: 2_000,
                currentRoundStartedAt: nil
            )
        }
    }

    func testReadySessionCannotCarryRunningTimer() throws {
        let running = try runningTimerSession().startTimer(deadlineMonotonicMs: 5_000)

        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                running,
                status: .ready,
                startedAt: nil,
                currentRoundStartedAt: nil
            )
        }
    }

    func testInvalidTimerStatesRemainRejected() throws {
        let base = try runningTimerSession()

        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                base,
                timerState: TimerState(
                    totalDurationMs: base.timerState.totalDurationMs,
                    remainingDurationMs: 30_001,
                    deadline: nil,
                    timerStatus: .idle
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                base,
                timerState: TimerState(
                    totalDurationMs: base.timerState.totalDurationMs,
                    remainingDurationMs: -1,
                    deadline: nil,
                    timerStatus: .idle
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                base,
                timerState: TimerState(
                    totalDurationMs: base.timerState.totalDurationMs,
                    remainingDurationMs: 0,
                    deadline: nil,
                    timerStatus: .paused
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                base,
                timerState: TimerState(
                    totalDurationMs: base.timerState.totalDurationMs,
                    remainingDurationMs: 1,
                    deadline: nil,
                    timerStatus: .completed
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                base,
                timerState: TimerState(
                    totalDurationMs: base.timerState.totalDurationMs,
                    remainingDurationMs: 30_000,
                    deadline: nil,
                    timerStatus: .running
                )
            )
        }
        assertValidation(.invalidTimerState) {
            _ = try rebuild(
                base,
                timerState: TimerState(
                    totalDurationMs: base.timerState.totalDurationMs,
                    remainingDurationMs: 30_000,
                    deadline: 0,
                    timerStatus: .running
                )
            )
        }
    }

    func testFailedUpdateLeavesOriginalSessionUntouched() throws {
        let base = try runningTimerSession()
        let beforeTimer = base.timerState

        assertValidation(.invalidStatus) {
            _ = try base.resumeTimer(deadlineMonotonicMs: 5_000)
        }

        XCTAssertEqual(base.timerState, beforeTimer)
        XCTAssertEqual(base.timerState.timerStatus, .idle)
        XCTAssertEqual(base.status, .running)
    }

    private func assertOtherFieldsEqual(
        _ before: ExecutionSession,
        _ after: ExecutionSession
    ) {
        XCTAssertEqual(before.sessionID, after.sessionID)
        XCTAssertEqual(before.routineID, after.routineID)
        XCTAssertEqual(before.routineSnapshot, after.routineSnapshot)
        XCTAssertEqual(before.progress, after.progress)
        XCTAssertEqual(before.records, after.records)
        XCTAssertEqual(before.startedAt, after.startedAt)
        XCTAssertEqual(before.currentRoundStartedAt, after.currentRoundStartedAt)
        XCTAssertEqual(before.completedAt, after.completedAt)
        XCTAssertEqual(before.cancelledAt, after.cancelledAt)
    }

    private func rebuild(
        _ running: ExecutionSession,
        status: ExecutionStatus? = nil,
        timerState: TimerState? = nil,
        startedAt: Int64? = nil,
        completedAt: Int64? = nil,
        cancelledAt: Int64? = nil,
        currentRoundStartedAt: Int64? = nil
    ) throws -> ExecutionSession {
        try ExecutionSession(
            sessionID: running.sessionID,
            routineSnapshot: running.routineSnapshot,
            status: status ?? running.status,
            progress: running.progress,
            timerState: timerState ?? running.timerState,
            records: running.records,
            startedAt: startedAt ?? running.startedAt,
            completedAt: completedAt ?? running.completedAt,
            cancelledAt: cancelledAt ?? running.cancelledAt,
            currentRoundStartedAt: currentRoundStartedAt ?? running.currentRoundStartedAt
        )
    }

    private func assertValidation(
        _ expected: ExecutionValidationError,
        _ block: () throws -> Void
    ) {
        do {
            try block()
            XCTFail("Expected \(expected)")
        } catch let error as ExecutionValidationError {
            XCTAssertEqual(error, expected)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    private func readyTimerSession() throws -> ExecutionSession {
        try ExecutionSession.create(routine: timerRoutine())
    }

    private func runningTimerSession() throws -> ExecutionSession {
        try readyTimerSession().start(at: 1_000)
    }

    private func countRunningSession() throws -> ExecutionSession {
        try ExecutionSession.create(routine: countRoutine()).start(at: 1_000)
    }

    private func completedTimerSession() throws -> ExecutionSession {
        let running = try runningTimerSession()
            .startTimer(deadlineMonotonicMs: 5_000)
            .completeTimer()
        let record = ExecutionStepRecord(
            stepID: "timer",
            routineIteration: 1,
            stepIteration: 1,
            outcome: .completed,
            startedAt: 1_000,
            endedAt: 1_500
        )
        return try running.appendRecord(record).complete(at: 2_000)
    }

    private func timerRoutine() -> Routine {
        Routine(
            id: "routine-1",
            name: "Timer",
            repeatCount: 1,
            steps: [
                RoutineStep(
                    id: "timer",
                    name: "Plank",
                    type: .timer,
                    durationMs: 30_000,
                    category: .normal
                )
            ],
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func countRoutine() -> Routine {
        Routine(
            id: "routine-2",
            name: "Count",
            repeatCount: 1,
            steps: [
                RoutineStep(
                    id: "count",
                    name: "Pushups",
                    type: .count,
                    target: 2,
                    countMode: .increment
                )
            ],
            createdAt: 1,
            updatedAt: 1
        )
    }
}
