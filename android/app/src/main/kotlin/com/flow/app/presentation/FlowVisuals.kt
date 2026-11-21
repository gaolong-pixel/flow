package com.flow.app.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.flow.app.domain.run.NodeRunState
import com.flow.app.domain.run.flowDisplayNumberSizeSp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FlowSystemBars(dark: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = context.findFlowActivity() ?: return@SideEffect
        @Suppress("DEPRECATION")
        run {
            val background = if (dark) FlowRunBackground else FlowBackground
            activity.window.statusBarColor = background.toArgb()
            activity.window.navigationBarColor = background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isStatusBarContrastEnforced = false
                activity.window.isNavigationBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}

private fun Context.findFlowActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findFlowActivity()
    else -> null
}

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val resolver = context.contentResolver
        val animator = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val transition = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        }.getOrDefault(1f)
        animator == 0f || transition == 0f
    }
}

class FlowHaptics(private val feedback: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun commit() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberFlowHaptics(): FlowHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { FlowHaptics(feedback) }
}

// ---------------------------------------------------------------------------
// Brand / icons
// ---------------------------------------------------------------------------

@Composable
fun FlowBoltMark(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.18f, h * 0.55f)
            lineTo(w * 0.48f, h * 0.55f)
            lineTo(w * 0.38f, h)
            lineTo(w * 0.84f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(
            path,
            Brush.linearGradient(
                colors = listOf(Color(0xFF75A8FF), Color(0xFF3B82F6), Color(0xFF2457D8)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
        )
    }
}

enum class FlowPlanIcon { Sun, Target, Flow }

@Composable
fun FlowPlanIconTile(
    icon: FlowPlanIcon,
    modifier: Modifier = Modifier,
    tileSize: Dp = 44.dp
) {
    val (start, end) = when (icon) {
        FlowPlanIcon.Sun -> Color(0xFFF2F9FF) to Color(0xFFD9EBFF)
        FlowPlanIcon.Target -> Color(0xFFF4F2FF) to Color(0xFFE3E7FF)
        FlowPlanIcon.Flow -> Color(0xFFEFF8FF) to Color(0xFFD9EAFF)
    }
    Box(
        modifier = modifier
            .size(tileSize)
            .background(
                Brush.linearGradient(listOf(start, end)),
                RoundedCornerShape(12.dp)
            )
            .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(23.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.8.dp.toPx()
            val brush = Brush.linearGradient(
                colors = listOf(Color(0xFF6CA0FF), Color(0xFF2F6BFF)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
            val cx = w / 2f
            val cy = h / 2f
            when (icon) {
                FlowPlanIcon.Sun -> {
                    drawCircle(brush, radius = w * 0.18f, center = Offset(cx, cy), style = Stroke(stroke))
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val inner = w * 0.30f
                        val outer = w * 0.43f
                        drawLine(
                            brush = brush,
                            start = Offset(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat()),
                            end = Offset(cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat()),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
                FlowPlanIcon.Target -> {
                    drawCircle(brush, radius = w * 0.4f, center = Offset(cx, cy), style = Stroke(stroke))
                    drawCircle(brush, radius = w * 0.24f, center = Offset(cx, cy), style = Stroke(stroke * 0.9f))
                    drawCircle(brush, radius = w * 0.075f, center = Offset(cx, cy))
                }
                FlowPlanIcon.Flow -> {
                    for (i in 0 until 3) {
                        val y = h * (0.28f + i * 0.22f)
                        val p = Path().apply {
                            moveTo(w * 0.08f, y)
                            cubicTo(
                                w * 0.34f, y - h * 0.16f,
                                w * 0.62f, y + h * 0.16f,
                                w * 0.92f, y
                            )
                        }
                        drawPath(p, brush, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}

@Composable
fun FlowTypeGlyph(
    type: com.flow.app.domain.model.StepType,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    onDark: Boolean = false
) {
    val color = when {
        type == com.flow.app.domain.model.StepType.COUNT && onDark -> FlowPrimaryBright
        type == com.flow.app.domain.model.StepType.COUNT -> FlowPrimary
        onDark -> FlowWarm
        else -> FlowWarmInk
    }
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        if (type == com.flow.app.domain.model.StepType.COUNT) {
            val w = this.size.width
            val h = this.size.height
            for (i in 0 until 4) {
                val x = w * (0.2f + i * 0.2f)
                drawLine(color, Offset(x, h * 0.2f), Offset(x, h * 0.82f), stroke, StrokeCap.Round)
            }
            drawLine(color, Offset(w * 0.08f, h * 0.22f), Offset(w * 0.92f, h * 0.78f), stroke, StrokeCap.Round)
        } else {
            drawCircle(color, radius = this.size.minDimension * 0.4f, style = Stroke(stroke))
            drawLine(color, Offset(this.size.width * 0.5f, this.size.height * 0.3f), Offset(this.size.width * 0.5f, this.size.height * 0.53f), stroke, StrokeCap.Round)
            drawLine(color, Offset(this.size.width * 0.5f, this.size.height * 0.53f), Offset(this.size.width * 0.64f, this.size.height * 0.63f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
fun FlowChevron(
    modifier: Modifier = Modifier,
    glyphSize: Dp = 14.dp,
    color: Color = FlowSecondaryText
) {
    Canvas(modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.18f)
            lineTo(w * 0.7f, h * 0.5f)
            lineTo(w * 0.34f, h * 0.82f)
        }
        drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun FlowBackChevron(
    modifier: Modifier = Modifier,
    glyphSize: Dp = 18.dp,
    color: Color = FlowText
) {
    Canvas(modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.64f, h * 0.16f)
            lineTo(w * 0.3f, h * 0.5f)
            lineTo(w * 0.64f, h * 0.84f)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun FlowClockIcon(
    modifier: Modifier = Modifier,
    glyphSize: Dp = 14.dp,
    color: Color = FlowSecondaryText
) {
    Canvas(modifier.size(glyphSize)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, radius = size.minDimension * 0.4f, style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.28f), Offset(size.width * 0.5f, size.height * 0.52f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.52f), Offset(size.width * 0.64f, size.height * 0.61f), stroke, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------
// Illustrated backgrounds
// ---------------------------------------------------------------------------

enum class FlowArtMode { Home, Prepare, Done, Exit }

@Composable
fun FlowLightArt(mode: FlowArtMode = FlowArtMode.Home) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(FlowBackground)
        // soft top-right aurora
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x339CC0FF), Color(0x000B1220)),
                center = Offset(size.width * 0.92f, size.height * 0.08f),
                radius = size.width * 0.9f
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width * 0.92f, size.height * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2BB9D3FF), Color.Transparent),
                center = Offset(size.width * 0.05f, size.height * 0.92f),
                radius = size.width * 0.75f
            ),
            radius = size.width * 0.75f,
            center = Offset(size.width * 0.05f, size.height * 0.92f)
        )

        // flowing ribbons
        val ribbonA = Path().apply {
            moveTo(-size.width * 0.2f, size.height * 0.08f)
            cubicTo(
                size.width * 0.18f, -size.height * 0.02f,
                size.width * 0.72f, size.height * 0.03f,
                size.width * 1.18f, size.height * 0.26f
            )
            lineTo(size.width * 1.18f, size.height * 0.38f)
            cubicTo(
                size.width * 0.72f, size.height * 0.15f,
                size.width * 0.2f, size.height * 0.12f,
                -size.width * 0.2f, size.height * 0.25f
            )
            close()
        }
        drawPath(
            ribbonA,
            Brush.linearGradient(
                colors = listOf(Color(0x559CC0FF), Color(0x11EAF2FF)),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height * 0.4f)
            )
        )
        val ribbonB = Path().apply {
            moveTo(-size.width * 0.2f, size.height * 0.68f)
            cubicTo(
                size.width * 0.25f, size.height * 0.56f,
                size.width * 0.74f, size.height * 0.60f,
                size.width * 1.2f, size.height * 0.78f
            )
            lineTo(size.width * 1.2f, size.height * 1.02f)
            lineTo(-size.width * 0.2f, size.height * 1.02f)
            close()
        }
        drawPath(
            ribbonB,
            Brush.linearGradient(
                colors = listOf(Color(0x33C6DCFF), Color(0x05F4F8FF)),
                start = Offset(0f, size.height * 0.65f),
                end = Offset(size.width, size.height)
            )
        )

        // contour lines
        val contourColor = Color(0xFF9FBDF0).copy(alpha = 0.30f)
        for (i in 0 until 5) {
            val y = size.height * (0.30f + i * 0.045f)
            val p = Path().apply {
                moveTo(-size.width * 0.1f, y)
                cubicTo(
                    size.width * 0.18f, y - size.height * 0.05f,
                    size.width * 0.42f, y + size.height * 0.04f,
                    size.width * 0.63f, y - size.height * 0.02f
                )
                cubicTo(
                    size.width * 0.82f, y - size.height * 0.06f,
                    size.width * 0.96f, y - size.height * 0.02f,
                    size.width * 1.1f, y + size.height * 0.01f
                )
            }
            drawPath(p, contourColor, style = Stroke(1.dp.toPx()))
        }
        // sparkles
        val sparkles = listOf(
            Offset(0.15f, 0.22f), Offset(0.7f, 0.11f),
            Offset(0.24f, 0.72f), Offset(0.86f, 0.62f)
        )
        sparkles.forEach { p ->
            drawCircle(
                color = FlowPrimary.copy(alpha = 0.32f),
                radius = 1.6.dp.toPx(),
                center = Offset(size.width * p.x, size.height * p.y)
            )
        }
    }
}

@Composable
fun FlowDarkArt() {
    val reduceMotion = rememberReduceMotion()
    val infinite = rememberInfiniteTransition(label = "darkArt")
    val breath by infinite.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing)
        ),
        label = "darkBreath"
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(FlowRunBackground)
        val glowAlpha = if (reduceMotion) 1f else breath
        // central energy field
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    FlowPrimary.copy(alpha = 0.30f * glowAlpha),
                    FlowPrimary.copy(alpha = 0.10f * glowAlpha),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.50f),
                radius = size.width * 0.75f
            ),
            radius = size.width * 0.75f,
            center = Offset(size.width * 0.5f, size.height * 0.50f)
        )
        // orbital contours
        val center = Offset(size.width * 0.5f, size.height * 0.50f)
        for (i in 0 until 3) {
            drawCircle(
                color = Color(0xFF31527F).copy(alpha = 0.28f),
                radius = size.width * (0.48f + i * 0.10f),
                center = center,
                style = Stroke(1.dp.toPx())
            )
        }
        // horizon waves
        val waveA = Path().apply {
            moveTo(-size.width * 0.15f, size.height * 0.73f)
            cubicTo(
                size.width * 0.18f, size.height * 0.68f,
                size.width * 0.4f, size.height * 0.76f,
                size.width * 0.63f, size.height * 0.72f
            )
            cubicTo(
                size.width * 0.82f, size.height * 0.69f,
                size.width * 0.96f, size.height * 0.73f,
                size.width * 1.15f, size.height * 0.70f
            )
            lineTo(size.width * 1.15f, size.height * 1.05f)
            lineTo(-size.width * 0.15f, size.height * 1.05f)
            close()
        }
        drawPath(
            waveA,
            Brush.verticalGradient(
                colors = listOf(Color(0xCC14294A), FlowRunBackground),
                startY = size.height * 0.7f,
                endY = size.height
            )
        )
        val waveB = Path().apply {
            moveTo(-size.width * 0.15f, size.height * 0.82f)
            cubicTo(
                size.width * 0.2f, size.height * 0.77f,
                size.width * 0.45f, size.height * 0.85f,
                size.width * 0.66f, size.height * 0.80f
            )
            cubicTo(
                size.width * 0.86f, size.height * 0.76f,
                size.width * 0.98f, size.height * 0.8f,
                size.width * 1.15f, size.height * 0.78f
            )
            lineTo(size.width * 1.15f, size.height * 1.05f)
            lineTo(-size.width * 0.15f, size.height * 1.05f)
            close()
        }
        drawPath(
            waveB,
            Brush.verticalGradient(
                colors = listOf(Color(0xB30E1D35), Color(0xFF080F1B)),
                startY = size.height * 0.78f,
                endY = size.height
            )
        )
        // star specks
        val stars = listOf(
            Offset(0.12f, 0.20f), Offset(0.84f, 0.28f), Offset(0.74f, 0.14f),
            Offset(0.22f, 0.62f), Offset(0.86f, 0.58f), Offset(0.46f, 0.11f)
        )
        stars.forEach { p ->
            drawCircle(
                color = Color(0xFFA9C8FF).copy(alpha = 0.38f),
                radius = 1.4.dp.toPx(),
                center = Offset(size.width * p.x, size.height * p.y)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

enum class FlowActionIcon { Play, Pause, Check, Close, Loop }

@Composable
fun FlowCircleAction(
    icon: FlowActionIcon,
    label: String,
    accessibilityLabel: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "actionScale")
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (primary) {
                Box(
                    Modifier
                        .size(78.dp)
                        .background(FlowPrimary.copy(alpha = 0.10f), CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .size(if (primary) 66.dp else 58.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(
                        if (primary) {
                            Brush.linearGradient(listOf(FlowPrimaryBright, FlowPrimary))
                        } else {
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.05f))
                            )
                        }
                    )
                    .border(
                        1.dp,
                        if (primary) Color.Transparent else Color.White.copy(alpha = 0.12f),
                        CircleShape
                    )
                    .clickable(
                        enabled = enabled,
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                    .semantics {
                        contentDescription = accessibilityLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val c = Color.White.copy(alpha = if (primary) 0.98f else 0.82f)
                    val stroke = 2.dp.toPx()
                    val w = size.width
                    val h = size.height
                    when (icon) {
                        FlowActionIcon.Play -> {
                            val path = Path().apply {
                                moveTo(w * 0.30f, h * 0.18f)
                                lineTo(w * 0.82f, h * 0.5f)
                                lineTo(w * 0.30f, h * 0.82f)
                                close()
                            }
                            drawPath(path, c)
                        }
                        FlowActionIcon.Pause -> {
                            drawLine(c, Offset(w * 0.34f, h * 0.22f), Offset(w * 0.34f, h * 0.78f), stroke, StrokeCap.Round)
                            drawLine(c, Offset(w * 0.66f, h * 0.22f), Offset(w * 0.66f, h * 0.78f), stroke, StrokeCap.Round)
                        }
                        FlowActionIcon.Check -> {
                            val path = Path().apply {
                                moveTo(w * 0.2f, h * 0.53f)
                                lineTo(w * 0.43f, h * 0.74f)
                                lineTo(w * 0.8f, h * 0.28f)
                            }
                            drawPath(path, c, style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                        FlowActionIcon.Close -> {
                            drawLine(c, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), stroke, StrokeCap.Round)
                            drawLine(c, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), stroke, StrokeCap.Round)
                        }
                        FlowActionIcon.Loop -> {
                            val p = Path().apply {
                                arcTo(
                                    androidx.compose.ui.geometry.Rect(
                                        w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f
                                    ),
                                    40f, 280f, false
                                )
                            }
                            drawPath(p, c, style = Stroke(stroke, cap = StrokeCap.Round))
                            drawLine(c, Offset(w * 0.72f, h * 0.16f), Offset(w * 0.82f, h * 0.27f), stroke, StrokeCap.Round)
                            drawLine(c, Offset(w * 0.82f, h * 0.27f), Offset(w * 0.68f, h * 0.31f), stroke, StrokeCap.Round)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = if (primary) Color.White.copy(alpha = 0.78f) else FlowRunSecondaryText
        )
    }
}

@Composable
fun FlowGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = FlowPrimary.copy(alpha = 0.25f),
                spotColor = FlowPrimary.copy(alpha = 0.30f)
            )
            .clip(shape)
            .background(
                if (enabled) Brush.linearGradient(listOf(Color(0xFF4F8CFF), Color(0xFF2F6BFF)))
                else Brush.linearGradient(listOf(FlowSegmentBackground, FlowSegmentBackground))
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else FlowSecondaryText
        )
    }
}

@Composable
fun FlowOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.72f))
            .border(1.dp, FlowOutline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) FlowPrimary else FlowSecondaryText
        )
    }
}

@Composable
fun FlowRunProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = size.height / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(r, r)
        )
        if (progress > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(FlowPrimaryBright, FlowPrimary)),
                size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
                cornerRadius = CornerRadius(r, r)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rings
// ---------------------------------------------------------------------------

@Composable
fun FlowTimerRing(
    diameter: Dp,
    overallFraction: Float,
    remainingFraction: Float,
    countdown: String,
    state: NodeRunState,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedOverall by animateFloatAsState(
        targetValue = overallFraction.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) tween(0) else tween(420, easing = FastOutSlowInEasing),
        label = "timerOverall"
    )
    val targetRemaining = if (state == NodeRunState.READY) 1f else remainingFraction.coerceIn(0f, 1f)
    val animatedRemaining by animateFloatAsState(
        targetValue = targetRemaining,
        animationSpec = if (reduceMotion) tween(0) else tween(200, easing = LinearEasing),
        label = "timerRemaining"
    )
    val infinite = rememberInfiniteTransition(label = "timerGlow")
    val breath by infinite.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing)),
        label = "timerGlowPulse"
    )
    val status = when (state) {
        NodeRunState.READY -> "准备开始"
        NodeRunState.RUNNING -> "剩余时间"
        NodeRunState.PAUSED -> "已暂停"
        NodeRunState.EXPIRED -> "时间到"
    }
    val statusColor = when (state) {
        NodeRunState.READY -> FlowRunSecondaryText
        NodeRunState.RUNNING -> FlowPrimaryBright
        NodeRunState.PAUSED -> FlowWarm
        NodeRunState.EXPIRED -> FlowRunDanger
    }
    val fontScale = LocalDensity.current.fontScale
    val timeSize = timerNumberSize(countdown.length, fontScale)
    val glow = if (reduceMotion) 1f else breath

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val minSide = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            // ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(FlowPrimary.copy(alpha = 0.24f * glow), Color.Transparent),
                    center = center,
                    radius = minSide * 0.62f
                ),
                radius = minSide * 0.62f,
                center = center
            )

            val outerRadius = minSide / 2f - 4.dp.toPx()
            val mainRadius = minSide / 2f - 26.dp.toPx()
            // overall track + arc (thin outer ring)
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = outerRadius,
                center = center,
                style = Stroke(2.dp.toPx())
            )
            if (animatedOverall > 0.001f) {
                drawArc(
                    color = FlowSuccess.copy(alpha = 0.65f),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedOverall,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            // main remaining ring
            drawCircle(
                color = Color.White.copy(alpha = 0.09f),
                radius = mainRadius,
                center = center,
                style = Stroke(10.dp.toPx())
            )
            if (animatedRemaining > 0.001f) {
                val arcBrush = Brush.linearGradient(
                    colors = listOf(Color(0xFFA8C8FF), Color(0xFF5B8CFF), Color(0xFF2F6BFF)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                )
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedRemaining,
                    useCenter = false,
                    topLeft = Offset(center.x - mainRadius, center.y - mainRadius),
                    size = Size(mainRadius * 2f, mainRadius * 2f),
                    style = Stroke(18.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 0.14f * glow
                )
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedRemaining,
                    useCenter = false,
                    topLeft = Offset(center.x - mainRadius, center.y - mainRadius),
                    size = Size(mainRadius * 2f, mainRadius * 2f),
                    style = Stroke(10.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = status,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = statusColor
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = countdown,
                fontFamily = FontFamily.Monospace,
                fontSize = timeSize.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowRunText,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun timerNumberSize(characters: Int, fontScale: Float): Float {
    val base = when {
        characters <= 5 -> 56f
        characters <= 7 -> 44f
        else -> 36f
    }
    val cap = (1.3f / fontScale.coerceAtLeast(1f)).coerceIn(0.62f, 1f)
    return (base * cap).coerceAtLeast(26f)
}

@Composable
fun FlowCountRing(
    diameter: Dp,
    target: Int,
    state: NodeRunState,
    reduceMotion: Boolean,
    pulseKey: Any?,
    modifier: Modifier = Modifier
) {
    val spin = rememberInfiniteTransition(label = "countSpin")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "countSpinAngle"
    )
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulseKey) {
        if (reduceMotion) return@LaunchedEffect
        pulse.snapTo(0f)
        pulse.animateTo(1f, tween(130, easing = FastOutSlowInEasing))
        pulse.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
    }
    val isRunning = state == NodeRunState.RUNNING
    val angle = if (isRunning && !reduceMotion) spinAngle else 0f
    val numberSize = flowDisplayNumberSizeSp(72f, target.toString().length, LocalDensity.current.fontScale)
    val statusColor = if (isRunning) FlowPrimaryBright else FlowRunSecondaryText
    val arcBrush = Brush.linearGradient(
        colors = listOf(Color(0xFFA8C8FF), Color(0xFF5B8CFF), Color(0xFF2F6BFF)),
        start = Offset(0f, 0f),
        end = Offset(diameter.value, diameter.value)
    )

    Box(
        modifier = modifier
            .size(diameter)
            .scale(1f + pulse.value * 0.01f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 22.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(FlowPrimary.copy(alpha = 0.20f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.62f
                ),
                radius = size.minDimension * 0.62f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.075f),
                radius = radius,
                center = center,
                style = Stroke(9.dp.toPx())
            )
            rotate(angle, center) {
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = 58f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(16.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 0.12f
                )
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = 58f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(9.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            // faint full ring accent
            drawArc(
                brush = arcBrush,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(9.dp.toPx()),
                alpha = 0.07f
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "目标次数",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
                color = FlowRunMutedText
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$target",
                    fontSize = numberSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowRunText,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "次",
                    fontSize = 15.sp,
                    color = FlowRunSecondaryText,
                    modifier = Modifier.padding(bottom = (numberSize * 0.12f).dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isRunning) "进行中" else "准备开始",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

@Composable
fun FlowResultEmblem(
    completedNormally: Boolean,
    animationKey: Any?,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(animationKey) {
        progress.snapTo(0f)
        if (reduceMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val checkDraw = ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(104.dp)
            .scale(0.9f + progress.value * 0.1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            if (completedNormally) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x445B8CFF), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.75f
                    ),
                    radius = size.minDimension * 0.75f,
                    center = center
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF8FB8FF), Color(0xFF4F8CFF), Color(0xFF2F6BFF)),
                        center = Offset(size.width * 0.34f, size.height * 0.28f),
                        radius = size.minDimension * 0.72f
                    ),
                    radius = size.minDimension / 2f,
                    center = center
                )
                if (checkDraw > 0f) {
                    val path = Path().apply {
                        moveTo(size.width * 0.28f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.68f)
                        lineTo(size.width * 0.73f, size.height * 0.34f)
                    }
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val partial = Path()
                    measure.getSegment(0f, measure.length * checkDraw, partial, true)
                    drawPath(
                        partial,
                        Color.White,
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            } else {
                drawCircle(
                    color = Color(0xFFCBD5E6),
                    radius = size.minDimension / 2f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFF7FAFF), Color(0xFFE7ECF5)),
                        center = Offset(size.width * 0.35f, size.height * 0.3f),
                        radius = size.minDimension * 0.7f
                    ),
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    center = center
                )
                drawLine(
                    color = Color(0xFF64748B),
                    start = Offset(size.width * 0.34f, size.height * 0.5f),
                    end = Offset(size.width * 0.66f, size.height * 0.5f),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

data class FlowConfettiPiece(
    val x: Float,
    val y: Float,
    val color: Color,
    val angle: Float,
    val width: Dp,
    val height: Dp,
    val circle: Boolean = false
)

@Composable
fun FlowConfetti(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val pieces = remember {
        listOf(
            FlowConfettiPiece(0.12f, 0.20f, FlowPrimaryBright, -18f, 4.dp, 9.dp),
            FlowConfettiPiece(0.26f, 0.10f, Color(0xFF93B6FF), 32f, 4.dp, 8.dp),
            FlowConfettiPiece(0.40f, 0.05f, FlowSuccess, -42f, 4.dp, 9.dp),
            FlowConfettiPiece(0.62f, 0.06f, FlowWarm, 18f, 4.dp, 8.dp),
            FlowConfettiPiece(0.78f, 0.16f, FlowPrimaryBright, 54f, 4.dp, 9.dp),
            FlowConfettiPiece(0.88f, 0.30f, Color(0xFF93B6FF), -24f, 4.dp, 8.dp),
            FlowConfettiPiece(0.10f, 0.44f, FlowWarm, 64f, 4.dp, 9.dp),
            FlowConfettiPiece(0.16f, 0.66f, FlowPrimaryBright, -36f, 4.dp, 8.dp),
            FlowConfettiPiece(0.86f, 0.56f, FlowSuccess, 18f, 4.dp, 9.dp),
            FlowConfettiPiece(0.76f, 0.74f, Color(0xFF93B6FF), -52f, 4.dp, 8.dp),
            FlowConfettiPiece(0.44f, 0.80f, FlowWarm, 38f, 4.dp, 9.dp),
            FlowConfettiPiece(0.60f, 0.86f, FlowPrimaryBright, 12f, 4.dp, 8.dp),
            FlowConfettiPiece(0.34f, 0.34f, FlowSuccess, 72f, 3.dp, 6.dp),
            FlowConfettiPiece(0.68f, 0.42f, FlowWarm, -68f, 3.dp, 6.dp),
            FlowConfettiPiece(0.24f, 0.52f, Color(0xFF93B6FF), 44f, 3.dp, 6.dp),
            FlowConfettiPiece(0.80f, 0.86f, FlowPrimaryBright, -16f, 3.dp, 6.dp)
        )
    }
    Canvas(modifier) {
        val p = progress.coerceIn(0f, 1f)
        pieces.forEachIndexed { index, piece ->
            val drag = 1f - p
            val cx = size.width * piece.x + sin((index + 1) * 2.3f) * 6.dp.toPx() * drag
            val cy = size.height * piece.y - drag * 18.dp.toPx()
            val alpha = (p * 1.2f).coerceIn(0f, 1f)
            rotate(piece.angle + p * 240f, Offset(cx, cy)) {
                if (piece.circle) {
                    drawCircle(piece.color.copy(alpha = alpha), radius = piece.width.toPx() / 2f, center = Offset(cx, cy))
                } else {
                    drawRoundRect(
                        color = piece.color.copy(alpha = alpha),
                        topLeft = Offset(cx - piece.width.toPx() / 2f, cy - piece.height.toPx() / 2f),
                        size = Size(piece.width.toPx(), piece.height.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx())
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Launch countdown
// ---------------------------------------------------------------------------

enum class FlowLaunchStage(val fraction: Float) {
    Idle(0f),
    Ready(0.10f),
    Three(0.40f),
    Two(0.70f),
    One(1f)
}

@Composable
fun FlowLaunchOverlay(
    planName: String,
    stage: FlowLaunchStage,
    reduceMotion: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sweep by animateFloatAsState(
        targetValue = stage.fraction,
        animationSpec = tween(if (reduceMotion) 0 else 300, easing = LinearEasing),
        label = "launchSweep"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlowRunBackground)
            .semantics { contentDescription = "启动倒计时" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(FlowPrimary.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.48f),
                    radius = size.width * 0.8f
                ),
                radius = size.width * 0.8f,
                center = Offset(size.width * 0.5f, size.height * 0.48f)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "即将开始",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.8.sp,
                color = FlowRunMutedText
            )
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 3.5.dp.toPx()
                    val radius = size.minDimension / 2f - stroke
                    drawCircle(FlowRunTrack, radius = radius, style = Stroke(stroke))
                    if (sweep > 0.001f) {
                        drawArc(
                            color = FlowPrimaryBright,
                            startAngle = -90f,
                            sweepAngle = 360f * sweep,
                            useCenter = false,
                            topLeft = Offset(stroke, stroke),
                            size = Size(size.width - stroke * 2f, size.height - stroke * 2f),
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Crossfade(
                    targetState = stage,
                    animationSpec = tween(if (reduceMotion) 0 else 280),
                    label = "launchPhase"
                ) { current ->
                    Text(
                        text = when (current) {
                            FlowLaunchStage.Ready -> "准备好了吗？"
                            FlowLaunchStage.Three -> "3"
                            FlowLaunchStage.Two -> "2"
                            FlowLaunchStage.One -> "1"
                            FlowLaunchStage.Idle -> ""
                        },
                        fontSize = when (current) {
                            FlowLaunchStage.Three,
                            FlowLaunchStage.Two,
                            FlowLaunchStage.One -> 72.sp
                            else -> 21.sp
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = FlowRunText,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = planName,
                fontSize = 13.sp,
                color = FlowRunSecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.72f),
                textAlign = TextAlign.Center
            )
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 38.dp)
                .testTag("launch-cancel")
        ) {
            Text("取消", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = FlowRunMutedText)
        }
    }
}
