# Flow Plan Model V1

## 已落地的计划定义

- 计划持久化到设备内部 JSON 文件（Android `plans.json`，iOS `plans.json`）。
- 计划包含 id、名称、节点列表、创建/更新时间。名称在编辑页可修改；保存前去除首尾空白，要求非空、最多 60 个 Unicode 字符。
- COUNT 节点只保存目标数量。
- TIMER 节点只保存目标时长；不保存暂停、剩余时间、deadline 或运行时状态。
- 节点进入执行时默认待开始；运行实例和通知不属于计划数据。
- 计划执行/运行状态不持久化。

## 兼容边界

阶段七旧执行引擎已在第二阶段移除。计划编辑与文本解析保留历史语法兼容：

- 读取计划时 COUNT 统一为 `COMPLETE` 内部值、TIMER 分类统一为 `NORMAL`、step repeat 与 plan repeat 统一为 1，仅用于满足既有 Routine/RoutineStep 校验；
- 文本解析仍接受历史 `x N` / `[计数=完成]` / `[分类=休息]` 语法，不将其写入计划 JSON；
- 运行功能只读取 `RoutineStep.target` 与 `RoutineStep.durationMs`，不读取 repeat/countMode/category；
- 卡片编辑器不再暴露计数模式、计时分类或重复次数。

## 运行（第二阶段已实现）

- 计划运行使用纯内存状态机，详见 [`RUN_FEATURE.md`](RUN_FEATURE.md)。
- 运行实例保存在 App 进程内：Android `FlowApplication` / iOS `FlowApp`。
- 同一时间只允许一个运行实例；运行状态不写入 `plans.json`，无崩溃恢复、无执行历史。
- 正常结束/主动退出/进程结束后不保留运行实例；iOS 进程被杀后已预约的本地通知由下次启动清理。

## 模块位置

- Android 运行代码：`android/app/src/main/kotlin/com/flow/app/domain/run/`、`platform/run/`、`presentation/Run*Screen.kt`。
- iOS 运行代码：`ios/FlowApp/Domain/Run/`、`Platform/RunNotificationSink.swift`、`Presentation/Run*.swift`。
- 测试：`android/app/src/test/kotlin/com/flow/app/domain/run/`、`ios/FlowAppTests/Run/`、`ios/FlowUITests/`。
- 验证证据：本地 `artifacts/`（不随源码提交）。
