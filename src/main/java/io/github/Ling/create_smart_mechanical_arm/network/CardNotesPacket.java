package io.github.Ling.create_smart_mechanical_arm.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_smart_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_smart_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramTask;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「剪贴板 → 备注」的 C2S 意图包（用户第三轮要求）：<i>"在剪贴板上写下若干行的文本，放在副手，
 * 主手持有操作卡潜行右键会自动按序号添加对应行的备注。"</i>
 *
 * <p>行由**客户端**读出来发过来（剪贴板内容本就在物品组件里，客户端拿得到），服务端只做校验与落盘：
 * 第 i 行 → 当前任务第 i 条条目的 {@code note}。语义细节：
 * <ul>
 *   <li><b>空行跳过</b>：想给某条留白就空一行，不会被清成空备注；</li>
 *   <li>行数超过条目数 → 多出来的忽略，并在提示里说出来（免得玩家以为写丢了）；</li>
 *   <li>每条备注截到 {@link ArmProgramEntry#NOTE_MAX}：与编辑器里那个备注输入框的上限一致，
 *       否则会出现"界面上显示一截、实际存着另一截"的错位。</li>
 * </ul>
 */
public record CardNotesPacket(List<String> lines) implements CustomPacketPayload {

	/** 一次最多收多少行：挡住畸形/恶意包在 {@code readList} 里撑爆内存。 */
	private static final int MAX_LINES = 256;

	public static final CustomPacketPayload.Type<CardNotesPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_smart_mechanical_arm.MODID, "card_notes"));

	/**
	 * 把一行备注夹到协议上限内 —— <b>发送前必须过这一道</b>。
	 *
	 * <p>为什么不是"可选的清理"：编码器用的是 {@code writeUtf(line, NOTE_MAX)}，而 MC 的
	 * {@code Utf8String.write} 对超长字符串是**抛 {@code EncoderException}**，不是截断
	 *（`Utf8String.write`：`if (string.length() > maxLength) throw new EncoderException("String too big …")`）。
	 * 编码发生在客户端的 netty 管线里，于是"剪贴板里抄了一行长文本 + 潜行右键写备注"
	 * 会把客户端直接打成断线（{@code Internal Exception: String too big}）——
	 * 而 Create 的剪贴板单条文本并不受 48 字约束，所以这不是理论边界。
	 *
	 * <p>服务端的 {@code applyNotes} 本来就会截断（口径一致），这里只是把同一口径**提前到发送前**：
	 * 协议的上限就该由协议这一侧保证，而不是指望每个调用方自己记得夹。
	 */
	public static String clip(String line) {
		// 口径只有一处：ArmProgramEntry.clipNote（128 字总上限，超了补 …）。这里只是转发，
		// 免得"协议的上限"和"字段的上限"各写一份、迟早对不上。
		return ArmProgramEntry.clipNote(line);
	}

	public static final StreamCodec<FriendlyByteBuf, CardNotesPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> buf.writeCollection(packet.lines(), (b, line) -> b.writeUtf(clip(line), ArmProgramEntry.NOTE_MAX)),
		buf -> {
			int count = buf.readVarInt();
			if (count < 0 || count > MAX_LINES) {
				// 超限：把内容读掉保持流同步，然后当成空包（绝不按它分配内存）
				for (int i = 0; i < count; i++)
					buf.readUtf(ArmProgramEntry.NOTE_MAX);
				return new CardNotesPacket(List.of());
			}
			List<String> lines = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				lines.add(buf.readUtf(ArmProgramEntry.NOTE_MAX));
			return new CardNotesPacket(lines);
		});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(CardNotesPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			ItemStack card = player.getMainHandItem();
			if (!OperationTableItem.isTable(card))
				return;
			HolderLookup.Provider registries = player.level()
				.registryAccess();
			ArmProgram program = CardProgramStorage.read(card, registries);
			if (program.tasks.isEmpty()) {
				feedback(player, "card.notes_no_task", ChatFormatting.RED);
				return;
			}
			int index = Math.max(0, Math.min(program.editorTask, program.tasks.size() - 1));
			ArmProgramTask task = program.tasks.get(index);

			int[] counts = applyNotes(task, packet.lines());
			int written = counts[0];
			int ignored = counts[1];
			if (written == 0 && ignored == 0)
				return; // 全是空行：什么都没变，不必回写整张卡
			program.editorTask = index;
			CardProgramStorage.write(card, program, registries);
			// 备注被整批重写过 ⇒ 清单的"已完成到第几条"跟着重算（§5.5）
			ClipboardPlan.syncTicks(player, program);
			player.inventoryMenu.broadcastChanges();
			feedback(player, "card.notes_applied", ChatFormatting.GREEN, written, ignored, index + 1, task.name);
		});
	}

	/**
	 * 把行按序号写进条目的备注。返回 {@code {写入条数, 被忽略的行数}}。
	 *
	 * <p>逻辑抽成静态方法是为了让**服务端自检**能直接验它 —— 序号对应关系错了（差一位、空行被清成空备注、
	 * 多出来的行覆盖到别的条目上）在游戏里几乎看不出来，只能靠断言。
	 */
	public static int[] applyNotes(ArmProgramTask task, List<String> lines) {
		int written = 0;
		int ignored = 0;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i) == null ? "" : lines.get(i)
				.strip();
			if (line.isBlank())
				continue; // 空行 = 这一条不动
			if (i >= task.entries.size()) {
				ignored++;
				continue;
			}
			// 口径只有一处：ArmProgramEntry.clipNote（128 字，超了补省略号）。
			// 这里原来是自己写的 substring —— 少了省略号，与编辑器输入框、协议上限的显示就对不上了。
			task.entries.get(i).note = ArmProgramEntry.clipNote(line);
			written++;
		}
		return new int[] { written, ignored };
	}

	private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_smart_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

	/** 调试用：把行数写进日志时别把整段文本打出来。 */
	@Override
	public String toString() {
		return String.format(Locale.ROOT, "CardNotesPacket[%d lines]", lines.size());
	}

}
