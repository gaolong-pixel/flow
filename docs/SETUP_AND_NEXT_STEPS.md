# 初始化与开发顺序

1. 工程已初始化：Android 使用 `android/`，iOS 使用 `ios/Flow.xcodeproj`；启动页仅显示 Flow。下一步先确认模拟器运行成功，再接入业务层。
2. 接入 Routine / RoutineStep 模型与文本解析器（当前 V0 原型保留在 `android/app/src/main/java/com/example/flow/` 与 `ios/Flow/`，未参与新 App 构建）。
3. 实现流程名称、多行文字输入、解析预览。
4. 实现卡片列表、添加/编辑/删除/排序。
5. 建立唯一 RoutineDraft，两个编辑模式共同读写。
6. 接入 Room（Android）和 SQLite/GRDB（iOS）。
7. 使用共享解析样例做双端一致性测试。

优先级：
- P0：解析器、统一草稿、文字/卡片同步。
- P1：本地保存、拖拽排序、错误定位。
- P2：视觉细节、动效、空状态和无障碍。

本阶段暂不开发后台执行、通知、计时器和执行历史。
