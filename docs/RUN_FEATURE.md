# 第二阶段：计划运行功能

本文记录 Android 与 iOS 的计划运行实现、双端一致的产品语义、通知/后台策略、测试与已知限制。

## 0. 2026-11-21 执行滚筒 v2

- 运行页从单节点全屏改为纵向 3D 流程滚筒：当前节点展开，其他待执行/暂缓节点紧凑排列，按与视口的距离做缩放、透明度、旋转和透视。
- 调度扩展：每个节点有 `PENDING / CURRENT / DEFERRED / COMPLETED` 调度状态；`selectNode` 在不丢失进度的前提下切换执行目标。`SKIPPED` 仅作为旧内存快照的兼容值保留，不再产生新的跳过状态。
- COUNT 保持“目标次数 + 开始/完成”语义，不做逐次计数；开始后显示为目标次数服务的平静呼吸等待，完成由用户主动点击。
- 切换离开 RUNNING TIMER 时冻结剩余时间并标记暂缓；切回后恢复 PAUSED，由用户继续。
- 完成节点移入默认折叠的“已完成”区域；运行页不再提供独立跳过入口，节点切换由点击滚筒卡片完成，原节点自动暂停/保留进度。
- 启动前倒计时改为「准备好了吗？」→ 3 → 2 → 1，约 4.6 秒；倒计时期间提供“取消”，取消只返回准备页，不启动 Session。
- 新建流程先进入未保存草稿；至少添加一个有效节点后才允许保存，首页不会因打开创建页产生空流程。
- 未新增进程级 Session 持久化；运行状态仍只在当前进程内。

## 0.1 2026-11-19 UI 修正

- 编辑页顶部新增流程名称输入框；名称去除首尾空白、非空、最多 60 个 Unicode 字符，随保存与节点一起持久化。
- 单节点循环从启动设置移到运行页：启动设置不再显示循环选择，每次启动默认关闭；运行页保留完整循环功能和既有语义。

## 1. 产品闭环

```text
计划列表
  └─ 点击“开始” → 启动设置（不提供循环选择）
       └─ 开始运行 → 「准备好了吗？」→ 3 → 2 → 1（约 4.6 秒）→ 纵向 3D 滚筒运行页
            ├─ 当前 COUNT：开始 → 平静呼吸等待 → 完成本节点
            ├─ 当前 TIMER：开始 → 倒计时 ⇄ 暂停/继续 → 提前完成 / 到期 → 完成本节点
            ├─ 点击其他待执行/暂缓节点 → 切换执行目标
            │    └─ 原 TIMER RUNNING → 冻结为 PAUSED + DEFERRED
            │    └─ 原 COUNT 保持 RUNNING；未开始的节点保持 PENDING
            ├─ 当前节点循环：开启后完成 → 重置当前节点（不计完成数）
            ├─ 已完成区域：折叠 / 展开；完成节点从滚筒移除
            ├─ 退出 → 退出确认 → 已退出流程页（X/Y）→ 首页
            └─ 所有节点完成 → 结果页 → 首页
```

运行期间不提供编辑入口；计划列表在运行页背后不可达，退出/结束前不能启动第二个计划。

## 2. 运行状态机

双端使用同一状态语义，独立实现。

### 2.1 执行状态（节点级）

- 节点执行状态：`READY`、`RUNNING`、`PAUSED`、`EXPIRED`。
- `READY`：当前节点待开始；TIMER 显示完整时长。
- `RUNNING`：COUNT 已开始 / TIMER 已建立单调时钟 deadline。
- `PAUSED`：TIMER 剩余时间冻结，继续时从剩余时间建立新 deadline。
- `EXPIRED`：TIMER 归零；**不会自动完成节点**，必须用户点击“完成本节点”。
- 所有节点完成，或用户主动结束后运行结束；循环迭代不增加完成节点数。
- 每次启动默认循环关闭；启动设置不提供循环入口，运行页可随时开关。推进到下一节点时循环自动关闭（“循环只作用于当前节点”）。
- 关闭循环本身不推进节点，不重置剩余时间；只有点击完成才推进。
- 循环完成时：当前节点回到 `READY`，TIMER 重置为完整时长，COUNT 重新展示目标数量，不自动开始下一轮。
- 退出返回 `completedNodes / totalNodes` 用于进度反馈；运行实例随之销毁。

### 2.2 调度状态（流程级）

- 调度状态：`PENDING`、`CURRENT`、`DEFERRED`、`COMPLETED`；`SKIPPED` 是旧快照兼容值，不再由 UI/控制器产生。
- 同一时刻只有一个 `CURRENT`；初始为第一个节点，恢复时按真实快照恢复，不重置到第一个。
- 点击其他 `PENDING`/`DEFERRED` 节点调用 `selectNode`：
  - 原节点未开始 → 回到 `PENDING`；
  - 原 TIMER 正在运行 → 计算剩余时间、清除 deadline、置 `PAUSED`、调度状态 `DEFERRED`；
  - 原 COUNT 已开始 → 保留 `RUNNING`，调度状态 `DEFERRED`；
  - 目标节点可以是 `PENDING / DEFERRED`；若读取到旧值的 `SKIPPED`，按 `DEFERRED` 兼容处理并恢复其保留的执行状态（TIMER `PAUSED` / `EXPIRED`，COUNT `RUNNING` / `READY`）。
- `completeNode`：标记 `COMPLETED`、完成数 +1、从滚筒移除；随后按计划顺序选择下一个 `PENDING`/`DEFERRED`（循环一圈），只设为当前，不自动开始。
- 切换节点：运行中的 TIMER 冻结为 `PAUSED` 并标记 `DEFERRED`；已开始的 COUNT 保留 `RUNNING`；未开始的节点回到 `PENDING`。切换不会完成节点，也不会丢失进度。
- 循环只作用于当前节点；循环重置把 TIMER 恢复完整时长。切换节点会关闭循环（与原有“循环只作用于当前节点”一致）。
- 运行快照新增 `nodeStatuses`、`nodeRemainingMs`；`RunSummary.skippedNodes` 仅作为兼容字段保留并恒为 0。

单调时钟：
- Android `SystemClock.elapsedRealtime()`；iOS `mach_continuous_time()`。
- 剩余时间始终由 deadline 派生；UI ticker 只触发重新计算/重绘，不是时间来源。
- App 回到前台、后台服务 tick、用户操作都会重新计算；暂停冻结、继续恢复。
- wall clock 仍保留在 RunSummary 中用于真实用时；结果页展示真实完成节点数和用时，不伪造每节点统计。

## 3. 双端结构

### Android

- [`PlanRunController.kt`](../android/app/src/main/kotlin/com/flow/app/domain/run/PlanRunController.kt)：纯 JVM 状态机。
- [`RunCoordinator.kt`](../android/app/src/main/kotlin/com/flow/app/domain/run/RunCoordinator.kt)：App 级单例持有、单实例保护、通知/触觉/后台服务生命周期。
- [`FlowApplication.kt`](../android/app/src/main/kotlin/com/flow/app/FlowApplication.kt)：进程级 `RunCoordinator`。
- [`RunScreen.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunScreen.kt)：执行页编排、生命周期、到期反馈触发、结果跳转。
- [`RunRoller.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunRoller.kt)：3D 滚筒、当前/紧凑节点卡、COUNT/TIMER 动效、已完成归档、底部操作坞。
- [`RunSetupScreen.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunSetupScreen.kt)、[`RunResultScreen.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunResultScreen.kt)。
- [`AndroidRunPlatform.kt`](../android/app/src/main/kotlin/com/flow/app/platform/run/AndroidRunPlatform.kt)：通知、触觉、前台服务启动器。
- [`RunForegroundService.kt`](../android/app/src/main/kotlin/com/flow/app/platform/run/RunForegroundService.kt)：`START_NOT_STICKY`，500ms tick 调用同一 coordinator。

### iOS

- [`PlanRunController.swift`](../ios/FlowApp/Domain/Run/PlanRunController.swift)：`@MainActor` 状态机。
- [`RunCoordinator.swift`](../ios/FlowApp/Domain/Run/RunCoordinator.swift)：`ObservableObject`，由 `FlowApp` 的 `@StateObject` 持有。
- [`RunNotificationSink.swift`](../ios/FlowApp/Platform/RunNotificationSink.swift)：生命周期事件 → 本地通知调度器；`LocalTimerNotificationScheduler` 可在测试中替换。
- [`RunSetupView.swift`](../ios/FlowApp/Presentation/RunSetupView.swift)、[`RunView.swift`](../ios/FlowApp/Presentation/RunView.swift)、[`RunResultView.swift`](../ios/FlowApp/Presentation/RunResultView.swift)。

## 4. Android 后台与通知

- 选中“开始运行”后由 `AndroidRunBackgroundRunner` 启动一个前台服务；`started` 标志防止重复启动。
- 前台服务使用低重要度常驻通知展示：计划名、节点 x/N、节点名、状态、剩余时间或目标次数。
- 后台计时到期时额外发送高重要度“计时结束”通知，渠道启用声音/振动（后台通知触觉）；前台到期只触发应用内触觉，不同时发通知，避免重复提醒。
- 暂停：更新常驻通知为“已暂停”；继续：更新剩余时间；完成节点/推进：更新；退出或正常结束：取消通知并停止服务。
- 通知渠道在 Application 创建时注册：`flow_run_progress`（LOW）、`flow_timer_alerts`（HIGH）。
- Android 13+ 在进入启动设置时请求 `POST_NOTIFICATIONS`；拒绝后前台计时、暂停、到期、完成仍然可用，后台通知由系统隐藏。
- 服务不持久化任何状态，进程被杀后不恢复；`clearStale` 在 Application 启动时停止遗留服务并取消通知。
- 当前实现不依赖 AlarmManager；设备深度休眠时前台服务通知的到期提醒可能被系统延迟，恢复后按单调时钟立即校正。

## 5. iOS 后台与通知

- 不申请永久后台执行。TIMER 开始时按剩余秒数预约 `UNTimeIntervalNotificationTrigger` 本地通知。
- 暂停取消预约；继续重新预约；完成/退出取消该运行的通知；App 回到前台按单调时钟重算剩余时间。
- 通知权限被拒绝时，计时器、暂停、继续、到期、完成仍正常工作。
- 进程被杀后已提交的本地通知可能仍会触发；App 启动时按 `flow.run.` 前缀清理 pending + delivered 的陈旧 Flow 运行通知。
- 通知标识按 runId 区分；完成/退出时同时清理 pending 与 delivered。

## 6. 触觉

- 双端控制器在 TIMER 剩余 ≤3 秒时发出一次 `TIMER_WARNING`，到期时发出一次 `TIMER_EXPIRED`；每个计时 run 最多一次。
- 执行页另有一个进程级、一次性消费的到期 UI token：`RunCoordinator.expiryFeedback` / `expiryFeedbackToken`。真实到期时设置，UI 播放完 450ms 左右横向震动后消费；重新渲染、旋转或从后台恢复都不会重复触发。
- Android：前台使用 `Vibrator` / `VibrationEffect`；后台由前台服务检测到期并发送高重要度通知，由通知渠道振动，不同时触发直接振动。
- iOS：`UIImpactFeedbackGenerator` / `UINotificationFeedbackGenerator`；后台由本地通知声音/振动提示。
- 触觉只通过事件计数测试和平台 API 调用验证；Android 真机人工测试未单独评估触觉手感，模拟器无真实马达。

## 7. 数据与生命周期

- 运行实例只存在于当前进程：Android `FlowApplication`；iOS `FlowApp` 的 `@StateObject`。
- 不写入 `plans.json`，不做崩溃恢复/执行历史。
- 同一时刻只允许一个运行实例；`RunCoordinator.start` 返回 `ALREADY_RUNNING`。
- 正常结束或主动退出后清空 controller、通知、后台服务，释放引用。
- 进程重启后 `RunCoordinator` 初始为 `Idle`，没有旧运行实例。
- 运行期间计划列表/编辑入口不可达；退出/结束后才回到列表。

## 8. 旧执行模型清理

- 删除阶段七 `ExecutionSession`、`ExecutionScheduling`、`ExecutionController`、`ExecutionTimerEngine` 及对应测试。
- 运行功能不使用旧 Scheduler/records；本轮的 `NodeStatus` 与 `selectNode` 只是运行控制器内部的最小调度扩展，不恢复阶段七的自动推进/执行记录模型。
- 计划编辑/文本解析契约保留：`x N`、`[计数=完成]`、`[分类=休息]` 仍被解析器接受以兼容历史输入；计划 JSON 和运行逻辑只使用 COUNT 目标、TIMER 时长。
- 保留独立的单调时钟与计时思路，但新实现是运行控制器内部的最小 deadline 逻辑，不复用旧 TimerEngine。

## 9. 测试与验证

- Android JVM（168 tests / 0 failures）：`PlanRunControllerTest`、`RunCoordinatorTest`、`RunFormattingTest`、`PlanStoreTest` 覆盖状态转换、COUNT/TIMER、暂停/继续/到期、循环、退出统计、单实例、通知回调、后台生命周期、不持久化、Parser 共享用例、节点切换进度、空流程拒绝等。
- iOS XCTest（162 tests / 0 failures）：对应 `PlanRunControllerTests`、`RunCoordinatorTests`、`RunNotificationSinkTests`、`RunFormattingTests`、`PlanStoreTests`，新增用例与 Android 同构。
- iOS XCUITest（最终 9 tests / 0 failures）：
  - `FlowRunFlowUITests`：两计划全链路、启动倒计时取消/单次启动、滚筒切换/COUNT/TIMER/归档、前台到期反馈。
  - `FlowPlanNamingUITests`：命名/删除、空草稿不建流程、已有流程清空后不能保存且取消不破坏原数据。
  - `FlowTimerNotificationUITests`：后台 2 秒 TIMER，验证本地通知实际到达。
- Android 模拟器（API 37）：ADB 人工走查启动倒计时/取消、初始滚筒、COUNT 目标等待、切换节点、暂停/恢复、完成归档、空草稿退出不建流程、添加节点后保存、编辑删除全部节点时保存禁用且退出保留原计划。
- 验证证据：`artifacts/ui-roller-v2-20261121/`（不随源码提交）。

## 10. 已知限制

- Android 已完成真机安装与人工测试；iOS 仍以模拟器 XCTest/XCUITest 验证为主，iOS 真机签名发布、触觉和后台调度未作为 0.1.0 验收项确认。
- Android 深度休眠下的到期提醒依赖前台服务调度，可能延迟；不使用精确闹钟，不申请精确闹钟权限。
- iOS 不做永久后台；进程被杀后的本地通知无法立即撤销，只能在下一次启动清理。
- 通知权限被拒绝时，后台提醒不可用（前台功能不受影响）。
- 运行结果不持久化，退出/结束后无法恢复；进程被杀后没有 Session 恢复。这是当前架构的既有明确边界，本轮没有新增持久化。
- COUNT 没有独立的“暂停”执行状态，也不提供逐次计数；它是目标次数 + 开始/完成节点。切换节点时保留 RUNNING 执行状态；TIMER 的暂停/继续语义保持不变。
- 执行历史和卡片拖拽排序不在本次范围；流程名称编辑已支持。
- iOS 模拟器不提供真实触觉；Android 模拟器无真实马达。触觉触发路径由单元测试和平台 API 验证，手感需真机验收。
