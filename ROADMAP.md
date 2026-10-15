# 路线图 · Roadmap

> 这是**打算做什么**，不是承诺。细节都还在商榷中，优先级由实际使用中的需要决定。
> 已经做完的部分见 [README](README.md)。

## 计划中（会用，但还没开始）

### 1. 「过滤」操作（新操作类型）—— ✅ 已实现

用**机械动力自己的过滤机制**做判断（幽灵槽里放什么滤波器就用什么规则：普通物品 = 物品过滤、
`create:filter` = 列表过滤、`create:attribute_filter` = 属性过滤、`create:package_filter` = 包裹过滤），
判断对象二选一：

- **手持物品**：臂此刻握着的那一份存货；
- **外部容器**：动力臂能直接交互的目标方块，**只看它的第一格（第一个非空槽）**。

并给出**成功 / 失败两条出口** —— 与现有条目的「成功后 / 失败后」完全一致，
于是"过滤 + 跳转"就能拼出**分流**（能掏的走一条路、不能掏的走另一条）。

### 2. 操作导入方块

对标机械动力的**蓝图桌**（给空白蓝图导入结构）：给「操作表」一个**导入**途径 ——
把一份已经写好的操作/程序导入到**空白操作表**上，不用一件一件手录。

> 待定：导入来源是脚本文本、另一张操作表，还是方块自己的一个槽位。

### 3. 原点的设置界面与「整体偏移」—— ✅ 已实现

坐标统一成**全部相对坐标**（相对**执行它的那条臂**）+ 一个**卡上的原点**（只做录制/渲染的参考系）。已落地：

- **在世界里设原点**：手持操作表按**中键** —— 指着哪就把它设为原点；原点在世界里画成**完整方块的蓝色框**，
  操作的框仍是黄/红（用户口径：无论方块什么形状、有没有方块，都是完整方块那么大的蓝框）；
- **界面里改数值**：任务列表页底部两颗按钮 ——「原点坐标」（输入 `x y z`，留空 = 清原点）
  与「坐标偏移」（输入 `dx dy dz`，确认后施加到**所有任务里所有有目标条目**的 offset 上，一次点击 = 一步撤销）；
- **原点存在卡上**，不在程序里、也不在 `.sma` 文本里（用户第四十轮定的：原点对运行时没有意义，
  `offset` 永远是"相对执行它的那条臂"，所以它只作为卡上的参考值存在）。

> 一个刻意的区分：**改原点**不影响任何目标的位置（只是换个参考点），
> **整体偏移**才是"把东西搬走"。合成一个按钮会出现"我只想改参考点，结果东西全跑了"。

### 4. 从动力臂中复制操作

现在只有开发者命令 `/sma export`（把臂里的程序写进主手的操作表）。
计划给玩家一个正规途径：**把臂里正在用的程序取回一张操作表** —— 换卡、备份、搬家都要用。
导出带走的是**程序本身**（文本）：条目坐标全是"相对执行它的那条臂"的偏移，插到哪条臂就相对哪条臂执行
（原点是**卡上**的参考值，不随程序走 —— 它只影响录制与渲染，不影响运行时）。

### 5. 美术资源

目前**没有任何自己的贴图**（仓库里连 `textures/` 目录都还没有），全部沿用占位 / Create 的资源：

| 位置 | 现状 |
|---|---|
| 方块 | 直接引用 Create 的动力臂模型 `create:block/mechanical_arm/block` |
| 物品·动力臂 | `create:block/mechanical_arm/item` |
| 物品·操作表 | 原版的 `paper` |
| 界面 | 没有自己的 UI 贴图 |

计划补：**UI 贴图、物品贴图、方块模型与贴图** —— 让它在视觉上是一条**自己的**臂，
操作表也有自己的样子。

### 6. 基础内容

目前**没有添加任何配方**（只能在创造模式物品栏里拿到）。

> ✅ **已完成**：原先这里的第 7 条「程序的存储改为以文本为准（载入时编译）」已经做完（0.2.0）。
> 卡片与臂现在盘上**只存 `.sma` 文本**，编译结果只在内存里；效果见下面的「现在就会遇到的限位」。
> 它还顺带把第 2 条「操作导入方块」与第 4 条「从臂中复制操作」要用的那条路铺好了。

## 现在就会遇到的限位

- **一份程序的条目上限 ≈ 100 ～ 3400 条**（不再是几十条）：盘上存的是脚本，天花板是 **60 KiB 文本**。
  实测单条最轻 **18 字节**（只有操作本身）、最重 **596 字节**（备注写满 128 字 + 图标 + 频率），
  所以朴素的纯 ASCII 脚本能放到几千条，而**每条备注都写满中文时约 100 条**（汉字一个字 3 字节）。
- 超限时**明确报错**：不会静默丢条目，也不会只存一半。单个条目备注上限 **128 字**。
- 配置项 `maxEntriesTotal` 默认 **1024**（名义上限，可在配置里调；调得比文本装得下的还大没有意义）。
- 这一层**不可能再往上抬了**：Minecraft 的 NBT 字符串是 16 位长度（65535 字节），一个字符串装不下更多 ——
  要更多只能改成多行 / 多段存。
- ⚠️ **0.1.0 之前的存档里已有的程序不会被读取**：那时盘上存的是编译结果，本版起只认文本
  （卡片与臂都一样），重进世界后它们是空的，重录或导入脚本即可。

## 待定（可能不做）

### 1. 游戏内操作代码编辑 UI

`.sma` 脚本现在只能通过命令与文件进出（`/sma code export | check | import`）。
"在游戏里直接编辑脚本文本"的 UI **尚未决定是否要做**：它与 GUI 编辑器功能重叠，
真要做还得解决多行输入、行号、错误定位这些问题。

## 内部打磨（不改变玩法，但会做）

- 编辑器里的死代码（`unitSuffix` / `boolProp` / `TextButton` / 重复的 `opName`）；
- 网络包字段上限（防超长备注/图标把包体撑爆）；
- 数据包脚本扫描 + `/sma code apply`（让脚本能随数据包分发）。

## 不在计划内（有意为之的设计取向）

- **跨模组适配**：不读任何其它模组的数据；
- **新增方块族**：只做这条臂和它的操作表，尽量不添加额外内容。

---

## English

This is a **plan, not a promise** — details are still being discussed and may change.

**Planned (not started):**

1. **Filter operation** — a new entry type that uses Create's own filtering (FilterItem) to test the
   item sitting on a takeable target (e.g. a depot) or the item the arm is holding, with separate
   **success / failure exits** so filtering plus jumps can build a splitter.
2. **Operation import block** — the equivalent of Create's Schematic Table for blank schematics:
   load a finished set of operations onto a blank operation table instead of recording every entry
   by hand. (Source still undecided: script text, another table, or a slot in the block.)
3. **Origin editing and "shift everything"** — coordinates are now unified (one origin per program, everything
   relative to it; the old "relative card / absolute card" split is gone), but there is no UI to change the
   origin yet. Planned: set the origin in world with the **middle mouse button** (currently swallowed while
   holding a table) and draw it as a **blue outline** (operation outlines stay gold/red) so relative targets
   become visible too; plus numeric editing of the origin and a **whole-program offset** in the GUI, both
   undoable. The two are deliberately separate: changing the origin keeps every target's world position,
   shifting moves them.
4. **Copy operations out of an arm** — today only the developer command `/sma export` can do this;
   a proper player-facing way is planned. The export carries the origin (the arm's own position) with it,
   so the program still points at the same places on another arm.
5. **Art** — there are no textures of our own yet (no `textures/` directory at all): the block
   reuses Create's mechanical arm model, the items reuse Create's and vanilla textures, and the UI
   has no textures. UI, item, block model and block textures are all still to be made.
6. **Base content** — there are no recipes yet (the items are creative-inventory only).

> ✅ **Done:** the former item 7, "text-authoritative storage (compiled on load)", shipped in 0.2.0.
> Cards and arms now store **only `.sma` text** on disk; the compiled form lives in memory. See the
> limits below, and note it also laid the groundwork items 2 and 4 need.

**Limits you will hit today:** a single program holds roughly **100 – 3400 entries** — the stored form is
script text capped at **60 KiB**. Measured: the leanest entry is **18 bytes**, the heaviest (128-char note
+ icon + frequency pair) **596 bytes**, so a plain ASCII script reaches a few thousand entries while one
whose every note is full of CJK text lands near 100 (3 bytes per CJK character). Exceeding it is a clear
error, never a silent drop or a half-saved program; a single note is capped at 128 characters. The config
option `maxEntriesTotal` defaults to **1024** (nominal — raising it beyond what the text limit holds buys
nothing). This ceiling **cannot be raised further**: a Minecraft NBT string is 16-bit (65535 bytes), so
storing more would require splitting the program across several strings/lines.
⚠️ **Programs already in saves from 0.1.0 or earlier are not read** — the old format stored the compiled
form; re-record them or import a script. **Cards from before the coordinate change are gone too**: the
"absolute operation table" item was removed, and old scripts use the retired `@abs` header.

**Undecided:** an in-game editor for `.sma` script text (may not happen — it overlaps with the GUI
editor).

**Internal polish:** dead code in the editor, network packet field caps, datapack script scanning
plus `/sma code apply`.

**Deliberately out of scope:** cross-mod integration, and any new block families beyond the arm and
its operation table.
