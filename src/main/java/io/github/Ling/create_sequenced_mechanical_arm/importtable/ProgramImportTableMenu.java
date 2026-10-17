package io.github.Ling.create_sequenced_mechanical_arm.importtable;

import com.simibubi.create.foundation.gui.menu.MenuBase;

import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * 「操作导入桌」的菜单 —— 槽位坐标、过滤与 {@code quickMoveStack} **逐字照抄** Create 的
 * {@code SchematicTableMenu}（{@code :19-106}），只把输入槽的过滤条件换成"空白操作表"。
 *
 * <p>槽位顺序是**契约**：0 = 输入、1 = 输出，之后才是 36+9 格玩家背包。界面那边用
 * {@code menu.getSlot(1).hasItem()} 判"导入完成"（与上游 {@code SchematicTableScreen:148} 同一个判据），
 * 所以 1 号槽必须是输出槽，插槽顺序不能调。
 *
 * <p><b>输入槽只收空白表</b>：这张桌子产出的是**一张新卡**（{@code ProgramImportTableBlockEntity.completeUpload}），
 * 如果把玩家用过的卡也能塞进去，导入就变成了"静默覆盖你原来的程序" —— 那不是这个方块该干的事
 * （要清空有别的入口）。注意这条过滤只是<b>玩家那一侧</b>的方便：服务端在
 * {@code startUpload} 里会再判一次，因为漏斗/管道不走 {@code mayPlace}。
 */
public class ProgramImportTableMenu extends MenuBase<ProgramImportTableBlockEntity> {

	private Slot inputSlot;
	private Slot outputSlot;

	/**
	 * 注册用的三参构造。
	 *
	 * <p>`IMenuTypeExtension.create(ProgramImportTableMenu::new)` 要的是 `(int id, Inventory, RegistryFriendlyByteBuf)`
	 * 这一形；只有四参构造时方法引用对不上（编译期报"参数类型不符"），所以补这一个由它自己取 MenuType。
	 */
	public ProgramImportTableMenu(int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
		this(SequencedArmRegistration.PROGRAM_IMPORT_MENU.get(), id, inv, extraData);
	}

	public ProgramImportTableMenu(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf extraData) {
		super(type, id, inv, extraData);
	}

	public ProgramImportTableMenu(MenuType<?> type, int id, Inventory inv, ProgramImportTableBlockEntity be) {
		super(type, id, inv, be);
	}

	public static ProgramImportTableMenu create(int id, Inventory inv, ProgramImportTableBlockEntity be) {
		return new ProgramImportTableMenu(SequencedArmRegistration.PROGRAM_IMPORT_MENU.get(), id, inv, be);
	}

	/** 输入槽能不能接收这张物品（{@link #addSlots} 里的槽位过滤与启动自检共用这一个判据）。 */
	public static boolean isAcceptedInput(ItemStack stack) {
		// 有程序的不收：导入的产物是一张新卡，"拿一张写好的卡当输入"没有语义，只会诱导误解
		return OperationTableItem.isTable(stack) && !CardProgramStorage.hasProgram(stack);
	}

	/**
	 * 上游同款：输入有东西 + 输出空格。
	 *
	 * <p>界面用它只是"按钮亮不亮"的参考 —— **真正的裁决在服务端**（{@code startUpload}）。
	 * 客户端不拿它拦请求：拦下来的话玩家点了没反应、也不知道为什么（回执比灰按钮有用）。
	 */
	public boolean canWrite() {
		return inputSlot.hasItem() && !outputSlot.hasItem();
	}

	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		Slot clickedSlot = getSlot(index);
		if (!clickedSlot.hasItem())
			return ItemStack.EMPTY;

		// 与上游逐字一致：0/1 号槽（本方块的两格）→ 玩家背包；其余 → 输入槽（输出槽永远不收东西，
		// 因为 SlotItemHandler 的 mayPlace 在那里恒为 false，shift 点击也不会绕过去）
		ItemStack stack = clickedSlot.getItem();
		if (index < 2)
			moveItemStackTo(stack, 2, slots.size(), true);
		else
			moveItemStackTo(stack, 0, 1, false);

		return ItemStack.EMPTY;
	}

	/**
	 * 客户端侧：从菜单额外数据里把**那台方块实体**取回来并读一份同步 NBT。
	 *
	 * <p>{@code sendToMenu}（{@code SmartBlockEntity}）写的是"方块坐标 + 客户端版 NBT"，
	 * 这两行与上游 {@code SchematicTableMenu:56-64} 完全相同。
	 */
	@Override
	protected ProgramImportTableBlockEntity createOnClient(RegistryFriendlyByteBuf extraData) {
		ClientLevel level = Minecraft.getInstance().level;
		BlockEntity blockEntity = level.getBlockEntity(extraData.readBlockPos());
		if (blockEntity instanceof ProgramImportTableBlockEntity table) {
			table.readClient(extraData.readNbt(), extraData.registryAccess());
			return table;
		}
		return null;
	}

	@Override
	protected void initAndReadInventory(ProgramImportTableBlockEntity contentHolder) {
	}

	@Override
	protected void addSlots() {
		// 坐标是上游原样（输入 (21,59)、输出 (166,59)、背包 38/107、快捷栏 38/165）——
		// 这些数字对应的是复用过来的那张蓝图桌贴图，差一个像素就对不上槽框
		inputSlot = new SlotItemHandler(contentHolder.inventory, ProgramImportTableBlockEntity.SLOT_INPUT, 21, 59) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return isAcceptedInput(stack);
			}
		};

		outputSlot = new SlotItemHandler(contentHolder.inventory, ProgramImportTableBlockEntity.SLOT_OUTPUT, 166, 59) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		};

		addSlot(inputSlot);
		addSlot(outputSlot);

		// player slots
		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 9; ++col) {
				this.addSlot(new Slot(player.getInventory(), col + row * 9 + 9, 38 + col * 18, 107 + row * 18));
			}
		}

		for (int hotbarSlot = 0; hotbarSlot < 9; ++hotbarSlot) {
			this.addSlot(new Slot(player.getInventory(), hotbarSlot, 38 + hotbarSlot * 18, 165));
		}
	}

	@Override
	protected void saveData(ProgramImportTableBlockEntity contentHolder) {
		// 没有要提交的数据：库存由方块实体自己在 setStackInSlot 时标脏（ProgramImportInventory）
	}

}
