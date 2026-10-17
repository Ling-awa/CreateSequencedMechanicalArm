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
 * 「把《某某》导入到输入槽那张空白操作表里」的 C2S 意图：**只有一个文件名**。
 *
 * <p>与记录表导出（{@link ExportScriptPacket}）同一条分工：客户端只说想要什么，
 * 服务端自己读自己验再自己动库存。这里连"哪台机器"都不传 —— 从玩家**当前打开的菜单**
 * 反查（{@link ProgramImportTableBlockEntity#ofOpenMenu}），客户端说破天也改不了这一点。
 *
 * <p>校验与动作整块在 {@link ProgramImportTableBlockEntity#startImport}（唯一实现，启动自检也走它），
 * 这个类只负责**把结局翻成一条回执**：每一种拒绝都有一条明确的话，绝不静默
 * （"按了确认、什么都没发生"是这类界面最容易犯的错）。
 */
public record ProgramImportStartPacket(String file) implements CustomPacketPayload {

	/**
	 * 名字长度上限直接取 {@link ScriptFiles#MAX_NAME}（32）：两端同源。
	 * 超过它的名字在服务端也会被 {@code ScriptFiles.isValidName} 拒，但**在解码期就该挡掉**——
	 * 一个 1 MB 的字符串没必要让它走到业务逻辑里。
	 */
	public static final CustomPacketPayload.Type<ProgramImportStartPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "program_import_start"));

	public static final StreamCodec<FriendlyByteBuf, ProgramImportStartPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> buf.writeUtf(packet.file(), ScriptFiles.MAX_NAME),
		buf -> new ProgramImportStartPacket(buf.readUtf(ScriptFiles.MAX_NAME)));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ProgramImportStartPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			// 旁观者没有"库存"这回事（菜单也打不开，见 ProgramImportTableBlock.useWithoutItem）
			if (player.isSpectator())
				return;
			ProgramImportTableBlockEntity table = ProgramImportTableBlockEntity.ofOpenMenu(player);
			if (table == null)
				return;

			StartResult result = table.startImport(packet.file(), player.level()
				.registryAccess());
			switch (result) {
				case STARTED -> feedback(player, "import.started", ChatFormatting.GREEN, packet.file());
				case ALREADY_IMPORTING -> feedback(player, "import.already", ChatFormatting.RED);
				case NO_INPUT -> feedback(player, "import.no_input", ChatFormatting.RED);
				case NOT_BLANK -> feedback(player, "import.not_blank", ChatFormatting.RED);
				case OUTPUT_BUSY -> feedback(player, "import.output_busy", ChatFormatting.RED);
				case BAD_NAME -> feedback(player, "import.bad_name", ChatFormatting.RED);
				case NOT_FOUND -> feedback(player, "import.not_found", ChatFormatting.RED, packet.file());
				case UNREADABLE -> feedback(player, "import.unreadable", ChatFormatting.RED, packet.file());
				case TOO_LARGE -> feedback(player, "import.too_large", ChatFormatting.RED, packet.file(),
					ProgramText.MAX_BYTES);
				case DOES_NOT_COMPILE -> feedback(player, "import.compile_failed", ChatFormatting.RED, packet.file());
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
	private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
