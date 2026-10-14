package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.Mth;

/**
 * 「副手剪贴板 = 待办清单」的**纯投影**（PLAN.md §5.5，用户第二十七轮要求）。
 *
 * <p>用户的原话：主手拿着录入模式的卡、副手拿着剪贴板时，屏幕左边显示一张清单，
 * <i>"显示 已完成 / 当前 / 下一个，录一条就划掉一条"</i>，并且"录一条就自动把那一行写成备注"。
 *
 * <h2>为什么是"投影"而不是"存一份对应关系"</h2>
 * 清单的进度<b>完全由「剪贴板上的行」和「任务里条目的备注」现算</b>（{@link #consumed}），
 * 不存任何 index → index 的映射表。存了就会有两份状态要同步：玩家在编辑器里删掉一条、
 * 左键撤销一条、从剪贴板重写一次备注 —— 每一条路径都要记得改那张表，漏一条清单就开始说谎。
 * 现算的代价只是每次比较几行字符串（一条程序最多几百条、清单通常十几行）。
 *
 * <p>同一份判定被<b>两处</b>用：服务端"录一条时把下一行写成备注"（{@link #nextNote}）
 * 与 HUD 的"已完成 / 当前 / 下一个"（{@link #consumed} + {@link #state}）——
 * 所以它必须是同一份，否则会出现"备注写的是第 3 行、清单却说当前在第 4 行"。
 *
 * <h2>匹配规则</h2>
 * <b>按顺序贪心</b>：从清单第 0 行开始，每一行去任务里从上次匹配位置之后找第一条备注与它相同的条目，
 * 找到就继续下一行，找不到就停下 —— 停下的位置就是"当前要做的那个"。
 *
 * <p>比较前先 {@link #normalize 归一化}：真换行与字面的 {@code \n} 视为同一个、连续空白折成一个空格、
 * 去首尾空白。这样"从剪贴板抄进来的备注"与"玩家在编辑器里手改过的备注"只要内容一样就认得出是同一条。
 */
public final class ClipboardChecklist {

	private ClipboardChecklist() {
	}

	/** 清单最多画几行（用户要求：超出窗口跟着"当前"那条走，不整页铺满屏幕）。 */
	public static final int MAX_ROWS = 8;

	/** 一行在清单里的状态。 */
	public enum State {
		/** 已经录进程序了（备注对上了）。 */
		DONE,
		/** 下一条要录的就是它。 */
		CURRENT,
		/** 还排在后面。 */
		UPCOMING
	}

	/**
	 * 归一化：真换行 → 字面的 {@code \n}，连续空白 → 单个空格，去首尾。
	 *
	 * <p>为什么要碰换行：备注里的换行有两种来源 —— 剪贴板条目自带的**真换行**，
	 * 以及编辑器里被写成两个字面字符（方案 B）再还原回来的换行。两者其实是同一段文本，
	 * 不折叠就等于"同一行文字，在两种存法下匹配不上"。
	 */
	public static String normalize(String text) {
		if (text == null)
			return "";
		return text.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replace("\n", "\\n")
			.strip()
			.replaceAll("[ \t\u3000]+", " ");
	}

	/** 两条文本是不是"同一行"（见 {@link #normalize}）。 */
	public static boolean sameLine(String a, String b) {
		return normalize(a).equals(normalize(b));
	}

	/**
	 * 清单里<b>已经录进程序</b>的行数（= 下一个要录的是第几条）。
	 *
	 * <p>要比较的是"正在录入的那个任务"的条目备注：别的任务里有相同文字不算数 ——
	 * 录入只录进当前任务，清单的进度也只能看当前任务。
	 */
	public static int consumed(List<String> lines, @Nullable ArmProgramTask task) {
		if (lines == null || lines.isEmpty() || task == null)
			return 0;
		int cursor = 0;
		int done = 0;
		for (String line : lines) {
			String want = normalize(line);
			if (want.isEmpty())
				break; // 空行不该出现在这里（读取时已经去掉），保险起见当作中断
			int found = -1;
			for (int j = cursor; j < task.entries.size(); j++) {
				if (normalize(task.entries.get(j).note)
					.equals(want)) {
					found = j;
					break;
				}
			}
			if (found < 0)
				break; // 这一行还没被录进去 → 它就是"当前"
			cursor = found + 1;
			done++;
		}
		return done;
	}

	/**
	 * 下一条要录的操作应该带上的备注（= 当前那一行的原文）。
	 *
	 * <p>返回原样的一行（**不**归一化）：写进备注的就是玩家写在剪贴板上的那段文本。
	 * 清单已经录完（或副手没有剪贴板）时返回空串 —— 此时新条目不带备注，与旧行为一致。
	 */
	public static String nextNote(List<String> lines, @Nullable ArmProgramTask task) {
		if (lines == null)
			return "";
		int consumed = consumed(lines, task);
		return consumed < lines.size() ? lines.get(consumed) : "";
	}

	/** 第 {@code index} 行在 {@code consumed} 之下的状态。 */
	public static State state(int index, int consumed) {
		if (index < consumed)
			return State.DONE;
		return index == consumed ? State.CURRENT : State.UPCOMING;
	}

	/**
	 * 窗口第一行画清单里的第几条。
	 *
	 * <p>优先锚在"当前那一行的<b>上一条</b>"（{@code consumed - 1}）：玩家既能看见刚做完的那条
	 * （"我确实录进去了"），又能看见后面几条（"接下来还要做什么"）。两个边界要夹住：
	 * 不能是负数，也不能让窗口越过清单末尾（那样会画出一片空白）；
	 * 清单本身不超过一屏就从头画，全部录完则退到末尾一段（让玩家看到"就是这些，都做完了"）。
	 *
	 * <p>副作用值得写下来：清单远长于一屏（比如 30 行）而进度还在前几行时，
	 * 末尾那个夹取会赢 —— 此时窗口里"已完成"的行会多于一行，但"当前"一定在窗口里。
	 */
	public static int windowStart(int consumed, int size, int maxRows) {
		if (size <= maxRows)
			return 0;
		int lastPage = size - maxRows;
		if (consumed >= size)
			return lastPage;
		return Mth.clamp(consumed - 1, 0, lastPage);
	}

	/** 全部录完了吗（清单非空且一行不剩）。 */
	public static boolean allDone(List<String> lines, @Nullable ArmProgramTask task) {
		return lines != null && !lines.isEmpty() && consumed(lines, task) >= lines.size();
	}

}
