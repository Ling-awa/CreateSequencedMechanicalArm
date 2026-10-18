package io.github.Ling.create_sequenced_mechanical_arm.program.script;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SignalMode;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.WaitUnit;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * 脚本文本 → {@link ArmProgram}（PLAN.md §12.1 / §12.3 / §12.3a）。
 *
 * <p><b>为什么自己写词法器，而不是拿 Brigadier 的 {@code StringReader}</b>：块注释要能**跨行**
 * （§12.3a 细则 1：{@code /*} 与注释终止符之间每一个字符原样进备注，含换行、空格、缩进），
 * 而且 {@code /*} <b>只在引号外</b>才算注释开头（细则 2）—— Brigadier 的 reader 是单行的，做不到这两件事。
 *
 * <p><b>与反编译器是一对</b>（§12.4 的两条不变量）：本类负责"吃得下多少种写法"
 * （两种备注写法、时间带不带单位、跳转带不带 {@code goto}、SNBT / 简写 / {@code #RRGGBB} 三种物品写法），
 * 规范形式只有 {@code ScriptDecompiler} 一个实现。
 *
 * <p>语言面（也是玩家可见错误的用词口径）：
 * <pre>
 *   &ast;/文件头注释 = 程序备注&ast;/
 *   task 名字 icon=minecraft:diamond
 *     take     10 64 -3 count=3 delay=10            &ast;/ 放铲子 &ast;/
 *     deposit  0 0 0 count=3 countmode=exact        &ast;/ 目标必须吃下恰好 3 个 &ast;/
 *     unload   0 0 2 repeat=until_empty amount=max
 *     attack   0 0 0 face=up ok=goto 清理 fail=skip timeout=5s
 *     wait     5s
 *     signal   recv on freq=iron_ingot + copper_ingot
 *     signal   recv on range=16 freq=iron_ingot + copper_ingot   &ast;/ 只收 16 格内的来源（含边界） &ast;/
 *     signal   send_on place=0t freq=iron_ingot + copper_ingot
 *     signal   send_off freq=iron_ingot + copper_ingot   &ast;/ 中断：立刻清除，**没有** place= / range= &ast;/
 *     call     "搬一趟" repeat=3 shift=1,0,0
 *     break
 *     throw    "没材料"
 * </pre>
 *
 * <p>⚠️ <b>P1 坐标模型统一</b>（PLAN.md §19）：条目坐标<b>不带任何空间记号</b>，直接写三个整数，
 * 语义是"相对**执行这条程序的臂**"。{@code @abs} / {@code @rel} 两个记号已删除（旧脚本不迁移）。
 *
 * <p>⚠️ <b>{@code @origin} 头也已删除</b>（用户第四十轮）：原点只剩"录制时的参考系"与"渲染蓝框"
 * 两个用途，而条目的 offset 永远相对执行它的那条臂 ⇒ 它不属于这份程序，现在作为**卡级**属性存在
 * （{@code CardProgramStorage.readOrigin}）。解析到这一行<b>硬报错</b>并说清"存在卡上"，
 * 不静默忽略 —— 忽略会让"导出的文本里还写着它"一路通过，而那份文本换个卡导入时基准其实已经变了。
 * 这和 {@code @abs} / {@code @rel} 报的是同一个道理：坐标的参考系只有一种，脚本里出现的"第二种"必须当场说破。
 *
 * <p>⚠️ 一处刻意的取舍（§12.3a 第 6 条）：条目行尾的块注释与紧跟其后的独占行块注释两者**合并**成一个
 * {@code note}（换行连接），不报错 —— 玩家想"再补一句注释"是很自然的事；三项合并（第三块）才报错。
 * 以前置写法写在条目**上面**的注释会按 §12.3a 归到**上一条**（用户明确要这个语义）。
 */
public final class ScriptParser {

	/** 坐标的合法范围。世界坐标与相对偏移都远在此内，超出必然是写错了。 */
	public static final int COORD_LIMIT = 30_000_000;
	/**
	 * <b>已删除</b>的程序头记号：{@code @origin x y z}（PLAN.md §19.2 第 2 条的老写法）。
	 *
	 * <p>用户第四十轮把原点搬到了卡上（{@code CardProgramStorage} 的 {@code ArmProgramOrigin} 键），
	 * 文本里不再有这一行。常量留着有两个用处：① 解析到它时给出**指名道姓**的报错；
	 * ② 自检断言"反编译出来的文本里不含它"（写头那条代码一旦被加回来立刻红）。
	 * 定义成常量而不是散落的字面量：解析与自检都要写它，两处各打一遍就会漂移（决策 68/85）。
	 */
	public static final String HEADER = "@origin";
	/**
	 * 个数的范围（§12.3：{@code count 1..64}）—— <b>取值范围只有这一处来源</b>：
	 * 取出 / 放入共用它，编辑器控件（{@code ArmProgramScreen}）与自检都从这里取。
	 */
	public static final int COUNT_MIN = 1;
	public static final int COUNT_MAX = 64;
	/** 时间**数值**上限：与编辑器里的等待 / 持续控件一致（0–120 × t/s/m）。 */
	public static final int TIME_AMOUNT_MAX = 120;
	/** delay 的上限。运行时它是"前摇 tick 数"，一天（72000t）已经远超任何合理用法。 */
	public static final int DELAY_MAX = 72_000;

	/** 备注的来源：块注释 / {@code note="…"} 保底形式 / 都没有。 */
	private enum NoteSource {
		NONE("", false),
		BLOCK("/*", true),
		QUOTED("note=\"", true);

		final String display;
		final boolean quoted;

		NoteSource(String display, boolean quoted) {
			this.display = display;
			this.quoted = quoted;
		}
	}

	private final HolderLookup.Provider registries;
	private final List<ScriptError> errors = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();

	private ScriptParser(HolderLookup.Provider registries) {
		this.registries = registries;
	}

	/**
	 * 编译一份脚本。<b>永远返回一个程序</b>（有错时是"已经解析到哪算哪"的那份，可能是空程序）——
	 * 调用方**必须先看** {@link ScriptResult#hasErrors()}（命令入口与自检都照这个顺序写）。
	 *
	 * <p>⚠️ 有错时值**不是 null** 是刻意的（§17.10）：模型允许编译器不认的状态（最典型的是**悬空的任务名**
	 * —— GUI 里删掉 / 改名了被「调用」的任务，这在模型里合法、运行时会走失败出口），而盘上只有文本。
	 * 如果"一有错就丢值"，这种程序读回来就变成**空程序** —— 玩家的程序当场没了。
	 * 所以：错误照报，**内容留着**。
	 *
	 * @param text       源文本（UTF-8 无 BOM、LF/CRLF 都吃）
	 * @param registries 解析物品 / 组件用的注册表（自检里有现成的 {@code server.registryAccess()}）
	 */
	public static ScriptResult<ArmProgram> parse(String text, HolderLookup.Provider registries) {
		ScriptParser parser = new ScriptParser(registries);
		ArmProgram program = parser.parseFile(text == null ? "" : text);
		if (parser.errors.isEmpty())
			return ScriptResult.ok(program, parser.warnings);
		return ScriptResult.withErrors(program, parser.errors, parser.warnings);
	}

	// ————————————————————————— 词法 —————————————————————————

	/** 一个记号。{@code col} 是行内字符下标（用来判"注释块在命令前还是后"）。 */
	private record Token(String text, int line, int col, boolean str) {
	}

	/** 一段块注释：{@code &ast;/} 与注释终止符之间的**原文** + 起始位置。 */
	private record CommentBlock(String body, int line, int col) {
	}

	/** 一条逻辑行：同一行上的记号与注释块，都按行内列号排好序。 */
	private record Line(int number, List<Token> tokens, List<CommentBlock> blocks) {
		boolean containsCommand() {
			return !tokens.isEmpty();
		}
	}

	private record Scan(List<Token> tokens, List<CommentBlock> comments) {
	}

	/** 词法期就判死的错误（未闭合的注释 / 字符串）。 */
	private record ScanError(int line, String message) {
	}

	private static Scan scan(String src, List<ScanError> out) {
		List<Token> tokens = new ArrayList<>();
		List<CommentBlock> comments = new ArrayList<>();
		// `=` 与源码位置一一对应，好让"括号开头的原子"能带上它前面的 `key=`（见下）
		java.util.Map<Integer, Token> equals = new java.util.HashMap<>();
		int line = 1;
		int col = 0;
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (c == '\r') { // CRLF 也吃：只把 '\n' 当换行，'\r' 丢掉
				i++;
				continue;
			}
			if (c == '\n') {
				line++;
				col = 0;
				i++;
				continue;
			}
			if (c == ' ' || c == '\t') {
				col++;
				i++;
				continue;
			}
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				// ★ 块注释**只在引号外**才算注释开头（§12.3a 细则 2）：这个分支天然满足 ——
				//   引号里的内容由下面那个字符串分支整个吃掉，SNBT 里的 `/*` 根本走不到这里。
				int startLine = line;
				int startCol = col;
				int bodyStart = i + 2;
				int cursor = bodyStart;
				int end = -1;
				int bodyLine = line;
				while (cursor + 1 < n) {
					if (src.charAt(cursor) == '*' && src.charAt(cursor + 1) == '/') {
						end = cursor;
						break;
					}
					if (src.charAt(cursor) == '\n')
						bodyLine++;
					cursor++;
				}
				if (end < 0) {
					out.add(new ScanError(startLine, "块注释没有闭合（少了 */）"));
					return new Scan(tokens, comments);
				}
				comments.add(new CommentBlock(src.substring(bodyStart, end), startLine, startCol));
				i = end + 2;
				col = startCol + (end + 2 - bodyStart) + 2; // 推到 `*/` 之后，同一行上后面的记号才排得对
				line = bodyLine;
				continue;
			}
			// 字符串记号有两种来路：独占一个引号串（任务名 / 抛错信息），以及 `key="…"` 的后半截
			boolean quotedValue = c == '"';
			if (quotedValue || src.startsWith("=\"", i)) {
				int startLine = line;
				int startCol = quotedValue ? col : col + 1;
				int quote = quotedValue ? i : i + 1;
				StringBuilder sb = new StringBuilder();
				int cursor = quote + 1;
				boolean closed = false;
				while (cursor < n) {
					char sc = src.charAt(cursor);
					if (sc == '\n')
						break;
					if (sc == '\\' && cursor + 1 < n) {
						sb.append(unescape(src.charAt(cursor + 1)));
						cursor += 2;
					} else if (sc == '"') {
						closed = true;
						cursor++;
						break;
					} else {
						sb.append(sc);
						cursor++;
					}
				}
				if (!closed) {
					// 未闭合的字符串：报到本行、**只放过这一行**（一个手抖的引号不该把后面所有行的错误一起吞掉）
					out.add(new ScanError(startLine, "字符串没有闭合（少了 \"）"));
					int eol = src.indexOf('\n', Math.max(cursor, quote + 1));
					if (eol < 0)
						return new Scan(tokens, comments);
					line++;
					col = 0;
					i = eol + 1;
					continue;
				}
				// `note="…"`：把 `=` 与引号串拼成**一个**记号，解析器按第一个 `=` 切开 ——
				// 否则 `note=` 与 `abc` 会变成两个记号，"引号串是一个值"这件事就丢了。
				Token quotedToken = new Token(quotedValue ? sb.toString() : "=" + sb, startLine, startCol, true);
				// `note="…"`：**裸扫描会先把 `note=` 吃成一个记号**，走到 `"` 时已经不在 `=` 上了，
				// 所以这里必须像括号分支那样把"上一个以 `=` 结尾的记号"拼过来 —— 否则 `note=` 与引号串
				// 是两个记号，"引号串是一个值"这件事就丢了（实测：`note="…"` 一律报"条目里不认识这一段"）。
				if (quotedValue && !tokens.isEmpty()) {
					Token previous = tokens.get(tokens.size() - 1);
					if (previous.text()
						.endsWith("=")) {
						tokens.remove(previous);
						quotedToken = new Token(previous.text() + sb, previous.line(), previous.col(), true);
					}
				}
				tokens.add(quotedToken);
				equals.put(i, quotedToken);
				col += cursor - i;
				i = cursor;
				continue;
			}
			// ★ 括号开头的原子：`{…}`（SNBT 物品）/ `[…]`（数组）/ `(…)` —— 整段当一个记号读完。
			//   为什么必须这样：SNBT 里**键带引号、字符串也带引号**，而下面那段"读到空白或引号为止"
			//   会在第一个 `"` 处截断 ⇒ `{"count":1,"id":"minecraft:iron_ingot"}` 被切成 `{count:` 这种
			//   半个记号（编译期报"Expected '}'"）。括号配对扫描天然就把引号里的 `{`/`}`/`/*` 当字符看，
			//   所以 §12.3a 细则 2（引号里的 `/*` 不是注释）在这里也成立。
			if (c == '{' || c == '[' || c == '(') {
				int end = balancedEnd(src, i);
				if (end < 0) {
					out.add(new ScanError(line, "括号没有闭合（少了 " + closeOf(c) + "）"));
					return new Scan(tokens, comments);
				}
				String text = src.substring(i, end + 1);
				// `icon={…}` / `freq={…}` 这种要带上它前面的 `key=`（值里带引号 ⇒ 必须整体当一个记号）。
				// 两种来源：`=` 的**前半截**（`icon` 与 `=` 拼成 `icon=`，见字符串分支）与**整个 key=** 记号
				// （先扫出 `freq=`、再遇到 `{`）。判据是"上一个记号以 = 结尾"——它必然是紧挨着的那个 key。
				Token keyToken = equals.get(i - 1);
				if (keyToken == null && !tokens.isEmpty()) {
					Token previous = tokens.get(tokens.size() - 1);
					if (previous.text()
						.endsWith("="))
						keyToken = previous;
				}
				tokens.add(keyToken == null ? new Token(text, line, col, false)
					: new Token(keyToken.text() + text, keyToken.line(), keyToken.col(), false));
				if (keyToken != null)
					tokens.remove(keyToken);
				col += end + 1 - i;
				i = end + 1;
				continue;
			}
			int start = i;
			int startCol = col;
			while (i < n) {
				char wc = src.charAt(i);
				if (wc == ' ' || wc == '\t' || wc == '\n' || wc == '\r' || wc == '"')
					break;
				// ★ 粘在 key 后面的括号也必须在这里**断开**：`icon={"count":1,…}` 的 `{` 不是这一段的字符。
				//   不断开的后果（实测）：`icon={` 被整段吃掉 ⇒ 后面的 `"count"` 又被引号分支当字符串拿走
				//   ⇒ SNBT 碎成 `icon={` / `"count"` / `:1,` / `"id"` …，报"Expected '}'"。
				//   而 `freq={…} + {…}` 的第二个因为前面有空格、`{` 成了记号首字符，走上面的括号分支就正常 ——
				//   这正是"第一个永远失败、第二个正常"这个症状的来源。
				if (wc == '{' || wc == '[' || wc == '(')
					break;
				if (wc == '/' && i + 1 < n && src.charAt(i + 1) == '*')
					break;
				i++;
			}
			if (i == start) {
				i++; // 理论上到不了；真到了就前进一个字符，绝不死循环
				col++;
				continue;
			}
			tokens.add(new Token(src.substring(start, i), line, startCol, false));
			col += i - start;
		}
		return new Scan(tokens, comments);
	}

	/**
	 * 从 {@code src.charAt(start)}（一定是 {@code &#123;} / {@code [} / {@code (}）扫到与它配对的收尾括号，
	 * 返回那个下标的；没配对返回 {@code -1}。**引号里的括号与注释符一律当普通字符**。
	 */
	private static int balancedEnd(String src, int start) {
		char open = src.charAt(start);
		char close = closeOf(open);
		int depth = 0;
		boolean inString = false;
		for (int i = start; i < src.length(); i++) {
			char c = src.charAt(i);
			if (inString) {
				if (c == '\\')
					i++;
				else if (c == '"')
					inString = false;
				continue;
			}
			if (c == '"')
				inString = true;
			else if (c == '\n')
				return -1; // 括号原子不许跨行：文本面是"一条条目一行"
			else if (c == open)
				depth++;
			else if (c == close && --depth == 0)
				return i;
		}
		return -1;
	}

	private static char closeOf(char open) {
		return switch (open) {
			case '{' -> '}';
			case '[' -> ']';
			default -> ')';
		};
	}

	/** {@code icon={…}} / {@code freq={…}}：把"值带引号"的原子切成 key 与 value。 */
	private static boolean isSnbtKey(String text) {
		int brace = text.indexOf('{');
		return brace > 0 && brace == text.indexOf('=') + 1;
	}

	/**
	 * {@code {…} + {…}}（两个 SNBT 物品被词法器连成一个原子）⇒ 拆成两段；不是这个形状返回 {@code null}。
	 * 分隔符只认**括号深度为 0 且不在引号里**的 {@code +}。
	 */
	@Nullable
	private static List<String> splitSnbtPair(String text) {
		if (!text.startsWith("{"))
			return null;
		int depth = 0;
		boolean inString = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				if (c == '\\')
					i++;
				else if (c == '"')
					inString = false;
				continue;
			}
			switch (c) {
				case '"' -> inString = true;
				case '{', '[', '(' -> depth++;
				case '}', ']', ')' -> depth--;
				case '+' -> {
					if (depth == 0)
						return List.of(text.substring(0, i)
							.trim(), text.substring(i + 1)
								.trim());
				}
				default -> {
				}
			}
		}
		return null;
	}

	/**
	 * {@code freq={…}} 这种"key 与 SNBT 值拼在同一个记号里"的**键名**：`=` 之前的那段。
	 *
	 * <p>⚠️ 原来写的是"取 `{` 之前的那段"，于是键名**带着等号**返回（`freq=`）⇒ 属性派发一个都认不出来，
	 * 整族 {@code freq=} / {@code icon=} 全报"不认识的属性：freq="。等号必须切掉。
	 */
	private static String snbtKeyOf(String text) {
		return text.substring(0, text.indexOf('='));
	}

	private static String snbtValueOf(String text) {
		return text.substring(text.indexOf('{'));
	}

	private static char unescape(char escaped) {
		return switch (escaped) {
			case 'n' -> '\n';
			case 't' -> '\t';
			case '"' -> '"';
			case '\\' -> '\\';
			case '/' -> '/';
			default -> escaped;
		};
	}

	private static List<Line> splitLines(Scan scan) {
		java.util.Map<Integer, List<Token>> tokenLines = new java.util.LinkedHashMap<>();
		for (Token token : scan.tokens())
			tokenLines.computeIfAbsent(token.line(), k -> new ArrayList<>())
				.add(token);
		java.util.Map<Integer, List<CommentBlock>> commentLines = new java.util.LinkedHashMap<>();
		for (CommentBlock block : scan.comments())
			commentLines.computeIfAbsent(block.line(), k -> new ArrayList<>())
				.add(block);

		Set<Integer> numbers = new LinkedHashSet<>();
		numbers.addAll(tokenLines.keySet());
		numbers.addAll(commentLines.keySet());
		List<Integer> sorted = new ArrayList<>(numbers);
		sorted.sort(Comparator.naturalOrder());

		List<Line> out = new ArrayList<>();
		for (int number : sorted) {
			List<Token> tokens = new ArrayList<>(tokenLines.getOrDefault(number, List.of()));
			List<CommentBlock> blocks = new ArrayList<>(commentLines.getOrDefault(number, List.of()));
			blocks.sort(Comparator.comparingInt(CommentBlock::col));
			out.add(new Line(number, tokens, blocks));
		}
		return out;
	}

	// ————————————————————————— 文件级 —————————————————————————

	private ArmProgram parseFile(String src) {
		ArmProgram program = new ArmProgram();
		List<ScanError> scanErrors = new ArrayList<>();
		Scan scan = scan(src, scanErrors);
		for (ScanError scanError : scanErrors)
			error(scanError.line(), scanError.message());
		if (!scanErrors.isEmpty()) {
			// 词法都过不去（注释/字符串没闭合）：剩下的文本已经不可信，直接收工
			return program;
		}

		Ctx ctx = new Ctx(program);
		for (Line line : splitLines(scan)) {
			// 「这一行上有命令」是判"注释块在命令前还是后"的唯一依据：
			//   行尾的块（列号在命令之后）= 行尾备注 ⇒ 归**这一条 / 这个任务**；
			//   独占一行的块 = **紧跟其后**的写法（§12.3a 表格第二行）⇒ 归**上一条**。
			//   文件最前面（第一条 task 之前）没得可归 ⇒ 程序备注。
			// ★ 不做"空行之后就算下一条的前置注释"（D 阶段曾这么实现、主代理 87 裁定去掉）：那等于让
			//   **空白行改变语义**，是个在代码编辑器里看不出来的隐藏规则；规范只留一条规则
			//   （"归上面最近的那个"），想给某条写说明就写在**它后面**。
			if (line.containsCommand()) {
				int commandCol = Integer.MAX_VALUE;
				for (Token token : line.tokens())
					commandCol = Math.min(commandCol, token.col());
				for (CommentBlock block : line.blocks()) {
					if (block.col() < commandCol)
						attachComment(ctx, block);
				}
				handleLine(ctx, line.tokens(), line.number());
				for (CommentBlock block : line.blocks()) {
					if (block.col() > commandCol)
						attachComment(ctx, block);
				}
				continue;
			}
			// 这一行只有注释块：一律归"上面最近的那个拥有备注的东西"（§12.3a 唯一那条规则）。
			// 同一个归属上的第二块由 mergeNote 用换行并进去（第 6 条）；第三块才报错。
			for (CommentBlock block : line.blocks())
				attachComment(ctx, block);
		}

		if (program.tasks.isEmpty())
			error(1, "这个文件里一个 task 都没有");
		validateTargets(program, ctx);
		return program;
	}

	/** 解析现场。 */
	private static final class Ctx {
		/** 备注的归属：程序 / 任务 / 条目 —— 就是 §12.3a 表格的三行。 */
		enum Owner {
			PROGRAM, TASK, ENTRY
		}

		final ArmProgram program;
		@Nullable
		ArmProgramTask task;
		@Nullable
		ArmProgramEntry entry;
		Owner owner = Owner.PROGRAM;
		/** 每个归属对象上已经见到几段备注（第 2 段换成行连接，第 3 段报错 —— §12.3a 第 6 条）。 */
		int programNoteBlocks;
		int taskNoteBlocks;
		int entryNoteBlocks;
		/** 每条条目写在源文件的哪一行（跳转落点的校验要用它报错）。用 IdentityHashMap：条目没实现 equals。 */
		final java.util.Map<ArmProgramEntry, Integer> targetLines = new java.util.IdentityHashMap<>();

		/**
		 * 备注块计数归零的**判据对象**：归属换了人（换条目 / 换任务 / 回程序）就要重新数。
		 *
		 * <p>⚠️ 不归零的后果（实测）：{@code entryNoteBlocks} 这类计数是**按归属种类**各一份的，
		 * 第一条条目上的注释把它加到 1 之后，**第二条**条目的第一块就落进 {@code blocks == 1} 的分支，
		 * 备注被拼成 {@code "" + "\n" + 内容} —— 凭空多一个开头的换行。预设 {@code take_or} 的往返
		 * 就是这么坏的（日志：{@code [ 否则取 B ] → [ \n否则取 B ]}）。
		 */
		Object noteCountOwner;

		Ctx(ArmProgram program) {
			this.program = program;
		}
	}

	private void handleLine(Ctx ctx, List<Token> tokens, int line) {
		Token head = tokens.get(0);
		if (head.str()) {
			error(line, "这一行以字符串开头（想写备注请用 /* */ 或 note=\"…\"）");
			return;
		}
		String keyword = head.text();
		if (keyword.startsWith("#")) {
			// §12.3a 细则 5：只保留 `/* */` 一种注释写法（草案里的 `#` 已去掉）
			error(line, "`#` 不再是注释写法 —— 请用 /* */（只保留一种注释语法）");
			return;
		}
		// 用户第四十轮：原点从程序里搬到了**卡上**，`.sma` 文本不再有 `@origin` 这一行。
		// 为什么必须**硬报错**而不是静默跳过：静默跳过会让"文本里还写着它"一路通过 ——
		// 玩家以为参考点跟着文本走了，其实它留在卡上（换张卡导入基准就变了），
		// 而"录制时减的是哪个坐标"完全看不出来。宁可现在就让他删掉这一行。
		if (keyword.equalsIgnoreCase(HEADER)) {
			error(head.line(), HEADER + " 不再写在脚本里：原点不属于程序"
				+ "（条目坐标一律相对执行它的那条臂），它现在只作为参考值存在**卡上**"
				+ " —— 对着方块按中键就能设，请删掉这一行再导入");
			return;
		}
		if ("task".equals(keyword)) {
			parseTaskHeader(ctx, tokens, line);
			return;
		}
		if (ctx.task == null) {
			error(line, "在第一个 task 之前出现了条目：" + keyword);
			return;
		}
		parseEntry(ctx, tokens, line);
	}

	// ————————————————————————— 注释 / 备注的归属 —————————————————————————

	/**
	 * 把一段块注释挂到"它上面最近的那个拥有备注的东西"上（§12.3a）。
	 *
	 * <p>块注释与 {@code note="…"} 共用这一条路径 —— 两者都只是"给同一个字段的一个写法"，
	 * 判据写两份必然漂移（决策 68/85）。
	 */
	private void attachComment(Ctx ctx, CommentBlock block) {
		registerNote(ctx, block.body(), block.line(), NoteSource.BLOCK);
	}

	/**
	 * 备注的统一上限检查：**超了报错带行号，绝不静默截断**（§12.3a 细则 4）。
	 *
	 * <p>为什么不能像 {@code clipNote} 那样截断：截断会直接破坏不变量 #1
	 * （{@code compile(decompile(p)) ≡ p} 的备注必须逐字符相等）。
	 *
	 * <p><b>两块注释 = 一个备注</b>（§12.3a 第 6 条，主代理 87②）：同一个归属对象上出现行尾一块 +
	 * 紧跟的独占行一块 ⇒ 用换行连接成**一个** {@code note}，**不报错** —— 备注本来就只有"一个字段"，
	 * 玩家想"再补一句注释"是很自然的事。三块才报错（再多就不是"补一句"，而是把两个东西写混了）。
	 * 合并口径只有这一处：{@code note="…"} 保底写法与块注释走同一条路径。
	 */
	private void registerNote(Ctx ctx, String note, int line, NoteSource source) {
		if (note.length() > ArmProgramEntry.NOTE_MAX) {
			error(line, "备注 " + note.length() + " 字，超过上限 " + ArmProgramEntry.NOTE_MAX + " 字（不截断）");
			return;
		}
		// 归属换了人 ⇒ 三个计数一起归零（"同一个对象上见过几块"才是要数的东西，见 Ctx#noteCountOwner）
		Object owner = switch (ctx.owner) {
			case ENTRY -> ctx.entry;
			case TASK -> ctx.task;
			case PROGRAM -> ctx.program;
		};
		if (ctx.noteCountOwner != owner) {
			ctx.noteCountOwner = owner;
			ctx.programNoteBlocks = 0;
			ctx.taskNoteBlocks = 0;
			ctx.entryNoteBlocks = 0;
		}
		switch (ctx.owner) {
			case ENTRY -> {
				ArmProgramEntry entry = ctx.entry;
				if (entry == null)
					return;
				entry.note = mergeNote(ctx, entry.note, note, line, "这条条目");
			}
			case TASK -> {
				ArmProgramTask task = ctx.task;
				if (task == null)
					return;
				task.note = mergeNote(ctx, task.note, note, line, "这个任务");
			}
			case PROGRAM -> ctx.program.note = mergeNote(ctx, ctx.program.note, note, line, "程序");
		}
	}

	/**
	 * 把新来的一段备注并进已有的那段（§12.3a 第 6 条）：第一段直接占位，第二段换行连接，
	 * 第三段起报错。计数放在 {@link Ctx} 上（按归属各一份），因为"两块能合并"是**块数**的规则，
	 * 不是"字段非空"的规则 —— 用字段值反推块数会把"第一段恰好是空串"算错。
	 */
	private String mergeNote(Ctx ctx, String existing, String added, int line, String what) {
		int blocks = switch (ctx.owner) {
			case ENTRY -> ctx.entryNoteBlocks++;
			case TASK -> ctx.taskNoteBlocks++;
			case PROGRAM -> ctx.programNoteBlocks++;
		};
		if (blocks == 0)
			return added;
		if (blocks == 1)
			return existing + "\n" + added;
		error(line, what + "已经有备注了（块注释与 note=\"…\" 各只能有一处，两块以上合不起来）");
		return existing;
	}

	// ————————————————————————— task 头 —————————————————————————

	private void parseTaskHeader(Ctx ctx, List<Token> tokens, int line) {
		if (tokens.size() < 2) {
			error(line, "task 后面要跟任务名");
			return;
		}
		Token nameToken = tokens.get(1);
		String name = nameToken.text();
		if (!nameToken.str()) {
			String problem = plainNameProblem(name);
			if (problem != null) {
				error(line, "任务名「" + name + "」" + problem);
				return;
			}
		}
		if (name.isEmpty()) {
			error(line, "任务名不能是空的");
			return;
		}
		for (ArmProgramTask existing : ctx.program.tasks)
			if (existing.name.equals(name)) {
				error(line, "任务名重复：「" + name + "」—— 同名任务没法被 call / 跳转区分");
				return;
			}

		ArmProgramTask task = new ArmProgramTask();
		task.name = name;
		ctx.program.tasks.add(task);
		ctx.task = task;
		ctx.entry = null;
		ctx.owner = Ctx.Owner.TASK;

		// task 头的参数：只剩 icon=…
		// ★ **任务级循环的 `loop=` / `loopN=` 已删除**（用户口径：循环次数不需要了，主任务恒为无限循环）。
		//   这里**不做兼容读取**：老文本里的这两个键现在会当成"不认识的键"报错（"未知操作"那类错误同理）——
		//   这是**期望行为**，别为它加迁就分支（用户明确不要向后兼容）。
		List<Token> rest = new ArrayList<>(tokens.subList(2, tokens.size()));
		int index = 0;
		while (index < rest.size()) {
			Token token = rest.get(index);
			String key = keyOf(token);
			if (key == null) {
				error(token.line(), "task 头里不认识这一段：" + token.text() + "（后缀要写成 key=value）");
				return;
			}
			switch (key) {
				case "icon" -> {
					ItemStack icon = item(valueOf(token), token.line());
					if (icon == null)
						return;
					task.icon = icon;
					index++;
				}
				default -> {
					error(token.line(), "task 头里不认识这个键：" + key + "（可用 icon）");
					return;
				}
			}
		}
	}

	// ————————————————————————— 条目 —————————————————————————

	private void parseEntry(Ctx ctx, List<Token> tokens, int line) {
		Token head = tokens.get(0);
		String opName = head.text();
		OperationType op = OperationType.byName(opName, null);
		if (op == null) {
			error(line, "未知操作：" + opName + "（可用 " + operationNames() + "）");
			return;
		}

		Parsed parsed = new Parsed(op, line);
		List<Token> rest = new ArrayList<>(tokens.subList(1, tokens.size()));

		if (op.requiresTarget()) {
			if (!position(rest, parsed, true))
				return;
		} else if (op == OperationType.FILTER) {
			// ★ 「过滤」是**唯一**允许在"不移动"的情况下带坐标的操作（它的 requiresTarget() 是 false，
			//   但条目级的 needsTarget() 在容器模式下为真）：容器模式的坐标是**数据参数**
			//   （读哪个容器），手持模式没有坐标 —— 判据落在"能不能解析成三个整数"上（optionalPosition），
			//   于是两种模式共用一条解析路径，不需要在语法里再分一次叉。
			//   这一点是必须的：条目坐标只能有一种读法（写两份判据必然分叉，决策 68/85）。
			optionalPosition(rest, parsed);
		} else if (looksLikePosition(rest)) {
			// ★ 其余"不需要坐标"的操作（等待 / 信号 / 调用 / 退出 / 抛错）**不接受**坐标。
			//   它们以前被 optionalPosition 静默吃下（那时 NBT 的 `off` 是无条件写的，于是"非零偏移"
			//   也能往返）—— 用户第四十一轮正是被这个问住的：脚本里 `call 任务` 后面那串 `-3 0 -1`
			//   在运行时既不移动、也没有目标，纯是老数据留下的噪声。
			//   现在把手写这种文本当**错误**当场说破：静默丢弃会让"坐标已删掉"与"坐标没生效"
			//   看不出区别，静默接受则让噪声继续传下去 —— 两种都留了"猜"的空间，这一轮要消灭的正是它。
			//   ⚠️ 判据必须是"紧跟着的三个**裸的**整数"（见 looksLikePosition）：`delay=5` / `fail=goto`
			//   这类后缀紧跟在后面是最常见的写法，误判会把一条完全正确的条目判红。
			error(parsed.line, "「" + opKeyword(op) + "」没有目标位置，不需要坐标（把 `"
				+ positionText(rest) + "` 删掉）");
			// ★ 报了错**照样把这一行剩下的部分解析完**（把这三个记号丢掉，不整条作废）。
			//   理由与 §17.10 的"带伤装载"是同一条：盘上只有文本，老脚本里那串残留坐标**不该**
			//   让玩家的「调用」整条消失（后面还有 `任务名` / `repeat=2` 这些真内容等着被读）。
			//   丢掉的只是"本来就没有意义的三个数"；错误已经报出来了，导入侧照样把它当失败处理。
			rest.subList(0, 3)
				.clear();
		}
		if (!operands(parsed, rest))
			return;
		if (!suffixes(ctx, parsed, rest))
			return;
		// §12.3 末尾（主代理 87③）：`unload` 的 `amount=` **必填**。
		// UnloadAmount 的枚举默认值是 ONE_STACK，靠它会让"默认值"变成第二个真理来源（决策 68/85）：
		// 反编译器永远写 amount=，编译器就必须永远要求它 —— 否则手写省略 amount 的文本
		// 在往返里会悄悄依赖枚举默认值。
		if (op == OperationType.UNLOAD && !parsed.sawAmount) {
			error(line, "unload 缺 amount=（必填；可用 one_stack / max）");
			return;
		}

		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = op;
		entry.offset = parsed.offset;
		entry.delay = parsed.delay;
		entry.face = parsed.face;
		entry.failAmount = parsed.failAmount;
		entry.failUnit = parsed.failUnit;
		entry.onSuccess = parsed.onSuccess;
		entry.successTarget = parsed.successTarget;
		entry.onTimeout = parsed.onTimeout;
		entry.timeoutTarget = parsed.timeoutTarget;
		entry.count = parsed.count;
		entry.countMode = parsed.countMode;
		entry.waitAmount = parsed.waitAmount;
		entry.waitUnit = parsed.waitUnit;
		entry.waitForPowered = parsed.powered;
		entry.signalMode = parsed.mode;
		entry.signalRange = ArmProgramEntry.clampSignalRange(parsed.range);
		entry.callTarget = parsed.callTarget;
		entry.callRepeat = parsed.callRepeat;
		entry.callShift = parsed.callShift;
		entry.errorMessage = parsed.error;
		entry.unloadRepeat = parsed.repeat;
		entry.unloadAmount = parsed.amount;
		entry.icon = parsed.icon;
		// 过滤的两个新字段（不在 FILTER 上的条目也不会带上它们：解析器只在 FILTER 行里认这两个键）
		entry.filterItem = parsed.filterItem;
		entry.filterSource = parsed.filterSource;
		entry.frequency = parsed.frequency;
		// ★ 模型的不变量：**不需要坐标的条目，offset 恒为 0**（与 ArmProgramEntry.load 的自愈、
		//   ScriptDecompiler 的写出是同一条口径）。这里能落到的只有一种手写：
		//   `filter 1 2 3 source=held`（或干脆没写 source=）—— 坐标写在 `source=` **之前**，
		//   解析位置参数时还不知道是哪种模式，所以判据只能放到这里（字段都齐了才问 needsTarget()）。
		//   为什么**报错**而不是静默清零：手持模式没有坐标，那三个整数只可能是"想写容器模式却漏了
		//   source=container" —— 静默丢掉会让玩家以为过滤在读那个容器（实际读的是手上那份）。
		//   报错之后**照样保留这条条目**（把坐标清掉继续走）：§17.10 的"带伤装载"要求
		//   编译期严格不能吃掉内容 —— 老卡里的过滤条目在这一步消失，行为会整个变掉。
		if (!parsed.offset.equals(BlockPos.ZERO) && !entry.needsTarget()) {
			error(line, "「filter」的手持模式（source=held）没有坐标 —— 要么把 `" + positionText(parsed.offset)
				+ "` 删掉，要么写成 source=container（容器模式的坐标是必填的数据参数）");
			entry.offset = BlockPos.ZERO;
		}
		if (entry.frequency != null)
			warnFrequencyComponents(parsed);
		// ★ 「接收距离」**只对「收」有意义**（用户口径：距离只约束收；发送 / 中断运行时不读它）。
		//   写在这一步（`signal` 的模式与全部后缀都读完了）才问得准：`range=` 与模式顺序无关，
		//   将来多一种写法也不会漏判。**报错而不是静默丢弃** —— 静默收下会让玩家以为
		//   "这条发送只发 16 格内的"，而运行时是**不带位置**地发。
		//   ⚠️ 报了错要把那个值**清回 0**（模型的不变量：发送 / 中断的 signalRange 恒为 0），
		//   与上面"手持过滤写了坐标"那一段同一条纪律：带上错误、但值自愈，条目本身留着（§17.10）。
		if (parsed.range != 0 && parsed.mode != SignalMode.RECEIVE) {
			error(line, "range 只对收信号有意义（发送 / 中断不读距离）");
			entry.signalRange = 0;
		}
		if (parsed.quotedNote != null) {
			// 与块注释走**同一条**备注路径（§12.3a 第 6 条：两块合并成一个备注）——
			// 一行里同时写了 `/* … */`（上面的 attachComment 已经写过）与 `note="…"` 时，
			// 静默让后写的覆盖先写的就是"哪一份是真的"这种最难的 bug。
			ctx.entry = entry;
			ctx.owner = Ctx.Owner.ENTRY;
			registerNote(ctx, parsed.quotedNote, parsed.line, NoteSource.QUOTED);
		}

		ctx.task.entries.add(entry);
		ctx.entry = entry;
		// ★ 归属：条目一落到列表里，后面的块注释就归**它**（§12.3a 表格第一行 / 第二行）
		ctx.owner = Ctx.Owner.ENTRY;
		// 跳转落点的行号：编译期校验是**解析完之后**才做的（要先知道全部任务名），
		// 但错误必须报在**写那一行**上，所以把它随条目一起带走。
		ctx.targetLines.put(entry, parsed.line);
	}

	/** 操作各自的必要操作数（wait 的时长、signal 的模式、call 的目标……）。 */
	private boolean operands(Parsed parsed, List<Token> rest) {
		switch (parsed.op) {
			case WAIT -> {
				if (rest.isEmpty()) {
					error(parsed.line, "wait 后面要跟时长（例如 5s / 200t / 2m）");
					return false;
				}
				Time time = parseTime(rest.get(0)
					.text());
				if (time == null) {
					error(parsed.line, "未知时间写法：" + rest.get(0)
						.text() + "（写成 5s / 200t / 2m，单位 t=刻 s=秒 m=分）");
					return false;
				}
				if (!inTimeRange(time, rest.get(0)
					.line(), "等待时长"))
					return false;
				parsed.waitAmount = time.amount;
				parsed.waitUnit = time.unit;
				rest.remove(0);
			}
			case SIGNAL -> {
				if (rest.isEmpty()) {
					error(parsed.line, "signal 后面要跟模式（recv / send_on / send_off）");
					return false;
				}
				SignalMode mode = switch (rest.get(0)
					.text()
					.toLowerCase(Locale.ROOT)) {
					case "recv", "receive" -> SignalMode.RECEIVE;
					case "send_on", "send" -> SignalMode.SEND_ON;
					case "send_off", "cut" -> SignalMode.SEND_OFF;
					default -> null;
				};
				if (mode == null) {
					error(rest.get(0)
						.line(), "未知信号模式：" + rest.get(0)
							.text() + "（可用 recv / send_on / send_off）");
					return false;
				}
				parsed.mode = mode;
				rest.remove(0);
				if (mode == SignalMode.RECEIVE) {
					// 收信号只要一个极性开关（等通电 / 等断电）；"持续"对接收没有意义（由 place= 给，可省）
					if (!rest.isEmpty()) {
						Boolean recvPolarity = polarity(rest.get(0)
							.text());
						if (recvPolarity != null) {
							parsed.powered = recvPolarity;
							rest.remove(0);
						}
					}
				}
			}
			case CALL -> {
				if (rest.isEmpty()) {
					error(parsed.line, "call 后面要跟任务名");
					return false;
				}
				Token target = rest.get(0);
				if (!target.str()) {
					String problem = plainNameProblem(target.text());
					if (problem != null) {
						error(target.line(), "任务名「" + target.text() + "」" + problem);
						return false;
					}
				}
				parsed.callTarget = nameText(target);
				rest.remove(0);
			}
			case THROW -> {
				if (rest.isEmpty() || !rest.get(0)
					.str()) {
					error(parsed.line, "throw 后面要跟一个带引号的错误信息（例如 throw \"没材料\"）");
					return false;
				}
				parsed.error = rest.get(0)
					.text();
				rest.remove(0);
			}
			default -> {
				// 其余操作没有必要操作数
			}
		}
		return true;
	}

	/** 后缀：key=value（+ 少数带空格的 `freq=物品 + 物品`、`fail=goto 任务名`）。 */
	private boolean suffixes(Ctx ctx, Parsed parsed, List<Token> rest) {
		int index = 0;
		while (index < rest.size()) {
			Token token = rest.get(index);
			String key = keyOf(token);
			if (key == null) {
				error(token.line(), "条目里不认识这一段：" + token.text() + "（后缀要写成 key=value）");
				return false;
			}
			String value = valueOf(token);
			switch (key) {
				case "delay" -> {
					Integer n = intValue(token, 0, DELAY_MAX, "delay");
					if (n == null)
						return false;
					parsed.delay = n;
					index++;
				}
				case "face" -> {
					Direction dir = Direction.byName(value.toLowerCase(Locale.ROOT));
					if (dir == null) {
						error(token.line(), "未知朝向：" + value + "（可用 down/up/north/south/west/east）");
						return false;
					}
					parsed.face = dir;
					index++;
				}
				case "count" -> {
					// 用户本轮口径：个数**取出 / 放入都行**（以前只有 take 认它）。
					// 判据是 OperationType.hasCount()（那一个方法的唯一定义处见枚举里）。
					if (!parsed.op.hasCount()) {
						error(token.line(), "count 只对 take / deposit 有意义");
						return false;
					}
					Integer n = intValue(token, COUNT_MIN, COUNT_MAX, "count");
					if (n == null)
						return false;
					parsed.count = n;
					index++;
				}
				// 个数的**模式**（用户本轮口径：等于 / 小于等于）：`countmode=exact|atmost`。
				// 语义：exact = 必须凑出恰好 count 个才动手；atmost（默认）= 有多少动多少、上限 count。
				// 反编译器**只写非默认**（atmost 一个字都不写）⇒ 手写省略时读回来必须落在同一个默认值上。
				case "countmode" -> {
					if (!parsed.op.hasCount()) {
						error(token.line(), "countmode 只对 take / deposit 有意义");
						return false;
					}
					ArmProgramEntry.CountMode countMode = switch (value.toLowerCase(Locale.ROOT)) {
						case "exact" -> ArmProgramEntry.CountMode.EXACT;
						case "atmost" -> ArmProgramEntry.CountMode.ATMOST;
						default -> null;
					};
					if (countMode == null) {
						error(token.line(), "未知个数模式：" + value + "（可用 exact / atmost）");
						return false;
					}
					parsed.countMode = countMode;
					index++;
				}
				case "repeat" -> {
					if (parsed.op == OperationType.UNLOAD) {
						UnloadRepeat repeat = switch (value.toLowerCase(Locale.ROOT)) {
							case "once" -> UnloadRepeat.ONCE;
							case "until_empty" -> UnloadRepeat.UNTIL_EMPTY;
							default -> null;
						};
						if (repeat == null) {
							error(token.line(), "未知卸货方式：" + value + "（可用 once / until_empty）");
							return false;
						}
						parsed.repeat = repeat;
					} else if (parsed.op == OperationType.CALL) {
						// 0..64，0 = 无限循环（用户第四十轮；原来借的是任务循环的 1..1024）
						Integer n = intValue(token, ArmProgramEntry.CALL_REPEAT_INFINITE,
							ArmProgramEntry.MAX_CALL_REPEAT, "repeat");
						if (n == null)
							return false;
						parsed.callRepeat = n;
					} else {
						error(token.line(), "repeat 只对 unload / call 有意义");
						return false;
					}
					index++;
				}
				// 「调用」的**调用偏移**：这个调用点让子任务里**需要坐标**的条目错开多少（相对量）。
				// 反编译器只写**逗号那种**（`shift=1,0,0`，一个记号），但解析**逗号与空格都吃**
				//（`shift=1 0 0`：后面两个裸记号跟着一起吃）—— 与"坐标 = 三个整数"同一口径。
				case "shift" -> {
					if (parsed.op != OperationType.CALL) {
						error(token.line(), "shift 只对 call 有意义（它是「这个调用点让子任务错开多少」）");
						return false;
					}
					if (parsed.sawCallShift) {
						error(token.line(), "调用偏移只能写一次（shift=）");
						return false;
					}
					parsed.sawCallShift = true;
					index++;
					List<String> parts = new ArrayList<>();
					for (String piece : value.split("[,\\s]+"))
						if (!piece.isEmpty())
							parts.add(piece);
					// 空格写法：后面两个是**裸记号**（没有 `=`）才吃；遇到下一个 key=value 就停 ——
					// 那时分量不够，下面报错，绝不静默补 0（补 0 会让"写错了"变成"偏移不生效"）。
					while (parts.size() < 3 && index < rest.size() && keyOf(rest.get(index)) == null) {
						parts.add(valueOf(rest.get(index))
							.trim());
						index++;
					}
					if (parts.size() != 3) {
						error(token.line(), "调用偏移要写三个整数（例如 shift=1,0,0）");
						return false;
					}
					// 复用报错版的 intValue（范围与坐标共用 ScriptParser.COORD_LIMIT）：三个分量一起验，
					// 任一不合法就整体作废 —— 半个偏移比没有偏移更难查。
					Token x = new Token(parts.get(0), token.line(), token.col(), false);
					Token y = new Token(parts.get(1), token.line(), token.col(), false);
					Token z = new Token(parts.get(2), token.line(), token.col(), false);
					Integer px = intValue(x, -COORD_LIMIT, COORD_LIMIT, "调用偏移 X");
					Integer py = intValue(y, -COORD_LIMIT, COORD_LIMIT, "调用偏移 Y");
					Integer pz = intValue(z, -COORD_LIMIT, COORD_LIMIT, "调用偏移 Z");
					if (px == null || py == null || pz == null)
						return false;
					parsed.callShift = new BlockPos(px, py, pz);
				}
				case "amount" -> {
					if (parsed.op != OperationType.UNLOAD) {
						error(token.line(), "amount 只对 unload 有意义");
						return false;
					}
					UnloadAmount amount = switch (value.toLowerCase(Locale.ROOT)) {
						case "one_stack" -> UnloadAmount.ONE_STACK;
						case "max" -> UnloadAmount.MAX;
						default -> null;
					};
					if (amount == null) {
						error(token.line(), "未知卸货上限：" + value + "（可用 one_stack / max）");
						return false;
					}
					parsed.amount = amount;
					parsed.sawAmount = true;
					index++;
				}
				// 「接收距离」（用户口径：距离**只约束收** —— 我只接受半径内的来源，发出去不管）。
				// 语法：`range=16`（0..256，整数格）。反编译器**只在「收」且非 0 时**写它，
				// 所以手写省略时读回来必须是默认的 0（= 只有同一格才算收到）。
				case "range" -> {
					if (parsed.op != OperationType.SIGNAL) {
						error(token.line(), "range 只对 signal 有意义");
						return false;
					}
					if (parsed.sawRange) {
						error(token.line(), "接收距离只能写一次（range=）");
						return false;
					}
					parsed.sawRange = true;
					Integer n = intValue(token, 0, ArmProgramEntry.SIGNAL_RANGE_MAX, "range");
					if (n == null)
						return false;
					parsed.range = n;
					index++;
				}
				case "place" -> {
					if (parsed.op != OperationType.SIGNAL) {
						error(token.line(), "place 只对 signal 有意义");
						return false;
					}
					// ★ 用户口径（本轮）：**中断没有持续时间** —— 它无论是否无限保持、有没有剩余时间，
					//   都立即把该频率清除（电平 0 + 退网 + 取消未到期的脉冲）；**接收**只是等通电。
					//   只有「发送通电」才有持续。所以这两种模式带 place= 是**明确报错**，
					//   而不是静默忽略 —— 静默收下会让玩家以为"5 秒后才断"，行为与脚本写的刚好相反
					//   （反编译侧同口径：ScriptDecompiler 对它们一个字都不写）。
					//   ⚠️ 报了错**照样把这一行剩下的部分解析完**（只丢掉这个 place=），
					//   不整条作废 —— 与上面"不需要坐标的操作"报错后继续是同一条纪律：
					//   编译期严格不能把一整条信号操作吃掉（盘上只有文本，丢掉就是行为整个变掉）。
					if (parsed.mode == SignalMode.SEND_OFF || parsed.mode == SignalMode.RECEIVE) {
						error(token.line(), (parsed.mode == SignalMode.SEND_OFF ? "「中断」没有持续时间：它立刻清除该频率"
							: "「接收」没有持续时间：它只是等该频率通电") + "（要持续时间请用 send_on）");
						index++;
						continue;
					}
					Time time = parseTime(value);
					if (time == null) {
						error(token.line(), "未知时间写法：" + value + "（写成 5s / 200t / 2m）");
						return false;
					}
					if (!inTimeRange(time, token.line(), "持续"))
						return false;
					parsed.waitAmount = time.amount;
					parsed.waitUnit = time.unit;
					index++;
				}
				case "timeout" -> {
					Time time = parseTime(value);
					if (time == null) {
						error(token.line(), "未知时间写法：" + value + "（写成 5s / 200t / 2m）");
						return false;
					}
					if (!inTimeRange(time, token.line(), "时限"))
						return false;
					parsed.failAmount = time.amount;
					parsed.failUnit = time.unit;
					// ⚠️ 这里**不设** sawFailAction：`timeout=`（时限）与 `fail=`（去向）是**两个字段**，
					//    反编译器永远同时写（`timeout=5s fail=skip`）—— 第一版把它当"同一件事的两半"，
					//    于是反编译器写出来的每一行都被自己判成重复。时限的重复由 failAmount 的哨兵值抓
					//    （见下面的 failAmount < 0 判据），判据只有一处。
					if (parsed.failAmountSeen) {
						error(token.line(), "时限只能写一次（timeout=）");
						return false;
					}
					parsed.failAmountSeen = true;
					index++;
				}
				case "fail" -> {
					if (parsed.sawFailAction) {
						error(token.line(), "失败去向 / 时限只能写一次（timeout= 与 fail= 是同一件事的两半）");
						return false;
					}
					parsed.sawFailAction = true;
					switch (value.toLowerCase(Locale.ROOT)) {
						case "goto" -> {
							parsed.onTimeout = SkipAction.SKIP_TO;
							index++;
							if (index >= rest.size() || keyOf(rest.get(index)) != null) {
								error(token.line(), "fail=goto 后面要跟任务名或序号");
								return false;
							}
							Token target = rest.get(index);
							if (!target.str()) {
								String problem = plainNameProblem(target.text());
								if (problem != null) {
									error(target.line(), "任务名「" + target.text() + "」" + problem);
									return false;
								}
							}
							parsed.timeoutTarget = nameText(target);
							index++;
						}
						case "skip", "continue" -> {
							parsed.onTimeout = SkipAction.CONTINUE;
							parsed.timeoutTarget = "";
							index++;
						}
						case "stop", "none" -> {
							parsed.onTimeout = SkipAction.NONE;
							parsed.timeoutTarget = "";
							index++;
						}
						default -> {
							error(token.line(), "未知失败去向：" + value + "（可用 goto / skip / stop）");
							return false;
						}
					}
				}
				case "ok" -> {
					if (parsed.sawOkAction) {
						error(token.line(), "ok 写了两次");
						return false;
					}
					parsed.sawOkAction = true;
					switch (value.toLowerCase(Locale.ROOT)) {
						case "goto" -> {
							parsed.onSuccess = SkipAction.SKIP_TO;
							index++;
							if (index >= rest.size() || keyOf(rest.get(index)) != null) {
								error(token.line(), "ok=goto 后面要跟任务名或序号");
								return false;
							}
							Token target = rest.get(index);
							if (!target.str()) {
								String problem = plainNameProblem(target.text());
								if (problem != null) {
									error(target.line(), "任务名「" + target.text() + "」" + problem);
									return false;
								}
							}
							parsed.successTarget = nameText(target);
							index++;
						}
						case "none", "stop" -> {
							parsed.onSuccess = SkipAction.NONE;
							parsed.successTarget = "";
							index++;
						}
						default -> {
							error(token.line(), "未知成功去向：" + value + "（可用 goto）");
							return false;
						}
					}
				}
				case "note" -> {
					// 保底写法（§12.3a 细则 3）：备注含 `*/` 时反编译器只有它能表达
					parsed.quotedNote = value;
					index++;
				}
				case "icon" -> {
					ItemStack icon = item(value, token.line());
					if (icon == null)
						return false;
					parsed.icon = icon;
					index++;
				}
				// 「过滤」的**过滤位置**（必填，反编译器永远写）：held = 手上那份存货 / container = 外部容器。
				// 为什么必须必填而不是"缺省 handheld"（语法上是可省的）：模式写错/漏写会让"容器模式"
				// 静默读成手持 —— 那是两种完全不同的行为，不该靠默认值决定。
				case "source" -> {
					if (parsed.op != OperationType.FILTER) {
						error(token.line(), "source 只对 filter 有意义");
						return false;
					}
					ArmProgramEntry.FilterSource source = switch (value.toLowerCase(Locale.ROOT)) {
						case "held", "hand" -> ArmProgramEntry.FilterSource.HELD;
						case "container" -> ArmProgramEntry.FilterSource.CONTAINER;
						default -> null;
					};
					if (source == null) {
						error(token.line(), "未知过滤位置：" + value + "（可用 held / container）");
						return false;
					}
					parsed.filterSource = source;
					index++;
				}
				// 「过滤」的**幽灵过滤器物品**（必填）：复用 icon=/freq= 那套现成写法
				//（简写 id、#RRGGBB、完整 SNBT），所以"用 ID 还是用完整 stack"这条与图标完全一致。
				case "item" -> {
					if (parsed.op != OperationType.FILTER) {
						error(token.line(), "item 只对 filter 有意义（条目图标用 icon=）");
						return false;
					}
					ItemStack filter = item(value, token.line());
					if (filter == null)
						return false;
					parsed.filterItem = filter;
					index++;
				}
				case "freq" -> {
					if (parsed.op != OperationType.SIGNAL) {
						error(token.line(), "freq 只对 signal 有意义");
						return false;
					}
					if (value.isEmpty()) {
						error(token.line(), "freq= 后面要跟物品（形如 freq=iron_ingot + copper_ingot）");
						return false;
					}
					index++;
					// ⚠️ 第一个物品若写成 SNBT（`freq={…} + {…}`），两个 `{…}` 会被词法器连成
					//    **一个**原子记号（引号里的内容不能切开）⇒ 这里把它拆回两格。
					//    简写 id 那一路仍是三片记号：`freq=iron_ingot` / `+` / `copper_ingot`。
					List<String> inline = splitSnbtPair(value);
					if (inline != null && inline.size() == 2) {
						ItemStack firstInline = item(inline.get(0), token.line());
						ItemStack secondInline = item(inline.get(1), token.line());
						if (firstInline == null || secondInline == null)
							return false;
						Couple<Frequency> inlinePair = Couple.create(() -> Frequency.EMPTY);
						inlinePair.set(true, Frequency.of(firstInline));
						inlinePair.set(false, Frequency.of(secondInline));
						parsed.frequency = inlinePair;
						parsed.freqFirst = firstInline;
						parsed.freqSecond = secondInline;
						return true;
					}
					ItemStack first = item(value, token.line());
					if (first == null)
						return false;
					ItemStack second = ItemStack.EMPTY;
					if (index < rest.size() && "+".equals(rest.get(index)
						.text())) {
						index++;
						if (index >= rest.size()) {
							error(token.line(), "freq 的 `+` 后面要跟第二个物品");
							return false;
						}
						Token secondToken = rest.get(index);
						String secondKey = keyOf(secondToken);
						if (secondKey != null) {
							error(secondToken.line(), "freq 的 `+` 后面要跟第二个物品");
							return false;
						}
						second = item(secondToken.text(), secondToken.line());
						if (second == null)
							return false;
						index++;
					}
					Couple<Frequency> pair = Couple.create(() -> Frequency.EMPTY);
					pair.set(true, Frequency.of(first));
					pair.set(false, Frequency.of(second));
					parsed.frequency = pair;
					parsed.freqFirst = first;
					parsed.freqSecond = second;
				}
				default -> {
					error(token.line(), "不认识的属性：" + key);
					return false;
				}
			}
		}
		if (parsed.op == OperationType.SIGNAL && parsed.frequency == null) {
			error(parsed.line, "signal 必须绑频率（freq=物品 + 物品）—— 没绑就永远等不到 / 发不出去");
			return false;
		}
		return true;
	}

	// ————————————————————————— 小解析器 —————————————————————————

	/**
	 * 位置参数：<b>三个整数</b>（PLAN.md §19.7 —— 坐标是"相对**执行这条程序的那条臂**"的偏移，
	 * 世界目标 = {@code 臂位置 + 偏移}；{@code @abs} / {@code @rel} 两个记号已删除）。
	 *
	 * <p>为什么原来那个记号必须删掉而不是"继续容忍"：它表达的是"这条坐标是绝对的还是相对的"，
	 * 而新模型里这个区别<b>不存在</b> —— 留着它就会出现"同一个文件中两种语义"的读法，
	 * 正是这次统一要消灭的困惑（§19.1）。
	 */
	private boolean position(List<Token> rest, Parsed parsed, boolean required) {
		if (rest.size() < 3) {
			if (required)
				error(parsed.line, "缺坐标：需要三个整数（坐标相对执行它的那条臂，例如 0 0 2）");
			return !required;
		}
		// 老写法（`@rel 1 2 3`）走到这里：报错说清"记号没了"，别让玩家对着"缺坐标"发呆。
		if (rest.get(0)
			.text()
			.startsWith("@")) {
			if (!required)
				return true;
			error(rest.get(0)
				.line(), "坐标不再写 " + rest.get(0)
					.text() + "：直接写三个整数即可（坐标一律相对执行它的那条臂）");
			return false;
		}
		Integer x = intValue(rest.get(0), -COORD_LIMIT, COORD_LIMIT, "坐标 X");
		Integer y = intValue(rest.get(1), -COORD_LIMIT, COORD_LIMIT, "坐标 Y");
		Integer z = intValue(rest.get(2), -COORD_LIMIT, COORD_LIMIT, "坐标 Z");
		if (x == null || y == null || z == null)
			return false;
		parsed.offset = new BlockPos(x, y, z);
		rest.subList(0, 3)
			.clear();
		return true;
	}

	/**
	 * 非必需的位置参数：只有前三个记号都是整数时才吃。
	 *
	 * <p>现在**只有「过滤」走这一支**（用户第四十一轮起：其余"不需要坐标的操作"写了坐标一律报错，
	 * 见 {@code parseEntry} 的 {@code looksLikePosition} 分支）—— 因为过滤是"同一条操作、两种模式"：
	 * 容器模式的坐标是必填的**数据参数**，手持模式则一个数都没有。判据不能提前绑死，
	 * 所以这里只试探、后面再按 {@code source=} 对账。
	 *
	 * <p>判据只能是"能不能解析成三个整数"，不能用"是不是以 {@code @} 开头"那种记号启发式：
	 * 曾经位置参数带 {@code @abs/@rel} 前缀，现在同一位置上可能是 {@code fail=goto} / {@code delay=5}
	 * 这类后缀 —— 直接拿它们去当坐标解析就会把一条完全正确的条目判成"坐标不是整数"。
	 */
	private void optionalPosition(List<Token> rest, Parsed parsed) {
		if (rest.size() < 3)
			return;
		if (!isInt(rest.get(0)) || !isInt(rest.get(1)) || !isInt(rest.get(2)))
			return;
		position(rest, parsed, false);
	}

	/**
	 * 接下来这三个记号**看起来像坐标**吗 —— <b>只判、不报错</b>（与 {@link #isInt} 同一套路）。
	 *
	 * <p>用途只有一个：给"不需要坐标的操作"写了坐标时**当场说破**（用户第四十一轮：脚本里
	 * {@code call 任务 -3 0 -1} 那串数字运行时根本不看，纯是噪声）。
	 *
	 * <p>判据两条，缺一不可：① 三个记号都是**裸记号**（{@code keyOf} 为空）—— 坐标从来不写成
	 * {@code delay=5} 那种 key=value；② 它们的值都是整数。只判②的话，一条写法完全正确的
	 * {@code break timeout=3 …}（后面又碰巧跟着两个整数后缀）会被误判成坐标；只判①的话，
	 * {@code break a b c} 会被当成"坐标写错了"而报出更离谱的错。
	 */
	private static boolean looksLikePosition(List<Token> rest) {
		if (rest.size() < 3)
			return false;
		for (int i = 0; i < 3; i++)
			if (keyOf(rest.get(i)) != null || !isInt(rest.get(i)))
				return false;
		return true;
	}

	/** 报错里显示坐标：用**玩家自己写的记号原文**（`-3 0 -1`），这样他能直接对着删。 */
	private static String positionText(List<Token> rest) {
		return rest.get(0).text() + " " + rest.get(1).text() + " " + rest.get(2).text();
	}

	/** 报错里显示坐标：从解析好的值拼（用于"坐标已经吃掉、模式后来才知道"的那种判断）。 */
	private static String positionText(BlockPos offset) {
		return offset.getX() + " " + offset.getY() + " " + offset.getZ();
	}

	/**
	 * 这个记号是不是"范围合法的十进制整数"—— <b>只判、不报错</b>。
	 *
	 * <p>为什么不能借用 {@link #intValue}：它是"报错版"，用它做**试探**会把一条完全正确的条目
	 * （后面跟着 {@code delay=5}，首个后缀显然不是整数）判成"坐标要写整数"而报红。
	 * 试探与解析必须是两个入口（这也是 {@code intValue} 的 {@code what} 不再接受 null 的原因）。
	 */
	private static boolean isInt(Token token) {
		String value = valueOf(token).trim();
		try {
			int parsed = Integer.parseInt(value);
			return parsed >= -COORD_LIMIT && parsed <= COORD_LIMIT;
		} catch (NumberFormatException notAnInt) {
			return false;
		}
	}

	/**
	 * 物品：三种写法都吃（§12.1）——
	 * <ul>
	 *   <li>简写 {@code minecraft:dirt}（= 无组件、count 1 的规范形式）；</li>
	 *   <li>语法糖 {@code minecraft:leather_helmet#C41E3A}（唯一组件是染色时）；</li>
	 *   <li>完整 SNBT {@code {id:"minecraft:black_banner",count:1,components:{…}}}
	 *       —— <b>直接复用原版 {@code ItemStack.CODEC} 的 SNBT</b>，所以旗帜图案这类能无损往返。</li>
	 * </ul>
	 *
	 * <p>空物品（反编译时对"这一格留空"的写法）是 {@code {} }：与 SNBT 的默认值一致。
	 */
	@Nullable
	private ItemStack item(String text, int line) {
		String trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty() || "{}".equals(trimmed))
			return ItemStack.EMPTY;
		if (trimmed.startsWith("{"))
			return itemFromSnbt(trimmed, line);
		int hash = trimmed.lastIndexOf('#');
		if (hash > 0) {
			String id = trimmed.substring(0, hash);
			int rgb = parseRgb(trimmed.substring(hash + 1));
			if (rgb < 0) {
				error(line, "染色写法不对：" + trimmed + "（要写成 #RRGGBB，例如 minecraft:leather_helmet#C41E3A）");
				return null;
			}
			ItemStack stack = itemFromId(id, line);
			if (stack == null)
				return null;
			stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, true));
			return stack;
		}
		return itemFromId(trimmed, line);
	}

	@Nullable
	private ItemStack itemFromId(String id, int line) {
		ResourceLocation key = ResourceLocation.tryParse(id);
		if (key == null) {
			error(line, "不是合法的物品 id：" + id);
			return null;
		}
		if (!BuiltInRegistries.ITEM.containsKey(key)) {
			// 列几个"名字里含你写的那段"的物品 id —— 未知物品是玩家最常犯的错，
			// 而只报"未知"等于让人自己去猜（§12.3 要的是"编译期能报的错都在编译期报"）。
			error(line, "未知物品 id：" + id + suggestions(id));
			return null;
		}
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(key));
		if (stack.isEmpty()) {
			error(line, "这个物品是空气：" + id);
			return null;
		}
		return stack;
	}

	/** 形如 {@code （是不是想写：create:brass_ingot / minecraft:iron_ingot）}；没找到近似项就返回空串。 */
	private static String suggestions(String id) {
		String needle = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		if (needle.isEmpty())
			return "";
		List<String> found = new ArrayList<>();
		for (ResourceLocation candidate : BuiltInRegistries.ITEM.keySet()) {
			if (!candidate.getPath()
				.contains(needle))
				continue;
			found.add(candidate.toString());
			if (found.size() >= 3)
				break;
		}
		return found.isEmpty() ? "" : "（是不是想写：" + String.join(" / ", found) + "）";
	}

	@Nullable
	private ItemStack itemFromSnbt(String snbt, int line) {
		CompoundTag tag;
		try {
			tag = TagParser.parseTag(snbt);
		} catch (CommandSyntaxException bad) {
			error(line, "SNBT 物品写法不对：" + snbt + "（" + bad.getMessage() + "）");
			return null;
		}
		ItemStack stack = ItemStack.parseOptional(registries, tag);
		if (stack.isEmpty()) {
			error(line, "这个 SNBT 不是一个能认出来的物品：" + snbt);
			return null;
		}
		return stack;
	}

	/** {@code RRGGBB} → 颜色；不是六位十六进制时返回 -1。 */
	private static int parseRgb(String text) {
		if (text.length() != 6)
			return -1;
		try {
			return Integer.parseInt(text, 16);
		} catch (NumberFormatException notHex) {
			return -1;
		}
	}

	private boolean inTimeRange(Time time, int line, String what) {
		if (time.amount < 0 || time.amount > TIME_AMOUNT_MAX) {
			error(line, what + "超出范围 0–" + TIME_AMOUNT_MAX + "：" + time.amount);
			return false;
		}
		return true;
	}

	/** 时间量：数值 + 单位（{@code 5s} / {@code 200t} / {@code 2m}；不带单位 = 刻）。 */
	private record Time(int amount, WaitUnit unit) {
	}

	@Nullable
	private static Time parseTime(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty())
			return null;
		char last = trimmed.charAt(trimmed.length() - 1);
		WaitUnit unit = switch (last) {
			case 't', 'T' -> WaitUnit.TICK;
			case 's', 'S' -> WaitUnit.SECOND;
			case 'm', 'M' -> WaitUnit.MINUTE;
			default -> null;
		};
		String digits = unit == null ? trimmed : trimmed.substring(0, trimmed.length() - 1);
		if (digits.isEmpty() || !digits.chars()
			.allMatch(c -> c >= '0' && c <= '9'))
			return null;
		try {
			return new Time(Integer.parseInt(digits), unit == null ? WaitUnit.TICK : unit);
		} catch (NumberFormatException tooBig) {
			return null;
		}
	}

	@Nullable
	private static Boolean polarity(String text) {
		return switch (text.toLowerCase(Locale.ROOT)) {
			case "on", "true", "powered" -> Boolean.TRUE;
			case "off", "false" -> Boolean.FALSE;
			default -> null;
		};
	}

	/** 复数形式：*_count 在模型里，但文本面用 `count=`（更短、也是草案里的写法）。 */
	private static String operationNames() {
		StringBuilder sb = new StringBuilder();
		for (OperationType type : OperationType.values()) {
			if (sb.length() > 0)
				sb.append(" / ");
			sb.append(opKeyword(type));
		}
		return sb.toString();
	}

	/**
	 * 操作在脚本里的关键字（{@code CALL → call}）—— 报错里用它指认"是哪一条操作"。
	 *
	 * <p>⚠️ 这里**有意**用脚本关键字、而不是界面上的中文名：本类所有的编译期错误都是纯文本字符串
	 * （{@link ScriptError} 只有 {@code String message}），而中文名住在语言文件的
	 * {@code gui.editor.opt.*} 键里 —— 服务端（专用服务器 / 自检）根本读不到它，
	 * {@code Component.getString()} 会直接吐出键名。要换成中文名就得让错误消息能带
	 * {@code Component}（那是另一件事，不在这一轮的范围里）。
	 */
	private static String opKeyword(OperationType op) {
		return op.name()
			.toLowerCase(Locale.ROOT);
	}

	/** 不带引号的任务名：空白字符会把它截断，所以只接受"看起来是一个名字"的记号。 */
	@Nullable
	private static String plainNameProblem(String name) {
		if (name == null || name.isEmpty())
			return "不能是空的";
		if (name.chars()
			.anyMatch(c -> c == '=' || c == '+' || c == '"'))
			return "含有 `=` / `+` / 引号 —— 请用引号包起来";
		if (name.indexOf('@') == 0)
			return "不能以 @ 开头（那是坐标标记）—— 请用引号包起来";
		return null;
	}

	/**
	 * 跳转落点：带引号的记号直接取原文（引号里的名字就是那个名字）；SNBT 里的字符串
	 * （{@code {id:"minecraft:dirt"}} 那个 {@code "minecraft:dirt"}）也在这里取到裸名字。
	 */
	private static String nameText(Token token) {
		String text = token.text();
		if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"')
			return text.substring(1, text.length() - 1);
		return text;
	}

	@Nullable
	private static String keyOf(Token token) {
		String text = token.text();
		if (isSnbtKey(text))
			return snbtKeyOf(text);
		int eq = text.indexOf('=');
		return eq <= 0 ? null : text.substring(0, eq);
	}

	/**
	 * 记号里的"值"：{@code key=value} 取 {@code value}，**不带 {@code =} 的裸记号取原文**。
	 *
	 * <p>⚠️ 第二半是必须的：位置参数与等待时长都是**裸记号**（{@code 2 0 0} / {@code 5s}），
	 * 早先这里对没有 {@code =} 的记号返回空串，于是 {@code 2} 变成 {@code ""} ⇒
	 * {@code Integer.parseInt("")} 抛异常 ⇒ 所有带坐标的条目一律报"坐标要写整数"、
	 * 整条编译链路（以及不变量①②）全红。裸记号返回原文之后，数值/时间/物品 id 三种写法才走得通。
	 */
	private static String valueOf(Token token) {
		String text = token.text();
		if (isSnbtKey(text))
			return snbtValueOf(text);
		int eq = text.indexOf('=');
		return eq <= 0 ? text : text.substring(eq + 1);
	}

	@Nullable
	private Integer intValue(Token token, int min, int max, String what) {
		String value = valueOf(token);
		int parsed;
		try {
			parsed = Integer.parseInt(value.trim());
		} catch (NumberFormatException bad) {
			error(token.line(), what + " 要写整数，收到：" + value);
			return null;
		}
		if (parsed < min || parsed > max) {
			error(token.line(), what + " 超出范围 " + min + "–" + max + "：" + parsed);
			return null;
		}
		return parsed;
	}

	// ————————————————————————— 编译期校验（§12.3 的净收益） —————————————————————————

	/**
	 * 跳转 / 调用**只认任务名或条目序号**，所以能在编译期全部查清（用户点名要的净收益：
	 * {@code unknown task: cook_wok} 并列出已知任务名）。
	 */
	private void validateTargets(ArmProgram program, Ctx ctx) {
		List<String> known = new ArrayList<>();
		for (ArmProgramTask task : program.tasks)
			known.add(task.name);
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				int line = ctx.targetLines.getOrDefault(entry, 1);
				checkTarget(entry.successTarget, entry.onSuccess == SkipAction.SKIP_TO, task, line, known);
				checkTarget(entry.timeoutTarget, entry.onTimeout == SkipAction.SKIP_TO, task, line, known);
				if (entry.op == OperationType.CALL && known.stream()
					.noneMatch(name -> name.equals(entry.callTarget)))
					error(line, "unknown task: " + entry.callTarget + "（已知任务：" + String.join(" / ", known) + "）");
			}
		}
	}

	private void checkTarget(String target, boolean jumps, ArmProgramTask task, int line, List<String> known) {
		if (!jumps || target.isEmpty())
			return;
		if (target.chars()
			.allMatch(Character::isDigit)) {
			try {
				int index = Integer.parseInt(target);
				if (index < 1 || index > task.entries.size() + 1)
					error(line, "跳转序号越界：" + index + "（本任务 " + task.entries.size()
						+ " 条，合法 1–" + (task.entries.size() + 1) + "）");
			} catch (NumberFormatException tooBig) {
				error(line, "跳转序号太大：" + target);
			}
			return;
		}
		if (known.stream()
			.noneMatch(name -> name.equals(target)))
			error(line, "unknown task: " + target + "（已知任务：" + String.join(" / ", known) + "）");
	}

	// ————————————————————————— 频率的组件警告（§12.2） —————————————————————————

	/**
	 * {@code freq=} 里的物品："这个组件不影响频率"要**警告**，不静默改写、也不报硬错
	 * —— Create 的频率身份只有 (物品实例 + dyed_color) 两项，玩家带别的组件可能只是想要那个观感（§12.2）。
	 */
	private void warnFrequencyComponents(Parsed parsed) {
		warnOddComponents(parsed.freqFirst, parsed.line, "freq 的第一个物品");
		warnOddComponents(parsed.freqSecond, parsed.line, "freq 的第二个物品");
	}

	private void warnOddComponents(@Nullable ItemStack stack, int line, String what) {
		if (stack == null || stack.isEmpty())
			return;
		boolean plain = stack.getComponents()
			.isEmpty();
		boolean onlyDye = stack.getComponents()
			.size() == 1 && stack.has(DataComponents.DYED_COLOR);
		if (!plain && !onlyDye)
			warnings.add("第 " + line + " 行: " + what + "带了不影响频率的组件（Create 的频率身份只看物品 + 染色）");
	}

	/** 解析条目用的中间态：字段太多，先攒着，最后一次性构造（避免中途抛错留下半个模型）。 */
	private static final class Parsed {
		final OperationType op;
		final int line;
		BlockPos offset = BlockPos.ZERO;
		int delay;
		Direction face = Direction.UP;
		int failAmount = -1;
		boolean failAmountSeen;
		WaitUnit failUnit = WaitUnit.TICK;
		SkipAction onSuccess = SkipAction.NONE;
		String successTarget = "";
		SkipAction onTimeout = SkipAction.NONE;
		String timeoutTarget = "";
		int count = 1;
		/** 个数的模式（TAKE / DEPOSIT）：缺省 ATMOST = "有多少动多少"，`countmode=exact` 才要求恰好 count 个。 */
		ArmProgramEntry.CountMode countMode = ArmProgramEntry.CountMode.ATMOST;
		int waitAmount = 20;
		WaitUnit waitUnit = WaitUnit.TICK;
		boolean powered = true;
		SignalMode mode = SignalMode.RECEIVE;
		/** SIGNAL 的**接收半径**（`range=`，0..{@link ArmProgramEntry#SIGNAL_RANGE_MAX}）与"这个键见过没有"。 */
		int range;
		boolean sawRange;
		String callTarget = "";
		int callRepeat = 1;
		/** CALL 的调用偏移（Δ）与"这个键见过没有"（重复写要报错，见 suffixes）。 */
		BlockPos callShift = BlockPos.ZERO;
		boolean sawCallShift;
		String error = "";
		UnloadRepeat repeat = UnloadRepeat.ONCE;
		UnloadAmount amount = UnloadAmount.ONE_STACK;
		boolean sawAmount;
		ItemStack icon = ItemStack.EMPTY;
		/** 过滤的幽灵物品与位置（反编译器两者都永远写；这里缺省 = 空过滤器 + 手持，恰好是模型默认值）。 */
		ItemStack filterItem = ItemStack.EMPTY;
		ArmProgramEntry.FilterSource filterSource = ArmProgramEntry.FilterSource.HELD;
		@Nullable
		Couple<Frequency> frequency;
		@Nullable
		ItemStack freqFirst;
		@Nullable
		ItemStack freqSecond;
		@Nullable
		String quotedNote;
		boolean sawFailAction;
		boolean sawOkAction;

		Parsed(OperationType op, int line) {
			this.op = op;
			this.line = line;
			// 「个数」的默认值**按操作给**（取出 1 / 放入 64，见 ArmProgramEntry.defaultCount）：
			// 脚本里没写 `count=` 时必须落在这个初值上 —— 否则 `deposit` 省掉 count 会变成
			// "一次只放 1 个"，与旧行为（整摞）和编辑器显示都对不上。
			// ⚠️ 别在这里写死 1 / 64：默认值的唯一定义处是 defaultCount()。
			this.count = ArmProgramEntry.defaultCount(op);
		}
	}

	private void error(int line, String message) {
		errors.add(new ScriptError(line, message));
	}
}
