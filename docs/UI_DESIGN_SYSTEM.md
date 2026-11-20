# Flow 统一设计体系（当前实现）

本轮严格按产品负责人确定的设计稿实施。颜色、字号、尺寸、页面结构均为实现要求。

## 颜色 Token

| Token | HEX | 用途 |
|---|---|---|
| Background | `#F5F7FA` | 页面背景 |
| Surface | `#FFFFFF` | 卡片、输入区域 |
| Primary | `#172033` | 主要文字 |
| Secondary | `#7B8495` | 辅助文字 |
| Accent | `#356AE6` | 主按钮、选中状态 |
| AccentSoft | `#EAF0FF` | 蓝色浅背景 |
| Border | `#E5E9F0` | 边框、分割线 |
| Success | `#21865B` | 成功状态 |
| SuccessSoft | `#E8F5EE` | 成功浅背景 |
| Warning | `#D58A2A` | 暂停、计时状态 |
| WarningSoft | `#FFF4E5` | 警示浅背景 |
| Danger | `#D94B4B` | 删除、危险操作 |
| DangerSoft | `#FDEEEE` | 危险浅背景 |
| Connector | `#DCE3EF` | 节点纵向连接线 |

节点类型：COUNT 使用 Accent，TIMER 使用 Warning。

实现位置：

- Android `FlowTheme.kt`
- iOS `FlowDesignSystem.swift`

页面中不得硬编码颜色。

## 字体

系统字体；不引入第三方字体。

| 类型 | 字号 |
|---|---:|
| 首页 Flow 品牌 | 30 |
| 页面标题 | 20 |
| 区块标题 | 18 |
| 流程名称 | 18 |
| 正文 | 15–16 |
| 辅助文字 | 12–13 |
| COUNT 目标数字 | 84 |
| TIMER 倒计时 | 72–80 |

关键数字 SemiBold；TIMER 使用等宽数字。

Android 使用 sp，随系统字体缩放。iOS 使用 `@ScaledMetric` 包装的 `flowFont` / `flowMonoFont`，并针对固定高度的密集工具布局将 Dynamic Type 上限限制为 `.xxxLarge`，避免辅助功能极限字号造成控件互相遮挡。

## 尺寸

| 元素 | 值 |
|---|---:|
| 页面左右边距 | 22 |
| 卡片圆角 | 18 |
| 输入框圆角 | 12 |
| 主按钮圆角 | 14 |
| 普通按钮高度 | 46 |
| 主按钮高度 | 54 |
| 卡片内边距 | 18 |
| 卡片之间间距 | 14 |
| 页面区块间距 | 24 |

Android `FlowSpacing` / `FlowRadius`；iOS `FlowMetrics`。

## 组件

Android `FlowComponents.kt`：
`FlowCard`、`FlowPrimaryButton`、`FlowSecondaryButton`、`FlowTypeBadge`、`FlowSectionTitle`、`FlowProgressBar`、`FlowStepIndex`、`FlowPill`。

iOS `FlowDesignSystem.swift`：
`FlowCard`、`FlowPrimaryButtonStyle`、`FlowSecondaryButtonStyle`、`FlowTypeBadge`、`FlowProgressBar`、`FlowSectionTitle`、`FlowPalette`、`FlowMetrics`、`flowFont`、`flowMonoFont`。

## 页面结构

- 首页：品牌区 → 我的流程 → 纵向流程线卡片 → 开始/编辑/更多菜单（删除在菜单内）。
- 编辑页：返回/标题 → 名称 → 编辑方式 → 同步状态 → 卡片或文本编辑 → 底部固定“保存流程”。
- 启动设置：返回/准备开始 → 名称与全量节点预览 → 顺序执行说明 → 固定“开始执行”。
- COUNT：退出/流程名 → STEP 进度 → 类型/节点/目标 → 固定操作与次级循环开关。
- COUNT 操作语义：
  | 状态 | 按钮文案 | 行为 |
  |---|---|---|
  | READY | 开始 | 调用现有 `startNode` |
  | RUNNING | 完成当前节点 | 调用现有 `completeNode` |
  不得为了匹配旧设计稿而合并开始/完成或修改状态机。
- TIMER：同上，重点是倒计时、状态色、线性视觉进度条和固定操作区。
- 结果：居中反馈；正常完成与主动退出使用不同图标和文案；不展示统计。
