> 0.1.0 说明：本契约定义编辑/解析层。当前运行执行不展开流程级/步骤级重复，运行语义见 [RUN_FEATURE.md](RUN_FEATURE.md) 和 [PRODUCT_RULES.md](PRODUCT_RULES.md)。文中 `repeatCount` 等字段属于旧文本兼容与编辑契约，不是当前 0.1.0 的执行扩展语义。

# Flow 文本与卡片双向编辑契约 V1.1

- 状态：Phase 1 契约 V1.1 修订；文中标注“待确认”的条目仍不属于已定需求。
- 适用范围：编辑器数据契约、文本语法、解析状态、文本与卡片的双向转换、步骤 ID 稳定性。
- 不包含：执行引擎、通知、后台计时、持久化实现、UI 实现、MANUAL 节点。
- 相关文档：[产品规则](PRODUCT_RULES.md)、[验收清单](ACCEPTANCE_CHECKLIST.md)、[共享解析样例](../shared-spec/parser-cases.json)。

---

## 0. 术语

| 术语 | 定义 |
|---|---|
| Routine | 用户保存的流程模板，包含流程名称、流程级重复次数和步骤列表。 |
| RoutineStep | 模板中的一个步骤，包含稳定 ID、类型、名称、类型参数、步骤重复次数和顺序。 |
| RoutineDraft | 编辑期间的唯一事实来源，保存已确认的流程名称、流程级重复次数和步骤列表。 |
| RoutineEditorState | 编辑会话状态，包裹 `RoutineDraft`，并保存原始文本 buffer、最近成功解析快照、诊断、模式和行号映射。 |
| 文本 buffer | 用户在文字模式中看到的原始多行文本，允许包含空行、未完成行和非法行。 |
| 最近成功解析快照 | 最近一次“全部非空行均 VALID”且已提交到 `RoutineDraft` 的步骤集合；**只读快照**，用于 ID 匹配与错误回退，不是第二事实来源。 |
| 诊断 | 逐行解析结果，包含行号、原始行、状态和原因。 |
| 语义无损 | 步骤字段、步骤顺序和步骤 ID 不丢失；**已确认不要求保留原始空格、单位写法等文本格式**。 |
| 规范化文本 | 序列化器按本契约生成的唯一文本形式。 |

---

## 1. 规范原则

1. 文字模式与卡片模式完全平等，二者都是正式编辑入口。
2. 两种模式共同操作同一个 `RoutineDraft`；`RoutineDraft` 是已确认数据的唯一事实来源。
3. 文本 buffer 是输入/投影，不是第二事实来源；文本只有经过解析、校验、提交后才改变 `RoutineDraft`。
4. 卡片模式直接修改 `RoutineDraft`，修改成功后必须通过序列化器更新文本 buffer。
5. 文本与卡片之间必须语义无损；无法表示的步骤必须报错并阻止转换，不能静默截断或舍入。
6. 步骤 ID 在步骤创建时生成，之后不得因文本重新解析、模式切换或普通属性修改而变化。
7. 存在 `INCOMPLETE` 或 `INVALID` 行时，保留原始文本、保留最近成功快照、不得部分提交、不得进入卡片模式。
8. 不静默删除非法行，不静默丢弃用户输入。
9. 流程级重复次数属于 `Routine`，不属于 `RoutineStep`；文本语法只表达步骤级重复。
10. V1 不支持 MANUAL；解析、序列化、校验和保存路径都必须拒绝 MANUAL。

---

## 2. 数据模型

### 2.1 Routine

| 字段 | 类型 | V1 要求 |
|---|---|---|
| `id` | String | 模板唯一 ID；创建时生成，编辑期间不变。 |
| `name` | String | 流程名称；是否允许空名称、默认名称和重名策略待确认。 |
| `version` | Int | 语义待确认：是数据 schema 版本还是内容修订号；不得同时承担两种含义。 |
| `repeatCount` | Int | 流程级重复次数，`>= 1`；不进入文本语法。 |
| `steps` | List<RoutineStep> | 按执行顺序排列，`order` 与列表顺序一致。 |
| `createdAt` | Long / Int64 | 创建时间，毫秒时间戳；具体时钟来源待确认。 |
| `updatedAt` | Long / Int64 | 最后更新时间，毫秒时间戳。 |

流程级重复与步骤级重复的执行组合语义（相乘、外循环、内循环等）属于执行阶段，**待确认**，本契约不定义。

### 2.2 RoutineStep

| 字段 | 类型 | V1 要求 |
|---|---|---|
| `id` | String | 稳定唯一 ID；创建时生成；不可变；不得由解析器生成。 |
| `name` | String | 创建/更新时统一去除首尾 Unicode White_Space；trim 后非空；内部空白保留；不得包含换行。 |
| `type` | COUNT / TIMER | V1 不允许 MANUAL。 |
| `target` | Int? | 仅 COUNT；`> 0`。 |
| `countMode` | INCREMENT / COMPLETE? | 仅 COUNT；必填；默认 INCREMENT。 |
| `durationMs` | Long / Int64? | 仅 TIMER；`>= 1000`；必须是 1000 的整数倍；最小 1 秒；V1 不支持小数秒和毫秒输入。 |
| `category` | NORMAL / REST? | 仅 TIMER；必填；默认 NORMAL，可按名称推断。 |
| `repeatCount` | Int | 步骤级总执行次数，包含首次；`>= 1`；默认 1。 |
| `order` | Int | 0 基、连续、与列表顺序一致；建议改为派生值，不允许独立编辑。 |

字段不变量：

- COUNT：`target != null && target > 0`；`countMode != null`；`durationMs == null`；`category == null`。
- TIMER：`durationMs != null && durationMs >= 1000`；`durationMs % 1000 == 0`；`category != null`；`target == null`；`countMode == null`。
- 所有类型：`repeatCount >= 1`；`id` 非空且在 `RoutineDraft` 内唯一。
- 名称首尾 Unicode White_Space 不具有业务意义；模型创建和所有编辑入口必须统一 trim，内部空白保留。
- `MANUAL`：V1 禁止创建、解析、序列化和保存。
- V1 的时长精度固定为 1 秒：内部仍使用 `durationMs`，但值必须是 1000 的整数倍。非整秒、`毫秒`、小数秒输入一律 INVALID；卡片编辑也只能产生整秒。

### 2.3 RoutineDraft

`RoutineDraft` 只保存已确认的领域数据：

| 字段 | 类型 | V1 要求 |
|---|---|---|
| `id` | String | 模板身份；新建草稿创建时生成，保存时用于 upsert，避免重复创建模板。现有字段缺失，需要新增。 |
| `name` | String | 流程名称。 |
| `repeatCount` | Int | 流程级重复次数；`>= 1`。 |
| `steps` | List<RoutineStep> | 已确认步骤；按列表顺序执行；每个步骤 ID 唯一。 |

### 2.4 RoutineEditorState

以下状态**不应**塞进持久化的 `RoutineDraft`，而应放在 `RoutineEditorState` 中：原始文本 buffer、当前解析结果、编辑模式、最近成功快照和同步状态。

当前 Android/iOS 实现字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `mode` | TEXT / CARDS | 当前编辑模式。 |
| `draft` | RoutineDraft | 唯一事实来源。 |
| `textBuffer` | String | 当前文本，允许空行、INCOMPLETE、INVALID。 |
| `parseResult` | ParseResult | 当前解析结果，包含逐行状态、统计和诊断。 |
| `syncStatus` | SYNCED / DIRTY / INVALID | 文本与 Draft 的同步状态。 |
| `lastCommittedSteps` | List<RoutineStep> | **只读快照**：最近成功提交或卡片操作后的步骤，用于 ID 匹配与恢复；不是第二事实来源，禁止作为编辑入口。 |
| `lastCommittedText` | String | 与 `lastCommittedSteps` 对应的规范化文本；用于脏状态判断，不保留原始格式。 |

**同步状态：**

- `SYNCED`：文本与 Draft 语义一致。
- `DIRTY`：存在尚未提交的合法文本修改。
- `INVALID`：当前文本存在 `INCOMPLETE` 或 `INVALID` 阻断错误。

同步状态只表达文本与 Draft 的同步关系，不得与错误类型混用。

> 注意：`INVALID` 同时是解析行状态和编辑同步状态的名称，但两者作用域不同。同步状态 `INVALID` 表示当前文本至少存在一行 `INCOMPLETE`/`INVALID`；它不是错误码，也不能代替 `TEXT_BLOCKED` 等错误类型。

**错误类型：**

`EditorController` 通过独立错误码返回失败，不把错误码塞进 `syncStatus`：

`WRONG_MODE`、`TEXT_BLOCKED`、`DRAFT_INVALID`、`INVALID_STEP`、`DUPLICATE_STEP_ID`、`STEP_NOT_FOUND`、`INVALID_MOVE`、`INVALID_REPEAT_COUNT`、`SERIALIZATION_FAILED`。

失败操作必须保持状态不变，不得出现半更新。

**计算属性：**

- `hasBlockingErrors = parseResult.hasBlockingErrors`
- `canEnterCards = syncStatus == SYNCED && !hasBlockingErrors`
- 文本是否为未提交修改可按 `syncStatus == DIRTY` 判断。

当前阶段五实现**不包含** `lineBindings`、`draftRevision` 或 `persistedRevision`。行级诊断由 `parseResult` 提供；ID 匹配由 `StepIdMatcher` 基于语义步骤序列完成。

**`lastCommittedSteps` 的定位**：它只是提交成功后的只读快照。任何编辑（文字或卡片）都只能写 `RoutineDraft`；提交或卡片修改成功后，才刷新该快照。禁止从快照反向修改 `draft`，也不得把快照当作可编辑的第二份步骤列表。

### 2.5 逐行解析结果

当前实现使用 `ParsedLine`，字段为：

| 字段 | 类型 | 说明 |
|---|---|---|
| `lineNumber` | Int | 1 基逻辑行号。 |
| `raw` | String | 该行原始内容（保留用户输入，便于定位）。 |
| `status` | EMPTY / VALID / INCOMPLETE / INVALID | 行状态。 |
| `step` | StepCandidate? | VALID 时的无 ID 候选步骤。 |
| `diagnostic` | ParseDiagnostic? | 阻断行的稳定错误码（如 `INVALID_UNIT`）。 |

V1 不硬编码用户展示文案；UI 层负责把稳定错误码映射为本地化文案。

---

## 3. 文本语法 V1

### 3.1 文档与行

- 一个步骤一行；行顺序就是步骤顺序。
- 支持 `LF`、`CRLF`、`CR`；解析前统一规范化为 `LF`。
- 行号按规范化后的逻辑行计算，1 基。
- 空字符串视为一个空逻辑行；末尾换行不额外产生步骤。
- 空行、仅空白行是 `EMPTY`，不产生步骤，不阻断提交。
- 不支持注释和 Markdown 列表语法；`#`、`-` 等符号若出现在名称中，按名称字符处理，不是语法标记。
- V1 没有“多步骤分隔符”：一行最多按 3.3 的名称锚点解析为一个步骤。若一行中写了两段步骤样式，最后一段数量后缀生效，前面的内容可能被并入名称；无法可靠判定为“多步骤”，因此不承诺报 INVALID。
- 规范化序列化不输出空行，也不输出末尾换行。

### 3.2 语法定义

以下为描述性 EBNF；名称与数量后缀存在自然语言歧义，解析时以“行尾最后一个合法数量后缀”为锚点，见 3.3。

```ebnf
document        = { logical-line } ;
logical-line    = ws* [ step ] ws* ;

step            = name quantity [ repeat ] [ attribute ] ;

quantity        = count-quantity | timer-quantity ;
count-quantity  = number ws* "次" ;
timer-quantity  = number ws* unit ;

unit            = "秒" | "分钟" | "分" | "小时" | "时" ;

repeat          = ws* ( "x" | "X" | "×" ) ws* number ;

attribute       = ws* "[" attr-body "]" ;
attr-body       = "计数=完成"
                | "分类=休息"
                | "分类=普通" ;

number          = digit { digit } ;
digit           = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;

ws              = 一个 Unicode White_Space 字符，排除 CR/LF ;
name            = 去除首尾空白后非空、且不含 CR/LF 的字符序列 ;
```

约束：

- `name` 与 `quantity` 之间允许零个或多个 `ws`：`俯卧撑 15次` 和 `俯卧撑15次` 均合法。
- 无空格时，解析器以行尾最后一个数字串作为数量；若名称本身以数字结尾，尾随数字会与目标合并（见 3.3），需要保留名称尾部数字时应加空格。
- `number` 只接受 ASCII 数字；允许前导零，但数值必须 `> 0`；规范输出不带前导零。
- 不接受小数秒、毫秒或亚秒写法；`1.5秒`、`0.5分钟`、`1500毫秒` 均为 INVALID。
- `repeat` 与 `attribute` 都是可选的，顺序固定为“数量 → 重复 → 属性”。
- 最多一个属性；多个属性为 INVALID。
- 属性必须位于行尾；属性之后出现任何非空白内容为 INVALID。

### 3.3 名称与数量锚点

- 名称可以包含空格、制表符、数字、`[`、`]`、`x`、`×`、`=` 等字符。
- 名称与数量之间允许没有空白；解析时从行尾向前寻找最后一个合法数量后缀。
- 数量后缀由“最后一个连续数字串 + 可选空白 + 单位”构成；它之前的内容（去除首尾空白）是名称。
  - `俯卧撑15次` → 名称 `俯卧撑`，COUNT，target 15。
  - `俯卧撑 15次` → 名称 `俯卧撑`，COUNT，target 15。
  - `阅读20分钟` → 名称 `阅读`，TIMER，20 分钟。
  - `第2组 深蹲20次` → 名称 `第2组 深蹲`，COUNT，target 20。
  - `第2组15次` → 名称 `第2组`，COUNT，target 15。
  - `做 15次 俯卧撑 20次` → 名称 `做 15次 俯卧撑`，COUNT，target 20。
- **名称尾部数字歧义**：若名称以数字结尾且与数量之间没有空格，最后的连续数字串会整体作为数量。例如 `Room1015次` 会被解析为名称 `Room`、target 1015，而不是 `Room101` + 5。需要保留名称尾部数字时，必须加空格：`Room101 5次`。
- **小数拒绝**：先剥离可选的重复/属性后缀，再检查数量中的数字串；若其紧邻前一个字符是 `.`、`．`、`,` 或 `，`，该行视为小数写法并判为 INVALID，不得把小数点并入名称。例如 `阅读1.5秒`、`阅读0.5分钟`、`阅读1.5秒 x 2` 均为 INVALID。
- 名称去除首尾空白后不能为空；否则 INVALID。
- 名称中的内部连续空白保留，不做折叠。
- 名称不能包含换行；卡片编辑必须拒绝换行。
- 名称是否可以以属性样式结尾不作为限制，因为属性位于数量之后；序列化后不会产生歧义。
- 名称长度上限**待确认**；V1 不设硬上限。

### 3.4 数量、单位与重复

| 单位 | 含义 | 毫秒换算 | 规范输出 |
|---|---|---|---|
| `秒` | 秒 | 1,000 | `秒` |
| `分钟` / `分` | 分钟 | 60,000 | `分钟` |
| `小时` / `时` | 小时 | 3,600,000 | `小时` |

- `次` 表示 COUNT；`秒`/`分钟`/`分`/`小时`/`时` 表示 TIMER。
- `x N`、`X N`、`× N` 均表示步骤总执行次数，包含第一次；`N >= 1`。
- `x` 与 `×` 两侧空白可省略；`45秒x2`、`45秒 × 2` 均合法。
- `repeatCount == 1` 时规范输出省略 `x 1`；`repeatCount > 1` 时输出 ` x N`。
- 重复次数只属于 `RoutineStep`；流程级重复不进入文本。
- 时长精度固定为 1 秒：内部仍存 `durationMs`，但必须是 1000 的整数倍且 `>= 1000`。
- V1 不接受小数秒和毫秒：`1.5秒`、`0.5分钟`、`1500毫秒` 均为 INVALID；`毫秒` 不是合法单位。
- 数值边界：
  - `target`、`repeatCount` 使用有符号 32 位整数范围 `1..2,147,483,647`。
  - `amount` 和 `durationMs` 使用有符号 64 位整数；乘法必须做溢出检查，溢出即 INVALID，不得崩溃或回绕。
  - `durationMs` 必须 `>= 1000` 且 `% 1000 == 0`。

### 3.5 属性

| 属性 | 适用类型 | 含义 | 规范输出条件 |
|---|---|---|---|
| `[计数=完成]` | COUNT | `countMode = COMPLETE` | 仅当 `countMode == COMPLETE` |
| `[分类=休息]` | TIMER | `category = REST` | 仅当显式分类与名称推断结果不同，且实际为 REST |
| `[分类=普通]` | TIMER | `category = NORMAL` | 仅当显式分类与名称推断结果不同，且实际为 NORMAL |

规则：

- COUNT 默认 `INCREMENT`；V1 **不提供** `[计数=递增]` 的文本写法。显式写 `[计数=递增]` 为 INVALID；是否未来支持**待确认**。
- TIMER 默认分类按名称推断：名称包含“休息” → REST，否则 NORMAL。
- 显式 `[分类=...]` 优先级高于名称推断。
- `[分类=休息]` 与推断结果相同是合法的，但规范输出会省略冗余属性。
- `[分类=普通]` 与推断结果相同是合法的，但规范输出会省略冗余属性。
- COUNT 没有 `category`；`[分类=...]` 用于 COUNT 为 INVALID。
- TIMER 没有 `countMode`；`[计数=完成]` 用于 TIMER 为 INVALID。
- 属性键和值只接受上述中文精确写法；英文 `NORMAL`/`REST`/`COMPLETE`、`[分类=正常]`、空格包围 `=`、全角方括号等均为 INVALID；是否需要本地化别名**待确认**。
- 属性内部不允许空白：`[计数 = 完成]` 为 INVALID。
- 属性前允许零个或多个空白；规范输出一个 ASCII 空格。
- 属性必须最后；`[计数=完成] x 2` 为 INVALID。

### 3.6 默认值与优先级

1. 未写重复次数 → `repeatCount = 1`。
2. COUNT 未写属性 → `countMode = INCREMENT`。
3. COUNT 写 `[计数=完成]` → `COMPLETE`。
4. TIMER 未写属性 → 名称包含“休息”则 REST，否则 NORMAL。
5. TIMER 写 `[分类=休息]` → REST。
6. TIMER 写 `[分类=普通]` → NORMAL。
7. 显式分类优先于名称推断。
8. 名称推断只用于 TIMER；COUNT 没有 REST/NORMAL 分类。`休息 15次` 的类型由单位决定为 COUNT，是否应禁止这种组合**待确认**。
9. 不支持 MANUAL；没有任何 V1 文本写法可以生成 MANUAL。

### 3.7 规范化序列化

序列化器输入一个已确认的 `RoutineDraft`（或其步骤列表），输出规范化文本；它不输出 ID 和 `order`。

单步格式：

```text
COUNT:
  {name} {target}次[ x {repeat}][ [计数=完成]]

TIMER:
  {name} {duration}[ x {repeat}][ 分类属性]
```

其中：

- `name` 使用已规范化的名称（首尾已去除空白）。
- `[ x {repeat}]` 仅当 `repeat > 1`。
- `[ [计数=完成]]` 仅当 `countMode == COMPLETE`。
- TIMER 的分类属性规则：
  - `inferred = name.contains("休息") ? REST : NORMAL`
  - `category != inferred` 时输出：REST → ` [分类=休息]`，NORMAL → ` [分类=普通]`
  - `category == inferred` 时省略。
- `duration` 使用整秒规范格式（`durationMs` 必须 `>= 1000` 且为 1000 的整数倍）：
  - 若 `durationMs % 3,600,000 == 0` → `{h}小时`
  - 否则若 `durationMs % 60,000 == 0` → `{m}分钟`
  - 否则若 `durationMs % 1,000 == 0` → `{s}秒`
  - 否则为非法 Draft（V1 最小单位 1 秒），校验应在序列化前拒绝；序列化器返回错误而不是输出文本。
- `x` 统一输出小写 `x`；单位统一输出 `分钟`、`小时`。
- 步骤按 `order` / 列表顺序逐行输出，不输出空行。
- 多行用 `\n` 连接，末尾不加换行。
- 空步骤列表序列化为空字符串。
- 序列化是原子的：任一步骤不可表示时整体失败，不得输出部分文本。
- 序列化应是幂等的：`serialize(parse(serialize(steps))) == serialize(steps)`（在可表示范围内）。

示例：

| 步骤 | 规范化文本 |
|---|---|
| COUNT 15 INCREMENT repeat 1 | `俯卧撑 15次` |
| COUNT 15 COMPLETE repeat 1 | `俯卧撑 15次 [计数=完成]` |
| COUNT 15 COMPLETE repeat 2 | `俯卧撑 15次 x 2 [计数=完成]` |
| TIMER 20 分钟 NORMAL repeat 1 | `阅读 20分钟` |
| TIMER 60 秒 REST repeat 1，名称含“休息” | `休息 60秒` |
| TIMER 45 秒 NORMAL repeat 2 | `拉伸 45秒 x 2` |
| TIMER 45 秒 REST repeat 2，名称不含“休息” | `拉伸 45秒 x 2 [分类=休息]` |
| TIMER 60 秒 NORMAL，名称含“休息” | `休息 60秒 [分类=普通]` |
| TIMER 90 秒 NORMAL | `90秒` |
| TIMER 120 秒 NORMAL | `2分钟` |
| TIMER 3,600,000 毫秒 NORMAL | `1小时` |

---

## 4. 解析状态

### 4.1 状态定义

| 状态 | 定义 | 是否阻断提交 | 是否阻断进入卡片 |
|---|---|---|---|
| `VALID` | 完整符合语法与语义约束，可生成候选步骤 | 否 | 否 |
| `EMPTY` | 空行或仅空白行 | 否 | 否 |
| `INCOMPLETE` | 输入可继续补全为合法行，且当前尚未违反已知约束 | 是 | 是 |
| `INVALID` | 无法补全为合法行，或完整但违反语法/语义约束 | 是 | 是 |

`hasBlockingErrors = any(INCOMPLETE || INVALID)`。

### 4.2 解析结果要求

- 解析器必须为每个逻辑行返回结果，包括 `EMPTY` 行；不得通过丢弃空行改变行号。
- 解析器不得因一行错误而不返回其他行。
- 解析器不得生成持久化 ID，不得分配 `order`；候选步骤只包含领域字段，ID/order 由提交阶段生成。
- 解析器对同一输入必须确定性输出；不得包含随机 ID。
- 行号必须基于规范化后的逻辑行；CRLF 不得导致行号偏移。
- V1 建议每行一个主要诊断；错误消息仅用于展示，测试应优先断言状态和字段。

### 4.3 INCOMPLETE 判定

V1 规则：

1. 行可以继续追加字符后成为合法行，且当前没有完整但错误的“数量 + 单位 + 重复 + 属性”结构；
2. 已出现完整数量，但可选的重复/属性尚未闭合；
3. 行尾是属性前缀，例如 `[`、`[计数=`、`[计数=完`；
4. **裸名称**（只有名称、尚无数量/单位）属于 INCOMPLETE，例如 `俯卧撑`、`阅读`、`休息`。

典型示例：

- `俯卧撑`（裸名称，INCOMPLETE）
- `阅读`（裸名称，INCOMPLETE）
- `俯卧撑 15`
- `俯卧撑 15 `
- `俯卧撑 15次 x`
- `俯卧撑 15次 [` 
- `俯卧撑 15次 [计数=`
- `俯卧撑 15次 [计数=完`
- `俯卧撑 15次 [计数=完成`
- `拉伸 45秒 x 2 [分类=休`
- `俯卧撑 15次 x 2 [计数=完成`

注意：最后两例在语法上是前缀，补全后可能因类型不匹配或属性键错误变成 INVALID；在未补全前按 INCOMPLETE 处理。

### 4.4 INVALID 判定

以下任一情况为 INVALID：

- 已有完整数量，但单位未知或数值非法（`0`、负号、小数、中文数字、溢出）。
- 数字后跟随单位样式但单位未知，例如 `15个`、`1500毫秒`。
- 小数秒、毫秒或其他亚秒写法：`1.5秒`、`0.5分钟`、`1500毫秒`。
- 完整数量后出现无法解释的尾随内容。
- 属性键/值未知、内部有空白、重复、顺序错误、类型不匹配。
- 完整语法但语义不兼容（例如 COUNT 使用 `[分类=休息]`，TIMER 使用 `[计数=完成]`）。
- 显式使用 V1 未定义的写法，例如 `[计数=递增]`、**数量中**的全角数字、**属性语法中**的全角方括号/等号。名称中的全角字符本身允许。

### 4.5 状态示例

| 输入 | 状态 | 说明 |
|---|---|---|
| `俯卧撑 15次` | VALID | 默认 INCREMENT |
| `俯卧撑15次` | VALID | 名称与数量无空格 |
| `俯卧撑 15次 [计数=完成]` | VALID | COMPLETE |
| `阅读 20分钟` | VALID | NORMAL |
| `阅读20分钟` | VALID | 名称与数量无空格 |
| `阅读 1秒` | VALID | 最小时长 1 秒 |
| `休息 60秒` | VALID | 名称推断 REST |
| `休息 60秒 [分类=普通]` | VALID | 显式覆盖为 NORMAL |
| `拉伸 45秒 x 2 [分类=休息]` | VALID | 显式 REST + 重复 2 |
| `拉伸45秒x2` | VALID | 名称、数量、重复均无空格 |
| `俯卧撑 15` | INCOMPLETE | 缺单位 |
| `俯卧撑` | INCOMPLETE | 裸名称 |
| `阅读` | INCOMPLETE | 裸名称 |
| `俯卧撑 15次 x` | INCOMPLETE | 缺重复数 |
| `俯卧撑 15次 [计数=完成` | INCOMPLETE | 属性未闭合 |
| `俯卧撑 abc次` | INVALID | 数字非法 |
| `俯卧撑 15个` | INVALID | 未知单位 |
| `阅读1.5秒` | INVALID | 不支持小数秒 |
| `阅读 0.5分钟` | INVALID | 不支持小数分钟 |
| `阅读 1500毫秒` | INVALID | 不支持毫秒 |
| `俯卧撑 15次 [分类=休息]` | INVALID | COUNT 不允许 category |
| `阅读 20分钟 [计数=完成]` | INVALID | TIMER 不允许 countMode |
| `俯卧撑 15次 [计数=递增]` | INVALID | V1 未定义显式 INCREMENT |
| `俯卧撑 15次 [计数=完成] x 2` | INVALID | 属性必须在最后 |
| `俯卧撑 15次 [计数=完成] extra` | INVALID | 属性后有多余内容 |
| `""` | EMPTY | 空文档 |
| `"   "` | EMPTY | 仅空白 |

---

## 5. 文本 → RoutineDraft 提交

### 5.1 提交流程

1. 规范化换行，按逻辑行解析。
2. 生成逐行诊断；保留 `textBuffer` 原文。
3. 若存在任何 `INCOMPLETE`/`INVALID`：
   - 不修改 `draft`；
   - 不修改 `lastCommittedSteps`、`lastCommittedText`；快照保持上一次成功提交状态（只读）；
   - `syncStatus = INVALID`；
   - 模式保持 TEXT；
   - 原始文本完整保留，非法行不删除；
   - 禁止进入卡片模式；
   - 此时调用 `commitText()` 返回 `TEXT_BLOCKED` 错误，不改变 Draft。
4. 若所有非空行均 VALID 或 EMPTY：
   - 提取候选步骤（无 ID）；
   - 与最近成功快照做 ID 匹配（第 6 节）；
   - 为无法匹配的新步骤生成新 UUID；
   - 对每个步骤执行领域校验；
   - 原子替换 `draft.steps`，步骤顺序以列表顺序为准；
   - 提交成功后才刷新只读快照：`lastCommittedSteps = draft.steps 的副本`，并更新 `lastCommittedText = 规范文本`；
   - `textBuffer` 更新为规范文本；
   - `syncStatus = SYNCED`。
5. 任一领域校验或序列化失败：视为阻断/失败错误，不提交，保留原草稿与诊断；返回独立错误码（如 `DRAFT_INVALID`、`INVALID_STEP`、`SERIALIZATION_FAILED`），不修改同步状态。

### 5.2 关键约束

- **全有或全无**：有错误时，其他合法行的修改也不提交。修复所有错误后一次性提交。
- 不能因为某行不完整就用“部分解析结果”覆盖已确认步骤集合。
- 空文档（无步骤）是合法状态：提交空步骤列表；卡片模式允许进入空状态。
- 仅空行/空白行：提交空步骤列表，不阻断。
- 提交成功后，`textBuffer` 被替换为规范文本；空行、额外空格、冗余属性和单位写法会被规范化。
- 保存策略：存在阻断错误时是否允许保存“最近成功版本”**待确认**；V1 不建议静默保存旧数据而不提示。

---

## 6. 步骤 ID 匹配与稳定性

文本不携带 ID，因此文本重新解析后需要把候选步骤匹配回已有步骤，尽量保留 ID。匹配是确定性算法，双端必须实现一致。

### 6.1 输入

- `oldSteps`：最近成功快照 `lastCommittedSteps` 中的步骤（带 ID；**只读**，不是可编辑数据源）。
- `newCandidates`：当前全部 VALID 行解析出的候选步骤（无 ID）。
- 当前实现不维护 `lineBindings`；LCS 使用每个步骤的规范语义行键，空行不参与步骤匹配。

### 6.2 语义匹配键

```text
semanticKey = (
  type,
  name,
  target,
  countMode,
  durationMs,
  category,
  repeatCount
)
```

不包含 `id`、`order`、原始文本格式。

### 6.3 匹配算法

**阶段 1：完全匹配（优先）**

按 `newCandidates` 的行顺序遍历；对每个候选，在尚未匹配的 `oldSteps` 中寻找 `semanticKey` 相同的最早步骤，继承其 ID。

- 未修改的步骤即使被移动，也能按内容匹配并保留 ID。
- 相同内容的重复步骤按出现顺序先进先出匹配；重复步骤之间交换顺序时 ID 可能互换，这是不可观察差异，见 6.5。

**阶段 2：行替换匹配（次优先）**

在阶段 1 之外，使用完整旧步骤序列和新候选序列做 LCS 锚点，再在锚点之间处理剩余步骤：

1. 将旧步骤序列和新候选序列转换为规范语义行键（每步一行；空行跳过）。
2. 使用确定性的 LCS 得到锚点，并据此得到替换/删除/插入区块；两端使用同一平局规则（优先删除旧行）。
3. 对每个“替换”区块，按顺序把剩余旧步骤与新候选一一配对，最多配对 `min(oldCount, newCount)` 对。
4. 若旧步骤尚未匹配、新候选尚未匹配，且两者 `type` 相同，则把旧 ID 转给新候选。
5. 空行、无步骤行跳过；纯删除/纯插入不转移 ID。
6. 已被占用的 ID 不得复用。

**文本模式类型变更边界**：若替换行的 `type` 从 COUNT 变为 TIMER 或反向，行级 diff 无法可靠判断这是“同一个步骤改类型”还是“删除旧步骤、新增步骤”，因此**允许不继承旧 ID、生成新 ID**。这是文本模式的边界，不影响卡片模式；卡片模式必须保留 ID（见第 7 节）。

**阶段 3：新建**

仍未获得 ID 的新候选，在提交时生成新的 UUID。

**收尾**

- 未匹配的旧步骤视为删除。
- 按最终列表顺序归一化 `order`。
- 校验所有 ID 唯一；重复即视为内部错误，不得提交。

### 6.4 保证与不保证

| 场景 | ID 是否稳定 | 说明 |
|---|---|---|
| 文本重新解析但内容不变 | 是 | 阶段 1 完全匹配 |
| 模式切换 | 是 | 切换本身不重新分配 ID |
| 卡片修改普通字段 | 是 | 按 ID 更新，ID 不变 |
| 文本中单行修改属性/名称/数量 | 是 | 阶段 1 失败后由阶段 2 继承 |
| 文本中调整行顺序 | 是 | 阶段 1 按内容匹配 |
| 文本中新增/删除其他行 | 是 | 其余步骤阶段 1 匹配 |
| 两个完全相同的步骤互换位置 | 不可观察 | FIFO 可能交换 ID，但数据等价 |
| 同时移动并修改同一行 | 尽力而为 | 行 diff 可能无法判断，可能分配新 ID |
| 同一 diff 区块内同时插入与修改 | 尽力而为 | 行级 diff 可能把插入行与修改行配错；有精确编辑范围时优先使用 |
| 卡片模式修改 COUNT/TIMER 类型 | **是（必须）** | 按 ID 更新类型及字段，ID 保持不变 |
| 文本模式修改 COUNT/TIMER 类型 | **允许新 ID** | 行替换无法可靠匹配类型变更；可视为删除+新增，不强制继承 ID |

若编辑器能提供精确的编辑范围/行操作（insert/delete/replace），应优先使用精确范围替代通用 diff；但必须满足上表的稳定性保证。

### 6.5 测试要求

双端至少覆盖：

- 内容不变重解析；
- 单行名称/目标/时长/重复/分类/计数模式修改；
- 无空格输入（`俯卧撑15次`）与带空格输入之间切换格式后 ID 不变；
- 行重排；
- 插入新行；
- 删除行；
- 修改一行同时新增一行；
- 两个相同步骤；
- 同时移动并修改（记录当前尽力结果，不强制稳定）；
- 模式切换前后 ID 不变；
- 卡片模式 COUNT↔TIMER 类型修改后 ID 不变；
- 文本模式 COUNT↔TIMER 类型修改允许生成新 ID，且不破坏其他步骤 ID；
- `lastCommittedSteps` 只读，不从快照回写 `draft`。

---

## 7. 卡片 → 文本更新

### 7.1 卡片操作

| 操作 | 对 Draft 的影响 | ID 规则 |
|---|---|---|
| 新增步骤 | 追加/插入到列表 | 创建时生成新 UUID |
| 修改普通字段 | 按 ID 替换字段 | ID 保持不变 |
| 修改 COUNT/TIMER 类型 | 按 ID 替换类型并清理/重建类型字段 | **ID 保持不变** |
| 删除步骤 | 按 ID 删除 | 其他步骤 ID 不变 |
| 排序 | 调整列表顺序 | 所有步骤 ID 不变 |
| 修改流程名称/流程重复 | 修改 Routine 级字段 | 不涉及步骤 ID，不改变文本 |

### 7.2 更新策略

1. 卡片操作先构造候选变更，执行领域校验。
2. 校验通过后原子修改 `RoutineDraft`；`update` 不允许替换 `id`。
3. 类型变更时按 ID 定位步骤，保留 `id`：COUNT → TIMER 清空 `target`/`countMode` 并设置合法的 `durationMs`/`category`；TIMER → COUNT 清空 `durationMs`/`category` 并设置合法的 `target`/`countMode`。类型字段不完整时校验失败，不提交。
4. 卡片中的时长输入以整秒为单位，写入 `durationMs` 时乘以 1000；小数秒/毫秒输入直接拒绝。
5. 若变更影响步骤数据，立即调用序列化器生成完整文本。
6. 序列化成功后：
   - `textBuffer = canonicalText`；
   - `lastCommittedText = canonicalText`；
   - `lastCommittedSteps = draft.steps` 的只读快照；
   - `parseResult` 更新为规范文本的解析结果；
   - `syncStatus = SYNCED`。
7. 序列化失败（如名称含换行、非法字段、不可表示的时长）：
   - 本次操作返回独立错误码 `SERIALIZATION_FAILED`；
   - 回滚本次卡片变更；
   - 保留旧 Draft、旧文本、旧快照和旧解析结果；
   - 不修改 `syncStatus`；
   - 向用户显示具体步骤和原因；
   - 不允许带着该错误离开卡片模式。
8. 若卡片操作是 no-op（数据未变化），不重写文本，避免无意义格式化。
9. 当前阶段五 Controller 未实现流程名称/流程重复的卡片更新接口；后续 UI/流程编辑层接入时，流程名称或重复次数只修改 Draft，不影响文本。

### 7.3 格式化影响

- 卡片编辑成功后，文本被替换为规范化文本。
- 用户此前的空行、额外空格、`分`/`时` 等写法、冗余属性会被规范化。
- 这是语义无损下的格式规范化；产品已确认**不要求**字节级保留，见第 9.4 节。

---

## 8. 模式切换

- `TEXT → CARDS`：仅当 `syncStatus == SYNCED` 且没有 `INCOMPLETE`/`INVALID` 时允许。
  - `DIRTY`/`INVALID` 时拒绝切换，保持 TEXT，展示行号与原因。
  - 空草稿允许进入卡片模式，显示空状态。
- `CARDS → TEXT`：尝试对当前 Draft 执行完整序列化；成功后切换并更新规范文本，失败则返回 `SERIALIZATION_FAILED` 并留在 CARDS。
- `TEXT → CARDS` 不重新解析文本；`CARDS → TEXT` 使用序列化后的规范文本生成新的 `parseResult`，但不重新匹配或分配 ID。
- 模式切换本身不得重新分配 ID、不得改变步骤顺序。
- 若切换前文本刚提交，直接使用 `RoutineDraft`；不需要再次 parse。
- CARDS 模式下文本与 Draft 始终同步，切回 TEXT 时使用当前规范文本。

---

## 9. 无损转换

### 9.1 定义

本契约的“无损”指**语义无损**：

- 步骤类型、名称、target、countMode、durationMs、category、repeatCount 全部保留；
- 步骤顺序保留；
- 步骤 ID 在允许的匹配规则下保留；
- 流程名称、流程级重复次数保留在 Draft 中（它们不属于文本语法）；
- 不静默丢弃、截断、舍入或改变任何字段。

产品已确认：只要求语义无损，**不要求**保留原始空格、单位写法或其他文本格式；卡片编辑后的规范化文本可以与原文不同。

### 9.2 保留与规范化

| 内容 | 是否保留 |
|---|---|
| 步骤字段 | 保留 |
| 步骤顺序 | 保留 |
| 步骤 ID | 通过匹配保留；新建步骤生成新 ID |
| 空行 | 解析时保留行号，但卡片编辑后规范化文本不保留 |
| 行首/行尾空白 | 规范化去除 |
| 单位写法 `分`/`时` | 规范化为 `分钟`/`小时` |
| `X`/`×` | 规范化为 `x` |
| 冗余属性（与推断相同） | 规范化省略 |
| 前导零 | 规范化为无前导零 |
| 属性前空格 | 规范化为一个空格 |
| 注释、Markdown 标记 | 不支持；按名称字符处理或 INVALID |

### 9.3 不可表示状态

以下状态不能由 V1 文本表示，序列化必须失败并阻止卡片提交/模式切换：

- MANUAL 步骤；
- `durationMs < 1000` 或不是 1000 的整数倍（V1 最小 1 秒，不支持小数秒/毫秒）；
- `durationMs <= 0`、`target <= 0`、`repeatCount < 1`；
- 名称为空、仅空白、含换行；
- COUNT/TIMER 字段组合非法；
- `countMode`/`category` 缺失；
- 数值超出第 3.4 节范围；
- 任何未知枚举值。

失败时不得输出部分文本，不得静默丢弃步骤。

### 9.4 字节级保留（已确认不要求）

产品已确认只要求语义无损，不要求“卡片编辑后文本逐字不变”。因此 V1 不保存每步骤原始文本片段或格式信息；空行、空格、单位写法和冗余属性都可以被规范化。若未来改为要求字节级保留，需要新增格式字段，属于后续设计，不影响当前契约。

---

## 10. 校验不变量

### RoutineStep

- `id` 非空且唯一。
- `name` 在模型边界统一 trim；trim 后非空；不含 `\r`/`\n`。
- `type` 仅 COUNT/TIMER；MANUAL 拒绝。
- COUNT：`target > 0`；`countMode != null`；`durationMs == null`；`category == null`。
- TIMER：`durationMs >= 1000`；`durationMs % 1000 == 0`；`category != null`；`target == null`；`countMode == null`。
- `repeatCount >= 1`。
- `order >= 0` 且与列表索引一致（若保留该字段）。
- 序列化前再次确认：`durationMs >= 1000` 且 `% 1000 == 0`，数值在范围内。

### RoutineDraft

- `id` 非空。
- `repeatCount >= 1`。
- 步骤 ID 唯一。
- 步骤顺序与 `order` 一致。
- 流程名称是否允许为空、默认名称、最大长度**待确认**。

### Routine

- `id` 非空；`repeatCount >= 1`；步骤不变量成立。
- `version` 语义**待确认**。
- `createdAt <= updatedAt`（若两者均存在）。

---

## 11. 边界条件

| 场景 | V1 行为 |
|---|---|
| 空文档 `""` | EMPTY；提交空步骤；允许卡片模式 |
| 仅空行/空白行 | EMPTY；提交空步骤；不阻断 |
| 末尾换行 | 形成一个末尾 EMPTY 行（不产生步骤）；规范化序列化不输出末尾换行 |
| CRLF/CR | 规范化为 LF；行号不偏移 |
| 全角空格 U+3000 | 作为 `ws`；可用于名称/数量分隔 |
| 制表符 | 作为 `ws` |
| 首尾空白 | 解析时忽略 |
| 名称与数量之间 | 允许零个或多个空白；`俯卧撑15次` 与 `俯卧撑 15次` 等价；规范输出一个空格 |
| 名称以数字结尾且无空格 | 尾随数字会并入数量（如 `Room1015次` → target 1015）；需保留名称尾部数字时加空格 |
| 前导零 | 接受，按数值解析，规范输出去除 |
| `0`/`00` | INVALID（数值必须 > 0） |
| 负数/小数/中文数字 | INVALID |
| 小数秒/毫秒 | INVALID；V1 仅整秒，最小 1 秒 |
| 数量中的全角数字 | INVALID（V1 数量仅 ASCII 数字） |
| 属性中的全角方括号/等号 | INVALID；名称中的全角字符允许 |
| `x`/`X`/`×` | 均合法；规范输出 `x` |
| `分`/`时` | 合法；规范输出 `分钟`/`小时` |
| 名称含“休息” | TIMER 推断 REST；显式分类可覆盖 |
| 名称含数字/方括号/`x` | 允许；以行尾数量为锚点 |
| 两个完全相同的步骤 | 允许；ID 按 FIFO 匹配 |
| 一行多个步骤 | V1 无分隔符；最多按名称锚点解析为一步，前段可能并入名称，不承诺 INVALID |
| 注释/Markdown | 不作为语法；符号按名称处理或导致 INVALID |
| 超长名称/超多步骤 | V1 未设硬上限；**待确认**性能与产品上限 |
| 非整秒/毫秒时长 | V1 不支持；校验拒绝或序列化错误；`durationMs` 必须是 1000 的倍数且 `>= 1000` |
| MANUAL | V1 不支持 |

---

## 12. 待确认问题

以下不是本契约可以单方面决定的产品问题，必须在实现前确认：

1. **显式默认值**：是否接受 `[计数=递增]`、`[分类=正常]`、英文枚举值？V1 不接受。
2. **COUNT 名称含“休息”**：`休息 15次` 是否应允许？V1 按单位判为 COUNT，无分类。
3. **流程重复 × 步骤重复**：执行时如何组合？不属于编辑器契约，需执行阶段定义。
4. **错误时保存**：存在 INCOMPLETE/INVALID 时，是否允许保存最近成功版本？如何提示？
5. **未提交文本的持久化**：是否需要在崩溃/重启后恢复非法文本 buffer？
6. **`Routine.version` 语义**：schema 版本、内容修订号，还是两者分离？
7. **名称/步骤数量上限**：是否需要产品级最大长度和最大步骤数？
8. **本地化**：属性键值、单位、错误消息是否只支持中文？
9. **REST 推断的误判**：`休息日规划 20分钟` 会推断为 REST；是否改为更精确的规则？
10. **精确文本编辑范围**：UI 是否能提供插入/删除/替换范围以提升 ID 匹配？若不能，使用行级 diff。

---

## 13. 共享测试样例

文件：[`shared-spec/parser-cases.json`](../shared-spec/parser-cases.json)。

结构：

```json
{
  "input": "一行文本",
  "status": "VALID | INCOMPLETE | INVALID | EMPTY",
  "expected": {
    "name": "步骤名称",
    "type": "COUNT | TIMER",
    "target": 15,
    "countMode": "INCREMENT | COMPLETE",
    "durationMs": null,
    "category": null,
    "repeatCount": 1
  }
}
```

约定：

- 该文件是**行级**样例，不包含多行文档级用例。
- `VALID` 时 `expected` 必须包含全部字段；不适用字段显式为 `null`。
- `INCOMPLETE`、`INVALID`、`EMPTY` 时 `expected` 为 `null`。
- 不包含 ID/order，因为解析器不得生成持久化 ID/order。
- 多行、CRLF、空行隔离、全有或全无提交等属于文档级/集成测试，应在双端测试中另行覆盖；V1 暂不扩展为混合 schema。
- 样例覆盖：COUNT/TIMER 默认值、`[计数=完成]`、显式 REST/NORMAL、优先级、重复次数、单位、有/无空格、裸名称 INCOMPLETE、整秒边界、小数/毫秒 INVALID、名称锚点、前导零、溢出、空行。
