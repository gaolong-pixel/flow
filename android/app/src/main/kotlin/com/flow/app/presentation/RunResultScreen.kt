package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.app.domain.run.RunSummary

@Composable
fun RunResultScreen(summary: RunSummary, onBackToPlans: () -> Unit) {
    BackHandler { onBackToPlans() }
    ResultScaffold(
        accent = FlowSuccess,
        soft = FlowSuccessSoft,
        symbol = "✓",
        title = "流程已完成",
        planName = summary.planName,
        progress = "${summary.completedNodes} / ${summary.totalNodes} 个节点",
        note = null,
        onBack = onBackToPlans
    )
}

@Composable
fun RunExitResultScreen(summary: RunSummary, onBackToPlans: () -> Unit) {
    BackHandler { onBackToPlans() }
    ResultScaffold(
        accent = FlowSecondaryText,
        soft = FlowSegmentBackground,
        symbol = "—",
        title = "已退出流程",
        planName = summary.planName,
        progress = "已完成 ${summary.completedNodes} / ${summary.totalNodes} 个节点",
        note = "当前节点未计入已完成数量。",
        onBack = onBackToPlans
    )
}

@Composable
private fun ResultScaffold(
    accent: Color,
    soft: Color,
    symbol: String,
    title: String,
    planName: String,
    progress: String,
    note: String?,
    onBack: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = FlowBackground) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .size(76.dp)
                    .background(soft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = symbol,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = planName,
                fontSize = 15.sp,
                color = FlowSecondaryText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = progress,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText,
                textAlign = TextAlign.Center
            )
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note,
                    fontSize = 13.sp,
                    color = FlowSecondaryText,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.weight(1f))

            FlowPrimaryButton(text = "返回首页", onClick = onBack)
        }
    }
}
