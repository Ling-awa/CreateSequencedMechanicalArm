package io.github.Ling.create_smart_mechanical_arm.interaction;

import com.simibubi.create.AllSoundEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 动作反馈（音效 + 粒子）。全部<b>照抄</b> Create 对应路径，不自己发明观感。
 *
 * <ul>
 *   <li><b>DEPLOY</b> → {@code BeltDeployerCallbacks.activate:162}：只有一声
 *       {@code ITEM_PICKUP}(.25f/.75f)，即原版字幕"物品：被拾起"的那声"啵"；
 *       砂纸抛光配方追加 {@code AllSoundEvents.SANDING_SHORT}(.35f/1f)。
 *       粒子来自 {@code DeployerBlockEntity.read:393-397} —— 上游把"被加工物"的物品栈同步给
 *       客户端后调用 {@code SandPaperItem.spawnParticles}（20 个 {@code ItemParticleOption}，
 *       初速 ±1/8）。本类改成服务端 {@code sendParticles} 直接发（同样的数量与量级，
 *       位置精确落在作用点、初速用同量级高斯分布），省掉一条同步通道。</li>
 *   <li><b>TAKE</b> → {@code ArmBlockEntity.collectItem:398-400}：原版动力臂的取物声，
 *       {@code ITEM_PICKUP}(.125f，音高 .5~.75 随机)。上游的守卫是"<b>物品种类变了才响</b>"，
 *       避免循环取同一种物品时刷屏 —— 这里保持同样的守卫。</li>
 * </ul>
 *
 * <p><b>刻意不做的一件事</b>：上游 {@code BeltDeployerCallbacks:160-161} 在"手上的应用物被用尽"
 * 时会额外播一声 {@code SoundEvents.ITEM_BREAK}。我们<b>不播</b>（用户实测判定为错，见 PLAN.md §5.4）：
 * Create 的 {@code MECHANICAL_PRESS_ACTIVATION}（中文字幕"冲压机：工作中"）就是
 * {@code ANVIL_LAND + ITEM_BREAK} 两件套（{@code AllSoundEvents.java:74-78}），所以这一声听起来
 * 就是冲压机的"锵"，会把"啵"整个盖掉 —— 用 {@code /smartarm hold} 只放 1 个应用物时必现。
 *
 * <p>不在这里的：机械手语义的 USE / ATTACK / MINE。它们的音效由方块/物品自身产生，
 * 上游 {@code DeployerHandler} 里也只有两条额外音效（挥击命中、砂纸），
 * 已分别在 {@link RightClickBridge} 与 {@code DeployerHandler} 内部触发，
 * 不需要本类重复。
 */
public final class ArmEffects {

	/** 粒子数量（对齐 {@code SandPaperItem.spawnParticles}）。 */
	private static final int ITEM_PARTICLE_COUNT = 20;
	/** 初速量级：上游是每轴 ±1/8 的均匀分布，这里用同样 σ 的高斯分布。 */
	private static final double ITEM_PARTICLE_SPEED = 0.125;

	private ArmEffects() {
	}

	/**
	 * DEPLOY 成功后的反馈。
	 *
	 * @param soundAt     音效位置（上游机械手/传送带都在机器方块上响）
	 * @param particleAt  粒子位置（上游在机械手<b>前端</b>冒粒子，这里给爪子/作用点）
	 * @param processed   被加工物（粒子的贴图来源，与上游一致）
	 * @param sanding     命中的是否是砂纸抛光配方
	 */
	public static void deploy(Level level, BlockPos soundAt, Vec3 particleAt, ItemStack processed, boolean sanding) {
		if (!(level instanceof ServerLevel server))
			return;
		server.playSound(null, soundAt, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, .25f, .75f);
		if (sanding)
			AllSoundEvents.SANDING_SHORT.playOnServer(server, soundAt, .35f, 1f);
		itemParticles(server, particleAt, processed);
	}

	/**
	 * 取物反馈（原版动力臂语义）。守卫与 {@code ArmBlockEntity.collectItem} 相同：
	 * <b>只有物品种类变化才响</b>，否则一次"循环取同一种物品"会每轮都响一遍。
	 *
	 * @param before 取之前的臂内手持物
	 * @param after  取之后的臂内手持物
	 */
	public static void pickup(Level level, BlockPos at, ItemStack before, ItemStack after) {
		if (!(level instanceof ServerLevel server))
			return;
		if (ItemStack.isSameItem(before, after))
			return;
		// 音高随机区间照抄上游（上游用 Create.RANDOM，这里用世界随机源，效果等价）
		server.playSound(null, at, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, .125f,
			.5f + server.random.nextFloat() * .25f);
	}

	/** 物品碎屑粒子。传 {@code ServerLevel} 是有意的 —— 客户端世界没有 {@code sendParticles}。 */
	public static void itemParticles(Level level, Vec3 at, ItemStack stack) {
		if (!(level instanceof ServerLevel server) || stack.isEmpty())
			return;
		server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack), at.x, at.y, at.z,
			ITEM_PARTICLE_COUNT, 0, 0, 0, ITEM_PARTICLE_SPEED);
	}

}
