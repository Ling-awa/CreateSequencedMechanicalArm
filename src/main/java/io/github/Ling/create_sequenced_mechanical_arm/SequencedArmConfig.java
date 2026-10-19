package io.github.Ling.create_sequenced_mechanical_arm;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 本模组的配置（NeoForge {@link ModConfigSpec}，{@code ModConfig.Type.COMMON}）。
 *
 * <p><b>为什么是 COMMON 而不是 SERVER</b>：这里的三个数<b>两端都要用</b> —— 客户端编辑器要拿它们
 * 画提示、挡 ＋/复制（{@code ArmProgramScreen}），服务端要拿它们裁决落盘（{@code ArmProgram.isWithinLimits()}
 * / {@code SequencedArmBlockEntity} 的信号表）。COMMON 在<b>两端各自加载自己那份 {@code config/} 文件</b>，
 * 单人游戏里两者是同一个文件；专用服务器上则可能出现"客户端显示 32、服务端按 8 裁决"的不一致
 * （界面只是提前告知，真正的裁决永远在服务端 —— PLAN §5.2「已知边界」）。
 *
 * <p><b>读取纪律</b>：访问器（{@link #maxTasks()} 等）在<b>配置还没加载</b>时回落到默认值，
 * 绝不在类初始化阶段直接 {@code get()}（那会抛 {@code IllegalStateException}）。
 * 这是刻意的：{@code ArmProgram} / {@code SequencedArmBlockEntity} 的静态初始化、以及任何早期代码路径
 * 都可能碰到这些数，NPE/异常的代价远大于"早期读到默认值"。
 *
 * <p>⚠️ 这三个值都是<b>运行期</b>数量，<b>不得</b>被当作编译期常量使用（数组长度、注解、switch case 等）。
 */
public final class SequencedArmConfig {

	/** 默认任务数上限（主任务 + 子任务库）。 */
	public static final int DEFAULT_MAX_TASKS = 32;
	/** 默认同时保持的信号条数上限。 */
	public static final int DEFAULT_MAX_HELD_SIGNALS = 64;
	/** 默认条目总数上限（全程序合计）。1024 = "60 KiB 文本上限先响之前够用"的档位（§17.4）。 */
	public static final int DEFAULT_MAX_ENTRIES_TOTAL = 1024;
	/** 默认推进速率：每 tick 2 条（用户定稿的默认值）。 */
	public static final double DEFAULT_ENTRIES_PER_TICK = 2.0;
	/** 默认运行日志行数（环形缓冲，0 = 关闭）。 */
	public static final int DEFAULT_LOG_LINES = 256;
	/** 运行日志行数的上限 —— 再多就是拿内存换几乎没人会翻的旧历史。 */
	public static final int MAX_LOG_LINES = 4096;
	/**
	 * 推进速率的**下限**。
	 *
	 * <p>为什么不是 {@code 0.01}（那样一条要 100 tick = 5 秒，护目镜上确实"看得清"）：
	 * 每 tick 推进多少条是给**长控制链**提速用的，不是"逐帧慢放"用的 —— 用户要的慢档示例是
	 * 0.5（2 tick 一条）。0.05 = **20 tick 一条**（1 秒一条）已经足够慢，再往下只会在
	 * 无动作条目串上造成"看不出它在跑"的假死观感，而代价（几十秒不推进）没人会想要。
	 */
	public static final double MIN_ENTRIES_PER_TICK = 0.05;
	/**
	 * 推进速率的**上限** = 64。
	 *
	 * <p>理由只有一条：**配置值同时是"一条自跳转脚本能在一个 tick 里跑多少条"的硬上限**
	 * （见 {@code ProgramRuntime.maxEntriesPerTick()}）。速率再大，单 tick 的工作量也不会
	 * 无界增长，服务器不会因为一个跳转自环被卡住。64 已经远超"看得清流程"的数倍，
	 * 而 1.21 的动力臂本身还有动画/相位节流，再快只是把空转条目塞进同一 tick。
	 */
	public static final double MAX_ENTRIES_PER_TICK = 64.0;

	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	/**
	 * 一张操作表最多能存多少个任务。
	 *
	 * <p>条目的<b>数量</b>不影响单包体积（每个任务只是几条 NBT），所以可以放得比较开。
	 */
	public static final ModConfigSpec.IntValue MAX_TASKS = BUILDER
		.comment("Maximum number of tasks one operation table can hold (main task + sub-task library).",
			"一张操作表最多能存多少个任务（主任务 + 子任务库）。默认 32。")
		.defineInRange("maxTasks", DEFAULT_MAX_TASKS, 1, 1024);

	/**
	 * 同时保持的信号条数上限（§7.16e 的"持久变量"表）。
	 */
	public static final ModConfigSpec.IntValue MAX_HELD_SIGNALS = BUILDER
		.comment("Maximum number of redstone-link frequencies the arm can hold at the same time.",
			"一条臂同时保持的信号（红石链接频率）条数上限。默认 64。")
		.defineInRange("maxHeldSignals", DEFAULT_MAX_HELD_SIGNALS, 1, 1024);

	/**
	 * 条目总数上限（全程序合计）。
	 *
	 * <p>盘上存的是**脚本文本**（PLAN.md §17），所以真正的天花板不是"编译后 NBT 的体积"，
	 * 而是"整份程序转成脚本后的字节数" —— 上限 {@code ProgramText.MAX_BYTES} = 60 KiB，
	 * 依据是 **NBT 字符串的 16 位长度**（65535 字节，§17.4）：超过它不是"存得下但很大"，
	 * 而是**区块存盘时会抛异常**。
	 *
	 * <p>所以这个值也不是"越大越好"：填得比文本上限装得下的条目数还大，多出来的部分在保存时
	 * 会被明确拒绝（整份不落盘）。默认 <b>1024</b> 是"文本上限先响之前够用"的档位。
	 *
	 * <p><b>到底能放多少条由实测给</b>：启动自检的 {@code checkProgramSize} 每次都会打印
	 * "一条的脚本字节数"与"60 KiB ÷ 每条"的条数区间 —— 那些数字随物品 id 长度、备注语言（汉字 3 字节/字）
	 * 变化，写死在这里必然过期。
	 */
	public static final ModConfigSpec.IntValue MAX_ENTRIES_TOTAL = BUILDER
		.comment("Maximum total number of entries across the whole program.",
			"整份程序的条目总数上限（所有任务合计）。默认 1024 —— 见下面的实测说明。",
			"Storage note: a program is saved as .sma TEXT (not as compiled NBT), so the real ceiling is",
			"the 60 KiB text limit (NBT strings are 16-bit: 65535 bytes). A setting larger than what the",
			"text limit can hold simply gets refused at save time (the whole program is rejected, never",
			"silently truncated). See the selftest log line for the measured entries-per-60KiB.",
			"存储说明：盘上存的是 .sma 文本，所以真正的天花板是 60 KiB 的文本上限（NBT 字符串 16 位 = 65535 字节）。",
			"填得比文本装得下的还大，多出来的部分在保存时会被整份拒绝（不是静默丢）。能放多少条见自检日志。")
		.defineInRange("maxEntriesTotal", DEFAULT_MAX_ENTRIES_TOTAL, 1, 4096);

	/**
	 * 每 tick 推进多少条**无动作**条目（小数跨 tick 累积）。
	 *
	 * <p><b>0.5 = 每 2 tick 一条；2 = 每 tick 两条</b>（默认 2）。取值范围
	 * {@value #MIN_ENTRIES_PER_TICK} – {@value #MAX_ENTRIES_PER_TICK}，理由见那两个常量的注释。
	 *
	 * <p>它只影响"一瞬间就出结果"的条目（控制类：过滤 / 收信号 / 调用 / 退出 / 抛错 / 发信号）。
	 * <b>有动作的条目（移动 / 等待 / 重试）一点都不变快</b>：它们照样按真实 tick 走
	 * （判定在 {@code ProgramRuntime.isPaced}），所以抬手/等待的观感与旧版逐字节相同。
	 */
	public static final ModConfigSpec.DoubleValue ENTRIES_PER_TICK = BUILDER
		.comment("How many target-less entries the arm advances per tick (fractional values accumulate across ticks).",
			"每 tick 推进多少条「一瞬间就出结果」的条目（小数会跨 tick 累积）。",
			"0.5 = one entry every 2 ticks; 2 = two entries per tick (default).",
			"0.5 = 每 2 tick 一条；2 = 每 tick 两条（默认）。",
			"Only control-ish entries are paced by this: filter / receive / call / exit / throw / signal send.",
			"Entries that move the arm or wait (move / wait / retry) are NOT sped up",
			"by this setting: they still take real ticks, exactly like the old behaviour.",
			"只对控制类条目生效；有动作的条目（移动 / 等待 / 重试）不受它影响，仍然按真实 tick 走。")
		.defineInRange("entriesPerTick", DEFAULT_ENTRIES_PER_TICK, MIN_ENTRIES_PER_TICK, MAX_ENTRIES_PER_TICK);

	/**
	 * 每条臂在**内存里**保留多少行运行日志（环形缓冲，满了丢最旧的）。
	 *
	 * <p><b>0 = 关闭</b>：那种情况下 {@code SequencedArmBlockEntity.logLine} 一行都不产生，
	 * 而且每个记录点都是"先判配置、再拼字符串"（拼串的代价绝不能付在一个被关掉的功能上）。
	 *
	 * <p>数值越大越占内存：一行 ≈ 一个字符串对象 + 几十字节内容（`[+37t] 主任务#4 take 失败：目标为空`），
	 * 按 64 字节估，256 行/臂 ≈ 16 KiB/臂 —— 单条臂可以忽略，但**每个方块实体一份**，
	 * 所以别在大基地里随手填 4096（那是 256 KiB/臂）。
	 *
	 * <p>为什么默认 256：{@link #entriesPerTick()} = 2 时正常情况下每秒只产生几行（只在状态变化处记），
	 * 256 行足够回看几分钟的运行史；玩家要"导出最近的日志"时，一页剪贴板也装得下问题的现场。
	 */
	public static final ModConfigSpec.IntValue LOG_LINES = BUILDER
		.comment("How many run-log lines each arm keeps in memory (ring buffer; 0 = off).",
			"The log is what 'sneak-use a Create clipboard on the arm' writes into that clipboard:",
			"entry starts, failure/retry reasons, jump landings, call stack push/pop and halts.",
			"每条臂在内存里保留多少行运行日志（环形缓冲，满了丢最旧的）。0 = 关闭，一行都不记录。",
			"数值越大越占内存：每行约几十字节（一行 ≈ 64 字节，256 行 ≈ 16 KiB/臂）。",
			"日志只在内存里（不写存档），内容 = 潜行右键持剪贴板时写进那块剪贴板的东西。",
			"默认 256：正常节奏每秒只有几行，够回看几分钟；0 = 关闭记录（省内存 / 不想让玩家导出）。")
		.defineInRange("logLines", DEFAULT_LOG_LINES, 0, MAX_LOG_LINES);

	public static final ModConfigSpec SPEC = BUILDER.build();

	private SequencedArmConfig() {}

	/** 配置是否已经加载（未加载时下面几个访问器回落默认值）。 */
	public static boolean isLoaded() {
		return SPEC.isLoaded();
	}

	/** 任务数上限（配置未加载 ⇒ {@link #DEFAULT_MAX_TASKS}）。 */
	public static int maxTasks() {
		return read(MAX_TASKS, DEFAULT_MAX_TASKS);
	}

	/** 同时保持的信号上限（配置未加载 ⇒ {@link #DEFAULT_MAX_HELD_SIGNALS}）。 */
	public static int maxHeldSignals() {
		return read(MAX_HELD_SIGNALS, DEFAULT_MAX_HELD_SIGNALS);
	}

	/** 条目总数上限（配置未加载 ⇒ {@link #DEFAULT_MAX_ENTRIES_TOTAL}）。 */
	public static int maxEntriesTotal() {
		return read(MAX_ENTRIES_TOTAL, DEFAULT_MAX_ENTRIES_TOTAL);
	}

	/**
	 * 每 tick 推进多少条无动作条目（配置未加载 ⇒ {@link #DEFAULT_ENTRIES_PER_TICK}）。
	 *
	 * <p>与上面几个整型访问器同一套懒读纪律：**运行期每次现读**，绝不在类初始化阶段 {@code get()}。
	 */
	public static double entriesPerTick() {
		return read(ENTRIES_PER_TICK, DEFAULT_ENTRIES_PER_TICK);
	}

	/**
	 * 运行日志行数（配置未加载 ⇒ {@link #DEFAULT_LOG_LINES}）。
	 *
	 * <p>与上面几个访问器同一套懒读纪律；`0` 是一个**合法值**（= 关闭记录），
	 * 所以调用方必须用 `<= 0` 判"关掉"，不要拿 0 当"没配"。
	 */
	public static int logLines() {
		return read(LOG_LINES, DEFAULT_LOG_LINES);
	}

	/**
	 * 生效值的一行摘要（自检/日志用）。
	 *
	 * <p>两端各读自己那份 {@code config/} 文件 ⇒ 这行日志就是"这一侧到底按什么数在跑"的证据。
	 */
	public static String effectiveSummary() {
		return "配置: 任务上限=" + maxTasks() + " 信号上限=" + maxHeldSignals() + " 条目上限=" + maxEntriesTotal()
			+ " 推进速率=" + entriesPerTick() + " 条/tick 日志行数=" + logLines() + " (specLoaded=" + isLoaded() + ")";
	}

	private static int read(ModConfigSpec.IntValue value, int fallback) {
		// 不 catch 异常：`get()` 在未加载时抛 IllegalStateException，我们**先问 isLoaded()**，
		// 于是"未加载"这条路径根本不进 get()，也就不会在早期代码里炸出一个看不懂的异常。
		return isLoaded() ? value.get() : fallback;
	}

	private static double read(ModConfigSpec.DoubleValue value, double fallback) {
		return isLoaded() ? value.get() : fallback;
	}
}
