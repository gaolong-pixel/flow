package com.example.flow.presentation.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.flow.domain.parser.RoutineTextParser

private enum class EditorMode { TEXT, CARDS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowEditorScreen() {
    var routineName by remember { mutableStateOf("我的流程") }
    var text by remember { mutableStateOf("俯卧撑 15次\n休息 60秒\n深蹲 20次") }
    var mode by remember { mutableStateOf(EditorMode.TEXT) }
    val parser = remember { RoutineTextParser() }
    val parsed = remember(text) { parser.parse(text) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("编辑流程") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { /* TODO: Repository save */ }, modifier = Modifier.weight(1f)) { Text("保存") }
                    Button(
                        onClick = { /* TODO: next phase Execution Engine */ },
                        enabled = parsed.steps.isNotEmpty() && !parsed.hasBlockingErrors,
                        modifier = Modifier.weight(1f)
                    ) { Text("开始") }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = routineName, onValueChange = { routineName = it },
                label = { Text("流程名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(mode == EditorMode.TEXT, { mode = EditorMode.TEXT }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("文字") }
                SegmentedButton(mode == EditorMode.CARDS, { mode = EditorMode.CARDS }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("卡片") }
            }
            if (mode == EditorMode.TEXT) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("每行输入一个步骤") },
                    supportingText = { Text("例如：俯卧撑 15次、休息 60秒、阅读 20分钟") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    items(parsed.lines, key = { it.lineNumber }) { result ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(result.raw, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    result.message ?: result.step?.let {
                                        if (it.type.name == "COUNT") "次数 · ${it.target} 次"
                                        else "计时 · ${it.durationMs?.div(1000)} 秒"
                                    }.orEmpty(),
                                    color = if (result.message == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            Text("识别到 ${parsed.steps.size} 个有效步骤")
            parsed.lines.filter { it.message != null }.forEach {
                Text("第 ${it.lineNumber} 行：${it.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
