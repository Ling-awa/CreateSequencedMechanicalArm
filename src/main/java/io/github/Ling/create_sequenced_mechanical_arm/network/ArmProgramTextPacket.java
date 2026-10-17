package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「把这段脚本文本写进我主手那张操作表」的 C2S 请求（{@code /sma code import <名字>} 的下半截）。
 *
 * <h2>为什么这条路必须过服务端</h2>
 * 客户端的物品栏只是**副本**：直接改本地那份 {@code CUSTOM_DATA} 会被服务端权威副本
 * 在下一 tick 顶掉（表现是"提示说导入了，松手一看卡还是空的"）。而卡上存什么由服务端说了算 ——
 * 所以客户端只做"读本机文件 + 本地预检"，**写入**一定回到服务端。
 *
 * <p>这与编辑器的 {@link ArmProgramEditPacket} 同一条分工（客户端提交、服务端整份接受或整份拒绝），
 * 差别只在载荷形式：那条路交的是**程序 NBT**（编辑器改的是模型），这条路交的是**文件原文** ——
 * 用户的注释与排版必须原样进卡（{@code CardProgramStorage.writeText} 不做任何重排）。
 * 用 {@link ArmProgramEditPacket} 走这条路不行：它会先 {@code write(program)} 再反编译，
 * 导进来的注释会被抹掉。
 *
 * <h2>服务端不信客户端的任何一句话</h2>
 * 客户端在本地已经判过一次（{@code /sma code import} 里：能编译 + 规模在限内），这里是**第二道**，
 * 判据逐条重跑：主手是操作表 → 文本上限（{@link ProgramText#MAX_BYTES}）→ 能编译 →
 * {@code ArmProgram.isWithinLimits()} → {@code writeText} 的落盘判据。改过的客户端最多让自己
 * 收到一段"编译不过"，改不了别人的卡（主手是**服务端自己**看得见的那只）。
 *
 * <p>解码期的字符上限也用 {@link ProgramText#MAX_BYTES}：UTF-8 里一个字符至少占 1 字节，
 * 所以"字符数 ≤ 字节上限"是一条**只紧不松**的护栏（真超限的文本在服务端还会被逐字节判一次）。
 */
public record ArmProgramTextPacket(String text) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ArmProgramTextPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "arm_program_text"));

	public static final StreamCodec<FriendlyByteBuf, ArmProgramTextPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> buf.writeUtf(packet.text(), ProgramText.MAX_BYTES),
		buf -> new ArmProgramTextPacket(buf.readUtf(ProgramText.MAX_BYTES)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ArmProgramTextPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (player.isSpectator())
				return;
			String text = packet.text();

			// ① 主手确实是一张操作表（"客户端说它有"不算数）
			ItemStack held = player.getMainHandItem();
			if (!OperationTableItem.isTable(held)) {
				failure(player, "code.import_need_card");
				return;
			}
			// ② 文本上限：盘上存的就是这串文本（§17.4 的 60 KiB）
			if (!ProgramText.withinLimit(text)) {
				failure(player, "code.import_too_large", ProgramText.utf8Length(text), ProgramText.MAX_BYTES);
				return;
			}
			// ③ 编译：**过了才写**（存一份编译器不认的文本等于给玩家一张废卡）
			var compiled = ScriptCompiler.compile(text, player.level()
				.registryAccess());
			if (compiled.hasErrors()) {
				failure(player, "code.check_failed", compiled.errors()
					.size());
				ScriptCompiler.errorLines(compiled.errors())
					.forEach(line -> player.displayClientMessage(Component.literal("  " + line)
						.withStyle(ChatFormatting.RED), false));
				return;
			}
			// ④ 规模（任务 / 条目上限）：与编辑器同一条守卫，且只有 ArmProgram 一处实现
			if (!compiled.value()
				.isWithinLimits()) {
				failure(player, "code.import_over_limit", ScriptCompiler.summarize(compiled.value()));
				return;
			}
			// ⑤ 落盘：判据只有 writeText 一处（它内部再判一次 withinLimit）
			if (CardProgramStorage.writeText(held, text) != CardProgramStorage.StoreResult.OK) {
				failure(player, "code.import_too_large", ProgramText.utf8Length(text), ProgramText.MAX_BYTES);
				return;
			}
			player.inventoryMenu.broadcastChanges();
			player.displayClientMessage(Component
				.translatable("create_sequenced_mechanical_arm.code.import_ok", ScriptCompiler.summarize(compiled.value()))
				.withStyle(ChatFormatting.GREEN), false);
			compiled.warnings()
				.forEach(warning -> player.displayClientMessage(Component
					.translatable("create_sequenced_mechanical_arm.code.warning", warning)
					.withStyle(ChatFormatting.YELLOW), false));
		});
	}

	/** 失败回执走**聊天栏**（不是 actionbar）：这条路的成败是"这张卡有没有变"，值得留在聊天记录里。 */
	private static void failure(ServerPlayer player, String key, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(ChatFormatting.RED),
			false);
	}

}
