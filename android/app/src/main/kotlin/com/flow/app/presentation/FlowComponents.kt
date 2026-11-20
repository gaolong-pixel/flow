package com.flow.app.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flow.app.domain.model.StepType

@Composable
fun FlowCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = FlowSpacing.card,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FlowRadius.card),
        color = FlowSurface,
        border = BorderStroke(1.dp, FlowOutline)
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun FlowPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 54.dp
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(FlowRadius.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = FlowPrimary,
            contentColor = Color.White,
            disabledContainerColor = FlowSegmentBackground,
            disabledContentColor = FlowSecondaryText
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FlowSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 46.dp
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(FlowRadius.button),
        border = BorderStroke(1.dp, FlowOutline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = FlowPrimary)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FlowTypeBadge(type: StepType) {
    val label = if (type == StepType.COUNT) "COUNT" else "TIMER"
    val color = if (type == StepType.COUNT) FlowPrimary else FlowWarning
    Box(
        modifier = Modifier
            .height(28.dp)
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
fun FlowSectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = FlowText
        )
        Spacer(Modifier.weight(1f))
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = FlowSecondaryText)
        }
    }
}

@Composable
fun FlowProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(FlowRadius.pill))
            .background(FlowSegmentBackground)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(FlowRadius.pill))
                .background(FlowPrimary)
        )
    }
}

@Composable
fun FlowStepIndex(index: Int, active: Boolean = false) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(if (active) FlowPrimary else FlowSegmentBackground, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Color.White else FlowSecondaryText,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun FlowRowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FlowOutline)
    )
}

@Composable
fun FlowPill(text: String, color: Color = FlowSecondaryText) {
    Surface(shape = RoundedCornerShape(FlowRadius.pill), color = color.copy(alpha = 0.10f)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}
