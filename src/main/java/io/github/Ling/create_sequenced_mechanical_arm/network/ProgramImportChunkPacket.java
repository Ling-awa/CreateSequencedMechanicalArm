package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity.ChunkResult;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;

import io.netty.handler.codec.DecoderException;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 一片脚本文本：{@code 片序号 (seq) / 总字节数 (totalBytes) / 本片字节 (data)}。
 *
 * <p>★ 三个字段各管一件事，**都不是可省的**：
 * <ul>
 *   <li>{@code seq}：服务端只按"正好是下一片"收（{@link ProgramImportTableBlockEntity#acceptChunk}），
 *       所以它必须是**显式的**序号，而不是"你发了几次我就数到几" —— 后者在丢包/重发之后会静默错位；</li>
 *   <li>{@code totalBytes}：每片都带一遍，用来发现"这一片属于另一次上传"（会话已经换了、或者
 *       客户端在两次上传之间串了状态）。只在开场白里讲一次的话，服务端没有办法在片这一层复核；</li>
 *   <li>{@code data}：**原始字节**，不是格式化过的字符串。
 *       ⚠️ 千万不要把它塞进 {@code Component.translatable(key, data)} 之类的地方：
 *       {@code TranslatableContents} 的构造器在非生产环境逐字校验参数类型，塞 {@code byte[]}
 *       会当场抛 {@link IllegalArgumentException}（症状是"导入到一半客户端没反应"）。</li>
 * </ul>
 *
 * <p><b>片大小在解码期就卡死</b>：{@link ProgramImportTableBlockEntity#CHUNK_BYTES}（8 KiB）。
 * 手写 varint 读取而不是用 {@code readByteArray(max)}，是因为后者对**负数长度**只会在
 * {@code new byte[负数]} 时抛 {@code NegativeArraySizeException}（一个不受控的运行时异常），
 * 而这里抛 {@link DecoderException} —— NeoForge 会把它当成一个坏包丢掉，而不是让连接处在
 * 一个"半解码"的状态。超出上限同理（一个改过的客户端能发 8 MB 的片）。
 *
 * <p>时序见 {@link ProgramImportUploadStartPacket}（先声明、再分片）；客户端那一侧的发片节奏在
 * {@code client/ProgramImportUploader}：一片 / {@link ProgramImportTableBlockEntity#CHUNK_INTERVAL_TICKS} tick。
 */
public record ProgramImportChunkPacket(int seq, int totalBytes, byte[] data) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ProgramImportChunkPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "program_import_chunk"));

	public static final StreamCodec<FriendlyByteBuf, ProgramImportChunkPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> { // 参数顺序：StreamEncoder 是 (buf, value)
			buf.writeVarInt(packet.seq());
			buf.writeVarInt(packet.totalBytes());
			buf.writeByteArray(packet.data());
		},
		buf -> {
			int seq = buf.readVarInt();
			int totalBytes = buf.readVarInt();
			int size = buf.readVarInt();
			if (size <= 0 || size > ProgramImportTableBlockEntity.CHUNK_BYTES)
				throw new DecoderException("操作导入桌：片子大小不合法 " + size);
			byte[] data = new byte[size];
			buf.readBytes(data);
			return new ProgramImportChunkPacket(seq, totalBytes, data);
		});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ProgramImportChunkPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (player.isSpectator())
				return;
			ProgramImportTableBlockEntity table = ProgramImportTableBlockEntity.ofOpenMenu(player);
			if (table == null)
				return;

			ChunkResult result = table.acceptChunk(packet.seq(), packet.totalBytes(), packet.data());
			switch (result) {
				// 收下了还没收满：**不说话**（每片都回一条提示会把聊天栏刷爆）
				case ACCEPTED, NO_UPLOAD -> {
					// NO_UPLOAD = 会话已经没了（超时 / 人走了 / 上一个片被拒）。中止时那条回执已经发过，
					// 之后剩下的几片（最多几片的那点尾巴）到这儿静默丢掉，免得同一件事回好几遍。
					// 客户端那一侧本来也会在发完最后一片后自己收工（见 client/ProgramImportUploader）。
				}
				case COMPLETED -> {
					// 会话已经被清掉了（文件名也一起清了），所以这句回执从**输出槽里那张卡**取摘要 ——
					// 那正是"玩家拿到手的东西"，比复述文件名更有用。
					var saved = CardProgramStorage.read(
						table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT),
						player.level()
							.registryAccess());
					feedback(player, "import.done", ChatFormatting.GREEN, ScriptCompiler.summarize(saved));
				}
				case BAD_SEQUENCE -> feedback(player, "import.aborted", ChatFormatting.RED,
					reason("sequence"));
				case TOO_LARGE -> feedback(player, "import.aborted", ChatFormatting.RED, reason("too_large"));
				// 编译不过 / 写不进去：两条都**没有产出**，而且输入那张空白表已经消耗（会话开始时花的）
				case DOES_NOT_COMPILE -> feedback(player, "import.compile_failed", ChatFormatting.RED);
				case WRITE_FAILED -> feedback(player, "import.write_failed", ChatFormatting.RED,
					String.valueOf(result));
			}
		});
	}

	/** 中止原因那一段话（{@code create_sequenced_mechanical_arm.import.reason.*}）：拼进 actionbar 的那句里。 */
	private static Component reason(String key) {
		return Component.translatable("create_sequenced_mechanical_arm.import.reason." + key);
	}

	private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
