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

目前**没有添加任何配方**。

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

**Undecided:** an in-game editor for `.sma` script text (may not happen — it overlaps with the GUI
editor).

**Internal polish:** dead code in the editor, network packet field caps, datapack script scanning
plus `/sma code apply`.

**Deliberately out of scope:** cross-mod integration, and any new block families beyond the arm and
its operation table.
