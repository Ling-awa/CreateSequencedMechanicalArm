package io.github.Ling.create_smart_mechanical_arm.smartarm;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.client.ArmProgramScreen;
import io.github.Ling.create_smart_mechanical_arm.client.ClipboardChecklistOverlay;
import io.github.Ling.create_smart_mechanical_arm.client.OperationSelectionOverlay;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** 客户端注册（方块实体渲染器 + 菜单界面 + HUD 图层）。 */
@EventBusSubscriber(modid = Create_smart_mechanical_arm.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SmartArmClient {

	@SubscribeEvent
	public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(SmartArmRegistration.SMART_ARM_BE.get(), SmartArmRenderer::new);
	}

	@SubscribeEvent
	public static void registerScreens(RegisterMenuScreensEvent event) {
		event.register(SmartArmRegistration.ARM_PROGRAM_MENU.get(), ArmProgramScreen::new);
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
			ResourceLocation.fromNamespaceAndPath(Create_smart_mechanical_arm.MODID, "operation_selection"),
			OperationSelectionOverlay.INSTANCE);
		// 清单画在选择条**之后**（后注册的画在上面）：两者分别在屏幕左边与底部中间，
		// 正常不会重叠；真重叠时清单更靠边、也让清单在上，免得被选择条压掉一行。
		event.registerAboveAll(
			ResourceLocation.fromNamespaceAndPath(Create_smart_mechanical_arm.MODID, "clipboard_checklist"),
			ClipboardChecklistOverlay.INSTANCE);
	}

}
