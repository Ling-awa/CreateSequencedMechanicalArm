package io.github.Ling.create_sequenced_mechanical_arm.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 本模组**自己的**动态渲染部件（partial models）—— 只有臂的渲染真的用到的那 9 个
 * （用户要求资源自持：静态模型、动态部件、贴图一次性都能自己改）。
 * 其中 {@link #ARM_BASE_POWERED} 是用户后加的「信号活动」底座变体（同几何、只换天线贴图），
 * 其余 8 个与 Create 的 {@code mechanical_arm/*} 一一对应。
 *
 * <h2>为什么要这一层</h2>
 * {@code SequencedArmRenderer} 原来直接用 Create 的 {@code AllPartialModels.ARM_*}，
 * 那几个常量的模型是 {@code create:block/mechanical_arm/base} 之类 ——
 * <b>几何与贴图都在别人 jar 里，用户改不了</b>：只替换方块模型（第 2 片做的 {@code block/sequenced_arm/block.json}）
 * 时，臂的底座 + 关节 + 爪子这些"会动的部分"仍然是 Create 的美术，看起来就是"底座换了、手臂没换"。
 * 所以那 8 个 JSON（加上用户后补的 {@code base_powered} 共 9 个）已整份复制进
 * {@code assets/create_sequenced_mechanical_arm/models/block/sequenced_arm/}，贴图引用全部改指我们自己
 * （{@code sequenced_arm} / {@code _brass} / {@code _casing} / {@code _cogwheel} / {@code _goggles} / {@code _pulley}），
 * 这里把文件名钉成常量，渲染类只认这里。
 *
 * <h2>命名与 Create 一一对应（改渲染时是机械替换）</h2>
 * 常量名与 {@code AllPartialModels} 完全相同，只有模型路径换成了我们的；指向关系：
 * <pre>
 * ARM_COG             ← create:block/mechanical_arm/cog              → block/sequenced_arm/cog
 * ARM_BASE            ← create:block/mechanical_arm/base             → block/sequenced_arm/base
 * ARM_BASE_POWERED    ← （无上游对应）信号活动时的底座变体 → block/sequenced_arm/base_powered
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
 * <h2>⚠ 必须自己注册，否则<b>全部部件都会变成"缺失模型"</b></h2>
 * 这 9 个 json 不被任何 {@code blockstates/*.json} 或物品模型引用，而 1.21.1 的
 * {@code ModelBakery} <b>只烘焙"顶层模型"</b>：方块状态模型 + 物品模型 + 通过
 * {@code ModelEvent.RegisterAdditional} 显式登记的模型（见 {@code ModelBakery} 构造器
 * {@code ClientHooks.onRegisterAdditionalModels} 那一支 + {@code bakeModels} 只遍历
 * {@code topLevelModels}）。没登记的 json <b>能解析但永远不会被烘焙</b>，
 * 于是 {@code ModelManager.getModel(standalone(...))} 静默返回 {@code missingModel}：
 * 臂的每一节都画成一整块"紫黑缺失贴图"，而且<b>日志里一个字都不会有</b>。
 *
 * <p>所以本枚举用 {@link #registerAdditional} 把这 9 个
 * {@link ModelResourceLocation#standalone} 登记进 {@code RegisterAdditional}。
 * 直接照 {@code create:block/mechanical_arm/*} 写而不登记是不行的 —— 上游那 8 个不是靠
 * {@code blockstates} 进烘焙的：Create 自己一个 {@code RegisterAdditional} 监听都没有，
 * 它们只由 Flywheel 的 {@code PartialModel} 体系登记（间接证据：{@code CreateClient} 的
 * {@code clientInit} 里那句 {@code AllPartialModels.init()}，注释就是"init static fields"，
 * 目的就是先把类初始化、让 {@code PartialModel.of(...)} 都跑过一遍，才轮得到烘焙）。
 * 我们自己登记一份就不必赌那套内部的登记时机。
 *
 * <p>{@link #location} 由本枚举自己记一份：{@code PartialModel} 的访问器叫什么名字没有从 dump 里核到，
 * 我们自己存着就不用赌那个 API（渲染只需要 {@link #model}）。
 * 模型路径 = {@code <modid>:block/sequenced_arm/<文件名>}，与 {@code blockstates/sequenced_arm.json}
 * 引用的静态模型同目录（{@code block/sequenced_arm/block}），用户一个目录里就能改全套。
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public enum ModPartialModels {

	ARM_COG("cog"),
	ARM_BASE("base"),
	/**
	 * 「信号活动」时的底座变体 —— 用户新做的 {@code base_powered.json}。
	 *
	 * <p><b>为什么单独一项</b>：它是 {@link #ARM_BASE} 的<b>信号态变体</b> —— 底座本体（Joint 1 那块）与
	 * 上方那一小块的几何/uv 完全一致，差别在红石天线：贴图从 {@code redstone_antenna} 换成点亮的
	 * {@code redstone_antenna_powered}，且天线拆成了 X / Z / Top 三块（形状与 base 的那一根略有出入）。
	 * ⇒ "换模型"就等于"天线亮不亮"，不需要第二个开关或第二套渲染管线。
	 *
	 * <p><b>什么时候用</b>：{@code SequencedArmBlockEntity.isSignalActive()}（口径与理由都在那个方法的注释里）。
	 * 那个字段是服务端算好、随既有同步包下发的，渲染器直接读，不在客户端重算。
	 *
	 * <p>加了这一项之后 {@link #registerAdditional} 的 {@code values()} 循环会自动登记它 ——
	 * 这份清单**不是**手写的，不需要在别处补一行。
	 */
	ARM_BASE_POWERED("base_powered"),
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

	/**
	 * 把这 9 个模型登记为"顶层模型"，否则它们不会被烘焙（详见类注释）。
	 *
	 * <p>必须用 {@link ModelResourceLocation#standalone}：{@code RegisterAdditional.register}
	 * 会对 variant 做断言（只接受 {@code "standalone"}），而
	 * {@code PartialModel.of(ResourceLocation)} / {@code CachedBuffers.partial(...)}
	 * 查的也正是这个 standalone 键。
	 *
	 * <p>顺带解决了初始化时机：{@code values()} 在这里才第一次真正跑 {@code PartialModel.of(...)}，
	 * 而本事件在 {@code ModelBakery} 构造期间、烘焙之前派发 —— 正好赶在 {@code bakeModels()} 前面。
	 */
	@SubscribeEvent
	public static void registerAdditional(ModelEvent.RegisterAdditional event) {
		for (ModPartialModels partial : values())
			event.register(ModelResourceLocation.standalone(partial.location));
	}

}
