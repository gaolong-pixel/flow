package com.flow.app.domain.parser

import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineTextSerializerTest {

    private val serializer = RoutineTextSerializer()
    private val parser = RoutineTextParser()

    @Test
    fun serializesCanonicalCountAndTimerForms() {
        val steps = listOf(
            count("俯卧撑", 15),
            count("深蹲", 20, CountMode.COMPLETE, 3),
            timer("阅读", 1_200_000),
            timer("拉伸", 90_000, TimerCategory.NORMAL, 2),
            timer("休息", 60_000, TimerCategory.REST),
            timer("拉伸", 45_000, TimerCategory.REST),
            timer("休息", 60_000, TimerCategory.NORMAL),
            timer("阅读", 3_600_000),
            timer("阅读", 60_000),
            timer("阅读", 1_000),
            count("第2组 深蹲", 20)
        )

        val expected = listOf(
            "俯卧撑 15次",
            "深蹲 20次 x 3 [计数=完成]",
            "阅读 20分钟",
            "拉伸 90秒 x 2",
            "休息 1分钟",
            "拉伸 45秒 [分类=休息]",
            "休息 1分钟 [分类=普通]",
            "阅读 1小时",
            "阅读 1分钟",
            "阅读 1秒",
            "第2组 深蹲 20次"
        ).joinToString("\n")

        assertEquals(expected, serializer.serialize(steps))
    }

    @Test
    fun serializesEmptyListAsEmptyString() {
        assertEquals("", serializer.serialize(emptyList()))
    }

    @Test
    fun trimsCandidateNameButPreservesInternalWhitespace() {
        val step = count("  Push  ups  ", 15)
        val text = serializer.serialize(listOf(step))
        assertEquals("Push  ups 15次", text)

        val reparsed = parser.parse(text).lines.single().step
        assertEquals("Push  ups", reparsed?.name)
    }

    @Test
    fun rejectsCandidateNameThatIsBlankAfterTrim() {
        val invalid = count(" \u00A0\u2007\u202F ", 1)
        val exception = assertThrows(SerializationException::class.java) {
            serializer.serialize(listOf(invalid))
        }
        assertEquals(SerializationError.INVALID_STEP, exception.code)
    }

    @Test
    fun preservesInputOrder() {
        val steps = listOf(
            count("A", 1),
            timer("B", 1_000),
            count("C", 3)
        )
        assertEquals(
            "A 1次\nB 1秒\nC 3次",
            serializer.serialize(steps)
        )
    }

    @Test
    fun rejectsInvalidStepAtomically() {
        val invalid = StepCandidate(
            name = "阅读",
            type = StepType.TIMER,
            durationMs = 1_500,
            category = TimerCategory.NORMAL
        )
        val exception = assertThrows(SerializationException::class.java) {
            serializer.serialize(listOf(count("A", 1), invalid))
        }
        assertEquals(SerializationError.INVALID_STEP, exception.code)
        assertEquals(1, exception.stepIndex)
    }

    @Test
    fun rejectsNamesWithNewlines() {
        val invalid = count("A\nB", 1)
        val exception = assertThrows(SerializationException::class.java) {
            serializer.serialize(listOf(invalid))
        }
        assertEquals(SerializationError.INVALID_NAME, exception.code)
    }

    @Test
    fun roundTripPreservesAllSemanticFields() {
        val original = listOf(
            count("俯卧撑", 15),
            count("深蹲", 20, CountMode.COMPLETE, 3),
            timer("阅读", 1_200_000, TimerCategory.NORMAL, 2),
            timer("拉伸", 45_000, TimerCategory.REST, 1),
            timer("休息", 60_000, TimerCategory.NORMAL, 4)
        )

        val serialized = serializer.serialize(original)
        val parsed = parser.parse(serialized)
        assertTrue(parsed.canCommit)
        assertEquals(original.size, parsed.steps.size)
        assertEquals(original, parsed.steps)
    }

    @Test
    fun normalizationConverges() {
        val inputs = listOf(
            "俯卧撑15次",
            "俯卧撑 15 次 x 2 [计数=完成]",
            "阅读20分钟",
            "阅读 20分 [分类=休息]",
            "拉伸45秒x2[分类=休息]",
            "休息 60秒 [分类=普通]"
        )

        inputs.forEach { input ->
            val first = parser.parse(input)
            assertTrue("input should be valid: $input", first.canCommit)
            val firstText = serializer.serialize(first.steps)
            val second = parser.parse(firstText)
            assertTrue("canonical text should be valid: $firstText", second.canCommit)
            val secondText = serializer.serialize(second.steps)
            assertEquals("normalization must converge: $input", firstText, secondText)
        }
    }

    private fun count(
        name: String,
        target: Int,
        countMode: CountMode = CountMode.INCREMENT,
        repeatCount: Int = 1
    ) = StepCandidate(
        name = name,
        type = StepType.COUNT,
        target = target,
        countMode = countMode,
        repeatCount = repeatCount
    )

    private fun timer(
        name: String,
        durationMs: Long,
        category: TimerCategory = TimerCategory.NORMAL,
        repeatCount: Int = 1
    ) = StepCandidate(
        name = name,
        type = StepType.TIMER,
        durationMs = durationMs,
        category = category,
        repeatCount = repeatCount
    )
}
