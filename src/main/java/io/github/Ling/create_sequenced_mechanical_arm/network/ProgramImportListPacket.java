package io.github.Ling.create_sequenced_mechanical_arm.network;

import java.util.ArrayList;
import java.util.List;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.client.ProgramImportScreen;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportFiles;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「服务端目录里有哪些脚本」的 S2C 答案：**只有名字**（不含 {@code .sma}、不含内容、不含路径）。
 *
 * <p>为什么不把文本一起发下来：列表是每开一次界面就问一次的东西，而文本可以到 60 KiB/份
 * （{@link io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText#MAX_BYTES}）——
 * 把一整个目录的内容塞进一个"打开界面"的包是白白多一条大尾巴，而且客户端拿到的文本
 * **没有经过校验**（校验在服务端：{@link ProgramImportFiles#read}）。玩家确认导入时服务端再读一遍，
 * 那时才校验、才写卡。
 *
 * <p>两个解码侧的护栏：条数 ≤ {@link ProgramImportFiles#MAX_FILES}、每个名字 ≤ {@link ScriptFiles#MAX_NAME}。
 * 服务端写的时候已经限过一遍，这里再限是因为**客户端不能相信网络**（一个坏包能让列表控件
 * 分配几万条字符串、或让一个 1 MB 的名字把界面画到屏幕外）。越界直接抛 {@link DecoderException}，
 * 让 NeoForge 的处理器把这一包丢掉 —— 这比"悄悄截断"好：截断会把一个坏包伪装成一次正常的刷新。
 */
public record ProgramImportListPacket(List<String> names) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ProgramImportListPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "program_import_list"));

	public static final StreamCodec<FriendlyByteBuf, ProgramImportListPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> { // 参数顺序：StreamEncoder 是 (buf, value)
			buf.writeVarInt(packet.names().size());
			for (String name : packet.names())
				buf.writeUtf(name, ScriptFiles.MAX_NAME);
		},
		buf -> {
			int count = buf.readVarInt();
			if (count < 0 || count > ProgramImportFiles.MAX_FILES)
				throw new DecoderException("操作导入桌：文件列表条数不合法 " + count);
			List<String> names = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				names.add(buf.readUtf(ScriptFiles.MAX_NAME));
			return new ProgramImportListPacket(List.copyOf(names));
		});

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/**
	 * 客户端：把列表交给**当前打开着的**那个界面。
	 *
	 * <p>⚠️ **不能加 `@OnlyIn(Dist.CLIENT)`**：加了之后 `RuntimeDistCleaner` 会在**专用服务端**上把这个方法
	 * 整个摘掉，而 `SequencedArmNetwork` 的 `playToClient(TYPE, CODEC, ProgramImportListPacket::handle)`
	 * 是**双端都会注册**的 —— 于是模组在服务端加载阶段直接 `NoSuchMethodError:
	 * void ProgramImportListPacket.handle(ProgramImportListPacket, IPayloadContext)`（2026-10 实测踩过）。
	 * 本仓其它包（如 `CardUiPacket.handle`）也都没加，照同一惯例。
	 * 方法体里引用客户端类是安全的：它只会在客户端被调用。
	 */
	public static void handle(ProgramImportListPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> ProgramImportScreen.acceptList(packet.names()));
	}

}
