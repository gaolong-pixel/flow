import XCTest
@testable import Flow

final class ExecutionSessionTests: XCTestCase {
    func testCreatesReadySessionForNonEmptyRoutine() throws {
        let routine = makeRoutine(steps: [
            countStep("count-1", "Push-ups", 12),
            timerStep("timer-1", "Plank", 30_000)
        ])
        let session = try ExecutionSession.create(
            routine: routine,
            sessionID: "session-1"
        )

        XCTAssertEqual(session.sessionID, "session-1")
        XCTAssertEqual(session.routineID, "routine-1")
        XCTAssertEqual(session.status, .ready)
        XCTAssertEqual(session.progress.routineIteration, 1)
        XCTAssertEqual(session.progress.currentStepIndex, 0)
        XCTAssertEqual(session.progress.currentStepID, "count-1")
        XCTAssertEqual(session.progress.stepIteration, 1)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertFalse(session.progress.completeConfirmed)
        XCTAssertEqual(session.timerState.totalDurationMs, 0)
        XCTAssertEqual(session.timerState.remainingDurationMs, 0)
        XCTAssertEqual(session.timerState.timerStatus, .idle)
        XCTAssertTrue(session.records.isEmpty)
        XCTAssertEqual(session.completedCount, 0)
        XCTAssertEqual(session.skippedCount, 0)
    }

    func testTimerFirstStepStartsWithItsDuration() throws {
        let session = try ExecutionSession.create(
            routine: makeRoutine(steps: [timerStep("timer-1", "Plank", 30_000)])
        )

        XCTAssertEqual(session.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(session.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(session.timerState.timerStatus, .idle)
    }

    func testReadySessionHasNoCurrentRoundStart() throws {
        XCTAssertNil(try validSession().currentRoundStartedAt)
    }

    func testStartInitializesCurrentRoundStart() throws {
        XCTAssertEqual(try startSession().currentRoundStartedAt, 1_000)
    }

    func testPauseAndResumePreserveCurrentRoundStart() throws {
        let paused = try startSession().pause()
        XCTAssertEqual(paused.currentRoundStartedAt, 1_000)
        XCTAssertEqual(try paused.resume().currentRoundStartedAt, 1_000)
    }

    func testCancelClearsCurrentRoundStart() throws {
        XCTAssertNil(try startSession().cancel(at: 2_000).currentRoundStartedAt)
    }

    func testCompletedClearsCurrentRoundStart() throws {
        XCTAssertNil(try completedSession().currentRoundStartedAt)
    }

    func testRunningSessionRequiresCurrentRoundStart() throws {
        let base = try validSession()
        assertValidation(.invalidTimestamp) {
            try invalidSession(
                base,
                status: .running,
                startedAt: 1_000,
                currentRoundStartedAt: nil
            )
        }
    }

    func testPausedSessionRequiresCurrentRoundStart() throws {
        let base = try validSession()
        assertValidation(.invalidTimestamp) {
            try invalidSession(
                base,
                status: .paused,
                startedAt: 1_000,
                currentRoundStartedAt: nil
            )
        }
    }

    func testTerminalSessionRejectsCurrentRoundStart() throws {
        assertValidation(.invalidTimestamp) {
            try invalidSession(
                try completedSession(),
                currentRoundStartedAt: 1_000
            )
        }
    }

    func testRejectsCurrentRoundStartBeforeSessionStart() throws {
        let base = try validSession()
        assertValidation(.invalidTimestamp) {
            try invalidSession(
                base,
                status: .running,
                startedAt: 1_000,
                currentRoundStartedAt: 999
            )
        }
    }

    func testCommitAdvancesCurrentRoundStartToEndedAt() throws {
        let session = try ExecutionSession.create(
            routine: makeRoutine(
                steps: [countStep("a", "A", 1, repeatCount: 2)]
            )
        ).start(at: 1_000)

        let committed = try session.commitStepResult(
            StepRoundResult(
                outcome: .completed,
                startedAt: 1_000,
                endedAt: 1_500
            )
        )

        XCTAssertEqual(committed.currentRoundStartedAt, 1_500)
        XCTAssertEqual(committed.progress.stepIteration, 2)
    }

    func testCommitFinalClearsCurrentRoundStart() throws {
        let committed = try startSession().commitStepResult(
            StepRoundResult(
                outcome: .completed,
                startedAt: 1_000,
                endedAt: 1_500
            )
        )

        XCTAssertNil(committed.currentRoundStartedAt)
        XCTAssertEqual(committed.status, .completed)
    }

    func testFailedCommitKeepsCurrentRoundStart() throws {
        let session = try startSession()
        do {
            _ = try session.commitStepResult(
                StepRoundResult(
                    outcome: .completed,
                    startedAt: 999,
                    endedAt: 1_500
                )
            )
            XCTFail("Expected scheduling failure")
        } catch let error as SchedulingError {
            XCTAssertEqual(error, .invalidResultTimestamp)
        }
        XCTAssertEqual(session.currentRoundStartedAt, 1_000)
    }

    func testRejectsEmptyRoutine() {
        assertValidation(.emptySteps) {
            try ExecutionSession.create(routine: makeRoutine(steps: []))
        }
    }

    func testRejectsInvalidRoutineAndStepRepeatCounts() {
        assertValidation(.invalidRoutineRepeat) {
            try ExecutionSession.create(
                routine: makeRoutine(
                    steps: [countStep("a", "A", 1)],
                    repeatCount: 0
                )
            )
        }
        assertValidation(.invalidStepRepeat) {
            try ExecutionSession.create(
                routine: makeRoutine(steps: [countStep("a", "A", 1, repeatCount: 0)])
            )
        }
    }

    func testSnapshotIsIndependentFromOriginalRoutine() throws {
        var sourceSteps = [countStep("a", "A", 1)]
        let original = makeRoutine(steps: sourceSteps)
        let session = try ExecutionSession.create(routine: original)

        sourceSteps.append(timerStep("b", "B", 10_000))

        XCTAssertEqual(original.steps.count, 1)
        XCTAssertEqual(session.routineSnapshot.steps.count, 1)
        XCTAssertEqual(session.routineSnapshot.steps[0].id, "a")
    }

    func testValidatesProgressFields() throws {
        let base = try validSession()
        let target = base.routineSnapshot.steps[0].target ?? 0

        assertValidation(.invalidRoutineIteration) {
            try invalidSession(
                base,
                progress: copy(base.progress, routineIteration: 0)
            )
        }
        assertValidation(.invalidStepIndex) {
            try invalidSession(
                base,
                progress: copy(base.progress, currentStepIndex: 5)
            )
        }
        assertValidation(.stepIDMismatch) {
            try invalidSession(
                base,
                progress: copy(base.progress, currentStepID: "other")
            )
        }
        assertValidation(.invalidStepIteration) {
            try invalidSession(
                base,
                progress: copy(base.progress, stepIteration: 0)
            )
        }
        assertValidation(.invalidCount) {
            try invalidSession(
                base,
                progress: copy(base.progress, currentCount: -1)
            )
        }
        assertValidation(.countExceedsTarget) {
            try invalidSession(
                base,
                progress: copy(base.progress, currentCount: target + 1)
            )
        }
        assertValidation(.invalidCompleteConfirmed) {
            try invalidSession(
                base,
                progress: copy(base.progress, completeConfirmed: true)
            )
        }
    }

    func testValidatesTimerProgress() throws {
        let base = try ExecutionSession.create(
            routine: makeRoutine(steps: [timerStep("timer", "Plank", 30_000)])
        )

        assertValidation(.invalidCount) {
            try invalidSession(
                base,
                progress: copy(base.progress, currentCount: 1)
            )
        }
        assertValidation(.invalidCount) {
            try invalidSession(
                base,
                progress: copy(base.progress, completeConfirmed: true)
            )
        }
    }

    func testValidatesTimerState() throws {
        let base = try validTimerSession()

        assertValidation(.invalidTimerState) {
            try invalidSession(
                base,
                timerState: copy(base.timerState, remainingDurationMs: 40_000)
            )
        }
        assertValidation(.invalidTimerState) {
            try invalidSession(
                base,
                timerState: copy(
                    base.timerState,
                    deadline: nil,
                    timerStatus: .running
                )
            )
        }
        assertValidation(.invalidTimerState) {
            try invalidSession(
                base,
                timerState: copy(
                    base.timerState,
                    deadline: 5_000,
                    timerStatus: .paused
                )
            )
        }
        assertValidation(.invalidTimerState) {
            try invalidSession(
                base,
                timerState: copy(
                    base.timerState,
                    remainingDurationMs: 1,
                    timerStatus: .completed
                )
            )
        }
        assertValidation(.invalidTimerState) {
            try invalidSession(
                try validSession(),
                timerState: TimerState(
                    totalDurationMs: 1_000,
                    remainingDurationMs: 1_000,
                    deadline: nil,
                    timerStatus: .idle
                )
            )
        }
    }

    func testCreatesCompletedAndSkippedRecordsAndDerivesCounts() throws {
        let session = try ExecutionSession.create(
            routine: makeRoutine(steps: [
                countStep("a", "A", 2),
                timerStep("b", "B", 30_000)
            ])
        ).start(at: 1_000)

        let withCompleted = try session.appendRecord(
            record("a", 1, 1, .completed)
        )
        let withBoth = try withCompleted.appendRecord(
            record("b", 1, 1, .skipped)
        )

        XCTAssertEqual(withBoth.records.count, 2)
        XCTAssertEqual(withBoth.completedCount, 1)
        XCTAssertEqual(withBoth.skippedCount, 1)
        XCTAssertEqual(withBoth.status, .running)
    }

    func testRejectsDuplicateAndConflictingRecords() throws {
        let session = try startSession().appendRecord(
            record("a", 1, 1, .completed)
        )

        assertValidation(.duplicateRecord) {
            try session.appendRecord(record("a", 1, 1, .skipped))
        }
        assertValidation(.duplicateRecord) {
            try session.appendRecord(record("a", 1, 1, .completed))
        }
    }

    func testRejectsInvalidRecordValues() throws {
        let session = try startSession()

        assertValidation(.invalidRecord) {
            try session.appendRecord(record("missing", 1, 1, .completed))
        }
        assertValidation(.invalidRecord) {
            try session.appendRecord(record("a", 0, 1, .completed))
        }
        assertValidation(.invalidRecord) {
            try session.appendRecord(record("a", 1, 0, .completed))
        }
        assertValidation(.invalidRecord) {
            try session.appendRecord(
                record("a", 1, 1, .completed, startedAt: 2_000, endedAt: 1_000)
            )
        }
    }

    func testCompletedSessionRequiresFullExecutionResults() throws {
        let session = try startSession()

        assertValidation(.incompleteResults) {
            try session.complete(at: 2_000)
        }

        let full = try session.appendRecord(
            record("a", 1, 1, .completed)
        )
        let completed = try full.complete(at: 2_000)

        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.completedAt, 2_000)
        XCTAssertEqual(completed.completedCount, 1)
        assertValidation(.terminalSession) {
            try completed.appendRecord(record("a", 1, 1, .completed))
        }
    }

    func testCompletedSessionRequiresEveryRoutineAndStepIteration() throws {
        let session = try ExecutionSession.create(
            routine: makeRoutine(
                steps: [countStep("a", "A", 1, repeatCount: 2)],
                repeatCount: 2
            )
        ).start(at: 1_000)

        let partial = try session
            .appendRecord(record("a", 1, 1, .completed))
            .appendRecord(record("a", 1, 2, .skipped))
            .appendRecord(record("a", 2, 1, .completed))

        assertValidation(.incompleteResults) {
            try partial.complete(at: 2_000)
        }

        let completed = try partial
            .appendRecord(record("a", 2, 2, .completed))
            .complete(at: 2_000)

        XCTAssertEqual(completed.records.count, 4)
        XCTAssertEqual(completed.completedCount, 3)
        XCTAssertEqual(completed.skippedCount, 1)
        XCTAssertEqual(completed.status, .completed)
    }

    func testCancelledSessionRetainsRecordsAndRejectsFurtherResults() throws {
        let session = try startSession().appendRecord(
            record("a", 1, 1, .skipped)
        )
        let cancelled = try session.cancel(at: 2_000)

        XCTAssertEqual(cancelled.status, .cancelled)
        XCTAssertEqual(cancelled.cancelledAt, 2_000)
        XCTAssertEqual(cancelled.records.count, 1)
        XCTAssertEqual(cancelled.skippedCount, 1)
        assertValidation(.terminalSession) {
            try cancelled.appendRecord(record("a", 1, 2, .completed))
        }
        assertValidation(.terminalSession) {
            try cancelled.complete(at: 3_000)
        }
    }

    func testPausedSessionCanResume() throws {
        let paused = try startSession().pause()
        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(try paused.resume().status, .running)
    }

    func testValidatesTimestamps() throws {
        let base = try validSession()
        assertValidation(.invalidTimestamp) {
            try invalidSession(base, startedAt: 100)
        }
        assertValidation(.invalidTimestamp) {
            try ExecutionSession(
                sessionID: base.sessionID,
                routineSnapshot: base.routineSnapshot,
                status: .completed,
                progress: base.progress,
                timerState: base.timerState,
                records: [],
                startedAt: 200,
                completedAt: 100
            )
        }
    }

    private func completedSession() throws -> ExecutionSession {
        let running = try startSession()
        let record = ExecutionStepRecord(
            stepID: "a",
            routineIteration: 1,
            stepIteration: 1,
            outcome: .completed,
            startedAt: 1_000,
            endedAt: 1_500
        )
        return try running.appendRecord(record).complete(at: 2_000)
    }

    private func startSession() throws -> ExecutionSession {
        try ExecutionSession.create(
            routine: makeRoutine(steps: [countStep("a", "A", 2)])
        ).start(at: 1_000)
    }

    private func validSession() throws -> ExecutionSession {
        try ExecutionSession.create(
            routine: makeRoutine(steps: [countStep("a", "A", 2)])
        )
    }

    private func validTimerSession() throws -> ExecutionSession {
        try ExecutionSession.create(
            routine: makeRoutine(steps: [timerStep("timer", "Plank", 30_000)])
        )
    }

    private func invalidSession(
        _ base: ExecutionSession,
        status: ExecutionStatus? = nil,
        progress: ExecutionProgress? = nil,
        timerState: TimerState? = nil,
        records: [ExecutionStepRecord]? = nil,
        startedAt: Int64? = nil,
        completedAt: Int64? = nil,
        cancelledAt: Int64? = nil,
        currentRoundStartedAt: Int64? = nil
    ) throws -> ExecutionSession {
        try ExecutionSession(
            sessionID: base.sessionID,
            routineSnapshot: base.routineSnapshot,
            status: status ?? base.status,
            progress: progress ?? base.progress,
            timerState: timerState ?? base.timerState,
            records: records ?? base.records,
            startedAt: startedAt ?? base.startedAt,
            completedAt: completedAt ?? base.completedAt,
            cancelledAt: cancelledAt ?? base.cancelledAt,
            currentRoundStartedAt: currentRoundStartedAt ?? base.currentRoundStartedAt
        )
    }

    private func assertValidation(
        _ expected: ExecutionValidationError,
        _ block: () throws -> Void
    ) {
        XCTAssertThrowsError(try block()) { error in
            XCTAssertEqual(error as? ExecutionValidationError, expected)
        }
    }

    private func record(
        _ stepID: String,
        _ routineIteration: Int,
        _ stepIteration: Int,
        _ outcome: ExecutionStepOutcome,
        startedAt: Int64 = 1_000,
        endedAt: Int64 = 1_500
    ) -> ExecutionStepRecord {
        ExecutionStepRecord(
            stepID: stepID,
            routineIteration: routineIteration,
            stepIteration: stepIteration,
            outcome: outcome,
            startedAt: startedAt,
            endedAt: endedAt
        )
    }

    private func makeRoutine(
        steps: [RoutineStep],
        repeatCount: Int = 1
    ) -> Routine {
        Routine(
            id: "routine-1",
            name: "Test",
            repeatCount: repeatCount,
            steps: steps,
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func countStep(
        _ id: String,
        _ name: String,
        _ target: Int,
        repeatCount: Int = 1
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: target,
            countMode: .increment,
            repeatCount: repeatCount
        )
    }

    private func timerStep(
        _ id: String,
        _ name: String,
        _ durationMs: Int64,
        repeatCount: Int = 1
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .timer,
            durationMs: durationMs,
            category: .normal,
            repeatCount: repeatCount
        )
    }

    private func copy(
        _ progress: ExecutionProgress,
        routineIteration: Int? = nil,
        currentStepIndex: Int? = nil,
        currentStepID: String? = nil,
        stepIteration: Int? = nil,
        currentCount: Int? = nil,
        completeConfirmed: Bool? = nil
    ) -> ExecutionProgress {
        ExecutionProgress(
            routineIteration: routineIteration ?? progress.routineIteration,
            currentStepIndex: currentStepIndex ?? progress.currentStepIndex,
            currentStepID: currentStepID ?? progress.currentStepID,
            stepIteration: stepIteration ?? progress.stepIteration,
            currentCount: currentCount ?? progress.currentCount,
            completeConfirmed: completeConfirmed ?? progress.completeConfirmed
        )
    }

    private func copy(
        _ timer: TimerState,
        remainingDurationMs: Int64? = nil,
        deadline: Int64? = nil,
        timerStatus: TimerStatus? = nil
    ) -> TimerState {
        TimerState(
            totalDurationMs: timer.totalDurationMs,
            remainingDurationMs: remainingDurationMs ?? timer.remainingDurationMs,
            deadline: deadline,
            timerStatus: timerStatus ?? timer.timerStatus
        )
    }
}
