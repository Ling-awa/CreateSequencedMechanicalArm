package io.github.Ling.create_smart_mechanical_arm.interaction;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 「任意方块」的交互点（用户第二轮反馈：<i>"右键、潜行右键、攻击这种操作是不需要 create 交互方块的，
 * 或者说是对任意方块都可以尝试"</i>）。
 *
 * <p>为什么要造这个类：智能臂的姿态**全部**是围绕 {@link ArmInteractionPoint} 算出来的 ——
 * {@code beginMove} 要拿到一个点才知道爪子该停在哪儿（{@code ArmAngleTarget}），
 * {@code getEffectorPosition()} 也要靠它给音效/粒子定位。而 Create 的交互点表是按方块类型注册的，
 * 普通石头/泥土根本不在里面，于是"对任意方块攻击"这条路在<b>伸过去</b>这一步就断了，
 * 连 {@code RightClickBridge} 都到不了（它其实对任意坐标都能干活）。
 *
 * <p>做法：继承 Create 的 {@link ArmInteractionPoint}（构造函数是 public，两个取值方法都是 protected），
 * 只覆盖两件事：
 * <ul>
 *   <li><b>爪子停在哪</b> —— 方块中心朝玩家录下的那一面外侧退出去，退到方块形状之外
 *       （薄方块如按钮/拉杆也不会把爪子插进去）；</li>
 *   <li><b>朝向</b> —— 是玩家点的那一面的**反方向**（爪尖朝方块里扎）。这是 Create 自己的约定：
 *       默认点与 {@code DepotPoint} 的取物点在方块顶上、朝向 {@code DOWN}；{@code DeployerPoint} 用
 *       {@code facing.getOpposite()}。（这一条曾经写反，症状见 {@link #getInteractionDirection()} 的说明。）</li>
 * </ul>
 *
 * <p>配套的"类型"（{@link #TYPE}）不是注册项：它只被 {@code isValid()} 用来判断目标还有效吗，
 * 而我们的点从不序列化（每次移动现造一个）。所以不必进 {@code CreateBuiltInRegistries}。
 */
public class AnyBlockInteractionPoint extends ArmInteractionPoint {

	/** 通用类型：任何**非空气**方块都算 —— 与 Create"必须是注册过的交互点"正好相反。 */
	public static final ArmInteractionPointType TYPE = new ArmInteractionPointType() {
		@Override
		public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
			return !state.isAir();
		}

		@Override
		public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
			// 这个重载拿不到"交互面"，只给 isValid()/类型查询用；真正造点走 create(...)
			return new AnyBlockInteractionPoint(level, pos, state, Direction.UP);
		}
	};

	/** 玩家录下来的那个面：爪子从这一侧够过去。 */
	private final Direction face;

	/** 爪子停在面的**外面**多远 —— 贴面站（0.06）既不会插进方块，也不会悬得太远。 */
	private static final double CLEARANCE = 0.06;

	/** 没有形状时（空气 / 没有碰撞箱）退化成整方块，这样"目标没了"也还有个可算的落点。 */
	private static final AABB FULL_BLOCK = new AABB(0, 0, 0, 1, 1, 1);

	private AnyBlockInteractionPoint(Level level, BlockPos pos, BlockState state, Direction face) {
		super(TYPE, level, pos, state);
		this.face = face;
	}

	/** 造一个"任意方块"的交互点。{@code face} 为点击面（朝外的法线方向）。 */
	public static ArmInteractionPoint create(Level level, BlockPos pos, BlockState state, Direction face) {
		return new AnyBlockInteractionPoint(level, pos, state, face);
	}

	/**
	 * 爪子的落点：**方块碰撞箱在"操作面"上的中心**，再沿面法线让开一点点。
	 *
	 * <p>用户第三轮要求："结合碰撞箱和操作面，共同决定动力臂的运动点位。" 旧写法是
	 * "方块几何中心 + 沿面外移固定的 0.5"，对整方块没问题，但对台阶 / 栅栏 / 按钮这类非整方块，
	 * 中心外移出来的点会离开真正要碰的那个面（下半砖的顶面只到 0.5，中心外移却给到 1.0 —— 手会悬在半空）。
	 *
	 * <p>现在：取形状的包围盒 → 只在**该轴**上取那一面（另外两轴取包围盒中点）→ 沿法线让开
	 * {@link #CLEARANCE}。整方块时结果与旧写法基本一致（顶面 1.06 而不是 1.0），非整方块则贴着真实的面。
	 */
	@Override
	protected Vec3 getInteractionPositionVector() {
		AABB box = shapeBox();
		Direction.Axis axis = face.getAxis();
		boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;

		double x = axis == Direction.Axis.X ? (positive ? box.maxX : box.minX) : (box.minX + box.maxX) / 2;
		double y = axis == Direction.Axis.Y ? (positive ? box.maxY : box.minY) : (box.minY + box.maxY) / 2;
		double z = axis == Direction.Axis.Z ? (positive ? box.maxZ : box.minZ) : (box.minZ + box.maxZ) / 2;

		// 形状坐标是**相对方块原点**的（栅栏这类还会超过 1），所以最后要加回方块自身的位置
		return new Vec3(x, y, z).add(Vec3.atLowerCornerOf(face.getNormal())
			.scale(CLEARANCE))
			.add(Vec3.atLowerCornerOf(pos));
	}

	/**
	 * 用哪个盒：**轮廓形状 ∪ 视觉形状**（都拿不到就退化成整方块）。
	 *
	 * <p>只看轮廓形状会在"轮廓比模型矮得多"的方块上把手伸进模型里 —— 用户那张炒锅图：
	 * 锅的轮廓只有底部那一小块（他自己量的是 3 像素高），而看到的锅是个大盆，
	 * 于是爪子落在盆**里面**。取两者的并集只会把落点往外推（永远不往方块内部推），
	 * 所以对普通方块毫无影响（两个形状基本一致），对这类机器方块正好把落点抬到看得见的外壳上。
	 *
	 * <p>仍不完美：我们只有形状，没有 Create 那种"每个方块类型各自标注的取物点"（置物台是 14/16 之类）。
	 * 形状表达不了的意图形状也给不出来 —— 详见 PLAN.md §7.9。
	 */
	private AABB shapeBox() {
		VoxelShape outline = cachedState.getShape(level, pos);
		VoxelShape visual = cachedState.getVisualShape(level, pos, CollisionContext.empty());
		AABB box = outline.isEmpty() ? null : outline.bounds();
		if (!visual.isEmpty())
			box = box == null ? visual.bounds() : box.minmax(visual.bounds());
		return box == null ? FULL_BLOCK : box;
	}

	/**
	 * 爪子**朝向**：{@code face} 的反方向，也就是"从爪尖指向方块内部"。
	 *
	 * <p>⚠️ 这一条是用户拿炒锅截图问"这是正常的吗"之后查出来的真 bug。方向约定看
	 * {@code ArmAngleTarget} 的构造器（Create 源码 :26-33）：它把 IK 的目标点沿
	 * {@code clawFacing.getOpposite()} 外移 0.5 —— 也就是"爪子从哪一侧伸过来"，
	 * 再由 {@code clawsTarget} 反算头部角度。所以 {@code clawFacing} 是**爪尖指向哪边**：
	 * 默认点（{@code ArmInteractionPoint.getInteractionDirection()}）返回 {@code DOWN}，
	 * 而它的取物点在方块**顶上** —— 朝向是"朝下扎进方块"，不是"朝上"。
	 *
	 * <p>之前这里直接返回玩家点的那一面（顶面 = UP），于是整条手臂反着算：IK 目标被挪到方块内部 0.5，
	 * 手臂水平地"塞进"方块里、头再翘起来。炒锅那张图就是这个现象（手臂插进锅里 + 头朝上）。
	 */
	@Override
	protected Direction getInteractionDirection() {
		return face.getOpposite();
	}

	@Override
	public void updateCachedState() {
		BlockState old = cachedState;
		super.updateCachedState();
		if (old != cachedState)
			cachedAngles = null;
	}

}
