package com.flow.app.domain.parser

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.model.ValidationError
import com.flow.app.domain.model.isBlankStepName

enum class ParseStatus {
    VALID,
    EMPTY,
    INCOMPLETE,
    INVALID
}

/**
 * Stable diagnostic codes. UI layers own user-facing text.
 */
enum class ParseErrorCode {
    INCOMPLETE_MISSING_QUANTITY,
    INCOMPLETE_MISSING_UNIT,
    INCOMPLETE_REPEAT_COUNT,
    INCOMPLETE_ATTRIBUTE,
    INVALID_NAME,
    INVALID_NUMBER,
    INVALID_UNIT,
    INVALID_DECIMAL,
    INVALID_COMPOUND,
    INVALID_REPEAT_COUNT,
    INVALID_ATTRIBUTE,
    INVALID_ATTRIBUTE_TYPE,
    INVALID_ATTRIBUTE_ORDER,
    INVALID_TRAILING,
    INVALID_OVERFLOW,
    INVALID_LINE
}

data class ParseDiagnostic(
    val lineNumber: Int,
    val code: ParseErrorCode
)

/**
 * A parsed step value without a persistent ID or order.
 * IDs/order are assigned by the draft/commit layer in phase 5.
 */
data class StepCandidate(
    val name: String,
    val type: StepType,
    val target: Int? = null,
    val countMode: CountMode? = null,
    val durationMs: Long? = null,
    val category: TimerCategory? = null,
    val repeatCount: Int = 1
) {
    fun validationErrors(): List<ValidationError> = buildList {
        if (name.isBlankStepName()) add(ValidationError.EMPTY_STEP_NAME)
        if (repeatCount < 1) add(ValidationError.INVALID_STEP_REPEAT_COUNT)

        when (type) {
            StepType.COUNT -> {
                if (target == null || target <= 0) {
                    add(ValidationError.INVALID_COUNT_TARGET)
                }
                if (countMode == null) {
                    add(ValidationError.MISSING_COUNT_MODE)
                }
                if (durationMs != null || category != null) {
                    add(ValidationError.COUNT_HAS_TIMER_FIELDS)
                }
            }

            StepType.TIMER -> {
                if (durationMs == null || durationMs < 1_000L || durationMs % 1_000L != 0L) {
                    add(ValidationError.INVALID_TIMER_DURATION)
                }
                if (category == null) {
                    add(ValidationError.MISSING_TIMER_CATEGORY)
                }
                if (target != null || countMode != null) {
                    add(ValidationError.TIMER_HAS_COUNT_FIELDS)
                }
            }
        }
    }

    val isValid: Boolean
        get() = validationErrors().isEmpty()
}

data class ParsedLine(
    val lineNumber: Int,
    val raw: String,
    val status: ParseStatus,
    val step: StepCandidate? = null,
    val diagnostic: ParseDiagnostic? = null
)

data class ParseStats(
    val valid: Int,
    val empty: Int,
    val incomplete: Int,
    val invalid: Int
) {
    val total: Int
        get() = valid + empty + incomplete + invalid
}

data class ParseResult(
    val lines: List<ParsedLine>,
    val stats: ParseStats,
    val steps: List<StepCandidate>,
    val diagnostics: List<ParseDiagnostic>
) {
    val hasBlockingErrors: Boolean
        get() = stats.incomplete > 0 || stats.invalid > 0

    val canCommit: Boolean
        get() = !hasBlockingErrors
}
