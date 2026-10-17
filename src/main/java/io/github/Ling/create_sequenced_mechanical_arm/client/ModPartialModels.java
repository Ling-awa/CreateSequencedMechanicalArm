package io.github.Ling.create_sequenced_mechanical_arm.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

import net.minecraft.resources.ResourceLocation;

/**
 * 本模组**自己的**动态渲染部件（partial models）—— 只有臂的渲染真的用到的那 8 个
 * （用户要求资源自持：静态模型、动态部件、贴图一次性都能自己改）。
 *
 * <h2>为什么要这一层</h2>
 * {@code SequencedArmRenderer} 原来直接用 Create 的 {@code AllPartialModels.ARM_*}，
 * 那几个常量的模型是 {@code create:block/mechanical_arm/base} 之类 ——
 * <b>几何与贴图都在别人 jar 里，用户改不了</b>：只替换方块模型（第 2 片做的 {@code block/sequenced_arm/block.json}）
 * 时，臂的底座 + 关节 + 爪子这些"会动的部分"仍然是 Create 的美术，看起来就是"底座换了、手臂没换"。
 * 所以那 8 个 JSON 已整份复制进
 * {@code assets/create_sequenced_mechanical_arm/models/block/sequenced_arm/}，贴图引用全部改指我们自己
 * （{@code sequenced_arm} / {@code _brass} / {@code _casing} / {@code _cogwheel} / {@code _goggles} / {@code _pulley}），
 * 这里把文件名钉成常量，渲染类只认这里。
 *
 * <h2>命名与 Create 一一对应（改渲染时是机械替换）</h2>
 * 常量名与 {@code AllPartialModels} 完全相同，只有模型路径换成了我们的；指向关系：
 * <pre>
 * ARM_COG             ← create:block/mechanical_arm/cog              → block/sequenced_arm/cog
 * ARM_BASE            ← create:block/mechanical_arm/base             → block/sequenced_arm/base
 * ARM_LOWER_BODY      ← create:block/mechanical_arm/lower_body       → block/sequenced_arm/lower_body
 * ARM_UPPER_BODY      ← create:block/mechanical_arm/upper_body       → block/sequenced_arm/upper_body
 * ARM_CLAW_BASE       ← create:block/mechanical_arm/claw_base        → block/sequenced_arm/claw_base
 * ARM_CLAW_BASE_GOGGLES ← create:block/mechanical_arm/claw_base_goggles → block/sequenced_arm/claw_base_goggles
 * ARM_CLAW_GRIP_UPPER ← create:block/mechanical_arm/upper_claw_grip  → block/sequenced_arm/upper_claw_grip
 * ARM_CLAW_GRIP_LOWER ← create:block/mechanical_arm/lower_claw_grip  → block/sequenced_arm/lower_claw_grip
 * </pre>
 *
 * <h2>刻意<b>不</b>搬的东西</h2>
 * <ul>
 *   <li><b>变换数学</b>：{@code ArmRenderer.transformBase/LowerArm/UpperArm/Head/ClawHalf} 继续借用 Create ——
 *       它们只有几个 {@code rotate/translate} 常数，没有美术，抄一份反而会和上游的姿态漂移；</li>
 *   <li>上游另有一份 {@code ArmVisual}（Flywheel 视觉）我们没写，所以不涉及；</li>
 *   <li>{@code ArmIcons} 的物品图标不属于渲染部件，不在本类范围。</li>
 * </ul>
 *
 * <p>{@link #location} 由本枚举自己记一份：{@code PartialModel} 的访问器叫什么名字没有从 dump 里核到，
 * 我们自己存着就不用赌那个 API（渲染只需要 {@link #model}）。
 * 模型路径 = {@code <modid>:block/sequenced_arm/<文件名>}，与 {@code blockstates/sequenced_arm.json}
 * 引用的静态模型同目录（{@code block/sequenced_arm/block}），用户一个目录里就能改全套。
 */
public enum ModPartialModels {

	ARM_COG("cog"),
	ARM_BASE("base"),
	ARM_LOWER_BODY("lower_body"),
	ARM_UPPER_BODY("upper_body"),
	ARM_CLAW_BASE("claw_base"),
	/** 戴护目镜时的爪座（上游是 {@code hat_test} 那张半透明贴图，走 cutout 渲染）。 */
	ARM_CLAW_BASE_GOGGLES("claw_base_goggles"),
	ARM_CLAW_GRIP_UPPER("upper_claw_grip"),
	ARM_CLAW_GRIP_LOWER("lower_claw_grip");

	/** 模型位置（{@code create_sequenced_mechanical_arm:block/sequenced_arm/*}），也是贴图自持的锚点。 */
	public final ResourceLocation location;
	/** 交给 {@code CachedBuffers.partial(...)} 的那一层包装（flywheel 的 {@code PartialModel}）。 */
	public final PartialModel model;

	ModPartialModels(String path) {
		this.location = ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID,
			"block/sequenced_arm/" + path);
		this.model = PartialModel.of(location);
	}

}
