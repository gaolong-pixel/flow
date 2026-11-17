package com.flow.app.domain.parser

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.model.isFlowWhitespace
import com.flow.app.domain.model.trimEndFlowWhitespace
import com.flow.app.domain.model.trimFlowWhitespace

/**
 * Parser for the V1.1 line grammar.
 *
 * The parser is intentionally stateless and line-oriented. It returns
 * candidates without IDs or order; committing to a RoutineDraft belongs to a
 * later layer.
 */
class RoutineTextParser {

    private enum class Attribute {
        COUNT_COMPLETE,
        TIMER_REST,
        TIMER_NORMAL
    }

    private sealed interface AttributeResult {
        data class None(val text: String) : AttributeResult
        data class Found(val text: String, val attribute: Attribute) : AttributeResult
        data class Incomplete(val code: ParseErrorCode) : AttributeResult
        data class Invalid(val code: ParseErrorCode) : AttributeResult
    }

    private sealed interface RepeatResult {
        data class None(val text: String) : RepeatResult
        data class Found(val text: String, val count: Int) : RepeatResult
        data class Incomplete(val text: String, val code: ParseErrorCode) : RepeatResult
        data class Invalid(val text: String, val code: ParseErrorCode) : RepeatResult
    }

    private sealed interface QuantityResult {
        data class Found(
            val name: String,
            val type: StepType,
            val target: Int?,
            val durationMs: Long?
        ) : QuantityResult

        data class Incomplete(val code: ParseErrorCode) : QuantityResult
        data class Invalid(val code: ParseErrorCode) : QuantityResult
    }

    fun parse(text: String): ParseResult {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = splitLogicalLines(normalized)

        val lines = rawLines.mapIndexed { index, raw ->
            parseLine(index + 1, raw)
        }

        val valid = lines.count { it.status == ParseStatus.VALID }
        val empty = lines.count { it.status == ParseStatus.EMPTY }
        val incomplete = lines.count { it.status == ParseStatus.INCOMPLETE }
        val invalid = lines.count { it.status == ParseStatus.INVALID }

        val stats = ParseStats(valid, empty, incomplete, invalid)
        return ParseResult(
            lines = lines,
            stats = stats,
            // Candidates are exposed for commit only when every non-empty line
            // is valid; per-line candidates remain available on ParsedLine.
            steps = if (incomplete == 0 && invalid == 0) {
                lines.mapNotNull { it.step }
            } else {
                emptyList()
            },
            diagnostics = lines.mapNotNull { it.diagnostic }
        )
    }

    private fun parseLine(lineNumber: Int, raw: String): ParsedLine {
        val trimmed = raw.trimFlowWhitespace()
        if (trimmed.isEmpty()) {
            return ParsedLine(
                lineNumber = lineNumber,
                raw = raw,
                status = ParseStatus.EMPTY
            )
        }

        val attribute = extractAttribute(trimmed)
        val attributeValue: Attribute?
        var working: String

        when (attribute) {
            is AttributeResult.None -> {
                attributeValue = null
                working = trimmed
            }

            is AttributeResult.Found -> {
                attributeValue = attribute.attribute
                working = attribute.text.trimEndFlowWhitespace()
                if (hasTrailingAttribute(working)) {
                    return invalid(lineNumber, raw, ParseErrorCode.INVALID_ATTRIBUTE)
                }
            }

            is AttributeResult.Incomplete -> {
                return incomplete(lineNumber, raw, attribute.code)
            }

            is AttributeResult.Invalid -> {
                return invalid(lineNumber, raw, attribute.code)
            }
        }

        val repeat = extractRepeat(working)
        val repeatCount: Int
        when (repeat) {
            is RepeatResult.None -> {
                repeatCount = 1
                working = repeat.text
            }

            is RepeatResult.Found -> {
                if (hasTrailingAttribute(repeat.text)) {
                    return invalid(lineNumber, raw, ParseErrorCode.INVALID_ATTRIBUTE_ORDER)
                }
                repeatCount = repeat.count
                working = repeat.text
            }

            is RepeatResult.Incomplete -> {
                if (hasTrailingAttribute(repeat.text)) {
                    return invalid(lineNumber, raw, ParseErrorCode.INVALID_ATTRIBUTE_ORDER)
                }
                return incomplete(lineNumber, raw, repeat.code)
            }

            is RepeatResult.Invalid -> {
                return invalid(lineNumber, raw, repeat.code)
            }
        }

        if (hasTrailingAttribute(working)) {
            return invalid(lineNumber, raw, ParseErrorCode.INVALID_ATTRIBUTE_ORDER)
        }

        val quantity = parseQuantity(working)
        val (name, type, target, durationMs) = when (quantity) {
            is QuantityResult.Found -> quantity
            is QuantityResult.Incomplete -> {
                return incomplete(lineNumber, raw, quantity.code)
            }
            is QuantityResult.Invalid -> {
                return invalid(lineNumber, raw, quantity.code)
            }
        }

        val countMode: CountMode?
        val category: TimerCategory?
        when (type) {
            StepType.COUNT -> {
                countMode = when (attributeValue) {
                    Attribute.COUNT_COMPLETE -> CountMode.COMPLETE
                    null -> CountMode.INCREMENT
                    else -> {
                        return invalid(
                            lineNumber,
                            raw,
                            ParseErrorCode.INVALID_ATTRIBUTE_TYPE
                        )
                    }
                }
                category = null
            }

            StepType.TIMER -> {
                countMode = null
                val inferred = if (name.contains("休息")) {
                    TimerCategory.REST
                } else {
                    TimerCategory.NORMAL
                }
                category = when (attributeValue) {
                    Attribute.TIMER_REST -> TimerCategory.REST
                    Attribute.TIMER_NORMAL -> TimerCategory.NORMAL
                    null -> inferred
                    else -> {
                        return invalid(
                            lineNumber,
                            raw,
                            ParseErrorCode.INVALID_ATTRIBUTE_TYPE
                        )
                    }
                }
            }
        }

        val candidate = StepCandidate(
            name = name,
            type = type,
            target = target,
            countMode = countMode,
            durationMs = durationMs,
            category = category,
            repeatCount = repeatCount
        )
        if (!candidate.isValid) {
            return invalid(lineNumber, raw, ParseErrorCode.INVALID_LINE)
        }

        return ParsedLine(
            lineNumber = lineNumber,
            raw = raw,
            status = ParseStatus.VALID,
            step = candidate
        )
    }

    private fun extractAttribute(line: String): AttributeResult {
        if (line.endsWith("]")) {
            val lastOpen = line.lastIndexOf('[')
            if (lastOpen >= 0) {
                val token = line.substring(lastOpen)
                val attribute = ATTRIBUTE_TOKENS[token]
                if (attribute != null) {
                    return AttributeResult.Found(
                        text = line.substring(0, lastOpen).trimEndFlowWhitespace(),
                        attribute = attribute
                    )
                }
                if (hasQuantityLike(line.substring(0, lastOpen))) {
                    return AttributeResult.Invalid(ParseErrorCode.INVALID_ATTRIBUTE)
                }
            }
            return AttributeResult.None(line)
        }

        val lastOpen = line.lastIndexOf('[')
        if (lastOpen >= 0) {
            val suffix = line.substring(lastOpen)
            val prefix = line.substring(0, lastOpen)
            val isPartialAttribute = ATTRIBUTE_TOKENS.keys.any { token ->
                token.startsWith(suffix) && token != suffix
            }
            if (isPartialAttribute) {
                return AttributeResult.Incomplete(ParseErrorCode.INCOMPLETE_ATTRIBUTE)
            }
            if (hasQuantityLike(prefix)) {
                return AttributeResult.Invalid(ParseErrorCode.INVALID_ATTRIBUTE)
            }
        }

        return AttributeResult.None(line)
    }

    private fun extractRepeat(line: String): RepeatResult {
        val trimmed = line.trimEndFlowWhitespace()
        if (trimmed.isEmpty()) return RepeatResult.None(trimmed)

        var digitStart = trimmed.length
        while (digitStart > 0 && trimmed[digitStart - 1] in '0'..'9') {
            digitStart--
        }

        if (digitStart == trimmed.length) {
            return if (trimmed.last() in REPEAT_MARKERS) {
                RepeatResult.Incomplete(
                    text = trimmed.substring(0, trimmed.length - 1).trimEndFlowWhitespace(),
                    code = ParseErrorCode.INCOMPLETE_REPEAT_COUNT
                )
            } else {
                RepeatResult.None(trimmed)
            }
        }

        val digits = trimmed.substring(digitStart)
        var markerCheck = digitStart
        while (markerCheck > 0 && trimmed[markerCheck - 1].isFlowWhitespace()) {
            markerCheck--
        }
        if (markerCheck == 0 || trimmed[markerCheck - 1] !in REPEAT_MARKERS) {
            return RepeatResult.None(trimmed)
        }

        val markerIndex = markerCheck - 1
        val core = trimmed.substring(0, markerIndex).trimEndFlowWhitespace()
        if (hasTrailingAttribute(core)) {
            return RepeatResult.Invalid(core, ParseErrorCode.INVALID_ATTRIBUTE_ORDER)
        }

        val count = digits.toIntOrNull()
            ?: return RepeatResult.Invalid(core, ParseErrorCode.INVALID_OVERFLOW)
        if (count < 1) {
            return RepeatResult.Invalid(core, ParseErrorCode.INVALID_REPEAT_COUNT)
        }

        return RepeatResult.Found(core, count)
    }

    private fun parseQuantity(core: String): QuantityResult {
        val match = QUANTITY_REGEX.matchEntire(core)
            ?: return classifyNoQuantity(core)

        val namePart = match.groups[1]!!.value
        val digits = match.groups[2]!!.value
        val unit = match.groups[3]!!.value
        val digitStart = match.groups[2]!!.range.first
        val charBefore = core.getOrNull(digitStart - 1)

        if (charBefore != null && charBefore in DECIMAL_SEPARATORS) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_DECIMAL)
        }
        if (charBefore != null && charBefore in NUMBER_SIGNS) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_NUMBER)
        }
        if (isCompoundQuantity(namePart)) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_COMPOUND)
        }

        val name = namePart.trimFlowWhitespace()
        if (name.isEmpty()) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_NAME)
        }

        return when (unit) {
            "次" -> {
                val target = digits.toIntOrNull()
                    ?: return QuantityResult.Invalid(ParseErrorCode.INVALID_OVERFLOW)
                if (target <= 0) {
                    return QuantityResult.Invalid(ParseErrorCode.INVALID_NUMBER)
                }
                QuantityResult.Found(
                    name = name,
                    type = StepType.COUNT,
                    target = target,
                    durationMs = null
                )
            }

            "秒", "分钟", "分", "小时", "时" -> {
                val amount = digits.toLongOrNull()
                    ?: return QuantityResult.Invalid(ParseErrorCode.INVALID_OVERFLOW)
                if (amount <= 0) {
                    return QuantityResult.Invalid(ParseErrorCode.INVALID_NUMBER)
                }

                val multiplier = when (unit) {
                    "秒" -> 1_000L
                    "分钟", "分" -> 60_000L
                    "小时", "时" -> 3_600_000L
                    else -> return QuantityResult.Invalid(ParseErrorCode.INVALID_UNIT)
                }
                if (amount > Long.MAX_VALUE / multiplier) {
                    return QuantityResult.Invalid(ParseErrorCode.INVALID_OVERFLOW)
                }
                val durationMs = amount * multiplier
                if (durationMs < 1_000L || durationMs % 1_000L != 0L) {
                    return QuantityResult.Invalid(ParseErrorCode.INVALID_UNIT)
                }

                QuantityResult.Found(
                    name = name,
                    type = StepType.TIMER,
                    target = null,
                    durationMs = durationMs
                )
            }

            else -> QuantityResult.Invalid(ParseErrorCode.INVALID_UNIT)
        }
    }

    private fun classifyNoQuantity(core: String): QuantityResult {
        val trimmed = core.trimFlowWhitespace()
        if (trimmed.isEmpty()) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_NAME)
        }
        if (trimmed.endsWith("个") || trimmed.endsWith("毫秒") || trimmed.endsWith("ms")) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_UNIT)
        }

        val unitAtEnd = RECOGNIZED_UNIT_TOKENS.firstOrNull { trimmed.endsWith(it) }
        if (unitAtEnd != null) {
            val prefix = trimmed.dropLast(unitAtEnd.length)
            if (prefix.any { it.isQuantitySignal() }) {
                return QuantityResult.Invalid(ParseErrorCode.INVALID_UNIT)
            }
            return QuantityResult.Incomplete(ParseErrorCode.INCOMPLETE_MISSING_QUANTITY)
        }

        if (hasRecognizedUnitPrecededByQuantitySignal(trimmed)) {
            return QuantityResult.Invalid(ParseErrorCode.INVALID_UNIT)
        }
        if (trimmed.last() in '0'..'9') {
            return QuantityResult.Incomplete(ParseErrorCode.INCOMPLETE_MISSING_UNIT)
        }
        return QuantityResult.Incomplete(ParseErrorCode.INCOMPLETE_MISSING_QUANTITY)
    }

    private fun hasRecognizedUnitPrecededByQuantitySignal(text: String): Boolean {
        for (unit in RECOGNIZED_UNIT_TOKENS) {
            var index = text.indexOf(unit)
            while (index >= 0) {
                if (index > 0 && text[index - 1].isQuantitySignal()) {
                    return true
                }
                index = text.indexOf(unit, index + 1)
            }
        }
        return false
    }

    private fun Char.isQuantitySignal(): Boolean =
        this in '0'..'9' ||
            this in 'a'..'z' ||
            this in 'A'..'Z' ||
            this in CHINESE_NUMERALS

    private fun hasQuantityLike(text: String): Boolean =
        QUANTITY_LIKE_REGEX.matches(text.trimFlowWhitespace())

    private fun hasTrailingAttribute(text: String): Boolean {
        val trimmed = text.trimEndFlowWhitespace()
        return ATTRIBUTE_TOKENS.keys.any { trimmed.endsWith(it) }
    }

    private fun isCompoundQuantity(prefix: String): Boolean {
        for (unit in RECOGNIZED_UNIT_TOKENS) {
            if (prefix.endsWith(unit)) {
                val index = prefix.length - unit.length - 1
                return index >= 0 && prefix[index] in '0'..'9'
            }
        }
        return false
    }

    private fun splitLogicalLines(text: String): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end < 0) {
                lines.add(text.substring(start))
                break
            }
            lines.add(text.substring(start, end))
            start = end + 1
        }
        return lines
    }

    private fun incomplete(lineNumber: Int, raw: String, code: ParseErrorCode): ParsedLine =
        ParsedLine(
            lineNumber = lineNumber,
            raw = raw,
            status = ParseStatus.INCOMPLETE,
            diagnostic = ParseDiagnostic(lineNumber, code)
        )

    private fun invalid(lineNumber: Int, raw: String, code: ParseErrorCode): ParsedLine =
        ParsedLine(
            lineNumber = lineNumber,
            raw = raw,
            status = ParseStatus.INVALID,
            diagnostic = ParseDiagnostic(lineNumber, code)
        )

    private companion object {
        private const val DIGITS = "[0-9]+"
        private const val WS =
            "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

        private val QUANTITY_REGEX = Regex(
            "^(.*?)($DIGITS)$WS*(次|秒|分钟|分|小时|时)$"
        )

        private val QUANTITY_LIKE_REGEX = Regex(
            ".*$DIGITS$WS*(次|秒|分钟|分|小时|时)" +
                "(?:$WS*[xX×]$WS*$DIGITS)?$"
        )

        private val ATTRIBUTE_TOKENS = linkedMapOf(
            "[计数=完成]" to Attribute.COUNT_COMPLETE,
            "[分类=休息]" to Attribute.TIMER_REST,
            "[分类=普通]" to Attribute.TIMER_NORMAL
        )

        private val REPEAT_MARKERS = setOf('x', 'X', '×')
        private val DECIMAL_SEPARATORS = setOf('.', '．', ',', '，')
        private val NUMBER_SIGNS = setOf('-', '+')

        private val RECOGNIZED_UNIT_TOKENS = listOf("分钟", "小时", "秒", "分", "时", "次")
        private val CHINESE_NUMERALS = "零〇一二两三四五六七八九十百千万亿".toSet()
    }
}
