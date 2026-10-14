package io.github.Ling.create_sequenced_mechanical_arm.menu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import com.simibubi.create.foundation.gui.menu.HeldItemGhostItemMenu;

import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

/**
 * 操作表编辑器菜单（PLAN.md §5.2）。骨架照 Create 的 {@code ScheduleMenu}：继承
 * {@link HeldItemGhostItemMenu}，把界面绑在"主手那张卡"上，并且带<b>一个幽灵槽 + 玩家背包</b>。
 *
 * <p>幽灵槽的用途与时刻表完全相同：<b>从背包把物品拖进槽里当"图标"</b>
 * （我们是动作/任务的自定义图标；时刻表用它当过滤器/参考物品）。
 *
 * <p>槽位布局也照 {@code ScheduleMenu}：先 36 个玩家背包槽，再 1 个幽灵槽 ——
 * <b>顺序不能变</b>，因为 Create 的 {@code GhostItemSubmitPacket} 写死了 {@code menu.getSlot(36 + slot)}。
 *
 * <p>{@link #slotsActive} 控制背包能不能点：只有打开编辑子面板时才让点（照 ScheduleMenu 的做法，
 * 靠 {@code Slot.isActive()} 生效 —— 屏幕取鼠标下槽位时会跳过非活动槽）。
 *
 * <p>保存路径不在这里：关界面时由客户端发 {@code ArmProgramEditPacket}，服务端校验规模后写入卡片。
 */
public class ArmProgramMenu extends HeldItemGhostItemMenu {

	/**
	 * 幽灵槽数量 = <b>3</b>：0 号 = 自定义图标，1/2 号 = 「等待信号」频率的那一对物品。
	 *
	 * <p>用户第十九轮纠正过这里："等信号确实有两个幽灵槽位，但是你忘记了有一个是我们用来显示图标的，
	 * 不是标记频率的。标记频率的幽灵槽位有两个，加上原本的显示图标，一共有三个幽灵槽位。"
	 * —— 原来只有 2 个，于是"图标 + 频率第一个"占满，频率的第二个物品根本没有槽可放（也就绑不成频率）。
	 * 位置照上游时刻表按 20px 排开（`ScheduleScreen:1050-1051`）：GUI x = 54 / 74 / 94。
	 */
	public static final int GHOST_SLOTS = 3;

	/** 编辑子面板打开时才允许点背包/幽灵槽。 */
	public boolean slotsActive;
	/**
	 * 当前**可用**的幽灵槽个数（从 0 号起连续）：
	 * {@code 0} = 编辑属性时（三个槽都不适用）、{@code 1} = 编辑条目/任务本体（只用图标槽）、
	 * {@code 3} = 编辑「等待信号」本体（图标 + 一对频率）。
	 */
	public int targetSlotsActive;

	public ArmProgramMenu(int id, Inventory inventory, ItemStack card) {
		super(SequencedArmRegistration.ARM_PROGRAM_MENU.get(), id, inventory, card);
	}

	public ArmProgramMenu(int id, Inventory inventory, RegistryFriendlyByteBuf extraData) {
		super(SequencedArmRegistration.ARM_PROGRAM_MENU.get(), id, inventory, extraData);
	}

	/** 编辑对象（卡片）。服务端是主手那份引用，客户端是反序列化出来的副本。 */
	public ItemStack getCard() {
		return contentHolder;
	}

	@Override
	protected ItemStackHandler createGhostInventory() {
		return new ItemStackHandler(GHOST_SLOTS);
	}

	@Override
	protected boolean allowRepeats() {
		return true;
	}

	@Override
	protected void addSlots() {
		// 位置与 ScheduleMenu 完全一致（玩家背包 46,140；幽灵槽 54,88）
		addPlayerSlots(46, 140);
		for (int i = 0; i < GHOST_SLOTS; i++)
			addSlot(new InactiveItemHandlerSlot(ghostInventory, i, i, 54 + 20 * i, 88));
	}

	@Override
	protected Slot createPlayerSlot(Inventory inventory, int index, int x, int y) {
		return new InactiveSlot(inventory, index, x, y);
	}

	@Override
	protected void saveData(ItemStack contentHolder) {
		// 保存走 ArmProgramEditPacket（服务端权威写入），不靠菜单关闭时机
	}

	/** 只有编辑子面板打开时背包才能点（照 ScheduleMenu.InactiveSlot）。 */
	class InactiveSlot extends Slot {

		InactiveSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean isActive() {
			return slotsActive;
		}
	}

	class InactiveItemHandlerSlot extends SlotItemHandler {

		private final int targetIndex;

		InactiveItemHandlerSlot(IItemHandler handler, int targetIndex, int index, int x, int y) {
			super(handler, index, x, y);
			this.targetIndex = targetIndex;
		}

		@Override
		public boolean isActive() {
			return slotsActive && targetIndex < targetSlotsActive;
		}
	}

}
