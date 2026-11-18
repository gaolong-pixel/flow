import XCTest
@testable import Flow

@MainActor
final class ExecutionControllerTests: XCTestCase {
    func testCreatesReadyControllerSession() throws {
        let controller = try makeController(FakeClock())

        let snapshot = controller.snapshot()
        XCTAssertEqual(snapshot.session.status, .ready)
        XCTAssertNil(snapshot.session.currentRoundStartedAt)
        XCTAssertEqual(snapshot.revision, 0)
    }

    func testRejectsEmptyRoutineWithoutCreatingSession() {
        let result = ExecutionController.create(
            routine: makeRoutine(steps: []),
            sessionID: "session-1",
            clock: FakeClock()
        )
        guard case let .failure(error) = result else {
            return XCTFail("Expected empty routine failure")
        }
        XCTAssertEqual(error, .emptyRoutine)
    }

    func testStartInitializesSessionAndRoundTimestamps() throws {
        let clock = FakeClock(5_000)
        let controller = try makeController(clock)

        let result = controller.start(expectedRevision: 0)
        guard case let .success(session, revision) = result else {
            return XCTFail("Expected start success")
        }

        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.startedAt, 5_000)
        XCTAssertEqual(session.currentRoundStartedAt, 5_000)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().revision, 1)
    }

    func testPauseAndResumeRetainCurrentRoundStart() throws {
        let clock = FakeClock(5_000)
        let controller = try makeController(clock)
        _ = controller.start(expectedRevision: 0)
        clock.current = 6_000

        guard case let .success(paused, _) = controller.pause(expectedRevision: 1) else {
            return XCTFail("Expected pause success")
        }
        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(paused.currentRoundStartedAt, 5_000)
        XCTAssertEqual(paused.startedAt, 5_000)

        clock.current = 7_000
        guard case let .success(resumed, _) = controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected resume success")
        }
        XCTAssertEqual(resumed.status, .running)
        XCTAssertEqual(resumed.currentRoundStartedAt, 5_000)
    }

    func testCancelFromReadyClearsRoundStart() throws {
        let controller = try makeController(FakeClock(5_000))

        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 0) else {
            return XCTFail("Expected cancel success")
        }

        XCTAssertEqual(session.status, .cancelled)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertEqual(revision, 1)
    }

    func testCancelFromRunningClearsRoundStartAndKeepsRecords() throws {
        let controller = try makeController(FakeClock(5_000))
        _ = controller.start(expectedRevision: 0)

        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected cancel success")
        }

        XCTAssertEqual(session.status, .cancelled)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertEqual(session.startedAt, 5_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(session.records.count, 0)
    }

    func testCancelFromPausedClearsRoundStart() throws {
        let controller = try makeController(FakeClock(5_000))
        _ = controller.start(expectedRevision: 0)
        _ = controller.pause(expectedRevision: 1)

        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 2) else {
            return XCTFail("Expected cancel success")
        }

        XCTAssertEqual(session.status, .cancelled)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertEqual(revision, 3)
    }

    func testCompletedSessionCannotBeCancelled() throws {
        let completed = try completedSession()
        let controller = ExecutionController(
            clock: FakeClock(3_000),
            initialState: completed,
            initialRevision: 1
        )

        guard case let .failure(error, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected terminal failure")
        }

        XCTAssertEqual(error, .terminalSession)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().session.status, .completed)
    }

    func testCancelledSessionCannotBeCancelledAgain() throws {
        let controller = try makeController(FakeClock())
        _ = controller.cancel(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected terminal failure")
        }

        XCTAssertEqual(error, .terminalSession)
        XCTAssertEqual(revision, 1)
    }

    func testReadySessionCannotPause() throws {
        let controller = try makeController(FakeClock())

        guard case let .failure(error, revision) =
            controller.pause(expectedRevision: 0) else {
            return XCTFail("Expected pause failure")
        }

        XCTAssertEqual(error, .sessionNotRunning)
        XCTAssertEqual(revision, 0)
    }

    func testReadySessionCannotResume() throws {
        let controller = try makeController(FakeClock())

        guard case let .failure(error, revision) =
            controller.resume(expectedRevision: 0) else {
            return XCTFail("Expected resume failure")
        }

        XCTAssertEqual(error, .sessionNotPaused)
        XCTAssertEqual(revision, 0)
    }

    func testRunningSessionCannotResume() throws {
        let controller = try makeController(FakeClock())
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.resume(expectedRevision: 1) else {
            return XCTFail("Expected resume failure")
        }

        XCTAssertEqual(error, .sessionNotPaused)
        XCTAssertEqual(revision, 1)
    }

    func testPausedSessionCannotPauseAgain() throws {
        let controller = try makeController(FakeClock())
        _ = controller.start(expectedRevision: 0)
        _ = controller.pause(expectedRevision: 1)

        guard case let .failure(error, revision) =
            controller.pause(expectedRevision: 2) else {
            return XCTFail("Expected pause failure")
        }

        XCTAssertEqual(error, .sessionNotRunning)
        XCTAssertEqual(revision, 2)
    }

    func testSuccessfulLifecycleOperationsIncrementRevisionOnce() throws {
        let controller = try makeController(FakeClock())

        _ = controller.start(expectedRevision: 0)
        _ = controller.pause(expectedRevision: 1)
        _ = controller.resume(expectedRevision: 2)
        _ = controller.cancel(expectedRevision: 3)

        XCTAssertEqual(controller.snapshot().revision, 4)
    }

    func testFailedOperationKeepsRevisionAndSessionUnchanged() throws {
        let controller = try makeController(FakeClock())
        let before = controller.snapshot()

        guard case let .failure(error, revision) =
            controller.pause(expectedRevision: 0) else {
            return XCTFail("Expected pause failure")
        }
        let after = controller.snapshot()

        XCTAssertEqual(error, .sessionNotRunning)
        XCTAssertEqual(revision, before.revision)
        XCTAssertEqual(after.revision, before.revision)
        XCTAssertEqual(after.session.status, before.session.status)
        XCTAssertEqual(after.session.progress, before.session.progress)
        XCTAssertEqual(after.session.records, before.session.records)
        XCTAssertEqual(after.session.startedAt, before.session.startedAt)
        XCTAssertEqual(after.session.currentRoundStartedAt, before.session.currentRoundStartedAt)
    }

    func testStaleExpectedRevisionIsRejected() throws {
        let controller = try makeController(FakeClock())
        _ = controller.start(expectedRevision: 0)

        guard case let .failure(error, revision) =
            controller.pause(expectedRevision: 0) else {
            return XCTFail("Expected stale failure")
        }

        XCTAssertEqual(error, .staleOperation)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().session.status, .running)
    }

    func testQueryDoesNotChangeRevision() throws {
        let controller = try makeController(FakeClock())

        XCTAssertEqual(controller.snapshot().revision, 0)
        XCTAssertEqual(controller.snapshot().revision, 0)
    }

    func testFakeClockControlsStartAndCancelTime() throws {
        let clock = FakeClock(9_000)
        let controller = try makeController(clock)
        _ = controller.start(expectedRevision: 0)
        clock.current = 12_000

        guard case let .success(session, _) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected cancel success")
        }

        XCTAssertEqual(session.startedAt, 9_000)
        XCTAssertEqual(session.cancelledAt, 12_000)
    }

    func testClockGoingBackwardsDoesNotCancelOrChangeState() throws {
        let clock = FakeClock(5_000)
        let controller = try makeController(clock)
        _ = controller.start(expectedRevision: 0)
        clock.current = 4_000
        let before = controller.snapshot()

        guard case let .failure(error, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected invalid timestamp")
        }

        XCTAssertEqual(error, .invalidTimestamp)
        XCTAssertEqual(revision, before.revision)
        XCTAssertEqual(controller.snapshot().revision, before.revision)
        XCTAssertEqual(controller.snapshot().session.status, .running)
        XCTAssertEqual(controller.snapshot().session.currentRoundStartedAt, 5_000)
    }

    func testNegativeClockDoesNotChangeStateOrRevision() throws {
        let controller = try makeController(FakeClock(-1))

        guard case let .failure(error, revision) =
            controller.start(expectedRevision: 0) else {
            return XCTFail("Expected timestamp failure")
        }

        XCTAssertEqual(error, .invalidTimestamp)
        XCTAssertEqual(revision, 0)
        XCTAssertEqual(controller.snapshot().session.status, .ready)
        XCTAssertEqual(controller.snapshot().revision, 0)
    }

    func testSameExpectedRevisionOnlyAllowsOneSequentialOperation() throws {
        let controller = try makeController(FakeClock(5_000))

        let first = controller.start(expectedRevision: 0)
        let second = controller.start(expectedRevision: 0)

        guard case .success = first else {
            return XCTFail("Expected first start success")
        }
        guard case let .failure(error, revision) = second else {
            return XCTFail("Expected second start failure")
        }
        XCTAssertEqual(error, .staleOperation)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().revision, 1)
    }

    func testConcurrentMainActorOperationsWithSameRevisionOnlyOneSucceeds() async throws {
        let controller = try makeController(FakeClock(5_000))

        let first = Task { @MainActor in
            isSuccess(controller.start(expectedRevision: 0))
        }
        let second = Task { @MainActor in
            isSuccess(controller.start(expectedRevision: 0))
        }

        let successCount = await first.value + second.value
        XCTAssertEqual(successCount, 1)
        XCTAssertEqual(controller.snapshot().revision, 1)
        XCTAssertEqual(controller.snapshot().session.status, .running)
    }

    private func isSuccess(_ result: ExecutionControllerResult) -> Int {
        if case .success = result {
            return 1
        }
        return 0
    }

    private func makeController(_ clock: ExecutionClock) throws -> ExecutionController {
        let result = ExecutionController.create(
            routine: makeRoutine(steps: [countStep("a", "A", 2)]),
            sessionID: "session-1",
            clock: clock
        )
        guard case let .success(controller) = result else {
            throw TestError.unexpectedCreateFailure
        }
        return controller
    }

    private func completedSession() throws -> ExecutionSession {
        let initial = try ExecutionSession.create(
            routine: makeRoutine(steps: [countStep("a", "A", 1)]),
            sessionID: "session-1"
        )
        let running = try initial.start(at: 1_000)
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

    private func makeRoutine(steps: [RoutineStep]) -> Routine {
        Routine(
            id: "routine-1",
            name: "Test",
            steps: steps,
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func countStep(_ id: String, _ name: String, _ target: Int) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: target,
            countMode: .increment
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
