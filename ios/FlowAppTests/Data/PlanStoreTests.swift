import XCTest
@testable import Flow

final class PlanStoreTests: XCTestCase {
    func testRoundTripKeepsOnlyPlanFields() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flow-plans-\(UUID().uuidString).json")
        let store = PlanStore(fileURL: url)
        let plan = Routine(id: "p", name: "P", repeatCount: 7, steps: [
            RoutineStep(id: "c", name: "Push", type: .count, target: 15,
                        countMode: .increment, repeatCount: 9),
            RoutineStep(id: "t", name: "Rest", type: .timer, durationMs: 60_000,
                        category: .rest, repeatCount: 5)
        ], createdAt: 1, updatedAt: 2)
        try store.save(plan)
        let loadedPlans = try store.loadAll()
        XCTAssertEqual(loadedPlans.count, 1)
        let loaded = loadedPlans[0]
        XCTAssertEqual(loaded.repeatCount, 1)
        XCTAssertEqual(loaded.steps[0].target, 15)
        XCTAssertEqual(loaded.steps[0].countMode, CountMode.complete)
        XCTAssertEqual(loaded.steps[0].repeatCount, 1)
        XCTAssertEqual(loaded.steps[1].category, TimerCategory.normal)
        XCTAssertEqual(loaded.steps[1].repeatCount, 1)
    }

    func testRenameUpdatesSamePlanWithoutChangingIDsOrNodes() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flow-plans-\(UUID().uuidString).json")
        let store = PlanStore(fileURL: url)
        let plan = Routine(id: "p", name: "Old name", steps: [
            RoutineStep(id: "c", name: "Push", type: .count, target: 15,
                        countMode: .complete),
            RoutineStep(id: "t", name: "Rest", type: .timer, durationMs: 60_000,
                        category: .normal)
        ], createdAt: 1, updatedAt: 2)
        try store.save(plan)
        let renamed = Routine(
            id: plan.id,
            name: "New name",
            version: plan.version,
            repeatCount: plan.repeatCount,
            steps: plan.steps,
            createdAt: plan.createdAt,
            updatedAt: 3
        )
        try store.save(renamed)

        let loaded = try store.loadAll()
        XCTAssertEqual(loaded.count, 1)
        XCTAssertEqual(loaded[0].name, "New name")
        XCTAssertEqual(loaded[0].steps.map(\.id), ["c", "t"])
        XCTAssertEqual(loaded[0].steps[0].target, 15)
        XCTAssertEqual(loaded[0].steps[1].durationMs, 60_000)
    }

    func testEmptyPlanIsRejectedAndDoesNotCreateAFile() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flow-plans-\(UUID().uuidString).json")
        let store = PlanStore(fileURL: url)
        let empty = Routine(id: "empty", name: "Empty", steps: [], createdAt: 1, updatedAt: 1)
        XCTAssertThrowsError(try store.save(empty))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(try store.loadAll().isEmpty)
    }

    func testEmptyPlanCannotOverwriteAnExistingPlan() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flow-plans-\(UUID().uuidString).json")
        let store = PlanStore(fileURL: url)
        let valid = Routine(id: "p", name: "Valid", steps: [
            RoutineStep(id: "c", name: "Push", type: .count, target: 3, countMode: .complete)
        ], createdAt: 1, updatedAt: 1)
        try store.save(valid)
        let empty = Routine(id: "p", name: "Valid", steps: [], createdAt: 1, updatedAt: 2)
        XCTAssertThrowsError(try store.save(empty))
        let loadedPlans = try store.loadAll()
        XCTAssertEqual(loadedPlans.count, 1)
        let loaded = loadedPlans[0]
        XCTAssertEqual(loaded.steps.count, 1)
        XCTAssertEqual(loaded.steps[0].name, "Push")
    }

    func testCorruptFileIsRejectedAndUnchanged() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("flow-plans-\(UUID().uuidString).json")
        try "{bad".write(to: url, atomically: true, encoding: .utf8)
        XCTAssertThrowsError(try PlanStore(fileURL: url).loadAll())
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), "{bad")
    }
}
