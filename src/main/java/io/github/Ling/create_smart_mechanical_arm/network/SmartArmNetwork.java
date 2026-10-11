package io.github.Ling.create_smart_mechanical_arm.network;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 网络负载注册（mod 总线）。 */
@EventBusSubscriber(modid = Create_smart_mechanical_arm.MODID, bus = EventBusSubscriber.Bus.MOD)
public class SmartArmNetwork {

	@SubscribeEvent
	public static void register(RegisterPayloadHandlersEvent event) {
		event.registrar("1")
			.playToServer(CardEditPacket.TYPE, CardEditPacket.STREAM_CODEC, CardEditPacket::handle)
			.playToServer(ArmProgramEditPacket.TYPE, ArmProgramEditPacket.STREAM_CODEC, ArmProgramEditPacket::handle)
			.playToServer(CardUiPacket.TYPE, CardUiPacket.STREAM_CODEC, CardUiPacket::handle)
			.playToServer(CardNotesPacket.TYPE, CardNotesPacket.STREAM_CODEC, CardNotesPacket::handle);
	}

}
