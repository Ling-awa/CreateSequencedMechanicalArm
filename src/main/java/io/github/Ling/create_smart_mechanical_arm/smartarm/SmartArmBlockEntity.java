package io.github.Ling.create_smart_mechanical_arm.smartarm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.interaction.AnyBlockInteractionPoint;
import io.github.Ling.create_smart_mechanical_arm.interaction.RightClickBridge;
import io.github.Ling.create_smart_mechanical_arm.mixin.ArmInteractionPointAccessor;
import io.github.Ling.create_smart_mechanical_arm.program.ArmEntryText;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramView;
import io.github.Ling.create_smart_mechanical_arm.program.OperationType;
import io.github.Ling.create_smart_mechanical_arm.program.ProgramRuntime;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 智能动力臂方块实体。
 *
 * <p>阶段 2 起，行为完全由 {@link ArmProgram 操作表} 驱动，
 * {@link ProgramRuntime 解释器} 每 tick 至多推进一步；本类只负责
 * "运动学 + 目标解析 + 库存"这三件事，不做任何决策。
 *
 * <p>运动学数值对齐 Create 6.0.10 的 {@code ArmBlockEntity}：进度式移动
 * （{@code min(256,|speed|)/1024}，满速 4 tick）、姿态"先折回静止位再伸向目标"、
 * 客户端在 {@link #read} 里检测目标变化以快照上一姿态、转速 0 则冻结。
 *
 * <p>库存结构对齐机械手：<b>1 格手持 + 最多 10 格溢出</b>；满仓时仍可行动，
 * 捕获不到的物品正常掉落到世界（PLAN.md §7.4）。
 */
public class SmartArmBlockEntity extends KineticBlockEntity implements TransformableBlockEntity {

	public static final int MAX_OVERFLOW = 10;

	public enum Phase {
		IDLE, MOVING
	}

	// ——— 运动学状态（客户端插值；字段公开给渲染器） ———
	public float chasedPointProgress;
	public int chasedPointIndex = -1;
	public float previousBaseAngle;
	public SmartArmAngleTarget previousTarget = SmartArmAngleTarget.RESTING;
	public LerpedFloat baseAngle;
	public LerpedFloat lowerArmAngle;
	public LerpedFloat upperArmAngle;
	public LerpedFloat headAngle;

	// ——— 逻辑状态 ———
	public Phase phase = Phase.IDLE;
	/** 当前移动目标（相对手臂方块）；同步给客户端用于计算角度。 */
	public BlockPos movingOffset = BlockPos.ZERO;
	public ItemStack heldItem = ItemStack.EMPTY;
	public final List<ItemStack> overflow = new ArrayList<>();
	/** 爪子上的护目镜（外观变体，同时决定用 cutout 还是 solid 渲染）。信息显示不依赖它。 */
	public boolean goggles;
	protected boolean redstoneLocked;

	/**
	 * 每次 {@link #beginMove} 自增，并同步给客户端。
	 *
	 * <p>用途：客户端要靠它判断"新的一次移动开始了"，从而把<b>当前</b>姿态快照成插值起点。
	 * 不能拿"目标偏移/相位变了"当判据 —— 失败重试时目标就是同一个方块，
	 * 偏移与相位都不变（MOVING→MOVING），那样就永远不快照，插值起点会一直是旧的静止位，
	 * 表现就是"动作失败后手臂瞬间缩回"。
	 */
	private int moveSeq;
	private final ArmProgram program = new ArmProgram();
	public final ProgramRuntime runtime = new ProgramRuntime();
	/** 客户端可见的程序摘要（护目镜用）；服务端在同步包里填充。 */
	public final ArmProgramView hud = new ArmProgramView();
	/** 机械手语义的左右键执行体（状态：破坏进度）。 */
	public final RightClickBridge rightClick = new RightClickBridge(this);

	@Nullable
	private ArmInteractionPoint movingPoint;
	/** 通用目标（攻击/使用任意方块）时爪子从哪一面够过去；客户端插值也要用，所以进同步 NBT。 */
	@Nullable
	public Direction movingFace;
	@Nullable
	private IItemHandler movingHandler;
	/** {@link #movingHandler} 是从哪个方块实体上取下来的 —— 用来判断快照有没有失效（见 getMovingHandler）。 */
	@Nullable
	private BlockEntity movingHandlerOwner;
	@Nullable
	private DeployerFakePlayer player;
	/** 库存视图（漏斗 / 溜槽 / 其它元件看的那份）；懒建，注册表通过 {@link #itemHandler()} 拿它。 */
	@Nullable
	private SmartArmItemHandler itemHandler;

	public SmartArmBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		baseAngle = LerpedFloat.angular()
			.startWithValue(previousTarget.baseAngle);
		lowerArmAngle = LerpedFloat.angular()
			.startWithValue(previousTarget.lowerArmAngle);
		upperArmAngle = LerpedFloat.angular()
			.startWithValue(previousTarget.upperArmAngle);
		headAngle = LerpedFloat.angular()
			.startWithValue(previousTarget.headAngle);
		previousBaseAngle = previousTarget.baseAngle;
	}

	@Override
	public void initialize() {
		super.initialize();
		initPlayer();
	}

	private void initPlayer() {
		if (player != null)
			return;
		if (level instanceof ServerLevel serverLevel)
			player = new DeployerFakePlayer(serverLevel, null);
	}

	public boolean isOnCeiling() {
		return SmartArmBlock.isCeiling(getBlockState());
	}

	/**
	 * 渲染包围盒必须放大：手臂能伸出 2 格多，而视锥剔除用的就是它 ——
	 * NeoForge 的 {@code ClientHooks.isBlockEntityRendererVisible}（:947-950）对<b>全局</b>方块实体
	 * 也照样 {@code frustum.isVisible(renderer.getRenderBoundingBox(be))}，
	 * 所以只给一格包围盒的话，方块本身一离开视线，伸出去的手臂就会整段消失。
	 * 数值与 Create {@code ArmBlockEntity.createRenderBoundingBox} 一致。
	 */
	@Override
	protected AABB createRenderBoundingBox() {
		return super.createRenderBoundingBox()
			.inflate(3);
	}

	// ————————————————————————— 程序接口 —————————————————————————

	public ArmProgram getProgram() {
		return program;
	}

	public void setProgram(ArmProgram newProgram) {
		program.tasks.clear();
		program.tasks.addAll(newProgram.tasks);
		program.absolute = newProgram.absolute;
		runtime.onProgramReplaced(this); // 任务数可能变了：槽位与频率缓存都要重来
	}

	public void markProgramDirty() {
		setChanged();
		sendData();
	}

	// ————————————————————————— 目标 / 移动 —————————————————————————

	/**
	 * 开始移动到目标（相对偏移）。返回 false 表示目标既不是 Create 交互点、也没给出交互面。
	 * 载具（置物台/传送带）上的物品在移动途中由 {@link ArmInteractionPoint#keepAlive()} 锁住。
	 */
	public boolean beginMove(BlockPos offset) {
		return beginMove(offset, null);
	}

	/**
	 * 带交互面的版本：只有"对任意方块"的操作（攻击 / 使用 / 潜行使用）才用得上它 ——
	 * 那些操作的爪子要停在玩家点的那一面外侧，而不是 Create 交互点自己算好的取物位。
	 *
	 * @param face 玩家录下来的点击面；是 Create 交互点时忽略（交互点自己知道从哪侧接）
	 */
	public boolean beginMove(BlockPos offset, @Nullable Direction face) {
		if (level == null)
			return false;
		BlockPos target = worldPosition.offset(offset);
		BlockState state = level.getBlockState(target);
		ArmInteractionPoint point = pointAt(target, state, face);
		if (point == null) {
			cancelMove();
			return false;
		}
		movingOffset = offset;
		movingFace = face;
		movingPoint = point;
		movingHandlerOwner = level.isClientSide ? null : level.getBlockEntity(target);
		movingHandler = level.isClientSide ? null : handlerAt(target);
		chasedPointProgress = 0;
		chasedPointIndex = 0;
		phase = Phase.MOVING;
		moveSeq++;
		setChanged();
		sendData();
		return true;
	}

	/**
	 * 解析目标的交互点：优先 Create 注册的交互点；不是交互点、但玩家录了交互面时，退回
	 * {@link AnyBlockInteractionPoint}。
	 *
	 * <p>没有这一步，"攻击任意方块"就永远伸不过去 —— 手臂姿态全靠交互点算（{@link #anglesFor}），
	 * 而普通方块不在 Create 的交互点表里。用户第二轮反馈见 {@link OperationType#needsInteractionPoint()}。
	 */
	@Nullable
	private ArmInteractionPoint pointAt(BlockPos target, BlockState state, @Nullable Direction face) {
		if (level == null)
			return null;
		if (ArmInteractionPoint.isInteractable(level, target, state)) {
			ArmInteractionPoint point = ArmInteractionPoint.create(level, target, state);
			if (point != null)
				return point;
		}
		return face == null ? null : AnyBlockInteractionPoint.create(level, target, state, face);
	}

	/**
	 * 只解析目标、<b>不改变移动状态</b>：给运行时做"动身之前"的前置检查用
	 * （例如空置物台就别伸过去了，原地待机）。
	 */
	@Nullable
	public IItemHandler peekHandler(BlockPos offset) {
		if (level == null || level.isClientSide)
			return null;
		BlockPos target = worldPosition.offset(offset);
		if (!ArmInteractionPoint.isInteractable(level, target, level.getBlockState(target)))
			return null;
		return handlerAt(target);
	}

	public void cancelMove() {
		// 收工/换目标/等待/停机时都要清掉客户端的挖掘裂纹（残留的话方块上会挂着挖了一半的裂痕）
		rightClick.clearBreakingProgress();
		// 已经在待机就直接返回：重复发"进度归零"的同步会让客户端把动画钉在插值起点上
		// （表现为手臂卡在伸展姿态不收回）。这个坑是实测踩到的。
		if (phase == Phase.IDLE && movingPoint == null)
			return;
		movingPoint = null;
		movingHandler = null;
		movingHandlerOwner = null;
		chasedPointIndex = -1;
		chasedPointProgress = 0;
		phase = Phase.IDLE;
		// 必须同步：客户端要靠相位变化触发"折回静止位"的平滑插值
		setChanged();
		sendData();
	}

	public boolean hasArrived() {
		return phase == Phase.MOVING && chasedPointProgress >= 1;
	}

	@Nullable
	public IItemHandler getMovingHandler() {
		if (movingHandler == null || level == null || level.isClientSide)
			return movingHandler;
		// 快照可能已失效：移动途中目标容器被换掉/拿走时，旧 handler 指向的是**已经从世界移除的方块实体**，
		// 往里写就是把物品塞进一个没人看得见的对象（物品凭空消失）。每次读取时核对"还是那个方块实体吗"。
		BlockPos target = worldPosition.offset(movingOffset);
		BlockEntity current = level.getBlockEntity(target);
		if (current != movingHandlerOwner) {
			movingHandlerOwner = current;
			movingHandler = handlerAt(target);
			markProgramDirty();
		}
		return movingHandler;
	}

	/**
	 * 爪子（作用点）当前的世界坐标 —— 给音效/粒子定位用。
	 * 目标点自己的"取物位置"就是爪子实际够到的地方（{@link #anglesFor} 用的同一个向量），
	 * 没有目标时退回臂底中心。
	 */
	public Vec3 getEffectorPosition() {
		ArmInteractionPoint point = currentPoint();
		if (point != null)
			return ((ArmInteractionPointAccessor) point).create$getInteractionPositionVector();
		return Vec3.atCenterOf(worldPosition.offset(movingOffset));
	}

	@Nullable
	private IItemHandler handlerAt(BlockPos pos) {
		if (level == null)
			return null;
		return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
	}

	@Nullable
	private ArmInteractionPoint currentPoint() {
		if (movingPoint == null && phase == Phase.MOVING && level != null)
			movingPoint = resolvePoint(movingOffset);
		return movingPoint;
	}

	@Nullable
	private ArmInteractionPoint resolvePoint(BlockPos offset) {
		if (level == null)
			return null;
		BlockPos target = worldPosition.offset(offset);
		return pointAt(target, level.getBlockState(target), movingFace);
	}

	private SmartArmAngleTarget anglesFor(ArmInteractionPoint point) {
		// 各交互点类型自己的"取物位置 + 朝向"（protected，靠 Mixin 访问器拿）
		ArmInteractionPointAccessor access = (ArmInteractionPointAccessor) point;
		return new SmartArmAngleTarget(worldPosition, access.create$getInteractionPositionVector(),
			access.create$getInteractionDirection(), isOnCeiling());
	}

	// ————————————————————————— 库存 —————————————————————————

	public ItemStack getHeldItem() {
		return heldItem;
	}

	public void setHeldItem(ItemStack stack) {
		heldItem = stack;
		markProgramDirty();
	}

	public List<ItemStack> getOverflow() {
		return overflow;
	}

	/**
	 * 溢出库存的**出库顺序**：栈式 —— **后进的先出**（PLAN.md §7.3，用户第二十七轮）。
	 *
	 * <p>用户给的例子：依次进泥土、草方块、泥土、石头 ⇒ 两块泥土合并到同一格，溢出是
	 * {@code [泥土×2, 草方块, 石头]}，出库顺序应当是 <b>石头 → 草方块 → 泥土</b>。
	 *
	 * <p>为什么"倒着走一遍槽位"就等于"后进的先出"：槽位顺序天生就是"物品**第一次**出现的先后"
	 *（新物品 append 到末尾，合并只写回**原来那一格**），所以不需要额外记时间戳。
	 *
	 * <p><b>所有从溢出往外拿的路径都必须用这个顺序</b>：卸货（{@code ProgramRuntime.unloadRound}）与
	 * 空手潜行右键取一组（{@link SmartArmItemHandler#extractFromEnd}）。两处各写一份必然有一天不一致。
	 *
	 * <p>⚠️ 外部自动化（漏斗/溜槽抽臂）**不走这里**：抽取方自己挑槽号，我们无从规定顺序。
	 * 要让它们也变成栈式，得把溢出的物理槽序整体倒过来（`getStackInSlot` / NBT 布局一起动）——
	 * 那是另一件事，等有人提再说。
	 */
	public List<ItemStack> overflowOutOrder() {
		List<ItemStack> out = new ArrayList<>(overflow);
		Collections.reverse(out);
		return out;
	}

	/** 库存视图（懒建）：能力注册与臂自己的插入都走它，插入算法因此只有一份。 */
	public SmartArmItemHandler itemHandler() {
		if (itemHandler == null)
			itemHandler = new SmartArmItemHandler(this);
		return itemHandler;
	}

	/** 取前模拟用：臂内还接得下吗（与真插入同一份算法，见 {@link SmartArmItemHandler#canAccept}）。 */
	public boolean canAccept(ItemStack stack) {
		return itemHandler().canAccept(stack);
	}

	/** 放进臂内：先手持，再并入已有溢出堆，最后占空格；都满了就正常掉到世界（§7.4 满仓行为）。 */
	public void insertIntoInventory(ItemStack stack) {
		if (stack.isEmpty())
			return;
		ItemStack remainder = itemHandler().insert(stack, false);
		if (!remainder.isEmpty() && level != null)
			Block.popResource(level, worldPosition, remainder);
		markProgramDirty();
	}

	/**
	 * DEPLOY 之后结算手上的应用物 —— 顺序照抄 {@code BeltDeployerCallbacks.activate}：
	 * 配方要求保留则不动；有耐久则掉 1 点耐久；否则消耗 1 个并把手上的"容器物"（剩余物）收回。
	 */	public void consumeHeldItem(boolean keep) {
		if (keep || heldItem.isEmpty() || player == null)
			return;
		if (heldItem.getMaxDamage() > 0) {
			heldItem.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
		} else {
			ItemStack leftover = heldItem.getCraftingRemainingItem();
			heldItem.shrink(1);
			if (heldItem.isEmpty())
				heldItem = leftover;
			else if (!leftover.isEmpty())
				insertIntoInventory(leftover);
		}
		markProgramDirty();
	}

	// ————————————————————————— 假玩家（左右键执行体用） —————————————————————————

	@Nullable
	public DeployerFakePlayer getFakePlayer() {
		// 懒初始化：假玩家的常规初始化在 initialize()/tick() 里，但"放置后还没 tick 过"的方块实体
		// 也会被左右键执行体问到（自检就是直接放一块臂然后立刻用它）—— 顺手补一次，省掉一个空指针。
		initPlayer();
		return player;
	}

	/** 动作前：把臂内的手持物同步到假玩家主手。 */
	public void prepareFakePlayerHand() {
		if (player != null)
			player.setItemInHand(InteractionHand.MAIN_HAND, heldItem.copy());
	}

	/**
	 * 动作后：把假玩家那边的东西收回来。
	 *
	 * <p><b>顺序：先读主手、再排空其余槽</b>。Create 的 {@code DeployerFakePlayer} 会取消
	 * {@code LivingDropsEvent} 并把掉落物塞进假玩家背包（`DeployerFakePlayer.java:114-124`），
	 * 而 {@code placeItemBackInInventory} 是**从 0 号槽开始找位置**的：
	 * <ul>
	 *   <li>手上有工具 ⇒ 主手那格被工具占着 ⇒ 掉落物落在别的槽；</li>
	 *   <li>空手 ⇒ 第一条掉落物正好落在主手那格。</li>
	 * </ul>
	 * 所以"先读主手"恰好实现了用户要的规则：<b>手持格空 → 掉落物进手持格；否则进溢出</b>
	 *（主手读完之后，其余槽的掉落物统一走"手持满着就并进溢出"的那条路）。
	 *
	 * <p>反过来做（先排空、再读主手）会**把刚收进手持格的掉落物覆盖掉** —— 第一版就是这么写的，
	 * 自检日志 `溢出=0 格` 当场抓到一份牛排消失。
	 */
	public void syncHeldItemFromFakePlayer() {
		if (player == null)
			return;
		Inventory inventory = player.getInventory();
		// ① 主手 = "手上那把工具"动作之后的状态（耐久损耗、消耗、放置后变空手；
		//    空手动作时则是落进来的第一条掉落物）
		ItemStack after = player.getMainHandItem()
			.copy();
		if (!ItemStack.matches(after, heldItem)) {
			heldItem = after;
			markProgramDirty();
		}
		// 内容已经收进臂里了，假玩家不该再留一份（留着会被下一轮的排空当成掉落，或被同种掉落并进去）
		inventory.setItem(inventory.selected, ItemStack.EMPTY);
		// ② 其余槽里的掉落物
		collectFakePlayerDrops();
	}

	/**
	 * 把假玩家背包里剩下的东西收进臂内（上游 `DeployerBlockEntity:200-213` 的同款）。
	 *
	 * <p>规则（用户定）：<b>手持格空 → 这条掉落物进手持格；否则并进溢出</b>。
	 * 手持格已经被 {@link #syncHeldItemFromFakePlayer} 处理过了，所以这里第一条掉落物发现手持仍空
	 * 会补进手持格，之后的都进溢出。收不下的部分按满仓规则丢回世界（§7.4）。
	 */
	public void collectFakePlayerDrops() {
		if (player == null)
			return;
		Inventory inventory = player.getInventory();
		boolean changed = false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack item = inventory.getItem(i);
			if (item.isEmpty())
				continue;
			// 手持空 ⇒ 走"先补手持、再溢出"；手持满 ⇒ 只进溢出
			ItemStack leftover = heldItem.isEmpty() ? itemHandler().insert(item.copy(), false)
				: itemHandler().insertIntoOverflow(item.copy(), false);
			if (!leftover.isEmpty() && level != null)
				Block.popResource(level, worldPosition, leftover);
			inventory.setItem(i, ItemStack.EMPTY);
			changed = true;
		}
		if (changed)
			markProgramDirty();
	}

	// ————————————————————————— 护目镜 HUD（PLAN.md §5.3） —————————————————————————

	/**
	 * 护目镜信息（PLAN.md §5.3）。<b>只要玩家戴着护目镜就会显示</b> ——
	 * Create 的 {@code GoggleOverlayRenderer:109-111} 只在 {@code wearingGoggles} 时才调用本方法，
	 * 所以这里不需要（也不该）再看方块自己的 {@link #goggles} 标记：
	 * 那个标记是"爪子戴上护目镜"的<b>外观</b>变体，和查看信息是两件事。
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		// 基类会加应力信息（KineticBlockEntity:455），先留着
		boolean added = super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		if (added)
			tooltip.add(Component.empty());

		goggleLine().translate("gui.arm.header")
			.style(ChatFormatting.GOLD)
			.forGoggles(tooltip);

		// 只能用客户端可见的摘要 hud：程序本体不会同步过来（见 ArmProgramView 的说明）
		if (hud.taskCount == 0) {
			goggleLine().translate("gui.arm.no_program")
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			return true;
		}

		goggleLine().translate("gui.arm.task", hud.taskName)
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		goggleLine().translate("gui.arm.loop", hud.loopCounter, hud.loopMax)
			.style(ChatFormatting.DARK_GRAY)
			.forGoggles(tooltip, 1);

		// 当前步骤 —— 用户第三轮定稿的结构：**先"操作 + 必要属性"，再一行一个可选属性**。
		//   步骤 10/19：使用  +-2,1,0  上
		//   - 成功后 跳转 9
		//   - 失败后 5s 后 跳过
		// 全堆在一行（`USE rel-2, 1, 0 onSuccess=SKIP_TO(9)`）读起来太累，而且那句话与卡片上的胶囊
		// 完全是两套说法 —— 现在两边共用 program/ArmEntryText。
		// 序号按 1 起算：与卡片左上角的序号、「跳转 N」的 N 是同一套编号（0 起只在代码里）。
		if (hud.entry != null) {
			// 用 baseWithItems：工具提示里没有图标位，"等信号"绑的是哪对频率只能写名字
			goggleLine().translate("gui.arm.step", hud.entryIndex + 1, hud.entryCount,
				ArmEntryText.baseWithItems(hud.entry))
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			for (Component prop : ArmEntryText.props(hud.entry))
				goggleLine().translate("gui.arm.step_prop", prop)
					.style(ChatFormatting.DARK_GRAY)
					.forGoggles(tooltip, 2);
		} else {
			// 兜底：老存档/异常情况下只有字符串摘要
			goggleLine().translate("gui.arm.step", hud.entryIndex + 1, hud.entryCount, hud.entryText)
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
		}

		ChatFormatting stateColor = switch (hud.state) {
			case "HALTED" -> ChatFormatting.RED;
			case "WAITING", "RETRY" -> ChatFormatting.YELLOW;
			default -> ChatFormatting.AQUA;
		};
		goggleLine().translate("gui.arm.state", hud.state)
			.style(stateColor)
			.forGoggles(tooltip, 1);

		if (!hud.failure.isEmpty())
			goggleLine().text(hud.failure)
				.style(ChatFormatting.YELLOW)
				.forGoggles(tooltip, 1);
		if (!hud.error.isEmpty())
			goggleLine().translate("gui.arm.error", hud.error)
				.style(ChatFormatting.RED)
				.forGoggles(tooltip, 1);

		goggleLine().translate("gui.arm.inventory", describeHeldItem(), overflow.size(), MAX_OVERFLOW)
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		goggleLine().translate("gui.arm.redstone", redstoneLocked ? "ON" : "OFF",
			String.format("%.0f", getSpeed()))
			.style(ChatFormatting.DARK_GRAY)
			.forGoggles(tooltip, 1);

		// 潜行 + 护目镜 = 动画调试视图。
		// "手臂卡住/瞬移"这类问题全都出在 chasedPointProgress 被重置而客户端没拿到对应的
		// "新移动"信号（moveSeq）或相位变化 —— 这三个数摆出来就能一眼分辨是哪一种。
		if (isPlayerSneaking) {
			goggleLine().text(String.format("phase=%s progress=%.2f seq=%d target=%s", phase, chasedPointProgress,
				moveSeq, movingOffset.toShortString()))
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
		}
		return true;
	}

	/**
	 * 护目镜信息的缩进统一交给 catnip 的 {@code LangBuilder.forGoggles}：
	 * 它会加 {@code 4 + indents} 个空格（并按当前字体的空格宽度归一化）。
	 * 自己手写空格会因为字体不同而与其他模组的护目镜信息对不齐 ——
	 * 之前这里没加缩进，表现就是文字压在护目镜图标上。
	 */
	private static LangBuilder goggleLine() {
		return new LangBuilder(Create_smart_mechanical_arm.MODID);
	}

	private String describeHeldItem() {
		return heldItem.isEmpty() ? "-"
			: heldItem.getCount() + "x" + heldItem.getHoverName()
				.getString();
	}

	// ————————————————————————— tick —————————————————————————

	@Override
	public void tick() {
		super.tick();
		tickMovementProgress();

		// 移动途中锁住目标上的物品（上游 `ArmBlockEntity:133-140`）：置物台/传送带上的物品会被
		// `keepAlive()` 标记为 `lockedExternally`。少了这一步，前置检查（动身前）与真正取物（几 tick 后）
		// 之间那段时间里，带子完全有可能已经把物品送走 —— 表现就是"从传送带取物偶尔取空"。
		if (level != null && !level.isClientSide && chasedPointProgress < 1 && movingPoint != null)
			movingPoint.keepAlive();

		if (level == null || level.isClientSide)
			return;
		if (player == null) {
			initPlayer();
			if (player == null)
				return;
		}
		runtime.tick(this);
	}

	/** 推进移动进度；客户端顺带做角度插值（照抄 ArmBlockEntity.tickMovementProgress 的语义）。 */
	private void tickMovementProgress() {
		chasedPointProgress += Math.min(256, Math.abs(getSpeed())) / 1024f;
		if (chasedPointProgress > 1)
			chasedPointProgress = 1;
		if (level == null || !level.isClientSide)
			return;

		ArmInteractionPoint targeted = currentPoint();
		SmartArmAngleTarget target = targeted == null ? SmartArmAngleTarget.RESTING : anglesFor(targeted);

		baseAngle.setValue(AngleHelper.angleLerp(chasedPointProgress, previousBaseAngle,
			target == SmartArmAngleTarget.RESTING ? previousBaseAngle : target.baseAngle));

		SmartArmAngleTarget from = previousTarget;
		if (chasedPointProgress < .5f)
			target = SmartArmAngleTarget.RESTING; // 前半段：折回静止位
		else
			from = SmartArmAngleTarget.RESTING; // 后半段：从静止位伸向目标
		float progress = chasedPointProgress == 1 ? 1 : (chasedPointProgress % .5f) * 2;

		lowerArmAngle.setValue(Mth.lerp(progress, from.lowerArmAngle, target.lowerArmAngle));
		upperArmAngle.setValue(Mth.lerp(progress, from.upperArmAngle, target.upperArmAngle));
		headAngle.setValue(AngleHelper.angleLerp(progress, from.headAngle % 360, target.headAngle % 360));
	}

	// ————————————————————————— 红石 —————————————————————————

	public void redstoneUpdate() {
		if (level == null || level.isClientSide)
			return;
		boolean blockPowered = level.hasNeighborSignal(worldPosition);
		if (blockPowered == redstoneLocked)
			return;
		redstoneLocked = blockPowered;
		sendData();
	}

	public boolean isRedstoneLocked() {
		return redstoneLocked;
	}

	public boolean hasNeighborSignal() {
		return level != null && level.hasNeighborSignal(worldPosition);
	}

	@Override
	public void destroy() {
		super.destroy();
		if (level != null) {
			if (!heldItem.isEmpty())
				Block.popResource(level, worldPosition, heldItem);
			overflow.forEach(stack -> Block.popResource(level, worldPosition, stack));
		}
		heldItem = ItemStack.EMPTY;
		overflow.clear();
		if (player != null) {
			// 假玩家背包里的东西也要还回世界：上游 `DeployerBlockEntity.discardPlayer:466-474` 就是
			// `inventory.dropAll()` + 丢掉溢出 —— 少了这一句，被 `LivingDropsEvent` 塞进背包的击杀掉落物
			// 会随 `player.discard()` 一起消失（审计发现的"物品黑洞"）。
			player.getInventory()
				.dropAll();
			player.discard();
			player = null;
		}
	}

	/**
	 * 结构变换 / 蓝图放置时把**相对偏移**一起转过去（PLAN.md §6、上游 `ArmBlockEntity:424-433`）。
	 *
	 * <p>为什么必须有这一步：臂内部存的偏移是"相对臂的世界轴偏移"（与上游 `serialize(anchor)` 一致），
	 * 蓝图把整台机器**平移**时它自动成立，但**旋转/镜像**时臂的朝向转了、目标方块也转了，偏移却不会跟着转
	 * ⇒ 复制出来的程序全部指错方向。上游的做法就是对存下来的偏移调 `transform.applyWithoutOffset(pos)`
	 *（只取旋转/镜像、不含结构平移）。数学上正确：旋转是线性的，`R(目标) − R(臂) = R(目标 − 臂)`。
	 *
	 * <p>相对偏移之外的情况这里不用管：绝对条目在"插卡进臂"那一刻就被 {@code ProgramCoordinates.toRelative}
	 * 换算成相对了，所以 BE 里的程序不含绝对条目（这一条由 {@link SmartArmSelfTest} 顺带钉住）。
	 */
	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		boolean changed = false;
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				if (!entry.op.requiresTarget() || entry.absolute)
					continue;
				entry.offset = transform.applyWithoutOffset(entry.offset);
				changed = true;
			}
		}
		if (changed) {
			markProgramDirty();
			notifyUpdate();
		}
	}

	// ————————————————————————— 存读盘 / 同步 —————————————————————————

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putBoolean("Powered", redstoneLocked);
		tag.putBoolean("Goggles", goggles);
		tag.putInt("Phase2", phase.ordinal());
		tag.put("Offset", net.minecraft.nbt.NbtUtils.writeBlockPos(movingOffset));
		if (movingFace != null)
			tag.putString("Face", movingFace.getSerializedName());
		tag.putInt("MoveSeq", moveSeq);
		tag.putFloat("Progress", chasedPointProgress);
		tag.put("HeldItem", heldItem.saveOptional(registries));
		tag.put("Overflow", NBTHelper.writeItemList(overflow, registries));
		if (!clientPacket) {
			tag.put("Program", program.save(registries));
			tag.put("Runtime", runtime.save());
		} else {
			// 客户端拿不到程序本体（最多 256 条，且每次条目切换都会同步，塞进去会刷爆包），
			// 所以只发护目镜需要的摘要（含**当前这一条**的完整 NBT —— 见 ArmProgramView.entry）。
			// 完整程序的同步留给阶段 3c 的编辑器 Menu。
			tag.put("Hud", ArmProgramView.capture(program, runtime, registries)
				.save(registries));
		}
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);

		BlockPos oldOffset = movingOffset;
		Phase oldPhase = phase;
		int oldMoveSeq = moveSeq;

		redstoneLocked = tag.getBoolean("Powered");
		goggles = tag.getBoolean("Goggles");
		phase = Phase.values()[Mth.clamp(tag.getInt("Phase2"), 0, Phase.values().length - 1)];
		movingOffset = NBTHelper.readBlockPos(tag, "Offset");
		movingFace = tag.contains("Face") ? Direction.byName(tag.getString("Face")) : null;
		moveSeq = tag.getInt("MoveSeq");
		chasedPointProgress = tag.getFloat("Progress");
		heldItem = ItemStack.parseOptional(registries, tag.getCompound("HeldItem"));
		overflow.clear();
		overflow.addAll(NBTHelper.readItemList(tag.getList("Overflow", Tag.TAG_COMPOUND), registries));

		if (!clientPacket) {
			ArmProgram loaded = ArmProgram.load(tag.getCompound("Program"), registries);
			program.tasks.clear();
			program.tasks.addAll(loaded.tasks);
			program.absolute = loaded.absolute;
			runtime.load(tag.getCompound("Runtime"));
			return;
		}

		hud.load(tag.getCompound("Hud"), registries);

		// 客户端：只要"新的一次移动开始了"就快照**当前**姿态作为插值起点。
		// 判据用 moveSeq（每次 beginMove 自增）—— 不能只看偏移/相位：
		// 失败重试时目标就是同一个方块，偏移与相位都不变，那样会永远不快照（见字段注释）。
		//
		// ⚠️ 起点取**四个 chaser 的当前值**（手臂现在实际在哪），不要按相位去猜：
		// 用户实测"等信号时上一个动作还没结束就瞬移回原位" —— 因为「等待」会先 cancelMove()（相位变 IDLE，
		// 手臂正在折返途中），等它结束时新移动就"从静止位起步"，把半路的姿态瞬间摆回去。
		// 取当前值对两种情况都成立：旧移动已到位（当前值就是旧目标）与旧移动未做完（当前值就是半路位置）。
		if (oldMoveSeq != moveSeq || !oldOffset.equals(movingOffset) || oldPhase != phase) {
			movingPoint = null;
			previousTarget = SmartArmAngleTarget.of(baseAngle.getValue(), lowerArmAngle.getValue(),
				upperArmAngle.getValue(), headAngle.getValue());
			previousBaseAngle = previousTarget.baseAngle;
		}
	}

}
