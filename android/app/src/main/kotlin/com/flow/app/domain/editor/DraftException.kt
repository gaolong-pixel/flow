package com.flow.app.domain.editor

import com.flow.app.domain.model.ValidationError

sealed class DraftException(message: String) : IllegalArgumentException(message) {
    class InvalidDraft(val errors: List<ValidationError>) :
        DraftException("Invalid draft: $errors")

    class InvalidStep(val errors: List<ValidationError>) :
        DraftException("Invalid step: $errors")

    class DuplicateStepID(val id: String) :
        DraftException("Duplicate step id: $id")

    class StepNotFound(val id: String) :
        DraftException("Step not found: $id")

    class InvalidMove(val from: Int, val to: Int) :
        DraftException("Invalid move: $from -> $to")

    class InvalidRepeatCount(val value: Int) :
        DraftException("Invalid repeat count: $value")
}
