package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 录入会话状态（模式 / 正在编辑哪个任务）的 C2S 意图包（PLAN.md §5.1）。
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
 * <p>服务端不校验"这个玩家该不该有编辑模式"：编辑的是他自己手上的卡，模式只是 UX 约定。
 */
public record CardUiPacket(String mode, int task) implements CustomPacketPayload {

	// 线上取值只有一处定义（`ArmProgram` 的模型常量）—— 这里只是别名，免得两边各写一个 "EDIT"
	public static final String RECORD = ArmProgram.MODE_RECORD;
	public static final String EDIT = ArmProgram.MODE_EDIT;

	public static final CustomPacketPayload.Type<CardUiPacket> TYPE =
		new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "card_ui"));

	public static final StreamCodec<FriendlyByteBuf, CardUiPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> {
			buf.writeUtf(packet.mode());
			buf.writeVarInt(packet.task());
		},
		buf -> new CardUiPacket(buf.readUtf(), buf.readVarInt()));

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

}
