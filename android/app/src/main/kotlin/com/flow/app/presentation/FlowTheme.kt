package com.flow.app.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FlowBackground = Color(0xFFF7F6F2)
val FlowSurface = Color(0xFFFFFFFF)
val FlowPrimary = Color(0xFF2F6B5F)
val FlowPrimarySoft = Color(0xFFE5F0EB)
val FlowText = Color(0xFF1E231F)
val FlowSecondaryText = Color(0xFF6C736D)
val FlowOutline = Color(0xFFE4E1D9)
val FlowSegmentBackground = Color(0xFFEDEBE4)
val FlowSynced = Color(0xFF2D6B57)
val FlowDirty = Color(0xFF946200)
val FlowInvalid = Color(0xFFB3261E)

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
