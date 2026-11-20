package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.run.flowPlanDurationLabel
import com.flow.app.domain.run.formatDurationLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun RunSetupScreen(
    plan: Routine,
    message: String?,
    onBack: () -> Unit,
    onStart: () -> Boolean,
    onLaunchingChanged: (Boolean) -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var launching by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(FlowLaunchStage.Idle) }
    var startRequested by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberFlowHaptics()
    val reduceMotion = rememberReduceMotion()
    val lifecycleOwner = LocalLifecycleOwner.current

    fun clearLaunchState() {
        job?.cancel()
        job = null
        launching = false
        stage = FlowLaunchStage.Idle
        onLaunchingChanged(false)
    }

    fun finishLaunch() {
        if (startRequested) return
        startRequested = true
        job?.cancel()
        job = null
        launching = false
        stage = FlowLaunchStage.Idle
        val started = onStart()
        onLaunchingChanged(false)
        if (!started) startRequested = false
    }

    fun cancelLaunch() {
        if (!launching) return
        clearLaunchState()
    }

    fun beginLaunch() {
        if (launching || startRequested) return
        if (reduceMotion) {
            finishLaunch()
            return
        }
        launching = true
        stage = FlowLaunchStage.Ready
        onLaunchingChanged(true)
        haptics.tick()
        job = scope.launch {
            delay(280L)
            stage = FlowLaunchStage.Three
            haptics.tick()
            delay(330L)
            stage = FlowLaunchStage.Two
            haptics.tick()
            delay(330L)
            stage = FlowLaunchStage.One
            haptics.tick()
            delay(330L)
            stage = FlowLaunchStage.Go
            haptics.commit()
            delay(170L)
            if (isActive) finishLaunch()
        }
    }

    BackHandler {
        if (launching) cancelLaunch() else onBack()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) cancelLaunch()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            job?.cancel()
            if (launching) onLaunchingChanged(false)
        }
    }

    LaunchedEffect(message) {
        if (message != null) startRequested = false
    }

    Box(Modifier.fillMaxSize()) {
        FlowLightArt(FlowArtMode.Prepare)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            SetupTopBar(
                planName = plan.name,
                onBack = onBack,
                menuOpen = menuOpen,
                onMenuOpen = { menuOpen = true },
                onMenuDismiss = { menuOpen = false },
                onEdit = {
                    menuOpen = false
                    onEdit()
                },
                onDelete = {
                    menuOpen = false
                    deleteOpen = true
                }
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FlowSpacing.page)
            ) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${plan.steps.size}",
                        fontSize = 34.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1.0).sp,
                        color = FlowText
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "个节点",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = FlowSecondaryText,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowClockIcon(glyphSize = 14.dp, color = FlowSecondaryText)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = flowPlanDurationLabel(plan.steps) ?: "按顺序执行",
                        fontSize = 12.5.sp,
                        color = FlowSecondaryText
                    )
                }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth()) {
                    plan.steps.forEachIndexed { index, step ->
                        SetupTimelineRow(
                            step = step,
                            index = index,
                            isFirst = index == 0,
                            isLast = index == plan.steps.lastIndex
                        )
                    }
                }
                Spacer(Modifier.height(110.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, FlowBackground.copy(alpha = 0.94f), FlowBackground)
                        )
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FlowSpacing.page)
                        .padding(top = 24.dp, bottom = 30.dp)
                ) {
                    if (message != null) {
                        Text(
                            text = message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(FlowDangerSoft, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            fontSize = 12.sp,
                            color = FlowDanger
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    FlowGradientButton(
                        text = "开始执行",
                        onClick = { beginLaunch() },
                        enabled = plan.steps.isNotEmpty()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${plan.steps.size} 个节点 · 按顺序执行",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 10.5.sp,
                        color = FlowSecondaryText
                    )
                }
            }
        }
        if (stage != FlowLaunchStage.Idle) {
            FlowLaunchOverlay(
                planName = plan.name,
                stage = stage,
                reduceMotion = reduceMotion,
                onSkip = { if (launching) finishLaunch() }
            )
        }
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = FlowSurface,
            tonalElevation = 0.dp,
            titleContentColor = FlowText,
            textContentColor = FlowSecondaryText,
            title = { Text("删除这个流程？") },
            text = { Text("删除后，这个流程及其节点将无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteOpen = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = FlowDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text("取消", color = FlowSecondaryText)
                }
            }
        )
    }
}

@Composable
private fun SetupTopBar(
    planName: String,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .semantics { contentDescription = "返回" }
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            FlowBackChevron(glyphSize = 18.dp, color = FlowText)
        }
        Text(
            text = planName,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 52.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = FlowText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "更多" }
                    .clickable(onClick = onMenuOpen),
                contentAlignment = Alignment.Center
            ) {
                Text("⋯", fontSize = 20.sp, color = FlowSecondaryText)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = onMenuDismiss
            ) {
                DropdownMenuItem(
                    text = { Text("编辑流程") },
                    onClick = onEdit
                )
                DropdownMenuItem(
                    text = { Text("删除流程", color = FlowDanger) },
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun SetupTimelineRow(
    step: RoutineStep,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(27.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2f
                    val lineColor = FlowPrimary.copy(alpha = 0.24f)
                    if (!isFirst) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, 20.dp.toPx()), 1.5.dp.toPx())
                    }
                    if (!isLast) {
                        drawLine(lineColor, Offset(x, 28.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx())
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .size(27.dp)
                    .background(FlowPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(
                text = step.name,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowTypeGlyph(type = step.type, size = 12.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (step.type == StepType.COUNT) {
                        "${step.target ?: 0} 次"
                    } else {
                        formatDurationLabel(step.durationMs)
                    },
                    fontSize = 12.5.sp,
                    color = FlowSecondaryText
                )
            }
        }
    }
}
