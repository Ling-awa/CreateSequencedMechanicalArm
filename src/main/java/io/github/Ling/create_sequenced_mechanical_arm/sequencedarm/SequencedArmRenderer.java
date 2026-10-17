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

	/**
	 * 臂的全部动态部件（齿轮 + 底座 + 上下臂 + 爪座 + 两片爪）统一使用的渲染类型。
	 *
	 * <p><b>为什么必须恒定用 cutout</b>：用户要求「完全透明的部分任何时候都要正确渲染」，
	 * 不许依赖护目镜状态。{@code cutout} 的像素着色器会丢弃 {@code alpha < 0.1} 的片元
	 * ⇒ 贴图里的全透明像素**不画**，露出后面的东西；{@code solid} 没有 alpha 测试
	 * ⇒ 全透明像素被写成不透明（表现为黑块 / 实心）。
	 *
	 * <p><b>为什么不靠模型 json 的 {@code "render_type"}</b>：这些部件走的是
	 * {@code CachedBuffers.partial(...)} → {@code SuperByteBuffer.renderInto(ms, VertexConsumer)}，
	 * 几何 / uv / 贴图来自烘焙模型，但**写进哪个 RenderType 的 buffer 完全由这里传进去的
	 * VertexConsumer 决定**；模型 json 的 {@code render_type} 只在"以烘焙模型默认渲染类型去画"
	 * 时才生效（区块渲染、{@code BlockRenderDispatcher}），对显式 {@code renderInto} 无效。
	 * 所以补 json 键治不了这个现象 —— 只有这条线选 cutout 才治。
	 *
	 * <p>⚠ 上游 {@code ArmRenderer.java:52} 是 {@code be.goggles ? RenderType.cutout() : RenderType.solid()}，
	 * 我们**有意偏离**：那个三元表达式正是"只有戴护目镜时才透明"的来源。
	 */
	private static final RenderType ARM_RENDER_TYPE = RenderType.cutout();

	public SequencedArmRenderer(BlockEntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	protected SuperByteBuffer getRotatedModel(SequencedArmBlockEntity be, BlockState state) {
		return CachedBuffers.partial(ModPartialModels.ARM_COG.model, state);
	}

	/**
	 * 齿轮（即 {@code super.renderSafe} 在"没有 Flywheel"时自己画的那一份）也固定 cutout —— 同一个理由。
	 *
	 * <p>上游 {@code KineticBlockEntityRenderer:61-70} 的实现是"按方块模型声明的渲染类型挑，兜底
	 * {@code cutoutMipped}"；对"贴图里有全透明像素"这件事，挑到 solid 的后果和臂身那几个部件一样
	 * （黑块），所以这里不再推断，直接钉住。贴图完全不透明时 cutout 与 solid 的像素结果一致。
	 */
	@Override
	protected RenderType getRenderType(SequencedArmBlockEntity be, BlockState state) {
		return ARM_RENDER_TYPE;
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
			// 齿轮也是动态部件，贴图里的全透明像素同样不能被画成黑块 ⇒ 与其余部件共用 cutout。
			KineticBlockEntityRenderer.renderRotatingBuffer(be, getRotatedModel(be, state), ms,
				buffer.getBuffer(getRenderType(be, state)), light);
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

		// 渲染类型**不再跟护目镜走**：用户要求透明部分总是正确渲染（详见 ARM_RENDER_TYPE 的注释）。
		// 护目镜现在只影响"用哪个爪座模型"（下面 be.goggles ? ... : ... 那一处），不再影响"透明怎么画"。
		VertexConsumer vb = buffer.getBuffer(ARM_RENDER_TYPE);
		PoseStack local = new PoseStack();
		TransformStack msr = TransformStack.of(local);
		msr.center();
		if (inverted)
			msr.rotateXDegrees(180);

		ArmRenderer.transformBase(msr, base);
		// 底座：**信号活动时换成 base_powered**（用户要求：臂发出 / 接收信号时用这个模型，否则用 base）。
		// 两个模型是同一个底座（只有红石天线换成点亮的贴图）⇒ 这里只挑模型，变换、姿态、渲染类型一概不动。
		// 判据来自 be.isSignalActive()（服务端算好、随既有同步包下发的字段，客户端不重算）。
		CachedBuffers.partial(
			(be.isSignalActive() ? ModPartialModels.ARM_BASE_POWERED : ModPartialModels.ARM_BASE).model, state)
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
