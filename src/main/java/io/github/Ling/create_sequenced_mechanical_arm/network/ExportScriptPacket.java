package io.github.Ling.create_sequenced_mechanical_arm.network;

import java.nio.file.Path;

import org.jetbrains.annotations.Nullable;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationRecordItem;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.ArmScriptExport;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmBlock;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmBlockEntity;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「操作记录表 → 上传」的 C2S 意图包：**名字 + 哪条臂**（用户口径）。
 *
 * <p>只传意图、不传程序：客户端不读臂里的程序（那是方块实体数据，客户端根本没同步过整份），
 * 服务端收到后才把<b>自己那条臂</b>的程序导出成文件。这样"上传的是哪条臂"由服务端现场确认，
 * 客户端说破天也只能提供一个坐标 —— 而坐标要过下面四条校验。
 *
 * <p><b>校验顺序就是这张单子的顺序</b>（每一步失败都明确回一条 actionbar，绝不静默）：
 * <ol>
 *   <li>主手确实是操作记录表（手势物品没换过）；</li>
 *   <li>目标是我们的臂：方块是 {@link SequencedArmBlock}，且方块实体确实是 {@link SequencedArmBlockEntity}
 *       —— 两条都查，因为"方块对但 BE 类型不对"只可能是存档坏了或被别的模组顶掉；</li>
 *   <li>距离合理：与 {@code /sma info} 那些命令的口径一致，取 Create 自己的臂范围
 *       {@link ArmBlockEntity#getRange()}（{@code mechanicalArmRange}，默认 5 格）——
 *       比原版玩家的 4.5 格交互距离宽一点，不会挡住正常手势，但挡住"隔着一百格点一下就把文件导出去"；</li>
 *   <li>程序相关（空 / 转不成文本 / 超上限）与文件名（非法 / 同名）——这些都交给
 *       {@link ArmScriptExport#write}，那边是唯一实现，自检也走它。</li>
 * </ol>
 */
public record ExportScriptPacket(String name, BlockPos pos) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<ExportScriptPacket> TYPE = new CustomPacketPayload.Type<>(
		ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "export_script"));

	public static final StreamCodec<FriendlyByteBuf, ExportScriptPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> { // 参数顺序：StreamEncoder 是 (buf, value)
			buf.writeUtf(packet.name());
			buf.writeBlockPos(packet.pos());
		},
		buf -> new ExportScriptPacket(buf.readUtf(), buf.readBlockPos()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	// ————————————————————————— 服务端处理 —————————————————————————

	public static void handle(ExportScriptPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			// 旁观者没有"手部手势"这回事（客户端那一侧也不会开界面，见 OperationRecordHandler）
			if (player.isSpectator())
				return;
			Level level = player.level();

			// ① 主手是记录表
			ItemStack held = player.getMainHandItem();
			if (!OperationRecordItem.isRecord(held)) {
				feedback(player, "record.need_item", ChatFormatting.RED);
				return;
			}

			// ② 目标是我们的臂（方块 + 方块实体）
			BlockPos pos = packet.pos();
			// ⚠️ 先问"那一格在不在已加载的区块里"：坐标是**客户端给的**，直接 getBlockState 会顺手
			//    加载一个远处的区块（坏包 / 改包都能触发）。没加载 = 不可能是"你正看着的那条臂"。
			if (!level.hasChunkAt(pos)) {
				feedback(player, "record.no_arm", ChatFormatting.RED);
				return;
			}
			if (!(level.getBlockState(pos)
				.getBlock() instanceof SequencedArmBlock)
				|| !(level.getBlockEntity(pos) instanceof SequencedArmBlockEntity arm)) {
				feedback(player, "record.no_arm", ChatFormatting.RED);
				return;
			}

			// ③ 距离：口径与 ProgramRuntime.withinArmRange 同一个来源（Create 的 mechanicalArmRange）
			double range = ArmBlockEntity.getRange();
			if (player.getEyePosition()
				.distanceToSqr(Vec3.atCenterOf(pos)) > range * range) {
				feedback(player, "record.too_far", ChatFormatting.RED, (int) range);
				return;
			}

			// ④ 程序与文件名 → 落盘（唯一实现）
			ArmScriptExport.Outcome outcome = ArmScriptExport.write(packet.name(), arm.getProgram(),
				level.registryAccess());
			switch (outcome.problem()) {
				case OK -> feedback(player, "record.written", ChatFormatting.GREEN, fileName(outcome.file()));
				case EMPTY_PROGRAM -> feedback(player, "record.empty_program", ChatFormatting.RED);
				case NOT_ENCODABLE -> feedback(player, "record.not_encodable", ChatFormatting.RED);
				case TOO_LARGE -> feedback(player, "record.too_large", ChatFormatting.RED, outcome.args());
				case BAD_NAME -> feedback(player, "record.bad_name", ChatFormatting.RED);
				case NAME_TAKEN -> feedback(player, "record.exists", ChatFormatting.RED, fileName(outcome.file()));
				case WRITE_FAILED -> feedback(player, "record.write_failed", ChatFormatting.RED, outcome.args());
			}
		});
	}

	/**
	 * 回执里显示的那串字：**只有文件名**（含 {@code .sma} 后缀），与 Create 存蓝图后那条提示完全同口径
	 * —— {@code SchematicAndQuillHandler.java:220} 传的就是 {@code file.getFileName().toString()}。
	 *
	 * <p><b>为什么绝对不能把 {@link Path} 直接塞进 {@code %s}（"导出成功但屏幕上什么都没有"的真凶）</b>：
	 * {@code TranslatableContents} 的构造器在**非生产环境**逐个校验参数，只接受
	 * {@code Component / Number / Boolean / String}，别的当场抛 {@link IllegalArgumentException}
	 * （反编译源码 {@code TranslatableContents.java:87-93}）。开发环境（{@code run/}）恰好就是"非生产"
	 * ⇒ 整个 {@code enqueueWork} 任务炸掉，快捷栏上一条消息都没有 —— 用户反馈的"没有提示"就是它。
	 * 生产环境不抛，但会把 {@code Path.toString()} 打成**绝对路径**（又长又跟机器有关，糊在快捷栏上没法看）。
	 *
	 * <p>"同名不覆盖"那一条原来同样传的是 {@link Path}，一并修掉。
	 */
	private static String fileName(@Nullable Path file) {
		return file == null ? "?" : file.getFileName()
			.toString();
	}

	/**
	 * 回执的那一条消息：**快捷栏上方那行（actionbar）**，失败也走这里，只是换颜色/换文案 —— 绝不静默。
	 *
	 * <p>与本体用的是**同一条通道**（核过源码）：Create 存完蓝图调用
	 * {@code CreateLang.translate("schematicAndQuill.saved", 文件名).sendStatus(player)}
	 * （{@code SchematicAndQuillHandler.java:220-221}），而 {@code LangBuilder#sendStatus} 的实现就是
	 * {@code player.displayClientMessage(component(), true)}（catnip {@code LangBuilder.java:161-163}）。
	 * 所以这里不需要任何 Create 专属 API：{@code true} 那个参数就是 actionbar。
	 *
	 * <p>⚠️ 参数只能传 {@code Component / Number / Boolean / String}（见 {@link #fileName(Path)} 的注释）。
	 */
	private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
