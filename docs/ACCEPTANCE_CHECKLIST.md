# 第一阶段验收

对应契约：[文本与卡片双向编辑契约 V1.1](TEXT_EDITING_CONTRACT_V1.md)。

## 解析器：基础

- [ ] `俯卧撑 15次` -> COUNT / target=15 / INCREMENT
- [ ] `俯卧撑15次` -> COUNT / target=15（名称与数量无空格）
- [ ] `阅读 20分钟` -> TIMER / durationMs=1200000 / NORMAL
- [ ] `阅读20分钟` -> TIMER / durationMs=1200000（无空格）
- [ ] `休息 60秒` -> TIMER / REST
- [ ] `拉伸 45秒 x 2` -> TIMER / repeatCount=2
- [ ] `拉伸45秒x2` -> TIMER / repeatCount=2（无空格）
- [ ] `俯卧撑 15` -> INCOMPLETE
- [ ] `俯卧撑`、`阅读` -> INCOMPLETE（裸名称）
- [ ] `俯卧撑 abc次` -> INVALID
- [ ] 空行忽略；非法行不影响其他有效行
- [ ] 空行/仅空白行返回 EMPTY，不阻断提交
- [ ] 解析器不生成持久化 ID，不分配 order
- [ ] 解析器逐行返回结果，不因一行错误丢弃其他行
- [ ] CRLF/CR 换行下错误行号不偏移

## 解析器：V1 属性与优先级

- [ ] `俯卧撑 15次 [计数=完成]` -> COUNT / COMPLETE
- [ ] `俯卧撑 15次 x 2 [计数=完成]` -> COMPLETE / repeatCount=2
- [ ] `拉伸 45秒 x 2 [分类=休息]` -> REST / repeatCount=2
- [ ] `休息 60秒 [分类=普通]` -> NORMAL（显式覆盖名称推断）
- [ ] `阅读 20分钟 [分类=休息]` -> REST（显式覆盖名称推断）
- [ ] `休息 60秒 [分类=休息]` -> REST（显式与推断相同，合法）
- [ ] `阅读 20分钟 [分类=普通]` -> NORMAL（显式与推断相同，合法）
- [ ] COUNT 使用 `[分类=...]` -> INVALID
- [ ] TIMER 使用 `[计数=完成]` -> INVALID
- [ ] 未知属性键/值 -> INVALID
- [ ] 重复属性 -> INVALID
- [ ] 属性出现在数量之后、重复之前 -> INVALID
- [ ] 属性后存在非空白内容 -> INVALID
- [ ] 属性内部空白、全角方括号 -> INVALID

## 解析器：重复、单位与空白

- [ ] `x N`、`× N`、`X N`、`xN` 均解析为 repeatCount=N
- [ ] 未写重复次数时 repeatCount=1
- [ ] `x 0` 和 `x 负数` -> INVALID
- [ ] `秒`、`分钟`、`分`、`小时`、`时` 正确换算
- [ ] 名称与数量之间允许无空格；`俯卧撑15次` 合法
- [ ] 数量与单位之间允许空白；`俯卧撑 15 次` 合法
- [ ] 全角空格 U+3000、制表符可作为空白
- [ ] 前导零按数值解析并规范化（待确认是否保留该宽松规则）
- [ ] 超过 target/repeatCount 32 位范围 -> INVALID
- [ ] 时长乘法溢出 -> INVALID，不崩溃、不回绕
- [ ] `1秒` 合法；`0.5秒`、`1.5秒`、`1500毫秒` -> INVALID

## 解析器：非法、未完成与空输入

- [ ] `俯卧撑 15` -> INCOMPLETE
- [ ] `俯卧撑 15次 x` -> INCOMPLETE
- [ ] `俯卧撑 15次 [` / `[计数=` / `[计数=完成` -> INCOMPLETE
- [ ] `俯卧撑 15个` -> INVALID
- [ ] `俯卧撑 0次`、`俯卧撑 -5次` -> INVALID
- [ ] `阅读 1.5秒`、`阅读 0.5分钟`、`阅读 1500毫秒` -> INVALID
- [ ] `阅读 1.5分钟`、`阅读 十五分钟` -> INVALID
- [ ] `俯卧撑 15次 [计数=递增]` -> INVALID（待确认）
- [ ] `""`、`"   "`、`"\t"` -> EMPTY
- [ ] 一行最多解析一个步骤；V1 不提供多步骤分隔符
- [ ] 数量中的全角数字、属性中的全角等号 -> INVALID（待确认是否归一化）

## 文本 -> RoutineDraft 提交

- [ ] 所有非空行 VALID 时才提交步骤
- [ ] 有 INCOMPLETE/INVALID 时不修改 Draft
- [ ] 有 INCOMPLETE/INVALID 时编辑同步状态为 INVALID
- [ ] 有错误时保留原始文本，非法行不被删除
- [ ] 有错误时保留最近一次成功解析的步骤集合（只读快照）
- [ ] `lastCommittedSteps` 只读，不作为编辑入口或第二事实来源
- [ ] 有错误时不允许进入卡片模式
- [ ] 修复全部错误后一次性提交全部合法行
- [ ] 不因一行错误而部分提交其他合法行
- [ ] 空文本/仅空行提交空步骤列表，卡片模式显示空状态
- [ ] 提交后步骤顺序以列表顺序为准（当前模型不持久化独立 order）
- [ ] 解析成功但领域校验失败时不提交，并显示阻断错误

## 编辑同步状态与错误码

- [ ] 初始状态为 SYNCED
- [ ] 合法但未提交的文本修改为 DIRTY
- [ ] 存在 INCOMPLETE/INVALID 行时为 INVALID
- [ ] 文本提交成功后为 SYNCED，且 textBuffer 为规范文本
- [ ] 卡片操作成功后立即为 SYNCED，且 textBuffer 为规范文本
- [ ] DIRTY/INVALID 时禁止 `TEXT → CARDS`
- [ ] SYNCED 时允许 `TEXT → CARDS`
- [ ] `CARDS → TEXT` 序列化成功后切换，失败时状态不变
- [ ] 同步状态只使用 SYNCED / DIRTY / INVALID
- [ ] 错误码独立于同步状态：TEXT_BLOCKED、DRAFT_INVALID、INVALID_STEP、DUPLICATE_STEP_ID、STEP_NOT_FOUND、INVALID_MOVE、INVALID_REPEAT_COUNT、SERIALIZATION_FAILED、WRONG_MODE
- [ ] 任一失败操作不修改 Draft、textBuffer、lastCommittedSteps、parseResult、mode 或 syncStatus
- [ ] 当前实现不维护 lineBindings、draftRevision、persistedRevision
- [ ] 解析行状态的 INVALID 与编辑同步状态的 INVALID 按各自作用域使用，不混用

## 步骤 ID 稳定性

- [ ] 文本内容不变、重新解析，ID 不变
- [ ] 无空格输入与带空格输入之间切换格式后 ID 不变
- [ ] 文字/卡片模式切换，ID 不变
- [ ] 卡片修改普通字段，ID 不变
- [ ] 文本中修改单行名称/target/duration/repeat/category/countMode，ID 不变
- [ ] 文本中调整行顺序，ID 随步骤移动且不变
- [ ] 文本中新增行，新步骤获得新 ID，其他 ID 不变
- [ ] 文本中删除行，仅被删除步骤消失，其他 ID 不变
- [ ] 两个相同步骤按出现顺序匹配，不产生重复 ID
- [ ] 同时移动并修改同一行时记录尽力结果（不强制稳定）
- [ ] 卡片模式 COUNT ↔ TIMER 类型修改后 ID 不变
- [ ] 文本模式 COUNT ↔ TIMER 类型修改允许生成新 ID，不破坏其他步骤 ID
- [ ] ID 在 Draft 内唯一；更新不得替换 id

## 卡片 -> 文本与序列化

- [ ] 卡片新增步骤后文本立即出现对应行
- [ ] 卡片修改字段后文本立即更新
- [ ] 卡片修改 COUNT↔TIMER 类型后 ID 不变且字段合法
- [ ] 卡片删除步骤后文本对应行消失
- [ ] 卡片排序后文本行顺序同步
- [ ] COUNT COMPLETE 序列化为 `[计数=完成]`
- [ ] TIMER REST 且名称不含“休息”时序列化为 `[分类=休息]`
- [ ] TIMER NORMAL 且名称含“休息”时序列化为 `[分类=普通]`
- [ ] 推断一致的分类不输出冗余属性
- [ ] repeatCount=1 时省略 `x 1`；>1 时输出 ` x N`
- [ ] 时长按最大整除单位规范化（120 秒 -> `2分钟`，90 秒 -> `90秒`）
- [ ] `x` 统一小写；`分`/`时` 统一为 `分钟`/`小时`
- [ ] 序列化输出无空行、无末尾换行
- [ ] 序列化是原子的；任一步骤不可表示时整体失败
- [ ] 序列化幂等：再次解析并序列化结果一致
- [ ] 非整秒/毫秒时长被拒绝并提示（首版仅整秒）
- [ ] MANUAL 无法创建/解析/序列化/保存
- [ ] 流程名称和流程级重复不进入文本

## 双向无损

- [ ] Draft -> 文本 -> Draft 后全部步骤字段一致
- [ ] 文本 -> Draft -> 文本 后步骤字段一致
- [ ] 双向转换后步骤顺序一致
- [ ] 双向转换后步骤 ID 在规则允许范围内一致
- [ ] 卡片编辑后再次进入文字模式，文本与卡片一致
- [ ] 文字模式修复错误后进入卡片模式，数据一致
- [ ] 空草稿双向转换保持为空
- [ ] 规范化导致的格式变化（空格/单位/属性）不视为数据丢失

## 编辑器 UI

- [ ] 可修改流程名称
- [ ] 文字输入实时显示解析结果
- [ ] 错误显示行号和原因（Android 与 iOS 均需）
- [ ] 存在 INCOMPLETE/INVALID 时卡片入口禁用并说明原因
- [ ] 卡片增删改排序只修改同一份 Draft
- [ ] COUNT 的 countMode 可在卡片编辑中设置
- [ ] TIMER 的 category 可在卡片编辑中设置
- [ ] 步骤重复次数可在卡片编辑中设置
- [ ] 存在无效或未完成行时禁止启动
- [ ] 模式切换不丢步骤、不重置 ID
- [ ] 卡片编辑后切回文字模式，文本已同步
- [ ] 空状态、仅空行状态可用

## 持久化

- [ ] 保存后重启仍存在
- [ ] 编辑已有流程不会重复创建模板
- [ ] 保存失败/序列化失败时有明确提示，不静默丢数据
- [ ] 步骤 ID、顺序、countMode、category、repeatCount 均持久化
- [ ] 流程级 repeatCount 与步骤级 repeatCount 分列保存
- [ ] 后续执行快照与模板修改隔离


## 执行控制器

- [ ] ExecutionSession 的 currentRoundStartedAt 生命周期符合 READY/RUNNING/PAUSED/COMPLETED/CANCELLED 规则
- [ ] start 初始化当前轮次开始时间
- [ ] pause/resume 保留当前轮次开始时间
- [ ] cancel/最终完成清空当前轮次开始时间
- [ ] 执行控制器空流程不创建 READY 会话
- [ ] 控制器 revision 成功操作递增、失败和查询不递增
- [ ] stale expectedRevision 被拒绝且不修改状态
- [ ] 本轮结果时间早于 currentRoundStartedAt 时被拒绝且不修改状态
- [ ] 时钟倒退时取消操作被拒绝且 revision 不变
- [ ] 控制器生命周期双端语义一致


## COUNT INCREMENT 执行

- [ ] 未达目标只增加 currentCount，不生成记录
- [ ] 达到目标时原子生成一条 COMPLETED 记录并推进
- [ ] 下一轮/下一步骤/下一流程的 currentCount 清零
- [ ] 最后一轮完成后会话为 COMPLETED
- [ ] 暂停、取消、完成状态禁止计数
- [ ] 非 COUNT 和 COUNT + COMPLETE 禁止调用 INCREMENT
- [ ] 过期 revision、非法计数和提交失败不改变 Session 或 revision


## COUNT COMPLETE 与 SKIP

- [ ] COUNT + COMPLETE 确认生成一条 COMPLETED 记录并推进
- [ ] COMPLETE 不修改 currentCount、不伪造逐次计数
- [ ] COUNT + INCREMENT 和 TIMER 禁止调用 COMPLETE 确认
- [ ] 重复确认和过期 revision 被拒绝
- [ ] SKIP 支持 COUNT INCREMENT、COUNT COMPLETE 和 TIMER
- [ ] SKIP 只跳过当前轮次并遵循步骤/流程重复顺序
- [ ] SKIPPED 不计入 completedCount，skippedCount 正确增加
- [ ] COMPLETE/SKIP 失败时 Session、records、progress、status、时间字段和 revision 不变

---

# 阶段七执行引擎验收（当前工作区）

本清单依据阶段七代码/测试提交 `bcf48a6b577c119f2031aa3f86adaed5de45bdcf` 的 Android/iOS 源码和实际测试结果。
执行引擎代码已实现；以下为当前验收证据。

## 领域模型与执行状态

- [x] COUNT / TIMER 类型，COUNT INCREMENT/COMPLETE，REST = TIMER + REST
- [x] MANUAL 不在 V1 模型/解析/序列化/执行中支持
- [x] READY / RUNNING / PAUSED / COMPLETED / CANCELLED 状态及终态约束
- [x] 空流程拒绝创建 Session
- [x] Routine snapshot 与后续 Routine 修改隔离
- [x] currentRoundStartedAt、startedAt、completedAt、cancelledAt 生命周期
- 证据：Android/iOS ExecutionSessionTests、ExecutionControllerTests、ExecutionTimerStateTests

## Scheduler 与 records

- [x] 步骤顺序由 steps 数组决定
- [x] Scheduler 重新计算推进，不信任外部 SchedulePlan
- [x] records 唯一键 stepId + routineIteration + stepIteration
- [x] COMPLETED 会话 records 覆盖全部合法轮次
- [x] completedAt = 最后一条 record.endedAt
- [x] completedCount / skippedCount 从 records 派生
- 证据：ExecutionSchedulingTests、ExecutionEngineIntegrationTest、iOS 集成场景

## COUNT

- [x] INCREMENT 未达 target 只增加 currentCount
- [x] 达标自动生成 COMPLETED 并推进
- [x] COMPLETE 只确认一次且不伪造计数
- [x] 新轮次 currentCount 清零
- [x] 非法 COUNT 操作失败且原子
- 证据：ExecutionCountingTests、ExecutionCompleteSkipTests、集成测试

## SKIP

- [x] COUNT INCREMENT / COMPLETE / TIMER 均可跳过当前轮次
- [x] 步骤剩余轮次进入下一轮，否则进入下一步骤/流程
- [x] SKIPPED 不计入 completedCount
- [x] TIMER 到期优先于 SKIP；commit 时 now >= deadline -> COMPLETED
- [x] 不生成重复 records
- 证据：ExecutionCompleteSkipTests、ExecutionEngineIntegrationTest、iOS 集成/并发测试

## TIMER 基础与生命周期

- [x] deadline 使用单调时钟，records 使用 Unix epoch 毫秒
- [x] snapshot 纯查询
- [x] poll 负责到期转换，重复 poll 幂等
- [x] now >= deadline 统一处理
- [x] pause/resume/cancel 到期优先级
- [x] Resume anchor 同周期固定
- [x] TimerRunId 隔离，旧 run 事件不影响新 run
- [x] Plan 不可伪造，重复提交安全
- [x] TIMER→TIMER 在同一推进事务内启动
- 证据：ExecutionTimerEngineTests、ExecutionControllerTimerLifecycleTests、并发/集成测试

## PAUSE / RESUME / CANCEL

- [x] COUNT 暂停保留进度
- [x] TIMER 暂停保存剩余时间，暂停期间不消耗
- [x] 恢复使用固定 anchor，commit 到期自然完成
- [x] 未到期 CANCEL 不伪造完成记录
- [x] 已到期 CANCEL 先完成当前 TIMER 再取消剩余流程
- [x] 完成后流程结束则 COMPLETED，否则 CANCELLED
- [x] 终态不可恢复，reference 清空，cancelledAt 合法
- 证据：ExecutionControllerTimerLifecycleTests、iOS TimerStartTests、集成测试

## 并发与异常

- [x] Android synchronized 覆盖完整事务
- [x] iOS @MainActor 覆盖完整事务
- [x] 同一 revision 并发最多一个成功
- [x] 旧 revision / 旧 runId 隔离
- [x] revision 溢出安全
- [x] prepare/commit/Session/Scheduler 失败原子性
- [x] 无 sleep/随机延迟依赖
- 证据：ExecutionControllerConcurrencyTest（Android 14 tests）、iOS 并发测试

## ActiveTimerReference

- [x] RUNNING/PAUSED TIMER 有匹配 reference
- [x] COUNT 无活动 reference
- [x] 终态清除 reference
- [x] sessionId/routineIteration/stepId/stepIteration/runId 完整校验
- 证据：TimerBindingTests、集成不变量检查、生产 validateActiveTimerReference

## 双端一致性与集成

- [x] COUNT/TIMER 混合、步骤重复、流程重复、SKIP、PAUSE/RESUME、CANCEL
- [x] 综合流程 G：routine=2、step repeat=2、SKIP、PAUSE/RESUME、8 records、revision=15
- [x] Android/iOS 最终状态、progress、records、TimerState、reference、revision 语义一致
- [x] Android 全量 350 tests、0 failures
- [x] iOS 全量 351 tests、0 failures
- [x] Parser 88/88 双端
- [x] Android assembleDebug 成功
- [x] iOS Simulator build/test 成功
- 证据：ExecutionEngineIntegrationTest、iOS 集成场景、当前全量测试输出

## 不属于阶段七、留待后续

- [ ] 执行 UI 接入
- [ ] 本地持久化与进程重启恢复
- [ ] 后台/锁屏/设备重启计时恢复
- [ ] 执行历史展示
- [ ] 真实设备交互验收
