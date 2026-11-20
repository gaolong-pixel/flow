import XCTest
@testable import Flow

final class RunFormattingTests: XCTestCase {
    func testCountdownRoundsUpAndFormatsMinutes() {
        XCTAssertEqual(formatRunCountdown(0), "00:00")
        XCTAssertEqual(formatRunCountdown(1), "00:01")
        XCTAssertEqual(formatRunCountdown(999), "00:01")
        XCTAssertEqual(formatRunCountdown(45_000), "00:45")
        XCTAssertEqual(formatRunCountdown(59_500), "01:00")
        XCTAssertEqual(formatRunCountdown(600_000), "10:00")
        XCTAssertEqual(formatRunCountdown(nil), "00:00")
    }

    func testCountdownSupportsHours() {
        XCTAssertEqual(formatRunCountdown(3_600_000), "1:00:00")
        XCTAssertEqual(formatRunCountdown(3_909_000), "1:05:09")
    }

    func testElapsedIsHumanReadable() {
        XCTAssertEqual(formatRunElapsed(5_000), "5 秒")
        XCTAssertEqual(formatRunElapsed(65_000), "1 分 5 秒")
        XCTAssertEqual(formatRunElapsed(7_200_000), "2 小时 0 分")
    }

    func testDurationAndTargetLabelsMatchPlanModel() {
        let timer = RoutineStep(
            id: "t",
            name: "阅读",
            type: .timer,
            durationMs: 120_000,
            category: .normal
        )
        XCTAssertEqual(formatRunNodeTarget(timer), "计时 2 分钟")

        let count = RoutineStep(
            id: "c",
            name: "俯卧撑",
            type: .count,
            target: 15,
            countMode: .complete
        )
        XCTAssertEqual(formatRunNodeTarget(count), "目标 15 次")
        XCTAssertEqual(runNodeStateLabel(.ready), "待开始")
        XCTAssertEqual(runNodeStateLabel(.expired), "已到期")
    }

    func testOverallProgressUsesCompletedNodesOnly() {
        XCTAssertEqual(flowOverallProgress(completed: 0, total: 5), 0, accuracy: 0.0001)
        XCTAssertEqual(flowOverallProgress(completed: 2, total: 4), 0.5, accuracy: 0.0001)
        XCTAssertEqual(flowOverallProgress(completed: 4, total: 4), 1, accuracy: 0.0001)
        XCTAssertEqual(flowOverallProgress(completed: 0, total: 0), 0, accuracy: 0.0001)
        XCTAssertEqual(flowOverallPercent(completed: 2, total: 5), 40)
    }

    func testTimerRemainingFractionIsClampedForDrawing() {
        XCTAssertEqual(flowTimerRemainingFraction(totalMs: 120_000, remainingMs: 120_000), 1, accuracy: 0.0001)
        XCTAssertEqual(flowTimerRemainingFraction(totalMs: 120_000, remainingMs: 60_000), 0.5, accuracy: 0.0001)
        XCTAssertEqual(flowTimerRemainingFraction(totalMs: 120_000, remainingMs: 0), 0, accuracy: 0.0001)
        XCTAssertEqual(flowTimerRemainingFraction(totalMs: 120_000, remainingMs: 180_000), 1, accuracy: 0.0001)
        XCTAssertEqual(flowTimerRemainingFraction(totalMs: nil, remainingMs: 10_000), 0, accuracy: 0.0001)
    }

    func testPlanDurationLabelNeverInventsAnEstimate() {
        let count = RoutineStep(
            id: "c",
            name: "俯卧撑",
            type: .count,
            target: 10,
            countMode: .complete
        )
        let timer = RoutineStep(
            id: "t",
            name: "阅读",
            type: .timer,
            durationMs: 300_000,
            category: .normal
        )
        XCTAssertNil(flowPlanDurationLabel(steps: [count]))
        XCTAssertNil(flowPlanDurationLabel(steps: []))
        XCTAssertEqual(flowPlanDurationLabel(steps: [timer]), "预计 5 分钟")
        XCTAssertEqual(flowPlanDurationLabel(steps: [count, timer]), "计时 5 分钟")
        XCTAssertEqual(flowPlanTimerTotalMs(steps: [count, timer]), 300_000)
    }

    func testGiantCountdownFontShrinksForLongValues() {
        XCTAssertGreaterThan(flowTimerNumberSize(characters: 5), flowTimerNumberSize(characters: 7))
        XCTAssertGreaterThanOrEqual(flowTimerNumberSize(characters: 7), 38)
    }
}
