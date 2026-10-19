package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.SequencedArmConfig;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.AnyBlockInteractionPoint;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.RightClickBridge;
import io.github.Ling.create_sequenced_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_sequenced_mechanical_arm.mixin.ArmInteractionPointAccessor;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmEntryText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramView;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramRuntime;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;

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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
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
 * 可编程动力臂方块实体。
 *
 * <p>阶段 2 起，行为完全由 {@link ArmProgram 操作表} 驱动，
 * {@link ProgramRuntime 解释器} 每 tick 至多推进 N 条（N = 配置项 {@code entriesPerTick}，
 * 默认 2，只有"一瞬间出结果"的控制类条目会连推）；本类只负责
 * "运动学 + 目标解析 + 库存"这三件事，不做任何决策。
 *
 * <p>运动学数值对齐 Create 6.0.10 的 {@code ArmBlockEntity}：进度式移动
 * （{@code min(256,|speed|)/1024}，满速 4 tick）、姿态"先折回静止位再伸向目标"、
 * 客户端在 {@link #read} 里检测目标变化以快照上一姿态、转速 0 则冻结。
 *
 * <p>库存结构对齐机械手：<b>1 格手持 + 最多 10 格溢出</b>；满仓时仍可行动，
 * 捕获不到的物品正常掉落到世界（PLAN.md §7.4）。
 */
public class SequencedArmBlockEntity extends KineticBlockEntity implements TransformableBlockEntity {

	private static final Logger LOGGER = LogUtils.getLogger();

	public static final int MAX_OVERFLOW = 10;

	public enum Phase {
		IDLE, MOVING
	}

	// ——— 运动学状态（客户端插值；字段公开给渲染器） ———
	public float chasedPointProgress;
	public int chasedPointIndex = -1;
	public float previousBaseAngle;
	public SequencedArmAngleTarget previousTarget = SequencedArmAngleTarget.RESTING;
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
	 * 臂此刻是否处于「信号活动」（= 客户端该用 {@code base_powered} 而不是 {@code base} 底座的唯一判据）。
	 *
	 * <p><b>为什么是"存下来的字段"而不是客户端现算</b>：口径要读链接表（{@link #getArmLinkLevel()}）
	 * 与"刚收到信号"的脉冲计时器（{@link #signalFlashTicks}），而这两样在客户端<b>要么没有</b>、
	 * <b>要么只有自己发的那份</b>（客户端不入网）。所以由服务端每 tick 重算
	 * （{@link #tickSignalActive()}）、随既有同步包下发（{@code "SignalActive"} 键），
	 * 渲染器只读 {@link #isSignalActive()}。判据本身**不允许写第二份**（客户端没有那些数据，
	 * 写两份迟早两边不一致 —— 改口径只改 {@link #computeSignalActive()}）。
	 */
	private boolean signalActive;

	/**
	 * 「刚真的收到一路信号」的短脉冲长度（tick）。
	 *
	 * <p><b>为什么需要这个数</b>：用户口径（本轮）—— receiving 必须<b>真的收到</b>才点亮底座，
	 * "尝试接收但没收到"不亮。可是"收到"这件事在状态机里是<b>零时长</b>的：
	 * {@code ProgramRuntime.tickWait} 一旦发现极性吻合就在<b>同一个 tick</b> 里 {@code succeed}
	 * 把条目走掉，臂身上没有任何"我正在收"的持续状态可以拿来当判据。
	 * 于是用一个短脉冲把"这一下真收到了"变成<b>看得见</b>的东西。
	 *
	 * <p><b>为什么是 10 tick = 0.5 秒</b>：1 tick 的窗口人眼看不见（那正是当初误加"正在等也算"的原因，
	 * 见 {@link #computeSignalActive()}）；0.5 秒是"能看清、又不至于像常亮"的量。**要改手感只改这一处**。
	 */
	public static final int SIGNAL_FLASH_TICKS = 10;

	/**
	 * 短脉冲的剩余 tick 数（{@code > 0} = 刚收到信号，底座该亮）。
	 *
	 * <p>由 {@link #flashSignalReceived()} 置满、每 tick 递减（{@link #tickSignalActive()}）。
	 * <b>不存盘、也不单独同步</b>：客户端只认 {@link #isSignalActive()} 这个结果（不重复一份判据）。
	 */
	private int signalFlashTicks;

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
	private SequencedArmItemHandler itemHandler;

	public SequencedArmBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
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
		return SequencedArmBlock.isCeiling(getBlockState());
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
		// ⚠️ 这里曾经把卡上的 `origin`/`hasOrigin` 一起复制进臂内 —— 用户第四十轮删掉了这一步：
		// 原点现在住在**卡**上（`CardProgramStorage.readOrigin`），臂运行时**完全不用它**
		// （世界目标 = 臂位置 + offset，§19.7），复制进臂只会让"这个数是谁的"变得说不清。
		runtime.onProgramReplaced(this); // 任务数可能变了：槽位与频率缓存都要重来
	}

	public void markProgramDirty() {
		setChanged();
		sendData();
	}

	// ————————————————————————— 运行日志（环形缓冲，只在内存） —————————————————————————

	/**
	 * 导出到剪贴板时**一页写几行**。
	 *
	 * <p>8 不是随手取的：Create 的剪贴板界面**没有滚动**，一页能画的高度由
	 * {@code ClipboardScreen.validateTextForEntry} 卡在 185 px，而每条目占
	 * {@code max(12, 换行后行数 * 9 + 3)}（`ClipboardScreen:186-196/220-229`）。
	 * 我们的一行日志约 30〜45 个字符 ⇒ 在 150 px 宽度上会折成 2 行 ⇒ 21 px/条 ⇒ 185/21 ≈ 8.8。
	 * 取 8 是"最长的那些行也不会被挤出纸面"的档位。
	 */
	public static final int LOG_LINES_PER_PAGE = 8;
	/**
	 * 一次导出最多写几页：Create 的界面把页码 {@code clamp(..., 0, 50)}（`ClipboardScreen:245`），
	 * 所以第 50 页之后的条目玩家**永远翻不到** —— 写了也等于没写。
	 */
	public static final int LOG_MAX_PAGES = 50;

	/**
	 * 运行日志（环形缓冲）。
	 *
	 * <p><b>为什么存在方块实体上、而且不写 NBT</b>：它是"给玩家看刚才发生了什么"的诊断材料，
	 * 不是程序状态 —— 写进存档只会让区块 NBT 变大、让重进世界后出现一份"上次运行"的假现场。
	 * 换程序（{@code ProgramRuntime.resetToStart}）会清空它：日志永远只描述**当前这份程序**。
	 */
	private final Deque<String> logLines = new ArrayDeque<>();
	/**
	 * 上一条日志写在哪一 tick（{@link #logLine} 用它算 {@code [+Nt]}）；{@code Long.MIN_VALUE} = 缓冲刚清空。
	 *
	 * <p><b>为什么记 tick 差而不是绝对时间</b>：绝对 tick 数对玩家没有任何意义（也随存档/重进漂移），
	 * 而"距上一条过了多少 tick"正好回答用户要的那个问题 —— <i>"它到底卡了多久 / 节奏是快是慢"</i>。
	 */
	private long lastLogTick = Long.MIN_VALUE;
	/** 自检用：把容量钉成固定值（配置是运行期现读的，自检要的是确定值，与 {@code pinRateForTests} 同一手法）。 */
	@Nullable
	private static Integer logLinesOverride;

	/** 自检用：钉住日志容量；{@code null} = 恢复读配置。 */
	public static void pinLogLinesForTests(@Nullable Integer lines) {
		logLinesOverride = lines;
	}

	/** 当前生效的日志容量（0 = 关闭记录）。 */
	public static int logCapacity() {
		Integer pinned = logLinesOverride;
		return Mth.clamp(pinned != null ? pinned : SequencedArmConfig.logLines(), 0, SequencedArmConfig.MAX_LOG_LINES);
	}

	/**
	 * 现在要不要记日志（每个记录点的第一句）。
	 *
	 * <p>★ <b>先判它、再拼字符串</b>：拼串的分配代价不该付在一个被关掉的功能上
	 * （记录点全在 {@code ProgramRuntime} 的热路径里，正常运行时每秒会经过好几次）。
	 */
	public boolean logEnabled() {
		return logCapacity() > 0;
	}

	/**
	 * 记一行（自动加 {@code [+Nt]} 前缀 = 距上一条的 tick 差；缓冲的第一行是 {@code [+0t]}）。
	 *
	 * <p>满了丢最旧的（环形缓冲）—— 用户要的是"最近发生了什么"，不是完整历史。
	 */
	public void logLine(String text) {
		if (text == null)
			return;
		int capacity = logCapacity();
		if (capacity <= 0)
			return;
		long now = level == null ? 0L : level.getGameTime();
		long delta = lastLogTick == Long.MIN_VALUE ? 0L : Math.max(0L, now - lastLogTick);
		lastLogTick = now;
		// 容量可能在运行期被配置改小（配置是现读的）——写之前按当前容量腾位置，不依赖"上次清过"
		while (logLines.size() >= capacity)
			logLines.pollFirst();
		logLines.addLast("[+" + delta + "t] " + text);
	}

	/** 缓冲的一份快照（旧 → 新）。关闭记录时恒为空表。 */
	public List<String> logSnapshot() {
		int capacity = logCapacity();
		if (capacity <= 0) {
			logLines.clear(); // 关掉记录 ⇒ 旧内容也不该再被导出
			return List.of();
		}
		while (logLines.size() > capacity)
			logLines.pollFirst();
		return List.copyOf(logLines);
	}

	/** 清空（换程序 / 回到开头时调，见 {@code ProgramRuntime.resetToStart}）。 */
	public void clearLog() {
		logLines.clear();
		lastLogTick = Long.MIN_VALUE;
	}

	/** 导出失败的原因（给调用方映射成 lang 键，不给玩家看这些英文常量）。 */
	public enum LogProblem {
		/** 没有失败。 */
		NONE,
		/** 手上那个物品不是 Create 的剪贴板。 */
		NOT_CLIPBOARD,
		/** 只读剪贴板（1.20 老存档迁移过来的那种）。 */
		READ_ONLY,
		/** 缓冲里没有任何行（程序还没跑过，或配置 {@code logLines = 0} 关掉了记录）。 */
		EMPTY
	}

	/**
	 * 一次导出的结果。
	 *
	 * @param written 真正写进剪贴板的**日志行数**（不含头部那一行）
	 * @param dropped 剪贴板装不下、被丢掉的最旧行数
	 */
	public record LogExport(boolean ok, int written, int dropped, LogProblem problem) {}

	/**
	 * 把缓冲里的行**写进那块剪贴板**（公开静态 = 自检可以直接调，与 {@code CardEditPacket.addEntry} 同一手法）。
	 *
	 * <p><b>Create 剪贴板的多行怎么存</b>（全部照源码）：
	 * 一条 {@code ClipboardEntry} = 屏幕上的一行（`ClipboardEntry:53-57`：`(boolean checked, MutableComponent text)`），
	 * 一页 = {@code List<ClipboardEntry>}，整块 = {@code ClipboardContent(type, pages, readOnly, previouslyOpenedPage, copiedValues)}。
	 * 所以我们按 {@link #LOG_LINES_PER_PAGE} 分页、每行建一条**未勾选**的条目。
	 *
	 * <p>⚠️ 三条必须照做的细节：
	 * <ol>
	 *   <li><b>新建对象写回去，绝不就地改</b>：{@code AbstractContainerMenu.remoteSlots} 里那份副本与手上这份
	 *       共享同一个组件值对象，就地改会让"变了吗"的判定为否 ⇒ 客户端永远看不到新内容
	 *       （这条纪律出自 {@code ClipboardPlan.writeTicks} 的注释）；</li>
	 *   <li>只读剪贴板一个字都不动（返回值里带 {@link LogProblem#READ_ONLY} 让调用方说清楚）；</li>
	 *   <li>类型置 {@code WRITTEN}（不是 {@code EDITING}）：玩家再右键它就是"看清单"，不会一开界面就进编辑态。</li>
	 * </ol>
	 *
	 * @param lines 旧 → 新的日志行（{@link #logSnapshot()}）；**头部由本方法加**，调用方不用管
	 */
	public static LogExport exportLog(ItemStack clipboard, BlockPos armPos, List<String> lines) {
		if (!ClipboardPlan.isClipboard(clipboard))
			return new LogExport(false, 0, 0, LogProblem.NOT_CLIPBOARD);
		ClipboardContent old = clipboard.get(AllDataComponents.CLIPBOARD_CONTENT);
		if (old != null && old.readOnly())
			return new LogExport(false, 0, 0, LogProblem.READ_ONLY);
		if (lines == null || lines.isEmpty())
			return new LogExport(false, 0, 0, LogProblem.EMPTY);

		// 头部占一条 ⇒ 能装的日志行 = 总容量 - 1。只留**最后**（最新）那些行：导出的意思是
		// "刚才到底发生了什么"，最旧的行被挤掉比整份写不进去有用。
		int room = LOG_LINES_PER_PAGE * LOG_MAX_PAGES - 1;
		int dropped = Math.max(0, lines.size() - room);
		List<String> kept = dropped == 0 ? lines : lines.subList(dropped, lines.size());

		List<String> all = new ArrayList<>(kept.size() + 1);
		all.add(logHeader(armPos, lines.size(), dropped));
		all.addAll(kept);

		List<List<ClipboardEntry>> pages = new ArrayList<>();
		for (int i = 0; i < all.size(); i += LOG_LINES_PER_PAGE) {
			List<String> chunk = all.subList(i, Math.min(i + LOG_LINES_PER_PAGE, all.size()));
			List<ClipboardEntry> page = new ArrayList<>(chunk.size());
			for (String line : chunk)
				page.add(new ClipboardEntry(false, Component.literal(line)));
			pages.add(page);
		}
		clipboard.set(AllDataComponents.CLIPBOARD_CONTENT,
			new ClipboardContent(ClipboardType.WRITTEN, pages, false, 0, Optional.empty()));
		return new LogExport(true, kept.size(), dropped, LogProblem.NONE);
	}

	/** 剪贴板第一行：说明这是谁的日志、有没有被剪贴板容量截断、{@code [+Nt]} 是什么意思。 */
	private static String logHeader(BlockPos armPos, int total, int dropped) {
		return "运行日志 · 可编程动力臂 " + (armPos == null ? "?" : armPos.toShortString()) + " · 缓冲 " + total + " 行"
			+ (dropped > 0 ? "（剪贴板最多装 " + (LOG_LINES_PER_PAGE * LOG_MAX_PAGES - 1) + " 行，更早的 " + dropped
				+ " 行没带）" : "") + " · [+Nt] = 距上一条的 tick 差";
	}

	/**
	 * 玩家手势的落地：把这条臂的日志写进他手上那块剪贴板，并给一条 actionbar 反馈。
	 *
	 * <p>手势本身（手持剪贴板 + 潜行右键这块臂）在 {@link SequencedArmBlock} 的事件钩子里 ——
	 * 那里必须用事件是因为"潜行 + 手上有东西"会跳过方块交互，写在这里只是为了让"写剪贴板 + 报结果"
	 * 与缓冲挨在一起。
	 */
	public void exportLogToClipboard(Player player, ItemStack clipboard) {
		LogExport result = exportLog(clipboard, worldPosition, logSnapshot());
		Object[] args;
		String key;
		ChatFormatting color;
		if (result.ok()) {
			color = ChatFormatting.GREEN;
			if (result.dropped() > 0) {
				key = "log.exported_trimmed";
				args = new Object[] { result.written(), result.dropped() };
			} else {
				key = "log.exported";
				args = new Object[] { result.written() };
			}
		} else {
			color = ChatFormatting.YELLOW;
			key = switch (result.problem()) {
				case NOT_CLIPBOARD -> "log.not_clipboard";
				case READ_ONLY -> "log.read_only";
				default -> "log.empty";
			};
			args = new Object[0];
		}
		player.displayClientMessage(Component.translatable("create_sequenced_mechanical_arm." + key, args)
			.withStyle(color), true);
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
	 * 带交互面的版本：只有"对任意方块"的操作（攻击 / 使用，含潜行右键那个开关）才用得上它 ——
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
		// ★ **从 0 起手（整段）**：前半段是原版的"站在静止位转身"（臂角度不变，因为起点与终点都是静止位），
		//   后半段才是伸出 —— 于是"伸出开始时旋转已过半"（用户第二十七轮续三十八实测的原版观感）。
		//   中途起手（挖掘连挥 `restartMove`：手臂还伸在目标上）走的是同一段：先折回、再伸出。
		//   ⚠️ 之所以"从 0 起手"不会再出现"收回&旋转"：收回现在自成一段（`retract()` + 相位 IDLE，
		//   那段里 `currentPoint()` 是 null ⇒ 旋转冻结），而运行时靠 `isAtRest()` 闸门保证
		//   折返走完才开始这一段（见 {@code ProgramRuntime.beginEntry}）。⇒ 这半段一定是"原地转身"。
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
		if (pin && !level.isClientSide)
			point.keepAlive(); // 同一 tick 先锁一次（理由见上面那段注释）
		movingHandlerOwner = level.isClientSide ? null : level.getBlockEntity(target);
		movingHandler = level.isClientSide ? null : handlerAt(target);
		chasedPointProgress = 0f;
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

	/**
	 * 只读地解析<b>任何方块</b>那一格的物品能力（不过 Create 交互点那道门）——
	 * 「过滤」的外部容器模式要用它。
	 *
	 * <p><b>为什么不能用 {@link #peekHandler} / {@link #getMovingHandler}</b>：
	 * <ul>
	 *   <li>{@link #peekHandler} 要求目标必须是 Create 注册过的交互点，而**箱子 / 保险箱这类普通容器
	 *       不在那张表里**（{@code AllArmInteractionPointTypes} 只有置物台 / 带子 / 漏斗 …），
	 *       结果会把"能读的箱子"报成"目标不是容器"；</li>
	 *   <li>{@link #getMovingHandler} 认的是 {@code movingOffset}（"上一次真的动过身"的那一格）——
	 *       而**过滤是控制类操作、臂根本不动**，那个偏移永远停在别的条目上
	 *       （拿它读容器就是"读错方块"，而且是静默的）。</li>
	 * </ul>
	 * 所以这里按调用方给的**偏移**现场解析，读的能力入口与 {@link #handlerAt} 完全同一个
	 * （{@code Capabilities.ItemHandler.BLOCK}，面 {@code UP}）——不新增第二条读法。
	 */
	@Nullable
	public IItemHandler peekContainerHandler(BlockPos offset) {
		if (level == null || level.isClientSide)
			return null;
		return handlerAt(worldPosition.offset(offset));
	}

	public void cancelMove() {
		// 松手时也要把**客户端那一半**（带子物品的 `locked`）放开 —— 它不会被自动消费，
		// 忘了清就会在客户端留下一件永远不动的"幽灵物品"（服务端那边 `lockedExternally` 本来就是每 tick 重设的）。
		if (clientHoldItem != null || pinnedBeltItem != null)
			releaseBeltHold();
		retract();
	}

	/**
	 * **只收臂，不松手**：折回待机位（客户端靠相位变化做平滑插值），但目标上的物品继续按着。
	 *
	 * <p>为什么要有这么一个和 {@link #cancelMove()} 分开的方法：**收回是动作，认领是意图**。
	 * 每条条目结束后的收回（§7.22 的"每物品一次伸收"）不该顺手把目标上的物品放回传送带 ——
	 * 否则那一格在"收回 + 待机"期间无人认领，物品流走，下一件又要重新等
	 * （用户第二十七轮续三十五实测："又变成无法定住被加工的物品了"）。
	 * 认领的寿命归程序意图管：{@link #canPinTarget()} 为假、或目标换了，才真的松手。
	 *
	 * <p>真的该松手的场合（换卡 / 等待 / 停机 / 拆方块）走的仍然是 {@link #cancelMove()}。
	 */
	public void retract() {
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
		TransportedItemStack item = claimBeltItem();
		if (item == null)
			return;
		item.lockedExternally = true; // 每 tick 重设（会被带子消费）；locked 不会被消费，认领时设一次即可
	}

	/**
	 * 认领物品时允许的"离格子中心最大距离"。
	 *
	 * <p>两个条目类型的要求不同（用户第二十七轮续四十实测）：
	 * <ul>
	 *   <li><b>取物</b>：物品**一进入判定范围**就按住 —— 范围取带子自己的加工范围 {@code ±.51}
	 *       （`handleProcessingOnAllItems` 用的同一个值）。</li>
	 *   <li><b>组装</b>：要等它**走到格子中间**再按住 —— 容差至少一个 tick 的带速
	 *       （否则高速带上每 tick 的位移就跨过了容差窗口，永远追不上），慢带子则用 1/32 格（半个像素）。
	 *       16rpm 下带速 1/64 格/tick ⇒ 容差 1/32 ⇒ 物品停在离中心 ≤1/32 格处，肉眼就是"中间"。</li>
	 * </ul>
	 *
	 * <p>⚠️ **取物不动物品**（用户实测：<i>"它会捕获并定住物品，但是物品会立即瞬移到中间而不是留在原本的位置"</i>）——
	 * 取物停在原地、组装才归位，见 {@link #snapToWorkPosition}。
	 */
	public static float claimTolerance(boolean needsCentre, float beltStep) {
		return needsCentre ? Math.max(1 / 32f, Math.abs(beltStep)) : CLAIM_RANGE;
	}

	/** 判定范围：与带子自己的加工范围一致（`handleProcessingOnAllItems` ⇒ 中心 ±.51）。 */
	private static final float CLAIM_RANGE = .51f;

	/**
	 * **作业位**的精确坐标 —— 照抄本体把被 HOLD 的物品居中的那一行
	 * （`BeltInventory:292`：{@code beltPosition = segment + .5f ± 1/512f}）。
	 *
	 * <p>为什么必须有这一格：本体**只在物品跨过格子中心的那一 tick** 才询问机器
	 * （{@code BeltInventory:268-297}），并在那一刻把它**居中**。用户第二十七轮续四十一实测我们的偏差：
	 * <i>"组装的时候，物品还没有运动到正中间，动力臂就已经组装了"</i> —— 结果产物在半路生成、挡住后面那件。
	 */
	public static float centreSnapPosition(int beltIndex, boolean movementPositive) {
		return beltIndex + .5f + (movementPositive ? 1 / 512f : -1 / 512f);
	}

	/** 纯判据：某件物品是否已经在**作业位**上（离格子中心 < 容差）。所有"能不能动手"的判断都走它（决策 68）。 */
	public static boolean atWorkPosition(float beltPosition, float centre, float tolerance) {
		return Math.abs(beltPosition - centre) < tolerance;
	}

	/**
	 * 这件物品在**目标带**上是否已走到作业位。
	 *
	 * <p>三处共用（认领 / 前置检查 / 真正加工）：组装必须等它到这里才动手 ——
	 * 这也是"速度受传送带限制"的来源：产物没被送走时，后面那件到不了中心 ⇒ 我们收不到"有活干" ⇒ 自然降速。
	 */
	public boolean isAtWorkPosition(TransportedItemStack item) {
		BeltBlockEntity belt = beltBlock();
		if (belt == null)
			return false;
		return atWorkPosition(item.beltPosition, belt.index + .5f,
			claimTolerance(true, belt.getBeltMovementSpeed()));
	}

	/** 把物品搬到作业位（照抄本体的 HOLD 居中）。**只在"组装"认领时调用**，位移 ≤ 一个 tick 的带速。 */
	private void snapToWorkPosition(BeltBlockEntity belt, TransportedItemStack item) {
		float snapped = centreSnapPosition(belt.index, belt.getDirectionAwareBeltMovementSpeed() > 0);
		item.beltPosition = snapped;
		item.prevBeltPosition = snapped;
	}

	/**
	 * 认领要按住的那件物品：上次那件还在就继续用它，否则按**当前条目的位置条件**在目标格里挑一件
	 * **这条条目真的加工得了的**、离中心最近的。
	 *
	 * <p><b>为什么认领要过"加工得了"这一关</b>（用户第二十七轮续三十五）：认领是**长期**的
	 * （跨收回、跨待机，见 {@link #canPinTarget()}）。见谁按谁的话，刚加工出来的**产物**会被按在
	 * 原地 —— 它恰好不再匹配配方，于是"按着 → 等不到加工 → 一直按着"，整条带子被一件物品堵死。
	 * 判据交给运行时（{@code ProgramRuntime.canWorkOn}），它用的是**与前置检查同一批事实**。
	 */
	@Nullable
	private TransportedItemStack claimBeltItem() {
		BeltInventory inventory = beltInventory();
		BeltBlockEntity belt = beltBlock();
		if (inventory == null || belt == null) {
			releaseBeltHold();
			return null;
		}
		List<TransportedItemStack> items = inventory.getTransportedItems();
		boolean needsCentre = runtime.claimNeedsCentre(this);
		float centre = belt.index + .5f;
		float tolerance = claimTolerance(needsCentre, belt.getBeltMovementSpeed());
		// ① 保留：上一次认领的那件**还在带子上**就继续按着（它被我们按住、不会漂走）。
		TransportedItemStack item = pinnedBeltItem != null && items.contains(pinnedBeltItem)
			&& !pinnedBeltItem.stack.isEmpty() ? pinnedBeltItem : null;
		// ①b 组装：**已经不在作业位的那件不再保留** —— 否则会把一件我们永远不会加工的物品按死在那里
		//     （它的位置条件已经不满足，松手让带子把它送走，下一件走到中心再认领）。
		if (item != null && needsCentre && !atWorkPosition(item.beltPosition, centre, tolerance)) {
			releaseBeltHold();
			item = null;
		}
		// ② 认领：目标格里"这条条目加工得了"、且满足**位置条件**的一件。
		if (item == null) {
			float bestDistance = Float.MAX_VALUE;
			for (TransportedItemStack candidate : items) {
				if (candidate.stack.isEmpty())
					continue;
				float distance = Math.abs(candidate.beltPosition - centre);
				if (distance >= tolerance)
					continue; // 取物：整格都算"进入判定范围"；组装：只有走到中间才算
				if (!runtime.canWorkOn(this, candidate.stack))
					continue; // 产物 / 加工不了的东西：不按（否则会堵住带子）
				if (distance < bestDistance) {
					bestDistance = distance;
					item = candidate;
				}
			}
		}
		if (item == null) {
			releaseBeltHold();
			return null;
		}
		pinnedBeltItem = item;
		// ③ 按住它。`locked` 才是"按住"的那个标记（带子服务端 `BeltInventory:161-162` 与客户端
		//    `:119-121` 都会因此跳过移动，且都不会清掉它）；`lockedExternally` 每 tick 由
		//    `holdBeltItem()` 重设，挡住带子自己的清理。
		//    **组装**在按住的同时把它搬到作业位 —— 照抄本体 HOLD 的居中（`BeltInventory:292`），
		//    位移 ≤ 一个 tick 的带速（"它到了"），不是从格子边缘拽过来。**取物不搬**。
		if (item != clientHoldItem || !item.locked) {
			if (needsCentre)
				snapToWorkPosition(belt, item);
			item.locked = true;
			clientHoldItem = item;
			syncBeltController();
		}
		return item;
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

	/** 目标方块是传送带时返回它的（控制器的）库存。 */
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
		if (level == null)
			return null;
		BlockPos offset = workOffset();
		if (offset == null)
			return null;
		return level.getBlockEntity(worldPosition.offset(offset)) instanceof BeltBlockEntity belt ? belt : null;
	}

	/**
	 * 本 tick 关心的那一格（相对本臂）：**当前条目的目标优先**，退回"上一次真的动过身"的目标。
	 *
	 * <p>为什么不能只用 {@code movingOffset}：它是"上一次真的动过身"的目标，换目标时会指着一格旧位置 ——
	 * 认领（{@link #pinPoint()}）已经改成按当前条目算，若 {@link #beltBlock()} 还按旧位置，
	 * 就会去按**另一条带子**上的物品。两者必须同源。
	 */
	@Nullable
	private BlockPos workOffset() {
		BlockPos offset = runtime.entryOffset(this);
		return offset != null ? offset : movingOffset;
	}

	private void syncBeltController() {
		BeltBlockEntity belt = beltBlock();
		BeltBlockEntity controller = belt == null ? null : belt.getControllerBE();
		if (controller != null)
			controller.sendData();
	}

	/**
	 * 判定窗口被**短暂占住**大约会持续几 tick（按带速算：走完一整格需要多久）。
	 *
	 * <p>用途：前置检查失败时**不必立刻收臂** —— 最常见的短暂阻塞是"我们刚加工出来的产物还占着判定窗口"，
	 * 几 tick 它就走了。收回→再伸出去既费时又难看（用户第二十七轮续三十三："每次组装都会卡顿一会"）。
	 * 所以运行时拿这个值当**宽限期**：超过它还没等到，才折回待机（"空载具时原地待机"的语义不变）。
	 */
	public int transientBlockTicks() {
		BeltBlockEntity belt = beltBlock();
		if (belt == null)
			return TRANSIENT_BLOCK_DEFAULT_TICKS;
		float perTick = Math.abs(belt.getBeltMovementSpeed());
		if (perTick < 1e-4f)
			return TRANSIENT_BLOCK_DEFAULT_TICKS;
		return Mth.clamp((int) Math.ceil(1f / perTick) + 1, TRANSIENT_BLOCK_MIN_TICKS, TRANSIENT_BLOCK_MAX_TICKS);
	}

	/** 没有带速可算时的保守值（约半秒）。 */
	private static final int TRANSIENT_BLOCK_DEFAULT_TICKS = 10;
	private static final int TRANSIENT_BLOCK_MIN_TICKS = 2;
	private static final int TRANSIENT_BLOCK_MAX_TICKS = 40;

	/**
	 * 现在要不要**认领并按住**目标上的物品：运行时说"当前这条条目是从目标取东西 / 在目标上加工"
	 * （{@code TAKE} / {@code DEPLOY}）。
	 *
	 * <p>判据是**程序意图**，不是手臂状态：手臂在伸、在收、在待机、换目标前的等待期，
	 * 只要当前条目还是取物/组装，就继续按着那一格里的物品（§7.22 与"每物品一次伸收"配套的那一半）。
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

	private SequencedArmAngleTarget anglesFor(ArmInteractionPoint point) {
		// 各交互点类型自己的"取物位置 + 朝向"（protected，靠 Mixin 访问器拿）
		ArmInteractionPointAccessor access = (ArmInteractionPointAccessor) point;
		return new SequencedArmAngleTarget(worldPosition, access.create$getInteractionPositionVector(),
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
	 * 空手潜行右键取一组（{@link SequencedArmItemHandler#extractFromEnd}）。两处各写一份必然有一天不一致。
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
	public SequencedArmItemHandler itemHandler() {
		if (itemHandler == null)
			itemHandler = new SequencedArmItemHandler(this);
		return itemHandler;
	}

	/** 取前模拟用：臂内还接得下吗（与真插入同一份算法，见 {@link SequencedArmItemHandler#canAccept}）。 */
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
		// ⚠️ 这里原来还有一行「循环：%s / %s」（摘要里那两个循环计数字段）。用户把任务级循环
		// 整体删掉了（主任务恒为无限循环）⇒ 那个数不再有答案，行、字段与语言键一起删除。

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

		// ⚠️ 两行都是 Component（不是拼好的字符串）：客户端渲染时才解析翻译键 ⇒ 按玩家语言显示
		//   （见 ArmProgramView 的 failure / error 字段与 ProgramRuntime.FailText）。
		if (hud.failure != null)
			goggleLine().translate("gui.arm.fail_line", hud.failure)
				.style(ChatFormatting.YELLOW)
				.forGoggles(tooltip, 1);
		if (hud.error != null)
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
			goggleLine().translate("gui.arm.signals", heldSignalCount(), maxHeldSignals())
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
			if (armLinkRejectedLastTick > 0)
				goggleLine().translate("gui.arm.signal_rejected", maxHeldSignals())
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
		return new LangBuilder(Create_sequenced_mechanical_arm.MODID);
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

		// 取物/组装期间按住目标上的物品（上游 `ArmBlockEntity:133-140` 的同一机制）。分两半：
		//   ① 服务端：`keepAlive()`（`lockedExternally`）—— **必须每 tick 重设**，带子每 tick 会把它消费掉；
		//   ② 客户端：`locked` —— **只在状态变化时同步一次**（客户端不会消费它），这样就不会因为
		//      "包一抖、物品动一格又被纠正回来"而抽动（见 applyBeltClientHold 的注释）。
		//
		// ⚠️ 判据（`canPinTarget()`）是**程序意图**：当前条目是不是"取物 / 组装"。
		// 曾经写成 `chasedPointProgress < 1`（漏掉到位那一 tick），后来又写成 `MOVING + EXECUTING`
		// —— 后者在"每物品一次伸收"（§7.22）之后会出现整段"收回 + 待机"无人认领的窗口，
		// 物品从臂下直接流走（用户第二十七轮续三十五："又变成无法定住被加工的物品了"）。
		// 认领**不随手臂状态起落**：手臂在折返/在等下一件，仍然是"我要这一格里的物品"。
		// ⚠️ 判据**不能**用 `movingPoint != null`：读盘路径会把它显式清成 null（只留存盘的 `phase`/`Offset`），
		// 而"退出重进"正是走读盘这条路 ⇒ 用缓存当判据会在重登后**静默松手**（用户第二十七轮续二十六实测）。
		// 所以用 {@link #pinPoint()}：按**当前条目的目标**解析（那是程序里持久化的事实）。
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
		// 底座模型切换（信号活动）每 tick 一次：**先让"刚收到信号"的短脉冲倒计时走一格**，再重算口径。
		// 顺序很重要：ProgramRuntime 在**同一 tick** 里置上的脉冲（flashSignalReceived）会在这里被
		// 第一次递减并立刻参与重算 ⇒ "收到信号"与"底座亮起来"落在同一个 tick，窗口不会漏一帧。
		// 只在翻转时推包：这个字段只决定"画哪个底座"，不该每 tick 一个同步包；
		// 而脉冲**结束**那一下同样是一次翻转 ⇒ 灭灯也一定会发包（不会把客户端留在常亮态）。
		if (tickSignalActive())
			sendData();
	}

	/**
	 * 要认领的那个交互点：<b>按当前条目的目标算</b>（{@code ProgramRuntime.pinOffset}）。
	 *
	 * <p>为什么不用缓存、也不用 {@code movingOffset}：
	 * <ul>
	 *   <li>读盘会把 {@code movingPoint} 显式清成 null（只留 `phase`/`Offset`），而"退出重进"走的
	 *       就是读盘 ⇒ 只看缓存的写法会在重登后**静默松手**，把已经定住的物品放回传送带上
	 *       （用户第二十七轮续二十六实测）。</li>
	 *   <li>{@code movingOffset} 是"上一次真的动过身"的目标：换目标时它还是旧的那一格，
	 *       于是新目标的前置检查期间会继续按着**旧目标**的物品（本次顺带修掉）。</li>
	 * </ul>
	 * 当前条目的目标是**程序里持久化的事实**，与手臂状态无关，所以两个毛病一起没了。
	 */
	@Nullable
	public ArmInteractionPoint pinPoint() {
		BlockPos offset = runtime.pinOffset(this);
		return offset == null ? null : resolvePoint(offset);
	}

	/**
	 * 插值进度 → **旋转**因子（照抄上游 `ArmBlockEntity:208`）：**整段** 0→1。
	 *
	 * <p>与 {@link #extensionFactor} 的差是这一整套观感的关键：旋转**先走**，
	 * 因此伸出开始时旋转已经走完一半（用户第二十七轮续三十八实测：
	 * <i>"原版动力臂似乎是转到一半才开始伸出（视觉上看大约是在角平分线上）"</i>）。
	 */
	public static float rotationFactor(float progress) {
		return progress;
	}

	/**
	 * 插值进度 → **伸出/折回**（臂角度）因子（照抄上游 `ArmBlockEntity:216`）：**半段** 0→1。
	 *
	 * <p>前半段（{@code progress < .5}）臂角度从"上一目标"折回静止位，后半段从静止位伸向目标。
	 * 于是 {@code progress = .5} 是**伸出开始**的那一刻 —— 那一刻 {@link #rotationFactor} 正好是 .5。
	 */
	public static float extensionFactor(float progress) {
		return progress == 1 ? 1 : (progress % .5f) * 2;
	}

	/**
	 * 给定**移动进度**算这一次移动该摆成什么姿态 —— **纯函数**（不碰 chaser、不读世界）。
	 * 渲染与自检用的是同一份实现（决策 68/94：判据不许写两份）。
	 *
	 * <p>顺序照抄上游 `ArmBlockEntity:203-220`，**顺序本身就是正确性的一部分**：
	 * ① 先按**真正的目标**算旋转（`aimed`，整段因子）；
	 * ② 然后 `target` 才会被"前半段折回/停在静止位"覆盖（半段因子用在臂角度上）。
	 *
	 * <p>⚠️ 我上一版把旋转那行挪到了②之后 ⇒ 前半段里两个端点都成了 `previousBaseAngle`
	 * ⇒ **旋转被冻结**，到 `progress = .5` 才一步跳到一半。用户第二十七轮续三十九实测：
	 * <i>"动画的旋转缺失了一部分……原本的旋转，前面一半没有了，然后突然瞬移到后面一半。"</i>
	 *
	 * @param progress          移动进度 0→1
	 * @param previousTarget    起点姿态（客户端在开始新移动时快照的"手臂现在在哪"）
	 * @param aimed             这一次真正的目标姿态；{@code null} = 没有目标（收回 / 等待）⇒ 旋转冻结
	 * @param previousBaseAngle 起点的旋转角
	 */
	public static SequencedArmAngleTarget poseAt(float progress, SequencedArmAngleTarget previousTarget,
		@Nullable SequencedArmAngleTarget aimed, float previousBaseAngle) {
		// ① 旋转：**整段**因子，必须在这里算（下面 target 会被覆盖）
		float base = AngleHelper.angleLerp(rotationFactor(progress), previousBaseAngle,
			aimed == null ? previousBaseAngle : aimed.baseAngle);

		// ② 臂角度：前半段折回静止位、后半段从静止位伸向目标（半段因子）
		SequencedArmAngleTarget target = aimed == null ? SequencedArmAngleTarget.RESTING : aimed;
		SequencedArmAngleTarget from = previousTarget;
		if (progress < .5f)
			target = SequencedArmAngleTarget.RESTING;
		else
			from = SequencedArmAngleTarget.RESTING;
		float extension = extensionFactor(progress);

		return SequencedArmAngleTarget.of(base, Mth.lerp(extension, from.lowerArmAngle, target.lowerArmAngle),
			Mth.lerp(extension, from.upperArmAngle, target.upperArmAngle),
			AngleHelper.angleLerp(extension, from.headAngle % 360, target.headAngle % 360));
	}

	/** 推进移动进度；客户端顺带做角度插值（照抄 ArmBlockEntity.tickMovementProgress 的语义）。 */
	private void tickMovementProgress() {
		chasedPointProgress += Math.min(256, Math.abs(getSpeed())) / 1024f;
		if (chasedPointProgress > 1)
			chasedPointProgress = 1;
		if (level == null || !level.isClientSide)
			return;

		ArmInteractionPoint targeted = currentPoint();
		SequencedArmAngleTarget pose = poseAt(chasedPointProgress, previousTarget,
			targeted == null ? null : anglesFor(targeted), previousBaseAngle);
		baseAngle.setValue(pose.baseAngle);
		lowerArmAngle.setValue(pose.lowerArmAngle);
		upperArmAngle.setValue(pose.upperArmAngle);
		headAngle.setValue(pose.headAngle);
	}

	/**
	 * 手臂现在是不是**停在静止位、随时可以开始一次新的伸出**。
	 *
	 * <p>判据：相位是待机（没有目标）、且折返已经走到静止位（进度过半点 `.5`）。
	 * "从没动过"（{@code moveSeq == 0}）也算静止位 —— 刚放下/刚读盘的臂就在静止位。
	 *
	 * <p><b>用途</b>：运行时靠它决定"能不能动身"—— 一次完整的操作是
	 * <b>旋转+伸出 → 动作 → 收回</b>，**收回属于这一次**，不是下一次动作的开头
	 * （见 {@code ProgramRuntime.beginEntry} 与 §7.25）。
	 */
	public boolean isAtRest() {
		if (phase != Phase.IDLE || movingPoint != null)
			return false;
		return chasedPointProgress >= .5f || moveSeq == 0;
	}

	// ————————————————————————— 红石 —————————————————————————

	public void redstoneUpdate() {
		if (level == null || level.isClientSide)
			return;
		boolean blockPowered = level.hasNeighborSignal(worldPosition);
		if (blockPowered == redstoneLocked)
			return;
		redstoneLocked = blockPowered;
		// ⚠️ 「信号活动」口径**不再**含 redstoneLocked（用户要求：通电即暂停不是"收到信号"），
		// 所以这里重算其实不会因红石翻转；仍然算一次是为了让同一个同步包里的两个字段永远一致
		// （例如恰好在这一刻"发出去的脉冲到点"了），并保持"红石翻转 ⇒ 一包发完"的老纪律。
		refreshSignalActive();
		sendData();
	}

	public boolean isRedstoneLocked() {
		return redstoneLocked;
	}

	public boolean hasNeighborSignal() {
		return level != null && level.hasNeighborSignal(worldPosition);
	}

	// ————————————————————————— 信号活动（底座模型切换） —————————————————————————

	/**
	 * 臂此刻是否处于**信号活动** —— 渲染器据此把底座从 {@code base} 换成 {@code base_powered}。
	 *
	 * <p><b>为什么需要它</b>：用户新做了 {@code base_powered.json}（{@code base.json} 的信号态变体：
	 * 底座本体一样，红石天线换成点亮的 {@code redstone_antenna_powered}），要求「当臂发出信号或者接收信号
	 * 的时候，使用这个模型替代原本的 {@code base.json}，没有接收或发出信号的时候再用 {@code base.json}」。
	 * 所以这条判据只回答一件事：<b>臂现在是不是正在跟红石信号打交道</b>。
	 *
	 * <p><b>口径 = 下面两条任一为真</b>（用户第十九轮口径，本轮按反馈收紧）：
	 * <ol>
	 *   <li><b>发</b>：{@link #getArmLinkLevel()} {@code > 0} —— 此刻真的有一路链接被我们维持在电平 &gt; 0。
	 *       <b>「曾经发过」不算</b>：脉冲到点后电平归 0、条目却按设计留在表里
	 *       （见 {@link #tickArmLink()} 的注释），那种情况这里必须是 {@code false}；</li>
	 *   <li><b>收</b>：{@link #signalFlashTicks} {@code > 0} —— <b>刚刚真的收到</b>了一路<b>无线</b>信号
	 *       （「收发信号」的**接收**模式等到那一路红石链接频率通电），由 {@code ProgramRuntime.tickWait}
	 *       在判定成功那一刻调 {@link #flashSignalReceived()} 置上的短脉冲。
	 *       ⚠️ <b>只认无线信号</b>（用户口径：「这个只与无线信号有关，与接收红石信号无关」）——
	 *       相邻方块的红石电（`hasNeighborSignal()` / {@code redstoneLocked}）与发光无关，
	 *       它只表示"通电即暂停"。曾经有一条"等相邻红石"的操作，已按用户口径整体删除；
	 *       现在 {@code tickWait} 里那层 `op == SIGNAL && isSignalReceive()` 仍然守着同一条区分。</li>
	 * </ol>
	 *
	 * <p><b>本轮被推翻的两条旧判据（用户原话：「接收信号发光的时候<b>必须成功接收</b>信号才发光，
	 * 尝试接收但是没有就不会发光」）</b>：
	 * <ul>
	 *   <li>~~程序正停在「等待」上~~ —— 那只是"在听"，<b>没收到就不该亮</b>。
	 *       当初加上它是因为怕"真收到"的窗口是零时长、收信号那一侧永远不亮；现在由上面的短脉冲解决，
	 *       这个替代品必须删掉（它会把"一直在等、一直没信号"画成常亮，正是用户这次反馈的现象）；</li>
	 *   <li>~~{@link #redstoneLocked}（相邻红石把臂点亮）~~ —— 那只是"通电即暂停"，不是"收到信号"，
	 *       按用户口径也算"没收到"。</li>
	 * </ul>
	 *
	 * <p>⚠️ 只认<b>当前</b>活动：等完了继续跑、或者只是曾经发过一轮，都不算。
	 */
	public boolean isSignalActive() {
		return signalActive;
	}

	/** 短脉冲剩余 tick 数（{@code 0} = 没在闪）。护目镜与自检读它，判据本身仍然只有 {@link #isSignalActive()}。 */
	public int signalFlashTicks() {
		return signalFlashTicks;
	}

	/**
	 * <b>真的收到一路无线信号</b>的那一刻由 {@code ProgramRuntime} 调用（{@code tickWait} 里极性判定的成功分支）：
	 * 给底座一个看得见的短脉冲（{@link #SIGNAL_FLASH_TICKS}）。
	 *
	 * <p>⚠️ <b>调用方只有一处、且带判据</b>：{@code tickWait} 里那一句写成
	 * {@code if (entry.op == OperationType.SIGNAL && entry.isSignalReceive())} ——
	 * 用户口径「这个只与无线信号有关，与接收红石信号无关」：只有"经红石链接频率收到的信号"算数
	 * （相邻方块的红石电不算；等它的那条操作已删除）。所以别在别处"补一次"调用，
	 * 那会把红石电也算成发光。
	 *
	 * <p>⚠️ 这里**不推包**：{@link #signalActive} 的翻转检测统一在 {@link #tickSignalActive()} 里做
	 * （{@code tick()} 每 tick 调它一次），这样"收到即亮"在任何 tick 内都只会有一处口径翻转、一次同步 ——
	 * 判定与同步各只有一处，不会出现"这里发了包、那里又翻了一次"。
	 */
	public void flashSignalReceived() {
		signalFlashTicks = SIGNAL_FLASH_TICKS;
	}

	/**
	 * 每 tick 推进一次「信号活动」：先让短脉冲倒计时走一格，再重算口径并检测翻转。
	 *
	 * <p><b>为什么"翻转才推包"由调用方决定</b>：这个值只影响"画哪个底座"，每 tick 都 {@code sendData()}
	 * 会把同步包刷成每 tick 一个；而它变化的每一种原因（电平改、脉冲到点、刚收到信号的短脉冲开始与结束）
	 * 都已经在各自的路径上发过包，这里只补"口径真的翻了、但上面那些包没带上"的那一次。
	 *
	 * <p>抽成方法而不是塞在 {@code tick()} 里，是因为自检<b>不跑运动学</b>（{@code arm.tick()} 要真实动力网络，
	 * 见 {@code checkOverflowStackAndExchange} 的同样处理），需要拿这个方法来驱动"每 tick 走一格"。
	 *
	 * @return 口径是否翻转（{@code true} = 调用方该推一次同步包）
	 */
	public boolean tickSignalActive() {
		if (signalFlashTicks > 0)
			signalFlashTicks--;
		return refreshSignalActive();
	}

	/**
	 * {@link #isSignalActive()} 的口径本体。**只在服务端调用**（要读链接表与脉冲计时器）。
	 */
	private boolean computeSignalActive() {
		// ① 发：此刻真的有电平 > 0 的链接（"曾经发过、现在归 0"不算）
		if (getArmLinkLevel() > 0)
			return true;
		// ② 收：**刚刚真的收到**（用户要求：必须成功接收才发光，尝试接收不算）
		return signalFlashTicks > 0;
	}

	/**
	 * 重算口径并记到 {@link #signalActive}。
	 *
	 * <p><b>为什么"翻转才推包"由调用方决定</b>：这个值只影响"画哪个底座"，每 tick 都 {@code sendData()}
	 * 会把同步包刷成每 tick 一个；而它变化的每一种原因（电平改、发出去的脉冲到点、
	 * {@link #flashSignalReceived()} 置上的短脉冲开始与结束）都已经在各自的路径上发过包，
	 * 这里只补"口径真的翻了、但上面那些包没带上"的那一次。
	 *
	 * @return 口径是否翻转（{@code true} = 调用方该推一次同步包）
	 */
	private boolean refreshSignalActive() {
		boolean active = computeSignalActive();
		if (active == signalActive)
			return false;
		signalActive = active;
		return true;
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
	 * <p>值来自配置项 {@code maxHeldSignals}（默认 {@link SequencedArmConfig#DEFAULT_MAX_HELD_SIGNALS}）；
	 * ⚠️ 运行期读出来的数，**不是**编译期常量 —— 别拿它当数组长度/注解参数。
	 * 配置未加载时回落默认值（见 {@link SequencedArmConfig#maxHeldSignals()}）。
	 */
	public static int maxHeldSignals() {
		return SequencedArmConfig.maxHeldSignals();
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
			return level.getBlockEntity(worldPosition) == SequencedArmBlockEntity.this;
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
	 * <p>相对偏移之外的情况这里不用管：P1「坐标模型统一」之后条目坐标只有一种语义
	 * （**相对执行它的那条臂**，§19.7；卡上的录制原点只服务录制与渲染、且<b>不在臂内这份程序里</b>，
	 * 用户第四十轮已把它从模型搬到卡上）。
	 */
	@Override
	public void transform(BlockEntity be, StructureTransform transform) {
		boolean changed = false;
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				// ★ 问**条目级**的 needsTarget()：过滤的容器模式也指着一个真实的方块，
				//   蓝图旋转/镜像时它的坐标必须一起转 —— 漏了它就指错方向（手持过滤没有坐标，跳过）。
				if (!entry.needsTarget())
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
		// 「信号活动」（底座模型切换，见 isSignalActive）：派生值，但客户端算不出来（没有程序本体与链接网络表），
		// 所以服务端算好后**跟着既有同步包**一起发；不另造网络包。翻转时由 tickSignalActive 触发 sendData。
		tag.putBoolean("SignalActive", signalActive);
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
			// ★ PLAN.md §17：盘上**只存文本**。编译结果（program 字段）只活在内存里，
			// 写盘这一刻现生成一次脚本文本 —— 不缓存是为了"存下来的永远就是现在跑的那份"：
			// `/sma` 命令、编辑器菜单、蓝图 `transform` 都能在 `setProgram` 之后改模型，
			// 缓存文本迟早会与程序对不上（那种不一致只在重进世界时才现形）。
			tag.putString("ProgramText", programTextForSave(registries));
			tag.put("Runtime", runtime.save(registries));
		} else {
			// 客户端拿不到程序本体（最多 256 条，且每次条目切换都会同步，塞进去会刷爆包），
			// 所以只发护目镜需要的摘要（含**当前这一条**的完整 NBT —— 见 ArmProgramView.entry）。
			// 完整程序的同步留给阶段 3c 的编辑器 Menu。
			tag.put("Hud", ArmProgramView.capture(program, runtime, registries)
				.save(registries));
		}
	}

	/**
	 * 落盘用的脚本文本（PLAN.md §17）：内存里只有编译结果，**写盘这一刻**生成一次。
	 *
	 * <p>空串 = "这条臂没有程序"（新臂，或程序被换掉还没插新的）。
	 *
	 * <p>两种失败都**宁可不写**，也不写一份对不上的东西：
	 * <ul>
	 *   <li>反编译不出来（{@link ProgramText#toText} 已 WARN）——写个空串，别让重进世界后跑的是另一份程序；</li>
	 *   <li>文本超过 {@link ProgramText#MAX_BYTES} —— 这个必须挡住：NBT 字符串是 <b>16 位长度</b>，
	 *       硬塞进去会在<b>区块存盘时抛异常</b>（不是"存得下但很大"）。真正能进臂的程序在插卡那一刻已被限住，
	 *       这条是给 `setProgram` 这类入口兜底的（§17.4）。</li>
	 * </ul>
	 */
	private String programTextForSave(HolderLookup.Provider registries) {
		if (program.isEmpty())
			return "";
		String text = ProgramText.toText(program, registries);
		if (text == null) {
			LOGGER.error("{} 上的程序无法转成脚本文本，本次不写入存档（重进世界后这条臂会没有程序）", worldPosition);
			return "";
		}
		if (!ProgramText.withinLimit(text)) {
			LOGGER.error("{} 上的程序转成脚本后有 {} 字节，超过上限 {}（NBT 字符串是 16 位长度）——本次不写入存档",
				worldPosition, ProgramText.utf8Length(text), ProgramText.MAX_BYTES);
			return "";
		}
		return text;
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);

		BlockPos oldOffset = movingOffset;
		Phase oldPhase = phase;
		int oldMoveSeq = moveSeq;

		redstoneLocked = tag.getBoolean("Powered");
		// 客户端只读这一份（服务端下一 tick 会按真实状态重算，存档里那个值只是顺带写的）
		signalActive = tag.getBoolean("SignalActive");
		goggles = tag.getBoolean("Goggles");
		phase = Phase.values()[Mth.clamp(tag.getInt("Phase2"), 0, Phase.values().length - 1)];
		movingOffset = NBTHelper.readBlockPos(tag, "Offset");
		movingFace = tag.contains("Face") ? Direction.byName(tag.getString("Face")) : null;
		moveSeq = tag.getInt("MoveSeq");
		// ⚠️ 客户端接受服务端 `Progress` 的判据：**相位变了 或 开了新一次移动（`moveSeq` 变了）**。
		//
		// 为什么（两轮才收敛，两个方向都踩过）：
		//   ① **必须收**：`beginMove` 把进度置 0 并 `moveSeq++` —— 连续任务时相位**一直是 MOVING**
		//      （中间不经过 IDLE），所以只看相位的话客户端进度会永远停在 1.00 ⇒ **动画直接闪现、不插值**
		//      （用户第二十七轮续三十二实测：潜行看护目镜 `progress` 恒为 1.00）。这就是 `moveSeq` 这一项的用处。
		//   ② **不能乱收**：收回（IDLE）是**客户端自己推进**的动画，`chasedPointProgress` 就是它的插值系数；
		//      收回期间任何一次同步（重试失败、目标重解析、脏标记…）若把进度按回服务端的 0，
		//      观感就是"收回变慢/回弹一下"（§7.19）。而收回期间**没有新的 `beginMove`** ⇒
		//      相位与 `moveSeq` 都不变 ⇒ 这些同步**不会**再打断它 ✓。
		if (!clientPacket || oldPhase != phase || oldMoveSeq != moveSeq)
			chasedPointProgress = tag.getFloat("Progress");
		// 信号表：存档与客户端包都读（§7.16e）。**入网只在服务端**（下面 rejoinNetwork）——
		// 客户端读到的那份纯粹给护目镜看，不碰网络表（getNetworkOf 把 world 当 IdentityHashMap 的键）。
		readSignalTable(tag, registries);
		heldItem = ItemStack.parseOptional(registries, tag.getCompound("HeldItem"));
		overflow.clear();
		overflow.addAll(NBTHelper.readItemList(tag.getList("Overflow", Tag.TAG_COMPOUND), registries));

		if (!clientPacket) {
			// ★ PLAN.md §17：盘上只读文本，**载入时编译**（编译一次，不是每 tick）。
			// `read` 是 `SmartBlockEntity.loadAdditional(CompoundTag, HolderLookup.Provider)` 转调进来的
			// ⇒ 这里拿得到 `registries`，编译所需的注册表齐全（`ScriptParser` 解析物品要用它）。
			String text = tag.getString("ProgramText");
			if (text.isEmpty() && tag.contains("Program", Tag.TAG_COMPOUND)) {
				// 旧存档（0.1.0 之前存的是编译结果）：用户定的"不兼容"——不当程序用，但要说一声，
				// 不能让一条臂"程序凭空没了"却毫无痕迹（§17.3）。
				LOGGER.warn("{} 上的操作表是旧格式（编译结果），本版本起不再读取：程序视为空（PLAN.md §17.3）", worldPosition);
			}
			ArmProgram loaded = ProgramText.fromText(text, registries);
			program.tasks.clear();
			// ⚠️ 这里原来有一句 `program.origin = worldPosition`（"臂内的原点就是臂自己"）。
			// 用户第四十轮把原点搬到了卡上 ⇒ 臂内那份程序里根本没有原点这个字段，
			// 也不该凭空造一个：臂运行时用的是"臂位置 + 偏移"（§19.7），与原点无关。
			if (loaded != null) {
				program.tasks.addAll(loaded.tasks);
				runtime.load(tag.getCompound("Runtime"), registries);
			} else {
				// 没有程序 / 文本编译不过：运行时状态必须跟着回到起点。
				// 只复位 runtime（空标签 = 下标 0、IDLE、空调用栈）而**不**调 onProgramReplaced ——
				// 后者会 clearTransmissions()，而信号表刚在上面读进来，那会把持久变量误删（§7.16e）。
				runtime.load(new CompoundTag(), registries);
			}
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
			previousTarget = SequencedArmAngleTarget.of(baseAngle.getValue(), lowerArmAngle.getValue(),
				upperArmAngle.getValue(), headAngle.getValue());
			previousBaseAngle = previousTarget.baseAngle;
		}
	}

}
