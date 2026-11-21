package com.flow.app.data

import com.flow.app.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlanStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun roundTripKeepsOnlyPlanFields() {
        val store = PlanStore(File(temp.root, "plans.json"))
        val plan = Routine(
            id = "p", name = "P", repeatCount = 7,
            steps = listOf(
                RoutineStep("c", "Push", StepType.COUNT, target = 15, countMode = CountMode.INCREMENT, repeatCount = 9),
                RoutineStep("t", "Rest", StepType.TIMER, durationMs = 60_000, category = TimerCategory.REST, repeatCount = 5)
            ), createdAt = 1, updatedAt = 2
        )
        assertTrue(store.save(plan))
        val loaded = store.loadAll().single()
        assertEquals(1, loaded.repeatCount)
        assertEquals(15, loaded.steps[0].target)
        assertEquals(CountMode.COMPLETE, loaded.steps[0].countMode)
        assertEquals(1, loaded.steps[0].repeatCount)
        assertEquals(TimerCategory.NORMAL, loaded.steps[1].category)
        assertEquals(1, loaded.steps[1].repeatCount)
    }

    @Test fun renameUpdatesSamePlanWithoutChangingIdsOrNodes() {
        val store = PlanStore(File(temp.root, "plans.json"))
        val plan = Routine(
            id = "p", name = "Old name",
            steps = listOf(
                RoutineStep("c", "Push", StepType.COUNT, target = 15, countMode = CountMode.COMPLETE),
                RoutineStep("t", "Rest", StepType.TIMER, durationMs = 60_000, category = TimerCategory.NORMAL)
            ), createdAt = 1, updatedAt = 2
        )
        assertTrue(store.save(plan))
        val renamed = Routine(
            id = plan.id, name = "New name", version = plan.version, repeatCount = plan.repeatCount,
            steps = plan.steps, createdAt = plan.createdAt, updatedAt = 3
        )
        assertTrue(store.save(renamed))
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("New name", loaded.single().name)
        assertEquals(listOf("c", "t"), loaded.single().steps.map { it.id })
        assertEquals(15, loaded.single().steps[0].target)
        assertEquals(60_000L, loaded.single().steps[1].durationMs)
    }

    @Test fun emptyPlanIsRejectedAndDoesNotCreateAFile() {
        val file = File(temp.root, "plans.json")
        val store = PlanStore(file)
        val empty = Routine(id = "empty", name = "Empty", steps = emptyList(), createdAt = 1, updatedAt = 1)
        assertFalse(store.save(empty))
        assertFalse(file.exists())
        assertTrue(store.loadAll().isEmpty())
    }

    @Test fun emptyPlanCannotOverwriteAnExistingPlan() {
        val store = PlanStore(File(temp.root, "plans.json"))
        val valid = Routine(
            id = "p", name = "Valid",
            steps = listOf(RoutineStep("c", "Push", StepType.COUNT, target = 3, countMode = CountMode.COMPLETE)),
            createdAt = 1, updatedAt = 1
        )
        assertTrue(store.save(valid))
        val empty = Routine(id = "p", name = "Valid", steps = emptyList(), createdAt = 1, updatedAt = 2)
        assertFalse(store.save(empty))
        val loaded = store.loadAll().single()
        assertEquals(1, loaded.steps.size)
        assertEquals("Push", loaded.steps.single().name)
    }

    @Test fun corruptFileIsRejectedWithoutOverwrite() {
        val file = File(temp.root, "plans.json").also { it.writeText("{bad") }
        val store = PlanStore(file)
        assertThrows(IllegalStateException::class.java) { store.loadAll() }
        assertEquals("{bad", file.readText())
    }
}
