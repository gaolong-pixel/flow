# 初始化与开发状态

1. 工程已初始化：Android 使用 `android/`，iOS 使用 `ios/Flow.xcodeproj`。
2. Routine / RoutineStep 模型、文本解析器、序列化器和稳定步骤 ID 已实现。
3. 流程名称、多行文字输入、解析预览已实现。
4. 卡片列表、添加/编辑/删除/排序已实现。
5. 唯一 RoutineDraft 与文字/卡片双向同步已实现。
6. 本地 JSON 持久化（`plans.json`）已实现；不使用 Room/SQLite。
7. 共享解析样例双端一致性测试已实现（88/88）。
8. 第二阶段计划运行功能已完成：启动设置、运行页、COUNT/TIMER、暂停/继续/到期、单节点循环、退出/结束、结果页。
9. Android 前台服务与通知、iOS 本地通知已实现。
10. 阶段七旧执行引擎已移除，运行功能使用独立的内存运行控制器。

## 当前运行方式

- Android：`cd android && ./gradlew :app:assembleDebug`；单元测试 `:app:testDebugUnitTest`。
- iOS：`xcodebuild -project ios/Flow.xcodeproj -scheme Flow ... build/test`。
- Parser 共享用例位于 `shared-spec/parser-cases.json`，双端均执行 88 个用例。

## 0.1.0 状态

- Android Release APK 已构建、v2 签名验证通过，并完成真机安装与人工测试。
- iOS 完成模拟器构建、单元测试和 XCUITest；iOS 真机签名发布与真机触觉/后台验收不在 0.1.0 范围。
- 计划名称编辑已支持；执行历史、崩溃恢复和卡片拖拽排序未实现。

## 后续可选工作

1. iOS 真机签名、触觉和后台调度验收；
2. 卡片拖拽排序；
3. 执行历史展示；
4. Android 深度休眠下的精确到期提醒（如确需精确闹钟，需要单独权限设计）；
5. iOS 被杀进程后的通知清理体验优化。

详见 [README](../README.md)、[RUN_FEATURE.md](RUN_FEATURE.md) 与 [PLAN_MODEL_V1.md](PLAN_MODEL_V1.md)。
