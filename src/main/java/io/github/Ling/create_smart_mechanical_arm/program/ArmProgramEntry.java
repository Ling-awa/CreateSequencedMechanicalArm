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
	/** WAIT_REDSTONE / WAIT_SIGNAL：等待通电（true）还是断电（false）。 */
	public boolean waitForPowered = true;
	/**
	 * WAIT_SIGNAL（等待信号）：要等的红石链接频率（两个物品，与 Create 的链接频率一致）。
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
		if (op == OperationType.WAIT_REDSTONE || op == OperationType.WAIT_SIGNAL)
			sb.append(" redstone=")
				.append(waitForPowered ? "on" : "off");
		if (op == OperationType.WAIT_SIGNAL)
			sb.append(" freq=")
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
