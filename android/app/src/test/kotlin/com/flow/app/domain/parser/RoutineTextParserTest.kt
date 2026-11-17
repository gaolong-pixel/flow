package com.flow.app.domain.parser

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoutineTextParserTest {

    private val parser = RoutineTextParser()
    private val serializer = RoutineTextSerializer()

    @Test
    fun passesAllSharedParserCases() {
        val path = System.getProperty("flow.sharedSpecFile")
            ?: error("flow.sharedSpecFile system property is missing")
        val cases = JSONArray(File(path).readText(Charsets.UTF_8))

        var valid = 0
        var empty = 0
        var incomplete = 0
        var invalid = 0
        var passed = 0
        val failures = mutableListOf<String>()

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getString("input")
            val expectedStatus = ParseStatus.valueOf(case.getString("status"))
            val result = parser.parse(input)

            when (expectedStatus) {
                ParseStatus.VALID -> valid++
                ParseStatus.EMPTY -> empty++
                ParseStatus.INCOMPLETE -> incomplete++
                ParseStatus.INVALID -> invalid++
            }

            if (result.lines.size != 1) {
                failures += "case $index '$input': expected one line, got ${result.lines.size}"
                continue
            }

            val line = result.lines.single()
            if (line.status != expectedStatus) {
                failures += "case $index '$input': expected $expectedStatus, got ${line.status}"
                continue
            }

            if (expectedStatus == ParseStatus.VALID) {
                val expected = case.getJSONObject("expected")
                val step = line.step
                if (step == null) {
                    failures += "case $index '$input': VALID without candidate"
                    continue
                }
                if (step.name != expected.getString("name") ||
                    step.type != StepType.valueOf(expected.getString("type")) ||
                    step.target != nullableInt(expected, "target") ||
                    step.countMode != nullableCountMode(expected, "countMode") ||
                    step.durationMs != nullableLong(expected, "durationMs") ||
                    step.category != nullableTimerCategory(expected, "category") ||
                    step.repeatCount != expected.getInt("repeatCount")
                ) {
                    failures += "case $index '$input': candidate mismatch $step"
                    continue
                }

                val canonical = serializer.serialize(listOf(step))
                val reparsed = parser.parse(canonical)
                if (reparsed.steps.size != 1 || reparsed.steps.single() != step) {
                    failures += "case $index '$input': round-trip mismatch '$canonical'"
                    continue
                }
                val canonicalAgain = serializer.serialize(reparsed.steps)
                if (canonicalAgain != canonical) {
                    failures += "case $index '$input': normalization did not converge"
                    continue
                }
            }

            passed++
        }

        println(
            "parser-cases: total=${cases.length()}, valid=$valid, empty=$empty, " +
                "incomplete=$incomplete, invalid=$invalid, passed=$passed, failed=${failures.size}"
        )

        assertEquals(88, cases.length())
        assertEquals(40, valid)
        assertEquals(3, empty)
        assertEquals(9, incomplete)
        assertEquals(36, invalid)
        assertEquals(88, passed)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun normalizesLineEndingsAndPreservesEmptyLineNumbers() {
        val result = parser.parse("俯卧撑 15次\r\n\r\n休息 60秒\r俯卧撑 0次")

        assertEquals(4, result.lines.size)
        assertEquals(
            listOf(
                ParseStatus.VALID,
                ParseStatus.EMPTY,
                ParseStatus.VALID,
                ParseStatus.INVALID
            ),
            result.lines.map { it.status }
        )
        assertEquals(listOf(1, 2, 3, 4), result.lines.map { it.lineNumber })
        assertEquals(2, result.stats.valid)
        assertEquals(1, result.stats.empty)
        assertEquals(1, result.stats.invalid)
        assertEquals(0, result.steps.size)
        assertNotNull(result.lines[0].step)
        assertNotNull(result.lines[2].step)
        assertFalse(result.canCommit)
    }

    @Test
    fun emptyDocumentAndBlankLinesAreEmpty() {
        val emptyDocument = parser.parse("")
        assertEquals(1, emptyDocument.lines.size)
        assertEquals(ParseStatus.EMPTY, emptyDocument.lines.single().status)
        assertEquals(0, emptyDocument.stats.total - emptyDocument.stats.empty)
        assertTrue(emptyDocument.canCommit)
        assertEquals(emptyList<StepCandidate>(), emptyDocument.steps)
        assertTrue(emptyDocument.diagnostics.isEmpty())

        val whitespaceOnly = parser.parse("   \n\t\n\u3000")
        assertEquals(3, whitespaceOnly.lines.size)
        assertTrue(whitespaceOnly.lines.all { it.status == ParseStatus.EMPTY })
        assertEquals(3, whitespaceOnly.stats.empty)
        assertTrue(whitespaceOnly.canCommit)
    }

    @Test
    fun exposesStableDiagnosticCodes() {
        assertCode("俯卧撑", ParseStatus.INCOMPLETE, ParseErrorCode.INCOMPLETE_MISSING_QUANTITY)
        assertCode("俯卧撑 15", ParseStatus.INCOMPLETE, ParseErrorCode.INCOMPLETE_MISSING_UNIT)
        assertCode("俯卧撑 15次 x", ParseStatus.INCOMPLETE, ParseErrorCode.INCOMPLETE_REPEAT_COUNT)
        assertCode("俯卧撑 15次 [", ParseStatus.INCOMPLETE, ParseErrorCode.INCOMPLETE_ATTRIBUTE)
        assertCode("俯卧撑 15个", ParseStatus.INVALID, ParseErrorCode.INVALID_UNIT)
        assertCode("阅读 1.5秒", ParseStatus.INVALID, ParseErrorCode.INVALID_DECIMAL)
        assertCode("阅读 1小时30分钟", ParseStatus.INVALID, ParseErrorCode.INVALID_COMPOUND)
        assertCode("俯卧撑 15次 [分类=休息]", ParseStatus.INVALID, ParseErrorCode.INVALID_ATTRIBUTE_TYPE)
        assertCode("俯卧撑 15次 [计数=递增]", ParseStatus.INVALID, ParseErrorCode.INVALID_ATTRIBUTE)
        assertCode("阅读 9223372036854775807秒", ParseStatus.INVALID, ParseErrorCode.INVALID_OVERFLOW)
    }

    @Test
    fun rejectsInvalidAttributesAndKeepsOtherLinesDiagnosable() {
        val result = parser.parse(
            "俯卧撑 15次\n" +
                "俯卧撑 15次 [计数=完成]\n" +
                "俯卧撑 15次 [计数=递增]\n" +
                "阅读 20分钟"
        )

        assertEquals(4, result.lines.size)
        assertEquals(ParseStatus.VALID, result.lines[0].status)
        assertEquals(ParseStatus.VALID, result.lines[1].status)
        assertEquals(ParseStatus.INVALID, result.lines[2].status)
        assertEquals(ParseStatus.VALID, result.lines[3].status)
        assertEquals(0, result.steps.size)
        assertNotNull(result.lines[0].step)
        assertNotNull(result.lines[1].step)
        assertNotNull(result.lines[3].step)
        assertEquals(1, result.diagnostics.size)
        assertEquals(3, result.diagnostics.single().lineNumber)
        assertFalse(result.canCommit)
    }

    @Test
    fun bareNamesContainingUnitCharactersRemainIncomplete() {
        listOf("时间", "得分", "阅读分钟", "第2组 时间", "名称[foo]", "名称]").forEach { input ->
            val line = parser.parse(input).lines.single()
            assertEquals("status for '$input'", ParseStatus.INCOMPLETE, line.status)
        }
    }

    @Test
    fun infersTimerCategoryAndHonorsExplicitOverride() {
        val inferredRest = parser.parse("休息 60秒").lines.single().step
        assertEquals(TimerCategory.REST, inferredRest?.category)

        val explicitNormal = parser.parse("休息 60秒 [分类=普通]").lines.single().step
        assertEquals(TimerCategory.NORMAL, explicitNormal?.category)

        val inferredNormal = parser.parse("阅读 20分钟").lines.single().step
        assertEquals(TimerCategory.NORMAL, inferredNormal?.category)

        val explicitRest = parser.parse("阅读 20分钟 [分类=休息]").lines.single().step
        assertEquals(TimerCategory.REST, explicitRest?.category)
    }

    @Test
    fun trimsNamesAndPreservesInternalWhitespaceAcrossRoundTrip() {
        val input = "  \u00A0第 2  组 深蹲\u2007 20次  "
        val parsed = parser.parse(input)
        assertTrue(parsed.canCommit)
        val step = parsed.lines.single().step
        assertEquals("第 2  组 深蹲", step?.name)

        val canonical = serializer.serialize(parsed.steps)
        val reparsed = parser.parse(canonical)
        assertTrue(reparsed.canCommit)
        assertEquals(step?.name, reparsed.steps.single().name)
    }

    @Test
    fun parserNeverAllocatesPersistentStepIdentity() {
        val line = parser.parse("俯卧撑 15次").lines.single()
        assertNotNull(line.step)
        // StepCandidate intentionally has no id/order properties.
        assertEquals("俯卧撑", line.step?.name)
        assertEquals(StepType.COUNT, line.step?.type)
    }

    private fun assertCode(input: String, expectedStatus: ParseStatus, expected: ParseErrorCode) {
        val line = parser.parse(input).lines.single()
        assertEquals("status for '$input'", expectedStatus, line.status)
        assertEquals("code for '$input'", expected, line.diagnostic?.code)
    }

    private fun nullableInt(obj: JSONObject, key: String): Int? =
        if (obj.isNull(key)) null else obj.getInt(key)

    private fun nullableLong(obj: JSONObject, key: String): Long? =
        if (obj.isNull(key)) null else obj.getLong(key)

    private fun nullableCountMode(obj: JSONObject, key: String): CountMode? =
        if (obj.isNull(key)) null else CountMode.valueOf(obj.getString(key))

    private fun nullableTimerCategory(obj: JSONObject, key: String): TimerCategory? =
        if (obj.isNull(key)) null else TimerCategory.valueOf(obj.getString(key))
}
