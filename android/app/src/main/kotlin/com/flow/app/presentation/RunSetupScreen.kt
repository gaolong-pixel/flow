package com.flow.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.app.domain.model.Routine
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType

@Composable
fun RunSetupScreen(
    plan: Routine,
    message: String?,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = FlowBackground) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = FlowSpacing.page),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "准备开始",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowText
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack)
                        .semantics { contentDescription = "返回" },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 22.sp, color = FlowText)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FlowSpacing.page)
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = plan.name,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "共 ${plan.steps.size} 个节点",
                    fontSize = 13.sp,
                    color = FlowSecondaryText
                )

                Spacer(Modifier.height(24.dp))
                Column {
                    plan.steps.forEachIndexed { index, step ->
                        SetupStepRow(
                            step = step,
                            index = index,
                            isLast = index == plan.steps.lastIndex
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FlowSegmentBackground.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(FlowPrimarySoft, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("→", fontSize = 12.sp, color = FlowPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "将按照节点顺序依次执行",
                        fontSize = 13.sp,
                        color = FlowSecondaryText
                    )
                }

                if (message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FlowDangerSoft, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        fontSize = 13.sp,
                        color = FlowDanger
                    )
                }

                Spacer(Modifier.height(24.dp))
            }

            Column(Modifier.fillMaxWidth().background(FlowSurface)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(FlowOutline))
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = onStart,
                        enabled = plan.steps.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(FlowRadius.button),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("开始执行", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

}

@Composable
private fun SetupStepRow(
    step: RoutineStep,
    index: Int,
    isLast: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(20.dp).fillMaxHeight()) {
            if (index > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(1.dp)
                        .height(30.dp)
                        .background(FlowConnector)
                )
            }
            if (!isLast) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(1.dp)
                        .height(30.dp)
                        .background(FlowConnector)
                )
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .background(FlowPrimarySoft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowPrimary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = FlowText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (step.type == StepType.COUNT) "COUNT" else "TIMER",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (step.type == StepType.COUNT) FlowPrimary else FlowWarning
            )
        }
        Text(
            text = if (step.type == StepType.COUNT) {
                "${step.target ?: 0} 次"
            } else {
                setupDurationLabel(step.durationMs)
            },
            fontSize = 14.sp,
            color = FlowSecondaryText
        )
    }
}

private fun setupDurationLabel(durationMs: Long?): String {
    val seconds = (durationMs ?: 0L) / 1_000L
    return when {
        seconds > 0 && seconds % 3_600L == 0L -> "${seconds / 3_600L} 小时"
        seconds > 0 && seconds % 60L == 0L -> "${seconds / 60L} 分钟"
        else -> "$seconds 秒"
    }
}
