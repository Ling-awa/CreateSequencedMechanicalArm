# 列车时刻表 GUI 全解（Create 6.0.10 实测源码整理）

来源：`.scratch/create-6.0.10-280-src/…/trains/schedule/` 下的 `ScheduleScreen`(1128 行)、`ScheduleMenu`、
`ScheduleEntry`、`Schedule`、`IScheduleInput`、`ScheduleEditPacket`、`DestinationSuggestions`、
`condition/*`、`destination/*`，以及 `foundation/gui/{ModularGuiLine, ModularGuiLineBuilder}`。

---

## 0. 一句话概括它的交互哲学

**列表页只放"卡片 + 胶囊"两种可点物件，所有编辑都在弹出的子面板里完成；卡片上永远只显示"当前值"，
默认值不占位置。** 点击热区不是控件，而是自己算坐标（`x = mx-leftPos-25`）—— 所以它敢在卡片里塞
图标、胶囊、滚动箭头这些东西，而不受 vanilla 控件系统限制。

---

## 1. 窗口与骨架

| 项 | 值 |
|---|---|
| 基类 | `AbstractSimiContainerScreen<ScheduleMenu>`（= `AbstractContainerScreen` + 窗口偏移/工具提示/浮动控件） |
| 窗口尺寸 | `AllGuiTextures.SCHEDULE` = **256×226**（`setWindowSize`），无烘焙文字 |
| 标题 | 屏幕自带 `title`，居中画在 `topPos+4`，颜色 `0x505050`（`renderBg:1022-1026`） |
| 底部按钮带 | y = `topPos+196`：循环开关 `leftPos+21`（+状态指示器）、重置进度 `leftPos+45`、跳过进度 `leftPos+63`；右下确认键 `leftPos+214, topPos+196`（`-42, -30`） |
| 窗口外右下 | 手里那张时刻表的**物品预览**，`GuiGameElement.of(contentHolder).at(leftPos+256, topPos+170).scale(3)`（`renderForeground:987-991`） |
| JEI 避让 | `getExtraAreas()` 返回那块预览区 `(leftPos+256, topPos+170, 48, 48)` |
| 保存时机 | `removed()`（关界面）→ 整份 `Schedule` 打包成 `ScheduleEditPacket` 发服务端（`:1074-1078`） |
| 编辑时 | 整屏压暗 `fillGradient(0,0,w,h,-1072689136,-804253680)`，z=200（`:1032-1036`） |

`ScheduleMenu` 是一个 **ghost-item 菜单**（上游 `HeldItemGhostItemMenu`）：绑主手物品、2 个幽灵槽
（给条件选择物品用）、玩家背包画在 `(leftPos+38, topPos+122)`，编辑时 `slotsActive` 控制背包能不能点。

---

## 2. 贴图清单（每一段用在哪）

| 贴图 | 尺寸 | 用途 |
|---|---|---|
| `SCHEDULE` | 256×226 | 整个窗口 |
| `SCHEDULE_EDITOR` | 256×89 | 子面板编辑器（画在 `leftPos-2, topPos+40`） |
| `PLAYER_INVENTORY` | 176×108 | 编辑器里的玩家背包底板 |
| `SCHEDULE_CARD_LIGHT/MEDIUM/DARK` | 1×1 | 卡片四层拉伸用的**纯色块**（浅/中/深） |
| `SCHEDULE_STRIP_DARK/LIGHT` | 3×1 | 列表左侧竖轨 / 卡片左内侧竖条 |
| `SCHEDULE_STRIP_TRAVEL` / `_ACTION` | 11×16 | 卡片左上的"类型图标"（有条件 / 无条件的动作） |
| `SCHEDULE_STRIP_WAIT` | 11×16 | 卡片左下的"等待"图标 |
| `SCHEDULE_STRIP_DOTTED` | 11×16 | 卡片之间的虚线连接 |
| `SCHEDULE_STRIP_END` | 11×16 | 末尾"新动作"卡的上方端点 |
| `SCHEDULE_CARD_REMOVE` | 12×12 | 卡片右上 × |
| `SCHEDULE_CARD_MOVE_UP` / `_MOVE_DOWN` | 12×12 | 卡片右外侧 ↑ / ↓ |
| `SCHEDULE_CARD_DUPLICATE` | 12×12 | 卡片右下"复制" |
| `SCHEDULE_CARD_NEW` | 16×16 | 末尾"新动作"卡的 ＋ |
| `SCHEDULE_CONDITION_LEFT` / `_LEFT_CLEAN` / `_MIDDLE` / `_RIGHT` | 6/2/1/2×16 | 胶囊的左端（带/不带凹口）、中段（拉伸）、右端 |
| `SCHEDULE_CONDITION_ITEM` | 18×16 | 胶囊里"有物品图标"时的那一格底 |
| `SCHEDULE_CONDITION_APPEND` | 10×10 | 胶囊列底部的"＋ 追加" |
| `SCHEDULE_CONDITION_NEW` | 19×16 | 新增一列（备选条件）的 ＋ |
| `SCHEDULE_SCROLL_LEFT` / `_RIGHT` | 4×8 | 胶囊区横向滚动的左右箭头 |
| `SCHEDULE_POINTER` / `_OFFSCREEN` | 21/13×16 | 左侧"当前进度指针"（指到正在执行的那条；超出视野时用 offscreen 版） |
| `SCHEDULE_EDITOR_ADDITIONAL_SLOT` / `_INACTIVE_SLOT` | 32×18 / 18×18 | 编辑器里的物品槽（幽灵槽 / 空槽占位） |
| `DATA_AREA` / `_START` / `_END` / `_SPEECH` | 1/2/2/5×18 | **编辑器第二行控件的底框**（左右端 + 拉伸中段；speech = 带小尖角） |

---

## 3. 列表页排版（坐标全部相对 `leftPos/topPos`）

```
┌─ 窗口 256×226 ──────────────────────────────────────────┐
│ 标题（居中, y+4）                                        │
│                                                          │
│   x=16 ──────────────────────── x=236                    │  ← 裁剪区 (16,16)-(236,189)
│    ┆(x=33 竖轨 STRIP_DARK, y=16..189)                    │
│    ┆   ┌─ 卡片 195 宽 (画在 x=25) ─────────┐             │
│    ┆   │ 标题芯片(26,5,高16)   [×](181,2)  │  y=25 起     │
│    ┆   │ 胶囊行1 (26,29,高16)  [↑](195,8)  │             │
│    ┆   │ 胶囊行2 (26,47)       [↓](195,22) │             │
│    ┆   │ ＋追加(列中)          [复制](181,h-14)│          │
│    ┆   └──────────────────────────────────┘             │
│    ┆   ┆虚线(x=29)┆  ← 卡间距 10                          │
│    ┆   ┌─ 下一张卡 ─┐                                    │
│    ┆   …                                                │
│    ┆   [STRIP_END + CARD_NEW] 末尾"新动作"卡              │
│  上/下 10px 淡出条 (fillGradient)                        │
│                                                          │
│ y+196: [循环][重置][跳过]                    [确认✓]      │
└──────────────────────────────────────────────────────────┘
   右下窗外：手里时刻表的 3× 物品预览
```

数值（`renderSchedule:388-485` / `renderScheduleEntry:487-537`）：

- 竖轨：`drawStretched(leftPos+33, topPos+16, 3, 173, z=200, STRIP_DARK)`
- 裁剪：`enableScissor(leftPos+16, topPos+16, leftPos+236, topPos+189)`
- 卡片起点：`yOffset = 25`；每张卡 `translate(leftPos+25, topPos+yOffset)`；画完 `yOffset += cardHeight`；
  若后面还有卡：`STRIP_DOTTED.render(leftPos+29, topPos+yOffset-3)` 然后 `yOffset += 10`
- 列表头：第一张卡（或空列表）上方补一段 `drawStretched(leftPos+33, topPos+16, 3, 10, STRIP_LIGHT)`
- 末尾新卡：`yOffset += 9` 后画 `STRIP_END(leftPos+29)` + `CARD_NEW(leftPos+43)`
- 淡出：顶 `fillGradient(16,16,236,26, 0x77..→0x00..)`、底 `fillGradient(16,179,236,189, 0x00..→0x77..)`
- **进度指针**：`savedProgress == i` 时在 `leftPos` 画 `SCHEDULE_POINTER`，y = `clamp(cardY+4, topPos+18, topPos+170)`
  （被夹住时换成 `POINTER_OFFSCREEN`）

---

## 4. 一张卡片里面有什么

**尺寸公式**（`:497-501`）：
```
maxRows   = max(每列的条件数量)
cardHeight = 22 (CARD_HEADER)
           + (支持条件 ? 24 + maxRows*18 : 4)
```
`instruction.supportsConditions()` 决定卡片有没有条件区（例如"限制最高速度""更新标题"就没有）。

**美术（`:507-525`，逐层）**：
1. `drawStretched(0, 1, 195, h-2, light)`：浅色描边
2. `drawStretched(1, 0, 193, h, light)`：浅色描边
3. `drawStretched(1, 1, 193, h-2, dark)`：深色内衬
4. `drawStretched(2, 2, 191, h-4, medium)`：中色卡面
5. `drawStretched(2, 2, 191, 22, light)`：标题带
6. `drawStretched(8, 0, 3, h+10, STRIP_LIGHT)`：左内侧竖条
7. `STRIP_TRAVEL`/`STRIP_ACTION` 画在 `(4,6)`；`STRIP_WAIT` 画在 `(4,28)`

**热区（都在卡片局部坐标里）**：

| 位置 | 区域 | 行为 | 工具提示文案 |
|---|---|---|---|
| 标题芯片 | `x∈(25, 25+size]`, `y∈(4,20]` | 左键 → 打开**指令编辑器** | "左键点击编辑" |
| 右上 | `x∈(181,193]`, `y∈(0,14]` | 左键 → 删除该动作 | 移除动作 |
| 右下 | `x∈(181,193]`, `y > h-14` | 左键 → **复制**该动作（插在自己前面） | 复制 |
| 右外侧 | `x>194`, `y∈(7,20]` | 左键 → 上移 | 上移 |
| 右外侧 | `x>194`, `y∈(20,33]` | 左键 → 下移 | 下移 |
| 横向箭头 | `x∈(12,19]` / `x∈(177,184]`, y 在卡中间 | 左键 → 胶囊区左右滚动一列 | — |
| 胶囊 | `(x-18, y-28)` 后按列宽/行高切 | **左键 → 打开条件编辑器**；**右键 → 删除该条件**（工具提示会写"右键点击移除"） | 条件类型 + "左键点击编辑" |
| 追加 ＋ | 该列最后一行的下一行居中 10×10 | 左键 → 打开"添加条件"（默认 `ScheduledDelay`） | 添加条件 |
| 左侧空白 | `x∈(0,15]`, `y<=20` | 左键 → 添加一列**备选条件（OR）** | 备选条件 |
| 末尾新卡 | `x∈[18,33]`, `y∈[newY-4, newY+14]` | 左键 → 新建一条（先在编辑器里选指令，确认才真的建） | 添加动作 |

**点击热区的判定顺序**：标题芯片 → 删除/复制 → 上移/下移 → 横向箭头 → 胶囊 → 追加 → 备选列 → 新卡。
判定前先做窗口/裁剪区检查（`x∈[0,205)`、`y∈[0,173)`）。

---

## 5. 卡片里"条件"那一块的结构（重点）

数据是 **`List<List<ScheduleWaitCondition>>` = 列(OR) 的列表，每列里是行(AND)**。

- 渲染：一列一列横着排；每列宽 = `max(该列所有条件的胶囊宽)`，列间距 10；**每行高 18**，从 `(26,29)` 起。
  一列里的每个胶囊：`clean = (行号 != 0)`（第一行左端带凹口，后面的用 CLEAN 版接上去）。
- 胶囊宽度 = `max(文字宽 + (有图标?20:0) + 16, 最小宽度)`，最长 150。
- 列底部画 `CONDITION_APPEND`；所有列之后再画 `CONDITION_NEW`（新增一列）。
- 溢出：整块胶囊区超过卡片宽度时，可以**横向滚动**（每张卡一个 `LerpedFloat horizontalScrolls[i]`），
  并在卡片中间两侧显示 `SCROLL_LEFT/RIGHT` 箭头；Shift+滚轮或点箭头翻列。
- 裁剪：胶囊区单独 `enableScissor(leftPos+43, 0, leftPos+204, 400)`，并在需要滚动时叠两条竖直淡出。

**语义**（运行时）：一列里的条件全部满足（AND）→ 该列满足；**任意一列满足即可发车**（OR）。
所以"等待 5s"和"无线开启"放在同一列 = 两个都要满足；放在两列 = 满足其一即可。

---

## 6. 交互总表

| 输入 | 位置 | 行为 |
|---|---|---|
| 左键 | 见上表 | 编辑 / 删除 / 复制 / 上下移 / 翻列 / 追加 / 新建 |
| 右键 | 胶囊 | **删除该条件**（仅当"该列多于 1 行 或 多于 1 列"时才允许删） |
| 滚轮 | 列表空白 | 垂直滚动：`target -= Δ*12`，`clamp(0, Σ(卡高+10)+…)`，`chase(…, 0.7f, EXP)`（缓动） |
| Shift+滚轮 | 卡片胶囊区 | 横向翻列（逐列 chase） |
| 左键（编辑器内） | 确认✓ / 删除✗ | `onClose(true)` / `onClose(false)` |
| Esc / E | 编辑器 | 关闭界面（`keyPressed` 里 E 直接 return false → 交给 vanilla 关屏） |
| Enter | 编辑器（焦点在文本框） | 等于按确认✓ |
| 悬停 | 任何热区 | 画工具提示（`renderActionTooltip`），列表页每帧用 `action(graphics, …, click=-1)` 跑一遍只为出提示 |

编辑器打开时：列表页的 `action()` 直接返回 false（不响应），只能操作编辑器里的控件。

---

## 7. 编辑器子面板（两行控件）

```
        ┌──────────── SCHEDULE_EDITOR 256×89 (画在 leftPos-2, topPos+40) ────────────┐
        │  标题：指令编辑器 / 条件编辑器（居中, topPos+44）                            │
        │                                                                             │
        │  第一行  y=65..81      ┌ 类型选择框 143×16 (leftPos+56) ┐  [✓](+168, +22)  │
        │                        └ + 文字 Label (leftPos+59,+69) ┘  [✗](-45,  +22)   │
        │                                                                             │
        │  第二行  y=87..105     ┌ 该类型的配置控件（ModularGuiLine）┐  ┌ 物品槽 18×18 ┐│
        │                        └ 底框 DATA_AREA_START/MIDDLE/END ┘  └ (leftPos+53) ┘│
        │                                                                             │
        │  背包：PLAYER_INVENTORY 176×108 (leftPos+38, topPos+122)                     │
        └─────────────────────────────────────────────────────────────────────────────┘
```

- **第一行**永远是"类型选择框"：`SelectionScrollInput(143×16)` + `Label`，内容是
  *指令类型* 或 *条件类型* 的列表（`Schedule.getTypeOptions(...)`）。滚轮切换类型时**当场换对象**
  （新建一个实例，把旧的值丢掉），并重建第二行控件。
- **第二行**是该类型的配置控件，由 `ModularGuiLineBuilder` 生成，只支持 5 种元素：
  | 构建器方法 | 生成什么 | 备注 |
  |---|---|---|
  | `addScrollInput(x, w, transform, key)` | `ScrollInput`（18 高）+ Label | 数字，可设 range / shiftStep / 锁定提示位置 |
  | `addSelectionScrollInput(x, w, transform, key)` | `SelectionScrollInput` | 枚举/布尔选择 |
  | `addIntegerTextInput(x, w, transform, key)` | `EditBox`（无边框）+ `TooltipArea` | 只允许整数 |
  | `addTextInput(x, w, transform, key)` | `EditBox`（无边框）+ `TooltipArea` | 自由文本（车站名等） |
  | `customArea(x, w)` / `speechBubble()` | 预留空框 / 给第一个框加小尖角 | 特殊图标用 |
  控件自己的底框是 `DATA_AREA_START/MIDDLE/END` 拉伸出来的（**这就是编辑器里那个灰框**）。
  `key` 是存 NBT 用的字段名；`save(editing.getData())` 在关闭时把值写回对象。
- **物品槽**：跟着类型走。`slotsTargeted()` = 0 → 画 `EDITOR_INACTIVE_SLOT` 或该类型的特殊图标；
  1..n → 画 `EDITOR_ADDITIONAL_SLOT` + 用 ghost 槽里的物品（过滤器/参考物品）。玩家从背包把物品
  拖进幽灵槽来设置过滤。
- **确认/取消语义**：`✓` = `onClose(true)`（提交），`✗` = `onClose(false)`。
  调用方的回调决定"提交后放在哪"：编辑已有条件 → 先 `remove(row)`，确认才 `add(row, editing)`；
  取消且该列空了 → 整列删掉。新建动作 → 取消就不建。
- **滚回与重建**：`stopEditing()` 里先 `save()` 再 `init()`，所以整屏会重画一次。

---

## 8. 数据模型

```
Schedule { List<ScheduleEntry> entries; boolean cyclic; int savedProgress; }
ScheduleEntry { ScheduleInstruction instruction; List<List<ScheduleWaitCondition>> conditions; }
```
`IScheduleInput` 是"能在卡片上显示、能在编辑器里编辑"的统一接口：

| 方法 | 作用 |
|---|---|
| `Pair<ItemStack, Component> getSummary()` | **卡片上那行胶囊的文字（+可选物品图标）** |
| `List<Component> getTitleAs(String type)` | 工具提示标题（如"调度延迟" + "5 秒后发车"） |
| `ItemStack getSecondLineIcon()` | 编辑器物品槽空时显示的图标（如中继器=延时） |
| `int slotsTargeted()` / `getItem(i)` / `setItem(i, stack)` | 需要几个幽灵槽 |
| `List<Component> getSecondLineTooltip(i)` | 幽灵槽的提示 |
| `void initConfigurationWidgets(ModularGuiLineBuilder)` | **第二行配置控件** |
| `boolean renderSpecialIcon(graphics, x, y)` | 自己画特殊图标（不走物品槽） |
| `read/write(registries)` | NBT |

**5 种指令**（卡片第一行 / 编辑器第一行选择框的选项）：
| id | 中文名 | 卡片摘要 | 编辑器第二行 |
|---|---|---|---|
| `destination` | 前往车站 | "下一站：<车站名>" + 车站物品图标 | 车站名文本框（121 宽，带**自动补全下拉**、`*` 通配符）+ 小尖角底框 |
| `package_delivery` | 传递包裹 | "将货存中的第一个包裹运往对应地点" | 无（0 个控件） |
| `package_retrieval` | 检索包裹 | "提取要发往指定地址的包裹" | 地址文本框（带通配符提示） |
| `rename` | 更新时刻表标题 | "新标题：<文本>" | 标题文本框 |
| `throttle` | 限制最高速度 | "最高速度调整到 <值>" | 数字滚动输入（节流阀） |

**9 种条件**（卡片胶囊 / 编辑器第二行）：
| id | 中文名 | 胶囊文字 | 编辑器第二行 |
|---|---|---|---|
| `delay` | 调度延迟 | "等待：5s" | 数字滚动（0..120，Shift+15）+ 单位选择（秒/分…） |
| `time_of_day` | 一天中的时间 | "调度时间：<时刻>" | 时刻数字 + 轮换选择（每0:15/每1:00/每天…） |
| `fluid_threshold` | 流体货物条件 | "列车持有 <量> <流体>" | 比较符选择 + 数字 + 流体槽 |
| `item_threshold` | 物品货物条件 | "列车持有 <N> 组 <物品>" | 比较符选择 + 数字 + 度量（组/个/桶）+ **物品幽灵槽** |
| `redstone_link` | 无线红石 | "无线开启" / "无线关闭" | 频率显示 + 充能/未充能选择 |
| `player_count` | 玩家入座 | "1 玩家"（复数另有一键） | 数字滚动 + "恰好/或以上"选择 |
| `idle` | 装卸停止 | "装卸停止：<N> 秒" | 数字滚动 |
| `unloaded` | 区块卸载 | — | 无 |
| `powered` | 车站被充能 | "等待红石信号" | 无 |

---

## 9. 从它身上能抄到的"手法"（与具体内容无关）

1. **卡片高度 = 22 + 24 + 行数×18**：行数由内容算出来，卡片因此天然自适应。
2. **两层嵌套拉伸做美术**：4 层 `drawStretched` 叠出"描边+内衬+面+标题带"，不需要画一张完整卡。
3. **胶囊三片法**：左端（可带凹口）+ 中段（拉伸）+ 右端 = 任意宽度的小标签，且能串起来。
4. **热区手算**：`x = mx-leftPos-25`、`y = my-topPos-25+scroll`，比塞 vanilla 控件简单得多，
   也不受控件系统限制（卡片上能放图标、箭头、胶囊）。
5. **悬停提示靠"空跑一遍 action"**：`action(graphics, mx, my, -1)`，click=-1 时只出提示不改数据。
6. **每个可编辑对象自己要"三件套"**：卡片摘要 / 工具提示 / 编辑器控件（`getSummary` / `getTitleAs` /
   `initConfigurationWidgets`）。
7. **配置控件行是数据类型无关的**：`ModularGuiLineBuilder` 只有 5 种元素，任何新类型都能拼出来，
   值按 `key` 存进对象的 NBT —— 加新类型不用动屏幕代码。
8. **编辑器用"草稿对象 + 回调"**：改的是副本，`onClose(confirmed)` 决定提交/丢弃，
   屏幕完全不知道业务语义。
9. **滚动是缓动的**（`LerpedFloat` + EXP chaser），不是直接跳；横向翻列同理。
10. **裁剪 + 边缘淡出**是"列表看起来是嵌在窗口里的"关键，少了它就会"卡片从窗口里漏出去"。
11. **`ScrollInput` 每次值变化都会播音**（`ScrollInput:147-153`：`SimpleSoundInstance.forUI(AllSoundEvents.SCROLL_VALUE…)`
    ⇒ **MASTER** 音源），一个 `soundPlayed` 标志在 `tick()` 里复位 —— 也就是说**只要值变了就响**。
    两条实际后果：① 任何"喂滚轮"的自检（我们 `checkWheel` 就是）会在标题界面炸出一串咔哒音；
    ② 想静音**不能**把 MASTER 音量调 0（`SoundManager.updateSourceVolume(MASTER, ≤0)` 会 `stop()` 整个声音
    引擎，连音乐一起），要用 NeoForge 的 `PlaySoundEvent` + `setSound(null)` 按音源过滤（音乐是 MUSIC 类）。
12. **滚轮分发的两个坑**（我们在 3c⁷ 踩满）：`getChildAt` 是**从前往后**扫第一个命中的控件，
    且它**只扫 `children()`**（可交互的），装饰性部件必须 `addRenderableOnly` 注册；否则一个盖在上面的
    Label 会把滚轮事件吃掉并返回 false，事件不会下沉到下层的输入框。
13. **`ScrollInput.clampState()` 是"到上界就退一格"**（`if (state >= max) state = max - 1`）：
    想要 `0..120` 含 120 就得传 `withRange(0, 121)`。

---

## 10. 我们（智能动力臂）与它的对应关系

**用户已定的落地方式**（2026-10-06）：尽量小的改动，尽量只用时刻表的贴图。

| 它的概念 | 我们的做法 | 状态 |
|---|---|---|
| 卡片 = 一个 `ScheduleEntry` | 卡片 = 一条操作 | ✔ 复刻卡片美术与全部热区 |
| 无分组 | **分两页**：任务列表 / 某任务的操作列表；都用同一套卡片画法 | ✔ 底部第一个按钮切换 |
| 指令（第一行选择框，5 种） | `OperationType`（9 种），仍然是"标题芯片点开 → 选择框" | ✔ |
| 条件 = **AND 行 × OR 列**，语义"等到满足" | 属性 = **一行**（每格一个属性），语义"参数"（成功/超时/时限…） | ✔ 用户定：**去掉 AND/OR**（= 上游"一列一行"的最简情形） |
| 列下面的行 | **不用**：修饰词直接写进胶囊（`成功跳转 3`） | ✔ 试过"子行"，用户否掉（"效果并不理想"） |
| `SCHEDULE_CONDITION_APPEND`（列底部追加） | **不用**（没有子行可加） | ✔ 见上 |
| `SCHEDULE_CONDITION_NEW`（新增一列） | **改成"添加属性"**，摆在顶层一行胶囊的末尾；已加过的自动排除 | ✔ |
| 编辑器左下角垃圾桶 | **删除**正在编辑的东西（属性=恢复默认 / 条目=整条删掉 / 添加中=取消添加）；"放弃修改"用 ESC | ✔ 照上游 `ScheduleScreen:879-882`（上游也只有"完成"+"删除"两颗键） |
| 第二行（值行）x 起点 | 上游 `editorSubWidgets.renderBg(getGuiLeft() + 77, …)`：物品只画在 54..70，从 77 起正好盖住槽框右侧空档 | ✔ 第一版从 91 起 → 左侧一条空档、右侧越过凹槽，用户实测"整体偏移" |
| 幽灵槽 / 物品过滤 | **幽灵槽 = 自定义图标**（取物=置物台、组装=黄铜手、攻击=铁镐…，默认表在 `ArmIcons`） | ✔ 同一套机制（`SCHEDULE_EDITOR_ADDITIONAL_SLOT` + 玩家背包 + `GhostItemSubmitPacket`） |
| 进度指针 | 指针 = **插入位置**（运行中同一标记也表示"当前步骤"）；点卡片背景把指针移过去 | ✔ |
| 横向翻列（Shift+滚轮 / 箭头） | 一行胶囊超宽时横向翻，**单位是"格"（一格一个胶囊）** | ✔ 照抄 `getConditionScroll:589-600`；按像素滚既慢又会把胶囊切一半 |
| 车站名自动补全 | — | ✔ 用户定：**不做** |
| 底部三键（循环/重置/跳过） | **任务列表 / 撤销 / 插入新操作到下方** | ✔ 位置与贴图不变 |
| 删除限制（时刻表至少留一条） | **允许删光** | ✔ 用户定 |
| 胶囊宽度 | 封顶 **150**（上游 `renderInput` 也是 `min(getFieldSize(...), 150)`） | ✔ 不封顶时长备注会撑出卡片（实测反馈） |
| 胶囊区裁剪 | **单独一块** `enableScissor(leftPos+43, 0, leftPos+204, 400)` + 两端 10px 淡出 | ✔ 复刻上游 `:457` / `:570-577`；不做的话内容会画到卡片外面（实测反馈） |
| 卡片高度 | 恒为 `22+24+18`（只有一行） | ✔ 上游 `cardHeight = 22 + (supportsConditions ? 24 + maxRows*18 : 4)` 取 maxRows=1 |
| 编辑器第二行 | **基础内容**（随动作切换：坐标 / 等待方式+时长+单位）或**属性值**，一律 DATA_AREA 盒子 + 无边框输入 | ✔ 复刻上游 `ModularGuiLine`；原版带黑框的 `EditBox` 用户嫌难看 |
| 编辑器第一行 | 动作=选择框；属性=**值**（属性名放到面板标题那行）；加属性=候选选择框 | ✔ 照上游"名字在上、值在下" |
| 属性表 | **跟动作走**：所有操作都能加"成功后跳转"；**会重试**的操作有「失败后」（= 0–120 × t/s/m 的重试窗口 + 去向 + 落点，四格都在第二行）；只有硬失败的操作（攻击）第二行只有去向 + 落点；前摇已删 | ✔ 第五轮定稿 |
| 数值型 ScrollInput 的上界 | Create 的 `ScrollInput.clampState()` 是 `if (state >= max) state = max - 1` —— **要选到 N 就得传 N+1** | ⚠️ 踩过：`withRange(0,120)` 只能选到 119（用户实测）；现在统一传 `max+1` |
| 装饰性 Label / TooltipArea | **必须只画不收事件**（`addRenderableOnly`） | ⚠️ 踩过：1.21 的滚轮分发是 `getChildAt(x,y).filter(消费)`，`getChildAt` 从前往后扫 —— 先注册又盖在输入框上的 Label 会把滚轮吃掉再返回 false，症状是"一会能滚一会不能" |
| Label 的宽度 | `new Label(...)` 把 width 设成**未截断**文本的宽度；长说明会撑到面板外 | ⚠️ 踩过：261px 的长 hint → 自检报"控件越界"；`textLabel` 现在把宽度钉到上限 |
| 淡出渐变方向 | 上游用 `TransformStack.rotateZDegrees(-90)` 把**上下**渐变转成左右；我们直接在卡片位姿里画，必须自己按列插值 | ⚠️ 踩过：去掉旋转后照抄坐标 → 渐变变成上下（用户实测）；现用自写 `hGradient` |
| 文字按钮 | 按钮贴图只有 18×18，宽按钮必须九宫格拉伸 | ⚠️ 踩过：`blit(..., min(width,18))` → 34px 按钮只画 18px 底，文字飘空（"图标与输入框之间一个大空隙"） |
| 横向箭头位置 | 上游画在**窗口**坐标 `leftPos+40` / `leftPos+203`；卡片位姿原点已是 `leftPos+25`，所以在卡片里要写 **15 / 178** | ⚠️ 踩过：直接抄 40/203 → 箭头比热区右移 25px（用户实测"太靠右"） |
| 行尾"添加属性"＋ | 贴图 `SCHEDULE_CONDITION_NEW` = 19×16，**＋ 号在贴图内第 8..13 列**，贴图画在 `x-3` → ＋ 中心在 `x+8` | ⚠️ 踩过：判定区按 `cursor±8` → 偏左 8px（用户实测"和新建条目那儿差不多"）；现左沿对齐标题芯片 x=26、右沿 `x+16` |
| 「加内容」何时落地 | **只在按「完成」时**写数据；选择框的滚动回调只记"当前选中项" | ⚠️ 上游 `ScrollInput` 每滚过一项就回调一次 —— 在回调里直接改数据会把沿途所有选项全加上（实测："加一个属性冒出一堆内容"） |
