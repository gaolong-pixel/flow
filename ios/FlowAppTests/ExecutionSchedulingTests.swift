import XCTest
@testable import Flow

final class ExecutionSchedulingTests: XCTestCase {
    func testSchedulePlanRequiresCompletenessInvariant() throws {
        let record = record("a", 1, 1)
        let progress = progressForIndex(0, "a", 1)

        XCTAssertNoThrow(
            try SchedulePlan(kind: .complete, record: record, nextProgress: nil)
        )
        XCTAssertNoThrow(
            try SchedulePlan(
                kind: .nextStepRound,
                record: record,
                nextProgress: progress
            )
        )
        XCTAssertThrowsError(
            try SchedulePlan(
                kind: .complete,
                record: record,
                nextProgress: progress
            )
        ) { error in
            XCTAssertEqual(error as? SchedulePlanValidationError, .invalidCompleteness)
        }
        XCTAssertThrowsError(
            try SchedulePlan(
                kind: .nextStepRound,
                record: record,
                nextProgress: nil
            )
        ) { error in
            XCTAssertEqual(error as? SchedulePlanValidationError, .invalidCompleteness)
        }
    }

    func testEmptyRecordsStartsAtFirstCanonicalRound() throws {
        let session = try startedSession(steps: [countStep("a", "A", 3)])

        let plan = try planNext(session)

        XCTAssertEqual(plan.kind, .nextStepRound)
        XCTAssertEqual(plan.record.stepID, "a")
        XCTAssertEqual(plan.record.routineIteration, 1)
        XCTAssertEqual(plan.record.stepIteration, 1)
        XCTAssertEqual(plan.nextProgress?.currentStepID, "a")
        XCTAssertEqual(plan.nextProgress?.stepIteration, 2)
        XCTAssertEqual(plan.nextProgress?.currentCount, 0)
        XCTAssertEqual(plan.nextProgress?.completeConfirmed, false)
    }

    func testCompletedRoundProducesCompletedRecord() throws {
        let session = try startedSession(steps: [countStep("a", "A", 3)])
        let result = stepResult(.completed, 1_000, 1_500)

        let plan = try planNext(session, result: result)
        let committed = try session.commitStepResult(result)

        XCTAssertEqual(plan.record.outcome, .completed)
        XCTAssertEqual(committed.records.count, 1)
        XCTAssertEqual(committed.records[0].outcome, .completed)
        XCTAssertEqual(committed.completedCount, 1)
        XCTAssertEqual(committed.skippedCount, 0)
        XCTAssertEqual(committed.status, .running)
        XCTAssertEqual(committed.progress.stepIteration, 2)
    }

    func testSkippedRoundProducesSkippedRecordAndNotCompletedCount() throws {
        let session = try startedSession(steps: [countStep("a", "A", 3)])
        let result = stepResult(.skipped, 1_000, 1_500)

        let committed = try session.commitStepResult(result)

        XCTAssertEqual(committed.records[0].outcome, .skipped)
        XCTAssertEqual(committed.completedCount, 0)
        XCTAssertEqual(committed.skippedCount, 1)
        XCTAssertEqual(committed.progress.stepIteration, 2)
    }

    func testAdvancesToNextRoundOfSameStep() throws {
        let session = try startedSession(steps: [countStep("a", "A", 3)])

        let plan = try planNext(session)

        XCTAssertEqual(plan.kind, .nextStepRound)
        XCTAssertEqual(plan.nextProgress?.currentStepID, "a")
        XCTAssertEqual(plan.nextProgress?.stepIteration, 2)
    }

    func testAdvancesToNextStep() throws {
        let session = try startedSession(
            steps: [
                countStep("a", "A", 1),
                countStep("b", "B", 1)
            ]
        )

        let plan = try planNext(session)

        XCTAssertEqual(plan.kind, .nextStep)
        XCTAssertEqual(plan.nextProgress?.currentStepIndex, 1)
        XCTAssertEqual(plan.nextProgress?.currentStepID, "b")
        XCTAssertEqual(plan.nextProgress?.stepIteration, 1)
        XCTAssertEqual(plan.nextProgress?.currentCount, 0)
        XCTAssertEqual(plan.nextProgress?.completeConfirmed, false)
    }

    func testAdvancesToNextRoutine() throws {
        let session = try runningSession(
            steps: [
                countStep("a", "A", 1),
                countStep("b", "B", 1)
            ],
            repeatCount: 2,
            records: [record("a", 1, 1)],
            progress: progressForIndex(1, "b", 1)
        )

        let plan = try planNext(session)

        XCTAssertEqual(plan.kind, .nextRoutine)
        XCTAssertEqual(plan.nextProgress?.routineIteration, 2)
        XCTAssertEqual(plan.nextProgress?.currentStepIndex, 0)
        XCTAssertEqual(plan.nextProgress?.currentStepID, "a")
        XCTAssertEqual(plan.nextProgress?.stepIteration, 1)
        XCTAssertEqual(plan.nextProgress?.currentCount, 0)
        XCTAssertEqual(plan.nextProgress?.completeConfirmed, false)
    }

    func testFinalRecordCommitsCompletedStatusAndTimestamp() throws {
        let session = try startedSession(steps: [countStep("a", "A", 1)])
        let result = stepResult(.completed, 1_000, 1_500)

        let plan = try planNext(session, result: result)
        let committed = try session.commitStepResult(result)

        XCTAssertEqual(plan.kind, .complete)
        XCTAssertNil(plan.nextProgress)
        XCTAssertEqual(committed.status, .completed)
        XCTAssertEqual(committed.completedAt, 1_500)
        XCTAssertEqual(committed.records.count, 1)
        XCTAssertEqual(committed.progress.currentStepID, "a")
        XCTAssertEqual(committed.progress.stepIteration, 1)
        XCTAssertEqual(committed.completedCount, 1)
    }

    func testRejectsMissingMiddleRound() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 3)],
            records: [
                record("a", 1, 1),
                record("a", 1, 3)
            ],
            progress: progressForIndex(0, "a", 2)
        )

        assertSchedulingError(.recordsNotPrefix) {
            try planNext(session)
        }
    }

    func testRejectsReversedRecordOrder() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 3)],
            records: [
                record("a", 1, 2),
                record("a", 1, 1)
            ],
            progress: progressForIndex(0, "a", 3)
        )

        assertSchedulingError(.recordsNotPrefix) {
            try planNext(session)
        }
    }

    func testRejectsPrematureNextStepRecord() throws {
        let session = try runningSession(
            steps: [
                countStep("a", "A", 3),
                countStep("b", "B", 2)
            ],
            records: [
                record("a", 1, 1),
                record("b", 1, 1)
            ],
            progress: progressForIndex(0, "a", 2)
        )

        assertSchedulingError(.recordsNotPrefix) {
            try planNext(session)
        }
    }

    func testRejectsSchedulingWithCompleteRecords() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 1)],
            records: [record("a", 1, 1)],
            progress: progressForIndex(0, "a", 1)
        )

        assertSchedulingError(.noRemainingIterations) {
            try planNext(session)
        }
        assertSchedulingError(.noRemainingIterations) {
            try session.commitStepResult(stepResult(.completed, 1_000, 1_500))
        }
    }

    func testRejectsProgressThatDoesNotMatchRecords() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 3)],
            records: [record("a", 1, 1)],
            progress: progressForIndex(0, "a", 3)
        )

        assertSchedulingError(.progressMismatch) {
            try planNext(session)
        }
    }

    func testRejectsPausedSession() throws {
        let paused = try startedSession(steps: [countStep("a", "A", 1)]).pause()

        assertSchedulingError(.sessionNotRunning) {
            try planNext(paused)
        }
        assertSchedulingError(.sessionNotRunning) {
            try paused.commitStepResult(stepResult(.completed, 1_000, 1_500))
        }
    }

    func testPausedSessionMustAlsoHaveOrderedProgress() throws {
        let paused = try ExecutionSession(
            sessionID: "session-1",
            routineSnapshot: routine(steps: [countStep("a", "A", 3)]),
            status: .paused,
            progress: progressForIndex(0, "a", 3),
            timerState: TimerState(
                totalDurationMs: 0,
                remainingDurationMs: 0,
                deadline: nil,
                timerStatus: .idle
            ),
            records: [record("a", 1, 1)],
            startedAt: 1_000,
            currentRoundStartedAt: 1_000
        )

        assertSchedulingError(.progressMismatch) {
            try planNext(paused)
        }
    }

    func testRejectsReadySession() throws {
        let ready = try ExecutionSession.create(
            routine: routine(steps: [countStep("a", "A", 1)])
        )

        assertSchedulingError(.sessionNotRunning) {
            try planNext(ready)
        }
        assertSchedulingError(.sessionNotRunning) {
            try ready.commitStepResult(stepResult(.completed, 1_000, 1_500))
        }
    }

    func testRejectsDuplicateCurrentRoundResult() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 3)],
            records: [record("a", 1, 1)],
            progress: progressForIndex(0, "a", 1)
        )

        assertSchedulingError(.currentRoundAlreadyRecorded) {
            try planNext(session)
        }
        assertSchedulingError(.currentRoundAlreadyRecorded) {
            try session.commitStepResult(stepResult(.completed, 1_000, 1_500))
        }
    }

    func testRejectsStartedAtBeforeSessionStart() throws {
        let session = try startedSession(steps: [countStep("a", "A", 1)])

        assertSchedulingError(.invalidResultTimestamp) {
            try planNext(session, result: stepResult(.completed, 999, 1_500))
        }
    }

    func testRejectsEndedAtBeforeStartedAt() throws {
        let session = try startedSession(steps: [countStep("a", "A", 1)])

        assertSchedulingError(.invalidResultTimestamp) {
            try planNext(session, result: stepResult(.completed, 1_500, 1_000))
        }
    }

    func testCountToTimerTransitionInitializesNextTimerState() throws {
        let session = try startedSession(
            steps: [
                countStep("a", "A", 1),
                timerStep("t", "T", 30_000)
            ]
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.progress.currentStepID, "t")
        XCTAssertEqual(committed.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
    }

    func testTimerToCountTransitionInitializesIdleTimer() throws {
        let session = try startedSession(
            steps: [
                timerStep("t", "T", 30_000),
                countStep("a", "A", 1)
            ]
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.progress.currentStepID, "a")
        XCTAssertEqual(committed.timerState.totalDurationMs, 0)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 0)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
    }

    func testFinalSkippedRoundAlsoCompletesAndKeepsSkippedOutcome() throws {
        let session = try startedSession(steps: [countStep("a", "A", 1)])

        let committed = try session.commitStepResult(
            stepResult(.skipped, 1_000, 1_500)
        )

        XCTAssertEqual(committed.status, .completed)
        XCTAssertEqual(committed.records[0].outcome, .skipped)
        XCTAssertEqual(committed.completedCount, 0)
        XCTAssertEqual(committed.skippedCount, 1)
        XCTAssertEqual(committed.completedAt, 1_500)
    }

    func testTimerStepsUseTheSameSchedulingRules() throws {
        let session = try startedSession(steps: [timerStep("t", "T", 30_000)])

        let plan = try planNext(session)
        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(plan.kind, .complete)
        XCTAssertEqual(plan.record.stepID, "t")
        XCTAssertEqual(committed.status, .completed)
    }

    func testCountToCountResetsTimerState() throws {
        let session = try startedSession(
            steps: [
                countStep("a", "A", 1),
                countStep("b", "B", 1)
            ]
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.progress.currentStepID, "b")
        XCTAssertEqual(committed.timerState.totalDurationMs, 0)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 0)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
    }

    func testTimerToTimerUsesNextStepFullDuration() throws {
        let session = try startedSession(
            steps: [
                timerStep("t1", "T1", 20_000),
                timerStep("t2", "T2", 30_000)
            ]
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.progress.currentStepID, "t2")
        XCTAssertEqual(committed.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
    }

    func testSameTimerStepNextRoundResetsRemaining() throws {
        let session = try startedSession(
            steps: [timerStep("t", "T", 20_000, repeatCount: 2)]
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.progress.currentStepID, "t")
        XCTAssertEqual(committed.progress.stepIteration, 2)
        XCTAssertEqual(committed.timerState.totalDurationMs, 20_000)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 20_000)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
    }

    func testSkippedCountToTimerInitializesFullDuration() throws {
        let session = try startedSession(
            steps: [
                countStep("a", "A", 1),
                timerStep("t", "T", 30_000)
            ]
        )

        let committed = try session.commitStepResult(
            stepResult(.skipped, 1_000, 1_500)
        )

        XCTAssertEqual(committed.progress.currentStepID, "t")
        XCTAssertEqual(committed.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
        XCTAssertEqual(committed.skippedCount, 1)
    }

    func testNextTimerDoesNotInheritRemainingDuration() throws {
        let session = try runningSession(
            steps: [
                timerStep("t1", "T1", 20_000),
                timerStep("t2", "T2", 30_000)
            ],
            records: [],
            progress: progressForIndex(0, "t1", 1),
            timerState: TimerState(
                totalDurationMs: 20_000,
                remainingDurationMs: 5_000,
                deadline: 5_000,
                timerStatus: .running
            )
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(committed.timerState.timerStatus, .idle)
    }

    func testFinalTimerCompletionRetainsCurrentTimerState() throws {
        let runningTimer = TimerState(
            totalDurationMs: 30_000,
            remainingDurationMs: 12_000,
            deadline: 5_000,
            timerStatus: .running
        )
        let session = try runningSession(
            steps: [timerStep("t", "T", 30_000)],
            records: [],
            progress: progressForIndex(0, "t", 1),
            timerState: runningTimer
        )

        let committed = try session.commitStepResult(
            stepResult(.completed, 1_000, 1_500)
        )

        XCTAssertEqual(committed.status, .completed)
        XCTAssertEqual(committed.timerState, runningTimer)
        XCTAssertEqual(committed.completedAt, 1_500)
    }

    func testRejectsResultStartingBeforeCurrentRoundStart() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 1)],
            records: [],
            progress: progressForIndex(0, "a", 1),
            currentRoundStartedAt: 2_000
        )

        assertSchedulingError(.invalidResultTimestamp) {
            try planNext(
                session,
                result: stepResult(.completed, 1_000, 1_500)
            )
        }
    }

    func testRejectsResultEndingBeforeCurrentRoundStart() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 1)],
            records: [],
            progress: progressForIndex(0, "a", 1),
            currentRoundStartedAt: 2_000
        )

        assertSchedulingError(.invalidResultTimestamp) {
            try planNext(
                session,
                result: stepResult(.completed, 1_500, 1_900)
            )
        }
    }

    func testAcceptsResultStartingAtCurrentRoundStart() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 1)],
            records: [],
            progress: progressForIndex(0, "a", 1),
            currentRoundStartedAt: 2_000
        )

        let plan = try planNext(
            session,
            result: stepResult(.completed, 2_000, 2_500)
        )

        XCTAssertEqual(plan.kind, .complete)
    }

    func testAcceptsResultEndingAtCurrentRoundStartWithZeroDuration() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 1)],
            records: [],
            progress: progressForIndex(0, "a", 1),
            currentRoundStartedAt: 2_000
        )

        let plan = try planNext(
            session,
            result: stepResult(.completed, 2_000, 2_000)
        )

        XCTAssertEqual(plan.kind, .complete)
    }

    func testFailedRoundStartTimeCommitKeepsSessionUnchanged() throws {
        let session = try runningSession(
            steps: [countStep("a", "A", 1)],
            records: [],
            progress: progressForIndex(0, "a", 1),
            currentRoundStartedAt: 2_000
        )
        let beforeProgress = session.progress
        let beforeRecords = session.records
        let beforeStatus = session.status
        let beforeRoundStart = session.currentRoundStartedAt

        assertSchedulingError(.invalidResultTimestamp) {
            try session.commitStepResult(
                stepResult(.completed, 1_000, 1_500)
            )
        }

        XCTAssertEqual(session.progress, beforeProgress)
        XCTAssertEqual(session.records, beforeRecords)
        XCTAssertEqual(session.status, beforeStatus)
        XCTAssertEqual(session.currentRoundStartedAt, beforeRoundStart)
    }

    func testFailedCommitDoesNotChangeOriginalSession() throws {
        let session = try startedSession(steps: [countStep("a", "A", 1)])
        let beforeProgress = session.progress
        let beforeRecords = session.records
        let beforeStatus = session.status

        assertSchedulingError(.invalidResultTimestamp) {
            try session.commitStepResult(stepResult(.completed, 999, 1_500))
        }

        XCTAssertEqual(session.progress, beforeProgress)
        XCTAssertEqual(session.records, beforeRecords)
        XCTAssertEqual(session.status, beforeStatus)
        XCTAssertEqual(session.records.count, 0)
    }

    private func startedSession(
        steps: [RoutineStep],
        repeatCount: Int = 1
    ) throws -> ExecutionSession {
        try ExecutionSession.create(
            routine: routine(steps: steps, repeatCount: repeatCount)
        ).start(at: 1_000)
    }

    private func runningSession(
        steps: [RoutineStep],
        repeatCount: Int = 1,
        records: [ExecutionStepRecord],
        progress: ExecutionProgress,
        timerState: TimerState? = nil,
        currentRoundStartedAt: Int64 = 1_000
    ) throws -> ExecutionSession {
        try ExecutionSession(
            sessionID: "session-1",
            routineSnapshot: routine(steps: steps, repeatCount: repeatCount),
            status: .running,
            progress: progress,
            timerState: timerState ?? timerStateFor(steps[progress.currentStepIndex]),
            records: records,
            startedAt: 1_000,
            currentRoundStartedAt: currentRoundStartedAt
        )
    }

    private func progressForIndex(
        _ index: Int,
        _ stepID: String,
        _ stepIteration: Int,
        routineIteration: Int = 1
    ) -> ExecutionProgress {
        ExecutionProgress(
            routineIteration: routineIteration,
            currentStepIndex: index,
            currentStepID: stepID,
            stepIteration: stepIteration
        )
    }

    private func planNext(
        _ session: ExecutionSession,
        result: StepRoundResult? = nil
    ) throws -> SchedulePlan {
        try ExecutionScheduler.planNext(
            session: session,
            result: result ?? stepResult(.completed, 1_000, 1_500)
        )
    }

    private func stepResult(
        _ outcome: ExecutionStepOutcome,
        _ startedAt: Int64,
        _ endedAt: Int64
    ) -> StepRoundResult {
        StepRoundResult(outcome: outcome, startedAt: startedAt, endedAt: endedAt)
    }

    private func record(
        _ stepID: String,
        _ routineIteration: Int,
        _ stepIteration: Int,
        _ outcome: ExecutionStepOutcome = .completed
    ) -> ExecutionStepRecord {
        ExecutionStepRecord(
            stepID: stepID,
            routineIteration: routineIteration,
            stepIteration: stepIteration,
            outcome: outcome,
            startedAt: 1_000,
            endedAt: 1_500
        )
    }

    private func routine(
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
        _ repeatCount: Int
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: 1,
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

    private func timerStateFor(_ step: RoutineStep) -> TimerState {
        switch step.type {
        case .count:
            return TimerState(
                totalDurationMs: 0,
                remainingDurationMs: 0,
                deadline: nil,
                timerStatus: .idle
            )
        case .timer:
            let duration = step.durationMs ?? 0
            return TimerState(
                totalDurationMs: duration,
                remainingDurationMs: duration,
                deadline: nil,
                timerStatus: .idle
            )
        }
    }

    private func assertSchedulingError(
        _ expected: SchedulingError,
        _ block: () throws -> Void
    ) {
        XCTAssertThrowsError(try block()) { error in
            XCTAssertEqual(error as? SchedulingError, expected)
        }
    }
}
