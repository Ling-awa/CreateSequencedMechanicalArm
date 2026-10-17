package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity.StartResult;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「我要把这台桌子上的《某某》传给你，共 {@code totalBytes} 字节」—— 一次上传的**开场白**。
 *
 * <p>为什么要有这一包（而不是直接开始发片）：服务端必须**在收任何字节之前**就知道"多大、叫什么、
 * 这张桌子现在能不能收"，因为那三件事决定了后面的一切 —— 总量上限（{@link ProgramText#MAX_BYTES}）、
 * 输入槽要不要在这一刻消耗、以及"以 {@code totalBytes} 为界"的三道片校验
 * （{@link ProgramImportTableBlockEntity#acceptChunk}）。没有开场白，服务端就只能盲收，
 * "超量"与"乱序"也就无从判起。
 *
 * <p><b>服务端不碰文件</b>（用户口径）：脚本在玩家本机，这个包只带来**文件名与字节数**，
 * 内容由随后的 {@link ProgramImportChunkPacket} 分片送达。这与上游
 * {@code ClientSchematicLoader → ServerSchematicLoader} 的分工一致（那边也是先声明、再分片）。
 *
 * <p>校验与动作整块在 {@link ProgramImportTableBlockEntity#startUpload}（唯一实现，启动自检也走它），
 * 这个类只负责**把结局翻成一条回执**：每一种拒绝都有一条明确的话，绝不静默
 * （"按了确认、什么都没发生"是这类界面最容易犯的错）。
 *
 * <p>哪台机器？连坐标都不传 —— 从玩家**当前打开的菜单**反查
 * （{@link ProgramImportTableBlockEntity#ofOpenMenu}），客户端说破天也改不了这一点。
 */
public record ProgramImportUploadStartPacket(String file, int totalBytes) implements CustomPacketPayload {

	/**
	 * 名字长度上限直接取 {@link ScriptFiles#MAX_NAME}（32）：两端同源。
	 * 超过它的名字在服务端也会被 {@code ScriptFiles.isValidName} 拒，但**在解码期就该挡掉**——
	 * 一个 1 MB 的字符串没必要让它走到业务逻辑里。
	 */
	public static final CustomPacketPayload.Type<ProgramImportUploadStartPacket> TYPE =
		new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID,
				"program_import_upload_start"));

	public static final StreamCodec<FriendlyByteBuf, ProgramImportUploadStartPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> { // 参数顺序：StreamEncoder 是 (buf, value)
			buf.writeUtf(packet.file(), ScriptFiles.MAX_NAME);
			buf.writeVarInt(packet.totalBytes());
		},
		buf -> new ProgramImportUploadStartPacket(buf.readUtf(ScriptFiles.MAX_NAME), buf.readVarInt()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ProgramImportUploadStartPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			// 旁观者没有"库存"这回事（菜单也打不开，见 ProgramImportTableBlock.useWithoutItem）
			if (player.isSpectator())
				return;
			ProgramImportTableBlockEntity table = ProgramImportTableBlockEntity.ofOpenMenu(player);
			if (table == null)
				return;

			StartResult result = table.startUpload(player.getUUID(), packet.file(), packet.totalBytes());
			switch (result) {
				case STARTED -> feedback(player, "import.started", ChatFormatting.GREEN, packet.file(),
					packet.totalBytes());
				case ALREADY_UPLOADING -> feedback(player, "import.already", ChatFormatting.RED);
				case NO_INPUT -> feedback(player, "import.no_input", ChatFormatting.RED);
				case NOT_BLANK -> feedback(player, "import.not_blank", ChatFormatting.RED);
				case OUTPUT_BUSY -> feedback(player, "import.output_busy", ChatFormatting.RED);
				case BAD_NAME -> feedback(player, "import.bad_name", ChatFormatting.RED);
				case EMPTY_FILE -> feedback(player, "import.empty_file", ChatFormatting.RED, packet.file());
				case TOO_LARGE -> feedback(player, "import.too_large", ChatFormatting.RED, packet.totalBytes(),
					ProgramText.MAX_BYTES);
			}
		});
	}

	/**
	 * 回执走**快捷栏上方那行**（actionbar），与本体存完蓝图后的提示同一条通道
	 * （{@code LangBuilder#sendStatus} 就是 {@code displayClientMessage(component(), true)}；
	 * 记录表导出那条路 {@link ExportScriptPacket#handle} 也用它）。
	 *
	 * <p>⚠️ 参数只传 {@code String / Number}：{@code TranslatableContents} 的构造器在非生产环境
	 * 逐个校验参数类型，塞别的东西会当场抛 {@link IllegalArgumentException}，症状是"什么提示都没有"
	 * （这个坑在记录表那一轮踩过，见 {@link ExportScriptPacket} 的 {@code fileName} 注释）。
	 */
	static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
