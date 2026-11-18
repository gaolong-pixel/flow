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
