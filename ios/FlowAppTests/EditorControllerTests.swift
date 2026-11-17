import XCTest
@testable import Flow

final class StepIDMatcherTests: XCTestCase {

    private let matcher = StepIDMatcher()

    func testPreservesIDForSingleFieldModification() {
        assertIDs(
            old: [countStep("a", "Push-ups", 15)],
            new: [countCandidate("Push-ups", 20)],
            expected: ["a"]
        )
        assertIDs(
            old: [countStep("a", "Push-ups", 15)],
            new: [countCandidate("Push-ups edited", 15)],
            expected: ["a"]
        )
        assertIDs(
            old: [countStep("a", "Push-ups", 15)],
            new: [countCandidate("Push-ups", 15, repeatCount: 3)],
            expected: ["a"]
        )
        assertIDs(
            old: [countStep("a", "Push-ups", 15, mode: .increment)],
            new: [countCandidate("Push-ups", 15, mode: .complete)],
            expected: ["a"]
        )
        assertIDs(
            old: [timerStep("a", "Read", 60_000)],
            new: [timerCandidate("Read", 90_000)],
            expected: ["a"]
        )
        assertIDs(
            old: [timerStep("a", "Stretch", 45_000, category: .normal)],
            new: [timerCandidate("Stretch", 45_000, category: .rest)],
            expected: ["a"]
        )
    }

    func testInsertionDoesNotChangeOtherIDs() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2)
            ],
            new: [
                countCandidate("A", 1),
                countCandidate("X", 9),
                countCandidate("B", 2)
            ],
            expected: ["a", nil, "b"]
        )
    }

    func testDeletionDoesNotChangeOtherIDs() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("B", 2),
                countCandidate("C", 3)
            ],
            expected: ["b", "c"]
        )
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("A", 1),
                countCandidate("C", 3)
            ],
            expected: ["a", "c"]
        )
    }

    func testMovePreservesIDsByContent() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("C", 3),
                countCandidate("A", 1),
                countCandidate("B", 2)
            ],
            expected: ["c", "a", "b"]
        )
    }

    func testDuplicateSemanticStepsUseStableFIFO() {
        assertIDs(
            old: [
                countStep("a", "Same", 5),
                countStep("b", "Same", 5)
            ],
            new: [
                countCandidate("Same", 5),
                countCandidate("Same", 5)
            ],
            expected: ["a", "b"]
        )
    }

    func testMultipleSameTypeModificationsInOneRegionKeepOrder() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2)
            ],
            new: [
                countCandidate("A edited", 10),
                countCandidate("B edited", 20)
            ],
            expected: ["a", "b"]
        )
    }

    func testTypeChangeDoesNotInheritID() {
        assertIDs(
            old: [countStep("a", "Same", 5)],
            new: [timerCandidate("Same", 60_000)],
            expected: [nil]
        )
        assertIDs(
            old: [timerStep("a", "Same", 60_000)],
            new: [countCandidate("Same", 5)],
            expected: [nil]
        )
    }

    func testDifferentTypesInRegionDoNotPairByPosition() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                timerStep("b", "B", 60_000)
            ],
            new: [
                timerCandidate("A", 60_000),
                countCandidate("B", 2)
            ],
            expected: [nil, nil]
        )
    }

    func testMultipleChangesKeepUnchangedAnchors() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("A edited", 10),
                countCandidate("B", 2),
                countCandidate("C edited", 30)
            ],
            expected: ["a", "b", "c"]
        )
    }

    func testInsertedStepDoesNotStealFollowingIDs() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("A", 1),
                countCandidate("B", 2),
                countCandidate("X", 9),
                countCandidate("C", 3)
            ],
            expected: ["a", "b", nil, "c"]
        )
    }

    func testModificationAdjacentToInsertionKeepsTheModifiedID() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("A", 1),
                countCandidate("B edited", 20),
                countCandidate("X", 9),
                countCandidate("C", 3)
            ],
            expected: ["a", "b", nil, "c"]
        )
    }

    func testMoveAndModifyUsesBestEffortReplacementMatching() {
        assertIDs(
            old: [
                countStep("a", "A", 1),
                countStep("b", "B", 2),
                countStep("c", "C", 3)
            ],
            new: [
                countCandidate("C", 3),
                countCandidate("A", 1),
                countCandidate("B edited", 20)
            ],
            expected: ["c", "a", nil]
        )
    }

    func testEmptyNewTextDeletesAllSteps() {
        let result = matcher.match(
            oldSteps: [countStep("a", "A", 1), timerStep("b", "B", 1_000)],
            newCandidates: []
        )
        XCTAssertTrue(result.isEmpty)
    }

    func testNoOldIDIsUsedTwice() {
        let matches = matcher.match(
            oldSteps: [
                countStep("a", "A", 1),
                countStep("b", "B", 2)
            ],
            newCandidates: [
                countCandidate("A", 1),
                countCandidate("A", 1),
                countCandidate("B", 2)
            ]
        )
        let ids = matches.compactMap(\.id)
        XCTAssertEqual(Set(ids).count, ids.count)
    }

    private func assertIDs(
        old: [RoutineStep],
        new: [StepCandidate],
        expected: [String?],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let result = matcher.match(oldSteps: old, newCandidates: new)
        XCTAssertEqual(result.map(\.id), expected, file: file, line: line)
    }
}

final class EditorControllerTests: XCTestCase {

    func testInitialStateIsSyncedAndCanonical() throws {
        let initial = try makeDraft(
            countStep("a", "Push-ups", 15),
            timerStep("b", "Read", 60_000)
        )
        let controller = try EditorController(initialDraft: initial)

        XCTAssertEqual(controller.state.mode, .text)
        XCTAssertEqual(controller.state.syncStatus, .synced)
        XCTAssertEqual(controller.state.textBuffer, "Push-ups 15次\nRead 1分钟")
        XCTAssertEqual(controller.state.lastCommittedSteps, initial.steps)
        XCTAssertEqual(controller.state.lastCommittedText, controller.state.textBuffer)
        XCTAssertTrue(controller.state.parseResult.canCommit)
        XCTAssertEqual(controller.state.draft, initial)
    }

    func testValidTextChangeIsDirtyAndCommitIsAtomic() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(
                countStep("a", "Push-ups", 15),
                timerStep("b", "Read", 60_000)
            )
        )
        let previousDraft = controller.state.draft
        let previousSnapshot = controller.state.lastCommittedSteps

        let dirty = controller.onTextChanged("Push-ups 20次\nRead 1分钟\n")
        XCTAssertEqual(dirty.syncStatus, .dirty)
        XCTAssertEqual(dirty.draft.steps[0].id, "a")
        XCTAssertEqual(dirty.draft.steps[0].target, 15)
        XCTAssertEqual(dirty.lastCommittedSteps, previousSnapshot)
        XCTAssertEqual(dirty.draft, previousDraft)

        let committed = requireSuccess(controller.commitText())
        XCTAssertEqual(committed.syncStatus, .synced)
        XCTAssertEqual(committed.draft.steps[0].target, 20)
        XCTAssertEqual(committed.draft.steps[0].id, "a")
        XCTAssertEqual(committed.textBuffer, "Push-ups 20次\nRead 1分钟")
        XCTAssertEqual(committed.lastCommittedSteps, committed.draft.steps)
        XCTAssertEqual(committed.lastCommittedText, committed.textBuffer)
        XCTAssertTrue(committed.parseResult.canCommit)
    }

    func testEmptyLinesDoNotBlockCommit() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "Push-ups", 15))
        )

        controller.onTextChanged("\n Push-ups 20次 \n\n")
        let committed = requireSuccess(controller.commitText())

        XCTAssertEqual(committed.draft.steps.count, 1)
        XCTAssertEqual(committed.textBuffer, "Push-ups 20次")
        XCTAssertEqual(committed.syncStatus, .synced)
    }

    func testIncompleteAndInvalidTextBlockCommitWithoutPartialChanges() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "Push-ups", 15))
        )
        let previousDraft = controller.state.draft
        let previousSnapshot = controller.state.lastCommittedSteps

        let incomplete = controller.onTextChanged("Push-ups 20次\nRead")
        XCTAssertEqual(incomplete.syncStatus, .invalid)
        XCTAssertEqual(requireFailure(controller.commitText()), .textBlocked)
        XCTAssertEqual(controller.state.draft, previousDraft)
        XCTAssertEqual(controller.state.lastCommittedSteps, previousSnapshot)
        XCTAssertEqual(controller.state.textBuffer, "Push-ups 20次\nRead")

        let invalid = controller.onTextChanged("Push-ups 20次\nRead 15个")
        XCTAssertEqual(invalid.syncStatus, .invalid)
        XCTAssertEqual(requireFailure(controller.commitText()), .textBlocked)
        XCTAssertEqual(controller.state.draft, previousDraft)
        XCTAssertEqual(controller.state.lastCommittedSteps, previousSnapshot)
        XCTAssertEqual(controller.state.textBuffer, "Push-ups 20次\nRead 15个")
    }

    func testFixingErrorsAllowsCommit() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "Push-ups", 15))
        )

        controller.onTextChanged("Push-ups")
        XCTAssertEqual(controller.state.syncStatus, .invalid)
        XCTAssertEqual(requireFailure(controller.commitText()), .textBlocked)

        controller.onTextChanged("Push-ups 25次")
        XCTAssertEqual(controller.state.syncStatus, .dirty)
        let committed = requireSuccess(controller.commitText())
        XCTAssertEqual(committed.draft.steps[0].target, 25)
        XCTAssertEqual(committed.syncStatus, .synced)
    }

    func testWhitespaceOnlyTextCommitsAsEmpty() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1))
        )

        controller.onTextChanged("\n  \n\t\n\u{3000}")
        XCTAssertEqual(controller.state.syncStatus, .dirty)
        let committed = requireSuccess(controller.commitText())

        XCTAssertTrue(committed.draft.steps.isEmpty)
        XCTAssertEqual(committed.textBuffer, "")
        XCTAssertEqual(committed.syncStatus, .synced)
    }

    func testBlankLinesBetweenValidStepsAreRemovedOnCommit() throws {
        let controller = try EditorController(
            initialDraft: makeDraft()
        )

        controller.onTextChanged("A 1次\n\n  \nB 2次")
        let committed = requireSuccess(controller.commitText())

        XCTAssertEqual(committed.draft.steps.count, 2)
        XCTAssertEqual(committed.textBuffer, "A 1次\nB 2次")
        XCTAssertEqual(committed.syncStatus, .synced)
    }

    func testEmptyTextCommitClearsSteps() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "Push-ups", 15))
        )

        controller.onTextChanged("")
        XCTAssertEqual(controller.state.syncStatus, .dirty)
        let committed = requireSuccess(controller.commitText())

        XCTAssertTrue(committed.draft.steps.isEmpty)
        XCTAssertTrue(committed.lastCommittedSteps.isEmpty)
        XCTAssertEqual(committed.textBuffer, "")
        XCTAssertEqual(committed.syncStatus, .synced)
        XCTAssertTrue(committed.parseResult.canCommit)
    }

    func testTextCommitMatchesExistingIDsAndCreatesNewIDs() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(
                countStep("a", "A", 1),
                countStep("b", "B", 2)
            )
        )

        controller.onTextChanged("X 9次\nA 1次\nB 2次")
        let committed = requireSuccess(controller.commitText())

        XCTAssertEqual(committed.draft.steps.map(\.name), ["X", "A", "B"])
        XCTAssertEqual(committed.draft.steps[1].id, "a")
        XCTAssertEqual(committed.draft.steps[2].id, "b")
        XCTAssertFalse(committed.draft.steps[0].id.isEmpty)
        XCTAssertEqual(
            Set(committed.draft.steps.map(\.id)).count,
            committed.draft.steps.count
        )
    }

    func testCardOperationsUpdateTextAndSnapshotImmediately() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1)),
            initialMode: .cards
        )

        let added = requireSuccess(controller.addStep(countCandidate("B", 2)))
        XCTAssertEqual(added.draft.steps.count, 2)
        XCTAssertNotEqual(added.draft.steps[0].id, added.draft.steps[1].id)
        XCTAssertEqual(added.lastCommittedSteps, added.draft.steps)
        XCTAssertEqual(added.lastCommittedText, added.textBuffer)
        XCTAssertEqual(added.syncStatus, .synced)
        XCTAssertEqual(added.textBuffer, "A 1次\nB 2次")

        let updated = requireSuccess(
            controller.updateStep(
                countStep("a", "A edited", 1)
            )
        )
        XCTAssertEqual(updated.draft.steps[0].id, "a")
        XCTAssertEqual(updated.draft.steps[0].name, "A edited")
        XCTAssertEqual(updated.textBuffer, "A edited 1次\nB 2次")

        let moved = requireSuccess(controller.moveStep(from: 0, to: 1))
        XCTAssertEqual(moved.draft.steps.map(\.name), ["B", "A edited"])
        XCTAssertEqual(moved.textBuffer, "B 2次\nA edited 1次")

        let duplicated = requireSuccess(
            controller.duplicateStep(id: moved.draft.steps[0].id)
        )
        XCTAssertEqual(duplicated.draft.steps.count, 3)
        XCTAssertNotEqual(duplicated.draft.steps[0].id, duplicated.draft.steps[1].id)
        XCTAssertEqual(
            Set(duplicated.draft.steps.map(\.id)).count,
            duplicated.draft.steps.count
        )

        let deleted = requireSuccess(
            controller.deleteStep(id: duplicated.draft.steps[0].id)
        )
        XCTAssertEqual(deleted.draft.steps.count, 2)
        XCTAssertEqual(deleted.syncStatus, .synced)

        let cleared = requireSuccess(controller.clearSteps())
        XCTAssertTrue(cleared.draft.steps.isEmpty)
        XCTAssertEqual(cleared.textBuffer, "")
        XCTAssertTrue(cleared.lastCommittedSteps.isEmpty)
        XCTAssertEqual(cleared.syncStatus, .synced)
    }

    func testCardTypeChangesPreserveIDAndClearOldFields() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 15)),
            initialMode: .cards
        )

        let toTimer = requireSuccess(
            controller.updateStepType(
                id: "a",
                type: .timer,
                durationMs: 60_000,
                category: .rest
            )
        )
        let timer = toTimer.draft.steps[0]
        XCTAssertEqual(timer.id, "a")
        XCTAssertEqual(timer.type, .timer)
        XCTAssertNil(timer.target)
        XCTAssertNil(timer.countMode)
        XCTAssertEqual(timer.durationMs, 60_000)
        XCTAssertEqual(timer.category, .rest)
        XCTAssertEqual(toTimer.textBuffer, "A 1分钟 [分类=休息]")

        let toCount = requireSuccess(
            controller.updateStepType(
                id: "a",
                type: .count,
                target: 20,
                countMode: .complete
            )
        )
        let count = toCount.draft.steps[0]
        XCTAssertEqual(count.id, "a")
        XCTAssertEqual(count.type, .count)
        XCTAssertEqual(count.target, 20)
        XCTAssertEqual(count.countMode, .complete)
        XCTAssertNil(count.durationMs)
        XCTAssertNil(count.category)
        XCTAssertEqual(toCount.textBuffer, "A 20次 [计数=完成]")
    }

    func testAddsCountAndTimerStepsToEmptyCards() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(),
            initialMode: .cards
        )

        let withCount = requireSuccess(controller.addStep(countCandidate("Push-ups", 15)))
        XCTAssertEqual(withCount.draft.steps.count, 1)
        XCTAssertEqual(withCount.draft.steps[0].type, .count)
        XCTAssertEqual(withCount.textBuffer, "Push-ups 15次")

        let withTimer = requireSuccess(
            controller.addStep(
                StepCandidate(
                    name: "Read",
                    type: .timer,
                    durationMs: 30_000,
                    category: .rest
                )
            )
        )
        XCTAssertEqual(withTimer.draft.steps.count, 2)
        XCTAssertEqual(withTimer.draft.steps[1].type, .timer)
        XCTAssertEqual(withTimer.textBuffer, "Push-ups 15次\nRead 30秒 [分类=休息]")
        XCTAssertEqual(withTimer.syncStatus, .synced)
    }

    func testCardEditUpdatesFieldsAndPreservesID() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 10)),
            initialMode: .cards
        )

        let updated = requireSuccess(
            controller.updateStep(
                RoutineStep(
                    id: "a",
                    name: "A edited",
                    type: .count,
                    target: 20,
                    countMode: .complete,
                    repeatCount: 3
                )
            )
        )
        XCTAssertEqual(updated.draft.steps[0].id, "a")
        XCTAssertEqual(updated.draft.steps[0].target, 20)
        XCTAssertEqual(updated.draft.steps[0].countMode, .complete)
        XCTAssertEqual(updated.draft.steps[0].repeatCount, 3)
        XCTAssertEqual(updated.textBuffer, "A edited 20次 x 3 [计数=完成]")

        let asTimer = requireSuccess(
            controller.updateStep(
                RoutineStep(
                    id: "a",
                    name: "A edited",
                    type: .timer,
                    durationMs: 45_000,
                    category: .rest,
                    repeatCount: 3
                )
            )
        )
        XCTAssertEqual(asTimer.draft.steps[0].id, "a")
        XCTAssertEqual(asTimer.draft.steps[0].type, .timer)
        XCTAssertNil(asTimer.draft.steps[0].target)
        XCTAssertNil(asTimer.draft.steps[0].countMode)
        XCTAssertEqual(asTimer.draft.steps[0].durationMs, 45_000)
        XCTAssertEqual(asTimer.draft.steps[0].category, .rest)
        XCTAssertEqual(asTimer.textBuffer, "A edited 45秒 x 3 [分类=休息]")
    }

    func testFailedCardOperationsLeaveStateUntouched() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1)),
            initialMode: .cards
        )
        let before = controller.state

        XCTAssertEqual(requireFailure(controller.deleteStep(id: "missing")), .stepNotFound)
        XCTAssertEqual(controller.state, before)

        XCTAssertEqual(requireFailure(controller.addStep(countCandidate("Bad", 0))), .invalidStep)
        XCTAssertEqual(controller.state, before)

        XCTAssertEqual(requireFailure(controller.moveStep(from: 0, to: 99)), .invalidMove)
        XCTAssertEqual(controller.state, before)

        XCTAssertEqual(
            requireFailure(
                controller.updateStep(
                    RoutineStep(
                        id: "missing",
                        name: "Missing",
                        type: .count,
                        target: 1,
                        countMode: .increment
                    )
                )
            ),
            .stepNotFound
        )
        XCTAssertEqual(controller.state, before)

        XCTAssertEqual(
            requireFailure(
                controller.updateStepType(id: "missing", type: .timer, durationMs: 1_000)
            ),
            .stepNotFound
        )
        XCTAssertEqual(controller.state, before)

        let newlineName = countStep("a", "A\nB", 1)
        XCTAssertEqual(requireFailure(controller.updateStep(newlineName)), .serializationFailed)
        XCTAssertEqual(controller.state, before)
    }

    func testNoSpaceFormattingDoesNotChangeID() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 15))
        )

        controller.onTextChanged("A15次")
        let committed = requireSuccess(controller.commitText())

        XCTAssertEqual(committed.draft.steps[0].id, "a")
        XCTAssertEqual(committed.textBuffer, "A 15次")
    }

    func testTextTypeChangeMayCreateNewID() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 15))
        )

        controller.onTextChanged("A 1分钟")
        let committed = requireSuccess(controller.commitText())

        XCTAssertEqual(committed.draft.steps[0].type, .timer)
        XCTAssertNotEqual(committed.draft.steps[0].id, "a")
    }

    func testCardChangesArePreservedWhenSwitchingBackToText() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1)),
            initialMode: .cards
        )

        let added = requireSuccess(controller.addStep(countCandidate("B", 2)))
        let ids = added.draft.steps.map(\.id)
        let text = requireSuccess(controller.switchMode(.text))

        XCTAssertEqual(text.mode, .text)
        XCTAssertEqual(text.draft.steps.map(\.id), ids)
        XCTAssertEqual(text.textBuffer, "A 1次\nB 2次")
        XCTAssertEqual(text.syncStatus, .synced)
    }

    func testCardOperationInTextModeIsRejected() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1))
        )
        let before = controller.state

        XCTAssertEqual(
            requireFailure(controller.addStep(countCandidate("B", 2))),
            .wrongMode
        )
        XCTAssertEqual(controller.state, before)
    }

    func testModeSwitchingRequiresSyncedTextAndPreservesIDs() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(
                countStep("a", "A", 1),
                timerStep("b", "B", 60_000)
            )
        )

        let cards = requireSuccess(controller.switchMode(.cards))
        XCTAssertEqual(cards.mode, .cards)
        XCTAssertEqual(cards.draft.steps.map(\.id), ["a", "b"])

        let text = requireSuccess(controller.switchMode(.text))
        XCTAssertEqual(text.mode, .text)
        XCTAssertEqual(text.draft.steps.map(\.id), ["a", "b"])
        XCTAssertEqual(text.syncStatus, .synced)
    }

    func testDirtyAndInvalidTextBlockModeSwitch() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1))
        )
        let initial = controller.state

        controller.onTextChanged("A 2次")
        XCTAssertEqual(controller.state.syncStatus, .dirty)
        XCTAssertEqual(requireFailure(controller.switchMode(.cards)), .textBlocked)
        XCTAssertEqual(controller.state.mode, .text)
        XCTAssertEqual(controller.state.textBuffer, "A 2次")
        XCTAssertEqual(controller.state.lastCommittedSteps, initial.lastCommittedSteps)

        controller.onTextChanged("A")
        XCTAssertEqual(controller.state.syncStatus, .invalid)
        XCTAssertEqual(requireFailure(controller.switchMode(.cards)), .textBlocked)
        XCTAssertEqual(controller.state.mode, .text)
        XCTAssertEqual(controller.state.textBuffer, "A")

        controller.onTextChanged("A 2次")
        _ = requireSuccess(controller.commitText())
        _ = requireSuccess(controller.switchMode(.cards))
        XCTAssertEqual(controller.state.mode, .cards)
    }

    func testRestoreLastCommittedRecoversDraftAndCanonicalText() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1))
        )

        controller.onTextChanged("A 2次")
        _ = requireSuccess(controller.commitText())
        XCTAssertEqual(controller.state.draft.steps[0].target, 2)

        controller.onTextChanged("A 3次\nB")
        XCTAssertEqual(controller.state.syncStatus, .invalid)
        let restored = requireSuccess(controller.restoreLastCommitted())

        XCTAssertEqual(restored.draft.steps[0].target, 2)
        XCTAssertEqual(restored.draft.steps[0].id, "a")
        XCTAssertEqual(restored.textBuffer, "A 2次")
        XCTAssertEqual(restored.syncStatus, .synced)
        XCTAssertEqual(restored.lastCommittedSteps, restored.draft.steps)
    }

    func testLastCommittedStepsIsValueSnapshot() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1))
        )
        var snapshot = controller.state.lastCommittedSteps
        snapshot.append(countStep("b", "B", 2))
        XCTAssertEqual(controller.state.lastCommittedSteps.count, 1)
    }

    func testFailedCommitDoesNotChangeSnapshotOrDraft() throws {
        let controller = try EditorController(
            initialDraft: makeDraft(countStep("a", "A", 1))
        )
        let previousDraft = controller.state.draft
        let previousSnapshot = controller.state.lastCommittedSteps
        let previousText = controller.state.textBuffer

        controller.onTextChanged("A 2次\nBroken")
        XCTAssertEqual(requireFailure(controller.commitText()), .textBlocked)
        XCTAssertEqual(controller.state.draft, previousDraft)
        XCTAssertEqual(controller.state.lastCommittedSteps, previousSnapshot)
        XCTAssertEqual(controller.state.textBuffer, "A 2次\nBroken")
        XCTAssertNotEqual(controller.state.textBuffer, previousText)
        XCTAssertEqual(controller.state.mode, .text)
    }

    private func makeDraft(_ steps: RoutineStep...) throws -> RoutineDraft {
        try RoutineDraft(
            id: "draft",
            name: "Routine",
            version: Routine.currentSchemaVersion,
            repeatCount: 1,
            steps: steps,
            createdAt: 1_000,
            updatedAt: 1_000
        ).validated()
    }

    private func requireSuccess(
        _ result: EditorResult,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> EditorState {
        switch result {
        case .success(let state):
            return state
        case .failure(let error, _):
            XCTFail("Expected success, got \(error)", file: file, line: line)
            return result.state
        }
    }

    private func requireFailure(
        _ result: EditorResult,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> EditorError {
        switch result {
        case .failure(let error, _):
            return error
        case .success:
            XCTFail("Expected failure", file: file, line: line)
            return .wrongMode
        }
    }
}

private func countStep(
    _ id: String,
    _ name: String,
    _ target: Int,
    mode: CountMode = .increment,
    repeatCount: Int = 1
) -> RoutineStep {
    RoutineStep(
        id: id,
        name: name,
        type: .count,
        target: target,
        countMode: mode,
        repeatCount: repeatCount
    )
}

private func timerStep(
    _ id: String,
    _ name: String,
    _ durationMs: Int64,
    category: TimerCategory = .normal,
    repeatCount: Int = 1
) -> RoutineStep {
    RoutineStep(
        id: id,
        name: name,
        type: .timer,
        durationMs: durationMs,
        category: category,
        repeatCount: repeatCount
    )
}

private func countCandidate(
    _ name: String,
    _ target: Int,
    mode: CountMode = .increment,
    repeatCount: Int = 1
) -> StepCandidate {
    StepCandidate(
        name: name,
        type: .count,
        target: target,
        countMode: mode,
        repeatCount: repeatCount
    )
}

private func timerCandidate(
    _ name: String,
    _ durationMs: Int64,
    category: TimerCategory = .normal,
    repeatCount: Int = 1
) -> StepCandidate {
    StepCandidate(
        name: name,
        type: .timer,
        durationMs: durationMs,
        category: category,
        repeatCount: repeatCount
    )
}
