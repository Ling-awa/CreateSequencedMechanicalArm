package io.github.Ling.create_sequenced_mechanical_arm.program;

/**
 * 操作类型（PLAN.md §4.2）。
 *
 * <p>{@code requiresTarget} 为真的操作必须先让手臂移动到目标；
 * 左右键（ATTACK/MINE）与 USE 的成败无法客观判定 —— 它们"发起即算完成"，
 * 因此也不需要为它们设超时（§7.7 v2 里超时只是"重试到点"的出口）。
 *
 * <p>★ 这个标志只有<b>一个</b>含义：<b>臂要不要伸过去</b>（运行时由 {@code ArmProgramEntry.movesArm()}
 * 转发）。它<b>不是</b>"这一条有没有世界坐标参数"—— 那个问题按条目问
 * {@code ArmProgramEntry.needsTarget()}（过滤的容器模式有坐标、却不移动，
 * 因为那个坐标是"读哪个方块"的数据参数）。两者混用就会让臂为了一次纯判断先飞过去。
 *
 * <p><b>控制类操作</b>（不做动作，只改程序的走向或做一次判断）：{@link #SIGNAL}（收发红石链接频率信号）、
 * {@link #CALL}（调用子任务）、{@link #BREAK}（退出当前任务）、{@link #THROW}（抛错并失败结束）、
 * {@link #FILTER}（过滤）。
 * 它们的文档见 §7.6「单线程 + 调用栈」。
 */
public enum OperationType {

	TAKE(true),
	DEPOSIT(true),
	UNLOAD(true),
	DEPLOY(true),
	/**
	 * 攻击：对着那一格**挥一击**，打格里的一只实体。
	 *
	 * <p>与机械手完全一致（用户第二十七轮定的）：**不筛选**（上游 `DeployerHandler:167-170` 唯一排除的是
	 * {@code AbstractContraptionEntity}）、**不判断格里有没有实体** —— 没有就只是挥空，这一条照样算完成。
	 * 想"等怪物走进来再打"就在前面加一条「等待」。
	 */
	ATTACK(true),
	/**
	 * 挖掘：**只挖方块，永远不碰实体**。
	 *
	 * <p>这正是把旧的 {@code PUNCH} 拆开的理由：旧实现是"实体优先、没有才挖方块"，于是玩家想挖方块、
	 * 只要那一格站着任何实体（玩家/宠物/盔甲架）就会去打它 —— 卡片上却写着"攻击"，属于静默误伤。
	 * 同样**不在动身前判断"能不能挖动"**：挥下去，进度由 vanilla 给（照机械手）。
	 *
	 * <p>⚠️ 旧卡里的 {@code PUNCH} 在载入时**迁到这里**（见 {@link #byName}）：老卡继续只挖方块，
	 * 不会因为这次拆分突然开始打实体。
	 */
	MINE(true),
	USE(true),
	USE_SNEAK(true),
	/**
	 * 过滤：拿一份物品去比"幽灵过滤器物品"，按 Create 本体那套过滤规则判符合 / 不符合。
	 *
	 * <p><b>为什么 {@code requiresTarget} 是 false</b>（用户口径：过滤是<b>控制类</b>操作，
	 * "判断过滤是一瞬间的事情，不需要动力臂伸过去判断"，跟「收发信号」一样<b>没有动作</b>）：
	 * 它<b>不移动手臂</b>、不做任何动作，只是一次纯判断 —— 所以这里的 false 就是
	 * "臂不要伸过去"那个判据（{@code ArmProgramEntry.movesArm()} 直接转发它）。
	 *
	 * <p>⚠️ 但过滤位置是<b>二选一</b>的条目级属性：{@code HELD}（手上那份存货，
	 * {@code SequencedArmBlockEntity.getHeldItem()}）没有坐标，{@code CONTAINER}（一个要坐标的外部容器）
	 * 有坐标 —— 那个坐标是"<b>读哪个方块</b>"的<b>数据参数</b>，不是"伸过去的目标"。
	 * 所以"这一条有没有坐标参数"要按<b>条目</b>问（{@link ArmProgramEntry#needsTarget()}）：
	 * GUI 的坐标行、整套平移 / 蓝图变换 / 世界描框 / 撤销比对 / 脚本写出全部走那一个方法。
	 * <b>两件事、两处判据</b>（一个问"要不要伸过去"、一个问"有没有坐标"），混用就会让臂先飞过去。
	 *
	 * <p>⚠️ 也正是因为这个 false，它<b>不会</b>出现在 {@link #recordable()}（世界里录进卡的操作）里 ——
	 * 过滤是纯判断、不对世界做动作，属于"在编辑器里加"的操作；
	 * 外部容器的坐标在编辑器第二行填。
	 */
	FILTER(false),
	WAIT(false),
	/**
	 * 收发信号：等到绑定的红石链接频率通电（或断电）为止 —— 频率从任务搬到了这里。
	 *
	 * <p>第二十七轮续十六起**收发合一**（§7.16）：模式见 {@code ArmProgramEntry.SignalMode}
	 * —— 收（默认，老卡行为不变）/ 发通电 / 发断电。发送不阻塞、也**没有失败出口**，
	 * 所以"有没有重试窗口"这件事只能按**条目**判（见 {@code ArmProgramEntry.hasRetryWindow()}）。
	 */
	SIGNAL(false, true),
	/** 调用另一个任务；被调任务跑完（或退出）后回到下一条。 */
	CALL(false),
	/** 立即结束当前任务：跳过剩余条目，回到调用方；在**主任务**里 = 收工停机（不重来）。 */
	BREAK(false),
	/** 立即结束当前任务并**失败**：调用方走"失败后"，主任务则停机报错。 */
	THROW(false);

	private final boolean requiresTarget;
	/**
	 * 这个操作有没有"收 / 发"这类**模式**（§7.16b 一.1）—— 决定菜单该激活几个幽灵槽、
	 * 编辑器要不要建模式控件、客户端自检期望几个槽位。
	 *
	 * <p>收在这里一处，是因为这三处原来各自写了一遍 {@code == WAIT_SIGNAL}：
	 * 改名字会漏改、加模式会不一致（"判据必须只有一处"）。
	 */
	private final boolean hasSignalMode;

	OperationType(boolean requiresTarget) {
		this(requiresTarget, false);
	}

	OperationType(boolean requiresTarget, boolean hasSignalMode) {
		this.requiresTarget = requiresTarget;
		this.hasSignalMode = hasSignalMode;
	}

	public boolean requiresTarget() {
		return requiresTarget;
	}

	/** 这个操作有没有收发模式的区分（目前只有 {@link #SIGNAL}）。 */
	public boolean hasSignalMode() {
		return hasSignalMode;
	}

	/** 这个操作是否**收发共用同一对频率**（目前只有 {@link #SIGNAL}）。 */
	public boolean usesSignalPair() {
		return this == SIGNAL;
	}

	/**
	 * 这个操作有没有「个数」参数（取出 / 放入）—— <b>这一个判据的唯一定义处</b>。
	 *
	 * <p>用户本轮口径：个数属性<b>同时服务于取出与放入</b>（放入以前是"整摞都塞"，现在也受个数约束）。
	 * 之所以收在枚举上而不是各处写 {@code op == TAKE || op == DEPOSIT}：它同时决定
	 * 编辑器属性表要不要列出这一项（{@code ArmProgramScreen.entryProps}）、卡片胶囊要不要显示
	 * （{@code ArmEntryText.props}，判据 {@code ArmProgramEntry.countIsSet()}）、
	 * 脚本 {@code count=} / {@code countmode=} 允不允许出现（{@code ScriptParser} / {@code ScriptDecompiler}）、
	 * 以及 {@code describe()} 报不报它 —— 五处各写一遍必然有一天只有四处改到。
	 */
	public boolean hasCount() {
		return this == TAKE || this == DEPOSIT;
	}

	/**
	 * 可以在世界里录进卡里的操作（= 所有需要坐标的操作），<b>顺序就是 Alt+滚轮的选择顺序</b>。
	 *
	 * <p>派生而不是另写一张表：之前 {@code OperationTableHandler} 手抄了一份 7 项清单，
	 * 以后加操作就会两边不一致（录不进去 / 选不到）。这里直接从枚举推导，只留一处真相。
	 */
	public static java.util.List<OperationType> recordable() {
		return java.util.Arrays.stream(values())
			.filter(OperationType::requiresTarget)
			.toList();
	}

	/**
	 * 这个操作是否**必须**指向一个 Create 交互点（`ArmInteractionPoint`）。
	 *
	 * <p>用户实测反馈（第二轮）："右键、潜行右键、攻击这种操作是不需要 create 交互方块的，
	 * 或者说是对任意方块都可以尝试"。所以只有真正需要跟方块**交换物品**的四类操作
	 * （取出 / 放入 / 卸货 / 组装）才要求它是交互点 —— 它们的实现要拿 `IItemHandler`，
	 * 而通用方块根本没有那个能力；攻击 / 挖掘 / 使用 / 潜行使用只是"伸过去对那个位置做一次动作"，
	 * 任何方块都能试（成败交给 {@code RightClickBridge} 判）。
	 *
	 * <p>这个判定在**两处**必须一致：录入时（能不能录进卡里）与运行时（伸不伸得过去）。
	 * 两处不一致会出现"能录但永远跑不动"或反过来 —— 所以收在这里一个方法里，别各写一份。
	 */
	public boolean needsInteractionPoint() {
		return this == TAKE || this == DEPOSIT || this == UNLOAD || this == DEPLOY;
	}

	// ⚠️ 「有没有重试窗口 / 有没有失败出口」**不再按类型判**：同一条 SIGNAL，收有、发没有。
	// 类型级谓词已搬到 ArmProgramEntry.hasRetryWindow() / hasFailureExit() / hasHardFailureExit()
	//（§7.16b 一.2：判据必须只有一处 —— 写在两处就会不一致）。
	//
	// ⚠️ 同理，「控制类操作」那个类型级谓词（CALL / BREAK / THROW / SIGNAL / FILTER）**已删**：
	// 运行时真正要问的是"臂要不要伸过去"（{@code ArmProgramEntry.movesArm()} = {@code requiresTarget()}），
	// 而"不移动"还有 WAIT 这类非控制类的成员 —— 一个枚举在这里再列一遍只会与那个判据分叉。

	/**
	 * 等待类操作：原地耗时间，**不碰目标**。
	 *
	 * <p>运行时靠它把手臂<b>收回待机位</b>再等（用户实测："动力臂执行等待的时候不会收回去"）——
	 * 等待期间爪子还举在上一条的目标前面，既难看又会挡住视线；收回是纯客户端插值，
	 * <b>不占用等待时间</b>（等待从进入该条目的那一 tick 就开始计时，收回与计时并行）。
	 *
	 * <p>控制类（调用 / 退出 / 抛错）<b>不算</b>等待：它们马上就切到别处去了，
	 * 中间插一次"收回"反而会在连续两条目标之间多绕一趟。
	 */
	public boolean isWait() {
		return this == WAIT || this == SIGNAL;
	}

	/** 宽松解析：未知名字回落到 {@code fallback}，不让坏数据把存档读崩。 */
	public static OperationType byName(String name, OperationType fallback) {
		if (name != null) {
			// ★ 旧名字 "PUNCH"（v27 之前）是"实体优先、没有才挖方块"的混合操作。拆开后**归到挖掘**：
			// 老卡继续只挖方块 —— 反过来映射的话，所有老卡里的这一条会突然开始打实体/打人。
			if (name.equalsIgnoreCase("PUNCH"))
				return MINE;
			// ★ 旧名字 "WAIT_SIGNAL"（第二十七轮续十六之前）是"只等信号"。改名成收发合一的 SIGNAL 后
			// 老名照旧解析到 SIGNAL，而老卡没有 sigmode 键 ⇒ 模式 = RECEIVE ⇒ 行为一字不变（兼容清单 1/2）。
			if (name.equalsIgnoreCase("WAIT_SIGNAL"))
				return SIGNAL;
			for (OperationType type : values()) {
				if (type.name()
					.equalsIgnoreCase(name))
					return type;
			}
		}
		return fallback;
	}

}
