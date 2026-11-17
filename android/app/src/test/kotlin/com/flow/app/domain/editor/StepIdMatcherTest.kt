package com.flow.app.domain.editor

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.parser.StepCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepIdMatcherTest {

    private val matcher = StepIdMatcher()

    @Test
    fun preservesIdForSingleFieldModification() {
        assertId(
            "target",
            old = listOf(count("a", "Push-ups", 15)),
            new = listOf(countCandidate("Push-ups", 20)),
            expected = listOf("a")
        )
        assertId(
            "name",
            old = listOf(count("a", "Push-ups", 15)),
            new = listOf(countCandidate("Push-ups edited", 15)),
            expected = listOf("a")
        )
        assertId(
            "repeat",
            old = listOf(count("a", "Push-ups", 15, repeat = 1)),
            new = listOf(countCandidate("Push-ups", 15, repeat = 3)),
            expected = listOf("a")
        )
        assertId(
            "count mode",
            old = listOf(count("a", "Push-ups", 15, mode = CountMode.INCREMENT)),
            new = listOf(countCandidate("Push-ups", 15, mode = CountMode.COMPLETE)),
            expected = listOf("a")
        )
        assertId(
            "duration",
            old = listOf(timer("a", "Read", 60_000)),
            new = listOf(timerCandidate("Read", 90_000)),
            expected = listOf("a")
        )
        assertId(
            "category",
            old = listOf(timer("a", "Stretch", 45_000, TimerCategory.NORMAL)),
            new = listOf(timerCandidate("Stretch", 45_000, TimerCategory.REST)),
            expected = listOf("a")
        )
    }

    @Test
    fun insertionDoesNotChangeOtherIds() {
        assertId(
            listOf("insert"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2)
            ),
            new = listOf(
                countCandidate("A", 1),
                countCandidate("X", 9),
                countCandidate("B", 2)
            ),
            expected = listOf("a", null, "b")
        )
    }

    @Test
    fun deletionDoesNotChangeOtherIds() {
        assertId(
            listOf("delete first", "delete middle"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("B", 2),
                countCandidate("C", 3)
            ),
            expected = listOf("b", "c")
        )
        assertId(
            listOf("delete middle only"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("A", 1),
                countCandidate("C", 3)
            ),
            expected = listOf("a", "c")
        )
    }

    @Test
    fun movePreservesIdsByContent() {
        assertId(
            listOf("move"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("C", 3),
                countCandidate("A", 1),
                countCandidate("B", 2)
            ),
            expected = listOf("c", "a", "b")
        )
    }

    @Test
    fun duplicateSemanticStepsUseStableFifo() {
        assertId(
            listOf("duplicates"),
            old = listOf(
                count("a", "Same", 5),
                count("b", "Same", 5)
            ),
            new = listOf(
                countCandidate("Same", 5),
                countCandidate("Same", 5)
            ),
            expected = listOf("a", "b")
        )
    }

    @Test
    fun multipleSameTypeModificationsInOneRegionKeepOrder() {
        assertId(
            listOf("multiple same-type edits"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2)
            ),
            new = listOf(
                countCandidate("A edited", 10),
                countCandidate("B edited", 20)
            ),
            expected = listOf("a", "b")
        )
    }

    @Test
    fun typeChangeDoesNotInheritId() {
        assertId(
            listOf("count to timer"),
            old = listOf(count("a", "Same", 5)),
            new = listOf(timerCandidate("Same", 60_000)),
            expected = listOf(null)
        )
        assertId(
            listOf("timer to count"),
            old = listOf(timer("a", "Same", 60_000)),
            new = listOf(countCandidate("Same", 5)),
            expected = listOf(null)
        )
    }

    @Test
    fun differentTypesInRegionDoNotPairByPosition() {
        assertId(
            listOf("mixed types"),
            old = listOf(
                count("a", "A", 1),
                timer("b", "B", 60_000)
            ),
            new = listOf(
                timerCandidate("A", 60_000),
                countCandidate("B", 2)
            ),
            expected = listOf(null, null)
        )
    }

    @Test
    fun multipleChangesKeepUnchangedAnchors() {
        assertId(
            listOf("multiple edits"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("A edited", 10),
                countCandidate("B", 2),
                countCandidate("C edited", 30)
            ),
            expected = listOf("a", "b", "c")
        )
    }

    @Test
    fun emptyNewTextDeletesAllSteps() {
        val result = matcher.match(
            oldSteps = listOf(
                count("a", "A", 1),
                timer("b", "B", 1_000)
            ),
            newCandidates = emptyList()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun modificationAdjacentToInsertionKeepsTheModifiedId() {
        assertId(
            listOf("edit plus insert"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("A", 1),
                countCandidate("B edited", 20),
                countCandidate("X", 9),
                countCandidate("C", 3)
            ),
            expected = listOf("a", "b", null, "c")
        )
    }

    @Test
    fun moveAndModifyUsesBestEffortReplacementMatching() {
        assertId(
            listOf("move and edit"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("C", 3),
                countCandidate("A", 1),
                countCandidate("B edited", 20)
            ),
            expected = listOf("c", "a", null)
        )
    }

    @Test
    fun insertedStepDoesNotStealFollowingIds() {
        assertId(
            listOf("middle insert"),
            old = listOf(
                count("a", "A", 1),
                count("b", "B", 2),
                count("c", "C", 3)
            ),
            new = listOf(
                countCandidate("A", 1),
                countCandidate("B", 2),
                countCandidate("X", 9),
                countCandidate("C", 3)
            ),
            expected = listOf("a", "b", null, "c")
        )
    }

    @Test
    fun noOldIdIsUsedTwice() {
        val matches = matcher.match(
            oldSteps = listOf(
                count("a", "A", 1),
                count("b", "B", 2)
            ),
            newCandidates = listOf(
                countCandidate("A", 1),
                countCandidate("A", 1),
                countCandidate("B", 2)
            )
        )
        val ids = matches.mapNotNull { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    private fun assertId(
        labels: Any,
        old: List<RoutineStep>,
        new: List<StepCandidate>,
        expected: List<String?>
    ) {
        val result = matcher.match(old, new)
        assertEquals(labels.toString(), expected, result.map { it.id })
    }

    private fun assertId(
        label: String,
        old: List<RoutineStep>,
        new: List<StepCandidate>,
        expected: List<String?>
    ) = assertId(label as Any, old, new, expected)

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

    private fun timerCandidate(
        name: String,
        durationMs: Long,
        category: TimerCategory = TimerCategory.NORMAL,
        repeat: Int = 1
    ) = StepCandidate(
        name = name,
        type = StepType.TIMER,
        durationMs = durationMs,
        category = category,
        repeatCount = repeat
    )
}
