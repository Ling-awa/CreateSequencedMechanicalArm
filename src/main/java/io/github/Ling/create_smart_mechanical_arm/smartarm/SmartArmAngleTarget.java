package io.github.Ling.create_smart_mechanical_arm.smartarm;

import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 两节 IK 解算，数值与 Create 6.0.10 的
 * {@code com.simibubi.create.content.kinetics.mechanicalArm.ArmAngleTarget} 完全一致。
 *
 * <p>为什么复制而不是复用：Create 那边四个角度字段是包私有（无修饰符），
 * 从别的包读不出来，而我们需要把它们喂给自己的渲染器。Create 是 MIT 许可，来源已注明。
 * 若上游改了臂长/算法，这里需要跟着更新（臂长常量 14/16 与 15/16 见下）。
 */
public class SmartArmAngleTarget {

	/** 静止姿态：Create {@code ArmAngleTarget()} 私有构造器里的那一组数值。 */
	public static final SmartArmAngleTarget RESTING = new SmartArmAngleTarget();

	public final float baseAngle;
	public final float lowerArmAngle;
	public final float upperArmAngle;
	public final float headAngle;

	private SmartArmAngleTarget() {
		this.baseAngle = 0;
		this.lowerArmAngle = 135;
		this.upperArmAngle = 45;
		this.headAngle = 0;
	}

	/** 直接给四个角度（不算 IK）—— 用来表示"手臂现在实际在什么姿态"。 */
	private SmartArmAngleTarget(float baseAngle, float lowerArmAngle, float upperArmAngle, float headAngle) {
		this.baseAngle = baseAngle;
		this.lowerArmAngle = lowerArmAngle;
		this.upperArmAngle = upperArmAngle;
		this.headAngle = headAngle;
	}

	/**
	 * 快照当前姿态：插值动画的**起点**必须是"手臂现在在哪"，而不是猜出来的位置。
	 *
	 * <p>用户实测（第十九/二十二轮）："等信号的时候动画异常，上一个动作没有结束就立马瞬移回到原位。"
	 * 原因是客户端在"新一次移动"开始时按相位猜起点：旧相位是 MOVING 就用旧目标的姿态，
	 * 否则一律当成静止位 —— 而「等待」会先 {@code cancelMove()}（相位变 IDLE，手臂正在折返途中），
	 * 等它结束时新移动就"从静止位起步"，于是折返没做完的手臂被**瞬间**摆回静止位。
	 */
	public static SmartArmAngleTarget of(float baseAngle, float lowerArmAngle, float upperArmAngle, float headAngle) {
		return new SmartArmAngleTarget(baseAngle, lowerArmAngle, upperArmAngle, headAngle);
	}

	public SmartArmAngleTarget(BlockPos armPos, Vec3 pointTarget, Direction clawFacing, boolean ceiling) {
		Vec3 target = pointTarget;
		Vec3 origin = VecHelper.getCenterOf(armPos)
			.add(0, ceiling ? -6 / 16f : 6 / 16f, 0);
		Vec3 clawTarget = target;
		target = target.add(Vec3.atLowerCornerOf(clawFacing.getOpposite()
			.getNormal())
			.scale(.5f));

		Vec3 diff = target.subtract(origin);
		float horizontalDistance = (float) diff.multiply(1, 0, 1)
			.length();

		float baseAngle = AngleHelper.deg(Mth.atan2(diff.x, diff.z)) + 180;
		if (ceiling) {
			diff = diff.multiply(1, -1, 1);
			baseAngle = 180 - baseAngle;
		}

		float alphaOffset = AngleHelper.deg(Mth.atan2(diff.y, horizontalDistance));

		float a = 14 / 16f; // lower arm length
		float a2 = a * a;
		float b = 15 / 16f; // upper arm length
		float b2 = b * b;
		float diffLength =
			Mth.clamp(Mth.sqrt((float) (diff.y * diff.y + horizontalDistance * horizontalDistance)), 1 / 8f, a + b);
		float diffLength2 = diffLength * diffLength;

		float alphaRatio = (-b2 + a2 + diffLength2) / (2 * a * diffLength);
		float alpha = AngleHelper.deg(Math.acos(alphaRatio)) + alphaOffset;
		float betaRatio = (-diffLength2 + a2 + b2) / (2 * b * a);
		float beta = AngleHelper.deg(Math.acos(betaRatio));

		if (Float.isNaN(alpha))
			alpha = 0;
		if (Float.isNaN(beta))
			beta = 0;

		Vec3 headPos = new Vec3(0, 0, 0);
		headPos = VecHelper.rotate(headPos.add(0, b, 0), beta + 180, Axis.X);
		headPos = VecHelper.rotate(headPos.add(0, a, 0), alpha - 90, Axis.X);
		headPos = VecHelper.rotate(headPos, baseAngle, Axis.Y);
		headPos = VecHelper.rotate(headPos, ceiling ? 180 : 0, Axis.X);
		headPos = headPos.add(origin);
		Vec3 headDiff = clawTarget.subtract(headPos);

		if (ceiling)
			headDiff = headDiff.multiply(1, -1, 1);

		float horizontalHeadDistance = (float) headDiff.multiply(1, 0, 1)
			.length();
		float headAngle = (float) (alpha + beta + 135 - AngleHelper.deg(Mth.atan2(headDiff.y, horizontalHeadDistance)));

		this.lowerArmAngle = alpha;
		this.upperArmAngle = beta;
		this.headAngle = -headAngle;
		this.baseAngle = baseAngle;
	}

}
