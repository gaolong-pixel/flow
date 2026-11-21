package com.flow.app.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.run.NodeRunState
import com.flow.app.domain.run.NodeStatus
import com.flow.app.domain.run.PlanRunSnapshot
import com.flow.app.domain.run.formatCountdown
import com.flow.app.domain.run.formatDurationLabel
import com.flow.app.domain.run.flowDisplayNumberSizeSp
import com.flow.app.domain.run.flowOverallPercent
import com.flow.app.domain.run.flowOverallProgress
import com.flow.app.domain.run.flowTimerRemainingFraction

// ---------------------------------------------------------------------------
// Header + completed drawer
// ---------------------------------------------------------------------------

@Composable
fun RunRollerHeader(
    snapshot: PlanRunSnapshot,
    reduceMotion: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val node = snapshot.node
    val overall = flowOverallProgress(snapshot.completedNodes, snapshot.totalNodes)
    val percent = flowOverallPercent(snapshot.completedNodes, snapshot.totalNodes)

    Column(modifier.fillMaxWidth().padding(horizontal = FlowSpacing.page)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape)
                    .clickable(onClick = onExit)
                    .testTag("run-exit")
                    .semantics {
                        contentDescription = "退出"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                FlowBackChevron(glyphSize = 17.dp, color = FlowRunSecondaryText)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = snapshot.plan.name,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowRunText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "当前 · ${node?.name ?: "运行结束"}",
                    fontSize = 10.5.sp,
                    color = FlowRunSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${snapshot.nodeIndex + 1} / ${snapshot.totalNodes}",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = FlowRunSecondaryText,
                    letterSpacing = 0.2.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "流程进度",
                fontSize = 9.5.sp,
                color = FlowRunMutedText,
                letterSpacing = 0.4.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${snapshot.completedNodes} / ${snapshot.totalNodes} · $percent%",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = FlowRunSecondaryText
            )
        }
        Spacer(Modifier.height(7.dp))
        FlowRunProgressBar(progress = overall)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun RunCompletedDrawer(
    snapshot: PlanRunSnapshot,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completed = snapshot.completedNodeIndices
    val shape = RoundedCornerShape(13.dp)

    Column(modifier.fillMaxWidth().padding(horizontal = FlowSpacing.page)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.035f))
                .border(1.dp, Color.White.copy(alpha = 0.065f), shape)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("run-completed-toggle")
                .semantics {
                    contentDescription = if (expanded) "收起已完成节点" else "展开已完成节点"
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (completed.isEmpty()) FlowRunMutedText else FlowSuccess,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "已完成 ${completed.size}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (completed.isEmpty()) FlowRunSecondaryText else FlowRunText
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = 10.sp,
                color = FlowRunMutedText
            )
            Spacer(Modifier.width(4.dp))
            FlowChevron(
                glyphSize = 11.dp,
                color = FlowRunMutedText,
                modifier = Modifier.rotate(if (expanded) 90f else 0f)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(160)),
            exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(FlowRunSurface.copy(alpha = 0.62f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(13.dp))
                    .heightIn(max = 148.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (completed.isEmpty()) {
                    Text(
                        text = "还没有已完成节点。完成当前节点后会收进这里。",
                        fontSize = 10.5.sp,
                        color = FlowRunMutedText,
                        lineHeight = 15.sp
                    )
                }
                completed.forEach { index ->
                    val step = snapshot.plan.steps.getOrNull(index) ?: return@forEach
                    CompletedNodeRow(index = index, step = step)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CompletedNodeRow(
    index: Int,
    step: RoutineStep
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("run-archived-$index"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(FlowSuccess.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(9.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.1f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.82f)
                    lineTo(size.width * 0.92f, size.height * 0.2f)
                }
                drawPath(
                    path,
                    FlowSuccess,
                    style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.name,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = FlowRunText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = compactMeta(step, null),
                fontSize = 9.5.sp,
                color = FlowRunMutedText,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "已完成",
            fontSize = 9.5.sp,
            color = FlowSuccess
        )
    }
}

// ---------------------------------------------------------------------------
// Roller
// ---------------------------------------------------------------------------

@Composable
fun FlowNodeRoller(
    snapshot: PlanRunSnapshot,
    listState: LazyListState,
    reduceMotion: Boolean,
    shakeDp: Float,
    expiryPulse: Float,
    onSelectNode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val indices = snapshot.visibleNodeIndices
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = FlowSpacing.page,
                end = FlowSpacing.page,
                top = 8.dp,
                bottom = 132.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = indices, key = { it }) { index ->
                val step = snapshot.plan.steps.getOrNull(index) ?: return@items
                val isCurrent = index == snapshot.nodeIndex
                val status = snapshot.nodeStatuses.getOrNull(index) ?: NodeStatus.PENDING
                RollerNodeItem(
                    snapshot = snapshot,
                    step = step,
                    index = index,
                    isCurrent = isCurrent,
                    status = status,
                    listState = listState,
                    reduceMotion = reduceMotion,
                    shakeDp = if (isCurrent) shakeDp else 0f,
                    expiryPulse = if (isCurrent) expiryPulse else 0f,
                    onSelect = { onSelectNode(index) }
                )
            }
        }

        // A soft spatial fade keeps the roller from looking like a plain list.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(22.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(FlowRunBackground.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, FlowRunBackground.copy(alpha = 0.78f))
                    )
                )
        )
    }
}

@Composable
private fun RollerNodeItem(
    snapshot: PlanRunSnapshot,
    step: RoutineStep,
    index: Int,
    isCurrent: Boolean,
    status: NodeStatus,
    listState: LazyListState,
    reduceMotion: Boolean,
    shakeDp: Float,
    expiryPulse: Float,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val depthModifier = Modifier.graphicsLayer {
        if (reduceMotion) {
            alpha = 1f
            return@graphicsLayer
        }
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == index }
        val viewportStart = layout.viewportStartOffset.toFloat()
        val viewportEnd = layout.viewportEndOffset.toFloat()
        val viewport = (viewportEnd - viewportStart).coerceAtLeast(1f)
        val viewportCenter = viewportStart + viewport / 2f
        val itemCenter = item?.let { it.offset + it.size / 2f } ?: viewportCenter
        val distance = ((itemCenter - viewportCenter) / viewport).coerceIn(-1.15f, 1.15f)
        val magnitude = kotlin.math.abs(distance)
        rotationX = -distance * 17f
        val baseScale = (1f - magnitude * 0.095f).coerceAtLeast(0.80f)
        val boost = if (isCurrent) 1.022f else 1f
        scaleX = baseScale * boost
        scaleY = baseScale * boost
        val currentBoost = if (isCurrent) 0.14f else 0f
        alpha = ((1f - magnitude * 0.58f + currentBoost).coerceIn(0.26f, 1f)) *
            (if (isCurrent) 1f else 0.92f)
        cameraDistance = 22f * density.density
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }

    val shakeModifier = if (isCurrent && shakeDp != 0f) {
        Modifier.offset(x = shakeDp.dp)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isCurrent) 1f else 0f)
            .then(depthModifier)
            .then(shakeModifier)
            .testTag(if (isCurrent) "run-current-node" else "run-node-$index")
    ) {
        if (isCurrent) {
            CurrentNodeCard(
                snapshot = snapshot,
                step = step,
                index = index,
                reduceMotion = reduceMotion,
                expiryPulse = expiryPulse
            )
        } else {
            CompactNodeCard(
                snapshot = snapshot,
                step = step,
                index = index,
                status = status,
                onSelect = onSelect
            )
        }
    }
}

@Composable
private fun CurrentNodeCard(
    snapshot: PlanRunSnapshot,
    step: RoutineStep,
    index: Int,
    reduceMotion: Boolean,
    expiryPulse: Float
) {
    val isTimer = step.type == StepType.TIMER
    val typeColor = if (isTimer) FlowRunTimerType else FlowRunCountType
    val shape = RoundedCornerShape(26.dp)
    val borderColor = when {
        expiryPulse > 0.05f -> FlowRunDanger.copy(alpha = 0.35f + expiryPulse * 0.55f)
        else -> typeColor.copy(alpha = 0.34f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 26.dp,
                shape = shape,
                ambientColor = typeColor.copy(alpha = 0.22f),
                spotColor = typeColor.copy(alpha = 0.26f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        FlowRunSurfaceRaised.copy(alpha = 0.98f),
                        FlowRunSurface.copy(alpha = 0.94f)
                    )
                )
            )
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 17.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeCapsule(type = step.type)
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(typeColor.copy(alpha = 0.10f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Box(Modifier.size(4.dp).background(typeColor, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "当前执行",
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = typeColor,
                    letterSpacing = 0.2.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = FlowRunMutedText
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = step.name,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = FlowRunText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = currentMeta(snapshot, step, index),
            fontSize = 10.5.sp,
            color = FlowRunSecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (isTimer) {
                RollerTimerDial(
                    diameter = 184.dp,
                    overallFraction = flowOverallProgress(
                        snapshot.completedNodes,
                        snapshot.totalNodes
                    ),
                    remainingFraction = flowTimerRemainingFraction(
                        snapshot.totalTimerMs,
                        snapshot.remainingMs
                    ),
                    countdown = formatCountdown(snapshot.remainingMs),
                    state = snapshot.nodeState,
                    reduceMotion = reduceMotion
                )
            } else {
                RollerCountDial(
                    diameter = 184.dp,
                    target = step.target ?: 0,
                    state = snapshot.nodeState,
                    overallFraction = flowOverallProgress(
                        snapshot.completedNodes,
                        snapshot.totalNodes
                    ),
                    reduceMotion = reduceMotion
                )
            }
        }
    }
}

@Composable
private fun CompactNodeCard(
    snapshot: PlanRunSnapshot,
    step: RoutineStep,
    index: Int,
    status: NodeStatus,
    onSelect: () -> Unit
) {
    val isTimer = step.type == StepType.TIMER
    val typeColor = if (isTimer) FlowRunTimerType else FlowRunCountType
    val deferred = status == NodeStatus.DEFERRED || status == NodeStatus.SKIPPED
    val selectable = status == NodeStatus.PENDING ||
        status == NodeStatus.DEFERRED ||
        status == NodeStatus.SKIPPED
    val shape = RoundedCornerShape(18.dp)
    val interaction = if (selectable) {
        Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .semantics {
                contentDescription = "切换到节点 ${step.name}，${compactStatusLabel(status)}"
                role = Role.Button
            }
    } else {
        Modifier.semantics {
            contentDescription = "节点 ${step.name}，${compactStatusLabel(status)}"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (deferred) FlowWarm.copy(alpha = 0.055f)
                else Color.White.copy(alpha = 0.035f)
            )
            .border(
                1.dp,
                if (deferred) FlowWarm.copy(alpha = 0.24f)
                else Color.White.copy(alpha = 0.065f),
                shape
            )
            .then(interaction)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(typeColor.copy(alpha = if (deferred) 0.06f else 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            FlowTypeGlyph(
                type = step.type,
                size = 13.dp,
                onDark = true
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (deferred) FlowRunSecondaryText else FlowRunText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = compactMeta(
                    step = step,
                    remainingMs = if (deferred) snapshot.remainingFor(index) else null
                ),
                fontSize = 9.5.sp,
                color = FlowRunMutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (isTimer) "TIMER" else "COUNT",
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                color = typeColor.copy(alpha = if (deferred) 0.55f else 0.90f),
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = compactStatusLabel(status),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (deferred) FlowWarm else FlowRunSecondaryText
            )
        }
    }
}

private fun compactStatusLabel(status: NodeStatus): String = when (status) {
    NodeStatus.DEFERRED -> "暂缓"
    NodeStatus.PENDING -> "待执行"
    NodeStatus.COMPLETED -> "已完成"
    NodeStatus.SKIPPED -> "暂缓"
    NodeStatus.CURRENT -> "当前"
}

private fun currentMeta(snapshot: PlanRunSnapshot, step: RoutineStep, index: Int): String {
    val localState = snapshot.nodeState
    return when (step.type) {
        StepType.COUNT -> "目标 ${step.target ?: 0} 次"
        StepType.TIMER -> {
            val remaining = snapshot.remainingFor(index)
            val status = when (localState) {
                NodeRunState.READY -> "待开始"
                NodeRunState.RUNNING -> "进行中"
                NodeRunState.PAUSED -> "已暂停"
                NodeRunState.EXPIRED -> "已到期"
            }
            "时长 ${formatDurationLabel(step.durationMs)} · $status · 剩余 ${
                formatCountdown(remaining)
            }"
        }
    }
}

private fun compactMeta(step: RoutineStep, remainingMs: Long?): String =
    when (step.type) {
        StepType.COUNT -> "目标 ${step.target ?: 0} 次"
        StepType.TIMER -> if (remainingMs != null) {
            "时长 ${formatDurationLabel(step.durationMs)} · 剩余 ${
                formatCountdown(remainingMs)
            }"
        } else {
            "时长 ${formatDurationLabel(step.durationMs)}"
        }
    }

@Composable
private fun TypeCapsule(type: StepType) {
    val color = if (type == StepType.TIMER) FlowRunTimerType else FlowRunCountType
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowTypeGlyph(type = type, size = 10.dp, onDark = true)
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (type == StepType.TIMER) "TIMER" else "COUNT",
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            letterSpacing = 0.6.sp
        )
    }
}

// ---------------------------------------------------------------------------
// COUNT
// ---------------------------------------------------------------------------

@Composable
private fun RollerCountDial(
    diameter: Dp,
    target: Int,
    state: NodeRunState,
    overallFraction: Float,
    reduceMotion: Boolean
) {
    val infinite = rememberInfiniteTransition(label = "countBreath")
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "countBreathValue"
    )
    val running = state == NodeRunState.RUNNING
    val breathing = if (running && !reduceMotion) 0.985f + breath * 0.07f else 1f
    val glow = when {
        running -> 0.16f + breath * 0.22f
        else -> 0.08f
    }
    val ringAlpha = if (running && !reduceMotion) 0.22f + breath * 0.26f else 0.15f
    val haloAlpha = if (running && !reduceMotion) 0.10f + breath * 0.12f else 0.04f
    val status = if (running) "进行中" else "准备开始"
    val statusColor = if (running) FlowRunCountType else FlowRunSecondaryText
    val fontScale = LocalDensity.current.fontScale
    val numberSize = flowDisplayNumberSizeSp(78f, target.toString().length, fontScale)

    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = breathing
                scaleY = breathing
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minSide = size.minDimension
            val outerRadius = minSide / 2f - 3.dp.toPx()
            val radius = minSide / 2f - 22.dp.toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(FlowPrimary.copy(alpha = glow), Color.Transparent),
                    center = center,
                    radius = minSide * 0.60f
                ),
                radius = minSide * 0.60f,
                center = center
            )

            // Thin outer ring is the real whole-flow completion.
            drawCircle(
                color = Color.White.copy(alpha = 0.055f),
                radius = outerRadius,
                center = center,
                style = Stroke(2.dp.toPx())
            )
            val overall = overallFraction.coerceIn(0f, 1f)
            if (overall > 0.001f) {
                drawArc(
                    color = FlowSuccess.copy(alpha = 0.62f),
                    startAngle = -90f,
                    sweepAngle = 360f * overall,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // A visible halo expands/contracts around the ring so the
            // breathing is obvious even at a glance.
            drawCircle(
                color = FlowPrimaryBright.copy(alpha = haloAlpha),
                radius = radius * (1.08f + breath * 0.07f),
                center = center,
                style = Stroke(1.5.dp.toPx())
            )

            // The inner ring is deliberately a full breathing ring, not a
            // progress arc: COUNT is target-only and has no per-repetition
            // counter in this product.
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = radius,
                center = center,
                style = Stroke(9.dp.toPx())
            )
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFB7D1FF).copy(alpha = ringAlpha),
                        Color(0xFF5B8CFF).copy(alpha = ringAlpha),
                        Color(0xFF2F6BFF).copy(alpha = ringAlpha)
                    ),
                    start = Offset(center.x - radius, center.y - radius),
                    end = Offset(center.x + radius, center.y + radius)
                ),
                radius = radius,
                center = center,
                style = Stroke(9.dp.toPx())
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "目标次数",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp,
                color = FlowRunMutedText
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$target",
                    fontSize = numberSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowRunText,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "次",
                    fontSize = 15.sp,
                    color = FlowRunSecondaryText,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = status,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = statusColor
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = if (running) "不着急，完成后再继续" else "点击下方开始",
                fontSize = 9.sp,
                color = FlowRunMutedText,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
// TIMER
// ---------------------------------------------------------------------------

@Composable
private fun RollerTimerDial(
    diameter: Dp,
    overallFraction: Float,
    remainingFraction: Float,
    countdown: String,
    state: NodeRunState,
    reduceMotion: Boolean
) {
    val animatedOverall by animateFloatAsState(
        targetValue = overallFraction.coerceIn(0f, 1f),
        animationSpec = if (reduceMotion) tween(0) else tween(420, easing = FastOutSlowInEasing),
        label = "rollerOverall"
    )
    val targetRemaining = if (state == NodeRunState.READY) 1f else {
        remainingFraction.coerceIn(0f, 1f)
    }
    val animatedRemaining by animateFloatAsState(
        targetValue = targetRemaining,
        animationSpec = if (reduceMotion) tween(0) else tween(220, easing = LinearEasing),
        label = "rollerRemaining"
    )
    val infinite = rememberInfiniteTransition(label = "timerBreath")
    val breath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "timerBreathValue"
    )

    val running = state == NodeRunState.RUNNING
    val glow = if (running && !reduceMotion) 0.17f + breath * 0.08f else 0.11f
    val status = when (state) {
        NodeRunState.READY -> "准备开始"
        NodeRunState.RUNNING -> "剩余时间"
        NodeRunState.PAUSED -> "已暂停"
        NodeRunState.EXPIRED -> "时间到"
    }
    val statusColor = when (state) {
        NodeRunState.READY -> FlowRunSecondaryText
        NodeRunState.RUNNING -> FlowRunCountType
        NodeRunState.PAUSED -> FlowRunTimerType
        NodeRunState.EXPIRED -> FlowRunDanger
    }
    val ringColors = when (state) {
        NodeRunState.EXPIRED -> listOf(FlowRunDanger.copy(alpha = 0.9f), FlowRunDanger)
        else -> listOf(Color(0xFFA8C8FF), Color(0xFF5B8CFF), Color(0xFF2F6BFF))
    }
    val fontScale = LocalDensity.current.fontScale
    val timeSize = flowDisplayNumberSizeSp(52f, countdown.length, fontScale)

    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minSide = size.minDimension
            val outerRadius = minSide / 2f - 3.dp.toPx()
            val mainRadius = minSide / 2f - 22.dp.toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(FlowPrimary.copy(alpha = glow), Color.Transparent),
                    center = center,
                    radius = minSide * 0.62f
                ),
                radius = minSide * 0.62f,
                center = center
            )

            // Outer ring: whole-flow completion, from the real completed count.
            drawCircle(
                color = Color.White.copy(alpha = 0.055f),
                radius = outerRadius,
                center = center,
                style = Stroke(2.dp.toPx())
            )
            if (animatedOverall > 0.001f) {
                drawArc(
                    color = FlowSuccess.copy(alpha = 0.62f),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedOverall,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Inner ring: this TIMER's real remaining time.
            drawCircle(
                color = Color.White.copy(alpha = 0.075f),
                radius = mainRadius,
                center = center,
                style = Stroke(10.dp.toPx())
            )
            if (animatedRemaining > 0.001f) {
                val brush = Brush.linearGradient(
                    colors = ringColors,
                    start = Offset(center.x - mainRadius, center.y - mainRadius),
                    end = Offset(center.x + mainRadius, center.y + mainRadius)
                )
                drawArc(
                    brush = brush,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedRemaining,
                    useCenter = false,
                    topLeft = Offset(center.x - mainRadius, center.y - mainRadius),
                    size = Size(mainRadius * 2f, mainRadius * 2f),
                    style = Stroke(18.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 0.12f
                )
                drawArc(
                    brush = brush,
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = countdown,
                fontFamily = FontFamily.Monospace,
                fontSize = timeSize.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state == NodeRunState.EXPIRED) FlowRunDanger else FlowRunText,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("run-countdown")
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom dock
// ---------------------------------------------------------------------------

@Composable
fun RunBottomDock(
    snapshot: PlanRunSnapshot,
    reduceMotion: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onToggleLoop: (Boolean) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val node = snapshot.node ?: return
    val isTimer = node.type == StepType.TIMER
    val state = snapshot.nodeState
    val secondaryComplete = isTimer &&
        (state == NodeRunState.RUNNING || state == NodeRunState.PAUSED)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        FlowRunBackground.copy(alpha = 0.0f),
                        FlowRunBackground.copy(alpha = 0.92f),
                        FlowRunBackground
                    )
                )
            )
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlowSpacing.page),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "单节点循环",
                    fontSize = 9.5.sp,
                    color = FlowRunSecondaryText
                )
                Spacer(Modifier.width(6.dp))
                FlowLoopToggle(
                    checked = snapshot.loopCurrentNode,
                    onCheckedChange = onToggleLoop
                )
            }
            Spacer(Modifier.weight(1f))
            DockTextAction(
                label = "结束",
                accessibilityLabel = "结束流程",
                testTag = "run-end",
                onClick = onExit
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlowSpacing.page),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                state == NodeRunState.READY -> {
                    RunDockAction(
                        label = if (isTimer) "开始计时" else "开始",
                        icon = FlowActionIcon.Play,
                        primary = true,
                        testTag = "run-primary",
                        onClick = onStart,
                        modifier = Modifier.weight(1f)
                    )
                }
                !isTimer && state == NodeRunState.RUNNING -> {
                    RunDockAction(
                        label = "完成当前节点",
                        icon = FlowActionIcon.Check,
                        primary = true,
                        testTag = "run-primary",
                        onClick = onComplete,
                        modifier = Modifier.weight(1f)
                    )
                }
                isTimer && state == NodeRunState.RUNNING -> {
                    RunDockAction(
                        label = "暂停",
                        icon = FlowActionIcon.Pause,
                        primary = true,
                        testTag = "run-primary",
                        onClick = onPause,
                        modifier = Modifier.weight(1f)
                    )
                    RunDockAction(
                        label = "完成当前节点",
                        icon = FlowActionIcon.Check,
                        primary = false,
                        testTag = "run-secondary",
                        onClick = onComplete,
                        modifier = Modifier.weight(1f)
                    )
                }
                isTimer && state == NodeRunState.PAUSED -> {
                    RunDockAction(
                        label = "继续计时",
                        icon = FlowActionIcon.Play,
                        primary = true,
                        testTag = "run-primary",
                        onClick = onResume,
                        modifier = Modifier.weight(1f)
                    )
                    RunDockAction(
                        label = "完成当前节点",
                        icon = FlowActionIcon.Check,
                        primary = false,
                        testTag = "run-secondary",
                        onClick = onComplete,
                        modifier = Modifier.weight(1f)
                    )
                }
                isTimer && state == NodeRunState.EXPIRED -> {
                    RunDockAction(
                        label = "完成当前节点",
                        icon = FlowActionIcon.Check,
                        primary = true,
                        testTag = "run-primary",
                        onClick = onComplete,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FlowSpacing.page)
                .padding(top = 7.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (secondaryComplete) {
                    "完成会记录本节点；切换节点不会丢失进度"
                } else {
                    "点击上方节点可切换执行目标"
                },
                fontSize = 9.5.sp,
                color = FlowRunMutedText,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FlowLoopToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 2.dp,
        animationSpec = tween(150),
        label = "loopThumb"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) FlowPrimary else Color.White.copy(alpha = 0.15f),
        animationSpec = tween(150),
        label = "loopTrack"
    )
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 20.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.dp, Color.White.copy(alpha = if (checked) 0f else 0.08f), CircleShape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .testTag("run-loop-toggle")
            .semantics { contentDescription = "单节点循环" }
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .offset(x = thumbOffset)
                .size(16.dp)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun DockTextAction(
    label: String,
    accessibilityLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag(testTag)
            .semantics {
                contentDescription = accessibilityLabel
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = FlowRunSecondaryText
        )
    }
}

@Composable
private fun RunDockAction(
    label: String,
    icon: FlowActionIcon,
    primary: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(
                if (primary) {
                    Brush.horizontalGradient(listOf(Color(0xFF4F8CFF), Color(0xFF2F6BFF)))
                } else {
                    Brush.horizontalGradient(
                        listOf(Color.White.copy(alpha = 0.075f), Color.White.copy(alpha = 0.05f))
                    )
                }
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else Color.White.copy(alpha = 0.11f),
                shape
            )
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(16.dp)) {
            val c = if (primary) Color.White else Color.White.copy(alpha = 0.84f)
            val stroke = 1.8.dp.toPx()
            val w = size.width
            val h = size.height
            when (icon) {
                FlowActionIcon.Play -> {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.28f, h * 0.16f)
                        lineTo(w * 0.84f, h * 0.5f)
                        lineTo(w * 0.28f, h * 0.84f)
                        close()
                    }
                    drawPath(path, c)
                }
                FlowActionIcon.Pause -> {
                    drawLine(c, Offset(w * 0.34f, h * 0.2f), Offset(w * 0.34f, h * 0.8f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.66f, h * 0.2f), Offset(w * 0.66f, h * 0.8f), stroke, StrokeCap.Round)
                }
                FlowActionIcon.Check -> {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.16f, h * 0.54f)
                        lineTo(w * 0.42f, h * 0.78f)
                        lineTo(w * 0.85f, h * 0.24f)
                    }
                    drawPath(path, c, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                FlowActionIcon.Close -> {
                    drawLine(c, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), stroke, StrokeCap.Round)
                }
                FlowActionIcon.Loop -> Unit
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            color = if (primary) Color.White else Color.White.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// Completion + expiry overlays
// ---------------------------------------------------------------------------

@Composable
fun CompletionFlash(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0.001f) return
    Canvas(modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val eased = 1f - (1f - progress) * (1f - progress)
        val radius = size.minDimension * (0.10f + eased * 0.46f)
        val alpha = (1f - progress).coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(FlowPrimaryBright.copy(alpha = 0.16f * alpha), Color.Transparent),
                center = center,
                radius = radius * 1.25f
            ),
            radius = radius * 1.25f,
            center = center
        )
        drawCircle(
            color = FlowSuccess.copy(alpha = 0.55f * alpha),
            radius = radius,
            center = center,
            style = Stroke(2.dp.toPx())
        )
    }
}
