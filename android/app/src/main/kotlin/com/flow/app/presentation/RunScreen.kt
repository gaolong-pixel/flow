package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flow.app.domain.model.StepType
import com.flow.app.domain.run.NodeRunState
import com.flow.app.domain.run.PlanRunSnapshot
import com.flow.app.domain.run.RunCoordinator
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
    var completedExpanded by rememberSaveable { mutableStateOf(false) }

    val reduceMotion = rememberReduceMotion()
    val haptics = rememberFlowHaptics()
    val listState = rememberLazyListState()
    val shake = remember { Animatable(0f) }
    val expiryPulse = remember { Animatable(0f) }
    val completionFlash = remember { Animatable(0f) }
    var lastCompletedNodes by remember { mutableStateOf(snapshot.completedNodes) }
    val expiryToken by coordinator.expiryFeedback.collectAsState()

    val visibleIndices = snapshot.visibleNodeIndices
    val visibleSignature = visibleIndices.joinToString(",")

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

    // The UI ticker only asks the engine to re-derive remaining time from the
    // monotonic deadline. It is never a time source and never ends a timer.
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

    // Entry restoration and explicit target switches bring the current node
    // into the roller viewport. Ordinary scrolling does not call selectNode and
    // therefore never changes the execution target.
    LaunchedEffect(snapshot.nodeIndex, visibleSignature) {
        val position = visibleIndices.indexOf(snapshot.nodeIndex)
        if (position < 0) return@LaunchedEffect
        val anchor = (position - 1).coerceAtLeast(0)
        listState.animateScrollToItem(anchor)
    }

    // Completion feedback is display-only. The engine transition already
    // happened before this effect runs.
    LaunchedEffect(snapshot.completedNodes) {
        if (snapshot.completedNodes > lastCompletedNodes) {
            lastCompletedNodes = snapshot.completedNodes
            if (reduceMotion) {
                completionFlash.snapTo(0.35f)
                delay(130L)
                completionFlash.snapTo(0f)
            } else {
                completionFlash.snapTo(0f)
                completionFlash.animateTo(1f, tween(520, easing = LinearEasing))
                completionFlash.snapTo(0f)
            }
        } else {
            lastCompletedNodes = snapshot.completedNodes
        }
    }

    // A TIMER expiry is consumed exactly once from the app-scoped coordinator.
    // Recomposition, rotation or returning from background cannot replay it.
    LaunchedEffect(expiryToken) {
        val token = expiryToken ?: return@LaunchedEffect
        // The coordinator publishes the token and the Active snapshot from the
        // same call. Reading the authoritative controller after one frame
        // avoids depending on Compose collection order, and keying only on the
        // token means consuming it never cancels the shake mid-flight.
        delay(60L)
        val current = coordinator.currentSnapshot()
        try {
            if (current?.timerRunId == token &&
                current.nodeState == NodeRunState.EXPIRED
            ) {
                if (reduceMotion) {
                    expiryPulse.snapTo(0.95f)
                    delay(160L)
                    expiryPulse.animateTo(0f, tween(220))
                } else {
                    shake.snapTo(0f)
                    for (offset in floatArrayOf(-16f, 15f, -13f, 10f, -7f, 5f, -3f, 2f, 0f)) {
                        shake.animateTo(offset, tween(50, easing = LinearEasing))
                    }
                    shake.snapTo(0f)
                    expiryPulse.snapTo(1f)
                    expiryPulse.animateTo(0f, tween(320))
                }
            }
        } finally {
            coordinator.consumeExpiryFeedback(token)
        }
    }

    BackHandler { showExitConfirmation = true }

    Box(Modifier.fillMaxSize()) {
        FlowDarkArt()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            RunRollerHeader(
                snapshot = snapshot,
                reduceMotion = reduceMotion,
                onExit = { showExitConfirmation = true }
            )
            RunCompletedDrawer(
                snapshot = snapshot,
                expanded = completedExpanded,
                onToggle = { completedExpanded = !completedExpanded }
            )
            FlowNodeRoller(
                snapshot = snapshot,
                listState = listState,
                reduceMotion = reduceMotion,
                shakeDp = shake.value,
                expiryPulse = expiryPulse.value,
                onSelectNode = { index ->
                    if (coordinator.selectNode(index)) haptics.tick()
                },
                modifier = Modifier.weight(1f)
            )
            RunBottomDock(
                snapshot = snapshot,
                reduceMotion = reduceMotion,
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
                onSkip = {
                    haptics.tick()
                    coordinator.skipCurrent()
                },
                onToggleLoop = {
                    haptics.tick()
                    coordinator.setLoop(it)
                },
                onExit = { showExitConfirmation = true }
            )
        }
        CompletionFlash(progress = completionFlash.value)
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
                    if (snapshot.skippedNodes > 0) {
                        Text("本次已跳过 ${snapshot.skippedNodes} 个节点，不计入完成。")
                    }
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
