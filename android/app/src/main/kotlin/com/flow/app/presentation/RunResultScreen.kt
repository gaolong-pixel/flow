package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.app.domain.run.RunSummary
import com.flow.app.domain.run.formatElapsed
import kotlinx.coroutines.delay

@Composable
fun RunResultScreen(
    summary: RunSummary,
    canRunAgain: Boolean,
    onRunAgain: () -> Unit,
    onBackToPlans: () -> Unit
) {
    BackHandler { onBackToPlans() }
    RunResultScaffold(
        summary = summary,
        completedNormally = true,
        canRunAgain = canRunAgain,
        onRunAgain = onRunAgain,
        onBackToPlans = onBackToPlans
    )
}

@Composable
fun RunExitResultScreen(
    summary: RunSummary,
    canRunAgain: Boolean,
    onRunAgain: () -> Unit,
    onBackToPlans: () -> Unit
) {
    BackHandler { onBackToPlans() }
    RunResultScaffold(
        summary = summary,
        completedNormally = false,
        canRunAgain = canRunAgain,
        onRunAgain = onRunAgain,
        onBackToPlans = onBackToPlans
    )
}

@Composable
private fun RunResultScaffold(
    summary: RunSummary,
    completedNormally: Boolean,
    canRunAgain: Boolean,
    onRunAgain: () -> Unit,
    onBackToPlans: () -> Unit
) {
    val reduceMotion = rememberReduceMotion()
    val haptics = rememberFlowHaptics()
    val confetti = remember { Animatable(0f) }
    val celebrate = completedNormally
    val title = if (completedNormally) "执行完成！" else "已退出流程"
    val subtitle = if (completedNormally) {
        "太棒了！继续保持！"
    } else {
        "当前节点未计入完成数量"
    }

    LaunchedEffect(summary.runId, completedNormally) {
        confetti.snapTo(0f)
        if (celebrate && !reduceMotion) {
            confetti.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
            delay(120L)
            haptics.commit()
        } else {
            confetti.snapTo(1f)
            if (!reduceMotion) {
                delay(320L)
                haptics.tick()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        FlowLightArt(if (completedNormally) FlowArtMode.Done else FlowArtMode.Exit)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FlowSpacing.page),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(30.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (celebrate) {
                        FlowConfetti(
                            progress = confetti.value,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    FlowResultEmblem(
                        completedNormally = completedNormally,
                        animationKey = summary.runId,
                        reduceMotion = reduceMotion
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowText,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = FlowSecondaryText,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (completedNormally) {
                            "${summary.completedNodes} / ${summary.totalNodes} 个节点"
                        } else {
                            "已完成 ${summary.completedNodes} / ${summary.totalNodes} 个节点"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (completedNormally) FlowText else FlowSecondaryInk
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 7.dp)
                            .size(3.dp)
                            .background(FlowOutlineStrong, CircleShape)
                    )
                    Text(
                        text = "用时 ${formatElapsed(summary.elapsedMs)}",
                        fontSize = 12.sp,
                        color = FlowSecondaryText
                    )
                }
                Spacer(Modifier.height(40.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlowSpacing.page)
                    .padding(top = 8.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (completedNormally) {
                    if (canRunAgain) {
                        FlowGradientButton(text = "再次执行", onClick = onRunAgain)
                    }
                    FlowOutlineButton(text = "返回首页", onClick = onBackToPlans)
                } else {
                    FlowGradientButton(text = "返回首页", onClick = onBackToPlans)
                    if (canRunAgain) {
                        FlowOutlineButton(text = "再次执行", onClick = onRunAgain)
                    }
                }
            }
        }
    }
}
