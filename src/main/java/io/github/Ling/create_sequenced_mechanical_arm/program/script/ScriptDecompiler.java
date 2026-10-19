package io.github.Ling.create_sequenced_mechanical_arm.program.script;

import java.util.Locale;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmEntryText;
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

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * {@link ArmProgram} → 脚本文本（PLAN.md §12.1 / §12.3 / §12.3a）。
 *
 * <p><b>这里就是"规范形式"的唯一实现</b>（§12.4 不变量 #2：{@code decompile(compile(t)) == canonical(t)}）。
 * 本类只做一件事：把一个模型原样写成**恰好一种**文本写法。于是"同一份程序导出两次得到同一段文本"
 * 天然成立，而"手写的另一种写法（时间不带单位、跳转不带 goto、备注写在条目上面）"
 * 只会被**重排位置、不会丢内容**（内容丢没丢由不变量 #1 守）。
 *
 * <p>规范形式速查：
 * <ul>
 *   <li>★ <b>没有 {@code &#64;origin} 头</b>（用户第四十轮）：原点不属于程序，它作为参考值存在**卡上**
 *       （{@code CardProgramStorage.readOrigin}）—— 条目坐标永远相对**执行这条程序的那条臂**（§19.7）；
 *       手写 {@code @origin …} 会被编译器硬报错（{@link ScriptParser} 的说明）；</li>
 *   <li>物品：{@code stack.getComponents().isEmpty()} ⇒ 只写 id；唯一组件是染色 ⇒ {@code #RRGGBB}；
 *       其余 ⇒ 完整 SNBT（键顺序由原版 codec 决定，是确定的）；空栈 ⇒ {@code {}}；</li>
 *   <li>坐标：<b>只有 {@link ArmProgramEntry#needsTarget()} 的操作才写</b> {@code x y z}（三个整数，相对
 *       **执行这条程序的那条臂** —— §19.7 起不再有任何空间记号）；不需要坐标的操作（等待 /
 *       信号 / 调用 / 退出 / 抛错）**一个数都不写** —— 它们的 {@code offset} 对运行时没有任何意义
 *       （用户第四十一轮就是被脚本里 {@code call 任务} 后面那串 {@code -3 0 -1} 问住的：那是残留噪声）。
 *       注意「过滤」的**容器模式**需要坐标（它是"读哪个容器"的**数据参数**），所以判据是条目级的；</li>
 *   <li>时间：{@code 5s / 200t / 2m}（不带单位只用于编译器容忍手写输入，规范形式永远带单位）；</li>
 *   <li>备注：条目 / 任务行**尾部**的块注释（{@code &ast;/…&ast;/}）；含注释终止符时退化为 {@code note="…"}
 *       （{@code \n} {@code \"} {@code \\} 转义）—— §12.3a 细则 3；</li>
 *   <li>无法用块注释表达时才用 {@code note="…"}，其它情况一律用块注释（那是唯一"人写起来舒服"的写法）。</li>
 * </ul>
 */
public final class ScriptDecompiler {

	/** 缩进：制表符一个（与 §12.3 草案一致，也是 TABS：项目里别的地方也用 tab 缩进）。 */
	/**
	 * 条目行的缩进：**两个空格**，不是制表符。
	 *
	 * <p>⚠️ 原来写的是 {@code "\t"}，游戏里显示成一个小方框、里面写着"HT" ——
	 * MC 的默认字体把 {@code \t} 当**控制字符**画（HT = Horizontal Tab），玩家看到的就是那个图标
	 * （用户第二十七轮续二十三的截图）。空格不会：既不赌字体，也和 §12.3 的示例写法一致。
	 * 解析侧不受影响：词法器本来就把 {@code \t} 当空白跳过，所以带制表符的老文件照样能编译。
	 */
	private static final String INDENT = "  ";

	private final HolderLookup.Provider registries;
	private final StringBuilder out = new StringBuilder();

	private ScriptDecompiler(HolderLookup.Provider registries) {
		this.registries = registries;
	}

	/** 反编译成脚本文本。<b>总能成功</b>（模型里没有"表达不了"的状态，除非备注超上限且没被夹过）。 */
	public static String decompile(ArmProgram program, HolderLookup.Provider registries) {
		ScriptDecompiler decompiler = new ScriptDecompiler(registries);
		decompiler.write(program);
		return decompiler.out.toString();
	}

	private void write(ArmProgram program) {
		// ⚠️ 这里曾经第一行写 `@origin x y z`（录制原点）。用户第四十轮把原点搬到了**卡上**
		// （`CardProgramStorage` 的 `ArmProgramOrigin` 键）⇒ 文本里不再有这一行，解析到它直接报错。
		// 为什么原点不该跟着文本走：条目的 offset 永远相对**执行它的那条臂**（§19.7），原点只是
		// "录制时减哪个数 + 画蓝框"的参考系；写进文本会让它随 `/sma code export` 复制给别人、
		// 又在导入时把别人卡上的参考点顶掉。**不要再把这一行加回来。**
		// 程序备注 = 第一条 task 之前的块注释（§12.3a 表格最后一行）
		if (notBlank(program.note))
			out.append(comment(program.note))
				.append('\n');
		for (ArmProgramTask task : program.tasks) {
			writeTask(task);
			out.append('\n');
		}
	}

	private void writeTask(ArmProgramTask task) {
		out.append("task ")
			.append(name(task.name));
		// ★ 任务头**不再写循环**（`loop=` / `loopN=` 已随任务级循环一起删除）：主任务恒为无限循环，
		//   子任务的轮次归**调用点**的 `repeat=`。所以任务头只剩 icon= 与备注。
		if (!task.icon.isEmpty())
			out.append(" icon=")
				.append(item(task.icon));
		if (notBlank(task.note))
			out.append(' ')
				.append(comment(task.note));
		out.append('\n');
		for (ArmProgramEntry entry : task.entries)
			writeEntry(entry);
	}

	private void writeEntry(ArmProgramEntry entry) {
		out.append(INDENT)
			.append(keyword(entry.op));
		writeOperands(entry);
		writeSuffixes(entry);
		out.append('\n');
	}

	// ————————————————————————— 各操作的操作数 —————————————————————————

	private void writeOperands(ArmProgramEntry entry) {
		OperationType op = entry.op;
		// ★ 坐标**只有需要目标的操作才写**（判据是**条目级**的 needsTarget()：过滤的容器模式需要坐标
		//   —— 那是"读哪个容器"的数据参数，必须永远写；手持模式不需要）。
		//   不需要坐标的操作（等待 / 信号 / 调用 / 退出 / 抛错）**一个数都不写**：
		//   这里原来还有一支"offset 非零也写出来"的写法，那是老口径（那会儿"新建条目默认取你正看着
		//   的方块"，于是每条都带着一串坐标）。用户第四十一轮正是被这个问住的 —— 脚本里
		//   `call 任务` 后面跟着 `-3 0 -1`，而「调用」是控制类操作：既不移动、也没有目标，
		//   运行时**根本不读**它 ⇒ 那三个数纯是噪声（"这条条目曾经是别的操作"留下的残留）。
		//   现在"这类操作的 offset 恒为 0"是模型定义的一部分（ArmProgramEntry.load 读完就自愈、
		//   编辑器切换操作时也清零），所以不写它**不会丢数据** —— 不变量①照样成立。
		if (entry.needsTarget()) {
			// 需要目标的操作：坐标是必填项，**永远写**（少写三个整数，读回来就是"这个目标在原点"）
			out.append(' ')
				.append(position(entry));
		}

		switch (op) {
			case WAIT -> out.append(' ')
				.append(time(entry.waitAmount, entry.waitUnit));
			// 过滤：本体操作数 = **过滤位置**（held / container）。位置的规范写法只有本源，
			// 所以它必须永远写出来（手写省略会让"容器模式"静默读回手持 —— 那是另一种语义）。
			case FILTER -> out.append(" source=")
				.append(filterSource(entry.filterSource));
			case SIGNAL -> {
				out.append(' ')
					.append(signalMode(entry.signalMode));
				if (entry.signalMode == SignalMode.RECEIVE)
					out.append(' ')
						.append(entry.waitForPowered ? "on" : "off");
				// ★ 只有「发送通电」才写 `place=`（用户口径：**中断没有持续时间** —— 它立即清除
				//   该频率，无论是否无限保持、有没有剩余时间；接收只是等通电）。
				//   规范形式里给中断写上 `place=5s` 会让玩家以为"5 秒后才断"，而运行时
				//   （ProgramRuntime 的 SIGNAL 分支 → `releaseSignal`）当场就把那一频段清掉了。
				else if (entry.hasSignalDuration())
					// 发信号：`place=` 永远是**数值 + 单位**（0t = 一直保持）；单位要写出来，
					// 否则"0s 持续"与"0t 持续"分不清（signalTicks 按单位换算）。
					out.append(" place=")
						.append(time(entry.waitAmount, entry.waitUnit));
			}
			case CALL -> out.append(' ')
				.append(name(entry.callTarget));
			case THROW -> out.append(' ')
				.append(quoted(entry.errorMessage));
			default -> {
				// 其余操作没有必要操作数
			}
		}
	}

	/** 坐标：{@code 1 2 3}（三个整数，相对**执行这条程序的那条臂**；§19.7 起不再有任何空间记号）。 */
	private static String position(ArmProgramEntry entry) {
		return entry.offset.getX() + " " + entry.offset.getY() + " " + entry.offset.getZ();
	}

	// ————————————————————————— 后缀 —————————————————————————

	private void writeSuffixes(ArmProgramEntry entry) {
		if (entry.delay != 0)
			out.append(" delay=")
				.append(entry.delay);
		// 「哪些操作有交互面」的判据**只有一处**（{@code ArmEntryText.needsFace}，卡片显示也用它）。
		// ⚠️ 「过滤」不在其中（用户第四十三轮）：容器模式只读内容 ⇒ 只要坐标，不再让玩家填朝向。
		//   但**写不写** face 仍由"模型里 face 是不是默认值"决定（下面那个 `|| entry.face != UP`）——
		//   手写的 `face=north` 因此在往返里不会丢，只是界面不再提供它。
		if (ArmEntryText.needsFace(entry.op) || entry.face != Direction.UP)
			out.append(" face=")
				.append(entry.face.getName()
					.toLowerCase(Locale.ROOT));
		switch (entry.op) {
			case TAKE, DEPOSIT -> {
				// 个数（取出 / 放入共用）：**本操作的默认值不写**（与 repeat=1 同一惯例）。
				// ⚠️ 默认值按操作给：取出 1 / **放入 64**（= 整摞 = 改造前行为，见 defaultCount）。
				//   所以判据必须问 defaultCount(op)，不能写死 `!= 1` —— 否则每条默认的放入
				//   都会被强制写成 `count=64`，而省略 count 的放入读回来又是 64，往返依然一致
				//   但文本里全是噪声；反过来若这里写死 1、解析侧默认 64，往返就会静默改数。
				// ⚠️ 模式却**只要非默认就必须写**，哪怕 count 还是默认值 —— "必须恰好 N 个"与"最多 N 个"
				// 是两种行为，不写就把语义丢了（而往返不变量①正是盯着这种静默丢失）。
				if (entry.count != ArmProgramEntry.defaultCount(entry.op))
					out.append(" count=")
						.append(entry.count);
				if (entry.countIsExact())
					out.append(" countmode=exact");
			}
			case UNLOAD -> {
				out.append(" repeat=")
					.append(entry.unloadRepeat == UnloadRepeat.UNTIL_EMPTY ? "until_empty" : "once");
				out.append(" amount=")
					.append(entry.unloadAmount == UnloadAmount.MAX ? "max" : "one_stack");
			}
			case CALL -> {
				// repeat=0 是**无限循环**（用户第四十轮），所以这里判 !=1 而不是 >1 —— 0 必须写出来
				if (entry.callRepeat != 1)
					out.append(" repeat=")
						.append(entry.callRepeat);
				// 调用偏移（Δ）：**零偏移不写**（与 repeat=1 / count=1 同一惯例：默认值不进文本）。
				// 规范写法是逗号那种（`shift=1,0,0`）—— 它在词法上是一个记号，往返最稳；
				// 空格写法（`shift=1 0 0`）解析侧也吃，但反编译器永远只输出逗号那种。
				if (entry.hasCallShift())
					out.append(" shift=")
						.append(entry.callShift.getX())
						.append(',')
						.append(entry.callShift.getY())
						.append(',')
						.append(entry.callShift.getZ());
			}
			case SIGNAL -> {
				out.append(" freq=")
					.append(item(frequencyStack(entry, true)))
					.append(" + ")
					.append(item(frequencyStack(entry, false)));
				// 「接收距离」：**只有「收」且非 0 才写**（用户口径：距离只约束「收」，发出去不管）。
				// 0 = 只有同一格才算收到 —— 那是默认值，与 count=1 / repeat=1 同一惯例不进文本。
				// ⚠️ 判据只看 hasSignalRange()（= 收模式且 > 0）：发送 / 中断即使模型里残留一个非 0 值
				//   也**一个字都不写**（解析侧对它们写了 range= 是明确报错，见 ScriptParser）。
				if (entry.hasSignalRange())
					out.append(" range=")
						.append(entry.signalRange);
			}
			// 过滤的**幽灵过滤器物品**：复用 icon=/freq= 那套物品写法（简写 id / #RRGGBB / 完整 SNBT），
			// 所以"列表过滤器"（create:filter 带 FILTER_ITEMS 组件）会写成完整 SNBT，
			// 手写的 `item=create:filter` 读回来则是"没配过条目的列表过滤器"（= 匹配一切）。
			// 空栈写成 `{}`（与 freq 的空槽一致）—— 空过滤器是个有意义的默认值，不能省掉。
			case FILTER -> out.append(" item=")
				.append(item(entry.filterItem));
			default -> {
				// 其余操作没有这类参数
			}
		}
		// 时限（failAmount）与"超时后去哪"是两个字段，**分开写**才不会丢：
		//   `timeout=0t` 单写 ⇒ 重试窗口为 0（立刻走去向）；
		//   去向是默认的 NONE ⇒ 还要写 `fail=stop`，否则读回来会变成"一直重试"。
		if (entry.failAmount >= 0)
			out.append(" timeout=")
				.append(time(entry.failAmount, entry.failUnit));
		if (entry.onSuccess == SkipAction.SKIP_TO)
			out.append(" ok=goto ")
				.append(name(entry.successTarget));
		if (entry.onTimeout == SkipAction.SKIP_TO)
			out.append(" fail=goto ")
				.append(name(entry.timeoutTarget));
		else if (entry.onTimeout == SkipAction.CONTINUE)
			out.append(" fail=skip");
		else if (entry.failAmount >= 0)
			out.append(" fail=stop");
		if (notBlank(entry.note))
			out.append(' ')
				.append(noteSuffix(entry.note));
		if (!entry.icon.isEmpty())
			out.append(" icon=")
				.append(item(entry.icon));
	}

	/** 过滤位置的规范写法（{@code HELD → held}、{@code CONTAINER → container}）。 */
	private static String filterSource(ArmProgramEntry.FilterSource source) {
		return source == ArmProgramEntry.FilterSource.CONTAINER ? "container" : "held";
	}

	/** 备注的规范写法：块注释；含注释终止符 {@code *&#47;} 时退化为 {@code note="…"}（§12.3a 细则 3）。 */
	private static String noteSuffix(String note) {
		return note.contains("*/") ? "note=" + quoted(note) : comment(note);
	}

	/** 块注释：{@code /*} 与注释终止符之间**原样**放进去（含换行），所以是"无损"的。 */
	private static String comment(String body) {
		return "/*" + body + "*/";
	}

	// ————————————————————————— 物品的三种写法（§12.1） —————————————————————————

	/**
	 * 物品的**规范形式**：空 ⇒ {@code {}}；无组件 ⇒ 只写 id；唯一组件是染色 ⇒ {@code #RRGGBB}；
	 * 其余 ⇒ 完整 SNBT（直接复用原版 {@code ItemStack.CODEC}，所以旗帜图案这类能无损往返）。
	 */
	private String item(ItemStack stack) {
		if (stack == null || stack.isEmpty())
			return "{}";
		// ⚠️ 判据必须来自**编码后的 SNBT 本身**，不能问 `stack.getComponents().isEmpty()` ——
		//    后者包含物品原型里的默认组件（max_stack_size 之类），**永远非空**，实测导致
		//    所有物品都被写成完整 SNBT（连铁锭都写成 `{count:1,id:"minecraft:iron_ingot"}`），
		//    "无组件只写 id"与"唯一染色用 #RRGGBB"两条规范形式一条都没生效。
		//    以 codec 的输出为准，同时保证"判据与输出同源"（判据写两份必然漂移，决策 68/85）。
		Tag tag = ItemStack.CODEC
			.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
			.getOrThrow();
		if (tag instanceof CompoundTag compound) {
			if (!compound.contains("components"))
				return idOf(stack);
			CompoundTag components = compound.getCompound("components");
			if (components.getAllKeys()
				.size() == 1 && components.contains("minecraft:dyed_color")) {
				// 染色是**唯一参与频率身份**的组件（§12.2），所以值得有个短写法；
				// 反过来，只有"唯一组件就是染色"时才用短写法 —— 别的组件会让它看起来像没带组件。
				return idOf(stack) + String.format(Locale.ROOT, "#%06X",
					stack.get(DataComponents.DYED_COLOR)
						.rgb() & 0xFFFFFF);
			}
			// `count=1` 是 SNBT 的默认值（§12.1）⇒ 规范形式省掉它（键顺序是字典序，省了才有唯一答案）
			if (compound.getInt("count") == 1)
				compound.remove("count");
		}
		return snbt(tag);
	}

	private static String idOf(ItemStack stack) {
		ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return key.toString();
	}

	/**
	 * 把标签写成**一行**的 SNBT（键带引号、字符串转义、不加缩进）。
	 *
	 * <p>为什么不用原版的工具：`CompoundTag.toString()` 走的是 {@code NbtUtils.prettyPrint}
	 * ——那是**给人看的**格式（换行、缩进、键不加引号、数组写成 {@code int[3] {…}}），
	 * 而我们的文本面是"一条条目一行"，玩家还会把整段复制进聊天栏。所以键顺序按
	 * {@code getAllKeys()}（CompoundTag 内部就是 TreeMap ⇒ **确定的字典序**，与反编译器的确定性一致）。
	 */
	private static String snbt(Tag tag) {
		StringBuilder sb = new StringBuilder();
		snbt(sb, tag);
		return sb.toString();
	}

	private static void snbt(StringBuilder sb, Tag tag) {
		switch (tag) {
			case CompoundTag compound -> {
				sb.append('{');
				boolean first = true;
				for (String key : compound.getAllKeys()) {
					if (!first)
						sb.append(',');
					first = false;
					// ⚠️ 键**只在安全时才裸写**：原版 TagParser 的裸键字符集只有 `[A-Za-z0-9._+-]`
					//    （`StringReader.readUnquotedString`），所以 `minecraft:dyed_color` 这种必须**加引号**，
					//    否则原版直接报 "Expected '}' at position …"。自检里那条"SNBT 能被原版 TagParser 吃下"
					//    的断言就是这么抓到的（此前注释里写"键不加引号两种都吃"是错的）。
					sb.append(snbtKey(key))
						.append(':');
					snbt(sb, compound.get(key));
				}
				sb.append('}');
			}
			case ListTag list -> {
				sb.append('[');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0)
						sb.append(',');
					snbt(sb, list.get(i));
				}
				sb.append(']');
			}
			// 数组一律写成 list（SNBT 里 `[1,2,3]` 反过来解析成 IntArray，ItemStack.CODEC 照收）——
			// 免去"类型头"那种只在编辑器里好看、在命令行里没法用的写法。
			case IntArrayTag array -> {
				int[] values = array.getAsIntArray();
				sb.append('[');
				for (int i = 0; i < values.length; i++) {
					if (i > 0)
						sb.append(',');
					sb.append(values[i]);
				}
				sb.append(']');
			}
			case StringTag text -> sb.append(quote(text.getAsString()));
			default -> sb.append(tag.getAsString());
		}
	}

	/** SNBT 的键：只有 {@code [A-Za-z0-9._+-]} 能裸写（原版 `readUnquotedString` 的字符集），其它一律加引号。 */
	private static String snbtKey(String key) {
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			boolean bare = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
				|| c == '.' || c == '+' || c == '-';
			if (!bare)
				return quote(key);
		}
		return key;
	}

	/** SNBT 里的字符串：转义 `\` 与 `"`（`\n` 用真正的换行会破坏"一条条目一行"）。 */
	private static String quote(String text) {
		StringBuilder sb = new StringBuilder(text.length() + 2);
		sb.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.append('"')
			.toString();
	}

	/** 频率里的两格物品（{@code first = true} 取第一格）；没绑频率时是空栈。 */
	private static ItemStack frequencyStack(ArmProgramEntry entry, boolean first) {
		if (entry.frequency == null)
			return ItemStack.EMPTY;
		Frequency frequency = first ? entry.frequency.getFirst() : entry.frequency.getSecond();
		if (frequency == null)
			return ItemStack.EMPTY;
		ItemStack stack = frequency.getStack();
		return stack == null ? ItemStack.EMPTY : stack;
	}

	// ————————————————————————— 文本小工具 —————————————————————————

	private static String keyword(OperationType op) {
		return op.name()
			.toLowerCase(Locale.ROOT);
	}

	private static String signalMode(SignalMode mode) {
		return switch (mode) {
			case RECEIVE -> "recv";
			case SEND_ON -> "send_on";
			case SEND_OFF -> "send_off";
		};
	}

	/** 时间：数值 + 单位（{@code 5s} / {@code 200t} / {@code 2m}）。 */
	private static String time(int amount, WaitUnit unit) {
		return amount + switch (unit) {
			case TICK -> "t";
			case SECOND -> "s";
			case MINUTE -> "m";
		};
	}

	/**
	 * 任务名 / 跳转落点：只在**必要时**加引号（纯单词最干净，也是草案里的写法）。
	 *
	 * <p>什么时候必须加引号：含空白、引号、反斜杠，或与语法记号冲突（{@code @} 开头、含 {@code =} / {@code +}）。
	 * 不加引号的话 {@code task 我的 任务} 会被切成两段 —— 那是**解析不出来**的名字，不是风格问题。
	 */
	private static String name(String raw) {
		String value = raw == null ? "" : raw;
		if (value.isEmpty())
			return "\"\"";
		boolean needsQuote = value.chars()
			.anyMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '"' || c == '\\' || c == '=')
			|| value.startsWith("@") || value.startsWith("#") || value.contains("+") || value.startsWith("/*");
		return needsQuote ? quoted(value) : value;
	}

	/** 引号串：{@code \n} {@code \"} {@code \\} 转义（§12.3：{@code note="…"} 的保底形式就是这个）。 */
	private static String quoted(String raw) {
		String value = raw == null ? "" : raw;
		StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.append('"')
			.toString();
	}

	private static boolean notBlank(String text) {
		return text != null && !text.isEmpty();
	}

}
