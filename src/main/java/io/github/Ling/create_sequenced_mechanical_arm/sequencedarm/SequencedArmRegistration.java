package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationRecordItem;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.menu.ArmProgramMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 可编程动力臂的注册项（方块 / 物品 / 方块实体）。 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, bus = EventBusSubscriber.Bus.MOD)
public class SequencedArmRegistration {

	public static final DeferredRegister.Blocks BLOCKS =
		DeferredRegister.createBlocks(Create_sequenced_mechanical_arm.MODID);
	public static final DeferredRegister.Items ITEMS =
		DeferredRegister.createItems(Create_sequenced_mechanical_arm.MODID);
	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
		DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Create_sequenced_mechanical_arm.MODID);
	public static final DeferredRegister<MenuType<?>> MENUS =
		DeferredRegister.create(Registries.MENU, Create_sequenced_mechanical_arm.MODID);

	public static final DeferredBlock<SequencedArmBlock> SEQUENCED_ARM = BLOCKS.register("sequenced_arm",
		() -> new SequencedArmBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(3.0F, 6.0F)
			.sound(SoundType.COPPER)
			.noOcclusion()
			.requiresCorrectToolForDrops()));

	public static final DeferredItem<BlockItem> SEQUENCED_ARM_ITEM =
		ITEMS.registerSimpleBlockItem("sequenced_arm", SEQUENCED_ARM);

	// ——— 操作表（程序卡）———
	// P1「坐标模型统一」（PLAN.md §19）之后**只有这一种**操作表：条目坐标一律**相对执行它的那条臂**
	// （用户第四十轮起，录制原点也不再进程序、只作为卡级参考值存在）⇒ "绝对卡 / 相对卡"这个区分
	// 没有意义了。
	// ⚠️ 原来那个 `operation_table_absolute` 的**注册 id 刻意不再保留**：用户已确认不做老数据兼容，
	//    老存档里的绝对卡会变成"未知物品"（这是 P1 明确接受的一次性代价）。

	public static final DeferredItem<OperationTableItem> OPERATION_TABLE_RELATIVE =
		ITEMS.register("operation_table_relative",
			() -> new OperationTableItem(new Item.Properties().stacksTo(1)));

	// ——— 操作记录表 ———
	// **不携带任何数据**的物品：手持它 + 潜行右键一条臂 = "把这条臂的程序导出成脚本文件"
	// （用户口径，见 item/OperationRecordItem 的类注释）。它只是"授权用的钥匙"，
	// 所以这里除了 stacksTo(1) 没有任何组件 / NBT 相关的东西 —— 别往它身上加程序。
	public static final DeferredItem<OperationRecordItem> OPERATION_RECORD =
		ITEMS.register("operation_record",
			() -> new OperationRecordItem(new Item.Properties().stacksTo(1)));

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SequencedArmBlockEntity>> SEQUENCED_ARM_BE =
		BLOCK_ENTITIES.register("sequenced_arm",
			// Create 的方块实体构造器要 (BlockEntityType, BlockPos, BlockState)，而 vanilla 的
			// BlockEntityType.Builder 只给 (BlockPos, BlockState) —— 所以在 BE 构造那一刻再取类型
			// （此时 holder 已绑定；放在外层 lambda 里会变成注册期自引用）。
			() -> BlockEntityType.Builder
				.of((pos, state) -> new SequencedArmBlockEntity(SequencedArmRegistration.SEQUENCED_ARM_BE.get(), pos, state),
					SEQUENCED_ARM.get())
				.build(null));

	/** 操作表编辑器菜单（PLAN.md §5.2）。客户端实例靠 extraData 里的卡片物品构造。 */
	public static final DeferredHolder<MenuType<?>, MenuType<ArmProgramMenu>> ARM_PROGRAM_MENU =
		MENUS.register("arm_program", () -> IMenuTypeExtension.create(ArmProgramMenu::new));

	public static void register(IEventBus modEventBus) {
		BLOCKS.register(modEventBus);
		ITEMS.register(modEventBus);
		BLOCK_ENTITIES.register(modEventBus);
		MENUS.register(modEventBus);
	}

	/**
	 * 把臂的库存暴露成物品能力（PLAN.md §7.3）：漏斗 / 溜槽 / 其它元件因此能给臂补料，
	 * 溢出格也能被抽走；<b>手持格恒不可抽</b>（见 {@link SequencedArmItemHandler}）。
	 *
	 * <p>审计发现：这一步此前**整个缺失** —— 全工程只有两处 `getCapability` 读取，没有任何注册，
	 * 于是 §7.3 那条"通过 capability 暴露给漏斗/溜槽"从来没生效过（而它不会报错，只会"漏斗喂不进去"）。
	 * 注册写法与上游 `DeployerBlockEntity.registerCapabilities:111-121` 同形。
	 */
	@SubscribeEvent
	public static void registerCapabilities(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, SEQUENCED_ARM_BE.get(), (be, context) -> be.itemHandler());
	}

}
