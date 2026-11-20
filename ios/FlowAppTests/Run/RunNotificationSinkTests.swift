import XCTest
@testable import Flow

@MainActor
final class RunNotificationSinkTests: XCTestCase {
    private let scheduler = FakeTimerScheduler()
    private lazy var sink = UserNotificationRunSink(scheduler: scheduler)

    private func snapshot(
        state: NodeRunState,
        remaining: Int64?,
        nodeName: String = "阅读",
        runId: String = "run-1"
    ) -> PlanRunSnapshot {
        let step = RoutineStep(
            id: "t1",
            name: nodeName,
            type: .timer,
            durationMs: 120_000,
            category: .normal,
            repeatCount: 1
        )
        let plan = Routine(
            id: "plan-1",
            name: "测试计划",
            version: 1,
            repeatCount: 1,
            steps: [step],
            createdAt: 1,
            updatedAt: 2
        )
        return PlanRunSnapshot(
            runId: runId,
            plan: plan,
            nodeIndex: 0,
            nodeState: state,
            loopCurrentNode: false,
            completedNodes: 0,
            remainingMs: remaining,
            timerRunId: 1,
            runStartedAtEpochMs: 0,
            finalized: false
        )
    }

    func testTimerStartSchedulesLocalNotification() {
        sink.onTimerStarted(snapshot(state: .running, remaining: 2_500))
        XCTAssertEqual(scheduler.scheduled, [
            .init(runId: "run-1", nodeName: "阅读", seconds: 3)
        ])
    }

    func testScheduleRoundsUpSeconds() {
        sink.onTimerStarted(snapshot(state: .running, remaining: 1_000))
        sink.onTimerStarted(snapshot(state: .running, remaining: 1_001))
        sink.onTimerStarted(snapshot(state: .running, remaining: 59_500))
        XCTAssertEqual(scheduler.scheduled.map(\.seconds), [1, 2, 60])
    }

    func testPauseCancelsScheduledNotification() {
        sink.onTimerPaused(snapshot(state: .paused, remaining: 40_000))
        XCTAssertEqual(scheduler.cancels, ["run-1"])
        XCTAssertTrue(scheduler.scheduled.isEmpty)
    }

    func testResumeReschedulesWithRemainingTime() {
        sink.onTimerResumed(snapshot(state: .running, remaining: 40_000))
        XCTAssertEqual(scheduler.scheduled, [
            .init(runId: "run-1", nodeName: "阅读", seconds: 40)
        ])
    }

    func testCompleteCancelsNotification() {
        sink.onTimerCompleted(snapshot(state: .running, remaining: 10_000))
        XCTAssertEqual(scheduler.cancels, ["run-1"])
    }

    func testExpiryCancelsPendingNotification() {
        sink.onTimerExpired(snapshot(state: .expired, remaining: 0))
        XCTAssertEqual(scheduler.cancels, ["run-1"])
    }

    func testExitCancelsNotificationForRun() {
        let summary = RunSummary(
            runId: "run-exit",
            planName: "P",
            completedNodes: 1,
            totalNodes: 3,
            elapsedMs: 1_000,
            completedNormally: false
        )
        sink.onRunExited(summary)
        XCTAssertEqual(scheduler.cancels, ["run-exit"])
    }

    func testFinishCancelsNotificationForRun() {
        let summary = RunSummary(
            runId: "run-done",
            planName: "P",
            completedNodes: 2,
            totalNodes: 2,
            elapsedMs: 1_000,
            completedNormally: true
        )
        sink.onRunFinished(summary)
        XCTAssertEqual(scheduler.cancels, ["run-done"])
    }

    func testRunStartRequestsAuthorizationAndClearsStale() {
        sink.onRunStarted(snapshot(state: .ready, remaining: 120_000))
        XCTAssertEqual(scheduler.authorizationRequests, 1)
        XCTAssertEqual(scheduler.clearCount, 1)
    }

    func testProgressDoesNotScheduleLocalNotification() {
        sink.onProgress(snapshot(state: .running, remaining: 50_000))
        XCTAssertTrue(scheduler.scheduled.isEmpty)
    }

    func testReadyStateIsNotScheduled() {
        sink.onTimerStarted(snapshot(state: .ready, remaining: 120_000))
        XCTAssertTrue(scheduler.scheduled.isEmpty)
    }

    func testClearStaleNotificationsDelegates() {
        sink.clearStaleNotifications()
        XCTAssertEqual(scheduler.clearCount, 1)
    }
}

private final class FakeTimerScheduler: LocalTimerNotificationScheduler {
    struct Scheduled: Equatable {
        let runId: String
        let nodeName: String
        let seconds: Int
    }

    var scheduled: [Scheduled] = []
    var cancels: [String] = []
    var clearCount = 0
    var authorizationRequests = 0

    func requestAuthorization() {
        authorizationRequests += 1
    }

    func schedule(runId: String, nodeName: String, seconds: Int) {
        scheduled.append(Scheduled(runId: runId, nodeName: nodeName, seconds: seconds))
    }

    func cancel(runId: String) {
        cancels.append(runId)
    }

    func clearStale() {
        clearCount += 1
    }
}
