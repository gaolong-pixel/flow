package com.flow.app.domain.editor

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.parser.RoutineTextParser
import com.flow.app.domain.parser.RoutineTextSerializer
import com.flow.app.domain.parser.SerializationException
import com.flow.app.domain.parser.StepCandidate
import java.util.UUID

/**
 * Synchronous, deterministic editor controller. It owns the only mutable
 * reference (the immutable state) and replaces it only after a complete
 * operation, including serialization and parsing, succeeds.
 */
class EditorController(
    initialDraft: RoutineDraft,
    initialMode: EditorMode = EditorMode.TEXT,
    private val parser: RoutineTextParser = RoutineTextParser(),
    private val serializer: RoutineTextSerializer = RoutineTextSerializer(),
    private val matcher: StepIdMatcher = StepIdMatcher()
) {
    var state: EditorState
        private set

    init {
        val canonical = canonicalText(initialDraft.steps)
        val parsed = parser.parse(canonical)
        require(!parsed.hasBlockingErrors) {
            "Initial draft produced blocking canonical text"
        }
        state = EditorState(
            mode = initialMode,
            draft = initialDraft,
            textBuffer = canonical,
            parseResult = parsed,
            syncStatus = SyncStatus.SYNCED,
            lastCommittedSteps = initialDraft.steps,
            lastCommittedText = canonical
        )
    }

    fun onTextChanged(text: String): EditorState {
        if (state.mode != EditorMode.TEXT) return state

        val parsed = parser.parse(text)
        val syncStatus = when {
            parsed.hasBlockingErrors -> SyncStatus.INVALID
            text == state.lastCommittedText -> SyncStatus.SYNCED
            else -> SyncStatus.DIRTY
        }

        state = state.copy(
            textBuffer = text,
            parseResult = parsed,
            syncStatus = syncStatus
        )
        return state
    }

    fun commitText(): EditorResult {
        if (state.mode != EditorMode.TEXT) {
            return EditorResult.Failure(EditorError.WRONG_MODE, state)
        }
        if (state.parseResult.hasBlockingErrors) {
            return EditorResult.Failure(EditorError.TEXT_BLOCKED, state)
        }

        return try {
            val matches = matcher.match(state.lastCommittedSteps, state.parseResult.steps)
            val newSteps = matches.map { match ->
                stepFrom(
                    candidate = match.candidate,
                    id = match.id ?: UUID.randomUUID().toString()
                )
            }
            val newDraft = RoutineDraft(
                id = state.draft.id,
                name = state.draft.name,
                version = state.draft.version,
                repeatCount = state.draft.repeatCount,
                steps = newSteps,
                createdAt = state.draft.createdAt,
                updatedAt = if (newSteps == state.draft.steps) {
                    state.draft.updatedAt
                } else {
                    now()
                }
            ).requireValid()
            publish(newDraft)
        } catch (error: DraftException) {
            EditorResult.Failure(mapDraftError(error), state)
        } catch (error: SerializationException) {
            EditorResult.Failure(EditorError.SERIALIZATION_FAILED, state)
        }
    }

    fun addStep(candidate: StepCandidate): EditorResult = cardOperation {
        state.draft.addStep(stepFrom(candidate, UUID.randomUUID().toString()))
    }

    fun updateStep(step: RoutineStep): EditorResult = cardOperation {
        state.draft.updateStep(step)
    }

    fun updateStepType(
        id: String,
        type: StepType,
        target: Int? = null,
        countMode: CountMode? = null,
        durationMs: Long? = null,
        category: TimerCategory? = null
    ): EditorResult = cardOperation {
        val existing = state.draft.steps.firstOrNull { it.id == id }
            ?: throw DraftException.StepNotFound(id)
        val updated = RoutineStep(
            id = existing.id,
            name = existing.name,
            type = type,
            target = if (type == StepType.COUNT) target else null,
            countMode = if (type == StepType.COUNT) countMode else null,
            durationMs = if (type == StepType.TIMER) durationMs else null,
            category = if (type == StepType.TIMER) category else null,
            repeatCount = existing.repeatCount
        )
        state.draft.updateStep(updated)
    }

    fun deleteStep(id: String): EditorResult = cardOperation {
        state.draft.deleteStep(id)
    }

    fun moveStep(from: Int, to: Int): EditorResult = cardOperation {
        state.draft.moveStep(from, to)
    }

    fun duplicateStep(id: String): EditorResult = cardOperation {
        state.draft.duplicateStep(id)
    }

    fun clearSteps(): EditorResult = cardOperation {
        if (state.draft.steps.isEmpty()) {
            state.draft
        } else {
            RoutineDraft(
                id = state.draft.id,
                name = state.draft.name,
                version = state.draft.version,
                repeatCount = state.draft.repeatCount,
                steps = emptyList(),
                createdAt = state.draft.createdAt,
                updatedAt = now()
            ).requireValid()
        }
    }

    fun switchMode(targetMode: EditorMode): EditorResult {
        if (targetMode == state.mode) {
            return EditorResult.Success(state)
        }

        return when (targetMode) {
            EditorMode.CARDS -> {
                if (state.syncStatus != SyncStatus.SYNCED ||
                    state.parseResult.hasBlockingErrors
                ) {
                    EditorResult.Failure(EditorError.TEXT_BLOCKED, state)
                } else {
                    state = state.copy(mode = EditorMode.CARDS)
                    EditorResult.Success(state)
                }
            }

            EditorMode.TEXT -> {
                val published = publish(state.draft)
                if (published is EditorResult.Failure) {
                    published
                } else {
                    state = state.copy(mode = EditorMode.TEXT)
                    EditorResult.Success(state)
                }
            }
        }
    }

    fun restoreLastCommitted(): EditorResult {
        return try {
            val restored = RoutineDraft(
                id = state.draft.id,
                name = state.draft.name,
                version = state.draft.version,
                repeatCount = state.draft.repeatCount,
                steps = state.lastCommittedSteps,
                createdAt = state.draft.createdAt,
                updatedAt = now()
            ).requireValid()
            publish(restored)
        } catch (error: DraftException) {
            EditorResult.Failure(mapDraftError(error), state)
        } catch (error: SerializationException) {
            EditorResult.Failure(EditorError.SERIALIZATION_FAILED, state)
        }
    }

    private fun cardOperation(block: () -> RoutineDraft): EditorResult {
        if (state.mode != EditorMode.CARDS) {
            return EditorResult.Failure(EditorError.WRONG_MODE, state)
        }

        return try {
            val nextDraft = block()
            if (nextDraft == state.draft) {
                EditorResult.Success(state)
            } else {
                publish(nextDraft)
            }
        } catch (error: DraftException) {
            EditorResult.Failure(mapDraftError(error), state)
        }
    }

    private fun publish(draft: RoutineDraft): EditorResult {
        val canonical = try {
            canonicalText(draft.steps)
        } catch (error: SerializationException) {
            return EditorResult.Failure(EditorError.SERIALIZATION_FAILED, state)
        }

        val parsed = parser.parse(canonical)
        if (parsed.hasBlockingErrors) {
            return EditorResult.Failure(EditorError.SERIALIZATION_FAILED, state)
        }

        val next = EditorState(
            mode = state.mode,
            draft = draft,
            textBuffer = canonical,
            parseResult = parsed,
            syncStatus = SyncStatus.SYNCED,
            lastCommittedSteps = draft.steps,
            lastCommittedText = canonical
        )
        state = next
        return EditorResult.Success(next)
    }

    private fun canonicalText(steps: List<RoutineStep>): String =
        serializer.serialize(steps.map(::candidateOf))

    private fun candidateOf(step: RoutineStep): StepCandidate = StepCandidate(
        name = step.name,
        type = step.type,
        target = step.target,
        countMode = step.countMode,
        durationMs = step.durationMs,
        category = step.category,
        repeatCount = step.repeatCount
    )

    private fun stepFrom(candidate: StepCandidate, id: String): RoutineStep = RoutineStep(
        id = id,
        name = candidate.name,
        type = candidate.type,
        target = candidate.target,
        countMode = candidate.countMode,
        durationMs = candidate.durationMs,
        category = candidate.category,
        repeatCount = candidate.repeatCount
    )

    private fun mapDraftError(error: DraftException): EditorError = when (error) {
        is DraftException.InvalidDraft -> EditorError.DRAFT_INVALID
        is DraftException.InvalidStep -> EditorError.INVALID_STEP
        is DraftException.DuplicateStepID -> EditorError.DUPLICATE_STEP_ID
        is DraftException.StepNotFound -> EditorError.STEP_NOT_FOUND
        is DraftException.InvalidMove -> EditorError.INVALID_MOVE
        is DraftException.InvalidRepeatCount -> EditorError.INVALID_REPEAT_COUNT
    }

    private fun now(): Long = System.currentTimeMillis()
}
