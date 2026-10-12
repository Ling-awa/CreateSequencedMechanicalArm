package io.github.Ling.create_smart_mechanical_arm.smartarm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.SmartArmConfig;
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
import net.createmod.catnip.data.Couple;
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
	/**
	 * 这次移动要不要**锁住目标上的物品**（取物 / 组装 = 是；投放类 = 否）。
	 *
	 * <p>锁的动作是 {@link ArmInteractionPoint#keepAlive()}（对传送带就是 `lockedExternally = true`），
	 * 而带子**每 tick 会把标记消费掉**（`BeltInventory:119-127`：这一 tick 不动，然后清标记），
	 * 所以必须每 tick 续锁 —— 见 {@link #tick()}。
	 */
	private boolean pinTarget;
	/**
	 * 当前被我们按住的那件带子物品（**按对象**认领，不靠位置窗口）。
	 *
	 * <p>为什么要记住对象：`BeltPoint.keepAlive()` 只在"方块中心 ±.51"的窗口里找一件物品，
	 * 带速高时物品在两次服务器 tick 之间就可能跳出去 ⇒ 那一次什么也锁不到 ⇒ 物品溜走
	 * （用户第二十七轮续二十九："高速时加工一次后立即溜走，中低速正常"）。
	 */
	private TransportedItemStack pinnedBeltItem;
	/** 已经给它打过"客户端按住"标记（`locked`）的那一件 —— 只在换了对象时再同步一次。 */
	private TransportedItemStack clientHoldItem;
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
		// 卡被换掉 = 上一个程序不再拥有它发出的频率 ⇒ **在清空 tasks 之前**先收尾
		//（§7.16b 一.4：换程序是"世界里留一个永远通电的频率"的一条路，三处钩子缺一不可）。
		clearTransmissions();
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
		return beginMove(offset, face, false);
	}

	/**
	 * {@code pin=true}：目标上的物品**立刻**锁一次，并在整条条目期间每 tick 续锁（见 {@link #tick()}）。
	 *
	 * <p>⚠️ 为什么必须**在这里**先锁一次，而不是等下一 tick 的 {@link #tick()}：定目标发生在
	 * {@code runtime.tick()} **内部**，而 {@link #tick()} 里的续锁跑在它**之前** —— 等下一 tick 再锁
	 * 就晚了整整一个 tick（16 rpm 的带子一 tick 也能挪出可取窗口），实测表现正是
	 * **"被定住的时候物品已经跑出去了"**（用户第二十七轮续二十三）。
	 */
	public boolean beginMove(BlockPos offset, @Nullable Direction face, boolean pin) {
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
		pinTarget = pin;
		if (pin && !level.isClientSide)
			point.keepAlive(); // 同一 tick 先锁一次（理由见上面那段注释）
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
		// 松手时也要把**客户端那一半**（带子物品的 `locked`）放开 —— 它不会被自动消费，
		// 忘了清就会在客户端留下一件永远不动的"幽灵物品"（服务端那边 `lockedExternally` 本来就是每 tick 重设的）。
		if (clientHoldItem != null || pinnedBeltItem != null)
			releaseBeltHold();
		// 收工/换目标/等待/停机时都要清掉客户端的挖掘裂纹（残留的话方块上会挂着挖了一半的裂痕）
		rightClick.clearBreakingProgress();
		// 已经在待机就直接返回：重复发"进度归零"的同步会让客户端把动画钉在插值起点上
		// （表现为手臂卡在伸展姿态不收回）。这个坑是实测踩到的。
		if (phase == Phase.IDLE && movingPoint == null)
			return;
		movingPoint = null;
		pinTarget = false;
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
		if (level == null || level.isClientSide)
			return null;
		// 快照可能已失效：移动途中目标容器被换掉/拿走时，旧 handler 指向的是**已经从世界移除的方块实体**，
		// 往里写就是把物品塞进一个没人看得见的对象（物品凭空消失）。每次读取时核对"还是那个方块实体吗"。
		//
		// ⚠️ `movingHandler == null` **也要走重解析**：读盘路径会把它清成 null（只留存盘的 `Offset`），
		// 而"退出重进"就是走这条路 —— 旧写法（`if (movingHandler == null) return movingHandler;`）
		// 会在缓存为空时**直接把 null 返回**，自愈分支永远跑不到 ⇒ 重登后**第一次取物必空**
		// （失败后走重试、下一轮 `beginMove` 才把缓存填回来，所以第二次又正常了 —— 用户第二十七轮续二十七实测）。
		BlockPos target = worldPosition.offset(movingOffset);
		BlockEntity current = level.getBlockEntity(target);
		if (movingHandler == null || current != movingHandlerOwner) {
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

	/**
	 * 每 tick 按住目标带子上的那件物品。
	 *
	 * <p><b>为什么两个标记都要用</b>（这是踩了三轮才对上的地方）：
	 * <ul>
	 *   <li>{@code locked} 才是真正"按住"的那个：服务端 `BeltInventory:161-162`
	 *       （{@code if (currentItem.locked) continue;}）与客户端 `:119-121` **都会**因此跳过这一 tick 的移动，
	 *       而且**都不会把它清掉**；它还随物品一起存盘/同步 ⇒ 客户端既不用额外发包、也不会抽动。</li>
	 *   <li>{@code lockedExternally} 是"挡住带子自己的加工路径"的盾：带子在 `:241-252` 里发现物品
	 *       {@code locked} 却**找不到加工行为**（我们是**臂**，不是装在带子 2 格高的
	 *       {@code BeltProcessingBehaviour}，见该类注释 `:13-17`）时，会替我们把它清掉并
	 *       {@code notifyUpdate()}。只要 {@code lockedExternally} 还在，`:124-126` 就会先 {@code continue}，
	 *       那段清理代码永远走不到。它每 tick 被消费一次 ⇒ **每 tick 重设**。</li>
	 * </ul>
	 */
	private void holdBeltItem() {
		TransportedItemStack item = findBeltItem();
		if (item == null)
			return;
		item.lockedExternally = true; // 每 tick 重设（会被带子消费）；locked 不会被消费，认领时设一次即可
	}

	/**
	 * 认领要按住的那件物品：上次那件还在就继续用它（哪怕它已经漂出目标格），
	 * 否则在**目标格**里挑离中心最近的一件（原地加工会把对象换掉，所以要能重新认领）。
	 *
	 * <p>认领时**顺手把它拉到格子中心**（照 `BeltInventory:291-295` 的 `ProcessingResult.HOLD` 处理）：
	 * 臂的取物点就在格子中心，而 `handleProcessingOnAllItems` 只认"中心 ±.51" ⇒
	 * 物品停在格子边缘时，取物与组装两条路都可能"看不到它"（用户第二十七轮续三十实测：
	 * 改完反而完全抓不到 —— 就是因为只锁不定心）。
	 */
	@Nullable
	private TransportedItemStack findBeltItem() {
		BeltInventory inventory = beltInventory();
		BeltBlockEntity belt = beltBlock();
		if (inventory == null || belt == null)
			return null;
		List<TransportedItemStack> items = inventory.getTransportedItems();
		TransportedItemStack item = pinnedBeltItem != null && items.contains(pinnedBeltItem) ? pinnedBeltItem : null;
		if (item == null) {
			float center = belt.index + .5f;
			float bestDistance = Float.MAX_VALUE;
			for (TransportedItemStack candidate : items) {
				if (candidate.stack.isEmpty())
					continue;
				if (candidate.beltPosition < belt.index || candidate.beltPosition >= belt.index + 1)
					continue; // 只认目标格
				float distance = Math.abs(candidate.beltPosition - center);
				if (distance < bestDistance) {
					bestDistance = distance;
					item = candidate;
				}
			}
		}
		pinnedBeltItem = item;
		if (item == null) {
			clientHoldItem = null;
			return null;
		}
		if (item != clientHoldItem || !item.locked) {
			centreOnBelt(belt, item);
			item.locked = true;
			clientHoldItem = item;
			syncBeltController();
		}
		return item;
	}

	/** 把物品拉到目标格中心；1/512 那个偏移是照抄上游的（让它落在中心的正确一侧）。 */
	private void centreOnBelt(BeltBlockEntity belt, TransportedItemStack item) {
		boolean positive = belt.getDirectionAwareBeltMovementSpeed() > 0;
		item.beltPosition = belt.index + .5f + (positive ? 1 / 512f : -1 / 512f);
		item.prevBeltPosition = item.beltPosition;
	}

	/** 松手：两个标记都要清（`locked` 不会被自动消费，忘清会在客户端留下永远不动的"幽灵物品"）。 */
	private void releaseBeltHold() {
		if (clientHoldItem != null) {
			clientHoldItem.locked = false;
			clientHoldItem.lockedExternally = false;
			syncBeltController();
		} else if (pinnedBeltItem != null)
			pinnedBeltItem.lockedExternally = false;
		clientHoldItem = null;
		pinnedBeltItem = null;
	}

	@Nullable
	private BeltInventory beltInventory() {
		BeltBlockEntity belt = beltBlock();
		if (belt == null)
			return null;
		BeltBlockEntity controller = belt.getControllerBE();
		return controller == null ? null : controller.getInventory();
	}

	@Nullable
	private BeltBlockEntity beltBlock() {
		if (level == null || movingOffset == null)
			return null;
		return level.getBlockEntity(worldPosition.offset(movingOffset)) instanceof BeltBlockEntity belt ? belt : null;
	}

	private void syncBeltController() {
		BeltBlockEntity belt = beltBlock();
		BeltBlockEntity controller = belt == null ? null : belt.getControllerBE();
		if (controller != null)
			controller.sendData();
	}

	/**
	 * 现在要不要**续锁**目标上的物品：运行时说"这一条正在从目标取东西"（`TAKE`/`DEPLOY` 且已开始/正在执行）。
	 *
	 * <p>⚠️ **停机（无应力）时也继续锁** —— 这是**原版语义**：上游 `ArmBlockEntity:133-140` 只看
	 * `chasedPointProgress < 1`，所以一条没通电的臂同样会把传送带上的物品一直占住（旧物品被拿走/移走后再来的
	 * 新物品也会被它锁住）。用户第二十七轮续二十五明确："停机臂无限期占住物品是原版逻辑，我们的没有问题"，
	 * 所以**不要**在这里加"没应力就松手"。
	 */
	public boolean canPinTarget() {
		return runtime.shouldPinTarget(this);
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

		// 保持中的信号（§7.16e：信号是持久变量，玩家得看得见"这条臂现在还按着几路"）。
		// 只有真的在保持时才多画一行；**超上限被拒的那次也要看得见**（用户：不许静默丢）。
		if (heldSignalCount() > 0 || armLinkRejectedLastTick > 0) {
			goggleLine().text("Signals: " + heldSignalCount() + " / " + maxHeldSignals())
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
			if (armLinkRejectedLastTick > 0)
				goggleLine().text("Signal rejected (limit " + maxHeldSignals() + ")")
					.style(ChatFormatting.RED)
					.forGoggles(tooltip, 2);
		}

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

		// 取物/组装期间续锁目标上的物品（上游 `ArmBlockEntity:133-140` 的同一机制）。分两半：
		//   ① 服务端：`keepAlive()`（`lockedExternally`）—— **必须每 tick 重设**，带子每 tick 会把它消费掉；
		//   ② 客户端：`locked` —— **只在状态变化时同步一次**（客户端不会消费它），这样就不会因为
		//      "包一抖、物品动一格又被纠正回来"而抽动（见 applyBeltClientHold 的注释）。
		//
		// ⚠️ 判据由**运行时**给（`shouldPinTarget()`），而不是只看 `chasedPointProgress < 1` ——
		// 旧写法会在**刚好到位**那一 tick 停掉续锁，而真正取物就发生在那之后（用户第二十七轮续二十三实测）。
		// ⚠️ 判据**不能**用 `movingPoint != null`：读盘路径会把它显式清成 null（只留存盘的 `phase`/`Offset`），
		// 而"退出重进"正是走读盘这条路 ⇒ 用缓存当判据会在重登后**静默松手**（用户第二十七轮续二十六实测）。
		// 所以改用 {@link #pinPoint()}：先看缓存，缓存没了就按 `movingOffset` 重建。
		ArmInteractionPoint pinPoint = pinPoint();
		boolean pinning = level != null && !level.isClientSide && pinPoint != null && canPinTarget();
		if (pinning)
			holdBeltItem();
		else if (clientHoldItem != null || pinnedBeltItem != null)
			releaseBeltHold();

		if (level == null || level.isClientSide)
			return;
		if (player == null) {
			initPlayer();
			if (player == null)
				return;
		}
		runtime.tick(this);
	}

	/**
	 * 要续锁的那个交互点：优先用移动中缓存的 {@link #movingPoint}，**缓存没了就按 {@link #movingOffset} 重建**。
	 *
	 * <p>为什么必须能重建：读盘时 `movingPoint` 被显式清成 null（只留 `phase`/`Offset` 两个存盘字段），
	 * 而"退出重进"走的就是读盘 ⇒ 只看缓存的写法会在重登后静默松手，把已经定住的物品放回传送带上
	 * （用户第二十七轮续二十六实测：重进后被定住的物品又被释放了）。
	 */
	@Nullable
	public ArmInteractionPoint pinPoint() {
		ArmInteractionPoint point = currentPoint();
		if (point == null && movingOffset != null && level != null && !level.isClientSide)
			point = resolvePoint(movingOffset);
		return point;
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

	// ————————————————————————— 收发信号（§7.16e 持久变量 / 多路并存） —————————————————————————
	//
	// 用户定稿（§7.16e）：**一个频率 = 一个持久布尔变量**。`发` = 置位、`中断` = 复位、`收` = 读值。
	// 信号一旦发出就**一直保持**：跨循环、跨停机、断动力、无转速、主任务正常结束都**不断**。
	// 只有两处会断：① 主动「中断」（只断指定那一频率）；② 操作卡变化（换卡 / 改程序 / 回到开头）。
	// 拆方块 / 区块卸载：前者清零退网，后者（onChunkUnloaded）**只退网、保留字段与 NBT**。
	//
	// 发射端的全部要求 = isListening() == false + getTransmittedStrength() > 0（§7.16 已核实）。
	// 两个必须守住的不对称（§7.16a 第 5 条）：
	//  ① getNetworkOf(level, actor) 把 level 当 IdentityHashMap 的**键** ⇒ level == null 会 NPE；
	//  ② hasAnyLoadedPower 只读强度、**完全不看 isAlive()** ⇒ "把 isAlive() 弄成 false"**不能代替**
	//     "把强度关到 0"。所以收尾一律是显式的三步：关 0 → updateNetworkOf → removeFromNetwork。

	/**
	 * 同时保持的频率条数上限（§7.16e：不限制用法，只留一条"别把网络表撑爆"的上限）。
	 *
	 * <p>值来自配置项 {@code maxHeldSignals}（默认 {@link SmartArmConfig#DEFAULT_MAX_HELD_SIGNALS}）；
	 * ⚠️ 运行期读出来的数，**不是**编译期常量 —— 别拿它当数组长度/注解参数。
	 * 配置未加载时回落默认值（见 {@link SmartArmConfig#maxHeldSignals()}）。
	 */
	public static int maxHeldSignals() {
		return SmartArmConfig.maxHeldSignals();
	}

	/**
	 * 最近一次「发」因为表满被拒之后留下的状态：{@code >0} = 有过一次被拒，{@code 0} = 干净。
	 *
	 * <p>存在理由（§7.16e：超了"拒绝并留可见状态"，**不静默丢**）：**日志不算可见** —— 它只能事后翻。
	 * 护目镜与自检都读它，语义与运行时的 {@code lastFailure} 同一手法（"上次出过什么事"，
	 * 在下一次成功发出/清零时消失）。
	 */
	private int armLinkRejectedLastTick;

	/**
	 * 已保持的信号表：**每个频率一份自己的电平与脉冲计时**（§7.16e）。
	 *
	 * <p>每个 entry 的 key 天生稳定 ⇒ §7.16a 第 6 条那个"换频率必须先按旧 key 退网"的坑**不存在**了
	 * （见 {@link ArmLink#frequency}）。惰性建：绝大多数臂一次信号都不发。
	 */
	@Nullable
	private Map<Couple<Frequency>, ArmLink> heldSignals;

	/**
	 * 臂的发送端：**一对频率一个实例**（普通内部类，照着上游
	 * {@code LinkedControllerServerHandler.ManualFrequencyEntry} 写 —— 不必是 {@code BlockEntityBehaviour}，
	 * §7.16a 第 2 条）。
	 */
	private final class ArmLink implements IRedstoneLinkable {

		/**
		 * 本实例绑的那对频率 —— **构造后就不再变**（{@code final}）。
		 *
		 * <p>⚠️ 它同时是 {@code getNetworkKey()} 的返回值，而上游那个方法是**活的**：
		 * {@code addToNetwork} 按当时的 key 入 set，{@code removeFromNetwork} 按**当前的** key 找 set ——
		 * 频率一变，老 key 那格就永远删不掉（§7.16a 第 6 条）。改成"一频率一实例 + final key"之后，
		 * 这个坑从根上没有了：要换频率就换一个实例（旧的先按它**自己的** key 退网）。
		 */
		private final Couple<Frequency> frequency;
		/**
		 * 当前发射的电平（0..15）；{@code getTransmittedStrength()} 就读它。
		 *
		 * <p>⚠️ 字段名**不能**叫 {@code level}：内部类里的 `level` 会遮蔽（且这里的读写全部绑定到内层）
		 * 外层 {@code BlockEntity.level}，使 {@code getNetworkOf(level, this)} 传进去的是 int 而不是世界。
		 */
		private int signalLevel;
		/**
		 * 脉冲剩余 tick：{@code -1} = 没有在倒计时（**一直保持** —— 新设计里的正常状态）。
		 *
		 * <p>与旧版不同：它**随 NBT 持久化**（§7.16e：频率 + 电平 + 脉冲剩余三样都存），
		 * 否则"重进存档后脉冲停在保持"会被用户看成"信号莫名一直通电"。
		 */
		private int pulse = -1;

		private ArmLink(Couple<Frequency> frequency) {
			this.frequency = frequency;
		}

		@Override
		public int getTransmittedStrength() {
			return signalLevel;
		}

		@Override
		public void setReceivedStrength(int power) {
			// 上游的 updateNetworkOf 只对 isListening() 为真的成员推强度，我们永远收不到 ——
			// 空实现是**安全的**，不是"反正用不到"（§7.16a 第 7 条）
		}

		@Override
		public boolean isListening() {
			return false; // 发射端的全部要求就是这一条 + getTransmittedStrength() > 0
		}

		/**
		 * 照上游 {@code LinkBehaviour:213-223} 的三条判定：区块已卸载 / 方块实体已被移除 / 位置失效。
		 *
		 * <p>不直接调 {@code isChunkUnloaded()}（Create 的 {@code SmartBlockEntity} 提供它）：
		 * 本类只保证"已移除"与"位置不再指向我"两条，够用且不依赖上游那个方法是否还在。
		 */
		@Override
		public boolean isAlive() {
			if (isRemoved())
				return false;
			if (level == null || !level.isLoaded(worldPosition))
				return false;
			return level.getBlockEntity(worldPosition) == SmartArmBlockEntity.this;
		}

		@Override
		@Nullable
		public Couple<Frequency> getNetworkKey() {
			return frequency;
		}

		@Override
		public BlockPos getLocation() {
			return worldPosition;
		}

		/**
		 * 从网络表里退出去 —— 先归 0（此时 key 还没变，推的正是那一格）→ {@code updateNetworkOf}
		 * → {@code removeFromNetwork}（§7.16a 第 5 条：只退网而不归 0 会留下"永远通电"的频率，
		 * 因为 {@code hasAnyLoadedPower} 只读强度）。
		 *
		 * @param unloadOnly 区块卸载专用：**保留电平与脉冲**（重新加载时会原样恢复并入网，§7.16e）；
		 *                   true 时那三步也不是"归 0"，而是把当前强度推一遍再退网
		 */
		private void leaveNetwork(boolean unloadOnly) {
			if (!unloadOnly) {
				signalLevel = 0;
				pulse = -1;
			}
			if (level != null && !level.isClientSide) {
				// ① 先把强度推成 0（此时 key 还没变，推的正是那一格）
				Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, this);
				// ② 再退网（退网后 getNetworkOf 就找不到那一格了，所以顺序不能反）
				Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, this);
			}
		}
	}

	/** 把 {频率+电平+脉冲剩余} 重新挂回红石链接网络（读档 / 重新加载时用，§7.16e）。 */
	private void rejoinNetwork() {
		if (heldSignals == null || level == null || level.isClientSide)
			return;
		for (ArmLink link : heldSignals.values()) {
			Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, link);
			Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, link);
		}
	}

	private Map<Couple<Frequency>, ArmLink> heldSignals() {
		if (heldSignals == null)
			heldSignals = new HashMap<>();
		return heldSignals;
	}

	/**
	 * 往某个红石链接频率发一次电平 —— **get-or-create**（§7.16e：多路并存，不再是"一次只能按着一个频率"）。
	 *
	 * @param freq 目标频率对（收/发共用 {@code entry.frequency}）；{@code null} = 没绑，直接无视
	 * @param strength 要发的电平（0..15）；<b>0 是合法的</b> —— "发断电"就是它
	 * @param ticks 持续：{@code 0} = 一直保持（"置位"，新设计的默认），{@code >0} = 到点自动**归 0**
	 */
	public void emitSignal(@Nullable Couple<Frequency> freq, int strength, int ticks) {
		if (freq == null)
			return; // 没绑频率 = 不发（与"收"那边 frequency == null 直接返回 false 对称）
		// ⚠️ 形参**不能**叫 level：它会**遮蔽** BlockEntity 的 `level` 字段，于是下面几处网络调用
		// 会把 int（电平）当成 LevelAccessor 传进去（编译期就报 "int cannot be converted to LevelAccessor"）。
		if (level == null || level.isClientSide)
			return; // getNetworkOf 把 world 当 IdentityHashMap 的键，null 会 NPE；客户端也不该碰网络表
		ArmLink link = heldSignals().get(freq);
		if (link == null) {
			if (heldSignals().size() >= maxHeldSignals()) {
				// 超上限：**拒绝并留可见状态**（不静默丢，也不去动已经保持的那些）—— §7.16e
				armLinkRejectedLastTick = maxHeldSignals();
				markProgramDirty();
				return;
			}
			link = new ArmLink(freq);
			heldSignals().put(freq, link);
			Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, link); // 同一实例只入网一次
		}
		link.pulse = ticks <= 0 ? -1 : Math.max(1, ticks); // 0 = 一直保持
		link.signalLevel = Mth.clamp(strength, 0, 15);
		armLinkRejectedLastTick = 0; // 成功发出 = 上一次"被拒"的提示过时了（与 lastFailure 同一纪律）
		Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, link);
		sendData();
	}

	/**
	 * 「中断」（{@code SEND_OFF}）与收尾专用：**只断指定那一频率** —— 电平 0 + 退网 + 从表里移除，
	 * 并取消它未到期的脉冲（§7.16e 规则 ①：别的频率不受影响）。
	 *
	 * @return 这一频率原本是否在保持（给调用方与自检判断"有没有东西可断"）
	 */
	public boolean releaseSignal(@Nullable Couple<Frequency> freq) {
		if (freq == null || heldSignals == null)
			return false;
		ArmLink link = heldSignals.get(freq);
		if (link == null)
			return false;
		heldSignals.remove(freq);
		link.leaveNetwork(false);
		sendData();
		return true;
	}

	/**
	 * 推进所有脉冲计时器（由 {@code ProgramRuntime.tick()} 在**任何早退之前**调用 —— 脉冲是臂的物理状态，
	 * 程序停机 / 无转速 / 红石锁定时照样要收尾，§7.16e 施工要点 2）。
	 *
	 * <p>到点**只把电平归 0**，entry 留在表里（= 那个频率不再通电、也没有未到期的脉冲）。
	 * 这与旧版"恢复成发脉冲之前的电平"不同：新设计里"保持"是常态，没有"发完脉冲要还原成什么"可言。
	 *
	 * @return 本轮是否至少有一路发生了"到点归 0"（给自检用）
	 */
	public boolean tickArmLink() {
		if (heldSignals == null || heldSignals.isEmpty())
			return false;
		boolean fired = false;
		for (ArmLink link : heldSignals.values()) {
			if (link.pulse < 0)
				continue;
			if (--link.pulse > 0)
				continue;
			link.pulse = -1;
			link.signalLevel = 0;
			if (level != null && !level.isClientSide)
				Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, link);
			fired = true;
		}
		if (fired)
			sendData();
		return fired;
	}

	/**
	 * 把**所有**保持的频率强制关到 0 再退网（§7.16e 的"两处会断"之一：操作卡变化；
	 * 另一处是 {@link #releaseSignal}。拆方块 / 移除也走这里）。
	 *
	 * <p>⚠️ 停机（{@code halt}）与主任务正常结束**不再**调它 —— 那是用户有意推翻的旧行为，
	 * 见 §7.16e「反转」那一条。
	 */
	public void clearTransmissions() {
		boolean removed = heldSignals != null && !heldSignals.isEmpty();
		if (heldSignals != null) {
			for (ArmLink link : heldSignals.values())
				link.leaveNetwork(false);
			heldSignals.clear();
		}
		armLinkRejectedLastTick = 0;
		// 只有真的清掉了东西才推包：换卡那条路会连着调两三次（setProgram → onProgramReplaced → resetToStart），
		// 表本来就是空的时候不该多推（护目镜看到的是同一份数据）。
		if (removed)
			sendData();
	}

	/** 还在表里的频率条数（**不管电平**）—— 自检用它断言"配置上限"与"只断指定那一路"。 */
	public int heldSignalCount() {
		return heldSignals == null ? 0 : heldSignals.size();
	}

	/** 这条臂现在保持的所有频率（只读快照；自检用）。 */
	public List<Couple<Frequency>> heldSignalFrequencies() {
		return heldSignals == null ? List.of() : List.copyOf(heldSignals.keySet());
	}

	/** 最近一次「发」因为表满被拒的信号条数（{@code 0} = 没发生）；护目镜与自检都读它。 */
	public int rejectedSignalCount() {
		return armLinkRejectedLastTick;
	}

	/** 当前发射的最大电平（0 = 没在发）；给自检与调试用。 */
	public int getArmLinkLevel() {
		if (heldSignals == null)
			return 0;
		int max = 0;
		for (ArmLink link : heldSignals.values())
			max = Math.max(max, link.signalLevel);
		return max;
	}

	/**
	 * 这条臂现在是不是**正按着**某一对频率 —— 即"表里有它"而且强度不为 0。
	 *
	 * <p>抽出来给自检用（{@code ArmLink} 是私有的，测试类拿不到它）：只读、不碰网络表
	 * （{@code getNetworkOf(world, actor)} 会**顺手建一个空格子**，拿它当查询用会污染网络表）。
	 */
	public boolean isTransmittingTo(Couple<Frequency> key) {
		if (heldSignals == null)
			return false;
		ArmLink link = heldSignals.get(key);
		return link != null && link.signalLevel > 0;
	}

	/**
	 * 这一对频率还在不在表里（**不管电平**）—— 自检用：抓"表里留着一个强度 0 的幽灵成员"
	 * （§7.16a 第 6 条那种只看"有没有电"抓不到的残留）。
	 */
	public boolean holdsSignal(Couple<Frequency> key) {
		return heldSignals != null && heldSignals.containsKey(key);
	}

	/** 当前**挂着的**网络 key 之一：{@code null} = 表空（还没发过 / 已经全退网）。 */
	@Nullable
	public Couple<Frequency> currentArmLinkKey() {
		return heldSignals == null || heldSignals.isEmpty() ? null
			: heldSignals.keySet()
				.iterator()
				.next();
	}

	/**
	 * 区块卸载（§7.16e）：**退网但保留字段与 NBT** —— 不清电平、不清表。
	 *
	 * <p>两条理由：① 不留"来自未加载的臂"的幽灵常通频率；② 重新加载时可以原样恢复
	 * （{@code read} 的 {@code !clientPacket} 分支会 {@code rejoinNetwork()}）。
	 *
	 * <p>为什么这条路只能挂在这里：{@code remove()} 在区块卸载时**不会**被调
	 * （{@code SmartBlockEntity.setRemoved()} 里是 {@code if (!chunkUnloaded) remove();}），
	 * 而 {@code setRemoved()} 本身是 {@code final} 不能覆写（§7.16a 第 4 条）。
	 */
	@Override
	public void onChunkUnloaded() {
		if (heldSignals != null)
			for (ArmLink link : heldSignals.values())
				link.leaveNetwork(true);
		super.onChunkUnloaded();
	}

	/** 非区块卸载的移除（拆方块 / 被替换）—— **清零并退网**（§7.16e 规则 ⑤）。 */
	@Override
	public void remove() {
		clearTransmissions();
		super.remove();
	}

	/**
	 * 方块被破坏 / 被替换（`destroy()` 是上游对"方块实体随方块一起没了"的钩子）：
	 * **清零并退网**（§7.16e 规则 ⑤ —— 不清就是幽灵常通频率）。
	 */
	@Override
	public void destroy() {
		clearTransmissions(); // 方块被破坏 / 替换：先把发出去的频率收干净
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

	/**
	 * 把 {"频率" → 电平 + 脉冲剩余} 写进 NBT（§7.16e：存档与客户端同步包都走这里）。
	 *
	 * <p>键结构刻意与条目里的 {@code freq} 一模一样（`[{Item1},{Item2}]`），读的一侧因此能直接复用
	 * {@link ArmProgramEntry#readFrequency} —— "频率怎么存"只有一份实现，不会两边写法漂移。
	 */
	private void writeSignalTable(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		if (heldSignals != null) {
			for (ArmLink link : heldSignals.values()) {
				if (link.frequency == null)
					continue;
				CompoundTag entry = new CompoundTag();
				entry.put("freq", link.frequency.serializeEach(f -> (CompoundTag) f.getStack()
					.saveOptional(registries)));
				entry.putInt("sig", link.signalLevel);
				entry.putInt("pulse", link.pulse);
				list.add(entry);
			}
		}
		tag.put("Signals", list);
	}

	/** 读回信号表（存档与客户端包共用）。**不碰网络表** —— 入网由服务端的 {@link #rejoinNetwork()} 做。 */
	private void readSignalTable(CompoundTag tag, HolderLookup.Provider registries) {
		if (heldSignals != null) {
			// 注意：这里**不做网络操作**。read() 只发生在"刚建出来的 BE 读盘"与"客户端读同步包"两条路上，
			// 世界里的网络表本来就没有我们的成员；服务端读完之后紧接着就 rejoinNetwork()。
			heldSignals.clear();
		}
		armLinkRejectedLastTick = 0;
		ListTag list = tag.getList("Signals", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size() && i < maxHeldSignals(); i++) {
			CompoundTag entry = list.getCompound(i);
			Couple<Frequency> freq = ArmProgramEntry.readFrequency(entry, registries);
			if (freq == null)
				continue;
			ArmLink link = new ArmLink(freq);
			link.signalLevel = Mth.clamp(entry.getInt("sig"), 0, 15);
			int pulse = entry.getInt("pulse");
			link.pulse = pulse > 0 ? pulse : -1; // 0 / 缺键 / 负数一律当"一直保持"
			heldSignals().put(freq, link);
		}
	}

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
		// 保持中的信号表（频率 + 电平 + 脉冲剩余）—— **服务端存档与客户端同步包都写**（§7.16e）：
		// ① 存档：不写就等于"重进存档信号全丢"，而用户要的是持久变量；
		// ② 客户端包：护目镜要看得见"这条臂现在把哪几个频率按着"（读的是同步下来的数据）。
		// 客户端那份**不参与入网**（read 里只有 !clientPacket 才 rejoinNetwork）。
		writeSignalTable(tag, registries);
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
		// ⚠️ 客户端**只在"相位真的变了"时才接受服务端带来的 `Progress`**。
		//
		// 为什么：收回（IDLE）这段动画是**客户端自己推进**的 —— 两边都用
		// `chasedPointProgress += min(256,|speed|)/1024` 各自累加，而客户端的角度插值就拿它当系数。
		// 收回开始时服务端把进度置 0（`cancelMove()`）并同步一次 ✓ 那一次要收；
		// 但**收回过程中任何一次同步**（重试失败、目标重新解析、程序脏标记…）都会把客户端刚走到的
		// 进度**按回 0** ⇒ 插值系数从头再来 ⇒ 现象就是"收回动画突然变慢/回弹"
		// （用户第二十七轮续三十一："完成一次组装、再喂料时它正常拦截，但收回去的动画会变慢"）。
		// 按相位变化来收：MOVING→IDLE（开始收）与 IDLE→MOVING（开始伸）各收一次，中间一律听客户端的。
		if (!clientPacket || oldPhase != phase)
			chasedPointProgress = tag.getFloat("Progress");
		// 信号表：存档与客户端包都读（§7.16e）。**入网只在服务端**（下面 rejoinNetwork）——
		// 客户端读到的那份纯粹给护目镜看，不碰网络表（getNetworkOf 把 world 当 IdentityHashMap 的键）。
		readSignalTable(tag, registries);
		heldItem = ItemStack.parseOptional(registries, tag.getCompound("HeldItem"));
		overflow.clear();
		overflow.addAll(NBTHelper.readItemList(tag.getList("Overflow", Tag.TAG_COMPOUND), registries));

		if (!clientPacket) {
			ArmProgram loaded = ArmProgram.load(tag.getCompound("Program"), registries);
			program.tasks.clear();
			program.tasks.addAll(loaded.tasks);
			program.absolute = loaded.absolute;
			runtime.load(tag.getCompound("Runtime"));
			// ★ §7.16e：**加载时自动重新入网** —— 区块卸载时只退了网，字段与 NBT 都还在，
			// 这一步把"断电 / 脉冲"原样接回世界（否则重新加载的臂看起来"发了但没通电"）。
			rejoinNetwork();
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
