package io.github.Ling.create_sequenced_mechanical_arm.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 操作表（"程序卡"）—— 全模组只有这一种程序卡（PLAN.md §19）。
 *
 * <p>P1「坐标模型统一」之前这里有两个形态（绝对 / 相对，注册成两个物品，绝对形态不允许插入臂）。
 * 现在一份程序只有<b>一个原点</b>（{@code ArmProgram.origin}）、所有条目坐标都相对它，
 * "这张卡是绝对的还是相对的"不再是一个概念 ⇒ 形态字段与创建栏里的第二项一并删除。
 */
public class OperationTableItem extends Item {

	public OperationTableItem(Properties properties) {
		super(properties);
	}

	/** 是否是操作表（不看形态）。 */
	public static boolean isTable(ItemStack stack) {
		return stack.getItem() instanceof OperationTableItem;
	}

}
