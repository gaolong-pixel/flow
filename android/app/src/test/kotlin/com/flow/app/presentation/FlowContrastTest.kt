package com.flow.app.presentation

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard the text/graphics pairs that carry meaning on the dark run screen and
 * the small functional copy on light surfaces. Ratios use the WCAG 2.x
 * relative-luminance formula on the sRGB channel values.
 */
class FlowContrastTest {
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertAtLeast(ratio: Double, foreground: Color, background: Color, label: String) {
        val actual = contrast(foreground, background)
        assertTrue("$label contrast was $actual, expected >= $ratio", actual >= ratio)
    }

    @Test
    fun darkExecutionTextMeetsAa() {
        val background = FlowRunBackground
        assertAtLeast(7.0, FlowRunText, background, "run primary text")
        assertAtLeast(4.5, FlowRunSecondaryText, background, "run secondary text")
        assertAtLeast(4.5, FlowRunMutedText, background, "run muted text")
        assertAtLeast(4.5, FlowPrimaryBright, background, "count accent")
        assertAtLeast(4.5, FlowWarm, background, "timer accent")
        assertAtLeast(4.5, FlowSuccess, background, "overall progress")
        assertAtLeast(4.5, FlowRunDanger, background, "expired state")
    }

    @Test
    fun lightFunctionalTextMeetsAa() {
        assertAtLeast(7.0, FlowText, FlowBackground, "page primary text")
        assertAtLeast(4.5, FlowSecondaryInk, FlowSurface, "small light copy")
        assertAtLeast(4.5, FlowWarmInk, FlowSurface, "timer ink")
        assertAtLeast(4.5, FlowSuccessInk, FlowSurface, "success ink")
        assertAtLeast(4.5, Color.White, FlowPrimary, "primary button label")
    }
}
