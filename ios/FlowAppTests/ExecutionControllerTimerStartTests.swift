import XCTest
@testable import Flow

@MainActor
final class ExecutionControllerTimerStartTests: XCTestCase {
    // MARK: - Start

    func testStartAutoStartsFirstTimerAtomically() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )

        guard case let .success(session, revision) =
            controller.start(expectedRevision: 0) else {
            return XCTFail("Expected start success")
        }
        let snapshot = controller.snapshot()
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.totalDurationMs, 30_000)
        XCTAssertEqual(session.timerState.remainingDurationMs, 30_000)
        XCTAssertEqual(session.timerState.deadline, 31_000)
        XCTAssertEqual(session.records.count, 0)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(snapshot.revision, 1)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(engine.deadlineMonotonicMs, 31_000)
        XCTAssertNotNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
        XCTAssertEqual(controller.currentTimerStepKey(), controller.activeTimerStepKey)
    }

    func testStartCountRoutineDoesNotStartTimerEngine() throws {
        let controller = try makeController(
            routine: countRoutine(),
            wall: FakeExecutionClock(1_000),
            monotonic: FakeMonotonicClock(1_000)
        )

        guard case let .success(session, revision) =
            controller.start(expectedRevision: 0) else {
            return XCTFail("Expected start success")
        }

        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.timerState.timerStatus, .idle)
        XCTAssertEqual(revision, 1)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .idle)
    }

    func testStartExpiredTimerCommitLeavesSessionAndEngineUnchanged() throws {
        let controller = try makeController(
            routine: timerRoutine(),
            wall: FakeExecutionClock(1_000),
            monotonic: SequenceMonotonicClock([1_000, 31_000])
        )
        let beforeEngine = controller.timerEngineForTesting().debugState()

        guard case let .failure(error, revision) =
            controller.start(expectedRevision: 0) else {
            return XCTFail("Expected expired timer commit failure")
        }

        XCTAssertEqual(error, .invalidTimestamp)
        XCTAssertEqual(revision, 0)
        XCTAssertEqual(controller.snapshot().revision, 0)
        XCTAssertEqual(controller.snapshot().session.status, .ready)
        XCTAssertNil(controller.snapshot().session.startedAt)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState(), beforeEngine)
    }

    func testStartCurrentTimerRejectsAutoStartedTimer() throws {
        let controller = try makeController(
            routine: timerRoutine(),
            wall: FakeExecutionClock(1_000),
            monotonic: FakeMonotonicClock(1_000)
        )
        _ = controller.start(expectedRevision: 0)
        let before = controller.timerEngineForTesting().debugState()

        guard case let .failure(error, revision) =
            controller.startCurrentTimer(expectedRevision: 1) else {
            return XCTFail("Expected duplicate timer rejection")
        }
        XCTAssertEqual(error, .invalidTimerState)
        XCTAssertEqual(revision, 1)
        XCTAssertEqual(controller.snapshot().revision, 1)
        XCTAssertEqual(controller.timerEngineForTesting().debugState(), before)

        guard case let .failure(staleError, _) =
            controller.startCurrentTimer(expectedRevision: 0) else {
            return XCTFail("Expected stale rejection")
        }
        XCTAssertEqual(staleError, .staleOperation)
    }

    func testStartCurrentTimerRejectsCountStepAndNonRunningSession() throws {
        let count = try makeController(
            routine: countRoutine(),
            wall: FakeExecutionClock(1_000),
            monotonic: FakeMonotonicClock(1_000)
        )
        _ = count.start(expectedRevision: 0)
        guard case let .failure(countError, _) =
            count.startCurrentTimer(expectedRevision: 1) else {
            return XCTFail("Expected count rejection")
        }
        XCTAssertEqual(countError, .invalidTimerState)

        let ready = try makeController(
            routine: timerRoutine(),
            wall: FakeExecutionClock(1_000),
            monotonic: FakeMonotonicClock(1_000)
        )
        guard case let .failure(readyError, _) =
            ready.startCurrentTimer(expectedRevision: 0) else {
            return XCTFail("Expected non-running rejection")
        }
        XCTAssertEqual(readyError, .sessionNotRunning)
    }

    // MARK: - COUNT -> TIMER

    func testIncrementCountToTimerStartsNextTimerWithOneRevision() throws {
        let controller = try makeController(
            routine: countThenTimerRoutine(),
            wall: FakeExecutionClock(1_000),
            monotonic: FakeMonotonicClock(1_000)
        )
        _ = controller.start(expectedRevision: 0)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .idle)

        guard case let .roundCompleted(session, revision) =
            controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected round completion")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.progress.currentStepID, "timer")
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 31_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(controller.snapshot().revision, 2)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(engine.deadlineMonotonicMs, 31_000)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
        XCTAssertEqual(controller.currentTimerStepKey(), controller.activeTimerStepKey)
    }

    func testConfirmCompleteToTimerStartsNextTimerWithOneRevision() throws {
        let controller = try makeController(
            routine: Routine(
                id: "routine-complete-timer",
                name: "Complete then timer",
                repeatCount: 1,
                steps: [
                    countStep("count", "Pushups", 3, mode: .complete),
                    timerStep("timer", "Plank", 30_000)
                ],
                createdAt: 1,
                updatedAt: 1
            ),
            wall: FakeExecutionClock(1_000),
            monotonic: FakeMonotonicClock(1_000)
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .completed(session, revision) =
            controller.confirmComplete(expectedRevision: 1) else {
            return XCTFail("Expected confirm completion")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.progress.currentStepID, "timer")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 31_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
    }

    // MARK: - Timer transitions

    func testPollBeforeExpiryIsNoOp() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let before = controller.snapshot()
        let beforeEngine = controller.timerEngineForTesting().debugState()

        monotonic.current = 15_000
        guard case let .success(session, revision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: controller.activeTimerRunId!) else {
            return XCTFail("Expected no-change poll success")
        }

        XCTAssertEqual(revision, before.revision)
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.timerState, before.session.timerState)
        XCTAssertEqual(session.records, before.session.records)
        XCTAssertEqual(controller.snapshot().revision, before.revision)
        XCTAssertEqual(controller.timerEngineForTesting().debugState(), beforeEngine)
        XCTAssertEqual(controller.activeTimerRunId, beforeEngine.runId)
    }

    func testTimerExpiryPollCompletesAndStartsNextTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let oldRunId = controller.activeTimerRunId

        monotonic.current = 31_000
        wall.current = 32_000

        guard case let .success(session, revision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: oldRunId!) else {
            return XCTFail("Expected expiry poll success")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .completed)
        XCTAssertEqual(session.records[0].stepID, "timer-1")
        XCTAssertEqual(session.progress.currentStepID, "timer-2")
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 61_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(engine.deadlineMonotonicMs, 61_000)
        XCTAssertNotEqual(controller.activeTimerRunId, oldRunId)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
    }

    func testTimerExpiryPollToCountClearsTimerStateAndReference() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: Routine(
                id: "routine-timer-count",
                name: "Timer then count",
                repeatCount: 1,
                steps: [
                    timerStep("timer", "Plank", 30_000),
                    countStep("count", "Pushups", 2)
                ],
                createdAt: 1,
                updatedAt: 1
            ),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let countRun = controller.activeTimerRunId

        monotonic.current = 31_000
        wall.current = 32_000

        guard case let .success(session, revision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: countRun!) else {
            return XCTFail("Expected expiry poll success")
        }

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.progress.currentStepID, "count")
        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(
            session.timerState,
            TimerState(
                totalDurationMs: 0,
                remainingDurationMs: 0,
                deadline: nil,
                timerStatus: .idle
            )
        )
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertNil(controller.activeTimerStepKey)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .completed)
        XCTAssertEqual(revision, 2)

        guard case let .success(noOpSession, noOpRevision) =
            controller.pollTimer(expectedRevision: 2, expectedRunId: countRun!) else {
            return XCTFail("Expected no-op poll on non-timer step")
        }
        XCTAssertEqual(noOpRevision, 2)
        XCTAssertEqual(noOpSession.progress.currentStepID, "count")
        XCTAssertNil(controller.activeTimerRunId)
    }

    func testFinalTimerExpiryPollCompletesSession() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let finalRun = controller.activeTimerRunId

        monotonic.current = 31_000
        wall.current = 31_000
        guard case let .success(session, revision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: finalRun!) else {
            return XCTFail("Expected final timer poll success")
        }

        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.completedCount, 1)
        XCTAssertEqual(session.completedAt, 31_000)
        XCTAssertEqual(session.timerState.timerStatus, .completed)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .completed)
        XCTAssertEqual(revision, 2)

        guard case let .success(repeated, repeatedRevision) =
            controller.pollTimer(expectedRevision: 2, expectedRunId: finalRun!) else {
            return XCTFail("Expected idempotent terminal poll success")
        }
        XCTAssertEqual(repeatedRevision, 2)
        XCTAssertEqual(repeated.status, .completed)
        XCTAssertEqual(repeated.records, session.records)
        XCTAssertNil(controller.activeTimerRunId)
    }

    func testDuplicatePollAfterExpiryDoesNotCommitAgain() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let firstRun = controller.activeTimerRunId

        monotonic.current = 31_000
        wall.current = 32_000
        guard case let .success(firstSession, firstRevision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: firstRun!) else {
            return XCTFail("Expected first expiry poll success")
        }
        let runAfterFirstPoll = controller.activeTimerRunId
        let engineAfterFirstPoll = controller.timerEngineForTesting().debugState()

        guard case let .failure(staleError, staleRevision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: firstRun!) else {
            return XCTFail("Expected stale duplicate poll")
        }
        XCTAssertEqual(staleError, .staleOperation)
        XCTAssertEqual(staleRevision, firstRevision)

        guard case let .success(secondSession, secondRevision) =
            controller.pollTimer(expectedRevision: firstRevision, expectedRunId: controller.activeTimerRunId!) else {
            return XCTFail("Expected no-change poll success")
        }

        XCTAssertEqual(secondRevision, firstRevision)
        XCTAssertEqual(secondSession.records.count, firstSession.records.count)
        XCTAssertEqual(secondSession.records.count, 1)
        XCTAssertEqual(controller.snapshot().revision, firstRevision)
        XCTAssertEqual(controller.activeTimerRunId, runAfterFirstPoll)
        XCTAssertEqual(
            controller.timerEngineForTesting().debugState(),
            engineAfterFirstPoll
        )
    }

    func testPauseAndResumeTimerKeepActiveRunAndRemainingDuration() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let runId = controller.activeTimerRunId

        monotonic.current = 6_000
        wall.current = 6_000
        guard case let .success(paused, pauseRevision) =
            controller.pause(expectedRevision: 1) else {
            return XCTFail("Expected pause success")
        }
        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(paused.timerState.timerStatus, .paused)
        XCTAssertEqual(paused.timerState.remainingDurationMs, 25_000)
        XCTAssertNil(paused.timerState.deadline)
        XCTAssertEqual(pauseRevision, 2)
        XCTAssertEqual(controller.activeTimerRunId, runId)
        let pausedEngine = controller.timerEngineForTesting().debugState()
        XCTAssertEqual(pausedEngine.status, .paused)
        XCTAssertEqual(pausedEngine.remainingDurationMs, 25_000)
        XCTAssertEqual(pausedEngine.runId, runId)

        monotonic.current = 9_000
        wall.current = 9_000
        guard case let .success(resumed, resumeRevision) =
            controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected resume success")
        }
        let resumedEngine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(resumed.status, .running)
        XCTAssertEqual(resumed.timerState.timerStatus, .running)
        XCTAssertEqual(resumed.timerState.remainingDurationMs, 25_000)
        XCTAssertEqual(resumed.timerState.deadline, 34_000)
        XCTAssertEqual(resumeRevision, 3)
        XCTAssertEqual(resumedEngine.status, .running)
        XCTAssertEqual(resumedEngine.deadlineMonotonicMs, 34_000)
        XCTAssertEqual(controller.activeTimerRunId, runId)
        XCTAssertEqual(controller.activeTimerStepKey, controller.currentTimerStepKey())
    }

    func testPauseAtExpiryCompletesAndStartsNextTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let oldRunId = controller.activeTimerRunId

        monotonic.current = 31_000
        wall.current = 31_000
        guard case let .success(session, revision) =
            controller.pause(expectedRevision: 1) else {
            return XCTFail("Expected expiry pause success")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.progress.currentStepID, "timer-2")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 61_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(engine.deadlineMonotonicMs, 61_000)
        XCTAssertNotEqual(controller.activeTimerRunId, oldRunId)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
    }

    func testResumeAtExpiryStartsFallbackNextTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = SequenceMonotonicClock([
            1_000, 1_000,
            6_000, 6_000,
            7_000, 7_000,
            33_000
        ])
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 6_000
        _ = controller.pause(expectedRevision: 1)
        let oldRunId = controller.activeTimerRunId

        wall.current = 7_000
        guard case let .success(session, revision) =
            controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected expiry resume success")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.status, .running)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.progress.currentStepID, "timer-2")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 37_000)
        XCTAssertEqual(revision, 3)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(engine.deadlineMonotonicMs, 37_000)
        XCTAssertNotEqual(controller.activeTimerRunId, oldRunId)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
        XCTAssertEqual(controller.currentTimerStepKey(), controller.activeTimerStepKey)
    }

    func testSkipTimerClearsReferenceAndStartsNextTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        let oldRunId = controller.activeTimerRunId

        guard case let .skipped(session, revision) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected timer skip success")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .skipped)
        XCTAssertEqual(session.records[0].stepID, "timer-1")
        XCTAssertEqual(session.progress.currentStepID, "timer-2")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 31_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(engine.deadlineMonotonicMs, 31_000)
        XCTAssertNotEqual(controller.activeTimerRunId, oldRunId)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
    }

    func testSkipAtExpiryWinsAndCompletesTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)

        monotonic.current = 31_000
        wall.current = 31_000
        guard case let .completed(session, revision) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected expiry completion instead of skip")
        }
        let engine = controller.timerEngineForTesting().debugState()

        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .completed)
        XCTAssertEqual(session.records[0].stepID, "timer-1")
        XCTAssertEqual(session.progress.currentStepID, "timer-2")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(session.timerState.deadline, 61_000)
        XCTAssertEqual(revision, 2)
        XCTAssertEqual(engine.status, .running)
        XCTAssertEqual(controller.activeTimerRunId, engine.runId)
    }

    func testCancelRunningTimerCommitsCancelledTimerState() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)

        monotonic.current = 6_000
        wall.current = 6_000
        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected cancel success")
        }

        XCTAssertEqual(session.status, .cancelled)
        XCTAssertEqual(session.timerState.timerStatus, .cancelled)
        XCTAssertEqual(session.timerState.remainingDurationMs, 25_000)
        XCTAssertNil(session.timerState.deadline)
        XCTAssertNil(session.currentRoundStartedAt)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .cancelled)
        XCTAssertEqual(revision, 2)
    }

    func testCancelAfterTimerExpiryCompletesInsteadOfCancelling() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)

        monotonic.current = 31_000
        wall.current = 31_000
        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected cancel-after-expiry success")
        }

        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.completedCount, 1)
        XCTAssertEqual(session.timerState.timerStatus, .completed)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .completed)
        XCTAssertEqual(revision, 2)
    }

    func testCancelPausedTimerCommitsCancelledTimerState() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: timerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        monotonic.current = 6_000
        wall.current = 6_000
        _ = controller.pause(expectedRevision: 1)

        monotonic.current = 7_000
        wall.current = 7_000
        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 2) else {
            return XCTFail("Expected paused timer cancel success")
        }

        XCTAssertEqual(session.status, .cancelled)
        XCTAssertEqual(session.timerState.timerStatus, .cancelled)
        XCTAssertEqual(session.timerState.remainingDurationMs, 25_000)
        XCTAssertNil(session.timerState.deadline)
        XCTAssertEqual(session.cancelledAt, 7_000)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .cancelled)
        XCTAssertEqual(revision, 3)
    }

    func testCancelAfterExpiryWithNextTimerCancelsAdvancedSession() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: twoTimerRoutine(),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)

        monotonic.current = 31_000
        wall.current = 31_000
        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected cancel-after-expiry success")
        }

        XCTAssertEqual(session.status, .cancelled)
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records[0].outcome, .completed)
        XCTAssertEqual(session.progress.currentStepID, "timer-2")
        XCTAssertEqual(session.timerState.timerStatus, .idle)
        XCTAssertEqual(session.cancelledAt, 31_000)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .completed)
        XCTAssertEqual(revision, 2)
    }

    func testNextTimerPrepareFailureLeavesSessionEngineAndReferenceUnchanged() throws {
        let wall = FakeExecutionClock(1_000)
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try makeController(
            routine: Routine(
                id: "routine-overflow-timer",
                name: "Overflow next timer",
                repeatCount: 1,
                steps: [
                    timerStep("timer-1", "First", 30_000),
                    timerStep(
                        "timer-2",
                        "Overflow",
                        9_223_372_036_854_775_000
                    )
                ],
                createdAt: 1,
                updatedAt: 1
            ),
            wall: wall,
            monotonic: monotonic
        )
        _ = controller.start(expectedRevision: 0)
        monotonic.current = 31_000
        wall.current = 32_000

        let before = controller.snapshot()
        let beforeReference = controller.activeTimerReferenceForTesting()
        let beforeEngine = controller.timerEngineForTesting().debugState()

        guard case let .failure(error, revision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: controller.activeTimerRunId!) else {
            return XCTFail("Expected next timer prepare failure")
        }

        XCTAssertEqual(error, .invalidSessionState)
        XCTAssertEqual(revision, before.revision)
        XCTAssertEqual(controller.snapshot().revision, before.revision)
        XCTAssertEqual(controller.snapshot().session.status, before.session.status)
        XCTAssertEqual(controller.snapshot().session.progress, before.session.progress)
        XCTAssertEqual(controller.snapshot().session.timerState, before.session.timerState)
        XCTAssertEqual(controller.snapshot().session.records, before.session.records)
        XCTAssertEqual(controller.activeTimerReferenceForTesting(), beforeReference)
        XCTAssertEqual(controller.timerEngineForTesting().debugState(), beforeEngine)
    }

    // MARK: - Helpers

    private func makeController(
        routine: Routine,
        wall: FakeExecutionClock,
        monotonic: MonotonicClock
    ) throws -> ExecutionController {
        try ExecutionController(
            clock: wall,
            initialState: ExecutionSession.create(
                routine: routine,
                sessionID: "session-1"
            ),
            initialRevision: 0,
            monotonicClock: monotonic
        )
    }

    private func timerRoutine() -> Routine {
        Routine(
            id: "routine-1",
            name: "Timer",
            repeatCount: 1,
            steps: [timerStep("timer", "Plank", 30_000)],
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func twoTimerRoutine() -> Routine {
        Routine(
            id: "routine-two",
            name: "Two timers",
            repeatCount: 1,
            steps: [
                timerStep("timer-1", "First", 30_000),
                timerStep("timer-2", "Second", 30_000)
            ],
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func countThenTimerRoutine() -> Routine {
        Routine(
            id: "routine-count-timer",
            name: "Count then timer",
            repeatCount: 1,
            steps: [
                countStep("count", "Pushups", 1),
                timerStep("timer", "Plank", 30_000)
            ],
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func countRoutine() -> Routine {
        Routine(
            id: "routine-count",
            name: "Count",
            repeatCount: 1,
            steps: [countStep("count", "Pushups", 2)],
            createdAt: 1,
            updatedAt: 1
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

    private func countStep(
        _ id: String,
        _ name: String,
        _ target: Int,
        mode: CountMode = .increment
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: target,
            countMode: mode
        )
    }

    private final class FakeExecutionClock: ExecutionClock {
        var current: Int64
        init(_ current: Int64) { self.current = current }
        var now: Int64 { current }
    }

    private final class FakeMonotonicClock: MonotonicClock {
        var current: Int64
        init(_ current: Int64) { self.current = current }
        var nowMs: Int64 { current }
    }

    private final class SequenceMonotonicClock: MonotonicClock {
        private let values: [Int64]
        private var index = 0
        init(_ values: [Int64]) { self.values = values }
        var nowMs: Int64 {
            let value = values[min(index, values.count - 1)]
            index += 1
            return value
        }
    }
}
