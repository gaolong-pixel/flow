import XCTest
@testable import Flow

@MainActor
final class PlanRunControllerTests: XCTestCase {
    private let clock = FakeRunClock()
    private let wall = FakeRunWallClock()

    private func makeCount(
        id: String = "c1",
        name: String = "俯卧撑",
        target: Int = 15
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .count,
            target: target,
            countMode: .complete,
            repeatCount: 1
        )
    }

    private func makeTimer(
        id: String = "t1",
        name: String = "阅读",
        ms: Int64 = 60_000
    ) -> RoutineStep {
        RoutineStep(
            id: id,
            name: name,
            type: .timer,
            durationMs: ms,
            category: .normal,
            repeatCount: 1
        )
    }

    private func makePlan(_ steps: [RoutineStep]) -> Routine {
        Routine(
            id: "plan-1",
            name: "测试计划",
            version: 1,
            repeatCount: 1,
            steps: steps,
            createdAt: 1,
            updatedAt: 2
        )
    }

    private func makeController(
        _ plan: Routine,
        initialLoop: Bool = false
    ) -> PlanRunController {
        PlanRunController(
            plan: plan,
            initialLoop: initialLoop,
            monotonic: clock,
            wallClock: wall,
            runId: "run-1"
        )
    }

    func testInitialSnapshotIsReadyWithoutProgress() {
        let run = makeController(makePlan([makeCount(), makeTimer()]))
        let snapshot = run.snapshot()
        XCTAssertEqual(snapshot.nodeState, .ready)
        XCTAssertEqual(snapshot.completedNodes, 0)
        XCTAssertEqual(snapshot.nodeIndex, 0)
        XCTAssertEqual(snapshot.totalNodes, 2)
        XCTAssertFalse(snapshot.loopCurrentNode)
        XCTAssertNil(snapshot.remainingMs)
        XCTAssertFalse(snapshot.finalized)
        XCTAssertEqual(snapshot.runId, "run-1")
    }

    func testTimerReadySnapshotShowsFullDuration() {
        let run = makeController(makePlan([makeTimer(ms: 90_000)]))
        XCTAssertEqual(run.snapshot().remainingMs, 90_000)
    }

    func testStartCountMovesToRunningAndCompleteAdvances() {
        let run = makeController(makePlan([makeCount(), makeTimer()]))
        XCTAssertTrue(run.startNode())
        XCTAssertEqual(run.snapshot().nodeState, .running)

        XCTAssertTrue(run.completeNode())
        let advanced = run.snapshot()
        XCTAssertEqual(advanced.nodeIndex, 1)
        XCTAssertEqual(advanced.completedNodes, 1)
        XCTAssertEqual(advanced.nodeState, .ready)
    }

    func testCountCannotCompleteBeforeStart() {
        let run = makeController(makePlan([makeCount()]))
        XCTAssertFalse(run.completeNode())
        XCTAssertEqual(run.snapshot().completedNodes, 0)
        XCTAssertEqual(run.snapshot().nodeIndex, 0)
    }

    func testStartIsRejectedWhenAlreadyRunning() {
        let run = makeController(makePlan([makeCount()]))
        XCTAssertTrue(run.startNode())
        XCTAssertFalse(run.startNode())
        XCTAssertEqual(run.snapshot().nodeState, .running)
    }

    func testTimerStartUsesFullDurationAndMonotonicClock() {
        let run = makeController(makePlan([makeTimer(ms: 60_000)]))
        clock.now = 5_000
        XCTAssertTrue(run.startNode())
        let started = run.snapshot()
        XCTAssertEqual(started.nodeState, .running)
        XCTAssertEqual(started.remainingMs, 60_000)
        XCTAssertNotNil(started.timerRunId)

        clock.advance(15_000)
        XCTAssertEqual(run.snapshot().remainingMs, 45_000)
        XCTAssertFalse(run.refresh())
        XCTAssertEqual(run.snapshot().remainingMs, 45_000)
    }

    func testPauseFreezesRemainingAndResumeContinuesFromIt() {
        let run = makeController(makePlan([makeTimer(ms: 60_000)]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(20_000)

        XCTAssertTrue(run.pause())
        let paused = run.snapshot()
        XCTAssertEqual(paused.nodeState, .paused)
        XCTAssertEqual(paused.remainingMs, 40_000)

        clock.advance(300_000)
        XCTAssertEqual(run.snapshot().remainingMs, 40_000)

        XCTAssertTrue(run.resume())
        XCTAssertEqual(run.snapshot().nodeState, .running)
        XCTAssertEqual(run.snapshot().remainingMs, 40_000)

        clock.advance(10_000)
        XCTAssertFalse(run.refresh())
        XCTAssertEqual(run.snapshot().remainingMs, 30_000)
    }

    func testPauseIsOnlyAllowedForRunningTimer() {
        let run = makeController(makePlan([makeTimer()]))
        XCTAssertFalse(run.pause())
        _ = run.startNode()
        XCTAssertTrue(run.pause())
        XCTAssertFalse(run.pause())
        XCTAssertTrue(run.resume())
        XCTAssertTrue(run.pause())
    }

    func testTimerExpiresWithoutCompletingNode() {
        let run = makeController(makePlan([makeTimer(ms: 10_000)]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(10_000)

        XCTAssertTrue(run.refresh())
        let expired = run.snapshot()
        XCTAssertEqual(expired.nodeState, .expired)
        XCTAssertEqual(expired.remainingMs, 0)
        XCTAssertEqual(expired.completedNodes, 0)
        XCTAssertEqual(expired.nodeIndex, 0)
        XCTAssertFalse(expired.finalized)

        XCTAssertFalse(run.refresh())
        XCTAssertEqual(run.snapshot().nodeState, .expired)
        XCTAssertEqual(run.snapshot().completedNodes, 0)
    }

    func testExpiredTimerRequiresExplicitCompletion() {
        let run = makeController(makePlan([makeTimer(ms: 5_000), makeCount()]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(6_000)
        _ = run.refresh()

        XCTAssertTrue(run.completeNode())
        let advanced = run.snapshot()
        XCTAssertEqual(advanced.nodeIndex, 1)
        XCTAssertEqual(advanced.completedNodes, 1)
        XCTAssertEqual(advanced.node?.type, .count)
    }

    func testPauseAtExpiryTransitionsToExpiredWithoutPausing() {
        let run = makeController(makePlan([makeTimer(ms: 5_000)]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(5_000)
        XCTAssertFalse(run.pause())
        XCTAssertEqual(run.snapshot().nodeState, .expired)
        XCTAssertEqual(run.snapshot().remainingMs, 0)
    }

    func testLoopResetsCurrentCountAndDoesNotCountProgress() {
        let run = makeController(makePlan([makeCount(), makeTimer()]), initialLoop: true)
        XCTAssertTrue(run.startNode())
        XCTAssertTrue(run.completeNode())

        let looped = run.snapshot()
        XCTAssertEqual(looped.nodeState, .ready)
        XCTAssertEqual(looped.nodeIndex, 0)
        XCTAssertEqual(looped.completedNodes, 0)
        XCTAssertTrue(looped.loopCurrentNode)
        XCTAssertFalse(looped.finalized)
    }

    func testTurningLoopOffDoesNotAdvanceButCompletionDoes() {
        let run = makeController(
            makePlan([makeCount(), makeCount(id: "c2", name: "深蹲")]),
            initialLoop: true
        )
        _ = run.startNode()
        XCTAssertTrue(run.setLoop(false))
        XCTAssertEqual(run.snapshot().nodeIndex, 0)
        XCTAssertEqual(run.snapshot().completedNodes, 0)
        XCTAssertEqual(run.snapshot().nodeState, .running)

        XCTAssertTrue(run.completeNode())
        XCTAssertEqual(run.snapshot().nodeIndex, 1)
        XCTAssertEqual(run.snapshot().completedNodes, 1)
        XCTAssertFalse(run.snapshot().loopCurrentNode)
    }

    func testLoopAppliesOnlyToCurrentNodeAndResetsOnAdvance() {
        let run = makeController(
            makePlan([makeCount(), makeCount(id: "c2", name: "深蹲")]),
            initialLoop: true
        )
        _ = run.startNode()
        _ = run.completeNode()
        XCTAssertTrue(run.snapshot().loopCurrentNode)

        _ = run.setLoop(false)
        _ = run.startNode()
        _ = run.completeNode()
        XCTAssertEqual(run.snapshot().nodeIndex, 1)
        XCTAssertFalse(run.snapshot().loopCurrentNode)
    }

    func testLoopTimerResetsToFullDurationWithoutAutoStart() {
        let run = makeController(makePlan([makeTimer(ms: 30_000)]), initialLoop: true)
        clock.now = 0
        _ = run.startNode()
        clock.advance(10_000)
        _ = run.refresh()
        XCTAssertEqual(run.snapshot().remainingMs, 20_000)

        XCTAssertTrue(run.completeNode())
        let reset = run.snapshot()
        XCTAssertEqual(reset.nodeState, .ready)
        XCTAssertEqual(reset.remainingMs, 30_000)
        XCTAssertEqual(reset.completedNodes, 0)
        XCTAssertNil(reset.timerRunId)
    }

    func testLoopOnLastNodeDoesNotFinishUntilLoopIsOff() {
        let run = makeController(makePlan([makeCount()]), initialLoop: true)
        _ = run.startNode()
        _ = run.completeNode()
        XCTAssertFalse(run.snapshot().finalized)
        XCTAssertEqual(run.snapshot().completedNodes, 0)

        _ = run.setLoop(false)
        _ = run.startNode()
        _ = run.completeNode()
        XCTAssertTrue(run.snapshot().finalized)
        XCTAssertEqual(run.snapshot().completedNodes, 1)
    }

    func testLastNodeCompletionFinalizesAndProducesNormalSummary() {
        let run = makeController(makePlan([makeCount(), makeTimer(ms: 5_000)]))
        clock.now = 0
        _ = run.startNode()
        _ = run.completeNode()
        _ = run.startNode()
        clock.advance(5_000)
        _ = run.refresh()
        _ = run.completeNode()

        XCTAssertTrue(run.snapshot().finalized)
        XCTAssertEqual(run.snapshot().completedNodes, 2)
        let summary = run.finishedSummary()
        XCTAssertEqual(summary?.completedNodes, 2)
        XCTAssertEqual(summary?.totalNodes, 2)
        XCTAssertEqual(summary?.completedNormally, true)
        XCTAssertNil(run.exit())
    }

    func testExitReportsProgressAndClearsRuntime() {
        let run = makeController(makePlan([
            makeCount(),
            makeCount(id: "c2", name: "深蹲"),
            makeTimer()
        ]))
        _ = run.startNode()
        _ = run.completeNode()
        _ = run.startNode()

        let summary = run.exit()
        XCTAssertEqual(summary?.completedNodes, 1)
        XCTAssertEqual(summary?.totalNodes, 3)
        XCTAssertEqual(summary?.completedNormally, false)
        XCTAssertNil(run.exit())
        XCTAssertFalse(run.startNode())
        XCTAssertFalse(run.completeNode())
        XCTAssertFalse(run.setLoop(true))
    }

    func testTimerWarningAndExpiryHapticsFireOnce() {
        let run = makeController(makePlan([makeTimer(ms: 10_000)]))
        clock.now = 0
        _ = run.startNode()

        clock.advance(7_000)
        _ = run.refresh()
        XCTAssertEqual(run.consumeHapticEvents(), [.timerWarning])

        clock.advance(500)
        _ = run.refresh()
        XCTAssertTrue(run.consumeHapticEvents().isEmpty)

        clock.advance(2_600)
        _ = run.refresh()
        XCTAssertEqual(run.consumeHapticEvents(), [.timerExpired])

        _ = run.refresh()
        XCTAssertTrue(run.consumeHapticEvents().isEmpty)
    }

    func testWarningIsNotRepeatedAfterPauseAndResume() {
        let run = makeController(makePlan([makeTimer(ms: 10_000)]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(7_000)
        _ = run.refresh()
        XCTAssertEqual(run.consumeHapticEvents(), [.timerWarning])

        XCTAssertTrue(run.pause())
        clock.advance(60_000)
        XCTAssertTrue(run.resume())
        clock.advance(500)
        _ = run.refresh()
        XCTAssertTrue(run.consumeHapticEvents().isEmpty)

        clock.advance(3_000)
        _ = run.refresh()
        XCTAssertEqual(run.consumeHapticEvents(), [.timerExpired])
    }

    func testElapsedSummaryUsesWallClock() {
        let run = makeController(makePlan([makeCount()]))
        wall.now = 100_000
        _ = run.startNode()
        wall.now = 112_500
        _ = run.completeNode()
        XCTAssertEqual(run.finishedSummary()?.elapsedMs, 12_500)
    }

    func testRemainingSurvivesWallClockChanges() {
        let run = makeController(makePlan([makeTimer(ms: 60_000)]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(10_000)
        wall.now += 3_600_000
        XCTAssertEqual(run.snapshot().remainingMs, 50_000)
    }
}
