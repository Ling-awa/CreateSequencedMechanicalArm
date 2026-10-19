package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.ArrayList;
import java.util.List;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.WaitUnit;

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
	 * <p>必要属性就是编辑器第二行里那些**必填**控件：位置类的坐标与个数、左右键的朝向、
	 * 等待类的时长/极性、收发信号的模式与接收距离、调用的目标任务。
	 * 可选属性（成功后 / 失败后 / 备注 / 卸货方式…）不在这里，见 {@link #props}。
	 */
	public static Component base(ArmProgramEntry entry) {
		return base(entry, false);
	}

	/**
	 * 护目镜专用：与 {@link #base} 相同，但把「等待信号」的频率物品名也写进去。
	 *
	 * <p>卡片上频率是**两个图标**（两枚物品槽，见 {@code ArmProgramScreen.renderSignalChip}），
	 * 文字里再写一遍既冗余又把芯片撑宽；
	 * 而工具提示没有图标位，只能写名字。这就是两者唯一的差别 —— 所以是同一个方法加一个开关，
	 * 而不是各写一份（各写一份就会像以前那样两边说法不一致）。
	 */
	public static Component baseWithItems(ArmProgramEntry entry) {
		return base(entry, true);
	}

	private static Component base(ArmProgramEntry entry, boolean withFrequencyItems) {
		StringBuilder suffix = new StringBuilder();
		switch (entry.op) {
			case WAIT -> suffix.append(" ")
				.append(entry.waitAmount)
				.append(unitSuffix(entry.waitUnit));
			case SIGNAL -> {
				// 短语本身随模式变（接收信号 / 发送信号 / 中断信号，见 signalHeadText），所以这里只补**尾巴**：
				// 发 + 持续 > 0 → 时长（1s）；收 + 老卡 redpow=false → 等断电（界面不给开关，
				// 但绝不能静默隐瞒它仍然在等断电）；其余（发 + 0 = 一直保持）什么都不写。
				String tail = signalTailText(entry).getString();
				if (!tail.isEmpty())
					suffix.append(" ")
						.append(tail);
				// ★ 用户本轮口径：「收发信号有距离」这件事必须**显式写在卡片第一行**，
				//   而不是让玩家翻第二排的属性胶囊才知道（原话："收 / 发信号有距离这种事情我觉得得显式的
				//   告诉玩家，而不是让玩家编辑其他属性的时候再找"）—— 所以「距离」和「个数」一样，
				//   属于**基础内容**：它现在只在这里出现，属性胶囊里那一份已经删掉了。
				//   判据只问 {@link ArmProgramEntry#hasSignalRange()}（收模式 + 半径 > 0），别在这里
				//   再写一遍 `signalMode == RECEIVE && signalRange > 0`。
				if (entry.hasSignalRange())
					suffix.append(" ")
						.append(signalRangeText(entry).getString());
				// 频率物品是"等哪一对物品"的必需信息：卡片上它是两个图标，而工具提示里没有图标，
				// 所以那里只能把名字写出来（空槽位不写，免得出现 "Air + Air"）。
				if (withFrequencyItems) {
					String freq = frequencyText(entry);
					if (!freq.isBlank())
						suffix.append(" ")
							.append(freq);
				}
			}
			case CALL -> {
				suffix.append(" → ")
					.append(entry.callTarget.isBlank() ? tr("call.none").getString() : entry.callTarget);
				// 调用次数归**调用点**（用户第二十七轮：把循环次数放在调用上），所以写在调用后面。
				// 0 = 无限循环（用户第四十轮）⇒ 写 ×∞，别让"×0"看起来像"不跑"
				if (entry.callRepeat == ArmProgramEntry.CALL_REPEAT_INFINITE)
					suffix.append(" ×∞");
				else if (entry.callRepeat > 1)
					suffix.append(" ×")
						.append(entry.callRepeat);
				// ★ 调用偏移（Δ）**不再写进第一行**（用户："调用偏移不显示在第一行"）：它只在第二行的
				//   胶囊里出现（见 {@link #callShiftText} 与编辑器的属性表）。
				//   理由：第一行是"操作 + 必要属性"，而偏移是**可选**属性 —— 混进标题芯片里会让
				//   "调用 搬一趟 ×2 +偏移 1,0,0" 读起来像三个必填项，卡片也就这么宽了。
				//   ⚠️ 反证：把这几行加回来 ⇒ 自检 checkSignalRange/B3 的那条"第一行不含偏移"立刻变红。
			}
			case FILTER -> {
				// 过滤的必需信息 = 过滤位置（用户口径：必要输入为"幽灵物品 + 过滤位置"）。
				// 位置名走 entry.filter_source.* 那个键（与编辑器第二行同一个来源，不写死中文）。
				suffix.append(" ")
					.append(tr(entry.filterSource == ArmProgramEntry.FilterSource.CONTAINER
						? "filter_source.container"
						: "filter_source.held")
							.getString());
				// 幽灵物品的名字：**空过滤器 = 匹配一切**，那是个有意义的默认，所以显式写"未设置"
				// 而不是省略（省略会让人以为这条没配好 —— 其实它一定成功）。
				suffix.append(" → ")
					.append(entry.filterItem.isEmpty() ? tr("filter.none").getString()
						: entry.filterItem.getHoverName()
							.getString());
			}
			default -> {
				// ★ 个数先写、坐标在后（顺序**不能反**）：标题芯片宽度封顶（{@code renderChip} 那侧
				//   文字按 120px 截断），个数是"这条要搬多少"的必读信息，必须留在前面；
				//   坐标被截掉一截反而还能从编辑器里看全。
				// ★ 个数**永远显示**（含默认值：取出 1 / 放入 64 / 收存 64）：用户口径是"防止忘记默认 64 个"——
				//   藏起默认值就会让人以为这条没配过数量。判据只问 {@link OperationType#hasCount()}
				//   （单点），别在这里写 `op == TAKE || op == DEPOSIT`（「收存」走的正是这一支：
				//   它的第一行因此自然读作「收存 最多 64 个」，不需要为它写坐标 / 朝向）。
				//   反证：把这两行删掉 ⇒ 自检里"取出默认必须含「最多 1 个」"那条立刻变红。
				if (entry.op.hasCount())
					suffix.append(" ")
						.append(countText(entry).getString());
				// ★ 问**条目级**的 needsTarget()：过滤的容器模式有坐标（且胶囊上要看得见），
				//   手持模式则一个字都不写（用户口径：手持模式不写坐标）。
				if (entry.needsTarget())
					// 显示的是**偏移**（模型里那个值），不是"原点 + 偏移"。用户定的口径（§19.7）：
					// **臂运行时就是"臂坐标 + 偏移"**（{@code ProgramRuntime.offsetOf} 直接返回 offset，
					// 卡上的原点不参与运行时、插卡也不平移），所以这一行读出来就是"相对执行它的那条臂"
					// —— `0,0,0` 就是那条臂自己。显示世界位置反而会让人以为臂在用绝对坐标（§19.6）。
					suffix.append(" +")
						.append(entry.offset.getX())
						.append(',')
						.append(entry.offset.getY())
						.append(',')
						.append(entry.offset.getZ());
			}
		}
		// 朝向是左右键的必填项（用户点名要它出现在必要属性里）：坐标之外再报一个面。
		// ⚠️ 过滤的**手持模式不报面** —— 与"手持模式不写坐标"同一条口径（那时面根本没有用）。
		//   {@link #needsFace} 里给它放行，是为了容器模式那一支能共用同一套控件代码。
		boolean faceMeaningful = needsFace(entry.op)
			&& !(entry.op == OperationType.FILTER && !entry.needsTarget());
		if (faceMeaningful)
			suffix.append(" ")
				.append(tr("opt." + entry.face.getName()).getString());
		// 「收发信号」的**操作名**就是随模式变的短语（接收信号 / 发送信号 / 中断信号）——
		// 与卡片标题芯片同一个方法（决策 43），别写成两份。
		// ★ 「使用」这一条的名字**随潜行开关变**（用户本轮口径：一个操作 + 一个开关，但卡片要读得出差别）：
		//   正常 → {@code opt.use}（「右键」/ Right-click）、潜行 → {@code opt.use_sneak}（「潜行右键」）。
		//   ⚠️ {@code opt.use_sneak} 这个键**保留但改值**：它不再是枚举派生键（枚举里没有 USE_SNEAK 了），
		//   只由这一处手动查 —— 所以**不要**把它加进 {@code ArmProgramScreen} 那个死键守卫的 dead[]。
		//   判据只问 {@link ArmProgramEntry#isSneakUse()}（= USE 且 sneaking），别在这里再写一遍。
		//   反证：把这一支删掉（一律走 opName）⇒ 自检"潜行那条的第一行要以 opt.use_sneak 开头"立刻红。
		Component name = entry.op == OperationType.SIGNAL ? signalHeadText(entry)
			: entry.isSneakUse() ? tr("opt.use_sneak") : opName(entry.op);
		return tr("chip.entry", name, suffix.toString());
	}

	/**
	 * 需要"交互面"的操作（左键/右键语义）—— 与编辑器里的判定同一处依据。
	 *
	 * <p>★ 「过滤」**不在**这里（用户第四十三轮："比较容器的话，不应该不考虑方位只需要坐标吗"）：
	 * 容器模式只读内容，读哪个面都一样 ⇒ 玩家只需填坐标；模型里的 {@code face} 保持默认 UP，
	 * 臂解析交互点时照旧用得到它，但它不是这个操作要玩家关心的东西。
	 */
	public static boolean needsFace(OperationType op) {
		return op == OperationType.MINE || op == OperationType.ATTACK || op == OperationType.USE;
	}

	/**
	 * 「收发信号」的**标题短语**（随模式变）—— <b>卡片标题芯片与护目镜唯一的来源</b>（§11 决策 43）。
	 *
	 * <p>用户反馈第二轮定稿：极性不再单独写"开/关"，而是**含进词里** ——
	 * <ul>
	 *   <li>{@code RECEIVE} → {@code 接收信号}（等到该频率通电）；</li>
	 *   <li>{@code SEND_ON} → {@code 发送信号}（把该频率置为通电）；</li>
	 *   <li>{@code SEND_OFF} → {@code 中断信号}（把该频率置为断电）。</li>
	 * </ul>
	 * 于是标题读法 = <b>短语 · 两个频率物品 · （有才写）时间</b>，见 {@link #signalTailText}。
	 */
	public static Component signalHeadText(ArmProgramEntry entry) {
		return tr(switch (entry.signalMode) {
			case RECEIVE -> "chip.signal_recv";
			case SEND_ON -> "chip.signal_send";
			case SEND_OFF -> "chip.signal_cut";
		});
	}

	/**
	 * 短语**之后还要写出来的**补充（没有就返回空组件，调用方一个字都不画）。
	 *
	 * <p>用户反馈第二轮定稿两条：
	 * <ul>
	 *   <li>发信号：持续 {@code > 0} 才写时间（{@code 1s}，数值 + {@link #unitSuffix}）；
	 *       {@code 0} = 一直保持 ⇒ <b>不写</b>（"保持"是默认状态，不是信息）；</li>
	 *   <li>接收信号：只有老卡（{@code redpow = false}，界面上已经没有这个开关了）才写
	 *       {@code 等断电} —— 兼容清单 3："静默改行为不行，静默隐瞒更不行"。</li>
	 * </ul>
	 *
	 * <p>★ 用户本轮口径：<b>中断没有持续时间</b>（它立即清除该频率，不管有没有剩余时间）⇒
	 * 判据用 {@link ArmProgramEntry#hasSignalDuration()}，只有「发送通电」才可能写时间。
	 * 旧写法问的是 {@code signalTicks() > 0}（= 任何"发"），于是老卡 / 预设里那条
	 * {@code 中断} 带着残留的 {@code waitAmount} 时，卡片上会写一个**运行时根本不读**的时间
	 * （"中断信号。1s"）—— 而 {@code ScriptDecompiler} 现在一个字都不写它，两处必须同口径。
	 */
	public static Component signalTailText(ArmProgramEntry entry) {
		if (entry.isSignalReceive())
			return entry.waitForPowered ? Component.empty() : tr("entry.signal_wait_off");
		if (!entry.hasSignalDuration())
			return Component.empty();
		int ticks = entry.signalTicks();
		if (ticks <= 0)
			return Component.empty();
		return Component.literal(entry.waitAmount + unitSuffix(entry.waitUnit));
	}

	/** 「收发信号」绑定的那对频率物品名（形如 {@code 铁锭 + 红石}）；没绑或空槽返回空串。 */
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
	 * 成功后 → 失败后 → 卸货方式 → 备注。
	 *
	 * <p>★ 用户本轮口径：**「个数」（取出 / 放入 / 收存）与「收信号距离」不再是属性** ——
	 * 它们升级成"基础内容"，只写在第一行（见 {@link #base}）。
	 * 反证：把原来那两支 {@code case TAKE, DEPOSIT} / {@code case SIGNAL} 加回来 ⇒
	 * 卡片上同一个数会出现两遍（第一行 + 一枚胶囊），自检
	 * "第一行之外不许再有「个数」/「距离」胶囊"那条立刻变红。
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
	 * —— 编辑器根本不给他们时限控件（{@link ArmProgramEntry#hasRetryWindow()} 为假），所以 {@code failAmount}
	 * 恒为 -1，而失败是**立刻**发生、去向**马上生效**的。拿 -1 去报"一直重试"就与实际行为刚好相反：
	 * 玩家选了「失败后 跳转 2」，卡片上却写着"一直重试（没设时限）"。这类操作直接报去向。
	 *
	 * <p>★ 「过滤」走的是**上面那条**（它有重试窗口）：不匹配 / 没有物品可比就一直在窗口里判断，
	 * {@code failAmount < 0} 时卡片上写「一直重试」是**对的** —— 那正是它的默认行为。
	 * 这几句文案与编辑器那几个控件同源于 {@link ArmProgramEntry#hasRetryWindow()}，不要各写一份。
	 */
	public static Component failureText(ArmProgramEntry entry) {
		// 按**条目**判（不是按类型）：同一条 SIGNAL，收有失败出口、发没有（§7.16b 一.7）
		if (!entry.hasRetryWindow())
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

	/**
	 * 「个数」的一句话 = <b>模式 + 数值</b>（用户本轮口径：小于等于写“最多 N”、等于写“等于 N”）。
	 *
	 * <p>⚠️ 两个模式必须写成**两句不同的话**：它们的行为不同（一个“有多少动多少、不足不算失败”，
	 * 一个“凑不出 N 个就走失败后”），共用一句“个数 N”会让玩家看不出这条到底会不会等。
	 * 文案走 lang（{@code chip.count_atmost} / {@code chip.count_exact}），不硬编码中文。
	 *
	 * <p>★ 用户本轮口径：「个数」从**属性**升级成**基础内容** ⇒ 它现在只出现在
	 * **第一行**（{@link #base} 的 {@code default} 分支，位于坐标之前）与护目镜那一行，
	 * **不再有第二行的胶囊**（{@link #props} 里那一支已删）。
	 * 语义判据仍然是 {@link ArmProgramEntry#countIsExact()}（单一来源）。
	 */
	public static Component countText(ArmProgramEntry entry) {
		return entry.countIsExact() ? tr("chip.count_exact", entry.count) : tr("chip.count_atmost", entry.count);
	}

	/**
	 * 调用点的轮次胶囊：{@code 调用 3 次}（用户第二十七轮）；0 = 无限循环 ⇒ {@code 调用 无限}。
	 */
	public static Component callRepeatText(ArmProgramEntry entry) {
		if (entry.callRepeat == ArmProgramEntry.CALL_REPEAT_INFINITE)
			return tr("chip.call_repeat_inf");
		return tr("chip.call_repeat", entry.callRepeat);
	}

	/**
	 * 「调用偏移」胶囊：{@code +偏移 1,0,0}（零偏移时不该出现在卡片上，判据是
	 * {@link ArmProgramEntry#hasCallShift()}）。
	 *
	 * <p>★ <b>只有一处消费方：编辑器属性表里的那枚胶囊</b>（{@code ArmProgramScreen.entryProps}）。
	 * 标题芯片（{@link #base}）里**有意不写它** —— 用户口径："调用偏移不显示在第一行"：
	 * 第一行是"操作 + 必要属性"，偏移是可选项，只在第二行的胶囊里出现。
	 * （{@code describe()} 里的 {@code shift=} 与脚本里的 {@code shift=} 都不是"第一行"，照旧保留。）
	 */
	public static Component callShiftText(ArmProgramEntry entry) {
		return tr("chip.call_shift", entry.callShift.getX() + "," + entry.callShift.getY() + "," + entry.callShift.getZ());
	}

	/**
	 * 「接收距离」的一句话：{@code 距离 16}（用户口径：距离只约束「收」）。
	 *
	 * <p>判据只有 {@link ArmProgramEntry#hasSignalRange()}（收模式且半径 &gt; 0）。
	 * ★ 用户本轮口径：距离从**属性**升级成**基础内容** ⇒ 它只出现在第一行
	 * （{@link #base} 的 {@code SIGNAL} 分支，短语与尾巴之后、频率物品之前），
	 * **不再有第二行的胶囊**（{@link #props} 里那一支已删），语句本身仍只有这一处。
	 * 键 {@code chip.signal_range}。
	 */
	public static Component signalRangeText(ArmProgramEntry entry) {
		return tr("chip.signal_range", entry.signalRange);
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

	/**
	 * 操作的显示名（派生键 {@code opt.<小写枚举名>}）。
	 *
	 * <p>⚙ 三个值得记住的口径：{@code DEPLOY} = 「组装」（Create 的 deploying 官方译名"使用"让给了
	 * {@code USE}）；{@code USE} 现在是「<b>右键</b>」（用户本轮把「使用 / 潜行使用」并成
	 * 一个操作 + 一个潜行开关 ⇒ 名字直接说这一步在干什么，"潜行"那一态由
	 * {@code opt.use_sneak}（「潜行右键」）在 {@link #base} 里替代它，不走这里）。
	 */
	public static Component opName(OperationType op) {
		return tr("opt." + op.name()
			.toLowerCase(java.util.Locale.ROOT));
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable("create_sequenced_mechanical_arm.gui.editor." + key, args);
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

		// ★ 用户口径：「个数」升级成**基础内容** ⇒ 它**永远**出现在第一行（含默认值），
		//   第二行的胶囊里**一个都不许再有**（同一个数出现两遍就是这次要修的东西）。
		//   ① 取出的默认 = **最多 1 个**（用户 2026-10-19 二次确认：「还是改成默认小于等于吧，
		//      用到等于的情况更少」—— 上一版「取出 = 等于」的按操作默认已取消）
		//      ⇒ 第一行必须含「最多 1 个」；
		//   ② 放入 / 收存的默认 = 最多 64 个 ⇒ 第一行必须含「最多 64 个」——
		//      这条正是用户说的"防止忘记默认 64"；
		//   ③ 三者的属性胶囊里必须没有个数那枚（props() 里那两支已删）。
		//   反证：把 props() 里的 `case TAKE, DEPOSIT` 加回来 ⇒ ③ 立刻红；
		//        把 base() 的 default 分支里那两行 `if (entry.op.hasCount())` 删掉 ⇒ ①② 立刻红；
		//        把 `CountMode countMode = CountMode.ATMOST` 的初值改回 EXACT ⇒ ① 立刻红。
		// 默认值一律从 fresh(op) 取（= defaultCount + 固定的 ATMOST 模式），别在夹具里写死数字
		ArmProgramEntry takeDefault = ArmProgramEntry.fresh(OperationType.TAKE);
		String takeBase = base(takeDefault).getString();
		String takeCount = tr("chip.count_atmost", ArmProgramEntry.defaultCount(OperationType.TAKE)).getString();
		if (!takeBase.contains(takeCount))
			return "取出默认（个数 1 / 小于等于）的第一行里没有「" + takeCount + "」：" + takeBase;
		if (props(takeDefault).stream()
			.anyMatch(c -> c.getString()
				.contains(takeCount)))
			return "个数已经进了第一行，属性胶囊里不该再有它：" + props(takeDefault);
		ArmProgramEntry depositDefault = ArmProgramEntry.fresh(OperationType.DEPOSIT);
		String depositBase = base(depositDefault).getString();
		String depositCount = tr("chip.count_atmost", ArmProgramEntry.defaultCount(OperationType.DEPOSIT)).getString();
		if (!depositBase.contains(depositCount))
			return "放入默认（个数 64 / 小于等于）的第一行里没有「" + depositCount + "」：" + depositBase;
		if (props(depositDefault).stream()
			.anyMatch(c -> c.getString()
				.contains(depositCount)))
			return "放入的个数已经进了第一行，属性胶囊里不该再有它：" + props(depositDefault);
		// 个数在**坐标之前**：标题芯片的文字会被截断（renderChip 按 120px 裁），
		// 顺序反了就会出现"坐标还在、个数被切掉"。反证：把 base() 里那两行挪到坐标之后 ⇒ 这里立刻红。
		ArmProgramEntry takeAt = ArmProgramEntry.fresh(OperationType.TAKE);
		takeAt.offset = new net.minecraft.core.BlockPos(1, 2, 3);
		String atBase = base(takeAt).getString();
		if (atBase.indexOf(takeCount) < 0 || atBase.indexOf("1,2,3") < 0 || atBase.indexOf(takeCount) > atBase.indexOf("1,2,3"))
			return "第一行里「个数」必须排在坐标之前（芯片会被截断）：" + atBase;
		// ★ 非默认模式也必须能读出来（用户原话："显式等于要写成「等于 N 个」"）：
		//   同一条取出切成「等于 1 个」之后，第一行必须是「等于 1 个」而不是「最多 1 个」——
		//   两种模式**行为不同**（一个"有多少有多少"，一个"凑不出就走失败后"），写成同一句就是骗人。
		//   反证：把 countText() 里的三元改成恒走 atmost ⇒ 这条立刻红；
		//        把 countIsExact() 改成恒假 ⇒ 同样红。
		ArmProgramEntry exactOne = ArmProgramEntry.fresh(OperationType.TAKE);
		exactOne.countMode = ArmProgramEntry.CountMode.EXACT;
		String exactOneText = tr("chip.count_exact", ArmProgramEntry.defaultCount(OperationType.TAKE)).getString();
		if (!base(exactOne).getString()
			.contains(exactOneText))
			return "取出 + 显式「等于」的第一行没写成「" + exactOneText + "」：" + base(exactOne).getString();

		// ★ 「距离」同样升级成**基础内容**，且只对**收信号**有意义（用户口径：距离只约束收；
		//   "中断信号不需要这个属性"）：
		//   ① 收 + 半径 16 ⇒ 第一行必须有一句「距离 16」（文案走 chip.signal_range，不写死中文）；
		//   ② 收 + 半径 0（默认）⇒ 第一行**不写**（0 = 只有同一格才算收到，是默认语义、不是信息）；
		//   ③ 发 / 中断 + 半径非 0 ⇒ **也不显示**（那份值运行时不读，显示出来等于骗人）；
		//   ④ 这三种情况下**属性胶囊里都不许有它**（它已经进了第一行）。
		//   反证：把 base() 里那两行判据从 hasSignalRange() 改成 signalRange != 0 ⇒ ③ 立刻红；
		//        把 props() 里的 `case SIGNAL` 加回来 ⇒ ④ 立刻红。
		ArmProgramEntry recvRange = new ArmProgramEntry();
		recvRange.op = OperationType.SIGNAL;
		recvRange.signalMode = ArmProgramEntry.SignalMode.RECEIVE;
		recvRange.signalRange = 16;
		String rangeText = tr("chip.signal_range", 16).getString();
		String rangeBase = base(recvRange).getString();
		if (!rangeBase.contains(rangeText))
			return "收信号 + 距离 16 的第一行里没有「" + rangeText + "」：" + rangeBase;
		if (props(recvRange).stream()
			.anyMatch(c -> c.getString()
				.contains(rangeText)))
			return "距离已经进了第一行，属性胶囊里不该再有它：" + props(recvRange);
		ArmProgramEntry recvNoRange = new ArmProgramEntry();
		recvNoRange.op = OperationType.SIGNAL;
		if (base(recvNoRange).getString()
			.contains(tr("chip.signal_range", 0).getString()))
			return "收信号 + 距离 0（默认）不该写进第一行：" + base(recvNoRange).getString();
		for (ArmProgramEntry.SignalMode mode : new ArmProgramEntry.SignalMode[] {
			ArmProgramEntry.SignalMode.SEND_ON, ArmProgramEntry.SignalMode.SEND_OFF }) {
			ArmProgramEntry sending = new ArmProgramEntry();
			sending.op = OperationType.SIGNAL;
			sending.signalMode = mode;
			sending.signalRange = 16;
			if (base(sending).getString()
				.contains(rangeText))
				return "「" + mode + "」的第一行不该写距离（距离只约束收）：" + base(sending).getString();
			if (!props(sending).isEmpty())
				return "「" + mode + "」不该有点数 / 距离胶囊：" + props(sending);
		}

		// ★ 调用偏移**不进第一行**（用户："调用偏移不显示在第一行"，它只在第二行的胶囊里）：
		//   第一行只写"操作 + 必要属性"（→ 任务名 / ×N），偏移由 {@link #callShiftText} 那枚胶囊负责。
		//   反证：把 base() 里那段 `if (entry.hasCallShift()) suffix.append(callShiftText(...))`
		//   加回去 ⇒ 这条立刻变红（数字 9,8,7 会出现在第一行里）。
		ArmProgramEntry shiftedCall = new ArmProgramEntry();
		shiftedCall.op = OperationType.CALL;
		shiftedCall.callTarget = "收尾";
		shiftedCall.callRepeat = 2;
		shiftedCall.callShift = new net.minecraft.core.BlockPos(9, 8, 7);
		String shiftedBase = base(shiftedCall).getString();
		if (shiftedBase.contains("9,8,7"))
			return "调用偏移被写进了第一行（它只该出现在第二行的胶囊里）：" + shiftedBase;
		if (!base(shiftedCall).getString()
			.contains("收尾"))
			return "第一行丢了调用目标（→ 任务名 那条不该被动）：" + shiftedBase;
		// 胶囊那一侧必须还在（否则"删掉第一行"会变成"偏移彻底看不见了"）
		if (!callShiftText(shiftedCall).getString()
			.contains("9,8,7"))
			return "调用偏移的胶囊文案里没有坐标：" + callShiftText(shiftedCall).getString();

		// ★ 用户本轮口径：「使用」与「潜行使用」合成**一个操作 + 一个潜行开关**
		//   ⇒ 卡片第一行的操作名必须随开关变：正常是「右键」（opt.use）、潜行是「潜行右键」（opt.use_sneak）。
		//   ⚠️ 这两个键都是**手动查**的（opt.use_sneak 不再是枚举派生键：枚举里没有 USE_SNEAK 了），
		//   判据只问 {@link ArmProgramEntry#isSneakUse()}（= USE 且 sneaking）。
		//   反证：把 base() 里那一支 `entry.isSneakUse() ? tr("opt.use_sneak") : opName(...)`
		//   改回一律走 opName ⇒ ② 立刻红；改成恒走 opt.use_sneak ⇒ ① 立刻红；
		//   把两份语言文件里的 opt.use / opt.use_sneak 改成同一个词 ⇒ ③ 立刻红。
		ArmProgramEntry useNormal = ArmProgramEntry.fresh(OperationType.USE);
		ArmProgramEntry useSneak = ArmProgramEntry.fresh(OperationType.USE);
		useSneak.sneaking = true;
		String normalHead = tr("opt.use").getString();
		String sneakHead = tr("opt.use_sneak").getString();
		String normalBase = base(useNormal).getString();
		String sneakBase = base(useSneak).getString();
		if (!normalBase.startsWith(normalHead) || normalBase.contains(sneakHead))
			return "「使用」（不潜行）的第一行没有以「" + normalHead + "」开头：" + normalBase;
		if (!sneakBase.startsWith(sneakHead))
			return "「使用 + 潜行」的第一行没有以「" + sneakHead + "」开头：" + sneakBase;
		// 两句必须是**两个词**：同一个词就分不出这一下是潜行还是不潜行（而两者行为不同 ——
		// 有些交互只有潜行才生效，用户的原话就是"有些交互必须潜行"）。
		if (normalHead.equals(sneakHead))
			return "「右键」与「潜行右键」在语言文件里是同一个词（" + normalHead + "）—— 卡片分不出潜行与否";

		return "OK(默认1行 / 设了3个属性=4行 / 右键与潜行右键分得开)";
	}

}
