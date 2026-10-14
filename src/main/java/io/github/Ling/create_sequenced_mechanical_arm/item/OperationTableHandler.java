package io.github.Ling.create_sequenced_mechanical_arm.item;

import com.simibubi.create.AllKeys;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.client.ClipboardChecklistOverlay;
import io.github.Ling.create_sequenced_mechanical_arm.client.OperationSelectionOverlay;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardEditPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardNotesPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardUiPacket;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.ClipboardChecklist;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmBlock;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 操作表的世界内录入手势（PLAN.md §5.1）。<b>纯客户端</b>，形态照抄 Create 的
 * {@code ArmInteractionPointHandler}（那边是零 GUI 录入的原版先例）：
 *
 * <ul>
 *   <li>手持操作表 → 进入会话；<b>换卡</b>（换成别的物品）才重建会话</li>
 *   <li><b>Alt + 滚轮</b> → 唯一的滚轮手势：录入模式切当前操作（界面见 {@link OperationSelectionOverlay}），
 *       编辑模式切"正在编辑的任务"</li>
 *   <li><b>右键方块</b> → 录入一条（记的是<b>世界坐标 + 点击面</b>，进模型时按程序原点换算成偏移），
 *       录进"当前任务"</li>
 *   <li><b>潜行右键</b> → 主手卡 + 副手写好字的剪贴板 = 按序号写入备注；否则在"录入模式 / 编辑模式"之间切换</li>
 *   <li><b>左键方块</b> → 撤销该方块上**最后录入的那一条**（一个方块可以绑多条操作）</li>
 *   <li>录过的目标实时画 AABB 轮廓</li>
 * </ul>
 *
 * <h2>⚠️ 为什么拦的是"键位事件"而不是 {@code RightClickBlock}</h2>
 * 用户实测（第二轮）：<i>"手持操作表的时候，在录入模式应当阻断与任何方块的交互，因为我们要录入操作。"</i>
 * 而旧的写法拦不住 —— 它在 {@code PlayerInteractEvent.RightClickBlock} 上 {@code setCanceled(true)}，
 * 那只改了**客户端的预测结果**：{@code MultiPlayerGameMode.useItemOn} 里
 * {@code startPrediction(...)} 无论预测成不成功都会把 {@code ServerboundUseItemOnPacket} 发出去
 * （{@code MultiPlayerGameMode:300-312}），于是服务端照样执行方块交互 —— 拿着操作表右键箱子，
 * 箱子真的会开。我们的处理器又只挂在客户端（{@code Dist.CLIENT}），服务端那一侧没人拦。
 *
 * <p>正确的拦截点是更早的 {@link InputEvent.InteractionKeyMappingTriggered}：{@code Minecraft.startUseItem}
 * / {@code startAttack} / {@code continueAttack} 都在**发任何包之前**检查它（{@code Minecraft:1639/1677/1718}），
 * 取消掉就什么都不会发生。所以本类把"攻击 / 使用 / 选取方块"三个键整体接管，自己的录入/删除/切模式
 * 逻辑也顺便搬到这里（读 {@code mc.hitResult}）—— 反正下游事件已经不会再被触发了。
 *
 * <p>唯一的放行口是<b>右键智能臂本身</b>（装载卡片），那一条必须留给方块自己。
 *
 * <p>客户端<b>不</b>直接改卡：只发 {@link CardEditPacket} 意图，服务端校验落盘后把权威副本同步回来。
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, value = Dist.CLIENT)
public class OperationTableHandler {

	/**
	 * 删除包的节流间隔（tick）。按住左键时 {@code Minecraft.continueAttack} 每 tick 都会发一次
	 * 键位事件 —— 不节流就是 20 包/秒（服务端虽然幂等、删不到东西就静默返回，但没必要刷）。
	 */
	private static final int REMOVE_INTERVAL = 5;

	public enum Mode {
		RECORDING, EDITING
	}

	private static Mode mode = Mode.RECORDING;
	/** 正在录入/编辑的任务下标（0 基），与卡上的 {@code etask} 同步。 */
	private static int currentTask;
	/** 当前任务名；{@code null} = 卡上一条任务都还没有。 */
	private static String currentTaskName;
	private static ItemStack tracked = ItemStack.EMPTY;
	/** 客户端 tick 计数 + 上次发删除包的时刻（见 {@link #REMOVE_INTERVAL}）。 */
	private static int tickCounter;
	private static int lastRemoveTick = -REMOVE_INTERVAL;
	/** 已发出、还没在卡上看到的会话状态（见 {@link #PUSH_TIMEOUT_TICKS}）。 */
	private static boolean pushPending;
	private static Mode pushedMode = Mode.RECORDING;
	private static int pushedTask;
	private static int pushTick;
	/** 回包在这个 tick 数内没到就当丢了，重新信任卡（别把客户端卡在一个过期的意图上）。 */
	private static final int PUSH_TIMEOUT_TICKS = 40;

	// ————————————————————————— 每 tick：状态与轮廓 —————————————————————————

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		tickCounter++;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (player == null || level == null) {
			tracked = ItemStack.EMPTY;
			OperationSelectionOverlay.INSTANCE.tick(false); // 否则 HUD 会永远留在屏幕上
			ClipboardChecklistOverlay.INSTANCE.hide();
			return;
		}
		ItemStack card = player.getMainHandItem();
		if (!OperationTableItem.isTable(card)) {
			tracked = ItemStack.EMPTY;
			OperationSelectionOverlay.INSTANCE.tick(false);
			ClipboardChecklistOverlay.INSTANCE.hide();
			return;
		}
		// 会话状态**以卡为准**（服务端才是权威）：每 tick 读一次，卡一变就跟着变。
		// ★ 用户实测的 bug 就出在"只在换物品时读一次"：第一次录入时是**服务端**建出的默认任务，
		// 客户端那份缓存还停在"没有任务"，于是切模式仍然优先提示"没有任务"，直到开过一次面板才对上。
		// 读程序本来就是每 tick 都要做的事（画轮廓），顺手比对，零额外开销。
		ArmProgram program = CardProgramStorage.read(card, level.registryAccess());
		Mode cardMode = CardUiPacket.EDIT.equals(program.editorMode) ? Mode.EDITING : Mode.RECORDING;
		int cardTask = program.tasks.isEmpty() ? -1
			: Math.max(0, Math.min(program.editorTask, program.tasks.size() - 1));
		String cardName = cardTask < 0 ? null : program.task(cardTask).name;

		boolean newCard = tracked.isEmpty() || tracked.getItem() != card.getItem();
		if (newCard)
			pushPending = false; // 换卡了：那张卡的 NBT 才是权威，之前发出的请求作废

		// 收到服务端回包（卡上已经是我们要的值）才算落地；超时就当回包丢了，重新信任卡
		if (pushPending && cardMode == pushedMode && cardTask == pushedTask)
			pushPending = false;
		else if (pushPending && tickCounter - pushTick > PUSH_TIMEOUT_TICKS)
			pushPending = false;
		// 在等回包期间**不要**用卡上的旧值覆盖玩家刚做出的选择 —— 否则 Ctrl+滚轮连滚两下的第二下
		// 会按旧模式解释（本该换任务却换了操作类型），甚至把模式顶回录入。任务名是纯显示信息，照旧跟卡走。
		boolean trustCard = !pushPending;

		if (newCard || (trustCard && (mode != cardMode || currentTask != cardTask))) {
			tracked = card.copy();
			if (trustCard) {
				mode = cardMode;
				currentTask = cardTask;
			}
			currentTaskName = cardName;
			// 只有**换卡**才主动报状态行：玩家自己切模式 / 换任务是那两个动作自己报的，
			// 而"服务端刚建出默认任务"这种被动变化不报 —— 否则又要盖掉刚弹出的"已录入"（上一轮反馈）。
			if (newCard)
				status(player);
		} else if (!Objects.equals(currentTaskName, cardName)) {
			currentTaskName = cardName;
		}
		// 选择条 HUD：只在**录入模式**显示（编辑模式不绑 Alt，画出来只会挡视线）。
		// 由这里驱动而不是让 HUD 每帧去读卡：每 tick 一次的状态推送，省掉每帧一次 NBT 解析。
		OperationSelectionOverlay.INSTANCE.tick(mode == Mode.RECORDING);
		pushChecklist(player, program);
		drawOutlines(level, program);
	}

	/**
	 * 副手清单 HUD（用户第二十七轮）：只在**录入模式**、且副手拿着一份非空剪贴板时显示。
	 *
	 * <p>进度**现算**而不是存一份对应关系（{@link ClipboardChecklist#consumed}）——
	 * 录一条、撤销一条、在编辑器里删一条，三种改法都会立刻反映到清单上，
	 * 不需要每一条修改路径都记得同步一份映射表。
	 *
	 * <p>清单看的是<b>正在录入的那个任务</b>的条目备注（与"左键撤销"、"画轮廓"同一个任务口径）。
	 */
	private static void pushChecklist(LocalPlayer player, ArmProgram program) {
		if (mode != Mode.RECORDING) {
			ClipboardChecklistOverlay.INSTANCE.hide();
			return;
		}
		List<String> plan = ClipboardPlan.lines(player.getOffhandItem());
		ArmProgramTask task = currentTask < 0 ? null : program.task(currentTask);
		ClipboardChecklistOverlay.INSTANCE.tick(!plan.isEmpty(), plan,
			ClipboardChecklist.consumed(plan, task));
	}

	/**
	 * 给"当前正在录入的那个任务"里带坐标的条目画轮廓。
	 *
	 * <p>范围与"左键撤销"严格一致（用户第三轮："切换任务之后只显示与当前录入的任务有关的操作的边框"）：
	 * 画出来的就是能被改的，别的任务既不画也不动 —— 否则会出现"删掉了看不见的东西"或者
	 * "看得见却删不掉"。
	 *
	 * <p><b>方块被挖掉了也照画</b>（用户："这样玩家破坏方块了也可以知道在那个地方是有操作的"）：
	 * 没有形状（空气 / 被破坏）时退化成整方块大小的框，并换成<b>红色</b> —— 红色 = 那里的方块已经没了，
	 * 金色 = 目标还在。未加载的区块直接跳过：那种情况下判不出"方块还在不在"，满世界画红框只会误导。
	 *
	 * <p>P1「坐标模型统一」（§19）：条目里存的是<b>相对原点的偏移</b>，所以世界位置要按
	 * {@code program.origin.offset(entry.offset)} 算；同时"只画绝对条目"这道过滤也删了
	 * —— 新模型里没有"绝对条目"这回事，所有带坐标的条目都该看得见（P2 才做原点的蓝框）。
	 */
	private static void drawOutlines(Level level, ArmProgram program) {
		ArmProgramTask task = currentTask < 0 ? null : program.task(currentTask);
		if (task == null)
			return;
		for (ArmProgramEntry entry : task.entries) {
			if (!entry.op.requiresTarget())
				continue;
			BlockPos pos = program.origin.offset(entry.offset);
			if (!level.hasChunkAt(pos))
				continue;
			BlockState state = level.getBlockState(pos);
			VoxelShape shape = state.getShape(level, pos);
			boolean missing = shape.isEmpty();
			AABB box = missing ? new AABB(pos) : shape.bounds()
				.move(pos);
			// key 用 BlockPos（稳定值类型）：读卡每次都会新建条目对象，
			// 拿对象当 key 会让 Outliner 每 tick 累积一份，越画越多。
			Outliner.getInstance()
				.showAABB(pos, box)
				.colored(missing ? 0xE06C6C : 0xDDC166)
				.lineWidth(1 / 16f);
		}
	}

	// ————————————————————————— 输入（唯一的入口） —————————————————————————

	/**
	 * 攻击 / 使用 / 选取方块三个键的总入口（见类注释：必须在这一层拦，下游拦不住）。
	 *
	 * <p>取消事件时手仍然会挥（{@code handSwing} 默认为 true），所以"右键→录入了"的手感不变。
	 */
	@SubscribeEvent
	public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.screen != null)
			return;
		// 只接管主手：副手拿着操作表时不该影响主手的东西（原版 use 键两个手各发一次事件）
		if (event.getHand() != InteractionHand.MAIN_HAND)
			return;
		ItemStack card = player.getMainHandItem();
		if (!OperationTableItem.isTable(card))
			return;

		HitResult hit = mc.hitResult;
		// ⚠️ 指空气时 {@code mc.hitResult} **仍然是 BlockHitResult**：原版 {@code BlockGetter.clip} 在没打中时
		// 返回 {@code BlockHitResult.miss(射线终点, 方向, BlockPos.containing(终点))} —— 位置是**空气方块**。
		// 所以"是不是方块"必须看 {@code getType() == BLOCK}，只判 {@code instanceof} 会把空气坐标录进卡里
		// （用户实测："对着空气右键也会记录坐标"）。
		BlockHitResult blockHit = hit instanceof BlockHitResult b ? b : null;
		boolean realBlock = blockHit != null && blockHit.getType() == HitResult.Type.BLOCK;
		BlockPos pos = blockHit == null ? null : blockHit.getBlockPos();
		Direction face = blockHit == null ? Direction.UP : blockHit.getDirection();
		boolean sneak = player.isShiftKeyDown();

		// 右键智能臂本身 = 装载卡片：这条必须放行（否则卡片永远插不进臂里）。
		// 只对"使用"键放行，而且必须是**真的指着那块方块**。
		if (event.isUseItem() && realBlock
			&& mc.level.getBlockState(pos)
				.getBlock()
				instanceof SequencedArmBlock)
			return;

		// 选取方块（中键）会把手上这张卡拉走换掉 —— 手持操作表时直接吞掉
		if (event.isPickBlock()) {
			event.setCanceled(true);
			return;
		}

		if (event.isAttack()) {
			// 编辑模式不吃左键：那边要把左键留给"正常挖方块 / 点界面"
			if (mode != Mode.RECORDING)
				return;
			// ★ 吃掉这一击：挖方块、打实体、发破坏包全在取消之后不再发生
			event.setCanceled(true);
			if (pos == null) {
				feedback(player, "card.need_block_remove", ChatFormatting.YELLOW);
				return;
			}
			// 左键 = 撤销指向该位置的条目。**这里故意也接受"空气位置"**：
			// 方块被挖掉之后那条操作还在，指回原位置按一下正好把它清掉（撤销只按坐标匹配，不需要方块存在）。
			// （要**录**攻击操作，用 Alt+滚轮 选「攻击」再右键。）
			if (tickCounter - lastRemoveTick >= REMOVE_INTERVAL) {
				lastRemoveTick = tickCounter;
				PacketDistributor
					.sendToServer(new CardEditPacket(CardEditPacket.Action.REMOVE_AT, pos, face, currentOp()));
			}
			return;
		}

		if (!event.isUseItem())
			return;

		// —— 使用键 ——
		// ① 副手拿着**写了字的剪贴板** = 按序号写入备注（用户第三轮要求）。这条优先于"潜行右键切模式"：
		//    那是玩家明确要做的数据操作；剪贴板空着就照旧切模式，免得把切模式堵死。
		List<String> notes = clipboardNotes(player);
		if (sneak && !notes.isEmpty()) {
			event.setCanceled(true);
			PacketDistributor.sendToServer(new CardNotesPacket(notes));
			return;
		}
		if (sneak) {
			event.setCanceled(true);
			toggleMode(player);
			return;
		}
		if (mode == Mode.EDITING) {
			// 编辑模式：右键任意方块 → 打开时刻表式编辑器（PLAN.md §5.1）
			event.setCanceled(true);
			openEditor();
			return;
		}
		event.setCanceled(true);
		if (pos == null || (!realBlock && !allowsAirTarget())) {
			// pos == null 只会发生在"指着实体"时（EntityHitResult）—— 那不是我们能录的目标。
			// 其余情况：录入必须真的指着一个方块，除了 allowsAirTarget 那两种操作。
			// 指空气时原版也会给一个"空气坐标"（BlockHitResult.miss），照它录进去对大多数操作就是一条
			// 永远找不到目标的操作（用户第十七轮实测过）。
			feedback(player, "card.need_block", ChatFormatting.YELLOW, opName());
			return;
		}
		record(player, pos, face);
	}

	/**
	 * 「使用 / 潜行使用」可以对着**空气**录入（用户第二十二轮："使用也不一定目的地必须有方块，
	 * 使用也可以是放置方块"）：把方块放进那一格，或者用掉手上的物品（`Item#use`）。
	 *
	 * <p>其它操作不行：取出/放入/卸货/组装要跟方块交换物品，攻击对着空气没有意义 ——
	 * 它们指空气时只提示"要对着方块"。
	 */
	private static boolean allowsAirTarget() {
		return currentOp() == OperationType.USE || currentOp() == OperationType.USE_SNEAK;
	}

	/**
	 * 录一条到"当前任务"。
	 *
	 * <p><b>只有</b>需要跟方块交换物品的四类操作才要求目标是 Create 交互点；攻击 / 使用 / 潜行使用
	 * 对**任意方块**都能试（用户第二轮反馈："对任意方块都可以尝试而不需要是 create 交互方块"）。
	 * 这个判定与运行时用的是同一个 {@link OperationType#needsInteractionPoint()}，免得两处各写一份。
	 */
	private static void record(LocalPlayer player, BlockPos pos, Direction face) {
		Level level = player.level();
		if (currentOp().needsInteractionPoint()
			&& !ArmInteractionPoint.isInteractable(level, pos, level.getBlockState(pos))) {
			feedback(player, "card.not_interactable", ChatFormatting.RED);
			return;
		}
		// 记的是**世界坐标**（客户端只报位置与面，服务端按卡上程序的原点换算成偏移，§19.2）；
		// 原点的整体平移（"以这条臂为基准"）发生在插卡进臂那一刻。
		PacketDistributor.sendToServer(new CardEditPacket(CardEditPacket.Action.ADD_ENTRY, pos, face, currentOp()));
	}

	/**
	 * 副手剪贴板上的文本行（用户第三轮要求："在剪贴板上写下若干行的文本，放在副手，
	 * 主手持有操作卡潜行右键会自动按序号添加对应行的备注"）。
	 *
	 * <p>实现搬去了 {@link ClipboardPlan#lines} —— <b>服务端也要读同一份行</b>
	 * （录一条时自动把下一行写成备注，见 §5.5），两边各写一份迟早会读得不一样。
	 */
	private static List<String> clipboardNotes(LocalPlayer player) {
		return ClipboardPlan.lines(player.getOffhandItem());
	}

	@SubscribeEvent
	public static void onScroll(InputEvent.MouseScrollingEvent event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.screen != null)
			return;
		ItemStack card = player.getMainHandItem();
		if (!OperationTableItem.isTable(card))
			return;
		double delta = event.getScrollDeltaY();
		if (delta == 0)
			return;
		// ★ Alt+滚轮 = 唯一的滚轮手势（照 Create 部署蓝图：那边整个"当前动作"就是一个 Alt 键）。
		// 按模式分工（用户第三轮："让切换任务也用 alt+滚轮而不是 ctrl+滚轮"）：
		//   录入模式 → 切当前操作（选择条 HUD 高亮的那一项）
		//   编辑模式 → 切正在编辑的任务
		// 切换时不再发文字提示（录入侧由选择条表达；任务侧由状态行报一句）。
		if (AllKeys.altDown()) {
			if (mode == Mode.EDITING)
				taskScroll(player, delta > 0 ? 1 : -1);
			else
				OperationSelectionOverlay.INSTANCE.cycle(delta > 0 ? 1 : -1);
			event.setCanceled(true); // 拦住快捷键栏滚动
		}
	}

	/** 打开编辑器：只发意图，由服务端把菜单开在"主手那张卡"上。 */
	private static void openEditor() {
		PacketDistributor.sendToServer(
			new CardEditPacket(CardEditPacket.Action.OPEN_EDITOR, BlockPos.ZERO, Direction.UP, currentOp()));
	}

	// ————————————————————————— 会话状态 —————————————————————————

	/** 切模式：改内存 + <b>写回卡</b>（否则换个物品就丢了，见用户反馈）。 */
	private static void toggleMode(LocalPlayer player) {
		mode = mode == Mode.RECORDING ? Mode.EDITING : Mode.RECORDING;
		pushSession();
		status(player);
	}

	/** Alt+滚轮换任务（编辑模式）：同样立刻写回卡，界面打开时会读到同一个下标。 */
	private static void taskScroll(LocalPlayer player, int step) {
		ArmProgram program = CardProgramStorage.read(player.getMainHandItem(), player.level()
			.registryAccess());
		int count = program.tasks.size();
		if (count <= 0) {
			feedback(player, "card.no_task", ChatFormatting.YELLOW);
			return;
		}
		currentTask = Math.floorMod(currentTask + step, count);
		ArmProgramTask task = program.task(currentTask);
		currentTaskName = task == null ? "" : task.name;
		pushSession();
		feedback(player, "card.task_switched", ChatFormatting.AQUA, currentTask + 1, currentTaskName);
	}

	/** 会话状态写回卡：服务端改 NBT 后把权威副本广播回来，与录制走同一条路径。 */
	private static void pushSession() {
		// 记下"我们想要的值"：在服务端回包之前，卡上还是旧值，不该拿它覆盖玩家的选择
		pushPending = true;
		pushedMode = mode;
		pushedTask = currentTask;
		pushTick = tickCounter;
		PacketDistributor.sendToServer(
			new CardUiPacket(mode == Mode.EDITING ? CardUiPacket.EDIT : CardUiPacket.RECORD, currentTask));
	}

	/** 状态行：模式 + 正在录入/编辑哪个任务（卡上没有任务时单独一句）。青色 = 当前会话状态。 */
	private static void status(LocalPlayer player) {
		if (currentTaskName == null) {
			feedback(player, "card.status.empty", ChatFormatting.AQUA);
			return;
		}
		if (mode == Mode.EDITING)
			feedback(player, "card.status.edit", ChatFormatting.AQUA, currentTask + 1, currentTaskName);
		else
			feedback(player, "card.status.record", ChatFormatting.AQUA, currentTask + 1, currentTaskName, opName());
	}

	// ————————————————————————— 辅助 —————————————————————————

	/** 当前操作：状态存在选择条里（它是"选哪个操作"的宿主），这里只是取用。 */
	private static OperationType currentOp() {
		return OperationSelectionOverlay.INSTANCE.selected();
	}

	/** 操作类型的本地化名字（提示文案用；与编辑器里的选项同一批 lang key）。 */
	private static Component opName() {
		return Component.translatable(
			"create_sequenced_mechanical_arm.gui.editor.opt." + currentOp().name()
				.toLowerCase(Locale.ROOT));
	}

	private static void feedback(LocalPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
