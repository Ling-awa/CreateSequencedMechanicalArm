package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.ArrayList;
import java.util.List;

import io.github.Ling.create_sequenced_mechanical_arm.SequencedArmConfig;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 操作表（PLAN.md §4.1 的 ArmProgram）：一组任务。
 *
 * <p>调度语义见 §7.6：任务按优先级取可执行者（同优先级按静态声明顺序），
 * 频率不满足的任务挂起并保留游标；切换粒度是<b>条目边界</b>，绝不中途抢占。
 */
public class ArmProgram {

	/**
	 * 任务数上限（配置项 {@code maxTasks}，默认 {@link SequencedArmConfig#DEFAULT_MAX_TASKS}）。
	 *
	 * <p>⚠️ 这是<b>运行期</b>读出来的数（配置未加载时回落默认值），**不是**编译期常量：
	 * 不要拿它当数组长度、注解参数或 switch case。
	 */
	public static int maxTasks() {
		return SequencedArmConfig.maxTasks();
	}

	/**
	 * 条目总数上限（配置项 {@code maxEntriesTotal}，默认 {@link SequencedArmConfig#DEFAULT_MAX_ENTRIES_TOTAL}）。
	 *
	 * <p>⚠️ 同上：运行期值。条目越多整份程序序列化越大，超过 {@code ArmProgramEditPacket.MAX_TAG_BYTES}
	 * （128KB）会在保存时被整份拒绝。
	 */
	public static int maxEntriesTotal() {
		return SequencedArmConfig.maxEntriesTotal();
	}

	/** {@code RELATIVE} 表示条目里的坐标是相对手臂的（臂内部表示）。 */
	public boolean absolute = false;
	public final List<ArmProgramTask> tasks = new ArrayList<>();

	/**
	 * 程序备注（PLAN.md §12.3b，NBT 键 {@code note}）：脚本文件最前面那段块注释。
	 *
	 * <p>与条目备注（{@link ArmProgramEntry#note}）共用同一个上限 {@link ArmProgramEntry#NOTE_MAX} 与
	 * 同一套写法 —— 判据写两份必然漂移（决策 68/85），所以连"多行怎么存"都一模一样（存真换行）。
	 *
	 * <p><b>只由代码编辑 / 导入写</b>（GUI 里没有编辑入口，P1 不做）：这正是"文件头注释最稳"的形态 ——
	 * GUI 根本不碰它，于是"GUI 改完再导出，注释还在"（§12.3a）。
	 */
	public String note = "";

	/**
	 * 世界内录入的"会话状态"：当前是录入还是编辑模式、正在编辑哪个任务。
	 *
	 * <p>为什么放在程序里而不是新建物品组件：这张卡的程序本来就是一个随卡走、且由服务端权威写入的
	 * NBT 块（录制/编辑都走它），塞两个字段就自动获得了"换物品不丢、重登不丢、服务端认账"三件事，
	 * 不用再开组件 + 同步通道。臂装载时这两个字段会被忽略（运行时只读 tasks）。
	 */
	public String editorMode = "RECORD";
	/** 正在编辑/录入的任务下标（1 基序号 - 1 存这里，越界时运行前会夹住）。 */
	public int editorTask;

	public boolean isEmpty() {
		return tasks.isEmpty();
	}

	public ArmProgramTask task(int index) {
		return index >= 0 && index < tasks.size() ? tasks.get(index) : null;
	}

	/**
	 * 还能再建一个任务吗。
	 *
	 * <p><b>为什么要有这个方法</b>：服务端是"<b>整份接受或整份拒绝</b>"（{@code ArmProgramEditPacket}
	 * 只判 {@link #isWithinLimits()}），所以编辑器必须在"<b>加到上限的那一刻</b>"就挡住 ——
	 * 放任玩家加到第 {@code maxTasks() + 1} 个，关界面时整份程序被拒，<b>这一场编辑一个字都落不了盘</b>
	 * （只有聊天栏一行红字），而玩家看到的是"编辑全都白做了"。
	 *
	 * <p>上限值只在这里和 {@link #isWithinLimits()} 各出现一次，两处共读同一个配置项 ——
	 * 界面用的守卫与服务端用的守卫必须是同一个判据，否则又会走回"能加但存不下"。
	 */
	public boolean canAddTask() {
		return tasks.size() < maxTasks();
	}

	/** 服务端写入前的大小守卫（PLAN.md §4.1）。 */
	public boolean isWithinLimits() {
		if (tasks.size() > maxTasks())
			return false;
		int total = 0;
		for (ArmProgramTask task : tasks) {
			total += task.entries.size();
			if (task.entries.size() > maxEntriesTotal())
				return false;
		}
		return total <= maxEntriesTotal();
	}

	public CompoundTag save(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("abs", absolute);
		ListTag list = new ListTag();
		for (ArmProgramTask task : tasks)
			list.add(task.save(registries));
		tag.put("tasks", list);
		tag.putString("emode", editorMode);
		tag.putInt("etask", editorTask);
		// 非空才写（同 sigmode 的省字节做法，§12.3b）：程序备注是"大多数卡上都没有"的字段，
		// 空字符串写进去只会让 128KB 预算里多占一份无用的键
		if (!note.isEmpty())
			tag.putString("note", note);
		return tag;
	}

	public static ArmProgram load(CompoundTag tag, HolderLookup.Provider registries) {
		ArmProgram program = new ArmProgram();
		program.absolute = tag.getBoolean("abs");
		program.editorMode = tag.contains("emode") ? tag.getString("emode") : "RECORD";
		program.editorTask = Math.max(0, tag.getInt("etask"));
		// 备注**不在这里夹**：夹（截断）会让"文本 → 模型 → 文本"丢字（不变量 #1 死在这一步）。
		// 上限的守门人只有两个：编译器（超了报错带行号，见 ScriptParser）与反编译后的文本本身。
		program.note = tag.getString("note");
		ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++)
			program.tasks.add(ArmProgramTask.load(list.getCompound(i), registries));
		// 会话状态里的任务下标必须在**读完任务之后**夹到合法范围：它会被界面直接当索引用，
		// 越界（改坏的 NBT、或删任务后残留的旧下标）就是打开编辑器当场崩。
		if (program.editorTask >= program.tasks.size())
			program.editorTask = Math.max(0, program.tasks.size() - 1);
		return program;
	}

	public List<String> describe() {
		List<String> out = new ArrayList<>();
		for (ArmProgramTask task : tasks)
			task.describeInto(out, i -> "  [" + i + "] ");
		return out;
	}

}
