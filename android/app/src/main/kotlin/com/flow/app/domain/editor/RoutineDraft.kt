package com.flow.app.domain.editor

import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.ValidationError
import com.flow.app.domain.model.duplicateStepIds
import com.flow.app.domain.model.immutableSteps
import java.util.Objects
import java.util.UUID

/**
 * The single editable source of truth for an in-progress routine.
 *
 * Step order is defined only by [steps]. There is deliberately no per-step
 * order field that could diverge from the list. The exposed list is a
 * defensive, unmodifiable copy of caller-owned data.
 */
class RoutineDraft(
    val id: String,
    val name: String,
    val version: Int = Routine.CURRENT_SCHEMA_VERSION,
    val repeatCount: Int = 1,
    steps: List<RoutineStep> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
) {
    val steps: List<RoutineStep> = immutableSteps(steps)

    fun validationErrors(): List<ValidationError> = buildList {
        if (repeatCount < 1) {
            add(ValidationError.INVALID_ROUTINE_REPEAT_COUNT)
        }
        addAll(steps.duplicateStepIds().map { ValidationError.DUPLICATE_STEP_ID })
        steps.forEach { step ->
            addAll(step.validationErrors())
        }
    }

    val isValid: Boolean
        get() = validationErrors().isEmpty()

    fun requireValid(): RoutineDraft {
        val errors = validationErrors()
        if (errors.isNotEmpty()) {
            throw DraftException.InvalidDraft(errors)
        }
        return this
    }

    fun rename(newName: String): RoutineDraft =
        copyWith(name = newName, updatedAt = now())

    fun setRepeatCount(newCount: Int): RoutineDraft {
        if (newCount < 1) {
            throw DraftException.InvalidRepeatCount(newCount)
        }
        return copyWith(repeatCount = newCount, updatedAt = now())
    }

    fun addStep(step: RoutineStep): RoutineDraft {
        if (steps.any { it.id == step.id }) {
            throw DraftException.DuplicateStepID(step.id)
        }
        validateStep(step)
        return copyWith(
            steps = steps + step,
            updatedAt = now()
        ).requireValid()
    }

    fun updateStep(step: RoutineStep): RoutineDraft {
        val index = steps.indexOfFirst { it.id == step.id }
        if (index < 0) {
            throw DraftException.StepNotFound(step.id)
        }
        validateStep(step)

        val updated = steps.toMutableList().also { it[index] = step }
        return copyWith(
            steps = updated,
            updatedAt = now()
        ).requireValid()
    }

    fun deleteStep(id: String): RoutineDraft {
        if (steps.none { it.id == id }) {
            throw DraftException.StepNotFound(id)
        }
        return copyWith(
            steps = steps.filterNot { it.id == id },
            updatedAt = now()
        ).requireValid()
    }

    fun moveStep(from: Int, to: Int): RoutineDraft {
        if (from !in steps.indices || to !in steps.indices) {
            throw DraftException.InvalidMove(from, to)
        }
        if (from == to) return this

        val reordered = steps.toMutableList()
        val step = reordered.removeAt(from)
        reordered.add(to, step)

        return copyWith(
            steps = reordered,
            updatedAt = now()
        ).requireValid()
    }

    fun duplicateStep(id: String): RoutineDraft {
        val index = steps.indexOfFirst { it.id == id }
        if (index < 0) {
            throw DraftException.StepNotFound(id)
        }

        val duplicatedStep = steps[index].duplicated(UUID.randomUUID().toString())
        val duplicated = steps.toMutableList().also { it.add(index + 1, duplicatedStep) }

        return copyWith(
            steps = duplicated,
            updatedAt = now()
        ).requireValid()
    }

    fun toRoutine(): Routine {
        requireValid()
        return Routine(
            id = id,
            name = name,
            version = version,
            repeatCount = repeatCount,
            steps = steps,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun copyWith(
        id: String = this.id,
        name: String = this.name,
        version: Int = this.version,
        repeatCount: Int = this.repeatCount,
        steps: List<RoutineStep> = this.steps,
        createdAt: Long = this.createdAt,
        updatedAt: Long = this.updatedAt
    ): RoutineDraft = RoutineDraft(
        id = id,
        name = name,
        version = version,
        repeatCount = repeatCount,
        steps = steps,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun validateStep(step: RoutineStep) {
        val errors = step.validationErrors()
        if (errors.isNotEmpty()) {
            throw DraftException.InvalidStep(errors)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    override fun equals(other: Any?): Boolean =
        other is RoutineDraft &&
            id == other.id &&
            name == other.name &&
            version == other.version &&
            repeatCount == other.repeatCount &&
            steps == other.steps &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt

    override fun hashCode(): Int = Objects.hash(
        id,
        name,
        version,
        repeatCount,
        steps,
        createdAt,
        updatedAt
    )

    override fun toString(): String =
        "RoutineDraft(id=$id, name=$name, version=$version, repeatCount=$repeatCount, " +
            "steps=$steps, createdAt=$createdAt, updatedAt=$updatedAt)"

    companion object {
        fun new(
            name: String = "",
            repeatCount: Int = 1,
            steps: List<RoutineStep> = emptyList(),
            id: String = UUID.randomUUID().toString(),
            now: Long = System.currentTimeMillis()
        ): RoutineDraft = RoutineDraft(
            id = id,
            name = name,
            version = Routine.CURRENT_SCHEMA_VERSION,
            repeatCount = repeatCount,
            steps = steps,
            createdAt = now,
            updatedAt = now
        ).requireValid()

        fun from(routine: Routine): RoutineDraft = RoutineDraft(
            id = routine.id,
            name = routine.name,
            version = routine.version,
            repeatCount = routine.repeatCount,
            steps = routine.steps,
            createdAt = routine.createdAt,
            updatedAt = routine.updatedAt
        ).requireValid()
    }
}
