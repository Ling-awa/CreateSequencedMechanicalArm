# 路线图 · Roadmap

> 这是**打算做什么**，不是承诺。细节都还在商榷中，优先级由实际使用中的需要决定。
> 已经做完的部分见 [README](README.md)。

## 计划中（会用，但还没开始）

### 1. 「过滤」操作（新操作类型）

用**机械动力自己的过滤机制**（`FilterItem` / 过滤槽）做判断，判断对象可以是：

- **可取物目标上的物品**（例如置物台上的物品）；
- 或者**臂手上的物品**。

并给出**成功 / 失败两条出口** —— 与现有条目的「成功后 / 失败后」完全一致，
于是"过滤 + 跳转"就能拼出**分流**（能掏的走一条路、不能掏的走另一条）。

### 2. 操作导入方块

对标机械动力的**蓝图桌**（给空白蓝图导入结构）：给「操作表」一个**导入**途径 ——
把一份已经写好的操作/程序导入到**空白操作表**上，不用一件一件手录。

> 待定：导入来源是脚本文本、另一张操作表，还是方块自己的一个槽位。

### 3. 相对坐标 ⇄ 绝对坐标的转化

现在「绝对坐标操作表」**插不进动力臂**（会明确拒绝并提示"请先复制到相对坐标操作表"），
但**没有提供转化途径**。计划补上正规做法：把绝对卡转成相对卡（以及反向）。
可能会采取其他转化方式。

> 待定：在哪个界面/方块里做、是否支持批量、相对化时以哪条臂为基准，是否采用相对坐标 + 定一个原点方块的方式。

### 4. 从动力臂中复制操作

现在只有开发者命令 `/sma export`（把臂里的程序写进主手的操作表）。
计划给玩家一个正规途径：**把臂里正在用的程序取回一张操作表** —— 换卡、备份、搬家都要用。
相对卡复制相对坐标，绝对卡复制绝对坐标。这部分仍然可能修改，比如结合第3点，不在使用两张卡而是提供一种卡内的坐标转化方式。

### 5. 美术资源

目前**没有任何自己的贴图**（仓库里连 `textures/` 目录都还没有），全部沿用占位 / Create 的资源：

| 位置 | 现状 |
|---|---|
| 方块 | 直接引用 Create 的动力臂模型 `create:block/mechanical_arm/block` |
| 物品·动力臂 | `create:block/mechanical_arm/item` |
| 物品·操作表（相对 / 绝对） | 分别是原版的 `paper` / `map` |
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
3. **Relative / absolute coordinate conversion** — an absolute operation table currently cannot be
   inserted into an arm (it is refused with a hint) and there is no way to convert one form into
   the other yet.
4. **Copy operations out of an arm** — today only the developer command `/sma export` can do this;
   a proper player-facing way is planned.
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
form; re-record them or import a script.

**Undecided:** an in-game editor for `.sma` script text (may not happen — it overlaps with the GUI
editor).

**Internal polish:** dead code in the editor, network packet field caps, datapack script scanning
plus `/sma code apply`.

**Deliberately out of scope:** cross-mod integration, and any new block families beyond the arm and
its operation table.
