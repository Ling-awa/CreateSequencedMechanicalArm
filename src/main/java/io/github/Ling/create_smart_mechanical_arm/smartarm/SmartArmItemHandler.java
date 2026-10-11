package io.github.Ling.create_smart_mechanical_arm.smartarm;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 臂的库存视图（PLAN.md §7.3）：1 格手持 + 最多 10 格溢出，暴露给漏斗 / 溜槽 / 其它元件。
 *
 * <p>三条语义：
 * <ul>
 *   <li><b>槽 0 = 手持格，禁止被外部抽取</b> —— {@code extractItem(0, …)} 恒返回空栈：手上那把工具不该被
 *       漏斗顺手抽走，它只能由臂自己的「放入」操作转移出去（对齐 Create 的
 *       {@code DepositOnlyArmInteractionPoint}：取物恒 {@code EMPTY}、槽位数 0）；</li>
 *   <li>槽 1..10 = 溢出格，正常可抽取；抽空后从列表里移除（外部按槽号遍历即可）；</li>
 *   <li><b>插入永远允许</b>（漏斗给臂补料）：先补手持的同种堆 → 再补溢出里已有的同种堆 → 最后占空格。</li>
 * </ul>
 *
 * <p><b>插入算法只有一份</b>（{@link #insert(ItemStack, boolean)}）：{@code canAccept}（模拟）与
 * {@code insertIntoInventory}（真放）都走它。审计发现的旧行为正是这两者各写一份的结果 ——
 * {@code canAccept} 只看格数、插入又从不并入已有的溢出堆，于是"10 个残堆"就被判成满仓、
 * 后面的物品直接掉在地上（而 §7.3 写的规则是"堆叠到溢出"）。
 */
public class SmartArmItemHandler implements IItemHandler {

	private final SmartArmBlockEntity arm;

	public SmartArmItemHandler(SmartArmBlockEntity arm) {
		this.arm = arm;
	}

	@Override
	public int getSlots() {
		return 1 + SmartArmBlockEntity.MAX_OVERFLOW;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		if (slot == 0)
			return arm.heldItem;
		int index = slot - 1;
		return index >= 0 && index < arm.overflow.size() ? arm.overflow.get(index) : ItemStack.EMPTY;
	}

	@Override
	public int getSlotLimit(int slot) {
		return 64;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return true; // 补料不挑物品：过滤器是漏斗自己的事
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if (stack.isEmpty())
			return ItemStack.EMPTY;
		if (slot == 0)
			return insertHeld(stack, simulate);
		int index = slot - 1;
		if (index < 0 || index > arm.overflow.size())
			return stack; // 越界或跳格：不收（IItemHandler 的约定是不凭空扩大）
		return insertOverflowAt(index, stack, simulate);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		if (amount <= 0 || slot == 0)
			return ItemStack.EMPTY; // 手持格永远抽不出来（见类注释）
		int index = slot - 1;
		if (index < 0 || index >= arm.overflow.size())
			return ItemStack.EMPTY;
		ItemStack stored = arm.overflow.get(index);
		int moved = Math.min(amount, stored.getCount());
		if (moved <= 0)
			return ItemStack.EMPTY;
		ItemStack out = stored.copyWithCount(moved);
		if (!simulate) {
			stored.shrink(moved);
			if (stored.isEmpty())
				arm.overflow.remove(index);
			arm.markProgramDirty();
		}
		return out;
	}

	/**
	 * <b>栈式出库</b>：从**最后一格非空溢出**拿走最多 {@code amount} —— 也就是
	 * {@link SmartArmBlockEntity#overflowOutOrder()} 的第一个。空手潜行右键走这条路。
	 *
	 * <p>"哪一格先出"**只有一处定义**（{@code overflowOutOrder}）：这里拿顺序表的第一个，
	 * 再回头找它在原列表里的下标（列表里放的就是那些 ItemStack 原件，所以按下标取就是同一格）。
	 *
	 * <p>取出本身复用 {@link #extractItem}（而不是自己动列表）：手持格的保护、抽空后移除空格、标脏
	 * 三件事都只有那一份实现。
	 */
	public ItemStack extractFromEnd(int amount, boolean simulate) {
		List<ItemStack> order = arm.overflowOutOrder();
		if (order.isEmpty())
			return ItemStack.EMPTY;
		ItemStack top = order.get(0);
		int index = arm.overflow.size() - 1 - order.indexOf(top);
		return index < 0 ? ItemStack.EMPTY : extractItem(index + 1, amount, simulate);
	}

	/**
	 * 臂自己的插入入口（「取出」拿回来的、「组装」剩下的）：先并进已有堆、再占空格，返回**没能放下**的部分。
	 * {@code simulate} 为真时一个字节都不改，所以 {@link #canAccept} 可以拿它当判据。
	 *
	 * <p>{@code stack.copy()} 不是多余的：{@code insertHeld} 在"手持物不同"时会把**原栈**原样返回，
	 * 后面的并堆要靠 {@code shrink} 记账 —— 不复制就会改掉调用方的栈
	 *（IItemHandler 的约定是插入方法绝不修改传入的栈）。
	 */
	public ItemStack insert(ItemStack stack, boolean simulate) {
		if (stack.isEmpty())
			return ItemStack.EMPTY;
		ItemStack remainder = insertHeld(stack.copy(), simulate);
		if (remainder.isEmpty())
			return remainder;
		return insertIntoOverflow(remainder, simulate);
	}

	/**
	 * <b>只往溢出里放</b>（先并入已有堆、再占空格）。
	 *
	 * <p>掉落物走这条路而不是 {@link #insert}：假玩家排空发生在"动作之后、读主手之前"，
	 * 而主手那一格马上会被 {@code heldItem = 主手内容} 覆盖 —— 掉落物要是落进手持格，就会被那次覆盖
	 * 直接抹掉（第一版自检当场抓到：`溢出=0 格`，牛排放进手持格后消失）。手持格只属于"手上那把工具"。
	 */
	public ItemStack insertIntoOverflow(ItemStack stack, boolean simulate) {
		if (stack.isEmpty())
			return ItemStack.EMPTY;
		ItemStack remainder = stack.copy();
		for (int i = 0; i < arm.overflow.size() && !remainder.isEmpty(); i++) {
			ItemStack stored = arm.overflow.get(i);
			if (stored.isEmpty() || !ItemStack.isSameItemSameComponents(stored, remainder))
				continue;
			int space = Math.min(stored.getMaxStackSize(), getSlotLimit(i + 1)) - stored.getCount();
			int moved = Math.min(space, remainder.getCount());
			if (moved <= 0)
				continue;
			if (!simulate) {
				stored.grow(moved);
				arm.markProgramDirty();
			}
			remainder.shrink(moved);
		}
		while (!remainder.isEmpty() && arm.overflow.size() < SmartArmBlockEntity.MAX_OVERFLOW) {
			ItemStack piece = remainder.split(Math.min(remainder.getMaxStackSize(), remainder.getCount()));
			if (!simulate)
				arm.overflow.add(piece);
		}
		return remainder;
	}

	/** 臂内还收得下这个物品吗（模拟插入的判据，与真插入同一份算法）。 */
	public boolean canAccept(ItemStack stack) {
		if (stack.isEmpty())
			return false;
		return insert(stack, true).getCount() < stack.getCount();
	}

	/** 手持格：空的就占位，同种的补堆，其余不收。 */
	private ItemStack insertHeld(ItemStack stack, boolean simulate) {
		if (arm.heldItem.isEmpty()) {
			if (!simulate) {
				arm.heldItem = stack.copy();
				arm.markProgramDirty();
			}
			return ItemStack.EMPTY;
		}
		if (!ItemStack.isSameItemSameComponents(arm.heldItem, stack))
			return stack;
		int space = Math.min(arm.heldItem.getMaxStackSize(), getSlotLimit(0)) - arm.heldItem.getCount();
		int moved = Math.min(space, stack.getCount());
		if (moved <= 0)
			return stack;
		if (!simulate) {
			arm.heldItem = arm.heldItem.copyWithCount(arm.heldItem.getCount() + moved);
			arm.markProgramDirty();
		}
		return stack.copyWithCount(stack.getCount() - moved);
	}

	/** 指定溢出格：同种的补堆（空的重填），否则不收。 */
	private ItemStack insertOverflowAt(int index, ItemStack stack, boolean simulate) {
		ItemStack stored = index < arm.overflow.size() ? arm.overflow.get(index) : ItemStack.EMPTY;
		if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(stored, stack))
			return stack;
		int already = stored.isEmpty() ? 0 : stored.getCount();
		int space = Math.min(stack.getMaxStackSize(), getSlotLimit(index + 1)) - already;
		int moved = Math.min(space, stack.getCount());
		if (moved <= 0)
			return stack;
		if (!simulate) {
			arm.markProgramDirty();
			if (stored.isEmpty())
				arm.overflow.add(index, stack.copyWithCount(moved));
			else
				stored.grow(moved);
		}
		return stack.copyWithCount(stack.getCount() - moved);
	}

}
