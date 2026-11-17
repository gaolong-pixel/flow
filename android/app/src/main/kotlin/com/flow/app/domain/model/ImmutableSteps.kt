package com.flow.app.domain.model

import java.util.Collections

/**
 * Copies the list and wraps it so callers cannot mutate model-owned storage,
 * even by casting the exposed List to MutableList.
 */
internal fun immutableSteps(steps: List<RoutineStep>): List<RoutineStep> =
    Collections.unmodifiableList(ArrayList(steps))
