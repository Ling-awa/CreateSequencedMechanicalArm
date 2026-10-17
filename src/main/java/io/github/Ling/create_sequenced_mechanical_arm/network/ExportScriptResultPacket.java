package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.client.ExportScriptFeedback;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「服务端校验通过后的脚本文本」的 S2C 回传：**名字 + 文本**，客户端拿到才落本地文件
 * （用户改口径：文件在玩家本机、服务端不存玩家脚本 —— 与 Create 蓝图"客户端先落本地"同一条分工）。
 *
 * <h2>为什么要把整份文本发回给玩家</h2>
 * 文本本来就在**他自己那条臂**里（{@link ExportScriptPacket#handle} 已经验过主手物品、方块与距离），
 * 所以这不是"泄露别人的程序"，只是把钱路走对：服务端负责判定（能编、不超 60 KiB、名字合法），
 * 客户端负责落到**玩家自己的硬盘**上。用户已明确接受这一点。
 *
 * <h2>为什么只发文本、不发"路径"</h2>
 * 服务端不该知道玩家把文件存哪（单人 / 多人 / 专用服务器上的本机路径各不相同），
 * 而且路径只能是本机概念。所以包里只有"写哪个名字"与"写什么内容"，
 * 目标目录由客户端用 {@link ScriptFiles#directory()} 现算。
 *
 * <h2>包体大小</h2>
 * 文本上限就是卡的落盘上限 {@link ProgramText#MAX_BYTES}（60 KiB），两个 {@code writeUtf}
 * 的上限都用它 ⇒ 不会出现"名字合法、文本被截断"的静默问题。NeoForge 对注册载荷有自动分片
 * （{@code GenericPacketSplitter}），所以这一个包不会被 {@code ServerboundCustomPayloadPacket}
 * 的 32767 字节上限卡住。
 */
public record ExportScriptResultPacket(String name, String text) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ExportScriptResultPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "export_script_result"));

	public static final StreamCodec<FriendlyByteBuf, ExportScriptResultPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> { // 参数顺序：StreamEncoder 是 (buf, value)
			buf.writeUtf(packet.name(), ScriptFiles.MAX_NAME);
			buf.writeUtf(packet.text(), ProgramText.MAX_BYTES);
		},
		buf -> new ExportScriptResultPacket(buf.readUtf(ScriptFiles.MAX_NAME), buf.readUtf(ProgramText.MAX_BYTES)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/**
	 * 客户端：写本地文件 + 一条 actionbar。真正的活儿在
	 * {@link ExportScriptFeedback}（客户端类：要拿本机玩家与 {@code Minecraft}）。
	 *
	 * <p>⚠️ 与其它 {@code playToClient} 处理器一样**不能加 `@OnlyIn(Dist.CLIENT)`**
	 * （{@code RuntimeDistCleaner} 会把这个方法摘掉，而注册是双端的 ⇒ 专用服务端
	 * {@code NoSuchMethodError}；2026-10 实测踩过，那条教训现在写在
	 * {@code network/SequencedArmNetwork} 的类注释里 —— 那三个旧包已经删了）。
	 * 方法体里引用客户端类是安全的：它只会在客户端被调用。
	 */
	public static void handle(ExportScriptResultPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> ExportScriptFeedback.finish(packet.name(), packet.text()));
	}

}
