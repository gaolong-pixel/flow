import XCTest
@testable import Flow

final class RoutineDomainTests: XCTestCase {

    private let countA = RoutineStep.count(id: "a", name: "Push-ups", target: 15)
    private let timerB = RoutineStep.timer(id: "b", name: "Read", durationMs: 60_000)
    private let countC = RoutineStep.count(id: "c", name: "Squat", target: 20)

    private func makeDraft(_ steps: RoutineStep...) throws -> RoutineDraft {
        try RoutineDraft.new(
            name: "Morning",
            repeatCount: 2,
            steps: steps,
            id: "draft-1",
            now: 1_000
        )
    }

    func testCreatesCountStepWithIncrementDefault() {
        let step = RoutineStep.count(id: "count-1", name: "Push-ups", target: 15)

        XCTAssertEqual(step.id, "count-1")
        XCTAssertEqual(step.type, .count)
        XCTAssertEqual(step.target, 15)
        XCTAssertEqual(step.countMode, .increment)
        XCTAssertNil(step.durationMs)
        XCTAssertNil(step.category)
        XCTAssertEqual(step.repeatCount, 1)
        XCTAssertTrue(step.isValid)
    }

    func testCreatesTimerStep() {
        let step = RoutineStep.timer(
            id: "timer-1",
            name: "Read",
            durationMs: 60_000,
            category: .normal
        )

        XCTAssertEqual(step.type, .timer)
        XCTAssertEqual(step.durationMs, 60_000)
        XCTAssertEqual(step.category, .normal)
        XCTAssertNil(step.target)
        XCTAssertNil(step.countMode)
        XCTAssertTrue(step.isValid)
    }

    func testCreatesRestTimerStep() {
        let step = RoutineStep.timer(
            id: "rest-1",
            name: "Rest",
            durationMs: 30_000,
            category: .rest
        )

        XCTAssertEqual(step.category, .rest)
        XCTAssertTrue(step.isValid)
    }

    func testRejectsInvalidTimerDurations() {
        for duration in [0, 999, 1_500, 2_500] as [Int64] {
            let step = RoutineStep.timer(
                id: "timer-\(duration)",
                name: "Timer",
                durationMs: duration
            )
            XCTAssertTrue(step.validationErrors.contains(.invalidTimerDuration))
            XCTAssertFalse(step.isValid)
        }

        XCTAssertTrue(RoutineStep.timer(id: "ok", name: "Timer", durationMs: 1_000).isValid)
    }

    func testRejectsInvalidRepeatCounts() throws {
        let step = RoutineStep.count(
            id: "count-repeat",
            name: "Squat",
            target: 10,
            repeatCount: 0
        )
        XCTAssertTrue(step.validationErrors.contains(.invalidStepRepeatCount))

        XCTAssertThrowsError(try RoutineDraft.new(repeatCount: 0, now: 1_000)) { error in
            guard case DraftError.invalidDraft(let errors) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertTrue(errors.contains(.invalidRoutineRepeatCount))
        }

        XCTAssertThrowsError(try makeDraft(countA).settingRepeatCount(0)) { error in
            XCTAssertEqual(error as? DraftError, .invalidRepeatCount(0))
        }

        let routine = Routine(
            id: "r",
            name: "Bad",
            repeatCount: 0,
            steps: [],
            createdAt: 0,
            updatedAt: 0
        )
        XCTAssertTrue(routine.validationErrors.contains(.invalidRoutineRepeatCount))
    }

    func testRejectsDuplicateStepIDs() throws {
        let duplicate = RoutineDraft(
            id: "duplicate-draft",
            name: "Duplicate",
            steps: [countA, RoutineStep.count(id: "a", name: "Duplicate", target: 1)],
            createdAt: 0,
            updatedAt: 0
        )
        XCTAssertTrue(duplicate.validationErrors.contains(.duplicateStepID))

        let duplicateRoutine = Routine(
            id: "duplicate-routine",
            name: "Duplicate",
            steps: [countA, RoutineStep.count(id: "a", name: "Duplicate", target: 1)],
            createdAt: 0,
            updatedAt: 0
        )
        XCTAssertTrue(duplicateRoutine.validationErrors.contains(.duplicateStepID))

        XCTAssertThrowsError(try makeDraft(countA).addingStep(countA)) { error in
            XCTAssertEqual(error as? DraftError, .duplicateStepID("a"))
        }
    }

    func testEnforcesCountAndTimerFieldExclusivity() {
        let countWithTimerFields = RoutineStep(
            id: "bad-count",
            name: "Push-ups",
            type: .count,
            target: 10,
            countMode: .increment,
            durationMs: 30_000,
            category: .normal
        )
        let timerWithCountFields = RoutineStep(
            id: "bad-timer",
            name: "Read",
            type: .timer,
            target: 10,
            countMode: .increment,
            durationMs: 30_000,
            category: .normal
        )

        XCTAssertTrue(countWithTimerFields.validationErrors.contains(.countHasTimerFields))
        XCTAssertTrue(timerWithCountFields.validationErrors.contains(.timerHasCountFields))
    }

    func testAddsUpdatesDeletesAndMovesStepsUsingListOrder() throws {
        var current = try makeDraft(countA, timerB, countC)
        XCTAssertEqual(current.steps.map(\.id), ["a", "b", "c"])

        current = try current.addingStep(
            RoutineStep.count(id: "d", name: "Plank", target: 1)
        )
        XCTAssertEqual(current.steps.map(\.id), ["a", "b", "c", "d"])

        current = try current.movingStep(from: 0, to: 2)
        XCTAssertEqual(current.steps.map(\.id), ["b", "c", "a", "d"])

        current = try current.updatingStep(
            RoutineStep.count(id: "a", name: "Push-ups edited", target: 15)
        )
        XCTAssertEqual(current.steps.map(\.id), ["b", "c", "a", "d"])
        XCTAssertEqual(current.steps.first(where: { $0.id == "a" })?.name, "Push-ups edited")

        current = try current.deletingStep(id: "c")
        XCTAssertEqual(current.steps.map(\.id), ["b", "a", "d"])
    }

    func testDuplicateStepCreatesNewIDAndKeepsExistingIDs() throws {
        let original = try makeDraft(countA, timerB)
        let duplicated = try original.duplicatingStep(id: "a")

        XCTAssertEqual(original.steps.map(\.id), ["a", "b"])
        XCTAssertEqual(duplicated.steps.count, 3)
        XCTAssertEqual(duplicated.steps[0].id, "a")
        XCTAssertNotEqual(duplicated.steps[1].id, "a")
        XCTAssertEqual(duplicated.steps[2].id, "b")
        XCTAssertEqual(Set(duplicated.steps.map(\.id)).count, duplicated.steps.count)
    }

    func testChangesStepTypeWhileKeepingID() throws {
        let original = try makeDraft(countA)
        let timer = RoutineStep.timer(
            id: countA.id,
            name: countA.name,
            durationMs: 45_000,
            category: .normal
        )

        let updated = try original.updatingStep(timer)

        XCTAssertEqual(updated.steps.count, 1)
        XCTAssertEqual(updated.steps[0].id, "a")
        XCTAssertEqual(updated.steps[0].type, .timer)
        XCTAssertEqual(updated.steps[0].durationMs, 45_000)
        XCTAssertNil(updated.steps[0].target)
        XCTAssertNil(updated.steps[0].countMode)
    }

    func testUpdatingOneStepDoesNotChangeOtherIDs() throws {
        let original = try makeDraft(countA, timerB, countC)
        let updated = try original.updatingStep(
            RoutineStep.timer(id: "b", name: "Read edited", durationMs: 60_000)
        )

        XCTAssertEqual(updated.steps.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(updated.steps.first(where: { $0.id == "b" })?.name, "Read edited")
    }

    func testCreatesDraftFromRoutineAndPreservesIdentityAndFields() throws {
        let routine = Routine(
            id: "routine-1",
            name: "Morning",
            version: 1,
            repeatCount: 3,
            steps: [countA, timerB],
            createdAt: 10,
            updatedAt: 20
        )

        let draft = try RoutineDraft.from(routine)

        XCTAssertEqual(draft.id, routine.id)
        XCTAssertEqual(draft.name, routine.name)
        XCTAssertEqual(draft.version, routine.version)
        XCTAssertEqual(draft.repeatCount, routine.repeatCount)
        XCTAssertEqual(draft.steps.map(\.id), routine.steps.map(\.id))
        XCTAssertEqual(draft.createdAt, routine.createdAt)
        XCTAssertEqual(draft.updatedAt, routine.updatedAt)
        XCTAssertEqual(try draft.toRoutine(), routine)
    }

    func testNewDraftUsesSchemaVersionAndStableStepIDs() throws {
        let created = try RoutineDraft.new(
            name: "New routine",
            steps: [countA, timerB],
            id: "new-draft",
            now: 5_000
        )

        XCTAssertEqual(created.version, Routine.currentSchemaVersion)
        XCTAssertEqual(created.id, "new-draft")
        XCTAssertEqual(created.steps.map(\.id), ["a", "b"])
        XCTAssertEqual(created.createdAt, 5_000)
        XCTAssertEqual(created.updatedAt, 5_000)
    }

    func testRejectsInvalidStepBeforeItEntersDraft() throws {
        let invalid = RoutineStep.timer(
            id: "invalid",
            name: "Invalid",
            durationMs: 1_500
        )

        XCTAssertThrowsError(try makeDraft(countA).addingStep(invalid)) { error in
            guard case DraftError.invalidStep(let errors) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertTrue(errors.contains(.invalidTimerDuration))
        }

        XCTAssertThrowsError(try RoutineDraft.new(steps: [invalid], now: 1_000)) { error in
            guard case DraftError.invalidDraft(let errors) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertTrue(errors.contains(.invalidTimerDuration))
        }
    }

    func testRejectsInvalidMovesAndMissingSteps() throws {
        let current = try makeDraft(countA, timerB)

        XCTAssertThrowsError(try current.movingStep(from: 0, to: 5)) { error in
            XCTAssertEqual(error as? DraftError, .invalidMove(0, 5))
        }
        XCTAssertThrowsError(try current.deletingStep(id: "missing")) { error in
            XCTAssertEqual(error as? DraftError, .stepNotFound("missing"))
        }
        XCTAssertThrowsError(try current.updatingStep(countC)) { error in
            XCTAssertEqual(error as? DraftError, .stepNotFound("c"))
        }
    }

    func testUsesExplicitUnicodeWhitespaceForStepNames() {
        let blankNames = [
            "",
            " ",
            "\t",
            "\n",
            "\u{00A0}",
            "\u{2007}",
            "\u{202F}",
            "\u{3000}"
        ]

        for (index, blankName) in blankNames.enumerated() {
            let step = RoutineStep.count(
                id: "blank-\(index)",
                name: blankName,
                target: 1
            )
            XCTAssertTrue(step.validationErrors.contains(.emptyStepName))
        }

        let named = RoutineStep.count(
            id: "named",
            name: "  Push-ups  ",
            target: 1
        )
        XCTAssertTrue(named.isValid)
        XCTAssertEqual(named.name, "Push-ups")
    }

    func testTrimsStepNameAtCreationButPreservesInternalWhitespace() {
        let ordinary = RoutineStep.count(
            id: "ordinary",
            name: "  Push-ups  ",
            target: 1
        )
        XCTAssertEqual(ordinary.name, "Push-ups")

        let unicode = RoutineStep.count(
            id: "unicode",
            name: "\u{00A0}第 2  组 深蹲\u{2007}",
            target: 1
        )
        XCTAssertEqual(unicode.name, "第 2  组 深蹲")
        XCTAssertTrue(unicode.isValid)

        let direct = RoutineStep(
            id: "direct",
            name: "\u{3000}Direct\u{202F}",
            type: .count,
            target: 1,
            countMode: .increment
        )
        XCTAssertEqual(direct.name, "Direct")
        XCTAssertTrue(direct.isValid)
    }

    func testDefensiveValueSemanticsForStepCollections() throws {
        var draftSource = [countA]
        let createdDraft = try RoutineDraft.new(
            name: "Copied",
            steps: draftSource,
            id: "copied-draft",
            now: 1_000
        )
        draftSource.append(timerB)
        XCTAssertEqual(createdDraft.steps.map(\.id), ["a"])

        var routineSource = [countA]
        let routine = Routine(
            id: "routine-copied",
            name: "Copied",
            steps: routineSource,
            createdAt: 0,
            updatedAt: 0
        )
        routineSource.append(timerB)
        XCTAssertEqual(routine.steps.map(\.id), ["a"])

        let fromRoutine = try RoutineDraft.from(routine)
        routineSource.append(countC)
        XCTAssertEqual(fromRoutine.steps.map(\.id), ["a"])

        let routineFromDraft = try createdDraft.toRoutine()
        let editedDraft = try createdDraft.addingStep(timerB)
        XCTAssertEqual(routineFromDraft.steps.map(\.id), ["a"])
        XCTAssertEqual(editedDraft.steps.map(\.id), ["a", "b"])
    }

    func testStepDuplicatedHelperPreservesAllFieldsExceptID() {
        let original = RoutineStep.count(
            id: "complete",
            name: "Chin-ups",
            target: 12,
            countMode: .complete,
            repeatCount: 3
        )
        let copy = original.duplicated(newID: "copy")

        XCTAssertNotEqual(copy.id, original.id)
        XCTAssertEqual(copy.name, original.name)
        XCTAssertEqual(copy.type, original.type)
        XCTAssertEqual(copy.target, original.target)
        XCTAssertEqual(copy.countMode, original.countMode)
        XCTAssertEqual(copy.durationMs, original.durationMs)
        XCTAssertEqual(copy.category, original.category)
        XCTAssertEqual(copy.repeatCount, original.repeatCount)
    }

    func testPreservesIDsForTargetDurationAndBothTypeChanges() throws {
        var current = try makeDraft(countA, timerB, countC)

        current = try current.updatingStep(
            RoutineStep.count(id: "a", name: "Push-ups", target: 30)
        )
        XCTAssertEqual(current.steps.first(where: { $0.id == "a" })?.id, "a")
        XCTAssertEqual(current.steps.first(where: { $0.id == "a" })?.target, 30)

        current = try current.updatingStep(
            RoutineStep.timer(id: "b", name: "Read", durationMs: 90_000)
        )
        XCTAssertEqual(current.steps.first(where: { $0.id == "b" })?.id, "b")
        XCTAssertEqual(current.steps.first(where: { $0.id == "b" })?.durationMs, 90_000)

        current = try current.updatingStep(
            RoutineStep.count(id: "b", name: "Read as count", target: 5)
        )
        XCTAssertEqual(current.steps.first(where: { $0.id == "b" })?.id, "b")
        XCTAssertEqual(current.steps.first(where: { $0.id == "b" })?.type, .count)

        current = try current.updatingStep(
            RoutineStep.timer(id: "a", name: "Push-ups as timer", durationMs: 30_000)
        )
        XCTAssertEqual(current.steps.first(where: { $0.id == "a" })?.id, "a")
        XCTAssertEqual(current.steps.first(where: { $0.id == "a" })?.type, .timer)
        XCTAssertEqual(current.steps.map(\.id), ["a", "b", "c"])
    }

    func testDuplicatePreservesAllFieldsExceptID() throws {
        let complete = RoutineStep.count(
            id: "complete",
            name: "Chin-ups",
            target: 12,
            countMode: .complete,
            repeatCount: 3
        )
        let completeCopy = try makeDraft(complete)
            .duplicatingStep(id: "complete")
            .steps
            .first(where: { $0.id != "complete" })!

        XCTAssertNotEqual(completeCopy.id, "complete")
        XCTAssertEqual(completeCopy.name, complete.name)
        XCTAssertEqual(completeCopy.type, complete.type)
        XCTAssertEqual(completeCopy.target, complete.target)
        XCTAssertEqual(completeCopy.countMode, complete.countMode)
        XCTAssertEqual(completeCopy.durationMs, complete.durationMs)
        XCTAssertEqual(completeCopy.category, complete.category)
        XCTAssertEqual(completeCopy.repeatCount, complete.repeatCount)

        let rest = RoutineStep.timer(
            id: "rest",
            name: "Deep rest",
            durationMs: 45_000,
            category: .rest,
            repeatCount: 4
        )
        let restCopy = try makeDraft(rest)
            .duplicatingStep(id: "rest")
            .steps
            .first(where: { $0.id != "rest" })!

        XCTAssertNotEqual(restCopy.id, "rest")
        XCTAssertEqual(restCopy.name, rest.name)
        XCTAssertEqual(restCopy.type, rest.type)
        XCTAssertEqual(restCopy.target, rest.target)
        XCTAssertEqual(restCopy.countMode, rest.countMode)
        XCTAssertEqual(restCopy.durationMs, rest.durationMs)
        XCTAssertEqual(restCopy.category, rest.category)
        XCTAssertEqual(restCopy.repeatCount, rest.repeatCount)
    }

    func testOldDraftRemainsUnchangedAfterEveryMutation() throws {
        let original = try makeDraft(countA, timerB)
        let originalIDs = original.steps.map(\.id)

        let added = try original.addingStep(countC)
        XCTAssertEqual(original.steps.map(\.id), originalIDs)
        XCTAssertEqual(added.steps.count, 3)

        let updated = try original.updatingStep(
            RoutineStep.count(id: "a", name: "Changed", target: 15)
        )
        XCTAssertEqual(original.steps.map(\.id), originalIDs)
        XCTAssertEqual(original.steps.first(where: { $0.id == "a" })?.name, "Push-ups")
        XCTAssertEqual(updated.steps.first(where: { $0.id == "a" })?.name, "Changed")

        let deleted = try original.deletingStep(id: "a")
        XCTAssertEqual(original.steps.map(\.id), originalIDs)
        XCTAssertEqual(deleted.steps.map(\.id), ["b"])

        let moved = try original.movingStep(from: 0, to: 1)
        XCTAssertEqual(original.steps.map(\.id), originalIDs)
        XCTAssertEqual(moved.steps.map(\.id), ["b", "a"])

        let duplicated = try original.duplicatingStep(id: "a")
        XCTAssertEqual(original.steps.map(\.id), originalIDs)
        XCTAssertEqual(duplicated.steps.count, 3)
    }

    func testHandlesMoveBoundaries() throws {
        let fourth = RoutineStep.count(id: "d", name: "Plank", target: 1)
        let fourSteps = try makeDraft(countA, timerB, countC, fourth)

        let firstToLast = try fourSteps.movingStep(from: 0, to: 3)
        XCTAssertEqual(firstToLast.steps.map(\.id), ["b", "c", "d", "a"])

        let lastToFirst = try fourSteps.movingStep(from: 3, to: 0)
        XCTAssertEqual(lastToFirst.steps.map(\.id), ["d", "a", "b", "c"])

        let same = try fourSteps.movingStep(from: 1, to: 1)
        XCTAssertEqual(same.steps.map(\.id), ["a", "b", "c", "d"])

        let empty = try RoutineDraft.new(id: "empty", now: 1_000)
        XCTAssertThrowsError(try empty.movingStep(from: 0, to: 0)) { error in
            XCTAssertEqual(error as? DraftError, .invalidMove(0, 0))
        }

        let single = try makeDraft(countA)
        let singleSame = try single.movingStep(from: 0, to: 0)
        XCTAssertEqual(singleSame.steps.map(\.id), ["a"])
        XCTAssertThrowsError(try single.movingStep(from: 0, to: 1)) { error in
            XCTAssertEqual(error as? DraftError, .invalidMove(0, 1))
        }
    }

    func testTracksTimestampsAndSchemaVersion() throws {
        let created = try RoutineDraft.new(
            name: "Timed",
            id: "timed",
            now: 1_000
        )
        XCTAssertEqual(created.createdAt, 1_000)
        XCTAssertEqual(created.updatedAt, 1_000)
        XCTAssertEqual(created.version, Routine.currentSchemaVersion)

        let edited = try created
            .renaming("Timed edited")
            .addingStep(countA)
        XCTAssertEqual(edited.createdAt, 1_000)
        XCTAssertGreaterThanOrEqual(edited.updatedAt, created.updatedAt)
        XCTAssertEqual(edited.version, Routine.currentSchemaVersion)

        let routine = try edited.toRoutine()
        XCTAssertEqual(routine.createdAt, edited.createdAt)
        XCTAssertEqual(routine.updatedAt, edited.updatedAt)
        XCTAssertEqual(routine.version, edited.version)

        let loaded = Routine(
            id: "loaded",
            name: "Loaded",
            version: 7,
            steps: [countA],
            createdAt: 10,
            updatedAt: 20
        )
        let fromLoaded = try RoutineDraft.from(loaded)
        XCTAssertEqual(fromLoaded.version, 7)
        XCTAssertEqual(fromLoaded.createdAt, 10)
        XCTAssertEqual(fromLoaded.updatedAt, 20)

        let loadedEdited = fromLoaded.renaming("Loaded edited")
        XCTAssertEqual(loadedEdited.version, 7)
        XCTAssertEqual(loadedEdited.createdAt, 10)
        XCTAssertGreaterThanOrEqual(loadedEdited.updatedAt, fromLoaded.updatedAt)
    }


    func testDraftTrimsStepNameOnAddAndUpdate() throws {
        let original = try makeDraft(countA)

        let added = try original.addingStep(
            RoutineStep.count(id: "d", name: "\u{00A0}Plank\u{2007}", target: 1)
        )
        XCTAssertEqual(added.steps.last?.name, "Plank")

        let updated = try original.updatingStep(
            RoutineStep.count(id: "a", name: "  Push  ups edited  ", target: 15)
        )
        XCTAssertEqual(updated.steps.first?.name, "Push  ups edited")

        let internalWhitespace = try original.updatingStep(
            RoutineStep.count(id: "a", name: "\u{3000}Push  ups\u{202F}", target: 15)
        )
        XCTAssertEqual(internalWhitespace.steps.first?.name, "Push  ups")

        XCTAssertEqual(original.steps.first?.name, "Push-ups")
    }

    func testDraftRejectsStepNameThatBecomesBlankAfterTrim() throws {
        let original = try makeDraft(countA)

        XCTAssertThrowsError(
            try original.updatingStep(
                RoutineStep.count(id: "a", name: " \u{00A0}\u{2007}\u{202F} ", target: 15)
            )
        ) { error in
            guard case DraftError.invalidStep(let errors) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertTrue(errors.contains(.emptyStepName))
        }
    }

}
