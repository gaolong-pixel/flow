package com.flow.app.domain.editor

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.parser.StepCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorControllerTest {

    @Test
    fun initialStateIsSyncedAndCanonical() {
        val initial = draft(count("a", "Push-ups", 15), timer("b", "Read", 60_000))
        val controller = EditorController(initial)

        assertEquals(EditorMode.TEXT, controller.state.mode)
        assertEquals(SyncStatus.SYNCED, controller.state.syncStatus)
        assertEquals("Push-ups 15次\nRead 1分钟", controller.state.textBuffer)
        assertEquals(initial.steps, controller.state.lastCommittedSteps)
        assertEquals(controller.state.textBuffer, controller.state.lastCommittedText)
        assertTrue(controller.state.parseResult.canCommit)
        assertEquals(initial, controller.state.draft)
    }

    @Test
    fun validTextChangeIsDirtyAndCommitIsAtomic() {
        val controller = EditorController(draft(count("a", "Push-ups", 15), timer("b", "Read", 60_000)))
        val previousDraft = controller.state.draft
        val previousSnapshot = controller.state.lastCommittedSteps

        val dirty = controller.onTextChanged("Push-ups 20次\nRead 1分钟\n")
        assertEquals(SyncStatus.DIRTY, dirty.syncStatus)
        assertEquals("a", dirty.draft.steps[0].id)
        assertEquals(15, dirty.draft.steps[0].target)
        assertEquals(previousSnapshot, dirty.lastCommittedSteps)
        assertEquals(previousDraft, dirty.draft)

        val committed = success(controller.commitText())
        assertEquals(SyncStatus.SYNCED, committed.syncStatus)
        assertEquals(20, committed.draft.steps[0].target)
        assertEquals("a", committed.draft.steps[0].id)
        assertEquals("Push-ups 20次\nRead 1分钟", committed.textBuffer)
        assertEquals(committed.draft.steps, committed.lastCommittedSteps)
        assertEquals(committed.textBuffer, committed.lastCommittedText)
        assertTrue(committed.parseResult.canCommit)
    }

    @Test
    fun emptyLinesDoNotBlockCommit() {
        val controller = EditorController(draft(count("a", "Push-ups", 15)))

        controller.onTextChanged("\n Push-ups 20次 \n\n")
        val committed = success(controller.commitText())

        assertEquals(1, committed.draft.steps.size)
        assertEquals("Push-ups 20次", committed.textBuffer)
        assertEquals(SyncStatus.SYNCED, committed.syncStatus)
    }

    @Test
    fun incompleteAndInvalidTextBlockCommitWithoutPartialChanges() {
        val controller = EditorController(draft(count("a", "Push-ups", 15)))
        val previousDraft = controller.state.draft
        val previousSnapshot = controller.state.lastCommittedSteps

        val incomplete = controller.onTextChanged("Push-ups 20次\nRead")
        assertEquals(SyncStatus.INVALID, incomplete.syncStatus)
        val incompleteFailure = failure(controller.commitText())
        assertEquals(EditorError.TEXT_BLOCKED, incompleteFailure)
        assertEquals(previousDraft, controller.state.draft)
        assertEquals(previousSnapshot, controller.state.lastCommittedSteps)
        assertEquals("Push-ups 20次\nRead", controller.state.textBuffer)

        val invalid = controller.onTextChanged("Push-ups 20次\nRead 15个")
        assertEquals(SyncStatus.INVALID, invalid.syncStatus)
        val invalidFailure = failure(controller.commitText())
        assertEquals(EditorError.TEXT_BLOCKED, invalidFailure)
        assertEquals(previousDraft, controller.state.draft)
        assertEquals(previousSnapshot, controller.state.lastCommittedSteps)
        assertEquals("Push-ups 20次\nRead 15个", controller.state.textBuffer)
    }

    @Test
    fun fixingErrorsAllowsCommit() {
        val controller = EditorController(draft(count("a", "Push-ups", 15)))

        controller.onTextChanged("Push-ups")
        assertEquals(SyncStatus.INVALID, controller.state.syncStatus)
        assertEquals(EditorError.TEXT_BLOCKED, failure(controller.commitText()))

        controller.onTextChanged("Push-ups 25次")
        assertEquals(SyncStatus.DIRTY, controller.state.syncStatus)
        val committed = success(controller.commitText())
        assertEquals(25, committed.draft.steps.single().target)
        assertEquals(SyncStatus.SYNCED, committed.syncStatus)
    }

    @Test
    fun whitespaceOnlyTextCommitsAsEmpty() {
        val controller = EditorController(draft(count("a", "A", 1)))

        controller.onTextChanged("\n  \n\t\n\u3000")
        assertEquals(SyncStatus.DIRTY, controller.state.syncStatus)
        val committed = success(controller.commitText())

        assertTrue(committed.draft.steps.isEmpty())
        assertEquals("", committed.textBuffer)
        assertEquals(SyncStatus.SYNCED, committed.syncStatus)
    }

    @Test
    fun blankLinesBetweenValidStepsAreRemovedOnCommit() {
        val controller = EditorController(draft())

        controller.onTextChanged("A 1次\n\n  \nB 2次")
        val committed = success(controller.commitText())

        assertEquals(2, committed.draft.steps.size)
        assertEquals("A 1次\nB 2次", committed.textBuffer)
        assertEquals(SyncStatus.SYNCED, committed.syncStatus)
    }

    @Test
    fun emptyTextCommitClearsSteps() {
        val controller = EditorController(draft(count("a", "Push-ups", 15)))

        controller.onTextChanged("")
        assertEquals(SyncStatus.DIRTY, controller.state.syncStatus)
        val committed = success(controller.commitText())

        assertTrue(committed.draft.steps.isEmpty())
        assertTrue(committed.lastCommittedSteps.isEmpty())
        assertEquals("", committed.textBuffer)
        assertEquals(SyncStatus.SYNCED, committed.syncStatus)
        assertTrue(committed.parseResult.canCommit)
    }

    @Test
    fun textCommitMatchesExistingIdsAndCreatesNewIds() {
        val controller = EditorController(draft(count("a", "A", 1), count("b", "B", 2)))

        controller.onTextChanged("X 9次\nA 1次\nB 2次")
        val committed = success(controller.commitText())

        assertEquals(listOf("X", "A", "B"), committed.draft.steps.map { it.name })
        val x = committed.draft.steps[0]
        val a = committed.draft.steps[1]
        val b = committed.draft.steps[2]
        assertNotNull(x.id)
        assertEquals("a", a.id)
        assertEquals("b", b.id)
        assertEquals(committed.draft.steps.map { it.id }.distinct().size, committed.draft.steps.size)
    }

    @Test
    fun cardOperationsUpdateTextAndSnapshotImmediately() {
        val controller = EditorController(
            initialDraft = draft(count("a", "A", 1)),
            initialMode = EditorMode.CARDS
        )

        val added = success(controller.addStep(countCandidate("B", 2)))
        assertEquals(2, added.draft.steps.size)
        assertNotEquals(added.draft.steps[0].id, added.draft.steps[1].id)
        assertEquals(added.draft.steps, added.lastCommittedSteps)
        assertEquals(added.textBuffer, added.lastCommittedText)
        assertEquals(SyncStatus.SYNCED, added.syncStatus)
        assertEquals("A 1次\nB 2次", added.textBuffer)

        val updated = success(
            controller.updateStep(
                added.draft.steps[0].copy(name = "A edited")
            )
        )
        assertEquals("a", updated.draft.steps[0].id)
        assertEquals("A edited", updated.draft.steps[0].name)
        assertEquals("A edited 1次\nB 2次", updated.textBuffer)

        val moved = success(controller.moveStep(0, 1))
        assertEquals(listOf("B", "A edited"), moved.draft.steps.map { it.name })
        assertEquals("B 2次\nA edited 1次", moved.textBuffer)

        val duplicated = success(controller.duplicateStep(moved.draft.steps[0].id))
        assertEquals(3, duplicated.draft.steps.size)
        assertNotEquals(duplicated.draft.steps[0].id, duplicated.draft.steps[1].id)
        assertEquals(duplicated.draft.steps.map { it.id }.distinct().size, duplicated.draft.steps.size)

        val deleted = success(controller.deleteStep(duplicated.draft.steps[0].id))
        assertEquals(2, deleted.draft.steps.size)
        assertEquals(SyncStatus.SYNCED, deleted.syncStatus)

        val cleared = success(controller.clearSteps())
        assertTrue(cleared.draft.steps.isEmpty())
        assertEquals("", cleared.textBuffer)
        assertTrue(cleared.lastCommittedSteps.isEmpty())
        assertEquals(SyncStatus.SYNCED, cleared.syncStatus)
    }

    @Test
    fun cardTypeChangesPreserveIdAndClearOldFields() {
        val controller = EditorController(
            initialDraft = draft(count("a", "A", 15)),
            initialMode = EditorMode.CARDS
        )

        val toTimer = success(
            controller.updateStepType(
                id = "a",
                type = StepType.TIMER,
                durationMs = 60_000,
                category = TimerCategory.REST
            )
        )
        val timer = toTimer.draft.steps.single()
        assertEquals("a", timer.id)
        assertEquals(StepType.TIMER, timer.type)
        assertEquals(null, timer.target)
        assertEquals(null, timer.countMode)
        assertEquals(60_000L, timer.durationMs)
        assertEquals(TimerCategory.REST, timer.category)
        assertEquals("A 1分钟 [分类=休息]", toTimer.textBuffer)

        val toCount = success(
            controller.updateStepType(
                id = "a",
                type = StepType.COUNT,
                target = 20,
                countMode = CountMode.COMPLETE
            )
        )
        val count = toCount.draft.steps.single()
        assertEquals("a", count.id)
        assertEquals(StepType.COUNT, count.type)
        assertEquals(20, count.target)
        assertEquals(CountMode.COMPLETE, count.countMode)
        assertEquals(null, count.durationMs)
        assertEquals(null, count.category)
        assertEquals("A 20次 [计数=完成]", toCount.textBuffer)
    }

    @Test
    fun addsCountAndTimerStepsToEmptyCards() {
        val controller = EditorController(
            initialDraft = draft(),
            initialMode = EditorMode.CARDS
        )

        val withCount = success(controller.addStep(countCandidate("Push-ups", 15)))
        assertEquals(1, withCount.draft.steps.size)
        assertEquals(StepType.COUNT, withCount.draft.steps[0].type)
        assertEquals("Push-ups 15次", withCount.textBuffer)

        val withTimer = success(
            controller.addStep(
                StepCandidate(
                    name = "Read",
                    type = StepType.TIMER,
                    durationMs = 30_000,
                    category = TimerCategory.REST
                )
            )
        )
        assertEquals(2, withTimer.draft.steps.size)
        assertEquals(StepType.TIMER, withTimer.draft.steps[1].type)
        assertEquals("Push-ups 15次\nRead 30秒 [分类=休息]", withTimer.textBuffer)
        assertEquals(SyncStatus.SYNCED, withTimer.syncStatus)
    }

    @Test
    fun cardEditUpdatesFieldsAndPreservesId() {
        val controller = EditorController(
            initialDraft = draft(count("a", "A", 10)),
            initialMode = EditorMode.CARDS
        )

        val updated = success(
            controller.updateStep(
                RoutineStep(
                    id = "a",
                    name = "A edited",
                    type = StepType.COUNT,
                    target = 20,
                    countMode = CountMode.COMPLETE,
                    repeatCount = 3
                )
            )
        )
        assertEquals("a", updated.draft.steps.single().id)
        assertEquals(20, updated.draft.steps.single().target)
        assertEquals(CountMode.COMPLETE, updated.draft.steps.single().countMode)
        assertEquals(3, updated.draft.steps.single().repeatCount)
        assertEquals("A edited 20次 x 3 [计数=完成]", updated.textBuffer)

        val asTimer = success(
            controller.updateStep(
                RoutineStep(
                    id = "a",
                    name = "A edited",
                    type = StepType.TIMER,
                    durationMs = 45_000,
                    category = TimerCategory.REST,
                    repeatCount = 3
                )
            )
        )
        assertEquals("a", asTimer.draft.steps.single().id)
        assertEquals(StepType.TIMER, asTimer.draft.steps.single().type)
        assertEquals(null, asTimer.draft.steps.single().target)
        assertEquals(null, asTimer.draft.steps.single().countMode)
        assertEquals(45_000L, asTimer.draft.steps.single().durationMs)
        assertEquals(TimerCategory.REST, asTimer.draft.steps.single().category)
        assertEquals("A edited 45秒 x 3 [分类=休息]", asTimer.textBuffer)
    }

    @Test
    fun failedCardOperationsLeaveStateUntouched() {
        val controller = EditorController(
            initialDraft = draft(count("a", "A", 1)),
            initialMode = EditorMode.CARDS
        )
        val before = controller.state

        assertEquals(EditorError.STEP_NOT_FOUND, failure(controller.deleteStep("missing")))
        assertEquals(before, controller.state)

        assertEquals(EditorError.INVALID_STEP, failure(controller.addStep(countCandidate("Bad", 0))))
        assertEquals(before, controller.state)

        assertEquals(EditorError.INVALID_MOVE, failure(controller.moveStep(0, 99)))
        assertEquals(before, controller.state)

        assertEquals(
            EditorError.STEP_NOT_FOUND,
            failure(
                controller.updateStep(
                    RoutineStep(
                        id = "missing",
                        name = "Missing",
                        type = StepType.COUNT,
                        target = 1,
                        countMode = CountMode.INCREMENT
                    )
                )
            )
        )
        assertEquals(before, controller.state)

        assertEquals(
            EditorError.STEP_NOT_FOUND,
            failure(controller.updateStepType("missing", StepType.TIMER, durationMs = 1_000))
        )
        assertEquals(before, controller.state)

        val newlineName = before.draft.steps.single().copy(name = "A\nB")
        assertEquals(
            EditorError.SERIALIZATION_FAILED,
            failure(controller.updateStep(newlineName))
        )
        assertEquals(before, controller.state)
    }

    @Test
    fun noSpaceFormattingDoesNotChangeId() {
        val controller = EditorController(draft(count("a", "A", 15)))

        controller.onTextChanged("A15次")
        val committed = success(controller.commitText())

        assertEquals("a", committed.draft.steps.single().id)
        assertEquals("A 15次", committed.textBuffer)
    }

    @Test
    fun textTypeChangeMayCreateNewId() {
        val controller = EditorController(draft(count("a", "A", 15)))

        controller.onTextChanged("A 1分钟")
        val committed = success(controller.commitText())

        assertEquals(StepType.TIMER, committed.draft.steps.single().type)
        assertNotEquals("a", committed.draft.steps.single().id)
    }

    @Test
    fun cardChangesArePreservedWhenSwitchingBackToText() {
        val controller = EditorController(
            initialDraft = draft(count("a", "A", 1)),
            initialMode = EditorMode.CARDS
        )

        val added = success(controller.addStep(countCandidate("B", 2)))
        val ids = added.draft.steps.map { it.id }
        val text = success(controller.switchMode(EditorMode.TEXT))

        assertEquals(EditorMode.TEXT, text.mode)
        assertEquals(ids, text.draft.steps.map { it.id })
        assertEquals("A 1次\nB 2次", text.textBuffer)
        assertEquals(SyncStatus.SYNCED, text.syncStatus)
    }

    @Test
    fun cardOperationInTextModeIsRejected() {
        val controller = EditorController(draft(count("a", "A", 1)))
        val before = controller.state

        assertEquals(EditorError.WRONG_MODE, failure(controller.addStep(countCandidate("B", 2))))
        assertEquals(before, controller.state)
    }

    @Test
    fun modeSwitchingRequiresSyncedTextAndPreservesIds() {
        val controller = EditorController(draft(count("a", "A", 1), timer("b", "B", 60_000)))

        val cards = success(controller.switchMode(EditorMode.CARDS))
        assertEquals(EditorMode.CARDS, cards.mode)
        assertEquals(listOf("a", "b"), cards.draft.steps.map { it.id })

        val text = success(controller.switchMode(EditorMode.TEXT))
        assertEquals(EditorMode.TEXT, text.mode)
        assertEquals(listOf("a", "b"), text.draft.steps.map { it.id })
        assertEquals(SyncStatus.SYNCED, text.syncStatus)
    }

    @Test
    fun dirtyAndInvalidTextBlockModeSwitch() {
        val controller = EditorController(draft(count("a", "A", 1)))
        val initial = controller.state

        controller.onTextChanged("A 2次")
        assertEquals(SyncStatus.DIRTY, controller.state.syncStatus)
        assertEquals(EditorError.TEXT_BLOCKED, failure(controller.switchMode(EditorMode.CARDS)))
        assertEquals(EditorMode.TEXT, controller.state.mode)
        assertEquals("A 2次", controller.state.textBuffer)
        assertEquals(initial.lastCommittedSteps, controller.state.lastCommittedSteps)

        controller.onTextChanged("A")
        assertEquals(SyncStatus.INVALID, controller.state.syncStatus)
        assertEquals(EditorError.TEXT_BLOCKED, failure(controller.switchMode(EditorMode.CARDS)))
        assertEquals(EditorMode.TEXT, controller.state.mode)
        assertEquals("A", controller.state.textBuffer)

        controller.onTextChanged("A 2次")
        success(controller.commitText())
        success(controller.switchMode(EditorMode.CARDS))
        assertEquals(EditorMode.CARDS, controller.state.mode)
    }

    @Test
    fun restoreLastCommittedRecoversDraftAndCanonicalText() {
        val controller = EditorController(draft(count("a", "A", 1)))

        controller.onTextChanged("A 2次")
        success(controller.commitText())
        assertEquals(2, controller.state.draft.steps.single().target)

        controller.onTextChanged("A 3次\nB")
        assertEquals(SyncStatus.INVALID, controller.state.syncStatus)
        val restored = success(controller.restoreLastCommitted())

        assertEquals(2, restored.draft.steps.single().target)
        assertEquals("a", restored.draft.steps.single().id)
        assertEquals("A 2次", restored.textBuffer)
        assertEquals(SyncStatus.SYNCED, restored.syncStatus)
        assertEquals(restored.lastCommittedSteps, restored.draft.steps)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun editorStateCollectionsAreReadOnly() {
        val controller = EditorController(draft(count("a", "A", 1)))

        assertThrows(UnsupportedOperationException::class.java) {
            (controller.state.parseResult.lines as MutableList<Any>).add("x")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (controller.state.parseResult.steps as MutableList<Any>).add("x")
        }
    }

    @Test
    fun lastCommittedStepsIsReadOnly() {
        val controller = EditorController(draft(count("a", "A", 1)))

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (controller.state.lastCommittedSteps as MutableList<RoutineStep>).add(
                count("b", "B", 2)
            )
        }
    }

    @Test
    fun failedCommitDoesNotChangeSnapshotOrDraft() {
        val controller = EditorController(draft(count("a", "A", 1)))
        val previousDraft = controller.state.draft
        val previousSnapshot = controller.state.lastCommittedSteps
        val previousText = controller.state.textBuffer

        controller.onTextChanged("A 2次\nBroken")
        assertEquals(EditorError.TEXT_BLOCKED, failure(controller.commitText()))
        assertEquals(previousDraft, controller.state.draft)
        assertEquals(previousSnapshot, controller.state.lastCommittedSteps)
        assertEquals("A 2次\nBroken", controller.state.textBuffer)
        assertNotEquals(previousText, controller.state.textBuffer)
        assertEquals(EditorMode.TEXT, controller.state.mode)
    }

    private fun success(result: EditorResult): EditorState {
        assertTrue("Expected success, got $result", result is EditorResult.Success)
        return (result as EditorResult.Success).state
    }

    private fun failure(result: EditorResult): EditorError {
        assertTrue("Expected failure, got $result", result is EditorResult.Failure)
        return (result as EditorResult.Failure).error
    }

    private fun draft(vararg steps: RoutineStep): RoutineDraft = RoutineDraft.new(
        name = "Routine",
        steps = steps.toList(),
        id = "draft",
        now = 1_000L
    )

    private fun count(
        id: String,
        name: String,
        target: Int,
        mode: CountMode = CountMode.INCREMENT,
        repeat: Int = 1
    ) = RoutineStep(
        id = id,
        name = name,
        type = StepType.COUNT,
        target = target,
        countMode = mode,
        repeatCount = repeat
    )

    private fun timer(
        id: String,
        name: String,
        durationMs: Long,
        category: TimerCategory = TimerCategory.NORMAL,
        repeat: Int = 1
    ) = RoutineStep(
        id = id,
        name = name,
        type = StepType.TIMER,
        durationMs = durationMs,
        category = category,
        repeatCount = repeat
    )

    private fun countCandidate(
        name: String,
        target: Int,
        mode: CountMode = CountMode.INCREMENT,
        repeat: Int = 1
    ) = StepCandidate(
        name = name,
        type = StepType.COUNT,
        target = target,
        countMode = mode,
        repeatCount = repeat
    )
}
