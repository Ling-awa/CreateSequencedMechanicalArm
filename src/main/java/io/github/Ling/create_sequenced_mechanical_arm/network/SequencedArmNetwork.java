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
			// 操作记录表的上传意图（名字 + 目标臂坐标）：服务端校验后导出成脚本文件
			.playToServer(ExportScriptPacket.TYPE, ExportScriptPacket.STREAM_CODEC, ExportScriptPacket::handle)
			// ——— 操作导入桌（第 2 片）：C2S 请求列表 / C2S 开始导入 / S2C 列表内容 ———
			// 方向是"客户端问、服务端答"：脚本目录在**服务端**（`run/sequenced_arm/scripts/`），
			// 客户端读不到它 —— 与蓝图桌那条"客户端读目录再上传"的链正好相反，理由见 ProgramImportFiles。
			.playToServer(ProgramImportRequestPacket.TYPE, ProgramImportRequestPacket.STREAM_CODEC,
				ProgramImportRequestPacket::handle)
			.playToServer(ProgramImportStartPacket.TYPE, ProgramImportStartPacket.STREAM_CODEC,
				ProgramImportStartPacket::handle)
			.playToClient(ProgramImportListPacket.TYPE, ProgramImportListPacket.STREAM_CODEC,
				ProgramImportListPacket::handle);
	}

}
