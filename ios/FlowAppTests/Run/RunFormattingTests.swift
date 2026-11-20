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
}
