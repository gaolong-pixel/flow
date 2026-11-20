import Foundation

/// Synchronous, deterministic editor controller. It owns the only mutable
/// reference (the immutable state) and replaces it only after a complete
/// operation, including serialization and parsing, succeeds.
final class EditorController {

    private(set) var state: EditorState
    private let parser: RoutineTextParser
    private let serializer: RoutineTextSerializer
    private let matcher: StepIDMatcher

    init(
        initialDraft: RoutineDraft,
        initialMode: EditorMode = .text,
        parser: RoutineTextParser = RoutineTextParser(),
        serializer: RoutineTextSerializer = RoutineTextSerializer(),
        matcher: StepIDMatcher = StepIDMatcher()
    ) throws {
        self.parser = parser
        self.serializer = serializer
        self.matcher = matcher

        let canonical = try serializer.serialize(initialDraft.steps.map { Self.candidate(of: $0) })
        let parsed = parser.parse(canonical)
        guard !parsed.hasBlockingErrors else {
            throw EditorError.serializationFailed
        }
        state = EditorState(
            mode: initialMode,
            draft: initialDraft,
            textBuffer: canonical,
            parseResult: parsed,
            syncStatus: .synced,
            lastCommittedSteps: initialDraft.steps,
            lastCommittedText: canonical
        )
    }

    @discardableResult
    func onTextChanged(_ text: String) -> EditorState {
        guard state.mode == .text else { return state }

        let parsed = parser.parse(text)
        let syncStatus: SyncStatus
        if parsed.hasBlockingErrors {
            syncStatus = .invalid
        } else if text == state.lastCommittedText {
            syncStatus = .synced
        } else {
            syncStatus = .dirty
        }

        state = EditorState(
            mode: state.mode,
            draft: state.draft,
            textBuffer: text,
            parseResult: parsed,
            syncStatus: syncStatus,
            lastCommittedSteps: state.lastCommittedSteps,
            lastCommittedText: state.lastCommittedText
        )
        return state
    }

    func commitText() -> EditorResult {
        guard state.mode == .text else {
            return .failure(.wrongMode, state)
        }
        guard !state.parseResult.hasBlockingErrors else {
            return .failure(.textBlocked, state)
        }

        do {
            let matches = matcher.match(
                oldSteps: state.lastCommittedSteps,
                newCandidates: state.parseResult.steps
            )
            let newSteps = matches.map { match in
                step(
                    from: match.candidate,
                    id: match.id ?? UUID().uuidString
                )
            }
            let newDraft = try RoutineDraft(
                id: state.draft.id,
                name: state.draft.name,
                version: state.draft.version,
                repeatCount: state.draft.repeatCount,
                steps: newSteps,
                createdAt: state.draft.createdAt,
                updatedAt: newSteps == state.draft.steps
                    ? state.draft.updatedAt
                    : nowMillis()
            ).validated()
            return publish(newDraft)
        } catch let error as DraftError {
            return .failure(mapDraftError(error), state)
        } catch {
            return .failure(.serializationFailed, state)
        }
    }

    /// Renames the routine without touching step IDs, order or step fields.
    /// The text buffer is left untouched because the name is not part of the
    /// step text syntax; the updated draft name is carried into persistence.
    func rename(_ newName: String) -> EditorResult {
        let trimmed = newName.trimmingFlowWhitespace
        guard !trimmed.isEmpty else {
            return .failure(.invalidName, state)
        }
        guard trimmed.count <= Self.maxNameLength else {
            return .failure(.nameTooLong, state)
        }
        guard trimmed != state.draft.name else {
            return .success(state)
        }

        let renamed = state.draft.renaming(trimmed)
        state = EditorState(
            mode: state.mode,
            draft: renamed,
            textBuffer: state.textBuffer,
            parseResult: state.parseResult,
            syncStatus: state.syncStatus,
            lastCommittedSteps: state.lastCommittedSteps,
            lastCommittedText: state.lastCommittedText
        )
        return .success(state)
    }

    static let maxNameLength = 60

    func addStep(_ candidate: StepCandidate) -> EditorResult {
        cardOperation {
            try state.draft.addingStep(
                step(from: candidate, id: UUID().uuidString)
            )
        }
    }

    func updateStep(_ step: RoutineStep) -> EditorResult {
        cardOperation {
            try state.draft.updatingStep(step)
        }
    }

    func updateStepType(
        id: String,
        type: StepType,
        target: Int? = nil,
        countMode: CountMode? = nil,
        durationMs: Int64? = nil,
        category: TimerCategory? = nil
    ) -> EditorResult {
        cardOperation {
            guard let existing = state.draft.steps.first(where: { $0.id == id }) else {
                throw DraftError.stepNotFound(id)
            }
            let updated = RoutineStep(
                id: existing.id,
                name: existing.name,
                type: type,
                target: type == .count ? target : nil,
                countMode: type == .count ? countMode : nil,
                durationMs: type == .timer ? durationMs : nil,
                category: type == .timer ? category : nil,
                repeatCount: existing.repeatCount
            )
            return try state.draft.updatingStep(updated)
        }
    }

    func deleteStep(id: String) -> EditorResult {
        cardOperation {
            try state.draft.deletingStep(id: id)
        }
    }

    func moveStep(from: Int, to: Int) -> EditorResult {
        cardOperation {
            try state.draft.movingStep(from: from, to: to)
        }
    }

    func duplicateStep(id: String) -> EditorResult {
        cardOperation {
            try state.draft.duplicatingStep(id: id)
        }
    }

    func clearSteps() -> EditorResult {
        cardOperation {
            if state.draft.steps.isEmpty {
                return state.draft
            }
            return try RoutineDraft(
                id: state.draft.id,
                name: state.draft.name,
                version: state.draft.version,
                repeatCount: state.draft.repeatCount,
                steps: [],
                createdAt: state.draft.createdAt,
                updatedAt: nowMillis()
            ).validated()
        }
    }

    func switchMode(_ targetMode: EditorMode) -> EditorResult {
        if targetMode == state.mode {
            return .success(state)
        }

        switch targetMode {
        case .cards:
            guard state.syncStatus == .synced && !state.parseResult.hasBlockingErrors else {
                return .failure(.textBlocked, state)
            }
            let next = EditorState(
                mode: .cards,
                draft: state.draft,
                textBuffer: state.textBuffer,
                parseResult: state.parseResult,
                syncStatus: state.syncStatus,
                lastCommittedSteps: state.lastCommittedSteps,
                lastCommittedText: state.lastCommittedText
            )
            state = next
            return .success(next)

        case .text:
            let published = publish(state.draft)
            if case .failure = published {
                return published
            }
            let next = EditorState(
                mode: .text,
                draft: state.draft,
                textBuffer: state.textBuffer,
                parseResult: state.parseResult,
                syncStatus: state.syncStatus,
                lastCommittedSteps: state.lastCommittedSteps,
                lastCommittedText: state.lastCommittedText
            )
            state = next
            return .success(next)
        }
    }

    func restoreLastCommitted() -> EditorResult {
        do {
            let restored = try RoutineDraft(
                id: state.draft.id,
                name: state.draft.name,
                version: state.draft.version,
                repeatCount: state.draft.repeatCount,
                steps: state.lastCommittedSteps,
                createdAt: state.draft.createdAt,
                updatedAt: nowMillis()
            ).validated()
            return publish(restored)
        } catch let error as DraftError {
            return .failure(mapDraftError(error), state)
        } catch {
            return .failure(.serializationFailed, state)
        }
    }

    private func cardOperation(_ block: () throws -> RoutineDraft) -> EditorResult {
        guard state.mode == .cards else {
            return .failure(.wrongMode, state)
        }

        do {
            let nextDraft = try block()
            if nextDraft == state.draft {
                return .success(state)
            }
            return publish(nextDraft)
        } catch let error as DraftError {
            return .failure(mapDraftError(error), state)
        } catch {
            return .failure(.serializationFailed, state)
        }
    }

    private func publish(_ draft: RoutineDraft) -> EditorResult {
        do {
            let canonical = try serializer.serialize(draft.steps.map { Self.candidate(of: $0) })
            let parsed = parser.parse(canonical)
            guard !parsed.hasBlockingErrors else {
                return .failure(.serializationFailed, state)
            }
            let next = EditorState(
                mode: state.mode,
                draft: draft,
                textBuffer: canonical,
                parseResult: parsed,
                syncStatus: .synced,
                lastCommittedSteps: draft.steps,
                lastCommittedText: canonical
            )
            state = next
            return .success(next)
        } catch {
            return .failure(.serializationFailed, state)
        }
    }

    private static func candidate(of step: RoutineStep) -> StepCandidate {
        StepCandidate(
            name: step.name,
            type: step.type,
            target: step.target,
            countMode: step.countMode,
            durationMs: step.durationMs,
            category: step.category,
            repeatCount: step.repeatCount
        )
    }

    private func step(from candidate: StepCandidate, id: String) -> RoutineStep {
        RoutineStep(
            id: id,
            name: candidate.name,
            type: candidate.type,
            target: candidate.target,
            countMode: candidate.countMode,
            durationMs: candidate.durationMs,
            category: candidate.category,
            repeatCount: candidate.repeatCount
        )
    }

    private func mapDraftError(_ error: DraftError) -> EditorError {
        switch error {
        case .invalidDraft:
            return .draftInvalid
        case .invalidStep:
            return .invalidStep
        case .duplicateStepID:
            return .duplicateStepID
        case .stepNotFound:
            return .stepNotFound
        case .invalidMove:
            return .invalidMove
        case .invalidRepeatCount:
            return .invalidRepeatCount
        }
    }

    private func nowMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1_000).rounded())
    }
}
