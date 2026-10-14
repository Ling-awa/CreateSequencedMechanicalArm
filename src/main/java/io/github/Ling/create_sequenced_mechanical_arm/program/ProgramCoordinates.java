package io.github.Ling.create_sequenced_mechanical_arm.program;

import net.minecraft.core.BlockPos;

/**
 * 程序的坐标语义（PLAN.md §6、§19 的坐标模型统一）。
 *
 * <p>约定（P1 之后只剩这一套）：
 * <ul>
 *   <li><b>一份程序一个原点</b>（{@link ArmProgram#origin}），所有条目的
 *       {@link ArmProgramEntry#offset} 都是相对它的偏移；条目上不再有"绝对 / 相对"标记；</li>
 *   <li>世界位置 = {@code origin.offset(offset)}（{@code SequencedArmBlock} 的范围提示、
 *       {@code CardEditPacket} 的撤销、GUI 的坐标框都按这条算）；</li>
 *   <li>臂内部<b>始终</b>以臂方块自身位置为原点（与上游 {@code ArmInteractionPoint.serialize(anchor)}
 *       的 {@code pos.subtract(anchor)} 一致），就靠插卡那一刻调一次
 *       {@link #rebaseTo(ArmProgram, BlockPos)}。</li>
 * </ul>
 *
 * <p>为什么保留"插卡时平移一次"、而不是让运行时去加原点：臂内部原点是臂自己的位置 ⇒ 蓝图平移、
 * "搬臂后目标跟着走"这些既有行为一个字都不用改（§19.2 第 1 条）。
 */
public final class ProgramCoordinates {

	private ProgramCoordinates() {
	}

	/**
	 * <b>重基</b>：把程序的原点改成 {@code newOrigin}，同时平移所有条目的偏移，
	 * 使每一条的<b>世界位置逐条不变</b>（{@code newOrigin + offset' == 旧 origin + offset}）。
	 *
	 * <p>这一个函数同时服务两件事（刻意合成一个入口，而不是两份实现）：
	 * <ul>
	 *   <li><b>插卡进臂</b>：{@code newOrigin = 臂的位置} ⇒ 臂内恒有"原点 = 臂的位置"；</li>
	 *   <li><b>GUI 改原点</b>（P2/P3）：玩家只是想换个参考点，东西不该跟着跑。</li>
	 * </ul>
	 * 反过来，"整体偏移"（世界位置搬家、原点不动）**不走这里** —— 那是加增量，不是重基（§19.2 第 4 条）。
	 *
	 * <p>只动 {@link OperationType#requiresTarget()} 的条目：不碰目标的操作没有位置语义，
	 * 它们的 {@code offset} 跟着平移只会变成一个凭空多出来的字段（反编译器又会把它写出来）。
	 *
	 * <p>旧名 {@code toRelative} 不再保留别名：它的语义（"把绝对条目转成相对"）在新模型里不存在了，
	 * 留个别名只会让两套心智模型继续并存。
	 *
	 * @return 实际平移的条目数（0 = 原点本来就等于 {@code newOrigin}）
	 */
	public static int rebaseTo(ArmProgram program, BlockPos newOrigin) {
		// delta = **旧原点 − 新原点**（顺序反了就是"东西全跑一格"这类静默位移）：
		// 要的是 `newOrigin + offset' == 旧 origin + offset` ⇒ `offset' = offset + (旧 origin − newOrigin)`。
		BlockPos delta = program.origin.subtract(newOrigin);
		program.origin = newOrigin;
		if (delta.equals(BlockPos.ZERO))
			return 0;
		int rebased = 0;
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				if (!entry.op.requiresTarget())
					continue;
				entry.offset = entry.offset.offset(delta);
				rebased++;
			}
		}
		return rebased;
	}

}
