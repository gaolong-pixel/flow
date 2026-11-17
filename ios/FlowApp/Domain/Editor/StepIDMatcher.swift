import Foundation

struct CandidateMatch: Equatable {
    let candidate: StepCandidate
    let id: String?
}

/// Deterministic ID matcher.
///
/// Phase 1 matches full semantic keys in FIFO order, independent of position.
/// Phase 2 uses an LCS over the semantic line keys of the full old/new
/// sequences to find replacement regions, then pairs remaining steps in order
/// only when their types match.
struct StepIDMatcher {

    private struct SemanticKey: Equatable {
        let type: StepType
        let name: String
        let target: Int?
        let countMode: CountMode?
        let durationMs: Int64?
        let category: TimerCategory?
        let repeatCount: Int
    }

    func match(
        oldSteps: [RoutineStep],
        newCandidates: [StepCandidate]
    ) -> [CandidateMatch] {
        let oldKeys = oldSteps.map(keyOf)
        let newKeys = newCandidates.map(keyOf)
        var oldUsed = Array(repeating: false, count: oldSteps.count)
        var ids = Array<String?>(repeating: nil, count: newCandidates.count)

        // Phase 1: full semantic exact match, FIFO, position-independent.
        for newIndex in newCandidates.indices {
            let key = newKeys[newIndex]
            if let oldIndex = oldSteps.indices.first(where: { index in
                !oldUsed[index] && oldKeys[index] == key
            }) {
                ids[newIndex] = oldSteps[oldIndex].id
                oldUsed[oldIndex] = true
            }
        }

        // Phase 2: LCS anchors define replacement regions.
        let lcs = buildLCSTable(oldKeys: oldKeys, newKeys: newKeys)
        var oldIndex = 0
        var newIndex = 0
        var oldRegionStart = 0
        var newRegionStart = 0

        while oldIndex < oldSteps.count && newIndex < newCandidates.count {
            if oldKeys[oldIndex] == newKeys[newIndex] {
                assignRegion(
                    oldStart: oldRegionStart,
                    oldEnd: oldIndex,
                    newStart: newRegionStart,
                    newEnd: newIndex,
                    oldSteps: oldSteps,
                    newCandidates: newCandidates,
                    oldUsed: &oldUsed,
                    ids: &ids
                )
                oldIndex += 1
                newIndex += 1
                oldRegionStart = oldIndex
                newRegionStart = newIndex
            } else if lcs[oldIndex + 1][newIndex] >= lcs[oldIndex][newIndex + 1] {
                oldIndex += 1
            } else {
                newIndex += 1
            }
        }

        assignRegion(
            oldStart: oldRegionStart,
            oldEnd: oldSteps.count,
            newStart: newRegionStart,
            newEnd: newCandidates.count,
            oldSteps: oldSteps,
            newCandidates: newCandidates,
            oldUsed: &oldUsed,
            ids: &ids
        )

        return newCandidates.indices.map { index in
            CandidateMatch(candidate: newCandidates[index], id: ids[index])
        }
    }

    private func assignRegion(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        oldSteps: [RoutineStep],
        newCandidates: [StepCandidate],
        oldUsed: inout [Bool],
        ids: inout [String?]
    ) {
        let remainingOld = (oldStart..<oldEnd).filter { !oldUsed[$0] }
        let remainingNew = (newStart..<newEnd).filter { ids[$0] == nil }
        let pairCount = min(remainingOld.count, remainingNew.count)

        guard pairCount > 0 else { return }
        for offset in 0..<pairCount {
            let oldIndex = remainingOld[offset]
            let newIndex = remainingNew[offset]
            if oldSteps[oldIndex].type == newCandidates[newIndex].type &&
                !oldUsed[oldIndex] &&
                ids[newIndex] == nil {
                ids[newIndex] = oldSteps[oldIndex].id
                oldUsed[oldIndex] = true
            }
        }
    }

    private func buildLCSTable(
        oldKeys: [SemanticKey],
        newKeys: [SemanticKey]
    ) -> [[Int]] {
        var table = Array(
            repeating: Array(repeating: 0, count: newKeys.count + 1),
            count: oldKeys.count + 1
        )

        if !oldKeys.isEmpty && !newKeys.isEmpty {
            for oldIndex in stride(from: oldKeys.count - 1, through: 0, by: -1) {
                for newIndex in stride(from: newKeys.count - 1, through: 0, by: -1) {
                    if oldKeys[oldIndex] == newKeys[newIndex] {
                        table[oldIndex][newIndex] = table[oldIndex + 1][newIndex + 1] + 1
                    } else {
                        table[oldIndex][newIndex] = max(
                            table[oldIndex + 1][newIndex],
                            table[oldIndex][newIndex + 1]
                        )
                    }
                }
            }
        }
        return table
    }

    private func keyOf(_ step: RoutineStep) -> SemanticKey {
        SemanticKey(
            type: step.type,
            name: step.name,
            target: step.target,
            countMode: step.countMode,
            durationMs: step.durationMs,
            category: step.category,
            repeatCount: step.repeatCount
        )
    }

    private func keyOf(_ candidate: StepCandidate) -> SemanticKey {
        SemanticKey(
            type: candidate.type,
            name: candidate.name,
            target: candidate.target,
            countMode: candidate.countMode,
            durationMs: candidate.durationMs,
            category: candidate.category,
            repeatCount: candidate.repeatCount
        )
    }
}
