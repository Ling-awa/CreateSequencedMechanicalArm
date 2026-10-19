package io.github.Ling.create_sequenced_mechanical_arm.program;

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
 * <p>NBT 键刻意短（这是会被反复存读的结构）：{@code op/off/delay/wt/to/tunit/timeout/onok/
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
	/**
	 * 目标位置：**永远相对"执行这条条目的那条臂"**的偏移（PLAN.md §19.7 的用户口径）。
	 *
	 * <p>世界目标 = {@code arm.getBlockPos().offset(offset)}（§19.7）：所以卡里写 {@code 0 0 0}
	 * 就是"挖臂自己脚下那一格"，与卡片在谁手里、卡上的原点在哪都无关。运行时不加任何东西。
	 *
	 * <p>卡上的**录制原点**（{@code CardProgramStorage.readOrigin}，用户第四十轮起它住在卡上、
	 * 不在程序里）是**录制参考系**（{@code offset = 世界位置 − 原点}）与画世界目标框用的；
	 * 它不是运行时的锚点，插卡也<b>不做平移</b>（P1 那个 {@code ProgramCoordinates.rebaseTo}
	 * 已按 §19.7 删除）。P1 之前这里还有 {@code absolute}
	 * 字段（真 = 世界绝对坐标），同样已删除 —— 条目坐标没有第二种语义了。
	 */
	public BlockPos offset = BlockPos.ZERO;

	/** 前摇等待（ticks）。 */
	public int delay;
	/** 攻击 / 挖掘 / 使用 的交互面（配合方块与面共同决定动作）。 */
	public Direction face = Direction.UP;

	/**
	 * 失败出口（{@code -1} = 失败就**一直重试**，默认）。与"时限"合成一个属性后，
	 * 界面上的「失败后」= {@code failAmount}×{@code failUnit} 的等待窗口 + {@link #onTimeout} 的去向。
	 *
	 * <p><b>默认值 {@code -1} 的语义就是"默认重试"</b>：新建条目（GUI 的"插入新操作"、脚本里
	 * 没写 {@code timeout=}）拿到的都是它 ⇒ 不成功就原地一直重试，去向不生效。
	 *
	 * <p>★ 对「过滤」这是**有意**的（用户口径：过滤是控制类操作、失败默认 retry）：过滤是**控制类操作** ——
	 * 一瞬间的判断，臂不移动、不做任何动作，因此没有"做错了"这回事；不匹配只意味着"时机还没到"
	 * （容器里还是上一批货 / 手上那份还没被取货条目换掉），与「收信号」是**同一类**。
	 * 所以过滤默认重试，要让它"不匹配就改道"必须自己把时限与「失败后」的去向设出来 ——
	 * 而不是像上一版那样一不匹配就直接走失败出口。判据见 {@link #hasRetryWindow()}。
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

	/**
	 * FILTER 的**幽灵过滤器物品**：拿它当"过滤器"，去比候选那一份物品（用户口径）。
	 *
	 * <p>放什么就用什么规则 —— {@code create:filter}（列表）、{@code create:attribute_filter}（属性）、
	 * {@code create:package_filter}（包裹地址），全由 Create 的 {@code FilterItemStack.of}
	 * 按物品类型自己选，我们**不重写比对逻辑**（见 {@code ProgramRuntime.filter}）。
	 * 空 = 没放过滤器 = <b>匹配一切</b>（Create 本体口径：{@code FilterItemStack.isEmpty()} ⇒ test 为真），
	 * 但候选那一份**必须非空**才算成功（用户口径：没有物品就总是不符合）。
	 */
	public ItemStack filterItem = ItemStack.EMPTY;
	/**
	 * FILTER 的**过滤位置**（用户口径二选一）：手上那份存货，还是一个外部容器坐标。
	 *
	 * <p>存的是**枚举名**而不是 ordinal（与本文件其它枚举同一惯例）：以后增删项不会把老卡的
	 * "外部容器"读成"手持"。缺键回落 {@link FilterSource#HELD}。
	 */
	public FilterSource filterSource = FilterSource.HELD;

	/** FILTER 的过滤位置（§用户口径：必要输入 = 幽灵物品 + 过滤位置）。 */
	public enum FilterSource {
		/** 手上那份存货（{@code SequencedArmBlockEntity.getHeldItem()}）。 */
		HELD,
		/** 一个外部容器：取它**第一个非空槽**的那一份当候选（只看第一格，不看整箱）。 */
		CONTAINER
	}

	/**
	 * TAKE / DEPOSIT 的**个数上限**（1..64）。
	 *
	 * <p>它的**语义**由 {@link #countMode} 决定，两个字段是一件事的两半：
	 * <ul>
	 *   <li>{@link CountMode#ATMOST}（默认）：<b>有多少动多少，上限 N</b> —— 不足 N 也算成功；</li>
	 *   <li>{@link CountMode#EXACT}：<b>必须能凑出恰好 N 个</b>才动手，凑不出就走「失败后」那条既有出口
	 *       （默认重试，时限 / 去向照旧）。</li>
	 * </ul>
	 *
	 * <p>也就是：这条属性**同时服务于「取出」与「放入」**。取值范围（1..64）只有一处来源：
	 * {@code ScriptParser.COUNT_MIN} / {@code COUNT_MAX}（编辑器控件与自检都问那里）。
	 */
	public int count = 1;

	/**
	 * 「个数」的<b>默认值按操作给</b>：取出 = 1、放入 = 64（用户口径）。
	 *
	 * <p><b>为什么放入的默认是 64 而不是 1</b>：改造前「放入」根本不读 {@code count}，
	 * 它把手上那<b>整摞</b>塞给目标 —— 也就是实际行为是"一次一摞"。若沿用 1，
	 * 所有既有卡片里的放入会静默变成"一次只放 1 个"（一晚上也搬不完一箱）。
	 * 64 = 一摞（{@code COUNT_MAX}）= 与改造前**完全一致**的行为；
	 * 想少放再自己调小。取出侧保持 1（与改造前一致）。
	 *
	 * <p>★ 这个方法是"默认值"的<b>唯一定义处</b>：{@link #countIsSet()}（胶囊 / 属性表要不要显示）、
	 * {@code ScriptParser}（脚本没写 {@code count=} 时的初值）、{@code ScriptDecompiler}
	 * （默认值不写进文本）、编辑器新建 / 换操作时的初值，全都问它 —— 各写一份"是不是默认"
	 * 就会出现"界面显示未设置、脚本里写 count=64、读回来又变成未设置"的三方分叉。
	 */
	public static int defaultCount(OperationType op) {
		return op == OperationType.DEPOSIT ? 64 : 1;
	}

	/**
	 * 个数的**模式**（用户本轮口径：等于 / 小于等于）。
	 *
	 * <p><b>默认 {@link CountMode#ATMOST} 是有意的</b>：它正好等于“取出”改造前的行为
	 * （{@code Math.min(可用, N)}，有多少拿多少、上限 N），所以老卡 / 老脚本读进来
	 * <b>一个字都不用迁移</b>；要“必须够数才动”才显式切到 EXACT。
	 * 枚举顺序即界面选择框的选中下标（{@code ordinal}）：ATMOST 在前 = 默认项。
	 *
	 * <p>★ 判据<b>只此一处</b>（{@link #countIsExact()}）：运行时（前置检查 / 取出 / 放入）、
	 * 编辑器第二行的模式框、卡片胶囊、{@code describe()}、脚本的写出与解析全都问那一个方法。
	 * <br>NBT 键 {@code cmode}，<b>默认值（ATMOST）不写</b> —— 与 sigmode / fsrc / coff 同一手法：
	 * 只写在小于等于模式下的老卡存取前后 NBT 逐字节不变。
	 */
	public CountMode countMode = CountMode.ATMOST;

	/**
	 * 个数模式：小于等于（默认）/ 等于。
	 *
	 * <p>⚠️ 顺序有意义：{@code ordinal} 就是编辑器里那个模式选择框的选中下标（ATMOST 在前 = 默认项），
	 * 而 NBT / 脚本写的是**枚举名字**（不是 ordinal）—— 增删项不会把老数据读成另一种模式。
	 */
	public enum CountMode {
		/** 有多少动多少，上限 {@link #count}；不足 N <b>不算失败</b>（= 取出操作改造前的行为）。 */
		ATMOST,
		/** 必须能凑出恰好 {@link #count} 个才动手；凑不出走「失败后」的重试窗口（默认重试）。 */
		EXACT
	}

	/**
	 * <b>等于 / 小于等于的唯一判据</b>：真 = 必须恰好 N 个。
	 *
	 * <p>为什么要收成一个方法：这条判据的消费方有六处（前置检查、{@code take}、{@code deposit}、
	 * 编辑器的模式框、卡片胶囊与 {@code describe()} 的文案、脚本的写出 / 解析）。
	 * 各写一个 {@code countMode == EXACT} 的话，界面说“等于”、运行时按“小于等于”跑这种分裂
	 * 只有在实机里丢过 / 取错过物品之后才看得出来。
	 */
	public boolean countIsExact() {
		return countMode == CountMode.EXACT;
	}

	/**
	 * 「个数」算不算<b>偏离默认值</b>（= 玩家改过、或选了「等于」模式）。
	 *
	 * <p>★ <b>它已经不再决定"卡片上显不显示"</b>（用户 2026-10-19 的口径变了）：
	 * 个数属于**填写的内容**，按"凡是填写的都写进第一行"这条规矩，{@code ArmEntryText.base}
	 * 对取出/放入<b>永远</b>写出「最多 N 个 / 等于 N 个」—— 正是为了不让人忘记默认值（放入默认 64）。
	 * 现在的唯一消费方是 {@code describe()}（护目镜那一行调试摘要：非默认才多带一段，免得每条都拖 noise）。
	 * 所以它的判据仍然是"跟 {@link #defaultCount(OperationType)} 比"，别改成跟 1 比。
	 *
	 * <p>⚠️ 判据也不能只是 {@code count != 1}：{@code count == 1 && EXACT}（“必须恰好 1 个”）与
	 * {@code count == 1 && ATMOST}（“最多 1 个”）<b>行为不同</b>，当成"默认"就等于静默改掉语义。
	 * 也不能只跟 1 比：放入的默认是 64（见 {@link #defaultCount(OperationType)}）。
	 */
	public boolean countIsSet() {
		return count != defaultCount(op) || countIsExact();
	}

	/**
	 * WAIT 的等待时长：<b>数值 × 单位</b>（用户要求"时间能选 tick / s / min，数值 0–120"）。
	 *
	 * <p>为什么不只存 ticks：界面上要能原样回显"10 s"而不是"200 tick"。
	 * 换算只在 {@link #waitTicks()} 一处发生，运行时永远用 ticks。
	 */
	public int waitAmount = 20;
	public WaitUnit waitUnit = WaitUnit.TICK;
	/**
	 * SIGNAL：极性（"通电"那一侧）。收：等通电（true）/ 等断电（false）；
	 * 发：发通电 15（true）/ 发断电 0（false）—— 同一字段、同一含义方向（§7.16b 三）。
	 *
	 * <p>★ 这个字段**只剩「收发信号」在用**（用户口径：「我现在倾向于移除那个按相邻红石等待的操作，
	 * 因为我们有更好的无线信号系统可以使用」）：它只描述**红石链接频率**那一侧的电平，
	 * 与相邻方块的普通红石电无关。
	 */
	public boolean waitForPowered = true;
	/**
	 * SIGNAL（收发信号）：模式 —— 收 / 发通电 / 中断（§7.16b 一.2）。
	 *
	 * <p>「中断」是**立即清除该频率**（用户本轮口径：无论是否无限保持、有没有剩余时间），
	 * 只有「发送通电」有持续时间 —— 判据见 {@link #hasSignalDuration()}。
	 *
	 * <p>{@code ordinal} 就是三选一选择框的下标（{@link #isSignalSend()} = {@code mode != RECEIVE}；
	 * "要不要持续时间"问 {@link #hasSignalDuration()}，只有 {@code SEND_ON} 有）。
	 * 老卡没有 {@code sigmode} 键 ⇒ 缺省 {@code RECEIVE} ⇒ 行为与改造前完全一致。
	 */
	public SignalMode signalMode = SignalMode.RECEIVE;
	/**
	 * SIGNAL（收发信号）的<b>接收半径</b>（格，0..{@link #SIGNAL_RANGE_MAX}）—— <b>只约束「收」</b>。
	 *
	 * <p><b>语义</b>（用户口径："设置收发信号的时候再加一个距离属性，默认为 0（只能自己接收发送），
	 * 范围是 0~256 格"；与宿主确认的方案是<b>距离只管收</b>）：我只接受<b>半径内</b>的来源，
	 * <b>发出去不管</b>（谁收到由收方自己的距离决定）。所以{@code 发送 / 中断一个字都不读它}。
	 *
	 * <p><b>{@code 0} 的含义</b>：只有"来源与本臂<b>同一格</b>"才算收到 —— 也就是自己发、自己收的那条
	 * <b>自环</b>，正是用户说的"只能自己接收发送"。判据是<b>方块欧氏距离</b>
	 * {@code dx² + dy² + dz² <= N²}（<b>含边界</b>）：{@code N = 0} 时只有 {@code distSqr == 0}
	 * 成立，所以判定处必须写成 {@code <=} 而不是 {@code <}（改成 {@code <} ⇒ 自检的边界那条变红）。
	 *
	 * <p>⚠️ 只有 {@code signalMode == RECEIVE} 时它才有意义（发送 / 中断的编辑器不给这个属性、
	 * 脚本也不写它）。判据<b>只此一处</b>：{@link #hasSignalRange()} —— 胶囊 / 编辑器 / 反编译器 /
	 * 运行时都问那一个方法，别在各处再写一遍 {@code signalMode == RECEIVE && signalRange > 0}。
	 *
	 * <p>NBT 键 {@code range}，<b>0（默认）不写</b> —— 与 {@code cmode} / {@code coff} / {@code sigmode}
	 * 同一手法：只含收信号、没设过距离的老卡存取前后 NBT 逐字节不变。
	 */
	public int signalRange = 0;

	/** 接收半径的上限（格）：用户定的 0..256。 */
	public static final int SIGNAL_RANGE_MAX = 256;

	/**
	 * 把任意整数夹进接收半径的合法区间（0..{@link #SIGNAL_RANGE_MAX}）—— <b>唯一的夹取处</b>
	 * （读盘兜底、编辑器控件、脚本解析都走它，三处各写一个 {@code Math.max/min} 迟早写出三个不同的区间）。
	 */
	public static int clampSignalRange(int raw) {
		return Math.max(0, Math.min(SIGNAL_RANGE_MAX, raw));
	}

	/**
	 * <b>这一条有没有"接收半径"</b>（= 收模式<b>且</b>半径 &gt; 0）—— 胶囊要不要占位、编辑器属性表要不要
	 * 把它算作"已加"、脚本要不要写 {@code range=}、运行时要不要按距离筛，全都问这一个方法。
	 *
	 * <p>⚠️ 别在四处各写一遍 {@code signalMode == RECEIVE && signalRange > 0}：那种分裂的症状是
	 * "卡片上有「距离 16」、运行时却按不限距离收"（或反过来），两边都看不出来谁对。
	 * 发送 / 中断那一侧恒假 —— 距离只约束收，用户口径已经定死。
	 */
	public boolean hasSignalRange() {
		return isSignalReceive() && signalRange > 0;
	}

	/**
	 * SIGNAL（收 / 发）要用的红石链接频率（两个物品，与 Create 的链接频率一致）。
	 *
	 * <p>从 {@link ArmProgramTask} 搬过来 —— 用户要求"把频率功能单独拆出来"，于是
	 * "这个任务要通电才跑"变成任务开头的第一条 `signal recv` 条目，而任务上不再有频率设置。
	 */
	@Nullable
	public Couple<Frequency> frequency;
	/** CALL（调用任务）：被调任务的名字。 */
	public String callTarget = "";
	/**
	 * CALL（调用任务）：调用次数（轮次）的上限。
	 *
	 * <p>原来是任务级循环次数的上限（1..1024）借着用，用户第四十轮改成自己的
	 * 区间：<b>0..64</b>，缺省 1，{@link #CALL_REPEAT_INFINITE}（= 0）= 无限循环。
	 * 1024 轮在游戏里既没人等得起、又和"不限次"这个真正想要的东西差一层，所以拆出独立上限。
	 */
	public static final int MAX_CALL_REPEAT = 64;
	/** 「无限循环」的哨兵值（就是 0）。 */
	public static final int CALL_REPEAT_INFINITE = 0;

	/** 把任意整数夹进调用次数的合法区间（0..{@link #MAX_CALL_REPEAT}）；0 是**合法值**，不是缺省。 */
	public static int clampCallRepeat(int raw) {
		return Math.max(CALL_REPEAT_INFINITE, Math.min(MAX_CALL_REPEAT, raw));
	}

	/**
	 * CALL（调用任务）：这个调用点让子任务跑几轮（{@code 0..}{@link #MAX_CALL_REPEAT}，
	 * {@code 0} = 无限循环）。
	 *
	 * <p><b>用户第二十七轮定的：循环次数归调用点</b> —— "把循环次数放在调用上"。
	 * 于是子任务的轮次完全由这里决定（任务级的循环设置在用户后来的口径里**整体删除**了：
	 * 主任务恒为无限循环、子任务跑完就返回调用方），避免"调用 ×3 × 子任务自己循环 ×2"这种要相乘、
	 * 谁都算不清的组合。
	 */
	public int callRepeat = 1;

	/**
	 * CALL（调用任务）：<b>调用偏移</b>（用户口径："给调用添加一个新属性，偏移 —— 输入一个坐标，
	 * 调用子任务的时候把全部坐标增加对应的偏移量"，为的是同一个任务被多个调用点复用）。
	 *
	 * <p><b>它是"调用点上的一个 Δ"，不是对子任务的永久改写</b>：该调用点生效期间，子任务里每个
	 * {@link #needsTarget()} 的条目，世界目标 = {@code 臂坐标 + 条目自己的 offset + 这个 Δ}；
	 * 子任务自己的 {@link #offset} <b>一个字节都不改</b>（所以同一个任务被调用多次、每次错开一点才成立）。
	 * 叠加只发生在**唯一那个咽喉**：{@code ProgramRuntime.offsetOf}（见那里的说明）。
	 *
	 * <p><b>嵌套叠加、返回恢复</b>：A 调 B（Δ1）、B 调 C（Δ2）⇒ C 里条目的生效偏移 = Δ1 + Δ2、
	 * B 里条目的 = Δ1；回到调用方时恢复（调用方的条目不受子任务那边影响）。
	 * 没人调的任务（主任务 / 被「跳转」过去而不是被调用的任务）生效偏移 = 0。
	 *
	 * <p>只影响**需要坐标**的条目：等待 / 信号 / 退出 / 抛错没有目标，原样不动。
	 *
	 * <p>★ <b>卡级「坐标偏移」（{@link ArmProgram#shiftAllOffsets}）有意不搬这个字段</b>：
	 * 它是**相对量**，整套平移时子任务自己的 {@code offset} 已经跟着搬过一次了，这里再搬一次
	 * 就是加两遍（子任务的目标会跑偏 Δ）。别"顺手"把它也搬了。
	 *
	 * <p>默认 {@link BlockPos#ZERO} = 什么都不改；缺 {@code coff} 键（老卡）也回落它 ⇒ 行为与改造前一致。
	 */
	public BlockPos callShift = BlockPos.ZERO;

	/** 调用偏移是不是**非默认**（非零）—— 判定只此一处：存读、脚本写出、胶囊、编辑器属性表都问它。 */
	public boolean hasCallShift() {
		return !callShift.equals(BlockPos.ZERO);
	}

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
	 * 发送持续的**数值**上限；{@code 0} = 一直保持（"占用"就是这个），{@code >0} = 到点自动恢复。
	 *
	 * <p>⚠️ 这是**数值**、不是 ticks：真正的 ticks = 数值 × {@link #waitUnit}（用户反馈第二轮：
	 * 持续控件和等待一样带 t/s/m 单位框，所以 {@link #signalTicks()} **必须按单位换算**）。
	 * 数值范围与 WAIT 一致（0–120），于是最长的脉冲 = 120 m = 144000 ticks。
	 *
	 * <p>⚠️ 上限只管**有持续时间的那种模式**（{@link #hasSignalDuration()} 的「发送通电」）：
	 * 「中断」立即清除该频率（用户本轮口径），它那条 {@code place=} / 数值框根本不存在。
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
	 * <b>这一条有没有"持续时间"参数</b> —— 只有「发送通电」（{@code SEND_ON}）有。
	 *
	 * <p>用户口径（本轮）：<b>「中断」是立即把信号清除</b>，无论这个频率是否无限保持、有没有剩余时间，
	 * 它的运行时行为就是 {@code releaseSignal}（电平 0 + 退网 + 从表里移除 + 取消未到期的脉冲）；
	 * 「接收」只是阻塞等通电。两者都**不接收持续时间** —— 于是"数值 + 单位"这两个控件
	 * （以及脚本里的 {@code place=}）在它们身上是**空参数**，运行时一个字都不读。
	 *
	 * <p>为什么不能继续用 {@link #isSignalSend()} 当判据：那个是"不是收"（{@code SEND_ON} 与
	 * {@code SEND_OFF} 都算），它的语义在别处（失败出口、频率绑定）仍然成立，所以**一个字都不改**；
	 * 而"要不要持续时间"只认 {@code SEND_ON}。判据写在一起、只写一处，才不会出现
	 * "界面给了空参数、脚本要求写 place=、运行时又不读"的三方分叉。
	 */
	public boolean hasSignalDuration() {
		return signalMode == SignalMode.SEND_ON;
	}

	/**
	 * 发送的**持续**时长（ticks）：{@code 0} = 一直保持，否则 {@code clamp(waitAmount,0,120) × 单位}。
	 *
	 * <p>⚠️ 单位换算**必须**走这条：用户反馈第二轮给持续控件加了 t/s/m 单位框，
	 * "选 1 s 却只发 1 tick"就是显示与实际相反。换算表与 {@link #waitTicks()} 完全一致
	 * （{@code t=1 / s=20 / m=1200}），只是**数值**字段仍复用 {@code waitAmount}。
	 *
	 * <p>⚠️ 这里**有意**仍然只问 {@link #isSignalReceive()}（= 不区分 {@code SEND_ON} / {@code SEND_OFF}）：
	 * 只改该改的地方、也不顺手改既有断言。"中断要不要持续时间"是**文本 / 界面 / 脚本**
	 * 三个消费方的事，判据是 {@link #hasSignalDuration()}；这个方法的语义（"如果真按发送算，
	 * 持续是多少"）保持不变，{@code DevPresets} 与既有自检都还按它读。
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
	 * <p>取 / 放 / 卸 / 组装 / **过滤**：不成功就是"还没成功"，会原地重试到「失败后」的时限，
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
	 *
	 * <p>★ {@code FILTER} 在这一列（用户口径：过滤是控制类操作、**失败默认 retry**）：
	 * 它不匹配（含"没有物品可比"）时走的必须是 {@code handleFailure} 那条既有路 ——
	 * 在重试窗口内反复判断，窗口到了才走「失败后」，与「收信号」一字不差。
	 */
	public boolean hasRetryWindow() {
		return switch (op) {
			case TAKE, DEPOSIT, UNLOAD, DEPLOY, FILTER -> true;
			case SIGNAL -> signalMode == SignalMode.RECEIVE;
			default -> false;
		};
	}

	/** 这条操作有"失败"这个出口吗（= 编辑器里要显示「失败后」）；条目级版本（§7.16b 一.2）。 */
	public boolean hasFailureExit() {
		return hasRetryWindow() || op == OperationType.MINE || op == OperationType.CALL;
	}

	/**
	 * <b>这一条</b>有没有一个<b>世界坐标参数</b> —— <b>"有没有 / 要不要坐标"唯一的那处判据</b>。
	 *
	 * <p>为什么不能继续只问 {@link OperationType#requiresTarget()}：过滤的位置是**二选一**的条目级属性 ——
	 * 手持模式根本没有坐标（连一条坐标都没有意义），容器模式却有一个真实的方块坐标。
	 * 于是"这个条目有没有坐标"变成了**条目级**的事（同一个 FILTER，两种模式答案不同）。
	 *
	 * <p>⚠️ 凡是"这个条目有没有 / 要不要一个**坐标**"的地方都必须问这一个方法：
	 * 认领（{@code pinOffset} / {@code entryOffset}）、GUI 的坐标行、卡上"有目标条目"的计数与整套平移、
	 * 蓝图旋转（{@code SequencedArmBlockEntity.transform}）、插卡时的超范围提示、
	 * 操作表的描框、撤销按坐标匹配、脚本写出（{@code ScriptDecompiler}）、脚本解析
	 * （{@code ScriptParser}：写了坐标的这类操作要报错）、胶囊文字里的坐标，
	 * 以及"不需要坐标的条目 offset 恒为 0"这条定义本身的三处落点
	 * （{@link #load} 的自愈、编辑器切换操作时清零、自检的逐字段比较）。
	 * 写在两处必然分叉 —— 症状分别是"容器模式没有坐标框"和"手持模式凭空多出一个目标框"。
	 *
	 * <p>⚠️⚠️ 但它**不是**"臂要不要伸过去"的判据 —— 那是 {@link #movesArm()}（操作**类型**级）。
	 * 两者混用就会出现这次的 bug：过滤的容器模式"有坐标"（GUI 要显示坐标框、平移要带上它）
	 * 却被当成"要伸过去"（臂先飞过去读箱子）。判据必须分开问，见 {@link #movesArm()}。
	 *
	 * <p>反过来，{@link OperationType#requiresTarget()} 保留原义：那是**操作类型天生**要不要移动，
	 * 由 {@link #movesArm()} 转发；另有 {@link OperationType#recordable()}（世界里能录哪些操作）
	 * 与脚本解析器（{@code ScriptParser.parseEntry} 判坐标必填/可选）直接问它。
	 */
	public boolean needsTarget() {
		return op.requiresTarget() || (op == OperationType.FILTER && filterSource == FilterSource.CONTAINER);
	}

	/**
	 * <b>这一条要不要让手臂移动过去</b>（= 运行时"伸过去 / 进入移动状态"唯一的判据）。
	 *
	 * <p><b>为什么必须是类型级、不能问 {@link #needsTarget()}</b>（用户口径）：过滤是**控制类操作** ——
	 * "判断过滤是一瞬间的事情，不需要动力臂伸过去判断"，它跟「收发信号」一样**没有动作**。
	 * 容器模式的坐标只是"**读哪个**方块"的数据参数，不是"伸过去的目标"。
	 * 拿 {@code needsTarget()} 当这条判据就会让臂先飞过去 —— 那是上一版的错。
	 *
	 * <p>反过来说，{@code needsTarget()} 回答的那个问题（"有没有坐标参数"）在过滤容器模式下仍然是真：
	 * GUI 要显示坐标框、平移/蓝图/描框/撤销比对都要带上它 ——
	 * 所以**两处判据都不能删**，只能各问各的。自检里"容器过滤的坐标跟着平移、但臂不移动"两条各钉一半。
	 */
	public boolean movesArm() {
		return op.requiresTarget();
	}

	/**
	 * 这个条目在编辑器里要占用几个**幽灵槽**（从 0 号起连续）—— 菜单激活数、屏幕渲染、
	 * 自检期望值全都问这一个方法。
	 *
	 * <p>槽位的分工：0 号恒为**自定义图标**；1 号起是"这个操作自己的物品"——
	 * 「收发信号」用 1/2 号记一对频率，{@link OperationType#FILTER} 用 1 号放幽灵过滤器物品。
	 * 判据收在这里的理由与 {@link OperationType#hasSignalMode()} 当初一样：
	 * 菜单（{@code targetSlotsActive}）、屏幕（{@code updateTargetSlots}）、自检（{@code checkGhostSlotClearance}）
	 * 三处各写一遍的话，加了新操作就会有地方忘了改 —— 表现是"槽画出来了但放不进东西"。
	 */
	public int ghostSlots() {
		if (op == OperationType.FILTER)
			return 2;
		return op.hasSignalMode() ? 3 : 1;
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
		// 个数模式：**默认（ATMOST）不写这个键** —— 与 sigmode / fsrc / coff 同一手法：
		// 老卡（只有 count、没有模式）存取前后 NBT 逐字节不变。写枚举**名字**，不用 ordinal。
		if (countMode != CountMode.ATMOST)
			tag.putString("cmode", countMode.name());
		tag.putInt("waitt", waitAmount);
		tag.putString("wunit", waitUnit.name());
		tag.putBoolean("redpow", waitForPowered);
		// 收信号（缺省）**不写**这个键：只含收信号的卡存取前后 NBT 逐字节相同（老卡零扰动，§7.16a 第 10 条）
		if (signalMode != SignalMode.RECEIVE)
			tag.putString("sigmode", signalMode.name());
		if (frequency != null)
			tag.put("freq", frequency.serializeEach(f -> (CompoundTag) f.getStack()
				.saveOptional(registries)));
		// 接收半径：**0（默认）不写这个键** —— 与 sigmode / cmode / coff 同一手法：只含收信号、
		// 没设过距离的老卡存取前后 NBT 逐字节不变（相容性清单：不许静默扰动老数据）。
		if (signalRange != 0)
			tag.putInt("range", signalRange);
		tag.putString("call", callTarget);
		tag.putInt("calln", callRepeat);
		// 调用偏移：**缺省（0,0,0）不写这个键** —— 与 sigmode / fsrc 同一手法：没设过偏移的老卡
		// 存取前后 NBT 逐字节不变（相容性清单：不许静默扰动老数据）。
		if (hasCallShift())
			tag.put("coff", NbtUtils.writeBlockPos(callShift));
		tag.putString("err", errorMessage);
		tag.putString("urep", unloadRepeat.name());
		tag.putString("uamt", unloadAmount.name());
		if (!icon.isEmpty())
			tag.put("icon", icon.saveOptional(registries));
		// 过滤位置：**缺省（HELD）不写这个键** —— 与 sigmode 同一手法，只含手持过滤的卡存取前后
		// 逐字节不变（老卡零扰动）。模式名写成字符串，不用 ordinal。
		if (op == OperationType.FILTER && filterSource != FilterSource.HELD)
			tag.putString("fsrc", filterSource.name());
		// 幽灵过滤器物品：与 icon 同一写法（只在设过时写）。⚠️ 用 saveOptional 而不是 save：
		// `DataComponents.CUSTOM_DATA` 在 1.21 是**不稳定**的组件，`save` 会往日志喷一条
		// "不稳定组件被序列化"的警告（icon 那一行就是为了这个才用 saveOptional 的）。
		if (op == OperationType.FILTER && !filterItem.isEmpty())
			tag.put("fitem", filterItem.saveOptional(registries));
		return tag;
	}

	public static ArmProgramEntry load(CompoundTag tag, HolderLookup.Provider registries) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.byName(tag.getString("op"), OperationType.TAKE);
		entry.offset = NBTHelper.readBlockPos(tag, "off");
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
		// 个数模式：**缺键（老卡）= ATMOST** ⇒ 语义与改造前完全一致（“有多少拿多少、上限 count”）。
		// 用 enumOf 而不是 ordinal：枚举增删项不会把老数据读成另一种模式（与 op / sigmode 同一惯例）。
		entry.countMode = enumOf(CountMode.class, tag.getString("cmode"), CountMode.ATMOST);
		// ★ 「放入」的个数：改造前这个字段**运行时根本不读**（整摞塞），所以老卡里那个 1 是
		//   "字段初始值"，不是玩家选的数 —— 它必须迁成 64（= 整摞 = 旧行为，见 defaultCount）。
		//   ⚠️ 判据要带上 `cmode` 键：EXACT 一定写过它，所以"放入 + 等于模式 + 恰好 1 个"
		//   （玩家真的选了 1）不会被这条迁移改成 64。小于等于 + 1 的放入则会变 64 —— 这是
		//   有意的取舍：旧数据里它无法与"没设过"区分，而"一次只放 1 个"几乎必然是误伤。
		if (entry.op == OperationType.DEPOSIT && entry.count == 1 && !tag.contains("cmode"))
			entry.count = defaultCount(OperationType.DEPOSIT);
		// waitt 以前是"ticks"，现在带单位：老卡片没有 wunit → 数值就是 tick 数，语义不变
		entry.waitAmount = Math.max(0, tag.getInt("waitt"));
		entry.waitUnit = enumOf(WaitUnit.class, tag.getString("wunit"), WaitUnit.TICK);
		entry.waitForPowered = !tag.contains("redpow") || tag.getBoolean("redpow");
		// sigmode 缺键（老卡）= RECEIVE ⇒ 行为不变（兼容清单 2）；写的是**字符串**而不是 ordinal，
		// 以后枚举增删项不会把老卡的"发"变成"收"（与 op/onok/timeout/urep 同一惯例）
		entry.signalMode = enumOf(SignalMode.class, tag.getString("sigmode"), SignalMode.RECEIVE);
		// 接收半径：**缺键（老卡 / 没设过）= 0**（= 只有同一格才算收到）；读到什么都夹进 0..256 ——
		// 手改过的卡 / 将来的上限变化不该让半径变成负数或上万格（夹取只有 clampSignalRange 一处）。
		entry.signalRange = clampSignalRange(tag.getInt("range"));
		// **有持续时间**的信号（只有「发送通电」）的持续**数值**夹进与 WAIT 相同的 0..120
		//（真正的 ticks = 数值 × 单位，见 signalTicks()）。判据是 hasSignalDuration() 而不是
		// isSignalSend()：中断 / 接收那两种模式的 waitt 是**空参数**（文本面不写、运行时也不读），
		// 读盘时再去动它就是"替玩家改一个他没有意义的值"—— 老卡里那个残留数字原样留着。
		if (entry.hasSignalDuration())
			entry.waitAmount = Math.max(0, Math.min(SIGNAL_MAX_AMOUNT, entry.waitAmount));
		entry.frequency = readFrequency(tag, registries);
		entry.callTarget = tag.getString("call");
		// 缺 calln（老卡）→ 1 轮。⚠️ 这里**不能**再靠 `Math.max(1, …)` 兜底了：0 现在是**合法值**
		// （无限循环），拿 0 当"缺键"会让所有没写过这个键的数据静默变成无限循环。所以先看键在不在。
		entry.callRepeat = tag.contains("calln") ? clampCallRepeat(tag.getInt("calln")) : 1;
		// 调用偏移：**缺键（老卡 / 没设过）= (0,0,0)** ⇒ 行为与改造前完全一致。
		// ⚠️ 判据必须写成"键在不在"，不能像 calln 那样只靠取值 —— 这里 (0,0,0) 恰好就是缺省值，
		// 但显式写出来才不依赖 NBTHelper 对缺键的处理（缺键回落是契约的一部分）。
		entry.callShift = tag.contains("coff") ? NBTHelper.readBlockPos(tag, "coff") : BlockPos.ZERO;
		entry.errorMessage = tag.getString("err");
		entry.unloadRepeat = enumOf(UnloadRepeat.class, tag.getString("urep"), UnloadRepeat.ONCE);
		entry.unloadAmount = enumOf(UnloadAmount.class, tag.getString("uamt"), UnloadAmount.ONE_STACK);
		if (tag.contains("icon"))
			entry.icon = ItemStack.parseOptional(registries, tag.getCompound("icon"));
		// 过滤位置：缺键（老卡 / 没设过）= HELD ⇒ 行为就是"看手上那一份"（枚举名解析，见 enumOf）
		entry.filterSource = enumOf(FilterSource.class, tag.getString("fsrc"), FilterSource.HELD);
		if (tag.contains("fitem"))
			entry.filterItem = ItemStack.parseOptional(registries, tag.getCompound("fitem"));

		// ★ 自愈：**不需要坐标的条目，offset 归零**（用户第四十一轮）。
		//   这类操作（等待 / 信号 / 调用 / 退出 / 抛错 / 手持过滤）的坐标对运行时没有任何意义
		//   ——「调用」是控制类操作，既不移动、也没有目标，运行时**根本不读** {@link #offset}。
		//   老卡 / 老文本里那串数字只是"这条曾经是别的操作"（或老版本"新建条目取你正看的方块"）
		//   留下的残留：读进来就清掉，**下一次保存它就消失了**，脚本里也不会再冒出来
		//   （用户这轮就是被 `.sma` 里 `call 任务` 后面那串 `-3 0 -1` 问住的）。
		//   这也是"模型里这个字段对这类操作没有意义"的**正式表达**：写出侧
		//   （{@code ScriptDecompiler}）只对 {@code needsTarget()} 的条目写坐标、解析侧
		//   （{@code ScriptParser}）对写了坐标的这类操作直接报错、编辑器切换操作时同样清零。
		//   ⚠️ 顺序：必须在 {@link #filterSource} **读完之后** —— {@link #needsTarget()} 对「过滤」
		//   要看它是手持还是容器，容器模式的坐标是**数据参数**，一个字都不能动。
		if (!entry.needsTarget())
			entry.offset = BlockPos.ZERO;

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
		// 问**条目级**的 needsTarget()（不是 op.requiresTarget()）：过滤的容器模式才有坐标可报，
		// 手持模式报一个 (0,0,0) 会让人以为它要伸到臂自己那一格去（用户口径：手持模式不写坐标）。
		if (needsTarget())
			sb.append(' ')
				.append(offset.toShortString());
		if (op == OperationType.FILTER)
			sb.append(" source=")
				.append(filterSource)
				.append(" item=")
				.append(filterItem.isEmpty() ? "未设置" : filterItem.getHoverName()
					.getString());
		if (delay > 0)
			sb.append(" delay=")
				.append(delay);
		if (op == OperationType.WAIT)
			sb.append(" wait=")
				.append(waitAmount)
				.append(waitUnit);
		if (op == OperationType.SIGNAL)
			sb.append(" redstone=")
				.append(waitForPowered ? "on" : "off");
		if (op == OperationType.SIGNAL)
			sb.append(" mode=")
				.append(signalMode)
				.append(" ticks=")
				.append(signalTicks())
				// 频率那一对物品名：语义上与 {@code ArmEntryText.frequencyText}（卡片 / 护目镜的
				// "铁锭 + 红石" 那句）**同源** —— 都是"把频率的两个物品名拼起来"。但**没有并成一处**：
				// ① 那个方法是 private（只服务卡片的文字排版），这里在 program 包里够不着；
				// ② 这一行是**调试 / 护目镜的一行摘要**，写法刻意更紧凑：分隔符是不带空格的 {@code +}，
				//    而且**空槽也照写**（"未设置+红石"比省略一个更好排查，卡片上那一侧才需要省掉 Air）。
				// 所以两者是"同一件事的两种排版"，不是重复实现；要合并得先把"谁的口径"定下来。
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
		if (op == OperationType.CALL) {
			sb.append(" call=")
				.append(callTarget);
			// 调用偏移（与 source=/item= 同一类"非默认才报"的写法）：零偏移不写，
			// 否则每条调用都拖着一段 noise，真正错开的那条反而看不出来。
			if (hasCallShift())
				sb.append(" shift=")
					.append(callShift.toShortString());
		}
		if (op == OperationType.THROW)
			sb.append(" error=")
				.append(errorMessage);
		// 个数（取出 / 放入）：与 shift= 同一手法 —— **非默认才报**。默认是"小于等于、1 个"，
		// 那时报个数只是给每一条取放拖一段 noise；而"等于"（非默认模式）必须露出来，
		// 否则护目镜那行看起来与"有多少拿多少"一模一样。
		if (op.hasCount() && countIsSet()) {
			sb.append(" count=")
				.append(count);
			if (countIsExact())
				sb.append(" exact");
		}
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
