import XCTest
@testable import Flow

@MainActor
final class ExecutionTimerEngineTests: XCTestCase {
    func testStartsWithInitialSnapshot() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)

        guard case let .success(snapshot) = engine.start(totalDurationMs: 500) else {
            return XCTFail("Expected start success")
        }

        XCTAssertEqual(snapshot.status, .running)
        XCTAssertEqual(snapshot.deadlineMonotonicMs, 1_500)
        XCTAssertEqual(snapshot.totalDurationMs, 500)
        XCTAssertEqual(snapshot.remainingDurationMs, 500)
        XCTAssertEqual(snapshot.runId?.value, "run-1")
    }

    func testRemainingTimeUsesDeadlineAndMonotonicClock() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_300

        guard case let .success(snapshot) = engine.snapshot() else {
            return XCTFail("Expected snapshot success")
        }

        XCTAssertEqual(snapshot.remainingDurationMs, 200)
        XCTAssertEqual(snapshot.deadlineMonotonicMs, 1_500)
        XCTAssertEqual(snapshot.status, .running)
    }

    func testPauseSavesRemainingAndKeepsRunId() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        let started = successSnapshot(engine.start(totalDurationMs: 500))
        clock.current = 1_200

        let paused = successSnapshot(engine.pause())
        clock.current = 1_900
        let stillPaused = successSnapshot(engine.snapshot())

        XCTAssertEqual(paused.status, .paused)
        XCTAssertEqual(paused.remainingDurationMs, 300)
        XCTAssertNil(paused.deadlineMonotonicMs)
        XCTAssertEqual(started.runId, paused.runId)
        XCTAssertEqual(stillPaused.remainingDurationMs, 300)
        XCTAssertEqual(stillPaused.status, .paused)
    }

    func testResumeBuildsNewDeadlineAndKeepsRunId() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_200
        let paused = successSnapshot(engine.pause())
        clock.current = 2_000

        let resumed = successSnapshot(engine.resume())

        XCTAssertEqual(resumed.status, .running)
        XCTAssertEqual(resumed.deadlineMonotonicMs, 2_300)
        XCTAssertEqual(resumed.remainingDurationMs, 300)
        XCTAssertEqual(paused.runId, resumed.runId)
    }

    func testMultiplePauseResumeCyclesRemainDeterministic() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        _ = engine.pause()
        clock.current = 5_000
        _ = engine.resume()
        clock.current = 5_300

        let snapshot = successSnapshot(engine.snapshot())

        XCTAssertEqual(snapshot.remainingDurationMs, 500)
        XCTAssertEqual(snapshot.deadlineMonotonicMs, 5_800)
    }

    func testPollBeforeDeadlineKeepsRunning() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_300

        let result = successSnapshot(engine.poll())

        XCTAssertEqual(result.status, .running)
        XCTAssertEqual(result.remainingDurationMs, 200)
        XCTAssertEqual(engine.debugState().remainingDurationMs, 200)
        XCTAssertEqual(engine.debugState().lastObservedMs, 1_300)
    }

    func testPollAtExactDeadlineExpiresOnce() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_500

        guard case let .expired(first) = engine.poll() else {
            return XCTFail("Expected expiration")
        }
        let second = successSnapshot(engine.poll())

        XCTAssertEqual(first.status, .completed)
        XCTAssertEqual(first.remainingDurationMs, 0)
        XCTAssertNil(first.deadlineMonotonicMs)
        XCTAssertEqual(second.status, .completed)
        XCTAssertEqual(first.runId, second.runId)
    }

    func testPollAfterDeadlineExpires() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_700

        guard case let .expired(snapshot) = engine.poll() else {
            return XCTFail("Expected expiration")
        }

        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertEqual(snapshot.remainingDurationMs, 0)
    }

    func testSnapshotDoesNotMutateRunningState() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        let before = engine.debugState()
        clock.current = 1_300

        let projected = successSnapshot(engine.snapshot())
        let after = engine.debugState()

        XCTAssertEqual(projected.remainingDurationMs, 200)
        XCTAssertEqual(projected.status, .running)
        XCTAssertEqual(before, after)
        XCTAssertEqual(after.remainingDurationMs, 500)
        XCTAssertEqual(after.deadlineMonotonicMs, 1_500)
        XCTAssertEqual(after.lastObservedMs, 1_000)
    }

    func testSnapshotAtExpiredDeadlineStaysRunningUntilPoll() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        let before = engine.debugState()
        clock.current = 1_500

        let projected = successSnapshot(engine.snapshot())
        let after = engine.debugState()

        XCTAssertEqual(projected.status, .running)
        XCTAssertEqual(projected.remainingDurationMs, 0)
        XCTAssertEqual(projected.deadlineMonotonicMs, 1_500)
        XCTAssertEqual(before, after)
        XCTAssertEqual(after.status, .running)
        XCTAssertEqual(after.remainingDurationMs, 500)
        XCTAssertEqual(after.deadlineMonotonicMs, 1_500)
        XCTAssertEqual(after.lastObservedMs, 1_000)

        guard case .expired = engine.poll() else {
            return XCTFail("Expected expiration")
        }
    }

    func testSnapshotDoesNotMutateNonRunningStates() {
        let idle = makeEngine(FakeClock(1_000))
        let idleBefore = idle.debugState()
        _ = idle.snapshot()
        XCTAssertEqual(idleBefore, idle.debugState())

        let paused = makeEngine(FakeClock(1_000))
        _ = paused.start(totalDurationMs: 500)
        _ = paused.pause()
        let pausedBefore = paused.debugState()
        _ = paused.snapshot()
        XCTAssertEqual(pausedBefore, paused.debugState())

        let completedClock = FakeClock(1_000)
        let completed = makeEngine(completedClock)
        _ = completed.start(totalDurationMs: 500)
        completedClock.current = 1_500
        _ = completed.poll()
        let completedBefore = completed.debugState()
        _ = completed.snapshot()
        XCTAssertEqual(completedBefore, completed.debugState())

        let cancelled = makeEngine(FakeClock(1_000))
        _ = cancelled.start(totalDurationMs: 500)
        _ = cancelled.cancel()
        let cancelledBefore = cancelled.debugState()
        _ = cancelled.snapshot()
        XCTAssertEqual(cancelledBefore, cancelled.debugState())
    }

    func testPauseAfterDeadlineCompletes() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_600

        guard case let .expired(snapshot) = engine.pause() else {
            return XCTFail("Expected expiration")
        }

        XCTAssertEqual(snapshot.status, .completed)
        assertFailure(.invalidState, engine.pause())
    }

    func testCancelBeforeDeadlineCancels() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_400

        let result = successSnapshot(engine.cancel())

        XCTAssertEqual(result.status, .cancelled)
        XCTAssertNil(result.deadlineMonotonicMs)
    }

    func testCancelAtExactDeadlineCompletes() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_500

        guard case let .expired(snapshot) = engine.cancel() else {
            return XCTFail("Expected expiration")
        }

        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertNil(snapshot.deadlineMonotonicMs)
        assertFailure(.invalidState, engine.cancel())
    }

    func testCancelAfterDeadlineCompletesAndDoesNotBecomeCancelled() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_700

        guard case let .expired(snapshot) = engine.cancel() else {
            return XCTFail("Expected expiration")
        }

        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertEqual(successSnapshot(engine.snapshot()).status, .completed)
        assertFailure(.invalidState, engine.cancel())
    }

    func testPauseAtExpiredDeadlineReturnsExpiredInsteadOfPaused() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_500

        guard case let .expired(snapshot) = engine.pause() else {
            return XCTFail("Expected expiration")
        }

        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertEqual(snapshot.remainingDurationMs, 0)
    }

    func testCancelInvalidatesResumeAndNewStartUsesNewId() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        let first = successSnapshot(engine.start(totalDurationMs: 500))
        clock.current = 1_200

        let cancelled = successSnapshot(engine.cancel())
        XCTAssertEqual(cancelled.status, .cancelled)
        XCTAssertEqual(cancelled.runId, first.runId)
        assertFailure(.invalidState, engine.resume())

        let second = successSnapshot(engine.start(totalDurationMs: 700))
        XCTAssertEqual(second.status, .running)
        XCTAssertNotEqual(second.runId, first.runId)
    }

    func testRejectsZeroAndNegativeDuration() {
        let engine = makeEngine(FakeClock(1_000))

        assertFailure(.invalidDuration, engine.start(totalDurationMs: 0))
        assertFailure(.invalidDuration, engine.start(totalDurationMs: -1))
        XCTAssertEqual(successSnapshot(engine.snapshot()).status, .idle)
    }

    func testRejectsIllegalStateOperations() {
        let engine = makeEngine(FakeClock(1_000))

        assertFailure(.invalidState, engine.pause())
        assertFailure(.invalidState, engine.resume())
        assertFailure(.invalidState, engine.cancel())

        _ = engine.start(totalDurationMs: 500)
        assertFailure(.invalidState, engine.start(totalDurationMs: 500))
        _ = engine.pause()
        assertFailure(.invalidState, engine.pause())
    }

    func testPauseClockRegressionFailsWithoutMutatingState() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        let before = engine.debugState()
        clock.current = 900

        assertFailure(.clockRegression, engine.pause())
        XCTAssertEqual(before, engine.debugState())
    }

    func testCancelClockRegressionFailsWithoutMutatingState() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        let before = engine.debugState()
        clock.current = 900

        assertFailure(.clockRegression, engine.cancel())
        XCTAssertEqual(before, engine.debugState())
    }

    func testSnapshotClockRegressionFailsWithoutMutatingState() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        let before = engine.debugState()
        clock.current = 900

        assertFailure(.clockRegression, engine.snapshot())
        XCTAssertEqual(before, engine.debugState())
        assertFailure(.clockRegression, engine.snapshot())
        XCTAssertEqual(before, engine.debugState())
    }

    func testPollClockRegressionFailsWithoutMutatingStateAndRecovers() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        let before = engine.debugState()
        clock.current = 900

        assertFailure(.clockRegression, engine.poll())
        XCTAssertEqual(before, engine.debugState())

        clock.current = 1_100
        let recovered = successSnapshot(engine.poll())
        XCTAssertEqual(recovered.status, .running)
        XCTAssertEqual(recovered.remainingDurationMs, 400)
        XCTAssertEqual(engine.debugState().lastObservedMs, 1_100)
    }

    func testRejectsNegativeClock() {
        let engine = makeEngine(FakeClock(-1))

        assertFailure(.invalidClock, engine.start(totalDurationMs: 500))
    }

    func testRejectsDeadlineOverflow() {
        let engine = makeEngine(FakeClock(Int64.max - 10))

        assertFailure(.deadlineOverflow, engine.start(totalDurationMs: 100))
    }

    func testRejectsResumeDeadlineOverflow() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_500
        _ = engine.pause()
        clock.current = Int64.max - 100

        assertFailure(.deadlineOverflow, engine.resume())
    }

    func testCompletedRunCanStartNewRunWithNewId() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        let first = successSnapshot(engine.start(totalDurationMs: 500))
        clock.current = 1_500
        _ = engine.poll()

        let second = successSnapshot(engine.start(totalDurationMs: 500))

        XCTAssertEqual(second.status, .running)
        XCTAssertNotEqual(first.runId, second.runId)
    }

    func testCancelFromPausedKeepsRemainingAndInvalidatesResume() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 500)
        clock.current = 1_200
        _ = engine.pause()

        let cancelled = successSnapshot(engine.cancel())

        XCTAssertEqual(cancelled.status, .cancelled)
        XCTAssertEqual(cancelled.remainingDurationMs, 300)
        assertFailure(.invalidState, engine.resume())
    }

    func testIdleSnapshotIsStable() {
        let engine = makeEngine(FakeClock(1_000))
        let snapshot = successSnapshot(engine.snapshot())

        XCTAssertEqual(snapshot.status, .idle)
        XCTAssertNil(snapshot.runId)
        XCTAssertEqual(snapshot.totalDurationMs, 0)
        XCTAssertEqual(snapshot.remainingDurationMs, 0)
    }

    func testPrepareStartIsPureWithRespectToTimerStateAndVersion() {
        let engine = makeEngine(FakeClock(1_000))
        let before = engine.debugState()

        guard case .success = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let afterPrepare = engine.debugState()

        XCTAssertEqual(before, afterPrepare)
        XCTAssertEqual(engine.debugState().status, .idle)
    }

    func testCommitUsesOriginalPreparedValues() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()

        guard case let .success(snapshot) = engine.commitStart(plan) else {
            return XCTFail("Expected commit success")
        }
        XCTAssertEqual(snapshot.status, .running)
        XCTAssertEqual(snapshot.totalDurationMs, 500)
        XCTAssertEqual(snapshot.remainingDurationMs, 500)
        XCTAssertEqual(snapshot.deadlineMonotonicMs, 1_500)
        XCTAssertEqual(snapshot.runId?.value, "run-1")
        XCTAssertEqual(engine.debugState().stateVersion, before.stateVersion + 1)
    }

    func testUnregisteredPlanIsRejected() {
        let engine = makeEngine(FakeClock(1_000))
        let before = engine.debugState()
        let fake = TimerPlan(
            targetRunId: TimerRunId(value: "fake"),
            deadlineMonotonicMs: 1
        )

        assertFailure(.invalidPlan, engine.commitStart(fake))
        XCTAssertEqual(before, engine.debugState())
    }

    func testPrepareFailureLeavesStateAndVersionUnchanged() {
        let engine = makeEngine(FakeClock(1_000))
        let before = engine.debugState()

        assertFailure(.invalidDuration, engine.start(totalDurationMs: 0))
        XCTAssertEqual(before, engine.debugState())

        guard case let .failure(error) = engine.prepareStart(totalDurationMs: 0) else {
            return XCTFail("Expected prepare failure")
        }
        XCTAssertEqual(error, .invalidDuration)
        XCTAssertEqual(before, engine.debugState())
    }

    func testPlanCannotBeCommittedToAnotherEngine() {
        let first = makeEngine(FakeClock(1_000))
        let second = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = first.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }

        assertFailure(.invalidPlan, second.commitStart(plan))
        XCTAssertEqual(second.debugState().status, .idle)
    }

    func testNewerPrepareInvalidatesOlderPlan() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(first) = engine.prepareStart(totalDurationMs: 500),
              case let .success(second) = engine.prepareStart(totalDurationMs: 700) else {
            return XCTFail("Expected plans")
        }
        let before = engine.debugState()

        assertFailure(.invalidPlan, engine.commitStart(first))
        XCTAssertEqual(before, engine.debugState())

        guard case let .success(snapshot) = engine.commitStart(second) else {
            return XCTFail("Expected second commit success")
        }
        XCTAssertEqual(snapshot.totalDurationMs, 700)
        XCTAssertEqual(snapshot.runId?.value, "run-2")
    }

    func testPlanIsInvalidatedByStateVersionChange() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        engine.setStateVersionForTesting(1)
        let before = engine.debugState()

        assertFailure(.planStale, engine.commitStart(plan))
        XCTAssertEqual(before, engine.debugState())
    }

    func testDuplicatePlanCommitIsRejected() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        _ = engine.commitStart(plan)
        let afterFirst = engine.debugState()

        assertFailure(.invalidPlan, engine.commitStart(plan))
        XCTAssertEqual(afterFirst, engine.debugState())
    }

    func testExpiredPlanCannotBeCommittedAsRunning() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()
        clock.current = 1_500

        assertFailure(.planExpired, engine.commitStart(plan))
        XCTAssertEqual(before, engine.debugState())
    }

    func testPlanCommitFailureDoesNotChangeVersion() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()
        clock.current = 1_500

        _ = engine.commitStart(plan)

        XCTAssertEqual(before, engine.debugState())
    }

    func testPrepareStartDoesNotUpdateObservedClock() {
        let engine = makeEngine(FakeClock(1_000))
        let before = engine.debugState()
        _ = engine.prepareStart(totalDurationMs: 500)
        let after = engine.debugState()

        XCTAssertEqual(before.lastObservedMs, after.lastObservedMs)
        XCTAssertEqual(before.stateVersion, after.stateVersion)
    }

    func testCommitBeforePreparedTimeIsRejected() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()
        clock.current = 900

        assertFailure(.clockRegression, engine.commitStart(plan))
        XCTAssertEqual(before, engine.debugState())
    }

    func testVersionOverflowRejectsStartWithoutStateChange() {
        let engine = makeEngine(FakeClock(1_000))
        engine.setStateVersionForTesting(Int64.max)
        let before = engine.debugState()

        assertFailure(.versionOverflow, engine.start(totalDurationMs: 500))
        XCTAssertEqual(before, engine.debugState())
    }

    func testVersionOverflowRejectsPauseResumeCancelAndPoll() {
        let pauseEngine = makeEngine(FakeClock(1_000))
        _ = pauseEngine.start(totalDurationMs: 500)
        pauseEngine.setStateVersionForTesting(Int64.max)
        let pauseBefore = pauseEngine.debugState()
        assertFailure(.versionOverflow, pauseEngine.pause())
        XCTAssertEqual(pauseBefore, pauseEngine.debugState())

        let resumeEngine = makeEngine(FakeClock(1_000))
        _ = resumeEngine.start(totalDurationMs: 500)
        _ = resumeEngine.pause()
        resumeEngine.setStateVersionForTesting(Int64.max)
        let resumeBefore = resumeEngine.debugState()
        assertFailure(.versionOverflow, resumeEngine.resume())
        XCTAssertEqual(resumeBefore, resumeEngine.debugState())

        let cancelEngine = makeEngine(FakeClock(1_000))
        _ = cancelEngine.start(totalDurationMs: 500)
        cancelEngine.setStateVersionForTesting(Int64.max)
        let cancelBefore = cancelEngine.debugState()
        assertFailure(.versionOverflow, cancelEngine.cancel())
        XCTAssertEqual(cancelBefore, cancelEngine.debugState())

        let pollEngine = makeEngine(FakeClock(1_000))
        _ = pollEngine.start(totalDurationMs: 500)
        pollEngine.setStateVersionForTesting(Int64.max)
        let pollBefore = pollEngine.debugState()
        assertFailure(.versionOverflow, pollEngine.poll())
        XCTAssertEqual(pollBefore, pollEngine.debugState())
    }

    func testVersionCanAdvanceUpToMaximum() {
        let engine = makeEngine(FakeClock(1_000))
        engine.setStateVersionForTesting(Int64.max - 1)

        guard case let .success(snapshot) = engine.start(totalDurationMs: 500) else {
            return XCTFail("Expected start success")
        }
        XCTAssertEqual(engine.debugState().stateVersion, Int64.max)
        XCTAssertEqual(snapshot.status, .running)
    }

    func testFailedPrepareKeepsPriorPlanValid() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()

        guard case let .failure(error) = engine.prepareStart(totalDurationMs: 0) else {
            return XCTFail("Expected prepare failure")
        }
        XCTAssertEqual(error, .invalidDuration)
        XCTAssertEqual(before, engine.debugState())

        guard case let .success(snapshot) = engine.commitStart(plan) else {
            return XCTFail("Expected commit success")
        }
        XCTAssertEqual(snapshot.totalDurationMs, 500)
    }

    func testExpiredCommitFailureIsRetryableOnlyAfterNewPrepare() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        guard case let .success(oldPlan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        clock.current = 1_500
        let afterExpiry = engine.debugState()

        assertFailure(.planExpired, engine.commitStart(oldPlan))
        XCTAssertEqual(afterExpiry, engine.debugState())
        assertFailure(.planExpired, engine.commitStart(oldPlan))
        XCTAssertEqual(afterExpiry, engine.debugState())

        guard case let .success(newPlan) = engine.prepareStart(totalDurationMs: 700),
              case let .success(snapshot) = engine.commitStart(newPlan) else {
            return XCTFail("Expected new plan success")
        }
        XCTAssertEqual(snapshot.totalDurationMs, 700)

        assertFailure(.invalidPlan, engine.commitStart(oldPlan))
    }

    func testVersionInvalidatedPlanCannotRecoverAndNewPrepareCanStart() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(oldPlan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        engine.setStateVersionForTesting(1)

        assertFailure(.planStale, engine.commitStart(oldPlan))
        assertFailure(.planStale, engine.commitStart(oldPlan))

        guard case let .success(newPlan) = engine.prepareStart(totalDurationMs: 700),
              case let .success(snapshot) = engine.commitStart(newPlan) else {
            return XCTFail("Expected new plan success")
        }
        XCTAssertEqual(snapshot.totalDurationMs, 700)

        assertFailure(.invalidPlan, engine.commitStart(oldPlan))
    }

    func testActualStartInvalidatesPriorPlan() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(oldPlan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }

        _ = engine.start(totalDurationMs: 700)

        assertFailure(.invalidPlan, engine.commitStart(oldPlan))
        XCTAssertEqual(engine.debugState().status, .running)
        XCTAssertEqual(engine.debugState().totalDurationMs, 700)
    }

    func testPollOnIdleDoesNotInvalidatePlan() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()

        _ = engine.poll()
        XCTAssertEqual(before, engine.debugState())

        guard case let .success(snapshot) = engine.commitStart(plan) else {
            return XCTFail("Expected commit success")
        }
        XCTAssertEqual(snapshot.totalDurationMs, 500)
    }

    func testPauseResumeCancelAreIllegalBeforeRunningAndKeepPlanValid() {
        let engine = makeEngine(FakeClock(1_000))
        guard case let .success(plan) = engine.prepareStart(totalDurationMs: 500) else {
            return XCTFail("Expected plan")
        }
        let before = engine.debugState()

        assertFailure(.invalidState, engine.pause())
        assertFailure(.invalidState, engine.resume())
        assertFailure(.invalidState, engine.cancel())
        XCTAssertEqual(before, engine.debugState())

        guard case let .success(snapshot) = engine.commitStart(plan) else {
            return XCTFail("Expected commit success")
        }
        XCTAssertEqual(snapshot.totalDurationMs, 500)
    }

    func testPausePlanCommitsPausedState() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200

        guard case let .ready(rawPlan) = engine.preparePause(),
              let plan = rawPlan as? PausePlan else {
            return XCTFail("Expected pause plan")
        }
        guard case let .paused(snapshot) = engine.commitPause(plan) else {
            return XCTFail("Expected paused commit")
        }

        XCTAssertEqual(plan.remainingDurationMs, 800)
        XCTAssertEqual(snapshot.status, .paused)
        XCTAssertEqual(snapshot.remainingDurationMs, 800)
        XCTAssertNil(snapshot.deadlineMonotonicMs)
        XCTAssertEqual(snapshot.runId?.value, "run-1")
    }

    func testPauseCommitAfterDeadlineCompletesInstead() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(rawPlan) = engine.preparePause(),
              let plan = rawPlan as? PausePlan else {
            return XCTFail("Expected pause plan")
        }
        clock.current = 2_000

        guard case let .expired(snapshot) = engine.commitPause(plan) else {
            return XCTFail("Expected expiry")
        }
        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertEqual(snapshot.remainingDurationMs, 0)
        XCTAssertNil(snapshot.deadlineMonotonicMs)
    }

    func testPreparePauseAfterDeadlineReturnsExpiredWithoutPlan() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 2_000

        guard case .expired = engine.preparePause() else {
            return XCTFail("Expected expired")
        }
    }

    func testResumePlanPreservesRunIdAndUsesAnchor() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(pauseRaw) = engine.preparePause(),
              let pausePlan = pauseRaw as? PausePlan else {
            return XCTFail("Expected pause plan")
        }
        _ = engine.commitPause(pausePlan)
        clock.current = 1_300

        guard case let .ready(rawPlan) = engine.prepareResume(),
              let plan = rawPlan as? ResumePlan else {
            return XCTFail("Expected resume plan")
        }
        XCTAssertEqual(plan.deadlineMonotonicMs, 2_100)

        guard case let .resumed(snapshot) = engine.commitResume(plan) else {
            return XCTFail("Expected resume")
        }
        XCTAssertEqual(snapshot.status, .running)
        XCTAssertEqual(snapshot.deadlineMonotonicMs, 2_100)
        XCTAssertEqual(snapshot.runId?.value, "run-1")
    }

    func testResumeCommitAfterAnchorCompletes() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(pauseRaw) = engine.preparePause(),
              let pausePlan = pauseRaw as? PausePlan else {
            return XCTFail("Expected pause plan")
        }
        _ = engine.commitPause(pausePlan)
        clock.current = 1_300
        guard case let .ready(resumeRaw) = engine.prepareResume(),
              let plan = resumeRaw as? ResumePlan else {
            return XCTFail("Expected resume plan")
        }
        clock.current = 2_100

        guard case let .expired(snapshot) = engine.commitResume(plan) else {
            return XCTFail("Expected expiry")
        }
        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertEqual(snapshot.remainingDurationMs, 0)
    }

    func testResumeAnchorIsNotExtendedByPendingReplacement() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(pauseRaw) = engine.preparePause(),
              let pausePlan = pauseRaw as? PausePlan else {
            return XCTFail("Expected pause plan")
        }
        _ = engine.commitPause(pausePlan)
        clock.current = 1_300
        guard case let .ready(firstRaw) = engine.prepareResume(),
              let first = firstRaw as? ResumePlan else {
            return XCTFail("Expected first resume plan")
        }

        clock.current = 1_400
        guard case let .ready(cancelRaw) = engine.prepareCancel(),
              let cancelPlan = cancelRaw as? CancelPlan else {
            return XCTFail("Expected cancel plan")
        }
        clock.current = 1_500
        guard case let .ready(secondRaw) = engine.prepareResume(),
              let second = secondRaw as? ResumePlan else {
            return XCTFail("Expected second resume plan")
        }

        XCTAssertEqual(first.deadlineMonotonicMs, second.deadlineMonotonicMs)
        XCTAssertEqual(second.deadlineMonotonicMs, 2_100)
        guard case .failure = engine.commitCancel(cancelPlan) else {
            return XCTFail("Expected stale cancel plan")
        }
        guard case .resumed = engine.commitResume(second) else {
            return XCTFail("Expected second resume")
        }
    }

    func testResumeAnchorIsClearedAfterSuccessfulResume() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(firstPauseRaw) = engine.preparePause(),
              let firstPause = firstPauseRaw as? PausePlan else {
            return XCTFail("Expected pause")
        }
        _ = engine.commitPause(firstPause)
        clock.current = 1_300
        guard case let .ready(resumeRaw) = engine.prepareResume(),
              let resume = resumeRaw as? ResumePlan else {
            return XCTFail("Expected resume")
        }
        _ = engine.commitResume(resume)

        clock.current = 1_400
        guard case let .ready(secondPauseRaw) = engine.preparePause(),
              let secondPause = secondPauseRaw as? PausePlan else {
            return XCTFail("Expected second pause")
        }
        _ = engine.commitPause(secondPause)
        clock.current = 1_500
        guard case let .ready(nextResumeRaw) = engine.prepareResume(),
              let nextResume = nextResumeRaw as? ResumePlan else {
            return XCTFail("Expected next resume")
        }

        XCTAssertEqual(nextResume.deadlineMonotonicMs, 2_200)
    }

    func testCancelPlanCommitsRunningAndPausedStates() {
        let runningClock = FakeClock(1_000)
        let running = makeEngine(runningClock)
        _ = running.start(totalDurationMs: 1_000)
        runningClock.current = 1_200
        guard case let .ready(runningRaw) = running.prepareCancel(),
              let runningPlan = runningRaw as? CancelPlan else {
            return XCTFail("Expected cancel")
        }
        guard case let .cancelled = running.commitCancel(runningPlan) else {
            return XCTFail("Expected cancelled")
        }

        let pausedClock = FakeClock(1_000)
        let paused = makeEngine(pausedClock)
        _ = paused.start(totalDurationMs: 1_000)
        pausedClock.current = 1_200
        guard case let .ready(pauseRaw) = paused.preparePause(),
              let pausePlan = pauseRaw as? PausePlan else {
            return XCTFail("Expected pause")
        }
        _ = paused.commitPause(pausePlan)
        pausedClock.current = 1_300
        guard case let .ready(cancelRaw) = paused.prepareCancel(),
              let cancelPlan = cancelRaw as? CancelPlan else {
            return XCTFail("Expected paused cancel")
        }
        guard case .cancelled = paused.commitCancel(cancelPlan) else {
            return XCTFail("Expected paused cancel")
        }
    }

    func testCancelCommitAfterDeadlineCompletes() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(rawPlan) = engine.prepareCancel(),
              let plan = rawPlan as? CancelPlan else {
            return XCTFail("Expected cancel plan")
        }
        clock.current = 2_000

        guard case let .expired(snapshot) = engine.commitCancel(plan) else {
            return XCTFail("Expected expiry")
        }
        XCTAssertEqual(snapshot.status, .completed)
        XCTAssertEqual(snapshot.remainingDurationMs, 0)
    }

    func testPollAndPausedExpiryCompleteOnce() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_500
        guard case .noChange = engine.preparePoll() else {
            return XCTFail("Expected no change")
        }

        clock.current = 2_000
        guard case let .ready(rawPlan) = engine.preparePoll(),
              let plan = rawPlan as? PollPlan else {
            return XCTFail("Expected poll plan")
        }
        guard case .completed = engine.commitPoll(plan) else {
            return XCTFail("Expected completed")
        }
        guard case .noChange = engine.preparePoll() else {
            return XCTFail("Expected repeated no change")
        }

        let pausedClock = FakeClock(1_000)
        let paused = makeEngine(pausedClock)
        _ = paused.start(totalDurationMs: 1_000)
        pausedClock.current = 1_200
        guard case let .ready(pauseRaw) = paused.preparePause(),
              let pausePlan = pauseRaw as? PausePlan else {
            return XCTFail("Expected pause")
        }
        _ = paused.commitPause(pausePlan)
        pausedClock.current = 1_300
        _ = paused.prepareResume()
        pausedClock.current = 2_100
        guard case let .ready(pollRaw) = paused.preparePoll(),
              let pausedPoll = pollRaw as? PollPlan else {
            return XCTFail("Expected paused poll")
        }
        guard case .completed = paused.commitPoll(pausedPoll) else {
            return XCTFail("Expected paused completed")
        }
    }

    func testPendingLifecycleReplacementRejectsOldPlan() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(pauseRaw) = engine.preparePause(),
              let pausePlan = pauseRaw as? PausePlan else {
            return XCTFail("Expected pause")
        }
        guard case let .ready(cancelRaw) = engine.prepareCancel(),
              let cancelPlan = cancelRaw as? CancelPlan else {
            return XCTFail("Expected cancel")
        }

        guard case .failure = engine.commitPause(pausePlan) else {
            return XCTFail("Expected stale pause")
        }
        guard case .cancelled = engine.commitCancel(cancelPlan) else {
            return XCTFail("Expected cancel")
        }
    }

    func testFailedPreparePreservesExistingLifecyclePlan() {
        let clock = FakeClock(1_000)
        let engine = makeEngine(clock)
        _ = engine.start(totalDurationMs: 1_000)
        clock.current = 1_200
        guard case let .ready(rawPlan) = engine.preparePause(),
              let plan = rawPlan as? PausePlan else {
            return XCTFail("Expected pause")
        }
        let before = engine.debugState()

        guard case let .failure(error) = engine.prepareResume() else {
            return XCTFail("Expected invalid resume")
        }
        XCTAssertEqual(error, .invalidState)
        XCTAssertEqual(before, engine.debugState())
        guard case .paused = engine.commitPause(plan) else {
            return XCTFail("Expected original pause plan")
        }
    }

    func testLifecyclePlanCannotCrossEngineAndFakePlanRejected() {
        let first = makeEngine(FakeClock(1_000))
        _ = first.start(totalDurationMs: 1_000)
        guard case let .ready(rawPlan) = first.preparePause(),
              let plan = rawPlan as? PausePlan else {
            return XCTFail("Expected pause")
        }
        let second = makeEngine(FakeClock(1_000))
        _ = second.start(totalDurationMs: 1_000)

        guard case .failure = second.commitPause(plan) else {
            return XCTFail("Expected cross-engine rejection")
        }
        guard case .failure = first.commitPause(PausePlan(remainingDurationMs: 1)) else {
            return XCTFail("Expected fake rejection")
        }
    }

    func testLifecyclePreparationsRespectVersionOverflow() {
        let engine = makeEngine(FakeClock(1_000))
        _ = engine.start(totalDurationMs: 1_000)
        engine.setStateVersionForTesting(Int64.max)

        guard case let .failure(error) = engine.preparePause() else {
            return XCTFail("Expected version overflow")
        }
        XCTAssertEqual(error, .versionOverflow)
    }

    private func makeEngine(_ clock: FakeClock) -> ExecutionTimerEngine {
        var nextId = 1
        return ExecutionTimerEngine(
            clock: clock,
            idGenerator: {
                let id = "run-\(nextId)"
                nextId += 1
                return id
            }
        )
    }

    private func successSnapshot(_ result: TimerOperationResult) -> TimerSnapshot {
        guard case let .success(snapshot) = result else {
            XCTFail("Expected success, got \(result)")
            return TimerSnapshot(
                runId: nil,
                status: .idle,
                totalDurationMs: 0,
                remainingDurationMs: 0,
                deadlineMonotonicMs: nil
            )
        }
        return snapshot
    }

    private func assertFailure(
        _ expected: TimerEngineError,
        _ result: TimerOperationResult
    ) {
        guard case let .failure(error) = result else {
            return XCTFail("Expected failure \(expected), got \(result)")
        }
        XCTAssertEqual(error, expected)
    }

    private final class FakeClock: MonotonicClock {
        var current: Int64

        init(_ current: Int64) {
            self.current = current
        }

        var nowMs: Int64 { current }
    }
}
