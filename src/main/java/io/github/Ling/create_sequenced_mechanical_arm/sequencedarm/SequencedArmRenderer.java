package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmRenderer;

import io.github.Ling.create_sequenced_mechanical_arm.client.ModPartialModels;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 臂身渲染：复用 Create 的机械臂部件变换，绘制<b>我们自己的</b>动态部件模型与贴图。
 *
 * <p>{@code ArmRenderer.transformBase/LowerArm/UpperArm/Head/ClawHalf} 都是 public static，
 * 这里只负责"喂自己的角度"，<b>不复制变换数学</b>（那几个 rotate/translate 常数没有美术，
 * 抄一份只会和上游姿态漂移）。
 *
 * <p>部件模型走 {@link ModPartialModels}（{@code block/sequenced_arm/*}）而不是 Create 的
 * {@code AllPartialModels.ARM_*}：用户要求资源自持 —— 静态方块模型（第 2 片）与这些会动的部件
 * 必须是同一套贴图，否则会出现"底座换了、关节和爪子还是 Create 的画"。
 *
 * <p>阶段 1 的取舍：末端先用动力臂自带的爪子（claw）——它的几何是为这个位置量身做的，
 * 保证摆位正确。换成机械手的手（{@code DEPLOYER_HAND_*}）需要对着画面调偏移，
 * 留到视觉阶段做（PLAN.md 阶段 1 说明）。
 */
public class SequencedArmRenderer extends KineticBlockEntityRenderer<SequencedArmBlockEntity> {

	public SequencedArmRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected SuperByteBuffer getRotatedModel(SequencedArmBlockEntity be, BlockState state) {
		return CachedBuffers.partial(ModPartialModels.ARM_COG.model, state);
	}

	@Override
	protected void renderSafe(SequencedArmBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		BlockState state = be.getBlockState();
		super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

		// Flywheel 接管时基类不渲染动力部件（KineticBlockEntityRenderer:50 直接早退），
		// 而 Flywheel 视觉我们还没写 —— 这里自己把旋转的齿轮补上，否则臂身上看不见传动。
		// （两边的条件互斥，不会重复绘制。）
		if (VisualizationManager.supportsVisualization(be.getLevel())) {
			KineticBlockEntityRenderer.renderRotatingBuffer(be, getRotatedModel(be, state), ms,
				buffer.getBuffer(RenderType.solid()), light);
		}

		boolean inverted = SequencedArmBlock.isCeiling(state);

		// 与 ArmRenderer 相同的取值约定：lower/upper 各减 135/90
		float base = be.baseAngle.getValue(partialTicks);
		float lower = be.lowerArmAngle.getValue(partialTicks) - 135;
		float upper = be.upperArmAngle.getValue(partialTicks) - 90;
		float head = be.headAngle.getValue(partialTicks);

		boolean hasItem = !be.heldItem.isEmpty();
		ItemRenderer itemRenderer = Minecraft.getInstance()
			.getItemRenderer();
		BakedModel bakedModel = itemRenderer.getModel(be.heldItem, be.getLevel(), null, 0);
		boolean isBlockItem = hasItem && be.heldItem.getItem() instanceof BlockItem && bakedModel.isGui3d();

		// 渲染类型要跟着护目镜变：ARM_CLAW_BASE_GOGGLES 有透明区域，
		// 用 solid 画会把透明部分涂成纯黑（与 ArmRenderer.java:52 的写法一致）。
		VertexConsumer vb = buffer.getBuffer(be.goggles ? RenderType.cutout() : RenderType.solid());
		PoseStack local = new PoseStack();
		TransformStack msr = TransformStack.of(local);
		msr.center();
		if (inverted)
			msr.rotateXDegrees(180);

		ArmRenderer.transformBase(msr, base);
		CachedBuffers.partial(ModPartialModels.ARM_BASE.model, state)
			.light(light)
			.transform(local)
			.renderInto(ms, vb);

		ArmRenderer.transformLowerArm(msr, lower);
		CachedBuffers.partial(ModPartialModels.ARM_LOWER_BODY.model, state)
			.light(light)
			.transform(local)
			.renderInto(ms, vb);

		ArmRenderer.transformUpperArm(msr, upper);
		CachedBuffers.partial(ModPartialModels.ARM_UPPER_BODY.model, state)
			.light(light)
			.transform(local)
			.renderInto(ms, vb);

		ArmRenderer.transformHead(msr, head);
		if (inverted)
			msr.rotateZDegrees(180);
		CachedBuffers.partial((be.goggles ? ModPartialModels.ARM_CLAW_BASE_GOGGLES : ModPartialModels.ARM_CLAW_BASE).model,
			state)
			.light(light)
			.transform(local)
			.renderInto(ms, vb);
		if (inverted)
			msr.rotateZDegrees(180);

		for (int flip : Iterate.positiveAndNegative) {
			local.pushPose();
			ArmRenderer.transformClawHalf(msr, hasItem, isBlockItem, flip);
			CachedBuffers.partial(
				(flip > 0 ? ModPartialModels.ARM_CLAW_GRIP_LOWER : ModPartialModels.ARM_CLAW_GRIP_UPPER).model, state)
				.light(light)
				.transform(local)
				.renderInto(ms, vb);
			local.popPose();
		}

		if (hasItem) {
			ms.pushPose();
			float itemScale = isBlockItem ? .5f : .625f;
			msr.rotateXDegrees(90);
			local.translate(0, isBlockItem ? -9 / 16f : -10 / 16f, 0);
			local.scale(itemScale, itemScale, itemScale);

			ms.last()
				.pose()
				.mul(local.last()
					.pose());

			itemRenderer.render(be.heldItem, ItemDisplayContext.FIXED, false, ms, buffer, light, overlay, bakedModel);
			ms.popPose();
		}
	}

	@Override
	public boolean shouldRenderOffScreen(SequencedArmBlockEntity be) {
		return true;
	}

}
