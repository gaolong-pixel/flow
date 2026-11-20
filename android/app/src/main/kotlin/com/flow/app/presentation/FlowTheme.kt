package com.flow.app.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Flow design tokens — light screens use the light palette, the run screen
// uses the dark run palette. Business state never lives here.
val FlowBackground = Color(0xFFF5F7FA)   // Page background
val FlowSurface = Color(0xFFFFFFFF)       // Surface
val FlowSurfaceMuted = Color(0xFFEEF2F8)  // Quiet surface
val FlowText = Color(0xFF172033)          // Primary text
val FlowSecondaryText = Color(0xFF8491A5) // Secondary text (spec token)
val FlowSecondaryInk = Color(0xFF5F6C80)  // Contrast-safe small copy on light
val FlowPrimary = Color(0xFF356AE6)       // Primary accent
val FlowPrimaryBright = Color(0xFF5B8CFF) // Dynamic accent on dark
val FlowPrimarySoft = Color(0xFFEAF0FF)   // Accent soft
val FlowOutline = Color(0xFFE5E9F0)       // Border
val FlowOutlineStrong = Color(0xFFD5DEEA)
val FlowSegmentBackground = Color(0xFFE9EDF3)

// Semantic accents. The saturated tones are for graphics; the Ink variants
// are used for small text on light surfaces to keep readable contrast.
val FlowSuccess = Color(0xFF35C99A)
val FlowSuccessInk = Color(0xFF0E7A60)
val FlowSuccessSoft = Color(0xFFE7F9F2)
val FlowWarm = Color(0xFFFFB45E)
val FlowWarmInk = Color(0xFFA86200)
val FlowWarmSoft = Color(0xFFFFF1DE)
val FlowDanger = Color(0xFFD94B4B)
val FlowDangerSoft = Color(0xFFFDEEEE)
val FlowSynced = FlowSuccessInk
val FlowDirty = FlowWarmInk
val FlowInvalid = FlowDanger
val FlowWarning = FlowWarmInk
val FlowAccent = FlowPrimary
val FlowAccentSoft = FlowPrimarySoft
val FlowBorder = FlowOutline
val FlowConnector = Color(0xFFDCE3EF)

// Dark execution screen.
val FlowRunBackground = Color(0xFF0B1220)
val FlowRunSurface = Color(0xFF16233B)
val FlowRunSurfaceRaised = Color(0xFF1D2D46)
val FlowRunText = Color(0xFFF5F8FF)
val FlowRunSecondaryText = Color(0xFF9AAAC1)
val FlowRunMutedText = Color(0xFF7D8DA6)
val FlowRunTrack = Color(0xFF22314A)
val FlowRunTrackStrong = Color(0xFF2D4363)
val FlowRunOutline = Color(0xFF31425C)
val FlowRunDanger = Color(0xFFFF7A7A)
val FlowRunCountType = FlowPrimaryBright
val FlowRunTimerType = FlowWarm

object FlowSpacing {
    val page = 22.dp
    val card = 18.dp
    val gap = 14.dp
    val small = 8.dp
    val section = 24.dp
}

object FlowRadius {
    val card = 18.dp
    val sheet = 18.dp
    val button = 18.dp
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
