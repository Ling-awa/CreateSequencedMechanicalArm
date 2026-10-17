package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportFiles;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「给我看一眼服务端脚本目录里有哪些 {@code .sma}」的 C2S 请求（空的）。
 *
 * <p><b>为什么必须有这个包</b>：界面上那个文件列表来自<b>服务端</b>的
 * {@code run/sequenced_arm/scripts/}（见 {@link ProgramImportFiles} 的类注释）。客户端读不到它，
 * 所以只有两条路：把整个目录搬给客户端，或者让客户端问一句、服务端答一句。这里是后者，
 * 而且**答案里只有名字**（{@link ProgramImportListPacket}）—— 文本等到玩家真的按下确认
 * （{@link ProgramImportStartPacket}）才由服务端自己读，客户端从头到尾不接触别人的脚本内容。
 *
 * <p>与上游（Create 的蓝图桌）形状上的对应：那边是 {@code SchematicSyncPacket}（服务端问客户端"你有这些文件吗"）
 * + {@code SchematicUploadPacket}（客户端一片片上传）。方向刚好相反，因为脚本目录在服务端；
 * 我们也不需要分片（只传名字，一个包就够）。
 */
public record ProgramImportRequestPacket() implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ProgramImportRequestPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "program_import_request"));

	/** 空载荷：只有一个"类型 + 序号"头，没有任何字段。 */
	public static final StreamCodec<FriendlyByteBuf, ProgramImportRequestPacket> STREAM_CODEC =
		StreamCodec.unit(new ProgramImportRequestPacket());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ProgramImportRequestPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			// 只认"玩家真的开着这台机器的界面"（{@code ofOpenMenu} 里已经验了菜单类型、方块实体与 8 格距离）：
			// 否则这就是一个"任何人都能问你要目录列表"的洞，而它跟手上有没有方块毫无关系。
			ProgramImportTableBlockEntity table = ProgramImportTableBlockEntity.ofOpenMenu(player);
			if (table == null)
				return;
			PacketDistributor.sendToPlayer(player, new ProgramImportListPacket(ProgramImportFiles.list()));
		});
	}

}
