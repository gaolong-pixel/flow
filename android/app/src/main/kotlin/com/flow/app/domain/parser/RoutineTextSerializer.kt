package com.flow.app.domain.parser

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.model.ValidationError
import com.flow.app.domain.model.trimFlowWhitespace

enum class SerializationError {
    INVALID_STEP,
    INVALID_NAME,
    UNREPRESENTABLE_DURATION
}

class SerializationException(
    val code: SerializationError,
    val stepIndex: Int,
    val validationErrors: List<ValidationError> = emptyList()
) : IllegalArgumentException(
    "Cannot serialize step $stepIndex: $code" +
        if (validationErrors.isEmpty()) "" else " $validationErrors"
)

/**
 * Serializes candidates into the canonical V1.1 text form.
 * The operation is atomic: any invalid step prevents returning partial text.
 */
class RoutineTextSerializer {

    fun serialize(steps: List<StepCandidate>): String {
        if (steps.isEmpty()) return ""

        return buildString {
            steps.forEachIndexed { index, step ->
                if (index > 0) append('\n')
                append(serializeStep(index, step))
            }
        }
    }

    private fun serializeStep(index: Int, step: StepCandidate): String {
        if (step.name.contains('\n') || step.name.contains('\r')) {
            throw SerializationException(SerializationError.INVALID_NAME, index)
        }
        if (!step.isValid) {
            throw SerializationException(
                code = SerializationError.INVALID_STEP,
                stepIndex = index,
                validationErrors = step.validationErrors()
            )
        }

        val name = step.name.trimFlowWhitespace()
        if (name.isEmpty()) {
            throw SerializationException(SerializationError.INVALID_NAME, index)
        }

        val base = when (step.type) {
            StepType.COUNT -> {
                val target = step.target
                    ?: throw SerializationException(SerializationError.INVALID_STEP, index)
                "$name ${target}次"
            }

            StepType.TIMER -> {
                val durationMs = step.durationMs
                    ?: throw SerializationException(SerializationError.INVALID_STEP, index)
                "$name ${formatDuration(index, durationMs)}"
            }
        }

        val repeat = if (step.repeatCount > 1) " x ${step.repeatCount}" else ""
        val attribute = when (step.type) {
            StepType.COUNT -> {
                when (step.countMode) {
                    CountMode.COMPLETE -> " [计数=完成]"
                    CountMode.INCREMENT -> ""
                    null -> throw SerializationException(SerializationError.INVALID_STEP, index)
                }
            }

            StepType.TIMER -> {
                val category = step.category
                    ?: throw SerializationException(SerializationError.INVALID_STEP, index)
                val inferred = if (name.contains("休息")) {
                    TimerCategory.REST
                } else {
                    TimerCategory.NORMAL
                }
                when {
                    category == inferred -> ""
                    category == TimerCategory.REST -> " [分类=休息]"
                    category == TimerCategory.NORMAL -> " [分类=普通]"
                    else -> throw SerializationException(SerializationError.INVALID_STEP, index)
                }
            }
        }

        return base + repeat + attribute
    }

    private fun formatDuration(index: Int, durationMs: Long): String {
        if (durationMs < 1_000L || durationMs % 1_000L != 0L) {
            throw SerializationException(
                SerializationError.UNREPRESENTABLE_DURATION,
                index
            )
        }
        if (durationMs % 3_600_000L == 0L) {
            return "${durationMs / 3_600_000L}小时"
        }
        if (durationMs % 60_000L == 0L) {
            return "${durationMs / 60_000L}分钟"
        }
        return "${durationMs / 1_000L}秒"
    }
}
