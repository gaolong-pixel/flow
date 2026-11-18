# Flow

Flow 是 Android/Kotlin + Jetpack Compose 与 iOS/Swift + SwiftUI 的原生流程执行应用。当前已实现流程编辑（文本/卡片）、解析与序列化、稳定步骤 ID、执行引擎与阶段七双端回归；执行 UI、持久化和后台恢复仍属于后续阶段。

## 已确定配置
- Android：Kotlin + Jetpack Compose，最低 Android 10 / API 29；当前 `compileSdk/targetSdk = 33`，包名 `com.flow.app`
- iOS：Swift + SwiftUI，最低 iOS 16；Bundle Identifier `com.flow.app`
- UI：现代卡片风；编辑界面已接入，执行界面仍待后续阶段。
- 首版本地优先，无账号、无服务端

## 工程状态

当前处于阶段七执行引擎收尾。执行引擎已完成并通过双端测试；产品尚未完成执行 UI、持久化、后台恢复和历史展示，不是完整可发布应用。

- Android 工程根目录：`android/`；Gradle Wrapper 7.6.1，AGP 7.4.2，Kotlin 1.8.22，Compose BOM 2023.06.01。
- iOS 工程：`ios/Flow.xcodeproj`；Xcode 工程文件由 `ios/project.yml` 生成，部署目标 iOS 16.0。
- 旧 Phase 1 V0 原型代码保留用于参考：`android/app/src/main/java/com/example/flow/` 与 `ios/Flow/`，**不参与新 App 编译**。这些文件中的 REST 名称推断等逻辑尚未接入运行应用。

## Android

1. 用 Android Studio 打开 `android/` 目录。
2. 确认 SDK 已安装 Android 13 / API 33（Build-Tools 33.0.2）；如 IDE 提示，设置本地 SDK 路径。
3. 选择 `app` 运行配置，启动模拟器或设备。
4. 命令行构建：`cd android && ./gradlew :app:assembleDebug`。

## iOS

1. 用 Xcode 打开 `ios/Flow.xcodeproj`。
2. Scheme 选择 `Flow`，运行目标选择 iOS 16+ 模拟器。
3. 命令行构建：
   `xcodebuild -project ios/Flow.xcodeproj -scheme Flow -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/flow-ios-derived CODE_SIGNING_ALLOWED=NO build`
4. 真机运行需要在 Xcode 中设置自己的 Development Team；模拟器不需要签名。

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
- 阶段七执行引擎已完成；执行 UI、持久化和后台恢复属于后续阶段。

## 当前状态

已实现：

- V1.1 文本解析、序列化、双向编辑状态与稳定步骤 ID
- Routine / RoutineStep 领域模型与校验
- Android/iOS 执行引擎：ExecutionSession、Scheduler、Controller、TimerEngine
- COUNT INCREMENT / COMPLETE、SKIP、TIMER 生命周期、PAUSE/RESUME/CANCEL
- Timer Plan/Commit、ActiveTimerReference、并发保护与失败原子性
- 阶段七双端执行引擎回归测试

仍未实现：

- 执行 UI 接入与真实设备交互验收
- Room/SQLite 持久化、进程重启恢复
- 后台/锁屏计时恢复、通知
- 执行历史展示
- 卡片拖拽排序 UI（卡片字段编辑已实现）

当前处于阶段七执行引擎收尾，尚未形成完整可发布产品。
