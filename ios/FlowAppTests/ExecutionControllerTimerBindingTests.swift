import XCTest
@testable import Flow

@MainActor
final class ExecutionControllerTimerBindingTests: XCTestCase {
    func testEachControllerOwnsAnIndependentEngine() throws {
        let first = try timerController()
        let second = try timerController()
        let firstEngine = first.timerEngineForTesting()
        let secondEngine = second.timerEngineForTesting()
        _ = firstEngine.start(totalDurationMs: 1_000)

        guard case let .success(firstSnapshot) = firstEngine.snapshot(),
              case let .success(secondSnapshot) = secondEngine.snapshot() else {
            return XCTFail("Expected snapshots")
        }
        XCTAssertEqual(firstSnapshot.status, .running)
        XCTAssertEqual(secondSnapshot.status, .idle)
    }

    func testControllerEngineUsesInjectedMonotonicClock() throws {
        let monotonic = FakeMonotonicClock(1_000)
        let controller = try timerController(monotonic: monotonic)
        let engine = controller.timerEngineForTesting()
        _ = engine.start(totalDurationMs: 1_000)
        monotonic.current = 1_300

        guard case let .success(snapshot) = engine.snapshot() else {
            return XCTFail("Expected snapshot")
        }
        XCTAssertEqual(snapshot.deadlineMonotonicMs, 2_000)
        XCTAssertEqual(snapshot.remainingDurationMs, 700)
        XCTAssertEqual(snapshot.status, .running)
    }

    func testActiveReferenceStartsEmpty() throws {
        let controller = try timerController()
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertNil(controller.activeTimerStepKey)
        XCTAssertNil(controller.activeTimerReferenceForTesting())
    }

    func testActiveReferenceInstallReplaceAndClearAreAtomic() throws {
        let controller = try timerController()
        _ = controller.start(expectedRevision: 0)
        let key = try XCTUnwrap(controller.currentTimerStepKey())
        let first = ActiveTimerReference(runId: TimerRunId(value: "run-1"), stepKey: key)
        controller.installActiveTimerForTesting(runId: first.runId, stepKey: first.stepKey)

        XCTAssertEqual(controller.activeTimerReferenceForTesting(), first)
        XCTAssertEqual(controller.activeTimerRunId, first.runId)
        XCTAssertEqual(controller.activeTimerStepKey, first.stepKey)

        let second = ActiveTimerReference(runId: TimerRunId(value: "run-2"), stepKey: key)
        controller.installActiveTimerForTesting(runId: second.runId, stepKey: second.stepKey)
        XCTAssertEqual(controller.activeTimerReferenceForTesting(), second)
        XCTAssertNotEqual(first.runId, controller.activeTimerRunId)

        controller.clearActiveTimerForTesting()
        XCTAssertNil(controller.activeTimerReferenceForTesting())
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertNil(controller.activeTimerStepKey)
    }

    func testActiveTimerStepKeyRequiresCurrentTimerStep() throws {
        let controller = try countController()
        _ = controller.start(expectedRevision: 0)
        XCTAssertNil(controller.currentTimerStepKey())
    }

    func testActiveTimerStepKeyDistinguishesAllIdentityFields() {
        let base = ActiveTimerStepKey(
            sessionID: "session-1",
            routineIteration: 1,
            stepID: "timer",
            stepIteration: 1
        )
        XCTAssertEqual(base, ActiveTimerStepKey(
            sessionID: "session-1",
            routineIteration: 1,
            stepID: "timer",
            stepIteration: 1
        ))
        XCTAssertNotEqual(base, ActiveTimerStepKey(
            sessionID: "session-2",
            routineIteration: 1,
            stepID: "timer",
            stepIteration: 1
        ))
        XCTAssertNotEqual(base, ActiveTimerStepKey(
            sessionID: "session-1",
            routineIteration: 2,
            stepID: "timer",
            stepIteration: 1
        ))
        XCTAssertNotEqual(base, ActiveTimerStepKey(
            sessionID: "session-1",
            routineIteration: 1,
            stepID: "timer-2",
            stepIteration: 1
        ))
        XCTAssertNotEqual(base, ActiveTimerStepKey(
            sessionID: "session-1",
            routineIteration: 1,
            stepID: "timer",
            stepIteration: 2
        ))
    }

    func testActiveIdentityAcceptsMatchingFields() throws {
        let controller = try timerController()
        _ = controller.start(expectedRevision: 0)
        let key = try XCTUnwrap(controller.currentTimerStepKey())
        let runId = TimerRunId(value: "run-1")
        controller.installActiveTimerForTesting(runId: runId, stepKey: key)

        XCTAssertTrue(controller.isActiveTimerIdentityValid(
            sessionID: key.sessionID,
            routineIteration: key.routineIteration,
            stepID: key.stepID,
            stepIteration: key.stepIteration,
            timerRunId: runId
        ))
    }

    func testActiveIdentityRejectsEveryMismatchedField() throws {
        let controller = try timerController()
        _ = controller.start(expectedRevision: 0)
        let key = try XCTUnwrap(controller.currentTimerStepKey())
        let runId = TimerRunId(value: "run-1")
        controller.installActiveTimerForTesting(runId: runId, stepKey: key)

        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: "other-session",
            routineIteration: key.routineIteration,
            stepID: key.stepID,
            stepIteration: key.stepIteration,
            timerRunId: runId
        ))
        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: key.sessionID,
            routineIteration: key.routineIteration + 1,
            stepID: key.stepID,
            stepIteration: key.stepIteration,
            timerRunId: runId
        ))
        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: key.sessionID,
            routineIteration: key.routineIteration,
            stepID: "other-step",
            stepIteration: key.stepIteration,
            timerRunId: runId
        ))
        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: key.sessionID,
            routineIteration: key.routineIteration,
            stepID: key.stepID,
            stepIteration: key.stepIteration + 1,
            timerRunId: runId
        ))
        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: key.sessionID,
            routineIteration: key.routineIteration,
            stepID: key.stepID,
            stepIteration: key.stepIteration,
            timerRunId: TimerRunId(value: "other-run")
        ))
    }

    func testActiveIdentityRejectsTerminalAndNonTimerSessions() throws {
        let terminal = try timerController()
        _ = terminal.start(expectedRevision: 0)
        let key = try XCTUnwrap(terminal.currentTimerStepKey())
        let runId = try XCTUnwrap(terminal.activeTimerRunId)
        _ = terminal.cancel(expectedRevision: 1)
        terminal.installActiveTimerForTesting(runId: runId, stepKey: key)
        XCTAssertFalse(terminal.isActiveTimerIdentityValid(
            sessionID: key.sessionID,
            routineIteration: key.routineIteration,
            stepID: key.stepID,
            stepIteration: key.stepIteration,
            timerRunId: runId
        ))

        let count = try countController()
        _ = count.start(expectedRevision: 0)
        let countKey = ActiveTimerStepKey(
            sessionID: count.snapshot().session.sessionID,
            routineIteration: 1,
            stepID: "count",
            stepIteration: 1
        )
        count.installActiveTimerForTesting(
            runId: TimerRunId(value: "run-2"),
            stepKey: countKey
        )
        XCTAssertFalse(count.isActiveTimerIdentityValid(
            sessionID: countKey.sessionID,
            routineIteration: 1,
            stepID: "count",
            stepIteration: 1,
            timerRunId: TimerRunId(value: "run-2")
        ))
    }

    func testOldStepAndRunCannotMatchNewTimerStep() throws {
        let controller = try twoTimerController()
        _ = controller.start(expectedRevision: 0)
        let oldKey = try XCTUnwrap(controller.currentTimerStepKey())
        let oldRun = TimerRunId(value: "run-old")
        controller.installActiveTimerForTesting(runId: oldRun, stepKey: oldKey)
        guard case let .failure(error, _) = controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected stale reference rejection")
        }
        XCTAssertEqual(error, .invalidTimerState)
        let engineRun = try XCTUnwrap(
            controller.timerEngineForTesting().debugState().runId
        )
        controller.installActiveTimerForTesting(runId: engineRun, stepKey: oldKey)
        _ = controller.skipCurrentRound(expectedRevision: 1)

        let newKey = try XCTUnwrap(controller.currentTimerStepKey())
        XCTAssertNotEqual(oldKey, newKey)
        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: oldKey.sessionID,
            routineIteration: oldKey.routineIteration,
            stepID: oldKey.stepID,
            stepIteration: oldKey.stepIteration,
            timerRunId: oldRun
        ))

        let newRun = TimerRunId(value: "run-new")
        controller.installActiveTimerForTesting(runId: newRun, stepKey: newKey)
        XCTAssertFalse(controller.isActiveTimerIdentityValid(
            sessionID: oldKey.sessionID,
            routineIteration: oldKey.routineIteration,
            stepID: oldKey.stepID,
            stepIteration: oldKey.stepIteration,
            timerRunId: oldRun
        ))
        XCTAssertTrue(controller.isActiveTimerIdentityValid(
            sessionID: newKey.sessionID,
            routineIteration: newKey.routineIteration,
            stepID: newKey.stepID,
            stepIteration: newKey.stepIteration,
            timerRunId: newRun
        ))
    }

    func testControllerStartAutoStartsEngineForTimerStep() throws {
        let controller = try timerController()
        _ = controller.start(expectedRevision: 0)

        guard case let .success(snapshot) = controller.timerEngineForTesting().snapshot() else {
            return XCTFail("Expected snapshot")
        }
        XCTAssertEqual(snapshot.status, .running)
        XCTAssertEqual(controller.snapshot().session.timerState.timerStatus, .running)
        XCTAssertEqual(controller.activeTimerRunId, snapshot.runId)
    }

    func testCountCompletionStartsNextTimerInSameRevision() throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "c", name: "Count", type: .count, target: 1, countMode: .increment),
            RoutineStep(id: "t", name: "Timer", type: .timer, durationMs: 30_000, category: .normal)
        ])
        _ = controller.start(expectedRevision: 0)

        guard case let .roundCompleted(session, revision) = controller.incrementCount(expectedRevision: 1) else {
            return XCTFail("Expected round completion")
        }
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.routineSnapshot.steps[session.progress.currentStepIndex].type, .timer)
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(revision, 2)
        XCTAssertNotNil(controller.activeTimerRunId)
    }

    func testTimerExpiryAdvancesToNextTimerWithOneRevisionAndNewRun() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [
                RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal),
                RoutineStep(id: "b", name: "B", type: .timer, durationMs: 20_000, category: .normal)
            ],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        let firstRun = controller.activeTimerRunId
        wall.current = 11_000
        mono.current = 11_000

        guard case let .success(session, revision) = controller.pollTimer(expectedRevision: 1, expectedRunId: controller.activeTimerRunId!) else {
            return XCTFail("Expected poll success")
        }
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.routineSnapshot.steps[session.progress.currentStepIndex].id, "b")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(revision, 2)
        XCTAssertNotNil(controller.activeTimerRunId)
        XCTAssertNotEqual(firstRun, controller.activeTimerRunId)
    }

    func testTimerExpiryAdvancesToCountAndClearsReference() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [
                RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal),
                RoutineStep(id: "c", name: "C", type: .count, target: 2, countMode: .increment)
            ],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 11_000
        mono.current = 11_000

        guard case let .success(session, revision) = controller.pollTimer(expectedRevision: 1, expectedRunId: controller.activeTimerRunId!) else {
            return XCTFail("Expected poll success")
        }
        XCTAssertEqual(session.routineSnapshot.steps[session.progress.currentStepIndex].type, .count)
        XCTAssertEqual(session.timerState.timerStatus, .idle)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(revision, 2)
    }

    func testTimerSkipAdvancesAndStartsNextTimer() throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal),
            RoutineStep(id: "b", name: "B", type: .timer, durationMs: 20_000, category: .normal)
        ])
        _ = controller.start(expectedRevision: 0)
        let firstRun = controller.activeTimerRunId

        guard case let .skipped(session, revision) = controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skipped")
        }
        XCTAssertEqual(session.records.first?.outcome, .skipped)
        XCTAssertEqual(session.routineSnapshot.steps[session.progress.currentStepIndex].id, "b")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(revision, 2)
        XCTAssertNotEqual(firstRun, controller.activeTimerRunId)
    }

    func testPauseAndResumeKeepPausedRemainingAndDoNotRestartRun() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        let firstRun = controller.activeTimerRunId
        wall.current = 3_000
        mono.current = 3_000

        guard case let .success(paused, pausedRevision) = controller.pause(expectedRevision: 1) else {
            return XCTFail("Expected pause")
        }
        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(paused.timerState.timerStatus, .paused)
        XCTAssertEqual(paused.timerState.remainingDurationMs, 8_000)
        XCTAssertEqual(pausedRevision, 2)
        XCTAssertEqual(controller.activeTimerRunId, firstRun)

        wall.current = 7_000
        mono.current = 7_000
        guard case let .success(resumed, resumedRevision) = controller.resume(expectedRevision: pausedRevision) else {
            return XCTFail("Expected resume")
        }
        XCTAssertEqual(resumed.status, .running)
        XCTAssertEqual(resumed.timerState.deadline, 15_000)
        XCTAssertEqual(resumedRevision, 3)
        XCTAssertEqual(controller.activeTimerRunId, firstRun)
    }

    func testDuplicatePollIsIdempotent() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 11_000
        mono.current = 11_000
        let firstRun = controller.activeTimerRunId!
        guard case let .success(firstSession, firstRevision) = controller.pollTimer(expectedRevision: 1, expectedRunId: firstRun) else {
            return XCTFail("Expected first poll")
        }
        guard case let .success(secondSession, secondRevision) = controller.pollTimer(expectedRevision: firstRevision, expectedRunId: firstRun) else {
            return XCTFail("Expected idempotent poll")
        }
        XCTAssertEqual(firstSession.records, secondSession.records)
        XCTAssertEqual(firstSession.progress, secondSession.progress)
        XCTAssertEqual(firstSession.timerState.timerStatus, secondSession.timerState.timerStatus)
        XCTAssertEqual(firstRevision, secondRevision)
        XCTAssertNil(controller.activeTimerRunId)
    }

    func testResumeRetryReusesFirstAnchor() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 3_000
        mono.current = 3_000
        _ = controller.pause(expectedRevision: 1)

        wall.current = 500
        mono.current = 5_000
        guard case let .failure(firstError, firstRevision) =
            controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected first resume failure")
        }
        XCTAssertEqual(firstError, .invalidTimestamp)
        XCTAssertEqual(firstRevision, 2)
        XCTAssertEqual(controller.snapshot().session.status, .paused)

        wall.current = 7_000
        mono.current = 7_000
        guard case let .success(session, revision) =
            controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected second resume success")
        }
        XCTAssertEqual(session.timerState.deadline, 13_000)
        XCTAssertEqual(revision, 3)
    }

    func testCancelAfterFailedResumeBeforeAnchorStillCancels() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 3_000
        mono.current = 3_000
        _ = controller.pause(expectedRevision: 1)

        wall.current = 500
        mono.current = 5_000
        guard case .failure = controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected failed resume")
        }

        wall.current = 6_000
        mono.current = 6_000
        guard case let .success(session, revision) =
            controller.cancel(expectedRevision: 2) else {
            return XCTFail("Expected cancel before anchor")
        }
        XCTAssertEqual(session.status, .cancelled)
        XCTAssertEqual(revision, 3)
        XCTAssertNil(controller.activeTimerRunId)
    }

    func testRevisionOverflowFailsBeforeEngineStart() throws {
        let session = try ExecutionSession.create(
            routine: Routine(
                id: "max",
                name: "Max",
                repeatCount: 1,
                steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
                createdAt: 1,
                updatedAt: 1
            ),
            sessionID: "max-session"
        )
        let controller = try ExecutionController(
            clock: FakeExecutionClock(1_000),
            initialState: session,
            initialRevision: Int64.max,
            monotonicClock: FakeMonotonicClock(1_000)
        )

        guard case let .failure(error, revision) = controller.start(expectedRevision: Int64.max) else {
            return XCTFail("Expected revision overflow")
        }
        XCTAssertEqual(error, .revisionOverflow)
        XCTAssertEqual(revision, Int64.max)
        XCTAssertEqual(controller.snapshot().session.status, .ready)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(controller.timerEngineForTesting().debugState().status, .idle)
    }

    func testCancelReadyTimerStepStillCancelsSession() throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)
        ])
        guard case let .success(session, revision) = controller.cancel(expectedRevision: 0) else {
            return XCTFail("Expected ready cancel")
        }
        XCTAssertEqual(session.status, .cancelled)
        XCTAssertEqual(session.cancelledAt, 1_000)
        XCTAssertNil(session.completedAt)
        XCTAssertNil(controller.activeTimerRunId)
        XCTAssertEqual(revision, 1)
    }

    func testDuplicateIncrementAtSameRevisionHasSingleWinner() async throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "c", name: "C", type: .count, target: 3, countMode: .increment)
        ])
        _ = controller.start(expectedRevision: 0)
        let wins = await race(
            { if case .incremented = controller.incrementCount(expectedRevision: 1) { return true }; return false },
            { if case .incremented = controller.incrementCount(expectedRevision: 1) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.progress.currentCount, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 0)
        XCTAssertEqual(controller.snapshot().revision, 2)
        try assertInvariants(controller)
    }

    func testDuplicateConfirmAtSameRevisionHasSingleWinner() async throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "c", name: "C", type: .count, target: 2, countMode: .complete)
        ])
        _ = controller.start(expectedRevision: 0)
        let wins = await race(
            { if case .completed = controller.confirmComplete(expectedRevision: 1) { return true }; return false },
            { if case .completed = controller.confirmComplete(expectedRevision: 1) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 1)
        XCTAssertEqual(controller.snapshot().revision, 2)
        try assertInvariants(controller)
    }

    func testDuplicateSkipAtSameRevisionHasSingleWinner() async throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "a", name: "A", type: .count, target: 2, countMode: .increment),
            RoutineStep(id: "b", name: "B", type: .count, target: 2, countMode: .increment)
        ])
        _ = controller.start(expectedRevision: 0)
        let wins = await race(
            { if case .skipped = controller.skipCurrentRound(expectedRevision: 1) { return true }; return false },
            { if case .skipped = controller.skipCurrentRound(expectedRevision: 1) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 1)
        XCTAssertEqual(controller.snapshot().session.progress.currentStepID, "b")
        XCTAssertEqual(controller.snapshot().revision, 2)
        try assertInvariants(controller)
    }

    func testDuplicatePauseAtSameRevisionHasSingleWinner() async throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)
        ])
        _ = controller.start(expectedRevision: 0)
        let wins = await race(
            { if case .success = controller.pause(expectedRevision: 1) { return true }; return false },
            { if case .success = controller.pause(expectedRevision: 1) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.status, .paused)
        XCTAssertEqual(controller.snapshot().session.timerState.timerStatus, .paused)
        XCTAssertEqual(controller.snapshot().revision, 2)
        try assertInvariants(controller)
    }

    func testDuplicateCancelAtSameRevisionHasSingleWinner() async throws {
        let controller = try lifecycleController(steps: [
            RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)
        ])
        _ = controller.start(expectedRevision: 0)
        let wins = await race(
            { if case .success = controller.cancel(expectedRevision: 1) { return true }; return false },
            { if case .success = controller.cancel(expectedRevision: 1) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.status, .cancelled)
        XCTAssertEqual(controller.snapshot().revision, 2)
        XCTAssertNil(controller.activeTimerRunId)
        try assertInvariants(controller)
    }

    func testExpiryRacingSkipAndCancelProducesOneCompletionRecord() async throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 11_000
        mono.current = 11_000
        let wins = await race(
            { if case .completed = controller.skipCurrentRound(expectedRevision: 1) { return true }; return false },
            { if case .success = controller.cancel(expectedRevision: 1) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.records.count, 1)
        XCTAssertEqual(controller.snapshot().session.records.first?.outcome, .completed)
        XCTAssertEqual(controller.snapshot().session.status, .completed)
        XCTAssertEqual(controller.snapshot().revision, 2)
        XCTAssertNil(controller.activeTimerRunId)
        try assertInvariants(controller)
    }

    func testStaleRevisionCannotPauseOrCancelNewTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [
                RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal),
                RoutineStep(id: "b", name: "B", type: .timer, durationMs: 20_000, category: .normal)
            ],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        let oldRun = try XCTUnwrap(controller.activeTimerRunId)
        wall.current = 11_000
        mono.current = 11_000
        _ = controller.pollTimer(expectedRevision: 1, expectedRunId: oldRun)
        let before = controller.snapshot()
        let beforeRef = controller.activeTimerReferenceForTesting()
        let beforeEngine = controller.timerEngineForTesting().debugState()

        guard case let .failure(pauseError, _) =
            controller.pause(expectedRevision: 1) else {
            return XCTFail("Expected stale pause")
        }
        XCTAssertEqual(pauseError, .staleOperation)
        guard case let .failure(cancelError, _) =
            controller.cancel(expectedRevision: 1) else {
            return XCTFail("Expected stale cancel")
        }
        XCTAssertEqual(cancelError, .staleOperation)
        XCTAssertEqual(controller.snapshot().session.status, before.session.status)
        XCTAssertEqual(controller.snapshot().session.progress, before.session.progress)
        XCTAssertEqual(controller.snapshot().session.timerState, before.session.timerState)
        XCTAssertEqual(controller.snapshot().session.records, before.session.records)
        XCTAssertEqual(controller.snapshot().revision, before.revision)
        XCTAssertEqual(controller.activeTimerReferenceForTesting(), beforeRef)
        XCTAssertEqual(controller.timerEngineForTesting().debugState(), beforeEngine)
        try assertInvariants(controller)
    }

    func testOldRunPollCannotAdvanceNewTimer() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [
                RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal),
                RoutineStep(id: "b", name: "B", type: .timer, durationMs: 20_000, category: .normal)
            ],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        let oldRun = try XCTUnwrap(controller.activeTimerRunId)
        wall.current = 11_000
        mono.current = 11_000
        guard case let .success(_, firstRevision) =
            controller.pollTimer(expectedRevision: 1, expectedRunId: oldRun) else {
            return XCTFail("Expected first poll")
        }
        let newRun = try XCTUnwrap(controller.activeTimerRunId)
        XCTAssertNotEqual(oldRun, newRun)

        guard case let .success(_, lateRevision) =
            controller.pollTimer(expectedRevision: firstRevision, expectedRunId: oldRun) else {
            return XCTFail("Expected late poll no-op")
        }
        XCTAssertEqual(lateRevision, firstRevision)
        XCTAssertEqual(controller.snapshot().revision, firstRevision)
        XCTAssertEqual(controller.snapshot().session.progress.currentStepID, "b")
        XCTAssertEqual(controller.snapshot().session.timerState.timerStatus, .running)
        XCTAssertEqual(controller.activeTimerRunId, newRun)
        try assertInvariants(controller)
    }

    func testExpiryRacingPauseAndPollCompletesOnlyOnce() async throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 10_000, category: .normal)],
            wall: wall,
            mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        let run = try XCTUnwrap(controller.activeTimerRunId)
        wall.current = 11_000
        mono.current = 11_000

        let wins = await race(
            { if case .success = controller.pause(expectedRevision: 1) { return true }; return false },
            { if case .success = controller.pollTimer(expectedRevision: 1, expectedRunId: run) { return true }; return false }
        )
        XCTAssertEqual(wins.filter { $0 }.count, 1)
        XCTAssertEqual(controller.snapshot().session.status, .completed)
        XCTAssertEqual(controller.snapshot().session.records.count, 1)
        XCTAssertEqual(controller.snapshot().revision, 2)
        try assertInvariants(controller)
    }

    func testSkipChecksExpiryAtEngineCommitTime() throws {
        let clock = CommitCrossingMonotonicClock([1_000, 1_000, 9_000, 9_000, 10_000])
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 9_000, category: .normal)],
            wall: FakeExecutionClock(9_000),
            mono: clock
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .completed(session, revision) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected commit-time expiry completion")
        }
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records.first?.outcome, .completed)
        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(revision, 2)
        XCTAssertNil(controller.activeTimerRunId)
        try assertInvariants(controller)
    }

    func testSkipBeforeDeadlineStillSkippedAtCommit() throws {
        let clock = CommitCrossingMonotonicClock([1_000, 1_000, 9_000, 9_000, 9_999])
        let controller = try lifecycleController(
            steps: [RoutineStep(id: "a", name: "A", type: .timer, durationMs: 9_000, category: .normal)],
            wall: FakeExecutionClock(9_000),
            mono: clock
        )
        _ = controller.start(expectedRevision: 0)

        guard case let .skipped(session, revision) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected skip before deadline")
        }
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records.first?.outcome, .skipped)
        XCTAssertEqual(session.status, .completed)
        XCTAssertEqual(revision, 2)
        try assertInvariants(controller)
    }

    func testSkipCrossingDeadlineStartsNextTimerAsCompleted() throws {
        let clock = CommitCrossingMonotonicClock([1_000, 1_000, 9_000, 9_000, 10_000])
        let controller = try lifecycleController(
            steps: [
                RoutineStep(id: "a", name: "A", type: .timer, durationMs: 9_000, category: .normal),
                RoutineStep(id: "b", name: "B", type: .timer, durationMs: 20_000, category: .normal)
            ],
            wall: FakeExecutionClock(9_000),
            mono: clock
        )
        _ = controller.start(expectedRevision: 0)
        let oldRun = controller.activeTimerRunId

        guard case let .completed(session, revision) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected completed transition")
        }
        XCTAssertEqual(session.records.count, 1)
        XCTAssertEqual(session.records.first?.outcome, .completed)
        XCTAssertEqual(session.progress.currentStepID, "b")
        XCTAssertEqual(session.timerState.timerStatus, .running)
        XCTAssertEqual(revision, 2)
        XCTAssertNotEqual(oldRun, controller.activeTimerRunId)
        try assertInvariants(controller)
    }

    private final class CommitCrossingMonotonicClock: MonotonicClock {
        private let values: [Int64]
        private var index = 0
        init(_ values: [Int64]) { self.values = values }
        var nowMs: Int64 {
            let value = values[min(index, values.count - 1)]
            index += 1
            return value
        }
    }

    func testMixedCountTimerFlowCompletesCanonicalRecords() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "integration", name: "Integration",
                repeatCount: 1,
                steps: [
                    countStep("count-a", target: 2, mode: .increment),
                    timerStep("timer-a", duration: 10_000, category: .normal),
                    countStep("count-b", target: 2, mode: .complete),
                    timerStep("rest-a", duration: 5_000, category: .rest),
                    countStep("count-c", target: 2, mode: .increment)
                ],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 1_100
        _ = controller.incrementCount(expectedRevision: 1)
        wall.current = 1_200
        guard case let .roundCompleted(startedTimer, rev3) =
            controller.incrementCount(expectedRevision: 2) else {
            return XCTFail("Expected timer start")
        }
        XCTAssertEqual(rev3, 3)
        XCTAssertEqual(startedTimer.progress.currentStepID, "timer-a")
        XCTAssertEqual(startedTimer.timerState.timerStatus, .running)

        wall.current = 11_000
        mono.current = 11_000
        guard case let .success(timerDone, rev4) = controller.pollTimer(
            expectedRevision: 3,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected timer completion") }
        XCTAssertEqual(rev4, 4)
        XCTAssertEqual(timerDone.progress.currentStepID, "count-b")
        XCTAssertNil(controller.activeTimerRunId)

        wall.current = 11_200
        guard case let .completed(completeDone, rev5) =
            controller.confirmComplete(expectedRevision: 4) else {
            return XCTFail("Expected complete round")
        }
        XCTAssertEqual(rev5, 5)
        XCTAssertEqual(completeDone.progress.currentStepID, "rest-a")
        XCTAssertEqual(completeDone.timerState.timerStatus, .running)

        wall.current = 16_000
        mono.current = 16_000
        guard case let .success(restDone, rev6) = controller.pollTimer(
            expectedRevision: 5,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected rest completion") }
        XCTAssertEqual(rev6, 6)
        XCTAssertEqual(restDone.progress.currentStepID, "count-c")

        wall.current = 16_100
        _ = controller.incrementCount(expectedRevision: 6)
        wall.current = 16_200
        guard case let .roundCompleted(final, rev8) =
            controller.incrementCount(expectedRevision: 7) else {
            return XCTFail("Expected final count completion")
        }
        XCTAssertEqual(rev8, 8)
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.completedAt, 16_200)
        XCTAssertEqual(final.records.map(\.stepID),
                       ["count-a", "timer-a", "count-b", "rest-a", "count-c"])
        XCTAssertTrue(final.records.allSatisfy { $0.outcome == .completed })
        XCTAssertNil(controller.activeTimerRunId)
        try assertInvariants(controller)
    }

    func testRoutineRepeatRestartsAndCompletesCanonically() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "repeat", name: "Repeat", repeatCount: 2,
                steps: [
                    countStep("count", target: 1, mode: .increment),
                    timerStep("timer", duration: 5_000, category: .normal)
                ],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 1_100
        _ = controller.incrementCount(expectedRevision: 1)
        wall.current = 6_000
        mono.current = 6_000
        guard case let .success(firstRound, _) = controller.pollTimer(
            expectedRevision: 2,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected first timer") }
        XCTAssertEqual(firstRound.progress.routineIteration, 2)
        XCTAssertEqual(firstRound.progress.currentStepID, "count")

        wall.current = 6_100
        _ = controller.incrementCount(expectedRevision: 3)
        wall.current = 11_000
        mono.current = 11_000
        guard case let .success(final, _) = controller.pollTimer(
            expectedRevision: 4,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected final timer") }
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.completedAt, 11_000)
        XCTAssertEqual(final.records.count, 4)
        XCTAssertEqual(final.records.map(\.stepID),
                       ["count", "timer", "count", "timer"])
        XCTAssertEqual(final.records.map(\.routineIteration), [1, 1, 2, 2])
        try assertInvariants(controller)
    }

    func testSkipRepeatedTimerRoundsAndFinalCount() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "skip", name: "Skip", repeatCount: 1,
                steps: [
                    timerStep("timer", duration: 10_000, category: .normal, repeatCount: 2),
                    countStep("count", target: 1, mode: .increment)
                ],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 1_100
        guard case let .skipped(first, rev2) =
            controller.skipCurrentRound(expectedRevision: 1) else {
            return XCTFail("Expected first timer skip")
        }
        XCTAssertEqual(rev2, 2)
        XCTAssertEqual(first.progress.stepIteration, 2)
        XCTAssertEqual(first.records.first?.outcome, .skipped)
        XCTAssertNotNil(controller.activeTimerRunId)

        wall.current = 1_200
        guard case let .skipped(second, rev3) =
            controller.skipCurrentRound(expectedRevision: 2) else {
            return XCTFail("Expected second timer skip")
        }
        XCTAssertEqual(rev3, 3)
        XCTAssertEqual(second.progress.currentStepID, "count")
        XCTAssertNil(controller.activeTimerRunId)

        wall.current = 1_300
        guard case let .roundCompleted(final, _) =
            controller.incrementCount(expectedRevision: 3) else {
            return XCTFail("Expected final count")
        }
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.records.map(\.outcome), [.skipped, .skipped, .completed])
        XCTAssertEqual(final.completedCount, 1)
        XCTAssertEqual(final.skippedCount, 2)
        try assertInvariants(controller)
    }

    func testPauseResumePreservesCountAndTimerRemaining() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "pause", name: "Pause", repeatCount: 1,
                steps: [
                    countStep("count", target: 2, mode: .increment),
                    timerStep("timer", duration: 10_000, category: .normal)
                ],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 1_100
        _ = controller.incrementCount(expectedRevision: 1)
        wall.current = 1_200
        guard case let .success(pausedCount, rev3) = controller.pause(expectedRevision: 2) else {
            return XCTFail("Expected count pause")
        }
        XCTAssertEqual(rev3, 3)
        XCTAssertEqual(pausedCount.status, .paused)
        XCTAssertEqual(pausedCount.progress.currentCount, 1)
        XCTAssertNil(controller.activeTimerRunId)

        wall.current = 1_300
        guard case let .success(resumedCount, _) = controller.resume(expectedRevision: 3) else {
            return XCTFail("Expected count resume")
        }
        XCTAssertEqual(resumedCount.progress.currentCount, 1)

        wall.current = 1_400
        guard case let .roundCompleted(timerStarted, _) =
            controller.incrementCount(expectedRevision: 4) else {
            return XCTFail("Expected timer start")
        }
        XCTAssertEqual(timerStarted.timerState.timerStatus, .running)

        wall.current = 3_000
        mono.current = 3_000
        guard case let .success(pausedTimer, _) = controller.pause(expectedRevision: 5) else {
            return XCTFail("Expected timer pause")
        }
        XCTAssertEqual(pausedTimer.timerState.remainingDurationMs, 8_000)
        XCTAssertEqual(pausedTimer.timerState.timerStatus, .paused)

        wall.current = 6_000
        mono.current = 6_000
        guard case let .success(resumedTimer, _) = controller.resume(expectedRevision: 6) else {
            return XCTFail("Expected timer resume")
        }
        XCTAssertEqual(resumedTimer.timerState.deadline, 14_000)

        wall.current = 14_000
        mono.current = 14_000
        guard case let .success(final, rev8) = controller.pollTimer(
            expectedRevision: 7,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected final timer completion") }
        XCTAssertEqual(rev8, 8)
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.records.count, 2)
        try assertInvariants(controller)
    }

    func testCombinedRoutineRepeatSkipPauseAndMix() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "combined", name: "Combined", repeatCount: 2,
                steps: [
                    RoutineStep(id: "count-inc", name: "count-inc", type: .count,
                                target: 2, countMode: .increment, repeatCount: 2),
                    timerStep("timer", duration: 10_000, category: .normal),
                    countStep("count-complete", target: 2, mode: .complete)
                ],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 1_100
        _ = controller.incrementCount(expectedRevision: 1)
        wall.current = 1_200
        _ = controller.skipCurrentRound(expectedRevision: 2)
        wall.current = 1_300
        _ = controller.incrementCount(expectedRevision: 3)
        wall.current = 1_400
        _ = controller.incrementCount(expectedRevision: 4)
        wall.current = 3_000
        mono.current = 3_000
        _ = controller.pause(expectedRevision: 5)
        wall.current = 5_000
        mono.current = 5_000
        _ = controller.resume(expectedRevision: 6)
        wall.current = 13_000
        mono.current = 13_000
        guard case let .success(firstComplete, rev8) = controller.pollTimer(
            expectedRevision: 7,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected first timer completion") }
        XCTAssertEqual(rev8, 8)
        XCTAssertEqual(firstComplete.progress.currentStepID, "count-complete")
        XCTAssertEqual(firstComplete.progress.routineIteration, 1)

        wall.current = 13_100
        _ = controller.confirmComplete(expectedRevision: 8) // rev 9, routine 2
        wall.current = 13_200
        _ = controller.incrementCount(expectedRevision: 9)
        wall.current = 13_300
        _ = controller.skipCurrentRound(expectedRevision: 10)
        wall.current = 13_400
        _ = controller.incrementCount(expectedRevision: 11)
        wall.current = 13_500
        _ = controller.incrementCount(expectedRevision: 12)
        wall.current = 23_000
        mono.current = 23_000
        guard case let .success(lastCount, rev14) = controller.pollTimer(
            expectedRevision: 13,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected second timer completion") }
        XCTAssertEqual(rev14, 14)
        XCTAssertEqual(lastCount.progress.routineIteration, 2)
        wall.current = 23_100
        guard case let .completed(final, rev15) =
            controller.confirmComplete(expectedRevision: 14) else {
            return XCTFail("Expected combined completion")
        }
        XCTAssertEqual(rev15, 15)
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.completedAt, 23_100)
        XCTAssertEqual(final.records.count, 8)
        XCTAssertEqual(final.records.map(\.stepID), [
            "count-inc", "count-inc", "timer", "count-complete",
            "count-inc", "count-inc", "timer", "count-complete"
        ])
        XCTAssertEqual(final.records.map(\.outcome), [
            .skipped, .completed, .completed, .completed,
            .skipped, .completed, .completed, .completed
        ])
        XCTAssertEqual(final.records.map(\.routineIteration), [1,1,1,1,2,2,2,2])
        XCTAssertNil(controller.activeTimerRunId)
        try assertInvariants(controller)
    }

    func testRepeatedTimerNaturallyExpiresEachRound() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "timer-repeat", name: "TimerRepeat", repeatCount: 1,
                steps: [
                    timerStep("timer", duration: 10_000, category: .normal, repeatCount: 2),
                    countStep("count", target: 1, mode: .increment)
                ],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 11_000
        mono.current = 11_000
        guard case let .success(first, rev2) = controller.pollTimer(
            expectedRevision: 1,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected first timer") }
        XCTAssertEqual(rev2, 2)
        XCTAssertEqual(first.progress.stepIteration, 2)
        XCTAssertEqual(first.timerState.timerStatus, .running)
        XCTAssertNotNil(controller.activeTimerRunId)

        wall.current = 21_000
        mono.current = 21_000
        guard case let .success(second, rev3) = controller.pollTimer(
            expectedRevision: 2,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected second timer") }
        XCTAssertEqual(rev3, 3)
        XCTAssertEqual(second.progress.currentStepID, "count")
        XCTAssertNil(controller.activeTimerRunId)

        wall.current = 21_100
        guard case let .roundCompleted(final, rev4) =
            controller.incrementCount(expectedRevision: 3) else {
            return XCTFail("Expected final count")
        }
        XCTAssertEqual(rev4, 4)
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.completedCount, 3)
        XCTAssertEqual(final.skippedCount, 0)
        XCTAssertEqual(final.records.prefix(2).map(\.stepIteration), [1, 2])
        try assertInvariants(controller)
    }

    func testMultiplePauseResumeCyclesPreserveTime() throws {
        let wall = FakeExecutionClock(1_000)
        let mono = FakeMonotonicClock(1_000)
        let controller = try scenarioController(
            routine: Routine(
                id: "multi-pause", name: "MultiPause", repeatCount: 1,
                steps: [timerStep("timer", duration: 10_000, category: .normal)],
                createdAt: 1, updatedAt: 1
            ),
            wall: wall, mono: mono
        )
        _ = controller.start(expectedRevision: 0)
        wall.current = 3_000
        mono.current = 3_000
        guard case let .success(firstPause, _) = controller.pause(expectedRevision: 1) else {
            return XCTFail("Expected first pause")
        }
        XCTAssertEqual(firstPause.timerState.remainingDurationMs, 8_000)

        wall.current = 5_000
        mono.current = 5_000
        guard case let .success(firstResume, _) = controller.resume(expectedRevision: 2) else {
            return XCTFail("Expected first resume")
        }
        XCTAssertEqual(firstResume.timerState.deadline, 13_000)

        wall.current = 7_000
        mono.current = 7_000
        guard case let .success(secondPause, _) = controller.pause(expectedRevision: 3) else {
            return XCTFail("Expected second pause")
        }
        XCTAssertEqual(secondPause.timerState.remainingDurationMs, 6_000)

        wall.current = 9_000
        mono.current = 9_000
        guard case let .success(secondResume, _) = controller.resume(expectedRevision: 4) else {
            return XCTFail("Expected second resume")
        }
        XCTAssertEqual(secondResume.timerState.deadline, 15_000)

        wall.current = 15_000
        mono.current = 15_000
        guard case let .success(final, rev6) = controller.pollTimer(
            expectedRevision: 5,
            expectedRunId: try XCTUnwrap(controller.activeTimerRunId)
        ) else { return XCTFail("Expected final expiry") }
        XCTAssertEqual(rev6, 6)
        XCTAssertEqual(final.status, .completed)
        XCTAssertEqual(final.completedCount, 1)
        XCTAssertEqual(final.skippedCount, 0)
        XCTAssertEqual(final.records.count, 1)
        try assertInvariants(controller)
    }

    private func scenarioController(
        routine: Routine,
        wall: FakeExecutionClock,
        mono: FakeMonotonicClock
    ) throws -> ExecutionController {
        try ExecutionController(
            clock: wall,
            initialState: ExecutionSession.create(routine: routine, sessionID: "scenario-session"),
            initialRevision: 0,
            monotonicClock: mono
        )
    }

    private func countStep(
        _ id: String,
        target: Int,
        mode: CountMode
    ) -> RoutineStep {
        RoutineStep(id: id, name: id, type: .count, target: target, countMode: mode)
    }

    private func timerStep(
        _ id: String,
        duration: Int64,
        category: TimerCategory,
        repeatCount: Int = 1
    ) -> RoutineStep {
        RoutineStep(
            id: id, name: id, type: .timer,
            durationMs: duration, category: category,
            repeatCount: repeatCount
        )
    }

    private func race(
        _ first: @escaping @MainActor () -> Bool,
        _ second: @escaping @MainActor () -> Bool
    ) async -> [Bool] {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask { await MainActor.run { first() } }
            group.addTask { await MainActor.run { second() } }
            var values: [Bool] = []
            for await value in group { values.append(value) }
            return values
        }
    }

    private func assertInvariants(_ controller: ExecutionController) throws {
        let snapshot = controller.snapshot()
        let session = snapshot.session
        let step = session.routineSnapshot.steps[session.progress.currentStepIndex]
        let reference = controller.activeTimerReferenceForTesting()
        let engine = controller.timerEngineForTesting().debugState()

        if step.type == .count {
            XCTAssertNil(reference)
        }
        if session.timerState.timerStatus == .running ||
            session.timerState.timerStatus == .paused {
            XCTAssertNotNil(reference)
            XCTAssertEqual(reference?.stepKey, controller.currentTimerStepKey())
            XCTAssertEqual(reference?.runId, engine.runId)
        }
        if session.status == .completed || session.status == .cancelled {
            XCTAssertNil(reference)
        }
        let signatures = session.records.map {
            "\($0.stepID)|\($0.routineIteration)|\($0.stepIteration)|\($0.outcome)"
        }
        XCTAssertEqual(signatures.count, Set(signatures).count)
        XCTAssertGreaterThanOrEqual(snapshot.revision, 0)
    }

    private func lifecycleController(
        steps: [RoutineStep],
        wall: FakeExecutionClock = FakeExecutionClock(1_000),
        mono: MonotonicClock = FakeMonotonicClock(1_000)
    ) throws -> ExecutionController {
        try ExecutionController(
            clock: wall,
            initialState: ExecutionSession.create(
                routine: Routine(
                    id: "lifecycle",
                    name: "Lifecycle",
                    repeatCount: 1,
                    steps: steps,
                    createdAt: 1,
                    updatedAt: 1
                ),
                sessionID: "lifecycle-session"
            ),
            initialRevision: 0,
            monotonicClock: mono
        )
    }

    private func timerController(
        monotonic: FakeMonotonicClock = FakeMonotonicClock(1_000)
    ) throws -> ExecutionController {
        try ExecutionController(
            clock: FakeExecutionClock(1_000),
            initialState: ExecutionSession.create(
                routine: timerRoutine(),
                sessionID: "session-1"
            ),
            initialRevision: 0,
            monotonicClock: monotonic
        )
    }

    private func countController() throws -> ExecutionController {
        try ExecutionController(
            clock: FakeExecutionClock(1_000),
            initialState: ExecutionSession.create(
                routine: countRoutine(),
                sessionID: "session-count"
            )
        )
    }

    private func twoTimerController() throws -> ExecutionController {
        try ExecutionController(
            clock: FakeExecutionClock(1_000),
            initialState: ExecutionSession.create(
                routine: Routine(
                    id: "routine-two",
                    name: "Two timers",
                    repeatCount: 1,
                    steps: [
                        timerStep("timer-1", "First"),
                        timerStep("timer-2", "Second")
                    ],
                    createdAt: 1,
                    updatedAt: 1
                ),
                sessionID: "session-two"
            ),
            initialRevision: 0,
            monotonicClock: FakeMonotonicClock(1_000)
        )
    }

    private func timerRoutine() -> Routine {
        Routine(
            id: "routine-1",
            name: "Timer",
            repeatCount: 1,
            steps: [timerStep("timer", "Plank")],
            createdAt: 1,
            updatedAt: 1
        )
    }

    private func countRoutine() -> Routine {
        Routine(
            id: "routine-count",
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

    private func timerStep(_ id: String, _ name: String) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .timer,
            durationMs: 30_000,
            category: .normal
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
}
