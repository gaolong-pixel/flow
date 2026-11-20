package com.flow.app.domain.editor

import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.immutableSteps
import com.flow.app.domain.parser.ParseResult
import java.util.Collections
import java.util.Objects

enum class EditorMode {
    TEXT,
    CARDS
}

enum class SyncStatus {
    SYNCED,
    DIRTY,
    INVALID
}

/**
 * Immutable editing snapshot. RoutineDraft remains the only editable data
 * source; lastCommittedSteps is a read-only snapshot used for ID matching and
 * recovery only.
 */
class EditorState internal constructor(
    val mode: EditorMode,
    val draft: RoutineDraft,
    val textBuffer: String,
    parseResult: ParseResult,
    val syncStatus: SyncStatus,
    lastCommittedSteps: List<RoutineStep>,
    val lastCommittedText: String
) {
    val lastCommittedSteps: List<RoutineStep> = immutableSteps(lastCommittedSteps)
    val parseResult: ParseResult = immutableParseResult(parseResult)

    val hasBlockingErrors: Boolean
        get() = parseResult.hasBlockingErrors

    val canEnterCards: Boolean
        get() = syncStatus == SyncStatus.SYNCED && !hasBlockingErrors

    internal fun copy(
        mode: EditorMode = this.mode,
        draft: RoutineDraft = this.draft,
        textBuffer: String = this.textBuffer,
        parseResult: ParseResult = this.parseResult,
        syncStatus: SyncStatus = this.syncStatus,
        lastCommittedSteps: List<RoutineStep> = this.lastCommittedSteps,
        lastCommittedText: String = this.lastCommittedText
    ): EditorState = EditorState(
        mode = mode,
        draft = draft,
        textBuffer = textBuffer,
        parseResult = parseResult,
        syncStatus = syncStatus,
        lastCommittedSteps = lastCommittedSteps,
        lastCommittedText = lastCommittedText
    )

    override fun equals(other: Any?): Boolean =
        other is EditorState &&
            mode == other.mode &&
            draft == other.draft &&
            textBuffer == other.textBuffer &&
            parseResult == other.parseResult &&
            syncStatus == other.syncStatus &&
            lastCommittedSteps == other.lastCommittedSteps &&
            lastCommittedText == other.lastCommittedText

    override fun hashCode(): Int = Objects.hash(
        mode,
        draft,
        textBuffer,
        parseResult,
        syncStatus,
        lastCommittedSteps,
        lastCommittedText
    )

    override fun toString(): String =
        "EditorState(mode=$mode, syncStatus=$syncStatus, " +
            "textBuffer=$textBuffer, steps=${draft.steps})"
}

private fun immutableParseResult(result: ParseResult): ParseResult = ParseResult(
    lines = Collections.unmodifiableList(ArrayList(result.lines)),
    stats = result.stats,
    steps = Collections.unmodifiableList(ArrayList(result.steps)),
    diagnostics = Collections.unmodifiableList(ArrayList(result.diagnostics))
)

enum class EditorError {
    WRONG_MODE,
    TEXT_BLOCKED,
    DRAFT_INVALID,
    INVALID_STEP,
    DUPLICATE_STEP_ID,
    STEP_NOT_FOUND,
    INVALID_MOVE,
    INVALID_REPEAT_COUNT,
    INVALID_NAME,
    NAME_TOO_LONG,
    SERIALIZATION_FAILED
}

sealed interface EditorResult {
    val state: EditorState

    data class Success(override val state: EditorState) : EditorResult
    data class Failure(val error: EditorError, override val state: EditorState) : EditorResult
}
