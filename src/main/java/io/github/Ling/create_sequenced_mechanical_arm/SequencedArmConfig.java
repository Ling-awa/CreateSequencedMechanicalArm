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
	 * 生效值的一行摘要（自检/日志用）。
	 *
	 * <p>两端各读自己那份 {@code config/} 文件 ⇒ 这行日志就是"这一侧到底按什么数在跑"的证据。
	 */
	public static String effectiveSummary() {
		return "配置: 任务上限=" + maxTasks() + " 信号上限=" + maxHeldSignals() + " 条目上限=" + maxEntriesTotal()
			+ " (specLoaded=" + isLoaded() + ")";
	}

	private static int read(ModConfigSpec.IntValue value, int fallback) {
		// 不 catch 异常：`get()` 在未加载时抛 IllegalStateException，我们**先问 isLoaded()**，
		// 于是"未加载"这条路径根本不进 get()，也就不会在早期代码里炸出一个看不懂的异常。
		return isLoaded() ? value.get() : fallback;
	}
}
