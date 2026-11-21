import XCTest
@testable import Flow

@MainActor
final class RunCoordinatorTests: XCTestCase {
    private let clock = FakeRunClock()
    private let wall = FakeRunWallClock()
    private let notifications = RecordingNotifications()
    private let haptics = RecordingHaptics()

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
        ms: Int64 = 10_000
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

    private func makeCoordinator() -> RunCoordinator {
        RunCoordinator(
            monotonic: clock,
            wallClock: wall,
            notifications: notifications,
            haptics: haptics
        )
    }

    private func startedSnapshot(_ result: RunStartResult) -> PlanRunSnapshot? {
        guard case let .started(snapshot) = result else {
            XCTFail("Expected start success but was \(result)")
            return nil
        }
        return snapshot
    }

    func testStaleResourcesAreClearedWhenCoordinatorIsCreated() {
        _ = makeCoordinator()
        XCTAssertEqual(notifications.staleClears, 1)
    }

    func testStartRejectsSecondRun() {
        let coordinator = makeCoordinator()
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeCount()]), initialLoop: false))
        XCTAssertTrue(coordinator.isActive)

        let second = coordinator.start(
            plan: makePlan([makeCount(id: "other", name: "别的")]),
            initialLoop: false
        )
        XCTAssertEqual(second, .rejected(.alreadyRunning))
    }

    func testNewRunDefaultsToLoopOff() {
        let coordinator = makeCoordinator()
        let snapshot = startedSnapshot(
            coordinator.start(plan: makePlan([makeCount()]))
        )
        XCTAssertEqual(snapshot?.loopCurrentNode, false)
        XCTAssertEqual(coordinator.currentSnapshot?.loopCurrentNode, false)
    }

    func testEmptyPlanCannotStart() {
        let coordinator = makeCoordinator()
        let result = coordinator.start(plan: makePlan([]), initialLoop: false)
        XCTAssertEqual(result, .rejected(.emptyPlan))
        XCTAssertEqual(coordinator.runtime, .idle)
    }

    func testTimerLifecycleDrivesNotificationEvents() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeTimer(ms: 10_000)]), initialLoop: false))
        XCTAssertTrue(notifications.events.contains("started"))
        XCTAssertTrue(notifications.events.contains("progress"))

        XCTAssertTrue(coordinator.startNode())
        XCTAssertTrue(notifications.events.contains("timerStarted"))

        clock.advance(2_000)
        coordinator.refresh()
        XCTAssertTrue(coordinator.pause())
        XCTAssertTrue(notifications.events.contains("timerPaused"))

        XCTAssertTrue(coordinator.resume())
        XCTAssertTrue(notifications.events.contains("timerResumed"))

        XCTAssertTrue(coordinator.completeNode())
        XCTAssertTrue(notifications.events.contains("timerCompleted"))
        XCTAssertTrue(notifications.events.contains("finished"))
        if case .finished = coordinator.runtime {
            // expected
        } else {
            XCTFail("Expected finished runtime")
        }
    }

    func testExpiryEmitsSingleAlertAndHapticsAndDoesNotComplete() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeTimer(ms: 5_000)]), initialLoop: false))
        coordinator.startNode()
        clock.advance(5_000)

        XCTAssertTrue(coordinator.refresh())
        guard case let .active(snapshot) = coordinator.runtime else {
            return XCTFail("Expected active runtime")
        }
        XCTAssertEqual(snapshot.nodeState, .expired)
        XCTAssertEqual(snapshot.completedNodes, 0)
        XCTAssertEqual(notifications.expired, 1)
        XCTAssertEqual(haptics.expiries, 1)

        coordinator.refresh()
        coordinator.refresh()
        XCTAssertEqual(notifications.expired, 1)
        XCTAssertEqual(haptics.expiries, 1)
        XCTAssertEqual(coordinator.currentSnapshot?.completedNodes, 0)
    }

    func testWarningHapticFiresOnce() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeTimer(ms: 10_000)]), initialLoop: false))
        coordinator.startNode()
        clock.advance(7_000)
        coordinator.refresh()
        coordinator.refresh()
        XCTAssertEqual(haptics.warnings, 1)
        XCTAssertEqual(haptics.expiries, 0)

        clock.advance(3_000)
        coordinator.refresh()
        XCTAssertEqual(haptics.warnings, 1)
        XCTAssertEqual(haptics.expiries, 1)
    }

    func testSceneBecameActiveRecomputesExpiry() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeTimer(ms: 2_000)]), initialLoop: false))
        coordinator.startNode()
        clock.advance(5_000)

        coordinator.onSceneBecameActive()
        guard case let .active(snapshot) = coordinator.runtime else {
            return XCTFail("Expected active runtime")
        }
        XCTAssertEqual(snapshot.nodeState, .expired)
        XCTAssertEqual(notifications.expired, 1)
    }

    func testExitClearsRuntimeAndReportsProgress() {
        let coordinator = makeCoordinator()
        _ = startedSnapshot(coordinator.start(
            plan: makePlan([makeCount(), makeCount(id: "c2", name: "深蹲"), makeTimer()]),
            initialLoop: false
        ))
        coordinator.startNode()
        coordinator.completeNode()
        coordinator.startNode()

        let summary = coordinator.exitCurrent()
        XCTAssertEqual(summary?.completedNodes, 1)
        XCTAssertEqual(summary?.totalNodes, 3)
        XCTAssertEqual(summary?.completedNormally, false)
        XCTAssertEqual(coordinator.runtime, .idle)
        XCTAssertEqual(coordinator.lastExitSummary, summary)
        XCTAssertFalse(coordinator.isActive)
        XCTAssertTrue(notifications.events.contains("exited"))
        XCTAssertNil(coordinator.exitCurrent())
    }

    func testFinishedResultCanBeDismissedAndAnotherPlanStarted() {
        let coordinator = makeCoordinator()
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeCount(id: "a", name: "A")]), initialLoop: false))
        coordinator.startNode()
        coordinator.completeNode()
        if case .finished = coordinator.runtime {
            // expected
        } else {
            XCTFail("Expected finished runtime")
        }

        XCTAssertEqual(
            coordinator.start(plan: makePlan([makeCount(id: "b", name: "B")]), initialLoop: false),
            .rejected(.alreadyRunning)
        )

        coordinator.dismissResult()
        XCTAssertEqual(coordinator.runtime, .idle)
        let started = startedSnapshot(
            coordinator.start(plan: makePlan([makeCount(id: "b", name: "B")]), initialLoop: false)
        )
        XCTAssertEqual(started?.node?.name, "B")
    }

    func testLoopCompletionKeepsRunActiveForLastNode() {
        let coordinator = makeCoordinator()
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeCount()]), initialLoop: true))
        coordinator.startNode()
        coordinator.completeNode()

        guard case let .active(snapshot) = coordinator.runtime else {
            return XCTFail("Expected active runtime")
        }
        XCTAssertEqual(snapshot.nodeState, .ready)
        XCTAssertEqual(snapshot.completedNodes, 0)
        XCTAssertFalse(snapshot.finalized)
        XCTAssertFalse(notifications.events.contains("finished"))

        XCTAssertTrue(coordinator.setLoop(false))
        coordinator.startNode()
        coordinator.completeNode()
        if case .finished = coordinator.runtime {
            // expected
        } else {
            XCTFail("Expected finished runtime")
        }
    }

    func testLoopToggleDoesNotAdvanceOrCount() {
        let coordinator = makeCoordinator()
        _ = startedSnapshot(
            coordinator.start(plan: makePlan([makeCount(), makeCount(id: "c2", name: "深蹲")]), initialLoop: false)
        )
        coordinator.startNode()
        XCTAssertTrue(coordinator.setLoop(true))
        guard case let .active(snapshot) = coordinator.runtime else {
            return XCTFail("Expected active runtime")
        }
        XCTAssertEqual(snapshot.nodeIndex, 0)
        XCTAssertEqual(snapshot.completedNodes, 0)
        XCTAssertEqual(snapshot.nodeState, .running)
    }

    func testRunStateIsNotPersistedToPlanStore() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flow-run-\(UUID().uuidString).json")
        let store = PlanStore(fileURL: url)
        let plan = makePlan([makeCount(), makeTimer()])
        try store.save(plan)
        let before = try String(contentsOf: url, encoding: .utf8)

        let coordinator = makeCoordinator()
        let snapshot = startedSnapshot(coordinator.start(plan: plan, initialLoop: false))
        coordinator.startNode()
        coordinator.completeNode()
        coordinator.onSceneBecameActive()

        let after = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(before, after)
        XCTAssertFalse(after.contains(snapshot?.runId ?? "missing"))
        XCTAssertEqual(try store.loadAll(), [plan])
    }

    func testProcessRestartHasNoOldRun() {
        let first = makeCoordinator()
        _ = startedSnapshot(first.start(plan: makePlan([makeCount()]), initialLoop: false))
        first.startNode()

        let second = makeCoordinator()
        XCTAssertEqual(second.runtime, .idle)
        XCTAssertFalse(second.isActive)
        XCTAssertNil(second.currentSnapshot)
        XCTAssertNil(second.exitCurrent())
    }

    func testTimerPausedThenCompletedAdvances() {
        let coordinator = makeCoordinator()
        _ = startedSnapshot(coordinator.start(
            plan: makePlan([makeTimer(ms: 30_000), makeCount(id: "c2", name: "深蹲")]),
            initialLoop: false
        ))
        coordinator.startNode()
        clock.advance(5_000)
        coordinator.pause()
        XCTAssertEqual(coordinator.currentSnapshot?.nodeState, .paused)

        XCTAssertTrue(coordinator.completeNode())
        XCTAssertEqual(coordinator.currentSnapshot?.nodeIndex, 1)
        XCTAssertEqual(coordinator.currentSnapshot?.completedNodes, 1)
        XCTAssertTrue(notifications.events.contains("timerCompleted"))
    }

    func testRunWorksWithoutNotificationAuthorization() {
        // Noop sink models denied notification permissions; the timer still runs.
        let coordinator = RunCoordinator(monotonic: clock, wallClock: wall)
        clock.now = 0
        XCTAssertNotNil(startedSnapshot(
            coordinator.start(plan: makePlan([makeTimer(ms: 2_000)]), initialLoop: false)
        ))
        coordinator.startNode()
        clock.advance(2_000)
        coordinator.refresh()
        XCTAssertEqual(coordinator.currentSnapshot?.nodeState, .expired)
        coordinator.completeNode()
        if case .finished = coordinator.runtime {
            // expected
        } else {
            XCTFail("Expected finished runtime")
        }
    }

    func testSelectNodeDefersPreviousAndCancelsTimerNotification() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([
            makeTimer(ms: 30_000),
            makeCount(id: "c2", name: "深蹲")
        ])))
        XCTAssertTrue(coordinator.startNode())
        clock.advance(10_000)
        let pausedBefore = notifications.events.filter { $0 == "timerPaused" }.count

        XCTAssertTrue(coordinator.selectNode(1))
        let snapshot = coordinator.currentSnapshot
        XCTAssertEqual(snapshot?.nodeIndex, 1)
        XCTAssertEqual(snapshot?.nodeStatuses[0], .deferred)
        XCTAssertEqual(snapshot?.nodeState, .ready)
        XCTAssertEqual(snapshot?.completedNodes, 0)
        XCTAssertEqual(notifications.events.filter { $0 == "timerPaused" }.count, pausedBefore + 1)
    }

    func testSelectNodeRestoresDeferredTimerProgress() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([
            makeTimer(ms: 30_000),
            makeCount(id: "c2", name: "深蹲")
        ])))
        XCTAssertTrue(coordinator.startNode())
        clock.advance(10_000)
        XCTAssertTrue(coordinator.selectNode(1))
        clock.advance(60_000)
        XCTAssertTrue(coordinator.selectNode(0))

        let restored = coordinator.currentSnapshot
        XCTAssertEqual(restored?.nodeState, .paused)
        XCTAssertEqual(restored?.remainingMs, 20_000)
        XCTAssertTrue(coordinator.resume())
    }

    func testExpiryFeedbackTokenIsOneShotAndConsumable() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeTimer(ms: 2_000)])))
        XCTAssertTrue(coordinator.startNode())
        clock.advance(2_000)
        XCTAssertTrue(coordinator.refresh())

        guard let token = coordinator.expiryFeedbackToken else {
            XCTFail("Expected an expiry feedback token")
            return
        }
        XCTAssertEqual(notifications.expired, 1)

        // Further refreshes must not produce another UI feedback token.
        coordinator.refresh()
        XCTAssertEqual(coordinator.expiryFeedbackToken, token)

        coordinator.consumeExpiryFeedback(token)
        XCTAssertNil(coordinator.expiryFeedbackToken)
        coordinator.refresh()
        XCTAssertNil(coordinator.expiryFeedbackToken)
        XCTAssertEqual(notifications.expired, 1)
    }

    func testExpiryFeedbackTokenResetsWhenRunEnds() {
        let coordinator = makeCoordinator()
        clock.now = 0
        _ = startedSnapshot(coordinator.start(plan: makePlan([makeTimer(ms: 2_000)])))
        XCTAssertTrue(coordinator.startNode())
        clock.advance(2_000)
        coordinator.refresh()
        XCTAssertNotNil(coordinator.expiryFeedbackToken)
        XCTAssertTrue(coordinator.completeNode())
        XCTAssertNil(coordinator.expiryFeedbackToken)
    }

    private final class RecordingNotifications: RunNotificationSink {
        var events: [String] = []
        var expired = 0
        var staleClears = 0

        func onRunStarted(_ snapshot: PlanRunSnapshot) {
            events.append("started")
        }

        func onProgress(_ snapshot: PlanRunSnapshot) {
            events.append("progress")
        }

        func onTimerStarted(_ snapshot: PlanRunSnapshot) {
            events.append("timerStarted")
        }

        func onTimerPaused(_ snapshot: PlanRunSnapshot) {
            events.append("timerPaused")
        }

        func onTimerResumed(_ snapshot: PlanRunSnapshot) {
            events.append("timerResumed")
        }

        func onTimerCompleted(_ snapshot: PlanRunSnapshot) {
            events.append("timerCompleted")
        }

        func onTimerExpired(_ snapshot: PlanRunSnapshot) {
            events.append("expired")
            expired += 1
        }

        func onRunFinished(_ summary: RunSummary) {
            events.append("finished")
        }

        func onRunExited(_ summary: RunSummary) {
            events.append("exited")
        }

        func clearStaleNotifications() {
            staleClears += 1
        }
    }

    private final class RecordingHaptics: RunHaptics {
        var warnings = 0
        var expiries = 0

        func timerWarning() {
            warnings += 1
        }

        func timerExpired() {
            expiries += 1
        }
    }
}
