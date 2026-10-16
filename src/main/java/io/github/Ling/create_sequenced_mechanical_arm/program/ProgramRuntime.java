package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.equipment.sandPaper.SandPaperPolishingRecipe;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.ArmEffects;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.TargetInteraction;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask.LoopType;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.RightClickBridge;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmBlockEntity;

import com.simibubi.create.foundation.recipe.RecipeApplier;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * 操作表解释器（PLAN.md §7）。
 *
 * <p><b>§7.6 v4：单线程 + 调用栈。</b>程序里只有一个"当前任务"在跑 —— 列表的第一个任务是
 * <b>主任务</b>，其余任务只能被 {@link OperationType#CALL} 调用。控制流图因此是：
 * <ul>
 *   <li>{@code 跳转}（条目级）：同任务内的 goto，不返回；</li>
 *   <li>{@code 调用}（任务级）：压返回地址、切到子任务；子任务走完（或「退出」）自动回到调用点的下一条；</li>
 *   <li>{@code 退出 / 抛出错误}：立刻结束当前任务 —— 前者算正常返回，后者让**调用方**走「失败后」；</li>
 *   <li>循环仍在任务上（计次 / 一直重复 / 直到成功），「退出」就是 while 循环的 break。</li>
 * </ul>
 * 没有调度器、没有优先级、没有"让位"（那套东西连字段一起删掉了，见 PLAN §11 决策 15 / 69）。
 *
 * <p>核心约束：
 * <ul>
 *   <li><b>每 tick 至多推进一条原语</b>；需要坐标的操作是显式多 tick 状态机（前摇 → 移动 → 到位 → 执行）。</li>
 *   <li><b>常驻执行 + 红石门控</b>（门控在「等待信号」操作里，见 §7.6）。</li>
 *   <li><b>两条守卫</b>（都只拦"真出问题"的情况，<b>不按时间停机</b>）：调用栈深度上限；
 *       「空转」上限 —— 连续 {@link #MAX_IDLE_SPINS} tick 一步都没跟世界打交道（没移动、没等待）才算空转。
 *       ⚠️ 原来还有"单次运行时长 / 总步数"两条预算，它们会误杀"等玩家主动触发"的长时间等待，已删除（见常量注释）。</li>
 *   <li>失败语义统一（§7.7）：一条操作只有两个出口 —— <b>成功</b>与<b>失败/超时</b>；
 *       会无限等的操作才有"时限"，只有硬失败的操作（左键 / 调用）失败即刻执行。</li>
 * </ul>
 */
public class ProgramRuntime {

	/**
	 * 「空转」守卫：连续多少个 tick **一步都没跟世界打交道**就绕回来了。
	 *
	 * <p>用户第三轮问过"连续无进展超时是怎么触发的" —— 原来那两条（60s 无成功、20 万步预算）都是
	 * "多久没成功就停机"，而<b>等玩家主动触发</b>（补货、拨红石链接、等机器产出）本来就可能等几分钟到几小时，
	 * 用户原话："有些事件需要玩家主动触发，动力臂必须一直等待"。所以那两条**删掉了**，换成这条：
	 * 只要进入了 {@link #isPaced} 认定的"要花时间"的状态就清零，等待**永远**不会被它盯上，
	 * 能抓到的只剩真正的空转（空的无限循环、没有任何操作的跳转自环）——这类才是真的什么也没发生。
	 */
	public static final int MAX_IDLE_SPINS = 4096;
	/** {@code MAX} 卸货模式下单次操作的搬运上限。 */
	public static final int MAX_TRANSFER_PER_OP = 64;
	/**
	 * 前置检查未通过时的**重试间隔**（ticks）。
	 *
	 * <p><b>为什么是 0（= 每 tick 重查）</b>：这里原来是 10，注释写着"避免每 tick 都去查能力" ——
	 * 那是**我们自己发明的节流，上游动力臂没有**：`ArmBlockEntity.tick()` 每 tick 都会走
	 * `searchForItem()` / `collectItem()`（`ArmBlockEntity.java:126-152`），所以它"看到物品就立刻动手"。
	 * 10 tick（0.5 秒）的间隔在传送带上直接表现为**取不到**：高速带上的物品在点位上只停留几 tick，
	 * 我们每 10 tick 才看一眼 ⇒ 几乎必然错过；而且每次前置检查失败还会 {@code cancelMove()}
	 * 把手臂收回去，于是"取出"看起来有明显间隔、物品从眼前溜走（用户第二十七轮续二十三实测）。
	 *
	 * <p>代价（每 tick 查一次能力）可以接受：{@code preCheckFailure} 只做
	 * {@code extract/insert(simulate)} 这类**纯模拟**，与"每 tick 推进一条原语"的其它路径同量级；
	 * 上游动力臂也是每 tick 做同类查询。
	 */
	public static final int PRECHECK_RETRY_TICKS = 0;
	/** 调用栈深度上限：超过判为疑似递归并停机（比"无限递归把栈撑爆"好）。 */
	public static final int MAX_CALL_DEPTH = 8;

	public enum EntryState {
		IDLE, DELAY, MOVING, EXECUTING, WAITING, RETRY, HALTED
	}

	/**
	 * 这个状态算不算"在跟世界打交道、要花时间"。
	 *
	 * <p>判定直接影响空转守卫能不能误杀等待，所以单独拎出来并让自检断言：
	 * <b>移动 / 等待 / 延迟 / 重试</b>是（重试 = 在原地等条件，等玩家补货就走这条）；
	 * <b>空闲 / 执行 / 停机</b>不是 —— 执行只占一个 tick（下一次就出结果了），
	 * 要是把它也算"要花时间"，那么空转守卫就永远抓不到任何东西了。
	 */
	public static boolean isPaced(EntryState state) {
		return state == EntryState.MOVING || state == EntryState.WAITING || state == EntryState.DELAY
			|| state == EntryState.RETRY;
	}

	// ——— 持久化状态 ———
	/** 当前任务的下标。主任务恒为 0；调用子任务时指向子任务。 */
	public int taskIndex;
	public int entryIndex;
	public EntryState state = EntryState.IDLE;
	public int timer;
	public int waitElapsed;
	/** 当前任务这一轮已经走完的圈数（循环计次用）。 */
	public int loopCounter;
	/** 前置检查未通过后不动手臂的那段时间。 */
	public int idleCooldown;
	/** 连续"没进入任何要花时间的状态"就绕回来的 tick 数（见 {@link #MAX_IDLE_SPINS}）。 */
	public int idleSpins;

	/**
	 * 调用栈：每层记住"从哪个任务的哪一条进来"。子任务跑完/退出时弹一层回到那里继续。
	 *
	 * <p>这就是单线程模型剩下的一点点"状态"—— 以前的 {@code taskStates}（每任务一个游标）
	 * 换成了这一个栈，而且语义清楚得多。
	 */
	public final List<Frame> callStack = new ArrayList<>();

	/** 一层调用：回到 {@code returnTask} 的 {@code returnEntry}；带上是调用方当时的循环计数。 */
	public record Frame(int returnTask, int returnEntry, int callerLoopCounter, int repeatsLeft) {
		/** 这个调用点还要让子任务再跑几轮（第 1 轮算在 {@code repeatsLeft} 里）。 */
		public boolean hasAnotherRound() {
			// ★ 0 = 无限循环（用户第四十轮）：不能递减、也不返回 —— 要停就靠「中断」/停机，或者子任务里的「退出」
			return repeatsLeft == ArmProgramEntry.CALL_REPEAT_INFINITE || repeatsLeft > 1;
		}

		/** 一轮跑完之后的剩余轮次；「无限」永远是「无限」（不许把 0 减成 -1 再当成"还剩 0 轮"）。 */
		public Frame withOneFewerRepeat() {
			return repeatsLeft == ArmProgramEntry.CALL_REPEAT_INFINITE ? this
				: new Frame(returnTask, returnEntry, callerLoopCounter, repeatsLeft - 1);
		}
	}

	// ——— 瞬态 ———
	private int unloadMoved;
	@Nullable
	private String lastFailure;
	@Nullable
	private String error;
	private int errorEntryIndex = -1;
	/** 频率判定的去抖缓存（照抄 Create 时刻表条件的 {@code globalPowerVersion} 手法）。 */
	private int cachedPowerVersion = -1;
	private boolean cachedSignal;

	public boolean isHalted() {
		return state == EntryState.HALTED;
	}

	/**
	 * 推进臂上那枚脉冲计时器（§7.16b 一.3）。
	 *
	 * <p>独立成一个公开方法是因为自检要**直接**推它一次 —— 断言"发出去的脉冲到点自动恢复"
	 * 不能靠 {@code tick()}（那要世界、转速、程序一应俱全）。
	 *
	 * @return 本轮是否发生了"到点恢复"
	 */
	public boolean tickArmLinkTimers(SequencedArmBlockEntity arm) {
		return arm.tickArmLink();
	}

	/** 调用深度（0 = 正在跑主任务）。护目镜用它显示面包屑。 */
	public int callDepth() {
		return callStack.size();
	}

	@Nullable
	public String getError() {
		return error;
	}

	public int getErrorEntryIndex() {
		return errorEntryIndex;
	}

	@Nullable
	public String getLastFailure() {
		return lastFailure;
	}

	public void clearError() {
		error = null;
		errorEntryIndex = -1;
		lastFailure = null;
	}

	// ————————————————————————— 主循环 —————————————————————————

	public void tick(SequencedArmBlockEntity arm) {
		if (arm.getLevel() == null || arm.getLevel()
			.isClientSide())
			return;
		// ★ 脉冲计时器必须在**任何早退之前**推进（§7.16b 一.3）：脉冲是臂的物理状态 ——
		// 程序停机 / 无转速 / 红石锁定时它照样要收尾，否则就正好踩中"世界里留一个永远通电的频率"。
		tickArmLinkTimers(arm);
		ArmProgram program = arm.getProgram();
		if (program.isEmpty() || isHalted())
			return;
		if (arm.isRedstoneLocked())
			return; // 常驻门控：通电即暂停（对齐原版两者）
		if (arm.getSpeed() == 0)
			return; // 无转速冻结

		// §7.6 v4：没有调度器了 —— 谁在跑就是谁。任务数变了（卡被编辑过）就回到主任务。
		if (program.task(taskIndex) == null) {
			callStack.clear();
			taskIndex = 0;
			entryIndex = 0;
			state = EntryState.IDLE;
		}

		// 待机（前置检查没通过）：不动手臂，也不算空转 —— 等输入是完全正常的状态。
		if (idleCooldown > 0) {
			idleCooldown--;
			if (state == EntryState.RETRY)
				waitElapsed++; // 强制等待按 tick 计时，否则超时会变成"重试次数"
			return;
		}

		// ★ 空转守卫（见 MAX_IDLE_SPINS）：只在"原地绕圈、一步都没跟世界打交道"时计数。
		// 移动/等待/延迟/重试都算"在跟世界打交道"并清零 —— 所以"等玩家发货"这种长时间等待**永远不会**被停机。
		if (isPaced(state)) {
			idleSpins = 0;
		} else if (++idleSpins > MAX_IDLE_SPINS) {
			halt(arm, "程序在空转（连续 " + MAX_IDLE_SPINS
				+ " tick 没有移动也没有等待就绕回来了）：检查跳转/调用的目标，或给循环加一条「等待」");
			return;
		}

		ArmProgramTask task = program.task(taskIndex);
		if (task == null) {
			taskIndex = 0;
			entryIndex = 0;
			return;
		}
		if (entryIndex >= task.entries.size()) {
			finishTask(arm, task);
			return;
		}

		ArmProgramEntry entry = task.entries.get(entryIndex);

		switch (state) {
			case IDLE -> {
				if (entry.delay > 0) {
					timer = entry.delay;
					state = EntryState.DELAY;
				} else
					beginEntry(arm, task, entry);
			}
			case DELAY -> {
				if (--timer <= 0)
					beginEntry(arm, task, entry);
			}
			case RETRY -> {
				waitElapsed++; // 按 tick 计时（前置检查的节流不改超时语义）
				beginEntry(arm, task, entry);
			}
			case MOVING -> {
				if (arm.hasArrived())
					state = EntryState.EXECUTING;
			}
			case WAITING -> tickWait(arm, task, entry);
			case EXECUTING -> execute(arm, task, entry);
			case HALTED -> {
			}
		}
	}

	// ————————————————————————— 调用栈（§7.6 v4） —————————————————————————

	/**
	 * 调用一个子任务：把"回到哪里"压栈，然后切过去从第一条开始。
	 *
	 * <p>目标不存在 = 硬失败（走调用卡自己的失败出口），而不是停机 —— 这样"子任务被改名/删掉"
	 * 只是让这条调用失败，主程序还能按你设定的路线走。
	 */
	private void callTask(SequencedArmBlockEntity arm, ArmProgramTask caller, ArmProgramEntry entry) {
		ArmProgram program = arm.getProgram();
		String target = entry.callTarget == null ? "" : entry.callTarget.trim();
		ArmProgramTask callee = null;
		int calleeIndex = -1;
		for (int i = 0; i < program.tasks.size(); i++) {
			if (program.tasks.get(i).name.equals(target)) {
				callee = program.tasks.get(i);
				calleeIndex = i;
				break;
			}
		}
		if (callee == null || callee.entries.isEmpty()) {
			lastFailure = callee == null ? "调用目标不存在：" + target : "调用目标没有操作：" + target;
			applySkip(arm, caller, entry, entry.onTimeout, lastFailure);
			return;
		}
		if (callStack.size() >= MAX_CALL_DEPTH) {
			lastFailure = "调用太深（>" + MAX_CALL_DEPTH + " 层，疑似递归）：" + target;
			applySkip(arm, caller, entry, entry.onTimeout, lastFailure);
			return;
		}
		callStack.add(new Frame(taskIndex, entryIndex + 1, loopCounter,
			ArmProgramEntry.clampCallRepeat(entry.callRepeat)));
		taskIndex = calleeIndex;
		entryIndex = 0;
		loopCounter = 0;
		state = EntryState.IDLE;
		waitElapsed = 0;
		resetEntryCounters();
		arm.markProgramDirty();
	}

	/**
	 * 结束当前任务：{@code failed} 为真表示"抛出错误"结束。
	 *
	 * <p>在子任务里 = 返回调用方（失败时调用方走「失败后」）；在主任务里 = 收工（失败则停机报错）。
	 */
	private void endTask(SequencedArmBlockEntity arm, boolean failed, @Nullable String reason) {
		ArmProgram program = arm.getProgram();
		if (failed && reason != null)
			lastFailure = reason;
		if (!callStack.isEmpty()) {
			Frame frame = callStack.remove(callStack.size() - 1);
			int failedTask = taskIndex;
			int failedEntry = entryIndex;
			taskIndex = frame.returnTask();
			entryIndex = frame.returnEntry();
			loopCounter = frame.callerLoopCounter();
			state = EntryState.IDLE;
			resetEntryCounters();
			if (failed) {
				// 把失败**冒泡到那张调用卡**：调用行为没有时限，立刻执行它的「失败后」。
				ArmProgramTask caller = program.task(taskIndex);
				if (caller != null && entryIndex - 1 >= 0 && entryIndex - 1 < caller.entries.size()) {
					ArmProgramEntry callEntry = caller.entries.get(entryIndex - 1);
					applySkip(arm, caller, callEntry, callEntry.onTimeout,
						"被调任务失败（" + program.task(failedTask) + " #" + (failedEntry + 1) + "）：" + reason);
					return;
				}
			}
			arm.markProgramDirty();
			return;
		}
		// 主任务结束
		if (failed) {
			halt(arm, reason == null ? "程序抛出错误" : reason);
			return;
		}
		arm.cancelMove(); // 收工：手臂折回待机位，别举在最后一个目标前面（与「等待」同一条理由）
		// ⚠️ §7.16e 有意的**反转**：主任务正常结束**不再清信号** —— 信号是持久变量，跨循环、跨停机、
		// 跑完主任务都保持；要断只能靠「中断」或换卡（旧行为 `clearTransmissions()` 已删，见 §7.16c 反证①）。
		resetEntryCounters();
		entryIndex = 0;
		state = EntryState.IDLE;
		arm.markProgramDirty();
	}

	/**
	 * 红石链接频率**当前是不是通电**；{@code globalPowerVersion} 去抖（Create 时刻表同款手法）。
	 *
	 * <p>⚠️ 这里只返回**原始通电状态**，绝不掺"等通电/等断电"的极性 ——
	 * 极性由调用方（{@code tickWait}）用 {@code powered == entry.waitForPowered} 比一次就够。
	 * 曾经这里写成 `hasPower != waitForPowered`（自己取反），调用方又比一次，**等于取反两次**：
	 * 用户实测"无论如何都是取反 —— 收到信号时判定为失败（停住），没信号时反而继续执行"。
	 * 与 {@code WAIT_REDSTONE} 那条分支（{@code arm.hasNeighborSignal()} 返回原值）保持一致。
	 */
	private boolean isSignalPowered(ArmProgramEntry entry) {
		if (entry.frequency == null)
			return false; // 没设频率 = 永远等不到，交给「失败后 + 时限」处理
		int version = Create.REDSTONE_LINK_NETWORK_HANDLER.globalPowerVersion.get();
		if (version != cachedPowerVersion) {
			cachedPowerVersion = version;
			cachedSignal = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(entry.frequency);
		}
		return cachedSignal;
	}


	/** 目标解析 + 移动（或直接执行无坐标操作）。前摇已在外层处理。 */
	private void beginEntry(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		// ★ 判据是**操作类型**（`movesArm()` = `op.requiresTarget()`），**不是**条目级 `needsTarget()`：
		//   「过滤」是**控制类操作** —— 一瞬间的判断，臂**不移动、不做任何动作**（用户口径：
		//   "不需要动力臂伸过去判断"，跟「等红石 / 收信号」一样）。容器模式的坐标只是
		//   "读哪个方块"的**数据参数**，不是"伸过去的目标"。
		//   ⚠️ 上一版这里问的是 `needsTarget()` ⇒ 容器过滤会让臂先飞过去读箱子（这次修的 bug）。
		//   ⚠️⚠️ 不要把 `needsTarget()` 从"有没有坐标参数"那些站点删掉（GUI 坐标行 / 脚本写出 /
		//   蓝图变换 / 整体平移 / 撤销比对 / 描框）—— 那些问的正是"有没有坐标"，答案没变。
		if (!entry.movesArm()) {
			// 等待类：**先收回待机位**再等（用户实测："执行等待的时候不会收回去"）。
			// 收回只是让手臂折回去（服务端只改相位，动画由客户端插值），所以**不占用等待时间** ——
			// 计时从进入这一条就开始，收回与计时并行；等下一条要伸出去时，手臂已经在回家的路上或到家了。
			// ⚠️ 「过滤」也走这一支，但它**不是等待**（`isWait()` 为假）：它不耗时间、只判断一次，
			//   所以不额外收回 —— 上一条收工（`succeed`）时臂本来就折回待机位了（用户口径：没有动作）。
			if (entry.op.isWait())
				arm.cancelMove();
			state = EntryState.EXECUTING;
			return;
		}
		BlockPos offset = offsetOf(arm, entry);

		// 前置检查先跑：不通过就**不伸过去**（空载具时手臂原地待机，用户明确要求的行为）
		String blocked = preCheckFailure(arm, entry, offset);
		if (blocked != null) {
			// ★ **短暂阻塞不收臂**：最常见的短暂阻塞是"我们刚加工出来的产物还占着判定窗口"，
			//   几 tick 它就走。立刻 `cancelMove()` 的话会变成"收回→再伸出去"的空转，观感就是卡顿
			//   （用户第二十七轮续三十三）。所以给它一个**宽限期**（时长按带速算，见 transientBlockTicks）；
			//   超过还没等到才折回待机 —— "空载具时原地待机"的语义不变，只是不再为一次短暂占用抖动画。
			//   同样用 `retract()`：折回待机**不是松手**，认领还归程序意图管（见 §7.22 / pinOffset）。
			if (waitElapsed >= arm.transientBlockTicks())
				arm.retract(); // 折回静止位待机；客户端靠相位变化做平滑插值
			setIdleCooldown(PRECHECK_RETRY_TICKS);
			handleFailure(arm, task, entry, blocked);
			return;
		}

		// 前置检查通过了：说明"正在等待"的原因已经不存在，那条黄字提示要立刻消失 ——
		// 不能留到条目成功或下一轮开始才清（实测：补货后手臂已经继续干活了，护目镜还挂着旧提示）。
		lastFailure = null;

		// ★ **收回属于"这一次"操作：没走完就不动身**（用户第二十七轮续三十七实测，对照原版动力臂）。
		//   一次完整的操作是「旋转+伸出 → 动作 → 收回」，收回不该被并进下一次动作的前半段
		//   （并进去的话，折回的同时就开始转向新目标 ⇒ 看起来是"收回&旋转"）。
		//   所以这里只是**把动作本身延后**：目标已经确认可以加工，就站在静止位等折返走完。
		//   ⚠️ **不许用 `setIdleCooldown()` 来"等"** —— 那会让整个运行时停止 tick（前置检查不跑、
		//     认领窗口空转），正是 §7.24 那个"每件物品漏一件"的聋窗。这里前置检查/认领照常每 tick 跑。
		if (!arm.isAtRest())
			return;

		// 取物/组装：从**定下目标的那一刻**就锁住目标上的物品（同一 tick 先锁一次，之后每 tick 续锁）。
		boolean pinsTarget = entry.op == OperationType.TAKE || entry.op == OperationType.DEPLOY;
		if (!arm.beginMove(offset, entry.face, pinsTarget)) {
			handleFailure(arm, task, entry, "目标无效或不属于 Create 交互点");
			return;
		}
		state = EntryState.MOVING;
		arm.markProgramDirty();
	}

	/**
	 * 目标是否在这条臂的**活动范围**内（用户第十九轮："很远的无法触及的操作位置产生了动作反应"）。
	 *
	 * <p>范围取 Create 自己的配置 {@code ArmBlockEntity.getRange()}（= {@code mechanicalArmRange}，默认 5 格）——
	 * 与原版**添加交互点时**的判据完全一致（{@code ArmInteractionPointHandler:99} 用的就是它），
	 * 所以这里判掉的正是"原版根本不让你选的点"。抽成静态方法是为了让自检能直接验它。
	 *
	 * <p>注意它和 IK 的两节臂总长（29/16 ≈ 1.81 格）是两回事：范围内但很远的点**允许**，
	 * 只是手臂会拉直（固有限制，见 PLAN §7.9）。
	 */
	public static boolean withinArmRange(BlockPos armPos, BlockPos target) {
		return target.closerThan(armPos, ArmBlockEntity.getRange());
	}

	/**
	 * 动身之前的前置检查（PLAN.md §7.2）。返回 {@code null} 表示可以出发，否则返回失败原因。
	 *
	 * <p>目的有二：① 目标明显做不成事时别空跑一趟（否则手臂会对着空置物台反复够）；
	 * ② 把"没有进展"提前暴露，让 {@code force} / {@code onFail} / {@code UNTIL_FAIL}
	 * 的语义在<b>手臂没动</b>的情况下就生效。
	 *
	 * <p><b>只有需要交换物品的四个操作</b>（{@link OperationType#needsInteractionPoint()}）才要求目标是
	 * Create 交互点；攻击 / 使用 / 潜行使用<b>对任意方块都能试</b>（用户第二轮反馈），
	 * 这里只要求那个位置确实有方块 —— 成败交给 {@code RightClickBridge} 判（它本来就能对任意坐标干活）。
	 */
	@Nullable
	private String preCheckFailure(SequencedArmBlockEntity arm, ArmProgramEntry entry, BlockPos offset) {
		Level level = arm.getLevel();
		if (level == null)
			return "世界未加载";
		BlockPos target = arm.getBlockPos()
			.offset(offset);

		// ★ 距离判定（用户第十九轮："动力臂对很远的无法触及的操作位置产生了动作反应，是不是有些操作没有写距离判定？"）
		// —— 确实一条都没有。原版是在**添加交互点时**就用范围卡住（`ArmInteractionPointHandler:99`
		// `point.getPos().closerThan(pos, ArmBlockEntity.getRange())`）：臂够不到的地方根本不让你选。
		// 我们的卡片是可携带的程序，录制时还不知道会插到哪条臂上，所以只能在这里按**这条臂的位置**判。
		// 范围直接取 Create 自己的配置（`mechanicalArmRange`，默认 5 格）—— 与"原版会接受多远的点"完全一致。
		// 注意它比 IK 的两节臂总长（29/16 ≈ 1.81 格）大：范围内的远处目标仍会让手臂拉直，
		// 那是固有限制、不在这里拦（见 PLAN §7.9）。
		int range = ArmBlockEntity.getRange();
		if (!withinArmRange(arm.getBlockPos(), target))
			return String.format("目标超出臂的范围（%.1f 格 > %d 格）",
				Math.sqrt(target.distSqr(arm.getBlockPos())), range);

		BlockState state = level.getBlockState(target);
		if (entry.op.needsInteractionPoint()) {
			if (!ArmInteractionPoint.isInteractable(level, target, state))
				return "目标无效或不属于 Create 交互点";
		}
		// ★ 这里**不再**对"攻击 / 挖掘"判断"那儿有没有方块"（v27 起）：用户第二十七轮明确要求
		// "和原本的机械手一模一样 —— 不检测能不能挖动，不检测有没有实体或者方块"。
		// 于是这两条的"有没有东西"完全交给动作本身：挖到空气 = 什么都不发生，格里没实体 = 挥空，都算完成；
		// 挖不动（进度恒 0）才由动作返回硬失败并带上原因（那不是动身前的检测，是挥完之后的事实）。
		IItemHandler handler = arm.peekHandler(offset);
		switch (entry.op) {
			case TAKE -> {
				if (TargetInteraction.isDepositOnly(level, target))
					// 判定方式与原版一致（`AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint`：
					// 漏斗 / 烈焰人 / 营火 / 重生锚 / 粉碎轮），所以这里**不能**再写"（漏斗类）"
					// —— 打包机曾经被误判成只进不出，就是"有 InvManipulationBehaviour 就算漏斗"的旧实现造成的。
					return "该目标只进不出（这类交互点没有可取的槽位）";
				if (handler == null)
					return "目标没有物品能力";
				int slot = firstNonEmptySlot(handler);
				if (slot < 0)
					return "目标为空";
				if (!arm.canAccept(handler.extractItem(slot, 1, true)))
					return "臂内已满";
			}
			case DEPOSIT -> {
				ItemStack held = arm.getHeldItem();
				if (held.isEmpty())
					return "手上没有物品";
				// 用 TargetInteraction 而不是裸能力：漏斗没有通用物品能力，走它自己的行为对象
				TargetInteraction.Insert probe = TargetInteraction.insert(level, target, held.copy(), true);
				if (probe.blockedReason() != null)
					return probe.blockedReason();
				if (!probe.madeProgress(held))
					return "目标放不下";
			}
			case UNLOAD -> {
				if (arm.getOverflow()
					.isEmpty())
					return "臂内没有可卸出的物品";
				TargetInteraction.Insert probe = TargetInteraction.insert(level, target,
					arm.getOverflow()
						.get(0)
						.copyWithCount(1),
					true);
				if (probe.blockedReason() != null)
					return probe.blockedReason();
			}
			case DEPLOY -> {
				if (handler == null)
					return "目标没有物品能力";
				ItemStack applicator = arm.getHeldItem();
				if (applicator.isEmpty())
					return "手上没有应用物";
				// ★ **逐件找**，不是"只看第一件"（用户第二十七轮续三十六实测："组装的时候臂会空一轮"）：
				//   目标格里最靠前的那件往往就是**我们刚加工出来的产物** —— 它当然不匹配配方，
				//   旧写法于是报"没有匹配的配方"、手臂干脆不伸出去，而它后面那件**能加工的原料**
				//   就永远等不到机会（带子一直被产物占着判定窗口）。
				//   这与真正加工时的判据必须**同源**：`deployOnBelt` 是逐件 it 的，
				//   上游机械手（Deployer）也是**逐件回调**、不匹配的直接 `PASS`
				//   （`BeltDeployerCallbacks:38-63`，`:58-59` 就是这一条的出处）——
				//   所以上游根本不存在"产物挡住原料"这个问题。
				if (!anyProcessable(level, arm, entry, handler, applicator))
					return "这一格没有可加工的原料（没有匹配 deploying / item_application 配方的物品）";
			}
			default -> {
			}
		}
		return null;
	}

	private static int firstNonEmptySlot(IItemHandler handler) {
		for (int slot = 0; slot < handler.getSlots(); slot++)
			if (!handler.extractItem(slot, 1, true)
				.isEmpty())
				return slot;
		return -1;
	}

	/**
	 * 目标上有没有**这条条目加工得了**的物品 —— 「组装」的前置检查用它，判据与真正加工时**同源**。
	 *
	 * <p>传送带：直接用带子自己的加工迭代（{@code handleProcessingOnAllItems}，与
	 * {@link #deployOnBelt} 同一个调用、同一个范围：格子中心 ±.51），回调返回 {@code doNothing()}
	 * 所以是**只读探测**（`BeltInventory.applyToEachWithin:466-467`：结果没变化就 continue，不改任何东西）。
	 * <br>普通容器：**逐槽找**（上游动力臂取物也是遍历所有槽位，`ArmBlockEntity:385-402`）。
	 *
	 * <p><b>为什么必须是"逐件"而不是"第一件"</b>：带子那一格里最靠前的通常是我们刚加工出来的**产物**，
	 * 它不匹配配方；只看第一件就会得出"没有匹配的配方"⇒ 手臂不伸出去 ⇒ 后面那件**原料**永远等不到
	 * （用户第二十七轮续三十六：<i>"组装的时候臂会空一轮"</i>）。上游没有这个问题，因为它是**逐件回调**、
	 * 不匹配的直接放行（`BeltDeployerCallbacks:58-59`）。
	 *
	 * <p>⚠️ 与 {@link #canWorkOn} 的分工：这里是"**这一格有没有**能加工的东西"（给前置检查），
	 * 那里是"**这一件**能不能加工"（给认领逐件用）。两者都调 {@link DeployRecipes#find}，
	 * **不要**在这里另写一套配方判据（决策 68/91：判据写两份必然分叉）。
	 *
	 * <p>★ **还要求"已经在作业位上"**（{@code arm.isAtWorkPosition}）：本体只在物品**跨过格子中心**的那一 tick
	 * 才询问机器（`BeltInventory:268-297`），而 {@code handleProcessingOnAllItems} 的范围是整格（±.51）——
	 * 不额外加这一条，手臂就会在半路动手（用户第二十七轮续四十一实测："物品还没有运动到正中间，动力臂就已经组装了"）。
	 */
	private static boolean anyProcessable(Level level, SequencedArmBlockEntity arm, ArmProgramEntry entry,
		IItemHandler handler, ItemStack applicator) {
		TransportedItemStackHandlerBehaviour transport = beltBehaviourAt(level, arm, entry);
		if (transport != null) {
			boolean[] found = { false };
			transport.handleProcessingOnAllItems(t -> {
				if (!found[0] && arm.isAtWorkPosition(t) && !t.stack.isEmpty()
					&& DeployRecipes.find(level, t.stack.copyWithCount(1), applicator) != null)
					found[0] = true;
				return TransportedItemStackHandlerBehaviour.TransportedResult.doNothing();
			});
			return found[0];
		}
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			ItemStack in = handler.extractItem(slot, 1, true);
			if (!in.isEmpty() && DeployRecipes.find(level, in.copyWithCount(1), applicator) != null)
				return true;
		}
		return false;
	}

	/**
	 * 设置待机节流。
	 *
	 * <p><b>必须写实时字段</b>（§7.6 v4 之后实时字段就是唯一真相，没有每任务的槽位了）：
	 * 直接写别的对象会被随后的状态推进覆盖掉，节流失效 → {@code cancelMove()} 每 tick 把进度归零
	 * → 客户端动画被钉在起点（表现为"手臂卡在准备收回的姿势"）。这是实测踩到的坑。
	 */
	private void setIdleCooldown(int ticks) {
		idleCooldown = Math.max(idleCooldown, ticks);
	}

	private static BlockPos offsetOf(SequencedArmBlockEntity arm, ArmProgramEntry entry) {
		// ★ 条目 offset **永远相对执行它的那条臂**（PLAN.md §19.7 的用户口径）：
		// 世界目标 = `arm.getBlockPos().offset(offset)`（见 preCheckFailure）⇒ 这里直接返回 offset。
		// 卡上的**录制原点**（用户第四十轮起它住在卡上，不在程序里）不参与运行时 ——
		// "插卡平移"那个函数（ProgramCoordinates.rebaseTo）已删。
		// ⚠️ 谁要是想在这里加上原点 / 臂位置之类的平移，请先看自检里那条
		//    "同一张卡插进两条位置不同的臂，两次 offset 必须完全相同" —— 那条断言就是拦这个的。
		return entry.offset;
	}

	private void execute(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		switch (entry.op) {
			case TAKE -> take(arm, task, entry);
			case DEPOSIT -> deposit(arm, task, entry);
			case UNLOAD -> unload(arm, task, entry);
			case DEPLOY -> deploy(arm, task, entry);
			case ATTACK -> attack(arm, task, entry);
			case MINE -> mine(arm, task, entry);
			case USE, USE_SNEAK -> use(arm, task, entry);
			case FILTER -> filter(arm, task, entry);
			case WAIT -> {
				timer = entry.waitTicks();
				state = EntryState.WAITING;
			}
			case WAIT_REDSTONE -> state = EntryState.WAITING;
			// 收发信号（§7.16e：一个频率 = 一个持久布尔变量）：**收** = 阻塞等待（走 tickWait 那张极性真值表）；
			// **发通电** = 置位（一直保持，直到有人**中断**它或换卡）；**中断** = 复位（只断这一路，
			// 并取消它未到期的脉冲）。两者都不阻塞、**没有失败出口**（发送不会失败），
			// 脉冲的收尾交给臂 BE 上那枚计时器（tickArmLinkTimers）。
			case SIGNAL -> {
				if (entry.isSignalReceive())
					state = EntryState.WAITING;
				else {
					if (entry.waitForPowered)
						arm.emitSignal(entry.frequency, 15, entry.signalTicks());
					else
						arm.releaseSignal(entry.frequency); // 「中断」= 只断指定频率那一路
					succeed(arm, task, entry);
				}
			}
			// 控制类：调用（压栈跳过去）、退出（正常收任务）、抛错（失败收任务）
			case CALL -> callTask(arm, task, entry);
			case BREAK -> endTask(arm, false, null);
			case THROW -> {
				String reason = entry.errorMessage == null || entry.errorMessage.isBlank() ? "程序主动抛出错误"
					: entry.errorMessage;
				lastFailure = reason;
				endTask(arm, true, reason);
			}
			default -> handleFailure(arm, task, entry, entry.op + " 尚未实现（阶段 2c/2d）");
		}
	}

	/**
	 * 等待类的**极性判定**：当前通电状态与"等通电 / 等断电"是否吻合。
	 *
	 * <p>抽成一个方法是为了让自检能直接验这张真值表 —— 这个位置出过一次"取反两次"的 bug：
	 * 电源查询那边自己异或了极性、这里又比一次，结果**收到信号反而停下**（用户实测）。
	 * 规则只有一句：**查询侧返回原始通电状态，极性只在这里比一次**。
	 */
	public static boolean waitSatisfied(boolean powered, boolean waitForPowered) {
		return powered == waitForPowered;
	}

	private void tickWait(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		if (entry.op == OperationType.WAIT) {
			if (--timer <= 0)
				succeed(arm, task, entry);
			return;
		}
		// 等红石 = 相邻方块信号；等信号 = 红石链接频率（§7.6 v4：频率从任务搬到了这里）
		// 两者都返回**原始通电状态**，极性只在这下面比一次（曾经等信号那条自己被取反过，见 isSignalPowered）
		boolean powered = entry.op.usesSignalPair() ? isSignalPowered(entry)
			: arm.hasNeighborSignal();
		if (waitSatisfied(powered, entry.waitForPowered)) {
			succeed(arm, task, entry);
			return;
		}
		waitElapsed++;
		// 等待类操作把「时限」当作"最多等多久"：到点走「失败后」（§7.7 与其它操作一致）
		if (entry.timeoutTicks() >= 0 && waitElapsed > entry.timeoutTicks())
			applySkip(arm, task, entry, entry.onTimeout,
				entry.op.usesSignalPair() ? "等待信号超时" : "等待红石超时");
	}

	// ————————————————————————— 各操作 —————————————————————————

	private void take(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		IItemHandler handler = arm.getMovingHandler();
		if (handler == null) {
			handleFailure(arm, task, entry, "目标没有物品能力");
			return;
		}
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			ItemStack available = handler.extractItem(slot, entry.count, true);
			if (available.isEmpty())
				continue;
			// 取前模拟：臂内放得下才动手（放不下就按"还没成功"重试，等臂内腾出位置）
			if (!arm.canAccept(available)) {
				handleFailure(arm, task, entry, "臂内已满");
				return;
			}
			int amount = Math.min(available.getCount(), entry.count);
			ItemStack taken = handler.extractItem(slot, amount, false);
			if (taken.isEmpty()) {
				handleFailure(arm, task, entry, "取出失败");
				return;
			}
			ItemStack beforeHeld = arm.getHeldItem();
			arm.insertIntoInventory(taken);
			// 原版动力臂的取物声（种类变化时才响，守卫与 ArmBlockEntity.collectItem 一致）
			ArmEffects.pickup(arm.getLevel(), arm.getBlockPos(), beforeHeld, arm.getHeldItem());
			succeed(arm, task, entry);
			return;
		}
		handleFailure(arm, task, entry, "目标为空");
	}

	private void deposit(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		ItemStack held = arm.getHeldItem();
		if (held.isEmpty()) {
			handleFailure(arm, task, entry, "手上没有物品");
			return;
		}
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		TargetInteraction.Insert result = TargetInteraction.insert(arm.getLevel(), target, held.copy(), false);
		if (result.remainder()
			.getCount() == held.getCount()) {
			handleFailure(arm, task, entry, result.blockedReason() != null ? result.blockedReason() : "目标放不下");
			return;
		}
		arm.setHeldItem(result.remainder());
		succeed(arm, task, entry);
	}

	private void unload(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		if (arm.getOverflow()
			.isEmpty()) {
			if (unloadMoved > 0)
				succeed(arm, task, entry);
			else
				handleFailure(arm, task, entry, "臂内没有可卸出的物品");
			return;
		}

		int moved = unloadRound(arm, target, entry);
		unloadMoved += moved;
		if (entry.unloadRepeat == UnloadRepeat.ONCE) {
			if (moved > 0)
				succeed(arm, task, entry);
			else
				handleFailure(arm, task, entry, "目标拒绝接收");
			return;
		}
		// UNTIL_EMPTY：本轮搬完不结束，下一 tick 继续；目标拒绝即失败
		if (moved == 0)
			handleFailure(arm, task, entry, "目标拒绝接收（未卸完）");
	}

	/** 卸一轮：ONE_STACK 只搬一个满组，MAX 在本轮内持续搬直到预算用尽或目标拒绝。 */
	private int unloadRound(SequencedArmBlockEntity arm, BlockPos target, ArmProgramEntry entry) {
		int budget = entry.unloadAmount == UnloadAmount.MAX ? MAX_TRANSFER_PER_OP : 64;
		int moved = 0;
		// ★ 栈式出库（用户第二十七轮）：**后进的先卸**。顺序只有一处定义
		// （SequencedArmBlockEntity.overflowOutOrder），空手潜行右键取一组用的是同一个顺序。
		List<ItemStack> overflow = arm.overflowOutOrder();
		for (ItemStack stack : overflow) {
			if (budget <= 0 || stack.isEmpty())
				continue;
			int amount = Math.min(stack.getCount(), budget);
			TargetInteraction.Insert result = TargetInteraction.insert(arm.getLevel(), target, stack.copyWithCount(amount),
				false);
			int inserted = amount - result.remainder()
				.getCount();
			if (inserted <= 0)
				continue;
			stack.shrink(inserted);
			budget -= inserted;
			moved += inserted;
		}
		arm.getOverflow()
			.removeIf(ItemStack::isEmpty);
		return moved;
	}

	/**
	 * deploying（PLAN.md §2.8 路线 B）：手上应用物 × 载具上的物品 → 配方结果写回载具。
	 * 结算顺序与 {@code BeltDeployerCallbacks.activate} 对齐：结果回载具、应用物按配方/耐久/剩余物结算。
	 */
	private void deploy(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		IItemHandler handler = arm.getMovingHandler();
		if (handler == null) {
			handleFailure(arm, task, entry, "目标没有物品能力");
			return;
		}
		ItemStack applicator = arm.getHeldItem();
		if (applicator.isEmpty()) {
			handleFailure(arm, task, entry, "手上没有应用物");
			return;
		}
		Level level = arm.getLevel();
		if (level == null) {
			handleFailure(arm, task, entry, "世界未加载");
			return;
		}

		// ★ 传送带走**原地加工**（照 `BeltDeployerCallbacks.activate:118-132`），置物台等普通容器走"取出→加工→放回"。
		//
		// ⚠️ 为什么带子不能"取出放回"（用户第二十七轮续二十八实测：第一次成功、之后产物全被"原料"堵住、
		//    最后全掉进臂的溢出库存）：带子的 `extractItem` 只是把栈清零、**真正移除要等下一次**
		//    `BeltInventory.tick/write` 处理 `toRemove`（`BeltInventory:67-73`、`:408-423`），
		//    而我们在**同一 tick** 里紧接着 `insertItem` ⇒ `canInsertAt` 用 `isBlocking`
		//    （`:368-374`：`insertedAt == 这一格 && insertedFrom == UP && 位置在后`）判定"那格还占着"。
		//    更糟的是：**我们自己放回去的那个产物恰好满足** `insertedAt == 这一格 && insertedFrom == UP`
		//    ⇒ 从那以后这一格的插入永远被拒。原地加工由带子自己记账（产物进 `toInsert`、原件进 `toRemove`），
		//    位置也不动 ⇒ 顺带解决"物品从上半格跳到格子中间"的观感问题。
		TransportedItemStackHandlerBehaviour transport = beltBehaviourAt(level, arm, entry);
		if (transport != null) {
			deployOnBelt(arm, task, entry, level, transport, applicator);
			return;
		}

		// 1) 从载具取出 1 个被加工物
		ItemStack processed = ItemStack.EMPTY;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			if (handler.extractItem(slot, 1, true)
				.isEmpty())
				continue;
			processed = handler.extractItem(slot, 1, false);
			if (!processed.isEmpty())
				break;
		}
		if (processed.isEmpty()) {
			handleFailure(arm, task, entry, "载具上没有可加工的物品");
			return;
		}

		// 2) 解析配方（顺序装配 → deploying → item_application，砂纸有特例）
		RecipeHolder<? extends Recipe<?>> recipe = DeployRecipes.find(level, processed, applicator);
		if (recipe == null) {
			returnToCarrier(arm, handler, processed); // 没配方就把物品放回去，别吞掉
			handleFailure(arm, task, entry, "没有匹配的 deploying / item_application 配方");
			return;
		}

		// 3) 应用配方（结果可能为空 = 物品被转化/消耗掉）
		List<ItemStack> results = RecipeApplier.applyRecipeOn(level, processed.copyWithCount(1), recipe.value(), true);

		// 4) 结果回载具；载具放不下的进臂内（臂内也满则正常落地）
		for (ItemStack result : results) {
			ItemStack leftover = ItemHandlerHelper.insertItem(handler, result, false);
			if (!leftover.isEmpty())
				arm.insertIntoInventory(leftover);
		}

		// 5) 结算手持应用物 + 反馈 + 记成功（与带子那条路共用）
		finishDeploy(arm, task, entry, level, recipe, processed);
	}

	/**
	 * 传送带上的组装：**原地加工**（照 Create 的 `BeltDeployerCallbacks.activate:118-132`）。
	 *
	 * <p>做法：在目标方块中心半格内找第一件"能匹配配方"的物品，对它调
	 * {@code handleProcessingOnAllItems}，返回
	 * {@code convertTo(left)}（没有产物，只剩原件被消耗后的余量）或
	 * {@code convertToAndLeaveHeld(collect, left)}（有产物 ⇒ 产物上带、原件余量留在带上）。
	 * **不 extract、不 insert** —— 记账全部交给带子（产物进 `toInsert`、原件进 `toRemove`），
	 * 于是不会撞上 `canInsertAt`/"那格被自己占住"的坑，物品位置也不会跳。
	 *
	 * <p>★ **只加工"已经在作业位上"的那件**（与前置检查同一条判据，见 {@code anyProcessable}）：
	 * 本体在 HOLD 时会把物品**居中**（`BeltInventory:292`），我们也照做（认领时 `snapToWorkPosition`），
	 * 所以产物一定在格子中心出生、不会提前半格挡在原料前面（用户第二十七轮续四十一实测）。
	 */
	private void deployOnBelt(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, Level level,
		TransportedItemStackHandlerBehaviour transport, ItemStack applicator) {
		boolean[] handled = { false };
		RecipeHolder<? extends Recipe<?>>[] recipeRef = new RecipeHolder<?>[1];
		ItemStack[] processedRef = new ItemStack[1];
		transport.handleProcessingOnAllItems(t -> {
			if (handled[0] || !arm.isAtWorkPosition(t))
				return TransportedItemStackHandlerBehaviour.TransportedResult.doNothing();
			RecipeHolder<? extends Recipe<?>> recipe = DeployRecipes.find(level, t.stack, applicator);
			if (recipe == null)
				return TransportedItemStackHandlerBehaviour.TransportedResult.doNothing();
			List<ItemStack> results = RecipeApplier.applyRecipeOn(level, t.stack.copyWithCount(1), recipe.value(),
				true);
			// ⚠️ 产物与余量都必须**把继承来的锁标记清掉**：`t.copy()` 会把 `locked`/`lockedExternally`
			//    一起带走，而我们是靠 `locked` 按住物品的（见 SequencedArmBlockEntity.holdBeltItem）——
			//    不清的话，加工出来的新物品会**带着锁出生**、永远不动（而带子那边因为找不到加工行为，
			//    只有在我们还按着它时才会替我们清理）。
			List<TransportedItemStack> produced = new ArrayList<>();
			for (ItemStack out : results) {
				if (out.isEmpty())
					continue;
				TransportedItemStack copy = t.copy();
				copy.stack = out;
				copy.locked = false;
				copy.lockedExternally = false;
				produced.add(copy);
			}
			TransportedItemStack left = t.copy();
			left.stack.shrink(1); // 消耗掉被加工的那 1 个；多出来的余量留在带上
			left.locked = false;
			left.lockedExternally = false;
			handled[0] = true;
			recipeRef[0] = recipe;
			processedRef[0] = t.stack.copyWithCount(1);
			return produced.isEmpty()
				? TransportedItemStackHandlerBehaviour.TransportedResult.convertTo(left)
				: TransportedItemStackHandlerBehaviour.TransportedResult.convertToAndLeaveHeld(produced, left);
		});
		if (!handled[0]) {
			// 前置检查刚放行的物品在这几 tick 里被别的机器加工/拿走了 ⇒ 按"没配方"重试（不是停机）
			handleFailure(arm, task, entry, "没有匹配的 deploying / item_application 配方");
			return;
		}
		finishDeploy(arm, task, entry, level, recipeRef[0], processedRef[0]);
	}

	/** 组装成功后的共同收尾：结算手持应用物 → 音效粒子 → 记成功。 */
	private void finishDeploy(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, Level level,
		RecipeHolder<? extends Recipe<?>> recipe, ItemStack processed) {
		arm.consumeHeldItem(DeployRecipes.keepsHeldItem(recipe.value()));
		ArmEffects.deploy(level, arm.getBlockPos(), arm.getEffectorPosition(), processed,
			recipe.value() instanceof SandPaperPolishingRecipe);
		// ⚠️ 这里**不再**主动等待产物离开：那会让手臂站着不动一段时间（用户第二十七轮续三十三：
		//    "每次组装都会卡顿一会"）。产物占住判定窗口这件事改由 `beginEntry` 的**宽限期**处理
		//    —— 短暂阻塞不收臂、停在原地每 tick 重查，产物一走、原料进来立刻加工。
		succeed(arm, task, entry);
	}

	/** 目标方块是传送带就返回它承载物品的 behaviour（否则 null ⇒ 走普通容器那条路）。 */
	private static TransportedItemStackHandlerBehaviour beltBehaviourAt(Level level, SequencedArmBlockEntity arm,
		ArmProgramEntry entry) {
		if (!(level.getBlockEntity(arm.getBlockPos()
			.offset(offsetOf(arm, entry))) instanceof BeltBlockEntity belt))
			return null;
		return belt.getBehaviour(TransportedItemStackHandlerBehaviour.TYPE);
	}

	private static void returnToCarrier(SequencedArmBlockEntity arm, IItemHandler handler, ItemStack stack) {
		ItemStack leftover = ItemHandlerHelper.insertItem(handler, stack, false);
		if (!leftover.isEmpty())
			arm.insertIntoInventory(leftover);
	}

	// ————————————————————————— 左右键（机械手语义） —————————————————————————

	/**
	 * 攻击：一次调用 = 挥一击。**照机械手，不做任何"那儿有没有东西"的判断**（用户第二十七轮），
	 * 所以它不会失败：挥空也算完成，想等怪物走近就自己在前面加「等待」。
	 */
	private void attack(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		RightClickBridge.Result result = arm.rightClick.attack(target, entry.face);
		switch (result) {
			case RETRY -> handleFailure(arm, task, entry, "攻击暂不可用：" + describeFailure(arm));
			// 没有"硬失败"这条路：不筛选、不判断，挥了就完事（HARD_FAIL 理论上到不了，按成功处理更安全）
			default -> succeed(arm, task, entry);
		}
	}

	/**
	 * 挖掘：一次调用 = 一次挥击。破坏需要多击，所以"没破"返回 {@code WAITING}，
	 * 这时重跑一次移动动画再挥（手臂折回再伸，视觉上就是原版机械手的反复挥击节奏）。
	 *
	 * <p>**只挖方块，绝不碰实体** —— 旧 {@code PUNCH} 是"实体优先"，玩家想挖方块时只要格里站着实体就会去打它，
	 * 因此 v27 拆成两条（用户第二十七轮）。
	 */
	private void mine(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		RightClickBridge.Result result = arm.rightClick.mine(target, entry.face);
		switch (result) {
			case SUCCESS -> succeed(arm, task, entry);
			// 还在挖：重新走一次挥击动画（这是"还没好"，重试是对的）
			case WAITING -> restartMove(arm, entry);
			case RETRY -> handleFailure(arm, task, entry, "挖掘暂不可用：" + describeFailure(arm));
			// **硬失败**（挖不动 / 无权 / 被保护）：立刻走失败出口，绝不原地重试 ——
			// 否则就是"臂对着基岩永远空挥"，而且阻塞等待时还占着臂。
			case HARD_FAIL -> applySkip(arm, task, entry, entry.onTimeout, "挖掘做不了：" + describeFailure(arm));
		}
	}

	/** 右键：一次调用 = 一次右键。决策上左右键无法客观判定成败，因此只要没被取消就算完成。 */
	private void use(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		boolean sneak = entry.op == OperationType.USE_SNEAK;
		RightClickBridge.Result result = arm.rightClick.use(target, entry.face, sneak);
		switch (result) {
			// 右键**不幂等**（拉杆会被反复拨、按钮会被反复按），所以硬失败也只能走出口，不能重试
			case HARD_FAIL -> applySkip(arm, task, entry, entry.onTimeout, "使用做不了：" + describeFailure(arm));
			case RETRY -> handleFailure(arm, task, entry, "使用暂不可用：" + describeFailure(arm));
			default -> succeed(arm, task, entry); // SUCCESS（含"交互已尝试"）与 WAITING 都算完成
		}
	}

	/**
	 * 过滤（{@link OperationType#FILTER}）：把候选那一份物品交给 Create 自己的过滤规则判一次。
	 *
	 * <p><b>用户口径（照做，不要"优化"）</b>：
	 * <ol>
	 *   <li><b>控制类操作、臂不移动</b>：判断是一瞬间的事 —— 臂<b>不伸过去、不做任何动作</b>
	 *       （与「等红石 / 收信号」同类）。容器模式的坐标只是"读哪个方块"的数据参数；
	 *       判据见 {@code ArmProgramEntry.movesArm()}（{@code beginEntry} 的那个分支）。
	 *       ⚠️ 所以这里<b>不能</b>走 {@code arm.getMovingHandler()}：那是"臂当前移动目标"那一格
	 *       （{@code movingOffset} 记的是"上一次真的动过身"的位置），而过滤不动身
	 *       ⇒ 它永远指着<b>别的</b>条目那一格；</li>
	 *   <li>候选 = {@code HELD}（臂此刻握着的那份存货）或 {@code CONTAINER}（目标容器的
	 *       <b>第一个非空槽</b> —— 只看第一格，不看整箱）；</li>
	 *   <li>候选为空 ⇒ <b>不满足</b>，无论过滤器是什么；</li>
	 *   <li>过滤器为空（没放幽灵物品）⇒ <b>匹配一切</b>（Create 本体口径：
	 *       {@code FilterItemStack.isEmpty()} 为真时 {@code test} 恒真）；</li>
	 *   <li>符合 ⇒ {@code succeed} 走成功后；不符合 ⇒ {@code handleFailure} —— 与「等红石 / 收信号」
	 *       走<b>同一条路</b>：在重试窗口（{@code failAmount}×单位，{@code -1} = 一直重试）内反复判断，
	 *       窗口到了才走 {@code onTimeout}。★ 上一版这里直接 {@code applySkip} ⇒ 一不匹配就停机，
	 *       与用户口径"失败默认 retry"相反（这次修的第二件事）。</li>
	 * </ol>
	 *
	 * <p><b>为什么不自己写比对逻辑</b>：用户要求"完全遵从机械动力本体的过滤逻辑：物品过滤、包裹过滤、
	 * 列表过滤、属性过滤"。那套语义全在 {@code FilterItemStack.of(幽灵物品)} 选出来的包装类里
	 * （{@code ListFilterItemStack} / {@code AttributeFilterItemStack} / {@code PackageFilterItemStack}）
	 * —— 幽灵物品里放什么滤波器，就用什么规则。抄一份到我们这边，Create 一改就分叉。
	 *
	 * <p><b>{@code matchNBT} 传 false</b>（本体默认那一档，也是我们界面上唯一提供的那一档）。
	 * 注意 {@code ListFilterItemStack.test} 会**无视**这个参数、改用自己的
	 * {@code FILTER_ITEMS_RESPECT_NBT} 组件（`FilterItemStack.java:141-146`），
	 * 所以"列表过滤要不要比 NBT"仍然归滤波器物品自己管 —— 那正是"遵从本体"。
	 *
	 * <p><b>只读</b>：这一条不取走、不放入、不改动任何物品，也不对目标方块发起交互（不调 {@code use}）。
	 * 容器模式只是<b>读</b>一格，臂待在原地 —— 读哪个方块由坐标决定，与有没有伸过去无关。
	 */
	private void filter(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		Level level = arm.getLevel();
		if (level == null) {
			handleFailure(arm, task, entry, "世界未加载");
			return;
		}
		ItemStack candidate;
		if (entry.filterSource == ArmProgramEntry.FilterSource.CONTAINER) {
			// ★ 范围仍然要判（这是上一版就有的守卫，不是"伸过去"的一部分）：坐标指的是**这条臂**
			//   周边的那一格。走 preCheckFailure 是为了让这条理由与其它操作**同源同文案**
			//   （对 FILTER 它只做"世界加载 + 臂的范围"两件事，物品能力那一段由下面自己报）。
			BlockPos offset = offsetOf(arm, entry);
			String blocked = preCheckFailure(arm, entry, offset);
			if (blocked != null) {
				handleFailure(arm, task, entry, blocked);
				return;
			}
			// 外部容器：只解析**那一格**的物品能力（只读）。方块不是容器 / 没有物品能力 ⇒
			// 返回 null ⇒ 走失败后（重试）并给出中文理由，**不抛异常**（用户点名要求）。
			// ⚠️ 用 `peekContainerHandler`（不过 Create 交互点那道门）而不是 `peekHandler`：
			//   过滤**不需要**目标是 Create 交互点，保险箱 / 箱子这类普通容器就该能读
			//   （`needsInteractionPoint()` 只认取/放/卸/组装）。
			// ⚠️ 也不用 `arm.getMovingHandler()`：臂不为过滤动身，那个缓存指的是**别的**条目那一格。
			IItemHandler handler = arm.peekContainerHandler(offset);
			if (handler == null) {
				handleFailure(arm, task, entry, "过滤：目标不是容器（没有物品能力）");
				return;
			}
			int slot = firstNonEmptySlot(handler);
			if (slot < 0) {
				handleFailure(arm, task, entry, "过滤：容器里没有物品");
				return;
			}
			// ★ **只看第一格**（第一个非空槽），不挑"任意一格符合的"（用户选定的口径）。
			//   反证：把它换成"遍历所有槽找匹配的"，自检里那条「容器 = [金锭, 铁锭]、幽灵 = 铁锭 ⇒ 失败」
			//   会立刻变绿 —— 那条断言就是钉这个口径的。
			candidate = handler.extractItem(slot, 1, true);
		} else {
			candidate = arm.getHeldItem();
		}
		if (candidate.isEmpty()) {
			// "没有物品可比"也是一种**不满足** —— 与"不匹配"走同一条路（重试窗口内反复判断）。
			handleFailure(arm, task, entry,
				entry.filterSource == ArmProgramEntry.FilterSource.CONTAINER ? "过滤：容器里没有物品" : "过滤：手上没有物品");
			return;
		}
		// 幽灵物品 → 包装器：空的、{@code create:filter}、{@code create:attribute_filter}、
		// {@code create:package_filter} 由 Create 自己按物品类型分派，我们只负责喂给它。
		FilterItemStack filter = FilterItemStack.of(entry.filterItem);
		// 空过滤器 = 匹配一切（本体口径）——但不能短路掉上面那条"候选为空"，两者顺序不能换。
		if (filter.isEmpty()) {
			succeed(arm, task, entry);
			return;
		}
		if (filter.test(level, candidate, false)) {
			succeed(arm, task, entry);
			return;
		}
		// 不匹配 = "时机还没到"，不是"做错了"：与等红石一样在重试窗口里反复判断（见方法头注释）。
		handleFailure(arm, task, entry, "过滤不符：" + candidate.getHoverName()
			.getString());
	}

	private static String describeFailure(SequencedArmBlockEntity arm) {
		String reason = arm.rightClick.getLastFailure();
		return reason == null ? "未知" : reason;
	}

	private void restartMove(SequencedArmBlockEntity arm, ArmProgramEntry entry) {
		if (!arm.beginMove(offsetOf(arm, entry), entry.face)) {
			halt(arm, "重新移动到目标失败");
			return;
		}
		state = EntryState.MOVING;
	}

	// ————————————————————————— 成败与跳转 —————————————————————————
	/**
	 * 条目成功。§7.7 v2：成功的出口是 {@code onSuccess} —— 不设就是按顺序下一条，
	 * 设了 {@code SKIP_TO} 就跳过去（这样"成功后跳回前面"就能组成循环与分支）。
	 */
	private void succeed(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		// 有进展：空转计数清零（它衡量的是"原地绕圈"，不是"跑了多久"）
		idleSpins = 0;
		// 成功了就说明之前那条失败提示已经过时（覆盖"到位后执行失败 → 重试成功"这条路径）
		lastFailure = null;

		// ★ **每条条目结束后收回手臂**（回到待机位），下一条要动手时再伸出去 —— 这才是
		//   "一个物品一次完整的伸→加工→收"的节奏。不收的话，连续条目会**一直停在目标上**连续加工：
		//   用户第二十七轮续三十四实测（256rpm 带 + 16rpm 臂）：它会一直加工、动画卡在加工姿势，
		//   加工速率只受"物品到货速度 / 有没有输出空间"限制，而不是受手臂动作限制。
		//
		// ⚠️ 用 `retract()` 而不是 `cancelMove()`：收回**不等于松手**。认领的寿命归程序意图管
		//   （`pinOffset()`），这里一松手、下一 tick 又认领回来，物品会在带上被放开一格再拽回来
		//   （用户第二十七轮续三十五："又变成无法定住被加工的物品了"）。
		//
		// ⚠️⚠️ **不要再加"等收回动画走完"的待机**（曾经写成 `setIdleCooldown(swingTicks())`，
		//   用户第二十七轮续三十六实测："组装的时候臂会空一轮"）：`chasedPointProgress` 一个 0→1
		//   本来就是"先折回静止位（0→.5）再伸向目标（.5→1）"（见 `tickMovementProgress` 与
		//   `SequencedArmAngleTarget`），**收回已经包含在下一次动作里**；额外的待机只是让运行时**变聋**：
		//   `tick()` 在 `idleCooldown > 0` 时直接返回 ⇒ 前置检查不跑、认领虽在但没有下一件可动，
		//   16rpm 下 64 tick 的聋窗刚好等于"一件物品走完一整格"的时间 ⇒ **每件物品漏掉一件**。
		arm.retract();

		if (entry.onSuccess == SkipAction.SKIP_TO && !entry.successTarget.isBlank()) {
			lastFailure = "成功 → 跳转 " + entry.successTarget;
			resetEntryCounters();
			jumpTo(arm, entry.successTarget);
			return;
		}

		resetEntryCounters();
		entryIndex++;
		if (entryIndex >= task.entries.size())
			finishTask(arm, task);
		else
			arm.markProgramDirty();
	}

	/**
	 * 现在要不要**认领并每 tick 续锁**目标上的物品（给 {@code SequencedArmBlockEntity.tick()} 用）。
	 *
	 * <p>只对"从目标取东西 / 在目标上加工"的条目（{@code TAKE} / {@code DEPLOY}）为真：
	 * 这两类要求目标上的物品**留在原地**等手臂（传送带会把它带走）。投放类（{@code DEPOSIT} 等）
	 * **不认领** —— 别为了一次投放把整条带子停住几秒。
	 *
	 * <p>⚠️ 判据**不再**看手臂状态（曾经是 {@code MOVING + EXECUTING}）。为什么：那样写的话，
	 * 每物品一次伸收（§7.22）会让"收回 + 待机"那段时间完全没有认领 ⇒ 物品从臂下流走、定不住
	 * （用户第二十七轮续三十五实测）。现在判据 = 当前条目是不是取物/组装（{@link #pinOffset}）。
	 */
	public boolean shouldPinTarget(SequencedArmBlockEntity arm) {
		return pinOffset(arm) != null;
	}

	/**
	 * 现在要认领的那个目标的偏移（相对本臂）；不认领时返回 {@code null}。
	 *
	 * <p><b>判据是"程序想干什么"，而不是"手臂现在处于什么状态"</b>（用户第二十七轮续三十五实测修正）：
	 * 原来写成 {@code state ∈ {MOVING, EXECUTING}}，于是**每条条目结束后的收回 + 待机那段时间
	 * 整条带子都不再被认领** —— 物品从臂下直接流过去、谁也定不住（"又变成无法定住被加工的物品了"）。
	 * 而"手臂在折返/在等下一件"并不代表它不要那件物品：只要当前条目还是「取物 / 组装」，
	 * 它就该继续把目标格里的物品按住。★ 这是"每物品一次伸收"（§7.22）与"定住物品"能同时成立的**唯一**
	 * 分法：**动作归动作（相位/动画），认领归认领（程序意图）**，两者不再互相绑死。
	 *
	 * <p>顺带修掉一个更老的隐患：偏移取自**当前条目的目标**（持久化在卡的程序里），而不是
	 * {@code movingOffset}（那是"上一次真的动过身"的目标）。换目标时旧写法会在新目标的
	 * 前置检查期间继续按着**旧目标那一格**的物品。
	 */
	@Nullable
	public BlockPos pinOffset(SequencedArmBlockEntity arm) {
		if (isHalted())
			return null;
		ArmProgramEntry entry = currentEntryOrNull(arm.getProgram());
		// 两道门，问的是**两个不同的问题**（两处判据都不能少）：
		//   ① `needsTarget()`（条目级）：这一条**有没有坐标** —— 过滤的容器模式有（容器模式的坐标是
		//      "读哪个方块"的数据参数），等待类没有；
		//   ② 类型门：只有「取物 / 组装」才**认领**（把目标格里的物品按住等臂来加工）。
		//   ★ 所以控制类条目（过滤 / 等红石 / 收发信号 / 调用 …）永远走不到认领那一支 ——
		//   认领等于"占住一个交互点等臂过来"，而控制类**根本没有臂过去这件事**。
		if (entry == null || !entry.needsTarget())
			return null;
		if (entry.op != OperationType.TAKE && entry.op != OperationType.DEPLOY)
			return null;
		return offsetOf(arm, entry);
	}

	/**
	 * 当前条目目标的那一格（相对本臂）；这条条目没有目标（等待类）时返回 {@code null}。
	 *
	 * <p>与 {@link #pinOffset} 的区别只有一条：**不看操作类型**。给"关心这一格本身"的用法
	 * （带速宽限期、目标是不是带子）用 —— 那些地方不该因为条目是「投放」就退回旧位置。
	 *
	 * <p>过滤容器模式在这里照旧返回它读的那一格（{@code needsTarget()} 为真）：这条问的是
	 * "这条条目指着哪一格"，**不是**"臂要不要伸过去"（后者见 {@code ArmProgramEntry.movesArm()}）。
	 * 它现在其实用不到（过滤不动身 ⇒ 不走前置检查、也不认领），留着是为了让"有坐标就有这一格"
	 * 这件事只有一处答案 —— 换成类型判据会在下次有人让控制类也关心格子时静默指错位置。
	 */
	@Nullable
	public BlockPos entryOffset(SequencedArmBlockEntity arm) {
		ArmProgramEntry entry = currentEntryOrNull(arm.getProgram());
		if (entry == null || !entry.needsTarget())
			return null;
		return offsetOf(arm, entry);
	}

	/**
	 * 认领的**位置条件**：当前条目是「组装」时，要等物品**走到格子中间**才按住；
	 * 其它（取物）**一进入判定范围就按住**（用户第二十七轮续四十实测要求）。
	 *
	 * <p>为什么分两种：取物时手臂的爪子是去"那一格"取东西，物品停在哪都一样（取物走
	 * {@code getStackAtOffset}，只看在不在这一格）；而组装要在物品身上作业，
	 * 让它**自己走到中间**再定住才符合观感 —— 一进范围就按住并把它拽到中间，看起来是"瞬移"。
	 */
	public boolean claimNeedsCentre(SequencedArmBlockEntity arm) {
		ArmProgramEntry entry = currentEntryOrNull(arm.getProgram());
		return entry != null && entry.op == OperationType.DEPLOY;
	}

	/**
	 * 这件物品**现在值得认领吗** —— 认领 = 用 {@code locked} 把它按在带子上等手臂来加工
	 * （见 {@link SequencedArmBlockEntity#updateBeltHold()}）。
	 *
	 * <p>为什么认领要过这一关：认领是**长期的**（跨条目、跨收回、跨待机），如果见谁按谁，
	 * 刚加工出来的**产物**就会被按在原点动弹不得 —— 组装出来的东西恰好不再匹配配方，
	 * 于是那条带子会被一件永远等不到加工的物品堵死。所以只认"这一条条目真的加工得了"的：
	 * <ul>
	 *   <li>{@code TAKE}：臂里放得下（放不下就别按着，让它流走，别把带子堵住）；</li>
	 *   <li>{@code DEPLOY}：手上拿着应用物，且**这件物品匹配得到配方**（产物自然被排除）。</li>
	 * </ul>
	 * 这一步与前置检查用的是同一批事实，所以"认领了却永远加工不了"不会出现。
	 */
	public boolean canWorkOn(SequencedArmBlockEntity arm, ItemStack stack) {
		if (stack.isEmpty())
			return false;
		ArmProgramEntry entry = currentEntryOrNull(arm.getProgram());
		if (entry == null)
			return false;
		Level level = arm.getLevel();
		if (level == null)
			return false;
		return switch (entry.op) {
			case TAKE -> arm.canAccept(stack.copyWithCount(1));
			case DEPLOY -> {
				ItemStack applicator = arm.getHeldItem();
				yield !applicator.isEmpty()
					&& DeployRecipes.find(level, stack.copyWithCount(1), applicator) != null;
			}
			default -> false;
		};
	}

	@Nullable
	private ArmProgramEntry currentEntryOrNull(ArmProgram program) {
		ArmProgramTask task = program.task(taskIndex);
		if (task == null || entryIndex < 0 || entryIndex >= task.entries.size())
			return null;
		return task.entries.get(entryIndex);
	}

	/**
	 * 一次不成功。§7.7 v2 的核心：<b>"失败"不是独立分支，而是"还没成功"</b> ——
	 * 原地重试（每 {@link #PRECHECK_RETRY_TICKS} 重跑一次前置检查，不空跑也不抖动画），
	 * 直到「失败后」的时限（{@link ArmProgramEntry#timeoutTicks()}，{@code -1} = 一直重试）到点；到点后走 {@code onTimeout}。
	 *
	 * <p>所以这里不再有"立刻失败"这条路：老版本的 {@code force}/{@code onFail}
	 * 已在 {@code ArmProgramEntry.load} 里迁移成 time/onTimeout。
	 */
	private void handleFailure(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, String reason) {
		// ⚠️ **同步节流**：重试本身是每 tick 的（对齐上游），但"每 tick 都发一次方块实体同步"纯属浪费 ——
		// 客户端每 tick 收到一次同步会让手臂动画看起来一顿一顿（用户实测："尝试定住物品的瞬间动画会变慢"）。
		// 所以只有**状态或失败原因真的变了**才发。
		boolean changed = state != EntryState.RETRY || !reason.equals(lastFailure);
		lastFailure = reason;
		if (entry.timeoutTicks() < 0 || waitElapsed <= entry.timeoutTicks()) {
			state = EntryState.RETRY; // 阻塞重试（前摇不重跑；计时在 RETRY 分支按 tick 累加）
			if (changed)
				arm.markProgramDirty();
			return;
		}
		applySkip(arm, task, entry, entry.onTimeout, reason + "（重试了 " + entry.failAmount + entry.failUnit.name() + "）");
	}

	/**
	 * 超时后的出口（§7.7 v2）。
	 *
	 * <ul>
	 *   <li>{@code CONTINUE}：跳过这条，继续下一条；</li>
	 *   <li>{@code SKIP_TO}：跳到指定任务/条目；</li>
	 *   <li>{@code NONE}：不设 —— {@code UNTIL_FAIL} 的循环里表示"停在原地等条件恢复"
	 *       （失败**不等于整轮重来**：那会把"手上已经拿着物品"这类进度丢掉；
	 *       正确做法是停在当前条目上节流重试，条件恢复后自然继续），
	 *       其它循环模式下才是真正的停机报错。</li>
	 * </ul>
	 */
	private void applySkip(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, SkipAction action,
		String reason) {
		switch (action) {
			case CONTINUE -> {
				lastFailure = reason + "（跳过）";
				resetEntryCounters();
				entryIndex++;
				if (entryIndex >= task.entries.size())
					finishTask(arm, task);
				else
					arm.markProgramDirty();
			}
			case SKIP_TO -> {
				lastFailure = reason + "（跳转 " + entry.timeoutTarget + "）";
				jumpTo(arm, entry.timeoutTarget);
			}
			case NONE -> {
				if (task.loop == LoopType.UNTIL_FAIL) {
					// "循环直到无法继续"：失败**不等于整轮重来** —— 那会把"手上已经拿着物品"这类
					// 进度丢掉：B 被占满时取出的物品再也送不进去（实测 bug）。
					// 正确做法是停在当前条目上、原地节流重试；条件恢复后自然继续往下走，
					// 只有一轮真正走完才回到开头。
					lastFailure = reason + "（UNTIL_FAIL 等待中）";
					resetEntryCounters(); // 同一条目重新尝试：清计时/计数，但**保留游标**
					if (idleCooldown <= 0)
						idleCooldown = PRECHECK_RETRY_TICKS;
					arm.markProgramDirty();
				} else
					halt(arm, reason);
			}
		}
	}

	/**
	 * 跳转：数字 = 本任务内的条目序号，**从 1 数**（用户在界面上看到的序号就是它）；
	 * 也接受任务名（切到那个任务的第 1 条）。
	 *
	 * <p>⚠️ 序号从 1 数是<b>用户可见的约定</b>（提示文案一直这么写），而运行时第一版直接把它当 0 基下标用，
	 * 于是"跳转到 1"实际跳到第 2 条 —— 实测发现的静默错位。现在统一：`N` → `entries[N-1]`。
	 *
	 * <p>额外允许 `N = 条目数 + 1`＝<b>跳到末尾（这一轮就到此为止）</b>：
	 * 循环体里"提前结束这一轮"用它很自然（要连循环一起结束就用「退出」操作）。
	 */
	private void jumpTo(SequencedArmBlockEntity arm, String target) {
		ArmProgram program = arm.getProgram();
		String trimmed = target == null ? "" : target.trim();
		ArmProgramTask task = program.task(taskIndex);
		if (task != null) {
			try {
				int oneBased = Integer.parseInt(trimmed);
				if (oneBased >= 1 && oneBased <= task.entries.size() + 1) {
					resetEntryCounters();
					entryIndex = oneBased - 1; // size+1 → entryIndex = size → 下一 tick 收尾
					arm.markProgramDirty();
					return;
				}
				halt(arm, "跳转序号超范围：" + oneBased + "（本任务共 " + task.entries.size() + " 条，序号从 1 数）");
				return;
			} catch (NumberFormatException ignored) {
				// 不是序号，按任务名找
			}
		}
		for (int i = 0; i < program.tasks.size(); i++) {
			if (program.tasks.get(i).name.equals(trimmed)) {
				resetCallScopeIfOutside(program, i);
				resetEntryCounters();
				taskIndex = i;
				entryIndex = 0;
				arm.markProgramDirty();
				return;
			}
		}
		halt(arm, "跳转目标不存在: " + trimmed);
	}

	/**
	 * 跳转到别的任务时收拾一下调用栈：与调用不同，跳转是**单向**的，没有返回地址可言。
	 * 如果目标任务正好在栈上（说明是"跳回调用者"），就把那层弹掉，避免栈里留下永远回不去的记录。
	 */
	private void resetCallScopeIfOutside(ArmProgram program, int newTask) {
		while (!callStack.isEmpty()) {
			Frame top = callStack.get(callStack.size() - 1);
			if (top.returnTask() == newTask) {
				callStack.remove(callStack.size() - 1);
				continue;
			}
			break;
		}
	}

	/**
	 * 一轮走完（{@code entryIndex} 已经越过最后一条）。
	 *
	 * <p>§7.6 v4：循环留在任务上 —— 计次重复 n 轮、一直重复、直到成功各是各的语义；
	 * 一轮"真正结束"之后：**在子任务里 = 返回调用方**，**在主任务里 = 收工**（主任务若在循环则回到开头）。
	 */
	private void finishTask(SequencedArmBlockEntity arm, ArmProgramTask task) {
		resetEntryCounters();
		entryIndex = 0;
		// ★ 被调用的任务：轮次由**调用点**决定（用户第二十七轮："把循环次数放在调用上"）。
		// 跑完一轮先看调用点还要求再跑几轮 —— 还要求就原地重跑（不弹栈），否则返回调用方。
		// 子任务自身的 loop 设置在这里**不参与**：两套机制相乘（调用 ×3 × 自己循环 ×2）谁都算不清。
		if (!callStack.isEmpty()) {
			int top = callStack.size() - 1;
			Frame frame = callStack.get(top);
			if (frame.hasAnotherRound()) {
				callStack.set(top, frame.withOneFewerRepeat());
				arm.markProgramDirty();
				return;
			}
			endTask(arm, false, null); // 次数用尽：回到调用点的下一条
			return;
		}
		boolean roundDone = switch (task.loop) {
			case COUNT -> {
				loopCounter++;
				if (loopCounter >= Math.min(task.loopCount, ArmProgramTask.MAX_LOOP_COUNT)) {
					loopCounter = 0;
					yield true;
				}
				yield false;
			}
			case NONE -> {
				loopCounter = 0;
				yield true;
			}
			case FOREVER, UNTIL_FAIL -> {
				// 永远重复：不结束，直接回到开头（要收尾就用「退出」操作）
				yield false;
			}
		};
		if (!roundDone) {
			arm.markProgramDirty();
			return;
		}
		endTask(arm, false, null);
	}

	public void halt(SequencedArmBlockEntity arm, String reason) {
		state = EntryState.HALTED;
		error = reason;
		errorEntryIndex = entryIndex;
		arm.cancelMove(); // 停机也收回：出错时把手臂留在目标前面既难看又挡视线
		// ⚠️ §7.16e 有意的**反转**：停机**不再清信号**（旧行为在 §7.16c 里被 D 验过，用户这轮推翻）。
		// 理由与"主任务正常结束"一样：信号是**持久变量**，停机只是"程序停了"，不等于"变量复位"。
		arm.markProgramDirty();
	}

	private void resetEntryCounters() {
		state = EntryState.IDLE;
		timer = 0;
		waitElapsed = 0;
		unloadMoved = 0;
	}

	/** dev 命令用：回到程序开头（主任务第 0 条），清错误、清调用栈。 */
	public void resetToStart(SequencedArmBlockEntity arm) {
		taskIndex = 0;
		entryIndex = 0;
		loopCounter = 0;
		idleSpins = 0;
		idleCooldown = 0;
		cachedPowerVersion = -1;
		callStack.clear();
		resetEntryCounters();
		clearError();
		arm.cancelMove();
		// 回到开头 = 这一轮的"占用"作废：发出去的频率要一起收干净（§7.16b 一.3）
		arm.clearTransmissions();
		arm.markProgramDirty();
	}

	/** 程序被替换时调用：调用栈与缓存都要重来。 */
	public void onProgramReplaced(SequencedArmBlockEntity arm) {
		// 卡被拆 / 被换：上一份程序发出的频率必须现在就关到 0 并退网（§7.16b 一.3 的第四个收尾点）
		arm.clearTransmissions();
		callStack.clear();
		cachedPowerVersion = -1;
		resetToStart(arm);
	}

	// ————————————————————————— 存读盘 —————————————————————————

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("task", taskIndex);
		tag.putInt("entry", entryIndex);
		tag.putString("state", state.name());
		tag.putInt("timer", timer);
		tag.putInt("waited", waitElapsed);
		tag.putInt("loop", loopCounter);
		tag.putInt("idle", idleCooldown);
		ListTag stack = new ListTag();
		for (Frame frame : callStack) {
			CompoundTag f = new CompoundTag();
			f.putInt("t", frame.returnTask());
			f.putInt("e", frame.returnEntry());
			f.putInt("l", frame.callerLoopCounter());
			f.putInt("r", frame.repeatsLeft()); // 调用点还剩几轮（不存的话重登后会多跑/少跑）
			stack.add(f);
		}
		tag.put("stack", stack);
		tag.putInt("spins", idleSpins); // 老存档里的 steps/ticks 已废（那两条预算删了），读的时候直接忽略
		if (error != null) {
			tag.putString("error", error);
			tag.putInt("errorEntry", errorEntryIndex);
		}
		return tag;
	}

	public void load(CompoundTag tag) {
		taskIndex = tag.getInt("task");
		entryIndex = tag.getInt("entry");
		state = readState(tag.getString("state"));
		timer = tag.getInt("timer");
		waitElapsed = tag.getInt("waited");
		loopCounter = tag.getInt("loop");
		idleCooldown = tag.getInt("idle");
		callStack.clear();
		ListTag stack = tag.getList("stack", Tag.TAG_COMPOUND);
		for (int i = 0; i < stack.size(); i++) {
			CompoundTag f = stack.getCompound(i);
			// 缺 r → 1（照 ArmProgramEntry.load 的理由：0 现在是"无限"这个**合法值**，不能拿它兜底）
			callStack.add(new Frame(f.getInt("t"), f.getInt("e"), f.getInt("l"),
				f.contains("r") ? ArmProgramEntry.clampCallRepeat(f.getInt("r")) : 1));
		}
		idleSpins = tag.getInt("spins");
		if (tag.contains("error")) {
			error = tag.getString("error");
			errorEntryIndex = tag.getInt("errorEntry");
		}
	}

	private static EntryState readState(String name) {
		for (EntryState candidate : EntryState.values()) {
			if (candidate.name()
				.equalsIgnoreCase(name))
				return candidate;
		}
		return EntryState.IDLE;
	}

}
