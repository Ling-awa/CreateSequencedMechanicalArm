package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 网络负载注册（mod 总线）。 */
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
			// ——— 操作导入桌（第 2 片）：C2S 请求列表 / C2S 开始导入 / S2C 列表内容 ———
			// ⚠️ 这三条是**旧口径**（服务端读盘）：脚本文件已经归玩家本机（见 ScriptFiles），
			// 第二步会改成"客户端扫本地目录 + 分片上传"（照 ClientSchematicLoader），
			// 那时这两个 C2S 包与那个 S2C 列表包都会去掉。
			.playToServer(ProgramImportRequestPacket.TYPE, ProgramImportRequestPacket.STREAM_CODEC,
				ProgramImportRequestPacket::handle)
			.playToServer(ProgramImportStartPacket.TYPE, ProgramImportStartPacket.STREAM_CODEC,
				ProgramImportStartPacket::handle)
			.playToClient(ProgramImportListPacket.TYPE, ProgramImportListPacket.STREAM_CODEC,
				ProgramImportListPacket::handle);
	}

}
