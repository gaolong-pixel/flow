# 初始化与开发顺序

1. 工程已初始化：Android 使用 `android/`，iOS 使用 `ios/Flow.xcodeproj`；已接入流程编辑与执行引擎。
2. Routine / RoutineStep 模型、文本解析器、序列化器和稳定步骤 ID 已实现。
3. 流程名称、多行文字输入、解析预览已实现。
4. 卡片列表、添加/编辑/删除/排序已实现。
5. 唯一 RoutineDraft 与文字/卡片双向同步已实现。
6. Room/SQLite 持久化尚未实现。
7. 共享解析样例双端一致性测试已实现（88/88）。

优先级：
- P0：解析器、统一草稿、文字/卡片同步。
- P1：本地保存、拖拽排序、错误定位。
- P2：视觉细节、动效、空状态和无障碍。

阶段七已实现执行引擎（Session/Scheduler/Controller/TimerEngine）及 COUNT/TIMER 生命周期。

后续仍需开展：

1. 执行 UI 接入；
2. 本地持久化与进程重启恢复；
3. 后台、锁屏和设备重启计时恢复；
4. 执行历史展示；
5. 真实设备交互验收。

上述属于后续产品阶段，不是阶段七执行引擎未完成项。


## 当前运行方式

- Android：`cd android && ./gradlew :app:assembleDebug`；单元测试 `:app:testDebugUnitTest`。
- iOS：`xcodebuild -project ios/Flow.xcodeproj -scheme Flow ... build/test`。
- Parser 共享用例位于 `shared-spec/parser-cases.json`，双端均执行 88 个用例。

当前执行引擎已通过 Controller 层集成回归；尚未接入执行 UI。
