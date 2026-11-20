package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay

/**
 * Run screen. The 200 ms UI ticker only asks the coordinator to recompute the
 * remaining time from the monotonic deadline; it is not the time source.
 */
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
    val progress = if (snapshot.totalNodes == 0) 0f else {
        snapshot.completedNodes.toFloat() / snapshot.totalNodes.toFloat()
    }

    Surface(Modifier.fillMaxSize(), color = FlowBackground) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = FlowSpacing.page)
        ) {
            RunTopBar(
                planName = snapshot.plan.name,
                onExit = { showExitConfirmation = true }
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "STEP ${(snapshot.nodeIndex + 1).toString().padStart(2, '0')}",
                    fontSize = 13.sp,
                    color = FlowSecondaryText
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${snapshot.nodeIndex + 1} / ${snapshot.totalNodes}",
                    fontSize = 13.sp,
                    color = FlowSecondaryText
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowProgressBar(progress = progress)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                node?.let { FlowTypeBadge(it.type) }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = node?.name ?: "运行结束",
                    fontSize = if (isTimer) 24.sp else 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowText,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(20.dp))

                if (isTimer) {
                    Text(
                        text = formatCountdown(snapshot.remainingMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 76.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = timerColor(snapshot.nodeState),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = timerStatusText(snapshot.nodeState),
                        fontSize = 15.sp,
                        color = timerColor(snapshot.nodeState),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    TimerLinearProgress(
                        totalMs = snapshot.totalTimerMs,
                        remainingMs = snapshot.remainingMs
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${node?.target ?: 0}",
                            fontSize = 84.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FlowText
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "次",
                            fontSize = 20.sp,
                            color = FlowSecondaryText,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "完成目标后点击下方按钮",
                        fontSize = 13.sp,
                        color = FlowSecondaryText,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("单节点循环", fontSize = 14.sp, color = FlowSecondaryText)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = snapshot.loopCurrentNode,
                    onCheckedChange = { coordinator.setLoop(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = FlowPrimary)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center
            ) {
                RunNodeActions(
                    snapshot = snapshot,
                    isTimer = isTimer,
                    onStart = { coordinator.startNode() },
                    onPause = { coordinator.pause() },
                    onResume = { coordinator.resume() },
                    onComplete = { coordinator.completeNode() }
                )
            }

            Text(
                text = "返回会询问是否退出，运行进度不会保存。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = FlowSecondaryText
            )
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("退出本次运行？") },
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
                    Text("退出运行", color = FlowDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) { Text("继续运行") }
            }
        )
    }
}

@Composable
private fun RunTopBar(planName: String, onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onExit)
                .semantics { contentDescription = "退出" },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", fontSize = 20.sp, color = FlowSecondaryText)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = planName,
            fontSize = 13.sp,
            color = FlowSecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TimerLinearProgress(totalMs: Long?, remainingMs: Long?) {
    val total = totalMs ?: 0L
    val remaining = (remainingMs ?: total).coerceIn(0L, total.coerceAtLeast(0L))
    val elapsed = if (total > 0L) ((total - remaining).toFloat() / total.toFloat()) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(5.dp)
            .background(FlowOutline, RoundedCornerShape(50))
    ) {
        Box(
            Modifier
                .fillMaxWidth(elapsed.coerceIn(0f, 1f))
                .height(5.dp)
                .background(FlowPrimary, RoundedCornerShape(50))
        )
    }
}

@Composable
private fun RunNodeActions(
    snapshot: PlanRunSnapshot,
    isTimer: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit
) {
    when {
        !isTimer && snapshot.nodeState == NodeRunState.READY -> {
            FlowPrimaryButton(text = "开始", onClick = onStart, height = 56.dp)
        }

        !isTimer && snapshot.nodeState == NodeRunState.RUNNING -> {
            FlowPrimaryButton(text = "完成当前节点", onClick = onComplete, height = 56.dp)
        }

        isTimer && snapshot.nodeState == NodeRunState.READY -> {
            FlowPrimaryButton(text = "开始计时", onClick = onStart, height = 56.dp)
        }

        isTimer && snapshot.nodeState == NodeRunState.RUNNING -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowPrimaryButton(
                    text = "暂停",
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    height = 56.dp
                )
                FlowSecondaryButton(
                    text = "完成当前节点",
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    height = 48.dp
                )
            }
        }

        isTimer && snapshot.nodeState == NodeRunState.PAUSED -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowPrimaryButton(
                    text = "继续计时",
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    height = 56.dp
                )
                FlowSecondaryButton(
                    text = "完成当前节点",
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    height = 48.dp
                )
            }
        }

        snapshot.nodeState == NodeRunState.EXPIRED -> {
            FlowPrimaryButton(text = "完成当前节点", onClick = onComplete, height = 56.dp)
        }
    }
}

private fun timerColor(state: NodeRunState): Color = when (state) {
    NodeRunState.READY -> FlowText
    NodeRunState.RUNNING -> FlowPrimary
    NodeRunState.PAUSED -> FlowWarning
    NodeRunState.EXPIRED -> FlowDanger
}

private fun timerStatusText(state: NodeRunState): String = when (state) {
    NodeRunState.READY -> "准备开始"
    NodeRunState.RUNNING -> "正在计时"
    NodeRunState.PAUSED -> "已暂停"
    NodeRunState.EXPIRED -> "时间到"
}
