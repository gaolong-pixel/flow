package com.flow.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.app.domain.editor.EditorController
import com.flow.app.domain.editor.EditorError
import com.flow.app.domain.editor.EditorMode
import com.flow.app.domain.editor.EditorResult
import com.flow.app.domain.editor.EditorState
import com.flow.app.domain.editor.RoutineDraft
import com.flow.app.domain.editor.SyncStatus
import com.flow.app.domain.model.CountMode
import com.flow.app.domain.model.RoutineStep
import com.flow.app.domain.model.StepType
import com.flow.app.domain.model.TimerCategory
import com.flow.app.domain.parser.StepCandidate
import com.flow.app.domain.parser.ParseErrorCode
import com.flow.app.domain.parser.ParseStatus
import com.flow.app.data.PlanStore
import com.flow.app.FlowApplication
import com.flow.app.domain.model.Routine
import com.flow.app.domain.run.RunCoordinator
import com.flow.app.domain.run.RunRuntimeState
import com.flow.app.domain.run.RunStartError
import com.flow.app.domain.run.RunSummary
import com.flow.app.domain.run.StartRunResult
import com.flow.app.domain.run.formatCountdown
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext

@Composable
fun FlowApp(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val application = context.applicationContext as FlowApplication
    val coordinator = remember(application) { application.runCoordinator }
    val store = remember { PlanStore(File(context.filesDir, "plans.json")) }
    var plans by remember { mutableStateOf<List<Routine>>(emptyList()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var setupPlan by remember { mutableStateOf<Routine?>(null) }
    var setupMessage by remember { mutableStateOf<String?>(null) }
    var exitSummary by remember { mutableStateOf<RunSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val runtime by coordinator.runtime.collectAsState()

    fun reload() {
        try {
            plans = store.loadAll()
            error = null
        } catch (e: Exception) {
            error = "读取计划失败"
        }
    }

    LaunchedEffect(Unit) { reload() }

    FlowTheme {
        when (val current = runtime) {
            is RunRuntimeState.Active -> {
                RunScreen(
                    coordinator = coordinator,
                    snapshot = current.snapshot,
                    onConfirmExit = {
                        val summary = coordinator.exitCurrent()
                        if (summary != null) exitSummary = summary
                        setupPlan = null
                        setupMessage = null
                    }
                )
            }

            is RunRuntimeState.Finished -> {
                RunResultScreen(
                    summary = current.summary,
                    onBackToPlans = {
                        coordinator.dismissResult()
                        exitSummary = null
                        reload()
                    }
                )
            }

            RunRuntimeState.Idle -> {
                val editing = editingId?.let { id -> plans.firstOrNull { it.id == id } }
                val launchPlan = setupPlan?.let { pending ->
                    plans.firstOrNull { it.id == pending.id } ?: pending
                }
                when {
                    exitSummary != null -> {
                        RunExitResultScreen(
                            summary = exitSummary!!,
                            onBackToPlans = {
                                exitSummary = null
                                reload()
                            }
                        )
                    }

                    editing != null -> {
                        EditorScreen(
                            initialRoutine = editing,
                            onBack = { editingId = null; reload() },
                            onPersist = { plan -> store.save(plan).also { if (it) reload() } }
                        )
                    }

                    launchPlan != null -> {
                        RunSetupScreen(
                            plan = launchPlan,
                            message = setupMessage,
                            onBack = {
                                setupPlan = null
                                setupMessage = null
                            },
                            onStart = {
                                when (val result = coordinator.start(launchPlan)) {
                                    is StartRunResult.Started -> {
                                        setupPlan = null
                                        setupMessage = null
                                        exitSummary = null
                                    }

                                    is StartRunResult.Rejected -> {
                                        setupMessage = when (result.error) {
                                            RunStartError.ALREADY_RUNNING ->
                                                "已有运行中的计划，请先完成或退出。"
                                            RunStartError.EMPTY_PLAN ->
                                                "计划没有节点，无法运行。"
                                        }
                                    }
                                }
                            }
                        )
                    }

                    else -> {
                        PlanHomeScreen(
                            plans = plans,
                            error = error,
                            onCreate = {
                                val plan = RoutineDraft.new(name = "未命名计划").toRoutine()
                                if (store.save(plan)) {
                                    reload()
                                    editingId = plan.id
                                } else {
                                    error = "保存计划失败"
                                }
                            },
                            onOpen = { editingId = it.id },
                            onStart = {
                                setupPlan = it
                                setupMessage = null
                            },
                            onDelete = { plan ->
                                if (store.delete(plan.id)) reload() else error = "删除计划失败"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanHomeScreen(
    plans: List<Routine>,
    error: String?,
    onCreate: () -> Unit,
    onOpen: (Routine) -> Unit,
    onStart: (Routine) -> Unit,
    onDelete: (Routine) -> Unit
) {
    var deleteCandidate by remember { mutableStateOf<Routine?>(null) }
    Surface(Modifier.fillMaxSize(), color = FlowBackground) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = FlowSpacing.page, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Flow",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FlowText
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "把流程变成可执行的步骤",
                        fontSize = 13.sp,
                        color = FlowSecondaryText
                    )
                }
                Button(
                    onClick = onCreate,
                    modifier = Modifier
                        .width(92.dp)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("＋ 新建", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(34.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的流程",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowText
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${plans.size} 个流程",
                    fontSize = 13.sp,
                    color = FlowSecondaryText
                )
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = FlowDangerSoft
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowDanger
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (plans.isEmpty()) {
                HomeEmptyState(onCreate = onCreate)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(plans.size) { index ->
                        PlanCard(
                            plan = plans[index],
                            onOpen = { onOpen(plans[index]) },
                            onStart = { onStart(plans[index]) },
                            onDelete = { deleteCandidate = plans[index] }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    deleteCandidate?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = FlowSurface,
            tonalElevation = 0.dp,
            titleContentColor = FlowText,
            textContentColor = FlowSecondaryText,
            title = { Text("删除这个流程？") },
            text = { Text("删除后，这个流程及其节点将无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        onDelete(plan)
                    }
                ) {
                    Text("删除", color = FlowDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("取消", color = FlowSecondaryText)
                }
            }
        )
    }
}

@Composable
private fun FlowGraphic() {
    Box(
        Modifier
            .size(72.dp)
            .background(FlowAccentSoft, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(7.dp).background(FlowPrimary, CircleShape))
            Box(Modifier.width(1.dp).height(8.dp).background(FlowPrimary.copy(alpha = 0.30f)))
            Box(Modifier.size(7.dp).background(FlowPrimary, CircleShape))
            Box(Modifier.width(1.dp).height(8.dp).background(FlowPrimary.copy(alpha = 0.30f)))
            Box(Modifier.size(7.dp).background(FlowPrimary, CircleShape))
        }
    }
}

@Composable
private fun HomeEmptyState(onCreate: () -> Unit) {
    FlowCard(contentPadding = 24.dp) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FlowGraphic()
            Spacer(Modifier.height(18.dp))
            Text(
                text = "还没有流程",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "创建一套流程，让每一步都有明确安排。",
                fontSize = 13.sp,
                color = FlowSecondaryText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onCreate,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                Text("创建第一个流程", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: Routine,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    FlowCard(
        modifier = Modifier.clickable(onClick = onOpen),
        contentPadding = 18.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = plan.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "${plan.steps.size} 个节点",
                fontSize = 13.sp,
                color = FlowSecondaryText
            )

            if (plan.steps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column {
                    plan.steps.take(3).forEachIndexed { index, step ->
                        PlanTimelineStep(
                            step = step,
                            index = index,
                            isLast = index == plan.steps.take(3).lastIndex
                        )
                    }
                }
                if (plan.steps.size > 3) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "还有 ${plan.steps.size - 3} 个节点",
                        fontSize = 12.sp,
                        color = FlowSecondaryText
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStart,
                    enabled = plan.steps.isNotEmpty(),
                    modifier = Modifier
                        .width(84.dp)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("开始", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("编辑", fontSize = 14.sp, color = FlowPrimary)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Text("⋮", fontSize = 24.sp, color = FlowSecondaryText)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("删除", color = FlowDanger) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanTimelineStep(step: RoutineStep, index: Int, isLast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(16.dp)
                .fillMaxHeight()
        ) {
            if (index > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(1.dp)
                        .height(19.dp)
                        .background(FlowConnector)
                )
            }
            if (!isLast) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(1.dp)
                        .height(19.dp)
                        .background(FlowConnector)
                )
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(8.dp)
                    .background(
                        if (step.type == StepType.COUNT) FlowPrimary else FlowWarning,
                        CircleShape
                    )
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = step.name,
            fontSize = 15.sp,
            color = FlowText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = compactStepTarget(step),
            fontSize = 13.sp,
            color = FlowSecondaryText
        )
    }
}

private fun compactStepTarget(step: RoutineStep): String = when (step.type) {
    StepType.COUNT -> "${step.target ?: 0} 次"
    StepType.TIMER -> formatCountdown(step.durationMs)
}

@Composable
private fun EditorScreen(
    initialRoutine: com.flow.app.domain.model.Routine,
    onBack: () -> Unit,
    onPersist: (com.flow.app.domain.model.Routine) -> Boolean
) {
    val controller = remember(initialRoutine.id) {
        EditorController(RoutineDraft.from(initialRoutine), initialMode = EditorMode.CARDS)
    }
    var state by remember { mutableStateOf(controller.state) }
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = controller.state.textBuffer,
                selection = TextRange(controller.state.textBuffer.length)
            )
        )
    }
    var modeMessage by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<UiNotice?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var cardForm by remember { mutableStateOf<CardFormState?>(null) }
    var deleteTarget by remember { mutableStateOf<RoutineStep?>(null) }
    var nameInput by remember(initialRoutine.id) { mutableStateOf(initialRoutine.name) }
    var nameError by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    val selectMode: (EditorMode) -> Unit = { targetMode ->
        if (state.mode != targetMode) {
            val result = controller.switchMode(targetMode)
            state = result.state
            when (result) {
                is EditorResult.Success -> {
                    modeMessage = null
                    if (result.state.mode == EditorMode.TEXT) {
                        textFieldValue = TextFieldValue(
                            text = result.state.textBuffer,
                            selection = TextRange(result.state.textBuffer.length)
                        )
                    }
                }

                is EditorResult.Failure -> {
                    modeMessage = modeFailureMessage(result.error, result.state)
                }
            }
        }
    }

    val onTextChange: (TextFieldValue) -> Unit = { value ->
        textFieldValue = value
        state = controller.onTextChanged(value.text)
        notice = null
        modeMessage = null
    }

    val persistDraft: (EditorState) -> Unit = { next ->
        val persisted = runCatching { onPersist(next.draft.toRoutine()) }.getOrDefault(false)
        notice = UiNotice(
            if (persisted) "已保存修改。" else "保存到计划存储失败。",
            isError = !persisted
        )
        modeMessage = null
    }

    val applyText: () -> Unit = {
        if (state.mode == EditorMode.TEXT) {
            val result = controller.commitText()
            state = result.state
            when (result) {
                is EditorResult.Success -> {
                    textFieldValue = TextFieldValue(
                        text = result.state.textBuffer,
                        selection = TextRange(result.state.textBuffer.length)
                    )
                    notice = UiNotice("已应用文本修改。", isError = false)
                    modeMessage = null
                }

                is EditorResult.Failure -> {
                    notice = UiNotice(commitErrorMessage(result.error), isError = true)
                }
            }
        }
    }

    val savePlan: () -> Unit = {
        when (val renamed = controller.rename(nameInput)) {
            is EditorResult.Failure -> {
                state = renamed.state
                nameError = nameErrorMessage(renamed.error)
                notice = UiNotice(nameError ?: "流程名称不合法。", isError = true)
                modeMessage = null
            }

            is EditorResult.Success -> {
                state = renamed.state
                nameInput = renamed.state.draft.name
                nameError = null

                if (state.mode == EditorMode.CARDS) {
                    // Card edits live in the draft directly; persist them and the
                    // name together. The name is not part of the step text.
                    persistDraft(state)
                } else {
                    val result = controller.commitText()
                    state = result.state
                    when (result) {
                        is EditorResult.Success -> {
                            textFieldValue = TextFieldValue(
                                text = result.state.textBuffer,
                                selection = TextRange(result.state.textBuffer.length)
                            )
                            persistDraft(result.state)
                        }

                        is EditorResult.Failure -> {
                            notice = UiNotice(commitErrorMessage(result.error), isError = true)
                        }
                    }
                }
            }
        }
    }

    val restore: () -> Unit = {
        val result = controller.restoreLastCommitted()
        state = result.state
        when (result) {
            is EditorResult.Success -> {
                textFieldValue = TextFieldValue(
                    text = result.state.textBuffer,
                    selection = TextRange(result.state.textBuffer.length)
                )
                nameInput = result.state.draft.name
                nameError = null
                notice = UiNotice("已恢复上次保存。", isError = false)
                modeMessage = null
            }

            is EditorResult.Failure -> {
                notice = UiNotice(commitErrorMessage(result.error), isError = true)
            }
        }
    }

    val onCardResult: (EditorResult, String) -> Unit = { result, successMessage ->
        state = result.state
        when (result) {
            is EditorResult.Success -> {
                textFieldValue = TextFieldValue(
                    text = result.state.textBuffer,
                    selection = TextRange(result.state.textBuffer.length)
                )
                notice = UiNotice(successMessage, isError = false)
                modeMessage = null
            }

            is EditorResult.Failure -> {
                notice = UiNotice(commitErrorMessage(result.error), isError = true)
            }
        }
    }

    val submitCardForm: (CardFormState) -> Unit = { form ->
        val targetValue = form.target.toIntOrNull()
        val durationSeconds = form.durationSeconds.toLongOrNull()
        val durationMs = if (durationSeconds != null &&
            durationSeconds <= Long.MAX_VALUE / 1_000L
        ) {
            durationSeconds * 1_000L
        } else {
            null
        }

        val validationError = when {
            form.type == StepType.COUNT && targetValue == null ->
                "请输入有效的目标次数。"
            form.type == StepType.TIMER && durationMs == null ->
                "请输入有效的计时时长（秒）。"
            else -> null
        }
        if (validationError != null) {
            cardForm = form.copy(error = validationError)
        } else {
            val candidate = StepCandidate(
                name = form.name,
                type = form.type,
                target = if (form.type == StepType.COUNT) targetValue else null,
                countMode = if (form.type == StepType.COUNT) CountMode.COMPLETE else null,
                durationMs = if (form.type == StepType.TIMER) durationMs else null,
                category = if (form.type == StepType.TIMER) TimerCategory.NORMAL else null,
                repeatCount = 1
            )
            val result = if (form.editingId == null) {
                controller.addStep(candidate)
            } else {
                controller.updateStep(
                    RoutineStep(
                        id = form.editingId,
                        name = candidate.name,
                        type = candidate.type,
                        target = candidate.target,
                        countMode = candidate.countMode,
                        durationMs = candidate.durationMs,
                        category = candidate.category,
                        repeatCount = 1
                    )
                )
            }
            state = result.state
            when (result) {
                is EditorResult.Success -> {
                    cardForm = null
                    textFieldValue = TextFieldValue(
                        text = result.state.textBuffer,
                        selection = TextRange(result.state.textBuffer.length)
                    )
                    notice = UiNotice(
                        if (form.editingId == null) "已添加步骤。" else "已更新步骤。",
                        isError = false
                    )
                    modeMessage = null
                }

                is EditorResult.Failure -> {
                    cardForm = form.copy(error = commitErrorMessage(result.error))
                }
            }
        }
    }

    val addCard: () -> Unit = {
        cardForm = CardFormState.new(StepType.COUNT)
    }

    val editCard: (RoutineStep) -> Unit = { step ->
        cardForm = CardFormState.from(step)
    }

    val deleteCard: (RoutineStep) -> Unit = { step ->
        deleteTarget = step
    }

    val duplicateCard: (RoutineStep) -> Unit = { step ->
        onCardResult(controller.duplicateStep(step.id), "已复制步骤。")
    }

    val moveCard: (Int, Int) -> Unit = { from, to ->
        onCardResult(controller.moveStep(from, to), "已调整步骤顺序。")
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            EditorHeader(onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FlowSpacing.page)
            ) {
                Spacer(Modifier.height(24.dp))
                FlowNameField(
                    name = nameInput,
                    error = nameError,
                    onNameChange = {
                        nameInput = it
                        nameError = null
                        notice = null
                    }
                )
                Spacer(Modifier.height(22.dp))
                ModeSection(
                    selectedMode = state.mode,
                    onModeSelected = selectMode
                )
                Spacer(Modifier.height(12.dp))
                SyncSection(status = state.syncStatus)
                modeMessage?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    ModeMessage(message = message)
                }
                Spacer(Modifier.height(24.dp))
                ContentCard(
                    state = state,
                    textFieldValue = textFieldValue,
                    onTextChange = onTextChange,
                    errors = lineErrors(state),
                    notice = notice,
                    onApplyText = applyText,
                    onRestoreRequest = { showRestoreDialog = true },
                    onAddCard = addCard,
                    onEditCard = editCard,
                    onDeleteCard = deleteCard,
                    onDuplicateCard = duplicateCard,
                    onMoveCard = moveCard
                )
                Spacer(Modifier.height(24.dp))
            }
            EditorSaveBar(onSave = savePlan)
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复上次保存") },
            text = {
                Text("当前未保存的输入将被清除，确定恢复上次保存的内容吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        restore()
                    }
                ) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    cardForm?.let { form ->
        CardFormDialog(
            form = form,
            onChange = { changed -> cardForm = changed.copy(error = null) },
            onDismiss = { cardForm = null },
            onSave = { submitCardForm(form) }
        )
    }

    deleteTarget?.let { step ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除步骤") },
            text = { Text("确定删除“${step.name}”吗？此操作不会删除其他步骤。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onCardResult(controller.deleteStep(step.id), "已删除步骤。")
                    }
                ) {
                    Text("删除", color = FlowInvalid)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun EditorHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = FlowSpacing.page),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "编辑流程",
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
}

@Composable
private fun FlowNameField(
    name: String,
    error: String?,
    onNameChange: (String) -> Unit
) {
    val tooLong = name.length > EditorController.MAX_NAME_LENGTH
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val inputHeight = (54f * fontScale.coerceAtLeast(1f)).dp
    val shownError = error ?: if (tooLong) {
        "流程名称最多 ${EditorController.MAX_NAME_LENGTH} 个字符。"
    } else {
        null
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "流程名称",
            fontSize = 13.sp,
            color = FlowSecondaryText
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(inputHeight),
            singleLine = true,
            isError = shownError != null,
            placeholder = { Text("例如：晨间拉伸", fontSize = 17.sp) },
            textStyle = TextStyle(
                color = FlowText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            ),
            shape = RoundedCornerShape(FlowRadius.input),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = FlowPrimary,
                unfocusedBorderColor = FlowOutline,
                focusedContainerColor = FlowSurface,
                unfocusedContainerColor = FlowSurface
            )
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = shownError ?: "${name.length}/${EditorController.MAX_NAME_LENGTH}",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            fontSize = 12.sp,
            color = if (shownError != null) FlowInvalid else FlowSecondaryText
        )
    }
}

@Composable
private fun ModeSection(
    selectedMode: EditorMode,
    onModeSelected: (EditorMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "编辑方式",
            fontSize = 13.sp,
            color = FlowSecondaryText
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(FlowRadius.input))
                .background(FlowSegmentBackground)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeButton(
                label = "卡片编辑",
                selected = selectedMode == EditorMode.CARDS,
                onClick = { onModeSelected(EditorMode.CARDS) },
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                label = "文本编辑",
                selected = selectedMode == EditorMode.TEXT,
                onClick = { onModeSelected(EditorMode.TEXT) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) FlowSurface else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) FlowPrimary else FlowSecondaryText
        )
    }
}

@Composable
private fun SyncSection(status: SyncStatus) {
    val label = when (status) {
        SyncStatus.SYNCED -> "已同步"
        SyncStatus.DIRTY -> "有未提交修改"
        SyncStatus.INVALID -> "存在输入错误"
    }
    val color = when (status) {
        SyncStatus.SYNCED -> FlowSynced
        SyncStatus.DIRTY -> FlowDirty
        SyncStatus.INVALID -> FlowInvalid
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun ContentCard(
    state: EditorState,
    textFieldValue: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    errors: List<UiLineError>,
    notice: UiNotice?,
    onApplyText: () -> Unit,
    onRestoreRequest: () -> Unit,
    onAddCard: () -> Unit,
    onEditCard: (RoutineStep) -> Unit,
    onDeleteCard: (RoutineStep) -> Unit,
    onDuplicateCard: (RoutineStep) -> Unit,
    onMoveCard: (Int, Int) -> Unit
) {
    when (state.mode) {
        EditorMode.TEXT -> TextModeEditor(
            textFieldValue = textFieldValue,
            onTextChange = onTextChange,
            errors = errors,
            notice = notice,
            onApplyText = onApplyText,
            onRestoreRequest = onRestoreRequest
        )

        EditorMode.CARDS -> CardsModeContent(
            state = state,
            notice = notice,
            onAdd = onAddCard,
            onEdit = onEditCard,
            onDelete = onDeleteCard,
            onDuplicate = onDuplicateCard,
            onMove = onMoveCard
        )
    }
}

@Composable
private fun TextModeEditor(
    textFieldValue: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    errors: List<UiLineError>,
    notice: UiNotice?,
    onApplyText: () -> Unit,
    onRestoreRequest: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "步骤文本",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText
            )
            Spacer(Modifier.weight(1f))
            Text("一行一个步骤", fontSize = 13.sp, color = FlowSecondaryText)
        }
        Spacer(Modifier.height(10.dp))
        FormatHint()
        notice?.let {
            Spacer(Modifier.height(8.dp))
            NoticeBanner(notice = it)
        }
        if (errors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ErrorList(errors = errors)
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = FlowSurface,
            border = BorderStroke(1.dp, FlowOutline)
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp)
                    .padding(16.dp),
                textStyle = TextStyle(
                    color = FlowText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                ),
                cursorBrush = SolidColor(FlowPrimary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default
                ),
                singleLine = false,
                maxLines = Int.MAX_VALUE,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = "在这里输入流程步骤，一行一个步骤。",
                                fontSize = 15.sp,
                                color = FlowSecondaryText
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onApplyText,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(FlowRadius.button),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlowPrimarySoft,
                    contentColor = FlowPrimary
                )
            ) {
                Text("应用文本修改", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            TextButton(
                onClick = onRestoreRequest,
                modifier = Modifier.height(44.dp)
            ) {
                Text("恢复", fontSize = 15.sp, color = FlowSecondaryText)
            }
        }
    }
}

@Composable
private fun FormatHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = FlowPrimarySoft
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "格式示例",
                fontSize = 13.sp,
                color = FlowPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "俯卧撑 15次 · 阅读 20分钟 · 拉伸 45秒 x2",
                fontSize = 13.sp,
                color = FlowText
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "一行一个步骤；数字和单位表示目标。",
                fontSize = 12.sp,
                color = FlowSecondaryText
            )
        }
    }
}

@Composable
private fun ModeMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = FlowInvalid.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, FlowInvalid.copy(alpha = 0.18f))
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = FlowInvalid
        )
    }
}

@Composable
private fun NoticeBanner(notice: UiNotice) {
    val color = if (notice.isError) FlowInvalid else FlowSynced
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f)
    ) {
        Text(
            text = notice.message,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
private fun ErrorList(errors: List<UiLineError>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 132.dp)
            .verticalScroll(rememberScrollState())
    ) {
        errors.forEach { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = FlowInvalid.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, FlowInvalid.copy(alpha = 0.14f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "第 ${error.lineNumber} 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowInvalid,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (error.status == ParseStatus.INCOMPLETE) {
                                "未完成"
                            } else {
                                "不合法"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = FlowInvalid
                        )
                    }
                    Text(
                        text = "“${error.raw.ifBlank { "空行" }}”",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowSecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowText
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CardsModeContent(
    state: EditorState,
    notice: UiNotice?,
    onAdd: () -> Unit,
    onEdit: (RoutineStep) -> Unit,
    onDelete: (RoutineStep) -> Unit,
    onDuplicate: (RoutineStep) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val steps = state.draft.steps
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "流程节点",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText
            )
            Spacer(Modifier.weight(1f))
            Text("${steps.size} 个节点", fontSize = 13.sp, color = FlowSecondaryText)
        }

        notice?.let {
            Spacer(Modifier.height(10.dp))
            NoticeBanner(notice = it)
        }

        if (steps.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "还没有节点，先添加一个节点。",
                fontSize = 14.sp,
                color = FlowSecondaryText
            )
        } else {
            Spacer(Modifier.height(12.dp))
            steps.forEachIndexed { index, step ->
                StepCard(
                    step = step,
                    index = index,
                    isFirst = index == 0,
                    isLast = index == steps.lastIndex,
                    onEdit = { onEdit(step) },
                    onDelete = { onDelete(step) },
                    onDuplicate = { onDuplicate(step) },
                    onMoveUp = { onMove(index, index - 1) },
                    onMoveDown = { onMove(index, index + 1) }
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(2.dp))
        AddNodeButton(onAdd = onAdd)
    }
}

@Composable
private fun AddNodeButton(onAdd: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .drawBehind {
                drawRoundRect(
                    color = Color(0xFFC8D5EB),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
                    )
                )
            }
            .clip(shape)
            .clickable(onClick = onAdd),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("+", fontSize = 19.sp, color = FlowPrimary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "添加节点",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = FlowPrimary
        )
    }
}

@Composable
private fun StepCard(
    step: RoutineStep,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FlowSurface, RoundedCornerShape(18.dp))
            .border(1.dp, FlowOutline, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(FlowPrimarySoft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowPrimary
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = step.name,
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = FlowText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowTypeBadge(step.type)
            Spacer(Modifier.weight(1f))
            Text(
                text = stepDetail(step),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = FlowText
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FlowOutline)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardActionButton(label = "编辑", onClick = onEdit)
            CardActionButton(label = "复制", onClick = onDuplicate)
            CardActionButton(label = "上移", onClick = onMoveUp, enabled = !isFirst)
            CardActionButton(label = "下移", onClick = onMoveDown, enabled = !isLast)
            Spacer(Modifier.weight(1f))
            CardActionButton(label = "删除", onClick = onDelete, color = FlowInvalid)
        }
    }
}

@Composable
private fun EditorSaveBar(onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(FlowSurface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(FlowOutline))
        Box(Modifier.padding(16.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(FlowRadius.button)
            ) {
                Text(
                    text = "保存流程",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun CardActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = FlowPrimary
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) color else FlowSecondaryText.copy(alpha = 0.4f)
        )
    }
}

private fun stepDetail(step: RoutineStep): String = when (step.type) {
    StepType.COUNT -> "${step.target ?: 0} 次"
    StepType.TIMER -> {
        val seconds = (step.durationMs ?: 0L) / 1_000L
        if (seconds % 60L == 0L) "${seconds / 60L} 分钟" else "$seconds 秒"
    }
}

private fun formatDurationLabel(durationMs: Long?): String {
    val ms = durationMs ?: 0L
    val seconds = ms / 1_000L
    return when {
        seconds > 0 && seconds % 3_600L == 0L -> "${seconds / 3_600L} 小时"
        seconds > 0 && seconds % 60L == 0L -> "${seconds / 60L} 分钟"
        else -> "$seconds 秒"
    }
}

private data class CardFormState(
    val editingId: String?,
    val type: StepType,
    val name: String,
    val target: String,
    val durationSeconds: String,
    val error: String? = null
) {
    fun withType(newType: StepType): CardFormState = when (newType) {
        StepType.COUNT -> copy(
            type = StepType.COUNT,
            target = target.ifBlank { "10" },
            error = null
        )

        StepType.TIMER -> copy(
            type = StepType.TIMER,
            durationSeconds = durationSeconds.ifBlank { "60" },
            error = null
        )
    }

    companion object {
        fun new(type: StepType): CardFormState = CardFormState(
            editingId = null,
            type = type,
            name = "新步骤",
            target = "10",
            durationSeconds = "60"
        )

        fun from(step: RoutineStep): CardFormState = CardFormState(
            editingId = step.id,
            type = step.type,
            name = step.name,
            target = (step.target ?: 10).toString(),
            durationSeconds = ((step.durationMs ?: 60_000L) / 1_000L).toString()
        )
    }
}

@Composable
private fun CardFormDialog(
    form: CardFormState,
    onChange: (CardFormState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (form.editingId == null) "添加步骤" else "编辑步骤")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    text = "步骤类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = FlowSecondaryText
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoicePill(
                        label = "计数步骤",
                        selected = form.type == StepType.COUNT,
                        onClick = { onChange(form.withType(StepType.COUNT)) },
                        modifier = Modifier.weight(1f)
                    )
                    ChoicePill(
                        label = "计时步骤",
                        selected = form.type == StepType.TIMER,
                        onClick = { onChange(form.withType(StepType.TIMER)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onChange(form.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("步骤名称") },
                    singleLine = true
                )

                if (form.type == StepType.COUNT) {
                    OutlinedTextField(
                        value = form.target,
                        onValueChange = { onChange(form.copy(target = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("目标次数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text(
                        text = "计数节点在运行时点击“完成本节点”确认整组完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowSecondaryText
                    )
                } else {
                    OutlinedTextField(
                        value = form.durationSeconds,
                        onValueChange = { onChange(form.copy(durationSeconds = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("计时时长（秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text(
                        text = "计时节点按真实剩余时间倒计时，可暂停、继续和提前完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowSecondaryText
                    )
                }

                form.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowInvalid
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) FlowPrimarySoft else FlowSurface)
            .border(
                BorderStroke(
                    1.dp,
                    if (selected) FlowPrimary.copy(alpha = 0.25f) else FlowOutline
                ),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) FlowPrimary else FlowSecondaryText,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

private fun modeFailureMessage(error: EditorError, state: EditorState): String = when (error) {
    EditorError.TEXT_BLOCKED -> {
        if (state.hasBlockingErrors) {
            "当前文本存在输入错误，请修复后再切换到卡片模式。"
        } else {
            "当前文本有未提交的修改，请先提交后再切换。"
        }
    }

    EditorError.SERIALIZATION_FAILED -> "当前流程暂时无法转换，请检查内容。"
    else -> "暂时无法切换编辑模式，请稍后再试。"
}

private data class UiNotice(
    val message: String,
    val isError: Boolean
)

private data class UiLineError(
    val lineNumber: Int,
    val status: ParseStatus,
    val raw: String,
    val message: String
)

private fun lineErrors(state: EditorState): List<UiLineError> =
    state.parseResult.diagnostics.mapNotNull { diagnostic ->
        val line = state.parseResult.lines.firstOrNull {
            it.lineNumber == diagnostic.lineNumber
        } ?: return@mapNotNull null
        UiLineError(
            lineNumber = diagnostic.lineNumber,
            status = line.status,
            raw = line.raw,
            message = parseErrorMessage(diagnostic.code)
        )
    }

private fun parseErrorMessage(code: ParseErrorCode): String = when (code) {
    ParseErrorCode.INCOMPLETE_MISSING_QUANTITY -> "缺少数量，请补充目标数量。"
    ParseErrorCode.INCOMPLETE_MISSING_UNIT -> "缺少单位，请补充“次”“秒”“分钟”或“小时”。"
    ParseErrorCode.INCOMPLETE_REPEAT_COUNT -> "重复次数未完成，请在 x 后补充数字。"
    ParseErrorCode.INCOMPLETE_ATTRIBUTE -> "属性未完成，请补全方括号内的内容。"
    ParseErrorCode.INVALID_NAME -> "步骤名称不能为空。"
    ParseErrorCode.INVALID_NUMBER -> "数量必须是正整数。"
    ParseErrorCode.INVALID_UNIT -> "单位无法识别，请使用“次”“秒”“分钟”或“小时”。"
    ParseErrorCode.INVALID_DECIMAL -> "暂不支持小数，请输入整秒或整数次。"
    ParseErrorCode.INVALID_COMPOUND -> "一行只能描述一个步骤，请拆分复合时长。"
    ParseErrorCode.INVALID_REPEAT_COUNT -> "重复次数必须大于零。"
    ParseErrorCode.INVALID_ATTRIBUTE -> "属性格式不正确，请检查名称、等号和方括号。"
    ParseErrorCode.INVALID_ATTRIBUTE_TYPE -> "属性与步骤类型不匹配。"
    ParseErrorCode.INVALID_ATTRIBUTE_ORDER -> "属性必须放在行尾。"
    ParseErrorCode.INVALID_TRAILING -> "数量或属性后存在无法识别的内容。"
    ParseErrorCode.INVALID_OVERFLOW -> "数量过大，请缩小数值。"
    ParseErrorCode.INVALID_LINE -> "该行无法解析，请检查格式。"
}

private fun commitErrorMessage(error: EditorError): String = when (error) {
    EditorError.TEXT_BLOCKED -> "文本仍存在未完成或不合法的行，请修复后再保存。"
    EditorError.DRAFT_INVALID -> "步骤数据校验未通过，请检查输入内容。"
    EditorError.INVALID_STEP -> "存在不合法的步骤，请检查名称、数量或单位。"
    EditorError.DUPLICATE_STEP_ID -> "步骤标识重复，暂时无法保存。"
    EditorError.STEP_NOT_FOUND -> "目标步骤不存在，请重新编辑。"
    EditorError.INVALID_MOVE -> "步骤顺序调整失败。"
    EditorError.INVALID_REPEAT_COUNT -> "重复次数必须大于零。"
    EditorError.INVALID_NAME -> "流程名称不能为空。"
    EditorError.NAME_TOO_LONG -> "流程名称最多 ${EditorController.MAX_NAME_LENGTH} 个字符。"
    EditorError.SERIALIZATION_FAILED -> "当前内容暂时无法转换为规范文本，请检查输入。"
    EditorError.WRONG_MODE -> "当前模式下无法执行该操作。"
}

private fun nameErrorMessage(error: EditorError): String? = when (error) {
    EditorError.INVALID_NAME -> "流程名称不能为空。"
    EditorError.NAME_TOO_LONG -> "流程名称最多 ${EditorController.MAX_NAME_LENGTH} 个字符。"
    else -> null
}
