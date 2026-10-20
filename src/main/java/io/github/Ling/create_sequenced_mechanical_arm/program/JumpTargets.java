package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.function.IntUnaryOperator;

/**
 * 「自动更正序号」：条目位置一变，把**数字形式的跳转落点**跟着挪，让它们仍然指向原来那一条。
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
 */
public final class JumpTargets {

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
		return rewrite(task, t -> mapInsert(t, at, count, sizeBefore));
	}

	/**
	 * 删除 {@code at}（0 基）那一条**之前**调用（改前条目数就是当前的 {@code size()}）。
	 *
	 * <p>落在被删那条上的落点怎么处理：**保持原数字**。删掉第 3 条之后，"3"指向的就是原来第 4 条
	 * （位置没动、只是内容换人），这比"跳过下一条"或"清成空"都好解释；如果删的是最后一条，
	 * 原数字正好等于新的 {@code size+1}，语义自动变成"本轮到此为止" —— 与原运行时的判据一致。
	 */
	public static int afterRemove(ArmProgramTask task, int at) {
		if (task == null)
			return 0;
		int sizeBefore = task.entries.size();
		return rewrite(task, t -> mapRemove(t, at, sizeBefore));
	}

	/** 把 {@code from}（0 基）那一条移到 {@code to}（0 基）之后调用。 */
	public static int afterMove(ArmProgramTask task, int from, int to) {
		if (task == null || from == to)
			return 0;
		int size = task.entries.size();
		return rewrite(task, t -> mapMove(t, from, to, size));
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

	public static int mapRemove(int target, int at, int sizeBefore) {
		if (!valid(target, sizeBefore))
			return target;
		if (target == at + 1)
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
	private static int rewrite(ArmProgramTask task, IntUnaryOperator map) {
		int changed = 0;
		for (ArmProgramEntry entry : task.entries) {
			if (rewriteOne(entry, true, map))
				changed++;
			if (rewriteOne(entry, false, map))
				changed++;
		}
		return changed;
	}

	private static boolean rewriteOne(ArmProgramEntry entry, boolean success, IntUnaryOperator map) {
		String raw = success ? entry.successTarget : entry.timeoutTarget;
		Integer parsed = parseIntOrNull(raw);
		if (parsed == null)
			return false; // 任务名（或空）—— 不归这里管
		int mapped = map.applyAsInt(parsed);
		if (mapped == parsed)
			return false;
		if (success)
			entry.successTarget = Integer.toString(mapped);
		else
			entry.timeoutTarget = Integer.toString(mapped);
		return true;
	}

	/**
	 * 与运行时**同一个判据**（{@code ProgramRuntime.jumpTo} 里那句 {@code Integer.parseInt(trimmed)}）：
	 * 只有"能整串解析成整数"的落点才是序号，其余都是任务名。
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
