package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.ArrayList;
import java.util.List;

import io.github.Ling.create_sequenced_mechanical_arm.SequencedArmConfig;

import net.minecraft.core.BlockPos;
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
	 * <p>⚠️ 同上：运行期值。这是**名义**上限；盘上真正的天花板是
	 * {@link ProgramText#MAX_BYTES}（整份程序转成脚本后的字节数，依据见 PLAN.md §17.4）——
	 * 超了在保存时被整份拒绝（不静默截断），能放多少条见自检 {@code checkProgramSize} 的实测日志。
	 */
	public static int maxEntriesTotal() {
		return SequencedArmConfig.maxEntriesTotal();
	}

	/**
	 * ★ <b>这里曾经有个 {@code origin}/{@code hasOrigin}</b>（一份程序一个录制参考点）——
	 * 用户第四十轮把它<b>搬到了卡上</b>（{@code CardProgramStorage#readOrigin}）。
	 *
	 * <p>为什么它不属于程序：条目的 {@link ArmProgramEntry#offset} <b>永远相对"执行它的那条臂"</b>
	 * （世界目标 = {@code arm.getBlockPos().offset(offset)}，见 {@code ProgramRuntime.offsetOf}），
	 * 运行时<b>不看</b>原点、插卡也<b>不做任何平移</b>（曾经的 {@code ProgramCoordinates.rebaseTo}
	 * 已删除：它会把卡上的 {@code 0 0 0} 平移到臂的位置，于是"卡里写 0 0 0"在臂里变成"指向世界原点"，
	 * 正是用户报的那个 bug）。原点只剩两件事：录制时把世界坐标换算成偏移
	 * （{@code offset = 世界位置 − 原点}）、以及在世界上画那个蓝框。
	 *
	 * <p>而这两件事都是"玩家手上这张卡"的语境 ⇒ 宿主是卡（还有 {@code .sma} 文本：原点写进文本
	 * 等于"导出 → 改 → 导入"一圈回来坐标的参考系会跟着别人走，所以文本里也彻底删掉了
	 * {@code @origin} 头，解析到它直接报错）。臂内那份程序压根不与录制打交道，复制来复制去只会
	 * 制造"这个数到底是谁的"的疑问。
	 */
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
	 * <p><b>它们不在 `.sma` 文本里</b>（§17.9）：文本描述的是**程序**，而这两个字段是**玩家怎么看这份程序**。
	 * 写进文本的话，`/sma code import` 一份别人的脚本就会顺手改掉你的模式；反过来，你在文本里手写
	 * `mode=EDIT` 也毫无意义。所以盘上它们与文本**并排**存在卡上（见 {@code CardProgramStorage}）。
	 *
	 * <p>⚠️ §17 之前它们寄生在卡里的**程序 NBT** 上 —— 那会儿的理由是"这张卡的程序本来就是一个随卡走、
	 * 且由服务端权威写入的 NBT 块，塞两个字段就自动获得换物品不丢 / 重登不丢 / 服务端认账"。
	 * 存储改成文本之后这句话不成立了，少了这处安身之地，症状就是"切换物品又回到录入模式"。
	 *
	 * <p>臂装载时这两个字段会被忽略（运行时只读 tasks）。
	 */
	public static final String MODE_RECORD = "RECORD";
	public static final String MODE_EDIT = "EDIT";

	public String editorMode = MODE_RECORD;
	/** 正在编辑/录入的任务下标（1 基序号 - 1 存这里；越界时**读回来**会夹住 —— 见 {@link #clampEditorTask()}）。 */
	public int editorTask;

	/**
	 * 把 {@code editorTask} 夹进合法范围（{@code 0 .. 任务数-1}；空程序 = 0）。
	 *
	 * <p>**这条规则只有一处**：载入 NBT 与"从卡上读回编辑状态"共用它。界面把它直接当索引用，
	 * 越界（改坏的 NBT、删任务后残留的旧下标、导入了一份更短的脚本）就是打开编辑器当场崩。
	 * 调用时机必须在**任务都读完**之后。
	 */
	public void clampEditorTask() {
		int last = Math.max(0, tasks.size() - 1);
		if (editorTask < 0)
			editorTask = 0;
		else if (editorTask > last)
			editorTask = last;
	}

	/**
	 * 把<b>所有任务里</b>有目标的条目的 {@link ArmProgramEntry#offset} 各加上 {@code (dx,dy,dz)}，
	 * 返回真正被改动的条目数。
	 *
	 * <p>这是任务列表页那个「坐标偏移」按钮的<b>全部逻辑</b>（界面只负责解析文本、压一次撤销、落盘）。
	 * 为什么必须住在模型里：它要能被<b>服务端启动自检</b>直接调用，而 GUI 类在专用服务器上根本不可用
	 * （§5.2）—— 判据写在界面里就等于"只有开客户端才测得到"。
	 *
	 * <p><b>只动 {@code requiresTarget()} 的条目</b>：其它操作（等待 / 收发信号 / 调用 /
	 * 退出 / 抛错）的 {@code offset} 没有任何意义（运行时压根不读它，见 §19.7 的
	 * "世界目标 = 臂位置 + offset"），给它们加一个数只会制造噪声：`.sma` 文本里多出一行
	 * 谁都没写的坐标，玩家之后看到会以为是目标，删起来还得一条条排查。
	 *
	 * <p>★ <b>「调用」条目的 {@link ArmProgramEntry#callShift}（调用偏移）有意**不搬**</b>，
	 * 别"顺手"把它也加进来：它是**相对量**（"这个调用点让子任务错开多少"），而子任务自己的
	 * {@code offset} 在上面那一轮里已经跟着搬过一次了 —— 再搬一次就是**加两遍**，
	 * 表现为"整套搬完，被调任务里的目标跑到两倍远处"。
	 *
	 * <p>{@code (0,0,0)} 直接返回 0、一个条目都不碰：界面按返回值决定"要不要记一步撤销"，
	 * 空操作压栈就是白记一步（撤销一次看起来什么都没发生）。
	 *
	 * <p>坐标用 long 相加再夹回 {@link BlockPos} 的合法区间：两个 3000 万的坐标相加会 int 溢出，
	 * 溢出后的坐标是负数/乱数，落盘之后<b>没有任何报错</b>，只会让臂跑去地图另一头。
	 */
	public int shiftAllOffsets(int dx, int dy, int dz) {
		if (dx == 0 && dy == 0 && dz == 0)
			return 0;
		int changed = 0;
		for (ArmProgramTask task : tasks)
			changed += shiftTaskOffsets(task, dx, dy, dz);
		return changed;
	}

	/**
	 * 只搬**一个任务**里所有有目标条目的坐标（用户 2026-10-20：「操作界面」上按偏移只动当前任务）。
	 *
	 * <p>与 {@link #shiftAllOffsets} 同一套判据（条目级 {@code needsTarget()}、long 相加再夹回、
	 * 调用偏移不搬）—— 差别只有作用域。抽成这个入口而不是让界面自己遍历，理由同上面那条：
	 * 判据要能被服务端自检直接调用。
	 */
	public int shiftTaskOffsets(ArmProgramTask task, int dx, int dy, int dz) {
		if (task == null || (dx == 0 && dy == 0 && dz == 0))
			return 0;
		int changed = 0;
		for (ArmProgramEntry entry : task.entries) {
			// ★ 问**条目级**的 needsTarget()（用户"过滤位置"二选一）：过滤的容器模式有真坐标，
			//   整套平移必须带上它 —— 漏了它就会出现"箱子搬了、过滤还在看旧址"。
			//   手持过滤没有坐标、不在这里动（给它加一个数只会制造噪声）。
			if (!entry.needsTarget())
				continue;
			entry.offset = shift(entry.offset, dx, dy, dz);
			changed++;
		}
		return changed;
	}

	/** 单个坐标的加法（long 中间量 + 夹回合法区间），见 {@link #shiftAllOffsets}。 */
	private static BlockPos shift(BlockPos pos, int dx, int dy, int dz) {
		int x = clampCoord((long) pos.getX() + dx);
		int y = clampCoord((long) pos.getY() + dy);
		int z = clampCoord((long) pos.getZ() + dz);
		return new BlockPos(x, y, z);
	}

	/**
	 * 坐标的合法区间 —— 与<b>脚本解析器同一个数</b>（{@code ScriptParser.COORD_LIMIT = 30000000}）。
	 *
	 * <p>为什么是 3 千万：`.sma` 文本里手写的坐标不能超过它（超了是带行号的硬错），
	 * 而界面上平移出来的坐标**一样要存成文本** —— 两边取不同的上限，就会出现"界面上搬得出来、
	 * 落盘时被编译器拒掉"，症状是"改完保存失败"而不是"坐标太大"。
	 *
	 * <p>为什么这里夹住而不是抛异常：平移是<b>批量</b>操作 —— 一条越界的条目让整批半途而废
	 * （前面几条已改、后面几条没改）比夹住更难排查，而夹住的后果是可预期的（停在世界的边界上）。
	 *
	 * <p>{@code public} 是给界面与自检用的：界面上那个输入框必须**用同一个数**拒掉超限的输入
	 * （否则"输进去 4000 万"要等到保存那一步才失败，玩家看到的提示是"保存失败"而不是"坐标太大"）。
	 */
	public static final int COORD_LIMIT = 30_000_000;

	private static int clampCoord(long value) {
		if (value > COORD_LIMIT)
			return COORD_LIMIT;
		if (value < -COORD_LIMIT)
			return -COORD_LIMIT;
		return (int) value;
	}

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
		// ⚠️ 这里曾经写过 `origin` 键（录制原点）—— 用户第四十轮把它搬到了卡上
		// （{@code CardProgramStorage.writeOrigin}）。**不要把原点加回来**：它既不参与运行时
		// （世界目标 = 臂坐标 + offset），也不该让"编辑一条程序"顺手改掉别人卡上的参考点。
		ListTag list = new ListTag();
		for (ArmProgramTask task : tasks)
			list.add(task.save(registries));
		tag.put("tasks", list);
		tag.putString("emode", editorMode);
		tag.putInt("etask", editorTask);
		// 非空才写（同 sigmode 的省字节做法，§12.3b）：程序备注是"大多数卡上都没有"的字段，
		// 空字符串写进去只会让脚本里多一行无用的键
		if (!note.isEmpty())
			tag.putString("note", note);
		return tag;
	}

	public static ArmProgram load(CompoundTag tag, HolderLookup.Provider registries) {
		ArmProgram program = new ArmProgram();
		// ⚠️ 老标签里可能还有 `origin` 键：**刻意不读**（用户明确说不做兼容）。原点现在住在卡上，
		// 读进来的话"这份程序带着别人的参考点到处走"就会重新出现（录制换算会用到它）。
		program.editorMode = tag.contains("emode") ? tag.getString("emode") : MODE_RECORD;
		program.editorTask = Math.max(0, tag.getInt("etask"));
		// 备注**不在这里夹**：夹（截断）会让"文本 → 模型 → 文本"丢字（不变量 #1 死在这一步）。
		// 上限的守门人只有两个：编译器（超了报错带行号，见 ScriptParser）与反编译后的文本本身。
		program.note = tag.getString("note");
		ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++)
			program.tasks.add(ArmProgramTask.load(list.getCompound(i), registries));
		// 会话状态里的任务下标必须在**读完任务之后**夹到合法范围：它会被界面直接当索引用，
		// 越界（改坏的 NBT、或删任务后残留的旧下标）就是打开编辑器当场崩。
		program.clampEditorTask();
		return program;
	}

	public List<String> describe() {
		List<String> out = new ArrayList<>();
		// ⚠️ 这里原来第一行是「原点 xxx / 未设置」。用户第四十轮把原点搬到了卡上（它不参与运行时、
		// 也不进 `.sma` 文本）⇒ 这份**程序**的自我描述里不该再有它。`/sma info` 看的是臂里跑的那份程序，
		// 它压根不与录制打交道，报一个从别处抄来的参考点只会误导排查。
		for (ArmProgramTask task : tasks)
			task.describeInto(out, i -> "  [" + i + "] ");
		return out;
	}

}
