# Flow 产品规则 V1.1

- Routine：用户保存的流程模板，包含流程名称、流程级重复次数和步骤列表。
- RoutineStep：模板中的一个步骤，包含稳定 ID、类型、名称、类型参数、步骤重复次数和顺序。
- RoutineDraft：编辑期间的唯一事实来源，保存已确认的流程名称、流程级重复次数和步骤列表；它是唯一可被编辑的权威数据。
- RoutineEditorState：编辑会话状态，包裹 RoutineDraft，并保存原始文本 buffer、当前解析结果、最近成功提交的只读步骤快照、编辑模式和同步状态。文本 buffer 和快照都不是第二事实来源。当前实现不包含 lineBindings、draftRevision 或 persistedRevision。
- ExecutionSession：一次执行的不可变状态快照，包含状态、进度、TimerState、records 和时间字段。

完整的文本语法、序列化、状态与 ID 匹配规则见 [文本与卡片双向编辑契约 V1.1](TEXT_EDITING_CONTRACT_V1.md)。

## 节点

- COUNT：`target > 0`，`countMode` 为 INCREMENT / COMPLETE，`durationMs` 为空，`category` 为空。
- TIMER：`durationMs >= 1000` 且为 1000 的整数倍，`category` 为 NORMAL / REST，`target` 和 `countMode` 为空。
- V1 仅支持整秒：最小 1 秒，不支持小数秒和毫秒输入；内部仍使用 `durationMs`。
- MANUAL：V1 不支持，模型枚举、解析器、序列化器、校验、保存、卡片创建和执行路径均不包含 MANUAL。

## 重复

- `Routine.repeatCount` 是**流程级**重复次数，不属于单个步骤，不进入文本语法。
- `RoutineStep.repeatCount` 是**步骤级**总执行次数，包含第一次；`x N` / `× N` 表示该步骤执行 N 次。
- 执行顺序确定为：流程轮次外循环 -> 步骤顺序内循环 -> 步骤轮次内循环；Routine.repeatCount 与 RoutineStep.repeatCount 相乘。

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

## COUNT COMPLETE 与 SKIP

- COMPLETE 仅适用于 RUNNING 的 COUNT + COMPLETE 当前轮次；用户确认整组完成，不伪造逐次计数。
- 确认通过 `completeConfirmed` 表达，随后生成一条 COMPLETED 记录并复用现有调度提交推进。
- COUNT + INCREMENT 和 TIMER 禁止调用 COMPLETE 确认。
- SKIP 适用于当前 RUNNING 步骤的当前轮次，COUNT 和 TIMER 均可；只生成 SKIPPED 记录，不要求计数或计时完成。
- 跳过只推进当前轮次；有剩余轮次进入下一轮，否则进入下一步骤/下一流程，最后轮次结束后会话正常 COMPLETED。
- SKIPPED 不计入 completedCount，完成和跳过统计分别从 records 派生。
- 两者均要求 expectedRevision，失败不改变 Session 或 revision。

## 校验

- 步骤名称的首尾 Unicode 空白不具有业务意义：创建和编辑入口统一 trim；trim 后非空；内部空白保留；名称不得包含换行。
- COUNT 不允许 `category`；TIMER 不允许 `target`/`countMode`。
- TIMER 的 `category` 必须非空；COUNT 的 `countMode` 必须非空。
- `repeatCount >= 1`；`target > 0`；`durationMs >= 1000` 且 `durationMs % 1000 == 0`。
- 步骤 ID 在 Draft 内唯一；`order` 与列表顺序一致。
- 保存前必须验证完整 Draft，而不仅验证解析状态。


## 阶段七执行引擎（已实现）

### 执行模型

- V1 只有 COUNT、TIMER 两种步骤类型；MANUAL 不在模型、解析、序列化或执行中支持。
- COUNT 有 INCREMENT、COMPLETE 两种 countMode。
- REST 是 `TIMER + category=REST`，不是独立步骤类型。
- 执行顺序由 Routine.steps 数组决定，V1 不支持嵌套循环。
- 执行轮次顺序：流程轮次外循环 -> 步骤顺序 -> 步骤自身重复次数。
- 每轮完整执行步骤目标；步骤重复之间没有隐式休息。

### 执行状态

- READY：已创建，尚未开始；可取消。
- RUNNING：正在执行；可暂停、跳过、取消、计数/确认或计时。
- PAUSED：已暂停；只能恢复或取消；不可计数、跳过或推进。
- COMPLETED：所有合法执行轮次已完成；终态，不可恢复或继续执行。
- CANCELLED：用户取消；终态，不可恢复或继续执行；保留已有 records。

空流程不得创建执行会话，Controller 返回 `EMPTY_ROUTINE`。

### COUNT 执行

- INCREMENT 每次点击 currentCount + 1；未达 target 不生成 record，不推进 progress。
- 达到 target 时，计数、COMPLETED record、下一 progress/终态在同一次 Controller 事务内提交。
- COMPLETE 由用户整组确认一次，不伪造 INCREMENT 计数。
- 新轮次 currentCount 清零；达到 target 后不能继续增加。
- 非 RUNNING、非 COUNT 或 countMode 不匹配的操作必须失败且不修改状态。

### SKIP 执行

- SKIP 只跳过当前步骤的当前轮次。
- 当前步骤有剩余轮次时进入下一轮；否则进入下一步骤/下一步流程。
- SKIPPED 不计入 completedCount，completedCount/skippedCount 均从 records 派生。
- TIMER 自然到期优先于 SKIP；提交时 `now >= deadline` 必须生成 COMPLETED，不得生成 SKIPPED。
- 同一轮次只能有一条结果 record。

### TIMER 执行

- Timer deadline 使用单调时钟毫秒；records 使用 Unix epoch 毫秒。
- snapshot 是纯查询，不修改状态。
- 到期判断统一为 `now >= deadline`。
- poll 负责 RUNNING TIMER 的到期状态转换；重复 poll 幂等。
- 暂停时保存剩余时间，恢复时以恢复时刻 + 剩余时间建立新 deadline。
- 同一 PAUSED 周期内 Resume anchor 固定；失败的 prepare/候选构造/commit 不得清除或延长 anchor。
- TIMER 完成/到期后若下一位置是 TIMER，必须在同一个 Controller 推进事务中启动下一 TIMER。
- 旧 runId 的迟到 poll 必须返回 NoChange，不能推进新 run。

### PAUSE / RESUME

- 只有有效 RUNNING TIMER 可以暂停；COUNT 暂停保留 currentCount。
- 暂停期间不消耗 TIMER 剩余时间。
- 只有 PAUSED 可以恢复；恢复不重置 COUNT 进度。
- 恢复使用固定 anchor；重复恢复不得延长计时。
- commit 时已到期必须自然完成，不得发布 PAUSED/旧 run 状态。

### CANCEL

- READY、RUNNING、PAUSED 可取消；取消后不可恢复。
- 保留已有 records。
- TIMER 未到期取消不生成自然完成 record。
- TIMER 已到期时先完成当前 TIMER，再取消剩余流程。
- 当前 TIMER 完成后流程已结束则最终 COMPLETED，否则 CANCELLED。
- 取消后清除 ActiveTimerReference；cancelledAt 仅在 CANCELLED 下非空。

### Session、Snapshot 与 Records

- 开始时保存完整 Routine 快照；原 Routine 后续修改不影响 Session。
- records 唯一键：stepId + routineIteration + stepIteration。
- 经 Controller/Scheduler 正常产生的 records 按规范执行序列排列，且是完整执行序列的连续前缀；低层 `appendRecord` 仅供受控内部/测试使用。
- COMPLETED 会话中每个合法执行轮次恰好一条 record。
- startedAt/endedAt 为 Unix epoch 毫秒，endedAt >= startedAt。
- completedAt 等于最后一条 record 的 endedAt；非 COMPLETED 为空。
- cancelledAt 仅用于 CANCELLED，且等于取消操作的 wall-clock 时间。
- completedCount/skippedCount 从 records 派生，不单独存储。

### Controller、revision 与 ActiveTimerReference

- Controller 是正常产品执行操作的唯一入口；正常 UI 不得直接构造/提交 ExecutionSession。
- 修改操作检查 expectedRevision；成功状态变化 revision +1；失败和查询不增加。
- revision 达到最大值时返回 `REVISION_OVERFLOW`，在 Engine commit 前失败。
- Android ExecutionSession 构造器、`create` 工厂和状态提交方法均为 `internal`；iOS 保持模块内可见。两者都依赖同模块边界，当前单模块工程中 UI 仍应只调用 Controller，不能把 `internal` 视为跨模块安全边界。
- RUNNING/PAUSED TIMER 必须有 ActiveTimerReference；COUNT 不得有。
- reference 必须匹配 sessionId、routineIteration、stepId、stepIteration 和 Engine 当前 runId。
- COMPLETED/CANCELLED 不得残留活动 reference。
- 到期优先级：TIMER 自然到期优先于 SKIP、CANCEL、PAUSE。

### 并发与异常

- Android Controller 全事务使用 `synchronized(lock)`；Engine 锁顺序始终 Controller -> Engine。
- iOS Controller/Engine 使用 `@MainActor`，事务内不得 await。
- 同一 revision 的并发操作最多一个成功。
- 失败操作不得发布 Session、records、progress、reference 或 revision。
- 旧 revision、旧 runId、旧 stepKey 不得影响新状态。
- TIMER 完成/跳过使用 CompletionPlan 的 commit 时刻状态决定最终 record，不允许检查后到 commit 前的时间窗口改变结果。
