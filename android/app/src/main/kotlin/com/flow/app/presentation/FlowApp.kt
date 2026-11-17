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

@Composable
fun FlowApp(onBack: () -> Unit = {}) {
    FlowTheme {
        EditorScreen(onBack = onBack)
    }
}

@Composable
private fun EditorScreen(onBack: () -> Unit) {
    val controller = remember {
        EditorController(RoutineDraft.new(name = "未命名流程"))
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

    val save: () -> Unit = {
        val result = controller.commitText()
        state = result.state
        when (result) {
            is EditorResult.Success -> {
                textFieldValue = TextFieldValue(
                    text = result.state.textBuffer,
                    selection = TextRange(result.state.textBuffer.length)
                )
                notice = UiNotice("已保存修改。", isError = false)
                modeMessage = null
            }

            is EditorResult.Failure -> {
                notice = UiNotice(commitErrorMessage(result.error), isError = true)
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
        val repeatValue = form.repeatCount.toIntOrNull()
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
            repeatValue == null -> "请输入有效的重复次数。"
            else -> null
        }
        if (validationError != null) {
            cardForm = form.copy(error = validationError)
        } else {
            val candidate = StepCandidate(
                name = form.name,
                type = form.type,
                target = if (form.type == StepType.COUNT) targetValue else null,
                countMode = if (form.type == StepType.COUNT) form.countMode else null,
                durationMs = if (form.type == StepType.TIMER) durationMs else null,
                category = if (form.type == StepType.TIMER) form.category else null,
                repeatCount = repeatValue ?: 1
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
                        repeatCount = candidate.repeatCount
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
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            EditorHeader(onBack = onBack)
            Spacer(Modifier.height(14.dp))
            FlowNameCard(name = state.draft.name)
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(16.dp))
            ContentCard(
                state = state,
                textFieldValue = textFieldValue,
                onTextChange = onTextChange,
                errors = lineErrors(state),
                notice = notice,
                onSave = save,
                onRestoreRequest = { showRestoreDialog = true },
                onAddCard = addCard,
                onEditCard = editCard,
                onDeleteCard = deleteCard,
                onDuplicateCard = duplicateCard,
                onMoveCard = moveCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Spacer(Modifier.height(16.dp))
            BottomActionArea()
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(FlowPrimary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "F",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "Flow",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = FlowText
                )
                Text(
                    text = "流程编辑器",
                    style = MaterialTheme.typography.bodySmall,
                    color = FlowSecondaryText
                )
            }
        }

        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "返回",
                color = FlowPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FlowNameCard(name: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, FlowOutline)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "当前流程",
                style = MaterialTheme.typography.labelMedium,
                color = FlowSecondaryText
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = FlowText
            )
        }
    }
}

@Composable
private fun ModeSection(
    selectedMode: EditorMode,
    onModeSelected: (EditorMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "编辑模式",
            style = MaterialTheme.typography.labelMedium,
            color = FlowSecondaryText
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(FlowSegmentBackground)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeButton(
                label = "文本",
                selected = selectedMode == EditorMode.TEXT,
                onClick = { onModeSelected(EditorMode.TEXT) },
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                label = "卡片",
                selected = selectedMode == EditorMode.CARDS,
                onClick = { onModeSelected(EditorMode.CARDS) },
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
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) FlowSurface else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(BorderStroke(1.dp, FlowOutline), shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "同步状态",
            style = MaterialTheme.typography.labelMedium,
            color = FlowSecondaryText
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = color.copy(alpha = 0.10f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(color, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
            }
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
private fun ContentCard(
    state: EditorState,
    textFieldValue: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    errors: List<UiLineError>,
    notice: UiNotice?,
    onSave: () -> Unit,
    onRestoreRequest: () -> Unit,
    onAddCard: () -> Unit,
    onEditCard: (RoutineStep) -> Unit,
    onDeleteCard: (RoutineStep) -> Unit,
    onDuplicateCard: (RoutineStep) -> Unit,
    onMoveCard: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, FlowOutline)
    ) {
        when (state.mode) {
            EditorMode.TEXT -> TextModeEditor(
                textFieldValue = textFieldValue,
                onTextChange = onTextChange,
                errors = errors,
                notice = notice,
                onSave = onSave,
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
}

@Composable
private fun TextModeEditor(
    textFieldValue: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    errors: List<UiLineError>,
    notice: UiNotice?,
    onSave: () -> Unit,
    onRestoreRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "文本编辑",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = FlowText
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "文本模式",
                style = MaterialTheme.typography.labelSmall,
                color = FlowSecondaryText
            )
        }
        Spacer(Modifier.height(8.dp))
        FormatHint()
        notice?.let {
            Spacer(Modifier.height(8.dp))
            NoticeBanner(notice = it)
        }
        if (errors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ErrorList(errors = errors)
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = FlowBackground,
            border = BorderStroke(1.dp, FlowOutline.copy(alpha = 0.8f))
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                textStyle = TextStyle(
                    color = FlowText,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(FlowPrimary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default
                ),
                singleLine = false,
                maxLines = 10,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = "在这里输入流程步骤，一行一个步骤。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FlowSecondaryText
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text(
                    text = "保存修改",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = onRestoreRequest,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, FlowOutline)
            ) {
                Text(
                    text = "恢复上次保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = FlowPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun FormatHint() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = FlowPrimarySoft.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = "格式示例",
                style = MaterialTheme.typography.labelSmall,
                color = FlowPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "俯卧撑 15次 · 阅读 20分钟 · 拉伸 45秒 x2",
                style = MaterialTheme.typography.bodySmall,
                color = FlowText
            )
            Text(
                text = "一行一个步骤；数字和单位表示目标；x2 表示总共执行两次。",
                style = MaterialTheme.typography.labelSmall,
                color = FlowSecondaryText
            )
        }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "步骤卡片",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FlowText
                    )
                    Text(
                        text = "点击编辑可修改步骤字段",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowSecondaryText
                    )
                }
                Spacer(Modifier.weight(1f))
                if (steps.isNotEmpty()) {
                    TextButton(onClick = onAdd) {
                        Text(
                            text = "添加步骤",
                            color = FlowPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        notice?.let {
            item {
                NoticeBanner(notice = it)
            }
        }

        if (steps.isEmpty()) {
            item {
                EmptyCards(onAdd = onAdd)
            }
        } else {
            itemsIndexed(
                items = steps,
                key = { _, step -> step.id }
            ) { index, step ->
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
            }
        }
    }
}

@Composable
private fun EmptyCards(onAdd: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = FlowBackground,
        border = BorderStroke(1.dp, FlowOutline.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "还没有步骤",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FlowText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "添加第一个计数或计时步骤。",
                style = MaterialTheme.typography.bodySmall,
                color = FlowSecondaryText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAdd,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("添加步骤")
            }
        }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = FlowBackground,
        border = BorderStroke(1.dp, FlowOutline.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(FlowPrimarySoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = FlowPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = FlowText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (step.type == StepType.COUNT) "计数步骤" else "计时步骤",
                        style = MaterialTheme.typography.labelSmall,
                        color = FlowSecondaryText
                    )
                }
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("编辑", style = MaterialTheme.typography.labelMedium, color = FlowPrimary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stepDetail(step),
                style = MaterialTheme.typography.bodyMedium,
                color = FlowText
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "总重复 ${step.repeatCount} 次",
                style = MaterialTheme.typography.labelSmall,
                color = FlowSecondaryText
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardActionButton(label = "复制", onClick = onDuplicate)
                CardActionButton(
                    label = "上移",
                    onClick = onMoveUp,
                    enabled = !isFirst
                )
                CardActionButton(
                    label = "下移",
                    onClick = onMoveDown,
                    enabled = !isLast
                )
                Spacer(Modifier.weight(1f))
                CardActionButton(
                    label = "删除",
                    onClick = onDelete,
                    color = FlowInvalid
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
            .padding(horizontal = 7.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) color else FlowSecondaryText.copy(alpha = 0.45f)
        )
    }
}

private fun stepDetail(step: RoutineStep): String = when (step.type) {
    StepType.COUNT -> {
        val mode = when (step.countMode) {
            CountMode.INCREMENT -> "逐次计数"
            CountMode.COMPLETE -> "整组完成"
            null -> "未设置计数模式"
        }
        "目标 ${step.target ?: 0} 次 · $mode"
    }

    StepType.TIMER -> {
        val category = when (step.category) {
            TimerCategory.NORMAL -> "普通计时"
            TimerCategory.REST -> "休息"
            null -> "未设置分类"
        }
        "计时 ${formatDurationLabel(step.durationMs)} · $category"
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
    val countMode: CountMode,
    val durationSeconds: String,
    val category: TimerCategory,
    val repeatCount: String,
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
            countMode = CountMode.INCREMENT,
            durationSeconds = "60",
            category = TimerCategory.NORMAL,
            repeatCount = "1"
        )

        fun from(step: RoutineStep): CardFormState = CardFormState(
            editingId = step.id,
            type = step.type,
            name = step.name,
            target = (step.target ?: 10).toString(),
            countMode = step.countMode ?: CountMode.INCREMENT,
            durationSeconds = ((step.durationMs ?: 60_000L) / 1_000L).toString(),
            category = step.category ?: TimerCategory.NORMAL,
            repeatCount = step.repeatCount.toString()
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
                        text = "计数模式",
                        style = MaterialTheme.typography.labelMedium,
                        color = FlowSecondaryText
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ChoicePill(
                            label = "逐次计数",
                            selected = form.countMode == CountMode.INCREMENT,
                            onClick = {
                                onChange(form.copy(countMode = CountMode.INCREMENT))
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ChoicePill(
                            label = "整组完成",
                            selected = form.countMode == CountMode.COMPLETE,
                            onClick = {
                                onChange(form.copy(countMode = CountMode.COMPLETE))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                        text = "计时分类",
                        style = MaterialTheme.typography.labelMedium,
                        color = FlowSecondaryText
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ChoicePill(
                            label = "普通计时",
                            selected = form.category == TimerCategory.NORMAL,
                            onClick = {
                                onChange(form.copy(category = TimerCategory.NORMAL))
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ChoicePill(
                            label = "休息",
                            selected = form.category == TimerCategory.REST,
                            onClick = {
                                onChange(form.copy(category = TimerCategory.REST))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedTextField(
                    value = form.repeatCount,
                    onValueChange = { onChange(form.copy(repeatCount = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("步骤重复次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

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

@Composable
private fun BottomActionArea() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = FlowSegmentBackground,
                disabledContentColor = FlowSecondaryText
            )
        ) {
            Text(
                text = "开始执行",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "执行功能暂未开放",
            style = MaterialTheme.typography.labelSmall,
            color = FlowSecondaryText
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
    EditorError.SERIALIZATION_FAILED -> "当前内容暂时无法转换为规范文本，请检查输入。"
    EditorError.WRONG_MODE -> "当前模式下无法执行该操作。"
}
