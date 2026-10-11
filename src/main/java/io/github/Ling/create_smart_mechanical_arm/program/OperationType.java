package io.github.Ling.create_smart_mechanical_arm.program;

/**
 * 操作类型（PLAN.md §4.2）。
 *
 * <p>{@code requiresTarget} 为真的操作必须先让手臂移动到目标；
 * 左右键（ATTACK/MINE）与 USE 的成败无法客观判定 —— 它们"发起即算完成"，
 * 因此也不需要为它们设超时（§7.7 v2 里超时只是"重试到点"的出口）。
 *
 * <p><b>控制类操作</b>（不碰世界，只改程序的走向）：{@link #WAIT_SIGNAL}（等红石链接频率）、
 * {@link #CALL}（调用子任务）、{@link #BREAK}（退出当前任务）、{@link #THROW}（抛错并失败结束）。
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
	WAIT(false),
	WAIT_REDSTONE(false),
	/** 等到绑定的红石链接频率通电（或断电）为止 —— 频率从任务搬到了这里。 */
	WAIT_SIGNAL(false),
	/** 调用另一个任务；被调任务跑完（或退出）后回到下一条。 */
	CALL(false),
	/** 立即结束当前任务：跳出循环、跳过剩余条目，回到调用方（主任务则是收工）。 */
	BREAK(false),
	/** 立即结束当前任务并**失败**：调用方走"失败后"，主任务则停机报错。 */
	THROW(false);

	private final boolean requiresTarget;

	OperationType(boolean requiresTarget) {
		this.requiresTarget = requiresTarget;
	}

	public boolean requiresTarget() {
		return requiresTarget;
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

	/** 控制类操作：不碰世界、没有"做完"的语义（除了 CALL/WAIT_SIGNAL 有成败）。 */
	public boolean isControl() {
		return this == CALL || this == BREAK || this == THROW || this == WAIT_SIGNAL;
	}

	/**
	 * 这类操作失败时是"<b>重试到时限</b>"吗 —— 即它有没有一个**重试窗口**。
	 *
	 * <p>取 / 放 / 卸 / 组装 / 等红石（含等信号）：不成功就是"还没成功"，会原地重试到「失败后」的时限，
	 * 时限到了才走去向。{@link #canFail()} 为真但没有重试窗口的（攻击 / 调用）失败是**立刻有结论**的 ——
	 * 挖不动、被领地保护、子任务抛错，等下去不会有任何变化。
	 *
	 * <p><b>为什么这条判定要收在模型里</b>：它同时决定三件事 —— 编辑器要不要给"时限/单位"控件、
	 * {@code failAmount} 是不是有效字段、以及卡片上「失败后」该怎么写。三者写在两处就会不一致：
	 * 曾经的 bug 正是"编辑器不给时限控件（所以 {@code failAmount} 一直是 -1），
	 * 而文案却拿 -1 报『一直重试（没设时限）』" —— 玩家选了「失败后 跳转 2」，卡片上看到的却是"一直重试"，
	 * 显示与实际行为刚好相反。
	 */
	public boolean hasRetryWindow() {
		return this == TAKE || this == DEPOSIT || this == UNLOAD || this == DEPLOY || this == WAIT_REDSTONE
			|| this == WAIT_SIGNAL;
	}

	/** 这类操作有"失败"这个出口吗（= 编辑器里要显示「失败后」）。 */
	public boolean canFail() {
		// 攻击**没有**失败出口：挥一击就是挥一击（不筛选、不判断有没有东西），
		// 永远返回"完成"。给它一个永远不会触发的「失败后」正是上一次修掉的那类误导（决策 73）。
		return hasRetryWindow() || this == MINE;
	}

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
		return this == WAIT || this == WAIT_REDSTONE || this == WAIT_SIGNAL;
	}

	/** 宽松解析：未知名字回落到 {@code fallback}，不让坏数据把存档读崩。 */
	public static OperationType byName(String name, OperationType fallback) {
		if (name != null) {
			// ★ 旧名字 "PUNCH"（v27 之前）是"实体优先、没有才挖方块"的混合操作。拆开后**归到挖掘**：
			// 老卡继续只挖方块 —— 反过来映射的话，所有老卡里的这一条会突然开始打实体/打人。
			if (name.equalsIgnoreCase("PUNCH"))
				return MINE;
			for (OperationType type : values()) {
				if (type.name()
					.equalsIgnoreCase(name))
					return type;
			}
		}
		return fallback;
	}

}
