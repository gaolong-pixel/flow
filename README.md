# Flow

Flow 是 Android/Kotlin + Jetpack Compose 与 iOS/Swift + SwiftUI 的原生流程执行应用。Android 当前版本为 0.1.3；iOS 源码仍为 0.1.0，本次未发布 iOS 安装包。已完成计划创建/编辑/持久化、双端计划运行闭环、纵向 3D 流程滚筒、COUNT 目标次数与平静呼吸等待、TIMER 双环倒计时与到期反馈、自由切换与暂缓调度、跳过节点保留且可重新选择、已完成归档、单节点循环、退出/结束反馈，以及 Android 前台服务通知与 iOS 本地通知。

## 已确定配置
- Android：Kotlin + Jetpack Compose，最低 Android 10 / API 29；当前 `compileSdk/targetSdk = 33`，包名 `com.flow.app`
- iOS：Swift + SwiftUI，最低 iOS 16；Bundle Identifier `com.flow.app`
- UI：reference v2 插画背景与统一设计 Token；Android 0.1.3 已完成首页、准备页、COUNT/TIMER、完成页视觉改造与执行滚筒 v2。
- 首版本地优先，无账号、无服务端。

## 0.1.3 状态

- Android 0.1.3 Release APK 随本次 v0.1.3 GitHub Release 提供，使用既有正式签名材料构建。
- Android 包名 `com.flow.app`，versionCode `4`，versionName `0.1.3`，minSdk 29。
- iOS 源码仍为 0.1.0 范围；本次不发布 iOS 安装包。
- Release APK 和本地验收截图/记录不进入源码提交，由 GitHub Release 附件或本地归档单独管理。
- Android Release 签名配置见 [Android Release 签名配置](docs/ANDROID_RELEASE_SIGNING.md)。

## 工程状态

第二阶段运行功能已双端完成：

- 计划持久化：Android/iOS 均保存到应用内部 `plans.json`，只保存计划定义。
- 运行实例：[`PlanRunController`](android/app/src/main/kotlin/com/flow/app/domain/run/PlanRunController.kt) / [`PlanRunController.swift`](ios/FlowApp/Domain/Run/PlanRunController.swift) 为纯内存状态机，持有每个节点的调度状态和 TIMER 冻结剩余时间；[`RunCoordinator`](android/app/src/main/kotlin/com/flow/app/domain/run/RunCoordinator.kt) / [`RunCoordinator.swift`](ios/FlowApp/Domain/Run/RunCoordinator.swift) 由 App 进程持有，保证同一时间只有一个运行实例。
- 运行状态不写入 PlanStore，无崩溃恢复，无执行历史。
- Android 后台：前台服务 + 两个通知渠道（运行状态、计时提醒），详见 [第二阶段运行功能](docs/RUN_FEATURE.md)。
- iOS 后台：仅使用本地通知；App 回到前台后按单调时钟重新计算剩余时间。
- 阶段七旧执行引擎（ExecutionSession/Scheduler/records 等）已移除；计划模型和文本编辑契约保持不变，历史 `x N`、`[计数=完成]`、`[分类=休息]` 语法继续被解析器接受作为兼容输入，但计划 JSON 只保存目标次数/时长。

- Android 工程根目录：`android/`；Gradle Wrapper 7.6.1，AGP 7.4.2，Kotlin 1.8.22，Compose BOM 2023.06.01。
- iOS 工程：`ios/Flow.xcodeproj`；Xcode 工程文件由 `ios/project.yml` 生成，部署目标 iOS 16.0。
- 旧 Phase 1 V0 原型代码保留用于参考：`android/app/src/main/java/com/example/flow/` 与 `ios/Flow/`，**不参与新 App 编译**。

## 计划运行（第二阶段）

1. 在计划列表创建计划并编辑 COUNT/TIMER 节点，保存。
2. 点击计划卡片上的“开始”进入启动设置；运行时默认推荐第一个待执行节点，但不强制线性执行：点击任意待执行/暂缓节点即可切换执行目标。单节点循环只在运行页控制（默认关闭）。
3. 运行页为纵向 3D 滚筒：当前节点展开并占据主要视觉区域，上下相邻节点按距离缩放、淡出和透视旋转；滚动只浏览，点击才切换执行目标。
4. COUNT 支持开始、完成；开始后进入平静的呼吸等待，不要求逐次点击计数，目标次数仅作信息展示。TIMER 支持真实倒计时、暂停、继续、到期和提前完成。
5. 切换执行目标时，正在运行的 TIMER 会被冻结并标记为暂缓；跳过节点保留在滚筒中并标记“已跳过”，不计入完成，但可以重新选择并恢复此前进度；最后一个活动节点的跳过会被拒绝，避免流程结束导致跳过节点失去入口。完成节点进入默认折叠的“已完成”区域。当最后一个可执行节点完成且仍有跳过节点时，会先提示“去完成跳过节点”或“直接结束”，不会立即收尾。
6. 运行期间可随时开关“当前节点循环”。循环完成后重置当前节点且不计入完成节点数；关闭循环本身不推进节点。
5. 编辑页顶部可填写/修改流程名称；名称随保存与节点一起持久化，空名称不可保存。
6. 主动退出会展示“已退出流程”和已完成节点数；全部节点完成后展示“流程已完成”结果页；两者都可返回首页。
7. 运行期间不能编辑计划，也不能启动第二个计划。
8. 运行状态仍只在当前进程内，不写入 `plans.json`；进程被杀后没有会话恢复。

产品语义见 [RUN_FEATURE.md](docs/RUN_FEATURE.md)，计划模型见 [PLAN_MODEL_V1.md](docs/PLAN_MODEL_V1.md)。

## Android

1. 用 Android Studio 打开 `android/` 目录。
2. 确认 SDK 已安装 Android 13 / API 33（Build-Tools 33.0.2）；如 IDE 提示，设置本地 SDK 路径。
3. 选择 `app` 运行配置，启动模拟器或设备。
4. 命令行构建：`cd android && ./gradlew :app:assembleDebug`。
5. 单元测试：`cd android && ./gradlew :app:testDebugUnitTest`。

运行会启动前台服务；Android 13+ 首次进入启动设置时会请求通知权限。拒绝通知权限不影响前台计时，但后台到期提醒会被系统隐藏。

## iOS

1. 用 Xcode 打开 `ios/Flow.xcodeproj`。
2. Scheme 选择 `Flow`，运行目标选择 iOS 16+ 模拟器。
3. 命令行构建：
   `xcodebuild -project ios/Flow.xcodeproj -scheme Flow -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/flow-ios-derived CODE_SIGNING_ALLOWED=NO build`
4. 单元测试 / UI 测试：
   `xcodebuild -project ios/Flow.xcodeproj -scheme Flow -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath /tmp/flow-ios-derived CODE_SIGNING_ALLOWED=NO test`
5. 真机运行需要在 Xcode 中设置自己的 Development Team；模拟器不需要签名。

首次运行会请求本地通知权限；拒绝后前台计时仍正常，只是没有后台到期提醒。iOS 不申请永久后台运行，进程被杀后已提交给系统的本地通知仍可能触发，重新启动 App 时会清理陈旧的 Flow 运行通知。

## 文本语法 V1.1

正式契约见 [文本与卡片双向编辑契约 V1.1](docs/TEXT_EDITING_CONTRACT_V1.md)，共享行级样例见 [parser-cases.json](shared-spec/parser-cases.json)。一行一个步骤：

- `俯卧撑 15次`
- `俯卧撑15次`
- `俯卧撑 15次 [计数=完成]`
- `阅读 20分钟`
- `阅读20分钟`
- `休息 60秒`
- `拉伸 45秒 x 2`
- `拉伸45秒x2`
- `拉伸 45秒 x 2 [分类=休息]`
- `休息 60秒 [分类=普通]`

规则摘要：
- 次 -> COUNT；秒/分钟/分/小时/时 -> TIMER。
- 名称与数量之间允许没有空格；`俯卧撑15次` 与 `俯卧撑 15次` 等价。
- COUNT 默认 INCREMENT；`[计数=完成]` 表示 COMPLETE。
- TIMER 默认 NORMAL；名称包含“休息”时推断为 REST；显式 `[分类=休息]` / `[分类=普通]` 优先于名称推断。
- `x N` / `× N` 表示该步骤总执行 N 次（包含首次）；未写时为 1。
- 流程级重复次数属于 Routine，不进入文本；文本只表达步骤级重复。
- 空行忽略但不阻断；裸名称 `俯卧撑`/`阅读`、`俯卧撑 15`、`俯卧撑 15次 x`、未闭合属性等返回 INCOMPLETE；未知单位/属性、数值非法等返回 INVALID。
- 首版时长只支持整秒，最小 1 秒；`1.5秒`、`0.5分钟`、`1500毫秒` 等小数/毫秒输入为 INVALID。
- 存在 INCOMPLETE/INVALID 时保留原始文本和最近成功快照（只读），不部分提交，不允许进入卡片模式；修复后才同步。
- 文字与卡片语义无损，不要求保留原始空格/单位写法；无法表示的步骤（MANUAL、非整秒时长等）必须阻止序列化。
- 卡片修改 COUNT/TIMER 类型时保留步骤 ID；文本模式无法可靠匹配类型变更时允许新 ID。
- V1.1 不支持 MANUAL；解析、序列化、校验、保存均拒绝。

## 当前状态

已实现：

- V1.1 文本解析、序列化、双向编辑状态与稳定步骤 ID
- Routine / RoutineStep 领域模型与校验、双端 `plans.json` 持久化
- 双端计划运行闭环：启动设置、纵向 3D 滚筒、COUNT 开始/完成（目标次数）、TIMER 倒计时/暂停/继续/到期/提前完成、自由切换、暂缓、跳过保留且可重新选择、已完成归档、单节点循环、退出与正常结束
- 运行实例 App 级单例保护；节点调度状态与进度保存在内存快照；运行状态不持久化；进程重启无旧运行实例
- Android 前台服务、通知渠道、通知权限、后台到期提醒、暂停/完成/退出通知更新
- iOS 本地通知预约/取消/重排、授权拒绝降级、陈旧通知清理
- 双端运行控制器/协调器/通知调度单元测试；iOS 端到端 UI 测试

仍未实现（明确不在当前发布范围）：

- iOS 真机签名发布与真机触觉/后台调度验收；Android 已完成真机安装与人工测试
- 执行历史展示（每个节点的实际用时/次数明细）
- 崩溃恢复 / 进程被杀后的会话恢复（当前架构明确不做）
- 后台长期计时（iOS 不做永久后台）
- 卡片拖拽排序（节点顺序通过上移/下移调整）

## 验证证据

- 验证截图和测试记录保留在本地 `artifacts/`，按发布规范不作为源码提交。执行滚筒 v2（修订版）的证据在 `artifacts/ui-roller-v2-final-20261121/`（双端关键状态截图、Android 到期震动慢放对比、COUNT 平静等待、跳过保留与重新选择截图）。
- Android：JVM 单元测试 + 模拟器人工走查 + Debug APK 安装/启动检查。
- iOS：XCTest 单元测试 + XCUITest 端到端 UI 测试（模拟器）。
