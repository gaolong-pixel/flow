# 第二阶段：计划运行功能

本文记录 Android 与 iOS 的计划运行实现、双端一致的产品语义、通知/后台策略、测试与已知限制。

## 0. 2026-11-19 UI 修正

- 编辑页顶部新增流程名称输入框；名称去除首尾空白、非空、最多 60 个 Unicode 字符，随保存与节点一起持久化。
- 单节点循环从启动设置移到运行页：启动设置不再显示循环选择，每次启动默认关闭；运行页保留完整循环功能和既有语义。

## 1. 产品闭环

```text
计划列表
  └─ 点击“开始” → 启动设置（顺序播放；不提供循环选择）
       └─ 开始运行 → 运行页
            ├─ COUNT：开始 → 完成本节点
            ├─ TIMER：开始 → 倒计时 ⇄ 暂停/继续 → 提前完成 / 到期 → 完成本节点
            ├─ 当前节点循环：开启后完成 → 重置当前节点（不计完成数）
            ├─ 退出 → 退出确认 → 已退出流程页（X/Y）→ 首页
            └─ 最后节点完成 → 流程已完成页 → 首页
```

运行期间不提供编辑入口；计划列表在运行页背后不可达，退出/结束前不能启动第二个计划。

## 2. 运行状态机

双端使用同一状态语义，独立实现：

- 节点状态：`READY`、`RUNNING`、`PAUSED`、`EXPIRED`。
- `READY`：当前节点待开始；TIMER 显示完整时长。
- `RUNNING`：COUNT 已开始 / TIMER 已建立单调时钟 deadline。
- `PAUSED`：TIMER 剩余时间冻结，继续时从剩余时间建立新 deadline。
- `EXPIRED`：TIMER 归零；**不会自动完成节点**，必须用户点击“完成本节点”。
- 全部节点完成后运行结束；循环迭代不增加完成节点数。
- 每次启动默认循环关闭；启动设置不提供循环入口，运行页可随时开关。推进到下一节点时循环自动关闭（“循环只作用于当前节点”）。
- 关闭循环本身不推进节点，不重置剩余时间；只有点击完成才推进。
- 循环完成时：当前节点回到 `READY`，TIMER 重置为完整时长，COUNT 重新展示目标数量，不自动开始下一轮。
- 退出返回 `completedNodes / totalNodes` 用于进度反馈；运行实例随之销毁。

单调时钟：
- Android `SystemClock.elapsedRealtime()`；iOS `mach_continuous_time()`。
- 剩余时间始终由 deadline 派生；UI ticker 只触发重新计算/重绘，不是时间来源。
- App 回到前台、后台服务 tick、用户操作都会重新计算；暂停冻结、继续恢复。
- wall clock 仍保留在 RunSummary 中用于内部结果数据；本轮 UI 不展示用时，避免把结果页做成统计面板。

## 3. 双端结构

### Android

- [`PlanRunController.kt`](../android/app/src/main/kotlin/com/flow/app/domain/run/PlanRunController.kt)：纯 JVM 状态机。
- [`RunCoordinator.kt`](../android/app/src/main/kotlin/com/flow/app/domain/run/RunCoordinator.kt)：App 级单例持有、单实例保护、通知/触觉/后台服务生命周期。
- [`FlowApplication.kt`](../android/app/src/main/kotlin/com/flow/app/FlowApplication.kt)：进程级 `RunCoordinator`。
- [`RunScreen.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunScreen.kt)、[`RunSetupScreen.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunSetupScreen.kt)、[`RunResultScreen.kt`](../android/app/src/main/kotlin/com/flow/app/presentation/RunResultScreen.kt)。
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
- 运行功能不再使用 `records`、`completedCount`、`skippedCount`、Scheduler 自动推进、流程/步骤 repeat 语义。
- 计划编辑/文本解析契约保留：`x N`、`[计数=完成]`、`[分类=休息]` 仍被解析器接受以兼容历史输入；计划 JSON 和运行逻辑只使用 COUNT 目标、TIMER 时长。
- 保留独立的单调时钟与计时思路，但新实现是运行控制器内部的最小 deadline 逻辑，不复用旧 TimerEngine。

## 9. 测试与验证

- Android JVM：`PlanRunControllerTest`、`RunCoordinatorTest`、`RunFormattingTest` 覆盖状态转换、COUNT/TIMER、暂停/继续/到期、循环、退出统计、单实例、通知回调、后台生命周期、不持久化、Parser 共享用例。
- iOS XCTest：对应 `PlanRunControllerTests`、`RunCoordinatorTests`、`RunNotificationSinkTests`、`RunFormattingTests`。
- iOS XCUITest：
  - `FlowRunFlowUITests`：从空数据创建两个计划，完成启动设置（确认无循环入口）→ COUNT → TIMER 暂停/继续/提前完成 → 运行页循环开关（默认关闭）→ 推进 → 退出确认 → 已退出流程页 → 第二计划 TIMER 到期 → 流程已完成页 → 返回首页。
  - `FlowTimerNotificationUITests`：后台 2 秒 TIMER，验证系统本地通知实际到达，回到前台显示到期且不自动完成。
- Android 模拟器（API 37）：人工 ADB 操作完成同一核心链路，并验证前后台通知、暂停冻结、到期不自动完成、退出/完成后通知与前台服务清理、force-stop 后无旧运行实例。
- 验证证据：本地 `artifacts/`（不随源码提交）。

## 10. 已知限制

- Android 已完成真机安装与人工测试；iOS 仍以模拟器 XCTest/XCUITest 验证为主，iOS 真机签名发布、触觉和后台调度未作为 0.1.0 验收项确认。
- Android 深度休眠下的到期提醒依赖前台服务调度，可能延迟；不使用精确闹钟，不申请精确闹钟权限。
- iOS 不做永久后台；进程被杀后的本地通知无法立即撤销，只能在下一次启动清理。
- 通知权限被拒绝时，后台提醒不可用（前台功能不受影响）。
- 运行结果不持久化，退出/结束后无法恢复。
- 执行历史和卡片拖拽排序不在本次范围；流程名称编辑已支持。
