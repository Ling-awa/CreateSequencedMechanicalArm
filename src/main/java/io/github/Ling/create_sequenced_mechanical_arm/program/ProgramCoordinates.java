package io.github.Ling.create_sequenced_mechanical_arm.program;

import net.minecraft.core.BlockPos;

/**
 * 卡片的坐标语义（PLAN.md §6）。
 *
 * <p>约定：
 * <ul>
 *   <li>世界内录入时记的是<b>世界绝对坐标</b>（你在世界里点的就是绝对坐标）；</li>
 *   <li>臂内部<b>始终用相对坐标</b>（基准是臂方块自身位置）——这与原版
 *       {@code ArmInteractionPoint.serialize(anchor)} 的做法一致，蓝图平移也就自动成立；</li>
 *   <li>转换发生在"插入臂"那一刻：绝对卡直接拒收，相对卡里的绝对条目就地转相对。</li>
 * </ul>
 */
public final class ProgramCoordinates {

	private ProgramCoordinates() {
	}

	/**
	 * 把程序里所有绝对条目转成相对 {@code armPos} 的偏移。
	 *
	 * @return 实际转换的条目数
	 */
	public static int toRelative(ArmProgram program, BlockPos armPos) {
		int converted = 0;
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				if (!entry.op.requiresTarget() || !entry.absolute)
					continue;
				entry.offset = entry.offset.subtract(armPos);
				entry.absolute = false;
				converted++;
			}
		}
		return converted;
	}

}
