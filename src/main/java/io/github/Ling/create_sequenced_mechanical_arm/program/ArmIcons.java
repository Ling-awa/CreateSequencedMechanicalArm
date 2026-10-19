package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.EnumMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 卡片胶囊上的默认图标（PLAN.md §5.2）。
 *
 * <p>用户提的做法：动作能用"虚拟物品图标"表示 —— 取物像置物台、**组装像机械手（{@code create:deployer}，
 * Create 官方中文名就是"机械手"）**、**使用 / 潜行使用像黄铜手部零件（{@code item.create.brass_hand}）**、
 * 攻击像铁镐……（第二十二轮按用户指定改过这三个）
 * 没设自定义图标时用这里的默认值；设了就用玩家在编辑器幽灵槽里放的那个物品。
 *
 * <p><b>查不到就退回原版物品，再查不到就用空栈</b>：图标只是显示，绝不该因为某个模组改了物品 id
 * 就让界面炸掉或让程序读不出来。启动自检会断言这 9 个默认图标都不是空的。
 */
public final class ArmIcons {

	private ArmIcons() {
	}

	/** 任务卡的默认图标：时刻表那种"列车"感，用 Create 的列车机壳。 */
	public static ItemStack taskIcon() {
		return of("create:railway_casing", Items.MINECART);
	}

	/**
	 * 默认图标的**惰性缓存**（一个操作类型一份）—— 卡片每帧都要画图标，
	 * 而 {@link #buildDefaultIcon} 每次都要 {@code ResourceLocation.tryParse} + 注册表查找 + {@code new ItemStack}。
	 *
	 * <p>★ <b>只在这里的调用期填充</b>（第一次真的要用到某个操作时），**不放在静态初始化块里**：
	 * 静态初始化可能在注册表还没就绪时跑（物品 id 查不到 ⇒ 会把"退回到原版物品"那张错误结果永久缓存下来）。
	 * 调用期填充的时机是画界面 / 自检，那时注册表一定已经冻结。
	 *
	 * <p>返回值是**共享实例**，所以**只读**：调用方一律只 renderItem / getItem / isEmpty，
	 * 谁要改它（setCount、复制成别的栈）必须自己 {@code copy()}。这是"默认图标"的本性 ——
	 * 一个操作类型只需要一个常量栈。
	 */
	private static final EnumMap<OperationType, ItemStack> DEFAULT_ICONS = new EnumMap<>(OperationType.class);

	/** 编辑器的"没有自定义图标"占位（同时也是空幽灵槽时显示的图标）。 */
	public static ItemStack defaultIcon(OperationType op) {
		ItemStack cached = DEFAULT_ICONS.get(op);
		if (cached != null)
			return cached;
		ItemStack built = buildDefaultIcon(op);
		DEFAULT_ICONS.put(op, built);
		return built;
	}

	/** 这个操作的默认图标（按物品 id 查；查不到退回原版物品）。只由 {@link #defaultIcon} 调用。 */
	private static ItemStack buildDefaultIcon(OperationType op) {
		return switch (op) {
			case TAKE -> of("create:depot", Items.HOPPER);
			case DEPOSIT -> of("create:chute", Items.DROPPER);
			// 收存 = **Create 的物品保险库**（一眼就是"把东西存起来"）；查不到退回原版箱子
			// —— 图标只是显示，绝不该因为某个模组改了物品 id 就让界面炸掉。
			case STASH -> of("create:item_vault", Items.CHEST);
			case UNLOAD -> of("create:brass_funnel", Items.HOPPER);
			// 组装 = **机械手**（create:deployer，Create 官方中文名就是"机械手"），
			// 使用 / 潜行使用 = **黄铜手部零件**（item.create.brass_hand）—— 用户第二十二轮指定的对应关系：
			// "组装"是机械手，"使用"是黄铜手部件，潜行使用也是黄铜手部件。
			case DEPLOY -> of("create:deployer", Items.SHEARS);
			// v27 拆开后：攻击 = 剑（打格里实体），挖掘 = 镐（只挖方块）
			case ATTACK -> of("minecraft:iron_sword", Items.IRON_SWORD);
			case MINE -> of("minecraft:iron_pickaxe", Items.IRON_PICKAXE);
			case USE -> of("create:brass_hand", Items.LEVER);
			case USE_SNEAK -> of("create:brass_hand", Items.LEVER);
			// 过滤 = **Create 的滤波器物品本身**（create:filter，列表过滤器）——
			// 图标就是"这条在用一个过滤器"这件事，比另找一个原版物品更直观。
			// 查不到（Create 被换版本/改名）就退回漏斗：图标只是显示，绝不该让界面炸掉。
			case FILTER -> of("create:filter", Items.HOPPER);
			case WAIT -> of("minecraft:clock", Items.CLOCK);
			// 控制类（§7.6 v4）
			case SIGNAL -> of("create:redstone_link", Items.REDSTONE);
			case CALL -> of("create:schematic_and_quill", Items.WRITTEN_BOOK);
			case BREAK -> of("minecraft:comparator", Items.COMPARATOR);
			case THROW -> of("minecraft:barrier", Items.BARRIER);
		};
	}

	/**
	 * 条目实际该显示的图标：**自定义优先**，否则用该操作的默认图标。
	 *
	 * <p>「等待信号」的那一对频率物品**不在这里** —— 卡片上它们有自己专用的两个物品槽
	 * （见 `ArmProgramScreen.renderSignalChip`，用户第二十二轮定的读法），所以这里只给"操作自己的图标"。
	 */
	public static ItemStack iconFor(ArmProgramEntry entry) {
		return entry.icon.isEmpty() ? defaultIcon(entry.op) : entry.icon;
	}

	/** 「等待信号」绑定的两个频率物品；没绑就是两个空栈（**空也要占位**，卡片上画成空框）。 */
	public static ItemStack[] signalPair(ArmProgramEntry entry) {
		if (entry.frequency == null)
			return new ItemStack[] { ItemStack.EMPTY, ItemStack.EMPTY };
		return new ItemStack[] { entry.frequency.getFirst()
			.getStack(), entry.frequency.getSecond()
				.getStack() };
	}

	/** 任务实际该显示的图标：自定义优先。 */
	public static ItemStack iconFor(ArmProgramTask task) {
		return task.icon.isEmpty() ? taskIcon() : task.icon;
	}

	private static ItemStack of(String id, net.minecraft.world.item.Item fallback) {
		ResourceLocation key = ResourceLocation.tryParse(id);
		if (key != null && BuiltInRegistries.ITEM.containsKey(key)) {
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(key));
			if (!stack.isEmpty())
				return stack;
		}
		return new ItemStack(fallback);
	}

}
