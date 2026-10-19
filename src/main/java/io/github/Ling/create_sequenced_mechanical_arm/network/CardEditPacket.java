package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.menu.ArmProgramMenu;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmEntryText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.ClipboardChecklist;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

/**
 * 世界内录入手势的 C2S 意图包（PLAN.md §5.1）。
 *
 * <p><b>只传意图，不传程序本体</b>：客户端说"给主手那张卡加一条/删一条"，
 * 由服务端读卡、校验、落盘，再把权威副本同步回客户端。
 *
 * <p>为什么不做客户端乐观写入：那样一旦服务端因规模上限拒收，客户端与服务端就会不一致，
 * 还得再补一个"回滚"通道。现在客户端只负责派发意图与画轮廓，读的是同步回来的卡。
 */
public record CardEditPacket(Action action, BlockPos pos, Direction face, OperationType op) implements CustomPacketPayload {

	/**
	 * {@code ADD_ENTRY}/{@code REMOVE_AT} 是世界内录入手势；{@code OPEN_EDITOR} 是"打开时刻表式编辑器"
	 * （PLAN.md §5.1 的编辑模式）。后者的 {@code pos/face/op} 无意义，服务端只用"主手是不是卡"做校验
	 * —— 编辑的是玩家自己的卡，客户端声明的模式只是 UX 约定，不需要服务端复现。
	 */
	public enum Action {
		ADD_ENTRY, REMOVE_AT, OPEN_EDITOR,
		/** 认不出来的 action（坏包/版本不一致）：什么都不做。见 {@link #readAction}。 */
		NOOP
	}

	public static final CustomPacketPayload.Type<CardEditPacket> TYPE =
		new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "card_edit"));

	public static final StreamCodec<FriendlyByteBuf, CardEditPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> { // 注意参数顺序：StreamEncoder 是 (buf, value)
			buf.writeUtf(packet.action()
				.name());
			buf.writeBlockPos(packet.pos());
			buf.writeByte(packet.face()
				.get3DDataValue());
			buf.writeUtf(packet.op()
				.name());
		},
		buf -> new CardEditPacket(CardEditPacket.readAction(buf.readUtf()), buf.readBlockPos(),
			Direction.from3DDataValue(buf.readByte()), OperationType.byName(buf.readUtf(), OperationType.TAKE)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static Action readAction(String name) {
		for (Action action : Action.values())
			if (action.name()
				.equals(name))
				return action;
		// 认不出来的 action 落到 **NO-OP**，不要落回 ADD_ENTRY：ADD_ENTRY 会**改卡**，
		// 于是一个字段被改坏的包（或换了版本的客户端）就能凭空往玩家卡里插一条操作。
		// 解析失败应当是"什么都不做"，而不是"猜一个会写数据的动作"。
		return Action.NOOP;
	}

	// ————————————————————————— 服务端处理 —————————————————————————

	public static void handle(CardEditPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			ItemStack card = player.getMainHandItem();
			if (!OperationTableItem.isTable(card))
				return;

			HolderLookup.Provider registries = player.level()
				.registryAccess();
			ArmProgram program = CardProgramStorage.read(card, registries);

			switch (packet.action()) {
				case ADD_ENTRY -> addEntry(player, card, program, registries, packet);
				case REMOVE_AT -> removeAt(player, card, program, registries, packet.pos());
				case OPEN_EDITOR -> openEditor(player, card);
				case NOOP -> {
					// 认不出来的动作：什么都不做（无声拒绝，不碰卡）
				}
			}
		});
	}

	/** 打开时刻表式编辑器：菜单绑在手上这张卡上（PLAN.md §5.2）。 */
	private static void openEditor(ServerPlayer player, ItemStack card) {
		player.openMenu(
			new SimpleMenuProvider((id, inventory, p) -> new ArmProgramMenu(id, inventory, card),
				Component.translatable("create_sequenced_mechanical_arm.gui.editor.title")),
			// 与服务端同一份卡片物品：客户端菜单对象就是从这上面读程序的
			buf -> ItemStack.STREAM_CODEC.encode(buf, card));
	}

	/**
	 * 录一条到"正在编辑的那个任务"，并把副手清单的**下一行**写成它的备注（PLAN.md §5.5）。
	 *
	 * <p>抽成 public static 是为了让**启动自检**能直接跑这条路径（与 {@code ArmProgramEditPacket.applyTo}
	 * 同一个理由）：这里同时动"卡上的程序"与"副手剪贴板的勾"两样东西，错了在游戏里都是静默的。
	 */
	public static void addEntry(ServerPlayer player, ItemStack card, ArmProgram program,
		HolderLookup.Provider registries, CardEditPacket packet) {
		// ★ **没有原点就不能录制**（PLAN.md §19.7 的用户口径）：操作里写的坐标全是**相对坐标**，
		// 而相对谁由原点决定 —— 没设原点时"减去原点"连该减什么都不知道。所以这里**拒绝**，
		// 并把"怎么设原点"说在提示里（中键，见 OperationTableHandler 的选取方块分支）。
		// 用户第四十轮起原点住在**卡上**（`CardProgramStorage.readOrigin`，null = 没设过）——
		// 它不再跟着程序走，所以这里必须**现读卡**，不能从 `program` 里拿。
		// ⚠️ 拒绝必须在**建任务之前**：否则玩家先被塞一个空任务，再看到"没有原点"，
		// 那张卡的状态已经被这次失败的录制改过了。
		BlockPos origin = CardProgramStorage.readOrigin(card);
		if (origin == null) {
			feedback(player, "card.need_origin", ChatFormatting.RED);
			return;
		}
		// 没有任务就先建一个默认任务：录完就能直接插进臂里跑
		// （主任务**恒为无限循环**是模型语义，不再需要写任何循环字段）
		if (program.tasks.isEmpty()) {
			ArmProgramTask created = new ArmProgramTask();
			created.name = "主任务";
			program.tasks.add(created);
		}
		// §5.1 v5：录进"正在编辑的那个任务"（不再是永远录到最后一条）——
		// 用户："录入模式只会给正在编辑的任务录入操作。"
		int index = Math.max(0, Math.min(program.editorTask, program.tasks.size() - 1));
		program.editorTask = index;
		ArmProgramTask task = program.tasks.get(index);

		// 走 fresh(op)：个数与模式都取该操作的默认值（**取出默认「等于 1 个」**；字段初始值是
		// "小于等于"，直接 new 会得到与口径不符的一条，见 ArmProgramEntry.fresh）。
		ArmProgramEntry entry = ArmProgramEntry.fresh(packet.op());
		// ★ 条目坐标**一律相对执行它的那条臂**（PLAN.md §19.7），所以录制时用**卡上的原点**把世界位置
		// 换算成偏移：`offset = 世界位置 − origin`。注意 origin 在这里是**纯录制参考点**，
		// 它不参与运行时（运行时是 `臂位置 + offset`）。
		// 世界位置回显也按 `origin + offset` 算 —— 界面上给玩家看的必须是那个位置。
		//
		// ⚠️ 卡上的原点不会因为"插进过某条臂"而改变（插卡不做任何平移了，§19.7）：它只由
		//    "中键设原点"改（CardUiPacket 的 setOrigin 那一支）。要改基准就再中键一次。
		BlockPos world = packet.pos();
		entry.offset = world.subtract(origin);
		entry.face = packet.face();
		// ★ 副手剪贴板 = 待办清单（用户第二十七轮）：录一条就把"下一个还没做的行"写成备注。
		// 判据与 HUD 上那句"当前：xxx"共用 ClipboardChecklist.consumed 一处 ——
		// 各写一份必然出现"备注写的是第 3 行、清单说当前在第 4 行"。
		// 副手不是剪贴板（或清单已录完）时是空串 ⇒ 行为与以前完全一样。
		List<String> plan = ClipboardPlan.lines(player.getOffhandItem());
		entry.note = ClipboardChecklist.nextNote(plan, task);
		task.entries.add(entry);

		if (!program.isWithinLimits()) {
			task.entries.remove(task.entries.size() - 1);
			feedback(player, "card.over_limit", ChatFormatting.RED);
			return;
		}
		CardProgramStorage.write(card, program, registries);
		// 程序变了 ⇒ 清单的勾跟着变（服务端写、随下面的 broadcastChanges 一起同步给客户端）
		ClipboardPlan.syncTicks(player, program);
		player.inventoryMenu.broadcastChanges();
		// 提示文本：本地化操作名 + 世界坐标 + "第几条 / 哪个任务"（序号与卡片上的序号一致）
		// ⚠️ 操作名走 {@code ArmEntryText.opName}（**唯一**那处 `opt.<小写名>` 的拼法，带 Locale.ROOT）：
		//   这里原来自己拼了一遍 `op.name().toLowerCase()` —— 默认 locale 是土耳其语时 `WAIT → waıt`，
		//   键就命不中、界面上显示原始 key。
		feedback(player, "card.recorded", ChatFormatting.GREEN, ArmEntryText.opName(packet.op()),
			world.toShortString(),
			task.entries.size(), index + 1, task.name);
	}

	/**
	 * 左键 = <b>撤销一条</b>（用户第三轮反馈：<i>"由于我们的一个方块可以绑定多个操作，
	 * 左键取消所有操作是不合适的，应该改为左键取消最后添加的操作"</i>）。
	 *
	 * <p>只在**当前任务**里找：录入是录进当前任务的（界面上也一直显示是哪个任务），
	 * 撤销却跨任务去删别的任务里的东西会非常意外。从列表末尾往前找第一条指向该方块的坐标类条目，
	 * 只删它一条 —— 新条目都是 append 到末尾的，所以"末尾那一条"就是"最后添加的那一条"。
	 *
	 * <p>抽成 public static 与 {@link #addEntry} 同一个理由：自检要能把"撤销"这条路径真跑一遍
	 * （它同时要还原清单上的勾）。
	 */
	public static void removeAt(ServerPlayer player, ItemStack card, ArmProgram program,
		HolderLookup.Provider registries, BlockPos pos) {
		if (program.tasks.isEmpty())
			return;
		int index = Math.max(0, Math.min(program.editorTask, program.tasks.size() - 1));
		ArmProgramTask task = program.tasks.get(index);
		// 撤销按**世界位置**匹配（条目里存的是"相对卡上原点的偏移"，见 §19.4 风险那条）。
		// 原点没设（`readOrigin == null`）时退回 (0,0,0)：这种卡只能来自"导入一份没有原点的脚本"
		// （录制被 `card.need_origin` 拒了），此时 offset 就是裸坐标 —— 与用户第四十轮之前
		// "没设原点 = 原点在 (0,0,0)"的行为一致，不能因为搬了家就悄悄少掉一条撤销路径。
		BlockPos recorded = CardProgramStorage.readOrigin(card);
		BlockPos base = recorded == null ? BlockPos.ZERO : recorded;
		for (int i = task.entries.size() - 1; i >= 0; i--) {
			ArmProgramEntry entry = task.entries.get(i);
			// 直接拿世界坐标去比 `entry.offset` 在原点非零时就一条都删不掉（§19.4）。
			// （`原点 + offset` 在"录制参考系"里仍然成立 —— 撤销与录制用的是同一个参考点。）
			// ★ 问**条目级**的 needsTarget()：过滤的容器模式能被"对着那个方块撤销"删掉
			//   （手持过滤没有坐标，(0,0,0) 不参与匹配）。
			if (!entry.needsTarget() || !base.offset(entry.offset)
				.equals(pos))
				continue;
			task.entries.remove(i);
			program.editorTask = index;
			CardProgramStorage.write(card, program, registries);
			// 撤销一条 ⇒ 清单上那一行的勾要还回去（用户选的方案 A：勾由项目管）
			ClipboardPlan.syncTicks(player, program);
			player.inventoryMenu.broadcastChanges();
			feedback(player, "card.removed", ChatFormatting.YELLOW, ArmEntryText.opName(entry.op),
				index + 1, task.name, i + 1);
			return;
		}
	}

	private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
