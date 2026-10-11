package io.github.Ling.create_smart_mechanical_arm.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 操作表（"程序卡"）。两种形态共用一个类、注册成两个物品（PLAN.md §1.2 已确认的决策）：
 * <ul>
 *   <li>{@code absolute=true}：<b>绝对坐标操作表</b> —— 工作副本，靠在世界里录坐标、复制、修改，
 *       <b>不能插入臂</b>；</li>
 *   <li>{@code absolute=false}：<b>相对坐标操作表</b> —— 能插入臂；插入时其中残留的绝对条目
 *       会以臂的位置为基准就地转成相对（见 {@code ProgramCoordinates}）。</li>
 * </ul>
 *
 * <p>做成两个物品而不是一个物品两个模式，是为了"不容易混淆"（用户明确要求）。
 */
public class OperationTableItem extends Item {

	private final boolean absolute;

	public OperationTableItem(Properties properties, boolean absolute) {
		super(properties);
		this.absolute = absolute;
	}

	public boolean isAbsoluteForm() {
		return absolute;
	}

	/** 是否是操作表（不看形态）。 */
	public static boolean isTable(ItemStack stack) {
		return stack.getItem() instanceof OperationTableItem;
	}

	/** 是否是绝对形态（绝对形态不允许插入臂）。 */
	public static boolean isAbsoluteForm(ItemStack stack) {
		return stack.getItem() instanceof OperationTableItem table && table.absolute;
	}

}
