package com.flow.app.domain.model

import java.util.Objects

class Routine(
    val id: String,
    val name: String,
    val version: Int = CURRENT_SCHEMA_VERSION,
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

    override fun equals(other: Any?): Boolean =
        other is Routine &&
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
        "Routine(id=$id, name=$name, version=$version, repeatCount=$repeatCount, " +
            "steps=$steps, createdAt=$createdAt, updatedAt=$updatedAt)"

    companion object {
        // Version is the data-schema version, not an edit revision.
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
