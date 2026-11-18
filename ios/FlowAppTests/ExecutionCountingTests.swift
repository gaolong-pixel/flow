import XCTest
@testable import Flow

@MainActor
final class ExecutionCountingTests: XCTestCase {
    func testIncrementFromZeroUpdatesOnlyCountAndRevision() throws {
        let clock = FakeClock(1_000)
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)]),
            clock: clock
        )
        guard case let .success(started, _) = controller.start(expectedRevision: 0) else {
            return XCTFail("Expected start success")
        }

        guard case let .incremented(session, revision, currentCount, target) =
            controller.incrementCount(expectedRevision: startedRevision(controller)) else {
            return XCTFail("Expected increment success")
        }

        XCTAssertEqual(currentCount, 1)
        XCTAssertEqual(target, 3)
        XCTAssertEqual(session.progress.currentCount, 1)
        XCTAssertEqual(session.records.count, 0)
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.startedAt, 1_000)
        XCTAssertEqual(session.currentRoundStartedAt, 1_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(controller.snapshot().revision, 2)
    }

    func testContinuousIncrementsBelowTargetKeepRecordsEmpty() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 4)])
        )
        _ = controller.start(expectedRevision: 0)
        var revision = controller.snapshot().revision

        for _ in 0..<3 {
            guard case let .incremented(_, nextRevision, _, _) =
                controller.incrementCount(expectedRevision: revision) else {
                return XCTFail("Expected increment success")
            }
            revision = nextRevision
        }

        let snapshot = controller.snapshot()
        XCTAssertEqual(snapshot.session.progress.currentCount, 3)
        XCTAssertEqual(snapshot.session.records.count, 0)
        XCTAssertEqual(snapshot.revision, 4)
        XCTAssertEqual(snapshot.session.status, .running)
    }

    func testReachingTargetCreatesOneRecordAndAdvances() throws {
        let clock = FakeClock(1_000)
        let controller = try makeController(
            routine: makeRoutine(
                steps: [countStep("a", "A", 2, repeatCount: 2)]
            ),
            clock: clock
        )
        _ = controller.start(expectedRevision: 0)
        _ = controller.incrementCount(expectedRevision: 1)
        clock.current = 1_500

        guard case let .roundCompleted(session, revision) =
            controller.incrementCount(expectedRevision: 2) else {
            return XCTFail("Expected round completion")
        }

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .completed)
        XCTAssertEqual(session.records[0].stepIteration, 1)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertEqual(session.progress.stepIteration, 2)
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(revision, 3)
    }

    func testTargetOneCompletesImmediately() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 1)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .roundCompleted(session, revision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected round completion")
        }

        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertEqual(revision, 2)
    }

    func testCompletionMovesToNextStepWithCountReset() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [
                    countStep("a", "A", 1),
                    countStep("b", "B", 2)
                ]
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .roundCompleted(first, firstRevision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected round completion")
        }
        XCTAssertEqual(first.progress.currentStepID, "b")
        XCTAssertEqual(first.progress.currentCount, 0)

        guard case let .incremented(second, _, _, _) =
            controller.incrementCount(expectedRevision: firstRevision) else {
            return XCTFail("Expected increment success")
        }
        XCTAssertEqual(second.progress.currentCount, 1)
        XCTAssertEqual(second.records.count, 1)
    }

    func testCompletionMovesToNextRoutineWithCountReset() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [countStep("a", "A", 1)],
                repeatCount: 2
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .roundCompleted(session, _) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected round completion")
        }

        XCTAssertEqual(session.progress.routineIteration, 2)
        XCTAssertEqual(session.progress.currentCount, 0)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.status, .running)
    }

    func testMultipleStepsAndRoundsFinishWithCompleteRecords() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [
                    countStep("a", "A", 1, repeatCount: 2),
                    countStep("b", "B", 1)
                ],
                repeatCount: 2
            )
        )
        _ = controller.start(expectedRevision: 0)
        var revision: Int64 = 1

        for _ in 0..<6 {
            guard case let .roundCompleted(_, nextRevision) =
                controller.incrementCount(expectedRevision: revision) else {
                return XCTFail("Expected round completion")
            }
            revision = nextRevision
        }

        let snapshot = controller.snapshot()
        XCTAssertEqual(snapshot.session.status, .completed)
        XCTAssertEqual(snapshot.session.records.count, 6)
        XCTAssertEqual(snapshot.session.completedCount, 6)
        XCTAssertEqual(snapshot.revision, 7)
    }

    func testTargetMinusOneToTargetCompletes() throws {
        let session = try runningCountSession(
            target: 5,
            currentCount: 4,
            repeatCount: 1
        )
        let controller = ExecutionController(
            clock: FakeClock(2_000),
            initialState: session,
            initialRevision: 0
        )

        guard case let .roundCompleted(completed, revision) =
            controller.incrementCount(expectedRevision: 0) else {
            return XCTFail("Expected round completion")
        }

        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.records.count, 1)
        XCTAssertEqual(revision, 1)
    }

    func testLargeTargetDoesNotOverflow() throws {
        let session = try runningCountSession(
            target: Int.max,
            currentCount: Int.max - 1,
            repeatCount: 1
        )
        let controller = ExecutionController(
            clock: FakeClock(2_000),
            initialState: session,
            initialRevision: 0
        )

        guard case let .roundCompleted(completed, _) =
            controller.incrementCount(expectedRevision: 0) else {
            return XCTFail("Expected round completion")
        }

        XCTAssertEqual(completed.status, .completed)
        XCTAssertEqual(completed.records.count, 1)
    }

    func testSameExpectedRevisionAllowsOnlyOneCount() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = controller.start(expectedRevision: 0)

        let first = controller.incrementCount(expectedRevision: 1)
        let second = controller.incrementCount(expectedRevision: 1)

        guard case .incremented = first else {
            return XCTFail("Expected first increment success")
        }
        guard case let .failure(error, revision) = second else {
            return XCTFail("Expected stale failure")
        }
        XCTAssertEqual(error, .staleOperation)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(controller.snapshot().session.progress.currentCount, 1)
        XCTAssertEqual(controller.snapshot().revision, 2)
    }

    func testPausedCountIsRejectedWithoutStateChange() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = controller.start(expectedRevision: 0)
        _ = controller.pause(expectedRevision: 1)

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: 2) else {
            return XCTFail("Expected paused failure")
        }

        XCTAssertEqual(error, .sessionNotRunning)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(controller.snapshot().session.status, .paused)
        XCTAssertEqual(controller.snapshot().session.progress.currentCount, 0)
    }

    func testCancelledCountIsRejected() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 3)])
        )
        _ = controller.start(expectedRevision: 0)
        _ = controller.cancel(expectedRevision: 1)

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: 2) else {
            return XCTFail("Expected terminal failure")
        }

        XCTAssertEqual(error, .terminalSession)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(controller.snapshot().session.status, .cancelled)
    }

    func testCompletedCountIsRejected() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 1)])
        )
        _ = controller.start(expectedRevision: 0)
        guard case let .roundCompleted(_, completedRevision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected round completion")
        }

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: completedRevision) else {
            return XCTFail("Expected terminal failure")
        }

        XCTAssertEqual(error, .terminalSession)
        XCTAssertEqual(revision, 2)
    }

    func testTimerStepIncrementIsRejected() throws {
        let controller = try makeController(
            routine: makeRoutine(steps: [timerStep("t", "T", 30_000)])
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected not-count failure")
        }

        XCTAssertEqual(error, .notCountStep)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 0)
    }

    func testCompleteModeIncrementIsRejected() throws {
        let controller = try makeController(
            routine: makeRoutine(
                steps: [countStep("a", "A", 3, mode: .complete)]
            )
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected mode failure")
        }

        XCTAssertEqual(error, .countModeNotIncrement)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().session.progress.currentCount, 0)
    }

    func testCountAlreadyAtTargetIsRejected() throws {
        let session = try runningCountSession(
            target: 2,
            currentCount: 2,
            repeatCount: 1
        )
        let controller = ExecutionController(
            clock: FakeClock(2_000),
            initialState: session,
            initialRevision: 0
        )

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: 0) else {
            return XCTFail("Expected count-at-target failure")
        }

        XCTAssertEqual(error, .countAlreadyAtTarget)
        XCTAssertEqual(revision, 0)
        XCTAssertEqual(controller.snapshot().session.progress.currentCount, 2)
    }

    func testTargetReachedSchedulingFailureLeavesSessionAndRevisionUntouched() throws {
        let clock = FakeClock(1_000)
        let controller = try makeController(
            routine: makeRoutine(steps: [countStep("a", "A", 1)]),
            clock: clock
        )
        _ = controller.start(expectedRevision: 0)
        clock.current = 500

        guard case let .failure(error, revision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected timestamp failure")
        }

        XCTAssertEqual(error, .invalidTimestamp)
        XCTAssertEqual(revision, 1)
        let snapshot = controller.snapshot()
        XCTAssertEqual(snapshot.session.status, .running)
        XCTAssertEqual(snapshot.session.progress.currentCount, 0)
        XCTAssertEqual(snapshot.session.records.count, 0)
        XCTAssertEqual(snapshot.session.currentRoundStartedAt, 1_000)
        XCTAssertEqual(snapshot.revision, 1)
    }

    private func startedRevision(_ controller: ExecutionController) -> Int64 {
        controller.snapshot().revision
    }

    private func makeController(
        routine: Routine,
        clock: ExecutionClock = FakeClock()
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

    private func runningCountSession(
        target: Int,
        currentCount: Int,
        repeatCount: Int
    ) throws -> ExecutionSession {
        try ExecutionSession(
            sessionID: "session-1",
            routineSnapshot: makeRoutine(
                steps: [countStep("a", "A", target, repeatCount: repeatCount)]
            ),
            status: .running,
            progress: ExecutionProgress(
                routineIteration: 1,
                currentStepIndex: 0,
                currentStepID: "a",
                stepIteration: 1,
                currentCount: currentCount
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

        init(_ current: Int64 = 1_000) {
            self.current = current
        }

        var now: Int64 { current }
    }

    private enum TestError: Error {
        case unexpectedCreateFailure
    }
}
