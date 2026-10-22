package io.github.Ling.create_sequenced_mechanical_arm.interaction;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlock;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 把「取出 / 放入 / 卸货」的<b>物品搬运</b>交给本体交互点自己的逻辑（用户 2026-10-21 口径：
 * <i>"就按照本体的判断逻辑来"</i>）。
 *
 * <h2>为什么必须走这一层</h2>
 *
 * <p>Create 的「输入端 / 输出端」不是枚举，而是 {@link ArmInteractionPoint} 的子类：能不能取、能不能放、
 * 从哪一面、放进什么，全部写在它的 {@code insert} / {@code extract} / {@code getSlotCount} /
 * {@code getInteractionDirection} 里。举几个本体现在就有的判据（都在 {@code AllArmInteractionPointTypes}）：
 * <ul>
 *   <li>{@code BlazeBurnerPoint.insert}：只收燃料 —— 走的是 {@code BlazeBurnerBlock.tryInsert(...)}，
 *     没达到点火标准就把物品原样退回；</li>
 *   <li>{@code RespawnAnchorPoint.insert} / {@code CampfirePoint.insert}：只收荧石 / 只收可烹饪物；</li>
 *   <li>{@code ComposterPoint}：只收可堆肥物，还要推进堆肥层；</li>
 *   <li>{@code JukeboxPoint}：只收唱片、只能取出唱片；</li>
 *   <li>{@code CrafterPoint}：只能从合成结果那一格取；</li>
 *   <li>{@code DepositOnlyArmInteractionPoint}：只进不出（{@code extract} 恒返回空）。</li>
 * </ul>
 *
 * <p>我们原来只按 {@code Capabilities.ItemHandler.BLOCK}、面写死 {@code UP} 去搬 —— 于是上面这些规则
 * 一条都不生效（往烈焰人里塞铁锭也不会被拒、方向还可能错）。这里把它们接回来。
 *
 * <h2>为什么要有"载具"</h2>
 *
 * <p>{@code insert/extract} 的签名要求一个 {@link ArmBlockEntity} 参数，而我们的臂是另一个方块实体。
 * 逐字节码核过（Create 6.0.10-280）：<b>基类 {@code getHandler(ArmBlockEntity)} 只用 {@code this.level} 与
 * {@code this.pos}</b>，而 29 个点类型的字节码里<b>没有任何一处解引用那个参数</b> —— 它是纯占位。
 * 所以我们造一个不下世界、不 tick、不存盘的载具实例传进去即可（构造体只做 super + 建空列表，不碰 level）。
 *
 * <p>⚠️ 不要传 {@code null}：Create 自己的点不读它，但**别的模组**注册的点类型可能读（它们写的时候以为
 * 拿到的是真臂）。传载具 + 下面每个入口都兜 {@code Throwable}，才是"能用它的语义就用，用不了不炸"。
 *
 * <h2>兼容性从哪来</h2>
 *
 * <p>第三方给 Create 加交互点走的是 {@code ArmInteractionPointType}（{@code getPrimaryType} /
 * {@code createPoint} / {@code getPriority}，甚至有优先级可以覆盖本体类型）。而
 * {@link ArmInteractionPoint#create} 就是查那张表 —— 只要我们的搬运走它，第三方类型的语义自动生效，
 * 不需要为任何模组单独适配。
 */
public final class ArmPointTransfer {

	private ArmPointTransfer() {
	}

	/** 唯一的载具实例（见类注释：不会被任何点解引用，只用来满足签名）。 */
	@Nullable
	private static ArmBlockEntity carrier;

	/**
	 * 机械臂的方块实例（拿它的 {@code BlockEntityType} 与默认状态）—— <b>不碰 registrate</b>。
	 *
	 * <p>为什么不用 {@code AllBlockEntityTypes.MECHANICAL_ARM.get()}：那玩意的编译期类型是
	 * {@code com.tterrag.registrate.util.entry.BlockEntityEntry}，而本项目只把 create / ponder 当
	 * 编译期伙伴（registrate 是 Create 的 jar-in-jar，不在类路径上）—— 调它会直接编译失败。
	 *
	 * <p>⚠️ 载具必须用**机械臂自己的默认方块状态**：1.21 的 {@code BlockEntity} 构造会校验
	 * "这个状态是不是这个 BE 类型的"，拿 {@code air} 会当场抛
	 * {@code Invalid block entity create:mechanical_arm … got Block{minecraft:air}}（实测踩到）。
	 */
	@Nullable
	private static ArmBlock armBlock() {
		return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "mechanical_arm"))
			instanceof ArmBlock armBlock ? armBlock : null;
	}

	@Nullable
	private static ArmBlockEntity carrier(Level level) {
		if (carrier == null) {
			try {
				ArmBlock armBlock = armBlock();
				if (armBlock == null)
					return null;
				@SuppressWarnings("unchecked")
				BlockEntityType<ArmBlockEntity> type = (BlockEntityType<ArmBlockEntity>) armBlock.getBlockEntityType();
				carrier = new ArmBlockEntity(type, BlockPos.ZERO, armBlock.defaultBlockState());
			} catch (Throwable broken) {
				return null; // 载具造不出来 ⇒ 退回裸能力，绝不把异常抛给运行时
			}
		}
		// 填上 level 只是为了"万一有第三方点去读 arm.getLevel()"时不炸；本体的点根本不看。
		if (carrier.getLevel() != level)
			carrier.setLevel(level);
		return carrier;
	}

	/** 这一格在 Create 眼里是什么交互点；不是（或类型构造抛异常）⇒ {@code null}，调用方退回裸能力。 */
	@Nullable
	public static ArmInteractionPoint pointAt(Level level, BlockPos pos) {
		if (level == null || pos == null)
			return null;
		try {
			return ArmInteractionPoint.create(level, pos, level.getBlockState(pos));
		} catch (Throwable broken) {
			return null;
		}
	}

	/**
	 * 按本体语义放入。
	 *
	 * @return 剩余物品（{@code ItemStack.EMPTY} = 全放进去）；<b>{@code null} = 这个点我们用不了</b>
	 *         （构造异常 / 第三方点抛错）—— 调用方此时应退回裸能力，而不是把物品当成"放不下"
	 */
	@Nullable
	public static ItemStack insert(ArmInteractionPoint point, Level level, ItemStack stack, boolean simulate) {
		ArmBlockEntity carrier = carrier(level);
		if (carrier == null)
			return null;
		try {
			return point.insert(carrier, stack, simulate);
		} catch (Throwable broken) {
			return null;
		}
	}

	/**
	 * 按本体语义取出（{@code simulate} 时只算不取）。
	 *
	 * @return 取到的物品（{@code EMPTY} = 这一格取不出来）；{@code null} = 这个点我们用不了
	 */
	@Nullable
	public static ItemStack extract(ArmInteractionPoint point, Level level, int slot, int amount, boolean simulate) {
		ArmBlockEntity carrier = carrier(level);
		if (carrier == null)
			return null;
		try {
			return point.extract(carrier, slot, amount, simulate);
		} catch (Throwable broken) {
			return null;
		}
	}

	/** 这个点有几"格"可搬（本体的 {@code getSlotCount}；只进不出的点是 0）。 */
	public static int slots(ArmInteractionPoint point, Level level) {
		ArmBlockEntity carrier = carrier(level);
		if (carrier == null)
			return 0;
		try {
			return point.getSlotCount(carrier);
		} catch (Throwable broken) {
			return 0;
		}
	}
}
