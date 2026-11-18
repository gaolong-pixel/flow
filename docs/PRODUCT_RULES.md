# Flow 产品规则 V1.1

- Routine：用户保存的流程模板，包含流程名称、流程级重复次数和步骤列表。
- RoutineStep：模板中的一个步骤，包含稳定 ID、类型、名称、类型参数、步骤重复次数和顺序。
- RoutineDraft：编辑期间的唯一事实来源，保存已确认的流程名称、流程级重复次数和步骤列表；它是唯一可被编辑的权威数据。
- RoutineEditorState：编辑会话状态，包裹 RoutineDraft，并保存原始文本 buffer、当前解析结果、最近成功提交的只读步骤快照、编辑模式和同步状态。文本 buffer 和快照都不是第二事实来源。当前实现不包含 lineBindings、draftRevision 或 persistedRevision。
- ExecutionSession：后续阶段的运行实例。

完整的文本语法、序列化、状态与 ID 匹配规则见 [文本与卡片双向编辑契约 V1.1](TEXT_EDITING_CONTRACT_V1.md)。

## 节点

- COUNT：`target > 0`，`countMode` 为 INCREMENT / COMPLETE，`durationMs` 为空，`category` 为空。
- TIMER：`durationMs >= 1000` 且为 1000 的整数倍，`category` 为 NORMAL / REST，`target` 和 `countMode` 为空。
- V1 仅支持整秒：最小 1 秒，不支持小数秒和毫秒输入；内部仍使用 `durationMs`。
- MANUAL：V1 不支持。解析器、序列化器、校验、保存和卡片创建路径都必须拒绝 MANUAL；模型中的枚举可暂时保留为预留值。

## 重复

- `Routine.repeatCount` 是**流程级**重复次数，不属于单个步骤，不进入文本语法。
- `RoutineStep.repeatCount` 是**步骤级**总执行次数，包含第一次；`x N` / `× N` 表示该步骤执行 N 次。
- 两者在执行阶段如何组合（相乘、外循环等）尚未确定，属于执行阶段，不在 V1 编辑器契约内。

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

## 执行控制器与轮次时间

- `ExecutionSession.currentRoundStartedAt` 是当前步骤轮次的开始时间，单位为 Unix epoch 毫秒。
- READY、COMPLETED、CANCELLED 下该字段为空；RUNNING、PAUSED 下必须非空且不早于会话 `startedAt`。
- 暂停和继续保留当前轮次开始时间；取消和最终完成清空该字段。
- 步骤完成或跳过并推进时，下一轮开始时间取本轮记录的 `endedAt`；全部完成时清空。
- 提交本轮结果时，`startedAt` 和 `endedAt` 都不得早于当前 `currentRoundStartedAt`；非法时间不得推进会话。
- `ExecutionStepRecord.startedAt`/`endedAt` 表示当前轮次的实际时间跨度，暂停时间包含在跨度内；本阶段不统计有效运动时长。
- `ExecutionController.revision` 是控制器操作版本，不属于 ExecutionSession。成功状态变化递增一次；失败和查询不递增；修改操作必须携带 `expectedRevision`。

## COUNT INCREMENT 执行

- 仅 RUNNING 的 COUNT + INCREMENT 当前轮次允许计数。
- 每次计数只增加当前轮次 `currentCount`；未达到目标时不生成 `ExecutionStepRecord`，不推进执行位置。
- 达到目标时，计数、COMPLETED 记录、下一 progress/终态由同一次领域操作原子提交。
- 完成后进入下一轮、下一步骤或下一流程时，`currentCount` 从 0 开始。
- 暂停、取消、完成状态禁止计数；COUNT + COMPLETE、非 COUNT 步骤禁止调用 INCREMENT。
- 过期 revision、非法计数状态或调度提交失败不得改变 Session 或 revision。

## 校验

- 步骤名称的首尾 Unicode 空白不具有业务意义：创建和编辑入口统一 trim；trim 后非空；内部空白保留；名称不得包含换行。
- COUNT 不允许 `category`；TIMER 不允许 `target`/`countMode`。
- TIMER 的 `category` 必须非空；COUNT 的 `countMode` 必须非空。
- `repeatCount >= 1`；`target > 0`；`durationMs >= 1000` 且 `durationMs % 1000 == 0`。
- 步骤 ID 在 Draft 内唯一；`order` 与列表顺序一致。
- 保存前必须验证完整 Draft，而不仅验证解析状态。
