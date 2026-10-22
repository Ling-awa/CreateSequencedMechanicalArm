package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.equipment.sandPaper.SandPaperPolishingRecipe;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.Ling.create_sequenced_mechanical_arm.SequencedArmConfig;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.ArmEffects;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.RightClickBridge;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.TargetInteraction;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmBlockEntity;

import com.simibubi.create.foundation.recipe.RecipeApplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
 *   <li>★ <b>主任务恒为无限循环</b>（任务级循环设置已删除）：走完最后一条就回到第 1 条继续跑；
 *       要收尾只有「退出」(主任务里 = 收工停机) 与「抛错」(失败停机) 两条路。
 *       子任务跑完则返回调用方，跑几轮由调用点的 {@code callRepeat} 决定。</li>
 * </ul>
 * 没有调度器、没有优先级、没有"让位"（那套东西连字段一起删掉了，见 PLAN §11 决策 15 / 69）。
 *
 * <p>核心约束：
 * <ul>
 *   <li><b>每 tick 至多推进 N 条</b>（N = 配置项 {@code entriesPerTick}，默认 2，小数跨 tick 累积）——
 *       但**只有"一瞬间就出结果"的条目**会按这个速率连推；需要坐标的操作仍然是显式多 tick 状态机
 *       （前摇 → 移动 → 到位 → 执行），慢的那些<b>一点都不变快</b>（判据见 {@link #isPaced}）。</li>
 *   <li><b>常驻执行 + 红石门控</b>（门控在「等待信号」操作里，见 §7.6）。</li>
 *   <li><b>两条守卫</b>（都只拦"真出问题"的情况，<b>不按时间停机</b>）：调用栈深度上限；
 *       「空转」上限 —— 连续 {@link #MAX_IDLE_SPINS} 条没跟世界打交道（没移动、没等待）才算空转。
 *       ⚠️ 原来还有"单次运行时长 / 总步数"两条预算，它们会误杀"等玩家主动触发"的长时间等待，已删除（见常量注释）。</li>
 *   <li>失败语义统一（§7.7）：一条操作只有两个出口 —— <b>成功</b>与<b>失败/超时</b>；
 *       会无限等的操作才有"时限"，只有硬失败的操作（左键 / 调用）失败即刻执行。</li>
 * </ul>
 */
public class ProgramRuntime {

	/** 失败 / 错误文案的翻译键前缀（{@link FailText#of} 只写后缀，自检也按它把后缀拼成键）。 */
	public static final String FAIL_PREFIX = "create_sequenced_mechanical_arm.gui.fail.";

	/**
	 * 一条运行期失败 / 错误文案：<b>护目镜按玩家语言显示</b>（客户端解析翻译键），
	 * 运行日志与 /sma 用服务端解析出来的文本。
	 *
	 * <p>为什么不能只留一个 String：这几句原来全是中文字面量，护目镜上是<b>写死的中文</b>
	 * （用户口径："护目镜上的报错也要跟着玩家语言走"）。而运行日志 / /sma 是服务端的东西、
	 * 拿不到玩家语言 ⇒ 同一份文案得装两种形态：能翻译的（{@code key} + 参数）走 Component，
	 * 没法翻译的（Create 自己给的英文原因、玩家自己写的抛错文本）走字面量（{@code key == null}）。
	 *
	 * <p>⚠️ 服务端 {@code Component#getString()} 是安全的（NeoForge 会把模组的 en_us 装进服务端的
	 * Language，实测解析得出英文），所以日志那一侧照旧拿字符串，不需要另存一份。
	 *
	 * @param text 要么是可翻译的一条（发给客户端由它解析），要么是字面量
	 * @param key  {@code null} = 不可翻译；否则是 {@link #FAIL_PREFIX} 之后的那个后缀
	 */
	public record FailText(Component text, @Nullable String key) {
		/** 可翻译的一条（key 不带前缀，前缀见 {@link #FAIL_PREFIX}）。 */
		public static FailText of(String key, Object... args) {
			return new FailText(Component.translatable(FAIL_PREFIX + key, args), key);
		}

		/** 不可翻译的一条（Create 自己给的英文原因、玩家自己写的抛错文本）。 */
		public static FailText literal(String text) {
			return new FailText(Component.literal(text), null);
		}
	}

	/**
	 * 「空转」守卫：连续多少**条**没跟世界打交道就绕回来了。
	 *
	 * <p>用户第三轮问过"连续无进展超时是怎么触发的" —— 原来那两条（60s 无成功、20 万步预算）都是
	 * "多久没成功就停机"，而<b>等玩家主动触发</b>（补货、拨红石链接、等机器产出）本来就可能等几分钟到几小时，
	 * 用户原话："有些事件需要玩家主动触发，动力臂必须一直等待"。所以那两条**删掉了**，换成这条：
	 * 只要进入了 {@link #isPaced} 认定的"要花时间"的状态就清零，等待**永远**不会被它盯上，
	 * 能抓到的只剩真正的空转（空的无限循环、没有任何操作的跳转自环）——这类才是真的什么也没发生。
	 *
	 * <p>⚠️ <b>单位是"条"不是"tick"</b>（本值改速率时改过一次）：一 tick 能跑多条无动作条目之后，
	 * 按 tick 计数会把正常的长控制链（几百条收信号/过滤，几 tick 就跑完）误判成空转并停机。
	 * 每一条"没做任何事、也没推进游标"的条目记一次（{@link #idleSpins}），
	 * 于是 4096 的含义是"4096 条空转条目"，而**每 tick 能跑多少条另有 {@link #MIN_ENTRY_BUDGET_PER_TICK}
	 * 与 {@link #maxEntriesPerTick()} 两道天花板** —— 所以这个数不会变成"一个 tick 里跑 4096 条"。
	 */
	public static final int MAX_IDLE_SPINS = 4096;
	/**
	 * 一个 tick 内**无动作条目**的硬上限里那条"小常量"。
	 *
	 * <p>为什么需要第二条上限：空转守卫是按**条**计数的（见 {@link #MAX_IDLE_SPINS}），
	 * 4096 条意味着"一个跳转自环可以在同一个 tick 里跑 4096 条" —— 单 tick 卡顿几百毫秒。
	 * 所以每 tick 再压一道 8 条的天花板（对默认速率 2 完全无影响，只是把"配置填得很大"与
	 * "脚本自跳转"这两种情况都按住）。取 8 而不是 1：常规控制链（收信号 → 过滤 → 调用）
	 * 常常是几连跳，压到 1 就等于把配置项废掉了。
	 *
	 * <p>⚠️ 它的单位是**条**，落到主循环里要乘 {@link #STEPS_PER_ENTRY} 换成步数（见 {@link #maxStepsPerTick()}）。
	 */
	public static final int MIN_ENTRY_BUDGET_PER_TICK = 8;
	/**
	 * 「一条（一瞬间出结果的）条目」在状态机里要走**几步**。
	 *
	 * <p>正好两步：{@code IDLE → beginEntry}（进 {@code EXECUTING}）与 {@code EXECUTING → execute}
	 * （出结果、游标前进）。所以"每 tick 推进 N 条" ⇔ "每 tick 给 {@code N × 2} 步的预算" ——
	 * {@link #tick} 里那两处（累积与硬上限）都用这个常量换算，别在两处各写一个 2。
	 *
	 * <p><b>为什么不直接按"游标前进一次 = 1 条"记账</b>（用户口径的字面读法，曾经想这么写）：
	 * 那样 {@code beginEntry} 与 {@code execute} 会被挤进**同一个 tick**，于是"控制类条目在两拍之间
	 * 停在 {@code EXECUTING}"这个窗口在 tick 边界上**永远看不到**。而自检里有两条判据正是拿它当
	 * "真的执行了"的唯一证人（{@code checkSignalCutHasNoDuration} 的 {@code sawExecuting}、
	 * {@code runFilterEntry} 的"先看见 EXECUTING 再回到 IDLE"），它们都写明过理由：
	 * 看不见就等于"测了空气"。步粒度记账两者都保得住 —— 观感/速率口径与"按条"完全一致
	 * （速率 2 ⇒ 一 tick 两条、速率 0.5 ⇒ 一条控制类条目跨 2 tick），中间态也照旧可见。
	 */
	public static final int STEPS_PER_ENTRY = 2;
	/**
	 * 小数预算的比较容差（单位 = 步）。
	 *
	 * <p>为什么需要：{@code 0.05 条/tick} 这种速率累加 10 次得到的是 {@code 0.9999999999999999}
	 * （二进制表示误差），严格比 {@code 1.0} 会让"每 10 tick 一条"变成每 11 tick 一条。
	 * 容差只用来**判够没够一步**；扣账永远是整 1.0，所以它不会让预算漂移。
	 */
	private static final double BUDGET_EPSILON = 1.0e-9;
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
	/** 前置检查未通过后不动手臂的那段时间。 */
	public int idleCooldown;
	/**
	 * 连续多少**条**"没进入任何要花时间的状态、也没推进游标"就绕回来了（见 {@link #MAX_IDLE_SPINS}）。
	 *
	 * <p>语义与旧版一致（仍然是"原地绕圈"的计数），只是单位从 tick 换成了条 —— 理由见常量注释。
	 */
	public int idleSpins;
	/**
	 * 速率预算里**不足一步的那点余量**（跨 tick 累积到这里）。
	 *
	 * <p>⚠️ <b>单位是"状态机步"，不是"条"</b>：一条一瞬间出结果的条目 = {@link #STEPS_PER_ENTRY} 步
	 * （见那个常量的注释）。{@code entriesPerTick = 0.5} 时每 tick 只加 {@code 0.5 × 2 = 1} 步 ——
	 * 于是控制类条目每 tick 前进**一步**：第 1 tick 进 {@code EXECUTING}、第 2 tick 出结果，
	 * 正是旧版（每 tick 一个状态机 case）的节奏；{@code 2} 时每 tick 加 4 步 = 两条。
	 *
	 * <p>纯小数速率（{@code 0.05}）靠它跨 tick 累积：每 tick 攒 0.1 步，攒够 1 步才动一下。
	 * <b>每走一步就扣一步</b>（{@link #tick} 里的 {@code rateBudget -= 1.0}），所以它**不会只涨不消** ——
	 * 之前的实现只加不扣，于是速率随运行时间自己越涨越高（攒到几万步就等于把配置项废掉了）。
	 *
	 * <p><b>不落盘</b>（重启后从 0 重新攒，最多让一条晚半拍，不值得为它加一个 NBT 键去污染存档格式）；
	 * 换卡 / 回到开头（{@link #resetToStart}）清零、停机（{@link #halt}）清零、待机让出时清零
	 * （"不攒账"，否则等待几百 tick 之后会一次性吐出几百条 —— 速率 0.5 的人看到的就是
	 * "卡住半天然后瞬移"）。
	 */
	public double rateBudget;

	/**
	 * <b>只给自检用</b>的速率覆盖（{@code null} = 按配置项 {@link SequencedArmConfig#entriesPerTick()}）。
	 *
	 * <p><b>优先级：覆盖值优先于配置项</b> —— 配置是运行期现读的（玩家可能在游戏里改），
	 * 而自检要的是"钉死的节奏"：老夹具全按"一 tick 一步"写（例如"第 2 拍进 EXECUTING、
	 * 第 3 拍出结果"），速率一变它们读到的中间状态就变了。所以自检统一钉
	 * {@code 0.5 条/tick}（= 每 tick 一步 = 旧版节奏，见 {@link #STEPS_PER_ENTRY} 与
	 * {@code SequencedArmSelfTest.OLD_PACE_RATE}）；只有专门验速率的几条会自己覆盖成 2.0 / 0.5。
	 *
	 * <p>⚠️ 别把常态值钉成 1.0：那等于"每 tick 两步"，控制类条目一 tick 就走完了，
	 * 靠"看见过 EXECUTING"当证人的老夹具会集体失去观察窗口（理由见 {@link #STEPS_PER_ENTRY}）。
	 *
	 * <p>为什么要这个开关而不是"让老夹具跑配置值"：断言本身**一条都不许放松**
	 * （决策 88：错的期望值和错的实现一样有害），钉住速率才能证明"速率换了，老语义没变"。
	 */
	@Nullable
	private static Double rateOverride;

	/**
	 * 自检专用：把推进速率钉成 {@code rate}（{@code null} = 恢复"按配置项"）。
	 *
	 * <p>静态字段 = 整个进程的运行时一起生效，自检是单线程顺序跑的，所以不需要按实例设置。
	 * <b>不改 PACE 语义</b>：这里只改"每 tick 给多少条预算"，有动作的条目该几 tick 还是几 tick。
	 */
	public static void pinRateForTests(@Nullable Double rate) {
		rateOverride = rate;
	}

	/** 生效速率：自检覆盖值优先，否则读配置项（配置未加载时回落默认值）。 */
	private static double effectiveRate() {
		Double override = rateOverride;
		return override != null ? override : SequencedArmConfig.entriesPerTick();
	}

	/**
	 * 一个 tick 内允许推进的**条目数硬上限** = {@code max(8, ceil(速率))}。
	 *
	 * <p>为什么必须有：速率是玩家可配的，而"一条条目跳回自己"的脚本会让本 tick 的推进永不停止 ——
	 * 上限把"配置填得很大"与"脚本自环"两种情况一起按住（{@code ceil} 保证任何合法速率都不被这个
	 * 上限卡住，{@code 8} 是给默认速率 2 与几连跳留的余量，见 {@link #MIN_ENTRY_BUDGET_PER_TICK}）。
	 */
	public static int maxEntriesPerTick() {
		return Math.max(MIN_ENTRY_BUDGET_PER_TICK, (int) Math.ceil(effectiveRate()));
	}

	/**
	 * 一个 tick 内允许走过的**状态机步数**硬上限 = {@code maxEntriesPerTick() × STEPS_PER_ENTRY}。
	 *
	 * <p>预算按步记（理由见 {@link #STEPS_PER_ENTRY}），所以天花板也必须换成步，否则速率一大，
	 * "两步才算一条"的控制类条目会被这道闸卡掉一半。取 {@code 2 × 条数}：每条最多两步，
	 * 于是"合法速率恰好不被卡住"，而"自跳转脚本"仍然被按住（速率 2 ⇒ 16 步 = 8 条）。
	 */
	public static int maxStepsPerTick() {
		return maxEntriesPerTick() * STEPS_PER_ENTRY;
	}

	/**
	 * 调用栈：每层记住"从哪个任务的哪一条进来"。子任务跑完/退出时弹一层回到那里继续。
	 *
	 * <p>这就是单线程模型剩下的一点点"状态"—— 以前的 {@code taskStates}（每任务一个游标）
	 * 换成了这一个栈，而且语义清楚得多。
	 */
	public final List<Frame> callStack = new ArrayList<>();

	/**
	 * 一层调用：回到 {@code returnTask} 的 {@code returnEntry}。
	 *
	 * <p>★ {@code entryShift} = <b>进入这个被调任务时生效的**调用偏移**</b>（见
	 * {@link ArmProgramEntry#callShift}）= 调用方那一刻的生效偏移 + 那条调用卡自己的 Δ。
	 * 它随帧一起压栈、随帧一起弹掉，所以"返回调用方就恢复"是**结构上**成立的（不需要额外的恢复代码）。
	 *
	 * <p>⚠️ 这里**不再有"调用方当时的循环计数"**（任务级循环随用户口径一起删除了）：
	 * 弹栈后要恢复的只有"回到哪一条"与生效偏移这两件。
	 */
	public record Frame(int returnTask, int returnEntry, int repeatsLeft, BlockPos entryShift) {
		/** 这个调用点还要让子任务再跑几轮（第 1 轮算在 {@code repeatsLeft} 里）。 */
		public boolean hasAnotherRound() {
			// ★ 0 = 无限循环（用户第四十轮）：不能递减、也不返回 —— 要停就靠「中断」/停机，或者子任务里的「退出」
			return repeatsLeft == ArmProgramEntry.CALL_REPEAT_INFINITE || repeatsLeft > 1;
		}

		/** 一轮跑完之后的剩余轮次；「无限」永远是「无限」（不许把 0 减成 -1 再当成"还剩 0 轮"）。 */
		public Frame withOneFewerRepeat() {
			return repeatsLeft == ArmProgramEntry.CALL_REPEAT_INFINITE ? this
				: new Frame(returnTask, returnEntry, repeatsLeft - 1, entryShift);
		}
	}

	/**
	 * <b>当前生效的调用偏移</b>（相对量，见 {@link ArmProgramEntry#callShift}）——
	 * 世界目标 = {@code 臂坐标 + 条目 offset + 这个值}，叠加点在 {@link #offsetOf} 一处。
	 *
	 * <p><b>为什么"按栈顶读"而不是维护一个"当前偏移"字段</b>（三选一里选这个）：
	 * <ul>
	 *   <li><b>没有第二份真相</b>：它是 {@code callStack} 的**纯函数**（栈空 = 主任务 = 0），
	 *       所以 {@code callStack.clear()}（程序被换 / 任务数变了）、{@code endTask} 弹帧、
	 *       {@code resetCallScopeIfOutside} 跳回调用方 —— 这些路径**一处都不用改**就不可能忘；
	 *       维护字段的话每一处都要手动恢复，漏一处就是"返回后调用方还在偏移"（决策 68/85 那类分叉）；</li>
	 *   <li><b>存读一致</b>：{@code save/load} 只要把每帧的偏移存成键，读回来栈顶就是答案，
	 *       不需要再存一个"当前值"、也就不会出现"栈与当前值对不上"；</li>
	 *   <li><b>性能可忽略</b>：栈深上限 {@link #MAX_CALL_DEPTH} = 8，而这里只是读栈顶一个字段
	 *       （{@code pinOffset} 每 tick 被问几次，与它同量级）。</li>
	 * </ul>
	 */
	public BlockPos effectiveCallShift() {
		return callStack.isEmpty() ? BlockPos.ZERO : callStack.get(callStack.size() - 1)
			.entryShift();
	}

	// ——— 瞬态 ———
	private int unloadMoved;
	/** 本 tick 已经走过的**状态机步数**（每次循环迭代 +1，见 {@link #tick}）—— 只服务硬上限 {@link #maxStepsPerTick()}；速率预算另记在 {@link #rateBudget}。 */
	private int tickBudgetUsed;
	@Nullable
	private FailText lastFailure;
	@Nullable
	private FailText error;
	private int errorEntryIndex = -1;
	/**
	 * 频率判定的去抖缓存（照抄 Create 时刻表条件的 {@code globalPowerVersion} 手法）。
	 *
	 * <p>⚠️ <b>只有 {@code globalPowerVersion} 一个键已经不够了</b>（加了「接收半径」之后）：
	 * 判定结果现在同时取决于 **本臂的位置** 与 **这一条的半径**，两者任一变化都必须重算 ——
	 * 否则"同一条程序里两条半径不同的收信号条目"会互相喂对方的缓存（症状是"半径改了却没生效"，
	 * 只有把条目顺序调一调才复现）。所以缓存的有效性 = {@code (version, 臂位置, 半径)} 三者全相同。
	 *
	 * <p>失效方式有两类：① 上面这个三元组不等（世界里的电平变了 / 换了条目 / 换了半径）；
	 * ② {@code resetToStart} 与 {@code onProgramReplaced} 把 {@code cachedPowerVersion} 置回 -1
	 * （换卡 / 回到开头 = 另一个故事，那一处原有写法照旧保留，不需要再清别的字段）。
	 */
	private int cachedPowerVersion = -1;
	/** 缓存那次判定时用的臂位置（{@code null} = 还没有缓存）。 */
	@Nullable
	private BlockPos cachedSignalPos;
	/** 缓存那次判定时用的接收半径。 */
	private int cachedSignalRange = -1;
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

	/**
	 * 停机原因（<b>服务端解析出来的文本</b>：运行日志与 /sma 读的就是这一份）。
	 *
	 * <p>护目镜要走 {@link #getErrorText()} —— 那一份是 Component，客户端会按玩家语言解析。
	 */
	@Nullable
	public String getError() {
		return error == null ? null : error.text()
			.getString();
	}

	/** 停机原因那条文案本身（{@code null} = 没停机）—— 给护目镜摘要用。 */
	@Nullable
	public Component getErrorText() {
		return error == null ? null : error.text();
	}

	/** 停机原因的翻译键后缀（{@code null} = 不可翻译 / 玩家自己写的字面量）。 */
	@Nullable
	public String getErrorKey() {
		return error == null ? null : error.key();
	}

	/** 最近一次失败的那句话（<b>服务端解析出来的文本</b>；护目镜走 {@link #getLastFailureText()}）。 */
	@Nullable
	public String getLastFailure() {
		return lastFailure == null ? null : lastFailure.text()
			.getString();
	}

	/** 最近一次失败 / 提示的那条文案本身（{@code null} = 现在没有失败）—— 给护目镜摘要用。 */
	@Nullable
	public Component getLastFailureText() {
		return lastFailure == null ? null : lastFailure.text();
	}

	/**
	 * 最近一次失败的翻译键后缀（{@code null} = 不可翻译）。
	 *
	 * <p>★ 自检拿它断言"失败属于哪一类"：{@link #getLastFailure()} 是<b>服务端语言</b>解析出来的文本
	 * （专用服务器上是英文），拿中文子串去比会随语言而红 —— 键才是与语言无关的判据。
	 */
	@Nullable
	public String getLastFailureKey() {
		return lastFailure == null ? null : lastFailure.key();
	}

	public void clearError() {
		error = null;
		errorEntryIndex = -1;
		lastFailure = null;
	}

	// ————————————————————————— 运行日志（用户要的"导出最近的日志"） —————————————————————————

	/**
	 * 日志行的"在哪一条"前缀：{@code 任务名#条目号 动作名}，条目号<b>从 1 数</b>
	 * （与界面、脚本里显示的序号是同一套，用户看到 "第 3 条" 时日志里就是 {@code #3}）。
	 *
	 * <p>动作名用枚举的小写名（{@code take} / {@code wait} / {@code call}…）而不是界面上的中文：
	 * 那些名字就是 {@code .sma} 脚本里的写法，玩家贴给别人的时候两处能对上。
	 *
	 * <p>★ <b>拼接纪律</b>：调用点一律写成
	 * {@code if (arm.logEnabled()) arm.logLine(logWhere(task, entry) + " …");} ——
	 * <b>先判配置、再拼字符串</b>（关掉记录时一行都不产生、也不付分配代价）。
	 */
	private String logWhere(@Nullable ArmProgramTask task, @Nullable ArmProgramEntry entry) {
		String name = task == null ? "?" : task.name;
		String op = entry == null ? "?" : entry.op.name()
			.toLowerCase(Locale.ROOT);
		return name + "#" + (entryIndex + 1) + " " + op;
	}

	/**
	 * 「这一条开始执行」那行的后半句。等待类必须带上"等多久 / 等哪个极性" ——
	 * 用户要回答的问题里有一半（"等了多久"）的答案就落在这一句上。
	 */
	private static String beginText(ArmProgramEntry entry) {
		return switch (entry.op) {
			case WAIT -> " 开始等待 " + entry.waitTicks() + "t";
			case SIGNAL -> entry.isSignalReceive() ? " 开始等信号" : " 发信号";
			// 「等待类」只有 WAIT 与 SIGNAL 两种，上面两个 case 已经把它们吃掉了 ⇒ 走到这里的
			// 一定不是等待类，直说"开始执行"即可（原来那个 `entry.op.isWait() ? …` 是恒真的三元）。
			default -> " 开始执行";
		};
	}

	// ————————————————————————— 主循环 —————————————————————————

	/**
	 * 推进一个 tick（PLAN.md §7.6 v4 的主循环）。
	 *
	 * <h2>推进速率（本值新增）</h2>
	 * 每 tick 先给本 tick 一笔**步数预算**（{@code budget += 速率 × STEPS_PER_ENTRY}，小数跨 tick 累积：
	 * 0.5 条/tick = 每 tick 一步），然后在"还能推进"时**连续推进**，每走一步扣一步。
	 *
	 * <p>★ <b>为什么按"状态机步"记账、而不是按字面的"游标前进一次 = 1 条"</b>（这是这一处最关键的推导，
	 * 见 {@link #STEPS_PER_ENTRY} 的注释）：一条"一瞬间出结果"的条目**正好两步**（{@code beginEntry}
	 * 进 {@code EXECUTING}，{@code execute} 出结果并推进游标），所以"每 tick 2×速率 步"就等价于
	 * "每 tick 速率 条"：速率 2 ⇒ 一 tick 两条、速率 0.5 ⇒ 一条控制类条目跨 2 tick。
	 * 若真按"游标前进一次"记 1 条，那两步会被挤进同一 tick，{@code EXECUTING} 这个中间态在 tick
	 * 边界上再也看不到 —— 而自检两条判据正是拿它当"真的执行了"的唯一证人（见 {@link #STEPS_PER_ENTRY}）。
	 *
	 * <h2>什么时候立刻让出（剩下的预算清零，留到下一 tick）</h2>
	 * <b>清零而不是留着</b>，理由是"小数跨 tick 累积"的口径要干净：移动 / 等待可能持续几百 tick，
	 * 留着预算就会在等待结束那一刻一次性吐出几百条（速率 0.5 的人看到的会是"卡住半天然后瞬移"）。
	 * 让出的条件（任一满足就 break）：
	 * <ol>
	 *   <li>{@link #isPaced}（移动 / 等待 / 延迟 / 重试）—— <b>有动作的条目一点都不变快</b>，
	 *       它们仍然按真实 tick 走，所以"抬手 → 加工 → 收回"的观感与旧版逐字节相同；</li>
	 *   <li>停机 / 无转速 / 红石锁定 / 程序为空 / {@code idleCooldown > 0} —— 主循环的早退门；</li>
	 *   <li>本 tick 走过的步数达到硬上限 {@link #maxStepsPerTick()} —— 防止"一条自跳转脚本"
	 *       把服务器卡在这一个 tick 里；</li>
	 *   <li>本 tick 的预算用完了（{@code rateBudget < 1} 步）—— 速率本身用完了。</li>
	 * </ol>
	 */
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

		// 本 tick 的速率预算：**以"状态机步"为单位**（一条一瞬间出结果的条目 = STEPS_PER_ENTRY 步），
		// 小数跨 tick 累积 —— 0.5 条/tick = 每 tick 一步（一条控制类条目跨 2 tick，= 旧版节奏），
		// 2 条/tick = 每 tick 四步（= 一 tick 两条）。
		// 只在**真的跑到这里**时累积 —— 上面那些早退（停机 / 锁定 / 无转速 / 空程序）都不攒预算，
		// 否则"暂停几小时后恢复"会一次性吐出几千条。
		// ★ 本 tick 的**步数硬上限只算一次**：它读的是配置项（{@code SequencedArmConfig.entriesPerTick()}，
		//   ModConfigSpec 取值），而下面循环里**每走一步**都要拿它比一次。同一 tick 内这个值不可能变
		//   （单线程；配置只在加载/重载时改），所以提前算一次与"每步现算"结果完全一致，只是省掉每步一次配置读取。
		//   ⚠️ 必须放在上面那些早退**之后**：那几条路径本来就不读配置、也不记预算账。
		final int stepCap = maxStepsPerTick();
		rateBudget += effectiveRate() * STEPS_PER_ENTRY;
		// 单 tick 额度封顶：预算**每走一步就扣一步**（见循环里那句 `rateBudget -= 1.0`），余量正常永远
		// < 1 步、这道封顶用不上 —— 留着是防"将来某条路径忘了扣"时它悄悄攒成一大笔（那正是本值修掉的旧 bug）。
		rateBudget = Math.min(rateBudget, stepCap);
		tickBudgetUsed = 0;

		// §7.6 v4：没有调度器了 —— 谁在跑就是谁。任务数变了（卡被编辑过）就回到主任务。
		if (program.task(taskIndex) == null) {
			// 栈一清，生效的调用偏移自动回到 0（它是栈的纯函数，见 effectiveCallShift）
			callStack.clear();
			taskIndex = 0;
			entryIndex = 0;
			state = EntryState.IDLE;
		}

		// 待机（前置检查没通过）：不动手臂，也不算空转 —— 等输入是完全正常的状态。
		// ⚠️ 节流按 **tick** 计（每 tick 只减 1，与这一 tick 推了多少条无关）；
		//    余下的预算一起清掉（"待机期间不攒账"，理由见方法头注释）。
		if (idleCooldown > 0) {
			idleCooldown--;
			rateBudget = 0;
			if (state == EntryState.RETRY)
				waitElapsed++; // 强制等待按 tick 计时，否则超时会变成"重试次数"
			return;
		}

		// ★ 空转守卫（见 MAX_IDLE_SPINS）：只在"原地绕圈、一条都没跟世界打交道"时计数。
		// 移动/等待/延迟/重试都算"在跟世界打交道"并清零 —— 所以"等玩家发货"这种长时间等待**永远不会**被停机。
		// ⚠️ 计数单位是**条**（不是 tick）：每 tick 可能跑多条无动作控制条目，按 tick 计数会把
		//    正常的长控制链误判成空转。这一点也解释了每 tick 那两道天花板为什么必须存在。
		if (isPaced(state)) {
			idleSpins = 0;
		} else if (++idleSpins > MAX_IDLE_SPINS) {
			halt(arm, FailText.of("idle_spin", MAX_IDLE_SPINS));
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
			// 这一步也要记账：不扣的话它会留下整整一步的零头，下一 tick 就会"多走一步"
			// （0.5 条/tick 时正好是 1 步，攒成 2 步 ⇒ 老夹具的时间线错开一拍）。
			rateBudget -= 1.0;
			return;
		}

		// ——— 本 tick 的连续推进循环 ———
		// 每轮**重新读一次**任务与条目：游标可能被上一步推进（下一条 / 跳转 / 换任务 / 收工），
		// 沿用旧的 `entry` 引用就会对着上一条干活（这正是"同一 tick 先 beginEntry 再 execute"
		// 必须允许的那件事）。
		while (true) {
			if (idleCooldown > 0 || isHalted())
				break; // 上一步可能刚把它设上（前置检查失败 / 停机）
			if (tickBudgetUsed >= stepCap)
				break; // 硬上限：本 tick 走够步数了，剩下的留给下一 tick
			if (rateBudget < 1.0 - BUDGET_EPSILON)
				break; // 本 tick 拿到的预算用完了（0.5 条/tick 时就是"攒够一步才动一下"）

			task = program.task(taskIndex);
			if (task == null) {
				taskIndex = 0;
				entryIndex = 0;
				break;
			}
			if (entryIndex >= task.entries.size()) {
				finishTask(arm, task); // 一轮走完（可能重跑 / 返回调用方 / 收工）：也是一步
				tickBudgetUsed++;
				rateBudget -= 1.0;
				continue;
			}

			ArmProgramEntry entry = task.entries.get(entryIndex);
			int entryBefore = entryIndex;
			int taskBefore = taskIndex;
			int spinsBefore = idleSpins;
			EntryState stateBefore = state;

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
				// ⚠️ 原来这里还有一个 `case HALTED -> { }`：它**不可达**（`tick` 在入口与循环开头
				//   都拿 `isHalted()` 早退/break，而 HALTED 只由 `halt()` 设置），而且真走到了它也只是
				//   "什么都不做"—— 与"没有这个 case"逐字节相同。所以删掉，不留一条假装在兜底的空分支。
			}

			// ★ 每一步都花掉一步的预算（否则"一条自跳转脚本"能在同一 tick 里无限转下去）。
			//   ⚠️ **必须真的从 rateBudget 里扣**：只加不扣的话它会随运行时间越攒越多
			//   （攒到几万步 = 速率配置彻底失效，表现是"一 tick 把能推的全推完"）。
			tickBudgetUsed++;
			rateBudget -= 1.0;

			// ★ 让出：移动 / 等待 / 延迟 / 重试 = "在跟世界打交道、要花真实时间" ⇒ 本 tick 到此为止。
			//   ⚠️ 这一条就是"有动作的条目**不许**变快"的实现：它们永远走这条 break，
			//      一个 tick 只前进一小步，无论速率配成多少。
			if (isPaced(state)) {
				idleSpins = 0;
				rateBudget = 0; // 待机/等待期间不攒账（见方法头）
				break;
			}

			// ★ 空转守卫（按条计数）：走了这一步，却在"不花时间的状态"里原地没动 ——
			//   既没推进游标、也没进任何要花时间的状态。`continue` 的那两种都不是空转：
			//   没有转速 = 冻结；IDLE + 手臂还在折返 = 正常的等动画（旧实现按 tick 计数时也不计它）。
			boolean progressed = entryIndex != entryBefore || taskIndex != taskBefore
				|| idleSpins > spinsBefore;
			if (!progressed && state == stateBefore && state == EntryState.IDLE) {
				if (++idleSpins > MAX_IDLE_SPINS) {
					halt(arm, FailText.of("idle_spin", MAX_IDLE_SPINS));
					break;
				}
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
			FailText reason = callee == null ? FailText.of("call_missing", target) : FailText.of("call_empty", target);
			lastFailure = reason;
			applySkip(arm, caller, entry, entry.onTimeout, reason);
			return;
		}
		if (callStack.size() >= MAX_CALL_DEPTH) {
			FailText reason = FailText.of("call_too_deep", MAX_CALL_DEPTH, target);
			lastFailure = reason;
			applySkip(arm, caller, entry, entry.onTimeout, reason);
			return;
		}
		callStack.add(new Frame(taskIndex, entryIndex + 1,
			ArmProgramEntry.clampCallRepeat(entry.callRepeat),
			// ★ 进入子任务时生效的调用偏移 = 调用方此刻的生效偏移 + 这条调用卡的 Δ（嵌套因此自然叠加：
			//   A→B(Δ1)→C(Δ2) 时 C 的帧里存的就是 Δ1+Δ2）。弹帧即恢复，不需要单独的恢复代码。
			effectiveCallShift().offset(entry.callShift)));
		// ★ 记录点⑥：**压栈**。必须在把游标切到被调任务**之前**拼（logWhere 读的是 entryIndex）。
		//   用户问的"它为什么停了"很多时候答案是"陷在一层调用里"，所以栈深一起写出来。
		if (arm.logEnabled())
			arm.logLine(logWhere(caller, entry) + " 调用 → " + callee.name + "#1（栈深 " + callStack.size() + "）");
		taskIndex = calleeIndex;
		entryIndex = 0;
		state = EntryState.IDLE;
		waitElapsed = 0;
		resetEntryCounters();
		arm.markProgramDirty();
	}

	/**
	 * 结束当前任务：{@code failed} 为真表示"抛出错误"结束。
	 *
	 * <p>在子任务里 = 返回调用方（失败时调用方走「失败后」；成功时调用方走「成功后」——
	 * 后者以前**没接上**，见 {@link #applySuccessExit}）。
	 *
	 * <p>★ <b>在主任务里 = 停机</b>：这个方法**只在「退出」(BREAK) 与「抛错」(THROW) 时被调到**
	 * —— 主任务把最后一条走完不算"结束"，那是 {@link #finishTask} 的恒循环那条路（回到第 1 条重来）。
	 * 所以这里的语义是用户口径的「退出 = 收工停机 / 抛错 = 失败停机」：两者都必须**真的停下**，
	 * 否则恒循环会把「退出」吞成"从头再来一次"。
	 */
	private void endTask(SequencedArmBlockEntity arm, boolean failed, @Nullable String reason) {
		ArmProgram program = arm.getProgram();
		if (failed && reason != null)
			lastFailure = FailText.literal(reason);
		if (!callStack.isEmpty()) {
			Frame frame = callStack.remove(callStack.size() - 1);
			int failedTask = taskIndex;
			int failedEntry = entryIndex;
			taskIndex = frame.returnTask();
			entryIndex = frame.returnEntry();
			// ★ **调用偏移在这里"恢复"**：它没有单独的字段要写回去 —— 弹掉这一帧之后
			//   `effectiveCallShift()` 读到的就是栈顶那一帧（父任务）的偏移，主任务则是 0。
			//   这就是"返回调用方时恢复"与"存读一致"都不用额外代码的原因（见 effectiveCallShift）。
			state = EntryState.IDLE;
			resetEntryCounters();
			// 调用方那条「调用」条目 = `returnEntry() - 1`（`callTask` 压栈时写的就是 `entryIndex + 1`）。
			// 成败两个出口读的**都是这一条**的字段：失败读 `onTimeout`，成功读 `onSuccess`。
			ArmProgramTask caller = program.task(taskIndex);
			int callIndex = entryIndex - 1;
			ArmProgramEntry callEntry = caller != null && callIndex >= 0 && callIndex < caller.entries.size()
				? caller.entries.get(callIndex)
				: null;
			// ★ 记录点⑦：**弹栈**（被调任务跑完 / 失败都从这里回去）。做成一条与⑥对称的行 ——
			//   日志里成对的 "调用 → …" / "↩ 返回 …" 就是调用栈的完整来去。
			if (arm.logEnabled())
				arm.logLine("↩ 返回 " + (caller == null ? "?" : caller.name) + "#" + (callIndex + 1)
					+ (failed ? "（被调任务失败）" : "（被调任务跑完）"));
			if (failed) {
				// 把失败**冒泡到那张调用卡**：调用行为没有时限，立刻执行它的「失败后」。
				if (callEntry != null) {
					// ⚠️ 报**任务名**，不要直接拼 `program.task(failedTask)`：ArmProgramTask 没有 toString()，
					//   拼进去是一个对象哈希 —— 用户看到的提示会是
					//   "被调任务失败（io.github...ArmProgramTask@186b8313 #3）：…"，**认不出是哪个任务**。
					//   （这一轮排查"两层栈深那条断言红在哪"时，正是这条提示最先把人卡住：
					//    只有靠 "#3" 去反推失败的是哪个任务。）
					ArmProgramTask failedTaskObj = program.task(failedTask);
					// "哪个任务哪一条 + 那句原因"一起塞进同一个 %s：键只有一条，但信息一个都不许丢
					applySkip(arm, caller, callEntry, callEntry.onTimeout, FailText.of("callee_failed",
						(failedTaskObj == null ? "?" : failedTaskObj.name) + " #" + (failedEntry + 1) + "：" + reason));
					return;
				}
			} else if (callEntry != null) {
				// ★★ **成功返回也要走那张调用卡的「成功后」出口**（用户报的 bug）：
				//  这里以前只写 `entryIndex = frame.returnEntry()` 就 return —— **从头到尾没读过 `onSuccess`**，
				//  于是「调用」上的 `ok=goto N` 被**静默忽略**（用户现场：脚本 `call 炒锅1 ok=goto 14`
				//  跑完没有跳走，继续执行下一条 `signal send_on … {} + pot` ⇒ 那个频率被通电，
				//  接着死在第 10 条 `take 2 2 -1` 上。`call 汤锅1 ok=goto 30` 等四处同一个坑）。
				//  判据**不在这里复制一份**（决策 68/85：同一判据写两份必然分叉）——
				//  直接复用 `succeed` 那条路：`applySuccessExit` 的约定是"游标指向刚完成的那一条"，
				//  所以先把游标退回「调用」条目自己的下标，它内部的 `entryIndex++` 才算得对
				//  （`onSuccess == NONE` ⇒ 结果仍然 = `frame.returnEntry()`，即"现在的行为"不变）。
				//  ⚠️ 不重跑 `succeed` 的开头三件（idleSpins / lastFailure / retract）：被调任务的收工
				//  已经做过等价的事（它最后一条走的就是 `succeed`），这里只补"出口判据"这一件。
				entryIndex = callIndex;
				applySuccessExit(arm, caller, callEntry);
				return;
			}
			arm.markProgramDirty();
			return;
		}
		// ——— 主任务：只有「退出」/「抛错」会走到这里（跑完最后一条走 finishTask 的恒循环）———
		if (failed) {
			halt(arm, reason == null ? FailText.of("thrown") : FailText.literal(reason)); // 停机那行由 halt 记
			return;
		}
		// ★ 记录点⑧：主任务里的「退出」= **收工停机**（用户口径：「退出」不能因为主任务恒循环而失效）。
		//   ⚠️ 报**任务名**，不要直接拼 task 对象：ArmProgramTask 没有 toString()，
		//   拼进去是一个对象哈希（同一个坑见下面 endTask 里那句"被调任务失败（…）"的注释）。
		if (arm.logEnabled()) {
			ArmProgramTask done = program.task(taskIndex);
			arm.logLine("收工 · 主任务 " + (done == null ? "?" : done.name) + "「退出」");
		}
		// 用 halt 而不是"游标归零"：恒循环之下，归零就等于"从第 1 条重来"，
		// 「退出」那样就彻底失去意义了。⚠️ §7.16e 的**反转**照旧成立：停机不清信号。
		halt(arm, reason == null ? FailText.of("break_stop") : FailText.literal(reason));
	}

	/**
	 * 红石链接频率**当前是不是（在接收半径内）通电**；{@code globalPowerVersion} 去抖
	 * （Create 时刻表同款手法，缓存键扩大到三元组，见 {@link #cachedPowerVersion}）。
	 *
	 * <p>⚠️ 这里只返回**原始通电状态**，绝不掺"等通电/等断电"的极性 ——
	 * 极性由调用方（{@code tickWait}）用 {@code powered == entry.waitForPowered} 比一次就够。
	 * 曾经这里写成 `hasPower != waitForPowered`（自己取反），调用方又比一次，**等于取反两次**：
	 * 用户实测"无论如何都是取反 —— 收到信号时判定为失败（停住），没信号时反而继续执行"。
	 * ⚠️ 查询侧（本方法）与判定侧（{@code tickWait}）**都只处理"返回原值、比一次"这一条规矩**；
	 * 相邻方块的普通红石电（`hasNeighborSignal()`）已经不参与任何等待操作了。
	 *
	 * <p>★ <b>为什么不再用 {@code hasAnyLoadedPower(frequency)}</b>（本值的新增部分）：那个方法
	 * **完全不看位置**（它把所有世界的同频率成员都算进来），于是"附近有没有人通电"变成了全局布尔 ——
	 * 用户要的「距离」表达不出来。现在走 {@code networksIn(level)} 取**同频率那一组**成员，
	 * 逐个判三条：{@code isAlive()}（区块卸载 / 方块被拆 / 位置失效的不算）、
	 * {@code getTransmittedStrength() > 0}（真的在通电）、以及
	 * <b>方块欧氏距离 {@code dx²+dy²+dz² <= N²}（含边界）</b>。
	 *
	 * <p>⚠️ {@code 0} 必须只有"同一格"成立（{@code distSqr == 0 <= 0}）：这就是用户说的
	 * "只能自己接收发送"（自己发、自己收的自环）。判据里的 {@code <=} <b>不能改成 {@code <}</b>
	 * —— 改了自检的边界那条（第二臂正好 N 格、range=N ⇒ 成功）立刻变红。
	 *
	 * <p>⚠️ <b>发送侧一个字都不读距离</b>（{@code emitSignal} / {@code heldSignals} /
	 * {@code getArmLinkLevel} 全都不动）：谁收到由**收方**自己的距离决定。把这段距离判定挪到
	 * 发送侧 ⇒ 自检"发不受距离影响"那条红。
	 *
	 * <p>⚠️ {@code networksIn} 在"世界没准备好"时会打一条 WARN 并返回空表，而且它返回的是
	 * <b>活的</b>表（每 tick 都在变）⇒ 本方法先自己挡掉 {@code level == null / 客户端}，
	 * 但**不缓存那张表**，只缓存"这一拍算出来的布尔"。
	 */
	private boolean isSignalPowered(SequencedArmBlockEntity arm, ArmProgramEntry entry) {
		if (entry.frequency == null)
			return false; // 没设频率 = 永远等不到，交给「失败后 + 时限」处理
		Level level = arm.getLevel();
		if (level == null || level.isClientSide)
			return false;
		BlockPos armPos = arm.getBlockPos();
		int version = Create.REDSTONE_LINK_NETWORK_HANDLER.globalPowerVersion.get();
		// 三元组全相同才复用（见 cachedPowerVersion 的说明：只按 version 会喂错缓存）。
		if (version != cachedPowerVersion || !armPos.equals(cachedSignalPos)
			|| entry.signalRange != cachedSignalRange) {
			cachedPowerVersion = version;
			cachedSignalPos = armPos;
			cachedSignalRange = entry.signalRange;
			cachedSignal = anyPowerWithin(level, armPos, entry);
		}
		return cachedSignal;
	}

	/**
	 * 同频率的成员里，有没有<b>一个以上</b>落在接收半径内并且真的在通电（{@link #isSignalPowered} 的本体）。
	 *
	 * <p>半径可以是 0（= 只有同一格），所以下面那个 {@code <=} 是<b>契约的一部分</b>，不是排版问题。
	 * 用 {@code long} 算平方和：{@code 256² = 65536}，而坐标差的分量理论上能到 ±3e7
	 * （{@code int} 平方会溢出成负数 ⇒ 一个"远得要命"的来源反而被判成"在半径内"）。
	 */
	private static boolean anyPowerWithin(Level level, BlockPos armPos, ArmProgramEntry entry) {
		Set<IRedstoneLinkable> network = Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(level)
			.get(entry.frequency);
		if (network == null || network.isEmpty())
			return false;
		long maxSqr = (long) entry.signalRange * entry.signalRange;
		for (IRedstoneLinkable link : network) {
			if (!link.isAlive() || link.getTransmittedStrength() <= 0)
				continue;
			BlockPos at = link.getLocation();
			long dx = (long) at.getX() - armPos.getX();
			long dy = (long) at.getY() - armPos.getY();
			long dz = (long) at.getZ() - armPos.getZ();
			if (dx * dx + dy * dy + dz * dz <= maxSqr) // ⚠️ 含边界：改成 < 会让"正好 N 格"收不到
				return true;
		}
		return false;
	}


	/** 目标解析 + 移动（或直接执行无坐标操作）。前摇已在外层处理。 */
	private void beginEntry(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		// ★ 判据是**操作类型**（`movesArm()` = `op.requiresTarget()`），**不是**条目级 `needsTarget()`：
		//   「过滤」是**控制类操作** —— 一瞬间的判断，臂**不移动、不做任何动作**（用户口径：
		//   "不需要动力臂伸过去判断"，跟「收发信号」一样）。容器模式的坐标只是
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
			// ★ 记录点①：一条"不移动的命令"开始执行（控制类 / 等待类）。
			//   为什么在这里、而不是 `tick()` 里每 tick 一行：tick() 每 tick 都跑，那样缓冲会在
			//   半秒内被同一件事刷爆，玩家反而看不出节奏。这里**一条条目只写一行**，
			//   tick 差（[+Nt]）负责把节奏显出来 —— 这正是用户要的"看清节奏"。
			if (arm.logEnabled())
				arm.logLine(logWhere(task, entry) + beginText(entry));
			return;
		}
		BlockPos offset = offsetOf(arm, entry);

		// 前置检查先跑：不通过就**不伸过去**（空载具时手臂原地待机，用户明确要求的行为）
		FailText blocked = preCheckFailure(arm, entry, offset);
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
			handleFailure(arm, task, entry, FailText.of("target_invalid"));
			return;
		}
		// ★ 记录点②：真的动身了（记录点①在前置检查通过后才轮到这一支，所以"目标为空"那种
		//   永远走不到这里 —— 日志里看到「移动」就说明前置检查是真的过了）。
		if (arm.logEnabled())
			arm.logLine(logWhere(task, entry) + " 移动 → " + offset.toShortString() + (pinsTarget ? "（按住目标）" : ""));
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
		return target.closerThan(armPos, armRange());
	}

	/**
	 * 条目偏移（<b>相对执行它的那条臂</b>）在不在臂的活动范围内 —— 与 {@link #withinArmRange}
	 * <b>同一个判据</b>，只是把臂的位置抽掉了。
	 *
	 * <p>为什么可以这么抽：条目坐标是**臂相对**的（世界目标 = {@code 臂位置 + 偏移}），而
	 * {@code closerThan} 对平移不变 ⇒ 「臂够不够得到」等价于「偏移本身在不在范围内」。
	 * 于是**客户端也能提前判**（编辑器红字、操作表的描框都问这一个方法），不需要知道那条臂在哪。
	 *
	 * <p>⚠️ 范围取 Create 自己的配置（{@code mechanicalArmRange}，默认 5 格）：专用服务器上客户端拿到的是
	 * 同步值，万一没同步到就是默认值 —— 这里的判定只用于**提前提示**，真跑起来服务器那边（{@code preCheckFailure}）
	 * 用同一个 {@code withinArmRange} 再判一次，所以提示与执行不会分叉到"提示说行、实际不行"以外的程度。
	 */
	public static boolean offsetInArmRange(BlockPos offset) {
		return withinArmRange(BlockPos.ZERO, offset);
	}

	/**
	 * 臂的活动范围（格）= Create 的 {@code mechanicalArmRange}（默认 5）。
	 *
	 * <p>⚠️ <b>必须兜一层 try/catch</b>：Create 的服务器配置在**还没加载**时取值会抛
	 * {@code IllegalStateException: Cannot get config value before config is loaded} ——
	 * 客户端在标题界面就跑编辑器自检、操作表描框也可能早于配置同步，直接调就是一次崩溃
	 * （第一版就是这么红的：{@code @withinArmRange} 抛 IllegalStateException）。
	 * 取不到时退回 Create 自己的默认值 {@link #DEFAULT_ARM_RANGE}，**不做缓存** ——
	 * 配置真加载好之后，下一次调用自然拿到真实值。
	 */
	private static final int DEFAULT_ARM_RANGE = 5;

	public static int armRange() {
		try {
			return ArmBlockEntity.getRange();
		} catch (RuntimeException configNotLoadedYet) {
			return DEFAULT_ARM_RANGE;
		}
	}

	/**
	 * 动身之前的前置检查（PLAN.md §7.2）。返回 {@code null} 表示可以出发，否则返回失败原因。
	 *
	 * <p>目的有二：① 目标明显做不成事时别空跑一趟（否则手臂会对着空置物台反复够）；
	 * ② 把"没有进展"提前暴露，让 {@code force} / {@code onFail} / {@code UNTIL_FAIL}
	 * 的语义在<b>手臂没动</b>的情况下就生效。
	 *
	 * <p><b>只有需要交换物品的四个操作</b>（{@link OperationType#needsInteractionPoint()}）才要求目标是
	 * Create 交互点；攻击 / 使用<b>对任意方块都能试</b>（用户第二轮反馈；潜行右键就是「使用」加一个开关），
	 * 这里只要求那个位置确实有方块 —— 成败交给 {@code RightClickBridge} 判（它本来就能对任意坐标干活）。
	 */
	@Nullable
	private FailText preCheckFailure(SequencedArmBlockEntity arm, ArmProgramEntry entry, BlockPos offset) {
		Level level = arm.getLevel();
		if (level == null)
			return FailText.of("world_unloaded");
		// ⚠️ `offset` 是**调用方（beginEntry）已经过 offsetOf 的那一份** —— 里面已经含了当前生效的
		//    调用偏移。这里再 offset(callShift) 一次就是叠两遍（这是"叠加只在 offsetOf 一处"的另一半）。
		BlockPos target = arm.getBlockPos()
			.offset(offset);

		// ★ 距离判定（用户第十九轮："动力臂对很远的无法触及的操作位置产生了动作反应，是不是有些操作没有写距离判定？"）
		// —— 确实一条都没有。原版是在**添加交互点时**就用范围卡住（`ArmInteractionPointHandler:99`
		// `point.getPos().closerThan(pos, ArmBlockEntity.getRange())`）：臂够不到的地方根本不让你选。
		// 我们的卡片是可携带的程序，录制时还不知道会插到哪条臂上，所以只能在这里按**这条臂的位置**判。
		// 范围直接取 Create 自己的配置（`mechanicalArmRange`，默认 5 格）—— 与"原版会接受多远的点"完全一致。
		// 注意它比 IK 的两节臂总长（29/16 ≈ 1.81 格）大：范围内的远处目标仍会让手臂拉直，
		// 那是固有限制、不在这里拦（见 PLAN §7.9）。
		int range = armRange();
		if (!withinArmRange(arm.getBlockPos(), target)) {
			// ★ 这段每 tick 都会走一遍（前置检查失败是**每 tick 重试**的）⇒ 不为此付 `String.format` +
			//   `Math.sqrt` 的钱：纯字符串拼接就够了，而"差多远"用**平方**距离同样回答得了
			//   （26 > 25 就是"差一点点"），还省掉一次开方。
			long distSqr = (long) target.distSqr(arm.getBlockPos());
			return FailText.of("target_too_far", distSqr, (long) range * range, range);
		}

		BlockState state = level.getBlockState(target);
		if (entry.op.needsInteractionPoint()) {
			if (!ArmInteractionPoint.isInteractable(level, target, state))
				return FailText.of("target_invalid");
		}
		// ★ 这里**不再**对"攻击 / 挖掘"判断"那儿有没有方块"（v27 起）：用户第二十七轮明确要求
		// "和原本的机械手一模一样 —— 不检测能不能挖动，不检测有没有实体或者方块"。
		// 于是这两条的"有没有东西"完全交给动作本身：挖到空气 = 什么都不发生，格里没实体 = 挥空，都算完成；
		// 挖不动（进度恒 0）才由动作返回硬失败并带上原因（那不是动身前的检测，是挥完之后的事实）。
		// ★ 目标那一格的**物品能力句柄按需取**（只有「取出」与「组装」真的用它）：`peekHandler` 是纯只读解析
		//   （不改变任何状态），所以挪进这两支是安全的；好处是「放入 / 卸货 / 攻击 / 挖掘 / 使用」不再为
		//   一次 `getCapability` 付钱 —— 而这段判定是**每 tick** 重跑的。
		switch (entry.op) {
			case TAKE -> {
				if (TargetInteraction.isDepositOnly(level, target))
					// 判定方式与原版一致（`AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint`：
					// 漏斗 / 烈焰人 / 营火 / 重生锚 / 粉碎轮），所以这里**不能**再写"（漏斗类）"
					// —— 打包机曾经被误判成只进不出，就是"有 InvManipulationBehaviour 就算漏斗"的旧实现造成的。
					return FailText.of("deposit_only");
				// ★ 用户 2026-10-21：「取出 / 放入按本体的判断标准」⇒ 槽位与能否取出都由
				//   {@link TargetInteraction} 按 Create 交互点算（`getSlotCount` / `extract`）。
				//   ⚠️ 因此这里**不再**要求"通用物品能力"：唱片机 / 营火 / 重生锚这类方块根本没有
				//   ItemHandler，本体是靠交互点取东西的。
				IItemHandler handler = arm.peekHandler(offset);
				if (TargetInteraction.takeSlots(level, target, handler) <= 0)
					return FailText.of("no_item_handler");
				// ★ 个数模式 = 等于（判据只有 countIsExact() 一处）：**动身之前**就得能凑出恰好 N 个。
				//   凑不出就**别伸过去** —— 与"臂内已满"同一个道理：不要为一个必然失败的动作空跑一趟。
				//   而前置检查是**每 tick** 重跑的（PRECHECK_RETRY_TICKS = 0）⇒"等补货"发生在**原位重试**里
				//   （货一到位下一 tick 就放行），与"失败后默认重试"的语义正好对齐。
				if (entry.countIsExact()) {
					int exactSlot = TargetInteraction.findSlot(level, target, handler, entry.count, true);
					if (exactSlot < 0)
						return FailText.of("count_short", entry.count);
					if (!arm.canAccept(TargetInteraction.extract(level, target, exactSlot, entry.count, true)))
						return FailText.of("arm_full");
				} else {
					int slot = TargetInteraction.findSlot(level, target, handler, 1, false);
					if (slot < 0)
						return FailText.of("target_empty");
					if (!arm.canAccept(TargetInteraction.extract(level, target, slot, 1, true)))
						return FailText.of("arm_full");
				}
			}
			case DEPOSIT -> {
				ItemStack held = arm.getHeldItem();
				if (held.isEmpty())
					return FailText.of("hand_empty");
				// ★ 个数模式 = 等于：动身之前"手上够 N 个"与"目标吃得下恰好 N 个"**两条**都要成立。
				//   报因必须能分辨是哪一条（"手上不足"与"目标放不下"是两种完全不同的补救办法）。
				//   ATMOST 照旧只看"整摞能不能进去一点"（有多少放多少，不足也不失败）。
				// ⚠️ 判据都走 TargetInteraction.Insert 的那两个方法：**不能按数量判**，
				//   本体对岩浆桶这类"换物"搬运返回的是容器（空桶），数量不变也是成功
				//   （用户 2026-10-21 实测："动力臂可以用熔岩桶为烈焰人添加燃料，留下一个空桶在手上"）。
				int amount = entry.countIsExact() ? entry.count : Math.min(held.getCount(), entry.count);
				if (entry.countIsExact() && held.getCount() < entry.count)
					return FailText.of("hand_short", entry.count, held.getCount());
				ItemStack offered = held.copyWithCount(amount);
				TargetInteraction.Insert probe = TargetInteraction.insert(level, target, offered.copy(), true);
				if (probe.blockedReason() != null)
					return FailText.of("target_refused", probe.blockedReason());
				if (entry.countIsExact() ? !probe.tookAll(offered) : !probe.madeProgress(offered))
					return FailText.of("target_full", amount);
			}
			case UNLOAD -> {
				if (arm.getOverflow()
					.isEmpty())
					return FailText.of("nothing_to_unload");
				TargetInteraction.Insert probe = TargetInteraction.insert(level, target,
					arm.getOverflow()
						.get(0)
						.copyWithCount(1),
					true);
				if (probe.blockedReason() != null)
					return FailText.of("target_refused", probe.blockedReason());
			}
			case DEPLOY -> {
				IItemHandler handler = arm.peekHandler(offset);
				if (handler == null)
					return FailText.of("no_item_handler");
				ItemStack applicator = arm.getHeldItem();
				if (applicator.isEmpty())
					return FailText.of("no_applicator");
				// ★ **逐件找**，不是"只看第一件"（用户第二十七轮续三十六实测："组装的时候臂会空一轮"）：
				//   目标格里最靠前的那件往往就是**我们刚加工出来的产物** —— 它当然不匹配配方，
				//   旧写法于是报"没有匹配的配方"、手臂干脆不伸出去，而它后面那件**能加工的原料**
				//   就永远等不到机会（带子一直被产物占着判定窗口）。
				//   这与真正加工时的判据必须**同源**：`deployInPlace` 是逐件 it 的，
				//   上游机械手（Deployer）也是**逐件回调**、不匹配的直接 `PASS`
				//   （`BeltDeployerCallbacks:38-63`，`:58-59` 就是这一条的出处）——
				//   所以上游根本不存在"产物挡住原料"这个问题。
				if (!anyProcessable(level, arm, entry, handler, applicator))
					// 一句话就够（用户第八十九轮："非必要不解释"——原来那串
					// "（没有匹配 deploying / item_application 配方的物品）"在护目镜上占了半行）
					return FailText.of("no_recipe");
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
	 * {@link #deployInPlace} 同一个调用、同一个范围：格子中心 ±.51），回调返回 {@code doNothing()}
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
	private boolean anyProcessable(Level level, SequencedArmBlockEntity arm, ArmProgramEntry entry,
		IItemHandler handler, ItemStack applicator) {
		TransportTarget transport = transportTargetAt(level, arm, entry);
		if (transport != null) {
			boolean belt = transport.belt();
			boolean[] found = { false };
			transport.behaviour()
				.handleProcessingOnAllItems(t -> {
					// 与 {@link #deployInPlace} 同一条门：作业位只对传送带生效
					// （置物台的物品天生在作业位上，`isAtWorkPosition` 对它恒为 false）。
					// 反证：把 `(!belt || …)` 的 belt 判断去掉 ⇒ 置物台上的组装永远不会开始
					// （前置检查永远说"没有可加工的东西"）。
					if (!found[0] && (!belt || arm.isAtWorkPosition(t)) && !t.stack.isEmpty()
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

	/**
	 * <b>条目 → 世界目标</b>的**唯一咽喉**：返回"相对本臂"的最终偏移
	 * （世界目标 = {@code arm.getBlockPos().offset(offsetOf(...))}）。
	 *
	 * <p>★ 条目 off**永远相对执行它的那条臂**（PLAN.md §19.7 的用户口径）：这里直接返回
	 * {@code entry.offset} **加上当前生效的调用偏移**（{@link #effectiveCallShift()}）。
	 * 卡上的**录制原点**（用户第四十轮起它住在卡上，不在程序里）不参与运行时 ——
	 * "插卡平移"那个函数（ProgramCoordinates.rebaseTo）已删。
	 *
	 * <p>★ <b>调用偏移只在这里叠加一处，别在别处再叠一遍</b>（决策 68/85："判据/换算写两份必然分叉"）。
	 * 运行时所有要世界目标的地方都从这里取：{@code beginEntry}（→ {@code preCheckFailure} 与
	 * {@code arm.beginMove}）、{@code DEPOSIT}/{@code UNLOAD}/{@code ATTACK}/{@code MINE}/
	 * {@code USE} 的坐标、{@code transportTargetAt}、{@code restartMove}、{@code peekHandler}
	 * （认领/前置检查读的那一格）、「过滤」容器模式的 {@code peekContainerHandler}（读哪个方块）、
	 * 以及对外暴露的 {@code pinOffset} / {@code entryOffset}。唯一另一处涉及
	 * {@code 臂坐标 + 偏移} 的算式在 {@code preCheckFailure} 里，它吃的是**本方法已经算好的结果**
	 * （由 {@code beginEntry} 传下去），所以那里<b>不得</b>再加一次。
	 *
	 * <p>⚠️ 谁要是想在这里加上卡上的原点 / 臂位置之类的平移，请先看自检里那条
	 * "同一张卡插进两条位置不同的臂，两次 offset 必须完全相同" —— 那条断言就是拦这个的。
	 * （调用偏移不违反它：它是**程序里写着的**相对量，与臂在哪无关。）
	 */
	private BlockPos offsetOf(SequencedArmBlockEntity arm, ArmProgramEntry entry) {
		return entry.offset.offset(effectiveCallShift());
	}

	private void execute(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		switch (entry.op) {
			case TAKE -> take(arm, task, entry);
			case DEPOSIT -> deposit(arm, task, entry);
			// 收存：把手持物品挪进溢出库存（无动作、无需坐标，见 stash 的判定链）
			case STASH -> stash(arm, task, entry);
			case UNLOAD -> unload(arm, task, entry);
			case DEPLOY -> deploy(arm, task, entry);
			case ATTACK -> attack(arm, task, entry);
			case MINE -> mine(arm, task, entry);
			case USE -> use(arm, task, entry);
			case FILTER -> filter(arm, task, entry);
			case WAIT -> {
				timer = entry.waitTicks();
				state = EntryState.WAITING;
			}
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
				// 玩家自己写的那句是**玩家数据**（不可翻译）；没写就用统一的那一条
				boolean blank = entry.errorMessage == null || entry.errorMessage.isBlank();
				lastFailure = blank ? FailText.of("thrown") : FailText.literal(entry.errorMessage);
				endTask(arm, true, blank ? null : entry.errorMessage);
			}
			default -> handleFailure(arm, task, entry, FailText.of("not_implemented", entry.op.name()));
		}
	}

	/**
	 * 等待类的**极性判定**：当前通电状态与"等通电 / 等断电"是否吻合。
	 *
	 * <p>抽成一个方法是为了让自检能直接验这张真值表 —— 这个位置出过一次"取反两次"的 bug：
	 * 电源查询那边自己异或了极性、这里又比一次，结果**收到信号反而停下**（用户实测）。
	 * 规则只有一句：**查询侧返回原始通电状态，极性只在这里比一次**。
	 *
	 * <p>★ 现在它服务的"等待类"只剩两种：{@code WAIT}（只看时长，不走极性）与
	 * {@code SIGNAL} 的**接收**模式（红石链接频率的通电状态）。
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
		// 走到这里只剩「收发信号」的**接收**模式：等的是红石链接频率（§7.6 v4：频率从任务搬到了这里）。
		// ⚠️ 必须走 `usesSignalPair()` 那一支（= isSignalPowered）：它返回**原始通电状态**，
		//   极性只在这下面比一次（曾经这条自己被取反过，见 isSignalPowered）；
		//   ⚠️ 相邻方块的普通红石电（arm.hasNeighborSignal()）**不再有任何等待操作在读它** ——
		//   那条"按相邻红石等待"的操作已按用户口径删除（「我们有更好的无线信号系统可以使用」）。
		//   左边那个 `usesSignalPair()` 是**防御性**的：这里现在只有 SIGNAL 的接收模式会进来，
		//   但写成"先问这一类操作用不用频率、再去读频率"就不会在将来多一种等待类时读错电源。
		boolean powered = entry.op.usesSignalPair() && isSignalPowered(arm, entry);
		if (waitSatisfied(powered, entry.waitForPowered)) {
			// ★ 用户在本轮把「收信号发光」的判据收紧了：**必须成功接收**才点亮底座，
			//   "尝试接收但没有"不算（原话：「base_powered 的模型接收信号发光的时候必须成功接收信号才发光，
			//   尝试接收但是没有就不会发光」）。
			//   而"收到"这件事在状态机里是**零时长**的 —— 这里一旦极性吻合就同 tick `succeed` 走掉，
			//   臂身上没有"我正在收"的持续状态可当判据 ⇒ 用臂上那个短脉冲把它变成看得见的东西
			//   （10 tick = 0.5 秒，常量在 SequencedArmBlockEntity.SIGNAL_FLASH_TICKS）。
			//
			// ★★ 用户口径原话：「这个只与无线信号有关，与接收红石信号无关」。
			//   闪光只给"经红石链接频率收到的信号"这一种情况，判据是**操作类型 + 模式**
			//   （`op == SIGNAL` 且 `isSignalReceive()`），不靠 `usesSignalPair()` 之类"似乎等价"的
			//   间接量 —— 以后若再多一种用频率的操作，这里必须显式再判断一次，而不是被顺手放进来。
			//   （那条"按相邻红石等待"的操作已经不存在了，但这条判据**不能因此简化成无条件调用**：
			//    它守的是同一件事 —— 只有"真的收到无线信号"才点亮底座。）
			//   发送侧不在这里 —— 它靠 `getArmLinkLevel() > 0`（见 SequencedArmBlockEntity.computeSignalActive），
			//   只统计 heldSignals（红石链接频率）的电平，`redstoneLocked` 不参与。
			if (entry.op == OperationType.SIGNAL && entry.isSignalReceive())
				arm.flashSignalReceived();
			succeed(arm, task, entry);
			return;
		}
		waitElapsed++;
		// 等待类操作把「时限」当作"最多等多久"：到点走「失败后」（§7.7 与其它操作一致）
		if (entry.timeoutTicks() >= 0 && waitElapsed > entry.timeoutTicks())
			applySkip(arm, task, entry, entry.onTimeout, FailText.of("signal_timeout"));
	}

	// ————————————————————————— 各操作 —————————————————————————

	private void take(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		Level level = arm.getLevel();
		// 现在伸向的那一格（世界坐标）。取物**按这一格的本体交互点语义**来，见 TargetInteraction.extract
		BlockPos target = arm.getBlockPos()
			.offset(arm.movingOffset);
		// handler 只当"退回裸能力"的兜底与失败回滚用；能取几格、能不能取由 TargetInteraction 判
		IItemHandler handler = arm.getMovingHandler();
		int slots = TargetInteraction.takeSlots(level, target, handler);
		if (slots <= 0) {
			handleFailure(arm, task, entry, FailText.of("no_item_handler"));
			return;
		}
		// ★ 个数模式只有一处判据（ArmProgramEntry.countIsExact()）—— 这里不重新解释 countMode。
		if (entry.countIsExact()) {
			// 「等于」：**必须能拿到恰好 N 个**才动手。先逐个槽模拟（真取用 false），
			// 找到第一个够数的槽才真取。
			// ⚠️ 顺序绝不能反：模拟用 true、真取用 false。
			// ⚠️ 也绝不能"先真取一部分再判失败"—— 那会把半摞物品丢进臂里，
			//    玩家看到的就是"报了失败、东西却少了"。
			int slot = TargetInteraction.findSlot(level, target, handler, entry.count, true);
			if (slot < 0) {
				handleFailure(arm, task, entry, FailText.of("count_short", entry.count));
				return;
			}
			if (!arm.canAccept(TargetInteraction.extract(level, target, slot, entry.count, true))) {
				handleFailure(arm, task, entry, FailText.of("arm_full"));
				return;
			}
			ItemStack taken = TargetInteraction.extract(level, target, slot, entry.count, false);
			if (taken.getCount() != entry.count) {
				// 兜底：同一个 tick 里"模拟够数、真取变少"不该发生（服务端单线程）。
				// 真发生了也**绝不吞物品** —— 放回原槽再走失败重试（塞进臂里再报失败是另一种怪状态）。
				if (!taken.isEmpty() && handler != null)
					handler.insertItem(slot, taken, false);
				handleFailure(arm, task, entry, FailText.of("count_short", entry.count));
				return;
			}
			ItemStack beforeHeld = arm.getHeldItem();
			arm.insertIntoInventory(taken);
			ArmEffects.pickup(arm.getLevel(), arm.getBlockPos(), beforeHeld, arm.getHeldItem());
			succeed(arm, task, entry);
			return;
		}
		// 「小于等于」（默认 = 取出改造前的行为）：有多少拿多少，上限 count；**不足 N 不算失败**。
		for (int slot = 0; slot < slots; slot++) {
			ItemStack available = TargetInteraction.extract(level, target, slot, entry.count, true);
			if (available.isEmpty())
				continue;
			// 取前模拟：臂内放得下才动手（放不下就按"还没成功"重试，等臂内腾出位置）
			if (!arm.canAccept(available)) {
				handleFailure(arm, task, entry, FailText.of("arm_full"));
				return;
			}
			int amount = Math.min(available.getCount(), entry.count);
			ItemStack taken = TargetInteraction.extract(level, target, slot, amount, false);
			if (taken.isEmpty()) {
				handleFailure(arm, task, entry, FailText.of("take_failed"));
				return;
			}
			ItemStack beforeHeld = arm.getHeldItem();
			arm.insertIntoInventory(taken);
			// 原版动力臂的取物声（种类变化时才响，守卫与 ArmBlockEntity.collectItem 一致）
			ArmEffects.pickup(arm.getLevel(), arm.getBlockPos(), beforeHeld, arm.getHeldItem());
			succeed(arm, task, entry);
			return;
		}
		handleFailure(arm, task, entry, FailText.of("target_empty"));
	}

	/**
	 * 放入（用户口径：这条现在**也读个数**）。
	 *
	 * <p>两种模式的判定链（判据都只有 {@link ArmProgramEntry#countIsExact()} 一处）：
	 * <ul>
	 *   <li><b>小于等于</b>：搬 {@code min(手上, count)} 个过去；进去了至少 1 个就算成功。
	 *       <b>没参与这 N 个的那一截留在臂里</b>（旧代码把整摞递出去再拿 remainder 当手，
	 *       现在只递 amount 个 ⇒ 回收时必须把"没参与的那一截"加回去，否则一放就丢货）。</li>
	 *   <li><b>等于</b>：① 手上不足 N 个 ⇒ 失败（一个都不真塞）；
	 *       ② 先 simulate 判"目标能不能吃下恰好 N 个"，模拟后还有剩 ⇒ 失败（**不真塞一半**）；
	 *       ③ 两条都过才真塞 N 个。</li>
	 * </ul>
	 * 失败一律走 {@link #handleFailure}（默认重试，时限 / 去向照旧 §7.7）。
	 */
	private void deposit(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		ItemStack held = arm.getHeldItem();
		if (held.isEmpty()) {
			handleFailure(arm, task, entry, FailText.of("hand_empty"));
			return;
		}
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		boolean exact = entry.countIsExact();
		// 这次要搬几个：两种模式都受 count 约束（改造前是"整摞都塞"，现在上限就是 count）
		int amount;
		if (exact) {
			if (held.getCount() < entry.count) {
				handleFailure(arm, task, entry, FailText.of("hand_short", entry.count, held.getCount()));
				return;
			}
			amount = entry.count;
		} else {
			amount = Math.min(held.getCount(), entry.count);
		}
		ItemStack offered = held.copyWithCount(amount);
		// ★ 先模拟一次，拿三件事：被挡原因 / 是否全进去 / **换物后的容器**。
		//   换物是这里的关键（用户 2026-10-21）：本体对岩浆桶返回的是空桶（crafting remainder），
		//   数量没变但确实搬成功了 —— 只能按"是不是同种物品"判，不能按数量判。
		TargetInteraction.Insert probe = TargetInteraction.insert(arm.getLevel(), target, offered.copy(), true);
		if (probe.blockedReason() != null) {
			handleFailure(arm, task, entry, FailText.of("target_refused", probe.blockedReason()));
			return;
		}
		if (exact ? !probe.tookAll(offered) : !probe.madeProgress(offered)) {
			handleFailure(arm, task, entry,
				exact ? FailText.of("target_full", amount) : FailText.of("target_full", offered.getCount()));
			return;
		}
		// ★ 回收预判：容器（空桶）与"没参与的那一截"都要有地方放，否则**真搬之前**就失败（别先搬了再犯难）
		ItemStack plannedHand = TargetInteraction.handAfterDeposit(held, amount, probe.remainder());
		if (plannedHand == null && !arm.canAccept(probe.remainder())) {
			handleFailure(arm, task, entry, FailText.of("arm_full"));
			return;
		}
		TargetInteraction.Insert result = TargetInteraction.insert(arm.getLevel(), target, offered.copy(), false);
		ItemStack remainder = result.remainder();
		if (result.blockedReason() != null) {
			handleFailure(arm, task, entry, FailText.of("target_refused", result.blockedReason()));
			return;
		}
		ItemStack back = TargetInteraction.handAfterDeposit(held, amount, remainder);
		if (back == null) {
			// 换物 + 手上还剩燃料：容器（空桶）塞进臂内库存，手上留燃料
			arm.insertIntoInventory(remainder);
			back = held.copyWithCount(held.getCount() - amount);
		}
		arm.setHeldItem(back);
		if (exact ? !result.tookAll(offered) : !result.madeProgress(offered)) {
			// 「等于」要求 N 个**全部**进去；「小于等于」要求至少进去一点。
			// ⚠️ 判据不是"数量变没变"（见 TargetInteraction.Insert.madeProgress 里那条用户实测）
			int taken = result.tookAll(offered) ? amount : amount - remainder.getCount();
			handleFailure(arm, task, entry,
				taken <= 0 ? FailText.of("target_full", amount) : FailText.of("target_partial", amount, taken));
			return;
		}
		succeed(arm, task, entry);
	}

	/**
	 * 收存（用户口径）：把**手上那一摞**挪进臂自己的溢出库存 —— <b>无世界动作、不移动手臂、不读坐标</b>。
	 *
	 * <p>判定链（「等于 / 小于等于」的判据都只有 {@link ArmProgramEntry#countIsExact()} 一处）：
	 * <ol>
	 *   <li>手上是空的 ⇒ 失败（与「放入」同一句「手上没有物品」）；</li>
	 *   <li>这次搬几个：小于等于 = {@code min(手上, count)}；等于 = 必须手上够 {@code count} 个，
	 *       不够就失败（**一个都不搬**）；</li>
	 *   <li><b>等于模式先模拟</b>：{@code insertIntoOverflow(moving, true)} 还有剩 ⇒ 失败
	 *       （**不许先塞一部分** —— 那会让「等于 N 个」退化成「塞了多少算多少」）；</li>
	 *   <li>真塞：{@code insertIntoOverflow(moving, false)}，{@code moved = amount − 剩余}；</li>
	 *   <li>小于等于：{@code moved == 0} ⇒ 失败「溢出库存已满」；{@code moved > 0} ⇒ 成功
	 *       （搬几个算几个，与取出 / 放入的 ATMOST 同一条语义）；</li>
	 *   <li>手上扣掉 {@code moved}，扣空了就 {@code EMPTY}（与「放入」同一写法：别丢物品）。</li>
	 * </ol>
	 *
	 * <p>⚠️ 必须走 {@code insertIntoOverflow}，**不能**用 {@code SequencedArmItemHandler.insert(...)}：
	 * 后者会**先补手持格**，而这次要搬的正是手上那一摞（同种）⇒ 又并回手上，等于什么都没搬。
	 * <p>⚠️ handler 一律从 {@link SequencedArmBlockEntity#itemHandler()} 取（懒建单例）：
	 * 自己 {@code new} 一个也能用，但那份对象不会替臂做别的事，日后加状态就会分叉。
	 *
	 * <p>失败一律走 {@link #handleFailure}（「收存」有重试窗口：溢出满了 = 「还没到时候」，
	 * 等卸货 / 等手上那一摞被别的条目换掉，与取出 / 放入同一条失败语义）。
	 */
	private void stash(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		ItemStack held = arm.getHeldItem();
		if (held.isEmpty()) {
			handleFailure(arm, task, entry, FailText.of("hand_empty"));
			return;
		}
		int amount;
		if (entry.countIsExact()) {
			if (held.getCount() < entry.count) {
				handleFailure(arm, task, entry, FailText.of("count_short", entry.count));
				return;
			}
			amount = entry.count;
			// ★ 等于模式先模拟：放不下就整批不搬（否则就是把一摞拆成两处的一半）
			ItemStack probeLeft = arm.itemHandler()
				.insertIntoOverflow(held.copyWithCount(amount), true);
			if (probeLeft.getCount() > 0) {
				handleFailure(arm, task, entry, FailText.of("overflow_short", amount));
				return;
			}
		} else {
			amount = Math.min(held.getCount(), entry.count);
		}
		ItemStack remainder = arm.itemHandler()
			.insertIntoOverflow(held.copyWithCount(amount), false);
		int moved = amount - remainder.getCount();
		if (moved <= 0) {
			handleFailure(arm, task, entry, FailText.of("overflow_full"));
			return;
		}
		int left = held.getCount() - moved;
		arm.setHeldItem(left <= 0 ? ItemStack.EMPTY : held.copyWithCount(left));
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
				handleFailure(arm, task, entry, FailText.of("nothing_to_unload"));
			return;
		}

		int moved = unloadRound(arm, target, entry);
		unloadMoved += moved;
		if (entry.unloadRepeat == UnloadRepeat.ONCE) {
			if (moved > 0)
				succeed(arm, task, entry);
			else
				handleFailure(arm, task, entry, FailText.of("unload_refused"));
			return;
		}
		// UNTIL_EMPTY：本轮搬完不结束，下一 tick 继续；目标拒绝即失败
		if (moved == 0)
			handleFailure(arm, task, entry, FailText.of("unload_partial"));
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
			handleFailure(arm, task, entry, FailText.of("no_item_handler"));
			return;
		}
		ItemStack applicator = arm.getHeldItem();
		if (applicator.isEmpty()) {
			handleFailure(arm, task, entry, FailText.of("no_applicator"));
			return;
		}
		Level level = arm.getLevel();
		if (level == null) {
			handleFailure(arm, task, entry, FailText.of("world_unloaded"));
			return;
		}

		// ★ **传送带和置物台都走"原地加工"**（照 Create 的 `BeltDeployerCallbacks.activate:118-132`，
		//   两者都注册了 `TransportedItemStackHandlerBehaviour`，也都被 Create 自己的部署器这么加工）；
		//   只有箱子这类**普通容器**才走"取出 1 个 → 加工 → 结果塞回去"。
		//
		// ⚠️ 为什么带子不能"取出放回"（用户第二十七轮续二十八实测：第一次成功、之后产物全被"原料"堵住、
		//    最后全掉进臂的溢出库存）：带子的 `extractItem` 只是把栈清零、**真正移除要等下一次**
		//    `BeltInventory.tick/write` 处理 `toRemove`（`BeltInventory:67-73`、`:408-423`），
		//    而我们在**同一 tick** 里紧接着 `insertItem` ⇒ `canInsertAt` 用 `isBlocking`
		//    （`:368-374`：`insertedAt == 这一格 && insertedFrom == UP && 位置在后`）判定"那格还占着"。
		//    更糟的是：**我们自己放回去的那个产物恰好满足** `insertedAt == 这一格 && insertedFrom == UP`
		//    ⇒ 从那以后这一格的插入永远被拒。原地加工由带子自己记账（产物进 `toInsert`、原件进 `toRemove`），
		//    位置也不动 ⇒ 顺带解决"物品从上半格跳到格子中间"的观感问题。
		//
		// ⚠️⚠️ 置物台**更不能**"取出放回"（用户 2026-10-19 实测：置物台上组装出来的产物全跑进臂的溢出库存，
		//    置物台上什么都没留下）：`DepotItemHandler.insertItem(0, …)` 只在
		//    「手上那格空 **且** 输出缓冲也空」时收（`DepotItemHandler:28-41`）——
		//    ① 置物台上还剩 N−1 个原料时，那一格**非空** ⇒ 产物根本塞不回去；
		//    ② 就算那一格空了，置物台的"输出位置"其实是它自己的 `processingOutputBuffer`
		//       （`DepotBehaviour:406-414` 由 `applyToAllItems` 写入，客户端的产物就画在那里），
		//       走 `insertItem(0)` 一辈子写不到它。
		//    而 `DepotBehaviour` 也注册了 `TransportedItemStackHandlerBehaviour`（`DepotBehaviour:250`），
		//    所以正确做法与带子**完全一样**：把加工结果交给那个 behaviour，让它自己决定
		//    "产物上手 / 进输出缓冲 / 多余的落地"。
		TransportTarget transport = transportTargetAt(level, arm, entry);
		if (transport != null) {
			deployInPlace(arm, task, entry, level, transport, applicator);
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
			handleFailure(arm, task, entry, FailText.of("carrier_empty"));
			return;
		}

		// 2) 解析配方（顺序装配 → deploying → item_application，砂纸有特例）
		RecipeHolder<? extends Recipe<?>> recipe = DeployRecipes.find(level, processed, applicator);
		if (recipe == null) {
			returnToCarrier(arm, handler, processed); // 没配方就把物品放回去，别吞掉
			handleFailure(arm, task, entry, FailText.of("no_recipe"));
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
	 * 传送带 / 置物台上的组装：**原地加工**（照 Create 的 `BeltDeployerCallbacks.activate:118-132`）。
	 *
	 * <p>做法：在目标方块中心半格内找第一件"能匹配配方"的物品，对它调
	 * {@code handleProcessingOnAllItems}，返回
	 * {@code convertTo(left)}（没有产物，只剩原件被消耗后的余量）或
	 * {@code convertToAndLeaveHeld(produced, left)}（有产物 ⇒ 产物交回载体、原件余量留在原地）。
	 * **不 extract、不 insert** —— 记账全部交给载体的 behaviour：
	 * <ul>
	 *   <li>传送带：产物进 `toInsert`、原件进 `toRemove`，于是不会撞上 `canInsertAt` /
	 *       "那格被自己占住"的坑，物品位置也不会跳；</li>
	 *   <li>置物台：`DepotBehaviour.applyToAllItems:387-418` 把"手上那格 + 输出缓冲 …"算清 ——
	 *       产物落**置物台**（手上那格空了就直接摆上去），多余的进它自己的 `processingOutputBuffer`
	 *       （那就是置物台的"输出位置"），实在放不下才落地。⚠️ 绝不能改回"取出→塞回 slot 0"：
	 *       置物台上还剩 N−1 个原料时那一格非空、`DepotItemHandler.insertItem` 直接拒收
	 *       ⇒ 产物全被塞进臂的库存/溢出（用户 2026-10-19 实测的 bug）。</li>
	 * </ul>
	 *
	 * <p>★ **传送带上只加工"已经在作业位上"的那件**（与前置检查同一条判据，见 {@code anyProcessable}）：
	 * 本体在 HOLD 时会把物品**居中**（`BeltInventory:292`），我们也照做（认领时 `snapToWorkPosition`），
	 * 所以产物一定在格子中心出生、不会提前半格挡在原料前面（用户第二十七轮续四十一实测）。
	 * 置物台没有"带速 / 作业位"这回事（`isAtWorkPosition` 在没有带子时恒为 false）⇒ 对它**不加**这道门。
	 */
	private void deployInPlace(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, Level level,
		TransportTarget transport, ItemStack applicator) {
		boolean belt = transport.belt();
		boolean[] handled = { false };
		RecipeHolder<? extends Recipe<?>>[] recipeRef = new RecipeHolder<?>[1];
		ItemStack[] processedRef = new ItemStack[1];
		transport.behaviour()
			.handleProcessingOnAllItems(t -> {
				// ⚠️ 作业位这道门**只对传送带**：置物台的物品天生就在作业位上（也根本没有带子）。
				if (handled[0] || (belt && !arm.isAtWorkPosition(t)))
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
				left.stack.shrink(1); // 消耗掉被加工的那 1 个；多出来的余量留在原地
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
			handleFailure(arm, task, entry, FailText.of("no_recipe"));
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

	/**
	 * 目标方块是不是**能原地加工**的载体（传送带 / 置物台）：两者都注册了
	 * {@code TransportedItemStackHandlerBehaviour}，也只有它们才有"原地加工"这回事
	 * （`BeltBlockEntity:105`、`DepotBehaviour:250`）。返回 null ⇒ 走普通容器那条路。
	 *
	 * <p>{@code belt} 那一项只用来决定"要不要过作业位这道门"（见 {@link #deployInPlace}）：
	 * 置物台没有带速/作业位的概念，{@code isAtWorkPosition} 在没有带子时恒为 false。
	 */
	@Nullable
	private TransportTarget transportTargetAt(Level level, SequencedArmBlockEntity arm, ArmProgramEntry entry) {
		BlockEntity be = level.getBlockEntity(arm.getBlockPos()
			.offset(offsetOf(arm, entry)));
		TransportedItemStackHandlerBehaviour behaviour =
			BlockEntityBehaviour.get(be, TransportedItemStackHandlerBehaviour.TYPE);
		if (behaviour == null)
			return null;
		return new TransportTarget(behaviour, be instanceof BeltBlockEntity);
	}

	/** {@link #transportTargetAt} 的结果：原地加工的 behaviour + "它是不是传送带"。 */
	private record TransportTarget(TransportedItemStackHandlerBehaviour behaviour, boolean belt) {
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
			case RETRY -> handleFailure(arm, task, entry, FailText.of("attack_unavailable", describeFailure(arm)));
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
			case RETRY -> handleFailure(arm, task, entry, FailText.of("mine_unavailable", describeFailure(arm)));
			// **硬失败**（挖不动 / 无权 / 被保护）：立刻走失败出口，绝不原地重试 ——
			// 否则就是"臂对着基岩永远空挥"，而且阻塞等待时还占着臂。
			case HARD_FAIL ->
				applySkip(arm, task, entry, entry.onTimeout, FailText.of("mine_impossible", describeFailure(arm)));
		}
	}

	/**
	 * 右键：一次调用 = 一次右键。<b>潜行不潜行只差一个布尔</b>（用户本轮口径：一个操作 + 一个开关）。
	 *
	 * <p>决策上右键无法客观判定成败，因此只要没被取消就算完成。
	 *
	 * @see ArmProgramEntry#isSneakUse() 潜行开关的唯一判据（卡片文案 / 脚本 / 这里共用）
	 */
	private void use(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		BlockPos target = arm.getBlockPos()
			.offset(offsetOf(arm, entry));
		// ★ 潜行与否问**条目**（isSneakUse = USE 且 sneaking），不是问操作类型：
		//   「潜行使用」这个操作已经删掉了，运行时这里只负责把这个布尔传下去。
		//   ⚠️ 反证放在**模型层**（这一行本身没有可观测的副作用，自检只能证明那个布尔是对的）：
		//   checkSneakUseMerge 的「老写法潜行 / 两种写法同一模型 / 普通与潜行只差这一个布尔」三条
		//   钉住 isSneakUse() 的值，ArmEntryText.selfCheck 钉住卡片上那句话 —— 两者合起来才是
		//   "卡片说潜行、跑起来也潜行"。把这里改成恒 false 只有实机才能看出来（待游戏内验证）。
		boolean sneak = entry.isSneakUse();
		RightClickBridge.Result result = arm.rightClick.use(target, entry.face, sneak);
		switch (result) {
			// 右键**不幂等**（拉杆会被反复拨、按钮会被反复按），所以硬失败也只能走出口，不能重试
			case HARD_FAIL ->
				applySkip(arm, task, entry, entry.onTimeout, FailText.of("use_impossible", describeFailure(arm)));
			case RETRY -> handleFailure(arm, task, entry, FailText.of("use_unavailable", describeFailure(arm)));
			default -> succeed(arm, task, entry); // SUCCESS（含"交互已尝试"）与 WAITING 都算完成
		}
	}

	/**
	 * 过滤（{@link OperationType#FILTER}）：把候选那一份物品交给 Create 自己的过滤规则判一次。
	 *
	 * <p><b>用户口径（照做，不要"优化"）</b>：
	 * <ol>
	 *   <li><b>控制类操作、臂不移动</b>：判断是一瞬间的事 —— 臂<b>不伸过去、不做任何动作</b>
	 *       （与「收信号」同类）。容器模式的坐标只是"读哪个方块"的数据参数；
	 *       判据见 {@code ArmProgramEntry.movesArm()}（{@code beginEntry} 的那个分支）。
	 *       ⚠️ 所以这里<b>不能</b>走 {@code arm.getMovingHandler()}：那是"臂当前移动目标"那一格
	 *       （{@code movingOffset} 记的是"上一次真的动过身"的位置），而过滤不动身
	 *       ⇒ 它永远指着<b>别的</b>条目那一格；</li>
	 *   <li>候选 = {@code HELD}（臂此刻握着的那份存货）或 {@code CONTAINER}（目标容器的
	 *       <b>第一个非空槽</b> —— 只看第一格，不看整箱）；</li>
	 *   <li>候选为空 ⇒ <b>不满足</b>，无论过滤器是什么；</li>
	 *   <li>过滤器为空（没放幽灵物品）⇒ <b>匹配一切</b>（Create 本体口径：
	 *       {@code FilterItemStack.isEmpty()} 为真时 {@code test} 恒真）；</li>
	 *   <li>符合 ⇒ {@code succeed} 走成功后；不符合 ⇒ {@code handleFailure} —— 与「收信号」
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
			handleFailure(arm, task, entry, FailText.of("world_unloaded"));
			return;
		}
		ItemStack candidate;
		if (entry.filterSource == ArmProgramEntry.FilterSource.CONTAINER) {
			// ★ 范围仍然要判（这是上一版就有的守卫，不是"伸过去"的一部分）：坐标指的是**这条臂**
			//   周边的那一格。走 preCheckFailure 是为了让这条理由与其它操作**同源同文案**
			//   （对 FILTER 它只做"世界加载 + 臂的范围"两件事，物品能力那一段由下面自己报）。
			BlockPos offset = offsetOf(arm, entry);
			FailText blocked = preCheckFailure(arm, entry, offset);
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
				handleFailure(arm, task, entry, FailText.of("filter_not_container"));
				return;
			}
			int slot = firstNonEmptySlot(handler);
			if (slot < 0) {
				handleFailure(arm, task, entry, FailText.of("filter_empty_container"));
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
				entry.filterSource == ArmProgramEntry.FilterSource.CONTAINER ? FailText.of("filter_empty_container")
					: FailText.of("filter_empty_hand"));
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
		// 不匹配 = "时机还没到"，不是"做错了"：与等信号一样在重试窗口里反复判断（见方法头注释）。
		// 物品名直接给 Component（客户端按玩家语言渲染），服务端 getString() 拿到的是英文名
		handleFailure(arm, task, entry, FailText.of("filter_mismatch", candidate.getHoverName()));
	}

	private static String describeFailure(SequencedArmBlockEntity arm) {
		String reason = arm.rightClick.getLastFailure();
		return reason == null ? "未知" : reason;
	}

	private void restartMove(SequencedArmBlockEntity arm, ArmProgramEntry entry) {
		if (!arm.beginMove(offsetOf(arm, entry), entry.face)) {
			halt(arm, FailText.of("move_failed"));
			return;
		}
		state = EntryState.MOVING;
	}

	// ————————————————————————— 成败与跳转 —————————————————————————
	/**
	 * 条目成功。§7.7 v2：成功的出口是 {@code onSuccess} —— 不设就是按顺序下一条，
	 * 设了 {@code SKIP_TO} 就跳过去（这样"成功后跳回前面"就能组成循环与分支）。
	 *
	 * <p>★ 去向的判断在 {@link #applySuccessExit}（**唯一一处**）：被调任务**成功返回**时，
	 * 调用卡自己也走那条 —— 那里以前没接上，于是「调用」上的 {@code ok=goto N} 从来没生效过。
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

		applySuccessExit(arm, task, entry);
	}

	/**
	 * 条目的「成功后」出口（§7.7 v2）——<b>唯一一处判据</b>，两条路径共用：
	 * ① 普通条目跑完（{@link #succeed}）；② 被调任务**成功返回**（{@link #endTask} 那一支）。
	 *
	 * <p>为什么必须抽出来（决策 68/85：同一判据写两份必然分叉）：调用返回那条路径原来直接
	 * 把游标放到"调用条目的下一条"，**从没读过调用卡的 {@code onSuccess}** ⇒
	 * 用户脚本里 {@code call 炒锅1 ok=goto 14} 被静默忽略（详见 {@code endTask} 里的现场记录）。
	 *
	 * <p><b>调用约定</b>：{@code entryIndex} 必须**指向刚完成的那一条**条目
	 * （{@code succeed} 天然如此；调用返回那条路径要先把游标退回「调用」条目自己的下标）——
	 * 下面 {@code entryIndex++} 的"下一条"语义全靠这个约定。
	 *
	 * <p>三种情形：{@code NONE} ⇒ 按顺序下一条（= 调用返回路径的"现在的行为"）；
	 * {@code SKIP_TO} + 有落点 ⇒ 跳到落点；{@code SKIP_TO} 但落点为空 ⇒ 同 {@code NONE}
	 * （与 §5.2 的界面守卫同一条规则：设了跳转却没落点不做事，而不是停机）。
	 */
	private void applySuccessExit(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry) {
		if (entry.onSuccess == SkipAction.SKIP_TO && !entry.successTarget.isBlank()) {
			// 落点已经**失效**（{@code n5} = 它原来指着的那一条被删掉了）：按"没有跳转"走，也就是
			// 顺序下一条 —— 用户 2026-10-20 的口径："不会直接触发跳转……运行到这里的时候无法跳转"。
			// ⚠️ 这里**不能**只是"不跳"就 return：{@code jumpTo} 才是推进游标的那个人，什么都不做地返回
			// 会让同一条条目每 tick 重跑一次（死循环，且表面上"什么都没发生"）。
			if (JumpTargets.isDead(entry.successTarget))
				logDeadTarget(arm, task, entry, true, "");
			else {
				lastFailure = FailText.of("ok_jump", entry.successTarget);
				resetEntryCounters();
				jumpTo(arm, entry.successTarget);
				return;
			}
		}

		advanceAfterSkip(arm, task);
	}

	/**
	 * 记一行"这个落点已经失效了"（{@code n5}）。
	 *
	 * <p>为什么值得单独记：跳转换成了"继续下一条"以后，日志里那一行是玩家唯一能看出"这里本该跳走"的地方
	 * —— 没有它，程序的行为和"这一条压根没设跳转"完全一样（用户要的正是"知道这里原本跳转的位置"）。
	 *
	 * @param reasonSuffix 失败那一路要带上"为什么放弃"（成功那一路给空串）
	 */
	private void logDeadTarget(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry,
		boolean success, String reasonSuffix) {
		String target = success ? entry.successTarget : entry.timeoutTarget;
		if (!arm.logEnabled())
			return;
		arm.logLine(logWhere(task, entry) + (success ? " 落点 " : " 放弃 → 落点 ") + target.trim() + " 已失效（原本第 "
			+ JumpTargets.deadPosition(target) + " 条已被删除）→ 继续下一条"
			+ (reasonSuffix.isEmpty() ? "" : "（" + reasonSuffix + "）"));
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
		//   ★ 所以控制类条目（过滤 / 收发信号 / 调用 …）永远走不到认领那一支 ——
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
	 * 它现在**确实有消费方**：{@code SequencedArmBlockEntity.workOffset()} 用它算"本 tick 关心的那一格"
	 * （认领要把物品按在哪、带速宽限期按哪条带子算）—— 那里问的正是"条目指着哪一格"，
	 * 所以不能换成类型判据（换成 {@code movesArm()} 的话，控制类条目会指向上一条的位置）。
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
	private void handleFailure(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, FailText reason) {
		// ⚠️ **同步节流**：重试本身是每 tick 的（对齐上游），但"每 tick 都发一次方块实体同步"纯属浪费 ——
		// 客户端每 tick 收到一次同步会让手臂动画看起来一顿一顿（用户实测："尝试定住物品的瞬间动画会变慢"）。
		// 所以只有**状态或失败原因真的变了**才发。判据按"键 + 服务端文本"比，而不是拿 Component 的
		// equals 去比（可变的 Component 之间不一定有值相等语义，比错了就是每 tick 一条日志 + 一次同步）。
		boolean changed = state != EntryState.RETRY || !sameFailure(reason, lastFailure);
		lastFailure = reason;
		if (entry.timeoutTicks() < 0 || waitElapsed <= entry.timeoutTicks()) {
			state = EntryState.RETRY; // 阻塞重试（前摇不重跑；计时在 RETRY 分支按 tick 累加）
			if (changed) {
				// ★ 记录点③：**失败与重试的原因**。只在 `changed` 时写 —— 重试是每 tick 的，
				//   同一条原因每 tick 一行会把缓冲变成刷屏器；而"原因变了"正是玩家最想看到的
				//   （"刚才报的是目标为空，现在报臂内已满"）。
				if (arm.logEnabled())
					arm.logLine(logWhere(task, entry) + " 失败：" + reason.text()
						.getString());
				arm.markProgramDirty();
			}
			return;
		}
		applySkip(arm, task, entry, entry.onTimeout,
			FailText.of("retried", reason.text(), entry.failAmount + entry.failUnit.name()));
	}

	/** 两条失败文案是不是"同一件"（键相同 + 服务端解析出来的文本相同）——见 {@link #handleFailure} 的节流说明。 */
	private static boolean sameFailure(@Nullable FailText a, @Nullable FailText b) {
		if (a == null || b == null)
			return a == b;
		return java.util.Objects.equals(a.key(), b.key()) && a.text()
			.getString()
			.equals(b.text()
				.getString());
	}

	/**
	 * 超时后的出口（§7.7 v2）。
	 *
	 * <ul>
	 *   <li>{@code CONTINUE}：跳过这条，继续下一条；</li>
	 *   <li>{@code SKIP_TO}：跳到指定任务/条目；</li>
	 *   <li>{@code NONE}：不设 —— 时限到了就是**停机报错**。</li>
	 * </ul>
	 *
	 * <p>⚠️ <b>「停在原地等条件恢复」那条分支已随任务级循环一起删除</b>：它只对老的"直到失败"
	 * 循环方式成立（用户口径：任务循环次数不需要了，主任务恒为无限循环）。
	 * 现在 {@code NONE} 只有一种语义 —— 停机，由 {@link #halt} 记下"停在哪一条"。
	 */
	private void applySkip(SequencedArmBlockEntity arm, ArmProgramTask task, ArmProgramEntry entry, SkipAction action,
		FailText reason) {
		switch (action) {
			case CONTINUE -> {
				lastFailure = FailText.of("skipped", reason.text());
				// ★ 记录点④：超时后的**去向**（放弃这一条 / 跳到别处）。
				//   与记录点③合起来回答"它为什么停了？停在哪一条？"：③是原因，④是结论。
				if (arm.logEnabled())
					arm.logLine(logWhere(task, entry) + " 放弃 → 继续下一条（" + reason.text()
						.getString() + "）");
				advanceAfterSkip(arm, task);
			}
			case SKIP_TO -> {
				// 落点已经失效（目标那条被删掉了）：与"跳过这一条"同一条路 —— 用户 2026-10-20 的口径
				// "运行到这里的时候无法跳转"（与 {@link #applySuccessExit} 里那一条是同一个判据）。
				if (JumpTargets.isDead(entry.timeoutTarget)) {
					lastFailure = FailText.of("jump_lost");
					logDeadTarget(arm, task, entry, false, reason.text()
						.getString());
					advanceAfterSkip(arm, task);
					return;
				}
				lastFailure = FailText.of("jumped", reason.text(), entry.timeoutTarget);
				if (arm.logEnabled())
					arm.logLine(logWhere(task, entry) + " 放弃 → 跳转 " + entry.timeoutTarget + "（" + reason.text()
						.getString() + "）");
				jumpTo(arm, entry.timeoutTarget);
			}
			case NONE -> halt(arm, reason); // 停机那行由 halt 自己记（含"停在哪一条"）
		}
	}

	/** 超时后"继续下一条"（「跳过这条」与"落点失效"共用）：推进游标，越过末尾就算这一轮走完。 */
	private void advanceAfterSkip(SequencedArmBlockEntity arm, ArmProgramTask task) {
		resetEntryCounters();
		entryIndex++;
		if (entryIndex >= task.entries.size())
			finishTask(arm, task);
		else
			arm.markProgramDirty();
	}

	/**
	 * 跳转：数字 = 本任务内的条目序号，**从 1 数**（用户在界面上看到的序号就是它）；
	 * 也接受任务名（切到那个任务的第 1 条）。
	 *
	 * <p>⚠️ 序号从 1 数是<b>用户可见的约定</b>（提示文案一直这么写），而运行时第一版直接把它当 0 基下标用，
	 * 于是"跳转到 1"实际跳到第 2 条 —— 实测发现的静默错位。现在统一：`N` → `entries[N-1]`。
	 *
	 * <p>额外允许 `N = 条目数 + 1`＝<b>跳到末尾（这一轮就到此为止）</b>：
	 * 主任务里它等于"提前结束这一轮 ⇒ 回到第 1 条重来"（恒循环，见 {@link #finishTask}），
	 * 子任务里它等于"提前返回调用方"。要**真的停下**只能用「退出」或「抛错」。
	 *
	 * <p>★ <b>失效落点（{@code n5}）不在这里处理</b>（用户 2026-10-20：它记着"原来指向第 5 条，那条被删掉了"，
	 * "运行到这里的时候无法跳转"）：{@link #applySuccessExit} 与 {@link #applySkip} 会先拦下它并按
	 * "继续下一条"处理 —— 而且**必须**在那里拦，因为"不跳"还得有人推进游标，否则同一条条目每 tick 重跑。
	 * 这里**不做兜底**：万一将来多一个调用点忘了拦，它会掉进最后那句
	 * {@code halt(jump_missing)}（响亮、一看就知道是"有人漏了"，而不是悄悄换个行为）。
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
					// ★ 记录点⑤：**跳转的落点**。数字落点写"任务名#序号"而不是只写数字 ——
					//   用户翻日志时最常问的是"它跳到哪条去了"，只给一个 7 还得自己回去数。
					if (arm.logEnabled())
						arm.logLine("↪ 落点 " + task.name + "#" + oneBased
							+ (oneBased > task.entries.size() ? "（末尾，本轮到此为止）" : ""));
					arm.markProgramDirty();
					return;
				}
				halt(arm, FailText.of("jump_out_of_range", oneBased, task.entries.size()));
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
				if (arm.logEnabled())
					arm.logLine("↪ 落点 " + program.tasks.get(i).name + "#1（换任务）");
				arm.markProgramDirty();
				return;
			}
		}
		halt(arm, FailText.of("jump_missing", trimmed));
	}

	/**
	 * 跳转到别的任务时收拾一下调用栈：与调用不同，跳转是**单向**的，没有返回地址可言。
	 * 如果目标任务正好在栈上（说明是"跳回调用者"），就把那层弹掉，避免栈里留下永远回不去的记录。
	 *
	 * <p>⚠️ 调用偏移**不需要在这里手动还原**：它按栈顶算（{@link #effectiveCallShift()}），
	 * 弹掉几层就自然回到父层 / 主任务（0）—— 这正是"不维护第二个字段"的收益。
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
	 * <p><b>子任务</b>：轮次由**调用点**决定（用户第二十七轮："把循环次数放在调用上"）——
	 * 跑完一轮先看调用点还要求再跑几轮，还要求就原地重跑（不弹栈），否则返回调用方。
	 *
	 * <p>★ <b>主任务：恒为无限循环</b>（用户口径："任务循环次数已经不需要了……主任务是不断循环的"）
	 * —— 走完最后一条就回到第 1 条继续跑，**没有"收工"这回事**，也不再有任何计次。
	 * 「跳转到末尾」（{@code N = 条数 + 1}）同样落到这里 ⇒ 也是"这一轮到此为止、从头重来"。
	 * 要停下只有两条路，都走 {@link #endTask}：「退出」(收工停机) 与「抛错」(失败停机)。
	 *
	 * <p>⚠️ 一轮结束时保留老的收臂动作（{@code cancelMove()}）：手臂不该举在最后一个目标前面
	 * 进入下一轮（与「等待」同一条理由）—— 这是删除循环设置前就有的行为，一个字没改。
	 */
	private void finishTask(SequencedArmBlockEntity arm, ArmProgramTask task) {
		resetEntryCounters();
		entryIndex = 0;
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
		// ★ 记录点⑧：主任务**跑完一轮**（"它为什么不动了？"的答案之一：不是卡住，是刚跑到末尾、
		//   下一 tick 就从头再来）。⚠️ 报**任务名**，不要直接拼 task 对象：ArmProgramTask 没有 toString()，
		//   拼进去是一个对象哈希（同一个坑见 endTask 里那句"被调任务失败（…）"的注释）。
		if (arm.logEnabled())
			arm.logLine("↻ 主任务 " + task.name + " 跑完一轮 → 回到第 1 条（恒循环）");
		arm.cancelMove(); // 一轮结束收臂：别举在最后一个目标前面进下一轮（与「等待」同一条理由）
		// ⚠️ §7.16e 有意的**反转**：一轮跑完**不清信号** —— 信号是持久变量，跨轮、跨停机都保持；
		//   要断只能靠「中断」或换卡（旧行为 `clearTransmissions()` 已删，见 §7.16c 反证①）。
		arm.markProgramDirty();
	}

	public void halt(SequencedArmBlockEntity arm, FailText reason) {
		state = EntryState.HALTED;
		error = reason;
		errorEntryIndex = entryIndex;
		// ★ 记录点⑨：**停机**（用户的问题"它为什么停了？停在哪一条？"就靠这一行回答）。
		//   带上"停在哪一条"：停机时游标还指着出错的那一条，logWhere 给的序号正是界面上的序号。
		if (arm.logEnabled())
			arm.logLine("停机 @" + logWhere(arm.getProgram()
				.task(taskIndex), currentEntryOrNull(arm.getProgram())) + "："
				+ (reason == null ? "未说明" : reason.text()
					.getString()));
		arm.cancelMove(); // 停机也收回：出错时把手臂留在目标前面既难看又挡视线
		// 停机后 tick() 会在累积预算之前就早退，所以这笔记账**不清也不会自己涨**；这里还是清掉：
		// 它是"本份程序还没花掉的那点余量"，而停机 = 这份程序到此为止（复位走 resetToStart 也会清）。
		// 留着它只会在将来有人加"解除停机"时变成一个"一恢复就多走一步"的暗坑。
		rateBudget = 0;
		// ⚠️ §7.16e 有意的**反转**：停机**不再清信号**（旧行为在 §7.16c 里被 D 验过，用户这轮推翻）。
		// 理由与"主任务跑完一轮"一样：信号是**持久变量**，停机只是"程序停了"，不等于"变量复位"。
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
		idleSpins = 0;
		idleCooldown = 0;
		// ★ 运行日志跟着这份程序走：换卡 / 回到开头 = 另一个故事，上一份程序的现场不该混进来
		//   （否则玩家导出后看到的是"两条臂混在一起"的时间线，`[+Nt]` 的差也会跨程序乱跳）。
		arm.clearLog();
		// 速率预算的余量也要归零：换卡 / 回到开头是"另一份程序"，不该继承上一份攒下的零头
		// （自检的速率夹具正是靠这一点拿到"第一 tick 的预算是确定值"，见 checkEntryRate ①）。
		rateBudget = 0;
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

	public CompoundTag save(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("task", taskIndex);
		tag.putInt("entry", entryIndex);
		tag.putString("state", state.name());
		tag.putInt("timer", timer);
		tag.putInt("waited", waitElapsed);
		tag.putInt("idle", idleCooldown);
		ListTag stack = new ListTag();
		for (Frame frame : callStack) {
			CompoundTag f = new CompoundTag();
			f.putInt("t", frame.returnTask());
			f.putInt("e", frame.returnEntry());
			// ⚠️ 老的 `l`（调用方的循环计数）**不再写**：任务级循环已删除，没有那个数了。
			//   老存档里读到它也直接无视（不做兼容读取，用户明确不要）。
			f.putInt("r", frame.repeatsLeft()); // 调用点还剩几轮（不存的话重登后会多跑/少跑）
			// ★ 进入这个被调任务时生效的**调用偏移**（键 `s` = shift）：不存的话，重登之后
			//   子任务的目标会**跳回**没加偏移的位置（用户口径里的"臂卸载再载入时偏移不能丢"）。
			f.put("s", NbtUtils.writeBlockPos(frame.entryShift()));
			stack.add(f);
		}
		tag.put("stack", stack);
		tag.putInt("spins", idleSpins); // 老存档里的 steps/ticks 已废（那两条预算删了），读的时候直接忽略
		// ★ 停机原因存**组件的 JSON + 键**（不是一句纯文本）：重进世界后护目镜上那一行仍然能按
		//   玩家语言显示，而 /sma 读到的是服务端解析出来的那句话 —— 见 FailText 的说明。
		if (error != null) {
			tag.putString("error", Component.Serializer.toJson(error.text(), registries));
			tag.putInt("errorEntry", errorEntryIndex);
			if (error.key() != null)
				tag.putString("errorkey", error.key());
		}
		return tag;
	}

	public void load(CompoundTag tag, HolderLookup.Provider registries) {
		taskIndex = tag.getInt("task");
		entryIndex = tag.getInt("entry");
		state = readState(tag.getString("state"));
		timer = tag.getInt("timer");
		waitElapsed = tag.getInt("waited");
		idleCooldown = tag.getInt("idle");
		callStack.clear();
		ListTag stack = tag.getList("stack", Tag.TAG_COMPOUND);
		for (int i = 0; i < stack.size(); i++) {
			CompoundTag f = stack.getCompound(i);
			// 缺 r → 1（照 ArmProgramEntry.load 的理由：0 现在是"无限"这个**合法值**，不能拿它兜底）
			// 缺 s = 不是三长整数数组（老存档 / 没设偏移的调用）⇒ 回落 (0,0,0)：行为与改造前一致
			// ⚠️ 老存档里那个 `l`（调用方的循环计数）**不读**：任务级循环已删，这一帧不再有那个数。
			callStack.add(new Frame(f.getInt("t"), f.getInt("e"),
				f.contains("r") ? ArmProgramEntry.clampCallRepeat(f.getInt("r")) : 1,
				NbtUtils.readBlockPos(f, "s")
					.orElse(BlockPos.ZERO)));
		}
		idleSpins = tag.getInt("spins");
		Component readError = readComponent(tag, "error", registries);
		if (readError != null) {
			error = new FailText(readError, tag.contains("errorkey") ? tag.getString("errorkey") : null);
			errorEntryIndex = tag.getInt("errorEntry");
		}
	}

	/**
	 * 把存进 NBT 的组件 JSON 读回来（{@code null} = 没存过这一条）。
	 *
	 * <p>⚠️ <b>必须兜一层</b>：改造前 {@code error} 存的是<b>一句纯文本</b>，直接丢给
	 * {@code Component.Serializer.fromJson} 会抛 JSON 语法异常（把区块读盘整条炸掉）。
	 * 读不动就按字面量兜回来 —— 那句话是现场的唯一记录，宁可它不翻译，也不许丢。
	 */
	@Nullable
	public static Component readComponent(CompoundTag tag, String key, HolderLookup.Provider registries) {
		if (!tag.contains(key))
			return null;
		String json = tag.getString(key);
		if (json.isEmpty())
			return null;
		try {
			Component parsed = Component.Serializer.fromJson(json, registries);
			return parsed == null ? Component.literal(json) : parsed;
		} catch (RuntimeException oldPlainText) {
			return Component.literal(json);
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
