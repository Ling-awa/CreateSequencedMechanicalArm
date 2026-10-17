package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.client.ArmProgramScreen;
import io.github.Ling.create_sequenced_mechanical_arm.client.ClipboardChecklistOverlay;
import io.github.Ling.create_sequenced_mechanical_arm.client.OperationSelectionOverlay;
import io.github.Ling.create_sequenced_mechanical_arm.client.ProgramImportScreen;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** 客户端注册（方块实体渲染器 + 菜单界面 + HUD 图层）。 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SequencedArmClient {

	@SubscribeEvent
	public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(SequencedArmRegistration.SEQUENCED_ARM_BE.get(), SequencedArmRenderer::new);
	}

	@SubscribeEvent
	public static void registerScreens(RegisterMenuScreensEvent event) {
		event.register(SequencedArmRegistration.ARM_PROGRAM_MENU.get(), ArmProgramScreen::new);
		// 操作导入桌：界面与贴图复用蓝图桌那套（用户口径）；列表来自**玩家本机**的
		// sequenced_arm/scripts/（客户端自己扫，服务端不碰文件），内容则由客户端分片上传
		event.register(SequencedArmRegistration.PROGRAM_IMPORT_MENU.get(), ProgramImportScreen::new);
	}

	/**
	 * 「当前操作」选择条（复刻 Create 部署蓝图那条 HUD）+「副手剪贴板清单」。
	 *
	 * <p>{@code registerAboveAll} 而不是塞进 {@code RenderGuiEvent.Post}：这样它就是标准的 HUD 图层，
	 * 跟着原版 GUI 一起被 {@code F1}（{@code options.hideGui}）与其它图层的可见性规则管，
	 * 也是 Create 自己注册 {@code SchematicHandler} 的同一条路。
	 */
	@SubscribeEvent
	public static void registerGuiLayers(RegisterGuiLayersEvent event) {
		event.registerAboveAll(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "operation_selection"),
			OperationSelectionOverlay.INSTANCE);
		// 清单画在选择条**之后**（后注册的画在上面）：两者分别在屏幕左边与底部中间，
		// 正常不会重叠；真重叠时清单更靠边、也让清单在上，免得被选择条压掉一行。
		event.registerAboveAll(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "clipboard_checklist"),
			ClipboardChecklistOverlay.INSTANCE);
	}

}
