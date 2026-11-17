package com.example.flow.domain.editor

import com.example.flow.domain.model.RoutineStep

data class RoutineDraft(
    val name: String = "",
    val repeatCount: Int = 1,
    val steps: List<RoutineStep> = emptyList()
) {
    fun add(step: RoutineStep) = copy(steps = (steps + step).mapIndexed { i, s -> s.copy(order = i) })
    fun update(step: RoutineStep) = copy(steps = steps.map { if (it.id == step.id) step else it })
    fun delete(stepId: String) = copy(steps = steps.filterNot { it.id == stepId }.mapIndexed { i, s -> s.copy(order = i) })

    fun move(from: Int, to: Int): RoutineDraft {
        if (from !in steps.indices || to !in steps.indices || from == to) return this
        val list = steps.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return copy(steps = list.mapIndexed { i, s -> s.copy(order = i) })
    }
}
