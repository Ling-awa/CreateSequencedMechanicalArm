package io.github.Ling.create_smart_mechanical_arm.program;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;

import net.createmod.catnip.data.Couple;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 操作表里的一条操作（PLAN.md §4.1 的 Entry）。
 *
 * <p>NBT 键刻意短（这是会被反复存读的结构）：{@code op/off/abs/delay/wt/to/tunit/timeout/onok/
 * okt/tot/note/count/waitt/wunit/redpow/urep/uamt}。
 *
 * <p><b>§7.7 v2 的成败语义（用户定的）</b>：一条操作只有两个出口 —— <b>成功</b>与<b>超时</b>。
 * "失败"不再是独立分支：任何一次不成功都等于"还没成功"，就地重试直到「失败后」的时限到点，
 * 到点后走 {@link #onTimeout}；成功则走 {@link #onSuccess}（不设就是按顺序下一条）。
 * 于是「成功后跳转」可以用来做循环与分支，而"等一下再试"变成每个操作的默认行为，
 * 不再需要一个额外的 {@code force} 开关（旧字段已在 {@link #load} 里迁移）。
 */
public class ArmProgramEntry {

	public OperationType op = OperationType.TAKE;
	/** 目标位置；{@code absolute=false} 时相对手臂方块。臂内部优先用相对坐标（对齐原版交互点）。 */
	public BlockPos offset = BlockPos.ZERO;
	public boolean absolute;

	/** 前摇等待（ticks）。 */
	public int delay;
	/** 攻击 / 挖掘 / 使用 的交互面（配合方块与面共同决定动作）。 */
	public Direction face = Direction.UP;

	/**
	 * 失败出口（{@code -1} = 失败就**一直重试**，默认）。与"时限"合成一个属性后，
	 * 界面上的「失败后」= {@code failAmount}×{@code failUnit} 的等待窗口 + {@link #onTimeout} 的去向。
	 */
	public int failAmount = -1;
	public WaitUnit failUnit = WaitUnit.TICK;

	/** 成功后：不设（按顺序下一条）或跳转。 */
	public SkipAction onSuccess = SkipAction.NONE;
	/** 失败之后：什么都不做（停下报错）/ 跳过继续 / 跳转。 */
	public SkipAction onTimeout = SkipAction.NONE;
	/**
	 * 两个分支各自的跳转落点：任务名，或同任务内的条目序号（十进制字符串）。
	 *
	 * <p><b>v3 拆成两个</b>：界面上它们是两个独立的胶囊（"成功跳转 3" / "超时跳转 1"），
	 * 两个出口几乎总要跳到不同地方，v2 共用一个 {@code skip} 表达不了。
	 */
	public String successTarget = "";
	public String timeoutTarget = "";

	public String note = "";

	/**
	 * 备注的**总字数**上限（用户第二十七轮定的：128 字，**行数不限**）。
	 * 编辑器里的备注输入框、协议发送、「剪贴板 → 备注」共用这一个上限：
	 * 两处不一致就会出现"界面显示一截、实际存着另一截"的错位（协议那层超了是**抛异常**，不是截断）。
	 */
	public static final int NOTE_MAX = 128;

	/**
	 * 把一段文本夹到备注上限：**超了在结尾补 `…`**（用户要求）。
	 *
	 * <p>省略号**算在上限之内**（`substring(0, NOTE_MAX - 1) + "…"` = 恰好 NOTE_MAX 字）——
	 * 否则"截断后还超上限"，协议那一层又会抛异常，等于没修。
	 *
	 * <p>**保留内部的换行**：备注可以是多行（剪贴板按条目导入时就会带进来），
	 * 显示交给 tooltip 与后面的清单 HUD；这里只做长度，不做结构。
	 */
	public static String clipNote(String text) {
		if (text == null)
			return "";
		if (text.length() <= NOTE_MAX)
			return text;
		return text.substring(0, NOTE_MAX - 1) + "…";
	}

	/**
	 * 多行备注 → 单行编辑框里的显示形式：把换行写成**字面的 `\n` 两个字符**（方案 B，用户选定）。
	 *
	 * <p>为什么用 `\n` 这两个字面字符当分隔符：单行 `EditBox` 装不下真换行（会渲染成缺字/断行不一致），
	 * 而 `\n` 一眼就能看懂是"这里有个换行"，同时是纯 ASCII（不赌字体有没有那个符号），
	 * 玩家手打出这两个字符的概率也极低。
	 */
	public static String flattenNote(String note) {
		return note == null ? "" : note.replace("\n", "\\n");
	}

	/** 单行编辑框里的显示形式 → 存回去的备注：把字面的 `\n` 还原成真换行，并按上限夹一次。 */
	public static String joinNote(String display) {
		return clipNote(display == null ? "" : display.replace("\\n", "\n"));
	}

	/**
	 * 备注 → 单行编辑框里**实际显示**的文本（= {@link #flattenNote} 再保证三件事）：
	 * ① 长度 ≤ {@link #NOTE_MAX}；② 不会把 `\n` 这两个字面字符**从中间切开**；③ 真丢了内容就在结尾补 `…`。
	 *
	 * <p>为什么不能直接把 {@code flattenNote(note)} 交给输入框：备注存的是**真换行**（1 个字符），
	 * 显示要变成**两个字面字符**，所以"存得下"不等于"显示得下" —— 128 个换行存得下，扁平化后是 256 字。
	 * 输入框自己会硬截到 128（{@code EditBox.setValue} 里的 {@code substring(0, maxLength)}），截点一旦
	 * 落在 `\` 与 `n` 之间，框里就留下一个孤立的反斜杠；玩家随后随便敲一个字符，这个孤立反斜杠就会被
	 * 当成备注的一部分存下去（{@code joinNote} 只认成对的 `\n`）。
	 */
	public static String flattenNoteForField(String note) {
		String src = note == null ? "" : note;
		String flat = flattenNote(src);
		if (flat.length() <= NOTE_MAX)
			return flat;
		// 超限：按**源文本**逐字符累加，`\n` 只在放得下整个两个字符时才写出（省略号算在限内）。
		StringBuilder out = new StringBuilder(NOTE_MAX);
		for (int i = 0; i < src.length(); i++) {
			char c = src.charAt(i);
			if (out.length() + (c == '\n' ? 2 : 1) > NOTE_MAX - 1)
				break;
			if (c == '\n')
				out.append("\\n");
			else
				out.append(c);
		}
		return out.append('…')
			.toString();
	}

	/**
	 * 自定义图标（可选）。空 = 用 {@code ArmIcons.defaultIcon(op)} —— 卡片胶囊上那个小图标。
	 * 来源是编辑器里的<b>幽灵槽</b>（照时刻表"从背包把物品拖进槽"的做法）。
	 */
	public ItemStack icon = ItemStack.EMPTY;

	/** TAKE 的数量。 */
	public int count = 1;
	/**
	 * WAIT 的等待时长：<b>数值 × 单位</b>（用户要求"时间能选 tick / s / min，数值 0–120"）。
	 *
	 * <p>为什么不只存 ticks：界面上要能原样回显"10 s"而不是"200 tick"。
	 * 换算只在 {@link #waitTicks()} 一处发生，运行时永远用 ticks。
	 */
	public int waitAmount = 20;
	public WaitUnit waitUnit = WaitUnit.TICK;
	/**
	 * WAIT_REDSTONE / SIGNAL：极性（"通电"那一侧）。收：等通电（true）/ 等断电（false）；
	 * 发：发通电 15（true）/ 发断电 0（false）—— 同一字段、同一含义方向（§7.16b 三）。
	 */
	public boolean waitForPowered = true;
	/**
	 * SIGNAL（收发信号）：模式 —— 收 / 发通电 / 发断电（§7.16b 一.2）。
	 *
	 * <p>{@code ordinal} 就是三选一选择框的下标（{@link #isSignalSend()} = {@code mode != RECEIVE}）。
	 * 老卡没有 {@code sigmode} 键 ⇒ 缺省 {@code RECEIVE} ⇒ 行为与改造前完全一致。
	 */
	public SignalMode signalMode = SignalMode.RECEIVE;
	/**
	 * SIGNAL（收 / 发）要用的红石链接频率（两个物品，与 Create 的链接频率一致）。
	 *
	 * <p>从 {@link ArmProgramTask} 搬过来 —— 用户要求"把频率功能单独拆出来"，于是
	 * "这个任务要通电才跑"变成任务开头的第一条等待信号条目，而任务只剩循环设置。
	 */
	@Nullable
	public Couple<Frequency> frequency;
	/** CALL（调用任务）：被调任务的名字。 */
	public String callTarget = "";
	/**
	 * CALL（调用任务）：这个调用点让子任务跑几轮（1..{@link ArmProgramTask#MAX_LOOP_COUNT}）。
	 *
	 * <p><b>用户第二十七轮定的：循环次数归调用点</b> —— "把循环次数放在调用上"。
	 * 于是子任务的轮次完全由这里决定（子任务自身的 {@code loop} 只对主任务有意义，
	 * 编辑器也不给子任务显示循环设置），避免"调用 ×3 × 子任务自己循环 ×2"这种要相乘、
	 * 谁都算不清的组合。
	 */
	public int callRepeat = 1;
	/** THROW（抛出错误）：写给护目镜看的错误信息。 */
	public String errorMessage = "";
	/** UNLOAD：卸一次还是卸到空。 */
	public UnloadRepeat unloadRepeat = UnloadRepeat.ONCE;
	/** UNLOAD：一次一组还是尽可能多。 */
	public UnloadAmount unloadAmount = UnloadAmount.ONE_STACK;

	/** WAIT 时长的单位。最大 120 min = 144000 ticks，只是把臂挂在那儿（等待不计入"无进展"预算）。 */
	public enum WaitUnit {
		TICK(1), SECOND(20), MINUTE(1200);

		public final int ticks;

		WaitUnit(int ticks) {
			this.ticks = ticks;
		}
	}

	/** 实际等待的 ticks（运行时唯一使用的值）。 */
	public int waitTicks() {
		return waitAmount * waitUnit.ticks;
	}

	/**
	 * 发送持续的**数值**上限；{@code 0} = 一直保持（"占用 / 解除占用"就是这个），{@code >0} = 到点自动恢复。
	 *
	 * <p>⚠️ 这是**数值**、不是 ticks：真正的 ticks = 数值 × {@link #waitUnit}（用户反馈第二轮：
	 * 持续控件和等待一样带 t/s/m 单位框，所以 {@link #signalTicks()} **必须按单位换算**）。
	 * 数值范围与 WAIT 一致（0–120），于是最长的脉冲 = 120 m = 144000 ticks。
	 */
	public static final int SIGNAL_MAX_AMOUNT = 120;

	/**
	 * SIGNAL 的模式（§7.16b 一.2）：收 / 发通电 / 发断电。
	 *
	 * <p>{@code ordinal} 直接当三选一选择框的下标用。
	 */
	public enum SignalMode {
		RECEIVE, SEND_ON, SEND_OFF
	}

	/** 发信号（= 不是"收"）：{@code SEND_ON} 与 {@code SEND_OFF} 只差 {@link #waitForPowered}。 */
	public boolean isSignalSend() {
		return signalMode != SignalMode.RECEIVE;
	}

	public boolean isSignalReceive() {
		return signalMode == SignalMode.RECEIVE;
	}

	/**
	 * 发送的**持续**时长（ticks）：{@code 0} = 一直保持，否则 {@code clamp(waitAmount,0,120) × 单位}。
	 *
	 * <p>⚠️ 单位换算**必须**走这条：用户反馈第二轮给持续控件加了 t/s/m 单位框，
	 * "选 1 s 却只发 1 tick"就是显示与实际相反。换算表与 {@link #waitTicks()} 完全一致
	 * （{@code t=1 / s=20 / m=1200}），只是**数值**字段仍复用 {@code waitAmount}。
	 *
	 * <p>收信号（{@code RECEIVE}）没有"持续"这个概念，返回 0。
	 */
	public int signalTicks() {
		if (isSignalReceive())
			return 0;
		int amount = Math.max(0, Math.min(SIGNAL_MAX_AMOUNT, waitAmount));
		return amount * waitUnit.ticks;
	}

	/**
	 * 这条操作失败时是"<b>重试到时限</b>"吗 —— 即它有没有一个**重试窗口**
	 * （从 {@code OperationType} 的类型级谓词搬来，按**条目**判定：§7.16b 一.2、决策 73）。
	 *
	 * <p>取 / 放 / 卸 / 组装 / 等红石：不成功就是"还没成功"，会原地重试到「失败后」的时限，
	 * 时限到了才走去向。没有重试窗口、但有失败出口的（攻击 / 调用）失败是**立刻有结论**的 ——
	 * 挖不动、被领地保护、子任务抛错，等下去不会有任何变化。
	 *
	 * <p><b>为什么这条判定必须按条目、也只能有一处</b>：它同时决定三件事 —— 编辑器要不要给
	 * "时限/单位"控件、{@code failAmount} 是不是有效字段、以及卡片上「失败后」该怎么写。
	 * 三者写在两处就会不一致：曾经的 bug 正是"编辑器不给时限控件（所以 {@code failAmount} 一直是 -1），
	 * 而文案却拿 -1 报『一直重试（没设时限）』" —— 玩家选了「失败后 跳转 2」，卡片上看到的却是"一直重试"，
	 * 显示与实际行为刚好相反。
	 *
	 * <p>⚠️ {@code SIGNAL} 是**同一条操作，收有窗口、发没有**（发送不会失败）：
	 * 收信号 {@code signalMode == RECEIVE} → 有；发信号 → 没有。
	 * 这里**永不看 {@code waitForPowered}** —— 那是极性（等通电/等断电），与有没有失败出口无关。
	 */
	public boolean hasRetryWindow() {
		return switch (op) {
			case TAKE, DEPOSIT, UNLOAD, DEPLOY, WAIT_REDSTONE -> true;
			case SIGNAL -> signalMode == SignalMode.RECEIVE;
			default -> false;
		};
	}

	/** 这条操作有"失败"这个出口吗（= 编辑器里要显示「失败后」）；条目级版本（§7.16b 一.2）。 */
	public boolean hasFailureExit() {
		return hasRetryWindow() || op == OperationType.MINE || op == OperationType.CALL;
	}

	/**
	 * 只有**硬失败**（做不成，等也没用）的条目 —— 挖掘 / 调用：能设「失败后」但**没有时限**。
	 *
	 * <p>攻击**没有**失败出口：挥一击就是挥一击（不筛选、不判断有没有东西），永远返回"完成"。
	 * 给它一个永远不会触发的「失败后」正是上一次修掉的那类误导（决策 73）。
	 */
	public boolean hasHardFailureExit() {
		return hasFailureExit() && !hasRetryWindow();
	}

	/** 失败重试的窗口（ticks）；{@code -1} = 一直重试（运行时唯一使用的值）。 */
	public int timeoutTicks() {
		return failAmount < 0 ? -1 : failAmount * failUnit.ticks;
	}

	public enum SkipAction {
		NONE, CONTINUE, SKIP_TO
	}

	public enum UnloadRepeat {
		ONCE, UNTIL_EMPTY
	}

	public enum UnloadAmount {
		ONE_STACK, MAX
	}

	public CompoundTag save(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putString("op", op.name());
		tag.put("off", NbtUtils.writeBlockPos(offset));
		tag.putBoolean("abs", absolute);
		tag.putInt("delay", delay);
		tag.putString("face", face.getName());
		tag.putInt("to", failAmount);
		tag.putString("tunit", failUnit.name());
		tag.putString("onok", onSuccess.name());
		tag.putString("timeout", onTimeout.name());
		tag.putString("okt", successTarget);
		tag.putString("tot", timeoutTarget);
		tag.putString("note", note);
		tag.putInt("count", count);
		tag.putInt("waitt", waitAmount);
		tag.putString("wunit", waitUnit.name());
		tag.putBoolean("redpow", waitForPowered);
		// 收信号（缺省）**不写**这个键：只含收信号的卡存取前后 NBT 逐字节相同（老卡零扰动，§7.16a 第 10 条）
		if (signalMode != SignalMode.RECEIVE)
			tag.putString("sigmode", signalMode.name());
		if (frequency != null)
			tag.put("freq", frequency.serializeEach(f -> (CompoundTag) f.getStack()
				.saveOptional(registries)));
		tag.putString("call", callTarget);
		tag.putInt("calln", callRepeat);
		tag.putString("err", errorMessage);
		tag.putString("urep", unloadRepeat.name());
		tag.putString("uamt", unloadAmount.name());
		if (!icon.isEmpty())
			tag.put("icon", icon.saveOptional(registries));
		return tag;
	}

	public static ArmProgramEntry load(CompoundTag tag, HolderLookup.Provider registries) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.byName(tag.getString("op"), OperationType.TAKE);
		entry.offset = NBTHelper.readBlockPos(tag, "off");
		entry.absolute = tag.getBoolean("abs");
		entry.delay = Math.max(0, tag.getInt("delay"));
		Direction parsedFace = Direction.byName(tag.getString("face"));
		entry.face = parsedFace == null ? Direction.UP : parsedFace;
		entry.onSuccess = enumOf(SkipAction.class, tag.getString("onok"), SkipAction.NONE);
		entry.onTimeout = enumOf(SkipAction.class, tag.getString("timeout"), SkipAction.NONE);
		entry.successTarget = tag.getString("okt");
		entry.timeoutTarget = tag.getString("tot");
		// 读入时也夹一次：手改过的卡 / 老数据可能超过上限，而超上限的字符串会在
		// 下次发送时让编码器抛异常（见 NOTE_MAX 的说明）。这是**兜底**，正常路径不会触发。
		entry.note = clipNote(tag.getString("note"));
		entry.count = Math.max(1, tag.getInt("count"));
		// waitt 以前是"ticks"，现在带单位：老卡片没有 wunit → 数值就是 tick 数，语义不变
		entry.waitAmount = Math.max(0, tag.getInt("waitt"));
		entry.waitUnit = enumOf(WaitUnit.class, tag.getString("wunit"), WaitUnit.TICK);
		entry.waitForPowered = !tag.contains("redpow") || tag.getBoolean("redpow");
		// sigmode 缺键（老卡）= RECEIVE ⇒ 行为不变（兼容清单 2）；写的是**字符串**而不是 ordinal，
		// 以后枚举增删项不会把老卡的"发"变成"收"（与 op/onok/timeout/urep 同一惯例）
		entry.signalMode = enumOf(SignalMode.class, tag.getString("sigmode"), SignalMode.RECEIVE);
		// 发信号的持续**数值**夹进与 WAIT 相同的 0..120（真正的 ticks = 数值 × 单位，见 signalTicks()）
		if (entry.isSignalSend())
			entry.waitAmount = Math.max(0, Math.min(SIGNAL_MAX_AMOUNT, entry.waitAmount));
		entry.frequency = readFrequency(tag, registries);
		entry.callTarget = tag.getString("call");
		// 缺 calln（老卡）→ 1 轮；夹到合法区间，坏数据不许把循环次数搞成 0 或天文数字
		entry.callRepeat = Math.max(1, Math.min(ArmProgramTask.MAX_LOOP_COUNT, tag.getInt("calln")));
		entry.errorMessage = tag.getString("err");
		entry.unloadRepeat = enumOf(UnloadRepeat.class, tag.getString("urep"), UnloadRepeat.ONCE);
		entry.unloadAmount = enumOf(UnloadAmount.class, tag.getString("uamt"), UnloadAmount.ONE_STACK);
		if (tag.contains("icon"))
			entry.icon = ItemStack.parseOptional(registries, tag.getCompound("icon"));

		// ——— v1 → v2 迁移（§7.7 语义改版）———
		// v1 有 force/forceTimeout（"重试到限时"）与 onFail（"立刻失败时怎么办"）两个分支。
		// v2 只剩"重试到限时"一条路，所以：时限取旧的 forceTimeout；超时后的动作优先用旧的 onTimeout，
		// 没设过就沿用旧的 onFail —— 老卡片的意图（"取不到就跳 B"）这样才不会被丢掉。
		// v3 又把"时限"并进了「失败后」，数值带单位：老数据的 `to`/`ftimeout` 都是 ticks，
		// 所以缺 `tunit` 时按 TICK 读，语义完全不变。
		if (tag.contains("to")) {
			entry.failAmount = tag.getInt("to");
			entry.failUnit = enumOf(WaitUnit.class, tag.getString("tunit"), WaitUnit.TICK);
		} else {
			entry.failAmount = tag.contains("ftimeout") ? tag.getInt("ftimeout") : -1;
			if (entry.onTimeout == SkipAction.NONE && tag.contains("fail"))
				entry.onTimeout = enumOf(SkipAction.class, tag.getString("fail"), SkipAction.NONE);
		}

		// v2 → v3：v2 两个分支共用一个 "skip" 落点 —— 谁在用跳转就补给谁
		if (!tag.contains("okt") && entry.onSuccess == SkipAction.SKIP_TO)
			entry.successTarget = tag.getString("skip");
		if (!tag.contains("tot") && entry.onTimeout == SkipAction.SKIP_TO)
			entry.timeoutTarget = tag.getString("skip");
		return entry;
	}

	/** 频率对（两物品）；空/缺省返回 null = 没绑频率。 */
	@Nullable
	public static Couple<Frequency> readFrequency(CompoundTag tag, HolderLookup.Provider registries) {
		if (!tag.contains("freq"))
			return null;
		ListTag freqTag = tag.getList("freq", Tag.TAG_COMPOUND);
		if (freqTag.size() != 2)
			return null;
		Couple<Frequency> parsed =
			Couple.deserializeEach(freqTag, c -> Frequency.of(ItemStack.parseOptional(registries, c)));
		if (parsed.getFirst() == Frequency.EMPTY && parsed.getSecond() == Frequency.EMPTY)
			return null;
		return parsed;
	}
	private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
		if (name != null && !name.isEmpty()) {
			for (E constant : type.getEnumConstants()) {
				if (constant.name()
					.equalsIgnoreCase(name))
					return constant;
			}
		}
		return fallback;
	}

	/** 护目镜 / 调试用的一行描述。 */
	public String describe() {
		StringBuilder sb = new StringBuilder(op.name());
		if (op.requiresTarget())
			sb.append(' ')
				.append(absolute ? "abs" : "rel")
				.append(offset.toShortString());
		if (delay > 0)
			sb.append(" delay=")
				.append(delay);
		if (op == OperationType.WAIT)
			sb.append(" wait=")
				.append(waitAmount)
				.append(waitUnit);
		if (op == OperationType.WAIT_REDSTONE || op == OperationType.SIGNAL)
			sb.append(" redstone=")
				.append(waitForPowered ? "on" : "off");
		if (op == OperationType.SIGNAL)
			sb.append(" mode=")
				.append(signalMode)
				.append(" ticks=")
				.append(signalTicks())
				.append(" freq=")
				.append(frequency == null ? "未设置"
					: frequency.getFirst()
						.getStack()
						.getHoverName()
						.getString() + "+"
						+ frequency.getSecond()
							.getStack()
							.getHoverName()
							.getString());
		if (op == OperationType.CALL)
			sb.append(" call=")
				.append(callTarget);
		if (op == OperationType.THROW)
			sb.append(" error=")
				.append(errorMessage);
		if (failAmount >= 0)
			sb.append(" failAfter=")
				.append(failAmount)
				.append(failUnit.name());
		if (onSuccess != SkipAction.NONE)
			sb.append(" onSuccess=")
				.append(onSuccess)
				.append('(')
				.append(successTarget)
				.append(')');
		if (onTimeout != SkipAction.NONE)
			sb.append(" onTimeout=")
				.append(onTimeout)
				.append('(')
				.append(timeoutTarget)
				.append(')');
		return sb.toString();
	}

}
