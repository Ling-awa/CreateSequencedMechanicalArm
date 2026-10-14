package io.github.Ling.create_sequenced_mechanical_arm.client;

import java.util.ArrayList;
import java.util.List;

import io.github.Ling.create_sequenced_mechanical_arm.program.ClipboardChecklist;
import io.github.Ling.create_sequenced_mechanical_arm.program.ClipboardChecklist.State;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

/**
 * 「副手剪贴板清单」HUD（PLAN.md §5.5，用户第二十七轮要求）：
 * 主手拿着录入模式的卡 + 副手拿着写了字的剪贴板时，在<b>屏幕左边</b>画一张半透明黑的清单，
 * 显示"已完成 / 当前 / 下一个"，录一条就划掉一条。
 *
 * <p>用户原话：<i>"左边，半透明黑色的底，像聊天框那样；显示已完成、当前、下一个"</i>，
 * 并明确"玩家不需要自己在上面手动勾" —— 勾由服务端按程序进度写（{@code ClipboardPlan.syncTicks}），
 * 这里只是把同一份进度<b>现算</b>出来画一遍（{@link ClipboardChecklist#consumed}）。
 *
 * <h2>几个刻意的取舍</h2>
 * <ul>
 *   <li><b>每帧不读卡、不读剪贴板</b>：状态由 {@code OperationTableHandler} 每 tick 推一次
 *       （与选择条 HUD 同一条路）—— 每帧解析一次 NBT 是白花的钱；</li>
 *   <li><b>最多 8 行、窗口跟着"当前"走</b>：清单可能有几十行，整页铺满会盖住半个屏幕。
 *       锚在"当前那一行的上一条"，所以玩家总能看到"刚做完的 + 接下来的"；</li>
 *   <li><b>开界面时不画</b>（{@code mc.screen != null}）与选择条一致 —— 编辑器和清单同时出现只会打架。</li>
 * </ul>
 *
 * <p>画法很朴素（{@code fill} 一行半透明黑 + 三个字形 + 文本），没有走 Create 的
 * {@code AllGuiTextures}：那套贴图是为"一张固定尺寸的面板"设计的，而这里行数随清单变，
 * 拉伸出来会变形；聊天框那种"一行一块底色"反而是原版本来就有的观感。
 */
public class ClipboardChecklistOverlay implements LayeredDraw.Layer {

	public static final ClipboardChecklistOverlay INSTANCE = new ClipboardChecklistOverlay();

	/** 距屏幕左边的边距。 */
	private static final int X = 4;
	/** 每行高度（用户要求：12px）。 */
	private static final int ROW_H = 12;
	/** 行内左右内边距。 */
	private static final int PAD = 3;
	/** 字形那一列的宽度。 */
	private static final int GLYPH_W = 10;
	/** 文本最多占的宽度（用户要求：150px），超出用省略号。 */
	private static final int TEXT_W = 150;
	/** 行底色：半透明黑（像聊天框）。 */
	private static final int ROW_BG = 0x80000000;

	private static final String GLYPH_DONE = "\u2714"; // ✔ 与 Create 剪贴板界面上那个对勾同一个码位
	private static final String GLYPH_CURRENT = "\u25B6"; // ▶
	private static final String GLYPH_UPCOMING = "\u25A1"; // □ 与 Create 剪贴板界面的空框同一个码位

	private static final int COLOR_DONE = 0xFF9E9E9E;
	private static final int COLOR_DONE_GLYPH = 0xFF31B25D; // Create 剪贴板里"已勾选"的绿
	private static final int COLOR_CURRENT = 0xFFFFFFFF;
	private static final int COLOR_CURRENT_GLYPH = 0xFFFFD966;
	private static final int COLOR_UPCOMING = 0xFFB0B0B0;
	private static final int COLOR_UPCOMING_GLYPH = 0xFF8D7F6B; // Create 剪贴板里空框的颜色

	/** 一行要画的全部内容（纯数据，{@link #selfCheck} 断言的就是它）。 */
	public record Row(String text, State state, String glyph, int glyphColor, int textColor) {
	}

	private List<String> lines = List.of();
	private int consumed;
	private boolean visible;

	private ClipboardChecklistOverlay() {
	}

	/** 每客户端 tick 推一次（由 {@code OperationTableHandler} 驱动）。 */
	public void tick(boolean visible, List<String> lines, int consumed) {
		this.visible = visible && lines != null && !lines.isEmpty();
		this.lines = lines == null ? List.of() : lines;
		this.consumed = consumed;
	}

	public void hide() {
		visible = false;
		lines = List.of();
		consumed = 0;
	}

	/**
	 * 这一帧要画的那些行（窗口内的）。
	 *
	 * <p>抽成纯方法是为了让自检能断言"画出来的到底是哪几行、什么状态" ——
	 * 渲染本身在自检里跑不了（要有 GL 上下文），但"画什么"是可以说清楚的。
	 */
	public List<Row> buildRows() {
		if (!visible || lines.isEmpty())
			return List.of();
		int start = ClipboardChecklist.windowStart(consumed, lines.size(), ClipboardChecklist.MAX_ROWS);
		int end = Math.min(lines.size(), start + ClipboardChecklist.MAX_ROWS);
		List<Row> rows = new ArrayList<>(end - start);
		for (int i = start; i < end; i++)
			rows.add(row(i));
		return rows;
	}

	private Row row(int index) {
		String text = lines.get(index);
		return switch (ClipboardChecklist.state(index, consumed)) {
			case DONE -> new Row(text, State.DONE, GLYPH_DONE, COLOR_DONE_GLYPH, COLOR_DONE);
			case CURRENT -> new Row(text, State.CURRENT, GLYPH_CURRENT, COLOR_CURRENT_GLYPH, COLOR_CURRENT);
			case UPCOMING -> new Row(text, State.UPCOMING, GLYPH_UPCOMING, COLOR_UPCOMING_GLYPH, COLOR_UPCOMING);
		};
	}

	/** 背景块要画多宽：贴着最宽的那一行（含字形与内边距）。 */
	private int boxWidth(Font font, List<Row> rows) {
		int widest = 0;
		for (Row row : rows)
			widest = Math.max(widest, Math.min(TEXT_W, font.width(row.text())));
		return PAD * 2 + GLYPH_W + widest;
	}

	@Override
	public void render(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		// 开界面时不画（与选择条一致）：编辑器/背包开着还挂着清单只会挡视线
		if (!visible || lines.isEmpty() || mc.options.hideGui || mc.screen != null)
			return;
		Font font = mc.font;
		List<Row> rows = buildRows();
		if (rows.isEmpty())
			return;

		int box = boxWidth(font, rows);
		int y = X;
		for (Row row : rows) {
			graphics.fill(X, y, X + box, y + ROW_H - 1, ROW_BG);
			graphics.drawString(font, row.glyph(), X + PAD, y + 3, row.glyphColor(), false);
			graphics.drawString(font, clip(font, row.text()), X + PAD + GLYPH_W, y + 3, row.textColor(), false);
			y += ROW_H;
		}
	}

	/** 太长的行截到 {@link #TEXT_W} 并补省略号（清单是给人扫一眼的，不是给人读全文的）。 */
	private static String clip(Font font, String text) {
		if (font.width(text) <= TEXT_W)
			return text;
		return font.plainSubstrByWidth(text, TEXT_W - font.width("…")) + "…";
	}

	/**
	 * 客户端自检：HUD 的**显示模型**必须与投影一致。
	 *
	 * <p>渲染结果本身断言不了（要 GL），但"画哪几行、什么状态、什么字形"是纯数据。
	 * 这里推一份 10 行的清单进去（比一屏长，窗口一定会动），检查四件事：
	 * ① 行数不超过 {@link ClipboardChecklist#MAX_ROWS}；② "当前"那一行一定在窗口里、且只有一行，
	 * 它就是清单里还没做的那一条；③ 它之前的都是已完成、之后的都是未完成；④ 三种字形互不相同。
	 * 再补两条边界：全部录完（不再有"当前"，整片已完成、窗口退到末尾）与隐藏时不画。
	 */
	public String selfCheck() {
		List<String> ten = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
			ten.add("第 " + i + " 条");

		// ① 还没开始录：第一条就是"当前"
		tick(true, ten, 0);
		List<Row> fresh = buildRows();
		if (fresh.size() != ClipboardChecklist.MAX_ROWS)
			return "清单行数应当是 " + ClipboardChecklist.MAX_ROWS + "，实际 " + fresh.size();
		if (fresh.get(0)
			.state() != State.CURRENT
			|| !fresh.get(0)
				.text()
				.equals("第 1 条"))
			return "还没录任何一条时，清单第一条应当是「当前」，实际 " + fresh.get(0)
				.state() + " " + fresh.get(0)
					.text();

		// ② 录到两条：当前必须是第 3 条，之前的都已完成、之后的都没完成
		tick(true, ten, 2);
		List<Row> rows = buildRows();
		String problem = windowProblem("录到两条", ten, 2, rows);
		if (problem != null)
			return problem;
		// 三种状态的字形必须互不相同（按**状态**取，不能按行号取：窗口会滑动，
		// 第一行不一定是"已完成"—— 第一版就是那么写的，当场被这条自检报红）
		String doneGlyph = glyphOf(rows, State.DONE);
		String currentGlyph = glyphOf(rows, State.CURRENT);
		String upcomingGlyph = glyphOf(rows, State.UPCOMING);
		if (doneGlyph.isEmpty() || currentGlyph.isEmpty() || upcomingGlyph.isEmpty()
			|| doneGlyph.equals(currentGlyph) || currentGlyph.equals(upcomingGlyph)
			|| doneGlyph.equals(upcomingGlyph))
			return "三种状态的字形有重复，玩家分不出哪条在做（已完成=" + doneGlyph + " 当前=" + currentGlyph + " 未完成="
				+ upcomingGlyph + "）";

		// ③ 全部录完：没有"当前"，最后 8 行全是已完成
		tick(true, ten, 10);
		List<Row> done = buildRows();
		if (done.size() != ClipboardChecklist.MAX_ROWS || done.stream()
			.anyMatch(r -> r.state() != State.DONE))
			return "全部录完之后清单没有整片变成已完成（" + done.stream()
				.map(Row::state)
				.toList() + "）";
		if (!done.get(0)
			.text()
			.equals("第 3 条"))
			return "全部录完之后窗口没有退到末尾：" + done.get(0)
				.text();

		// ④ 隐藏：不录的时候一行都不该画
		hide();
		if (!buildRows().isEmpty())
			return "隐藏之后还在画清单";
		tick(false, ten, 3);
		if (!buildRows().isEmpty())
			return "没在录入模式却画了清单";

		// ⑤ 短清单（不超过一屏）应当整条都画出来
		tick(true, ten.subList(0, 3), 1);
		if (buildRows().size() != 3)
			return "只有 3 行的清单应当画 3 行，实际 " + buildRows().size();
		return "OK(窗口=" + ClipboardChecklist.MAX_ROWS + "行 锚点=当前上一条 字形=3种 短清单=全画)";
	}

	/** 窗口里某个状态用的字形（该状态没出现时返回空串）。 */
	private static String glyphOf(List<Row> rows, State state) {
		for (Row row : rows)
			if (row.state() == state)
				return row.glyph();
		return "";
	}

	/**
	 * 窗口的通用判据：行数不超上限、"当前"唯一且在窗口里、它之前全已完成、之后全未完成，
	 * 而且那个"当前"就是清单里还没做的第一条。
	 */
	private static String windowProblem(String which, List<String> lines, int consumed, List<Row> rows) {		if (rows.size() > ClipboardChecklist.MAX_ROWS)
			return which + "：画了 " + rows.size() + " 行，超过上限 " + ClipboardChecklist.MAX_ROWS;
		int current = -1;
		for (int i = 0; i < rows.size(); i++) {
			State state = rows.get(i)
				.state();
			if (state == State.CURRENT) {
				if (current >= 0)
					return which + "：窗口里出现了两个「当前」";
				current = i;
				continue;
			}
			if (current < 0 && state != State.DONE)
				return which + "：「当前」之前出现了未完成的行（第 " + (i + 1) + " 行）";
			if (current >= 0 && state != State.UPCOMING)
				return which + "：「当前」之后出现了已完成的行（第 " + (i + 1) + " 行）";
		}
		if (current < 0)
			return which + "：窗口里没有「当前」那一行（进度 " + consumed + "/" + lines.size() + "）";
		int index = lines.indexOf(rows.get(current)
			.text());
		if (index != consumed)
			return which + "：窗口里的「当前」是清单第 " + (index + 1) + " 条，应当是第 " + (consumed + 1) + " 条";
		return null;
	}

}
