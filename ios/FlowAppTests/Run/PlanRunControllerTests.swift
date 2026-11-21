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

    func testInitialSchedulingStatusExposesFirstNodeAsCurrent() {
        let run = makeController(makePlan([
            makeCount(),
            makeTimer(),
            makeCount(id: "c2", name: "深蹲")
        ]))
        let snapshot = run.snapshot()

        XCTAssertEqual(snapshot.nodeStatuses, [.current, .pending, .pending])
        XCTAssertEqual(snapshot.visibleNodeIndices, [0, 1, 2])
        XCTAssertEqual(snapshot.skippedNodes, 0)
        XCTAssertFalse(snapshot.isFinalNode)
    }

    func testSwitchingFromRunningCountDefersItWithoutLosingRunningState() {
        let run = makeController(makePlan([makeCount(target: 5), makeTimer()]))
        _ = run.startNode()

        XCTAssertTrue(run.selectNode(1))
        let switched = run.snapshot()
        XCTAssertEqual(switched.nodeIndex, 1)
        XCTAssertEqual(switched.nodeStatuses[0], .deferred)
        XCTAssertEqual(switched.completedNodes, 0)

        XCTAssertTrue(run.selectNode(0))
        let restored = run.snapshot()
        XCTAssertEqual(restored.nodeIndex, 0)
        XCTAssertEqual(restored.nodeState, .running)
        XCTAssertFalse(run.startNode())
    }

    func testSwitchingFromRunningTimerFreezesRemainingAndRestoresPausedState() {
        let run = makeController(makePlan([makeTimer(ms: 60_000), makeCount()]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(20_000)

        XCTAssertTrue(run.selectNode(1))
        let switched = run.snapshot()
        XCTAssertEqual(switched.nodeIndex, 1)
        XCTAssertEqual(switched.nodeStatuses[0], .deferred)
        XCTAssertNil(switched.remainingMs)

        clock.advance(300_000)
        XCTAssertTrue(run.selectNode(0))
        let restored = run.snapshot()
        XCTAssertEqual(restored.nodeIndex, 0)
        XCTAssertEqual(restored.nodeState, .paused)
        XCTAssertEqual(restored.remainingMs, 40_000)

        XCTAssertTrue(run.resume())
        clock.advance(10_000)
        _ = run.refresh()
        XCTAssertEqual(run.snapshot().remainingMs, 30_000)
    }

    func testSwitchingAwayFromExpiredTimerKeepsExpiredStateForLaterCompletion() {
        let run = makeController(makePlan([makeTimer(ms: 5_000), makeCount()]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(5_000)
        _ = run.refresh()
        XCTAssertEqual(run.snapshot().nodeState, .expired)

        XCTAssertTrue(run.selectNode(1))
        XCTAssertEqual(run.snapshot().nodeStatuses[0], .deferred)
        XCTAssertTrue(run.selectNode(0))
        XCTAssertEqual(run.snapshot().nodeState, .expired)
        XCTAssertEqual(run.snapshot().remainingMs, 0)
        XCTAssertTrue(run.completeNode())
        XCTAssertEqual(run.snapshot().nodeStatuses[0], .completed)
        XCTAssertEqual(run.snapshot().completedNodes, 1)
    }

    func testCompletedNodeLeavesRollerAndNextSelectionWrapsAround() {
        let run = makeController(makePlan([
            makeCount(id: "c1", name: "一"),
            makeCount(id: "c2", name: "二"),
            makeCount(id: "c3", name: "三")
        ]))
        _ = run.startNode()
        _ = run.completeNode()
        XCTAssertEqual(run.snapshot().nodeIndex, 1)

        XCTAssertTrue(run.selectNode(2))
        XCTAssertEqual(run.snapshot().nodeStatuses[1], .pending)
        XCTAssertEqual(run.snapshot().nodeStatuses[2], .current)

        _ = run.startNode()
        _ = run.completeNode()
        let after = run.snapshot()
        XCTAssertEqual(after.nodeIndex, 1)
        XCTAssertEqual(after.nodeStatuses[0], .completed)
        XCTAssertEqual(after.nodeStatuses[2], .completed)
        XCTAssertEqual(after.nodeStatuses[1], .current)
        XCTAssertEqual(after.visibleNodeIndices, [1])
        XCTAssertEqual(after.completedNodes, 2)

        _ = run.startNode()
        _ = run.completeNode()
        XCTAssertTrue(run.snapshot().finalized)
        XCTAssertEqual(run.snapshot().completedNodes, 3)
    }

    func testSkipNeverCountsAsCompletedAndCanBeReselected() {
        let run = makeController(makePlan([makeCount(id: "c1"), makeTimer(ms: 5_000)]))
        _ = run.startNode() // COUNT is RUNNING
        XCTAssertTrue(run.skipCurrent())

        let skipped = run.snapshot()
        XCTAssertEqual(skipped.nodeIndex, 1)
        XCTAssertEqual(skipped.completedNodes, 0)
        XCTAssertEqual(skipped.skippedNodes, 1)
        XCTAssertEqual(skipped.nodeStatuses[0], .skipped)
        // The skipped node remains in the roller and is selectable.
        XCTAssertEqual(skipped.visibleNodeIndices, [0, 1])

        XCTAssertTrue(run.selectNode(0))
        let resumed = run.snapshot()
        XCTAssertEqual(resumed.nodeIndex, 0)
        XCTAssertEqual(resumed.nodeStatuses[0], .current)
        XCTAssertEqual(resumed.nodeState, .running)
        XCTAssertEqual(resumed.skippedNodes, 0)

        XCTAssertTrue(run.completeNode())
        XCTAssertEqual(run.snapshot().completedNodes, 1)
        XCTAssertEqual(run.snapshot().nodeStatuses[0], .completed)
        XCTAssertEqual(run.snapshot().visibleNodeIndices, [1])
    }

    func testSkippingReadyNodeReselectsFromReady() {
        let run = makeController(makePlan([makeCount(id: "c1"), makeCount(id: "c2")]))
        XCTAssertTrue(run.skipCurrent()) // c1 was never started

        XCTAssertTrue(run.selectNode(0))
        XCTAssertEqual(run.snapshot().nodeState, .ready)
        XCTAssertEqual(run.snapshot().skippedNodes, 0)
        XCTAssertTrue(run.startNode())
    }

    func testReselectedSkippedNodeCanBeDeferredAgainAndReselected() {
        let run = makeController(makePlan([makeCount(id: "c1"), makeCount(id: "c2")]))
        _ = run.startNode()
        XCTAssertTrue(run.skipCurrent()) // c1 SKIPPED (RUNNING retained), c2 current
        XCTAssertTrue(run.selectNode(0)) // c1 CURRENT
        XCTAssertTrue(run.selectNode(1)) // c1 DEFERRED, c2 current
        XCTAssertEqual(run.snapshot().nodeStatuses[0], .deferred)
        XCTAssertTrue(run.selectNode(0))
        XCTAssertEqual(run.snapshot().nodeStatuses[0], .current)
        XCTAssertEqual(run.snapshot().nodeState, .running)
    }

    func testSkippingRunningTimerPreservesProgressForReselection() {
        let run = makeController(makePlan([makeTimer(ms: 10_000), makeCount()]))
        clock.now = 0
        _ = run.startNode()
        clock.advance(4_000)
        XCTAssertTrue(run.skipCurrent())

        let skipped = run.snapshot()
        XCTAssertEqual(skipped.nodeIndex, 1)
        XCTAssertEqual(skipped.nodeStatuses[0], .skipped)
        XCTAssertEqual(skipped.completedNodes, 0)
        XCTAssertEqual(skipped.skippedNodes, 1)
        XCTAssertEqual(skipped.remainingFor(0), 6_000)

        XCTAssertTrue(run.selectNode(0))
        let restored = run.snapshot()
        XCTAssertEqual(restored.nodeIndex, 0)
        XCTAssertEqual(restored.nodeState, .paused)
        XCTAssertEqual(restored.remainingMs, 6_000)
        XCTAssertEqual(restored.skippedNodes, 0)
        XCTAssertTrue(run.resume())
        clock.advance(1_000)
        _ = run.refresh()
        XCTAssertEqual(run.snapshot().remainingMs, 5_000)
    }

    func testLastActiveNodeCannotBeSkippedAndCompletingItKeepsSkippedSummary() {
        let run = makeController(makePlan([makeCount(id: "c1"), makeCount(id: "c2")]))
        _ = run.startNode()
        XCTAssertTrue(run.skipCurrent())

        // c2 is the only remaining PENDING/DEFERRED node. Skipping it would
        // end the run and remove the skipped-node re-entry path, so it is
        // rejected; the skipped c1 stays selectable in the roller.
        XCTAssertFalse(run.skipCurrent())
        let active = run.snapshot()
        XCTAssertEqual(active.nodeIndex, 1)
        XCTAssertEqual(active.nodeStatuses[0], .skipped)
        XCTAssertEqual(active.nodeStatuses[1], .current)
        XCTAssertEqual(active.skippedNodes, 1)
        XCTAssertFalse(active.finalized)

        _ = run.startNode()
        _ = run.completeNode()
        let awaiting = run.snapshot()
        XCTAssertFalse(awaiting.finalized)
        XCTAssertTrue(awaiting.awaitingSkippedDecision)
        XCTAssertEqual(awaiting.completedNodes, 1)
        XCTAssertEqual(awaiting.skippedNodes, 1)
        XCTAssertEqual(awaiting.nodeIndex, 0)
        XCTAssertEqual(awaiting.nodeStatuses[0], .skipped)
        XCTAssertEqual(awaiting.nodeStatuses[1], .completed)

        XCTAssertTrue(run.finishAfterSkippedReview())
        let finished = run.snapshot()
        XCTAssertTrue(finished.finalized)
        XCTAssertEqual(finished.completedNodes, 1)
        XCTAssertEqual(finished.skippedNodes, 1)
        let summary = run.finishedSummary()
        XCTAssertEqual(summary?.completedNodes, 1)
        XCTAssertEqual(summary?.skippedNodes, 1)
    }

    func testSkippedReviewCanGoBackAndCompleteAllNodes() {
        let run = makeController(makePlan([makeCount(id: "c1"), makeCount(id: "c2")]))
        _ = run.startNode()
        XCTAssertTrue(run.skipCurrent()) // c1 RUNNING -> SKIPPED
        _ = run.startNode()
        _ = run.completeNode() // c2 completed, c1 still skipped -> review

        let awaiting = run.snapshot()
        XCTAssertTrue(awaiting.awaitingSkippedDecision)
        XCTAssertFalse(awaiting.finalized)

        XCTAssertTrue(run.resumeSkippedAfterReview())
        let resumed = run.snapshot()
        XCTAssertFalse(resumed.awaitingSkippedDecision)
        XCTAssertEqual(resumed.nodeIndex, 0)
        XCTAssertEqual(resumed.nodeStatuses[0], .current)
        XCTAssertEqual(resumed.nodeState, .running)
        XCTAssertEqual(resumed.skippedNodes, 0)

        XCTAssertTrue(run.completeNode())
        let finished = run.snapshot()
        XCTAssertTrue(finished.finalized)
        XCTAssertEqual(finished.completedNodes, 2)
        XCTAssertEqual(finished.skippedNodes, 0)
        XCTAssertTrue(finished.skippedNodeIndices.isEmpty)
    }

    func testLoopResetKeepsNodeCurrent() {
        let run = makeController(makePlan([makeCount(target: 3), makeTimer()]), initialLoop: true)
        _ = run.startNode()

        XCTAssertTrue(run.completeNode())
        let reset = run.snapshot()
        XCTAssertEqual(reset.nodeIndex, 0)
        XCTAssertEqual(reset.nodeState, .ready)
        XCTAssertEqual(reset.currentNodeStatus, .current)
        XCTAssertEqual(reset.completedNodes, 0)
        XCTAssertTrue(reset.loopCurrentNode)
    }

    func testSelectNodeRejectsInvalidAndCompletedTargets() {
        let run = makeController(makePlan([
            makeCount(id: "c1"),
            makeCount(id: "c2"),
            makeCount(id: "c3")
        ]))
        XCTAssertFalse(run.selectNode(0))
        XCTAssertFalse(run.selectNode(-1))
        XCTAssertFalse(run.selectNode(99))

        XCTAssertTrue(run.skipCurrent()) // c1 skipped, c2 current
        XCTAssertTrue(run.selectNode(0)) // skipped is selectable again
        XCTAssertEqual(run.snapshot().nodeIndex, 0)
        _ = run.startNode()
        _ = run.completeNode() // c1 completed, c2 current
        XCTAssertEqual(run.snapshot().nodeIndex, 1)
        XCTAssertFalse(run.selectNode(0)) // completed
        XCTAssertFalse(run.selectNode(1)) // current
    }

    func testSwitchingResetsLoopSettingForTheTargetNode() {
        let run = makeController(makePlan([makeTimer(ms: 10_000), makeCount()]), initialLoop: true)
        _ = run.startNode()
        XCTAssertTrue(run.snapshot().loopCurrentNode)
        XCTAssertTrue(run.selectNode(1))
        XCTAssertFalse(run.snapshot().loopCurrentNode)
    }

    func testExitSummaryCarriesSkippedCount() {
        let run = makeController(makePlan([makeCount(id: "c1"), makeCount(id: "c2")]))
        XCTAssertTrue(run.skipCurrent())
        let summary = run.exit()
        XCTAssertEqual(summary?.skippedNodes, 1)
        XCTAssertEqual(summary?.completedNodes, 0)
        XCTAssertEqual(summary?.totalNodes, 2)
        XCTAssertTrue(summary?.completedNormally == false)
    }
}
