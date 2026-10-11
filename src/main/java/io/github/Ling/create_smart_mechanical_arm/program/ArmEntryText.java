package io.github.Ling.create_smart_mechanical_arm.program;

import java.util.ArrayList;
import java.util.List;

import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry.WaitUnit;
import io.github.Ling.create_smart_mechanical_arm.program.OperationType;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * 把一条操作渲染成可读文本 —— <b>卡片标题芯片与护目镜 HUD 共用这一份</b>。
 *
 * <p>为什么单独抽一个类：用户要求护目镜按这个结构显示当前步骤（第三轮反馈）：
 * <pre>
 * 操作 必要属性（例如坐标、朝向）
 * - 可选属性1
 * - 可选属性2
 * </pre>
 * 而"操作 + 必要属性"正是卡片标题芯片那句话、"可选属性"正是卡片上的那些胶囊。
 * 两边各写一份必然越走越远（HUD 说"USE rel-2,1,0 onSuccess=SKIP_TO(9)"、卡片说"使用 +-2,1,0"，
 * 用户之前看到的正是这种分裂）—— 所以文案与判定都收在这里，屏幕和 HUD 都从这里取。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #base(ArmProgramEntry)}：第一行 —— 操作名 + 必要属性（坐标 / 朝向 / 时长 / 极性 / 调用目标）；</li>
 *   <li>{@link #props(ArmProgramEntry)}：其余行 —— **只列偏离默认值的**可选属性（与卡片"默认值不显示"一致）。</li>
 * </ul>
 */
public final class ArmEntryText {

	private ArmEntryText() {
	}

	// ————————————————————————— 第一行：操作 + 必要属性 —————————————————————————

	/**
	 * 操作 + 必要属性。
	 *
	 * <p>必要属性就是编辑器第二行里那些**必填**控件：位置类的坐标、左右键的朝向、等待类的时长/极性、
	 * 调用的目标任务。可选属性（成功后 / 失败后 / 备注 / 个数…）不在这里，见 {@link #props}。
	 */
	public static Component base(ArmProgramEntry entry) {
		return base(entry, false);
	}

	/**
	 * 护目镜专用：与 {@link #base} 相同，但把「等待信号」的频率物品名也写进去。
	 *
	 * <p>卡片上频率是**两个图标**（{@code ArmIcons.secondIconFor}），文字里再写一遍既冗余又把芯片撑宽；
	 * 而工具提示没有图标位，只能写名字。这就是两者唯一的差别 —— 所以是同一个方法加一个开关，
	 * 而不是各写一份（各写一份就会像以前那样两边说法不一致）。
	 */
	public static Component baseWithItems(ArmProgramEntry entry) {
		return base(entry, true);
	}

	private static Component base(ArmProgramEntry entry, boolean withFrequencyItems) {
		StringBuilder suffix = new StringBuilder();
		switch (entry.op) {
			case WAIT -> suffix.append("  ")
				.append(entry.waitAmount)
				.append(unitSuffix(entry.waitUnit));
			case WAIT_REDSTONE -> suffix.append("  ")
				.append(tr(entry.waitForPowered ? "on" : "off").getString());
			case WAIT_SIGNAL -> {
				suffix.append("  ")
					.append(tr(entry.waitForPowered ? "on" : "off").getString());
				// 频率物品是"等哪一对物品"的必需信息：卡片上它是两个图标，而工具提示里没有图标，
				// 所以那里只能把名字写出来（空槽位不写，免得出现 "Air + Air"）。
				if (withFrequencyItems) {
					String freq = frequencyText(entry);
					if (!freq.isBlank())
						suffix.append("  ")
							.append(freq);
				}
			}
			case CALL -> {
				suffix.append("  → ")
					.append(entry.callTarget.isBlank() ? tr("call.none").getString() : entry.callTarget);
				// 调用次数归**调用点**（用户第二十七轮：把循环次数放在调用上），所以写在调用后面
				if (entry.callRepeat > 1)
					suffix.append("  ×")
						.append(entry.callRepeat);
			}
			default -> {
				if (entry.op.requiresTarget())
					suffix.append("  ")
						.append(entry.absolute ? "@" : "+")
						.append(entry.offset.getX())
						.append(',')
						.append(entry.offset.getY())
						.append(',')
						.append(entry.offset.getZ());
			}
		}
		// 朝向是左右键的必填项（用户点名要它出现在必要属性里）：坐标之外再报一个面
		if (needsFace(entry.op))
			suffix.append("  ")
				.append(tr("opt." + entry.face.getName()).getString());
		return tr("chip.entry", opName(entry.op), suffix.toString());
	}

	/** 需要"交互面"的操作（左键/右键语义）—— 与编辑器里的判定同一处依据。 */
	public static boolean needsFace(OperationType op) {
		return op == OperationType.MINE || op == OperationType.ATTACK || op == OperationType.USE
			|| op == OperationType.USE_SNEAK;
	}

	/**
	 * 「等待信号」的极性文字（通 / 断）。
	 *
	 * <p>卡片上它排在**两个信号物品槽之后**（用户定的读法：图标 · 等信号 · 物品 · 物品 · 开/关），
	 * 所以单独取出来用，而不是拼进 {@link #base} 的字符串里；护目镜那边没有物品槽，仍旧拼在行内。
	 */
	public static Component signalPolarity(ArmProgramEntry entry) {
		return tr(entry.waitForPowered ? "on" : "off");
	}

	/** 「等待信号」绑定的那对频率物品名（形如 {@code 铁锭 + 红石}）；没绑或空槽返回空串。 */
	private static String frequencyText(ArmProgramEntry entry) {
		if (entry.frequency == null)
			return "";
		String first = entry.frequency.getFirst()
			.getStack()
			.isEmpty() ? "" : entry.frequency.getFirst()
				.getStack()
				.getHoverName()
				.getString();
		String second = entry.frequency.getSecond()
			.getStack()
			.isEmpty() ? "" : entry.frequency.getSecond()
				.getStack()
				.getHoverName()
				.getString();
		if (first.isBlank() && second.isBlank())
			return "";
		if (second.isBlank())
			return first;
		if (first.isBlank())
			return second;
		return first + " + " + second;
	}

	// ————————————————————————— 可选属性（每行一条） —————————————————————————

	/**
	 * 只列**偏离默认值**的可选属性，顺序与编辑器里一致：
	 * 成功后 → 失败后 → 动作参数（个数 / 卸货方式） → 备注。
	 *
	 * <p>终结操作（退出 / 抛错）执行之后就不在这个任务里了，没有"成功后/失败后"可谈 —— 与编辑器同一条规则。
	 */
	public static List<Component> props(ArmProgramEntry entry) {
		List<Component> out = new ArrayList<>();
		if (entry.op != OperationType.BREAK && entry.op != OperationType.THROW) {
			if (entry.onSuccess != SkipAction.NONE)
				out.add(successText(entry));
			if (entry.onTimeout != SkipAction.NONE || entry.failAmount >= 0)
				out.add(failureText(entry));
		}
		switch (entry.op) {
			case TAKE -> {
				if (entry.count != 1)
					out.add(countText(entry));
			}
			case UNLOAD -> {
				if (entry.unloadRepeat != UnloadRepeat.ONCE)
					out.add(unloadRepeatText(entry));
				if (entry.unloadAmount != UnloadAmount.ONE_STACK)
					out.add(unloadAmountText(entry));
			}
			default -> {
			}
		}
		if (!entry.note.isBlank())
			out.add(noteText(entry));
		return out;
	}

	// ————————————————————————— 各属性的一句话 —————————————————————————

	public static Component successText(ArmProgramEntry entry) {
		return entry.onSuccess == SkipAction.SKIP_TO ? tr("chip.on_success_jump", entry.successTarget)
			: tr("chip.on_success_stay");
	}

	/**
	 * 失败后 = 「多久之后」+「做什么」合成一句（与卡片胶囊同一句）。
	 *
	 * <p>⚠️ **没设时限时不能写"立刻/停下"**（用户第二十二轮实测反馈："此处实际上不是'停下'，而是重试"）：
	 * {@code failAmount < 0} 的语义是"一直重试"（{@code ProgramRuntime.handleFailure} 里
	 * {@code timeoutTicks() < 0} 直接 RETRY），去向根本到不了 —— 写成"立刻停下"既自相矛盾，
	 * 也解释了为什么玩家看到的是重试。所以那种情况只报"一直重试"。
	 *
	 * <p>⚠️⚠️ 但那句话**只对"有重试窗口"的操作成立**（第二十七轮用户实测的 bug）：攻击 / 调用属于"硬失败"
	 * —— 编辑器根本不给他们时限控件（{@link OperationType#hasRetryWindow()} 为假），所以 {@code failAmount}
	 * 恒为 -1，而失败是**立刻**发生、去向**马上生效**的。拿 -1 去报"一直重试"就与实际行为刚好相反：
	 * 玩家选了「失败后 跳转 2」，卡片上却写着"一直重试（没设时限）"。这类操作直接报去向。
	 */
	public static Component failureText(ArmProgramEntry entry) {
		if (!entry.op.hasRetryWindow())
			return switch (entry.onTimeout) {
				case CONTINUE -> tr("chip.fail_skip");
				case SKIP_TO -> tr("chip.fail_jump", entry.timeoutTarget);
				case NONE -> tr("chip.fail_stop");
			};
		if (entry.failAmount < 0)
			return tr("chip.fail_retry_forever");
		String limitText = entry.failAmount <= 0 ? tr("chip.fail_now").getString()
			: tr("chip.fail_in", entry.failAmount + unitSuffix(entry.failUnit))
				.getString();
		return tr("chip.fail", limitText, switch (entry.onTimeout) {
			case CONTINUE -> tr("chip.fail_skip").getString();
			case SKIP_TO -> tr("chip.fail_jump", entry.timeoutTarget).getString();
			case NONE -> tr("chip.fail_stop").getString();
		});
	}

	public static Component countText(ArmProgramEntry entry) {
		return tr("chip.count", entry.count);
	}

	/** 调用点的轮次胶囊：{@code 调用 3 次}（用户第二十七轮："把循环次数放在调用上"）。 */
	public static Component callRepeatText(ArmProgramEntry entry) {
		return tr("chip.call_repeat", entry.callRepeat);
	}

	public static Component unloadRepeatText(ArmProgramEntry entry) {
		return tr(entry.unloadRepeat == UnloadRepeat.UNTIL_EMPTY ? "chip.until_empty" : "chip.once");
	}

	public static Component unloadAmountText(ArmProgramEntry entry) {
		return tr(entry.unloadAmount == UnloadAmount.MAX ? "chip.max" : "chip.one_stack");
	}

	/**
	 * 「备注」胶囊上最多显示几个字（全文在悬停提示里，见 {@link #noteText}）。
	 *
	 * <p>8 这个数是**倒推**出来的，不是随手取的：胶囊区右边界是 GUI 204（局部 179），
	 * 行尾的「添加属性」＋ 贴图宽 19、画在"行末 - 3"，所以最后一枚胶囊宽度不能超过
	 * {@code 179 - 19 - 26 - 1 = 133}，否则 ＋ 会被顶到裁剪区外面 ——
	 * 而 8 个字（含 "备注 " 前缀与省略号）实测宽度约 119，留了余量。
	 */
	public static final int NOTE_CHIP_CHARS = 8;

	/**
	 * 「备注」胶囊 = **摘要**：前 {@link #NOTE_CHIP_CHARS} 个字 + 省略号，换行压成空格。
	 *
	 * <p>为什么不在胶囊上摊开整条备注（用户第二十七轮实测反馈：<i>"备注太长会把添加属性的按键挤到外面，
	 * 并且即使超出去了，没有显示，也可以点击"</i>）：备注最长 128 字，而胶囊宽度封顶 150 ——
	 * 一条长备注就把整行占满，行尾的 ＋ 被推到裁剪区外。既然全文在悬停提示里（卡片标题芯片与编辑器备注框
	 * 都挂了），胶囊只留一个开头就够：扫一眼知道"这条有备注、大概是啥"。
	 */
	public static String noteSummary(String note) {
		String flat = note == null ? "" : note.replace("\n", " ")
			.strip();
		return flat.length() > NOTE_CHIP_CHARS ? flat.substring(0, NOTE_CHIP_CHARS) + "…" : flat;
	}

	/** 备注胶囊的文案（摘要 + 前缀）。 */
	public static Component noteText(ArmProgramEntry entry) {
		return tr("chip.note", noteSummary(entry.note));
	}

	/**
	 * 备注 → tooltip 用的**逐行**文本（每个元素就是一行）。
	 *
	 * <p>⚠️ 为什么必须是"多行数组"而不是"一段带 {@code \n} 的文字"：NeoForge 的
	 * {@code ClientHooks.gatherTooltipComponentsFromElements} **只在 tooltip 放不下屏幕（或调用方限宽）时**
	 * 才走 {@code splitLine}（那里才会按 {@code \n} 与宽度断行）；装得下时它把每个元素直接包成一个
	 * **单行**的 {@code ClientTextTooltip} —— 于是 {@code \n} 被当成普通字符，字体里找不到字形，
	 * 就画成一个"缺字方框"。用户实测：<i>"我把鼠标移动到芯片上，并没有实现多行排版（这个图标代表 \n）"</i>。
	 *
	 * <p>所以"显示多行"的唯一稳妥做法就是：**一行一个元素**。
	 */
	public static List<String> noteLines(String note) {
		if (note == null || note.isEmpty())
			return List.of();
		return List.of(note.split("\n", -1));
	}

	// ————————————————————————— 杂项 —————————————————————————

	/** 单位的短后缀：t / s / m（用户第四轮："时间单位可以简写为 t（刻）s（秒）m（分）"）。 */
	public static String unitSuffix(WaitUnit unit) {
		return switch (unit) {
			case TICK -> "t";
			case SECOND -> "s";
			case MINUTE -> "m";
		};
	}

	/** 操作的中文名（DEPLOY=组装：Create 的 deploying 官方译名"使用"给了 USE）。 */
	public static Component opName(OperationType op) {
		return tr("opt." + op.name()
			.toLowerCase(java.util.Locale.ROOT));
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable("create_smart_mechanical_arm.gui.editor." + key, args);
	}

	// ————————————————————————— 自检 —————————————————————————

	/**
	 * 客户端自检：这个"结构"是用户点名要的（第一行=操作+必要属性，其余行=可选属性），
	 * 结构错了在游戏里只会看到"又变回一长串"，所以用断言钉住：
	 * ① 默认条目只有 1 行（没有可选属性）；② 设了属性就一行一条；③ 坐标与朝向必须在第一行里。
	 */
	public static String selfCheck() {
		ArmProgramEntry bare = new ArmProgramEntry();
		bare.op = OperationType.TAKE;
		bare.offset = new net.minecraft.core.BlockPos(1, 2, 3);
		List<Component> bareProps = props(bare);
		String baseText = base(bare).getString();
		if (!bareProps.isEmpty())
			return "默认条目不该有可选属性行：" + bareProps;
		if (!baseText.contains("1,2,3"))
			return "第一行里没有坐标：" + baseText;

		ArmProgramEntry full = new ArmProgramEntry();
		full.op = OperationType.USE;
		full.offset = new net.minecraft.core.BlockPos(-2, 1, 0);
		full.face = net.minecraft.core.Direction.UP;
		full.onSuccess = SkipAction.SKIP_TO;
		full.successTarget = "9";
		full.failAmount = 5;
		full.onTimeout = SkipAction.CONTINUE;
		full.note = "备注";
		String fullBase = base(full).getString();
		List<Component> fullProps = props(full);
		if (!fullBase.contains("-2,1,0"))
			return "第一行里没有坐标：" + fullBase;
		// 朝向的断言**不能写死中文**（原来是 contains("上")）—— en_us 客户端会必然误报。
		// 断的是"第一行用的是 opt.<朝向> 这个键"，与语言无关。
		String upText = tr("opt." + Direction.UP.getName()).getString();
		if (!fullBase.contains(upText))
			return "左右键的朝向没有进第一行（用户点名要它当必要属性）：" + fullBase;
		if (fullProps.size() != 3)
			return "可选属性行数不对（期望 成功后/失败后/备注 共 3 行）：" + fullProps;

		// 没设时限（failAmount < 0）= 一直重试，绝不能写成"立刻/停下"
		//（用户第二十二轮实测："此处实际上不是'停下'，而是重试"）
		// 断言同样**不能写死中文**：比的是"走的是重试那条分支、且不是停下那条"，
		// 两个对照值都从同一个 key 解析出来，en_us / zh_cn 下都成立。
		ArmProgramEntry noLimit = new ArmProgramEntry();
		noLimit.op = OperationType.TAKE;
		noLimit.failAmount = -1;
		String noLimitText = failureText(noLimit).getString();
		String retryForever = tr("chip.fail_retry_forever").getString();
		String stopText = tr("chip.fail_stop").getString();
		if (!noLimitText.equals(retryForever) || noLimitText.equals(stopText))
			return "没设时限的「失败后」写成了「" + noLimitText + "」—— 实际行为是一直重试";

		// ★ 硬失败的操作（攻击 / 调用）**没有**重试窗口：它们没有时限控件，failAmount 恒为 -1，
		// 而失败是立刻发生、去向马上生效的。这时候还报"一直重试（没设时限）"就与实际行为刚好相反
		//（用户第二十七轮实测：选了「失败后 跳转 2」，卡片上写的是"一直重试"）。
		ArmProgramEntry hardCall = new ArmProgramEntry();
		hardCall.op = OperationType.CALL;
		hardCall.failAmount = -1; // 编辑器根本不给它时限 ⇒ 真实数据就是这个样子
		hardCall.onTimeout = SkipAction.SKIP_TO;
		hardCall.timeoutTarget = "2";
		String hardText = failureText(hardCall).getString();
		if (hardText.equals(retryForever) || !hardText.contains("2"))
			return "「调用」这种硬失败选了『失败后 跳转 2』，卡片却写成「" + hardText + "」—— 显示与行为相反";
		ArmProgramEntry hardPunch = new ArmProgramEntry();
		hardPunch.op = OperationType.MINE; // 挖掘属于"硬失败"那一类（没有重试窗口）
		hardPunch.failAmount = -1;
		hardPunch.onTimeout = SkipAction.NONE;
		if (failureText(hardPunch).getString()
			.equals(retryForever))
			return "「攻击」的「失败后 停下」被写成了「一直重试」—— 硬失败没有重试窗口";

		return "OK(默认1行 / 设了3个属性=4行)";
	}

}
