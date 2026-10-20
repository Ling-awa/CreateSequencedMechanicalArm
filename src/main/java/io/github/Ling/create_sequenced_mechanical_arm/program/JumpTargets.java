package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.function.UnaryOperator;

/**
 * 「自动更正序号」：条目位置一变，把**数字形式的跳转落点**跟着挪，让它们仍然指向原来那一条 ——
 * 外加一件与开关无关的事：<b>落点指着的那一条被删掉时，把落点标成失效（{@code n5}）</b>。
 *
 * <p>为什么需要它（用户 2026-10-20）：跳转落点写的是<b>条目序号</b>（1 基，{@code 条数+1} = 本轮到此为止），
 * 而插入 / 删除 / 移动 / 复制都会让"第 N 条"换成别人 —— 加一条到最前面，原来"跳到 3"就变成了另一条操作。
 * 这个开关打开时，上面四种改动都会把受影响的那几个数字重写一遍。
 *
 * <p><b>只动数字，不动任务名</b>：落点既可以是序号也可以是任务名（{@code ProgramRuntime.jumpTo}：先
 * {@code parseInt}，不行再按任务名找到别的任务），任务名不随本任务内部的条目序号变化。
 *
 * <p><b>为什么"次数"由调用方给</b>：{@link #afterInsert} 要数改动前的条目数，而调用方那时可能已经把
 * 新条目放进去了；与其让这里猜"现在是改前还是改后"，不如让调用方用 {@code count} 说清楚
 * （插入 1 条 / 复制 1 条 / 批量插入 N 条都用同一个入口）。{@link #afterRemove} 则必须在**删除之前**调。
 *
 * <h2>失效落点（用户 2026-10-20 的第二条口径）</h2>
 *
 * <p>原先 {@link #afterRemove} 对"落点正好指着被删那一条"的处理是<b>保持原数字</b>，理由是"位置没动、
 * 只是内容换人"。用户否掉了这个理由：<i>"你忘记删除目标本身这个情况了……把跳转的位置改为占位数字
 * 比如用 n+数字……并且不会直接触发跳转，后者应该有利于玩家编辑的时候知道这里原本跳转的位置，
 * 并且防止不小心的错误跳转"</i> —— 玩家删掉第 3 条时，别处的"跳转到 3"应该明确变成"这个落点已经废了
 * （原本是第 3 条）"，而不是悄悄指着现在的第 3 条（那是一条完全没被指定的操作）。
 *
 * <p>于是有了 {@link #deadMark}：前缀 {@code n} + 原本的 1 基序号（{@code n5} = 原本指向第 5 条）。
 * 它<b>存进落点字段本身</b>，所以跟着卡一起存盘、一起导出、一起显示 —— 不需要额外的布尔字段
 * （多一个字段就多一处会不同步的地方）。
 *
 * <p>三条配套规则：
 * <ul>
 *   <li><b>失效落点不再随位置移动</b>：它是"历史"而不是"引用"（{@link #parseIntOrNull} 认不出它，
 *       插入 / 删除 / 移动自然跳过它）；</li>
 *   <li><b>标记与开关无关</b>：「自动更正序号」管的是"把还活着的落点跟着挪"，而"目标没了"是事实，
 *       开关关着也照标（否则关掉开关的玩家会得到一次静默的错误跳转，正是用户要避免的那件事）；</li>
 *   <li><b>运行时不再跳转</b>：{@code ProgramRuntime.jumpTo} 看到 {@code n5} 就当"没有跳转"处理，
 *       继续下一条（见那里的说明）。</li>
 * </ul>
 *
 * <p>⚠️ 唯一的代价：任务名正好叫 {@code n} + 纯数字（例如 {@code n5}）时，它会被当成失效落点而不被解析。
 * 两者在同一个字段里只能用"形状"区分，而用户点名要这个显示形式；真需要那个任务名时改个名字即可。
 */
public final class JumpTargets {

	/** 失效落点的前缀：{@code n5} = 原本指向第 5 条，那条已经被删掉了。 */
	public static final String DEAD_PREFIX = "n";

	private JumpTargets() {
	}

	/**
	 * 在 {@code at}（0 基）处插入 {@code count} 条之后调用（**可以在插入之后调**：改前条目数由此反推）。
	 *
	 * @param at    插入位置（0 基，取自"插入之前"的列表）
	 * @param count 插入了多少条
	 * @return 被改写的落点个数
	 */
	public static int afterInsert(ArmProgramTask task, int at, int count) {
		if (task == null || count <= 0)
			return 0;
		int sizeBefore = task.entries.size() - count;
		return rewrite(task, raw -> mapNumeric(raw, t -> mapInsert(t, at, count, sizeBefore)));
	}

	/**
	 * 删除 {@code at}（0 基）那一条**之前**调用（改前条目数就是当前的 {@code size()}）。
	 *
	 * <p>两种落点分开处理（这正是用户 2026-10-20 的修正）：
	 * <ul>
	 *   <li>指着被删那一条（{@code at+1}）⇒ 标成 {@link #deadMark 失效}（{@code n<at+1>}），**不管开关**；</li>
	 *   <li>指着别的活着的条目 ⇒ 只有 {@code shiftFollowers}（= 开关打开）时才跟着挪。</li>
	 * </ul>
	 *
	 * @param shiftFollowers 开关是否打开（打开才挪还活着的那些落点）
	 * @return 被改写的落点个数
	 */
	public static int afterRemove(ArmProgramTask task, int at, boolean shiftFollowers) {
		if (task == null)
			return 0;
		int sizeBefore = task.entries.size();
		int deadAt = at + 1;
		return rewrite(task, raw -> {
			Integer parsed = parseIntOrNull(raw);
			if (parsed == null)
				return raw; // 任务名，或已经失效的落点（失效落点不随位置移动）
			if (parsed == deadAt)
				return deadMark(deadAt); // 目标本身被删掉了
			if (!shiftFollowers)
				return raw;
			return Integer.toString(mapRemove(parsed, at, sizeBefore));
		});
	}

	/** 把 {@code from}（0 基）那一条移到 {@code to}（0 基）之后调用。 */
	public static int afterMove(ArmProgramTask task, int from, int to) {
		if (task == null || from == to)
			return 0;
		int size = task.entries.size();
		return rewrite(task, raw -> mapNumeric(raw, t -> mapMove(t, from, to, size)));
	}

	// ————————————————————————— 失效落点 —————————————————————————

	/** 失效落点的写法：{@code n} + 原本的 1 基序号（{@code position < 1} ⇒ 空串，调用方给的位置本来就无效）。 */
	public static String deadMark(int position) {
		return position < 1 ? "" : DEAD_PREFIX + position;
	}

	/**
	 * 这个落点是不是"目标已经被删掉"的失效落点。
	 *
	 * <p>只认 {@code n} + 纯数字：{@code n5} 是失效落点，{@code north} 仍是一个任务名
	 * （与运行时同一个判据 {@link #deadPosition}）。
	 */
	public static boolean isDead(String target) {
		return deadPosition(target) > 0;
	}

	/** 失效落点里记着的<b>原本序号</b>（1 基）；不是失效落点 ⇒ -1。 */
	public static int deadPosition(String target) {
		if (target == null)
			return -1;
		String trimmed = target.trim();
		if (trimmed.length() < 2 || trimmed.charAt(0) != 'n')
			return -1;
		for (int i = 1; i < trimmed.length(); i++)
			if (!Character.isDigit(trimmed.charAt(i)))
				return -1;
		try {
			int position = Integer.parseInt(trimmed.substring(1));
			return position >= 1 ? position : -1;
		} catch (NumberFormatException tooBig) {
			return -1; // 位数多到 int 装不下：当作任务名，运行时照旧报"找不到"
		}
	}

	// ————————————————————————— 单个落点的映射（纯函数，自检直接测） —————————————————————————

	/** 落点是否在本任务的合法区间内（1..size+1，{@code size+1} = 本轮到此为止）。 */
	public static boolean valid(int target, int size) {
		return target >= 1 && target <= size + 1;
	}

	public static int mapInsert(int target, int at, int count, int sizeBefore) {
		if (!valid(target, sizeBefore))
			return target;
		return target - 1 >= at ? target + count : target;
	}

	/**
	 * 删除之后"还活着的落点"怎么挪。
	 *
	 * <p>⚠️ {@code target == at + 1}（指着被删那一条）这一支<b>已经轮不到它</b>了：
	 * {@link #afterRemove} 先把它标成失效落点。这里保留"原地不动"只是这个纯函数的完整定义，
	 * 自检 {@code autoFixAndShiftProblem} 也直接测它。
	 */
	public static int mapRemove(int target, int at, int sizeBefore) {
		if (!valid(target, sizeBefore))
			return target;
		return target > at + 1 ? target - 1 : target;
	}

	public static int mapMove(int target, int from, int to, int size) {
		if (!valid(target, size))
			return target;
		if (target == from + 1)
			return to + 1;
		if (from < to && target > from + 1 && target <= to + 1)
			return target - 1;
		if (to < from && target >= to + 1 && target < from + 1)
			return target + 1;
		return target;
	}

	// ————————————————————————— 落地 —————————————————————————

	/** 把映射应用到整个任务的**两个落点**（成功后 / 失败后）上；返回真正改写的字段数。 */
	private static int rewrite(ArmProgramTask task, UnaryOperator<String> map) {
		int changed = 0;
		for (ArmProgramEntry entry : task.entries) {
			if (rewriteOne(entry, true, map))
				changed++;
			if (rewriteOne(entry, false, map))
				changed++;
		}
		return changed;
	}

	private static boolean rewriteOne(ArmProgramEntry entry, boolean success, UnaryOperator<String> map) {
		String raw = success ? entry.successTarget : entry.timeoutTarget;
		String mapped = map.apply(raw);
		if (mapped == null || mapped.equals(raw))
			return false;
		if (success)
			entry.successTarget = mapped;
		else
			entry.timeoutTarget = mapped;
		return true;
	}

	/** "只有数字落点才参与映射"这一条（任务名 / 空 / 失效落点一律原样返回）。 */
	private static String mapNumeric(String raw, java.util.function.IntUnaryOperator map) {
		Integer parsed = parseIntOrNull(raw);
		if (parsed == null)
			return raw;
		int mapped = map.applyAsInt(parsed);
		return mapped == parsed ? raw : Integer.toString(mapped);
	}

	/**
	 * 与运行时**同一个判据**（{@code ProgramRuntime.jumpTo} 里那句 {@code Integer.parseInt(trimmed)}）：
	 * 只有"能整串解析成整数"的落点才是序号，其余都是任务名。
	 *
	 * <p>失效落点 {@code n5} 在这里返回 {@code null}（它不是序号）—— 所有"跟着挪"的映射都因此跳过它，
	 * 这正是"失效落点不随位置移动"的实现方式（见类注释）。
	 */
	public static Integer parseIntOrNull(String target) {
		if (target == null)
			return null;
		String trimmed = target.trim();
		if (trimmed.isEmpty())
			return null;
		try {
			return Integer.parseInt(trimmed);
		} catch (NumberFormatException notANumber) {
			return null;
		}
	}

}
