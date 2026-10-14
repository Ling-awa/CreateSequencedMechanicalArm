package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramCoordinates;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramRuntime;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 可编程动力臂方块（阶段 1：硬件骨架）。
 *
 * <p>与 Create 原版动力臂（{@code ArmBlock}）的差异：
 * <ul>
 *   <li>没有 FACING —— 臂本身不朝向，方向信息属于操作表里的每条操作（§4.2）。</li>
 *   <li>保留 {@code CEILING} 吸顶变体，语义与原版一致。</li>
 *   <li>形状数值照抄 Create {@code AllShapes.MECHANICAL_ARM / MECHANICAL_ARM_CEILING}
 *       （那边是包私有之外的工具类，我们自建以免耦合）。</li>
 * </ul>
 *
 * <p>注意：{@code KineticBlock.hasShaftTowards} 默认返回 <b>false</b>
 * （Create 6.0.10 KineticBlock.java:51-53），所以动力臂必须像原版一样实现
 * {@link ICogWheel} —— 靠<b>相邻齿轮</b>取力，而不是靠传动轴。
 */
public class SequencedArmBlock extends KineticBlock implements IBE<SequencedArmBlockEntity>, ICogWheel {

	public static final BooleanProperty CEILING = BooleanProperty.create("ceiling");

	// 形状数值照抄 Create 的 AllShapes.MECHANICAL_ARM / MECHANICAL_ARM_CEILING（各三个盒子：
	// 臂身外框 + 内柱 + 底座）。注意 Create 那边是多行链式 .add(...)，容易漏掉最后那个底座。
	private static final VoxelShape SHAPE_GROUND = Shapes.or(
		Block.box(2, 0, 2, 14, 10, 14),
		Block.box(3, 0, 3, 13, 14, 13),
		Block.box(0, 0, 0, 16, 6, 16));
	private static final VoxelShape SHAPE_CEILING = Shapes.or(
		Block.box(2, 6, 2, 14, 16, 14),
		Block.box(3, 2, 3, 13, 16, 13),
		Block.box(0, 10, 0, 16, 16, 16));

	public SequencedArmBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(CEILING, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(CEILING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(CEILING, context.getClickedFace() == Direction.DOWN);
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(CEILING) ? SHAPE_CEILING : SHAPE_GROUND;
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return Axis.Y;
	}

	@Override
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		super.onPlace(state, level, pos, oldState, isMoving);
		withBlockEntityDo(level, pos, SequencedArmBlockEntity::redstoneUpdate);
	}

	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
		boolean isMoving) {
		withBlockEntityDo(level, pos, SequencedArmBlockEntity::redstoneUpdate);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hitResult) {
		// ★ 空手 = **必须放行**给 useWithoutItem。原版的顺序是"先问 useItemOn，只有它回
		//   PASS_TO_DEFAULT_BLOCK_INTERACTION 才轮到 useWithoutItem"（服务端
		//   `ServerPlayerGameMode:366-377`、客户端 `MultiPlayerGameMode:339-351`，两边一模一样）。
		//   这里要是自己返回 SUCCESS/FAIL，空手（含潜行）就**永远走不到** useWithoutItem ——
		//   用户实测的症状正是"空手潜行右键没去溢出取东西，反而把臂手上那件换到了手上"。
		if (stack.isEmpty())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		// 手持护目镜右键 = 给爪子戴上/摘掉护目镜（纯外观变体，同时切换渲染类型）。
		// 注意：<b>查看信息</b>只取决于玩家自己有没有戴护目镜，与这个标记无关。
		if (isGoggles(stack)) {
			ItemInteractionResult result = onBlockEntityUseItemOn(level, pos, arm -> {
				arm.goggles = !arm.goggles;
				arm.notifyUpdate();
				return ItemInteractionResult.SUCCESS;
			});
			if (result.consumesAction())
				return result;
		}
		// 手持操作表右键 = 把卡片里的程序装载进这条臂（P1 起没有形态检查，§19）。
		// ★ 操作表**不参与**下面的"交换物品"（用户第二十七轮："操作表除外"）—— 它有自己的手势。
		if (OperationTableItem.isTable(stack))
			return onBlockEntityUseItemOn(level, pos, arm -> loadProgramFromCard(arm, stack, player, level));
		// 手持**普通物品**右键 = 与臂交换手上的东西（用户第二十七轮）。
		// 只认主手：副手那个手势留给别的用途，两边同时换会让"我到底给了什么"变得说不清。
		//
		// ⚠️ 潜行时**走不到这里**：原版对"潜行 + 手上有东西"直接跳过方块交互（`flag1`，
		//    `ServerPlayerGameMode:362-365`），除非手上物品 `doesSneakBypassUse`。这正是用户要的 ——
		//   潜行是"空手取溢出"的修饰键，不该与交换混在一起（第一版没意识到这点，见 PLAN 决策 79）。
		if (hand == InteractionHand.MAIN_HAND)
			return onBlockEntityUseItemOn(level, pos, arm -> exchangeHeldItem(arm, player, level));

		return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
	}

	/**
	 * 空手右键（用户第二十七轮）：
	 * <ul>
	 *   <li><b>空手潜行右键</b> = 从溢出库存取走**最多一组**（栈式：后进的先出，见
	 *       {@link SequencedArmBlockEntity#overflowOutOrder()}）；</li>
	 *   <li><b>空手右键</b> = 取走臂手上的那一件（= "与空手交换"，臂手因此变空）。</li>
	 * </ul>
	 *
	 * <p>⚠️ 本方法<b>只会</b>在 {@link #useItemOn} 对空手返回
	 * {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} 时被原版调用（见那里的说明）——
	 * 空手的两条手势都挂在<b>这里</b>，别在 useItemOn 里再实现一遍。
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hitResult) {
		if (player.isShiftKeyDown())
			return onBlockEntityUse(level, pos, arm -> takeStackFromOverflow(arm, player, level));
		return onBlockEntityUse(level, pos, arm -> takeHeldItem(arm, player, level));
	}

	/**
	 * 与臂**交换手上的东西**（用户第二十七轮）：臂手上那件给玩家，玩家手上那件给臂。
	 *
	 * <p>语义细节：
	 * <ul>
	 *   <li>整组交换，不做同类合并 —— "交换"就是交换（臂手 3 个泥土、玩家 5 个泥土 ⇒ 给出 5、取回 3）；</li>
	 *   <li><b>只有手上有东西时才会走到这里</b>：空手被 {@link #useItemOn} 放行给了
	 *       {@link #useWithoutItem}（原版"先问 useItemOn、只有 PASS_TO_DEFAULT 才轮到 useWithoutItem"）；</li>
	 *   <li>只做**服务端**（客户端直接返回 SUCCESS，与装载卡片同一条约定）：物品栏由
	 *       {@code ServerPlayer.tick()} 每 tick 的 {@code containerMenu.broadcastChanges()} 同步回去，
	 *       所以这里不需要自己发包。</li>
	 * </ul>
	 *
	 * <p><b>为什么一句提示都不发</b>（用户第二十七轮明确的）：手势的结果**就在眼前** ——
	 * 臂手上那件进了你的手、你手上那件挂到了臂上，原版还会顺手弹出物品名；
	 * 再加一行动作栏文字只是噪音（第一版发了，用户当场说"这行文字提示是不需要的"）。
	 *
	 * <p>包可见（不是 private）是给启动自检用的：它与 {@link #takeHeldItem}/{@link #takeStackFromOverflow}
	 * 一起构成"右键臂"的全部语义，而这三条在游戏里只能靠手点 —— 必须能被断言。
	 */
	static ItemInteractionResult exchangeHeldItem(SequencedArmBlockEntity arm, Player player, Level level) {
		if (level.isClientSide)
			return ItemInteractionResult.SUCCESS;
		ItemStack given = player.getMainHandItem();
		ItemStack taken = arm.getHeldItem();
		arm.setHeldItem(given.isEmpty() ? ItemStack.EMPTY : given.copy());
		player.setItemInHand(InteractionHand.MAIN_HAND, taken.isEmpty() ? ItemStack.EMPTY : taken.copy());
		return ItemInteractionResult.SUCCESS;
	}

	/** 空手右键：把臂手上那一件拿给玩家（臂手变空）。包可见给自检用，见 {@link #exchangeHeldItem}。 */
	static InteractionResult takeHeldItem(SequencedArmBlockEntity arm, Player player, Level level) {
		if (level.isClientSide)
			return InteractionResult.SUCCESS;
		ItemStack taken = arm.getHeldItem();
		if (taken.isEmpty())
			return InteractionResult.SUCCESS; // 臂手空：什么都不做，也不发提示（见 exchangeHeldItem 的说明）
		arm.setHeldItem(ItemStack.EMPTY);
		giveToPlayer(player, taken.copy());
		return InteractionResult.SUCCESS;
	}

	/** 空手潜行右键：按栈式顺序从溢出里取走最多一组。包可见给自检用，见 {@link #exchangeHeldItem}。 */
	static InteractionResult takeStackFromOverflow(SequencedArmBlockEntity arm, Player player, Level level) {
		if (level.isClientSide)
			return InteractionResult.SUCCESS;
		ItemStack taken = arm.itemHandler()
			.extractFromEnd(64, false);
		if (!taken.isEmpty())
			giveToPlayer(player, taken);
		return InteractionResult.SUCCESS;
	}

	/**
	 * 把拿到的东西交给玩家：**空手就落回主手**（玩家刚对着臂右键，东西出现在手上最直观），
	 * 否则进背包；背包也满了就按原版行为掉在脚下。
	 */
	private static void giveToPlayer(Player player, ItemStack stack) {
		if (stack.isEmpty())
			return;
		if (player.getMainHandItem()
			.isEmpty())
			player.setItemInHand(InteractionHand.MAIN_HAND, stack);
		else
			player.getInventory()
				.placeItemBackInInventory(stack);
	}

	/**
	 * 装载卡片程序（PLAN.md §6、§19.2）。
	 *
	 * <p>三个语义要点：
	 * <ol>
	 *   <li><b>重基一次</b>：程序里的坐标一律相对卡上的原点，插进臂时把原点整体平移到臂的位置
	 *       （{@link ProgramCoordinates#rebaseTo}）—— 每一条的<b>世界位置逐条不变</b>，
	 *       而臂内部因此恒有"原点 = 臂的位置"（§19.2 第 1 条）；</li>
	 *   <li>卡片"是绝对卡还是相对卡"这件事已经<b>不存在</b>（P1 删掉了绝对坐标操作表），
	 *       所以没有任何形态检查；</li>
	 *   <li>卡片本身不被消耗（可反复插到多条臂上），需要清空有别的入口。</li>
	 * </ol>
	 *
	 * <p>包可见（不是 private）是给启动自检用的：这条路上的"重基"是**静默**的 ——
	 * 算错只会让臂搬到错的位置，编译期一点提示都没有（与 {@link #exchangeHeldItem} 同一个理由）。
	 */
	static ItemInteractionResult loadProgramFromCard(SequencedArmBlockEntity arm, ItemStack card, Player player,
		Level level) {
		if (level.isClientSide)
			return ItemInteractionResult.SUCCESS;
		ArmProgram program = CardProgramStorage.read(card, level.registryAccess());
		if (program.isEmpty()) {
			feedback(player, "empty_card", ChatFormatting.RED);
			return ItemInteractionResult.SUCCESS;
		}
		if (!program.isWithinLimits()) {
			feedback(player, "over_limit", ChatFormatting.RED);
			return ItemInteractionResult.SUCCESS;
		}
		int converted = ProgramCoordinates.rebaseTo(program, arm.getBlockPos());
		arm.setProgram(program);

		// 装载时顺手报一下"有多少条超出这条臂的范围"（用户第十九轮）：
		// 原版是在添加交互点时就把超距的点挡掉，我们的卡片是可携带的、录制时还不知道插给谁，
		// 所以最早能说的就是这一刻 —— 运行时也会再判一次（ProgramRuntime.preCheckFailure），
		// 这里只是**提前告知**，不拒绝整张卡（否则一张卡就没法给"远的臂"用了）。
		int range = ArmBlockEntity.getRange();
		int outOfRange = 0;
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				if (!entry.op.requiresTarget())
					continue;
				// 世界目标 = 原点 + 偏移。重基之后原点就是臂的位置 ⇒ 与"相对臂的偏移"同值。
				BlockPos target = program.origin.offset(entry.offset);
				if (!ProgramRuntime.withinArmRange(arm.getBlockPos(), target))
					outOfRange++;
			}
		}
		if (outOfRange > 0)
			feedback(player, "loaded_out_of_range", ChatFormatting.YELLOW, program.tasks.size(), outOfRange, range);
		else
			feedback(player, converted > 0 ? "loaded_converted" : "loaded", ChatFormatting.GREEN, program.tasks.size(),
				converted);
		return ItemInteractionResult.SUCCESS;
	}

	private static void feedback(Player player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm.card." + key, args)
				.withStyle(color),
			true);
	}

	/**
	 * 护目镜判定<b>不</b>用 {@code AllItems.GOGGLES}：它的静态类型是 Registrate 的
	 * {@code ItemEntry}，而 Registrate 只随 Create 的 jar-in-jar 提供、不在我们的编译类路径上
	 * （依赖是刻意收窄的：create + ponder + flywheel-api）。按注册名判定同样准确且零额外依赖。
	 */
	private static final ResourceLocation GOGGLES_ID = ResourceLocation.fromNamespaceAndPath("create", "goggles");

	private static boolean isGoggles(ItemStack stack) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.get(GOGGLES_ID) == stack.getItem();
	}

	@Override
	public Class<SequencedArmBlockEntity> getBlockEntityClass() {
		return SequencedArmBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends SequencedArmBlockEntity> getBlockEntityType() {
		return SequencedArmRegistration.SEQUENCED_ARM_BE.get();
	}

	/** 吸顶状态（渲染与 IK 都要用）。 */
	public static boolean isCeiling(BlockState state) {
		return state.hasProperty(CEILING) && state.getValue(CEILING);
	}

}
