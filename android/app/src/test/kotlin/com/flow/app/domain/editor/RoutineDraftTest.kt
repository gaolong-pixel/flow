package com.flow.app.domain.editor

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.model.ValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineDraftTest {

    private val countA = RoutineStep.count(id = "a", name = "Push-ups", target = 15)
    private val timerB = RoutineStep.timer(id = "b", name = "Read", durationMs = 60_000L)
    private val countC = RoutineStep.count(id = "c", name = "Squat", target = 20)

    private fun draft(vararg steps: RoutineStep): RoutineDraft =
        RoutineDraft.new(
            name = "Morning",
            repeatCount = 2,
            steps = steps.toList(),
            id = "draft-1",
            now = 1_000L
        )

    @Test
    fun addsUpdatesDeletesAndMovesStepsUsingListOrder() {
        var current = draft(countA, timerB, countC)
        assertEquals(listOf("a", "b", "c"), current.steps.map { it.id })

        current = current.addStep(RoutineStep.count(id = "d", name = "Plank", target = 1))
        assertEquals(listOf("a", "b", "c", "d"), current.steps.map { it.id })

        current = current.moveStep(0, 2)
        assertEquals(listOf("b", "c", "a", "d"), current.steps.map { it.id })

        current = current.updateStep(countA.copy(name = "Push-ups edited"))
        assertEquals(listOf("b", "c", "a", "d"), current.steps.map { it.id })
        assertEquals("Push-ups edited", current.steps.first { it.id == "a" }.name)

        current = current.deleteStep("c")
        assertEquals(listOf("b", "a", "d"), current.steps.map { it.id })
    }

    @Test
    fun duplicateStepCreatesNewIdAndKeepsExistingIds() {
        val original = draft(countA, timerB)
        val duplicated = original.duplicateStep("a")

        assertEquals(3, duplicated.steps.size)
        assertEquals(listOf("a", "b"), original.steps.map { it.id })
        assertEquals("b", duplicated.steps[2].id)
        assertEquals("a", duplicated.steps[0].id)
        assertNotEquals("a", duplicated.steps[1].id)
        assertEquals(countA.name, duplicated.steps[1].name)
        assertEquals(duplicated.steps.map { it.id }.distinct().size, duplicated.steps.size)
    }

    @Test
    fun changesStepTypeWhileKeepingId() {
        val original = draft(countA)
        val timer = RoutineStep.timer(
            id = countA.id,
            name = countA.name,
            durationMs = 45_000L,
            category = TimerCategory.NORMAL
        )

        val updated = original.updateStep(timer)

        assertEquals(StepType.TIMER, updated.steps.single().type)
        assertEquals("a", updated.steps.single().id)
        assertEquals(45_000L, updated.steps.single().durationMs)
        assertEquals(null, updated.steps.single().target)
        assertEquals(null, updated.steps.single().countMode)
    }

    @Test
    fun updatingOneStepDoesNotChangeOtherIds() {
        val original = draft(countA, timerB, countC)
        val updated = original.updateStep(timerB.copy(name = "Read edited"))

        assertEquals(listOf("a", "b", "c"), updated.steps.map { it.id })
        assertEquals("Read edited", updated.steps.first { it.id == "b" }.name)
    }

    @Test
    fun rejectsInvalidStepBeforeItEntersDraft() {
        val invalidTimer = RoutineStep.timer(
            id = "invalid",
            name = "Invalid",
            durationMs = 1_500L
        )

        assertThrows(DraftException.InvalidStep::class.java) {
            draft(countA).addStep(invalidTimer)
        }
        assertThrows(DraftException.InvalidDraft::class.java) {
            draft(countA, invalidTimer)
        }
    }

    @Test
    fun rejectsInvalidRepeatCounts() {
        assertThrows(DraftException.InvalidDraft::class.java) {
            RoutineDraft.new(repeatCount = 0, now = 1_000L)
        }

        assertThrows(DraftException.InvalidRepeatCount::class.java) {
            draft(countA).setRepeatCount(0)
        }

        val routine = Routine(
            id = "r",
            name = "Bad",
            repeatCount = 0,
            steps = emptyList(),
            createdAt = 0,
            updatedAt = 0
        )
        assertTrue(
            routine.validationErrors().contains(ValidationError.INVALID_ROUTINE_REPEAT_COUNT)
        )
    }

    @Test
    fun rejectsDuplicateStepIds() {
        val duplicate = RoutineDraft(
            id = "duplicate-draft",
            name = "Duplicate",
            steps = listOf(countA, countA.copy(name = "Duplicate")),
            createdAt = 0L,
            updatedAt = 0L
        )
        assertTrue(
            duplicate.validationErrors().contains(ValidationError.DUPLICATE_STEP_ID)
        )

        val duplicateRoutine = Routine(
            id = "duplicate-routine",
            name = "Duplicate",
            steps = listOf(countA, countA.copy(name = "Duplicate")),
            createdAt = 0L,
            updatedAt = 0L
        )
        assertTrue(
            duplicateRoutine.validationErrors().contains(ValidationError.DUPLICATE_STEP_ID)
        )

        assertThrows(DraftException.DuplicateStepID::class.java) {
            draft(countA).addStep(countA)
        }
    }

    @Test
    fun rejectsInvalidMovesAndMissingSteps() {
        val current = draft(countA, timerB)

        assertThrows(DraftException.InvalidMove::class.java) {
            current.moveStep(0, 5)
        }
        assertThrows(DraftException.StepNotFound::class.java) {
            current.deleteStep("missing")
        }
        assertThrows(DraftException.StepNotFound::class.java) {
            current.updateStep(countC)
        }
    }

    @Test
    fun createsDraftFromRoutineAndPreservesIdentityAndFields() {
        val routine = Routine(
            id = "routine-1",
            name = "Morning",
            version = 1,
            repeatCount = 3,
            steps = listOf(countA, timerB),
            createdAt = 10L,
            updatedAt = 20L
        )

        val draft = RoutineDraft.from(routine)

        assertEquals(routine.id, draft.id)
        assertEquals(routine.name, draft.name)
        assertEquals(routine.version, draft.version)
        assertEquals(routine.repeatCount, draft.repeatCount)
        assertEquals(routine.steps.map { it.id }, draft.steps.map { it.id })
        assertEquals(routine.createdAt, draft.createdAt)
        assertEquals(routine.updatedAt, draft.updatedAt)
        assertEquals(routine, draft.toRoutine())
    }

    @Test
    fun newDraftUsesSchemaVersionAndStableStepIds() {
        val created = RoutineDraft.new(
            name = "New routine",
            steps = listOf(countA, timerB),
            id = "new-draft",
            now = 5_000L
        )

        assertEquals(Routine.CURRENT_SCHEMA_VERSION, created.version)
        assertEquals("new-draft", created.id)
        assertEquals(listOf("a", "b"), created.steps.map { it.id })
        assertEquals(5_000L, created.createdAt)
        assertEquals(5_000L, created.updatedAt)
    }

    @Test
    fun changesRoutineNameAndRepeatCount() {
        val updated = draft(countA)
            .rename("Evening")
            .setRepeatCount(4)

        assertEquals("Evening", updated.name)
        assertEquals(4, updated.repeatCount)
        assertEquals(listOf("a"), updated.steps.map { it.id })
    }

    @Test
    fun rejectsTimerWithCountFieldsAndCountWithTimerFields() {
        val badTimer = RoutineStep.timer(
            id = "bad-timer",
            name = "Read",
            durationMs = 30_000L
        ).copy(target = 10, countMode = CountMode.INCREMENT)

        val badCount = RoutineStep.count(
            id = "bad-count",
            name = "Push-ups",
            target = 10
        ).copy(durationMs = 30_000L, category = TimerCategory.REST)

        assertTrue(
            badTimer.validationErrors().contains(ValidationError.TIMER_HAS_COUNT_FIELDS)
        )
        assertTrue(
            badCount.validationErrors().contains(ValidationError.COUNT_HAS_TIMER_FIELDS)
        )
        assertThrows(DraftException.InvalidStep::class.java) {
            draft(countA).addStep(badTimer)
        }
    }

    @Test
    fun defensiveCopiesCallerOwnedStepLists() {
        val draftSource = mutableListOf(countA)
        val createdDraft = RoutineDraft.new(
            name = "Copied",
            steps = draftSource,
            id = "copied-draft",
            now = 1_000L
        )
        draftSource.add(timerB)
        assertEquals(1, createdDraft.steps.size)
        assertEquals(listOf("a"), createdDraft.steps.map { it.id })

        val routineSource = mutableListOf(countA)
        val routine = Routine(
            id = "routine-copied",
            name = "Copied",
            steps = routineSource,
            createdAt = 0L,
            updatedAt = 0L
        )
        routineSource.add(timerB)
        assertEquals(1, routine.steps.size)

        val fromRoutine = RoutineDraft.from(routine)
        routineSource.add(countC)
        assertEquals(1, fromRoutine.steps.size)
        assertEquals(listOf("a"), fromRoutine.steps.map { it.id })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun exposedStepListsCannotBeMutatedByCasting() {
        val current = draft(countA)

        assertThrows(UnsupportedOperationException::class.java) {
            (current.steps as MutableList<RoutineStep>).add(timerB)
        }

        val routine = current.toRoutine()
        assertThrows(UnsupportedOperationException::class.java) {
            (routine.steps as MutableList<RoutineStep>).add(timerB)
        }
    }

    @Test
    fun toRoutineDoesNotShareStorageWithDraft() {
        val current = draft(countA)
        val routine = current.toRoutine()
        val edited = current.addStep(timerB)

        assertEquals(1, routine.steps.size)
        assertEquals(2, edited.steps.size)

        assertNotSame(current.steps, routine.steps)
        assertNotSame(current.steps, edited.steps)
    }

    @Test
    fun oldDraftRemainsUnchangedAfterEveryMutation() {
        val original = draft(countA, timerB)
        val originalIds = original.steps.map { it.id }

        val added = original.addStep(countC)
        assertEquals(2, original.steps.size)
        assertEquals(originalIds, original.steps.map { it.id })
        assertEquals(3, added.steps.size)

        val updated = original.updateStep(countA.copy(name = "Changed"))
        assertEquals(originalIds, original.steps.map { it.id })
        assertEquals("Push-ups", original.steps.first { it.id == "a" }.name)
        assertEquals("Changed", updated.steps.first { it.id == "a" }.name)

        val deleted = original.deleteStep("a")
        assertEquals(originalIds, original.steps.map { it.id })
        assertEquals(listOf("b"), deleted.steps.map { it.id })

        val moved = original.moveStep(0, 1)
        assertEquals(originalIds, original.steps.map { it.id })
        assertEquals(listOf("b", "a"), moved.steps.map { it.id })

        val duplicated = original.duplicateStep("a")
        assertEquals(2, original.steps.size)
        assertEquals(3, duplicated.steps.size)
        assertEquals(originalIds, original.steps.map { it.id })
    }

    @Test
    fun preservesIdsForTargetDurationAndBothTypeChanges() {
        var current = draft(countA, timerB, countC)

        current = current.updateStep(countA.copy(target = 30))
        assertEquals("a", current.steps.first { it.id == "a" }.id)
        assertEquals(30, current.steps.first { it.id == "a" }.target)

        current = current.updateStep(timerB.copy(durationMs = 90_000L))
        assertEquals("b", current.steps.first { it.id == "b" }.id)
        assertEquals(90_000L, current.steps.first { it.id == "b" }.durationMs)

        current = current.updateStep(
            RoutineStep.count(
                id = "b",
                name = "Read as count",
                target = 5
            )
        )
        assertEquals("b", current.steps.first { it.id == "b" }.id)
        assertEquals(StepType.COUNT, current.steps.first { it.id == "b" }.type)

        current = current.updateStep(
            RoutineStep.timer(
                id = "a",
                name = "Push-ups as timer",
                durationMs = 30_000L
            )
        )
        assertEquals("a", current.steps.first { it.id == "a" }.id)
        assertEquals(StepType.TIMER, current.steps.first { it.id == "a" }.type)
        assertEquals(listOf("a", "b", "c"), current.steps.map { it.id })
    }

    @Test
    fun duplicatePreservesAllStepFieldsExceptId() {
        val complete = RoutineStep.count(
            id = "complete",
            name = "Chin-ups",
            target = 12,
            countMode = CountMode.COMPLETE,
            repeatCount = 3
        )
        val completeCopy = draft(complete)
            .duplicateStep("complete")
            .steps
            .first { it.id != "complete" }

        assertNotEquals("complete", completeCopy.id)
        assertEquals(complete.name, completeCopy.name)
        assertEquals(complete.type, completeCopy.type)
        assertEquals(complete.target, completeCopy.target)
        assertEquals(complete.countMode, completeCopy.countMode)
        assertEquals(complete.durationMs, completeCopy.durationMs)
        assertEquals(complete.category, completeCopy.category)
        assertEquals(complete.repeatCount, completeCopy.repeatCount)

        val rest = RoutineStep.timer(
            id = "rest",
            name = "Deep rest",
            durationMs = 45_000L,
            category = TimerCategory.REST,
            repeatCount = 4
        )
        val restCopy = draft(rest)
            .duplicateStep("rest")
            .steps
            .first { it.id != "rest" }

        assertNotEquals("rest", restCopy.id)
        assertEquals(rest.name, restCopy.name)
        assertEquals(rest.type, restCopy.type)
        assertEquals(rest.target, restCopy.target)
        assertEquals(rest.countMode, restCopy.countMode)
        assertEquals(rest.durationMs, restCopy.durationMs)
        assertEquals(rest.category, restCopy.category)
        assertEquals(rest.repeatCount, restCopy.repeatCount)
    }

    @Test
    fun handlesMoveBoundaries() {
        val fourth = RoutineStep.count(id = "d", name = "Plank", target = 1)
        val fourSteps = draft(countA, timerB, countC, fourth)

        val firstToLast = fourSteps.moveStep(0, 3)
        assertEquals(listOf("b", "c", "d", "a"), firstToLast.steps.map { it.id })

        val lastToFirst = fourSteps.moveStep(3, 0)
        assertEquals(listOf("d", "a", "b", "c"), lastToFirst.steps.map { it.id })

        val same = fourSteps.moveStep(1, 1)
        assertEquals(listOf("a", "b", "c", "d"), same.steps.map { it.id })

        assertThrows(DraftException.InvalidMove::class.java) {
            RoutineDraft.new(id = "empty", now = 1_000L).moveStep(0, 0)
        }

        val single = draft(countA)
        val singleSame = single.moveStep(0, 0)
        assertEquals(listOf("a"), singleSame.steps.map { it.id })
        assertThrows(DraftException.InvalidMove::class.java) {
            single.moveStep(0, 1)
        }
    }

    @Test
    fun tracksTimestampsAndSchemaVersion() {
        val created = RoutineDraft.new(
            name = "Timed",
            id = "timed",
            now = 1_000L
        )
        assertEquals(1_000L, created.createdAt)
        assertEquals(1_000L, created.updatedAt)
        assertEquals(Routine.CURRENT_SCHEMA_VERSION, created.version)

        val edited = created
            .rename("Timed edited")
            .addStep(countA)
        assertEquals(1_000L, edited.createdAt)
        assertTrue(edited.updatedAt >= created.updatedAt)
        assertEquals(Routine.CURRENT_SCHEMA_VERSION, edited.version)

        val routine = edited.toRoutine()
        assertEquals(edited.createdAt, routine.createdAt)
        assertEquals(edited.updatedAt, routine.updatedAt)
        assertEquals(edited.version, routine.version)

        val loaded = Routine(
            id = "loaded",
            name = "Loaded",
            version = 7,
            steps = listOf(countA),
            createdAt = 10L,
            updatedAt = 20L
        )
        val fromLoaded = RoutineDraft.from(loaded)
        assertEquals(7, fromLoaded.version)
        assertEquals(10L, fromLoaded.createdAt)
        assertEquals(20L, fromLoaded.updatedAt)

        val loadedEdited = fromLoaded.rename("Loaded edited")
        assertEquals(7, loadedEdited.version)
        assertEquals(10L, loadedEdited.createdAt)
        assertTrue(loadedEdited.updatedAt >= fromLoaded.updatedAt)
    }


    @Test
    fun trimsStepNameOnAddAndUpdate() {
        val original = draft(countA)

        val added = original.addStep(
            RoutineStep.count(id = "d", name = "\u00A0Plank\u2007", target = 1)
        )
        assertEquals("Plank", added.steps.last().name)

        val updated = original.updateStep(
            countA.copy(name = "  Push  ups edited  ")
        )
        assertEquals("Push  ups edited", updated.steps.single().name)

        val internal = original.updateStep(
            countA.copy(name = "\u3000Push  ups\u202F")
        )
        assertEquals("Push  ups", internal.steps.single().name)

        assertEquals("Push-ups", original.steps.single().name)
    }

    @Test
    fun rejectsStepNameThatBecomesBlankAfterTrim() {
        val original = draft(countA)

        assertThrows(DraftException.InvalidStep::class.java) {
            original.updateStep(countA.copy(name = " \u00A0\u2007\u202F "))
        }
    }

}
