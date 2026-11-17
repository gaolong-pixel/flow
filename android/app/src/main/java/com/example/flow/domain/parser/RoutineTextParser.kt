package com.example.flow.domain.parser

import com.example.flow.domain.model.*
import java.util.UUID

enum class ParseStatus { VALID, INCOMPLETE, INVALID, EMPTY }
data class LineParseResult(
    val lineNumber: Int, val raw: String, val status: ParseStatus,
    val step: RoutineStep? = null, val message: String? = null
)
data class RoutineParseResult(val lines: List<LineParseResult>) {
    val steps get() = lines.mapNotNull { it.step }
    val hasBlockingErrors get() = lines.any {
        it.status == ParseStatus.INVALID || it.status == ParseStatus.INCOMPLETE
    }
}

class RoutineTextParser {
    private val countRegex = Regex("^(.+?)\\s+(\\d+)\\s*次(?:\\s*[x×]\\s*(\\d+))?$", RegexOption.IGNORE_CASE)
    private val timerRegex = Regex("^(.+?)\\s+(\\d+)\\s*(秒|分钟|分|小时|时)(?:\\s*[x×]\\s*(\\d+))?$", RegexOption.IGNORE_CASE)
    private val unfinishedRegex = Regex("^(.+?)\\s+\\d+$")

    fun parse(text: String): RoutineParseResult {
        val results = text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) null else parseLine(index + 1, line)
        }
        return RoutineParseResult(results)
    }

    private fun parseLine(number: Int, line: String): LineParseResult {
        countRegex.matchEntire(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val target = m.groupValues[2].toIntOrNull() ?: 0
            val repeat = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            if (name.isBlank() || target <= 0 || repeat <= 0) return invalid(number, line, "次数或重复次数必须大于 0")
            return valid(number, line, RoutineStep(
                id = UUID.randomUUID().toString(), name = name, type = StepType.COUNT,
                target = target, countMode = CountMode.INCREMENT,
                repeatCount = repeat, order = number - 1
            ))
        }

        timerRegex.matchEntire(line)?.let { m ->
            val name = m.groupValues[1].trim()
            val amount = m.groupValues[2].toLongOrNull() ?: 0L
            val unit = m.groupValues[3]
            val repeat = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
            val multiplier = when (unit) {
                "秒" -> 1_000L
                "分钟", "分" -> 60_000L
                "小时", "时" -> 3_600_000L
                else -> 1_000L
            }
            val duration = amount * multiplier
            if (name.isBlank() || duration <= 0 || repeat <= 0) return invalid(number, line, "时长或重复次数必须大于 0")
            val category = if (name.contains("休息")) TimerCategory.REST else TimerCategory.NORMAL
            return valid(number, line, RoutineStep(
                id = UUID.randomUUID().toString(), name = name, type = StepType.TIMER,
                durationMs = duration, category = category,
                repeatCount = repeat, order = number - 1
            ))
        }

        if (unfinishedRegex.matches(line)) {
            return LineParseResult(number, line, ParseStatus.INCOMPLETE, message = "请补充单位，例如：次、秒或分钟")
        }
        return invalid(number, line, "无法识别该步骤，请使用“名称 数字次/秒/分钟”格式")
    }

    private fun valid(n: Int, raw: String, step: RoutineStep) =
        LineParseResult(n, raw, ParseStatus.VALID, step)
    private fun invalid(n: Int, raw: String, message: String) =
        LineParseResult(n, raw, ParseStatus.INVALID, message = message)
}
