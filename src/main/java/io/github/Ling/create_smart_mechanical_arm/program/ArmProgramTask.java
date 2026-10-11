package io.github.Ling.create_smart_mechanical_arm.program;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 一个任务（PLAN.md §4.1 的 Task）：一串有序操作 + 循环。
 *
 * <p><b>§7.6 v4：单线程 + 调用栈</b>。任务不再有优先级 / 频率门控 ——
 * 列表里的<b>第一个任务就是主任务</b>（默认执行），其余任务只能被
 * {@link OperationType#CALL 调用}触发。红石链接频率搬进了「等待信号」操作，
 * 于是"这个任务要通电才跑"变成任务开头的第一条 `等待信号` 条目。
 *
 * <p>循环留在任务上（用户："一个任务就只包含循环次数的设置了"）：
 * 子任务循环 n 次，就等于"调用它 n 次的语义"由它自己定义。
 */
public class ArmProgramTask {

	public static final int MAX_LOOP_COUNT = 1024;

	public String name = "task";
	public LoopType loop = LoopType.NONE;
	public int loopCount = 1;
	/** 任务图标（可选）：编辑器幽灵槽里的物品，空 = 用默认的任务图标。 */
	public ItemStack icon = ItemStack.EMPTY;
	public final List<ArmProgramEntry> entries = new ArrayList<>();

	public enum LoopType {
		/** 走完一轮就结束。 */
		NONE,
		/** 走完一轮就回到开头，重复 {@link #loopCount} 轮。 */
		COUNT,
		/** 走完一轮就回到开头，一直重复（靠「退出」操作收尾）。 */
		FOREVER,
		/** 一直重复；失败时**停在原地等条件恢复**（不整轮重来）—— 取不到就等着那种。 */
		UNTIL_FAIL
	}

	public CompoundTag save(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putString("name", name);
		tag.putString("loop", loop.name());
		tag.putInt("loopN", loopCount);
		if (!icon.isEmpty())
			tag.put("icon", icon.saveOptional(registries));
		ListTag list = new ListTag();
		for (ArmProgramEntry entry : entries)
			list.add(entry.save(registries));
		tag.put("entries", list);
		return tag;
	}

	public static ArmProgramTask load(CompoundTag tag, HolderLookup.Provider registries) {
		ArmProgramTask task = new ArmProgramTask();
		task.name = tag.contains("name") ? tag.getString("name") : "task";
		task.loop = readLoop(tag.getString("loop"));
		task.loopCount = Math.max(1, Math.min(MAX_LOOP_COUNT, tag.getInt("loopN")));
		if (tag.contains("icon"))
			task.icon = ItemStack.parseOptional(registries, tag.getCompound("icon"));
		ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++)
			task.entries.add(ArmProgramEntry.load(list.getCompound(i), registries));

		// ——— v3 → v4：任务的频率门控搬成任务开头的「等待信号」条目 ———
		// 老卡片的意思是"这个任务要通电（或断电）才跑"，直接丢掉会让它从"门控"变成"常驻"，
		// 静默改变行为；写成第一条等待信号就完全等价。
		Couple<Frequency> legacy = readLegacyFrequency(tag, registries);
		if (legacy != null) {
			ArmProgramEntry gate = new ArmProgramEntry();
			gate.op = OperationType.WAIT_SIGNAL;
			gate.frequency = legacy;
			gate.waitForPowered = !tag.getBoolean("inv");
			task.entries.add(0, gate);
			// ★ 头插一条会把**所有序号后移一位**，而跳转落点是 1 基序号（{@code ProgramRuntime.jumpTo}：
			// `oneBased - 1` 直接当下标）—— 不补偿的话，老卡上每一处"跳转到 N"都会静默指向前一条，
			// 而"跳转"正是玩家用来做循环/分支的东西，错一条就等于程序换了意思。
			// "N = 本任务条数 + 1（立即结束本轮）"也一起 +1：插入后新条数 = 旧条数 + 1，
			// 所以"结束本轮"的新序号恰好也是旧值 + 1，两种含义同时成立。
			for (ArmProgramEntry entry : task.entries) {
				entry.successTarget = shiftNumericTarget(entry.successTarget);
				entry.timeoutTarget = shiftNumericTarget(entry.timeoutTarget);
			}
		}
		return task;
	}

	/**
	 * 迁移用：把"纯数字落点"整体后移一位；任务名（或空）原样返回。
	 *
	 * <p>只认纯数字：落点既可以是本任务内的 1 基序号，也可以是任务名，两者不能混为一谈
	 *（运行时的解析顺序是先试 {@code parseInt}、失败才按任务名找）。
	 */
	private static String shiftNumericTarget(String target) {
		if (target == null)
			return "";
		String trimmed = target.trim();
		if (trimmed.isEmpty() || !trimmed.chars()
			.allMatch(Character::isDigit))
			return target;
		try {
			return Integer.toString(Integer.parseInt(trimmed) + 1);
		} catch (NumberFormatException tooBig) {
			return target; // 位数多到 int 装不下：本来就只会被判"序号超范围"，原样留着更好排查
		}
	}

	/** 老结构里的 {@code freq}（v3 的任务频率门控），只用于迁移。 */
	@Nullable
	private static Couple<Frequency> readLegacyFrequency(CompoundTag tag, HolderLookup.Provider registries) {
		if (!tag.contains("freq"))
			return null;
		ListTag freqTag = tag.getList("freq", Tag.TAG_COMPOUND);
		if (freqTag.size() != 2)
			return null;
		Couple<Frequency> parsed =
			Couple.deserializeEach(freqTag, c -> Frequency.of(ItemStack.parseOptional(registries, c)));
		// 两个物品都为空的频率没有意义（等价于"没有发射器"），按常驻处理更不容易困惑
		if (parsed.getFirst() == Frequency.EMPTY && parsed.getSecond() == Frequency.EMPTY)
			return null;
		return parsed;
	}

	private static LoopType readLoop(String name) {
		for (LoopType type : LoopType.values()) {
			if (type.name()
				.equalsIgnoreCase(name))
				return type;
		}
		return LoopType.NONE;
	}

	/** 护目镜 / 调试用的一行描述。 */
	public String describeHead() {
		StringBuilder sb = new StringBuilder("task[").append(name)
			.append("] loop=")
			.append(loop);
		if (loop == LoopType.COUNT)
			sb.append('x')
				.append(loopCount);
		sb.append(" entries=")
			.append(entries.size());
		return sb.toString();
	}

	/** 调试用：把任务里的操作逐条列成文本。 */
	public void describeInto(List<String> out, IntFunction<String> linePrefix) {
		out.add(describeHead());
		for (int i = 0; i < entries.size(); i++)
			out.add(linePrefix.apply(i) + entries.get(i)
				.describe());
	}

}
