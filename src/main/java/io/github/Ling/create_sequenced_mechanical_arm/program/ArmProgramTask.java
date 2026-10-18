package io.github.Ling.create_sequenced_mechanical_arm.program;

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
 * 一个任务（PLAN.md §4.1 的 Task）：一串有序操作。
 *
 * <p><b>§7.6 v4：单线程 + 调用栈</b>。任务不再有优先级 / 频率门控 ——
 * 列表里的<b>第一个任务就是主任务</b>（默认执行），其余任务只能被
 * {@link OperationType#CALL 调用}触发。红石链接频率搬进了「收发信号」操作，
 * 于是"这个任务要通电才跑"变成任务开头的第一条 `signal recv` 条目。
 *
 * <p>★ <b>任务级循环已删除</b>（用户口径："任务循环次数已经不需要了，因为只有一个主任务有这个属性，
 * 但是主任务是不断循环的"）：<b>主任务恒为无限循环</b>（走完最后一条就回到第 1 条，永久重复），
 * 要收尾只能用「退出」(收工停机) 或「抛错」(失败停机)；<b>子任务</b>则跑完就返回调用方，
 * 跑几轮由**调用点**的 {@code callRepeat} 决定。所以任务上只剩名字 / 备注 / 图标 + 条目表。
 */
public class ArmProgramTask {

	public String name = "task";
	/**
	 * 任务备注（PLAN.md §12.3b，NBT 键 {@code note}）：{@code task} 那一行的行尾注释，
	 * 或紧跟其后的独占行注释块。
	 *
	 * <p>与条目备注共用同一个上限 {@link ArmProgramEntry#NOTE_MAX}（决策 68/85：同一件事不写两份判据），
	 * 存的是**真换行**（多行注释原样进来）。GUI 里**没有**编辑入口 —— 它只由代码编辑 / 导入写（P1）。
	 */
	public String note = "";
	/** 任务图标（可选）：编辑器幽灵槽里的物品，空 = 用默认的任务图标。 */
	public ItemStack icon = ItemStack.EMPTY;
	public final List<ArmProgramEntry> entries = new ArrayList<>();

	public CompoundTag save(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putString("name", name);
		// ★ 任务级循环（老的 `loop` / `loopN` 两个键）**已删、且不做兼容读取**（用户明确不要向后兼容）：
		//   老卡里那两个键读的时候直接无视，下一次保存它们就消失了。
		// 非空才写（同 sigmode 的省字节做法，§12.3b）
		if (!note.isEmpty())
			tag.putString("note", note);
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
		// 备注不在这里夹（见 ArmProgram.load 的说明）：截断会破坏"文本 → 模型 → 文本"的不变量 #1
		task.note = tag.getString("note");
		if (tag.contains("icon"))
			task.icon = ItemStack.parseOptional(registries, tag.getCompound("icon"));
		ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++)
			task.entries.add(ArmProgramEntry.load(list.getCompound(i), registries));

		// ——— v3 → v4：任务的频率门控搬成任务开头的「收发信号（收）」条目 ———
		// 老卡片的意思是"这个任务要通电（或断电）才跑"，直接丢掉会让它从"门控"变成"常驻"，
		// 静默改变行为；写成第一条「收发信号（收）」就完全等价（signalMode 缺省 = RECEIVE，行为不变）。
		Couple<Frequency> legacy = readLegacyFrequency(tag, registries);
		if (legacy != null) {
			ArmProgramEntry gate = new ArmProgramEntry();
			gate.op = OperationType.SIGNAL;
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

	/** 护目镜 / 调试用的一行描述。 */
	public String describeHead() {
		return "task[" + name + "] entries=" + entries.size();
	}

	/** 调试用：把任务里的操作逐条列成文本。 */
	public void describeInto(List<String> out, IntFunction<String> linePrefix) {
		out.add(describeHead());
		for (int i = 0; i < entries.size(); i++)
			out.add(linePrefix.apply(i) + entries.get(i)
				.describe());
	}

}
