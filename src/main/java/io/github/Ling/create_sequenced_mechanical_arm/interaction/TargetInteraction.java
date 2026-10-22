package io.github.Ling.create_sequenced_mechanical_arm.interaction;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 往"目标方块"里放东西的统一入口（PLAN.md §2.1b 的 R14：特化交互点类型要单独适配）。
 *
 * <p>两条路径，顺序与 Create 一致：
 * <ol>
 *   <li><b>漏斗类</b>（有 {@link InvManipulationBehaviour}）：走漏斗自己的行为对象，
 *       它会找到背后链接的容器。通用物品能力在漏斗上是<b>不存在</b>的 ——
 *       这正是"Deposit 到输入模式漏斗没反应"的根因。原版 {@code FunnelPoint.insert} 就是这条路。</li>
 *   <li><b>其余</b>：通用能力 {@code Capabilities.ItemHandler.BLOCK}（置物台/传送带/盆/溜槽…都是它）。</li>
 * </ol>
 *
 * <p>比原版多做的两件事（用户明确要求）：
 * <ul>
 *   <li>原版 <b>不查</b>漏斗的输入/输出模式，所以原版臂能往"输出模式漏斗"背后的容器里塞东西；
 *       我们显式拒收输出模式（{@code FunnelBlock.EXTRACTING}）。</li>
 *   <li>失败时给出<b>具体原因</b>（模式不对/被红石关闭/过滤器不匹配/没有能力），
 *       对应 PLAN.md 的 R3b：拒绝要可诊断。</li>
 * </ul>
 */
public final class TargetInteraction {

	private TargetInteraction() {
	}

	/** 插入结果：剩余物品 + 被挡下时的具体原因（{@code null} 表示不是"被挡"，可能是真的放不下）。 */
	public record Insert(ItemStack remainder, @Nullable String blockedReason) {

		public boolean madeProgress(ItemStack original) {
			return remainder.getCount() < original.getCount();
		}
	}

	@Nullable
	public static IItemHandler handler(Level level, BlockPos pos) {
		return level == null ? null : level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
	}

	/** 漏斗类目标的行为对象；非漏斗返回 {@code null}。 */
	@Nullable
	public static InvManipulationBehaviour manipulation(Level level, BlockPos pos) {
		return level == null ? null : BlockEntityBehaviour.get(level, pos, InvManipulationBehaviour.TYPE);
	}

	/**
	 * 这类目标"只进不出"吗 —— <b>照原版的判定方式</b>：让 Create 的交互点注册表造一个点出来，
	 * 看它是不是 {@code DepositOnlyArmInteractionPoint}（原版 {@code AllArmInteractionPointTypes:297-316}
	 * 的取物点分两类，只有那一类是取不出来的：漏斗 / 烈焰人 / 营火 / 重生锚 / 粉碎轮）。
	 *
	 * <p>用户第二十二轮实测：<i>"取出、放入、卸货应当是类似原版动力臂的输入、输出、输出……
	 * 例如打包机被认为只能作为输出端（会提示这个元件是类似漏斗的只进不出）"</i>。
	 * 旧实现是"有 {@code InvManipulationBehaviour} 就当成只进不出"，两头都会错：
	 * <b>打包机</b>有那个行为对象但其实**可以取**（原版 {@code PackagerType} 给的是普通交互点，
	 * `AllArmInteractionPointTypes:222-232`），而**烈焰人/营火/重生锚/粉碎轮**没有行为对象、原版却是只进不出。
	 */
	public static boolean isDepositOnly(Level level, BlockPos pos) {
		if (level == null)
			return false;
		ArmInteractionPoint point = ArmInteractionPoint.create(level, pos, level.getBlockState(pos));
		return point instanceof AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint;
	}

	/**
	 * 往目标里放东西 —— 三条路径，顺序就是本体的顺序：
	 * <ol>
	 *   <li><b>漏斗类</b>（有 {@link InvManipulationBehaviour}）：走漏斗自己的行为对象，
	 *       它会找到背后链接的容器。通用物品能力在漏斗上是<b>不存在</b>的 ——
	 *       这正是"Deposit 到输入模式漏斗没反应"的根因。原版 {@code FunnelPoint.insert} 就是这条路。</li>
	 *   <li><b>Create 认得的其余交互点</b>：交给 {@link ArmPointTransfer}，也就是
	 *       {@link ArmInteractionPoint#insert} —— 烈焰人只收燃料、重生锚只收荧石、堆肥桶只收可堆肥……
	 *       这些都写在 {@code AllArmInteractionPointTypes} 的子类里，见 {@link ArmPointTransfer} 的类注释。</li>
	 *   <li><b>其余</b>：通用能力 {@code Capabilities.ItemHandler.BLOCK}（箱子 / 保险箱这类
	 *       本体**不认**的容器，我们仍然允许）。</li>
	 * </ol>
	 */
	public static Insert insert(Level level, BlockPos pos, ItemStack stack, boolean simulate) {
		if (level == null)
			return new Insert(stack, "世界未加载");

		InvManipulationBehaviour inserter = manipulation(level, pos);
		if (inserter != null) {
			BlockState state = level.getBlockState(pos);
			// 输出模式（EXTRACTING=true）的漏斗是把容器里的东西往外掏，不该接收臂送来的物品
			if (state.hasProperty(FunnelBlock.EXTRACTING) && state.getValue(FunnelBlock.EXTRACTING))
				return new Insert(stack, "漏斗处于输出模式，不接收物品");
			if (state.getOptionalValue(BlockStateProperties.POWERED)
				.orElse(false))
				return new Insert(stack, "漏斗被红石关闭");
			FilteringBehaviour filtering = BlockEntityBehaviour.get(level, pos, FilteringBehaviour.TYPE);
			if (filtering != null && !filtering.test(stack))
				return new Insert(stack, "漏斗过滤器不匹配");
			// 行为对象对"背后那个容器"的引用是**缓存**，只在它自己的 tick / lazyTick 里刷新
			//（`CapManipulationBehaviourBase:102-116`，lazyTickRate = 5）。刚放下的漏斗还没 tick 过，
			// 缓存是空的 —— 这时 `insert` 会**原样把物品退回来**、不留任何原因，运行时把它读成"目标放不下"。
			// 补一次显式刷新，并把"背后真的没有容器"变成可诊断的原因（R3b）。
			if (!inserter.hasInventory())
				inserter.findNewCapability();
			if (!inserter.hasInventory())
				return new Insert(stack, "漏斗背后没有容器");
			if (simulate)
				inserter.simulate();
			ItemStack remainder = inserter.insert(stack);
			// ★ 补上原版 {@code FunnelPoint.insert} 的尾巴（`AllArmInteractionPointTypes:497-504`）：
			// "真的进去了"才通知漏斗 —— `onTransfer` 是它的内部状态，`flap(true)` 是**翻板动画**（服务端发包）
			// 连同音效。少了这一步，臂往黄铜漏斗里放东西时漏斗**毫无反应**
			//（用户实测："对着黄铜漏斗放入的时候黄铜漏斗没有任何动画"）。
			if (!simulate && remainder.getCount() != stack.getCount()
				&& level.getBlockEntity(pos) instanceof FunnelBlockEntity funnel) {
				funnel.onTransfer(stack);
				if (funnel.hasFlap())
					funnel.flap(true);
			}
			return new Insert(remainder, null);
		}

		// ★ 目标如果是 Create 认得的交互点，就交给**它自己的 insert**（用户 2026-10-21：按本体的判断标准）。
		//   漏斗在**上面那一支**已经处理掉了（我们额外做了模式 / 过滤器 / 红石的可诊断拒绝），这里只管其余类型。
		ArmInteractionPoint point = ArmPointTransfer.pointAt(level, pos);
		if (point != null) {
			ItemStack viaPoint = ArmPointTransfer.insert(point, level, stack, simulate);
			// viaPoint == null ⇒ 这个点我们用不了（第三方点抛错），退回裸能力，而不是当成"放不下"
			if (viaPoint != null)
				return new Insert(viaPoint, null);
		}

		IItemHandler handler = handler(level, pos);
		if (handler == null)
			return new Insert(stack, "目标没有物品能力");
		return new Insert(ItemHandlerHelper.insertItem(handler, stack, simulate), null);
	}

	/**
	 * 从目标<b>取出</b>物品 —— 与 {@link #insert} 对称：有 Create 交互点就按本体语义（{@code extract}），
	 * 否则退回裸能力。
	 *
	 * <p>为什么取出也必须走本体：{@code DepositOnlyArmInteractionPoint} 的 {@code extract} 恒返回空、
	 * {@code JukeboxPoint} 只吐唱片、{@code CrafterPoint} 只从合成结果那一格取 —— 这些都不在通用物品能力里。
	 */
	public static ItemStack extract(Level level, BlockPos pos, int slot, int amount, boolean simulate) {
		if (level == null)
			return ItemStack.EMPTY;
		ArmInteractionPoint point = ArmPointTransfer.pointAt(level, pos);
		if (point != null) {
			ItemStack viaPoint = ArmPointTransfer.extract(point, level, slot, amount, simulate);
			if (viaPoint != null)
				return viaPoint;
		}
		IItemHandler handler = handler(level, pos);
		return handler == null ? ItemStack.EMPTY : handler.extractItem(slot, amount, simulate);
	}

	/**
	 * 「取出」能扫的格数：交互点优先（本体的 {@code getSlotCount}），否则退回通用能力的槽数。
	 *
	 * <p>返回 0 表示<b>这一格根本没有可取的物品面</b>（既不是交互点、也没有物品能力）。
	 */
	public static int takeSlots(Level level, BlockPos pos, @Nullable IItemHandler fallback) {
		ArmInteractionPoint point = ArmPointTransfer.pointAt(level, pos);
		if (point != null) {
			int slots = ArmPointTransfer.slots(point, level);
			if (slots > 0)
				return slots;
		}
		return fallback == null ? 0 : fallback.getSlots();
	}

	/**
	 * 找一格能取的东西 —— 前置检查与真正执行<b>共用这一个判据</b>（否则会出现"检查放行、真取又不够"的摆动）。
	 *
	 * @param count 要取几个
	 * @param exact {@code true} = 这一格必须能给出<b>恰好</b> {@code count} 个；
	 *              {@code false} = 只要非空
	 * @return 能用的槽号；没有就 {@code -1}
	 */
	public static int findSlot(Level level, BlockPos pos, @Nullable IItemHandler fallback, int count, boolean exact) {
		ArmInteractionPoint point = ArmPointTransfer.pointAt(level, pos);
		if (point != null) {
			int slots = ArmPointTransfer.slots(point, level);
			if (slots > 0) {
				for (int slot = 0; slot < slots; slot++) {
					ItemStack probe = ArmPointTransfer.extract(point, level, slot, count, true);
					if (probe == null)
						break; // 这个点我们用不了 ⇒ 退回裸能力
					if (exact ? probe.getCount() == count : !probe.isEmpty())
						return slot;
				}
				return -1;
			}
		}
		if (fallback == null)
			return -1;
		for (int slot = 0; slot < fallback.getSlots(); slot++) {
			ItemStack probe = fallback.extractItem(slot, count, true);
			if (exact ? probe.getCount() == count : !probe.isEmpty())
				return slot;
		}
		return -1;
	}

}
