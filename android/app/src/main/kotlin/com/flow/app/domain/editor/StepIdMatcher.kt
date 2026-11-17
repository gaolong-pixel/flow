package com.flow.app.domain.editor

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.parser.StepCandidate

data class CandidateMatch(
    val candidate: StepCandidate,
    val id: String?
)

/**
 * Deterministic ID matcher.
 *
 * Phase 1 matches full semantic keys in FIFO order, independent of position.
 * Phase 2 uses an LCS over the semantic line keys of the full old/new
 * sequences to find replacement regions, then pairs remaining steps in order
 * only when their types match.
 */
class StepIdMatcher {

    private data class SemanticKey(
        val type: StepType,
        val name: String,
        val target: Int?,
        val countMode: CountMode?,
        val durationMs: Long?,
        val category: TimerCategory?,
        val repeatCount: Int
    )

    fun match(
        oldSteps: List<RoutineStep>,
        newCandidates: List<StepCandidate>
    ): List<CandidateMatch> {
        val oldKeys = oldSteps.map(::keyOf)
        val newKeys = newCandidates.map(::keyOf)
        val oldUsed = BooleanArray(oldSteps.size)
        val ids = arrayOfNulls<String>(newCandidates.size)

        // Phase 1: full semantic exact match, FIFO, position-independent.
        newCandidates.indices.forEach { newIndex ->
            val key = newKeys[newIndex]
            val oldIndex = oldSteps.indices.firstOrNull { index ->
                !oldUsed[index] && oldKeys[index] == key
            }
            if (oldIndex != null) {
                ids[newIndex] = oldSteps[oldIndex].id
                oldUsed[oldIndex] = true
            }
        }

        // Phase 2: LCS anchors define replacement regions.
        val lcs = buildLcsTable(oldKeys, newKeys)
        var oldIndex = 0
        var newIndex = 0
        var oldRegionStart = 0
        var newRegionStart = 0

        while (oldIndex < oldSteps.size && newIndex < newCandidates.size) {
            if (oldKeys[oldIndex] == newKeys[newIndex]) {
                assignRegion(
                    oldStart = oldRegionStart,
                    oldEnd = oldIndex,
                    newStart = newRegionStart,
                    newEnd = newIndex,
                    oldSteps = oldSteps,
                    newCandidates = newCandidates,
                    oldUsed = oldUsed,
                    ids = ids
                )
                oldIndex++
                newIndex++
                oldRegionStart = oldIndex
                newRegionStart = newIndex
            } else if (lcs[oldIndex + 1][newIndex] >= lcs[oldIndex][newIndex + 1]) {
                oldIndex++
            } else {
                newIndex++
            }
        }

        assignRegion(
            oldStart = oldRegionStart,
            oldEnd = oldSteps.size,
            newStart = newRegionStart,
            newEnd = newCandidates.size,
            oldSteps = oldSteps,
            newCandidates = newCandidates,
            oldUsed = oldUsed,
            ids = ids
        )

        return newCandidates.indices.map { index ->
            CandidateMatch(newCandidates[index], ids[index])
        }
    }

    private fun assignRegion(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        oldSteps: List<RoutineStep>,
        newCandidates: List<StepCandidate>,
        oldUsed: BooleanArray,
        ids: Array<String?>
    ) {
        val remainingOld = (oldStart until oldEnd).filter { !oldUsed[it] }
        val remainingNew = (newStart until newEnd).filter { ids[it] == null }
        val pairCount = minOf(remainingOld.size, remainingNew.size)

        for (offset in 0 until pairCount) {
            val oldIndex = remainingOld[offset]
            val newIndex = remainingNew[offset]
            if (oldSteps[oldIndex].type == newCandidates[newIndex].type &&
                !oldUsed[oldIndex] &&
                ids[newIndex] == null
            ) {
                ids[newIndex] = oldSteps[oldIndex].id
                oldUsed[oldIndex] = true
            }
        }
    }

    private fun buildLcsTable(
        oldKeys: List<SemanticKey>,
        newKeys: List<SemanticKey>
    ): Array<IntArray> {
        val table = Array(oldKeys.size + 1) { IntArray(newKeys.size + 1) }
        for (oldIndex in oldKeys.indices.reversed()) {
            for (newIndex in newKeys.indices.reversed()) {
                table[oldIndex][newIndex] = if (oldKeys[oldIndex] == newKeys[newIndex]) {
                    table[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(table[oldIndex + 1][newIndex], table[oldIndex][newIndex + 1])
                }
            }
        }
        return table
    }

    private fun keyOf(step: RoutineStep): SemanticKey = SemanticKey(
        type = step.type,
        name = step.name,
        target = step.target,
        countMode = step.countMode,
        durationMs = step.durationMs,
        category = step.category,
        repeatCount = step.repeatCount
    )

    private fun keyOf(candidate: StepCandidate): SemanticKey = SemanticKey(
        type = candidate.type,
        name = candidate.name,
        target = candidate.target,
        countMode = candidate.countMode,
        durationMs = candidate.durationMs,
        category = candidate.category,
        repeatCount = candidate.repeatCount
    )
}
