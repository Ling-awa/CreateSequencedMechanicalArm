package io.github.Ling.create_sequenced_mechanical_arm.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 取物点访问器。
 *
 * <p>为什么需要 Mixin：{@code ArmInteractionPoint.getInteractionPositionVector()} 与
 * {@code getInteractionDirection()} 是 <b>protected</b>，而各点类型正是靠覆写这两个方法来表达
 * "从哪个位置、以什么朝向交互"（例如 {@code DepotPoint} 是方块上方 14/16、
 * {@code FunnelPoint} 是朝漏斗面偏移 0.15、{@code DeployerPoint} 是沿朝向偏移 0.65）。
 * 拿不到它们，就只能退化成"方块中心 + 朝下"，手臂顶端会插进方块里 —— 这正是阶段 1 的现象。
 *
 * <p>用 {@code @Invoker} 而不是"往 Create 的包里塞桥接类"：后者在 NeoForge 的模块层下
 * 会造成 split package，有启动期即崩的风险。
 */
@Mixin(ArmInteractionPoint.class)
public interface ArmInteractionPointAccessor {

	@Invoker("getInteractionPositionVector")
	Vec3 create$getInteractionPositionVector();

	@Invoker("getInteractionDirection")
	Direction create$getInteractionDirection();

}
