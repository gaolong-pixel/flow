package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flow.app.domain.model.StepType
import com.flow.app.domain.run.NodeRunState
import com.flow.app.domain.run.PlanRunSnapshot
import com.flow.app.domain.run.RunCoordinator
import com.flow.app.domain.run.formatCountdown
import com.flow.app.domain.run.flowOverallPercent
import com.flow.app.domain.run.flowOverallProgress
import com.flow.app.domain.run.flowTimerRemainingFraction
import kotlinx.coroutines.delay

@Composable
fun RunScreen(
    coordinator: RunCoordinator,
    snapshot: PlanRunSnapshot,
    onConfirmExit: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var showExitConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    coordinator.setUiVisible(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    coordinator.setUiVisible(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            coordinator.setUiVisible(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            coordinator.setUiVisible(false)
        }
    }

    LaunchedEffect(
        snapshot.runId,
        snapshot.nodeIndex,
        snapshot.nodeState,
        snapshot.timerRunId,
        resumed
    ) {
        if (!resumed) return@LaunchedEffect
        if (snapshot.node?.type != StepType.TIMER) return@LaunchedEffect
        if (snapshot.nodeState != NodeRunState.RUNNING) return@LaunchedEffect
        while (true) {
            delay(200L)
            coordinator.refresh()
        }
    }

    BackHandler { showExitConfirmation = true }

    val node = snapshot.node
    val isTimer = node?.type == StepType.TIMER
    val reduceMotion = rememberReduceMotion()
    val haptics = rememberFlowHaptics()
    val overall = flowOverallProgress(snapshot.completedNodes, snapshot.totalNodes)
    val overallPercent = flowOverallPercent(snapshot.completedNodes, snapshot.totalNodes)

    Box(Modifier.fillMaxSize()) {
        FlowDarkArt()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            RunV2TopBar(
                nodeNumber = snapshot.nodeIndex + 1,
                totalNodes = snapshot.totalNodes,
                nodeName = node?.name ?: "运行结束",
                onExit = { showExitConfirmation = true }
            )
            RunV2Progress(
                progress = overall,
                percent = overallPercent
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = FlowSpacing.page),
                contentAlignment = Alignment.Center
            ) {
                val diameter = minOf(maxWidth - 8.dp, maxHeight - 6.dp)
                    .coerceIn(188.dp, 340.dp)
                if (node != null && isTimer) {
                    FlowTimerRing(
                        diameter = diameter,
                        overallFraction = overall,
                        remainingFraction = flowTimerRemainingFraction(
                            snapshot.totalTimerMs,
                            snapshot.remainingMs
                        ),
                        countdown = formatCountdown(snapshot.remainingMs),
                        state = snapshot.nodeState,
                        reduceMotion = reduceMotion
                    )
                } else if (node != null) {
                    FlowCountRing(
                        diameter = diameter,
                        target = node.target ?: 0,
                        state = snapshot.nodeState,
                        reduceMotion = reduceMotion,
                        pulseKey = snapshot.nodeState
                    )
                }
            }
            RunV2LoopRow(
                checked = snapshot.loopCurrentNode,
                onCheckedChange = { coordinator.setLoop(it) }
            )
            RunV2Actions(
                snapshot = snapshot,
                isTimer = isTimer,
                onStart = {
                    haptics.tick()
                    coordinator.startNode()
                },
                onPause = {
                    haptics.tick()
                    coordinator.pause()
                },
                onResume = {
                    haptics.tick()
                    coordinator.resume()
                },
                onComplete = {
                    haptics.commit()
                    coordinator.completeNode()
                },
                onExit = { showExitConfirmation = true }
            )
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            containerColor = FlowRunSurface,
            tonalElevation = 0.dp,
            titleContentColor = FlowRunText,
            textContentColor = FlowRunSecondaryText,
            title = { Text("退出本次运行？", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前进度：已完成 ${snapshot.completedNodes} / ${snapshot.totalNodes} 个节点。")
                    Text("退出后当前节点状态、计时和完成进度都会丢弃，不会保存。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        onConfirmExit()
                    }
                ) {
                    Text("退出运行", color = FlowRunDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("继续运行", color = FlowRunSecondaryText)
                }
            }
        )
    }
}

@Composable
private fun RunV2TopBar(
    nodeNumber: Int,
    totalNodes: Int,
    nodeName: String,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 2.dp)
                .size(40.dp)
                .clickable(onClick = onExit)
                .semantics { contentDescription = "退出" },
            contentAlignment = Alignment.Center
        ) {
            FlowBackChevron(glyphSize = 18.dp, color = FlowRunSecondaryText)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$nodeNumber / $totalNodes",
                fontSize = 10.5.sp,
                color = FlowRunMutedText,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = nodeName,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowRunText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RunV2Progress(progress: Float, percent: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FlowSpacing.page)
            .semantics { contentDescription = "整体进度，已完成 $percent%" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("整体进度", fontSize = 9.5.sp, color = FlowRunMutedText)
            Spacer(Modifier.weight(1f))
            Text(
                text = "$percent%",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = FlowRunSecondaryText
            )
        }
        Spacer(Modifier.height(7.dp))
        FlowRunProgressBar(progress = progress)
    }
}

@Composable
private fun RunV2LoopRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("单节点循环", fontSize = 10.5.sp, color = FlowRunSecondaryText)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .scale(0.72f)
                    .semantics { contentDescription = "单节点循环" },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = FlowPrimary,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.13f),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun RunV2Actions(
    snapshot: PlanRunSnapshot,
    isTimer: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top
    ) {
        val state = snapshot.nodeState
        when {
            !isTimer && state == NodeRunState.READY -> {
                FlowCircleAction(
                    icon = FlowActionIcon.Play,
                    label = "开始",
                    accessibilityLabel = "开始",
                    primary = true,
                    onClick = onStart
                )
                Spacer(Modifier.width(38.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Close,
                    label = "退出",
                    accessibilityLabel = "退出",
                    primary = false,
                    onClick = onExit
                )
            }
            !isTimer && state == NodeRunState.RUNNING -> {
                FlowCircleAction(
                    icon = FlowActionIcon.Check,
                    label = "完成",
                    accessibilityLabel = "完成当前节点",
                    primary = true,
                    onClick = onComplete
                )
                Spacer(Modifier.width(38.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Close,
                    label = "退出",
                    accessibilityLabel = "退出",
                    primary = false,
                    onClick = onExit
                )
            }
            isTimer && state == NodeRunState.READY -> {
                FlowCircleAction(
                    icon = FlowActionIcon.Play,
                    label = "开始",
                    accessibilityLabel = "开始计时",
                    primary = true,
                    onClick = onStart
                )
                Spacer(Modifier.width(38.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Close,
                    label = "退出",
                    accessibilityLabel = "退出",
                    primary = false,
                    onClick = onExit
                )
            }
            isTimer && state == NodeRunState.RUNNING -> {
                FlowCircleAction(
                    icon = FlowActionIcon.Pause,
                    label = "暂停",
                    accessibilityLabel = "暂停",
                    primary = false,
                    onClick = onPause
                )
                Spacer(Modifier.width(34.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Check,
                    label = "完成",
                    accessibilityLabel = "完成当前节点",
                    primary = false,
                    onClick = onComplete
                )
                Spacer(Modifier.width(34.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Close,
                    label = "退出",
                    accessibilityLabel = "退出",
                    primary = false,
                    onClick = onExit
                )
            }
            isTimer && state == NodeRunState.PAUSED -> {
                FlowCircleAction(
                    icon = FlowActionIcon.Play,
                    label = "继续",
                    accessibilityLabel = "继续计时",
                    primary = true,
                    onClick = onResume
                )
                Spacer(Modifier.width(34.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Check,
                    label = "完成",
                    accessibilityLabel = "完成当前节点",
                    primary = false,
                    onClick = onComplete
                )
                Spacer(Modifier.width(34.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Close,
                    label = "退出",
                    accessibilityLabel = "退出",
                    primary = false,
                    onClick = onExit
                )
            }
            isTimer && state == NodeRunState.EXPIRED -> {
                FlowCircleAction(
                    icon = FlowActionIcon.Check,
                    label = "完成",
                    accessibilityLabel = "完成当前节点",
                    primary = true,
                    onClick = onComplete
                )
                Spacer(Modifier.width(38.dp))
                FlowCircleAction(
                    icon = FlowActionIcon.Close,
                    label = "退出",
                    accessibilityLabel = "退出",
                    primary = false,
                    onClick = onExit
                )
            }
        }
    }
}
