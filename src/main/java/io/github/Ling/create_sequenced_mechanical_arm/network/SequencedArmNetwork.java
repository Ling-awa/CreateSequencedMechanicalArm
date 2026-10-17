package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络负载注册（mod 总线）。
 *
 * <p>⚠️ <b>删包时同步删注册</b>：{@code RegisterPayloadHandlersEvent} 里留着一条指向已删类的
 * {@code TYPE} / {@code STREAM_CODEC}，编译期就报错；而删了类却忘了删注册时打出来的包会让
 * 客户端与服务器的载荷表对不上（连接直接断）。① 的那条教训同理 —— 这次删掉的
 * {@code ProgramImportRequest/List/Start} 三个包在这里一并消失了，没有留下悬空的注册。
 *
 * <p>⚠️ <b>若将来再加一个 S2C 包，它的 {@code handle} 不要标 {@code @OnlyIn(Dist.CLIENT)}</b>：
 * {@code RuntimeDistCleaner} 会在**专用服务端**把那个方法整个摘掉，而这张表是**双端都会注册**的
 * ⇒ 服务端加载阶段直接 {@code NoSuchMethodError}（2026-10 实测踩过）。本仓其它包的
 * {@code handle} 都没有标注，照同一惯例（方法体里引用客户端类是安全的：它只会在客户端被调用）。
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, bus = EventBusSubscriber.Bus.MOD)
public class SequencedArmNetwork {

	@SubscribeEvent
	public static void register(RegisterPayloadHandlersEvent event) {
		event.registrar("1")
			.playToServer(CardEditPacket.TYPE, CardEditPacket.STREAM_CODEC, CardEditPacket::handle)
			.playToServer(ArmProgramEditPacket.TYPE, ArmProgramEditPacket.STREAM_CODEC, ArmProgramEditPacket::handle)
			.playToServer(CardUiPacket.TYPE, CardUiPacket.STREAM_CODEC, CardUiPacket::handle)
			.playToServer(CardViewPacket.TYPE, CardViewPacket.STREAM_CODEC, CardViewPacket::handle)
			.playToServer(CardNotesPacket.TYPE, CardNotesPacket.STREAM_CODEC, CardNotesPacket::handle)
			// 操作记录表的上传意图（名字 + 目标臂坐标）：服务端**只校验**，不写文件
			.playToServer(ExportScriptPacket.TYPE, ExportScriptPacket.STREAM_CODEC, ExportScriptPacket::handle)
			// …校验通过后的脚本文本回传（S2C）：**客户端**把它写进玩家本机的 run/sequenced_arm/scripts/
			.playToClient(ExportScriptResultPacket.TYPE, ExportScriptResultPacket.STREAM_CODEC,
				ExportScriptResultPacket::handle)
			// ——— 操作导入桌（第二步）：客户端读本机文件、分片上传，服务端只收片 + 裁决 + 写卡 ———
			// 开场白：文件名 + 总字节（服务端在这一刻决定收不收、并在这一刻消耗输入槽）
			.playToServer(ProgramImportUploadStartPacket.TYPE, ProgramImportUploadStartPacket.STREAM_CODEC,
				ProgramImportUploadStartPacket::handle)
			// 一片：序号 + 总字节 + 本片字节（服务端只按"正好是下一片"收，乱序/超量立刻中止）
			.playToServer(ProgramImportChunkPacket.TYPE, ProgramImportChunkPacket.STREAM_CODEC,
				ProgramImportChunkPacket::handle)
			// `/sma code import <名字>` 的写入那一段：本机文件 → 主手操作表（**必须过服务端**，
			// 客户端直接改 NBT 会被服务端权威副本顶掉）
			.playToServer(ArmProgramTextPacket.TYPE, ArmProgramTextPacket.STREAM_CODEC,
				ArmProgramTextPacket::handle);
	}

}
