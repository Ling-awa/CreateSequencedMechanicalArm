package io.github.Ling.create_smart_mechanical_arm.interaction;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.HashMultimap;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.deployer.DeployerHandler;
import com.simibubi.create.foundation.utility.BlockHelper;

import io.github.Ling.create_smart_mechanical_arm.smartarm.SmartArmBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;

/**
 * 机械手语义的左右键执行体（PLAN.md §4.3 的 {@code RightClickBridge}）。
 *
 * <p>编排逻辑抄自 Create 6.0.10 的 {@code DeployerHandler.activateInner}（MIT），
 * 因为那个方法对 {@code ArmBlockEntity} 之外的方块实体不可调用；能直接复用的公开部分
 * （{@link DeployerHandler#tryHarvestBlock}、{@link DeployerHandler#safeOnUse}）就直接调。
 *
 * <p><b>与上游的三处有意差异</b>：
 * <ol>
 *   <li><b>允许空手破坏</b>：上游在 {@code DeployerHandler.java:257} 对空手提前 return，
 *       我们按决策去掉那一步（行为由操作表定义，不需要防拆家）。</li>
 *   <li><b>破坏进度自己存</b>：上游存在 {@code DeployerFakePlayer.blockBreakingProgress}，
 *       那是包私有字段，外部读不到。</li>
 *   <li><b>没有 {@code ItemUseWorld} 假墙</b>：上游对桶/砂纸会用一层假 bedrock 限制作用范围，
 *       因为那些物品会自己做射线追踪。本阶段没抄这块，因此<b>桶类物品可能作用于射线更远处</b>。
 *       需要时照 {@code DeployerHandler} 的 {@code ItemUseWorld}（约 35 行）补上。</li>
 * </ol>
 */
public class RightClickBridge {

	public enum Result {
		/** 动作完成（挖掘还可能是"这一击没破，继续"）。 */
		SUCCESS,
		/** 还没完成，但也没失败：挖掘需要再挥一次。 */
		WAITING,
		/**
		 * <b>暂时</b>做不了：世界未加载、假玩家还没就绪 —— 属于基础设施，等一会儿就会自愈，
		 * 所以交给运行时"原地重试"（这也是 {@code timeout} 时限的用武之地）。
		 */
		RETRY,
		/**
		 * <b>硬失败</b>：这件事根本做不成（方块挖不动 / 无权交互 / 领地保护拒绝 / 目标没有碰撞形状）。
		 *
		 * <p>为什么必须和 {@link #RETRY} 分开：原地重试只对"还没好"有意义。硬失败重试 = 臂对着基岩
		 * 永远空挥（实测过），右键更糟 —— 右键**不幂等**，重试会把拉杆反复拨回来。
		 * 运行时收到这一态会<b>立刻</b>走条目自己的失败出口（{@code onTimeout}）。
		 */
		HARD_FAIL
	}

	private final SmartArmBlockEntity arm;

	@Nullable
	private BlockPos breakingPos;
	private float breakingProgress;
	@Nullable
	private String lastFailure;

	public RightClickBridge(SmartArmBlockEntity arm) {
		this.arm = arm;
	}

	@Nullable
	public String getLastFailure() {
		return lastFailure;
	}

	public void clearFailure() {
		lastFailure = null;
	}

	// ————————————————————————— 左键：攻击 / 挖掘（v27 拆成两条） —————————————————————————

	/**
	 * 攻击：对着那一格挥一击，打格里的一只实体。
	 *
	 * <p><b>与机械手逐字一致</b>（上游 `DeployerHandler.activateInner:165-220`）：唯一筛选是"不是活动结构实体"，
	 * 随后随机挑一只 `player.attack(entity)`。**不判断格里有没有实体**（用户第二十七轮："不检测有没有实体"）：
	 * 挥空也是挥了一击，这一条算完成 —— 想等怪物走进来，就在前面加一条「等待」。
	 *
	 * <p>注意它**永远返回完成**（除了世界/假玩家还没就绪那种基础设施重试）：所以编辑器不给它「失败后」
	 * （见 `OperationType.canFail()`）。
	 */
	public Result attack(BlockPos pos, Direction face) {
		if (!(arm.getLevel() instanceof ServerLevel level))
			return retry("世界未加载");
		DeployerFakePlayer player = arm.getFakePlayer();
		if (player == null)
			return retry("假玩家未就绪");
		arm.prepareFakePlayerHand();
		// ★ 必须先把假玩家摆到录下来的那一面之外：击退方向是 vanilla 从"攻击来源位置 − 实体位置"算的
		//（见 positionPlayer 的说明）。少了这一句，假玩家停在默认站位，击退方向与臂的朝向完全无关 ——
		// 用户实测："击退方向似乎总是 -x -z"。
		positionPlayer(player, pos, face);
		try {
			// 与机械手完全一致：只排除活动结构实体，别的什么都不筛
			List<Entity> entities = level.getEntitiesOfClass(Entity.class, new AABB(pos))
				.stream()
				.filter(e -> !(e instanceof AbstractContraptionEntity))
				.toList();
			if (!entities.isEmpty()) {
				Entity entity = entities.get(level.random.nextInt(entities.size()));
				var modifiers = applyAttributeModifiers(player, player.getMainHandItem());
				try {
					player.resetAttackStrengthTicker();
					player.attack(entity);
				} finally {
					removeAttributeModifiers(player, modifiers);
				}
			}
			// 格里没东西 = 挥空，也算完成（不在这里"检测"）
			resetBreakingProgress(level);
			return Result.SUCCESS;
		} finally {
			// 击杀掉落物由这一句收回臂内（Create 的假玩家会把掉落塞进自己背包）
			arm.syncHeldItemFromFakePlayer();
		}
	}

	/**
	 * 挖掘：**只挖方块，永远不碰实体** —— 这就是把它从旧 {@code PUNCH} 里拆出来的理由。
	 *
	 * <p>同样照机械手：**不在动身前判断"能不能挖动"**，挥下去，进度由 vanilla 的
	 * {@code getDestroyProgress} 给。破坏需要多击，所以"没破"返回 {@code WAITING}（运行时重跑一次挥击动画），
	 * 破了才 {@code SUCCESS}。
	 */
	public Result mine(BlockPos pos, Direction face) {
		if (!(arm.getLevel() instanceof ServerLevel level))
			return retry("世界未加载");
		DeployerFakePlayer player = arm.getFakePlayer();
		if (player == null)
			return retry("假玩家未就绪");
		arm.prepareFakePlayerHand();
		// 挖掘也摆位：让假玩家站在录下来的那一面之外（事件回调/方块反馈看到的位置才是"爪子所在处"）
		positionPlayer(player, pos, face);

		try {
			BlockState state = level.getBlockState(pos);
			// 那里没有可碰的东西（空气 / 无碰撞形状）：照机械手，**什么都不发生、也不算失败**
			//（上游是靠射线没打中目标就 return；我们这边等价于"这一挥打空了"）。
			if (state.getShape(level, pos)
				.isEmpty()) {
				resetBreakingProgress(level);
				return Result.SUCCESS;
			}
			if (!level.mayInteract(player, pos))
				return hardFailure("无权交互（出生点保护）");
			LeftClickBlock event =
				CommonHooks.onLeftClickBlock(player, pos, face, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
			if (event.isCanceled())
				return hardFailure("LeftClickBlock 被取消（保护插件）");
			if (BlockHelper.extinguishFire(level, player, pos, face))
				return Result.SUCCESS;
			if (event.getUseBlock() != TriState.FALSE)
				state.attack(level, pos, player);

			// 注意：上游在这里对空手 return，我们按决策允许空手破坏，故无此判断。
			float progress = state.getDestroyProgress(player, level, pos) * 16;
			float before = breakingPos != null && breakingPos.equals(pos) ? breakingProgress : 0;
			progress += before;
			level.playSound(null, pos, state.getSoundType()
				.getHitSound(), SoundSource.NEUTRAL, .25f, 1);

			if (progress >= 1) {
				boolean harvested = DeployerHandler.tryHarvestBlock(player, player.gameMode, pos);
				level.destroyBlockProgress(player.getId(), pos, -1);
				breakingPos = null;
				breakingProgress = 0;
				return harvested ? Result.SUCCESS : hardFailure("破坏被拒绝（领地/保护）");
			}
			// 进度恒为 0 = vanilla 说这一击**完全没效果**（基岩、被保护的方块）。
			// 这不是"动身前的检测"，而是挥完之后的事实：报出来，否则臂会对着基岩一直挥
			//（空转到空转守卫停机为止，3 分钟以上，而且护目镜上的原因还是"空转"，更难查）。
			if (progress <= 0)
				return hardFailure("挖不动（进度恒为 0：基岩或被保护的方块）");
			if ((int) (before * 10) != (int) (progress * 10))
				level.destroyBlockProgress(player.getId(), pos, (int) (progress * 10));
			breakingPos = pos;
			breakingProgress = progress;
			return Result.WAITING;
		} finally {
			arm.syncHeldItemFromFakePlayer();
		}
	}

	/**
	 * 清掉客户端的挖掘裂纹。
	 *
	 * <p>换目标、收工、等待、停机都要清：`destroyBlockProgress(id, pos, -1)` 是**唯一**能消掉那层裂纹的调用，
	 * 而裂纹是存在客户端上的 —— 漏掉它，方块上就会一直挂着一片"挖了一半"的裂痕，
	 * 直到那个方块被真的挖掉或者玩家重登。（挖到一半被打断正是最常发生的那种情况。）
	 */
	public void clearBreakingProgress() {
		if (arm.getLevel() instanceof ServerLevel serverLevel)
			resetBreakingProgress(serverLevel);
	}

	private void resetBreakingProgress(ServerLevel level) {
		if (breakingPos != null && arm.getFakePlayer() != null)
			level.destroyBlockProgress(arm.getFakePlayer()
				.getId(), breakingPos, -1);
		breakingPos = null;
		breakingProgress = 0;
	}

	// ————————————————————————— 右键（USE / USE_SNEAK） —————————————————————————

	public Result use(BlockPos clickedPos, Direction face, boolean sneak) {
		if (!(arm.getLevel() instanceof ServerLevel level))
			return retry("世界未加载");
		DeployerFakePlayer player = arm.getFakePlayer();
		if (player == null)
			return retry("假玩家未就绪");
		arm.prepareFakePlayerHand();
		positionPlayer(player, clickedPos);
		player.setShiftKeyDown(sneak);

		try {
			InteractionHand hand = InteractionHand.MAIN_HAND;
			ItemStack stack = player.getMainHandItem();

			// 实体优先
			List<Entity> entities = level.getEntitiesOfClass(Entity.class, new AABB(clickedPos))
				.stream()
				.filter(e -> !(e instanceof AbstractContraptionEntity))
				.toList();
			if (!entities.isEmpty()) {
				Entity entity = entities.get(level.random.nextInt(entities.size()));
				InteractionResult cancelResult = CommonHooks.onInteractEntity(player, entity, hand);
				if (cancelResult == InteractionResult.FAIL)
					// 取消 ≠ 失败：Create 自己'给方块安装物品'就是靠 setCanceled(true) 生效的（ManualApplicationRecipe:69-77）。
			// 与方块那条路一致，这里也按'交互已尝试'算成功。
			return Result.SUCCESS;
				if (cancelResult == null) {
					if (entity.interact(player, hand)
						.consumesAction())
						return Result.SUCCESS;
					if (entity instanceof LivingEntity living
						&& stack.interactLivingEntity(player, living, hand)
							.consumesAction())
						return Result.SUCCESS;
				}
			}

			BlockState clickedState = level.getBlockState(clickedPos);
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clickedPos), face, clickedPos, false);
			UseOnContext context = new UseOnContext(player, hand, hit);

			TriState useBlock = TriState.DEFAULT;
			TriState useItem = TriState.DEFAULT;
			if (!clickedState.getShape(level, clickedPos)
				.isEmpty()) {
				// 注意：**不能**把 isCanceled() 当成失败。NeoForge 21.1 的 setCanceled(true) 会把
				// useBlock/useItem 一起置成 FALSE（PlayerInteractEvent.java:341-345），而
				// Create 自己的"给方块安装物品"就是靠取消事件生效的：ManualApplicationRecipe:69-77
				// 先放金属音、替换方块、扣物品，再 setCanceled(true)。上游 DeployerHandler:290-295
				// 只读这两个 TriState、不看 isCanceled，正是为了不把"已经被处理掉了"误判成失败。
				// 这也意味着取消与保护插件的拒绝无法区分 —— 两者都表现为 useBlock=FALSE、
				// 后面直接走到 return SUCCESS，与机械手一致。
				RightClickBlock event = CommonHooks.onRightClickBlock(player, hand, clickedPos, hit);
				useBlock = event.getUseBlock();
				useItem = event.getUseItem();
			}

			if (useItem != TriState.FALSE) {
				InteractionResult first = stack.onItemUseFirst(context);
				if (first != InteractionResult.PASS)
					return Result.SUCCESS;
			}

			boolean holdingSomething = !stack.isEmpty();
			boolean notSneakBlocked = !(player.isShiftKeyDown() && holdingSomething)
				|| stack.doesSneakBypassUse(level, clickedPos, player);
			if (useBlock != TriState.FALSE && notSneakBlocked
				&& DeployerHandler.safeOnUse(clickedState, level, clickedPos, player, hand, hit)
					.consumesAction())
				return Result.SUCCESS;

			if (stack.isEmpty() || useItem == TriState.FALSE)
				return Result.SUCCESS; // 手上没东西可用 —— 交互到此为止，算"已尝试"

			if (stack.useOn(context)
				.consumesAction())
				return Result.SUCCESS;

			InteractionResultHolder<ItemStack> rightClick = stack.getItem()
				.use(level, player, hand);
			if (rightClick.getResult()
				.consumesAction()) {
				player.setItemInHand(hand, rightClick.getObject());
				return Result.SUCCESS;
			}
			// 决策：左右键无法客观判定成败 —— 交互已尝试即算完成（因此它们不支持"强制"）
			return Result.SUCCESS;
		} finally {
			player.setShiftKeyDown(false);
			arm.syncHeldItemFromFakePlayer();
		}
	}

	// ————————————————————————— 辅助 —————————————————————————

	private void positionPlayer(DeployerFakePlayer player, BlockPos pos) {
		positionPlayer(player, pos, null);
	}

	/**
	 * 把假玩家摆到目标前并朝着它。
	 *
	 * <p>{@code attackFace} 非空时（攻击 / 挖掘），额外把假玩家摆到**那一面之外**，这是**击退方向的来源**：
	 * vanilla 在 `LivingEntity.hurt` 里用 {@code d0 = source.getSourcePosition().x - 实体的x}、
	 * {@code d1 = ...z} 算出击退向量，而近战伤害源没有显式位置时会回落到**攻击者实体的位置** ——
	 * 也就是说"你把攻击者摆在哪一侧，实体就被推向另一侧"，和玩家自己挥击是同一条公式。
	 *
	 * <p>用录下来的面、而不是用臂自己的位置：臂可能在斜上方好几格外，方向会跟着漂；
	 * 而"录的时候点的是哪一面"就是"我从哪边打"的真相（左键的爪尖也正是从那一面扎进去的，
	 * 见 {@code AnyBlockInteractionPoint.getInteractionDirection()}）。
	 *
	 * <p>⚠️ 若哪天实测方向与预期相反，把这里换成 {@code attackFace.getOpposite()} 即可（只有这一处）。
	 */
	private void positionPlayer(DeployerFakePlayer player, BlockPos pos, @Nullable Direction attackFace) {
		Vec3 target = Vec3.atCenterOf(pos);
		Vec3 origin = Vec3.atCenterOf(arm.getBlockPos())
			.add(0, 6 / 16f, 0);
		if (attackFace != null)
			origin = target.add(Vec3.atLowerCornerOf(attackFace.getNormal())
				.scale(0.6)); // 站在那一面之外半步：够近，且不会和方块中心重合（重合时差分退化成 0）
		Vec3 diff = target.subtract(origin);
		double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
		float yaw = (float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90f;
		float pitch = (float) (-Math.toDegrees(Math.atan2(diff.y, horizontal)));
		player.setPos(origin.x, origin.y, origin.z);
		player.setYRot(yaw);
		player.setXRot(pitch);
	}

	private HashMultimap<Holder<Attribute>, AttributeModifier> applyAttributeModifiers(DeployerFakePlayer player,
		ItemStack stack) {
		HashMultimap<Holder<Attribute>, AttributeModifier> modifiers = HashMultimap.create();
		stack.getAttributeModifiers()
			.modifiers()
			.forEach(entry -> modifiers.put(entry.attribute(), entry.modifier()));
		EnchantmentHelper.forEachModifier(stack, EquipmentSlot.MAINHAND, modifiers::put);
		player.getAttributes()
			.addTransientAttributeModifiers(modifiers);
		return modifiers;
	}

	private void removeAttributeModifiers(DeployerFakePlayer player,
		HashMultimap<Holder<Attribute>, AttributeModifier> modifiers) {
		player.getAttributes()
			.removeAttributeModifiers(modifiers);
	}

	/** 暂时做不了（世界未加载、假玩家未就绪）—— 交给运行时原地重试。 */
	private Result retry(String reason) {
		lastFailure = reason;
		return Result.RETRY;
	}

	/** 根本做不成（挖不动 / 无权 / 被保护 / 目标无效）—— 运行时立刻走失败出口，不再重试。 */
	private Result hardFailure(String reason) {
		lastFailure = reason;
		return Result.HARD_FAIL;
	}

}
