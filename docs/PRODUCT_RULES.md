# Flow 产品规则 V1.1

- Routine：用户保存的流程模板，包含流程名称、流程级兼容字段和步骤列表。
- RoutineStep：模板中的一个步骤，包含稳定 ID、类型、名称、目标/时长和顺序。
- RoutineDraft：编辑期间的唯一事实来源，保存已确认的流程名称、兼容字段和步骤列表；它是唯一可被编辑的权威数据。
- RoutineEditorState：编辑会话状态，包裹 RoutineDraft，并保存原始文本 buffer、当前解析结果、最近成功提交的只读步骤快照、编辑模式和同步状态。文本 buffer 和快照都不是第二事实来源。当前实现不包含 lineBindings、draftRevision 或 persistedRevision。
- PlanRunController / RunCoordinator：当前运行实例；运行状态只存在于内存，不写入 PlanStore，也不产生执行历史。

完整的文本语法、序列化、状态与 ID 匹配规则见 [文本与卡片双向编辑契约 V1.1](TEXT_EDITING_CONTRACT_V1.md)。

## 节点

- COUNT：`target > 0`，`countMode` 为 INCREMENT / COMPLETE，`durationMs` 为空，`category` 为空。
- TIMER：`durationMs >= 1000` 且为 1000 的整数倍，`category` 为 NORMAL / REST，`target` 和 `countMode` 为空。
- V1 仅支持整秒：最小 1 秒，不支持小数秒和毫秒输入；内部仍使用 `durationMs`。
- MANUAL：V1 不支持，模型枚举、解析器、序列化器、校验、保存、卡片创建和执行路径均不包含 MANUAL。

## 重复字段（兼容说明）

- `Routine.repeatCount`、`RoutineStep.repeatCount`、`countMode`、`TimerCategory` 等字段仍存在于领域模型和编辑/解析兼容层。
- 文本模式仍接受历史 `x N`、`[计数=完成]`、`[分类=休息]` 输入，以保持旧文本可读取。
- 0.1.0 的运行执行不展开流程级或步骤级重复；当前运行按节点顺序执行一遍。
- 计划卡片编辑器和当前运行 UI 不暴露流程级/步骤级重复、计数模式或计时分类。
- 计划 JSON 只保存运行所需的步骤名称、类型、target、durationMs 等字段。

## 文本与卡片

- 文字模式与卡片模式完全平等，均支持编辑。
- 两种模式共同操作同一个 RoutineDraft；RoutineDraft 是已确认数据的唯一事实来源。
- 文字模式通过解析器把文本转换为候选步骤；只有所有非空行均为 VALID 时，才允许提交到 RoutineDraft。
- 卡片模式直接修改 RoutineDraft；修改成功后通过序列化器生成规范化文本。
- 两种模式之间必须语义无损：步骤字段、顺序和稳定 ID 不丢失；已确认不要求保留原始空格、单位写法等文本格式。
- 文本语法只表达步骤列表，不表达流程名称和流程级重复。
- 名称与数量之间允许没有空格：`俯卧撑 15次` 与 `俯卧撑15次` 均合法。
- 首版时长只支持整秒；小数秒和毫秒输入为 INVALID。
- 不可表示的步骤（MANUAL、非整秒时长、非法字段等）必须阻止序列化，不得静默截断或舍入。

## 稳定步骤 ID

- 步骤 ID 在步骤创建时生成，之后不得因文本重新解析、模式切换或普通属性修改而变化。
- 解析器不得生成持久化 ID 或 `order`；ID 由提交/草稿层分配。
- 文本重新解析时，按“完全匹配优先、行替换次优先、剩余新建”的规则尽可能保留 ID。
- 两个完全相同的步骤按出现顺序匹配；它们之间交换位置属于不可观察差异。
- 卡片模式修改 COUNT ↔ TIMER 类型时，**必须保留步骤 ID**。
- 文本模式无法可靠匹配类型变更时，**允许创建新 ID**；卡片模式不受此限制。
- `lastCommittedSteps` 只是提交后的只读快照，不是可编辑数据源，也不是第二事实来源。

## 解析状态

- `EMPTY`：空行或仅空白行；不产生步骤，不阻断提交。
- `VALID`：完整符合语法与语义，可生成候选步骤。
- `INCOMPLETE`：输入可能尚未完成，例如裸名称 `俯卧撑`/`阅读`、`俯卧撑 15`、`俯卧撑 15次 x`、未闭合属性。
- `INVALID`：明确不符合语法或语义，例如未知单位、未知属性、数值非法、属性类型不匹配、小数秒/毫秒。
- 解析器逐行返回结果，不得因一行错误丢弃其他行，也不得丢弃空行而改变行号。
- 存在 INCOMPLETE 或 INVALID 时：`syncStatus = INVALID`；保留用户原始文本，保留最近一次成功解析的步骤集合（只读快照），不得部分提交，不得进入卡片模式；修复全部错误后才允许提交。
- `commitText()` 在存在阻断错误时返回 `TEXT_BLOCKED`，不修改 Draft 或快照。

## 编辑同步状态

- `SYNCED`：文本与 Draft 语义一致。
- `DIRTY`：存在尚未提交的合法文本修改。
- `INVALID`：当前文本存在 INCOMPLETE 或 INVALID 阻断错误。

同步状态只表达文本与 Draft 的同步关系，不得与错误类型混用。解析行状态的 `INVALID` 与同步状态的 `INVALID` 作用域不同，不能互相替代。

错误类型独立于同步状态，当前实现包括：

`WRONG_MODE`、`TEXT_BLOCKED`、`DRAFT_INVALID`、`INVALID_STEP`、`DUPLICATE_STEP_ID`、`STEP_NOT_FOUND`、`INVALID_MOVE`、`INVALID_REPEAT_COUNT`、`SERIALIZATION_FAILED`。

## 编辑一致性

- `RoutineDraft` 保存已确认数据：模板 ID、名称、流程级重复次数、步骤列表。
- `RoutineEditorState` 保存编辑期状态：`mode`、`draft`、`textBuffer`、`parseResult`、`syncStatus`、`lastCommittedSteps`、`lastCommittedText`。
- 文本提交是全有或全无：只要有一行阻断错误，其他合法行的修改也不提交。
- 合法文本提交成功后，`textBuffer` 被替换为规范化文本，`syncStatus = SYNCED`，并刷新只读快照。
- 卡片编辑成功后立即更新文本 buffer；文本被替换为规范化文本，空行和格式会被规范化，`syncStatus = SYNCED`。
- 卡片类型变更按 ID 更新，清理旧类型字段并写入新类型字段；ID 不变。
- 模式切换不得重新分配 ID；`TEXT → CARDS` 仅允许在 `SYNCED` 且无阻断错误时进行。
- 空草稿、仅空行文本是合法状态；卡片模式显示空状态。
- 卡片操作或序列化失败时，`EditorController` 返回独立错误码，Draft、文本、快照和模式保持不变。

## 数据校验

- 流程名称去除首尾 Unicode 空白后不得为空，最多 60 个字符；保存失败不得覆盖已有名称。
- 步骤名称去除首尾 Unicode 空白后不得为空。
- COUNT：`target > 0`；TIMER：`durationMs >= 1000` 且为 1000 的整数倍。
- 步骤 ID 在计划内唯一；列表顺序就是执行顺序。
- 当前卡片 UI 不暴露旧兼容字段；加载旧计划时由 PlanStore 归一化为当前运行所需字段。
- 保存前必须验证完整 Draft，而不仅验证解析状态。

## 当前运行语义（0.1.0）

- 每次运行从计划第一个节点开始，按 `Routine.steps` 顺序执行。
- 当前运行实例由 `RunCoordinator` 持有；同一时刻只允许一个运行实例。
- 运行状态不写入 PlanStore；进程结束后不恢复运行，也不生成执行历史。
- 计划列表和编辑入口在运行期间不可达；完成或退出后回到首页。

### 节点状态

- READY：当前节点待开始。
- RUNNING：COUNT 已开始 / TIMER 已建立 deadline。
- PAUSED：仅 TIMER 可暂停，剩余时间冻结。
- EXPIRED：TIMER 到期，固定显示 00:00；不自动完成节点。

### COUNT

- READY 点击“开始”调用 `startNode`。
- RUNNING 点击“完成当前节点”调用 `completeNode`，推进到下一节点。
- 目标次数只作为执行提示，不做逐次计数记录；不生成执行历史或 records。

### TIMER

- READY 点击“开始计时”建立基于单调时钟的 deadline。
- RUNNING 可暂停；PAUSED 可继续；剩余时间由 deadline 派生，UI ticker 只触发重算。
- 到期进入 EXPIRED，不自动完成；用户必须点击“完成当前节点”。
- 可在 RUNNING/PAUSED 提前完成；完成后推进到下一节点。

### 单节点循环

- 每次开始运行默认关闭；启动设置不提供循环入口。
- 运行页可开关“当前节点循环”。
- 循环开启时，完成当前节点会重置当前节点为 READY，不增加已完成节点数，也不自动开始下一轮。
- 循环只作用于当前节点；推进到下一节点时自动关闭。
- 关闭循环本身不推进节点，也不重置剩余时间。

### 退出与结果

- 主动退出返回 `completedNodes / totalNodes` 作为已完成进度；当前节点不计入。
- 最后节点完成后产生正常完成结果；两种结果页都只用于本次运行的即时反馈，不持久化。
- 文本 buffer、编辑器快照、PlanStore 均不从运行状态写入。

### 历史执行引擎

阶段七的 `ExecutionSession`、`ExecutionScheduler`、`ExecutionTimerEngine`、records、SKIP、revision 和 ActiveTimerReference 已从当前实现删除。历史设计记录保留在 [phase7/README.md](phase7/README.md)，当前运行语义以本节和 [RUN_FEATURE.md](RUN_FEATURE.md) 为准。
