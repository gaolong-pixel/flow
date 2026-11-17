package com.example.flow.domain.model

enum class StepType { COUNT, TIMER, MANUAL }
enum class CountMode { INCREMENT, COMPLETE }
enum class TimerCategory { NORMAL, REST }

data class RoutineStep(
    val id: String,
    val name: String,
    val type: StepType,
    val target: Int? = null,
    val countMode: CountMode? = null,
    val durationMs: Long? = null,
    val category: TimerCategory? = null,
    val repeatCount: Int = 1,
    val order: Int
)

data class Routine(
    val id: String,
    val name: String,
    val version: Int = 1,
    val repeatCount: Int = 1,
    val steps: List<RoutineStep> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

fun RoutineStep.validate(): List<String> = buildList {
    if (name.isBlank()) add("步骤名称不能为空")
    if (repeatCount < 1) add("步骤重复次数必须大于 0")
    when (type) {
        StepType.COUNT -> {
            if ((target ?: 0) <= 0) add("次数目标必须大于 0")
            if (countMode == null) add("次数模式不能为空")
            if (durationMs != null) add("次数节点不能设置时长")
        }
        StepType.TIMER -> {
            if ((durationMs ?: 0L) <= 0L) add("计时时长必须大于 0")
            if (target != null || countMode != null) add("时间节点不能设置次数参数")
        }
        StepType.MANUAL -> Unit
    }
}
