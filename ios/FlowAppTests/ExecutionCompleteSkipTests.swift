import XCTest
@testable import Flow

@MainActor
final class ExecutionCompleteSkipTests: XCTestCase {
    func testCompleteConfirmationCreatesRecordAndAdvances() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [
                    countStep("a", "A", 5, repeatCount: 2, mode: .complete)
                ]
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .completed(session, revision) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected complete success")
        }

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .completed)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertEqual(session.progress.stepIteration, 2)
        XCTAssertEqual(session.progress.completeConfirmed, false)
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(revision, 2)
    }

    func testCompleteConfirmationDoesNotFabricateCurrentCount() throws {
        let session = try runningSession(
            step: countStep("a", "A", 5, mode: .complete),
            currentCount: 2,
            completeConfirmed: false
        )
        let controller = ExecutionController(
            clock: FakeClock(2_000),
            initialState: session,
            initialRevision: 0
        )

        guard case let .completed(completed, _) =
            controller.confirmComplete(expectedRevision: 0) else {
            return XCTFail("Expected complete success")
        }

        XCTAssertEqual(completed.progress.currentCount, 2)
        XCTAssertEqual(completed.progress.completeConfirmed, true)
        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.records.count, 1)
    }

    func testCompleteConfirmationMovesToNextStep() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [
                    countStep("a", "A", 3, mode: .complete),
                    countStep("b", "B", 3, mode: .complete)
                ]
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .completed(session, _) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected complete success")
        }

        XCTAssertEqual(session.progress.currentStepID, "b")
        XCTAssertEqual(session.progress.currentStepIndex, 1)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertEqual(session.status, .running)
    }

    func testCompleteConfirmationMovesToNextRoutine() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [countStep("a", "A", 3, mode: .complete)],
                repeatCount: 2
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .completed(session, _) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected complete success")
        }

        XCTAssertEqual(session.progress.routineIteration, 2)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.status, .running)
    }

    func testCompleteConfirmationFinalizesSession() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [countStep("a", "A", 5, mode: .complete)]
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .completed(session, revision) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected complete success")
        }

        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertEqual(session.progress.completeConfirmed, true)
        XCTAssertEqual(session.completedAt, 1_500)
        XCTAssertEqual(revision, 2)
    }

    func testCompleteConfirmationRejectsIncrementMode() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .increment)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected mode failure")
        }

        XCTAssertEqual(error, .countModeNotComplete)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 0)
    }

    func testCompleteConfirmationRejectsTimer() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [timerStep("t", "T", 30_000)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected type failure")
        }

        XCTAssertEqual(error, .notCountStep)
        XCTAssertEqual(revision, 1)
    }

    func testDuplicateCompleteConfirmationIsRejected() throws {
        let session = try runningSession(
            step: countStep("a", "A", 3, mode: .complete),
            currentCount: 0,
            completeConfirmed: true
        )
        let controller = ExecutionController(
            clock: FakeClock(2_000),
            initialState: session,
            initialRevision: 0
        )

        guard case let .failure(error, revision) =
            controller.confirmComplete(expectedRevision: 0) else {
            return XCTFail("Expected duplicate confirmation failure")
        }

        XCTAssertEqual(error, .completeAlreadyConfirmed)
        XCTAssertEqual(revision, 0)
        XCTAssertEqual(controller.snapshot().session.records.count, 0)
    }

    func testCompleteConfirmationRejectsPausedCancelledAndCompleted() throws {
        let paused = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)])
        )
        _ = paused.start(expectedRevision: 0)
        _ = paused.pause(expectedRevision: 1)
        assertCompleteFailure(
            paused.confirmComplete(expectedRevision: 2),
            .sessionNotRunning
        )

        let cancelled = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)])
        )
        _ = cancelled.start(expectedRevision: 0)
        _ = cancelled.cancel(expectedRevision: 1)
        assertCompleteFailure(
            cancelled.confirmComplete(expectedRevision: 2),
            .terminalSession
        )

        let completed = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)])
        )
        _ = completed.start(expectedRevision: 0)
        _ = completed.confirmComplete(expectedRevision: 1)
        assertCompleteFailure(
            completed.confirmComplete(expectedRevision: 2),
            .terminalSession
        )
    }

    func testCompleteConfirmationRejectsStaleRevision() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.confirmComplete(expectedRevision: 0) else {
            return XCTFail("Expected stale failure")
        }

        XCTAssertEqual(error, .staleOperation)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 0)
    }

    func testCompleteConfirmationFailureKeepsSessionUnchanged() throws {
        let clock = FakeClock(1_000)
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)]),
            clock: clock
        )
        _ = controller.start(expectedRevision: 0)
        clock.current = 500
        let before = controller.snapshot()

        guard case let .failure(error, _) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected timestamp failure")
        }

        XCTAssertEqual(error, .invalidTimestamp)
        assertUnchanged(before, controller.snapshot())
    }

    func testSkipCountIncrementCreatesSkippedRecordAndAdvancesRound() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 5, repeatCount: 2)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .skipped(session, revision) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skip success")
        }

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .skipped)
        XCTAssertEqual(session.completedCount, 0)
        XCTAssertEqual(session.skippedCount, 1)
        XCTAssertEqual(session.progress.stepIteration, 2)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(revision, 2)
    }

    func testSkipCountCompleteAndTimerAreAllowed() throws {
        let complete = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)])
        )
        _ = complete.start(expectedRevision: 0)
        guard case let .skipped(completeSession, _) =
            complete.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected count complete skip")
        }
        XCTAssertEqual(completeSession.records[0].outcome, .skipped)

        let timer = try makeController(
            routine: makeRoutine(steps: [timerStep("t", "T", 30_000)])
        )
        _ = timer.start(expectedRevision: 0)
        guard case let .skipped(timerSession, _) =
            timer.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected timer skip")
        }
        XCTAssertEqual(timerSession.records[0].outcome, .skipped)
    }

    func testSkipMovesToNextStepAndNextRoutine() throws {
        let nextStep = try makeController(
            routine: makeRoutine(
                steps: [
                    countStep("a", "A", 3),
                    countStep("b", "B", 3)
                ]
            )
        )
        _ = nextStep.start(expectedRevision: 0)
        guard case let .skipped(stepSession, _) =
            nextStep.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skip success")
        }
        XCTAssertEqual(stepSession.progress.currentStepID, "b")
        XCTAssertEqual(stepSession.progress.currentCount, 0)

        let nextRoutine = try makeController(
            routine: makeRoutine(
                steps: [countStep("a", "A", 3)],
                repeatCount: 2
            )
        )
        _ = nextRoutine.start(expectedRevision: 0)
        guard case let .skipped(routineSession, _) =
            nextRoutine.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skip success")
        }
        XCTAssertEqual(routineSession.progress.routineIteration, 2)
        XCTAssertEqual(routineSession.progress.currentCount, 0)
    }

    func testFinalSkipCompletesSessionWithoutCompletedCount() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .skipped(session, _) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skip success")
        }

        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(session.completedCount, 0)
        XCTAssertEqual(session.skippedCount, 1)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertEqual(session.completedAt, 1_500)
    }

    func testSkipRejectsStalePausedCancelledAndCompleted() throws {
        let stale = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = stale.start(expectedRevision: 0)
        assertSkipFailure(stale.skipCurrentRound(expectedRevision: 0), .staleOperation)

        let paused = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = paused.start(expectedRevision: 0)
        _ = paused.pause(expectedRevision: 1)
        assertSkipFailure(paused.skipCurrentRound(expectedRevision: 2), .sessionNotRunning)

        let cancelled = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = cancelled.start(expectedRevision: 0)
        _ = cancelled.cancel(expectedRevision: 1)
        assertSkipFailure(cancelled.skipCurrentRound(expectedRevision: 2), .terminalSession)

        let completed = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = completed.start(expectedRevision: 0)
        _ = completed.skipCurrentRound(expectedRevision: 1)
        assertSkipFailure(completed.skipCurrentRound(expectedRevision: 2), .terminalSession)
    }

    func testSkipFailureKeepsSessionUnchanged() throws {
        let clock = FakeClock(1_000)
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)]),
            clock: clock
        )
        _ = controller.start(expectedRevision: 0)
        clock.current = 500
        let before = controller.snapshot()

        guard case let .failure(error, _) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected timestamp failure")
        }

        XCTAssertEqual(error, .invalidTimestamp)
        assertUnchanged(before, controller.snapshot())
    }

    func testSuccessfulCompleteAndSkipIncrementRevisionOnce() throws {
        let complete = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3, mode: .complete)])
        )
        _ = complete.start(expectedRevision: 0)
        guard case let .completed(_, completedRevision) =
            complete.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected complete success")
        }
        XCTAssertEqual(completedRevision, 2)
        XCTAssertEqual(complete.snapshot().revision, 2)

        let skip = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = skip.start(expectedRevision: 0)
        guard case let .skipped(_, skippedRevision) =
            skip.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skip success")
        }
        XCTAssertEqual(skippedRevision, 2)
        XCTAssertEqual(skip.snapshot().revision, 2)
    }

    private func assertCompleteFailure(
        _ result: StepActionResult,
        _ expected: ExecutionControllerError
    ) {
        guard case let .failure(error, _) = result else {
            return XCTFail("Expected completion failure")
        }
        XCTAssertEqual(error, expected)
    }

    private func assertSkipFailure(
        _ result: StepActionResult,
        _ expected: ExecutionControllerError
    ) {
        guard case let .failure(error, _) = result else {
            return XCTFail("Expected skip failure")
        }
        XCTAssertEqual(error, expected)
    }

    private func assertUnchanged(
        _ before: ExecutionControllerSnapshot,
        _ after: ExecutionControllerSnapshot
    ) {
        XCTAssertEqual(before.revision, after.revision)
        XCTAssertEqual(before.session.status, after.session.status)
        XCTAssertEqual(before.session.records, after.session.records)
        XCTAssertEqual(before.session.progress, after.session.progress)
        XCTAssertEqual(before.session.timerState, after.session.timerState)
        XCTAssertEqual(before.session.currentRoundStartedAt, after.session.currentRoundStartedAt)
        XCTAssertEqual(before.session.completedAt, after.session.completedAt)
        XCTAssertEqual(before.session.cancelledAt, after.session.cancelledAt)
        XCTAssertEqual(before.session.startedAt, after.session.startedAt)
    }

    private func makeController(
        routine: Routine,
        clock: FakeClock = FakeClock()
    ) throws -> ExecutionController {
        let result = ExecutionController.create(
            routine: routine,
            sessionID: "session-1",
            clock: clock
        )
        guard case let .success(controller) = result else {
            throw TestError.unexpectedCreateFailure
        }
        return controller
    }

    private func runningSession(
        step: RoutineStep,
        currentCount: Int,
        completeConfirmed: Bool
    ) throws -> ExecutionSession {
        try ExecutionSession(
            sessionID: "session-1",
            routineSnapshot: makeRoutine(steps: [step]),
            status: .running,
            progress: ExecutionProgress(
                routineIteration: 1,
                currentStepIndex: 0,
                currentStepID: step.id,
                stepIteration: 1,
                currentCount: currentCount,
                completeConfirmed: completeConfirmed
            ),
            timerState: TimerState(
                totalDurationMs: 0,
                remainingDurationMs: 0,
                deadline: nil,
                timerStatus: .idle
            ),
            records: [],
            startedAt: 1_000,
            currentRoundStartedAt: 1_000
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
        repeatCount: Int = 1,
        mode: CountMode = .increment
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: target,
            countMode: mode,
            repeatCount: repeatCount
        )
    }

    private func timerStep(
        _ id: String,
        _ name: String,
        _ durationMs: Int64
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .timer,
            durationMs: durationMs,
            category: .normal
        )
    }

    private final class FakeClock: ExecutionClock {
        var current: Int64

        init(_ current: Int64 = 1_500) {
            self.current = current
        }

        var now: Int64 { current }
    }

    private enum TestError: Error {
        case unexpectedCreateFailure
    }
}
