package com.flow.app.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Flow design tokens — light theme is the product baseline.
val FlowBackground = Color(0xFFF5F7FA)   // Background
val FlowSurface = Color(0xFFFFFFFF)       // Surface
val FlowText = Color(0xFF172033)          // Primary
val FlowSecondaryText = Color(0xFF7B8495) // Secondary
val FlowPrimary = Color(0xFF356AE6)       // Accent
val FlowPrimarySoft = Color(0xFFEAF0FF)   // AccentSoft
val FlowOutline = Color(0xFFE5E9F0)       // Border
val FlowSegmentBackground = Color(0xFFE9EDF3)
val FlowSynced = Color(0xFF21865B)        // Success
val FlowDirty = Color(0xFFD58A2A)         // Warning
val FlowInvalid = Color(0xFFD94B4B)       // Danger
val FlowSuccess = FlowSynced
val FlowWarning = FlowDirty
val FlowDanger = FlowInvalid
val FlowSuccessSoft = Color(0xFFE8F5EE)
val FlowWarningSoft = Color(0xFFFFF4E5)
val FlowDangerSoft = Color(0xFFFDEEEE)
val FlowAccent = FlowPrimary
val FlowAccentSoft = FlowPrimarySoft
val FlowBorder = FlowOutline
val FlowConnector = Color(0xFFDCE3EF)

object FlowSpacing {
    val page = 22.dp
    val card = 18.dp
    val gap = 14.dp
    val small = 8.dp
    val section = 24.dp
}

object FlowRadius {
    val card = 18.dp
    val button = 14.dp
    val input = 12.dp
    val pill = 50.dp
}

private val FlowColorScheme = lightColorScheme(
    primary = FlowPrimary,
    onPrimary = Color.White,
    primaryContainer = FlowPrimarySoft,
    onPrimaryContainer = FlowText,
    background = FlowBackground,
    onBackground = FlowText,
    surface = FlowSurface,
    onSurface = FlowText,
    surfaceVariant = FlowSegmentBackground,
    onSurfaceVariant = FlowSecondaryText,
    outline = FlowOutline,
    error = FlowInvalid,
    onError = Color.White
)

@Composable
fun FlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlowColorScheme,
        typography = Typography(),
        content = content
    )
}
