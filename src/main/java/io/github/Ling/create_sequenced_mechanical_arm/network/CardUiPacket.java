package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * 录入会话状态（模式 / 正在编辑哪个任务）**以及"设原点"**的 C2S 意图包（PLAN.md §5.1、§19.3）。
 *
 * <p>为什么需要它：模式与"当前任务"必须是<b>卡上的状态</b>而不是客户端内存里的开关 —— 用户实测
 * "切换物品之后就回到录入模式"。客户端只发意图，服务端读了卡、改这两个字段、再广播权威副本回来
 * （与录制同一条路径）。
 *
 * <p>⚠️ 它们**不再**存在卡里的程序 NBT 上（那是 §17 之前的做法）：§17 把程序的落盘形式换成了
 * `.sma` 文本，而文本里没有"玩家怎么看"的位置（见 §17.9 的回归记录）。现在它们与文本**并排**
 * 存在卡上（`CardProgramStorage` 的 `ArmProgramMode` / `ArmProgramTaskIndex`），走的是同一套
 * 服务端权威写入 —— 这里的两行注释描述的机制没变，只是宿主换了。
 *
 * <p>★ <b>{@code setOrigin} 那一支</b>（§19.3 的中键）：原点也是"卡上的状态"，同样只能由服务端
 * 权威写。客户端只报坐标，服务端写卡（{@link CardProgramStorage#writeOrigin}）—— 用户第四十轮起
 * 它<b>不在程序里</b>（见 {@link CardProgramStorage} 的 {@code ArmProgramOrigin} 键注释）。
 * 现有调用方（模式 / 任务切换）一律传 {@code setOrigin=false}，行为一个字都没变。
 *
 * <p>服务端不校验"这个玩家该不该有编辑模式"：编辑的是他自己手上的卡，模式只是 UX 约定。
 */
public record CardUiPacket(String mode, int task, boolean setOrigin, @Nullable BlockPos origin)
	implements CustomPacketPayload {

	// 线上取值只有一处定义（`ArmProgram` 的模型常量）—— 这里只是别名，免得两边各写一个 "EDIT"
	public static final String RECORD = ArmProgram.MODE_RECORD;
	public static final String EDIT = ArmProgram.MODE_EDIT;

	public static final CustomPacketPayload.Type<CardUiPacket> TYPE =
		new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "card_ui"));

	/**
	 * 只改模式 / 当前任务（{@code setOrigin=false}）—— 现有两条调用路径用的就是这个。
	 *
	 * <p>留这个便捷构造是为了"调用方不用写两个没意义的参数"：`origin` 在
	 * {@code setOrigin=false} 时**根本不参与编码**，写什么都不影响语义。
	 */
	public CardUiPacket(String mode, int task) {
		this(mode, task, false, null);
	}

	public static final StreamCodec<FriendlyByteBuf, CardUiPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> {
			buf.writeUtf(packet.mode());
			buf.writeVarInt(packet.task());
			buf.writeBoolean(packet.setOrigin());
			// 坐标只在真的设原点时才写：不设时多写 12 字节毫无意义，而且"线上有没有这个字段"
			// 与语义严格对应（读的一侧照同一个判据读，两边不会错位）。
			if (packet.setOrigin()) {
				// ★ 还有一个"有没有"位：设原点与**清原点**是两个不同的意图，而 (0,0,0) 是
				// "原点设在世界原点"这个**合法状态**，不能拿它当"清空"（见 writeOrigin 的说明）。
				BlockPos origin = packet.origin();
				buf.writeBoolean(origin != null);
				if (origin != null)
					buf.writeBlockPos(origin);
			}
		},
		buf -> {
			String mode = buf.readUtf();
			int task = buf.readVarInt();
			boolean setOrigin = buf.readBoolean();
			BlockPos origin = null;
			if (setOrigin && buf.readBoolean())
				origin = buf.readBlockPos();
			return new CardUiPacket(mode, task, setOrigin, origin);
		});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(CardUiPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			ItemStack card = player.getMainHandItem();
			if (!OperationTableItem.isTable(card))
				return;
			HolderLookup.Provider registries = player.level()
				.registryAccess();
			ArmProgram program = CardProgramStorage.read(card, registries);
			if (packet.setOrigin()) {
				// ★ 中键设原点（§19.3）：只改参考点，**不动任何条目的 offset**。
				// 为什么不平移条目：offset 的语义是"相对执行它的那条臂"，卡上的 origin 只是
				// 录制 / 渲染的参考系 ⇒ 重设参考点不该让任何目标在世界里挪位置（§19.2 第 4 条）。
				applyOrigin(player, card, program, registries, packet.origin());
				return;
			}
			program.editorMode = EDIT.equals(packet.mode()) ? EDIT : RECORD;
			// 两头都要夹：只夹下界的话，一个越界（或负数溢出成正数）的 int 会**原样写进卡的 NBT**，
			// 读回来虽然会被 `ArmProgram.load` 再夹一次，但中间那段时间里谁按它取下标谁越界。
			// 上界用卡上实际的任务数（此时 program 已经读出来了）。
			program.editorTask = Mth.clamp(packet.task(), 0, Math.max(0, program.tasks.size() - 1));
			CardProgramStorage.write(card, program, registries);
			// 换了个正在录入的任务 ⇒ 清单要按**新任务**的备注重算进度（§5.5）
			ClipboardPlan.syncTicks(player, program);
			player.inventoryMenu.broadcastChanges();
		});
	}

	/**
	 * 把原点设到 {@code origin}（PLAN.md §19.3 的中键路径）。
	 *
	 * <p>抽成 public static 是给**启动自检**用的（与 {@code CardEditPacket.addEntry} 同一个理由）：
	 * 这里写的是"卡以后的坐标以哪为基准"，写错了在游戏里是**静默**的 ——
	 * 玩家会看到自己录的坐标全部偏掉，而编译期一点提示都没有。
	 *
	 * <p>★ <b>写的是卡上的键</b>（用户第四十轮）：{@code CardProgramStorage.writeOrigin} ——
	 * 原点不属于程序（条目的 offset 永远相对执行它的那条臂），所以它既不进 {@code program}，
	 * 也不进 `.sma` 文本。{@code program} 参数只剩"这条路径确实读到了这张卡的程序"这一层意思
	 * （以及让下面那次清单同步拿得到程序），**不再被改写**。
	 *
	 * <p>{@code origin == null} = <b>清掉原点</b>（回到"没设"）。用户第四十一轮加的那两个 GUI
	 * 按钮里，"输入框留空确认"走的就是这一支 —— 之前它只能在客户端直接改卡，会被服务端广播的
	 * 权威副本顶回去；现在起它与设原点共用同一条服务端权威路径。
	 */
	public static void applyOrigin(ServerPlayer player, ItemStack card, ArmProgram program,
		HolderLookup.Provider registries, @Nullable BlockPos origin) {
		CardProgramStorage.writeOrigin(card, origin);
		// 与模式/任务那条路一样：写完把权威副本广播回去（原点客户端也要立刻看到，
		// 否则"再录一条"的换算还是旧的；清单的勾不受原点影响，这里只为共用同一条收尾路径）
		ClipboardPlan.syncTicks(player, program);
		player.inventoryMenu.broadcastChanges();
	}

}
