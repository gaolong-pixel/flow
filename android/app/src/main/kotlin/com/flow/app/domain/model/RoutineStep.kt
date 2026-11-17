package com.flow.app.domain.model

import java.util.Objects
import java.util.UUID

enum class StepType {
    COUNT,
    TIMER
}

enum class CountMode {
    INCREMENT,
    COMPLETE
}

enum class TimerCategory {
    NORMAL,
    REST
}

enum class ValidationError {
    EMPTY_STEP_NAME,
    INVALID_STEP_REPEAT_COUNT,
    INVALID_COUNT_TARGET,
    MISSING_COUNT_MODE,
    COUNT_HAS_TIMER_FIELDS,
    INVALID_TIMER_DURATION,
    MISSING_TIMER_CATEGORY,
    TIMER_HAS_COUNT_FIELDS,
    DUPLICATE_STEP_ID,
    INVALID_ROUTINE_REPEAT_COUNT
}

private val STEP_NAME_WHITESPACE: Set<Char> = buildSet {
    addAll(
        listOf(
            '\u0009', '\u000A', '\u000B', '\u000C', '\u000D',
            '\u0020', '\u0085', '\u00A0', '\u1680',
            '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
        )
    )
    (0x2000..0x200A).forEach { add(it.toChar()) }
}

/**
 * Explicit Unicode White_Space rule shared with the Swift implementation.
 * Step names are normalized by trimming these characters at every model
 * creation/update entry point; internal whitespace is preserved.
 */
internal fun Char.isFlowWhitespace(): Boolean =
    this in STEP_NAME_WHITESPACE

internal fun String.trimFlowWhitespace(): String {
    var start = 0
    while (start < length && this[start].isFlowWhitespace()) start++
    var end = length
    while (end > start && this[end - 1].isFlowWhitespace()) end--
    return substring(start, end)
}

internal fun String.trimEndFlowWhitespace(): String {
    var end = length
    while (end > 0 && this[end - 1].isFlowWhitespace()) end--
    return substring(0, end)
}

internal fun String.isBlankStepName(): Boolean =
    all { it.isFlowWhitespace() }

class RoutineStep(
    val id: String,
    name: String,
    val type: StepType,
    val target: Int? = null,
    val countMode: CountMode? = null,
    val durationMs: Long? = null,
    val category: TimerCategory? = null,
    val repeatCount: Int = 1
) {
    /**
     * Names are normalized at the model boundary. Stored step names therefore
     * never retain leading/trailing Unicode White_Space.
     */
    val name: String = name.trimFlowWhitespace()

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

    /**
     * Returns a copy with the same ID. ID changes are intentionally not part
     * of this API; duplicating a step uses [duplicated].
     */
    fun copy(
        name: String = this.name,
        type: StepType = this.type,
        target: Int? = this.target,
        countMode: CountMode? = this.countMode,
        durationMs: Long? = this.durationMs,
        category: TimerCategory? = this.category,
        repeatCount: Int = this.repeatCount
    ): RoutineStep = RoutineStep(
        id = id,
        name = name,
        type = type,
        target = target,
        countMode = countMode,
        durationMs = durationMs,
        category = category,
        repeatCount = repeatCount
    )

    fun duplicated(newId: String): RoutineStep = RoutineStep(
        id = newId,
        name = name,
        type = type,
        target = target,
        countMode = countMode,
        durationMs = durationMs,
        category = category,
        repeatCount = repeatCount
    )

    override fun equals(other: Any?): Boolean =
        other is RoutineStep &&
            id == other.id &&
            name == other.name &&
            type == other.type &&
            target == other.target &&
            countMode == other.countMode &&
            durationMs == other.durationMs &&
            category == other.category &&
            repeatCount == other.repeatCount

    override fun hashCode(): Int = Objects.hash(
        id,
        name,
        type,
        target,
        countMode,
        durationMs,
        category,
        repeatCount
    )

    override fun toString(): String =
        "RoutineStep(id=$id, name=$name, type=$type, target=$target, " +
            "countMode=$countMode, durationMs=$durationMs, category=$category, " +
            "repeatCount=$repeatCount)"

    companion object {
        fun count(
            name: String,
            target: Int,
            id: String = UUID.randomUUID().toString(),
            countMode: CountMode = CountMode.INCREMENT,
            repeatCount: Int = 1
        ): RoutineStep = RoutineStep(
            id = id,
            name = name,
            type = StepType.COUNT,
            target = target,
            countMode = countMode,
            repeatCount = repeatCount
        )

        fun timer(
            name: String,
            durationMs: Long,
            id: String = UUID.randomUUID().toString(),
            category: TimerCategory = TimerCategory.NORMAL,
            repeatCount: Int = 1
        ): RoutineStep = RoutineStep(
            id = id,
            name = name,
            type = StepType.TIMER,
            durationMs = durationMs,
            category = category,
            repeatCount = repeatCount
        )
    }
}

internal fun List<RoutineStep>.duplicateStepIds(): Set<String> =
    groupingBy { it.id }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
