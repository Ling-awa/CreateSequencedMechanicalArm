package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 阶段 2 的开发预设操作表（卡片与 GUI 在阶段 3 取代它们）。
 *
 * <p>坐标一律是<b>相对手臂</b>的偏移，方便把手臂挪到任何地方测试。
 *
 * <p>★ <b>任务级循环已经删除</b>（用户口径："任务循环次数已经不需要了，因为只有一个主任务有这个属性，
 * 但是主任务是不断循环的"）⇒ 这里的预设**都不再写循环**：主任务恒为无限循环（一轮跑完从头再来），
 * 要"跑固定几轮"只能靠「调用」的 {@code callRepeat}（见 {@link #loop3()}）。
 */
public final class DevPresets {

	private DevPresets() {
	}

	public static Map<String, Supplier<ArmProgram>> all() {
		Map<String, Supplier<ArmProgram>> map = new LinkedHashMap<>();
		map.put("depot_ab", DevPresets::depotAB);
		map.put("loop3", DevPresets::loop3);
		map.put("wait", DevPresets::waitThenPlace);
		map.put("take_or", DevPresets::takeAOrB);
		map.put("unload", DevPresets::unload);
		map.put("deploy", DevPresets::deploy);
		map.put("mine", DevPresets::mine);
		map.put("attack", DevPresets::attack);
		map.put("use", DevPresets::use);
		map.put("use_sneak", DevPresets::useSneak);
		map.put("subtasks", DevPresets::mainWithSubTasks); // 主任务 + 子任务 + 调用/退出/抛错
		map.put("freq", DevPresets::frequency);
		map.put("signal_send", DevPresets::signalSend); // 收发信号：占用 / 解除（用户给的炒锅用法）
		map.put("hold", DevPresets::holdOnly);
		return map;
	}

	// ————————————————————————— 预设 —————————————————————————

	/** 取 (+2,0,0) 的置物台，放到 (0,0,2)；一直搬（主任务恒循环，一轮跑完自动从头再来）。 */
	public static ArmProgram depotAB() {
		ArmProgramTask task = task("depot_ab");
		task.entries.add(entry(OperationType.TAKE, 2, 0, 0));
		task.entries.add(entry(OperationType.DEPOSIT, 0, 0, 2));
		return single(task);
	}

	/**
	 * 同上，但**只跑 3 轮**。
	 *
	 * <p>★ 「跑 N 轮」现在只能由**调用点**表达：任务级的循环设置已删除，
	 * 所以这里改成"主任务调一次「搬」，{@code callRepeat = 3}"——
	 * 子任务跑完一轮返回调用方，调用点再让它跑一轮，正是原来的 COUNT=3 语义。
	 */
	public static ArmProgram loop3() {
		ArmProgram program = new ArmProgram();
		ArmProgramTask main = task("loop3");
		ArmProgramEntry call = new ArmProgramEntry();
		call.op = OperationType.CALL;
		call.callTarget = "搬";
		call.callRepeat = 3;
		call.note = "跑 3 轮（轮次归调用点）";
		main.entries.add(call);
		program.tasks.add(main);

		ArmProgramTask body = task("搬");
		body.entries.add(entry(OperationType.TAKE, 2, 0, 0));
		body.entries.add(entry(OperationType.DEPOSIT, 0, 0, 2));
		program.tasks.add(body);
		return program;
	}

	/** 取 → 等待 200t → 放。 */
	public static ArmProgram waitThenPlace() {
		ArmProgramTask task = task("wait");
		task.entries.add(entry(OperationType.TAKE, 2, 0, 0));

		ArmProgramEntry wait = new ArmProgramEntry();
		wait.op = OperationType.WAIT;
		wait.waitAmount = 10;
		wait.waitUnit = ArmProgramEntry.WaitUnit.SECOND;
		wait.note = "等 10 秒";
		task.entries.add(wait);

		task.entries.add(entry(OperationType.DEPOSIT, 0, 0, 2));
		return single(task);
	}

	/** 扁平 if/else（§7.7 v3）：取 A 等 100t 还没成 → 跳到第 1 条去取 B，最后都放到 (0,0,2)。 */
	public static ArmProgram takeAOrB() {
		ArmProgramTask task = task("take_or");

		ArmProgramEntry a = entry(OperationType.TAKE, 2, 0, 0);
		a.failAmount = 5;
		a.failUnit = ArmProgramEntry.WaitUnit.SECOND;
		a.onTimeout = SkipAction.SKIP_TO;
		a.timeoutTarget = "1";
		a.note = "A 有货就取 A";
		task.entries.add(a);

		ArmProgramEntry b = entry(OperationType.TAKE, 4, 0, 0);
		b.note = "否则取 B";
		task.entries.add(b);

		task.entries.add(entry(OperationType.DEPOSIT, 0, 0, 2));
		return single(task);
	}

	/** 把溢出全部卸进 (0,0,2)：卸到空 + 每次尽可能多 + 时限 600t 到点后跳过。 */
	public static ArmProgram unload() {
		ArmProgramTask task = task("unload");
		ArmProgramEntry entry = entry(OperationType.UNLOAD, 0, 0, 2);
		entry.unloadRepeat = UnloadRepeat.UNTIL_EMPTY;
		entry.unloadAmount = UnloadAmount.MAX;
		entry.failAmount = 30;
		entry.failUnit = ArmProgramEntry.WaitUnit.SECOND;
		entry.onTimeout = SkipAction.CONTINUE;
		task.entries.add(entry);
		return single(task);
	}

	/**
	 * deploying（我们叫"组装"）：对 (2,0,0) 的载具做一次加工 —— 臂手上必须有应用物
	 * （用 {@code /sma hold} 把主手物品放进臂里），载具上放被加工物。
	 */
	public static ArmProgram deploy() {
		ArmProgramTask task = task("deploy");
		ArmProgramEntry entry = entry(OperationType.DEPLOY, 2, 0, 0);
		entry.failAmount = 5;
		entry.failUnit = ArmProgramEntry.WaitUnit.SECOND;
		entry.onTimeout = SkipAction.CONTINUE;
		task.entries.add(entry);
		return single(task);
	}

	/** 挖掘：破坏 (2,0,0) 的方块。注意<b>空手也允许</b>（按决策，与原版机械手刻意不同）。 */
	public static ArmProgram mine() {
		ArmProgramTask task = task("mine");
		task.entries.add(entry(OperationType.MINE, 2, 0, 0));
		return single(task);
	}

	/** 攻击：对 (2,0,0) 挥一击（打那一格里的实体；不判断有没有，挥空也算完成）。 */
	public static ArmProgram attack() {
		ArmProgramTask task = task("attack");
		task.entries.add(entry(OperationType.ATTACK, 2, 0, 0));
		return single(task);
	}

	/** 右键：对 (2,0,0) 右键一次（例如给机器塞物品、开阀门）。 */
	public static ArmProgram use() {
		ArmProgramTask task = task("use");
		task.entries.add(entry(OperationType.USE, 2, 0, 0));
		return single(task);
	}

	/** 右键的潜行变体：某些交互必须潜行才生效。 */
	public static ArmProgram useSneak() {
		ArmProgramTask task = task("use_sneak");
		task.entries.add(entry(OperationType.USE_SNEAK, 2, 0, 0));
		return single(task);
	}

	/**
	 * 调用系统演示（§7.6 v4）：<b>主任务 + 子任务 + 调用栈 + 退出 + 抛错</b>。
	 *
	 * <p>主任务（列表第一个）**恒为无限循环**（模型自带语义，不需要设置）：等信号 → 调用「清溢出」×3 →
	 * 调用「搬一趟」→ 等 1 秒，然后回到第 1 条进下一轮。
	 * "空闲时干什么"由此完全由你自己写，而不是由调度器替你决定。
	 */
	public static ArmProgram mainWithSubTasks() {
		ArmProgram program = new ArmProgram();

		ArmProgramTask main = task("主任务");
		ArmProgramEntry gate = new ArmProgramEntry();
		gate.op = OperationType.SIGNAL;
		gate.frequency = demoFrequency();
		gate.waitForPowered = true;
		gate.note = "通电才跑这一轮";
		main.entries.add(gate);
		ArmProgramEntry clear = call("清溢出");
		clear.callRepeat = 3; // 轮次归**调用点**（任务级循环已删）：让「清溢出」跑 3 轮
		main.entries.add(clear);
		ArmProgramEntry work = call("搬一趟");
		work.onTimeout = SkipAction.CONTINUE; // 子任务失败 → 跳过继续（调用失败没有时限，立刻生效）
		work.note = "失败就跳过";
		main.entries.add(work);
		ArmProgramEntry rest = new ArmProgramEntry();
		rest.op = OperationType.WAIT;
		rest.waitAmount = 1;
		rest.waitUnit = ArmProgramEntry.WaitUnit.SECOND;
		rest.note = "空闲节流：自己定节奏";
		main.entries.add(rest);
		program.tasks.add(main);

		// 子任务 1：跑 3 轮，中途「退出」——演示"不只是固定次数"
		// ⚠️ 轮次归**调用点**（任务级循环已删）：这里就是 main 里那条 `call 清溢出` 的 callRepeat。
		ArmProgramTask unloadTask = task("清溢出");
		unloadTask.entries.add(entry(OperationType.UNLOAD, 0, 0, 2));
		ArmProgramEntry stopEarly = new ArmProgramEntry();
		stopEarly.op = OperationType.BREAK;
		stopEarly.note = "条件满足就提前退出（while 的 break）";
		unloadTask.entries.add(stopEarly);
		program.tasks.add(unloadTask);

		// 子任务 2：取不到就抛错 —— 失败会冒泡到调用方的「失败后」
		ArmProgramTask moveTask = task("搬一趟");
		ArmProgramEntry take = entry(OperationType.TAKE, 4, 0, 0);
		take.failAmount = 5;
		take.failUnit = ArmProgramEntry.WaitUnit.SECOND;
		moveTask.entries.add(take);
		ArmProgramEntry fail = new ArmProgramEntry();
		fail.op = OperationType.THROW;
		fail.errorMessage = "搬一趟：没拿到东西";
		moveTask.entries.add(fail);
		program.tasks.add(moveTask);

		return program;
	}

	/** 频率门控演示（§7.6 v4）：门控现在是任务开头的「收发信号（收）」条目。 */
	public static ArmProgram frequency() {
		ArmProgramTask task = task("freq");
		ArmProgramEntry gate = new ArmProgramEntry();
		gate.op = OperationType.SIGNAL;
		gate.frequency = demoFrequency();
		gate.waitForPowered = true;
		gate.note = "红石链接通电才开始";
		task.entries.add(gate);
		task.entries.add(entry(OperationType.TAKE, 2, 0, 0));
		task.entries.add(entry(OperationType.DEPOSIT, 0, 0, 2));
		return single(task);
	}

	/**
	 * 「收发信号（发）」的演示（§7.16b 一.9）：<b>发通电（一直保持）→ 等 1 秒 → 发断电</b>。
	 *
	 * <p>这正是用户给的炒锅用法：开始用一个锅时把那个频率**按下去**（占用），取完东西再**松开**
	 * （解除占用）。三条锅就是三对频率 —— 复制这张卡、只改频率即可。
	 *
	 * <p>第一条 {@code waitAmount = 0} = 一直保持（不是脉冲）；第三条的持续同样是 0，
	 * 但它发的是**电平 0**（{@code waitForPowered = false}）—— 0 是合法的发送值，那正是"发断电"。
	 */
	public static ArmProgram signalSend() {
		ArmProgramTask task = task("signal_send");

		ArmProgramEntry occupy = new ArmProgramEntry();
		occupy.op = OperationType.SIGNAL;
		occupy.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		occupy.frequency = demoFrequency();
		occupy.waitForPowered = true;
		occupy.waitAmount = 0; // 一直保持 = 占用
		occupy.note = "占用这个频率（一直保持）";
		task.entries.add(occupy);

		ArmProgramEntry hold = new ArmProgramEntry();
		hold.op = OperationType.WAIT;
		hold.waitAmount = 1;
		hold.waitUnit = ArmProgramEntry.WaitUnit.SECOND;
		hold.note = "锅在这 1 秒里干活";
		task.entries.add(hold);

		ArmProgramEntry release = new ArmProgramEntry();
		release.op = OperationType.SIGNAL;
		release.signalMode = ArmProgramEntry.SignalMode.SEND_OFF;
		release.frequency = demoFrequency();
		release.waitForPowered = false; // 电平 0 = 发断电
		release.waitAmount = 0; // 也是"保持"：断了就一直断着
		release.note = "解除占用";
		task.entries.add(release);

		return single(task);
	}

	/** 一条"调用某个任务"的条目。 */
	private static ArmProgramEntry call(String target) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.CALL;
		entry.callTarget = target;
		return entry;
	}

	/** 演示用的频率对：红石火把 + 铁锭（两个物品 = Create 红石链接的一对频率）。 */
	private static Couple<Frequency> demoFrequency() {
		Couple<Frequency> freq = Couple.create(() -> Frequency.EMPTY);
		freq.set(true, Frequency.of(new ItemStack(Items.REDSTONE_TORCH)));
		freq.set(false, Frequency.of(new ItemStack(Items.IRON_INGOT)));
		return freq;
	}

	/** 只取不放：用来观察"臂内满仓后掉落正常落地"的行为（PLAN.md §7.4）。 */
	public static ArmProgram holdOnly() {
		ArmProgramTask task = task("hold");
		ArmProgramEntry take = entry(OperationType.TAKE, 2, 0, 0);
		take.count = 64;
		take.failAmount = 10;
		take.failUnit = ArmProgramEntry.WaitUnit.SECOND;
		take.onTimeout = SkipAction.CONTINUE;
		task.entries.add(take);
		return single(task);
	}

	// ————————————————————————— 构造辅助 —————————————————————————

	/**
	 * 一个任务：名字（+ 若干条目由调用方 add）。
	 *
	 * <p>★ 原来这里还带一个"循环方式"参数、写进任务里 —— 任务级循环删除后那个字段已经不存在，
	 * 循环只剩"主任务恒为无限循环"这一条铁律（要跑固定几轮用「调用」的
	 * {@code callRepeat}，见 {@link #loop3()}）。
	 */
	private static ArmProgramTask task(String name) {
		ArmProgramTask task = new ArmProgramTask();
		task.name = name;
		return task;
	}

	private static ArmProgramEntry entry(OperationType op, int x, int y, int z) {
		// 走 fresh(op)：个数取该操作的默认值（取出 1 / 放入 64 / 收存 64），模式固定「小于等于」。
		// 不这么做的后果是**存读不对称**：`save` 只在非默认模式时写 `cmode`，而直接 new 的条目
		// 个数是字段初始值 1 ⇒ 一条「放入」会在 `load` 里被那条"老卡迁成整摞"的迁移改成 64，
		// 于是 `preset … nbtRoundTrip` 当场变红（它就是为这类不对称设的）。
		ArmProgramEntry entry = ArmProgramEntry.fresh(op);
		entry.offset = new BlockPos(x, y, z);
		return entry;
	}

	private static ArmProgram single(ArmProgramTask task) {
		ArmProgram program = new ArmProgram();
		program.tasks.add(task);
		return program;
	}

}
