package io.github.Ling.create_sequenced_mechanical_arm.client;

import java.io.BufferedReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.AllKeys;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.menu.GhostItemSubmitPacket;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import com.simibubi.create.foundation.gui.widget.TooltipArea;

import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.menu.ArmProgramMenu;
import io.github.Ling.create_sequenced_mechanical_arm.network.ArmProgramEditPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardUiPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardViewPacket;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmEntryText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmIcons;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.SkipAction;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadAmount;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.UnloadRepeat;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry.WaitUnit;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask.LoopType;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import io.netty.buffer.Unpooled;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.gui.UIRenderHelper;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 操作表编辑器 —— <b>照 Create 列车时刻表屏幕（{@code ScheduleScreen}）复刻，只做用户指定的最小改动</b>
 * （PLAN.md §5.2）。
 *
 * <h2>两个页面（都长成时刻表那样）</h2>
 * <ul>
 *   <li><b>操作页</b>（默认）：卡片 = 一条操作，属于 {@link #currentTask}。</li>
 *   <li><b>任务页</b>：卡片 = 一个任务；底部第一个按钮在两页之间切换。点任务卡片背景选中它。</li>
 * </ul>
 *
 * <h2>与时刻表的差异（全部是用户指定的，共 4 处）</h2>
 * <ol>
 *   <li><b>属性只有一行</b>：时刻表的条件是"AND 行 × OR 列"；我们只保留<b>一行</b>胶囊（模型里没有 AND/OR）。
 *       因此去掉"追加一行"的 ＋（{@code SCHEDULE_CONDITION_APPEND}），把"新增一列（备选条件）"的 ＋
 *       （{@code SCHEDULE_CONDITION_NEW}）改成<b>"添加属性"</b>摆在行尾；已加过的属性自动从候选里排除。</li>
 *   <li><b>指针 = 插入位置 / 当前步骤</b>：照时刻表在左侧画 {@code SCHEDULE_POINTER}；点卡片<b>背景</b>
 *       把指针移过去；底部第三个按钮"插入新操作到下方"就插在指针后面。</li>
 *   <li><b>胶囊带物品图标</b>：动作/任务有虚拟图标（取物=置物台、组装=黄铜手、左键=铁镐…，见
 *       {@link ArmIcons}）；编辑器子面板的幽灵槽就是改它的地方 —— 与时刻表"拖物品进槽"同一套机制
 *       （{@code SCHEDULE_EDITOR_ADDITIONAL_SLOT} + 玩家背包 + {@code GhostItemSubmitPacket}）。</li>
 *   <li><b>允许删光</b>：任务、操作都能删到一条不剩。</li>
 * </ol>
 *
 * <h2>照抄数值（改前先对照上游行号）</h2>
 * {@code CARD_WIDTH=195}、{@code CARD_HEADER=22}、卡片画在 {@code (leftPos+25, topPos+yOffset)}、
 * yOffset 从 25 起、卡间距 10 夹 {@code STRIP_DOTTED}、裁剪区 {@code (16,16)-(236,189)}、
 * 上下 {@code fillGradient} 淡出、左竖轨 {@code STRIP_DARK}；卡片四层 {@code drawStretched}
 * （上游 {@code :487-537}）；胶囊三片 {@code CONDITION_LEFT/MIDDLE/RIGHT}（上游 {@code :609-644}）；
 * 滚轮 {@code -Δ*12 → chase(0.7, EXP)}（上游 {@code :909-973}）；编辑器面板
 * {@code SCHEDULE_EDITOR}@({@code leftPos-2, topPos+40})、选择框@({@code +56,+65,143×16})、
 * 确认键@({@code +168,+22})、取消键@({@code -45,+22})、幽灵槽@({@code +53,+87})、
 * 背包 {@code PLAYER_INVENTORY}@({@code +38,+122})（上游 {@code :176-253, 1020-1072}）。
 *
 * <h2>排版硬约束（实测踩出来的）</h2>
 * Create 的 {@code Label} 构造器会丢掉传入文本（{@code Label.java:25} 写死 "Label"），必须
 * {@code setTextAndTrim}；汉字约 9px/字；构造期不能碰 {@code minecraft} 字段（要达到 {@code init} 才赋值，
 * 读它会 NPE —— 表现是"一开界面客户端掉线"）；结构性改动只置 {@link #rebuildPending}，
 * 下一帧重建（回调里 {@code clearWidgets()} 会踩迭代）。
 */
public class ArmProgramScreen extends AbstractSimiContainerScreen<ArmProgramMenu> {

	/** 两个页面：任务列表 / 某个任务的操作列表。 */
	private enum Page {
		TASKS, ENTRIES
	}

	// ——— 上游原样常量 ———
	private static final int CARD_HEADER = 22;
	private static final int CARD_WIDTH = 195;
	/** 一行胶囊的**热区**高度（上游 {@code x -= 18; y -= 28} 后按 18 切行）。⚠️ 它已不参与卡片高度。 */
	private static final int CARD_ROW = 18;
	private static final int CARD_TOP = 25;
	/** 胶囊高度 —— 贴图是 16，里面那个物品图标也是 16×16，**这就是它压不下去的原因**。 */
	private static final int CHIP_H = 16;
	/** 胶囊行上下各留的边距（标题栏底沿 24 + 5 = 胶囊顶 29）。 */
	private static final int CARD_ROW_PAD = 5;
	/** 胶囊行的 y（上边框 2 + 标题栏 22 + 边距 5 = 29，与上游 {@code 29 + i * 18} 一致）。 */
	private static final int CARD_ROW_Y = 2 + CARD_HEADER + CARD_ROW_PAD;
	/**
	 * 底部那一条（胶囊下沿 → 卡片下沿）= 5px，**与行上边距对称**。
	 *
	 * <p>用户第二十七轮续十二：<i>"可以再窄一些，按钮没有重叠，不用担心按钮问题，直接调整即可，
	 * 按钮仍然在右下角不变。"</i> —— ≡ 复制键（12 高，画在 {@code CARD_HEIGHT - 14}）**不需要额外的高度**：
	 * 它落在卡片右外侧那块空地里（胶囊最多画到 x=179，而卡片内区到 193），卡片有 50 高它就完整在卡内。
	 * 代价是它的判定区与**横向滚动箭头**在竖向上有 3 行重叠（箭头仍点得到：箭头判定是 x 177..184，
	 * 被 ≡ 拿走的只有最右 3 列）—— 用户明确说这条不用担心，自检里写成"最多重叠 3 行"。
	 *
	 * <p>⚠️ ≡ 的贴图**自带一块底色**（12×12 全不透，色值正好等于卡片主体色），所以它
	 * **只能待在卡片主体上**：放进标题栏（浅色带）就会显出一个色块。用户第二十七轮续十一实测：
	 * <i>"这个复制按钮带了底色，还是放在右下角更合适吧"</i>。
	 */
	private static final int CARD_BOTTOM_PAD = 5;
	/**
	 * 卡片高度 = 上边框 2 + 标题栏 22 + 行上边距 5 + 胶囊 16 + 行下边距 5 = <b>50</b>。
	 *
	 * <p>⚠️ 上游是 {@code CARD_HEADER + 24 + maxRows * 18}（单行 = 64）：多出来的是替复制键留的 18px。
	 * 用户第二十七轮实测：<i>"像这样一个胶囊……它的高度有点太高了，属性下方出现了空白但是没有用"</i>，
	 * 续十二又补一句 <i>"可以再窄一些"</i>。截图量过（5:1）：胶囊占 29..44，上游卡片内区一直到 61
	 * —— 现在按"内容 + **上下对称的 5px**"收边，行下那 17px 里有 12px 还给了内容。
	 */
	private static final int CARD_HEIGHT = 2 + CARD_HEADER + CARD_ROW_PAD + CHIP_H + CARD_BOTTOM_PAD;
	/** 「复制卡片」在右下角（上游就是 {@code cardWidth - 14, cardHeight - 14}）。 */
	private static final int DUPLICATE_X = CARD_WIDTH - 14;
	private static final int DUPLICATE_Y = CARD_HEIGHT - 14;
	/** 胶囊之间的小缝（时刻表列间距是 10；我们一行里用 4）。 */
	private static final int CHIP_GAP = 4;
	/**
	 * 胶囊宽度封顶 —— 上游 {@code renderInput} 也把字段封在 {@code min(getFieldSize(...), 150)}
	 * （`ScheduleScreen:614`），但**150 放到我们的布局里是错的**：胶囊区裁剪到 GUI 204（局部 179），
	 * 行尾的「添加属性」＋ 贴图 19 宽、画在"行末 - 3"，所以最宽的一枚胶囊必须满足
	 * {@code 26 + W + 4 + 19 ≤ 179} ⇒ {@code W ≤ 130}。封顶取 <b>133</b>（再留 3px 余量）：
	 * 这样即使这一行滚到最右，＋ 也**一定**完整落在裁剪区里（{@code addPropReachable} 把它钉住了）。
	 *
	 * <p>原来的 150 会让"最后那枚胶囊"宽到把 ＋ 顶出裁剪区 —— 用户实测：
	 * <i>"备注太长会把添加属性的按键挤到外面，并且即使超出去了，没有显示，也可以点击"</i>。
	 */
	private static final int CHIP_MAX = 133;
	/** 胶囊区单独裁剪（上游 `ScheduleScreen:457` 的 {@code enableScissor(leftPos+43, …, leftPos+204, …)}）。 */
	private static final int ROW_CLIP_LEFT = 43;
	private static final int ROW_CLIP_RIGHT = 204;
	/** 编辑器第二行（"值"那一行）：上游把整条线画在 {@code topPos+87}（`ScheduleScreen:1066-1069`）。 */
	private static final int ROW2_Y = 87;
	/**
	 * 第二行的内容起点 = <b>上游的 {@code getGuiLeft() + 77}</b>（`ScheduleScreen:1068`）。
	 *
	 * <p>为什么是 77 而不是"图标槽右边 85"：贴图里那张 {@code SCHEDULE_EDITOR} 的第二行是个
	 * 面板局部 x 56..216 的凹槽（= GUI 54..214），图标槽（GUI 53..85）就压在它左端，
	 * 而**真正的物品画在 54..70**（槽框右边那 15px 是空的）。上游把整条线画在 77，
	 * 正好盖住槽框右侧的空档 —— 视觉上就是"输入框紧贴图标，两边都没有多余空白"。
	 *
	 * <p>第一版取 91（槽框右沿 85 再留 6px），于是左边多出一条空档、右边又伸到 217
	 * （越过面板凹槽的右沿 214），整排看起来是"整体偏移"（用户实测反馈）。
	 */
	private static final int FIELD_X = 77;
	/** 内容宽度：77 + 120 = 197，正好和第一行那个选择框（GUI 54..197）**右沿对齐**。 */
	private static final int FIELD_W = 120;
	/**
	 * 「等待信号」专用的第二行起点：GUI **115**（= 第三个幽灵槽的右沿 111 再留 4px）。
	 *
	 * <p>为什么它得单独一个值：那个操作要记**一对频率**（两个槽）**外加图标槽**，三个槽在 GUI 53..111
	 * （{@code ArmProgramMenu} 里 {@code 54 + 20 * i}），而默认起点 77 会压住第二、第三个槽 ——
	 * 用户实测："滚动选择框的左边把幽灵物品槽位占用了，也因此我无法确认你是否设置正确了"。
	 * 宽度相应减 38，右沿仍然是 197（与第一行选择框对齐）。
	 */
	private static final int FIELD_X_SIGNAL = 115;
	/**
	 * 「收发信号」第二行的**模式**控件（接收 / 发送 / 中断）：起点同 {@link #FIELD_X_SIGNAL}，宽 30。
	 *
	 * <p>算式（用户反馈第二轮重新算的账）：115..145 **30** ＋ 2 ＋ 147..177 **30**（数值）
	 * ＋ 2 ＋ 179..197 **18**（单位）＝ **82** ＝ {@code FIELD_W - 38} —— 右沿仍是 197。
	 * 每个控件里放得下的文字 ≤ {@code width - 10}：模式 20（汉字 2 字 18 ✔ / 英文 3 字母 18 ✔）、
	 * 数值 20（{@code "120"} = 18 ✔）、单位 8（{@code t/s/m} = 6 ✔）—— 三个控件各留 ≥2px 余量
	 * （模式名改短之后把省下的宽度让给了数值与单位框，见 §7.16b 二）。Create 的 {@code Label}
	 * **不裁剪、画超出盒子**，所以这些余量必须由 {@code signalRowProblem()} 自动验。
	 */
	private static final int SIGNAL_MODE_X = FIELD_X_SIGNAL;
	private static final int SIGNAL_MODE_W = 30;
	/** 模式框 + 2px 间隔之后 = 持续（脉冲时长）的**数值**框（0 = 一直保持）。 */
	private static final int SIGNAL_TICKS_X = SIGNAL_MODE_X + SIGNAL_MODE_W + 2;
	private static final int SIGNAL_TICKS_W = 30;
	/** 数值框 + 2px 间隔之后 = 持续的单位框（t / s / m，与等待/时限同一个枚举）。 */
	private static final int SIGNAL_UNIT_X = SIGNAL_TICKS_X + SIGNAL_TICKS_W + 2;
	private static final int SIGNAL_UNIT_W = 18;
	/** 第二行右沿（= {@code FIELD_X + FIELD_W}）：所有控件都必须 ≤ 它。 */
	private static final int SIGNAL_ROW_END = FIELD_X + FIELD_W;
	/** 第二行的**可用宽度**（左侧三个幽灵槽压到 GUI 111 ⇒ 115..197 只有 82px）。 */
	private static final int SIGNAL_ROW_W = FIELD_W - 38;
	/**
	 * 信号标题芯片的宽度上限 = {@code ROW_CLIP_RIGHT - 25 - 26} = <b>153</b>。
	 *
	 * <p>为什么不复用 {@link #CHIP_MAX}（133）：那个上限是"行尾还有一枚 ＋ 贴图"推出来的
	 * （★ 见 {@code addPropReachable}），而标题芯片在**标题栏**、右边什么都没有。
	 * 但它仍必须留在卡片内：芯片画在卡片局部 x=26，裁剪区右沿局部 179（卡片内区到 193）
	 * ⇒ 最宽 179 - 26 = 153。英文短语天生比中文长（{@code Recv + Wait off}），
	 * 卡在 133 会把文字挤出胶囊 —— 而 {@code renderSignalChip} 的文字**不裁剪**。
	 */
	private static final int SIGNAL_CHIP_MAX = ROW_CLIP_RIGHT - 25 - 26;
	/**
	 * 横向箭头在**卡片局部坐标**里的 x（= 上游的窗口坐标 {@code leftPos+40} / {@code leftPos+203} 减 25）。
	 * 热区用的也是同一对数（见 {@code action()}）。
	 */
	private static final int SCROLL_LEFT_X = 15;
	private static final int SCROLL_RIGHT_X = 178;
	/**
	 * 行尾"添加属性"＋ 的判定区（{@code cx} 从"行首胶囊左沿"起算）。
	 *
	 * <p>为什么左沿是 0 而不是 -3：那张 {@code SCHEDULE_CONDITION_NEW} 贴图是 19×16，＋ 号画在贴图内
	 * 第 8..13 列（实测贴图像素），贴图又画在 {@code x-3}，所以**＋ 号中心在 x+8**。
	 * 第一版把判定区按 {@code [-8, +8]} 居中在 x 上 → 整体偏左 8px，用户实测"和新建条目那儿差不多"。
	 * 现在左沿对齐标题芯片（x=26 → cx=0），右沿覆盖整张贴图（x-3+19 = x+16）。
	 */
	private static final int ADD_PROP_HIT_LEFT = 0;
	private static final int ADD_PROP_HIT_RIGHT = 16;

	private final ArmProgramMenu menu;

	private ArmProgram program;
	private final Deque<CompoundTag> undoStack = new ArrayDeque<>();
	private final Deque<CompoundTag> redoStack = new ArrayDeque<>();
	private boolean dirty;
	private boolean rebuildPending;
	private String status = "";

	private Page page = Page.ENTRIES;
	/** 操作页当前编辑哪个任务。 */
	private int currentTask;
	/** 指针：当前页卡片列表里的第几张（= 插入位置；运行中同一个标记也表示"当前步骤"）。 */
	private int pointerIndex;

	/**
	 * 每个列表**各自**的纵向滚动位置：任务页一份、**每个任务的操作页各一份**。
	 *
	 * <p>用户第二十七轮续十三实测：<i>"操作卡的任务部分和操作部分的位置记录是一样的……如果我编辑任务的时候
	 * 在大概序号 15 的位置，那么我切换到选择任务的时候也是一样的位置，尽管这个位置没有任何操作，
	 * 同样的，编辑其他任务的操作的时候也会把位置移动到切换前任务 UI 的位置。你应该让它们独立出来，
	 * 而不是共用一个位置信息。"</i>
	 *
	 * <p>另外，原来切换页面时那句 {@code scroll.setValue(0)} **根本不起作用**（这是个更隐蔽的坑）：
	 * {@code LerpedFloat} 的 {@code chaseTarget} 没被清掉，下一 tick 的 {@code tickChaser()} 又把它拉回去 ——
	 * 所以"切页归零"从一开始就是无效代码，玩家看到的就是位置原样带过去。
	 */
	private final Map<String, LerpedFloat> listScrolls = new HashMap<>();

	/**
	 * 当前页面对应的"列表身份"：任务页一份、**每个任务的操作页各一份**。
	 *
	 * <p>指针（{@link #pointers}）与滚动（{@link #listScrolls}）**共用这一个 key** —— 两者说的都是
	 * "我在这个列表里看到哪一行"；各写一套 key 迟早会留下"指针记得、滚动忘了"这种半对的状态。
	 */
	private String listKey() {
		return page == Page.TASKS ? "tasks" : "task#" + currentTask;
	}

	/** 当前页面对应的那个滚动位置（懒创建；不是每帧新建，所以位置能记住）。 */
	private LerpedFloat listScroll() {
		return listScrolls.computeIfAbsent(listKey(), key -> LerpedFloat.linear()
			.startWithValue(0));
	}

	/**
	 * 每个列表**各自**的指针位置（key 与滚动完全相同）。
	 *
	 * <p>用户实测：<i>"另外操作界面的指针也是，切换UI之后会回到第一个"</i>。指针原来只有
	 * {@link #pointerIndex} 一份，切页时被另一个列表的下标覆盖（而且任务页会拿到**条目下标**），
	 * 回来自然落在别处。滚动位置早就按列表分开了（见 {@link #listScrolls} 的说明），
	 * 指针是同一类东西，所以也按列表记。
	 *
	 * <p>⚠️ 这里记的是**浏览位置**（"我看到哪一行"），与滚动位置**同寿**（关掉界面就没了）。
	 * 真正需要跨重开保留的是"我在编辑哪个任务"——那是**语义**（录制要写进那个任务），存在卡上（{@code etask}）。
	 */
	private final Map<String, Integer> pointers = new HashMap<>();

	/**
	 * 一个列表**第一次**被打开时，指针该在哪。
	 *
	 * <p>任务页 = **正在编辑的那个任务**。用户实测：界面关掉再开、切到任务页，指针指着任务 1 而实际在任务 2 ——
	 * 任务页的指针就是"我在哪个任务上"，没有任何理由从第 1 个开始。操作页 = 第一行。
	 */
	private int defaultPointer() {
		if (page == Page.TASKS)
			return Mth.clamp(currentTask, 0, Math.max(0, program.tasks.size() - 1));
		return 0;
	}

	/** 纵向滚动条：拖拽中 / 鼠标与滑块上沿的差（见 {@code mouseDragged}）。 */
	private boolean draggingBar;
	private float barGrab;

	/** 每张卡片的胶囊行横向滚动（照上游 {@code horizontalScrolls}）。 */
	private final List<LerpedFloat> rowScrolls = new ArrayList<>();

	@Nullable
	private EditTarget editing;
	@Nullable
	private CompoundTag editSnapshot;
	@Nullable
	private Prop editingProp;
	/** 上一次命中的热区名字（只给自检用 —— 点击热区是手算的，必须能被自动验证）。 */
	private String lastHit = "";

	private final List<GuiEventListener> editorWidgets = new ArrayList<>();

	public ArmProgramScreen(ArmProgramMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		this.menu = menu;
		program = CardProgramStorage.read(menu.getCard(), registries());
		view = CardProgramStorage.readView(menu.getCard());
		// 打开时直接落到"正在编辑的那个任务"上：玩家在界外用 Ctrl+滚轮选了任务 3，
		// 界面就得开在任务 3 —— 否则那两个入口各说各话（§5.1 v5）。
		if (program.tasks.isEmpty()) {
			// 卡上一条任务都没有：直接进**任务页**。操作页在这里没有任何可编辑的东西
			// （用户实测反馈："在没有任务的时候打开编辑界面应该直接进入任务编辑而不是操作编辑"）。
			page = Page.TASKS;
		} else {
			currentTask = Mth.clamp(program.editorTask, 0, program.tasks.size() - 1);
		}
		// 指针的初始位置跟着页面走：任务页落在"正在编辑的那个任务"上，操作页落在第一行
		//（用户实测：重开界面后切到任务页，指针指着任务 1 而实际在任务 2）。
		pointerIndex = defaultPointer();
		// ★ §18：再恢复上次的浏览位置（页面 / 操作页指针 / 两个列表的滚动）——
		//   "关掉去世界里试一下、再打开接着改"要能接着上一行。
		applyView();
	}

	/** 上次关界面时存下来的浏览位置（{@link #pushView()} 用它判"变了没有"）。 */
	private CardProgramStorage.View view = CardProgramStorage.View.DEFAULT;

	/**
	 * 恢复上次的浏览位置（§18）。**只恢复浏览位置**：页面、操作页指针、两个列表的滚动。
	 *
	 * <p>当前任务**不在这里**恢复 —— 它是语义，构造函数上面那段已经由 {@code program.editorTask} 决定了；
	 * 这两件事的生命周期不同，混在一起就会出现"打开界面落在别处"那种各说各话（§5.1 v5、§18 的表）。
	 *
	 * <p>数值只做"非负"兜底，真正的上限交给 {@code init → clampPointers → clampListScroll}：
	 * 构造期还没有布局，算不出列表现在多长（内容可能在上次离开之后变短了）。
	 */
	private void applyView() {
		if (!program.tasks.isEmpty() && CardProgramStorage.PAGE_TASKS.equals(view.page()))
			page = Page.TASKS;
		// 指针：先把"当前任务的操作页那一份"记进账本（哪怕这次开在任务页，也不能把它冲成 0 ——
		// 否则"去任务页看了一眼再关掉"就会把操作页的位置弄丢）
		pointers.put("task#" + currentTask, Math.max(0, view.entry()));
		if (page == Page.ENTRIES)
			pointerIndex = Math.max(0, view.entry());
		restoreScroll("tasks", view.taskScroll());
		restoreScroll("task#" + currentTask, view.scroll());
	}

	/** 把一个列表的滚动直接摆到位（**不带动画**：恢复的位置不该从顶部滑过去）。 */
	private void restoreScroll(String key, float value) {
		if (value <= 0f)
			return;
		LerpedFloat chaser = listScrolls.computeIfAbsent(key, key2 -> LerpedFloat.linear()
			.startWithValue(0));
		chaser.setValue(value);
		chaser.chase(value, 1f, Chaser.EXP);
	}

	/** 当前浏览位置（跨重开要保留的那四个数）。 */
	private CardProgramStorage.View currentView() {
		pointers.put(listKey(), pointerIndex); // 先把"现在"落进按列表的账本，再取
		return new CardProgramStorage.View(pointers.getOrDefault("task#" + currentTask, 0), page.name(),
			scrollOf("task#" + currentTask), scrollOf("tasks"));
	}

	/** 某个列表当前的滚动值（没建过就是 0）。 */
	private float scrollOf(String key) {
		LerpedFloat chaser = listScrolls.get(key);
		return chaser == null ? 0f : chaser.getChaseTarget();
	}

	/**
	 * 把"看到哪"写回卡（{@link CardViewPacket}）。**没变就不发**。
	 *
	 * <p>为什么不走 {@code ArmProgramEditPacket}（全量保存）：那条路要过规模上限与体积校验，
	 * 而"滚动到第 15 行"根本不是数据改动 —— 玩家只是挪了挪视线，没有理由为此冒"整份程序被拒"的风险
	 * （与 {@code pushTaskSelection} 用 {@code CardUiPacket} 的理由相同，§5.1 v5）。
	 */
	private void pushView() {
		CardProgramStorage.View now = currentView();
		if (now.equals(view))
			return;
		view = now;
		PacketDistributor.sendToServer(new CardViewPacket(now.entry(), now.page(), now.scroll(), now.taskScroll()));
	}

	/**
	 * 幽灵槽激活数量：只有「等待信号」要**两个**（红石链接频率是一对物品），其余情况只用图标槽那个。
	 *
	 * <p>在 {@code init()} 里算（布局阶段），**不要**放到渲染里 —— 渲染是每帧的事，而槽位的可用性是布局信息；
	 * 原本写在 {@code renderEditor} 里，导致"界面刚建好、还没渲染"的那一小段时间状态是过期的
	 * （自检在 init 之后立刻断言：应为 2 实际 1）。
	 */
	private void updateTargetSlots() {
		// 编辑**属性**时三个槽都不适用（图标/频率都属于"条目本体"，用户实测抱怨过这里还留着频率槽）
		if (editing == null || editing.task || editing.propName() != null) {
			menu.targetSlotsActive = 0;
			return;
		}
		ArmProgramEntry entry = entry(new Card(false, editing.taskIndex, editing.entryIndex));
		// 「收发信号」= 图标 + 一对频率（3 个）；其余 = 只有图标（1 个）
		menu.targetSlotsActive = entry != null && entry.op.hasSignalMode() ? 3 : 1;
	}

	/** 现在编辑的是"某个属性"吗（而不是条目/任务本体）。 */
	private boolean editingProperty() {
		return editing != null && editing.propName() != null;
	}

	/**
	 * 注册表来源：优先当前世界，其次菜单里的玩家 —— <b>不能碰 {@code minecraft} 字段</b>：
	 * 它要到 {@code init(mc,w,h)} 才赋值，而 NeoForge 是"先 new 屏幕、再 init"（实测 NPE 掉线）。
	 */
	private HolderLookup.Provider registries() {
		if (minecraft != null && minecraft.level != null)
			return minecraft.level.registryAccess();
		if (menu.playerInventory.player != null)
			return menu.playerInventory.player.level()
				.registryAccess();
		return RegistryAccess.EMPTY;
	}

	// ————————————————————————— 生命周期 —————————————————————————

	@Override
	protected void init() {
		AllGuiTextures bg = AllGuiTextures.SCHEDULE;
		setWindowSize(bg.getWidth(), bg.getHeight());
		super.init();
		clearWidgets();
		editorWidgets.clear();
		clampPointers();

		menu.slotsActive = editing != null;
		updateTargetSlots();
		if (editing != null) {
			// 编辑子面板打开时**不建**列表页那四个按钮：它们属于列表页，
			// 留着会压在子面板/背包上并且能被点到（实测反馈）。
			// 上游同样做法：startEditing 里把 confirmButton 等一起 visible = false。
			buildEditor();
			return;
		}

		// 底部三个按钮（位置照样板：21 / 45 / 63，确认键右下）
		IconButton switchPage = new IconButton(leftPos + 21, topPos + 196, AllIcons.I_VIEW_SCHEDULE);
		switchPage.withCallback(this::togglePage);
		switchPage.setToolTip(page == Page.ENTRIES ? tr("btn.task_list") : tr("btn.enter_task"));
		addRenderableWidget(switchPage);

		IconButton undo = new IconButton(leftPos + 45, topPos + 196, AllIcons.I_REFRESH);
		undo.withCallback(this::undo);
		undo.setToolTip(tr("btn.undo"));
		addRenderableWidget(undo);

		IconButton insert = new IconButton(leftPos + 63, topPos + 196, AllIcons.I_ADD);
		insert.withCallback(this::insertAfterPointer);
		insert.setToolTip(page == Page.ENTRIES ? tr("btn.insert_entry") : tr("btn.insert_task"));
		addRenderableWidget(insert);

		IconButton confirm = new IconButton(leftPos + bg.getWidth() - 42, topPos + bg.getHeight() - 30,
			AllIcons.I_CONFIRM);
		confirm.withCallback(() -> minecraft.player.closeContainer());
		confirm.setToolTip(tr("btn.save"));
		addRenderableWidget(confirm);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		// 每个列表的滚动各自推进（没在看的那些也照推 —— 它们的值不该因为"没被渲染"而停住）
		for (LerpedFloat chaser : listScrolls.values())
			chaser.tickChaser();
		for (LerpedFloat chaser : rowScrolls)
			chaser.tickChaser();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		if (rebuildPending) {
			rebuildPending = false;
			init();
		}
		super.render(graphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public void removed() {
		super.removed();
		// 浏览位置（§18）：与"数据有没有改动"无关，先走自己那条轻量通道写回卡
		pushView();
		if (!dirty)
			return;
		// 会话状态一起落盘（§5.1 v5）：在界面里换到任务 2 再关掉，回世界后录入的就是任务 2
		program.editorTask = Mth.clamp(currentTask, 0, Math.max(0, program.tasks.size() - 1));
		program.editorMode = CardUiPacket.EDIT;
		PacketDistributor.sendToServer(new ArmProgramEditPacket(program.save(registries())));
	}

	// ————————————————————————— 卡片列表 —————————————————————————

	/** 一张卡：{@code task=true} 是任务卡（操作页里不会有）。 */
	private record Card(boolean task, int taskIndex, int entryIndex) {
	}

	/** 当前页的卡片列表。 */
	private List<Card> layout() {
		List<Card> cards = new ArrayList<>();
		if (page == Page.TASKS) {
			for (int t = 0; t < program.tasks.size(); t++)
				cards.add(new Card(true, t, -1));
		} else {
			ArmProgramTask task = task(currentTask);
			if (task != null)
				for (int e = 0; e < task.entries.size(); e++)
					cards.add(new Card(false, currentTask, e));
		}
		return cards;
	}

	@Nullable
	private ArmProgramTask task(int index) {
		return index >= 0 && index < program.tasks.size() ? program.tasks.get(index) : null;
	}

	@Nullable
	private ArmProgramEntry entry(Card card) {
		ArmProgramTask task = task(card.taskIndex);
		if (task == null || card.entryIndex < 0 || card.entryIndex >= task.entries.size())
			return null;
		return task.entries.get(card.entryIndex);
	}

	/** 第 index 张卡的 y（相对 topPos）：卡高 + 10 间距（照上游）。 */
	private int cardY(int index) {
		return CARD_TOP + index * (CARD_HEIGHT + 10);
	}

	/** 末尾"新卡片"的 y（上游：最后一张 + 10 + 9）。 */
	private int newCardY(List<Card> cards) {
		return cards.isEmpty() ? CARD_TOP : cardY(cards.size() - 1) + CARD_HEIGHT + 19;
	}

	// ————————————————————————— 纵向滚动（每个列表一份 + 右侧滑块） —————————————————————————

	/** 卡片列表的**可视高度**（内容窗口 topPos+16 .. topPos+189）。 */
	private static final int LIST_VIEW_H = 189 - 16;
	/**
	 * 滚动条的位置：**面板内部、贴着右内边框**（窗口局部 x=231..235 —— 面板的内侧边线在 236/237）。
	 *
	 * <p>用户第二十七轮续十四实测：<i>"滑块在 UI 外面，可以把它放在 UI 里面贴着右边。"</i>
	 * 第一版取的 x=240 已经在面板之外（面板纹理可见宽度只到 237），所以画到了界面外面。
	 * 宽度 5 是贴图定的（{@code STOCK_KEEPER_REQUEST_SCROLL_*} 都是 5 宽）。
	 */
	private static final int BAR_X = 231;
	private static final int BAR_W = 5;
	private static final int BAR_MIN_THUMB = 12;

	/** 当前列表的总内容高度（最后一张卡 + 末尾"新卡片"按钮 + 一点余量）。 */
	private int listContentHeight() {
		return newCardY(layout()) + 30;
	}

	/** 当前列表能滚多远（装得下就是 0 —— 这时既不画滑块也不接受拖拽）。 */
	private float maxListScroll() {
		return Math.max(0, listContentHeight() - LIST_VIEW_H);
	}

	/**
	 * 直接落到某个滚动位置（滑块拖拽用：不做缓动，鼠标在哪就在哪）。
	 * {@code setValue} 与 {@code chase} **都要**：前者改当前值，后者改目标值 ——
	 * 只改一个的话下一 tick 的 {@code tickChaser()} 会把另一个拉回去（切页"归零"失效就是这个原因）。
	 */
	private void setListScroll(float value) {
		float v = Mth.clamp(value, 0, maxListScroll());
		LerpedFloat chaser = listScroll();
		chaser.setValue(v);
		chaser.chase(v, 0.5f, Chaser.EXP);
	}

	/** 把当前列表的滚动夹回自己的范围（内容变短 / 切到另一个列表时用）。 */
	private void clampListScroll() {
		float max = maxListScroll();
		LerpedFloat chaser = listScroll();
		if (chaser.getChaseTarget() > max || chaser.getValue() > max)
			setListScroll(Mth.clamp(chaser.getChaseTarget(), 0, max));
	}

	private int barThumbHeight() {
		int content = listContentHeight();
		int h = content <= LIST_VIEW_H ? LIST_VIEW_H : (int) ((long) LIST_VIEW_H * LIST_VIEW_H / content);
		return Mth.clamp(h, BAR_MIN_THUMB, LIST_VIEW_H);
	}

	/** 滑块上沿的 y（相对 topPos）。{@code partialTicks} 只为让画出来的位置跟手。 */
	private int barThumbY(float partialTicks) {
		float max = maxListScroll();
		if (max <= 0)
			return 16;
		float t = Mth.clamp(listScroll().getValue(partialTicks) / max, 0, 1);
		return 16 + (int) ((LIST_VIEW_H - barThumbHeight()) * t);
	}

	/** 鼠标点/拖到了滚动条上（只有内容装不下时才认）。 */
	private boolean barHit(double mouseX, double mouseY) {
		if (maxListScroll() <= 0)
			return false;
		int x = (int) mouseX - leftPos;
		int y = (int) mouseY - topPos;
		return x >= BAR_X && x < BAR_X + BAR_W && y >= 16 && y <= 189;
	}

	/** 滑块上沿放到 {@code thumbTop} 时对应的滚动量。 */
	private float barScrollFor(int thumbTop) {
		int span = LIST_VIEW_H - barThumbHeight();
		if (span <= 0)
			return 0;
		return Mth.clamp((thumbTop - 16) / (float) span, 0, 1) * maxListScroll();
	}

	/**
	 * 右侧那条滑块（内容装不下才画；绘制与拖拽用的是同一套几何）。
	 *
	 * <p>滑块**不是自己画的色块，而是 Create 自己的滚动条贴图** —— 库存请求界面
	 * （{@code StockKeeperRequestScreen}）用的那套 {@code STOCK_KEEPER_REQUEST_SCROLL_TOP/MID/BOT}
	 * 加 {@code _PAD}（5×1，拉长当主体），拼法与上游逐行一致：PAD 拉满整段 + TOP 盖顶 + MID 居中 +
	 * BOT 盖底。用户第二十七轮续十四问"滑块的贴图是取自哪里呢？"——
	 * 第一版是 `graphics.fill` 三块纯色（所以"上面一排更亮、下面一排和主体一样"），
	 * 现在换成贴图，顶部自带高光、底部自带暗边（就是用户想要的那种立体感）。
	 *
	 * <p>轨道仍是自绘（Create 没有轨道贴图）：拖拽中加深一点，让"正在拖"看得出来。
	 *
	 * <p>⚠️ 整条滚动条画在 <b>z=300</b>（位姿里抬 z，不是"后画就行"）：列表上下那两条**渐隐**
	 * （{@code fillGradient}，z=200）会盖住它 —— 用户第二十七轮续十五实测：
	 * <i>"滑块移动到最上方和最下方会被这个半透明的渐变色贴图遮挡，应该是"</i>。
	 * 那张渐隐贴图本来就是画在 z=200 让它压住卡片的（Create 的老规矩：z 越大越在上面），
	 * 所以滚动条必须抬到它上面去。
	 */
	private void renderScrollBar(GuiGraphics graphics, float partialTicks) {
		if (maxListScroll() <= 0)
			return;
		int thumbH = barThumbHeight();
		int thumbY = topPos + barThumbY(partialTicks);
		int x = leftPos + BAR_X;
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(0, 0, 300);
		graphics.fill(x, topPos + 16, x + BAR_W, topPos + 189, draggingBar ? 0x88000000 : 0x55000000);
		AllGuiTextures pad = AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_PAD;
		UIRenderHelper.drawStretched(graphics, x, thumbY, pad.getWidth(), thumbH, 0, pad);
		AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_TOP.render(graphics, x, thumbY);
		if (thumbH > 16)
			AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_MID.render(graphics, x, thumbY + thumbH / 2 - 4);
		AllGuiTextures.STOCK_KEEPER_REQUEST_SCROLL_BOT.render(graphics, x, thumbY + thumbH - 5);
		graphics.pose()
			.popPose();
	}

	private void clampPointers() {
		int size = layout().size();
		pointerIndex = Mth.clamp(pointerIndex, 0, Math.max(0, size - 1));
		// 滚动的范围也跟着内容走：内容变短了要夹回来，否则会在列表下方留出一片空白
		clampListScroll();
		while (rowScrolls.size() < Math.max(1, size))
			rowScrolls.add(LerpedFloat.linear()
				.startWithValue(0));
		while (rowScrolls.size() > Math.max(1, size))
			rowScrolls.remove(rowScrolls.size() - 1);
	}

	/** 在界面里换任务：改本地会话状态 + 写回卡（与界外 Ctrl+滚轮是同一条路径，§5.1 v5）。 */
	private void selectTask(int index) {
		if (program.tasks.isEmpty())
			return;
		currentTask = Mth.clamp(index, 0, program.tasks.size() - 1);
		pushTaskSelection();
	}

	/**
	 * 把"正在编辑哪个任务"写回卡。界面里选任务**不是**数据改动，所以不走 {@code removed()} 的全量保存
	 * （那条路要过规模上限校验，没必要为一次选择冒险），改用与 Ctrl+滚轮同一个 {@code CardUiPacket}。
	 * 本地 {@code program} 也同步改：之后若真的保存，带出去的下标是同一个。
	 */
	private void pushTaskSelection() {
		program.editorTask = currentTask;
		program.editorMode = CardUiPacket.EDIT;
		PacketDistributor.sendToServer(new CardUiPacket(CardUiPacket.EDIT, currentTask));
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		AllGuiTextures.SCHEDULE.render(graphics, leftPos, topPos);
		ArmProgramTask current = task(currentTask);
		Component heading;
		if (editing != null)
			heading = tr("title_editing", editingProp == null ? tr("edit.op") : editingProp.name);
		else if (page == Page.TASKS)
			heading = tr("title_tasks", program.tasks.size());
		else
			heading = tr("title_entries", currentTask + 1, current == null ? "?" : current.name);
		int center = leftPos + (AllGuiTextures.SCHEDULE.getWidth() - 8) / 2;
		graphics.drawString(font, heading, center - font.width(heading) / 2, topPos + 4, 0x505050, false);

		if (editing == null)
			renderCards(graphics, partialTick);
		else
			renderEditor(graphics);

		if (!status.isEmpty())
			graphics.drawString(font, status, leftPos + 16, topPos + 216, 0xE0A030, false);
	}

	/** 上游 {@code renderSchedule}（:388-485）的移植：竖轨 + 裁剪 + 逐卡 + 指针 + 末尾新卡 + 淡出。 */
	private void renderCards(GuiGraphics graphics, float partialTicks) {
		UIRenderHelper.drawStretched(graphics, leftPos + 33, topPos + 16, 3, 173, 200,
			AllGuiTextures.SCHEDULE_STRIP_DARK);

		List<Card> cards = layout();
		float scrollOffset = -listScroll().getValue(partialTicks);
		graphics.enableScissor(leftPos + 16, topPos + 16, leftPos + 236, topPos + 189);

		if (cards.isEmpty() || pointerIndex == 0)
			UIRenderHelper.drawStretched(graphics, leftPos + 33, topPos + 16, 3, 10, -100,
				AllGuiTextures.SCHEDULE_STRIP_LIGHT);

		for (int i = 0; i < cards.size(); i++) {
			int y = cardY(i);
			// 指针（上游 :402-410）：跟着列表滚，超出视野换 offscreen 版
			if (i == pointerIndex) {
				graphics.pose()
					.pushPose();
				float expected = scrollOffset + topPos + y + 4;
				float actual = Mth.clamp(expected, topPos + 18, topPos + 170);
				graphics.pose()
					.translate(0, actual, 0);
				(expected == actual ? AllGuiTextures.SCHEDULE_POINTER : AllGuiTextures.SCHEDULE_POINTER_OFFSCREEN)
					.render(graphics, leftPos, 0);
				graphics.pose()
					.popPose();
			}

			graphics.pose()
				.pushPose();
			graphics.pose()
				.translate(0, scrollOffset, 0);
			renderCard(graphics, cards, i, y, partialTicks);
			graphics.pose()
				.popPose();

			if (i + 1 < cards.size()) {
				graphics.pose()
					.pushPose();
				graphics.pose()
					.translate(0, scrollOffset, 0);
				AllGuiTextures.SCHEDULE_STRIP_DOTTED.render(graphics, leftPos + 29, topPos + y + CARD_HEIGHT - 3);
				graphics.pose()
					.popPose();
			}
		}

		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(0, scrollOffset, 0);
		int newY = newCardY(cards);
		AllGuiTextures.SCHEDULE_STRIP_END.render(graphics, leftPos + 29, topPos + newY);
		AllGuiTextures.SCHEDULE_CARD_NEW.render(graphics, leftPos + 43, topPos + newY);
		graphics.pose()
			.popPose();

		graphics.disableScissor();

		int z = 200;
		graphics.fillGradient(leftPos + 16, topPos + 16, leftPos + 236, topPos + 26, z, 0x77000000, 0x00000000);
		graphics.fillGradient(leftPos + 16, topPos + 179, leftPos + 236, topPos + 189, z, 0x00000000, 0x77000000);

		// 右侧滑块画在裁剪区之外（x=231..235 在卡片右侧那条空当里），所以放在 disableScissor 之后；
		// 它自己再抬到 z=300（见 renderScrollBar 的说明）—— 不然上下那两条渐隐会盖住它。
		renderScrollBar(graphics, partialTicks);
	}

	/** 上游 {@code renderScheduleEntry}（:487-537）+ 我们的"一行胶囊"。 */
	private void renderCard(GuiGraphics graphics, List<Card> cards, int index, int y, float partialTicks) {
		Card card = cards.get(index);
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(leftPos + 25, topPos + y, 0);

		AllGuiTextures light = AllGuiTextures.SCHEDULE_CARD_LIGHT;
		AllGuiTextures medium = AllGuiTextures.SCHEDULE_CARD_MEDIUM;
		AllGuiTextures dark = AllGuiTextures.SCHEDULE_CARD_DARK;

		UIRenderHelper.drawStretched(graphics, 0, 1, CARD_WIDTH, CARD_HEIGHT - 2, 0, light);
		UIRenderHelper.drawStretched(graphics, 1, 0, CARD_WIDTH - 2, CARD_HEIGHT, 0, light);
		UIRenderHelper.drawStretched(graphics, 1, 1, CARD_WIDTH - 2, CARD_HEIGHT - 2, 0, dark);
		UIRenderHelper.drawStretched(graphics, 2, 2, CARD_WIDTH - 4, CARD_HEIGHT - 4, 0, medium);
		UIRenderHelper.drawStretched(graphics, 2, 2, CARD_WIDTH - 4, CARD_HEADER, 0, light);

		AllGuiTextures.SCHEDULE_CARD_REMOVE.render(graphics, CARD_WIDTH - 14, 2);
		// 「复制卡片」在右下角（上游位置原样）。它自带一块**等于卡片主体色**的底色，
		// 所以只能待在主体上 —— 放进标题栏（浅色带）会显出一个色块（用户实测）。
		AllGuiTextures.SCHEDULE_CARD_DUPLICATE.render(graphics, DUPLICATE_X, DUPLICATE_Y);
		if (index > 0)
			AllGuiTextures.SCHEDULE_CARD_MOVE_UP.render(graphics, CARD_WIDTH, CARD_HEADER - 14);
		if (index < cards.size() - 1)
			AllGuiTextures.SCHEDULE_CARD_MOVE_DOWN.render(graphics, CARD_WIDTH, CARD_HEADER);

		UIRenderHelper.drawStretched(graphics, 8, 0, 3, CARD_HEIGHT + 10, 0, AllGuiTextures.SCHEDULE_STRIP_LIGHT);
		(card.task ? AllGuiTextures.SCHEDULE_STRIP_ACTION : AllGuiTextures.SCHEDULE_STRIP_TRAVEL)
			.render(graphics, 4, 6);
		AllGuiTextures.SCHEDULE_STRIP_WAIT.render(graphics, 4, 28);

		// 序号（1 基）：跳转/调用时填的就是这个号。主任务（第 1 张任务卡）用金色标出来。
		graphics.drawString(font, String.valueOf(index + 1), 14, 8,
			card.task && index == 0 ? 0xFFD966 : 0xFFFFFF, true);

		// 标题芯片（带虚拟物品图标）。「收发信号」是专用画法：图标 · 短语 · 物品 · 物品 · 时长（有才写）
		ArmProgramEntry headerEntry = card.task ? null : entry(card);
		if (headerEntry != null && headerEntry.op.hasSignalMode())
			renderSignalChip(graphics, headerEntry, 26, 5, headerWidth(card, index));
		else
			renderChip(graphics, headerIcon(card), headerIcon2(card), headerChip(card, index), 26, 5, false,
				headerWidth(card, index));

		// ⚠️ 胶囊区是**单独一块裁剪区**（上游 `ScheduleScreen:457`）：内容再宽也只裁到卡片内侧，
		// 不会画到卡片外面去（用户反馈"图三"里胶囊越过卡片右边缘就是这个没做）。
		graphics.enableScissor(leftPos + ROW_CLIP_LEFT, 0, leftPos + ROW_CLIP_RIGHT, 400);
		renderChips(graphics, card, index, partialTicks);
		graphics.disableScissor();

		// 需要横向滚动时：卡片中间两侧画箭头（上游 :464-475）
		// ⚠️ 上游是在**窗口**坐标画的（`leftPos + 40` / `leftPos + 203`），我们在卡片的位姿里
		// （原点已经 translate 到 `leftPos + 25`），所以要减掉 25 → 15 / 178。
		// 第一版直接抄了 40 / 203，于是画出来的箭头比它的热区（本来就按 15/178 算的）右移了 25px。
		if (rowScrollable(card)) {
			int center = scrollArrowY();
			float target = rowScrolls.get(Math.min(index, rowScrolls.size() - 1))
				.getChaseTarget();
			if (!Mth.equal(target, 0))
				AllGuiTextures.SCHEDULE_SCROLL_LEFT.render(graphics, SCROLL_LEFT_X, center);
			if (!Mth.equal(target, maxRowScroll(card)))
				AllGuiTextures.SCHEDULE_SCROLL_RIGHT.render(graphics, SCROLL_RIGHT_X, center);
		}

		graphics.pose()
			.popPose();
	}

	/** 一行胶囊 + 行尾"添加属性"＋（滚动与两端淡出都在这里）。 */
	private void renderChips(GuiGraphics graphics, Card card, int index, float partialTicks) {
		float rowOffset = rowScrollOffset(card, index, partialTicks);
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(-rowOffset, 0, 0);
		int x = 26;
		for (Prop prop : shownProps(card)) {
			int width = chipWidth(prop);
			renderChip(graphics, prop.chipIcon(), prop.chip, x, CARD_ROW_Y, x != 26, width);
			x += width + CHIP_GAP;
		}
		graphics.pose()
			.popPose();
		// 两端淡出（上游 :570-577：先把位姿转 -90°，让 fillGradient 变成左右方向）
		// ⚠️ 原版 {@code fillGradient} 是**上下**渐变；我第一版把上游的旋转去掉、直接按坐标画，
		// 于是渐变方向错了（用户实测："渐变边框确实在左右两边，但渐变是上下渐变的"）。
		// 这里不用旋转，直接按列插值画一条水平渐变，方向和位置都一眼可读。
		if (rowScrollable(card)) {
			int z = 200;
			hGradient(graphics, 18, 10, CARD_HEADER + 2, CARD_HEIGHT - 2, z, 0x44000000, 0x00000000);
			hGradient(graphics, CARD_WIDTH - 26, 10, CARD_HEADER + 2, CARD_HEIGHT - 2, z, 0x00000000, 0x44000000);
		}

		// ⚠️ ＋ 要**退出滚动位姿之后**、按**可见坐标**画（{@link #plusLocalX}），并且画在淡出之上。
		// 第一版在滚动位姿里拿"排版坐标"（没减滚动量）判可见性 ⇒ 滚到最右、＋ 明明在视野里，
		// 判断用的却是一个屏幕外的坐标，于是**没画出来**（用户实测："添加属性的按钮不见了"），
		// 而命中那一侧用的是可见坐标 —— 于是表现为"有悬停提示、却没有按钮"。
		int plusX = plusLocalX(x - 26, rowOffset);
		if (addPropVisible(plusX))
			AllGuiTextures.SCHEDULE_CONDITION_NEW.render(graphics, plusX, CARD_ROW_Y);
	}

	/**
	 * 行尾「添加属性」＋ 画在哪（**卡片局部**坐标，已含滚动量）—— **绘制与命中共用这一个算式**，
	 * 免得两边各算一份、出现"看得见却点不到"或"有提示却没画"（后者是用户实测到的）。
	 *
	 * @param cursor 胶囊行的总宽（{@code Σ(胶囊宽 + 缝)}，不含起点 26）
	 * @param rowOffset 当前横向滚动量
	 */
	private static int plusLocalX(int cursor, float rowOffset) {
		return 26 + cursor - (int) rowOffset - 3;
	}

	/** 水平渐变（左→右）：原版 {@code fillGradient} 只做上下，这里按 1px 列插值。 */
	private static void hGradient(GuiGraphics graphics, int x, int width, int y1, int y2, int z, int leftColor,
		int rightColor) {
		for (int i = 0; i < width; i++) {
			float t = width <= 1 ? 0 : (float) i / (width - 1);
			graphics.fill(x + i, y1, x + i + 1, y2, z, lerpArgb(leftColor, rightColor, t));
		}
	}

	private static int lerpArgb(int from, int to, float t) {
		int a = (int) Mth.lerp(t, from >>> 24, to >>> 24);
		int r = (int) Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF);
		int g = (int) Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF);
		int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
		return a << 24 | r << 16 | g << 8 | b;
	}

	/** 横向箭头（以及它的热区）的 y —— 绘制和命中必须用同一个。 */
	private int scrollArrowY() {
		return (CARD_HEIGHT - 8 + CARD_HEADER) / 2;
	}

	/**
	 * 行尾「添加属性」＋ 在**当前滚动位置**下是否整块落在胶囊裁剪区里。
	 *
	 * <p>为什么要判：胶囊区是一块单独的裁剪区（{@link #ROW_CLIP_LEFT}..{@link #ROW_CLIP_RIGHT}），
	 * 行内容太长时 ＋ 会被推到裁剪区外面 —— "滚过去就能看到"是正常的（上游也是这么滚的），
	 * 但**看不见还能点到**不是。用户第二十七轮实测：<i>"备注太长会把添加属性的按键挤到外面，
	 * 并且即使超出去了，没有显示，也可以点击"</i>。所以绘制（{@code renderChips}）与命中
	 * （{@code action}）用**同一个判据**，两边永远不会出现"画了却点不到"或"没画却能点"。
	 *
	 * @param plusLocalX ＋ 贴图左沿在**卡片局部**坐标里的 x（画的时候是 {@code x - 3}，
	 *        判定的时候是 {@code 26 + 已排胶囊总宽 - 当前滚动量}）
	 */
	private static boolean addPropVisible(int plusLocalX) {
		int clipLeft = ROW_CLIP_LEFT - 25; // 裁剪区是窗口坐标，卡片局部的原点是 leftPos+25
		int clipRight = ROW_CLIP_RIGHT - 25;
		return plusLocalX >= clipLeft && plusLocalX + PLUS_SPRITE_W <= clipRight;
	}

	/**
	 * 这一行**滚到最右之后**＋ 够不够得到 —— 这是"看不见就靠翻格"的兜底承诺，必须是恒真的。
	 *
	 * <p>滚到最右时整行的偏移量 = 行宽 - 最后一枚胶囊（{@code maxRowScroll} = 胶囊数 - 1 格），
	 * 于是 ＋ 落在 {@code 26 + 最后一枚胶囊宽 + 缝} 处 —— 与它是哪一枚胶囊无关，
	 * 只取决于 {@link #CHIP_MAX}。这就是 {@code CHIP_MAX ≤ 130 + 余量} 的由来（{@code addPropVisible} 必须为真）。
	 */
	private static boolean addPropReachable(int lastChipWidth) {
		return addPropVisible(26 + lastChipWidth + CHIP_GAP - 3);
	}

	/** 「添加属性」＋ 的贴图宽度（{@code SCHEDULE_CONDITION_NEW} 是 19×16）。 */
	private static final int PLUS_SPRITE_W = 19;

	/**
	 * 「备注」胶囊的宽度上限（自检用）。倒推依据：裁剪区右边界局部 179，＋ 贴图 19 宽、画在"行末-3"，
	 * 而备注胶囊**永远排在最后**（{@code ArmEntryText.entryProps} 的顺序），所以
	 * {@code 26 + 上限 + 19 ≤ 179} ⇒ 上限 ≤ 134；这里取 130 留 4px 余量。
	 */
	private static final int NOTE_CHIP_MAX_W = 130;

	/** 上游 {@code renderInput}（:609-644）：胶囊三片 + 可选物品图标 + 文字。 */
	private void renderChip(GuiGraphics graphics, ItemStack icon, Component text, int x, int y, boolean clean,
		int width) {
		renderChip(graphics, icon, ItemStack.EMPTY, text, x, y, clean, width);
	}

	/** 双图标版：红石链接频率是一对物品，胶囊上并排画两个（上游时刻表的条件胶囊也是这么显示的）。 */
	private void renderChip(GuiGraphics graphics, ItemStack icon, ItemStack icon2, Component text, int x, int y,
		boolean clean, int width) {
		boolean hasItem = !icon.isEmpty();
		boolean hasSecond = !icon2.isEmpty();
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(x, y, 0);
		UIRenderHelper.drawStretched(graphics, 0, 0, width, CHIP_H, 0, AllGuiTextures.SCHEDULE_CONDITION_MIDDLE);
		(clean ? AllGuiTextures.SCHEDULE_CONDITION_LEFT_CLEAN : AllGuiTextures.SCHEDULE_CONDITION_LEFT)
			.render(graphics, clean ? 0 : -3, 0);
		AllGuiTextures.SCHEDULE_CONDITION_RIGHT.render(graphics, width - 2, 0);
		if (hasItem) {
			AllGuiTextures.SCHEDULE_CONDITION_ITEM.render(graphics, 3, 0);
			GuiGameElement.of(icon)
				.at(4, 0)
				.render(graphics);
		}
		if (hasSecond) {
			int secondX = hasItem ? 22 : 3;
			AllGuiTextures.SCHEDULE_CONDITION_ITEM.render(graphics, secondX, 0);
			GuiGameElement.of(icon2)
				.at(secondX + 1, 0)
				.render(graphics);
		}
		if (text != null)
			graphics.drawString(font, font.substrByWidth(text, 120)
				.getString(), hasSecond ? 46 : hasItem ? 28 : 8, 4, 0xff_f2f2ee);
		graphics.pose()
			.popPose();
	}

	private int chipWidth(Prop prop) {
		return chipWidth(prop.chipIcon(), prop.chip);
	}

	private int chipWidth(ItemStack icon, Component text) {
		return chipWidth(icon, ItemStack.EMPTY, text);
	}

	private int chipWidth(ItemStack icon, ItemStack icon2, Component text) {
		int textWidth = text == null ? 0 : font.width(text);
		int icons = (icon.isEmpty() ? 0 : 20) + (icon2.isEmpty() ? 0 : 20);
		// 封顶 150：文字本来就在 renderChip 里被 substrByWidth 截到 120，不封顶的话胶囊框会远超文字
		return Mth.clamp(Math.max(textWidth + icons + 16, 32), 32, CHIP_MAX);
	}

	private int headerWidth(Card card, int index) {
		ArmProgramEntry entry = card.task ? null : entry(card);
		if (entry != null && entry.op.hasSignalMode())
			return Math.max(signalChipWidth(entry), 100);
		return Math.max(chipWidth(headerIcon(card), headerIcon2(card), headerChip(card, index)), 100);
	}

	/**
	 * 「收发信号」的标题芯片：<b>图标 · 短语（接收信号/发送信号/中断信号）· 频率物品 · 频率物品 · 时间（有才写）</b>。
	 *
	 * <p>用户反馈第二轮的读法（图二那张）：操作名这一处随模式变成短语，极性**不再单独写"开/关"**
	 * （已含在词里）；只有"发 + 持续 &gt; 0"才在后面加时间（{@code 发送信号 ⚙⚙ 1s}；{@code 0} = 保持 ⇒ 不写）；
	 * 老卡 {@code redpow=false} 仍然写成 {@code 接收信号 ⚙⚙ 等断电}。
	 * 短语与尾巴**都从 {@link ArmEntryText} 取**（与胶囊 / 护目镜同一个来源：§11 决策 43），这里不另写一份。
	 *
	 * <p>两个频率物品**单独占两个物品槽**（不是塞在文字里），**空槽也照画空框**
	 * —— 这样"频率有一格留空"看得出来（用户："频率本身是可以填空的，不是必须两个物品"）。
	 */
	private void renderSignalChip(GuiGraphics graphics, ArmProgramEntry entry, int x, int y, int width) {
		ItemStack entryIcon = entry.icon.isEmpty() ? ArmIcons.defaultIcon(OperationType.SIGNAL) : entry.icon;
		ItemStack first = entry.frequency == null ? ItemStack.EMPTY : entry.frequency.getFirst()
			.getStack();
		ItemStack second = entry.frequency == null ? ItemStack.EMPTY : entry.frequency.getSecond()
			.getStack();
		Component label = ArmEntryText.signalHeadText(entry);
		Component tail = ArmEntryText.signalTailText(entry);

		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(x, y, 0);
		UIRenderHelper.drawStretched(graphics, 0, 0, width, CHIP_H, 0, AllGuiTextures.SCHEDULE_CONDITION_MIDDLE);
		AllGuiTextures.SCHEDULE_CONDITION_LEFT.render(graphics, -3, 0);
		AllGuiTextures.SCHEDULE_CONDITION_RIGHT.render(graphics, width - 2, 0);

		// ① 图标（自定义优先，否则用默认的红石链接）
		AllGuiTextures.SCHEDULE_CONDITION_ITEM.render(graphics, 3, 0);
		GuiGameElement.of(entryIcon)
			.at(4, 0)
			.render(graphics);

		// ② 短语（接收信号 / 发送信号 / 中断信号）
		int textX = 26;
		graphics.drawString(font, label.getString(), textX, 4, 0xff_f2f2ee);
		textX += font.width(label) + 4;

		// ③ 两个信号物品槽（空的也画空框，位置留出来）
		for (ItemStack item : new ItemStack[] { first, second }) {
			AllGuiTextures.SCHEDULE_CONDITION_ITEM.render(graphics, textX - 1, 0);
			if (!item.isEmpty())
				GuiGameElement.of(item)
					.at(textX, 0)
					.render(graphics);
			textX += 19;
		}

		// ④ 尾巴：发的时长（1s）/ 老卡的"等断电"；**空就一个字都不画**（0 = 一直保持 ⇒ 不写）
		String tailText = tail.getString();
		if (!tailText.isEmpty())
			graphics.drawString(font, tailText, textX + 2, 4, 0xff_f2f2ee);
		graphics.pose()
			.popPose();
	}

	/**
	 * 信号芯片里**文字实际画到哪**（相对芯片左沿）= 图标槽 26 + 短语 + 4 + 两个物品槽 38 + 2 + 尾巴。
	 *
	 * <p>单独抽出来是为了让"宽度"与"绘制"用**同一个算式**：{@link #renderSignalChip} 里的
	 * {@code textX} 推进就是这条式子，两边各写一份必然会分家（表现成"文字糊出胶囊"）。
	 */
	private int signalChipTextEnd(ArmProgramEntry entry) {
		return 26 + font.width(ArmEntryText.signalHeadText(entry)) + 4 + 19 + 19 + 2
			+ font.width(ArmEntryText.signalTailText(entry));
	}

	/**
	 * 与 {@link #renderSignalChip} 配套的宽度：**按文字实际宽度**给（+2 收边），封顶
	 * {@link #SIGNAL_CHIP_MAX}（= 153，卡片内区；不是行内胶囊那个 133 —— 标题栏右边没有 ＋）。
	 */
	private int signalChipWidth(ArmProgramEntry entry) {
		return Mth.clamp(Math.max(signalChipTextEnd(entry) + 2, 32), 32, SIGNAL_CHIP_MAX);
	}

	private Component headerChip(Card card, int index) {
		if (card.task) {
			ArmProgramTask task = task(card.taskIndex);
			// 序号就是"调用/跳转"时用的那个号（任务页 = 任务序号，操作页 = 条目序号）——
			// 用户："我们的任务没有任何序号标记，应该选一个合适的位置添加序号标记。"
			return task == null ? CommonComponents.EMPTY : tr("chip.task", index + 1, task.name);
		}
		ArmProgramEntry entry = entry(card);
		if (entry == null)
			return CommonComponents.EMPTY;
		// 标题芯片带"这个动作的必填信息"：位置类的坐标 + 左右键的朝向、等待类的时长/极性、调用的目标。
		// 这段文字与护目镜 HUD 的步骤第一行**共用** ArmEntryText.base —— 两边各写一份必然越走越远
		// （用户上一轮看到的就是"HUD 说 USE rel-2,1,0 onSuccess=SKIP_TO(9)" vs 卡片说"使用 +-2,1,0"）。
		return ArmEntryText.base(entry);
	}

	/**
	 * 胶囊上的第二个图标。
	 *
	 * <p>「等待信号」现在走专用的 {@link #renderSignalChip}（它自己画两个物品槽），所以这里恒为空 ——
	 * 留着这个方法是为了 {@code renderChip(icon, icon2, …)} 这个双图标签名（上游时刻表本来就支持两个物品）。
	 */
	private ItemStack headerIcon2(Card card) {
		return ItemStack.EMPTY;
	}

	private ItemStack headerIcon(Card card) {
		if (card.task) {
			ArmProgramTask task = task(card.taskIndex);
			return task == null ? ItemStack.EMPTY : ArmIcons.iconFor(task);
		}
		ArmProgramEntry entry = entry(card);
		return entry == null ? ItemStack.EMPTY : ArmIcons.iconFor(entry);
	}

	/**
	 * 标题芯片（那块"虚拟物品"就在芯片最左边）的悬停提示。
	 *
	 * <p>用户第二十七轮：<i>"鼠标移动到虚拟物品的位置的时候，如果有备注就显示备注内容，而不是「左键编辑」"</i>
	 * —— 备注是玩家写给这一条的话，在列表里扫一眼就能读到它，比一句"左键编辑"有用得多。
	 * 没有备注时才退回动作提示。
	 *
	 * <p>§12.3b 的两个新备注也挂在这里（P1 只从代码写，GUI 不给编辑入口）：
	 * 任务页 ⇒ <b>任务备注</b>；操作页的第一张卡（= 主任务）⇒ <b>程序备注</b>。
	 * 程序的备注只可能有一份，挂在"你打开编辑器看到的第一张卡"上是最不容易找错的位置。
	 */
	private List<Component> headerTooltip(Card card, int index) {
		List<Component> lines = new ArrayList<>(entryNoteTooltip(card.task ? null : entry(card)));
		if (card.task) {
			ArmProgramTask task = task(card.taskIndex);
			if (task != null && !task.note.isBlank())
				lines.addAll(noteBlock("entry.task_note", task.note));
		} else if (index == 0 && program != null && !program.note.isBlank()) {
			lines.addAll(noteBlock("entry.program_note", program.note));
		}
		return lines;
	}

	/**
	 * 一段备注的悬停提示：「标题（灰）」+ **逐行原文**。
	 *
	 * <p>⚠️ 逐行的理由与 {@link #entryNoteTooltip} 完全相同（{@link ArmEntryText#noteLines}）：
	 * 把带 {@code \n} 的整段塞进一个元素，在 tooltip 装得下屏幕时 NeoForge 不会断行，
	 * {@code \n} 会被画成"缺字方框"（用户实测）。
	 */
	private static List<Component> noteBlock(String titleKey, String note) {
		List<Component> lines = new ArrayList<>();
		lines.add(tr(titleKey)
			.copy()
			.withStyle(ChatFormatting.GRAY));
		for (String line : ArmEntryText.noteLines(note))
			lines.add(Component.literal(line));
		return lines;
	}

	/**
	 * 自检：§12.3b 的两个新备注（程序备注 / 任务备注）要**真的挂在悬停提示上**。
	 *
	 * <p>为什么值得一条断言：这两处是**追加式**改动（决策：不叫醒 3800 行的编辑器），
	 * 而"挂了但挂错卡片""多行又塞回一个元素"这两种错都不会报错 —— 只会表现为
	 * "导出的代码注释在界面上看不见 / 看见一个缺字方框"。
	 *
	 * <p>判据与 {@link #noteTooltipProblem} 同源：**一行一个组件**，提示里不许出现含 {@code \n} 的元素。
	 */
	private static String programAndTaskNoteProblem(Inventory inventory, RegistryAccess registries) {
		ArmProgram program = new ArmProgram();
		program.note = "程序备注一\n程序备注二";
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检";
		task.note = "任务备注一\n任务备注二";
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.TAKE;
		entry.note = "条目备注";
		task.entries.add(entry);
		program.tasks.add(task);

		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE_RELATIVE.get());
		CardProgramStorage.write(card, program, registries);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		ItemStack.STREAM_CODEC.encode(buffer, card);
		ArmProgramScreen screen = new ArmProgramScreen(new ArmProgramMenu(0, inventory, buffer), inventory, tr("title"));

		// 操作页的第一张卡 = 主任务 ⇒ 应当带上程序备注；任务页的那张卡 ⇒ 应当带上任务备注
		screen.page = Page.ENTRIES;
		List<Card> entryCards = screen.layout();
		if (entryCards.isEmpty())
			return "自检数据里没有条目卡片";
		List<Component> firstTip = screen.headerTooltip(entryCards.get(0), 0);
		screen.page = Page.TASKS;
		List<Card> taskCards = screen.layout();
		if (taskCards.isEmpty())
			return "自检数据里没有任务卡片";
		List<Component> taskTip = screen.headerTooltip(taskCards.get(0), 0);

		for (Component line : firstTip)
			if (line.getString()
				.contains("\n"))
				return "程序备注的提示里有含 \\n 的整段文字（会被画成缺字方框）";
		for (Component line : taskTip)
			if (line.getString()
				.contains("\n"))
				return "任务备注的提示里有含 \\n 的整段文字（会被画成缺字方框）";

		boolean programHead = firstTip.stream()
			.anyMatch(line -> line.getString()
				.equals(tr("entry.program_note").getString()));
		boolean programBody = firstTip.stream()
			.anyMatch(line -> line.getString()
				.equals("程序备注二"));
		boolean taskHead = taskTip.stream()
			.anyMatch(line -> line.getString()
				.equals(tr("entry.task_note").getString()));
		boolean taskBody = taskTip.stream()
			.anyMatch(line -> line.getString()
				.equals("任务备注二"));
		if (!programHead || !programBody)
			return "程序备注没有挂到操作页第一张卡的悬停提示上（标题=" + programHead + " 正文=" + programBody + "）";
		if (!taskHead || !taskBody)
			return "任务备注没有挂到任务页那张卡的悬停提示上（标题=" + taskHead + " 正文=" + taskBody + "）";
		return null;
	}

	/**
	 * 备注的悬停提示：**"备注"标题 + 逐行原文**；没有备注时给动作提示。
	 *
	 * <p>⚠️ 原文必须**一行一个 Component**（{@link ArmEntryText#noteLines}），不能把带 {@code \n} 的
	 * 整段塞进一个组件里 —— NeoForge 只在 tooltip 放不下屏幕时才断行，装得下时 {@code \n} 会被画成
	 * 一个"缺字方框"（用户实测："并没有实现多行排版"）。抽成静态方法是为了让客户端自检能直接断言它。
	 */
	private static List<Component> entryNoteTooltip(@Nullable ArmProgramEntry entry) {
		if (entry == null || entry.note == null || entry.note.isBlank())
			return List.of(tr("tip.edit"));
		List<Component> lines = new ArrayList<>();
		lines.add(tr("entry.note")
			.copy()
			.withStyle(ChatFormatting.GRAY));
		for (String line : ArmEntryText.noteLines(entry.note))
			lines.add(Component.literal(line));
		return lines;
	}

	/**
	 * 自检：备注的悬停提示必须是**逐行**的组件（用户实测："并没有实现多行排版"）。
	 *
	 * <p>判据（纯数据，点得到也看得见）：多行备注 → 提示里**没有任何一个元素含 {@code \n}**，
	 * 且行数 = 1（"备注"标题）+ 备注行数；没有备注 → 退回「左键编辑」。
	 */
	private static String noteTooltipProblem() {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.WAIT;
		entry.note = "第一行\n第二行\n第三行";
		List<Component> tip = entryNoteTooltip(entry);
		if (tip.size() != 4)
			return "多行备注的提示应当是 4 行（标题 + 3 行原文），实际 " + tip.size();
		for (Component line : tip)
			if (line.getString()
				.contains("\n"))
				return "提示里还有含 \\n 的整段文字（tooltip 装得下时不会被断行，会画成缺字方框）";
		ArmProgramEntry single = new ArmProgramEntry();
		single.op = OperationType.WAIT;
		single.note = "只有一行";
		List<Component> one = entryNoteTooltip(single);
		if (one.size() != 2)
			return "单行备注的提示应当是 2 行（标题 + 1 行原文），实际 " + one.size();
		ArmProgramEntry blank = new ArmProgramEntry();
		blank.op = OperationType.WAIT;
		if (entryNoteTooltip(blank).size() != 1 || entryNoteTooltip(null).size() != 1)
			return "没有备注时应当只有一行「左键编辑」";
		return null;
	}

	/**
	 * 备注字段的悬停提示附加的**逐行**全文（用户第二十七轮要求：把全文挂在字段提示下面）。
	 *
	 * <p>为什么需要它：① 字段宽度约 150px，长备注在框里根本看不全；② 多行备注在框里是扁平化的
	 * （换行被写成字面的 {@code \n}），换行之后的原文只有这里能原样看到。
	 * 逐行的理由同 {@link #entryNoteTooltip}。
	 */
	private static List<Component> noteTipLines(ArmProgramEntry entry) {
		List<Component> lines = new ArrayList<>();
		for (String line : ArmEntryText.noteLines(entry.note))
			lines.add(Component.literal(line));
		return lines;
	}

	// ————————————————————————— 点击 —————————————————————————

	/** 上游 {@code action}（:651-855）的移植；返回 true 表示这次点击被消费。 */
	private boolean action(@Nullable GuiGraphics graphics, double mouseX, double mouseY, int click) {
		if (editing != null)
			return false;

		int mx = (int) mouseX;
		int my = (int) mouseY;
		int x = mx - leftPos - 25;
		// ⚠️ y 的基准必须是 topPos（卡片就画在 topPos + cardY(i)），**不能再减 25**：
		// cardY() 已经含了 CARD_TOP。第一版两边都减了一次，热区整体下移 25px ——
		// 现象是"标题芯片、×、↑↓ 全点不到，只有卡片中下部有反应"。
		int y = my - topPos;
		if (x < 0 || x >= 205)
			return false;
		if (y < 0 || y > 189)
			return false;
		y += listScroll().getValue(0);

		List<Card> cards = layout();
		for (int i = 0; i < cards.size(); i++) {
			Card card = cards.get(i);
			int localY = y - cardY(i);
			if (localY < 0 || localY > CARD_HEIGHT)
				continue;

			// 标题芯片 → 编辑（操作页=必填信息：动作+坐标；任务页=任务名）
			if (x > 25 && x <= 25 + headerWidth(card, i) && localY > 4 && localY <= 20) {
				lastHit = "header";
				tooltip(graphics, mx, my, headerTooltip(card, i));
				if (click == 0) {
					status = "";
					openEditor(card, null);
				}
				return true;
			}

			// × 删除 / ≡ 复制（上游 :697-716）。≡ **留在右下角**：它的贴图自带一块与卡片主体同色的
			// 底色，放进标题栏就是一个色块（用户实测），见 CARD_BOTTOM_PAD 的说明。
			if (x > 180 && x <= 192) {
				if (localY > 0 && localY <= 14) {
					lastHit = "remove";
					tooltip(graphics, mx, my, tr(card.task ? "tip.remove_task" : "tip.remove_entry"));
					if (click == 0)
						remove(card);
					return true;
				}
				if (localY > DUPLICATE_Y) {
					lastHit = "duplicate";
					tooltip(graphics, mx, my, tr("tip.duplicate"));
					if (click == 0)
						duplicate(card);
					return true;
				}
			}

			// 上移 / 下移（上游 :718-739）
			if (x > 194) {
				if (localY > 7 && localY <= 20 && i > 0) {
					lastHit = "up";
					tooltip(graphics, mx, my, tr("tip.move_up"));
					if (click == 0)
						move(card, -1);
					return true;
				}
				if (localY > 20 && localY <= 33 && i < cards.size() - 1) {
					lastHit = "down";
					tooltip(graphics, mx, my, tr("tip.move_down"));
					if (click == 0)
						move(card, 1);
					return true;
				}
			}

			// 胶囊行横向滚动的左右箭头（上游 :741-757）—— 单位是"格"，一格一个胶囊
			int center = scrollArrowY();
			if (localY > center - 1 && localY <= center + 7 && rowScrollable(card)) {
				LerpedFloat chaser = rowScrolls.get(Math.min(i, rowScrolls.size() - 1));
				float target = chaser.getChaseTarget();
				if (x > SCROLL_LEFT_X - 3 && x <= SCROLL_LEFT_X + 4 && !Mth.equal(target, 0)) {
					lastHit = "scrollleft";
					if (click == 0)
						chaser.chase(Mth.clamp(target - 1, 0, maxRowScroll(card)), 0.5f, Chaser.EXP);
					return true;
				}
				if (x > SCROLL_RIGHT_X - 1 && x <= SCROLL_RIGHT_X + 6 && !Mth.equal(target, maxRowScroll(card))) {
					lastHit = "scrollright";
					if (click == 0)
						chaser.chase(Mth.clamp(target + 1, 0, maxRowScroll(card)), 0.5f, Chaser.EXP);
					return true;
				}
			}

			// 胶囊行（上游 :759-819）：x-=18, y-=28 后按 18 行高切。
			// 为什么是 28 而不是胶囊顶 29：上游就是 28（比贴图高一行），"贴着上边框那一条"也能点到。
			int cx = x - 18;
			int cy = localY - (CARD_ROW_Y - 1);
			// ★ 胶囊行的一切热区都**必须先落在裁剪区里**（与绘制同一块裁剪区，见 ROW_CLIP_* 的说明）。
			//   用户实测："鼠标指针在个数属性的右边，已经超出范围了，个数属性的贴图被正常遮挡了……
			//   但是按钮的点击区域是有问题的。这个问题只发生在右侧不在左侧。"
			//   —— 绘制被 `enableScissor` 裁掉了，而命中没有，于是"看得见的那一半"之外还能点到。
			boolean inChipClip = x >= ROW_CLIP_LEFT - 25 && x <= ROW_CLIP_RIGHT - 25;
			if (inChipClip && cx >= 0 && cy >= 0 && cy < CARD_ROW) {
				int rowOffset = (int) rowScrollOffset(card, i, 0);
				cx += rowOffset - 8;
				int cursor = 0;
				Prop hit = null;
				for (Prop prop : shownProps(card)) {
					int width = chipWidth(prop);
					if (cx >= cursor && cx < cursor + width) {
						hit = prop;
						break;
					}
					cursor += width + CHIP_GAP;
				}

				if (hit != null) {
					lastHit = "prop:" + hit.name.getString();
					List<Component> tip = new ArrayList<>();
					tip.add(hit.name.copy()
						.withStyle(ChatFormatting.GRAY));
					if (!hit.hint.getString()
						.isEmpty())
						tip.add(hit.hint.copy()
							.withStyle(ChatFormatting.DARK_GRAY));
					tip.add(CommonComponents.EMPTY);
					tip.add(tr("tip.edit"));
					if (hit.removable)
						tip.add(tr("tip.reset"));
					tooltip(graphics, mx, my, tip);
					if (click == 1 && hit.removable) {
						pushUndo();
						hit.reset();
						rebuildPending = true;
						return true;
					}
					if (click == 0) {
						status = "";
						openEditor(card, hit.name.getString());
					}
					return true;
				}

				// 行尾的"添加属性"＋（判定区对齐标题芯片左沿，覆盖整张贴图 —— 见 ADD_PROP_HIT_* 说明）。
				// ★ 位置与可见性都走 `plusLocalX` / `addPropVisible`（与 renderChips 同一对算式）——
				//   旧写法自己算了一遍 `26 + cursor - rowOffset`，"绘制没画、命中却算命中"就是这么来的。
				if (addPropVisible(plusLocalX(cursor, rowOffset)) && cx >= cursor + ADD_PROP_HIT_LEFT
					&& cx <= cursor + ADD_PROP_HIT_RIGHT) {
					lastHit = "addprop";
					tooltip(graphics, mx, my, tr("tip.add_prop"));
					if (click == 0) {
						status = "";
						openEditor(card, firstUnsetName(card));
					}
					return true;
				}
			}

			// 卡片背景 → 指针移过来（用户要求：点胶囊背景让指针跳过去）
			if (click == 0) {
				lastHit = "background";
				pointerIndex = i;
				if (card.task)
					selectTask(card.taskIndex);
				status = "";
				return true;
			}
			return false;
		}

		// 末尾"新卡片"（上游 :837-854）→ **追加到末尾**，不是插到指针下面
		int newY = newCardY(cards);
		if (x >= 18 && x <= 33 && y >= newY - 4 && y <= newY + 14) {
			lastHit = "newcard";
			tooltip(graphics, mx, my, tr(page == Page.ENTRIES ? "tip.add_entry" : "tip.add_task"));
			if (click == 0)
				appendAtEnd();
			return true;
		}
		return false;
	}

	private void tooltip(@Nullable GuiGraphics graphics, int mx, int my, Component text) {
		tooltip(graphics, mx, my, List.of(text));
	}

	private void tooltip(@Nullable GuiGraphics graphics, int mx, int my, List<Component> lines) {
		if (graphics != null)
			graphics.renderComponentTooltip(font, lines, mx, my);
	}

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		super.renderForeground(graphics, mouseX, mouseY, partialTicks);
		// 照上游：空跑一遍 action（click=-1）只为出悬停提示 —— 所以每个热区分支都必须有 click==0 守卫
		if (editing == null)
			action(graphics, mouseX, mouseY, -1);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 右侧滑块（用户第二十七轮续十三要求）：先于卡片热区 —— 它在 x=240..244，
		// 而 action() 只认 x<205，两者本来不重叠；放在前面只是为了让"拖滚动条"这件事读起来最清楚。
		if (editing == null && button == 0 && barHit(mouseX, mouseY)) {
			int thumbH = barThumbHeight();
			int thumbY = barThumbY(1f);
			int my = (int) mouseY - topPos;
			if (my >= thumbY && my < thumbY + thumbH)
				barGrab = my - thumbY; // 抓住滑块本身：保持鼠标与滑块的相对位置
			else {
				barGrab = thumbH / 2f; // 点空白处：滑块跳过来居中，然后继续拖
				setListScroll(barScrollFor(my - (int) barGrab));
			}
			draggingBar = true;
			return true;
		}
		// ⚠️ 顺序不能反过来：`AbstractContainerScreen.mouseClicked` 对左右键**永远返回 true**
		// （1.21 源码 :331-413，最后一行无条件 `return true`），所以"先 super 后 action"会让
		// 卡片热区永远收不到点击 —— 现象是"悬停有提示、点下去什么都没发生"（实测）。
		// 上游 ScheduleScreen 也是先 action、后 super。
		if (action(null, mouseX, mouseY, button))
			return true;
		if (minecraft == null || minecraft.player == null)
			return false; // 自检/无玩家时不碰容器屏幕（它会解引用 player）
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/**
	 * 拖滑块。判断只看 {@code draggingBar}，不看鼠标在不在轨道里 ——
	 * 拖出轨道还继续跟手才是正常的滚动条行为。
	 */
	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (draggingBar) {
			setListScroll(barScrollFor((int) mouseY - topPos - (int) barGrab));
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingBar = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (editing != null)
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

		// Shift+滚轮：胶囊行横向翻（上游 :916-955）—— 一格 = 一个胶囊，一次滚一整格
		if (hasShiftDown()) {
			List<Card> cards = layout();
			// y 的基准同样是 topPos（见 action() 里的说明）
			int y = (int) (mouseY - topPos + listScroll().getValue());
			for (int i = 0; i < cards.size(); i++) {
				Card card = cards.get(i);
				int localY = y - cardY(i);
				if (localY < 0 || localY > CARD_HEIGHT)
					continue;
				if (!rowScrollable(card) || localY < 24)
					break;
				if (mouseX < leftPos + 25 || mouseX > leftPos + 205)
					break;
				LerpedFloat chaser = rowScrolls.get(Math.min(i, rowScrolls.size() - 1));
				float target = chaser.getChaseTarget();
				int max = maxRowScroll(card);
				float step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
				if (step != 0) {
					chaser.chase(Mth.clamp(target + step, 0, max), 0.5f, Chaser.EXP);
					return true;
				}
				return false;
			}
		}

		// 纵向滚轮：只动**当前列表**的位置（每个列表一份，见 listScrolls 的说明）
		float target = listScroll().getChaseTarget();
		float max = maxListScroll();
		if (max > 0) {
			target -= scrollY * 12;
			target = Mth.clamp(target, 0, max);
			listScroll().chase((int) target, 0.7f, Chaser.EXP);
		} else
			listScroll().chase(0, 0.7f, Chaser.EXP);
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (editing != null && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			closeEditor(false);
			return true;
		}
		if (editing == null && hasControlDown()) {
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
				undo();
				return true;
			}
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
				redo();
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// ————————————————————————— 属性（= 时刻表的"条件"，但只有一行） —————————————————————————

	/** 可调用的任务名（含自身 —— 允许递归，运行时用深度上限兜住）。 */
	private List<String> callTargetNames() {
		List<String> names = new ArrayList<>();
		for (ArmProgramTask t : program.tasks)
			if (!t.name.isBlank())
				names.add(t.name);
		return names;
	}

	private List<Component> callTargetOptions() {
		List<String> names = callTargetNames();
		List<Component> options = new ArrayList<>();
		if (names.isEmpty())
			options.add(tr("call.none"));
		else
			for (String name : names)
				options.add(Component.literal(name));
		return options;
	}

	private int callTargetIndex(String target) {
		int index = callTargetNames().indexOf(target == null ? "" : target);
		return Math.max(0, index);
	}

	/**
	 * 一条可编辑属性。{@link #show()} 为假 = 还是默认值 = <b>不占胶囊位</b>；
	 * 要改就从行尾的"添加属性"＋ 加进来（已加过的自动排除，加不了两次），右键恢复默认。
	 */
	private abstract class Prop {
		final Component name;
		final Component hint;
		/** 胶囊上的文字（含当前值）。 */
		final Component chip;
		final boolean removable;

		Prop(Component name, Component hint, Component chip, boolean removable) {
			this.name = name;
			this.hint = hint;
			this.chip = chip;
			this.removable = removable;
		}

		boolean show() {
			return isSet();
		}

		/** 胶囊上的小图标；默认空（纯文字）。 */
		ItemStack chipIcon() {
			return ItemStack.EMPTY;
		}

		abstract boolean isSet();

		abstract void reset();

		abstract void reveal();

		/** 建编辑器控件（放在上游选择框那一格）。 */
		abstract void buildEditor();
	}

	private List<Prop> shownProps(Card card) {
		List<Prop> shown = new ArrayList<>();
		for (Prop prop : props(card))
			if (prop.show())
				shown.add(prop);
		return shown;
	}

	private List<Prop> props(Card card) {
		if (card.task) {
			ArmProgramTask task = task(card.taskIndex);
			return task == null ? List.of() : taskProps(task);
		}
		ArmProgramEntry entry = entry(card);
		return entry == null ? List.of() : entryProps(entry);
	}

	private List<Prop> entryProps(ArmProgramEntry entry) {
		List<Prop> props = new ArrayList<>();

		// 用户定的属性表（第三轮 + 第四轮定稿）：
		//  · **所有动作**（含等待）都能"完成后跳转" —— 成功出口是通用的；
		//  · 「失败后」一个属性**合并了原来的「时限」+「超时后」**（用户第四轮要求）：
		//    会重试的动作第二行是四个控件（去向 / 落点 / 时限 0–120 / 单位 t·s·m）；
		//    只有硬失败的动作（左键）第二行只有两个（去向 / 落点）—— 硬失败是立刻发生的，没有重试窗口；
		//  · 前摇（delay）删掉了：已经有独立的"等待"操作。
		// 「退出 / 抛出错误」是终结操作：执行之后就不在这个任务里了，没有"成功后/失败后"可谈。
		if (entry.op != OperationType.BREAK && entry.op != OperationType.THROW) {
			props.add(successProp(entry));
			if (hasFailure(entry) || hasHardFailure(entry))
				props.add(failureProp(entry, hasFailure(entry)));
		}

		// 动作自己的参数
		// 朝向**不是**胶囊属性：它和坐标一样是这个动作的必填项，放在编辑器第二行（见 buildBaseControls）；
		// 前摇也没有了（已有独立的"等待"操作）
		switch (entry.op) {
			case TAKE -> props.add(intProp(tr("entry.count"), tr("hint.entry_count"), ArmEntryText.countText(entry), 1,
				1, 64, () -> entry.count, v -> entry.count = v));
			case UNLOAD -> {
				props.add(enumProp(tr("entry.unload_repeat"), tr("hint.entry_unload_repeat"),
					ArmEntryText.unloadRepeatText(entry), UnloadRepeat.values(), UnloadRepeat.ONCE,
					() -> entry.unloadRepeat, v -> entry.unloadRepeat = v));
				props.add(enumProp(tr("entry.unload_amount"), tr("hint.entry_unload_amount"),
					ArmEntryText.unloadAmountText(entry), UnloadAmount.values(), UnloadAmount.ONE_STACK,
					() -> entry.unloadAmount, v -> entry.unloadAmount = v));
			}
			case CALL -> props.add(intProp(tr("entry.call_repeat"), tr("hint.entry_call_repeat"),
				ArmEntryText.callRepeatText(entry), 1, 1, ArmProgramTask.MAX_LOOP_COUNT, () -> entry.callRepeat,
				v -> entry.callRepeat = v));
			default -> {
			}
		}
		// §7.6 v4：单线程之后没有"让位"，等待方式（阻塞/空闲）没有区别 → 这个属性也删掉了
		props.add(new Prop(tr("entry.note"), tr("hint.entry_note"), ArmEntryText.noteText(entry), true) {			@Override
			boolean isSet() {
				return !entry.note.isBlank();
			}

			@Override
			void reset() {
				entry.note = "";
			}

			@Override
			void reveal() {
				entry.note = "-";
			}

			@Override
			void buildEditor() {
				// 上限与「剪贴板 → 备注」共用 ArmProgramEntry.NOTE_MAX：两处不一致会让界面显示一截、实际存另一截。
				// 方案 B（用户选定）：多行备注在**单行框里显示成字面的 `\n` 两个字符**，保存时再还原成真换行；
				// 全文（含被隐藏的那几行）挂在 tooltip 上，见 noteHint。
				// 显示值走 flattenNoteForField 而不是 flattenNote：换行是 1 个字符、显示成 2 个，
				// 128 个换行存得下但显示不下，直接交给框会被从 `\n` 中间截断（见该方法的说明）。
				textField(FIELD_X, ROW2_Y, FIELD_W, tr("entry.note"), tr("hint.entry_note"),
					noteTipLines(entry), ArmProgramEntry.flattenNoteForField(entry.note), ArmProgramEntry.NOTE_MAX, v -> {
						entry.note = ArmProgramEntry.joinNote(v);
						dirty = true;
					});
			}
		});
		return props;
	}

	private List<Prop> taskProps(ArmProgramTask task) {
		List<Prop> props = new ArrayList<>();
		// ★ 循环设置只属于**主任务**（列表里的第一个）：子任务的轮次归"调用点"决定
		//（用户第二十七轮："把循环次数放在调用上"）。给子任务也显示循环，就会出现
		// "调用 ×3 × 子任务自己循环 ×2"这种要相乘、谁都算不清的组合 —— 所以这里干脆不给它。
		boolean isMainTask = program.tasks.isEmpty() || program.tasks.get(0) == task;
		if (!isMainTask)
			return props;
		// §7.6 v4：任务只剩"循环"这一项设置 —— 频率搬去了「等待信号」操作，
		// 优先级/取反随调度器一起删掉了（单线程没有"谁先跑"的问题）。
		props.add(enumProp(tr("task.loop"), tr("hint.task_loop"),
			tr("chip.loop", tr("opt." + task.loop.name()
				.toLowerCase(java.util.Locale.ROOT))), LoopType.values(), LoopType.NONE, () -> task.loop,
			v -> task.loop = v));
		if (task.loop == LoopType.COUNT)
			props.add(intProp(tr("task.loop_count"), tr("hint.task_loop_count"), tr("chip.loop_count", task.loopCount),
				1, 1, ArmProgramTask.MAX_LOOP_COUNT, () -> task.loopCount, v -> task.loopCount = v));
		return props;
	}

	/**
	 * 有"失败判定"、且会**无限等**的操作（§7.7）：只有它们才有「时限」+「超时后」。
	 *
	 * <p>⚠️ 按**条目**判而不是按类型（§7.16b 一.6）：同一条 {@code SIGNAL}，收有失败出口、发没有。
	 */
	private static boolean hasFailure(ArmProgramEntry entry) {
		return entry.hasRetryWindow();
	}

	/**
	 * 只有**硬失败**（做不成，等也没用）的操作 —— 挖掘 / 调用：挖不动 / 无权 / 被领地保护。
	 *
	 * <p>它们能设「失败后跳转」，但**没有时限**（用户定稿）：硬失败是立刻发生的，
	 * 而右键根本不幂等（重试会把拉杆反复拨回来），所以这两类都不给"重试窗口"。
	 */
	private static boolean hasHardFailure(ArmProgramEntry entry) {
		// 挖掘（挖不动）与调用（子任务失败）都是立刻知道结果，没有重试窗口
		return entry.hasHardFailureExit();
	}

	/** 需要"交互面"的操作（左键/右键语义）。 */
	private static boolean needsFace(OperationType op) {
		return op == OperationType.MINE || op == OperationType.ATTACK || op == OperationType.USE
			|| op == OperationType.USE_SNEAK;
	}

	/**
	 * "成功后" —— 胶囊是<b>一整句话</b>：{@code 成功继续} / {@code 成功跳转 3}（用户定的写法）。
	 *
	 * <p>落点不再是独立胶囊、也不再挂在别的属性下面：它就是这个属性自己的值。
	 * 所以 {@link Prop#reveal()} 可以连落点一起给（一次点击仍然只多出一个胶囊）。
	 */
	private Prop successProp(ArmProgramEntry entry) {
		return new Prop(tr("entry.on_success"), tr("hint.entry_on_success"), ArmEntryText.successText(entry), true) {
			@Override
			boolean isSet() {
				return entry.onSuccess != SkipAction.NONE;
			}

			@Override
			void reset() {
				entry.onSuccess = SkipAction.NONE;
				entry.successTarget = "";
			}

			@Override
			void reveal() {
				entry.onSuccess = SkipAction.SKIP_TO;
				if (entry.successTarget.isBlank())
					entry.successTarget = "1";
			}

			@Override
			void buildEditor() {
				// 第二行两格：① 去向（继续/跳转）② 落点输入框（**总是显示**，用户要求"再跟一个输入框"）
				inlinePicker(FIELD_X, 54, tr("entry.on_success"), tr("hint.entry_on_success"),
					List.of(tr("skip.stay"), tr("skip.jump")), entry.onSuccess == SkipAction.SKIP_TO ? 1 : 0, i -> {
						entry.onSuccess = i == 1 ? SkipAction.SKIP_TO : SkipAction.NONE;
						if (entry.onSuccess == SkipAction.SKIP_TO && entry.successTarget.isBlank())
							entry.successTarget = "1";
						rebuildPending = true;
					});
				textField(FIELD_X + 56, ROW2_Y, FIELD_W - 56, tr("entry.jump_target"),
					tr("hint.entry_jump_target"), entry.successTarget, 32, v -> {
						entry.successTarget = v;
						dirty = true;
					});
			}
		};
	}

	/**
	 * 「失败后」—— <b>一个属性管住整条失败路径</b>（用户第四轮要求：把「时限」和「失败后」合起来）。
	 *
	 * <p>第二行（{@code retryWindow=true}，即取/放/卸/组装/等红石）：四个控件 ——
	 * ① 去向（停下 / 跳过 / 跳转）② 落点输入框 ③ 时限 0–120 ④ 单位 t / s / m。
	 * 语义：失败时<b>重试</b>，攒够 时限×单位 之后执行①；时限 = 0 就是"立刻"执行①；
	 * ①②③④ 全不设（这个属性没加上）= 一直重试（{@code failAmount = -1}）。
	 *
	 * <p>第二行（{@code retryWindow=false}，即左键的硬失败）：只有 ①去向 + ②落点 ——
	 * 硬失败是立刻发生的，给它"重试多久"没有意义。
	 */
	private Prop failureProp(ArmProgramEntry entry, boolean retryWindow) {
		return new Prop(tr("entry.on_failure"), tr(retryWindow ? "hint.entry_on_timeout" : "hint.entry_on_failure"),
			ArmEntryText.failureText(entry), true) {
			@Override
			boolean isSet() {
				// 全部都是默认值（一直重试、停下、没有落点）时就不占胶囊位
				return entry.onTimeout != SkipAction.NONE || entry.failAmount >= 0;
			}

			@Override
			void reset() {
				entry.onTimeout = SkipAction.NONE;
				entry.timeoutTarget = "";
				entry.failAmount = -1;
			}

			@Override
			void reveal() {
				// 点一次只多一个胶囊：默认给"重试 5 秒后停下"（左键则给"立刻停下"占位后的跳转）
				if (retryWindow) {
					entry.failAmount = 5;
					entry.failUnit = WaitUnit.SECOND;
				} else {
					entry.onTimeout = SkipAction.CONTINUE;
				}
			}

			@Override
			void buildEditor() {
				// ① 去向
				int w1 = retryWindow ? 28 : 34;
				inlinePicker(FIELD_X, w1, tr("entry.on_failure"), tr("hint.entry_fail_action"),
					List.of(tr("skip.stop"), tr("skip.continue"), tr("skip.jump")), entry.onTimeout.ordinal(), i -> {
						pushUndo();
						entry.onTimeout = SkipAction.values()[i];
						if (entry.onTimeout == SkipAction.SKIP_TO && entry.timeoutTarget.isBlank())
							entry.timeoutTarget = "1";
						// 选了去向就必须有个时限：否则 failAmount = -1 = "一直重试"，去向永远到不了，
						// 界面上却显示成"立刻停下"（用户实测反馈："此处实际上不是停下，而是重试"）。
						// 默认给 0 = 立刻；玩家想要更长的重试窗口就在下面那个框里改。
						if (retryWindow && entry.failAmount < 0)
							entry.failAmount = 0;
						rebuildPending = true;
					});
				// ② 落点（总是显示：跳转要用它；不跳转时它只是留着）
				int x2 = FIELD_X + w1 + 2;
				textField(x2, ROW2_Y, retryWindow ? 38 : FIELD_W - w1 - 2, tr("entry.jump_target"),
					tr("hint.entry_jump_target"), entry.timeoutTarget, 32, v -> {
						entry.timeoutTarget = v;
						dirty = true;
					});
				if (!retryWindow)
					return;
				// ③ 时限 0–120（含 120）+ ④ 单位 t/s/m
				inlineInt(x2 + 40, 30, tr("entry.timeout"), tr("hint.entry_timeout"), 0, 120,
					Math.max(0, entry.failAmount), v -> {
						pushUndo();
						entry.failAmount = v;
					});
				inlinePicker(x2 + 72, 18, tr("entry.wait_unit"), tr("hint.entry_fail_unit"),
					labels(WaitUnit.values()), entry.failUnit.ordinal(), i -> {
						entry.failUnit = WaitUnit.values()[i];
						dirty = true;
					});
			}
		};
	}

	/** 单位的短后缀：t / s / m（唯一实现在 {@link ArmEntryText#unitSuffix}）。 */
	private static String unitSuffix(WaitUnit unit) {
		return ArmEntryText.unitSuffix(unit);
	}

	private Prop intProp(Component name, Component hint, Component chip, int def, int min, int max, IntSupplier get,
		IntConsumer set) {
		return new Prop(name, hint, chip, true) {
			@Override
			boolean isSet() {
				return get.getAsInt() != def;
			}

			@Override
			void reset() {
				set.accept(def);
			}

			@Override
			void reveal() {
				set.accept(def == -1 ? 100 : def + 1);
			}

			@Override
			void buildEditor() {
				intPicker(name, hint, min, max, get.getAsInt(), v -> {
					pushUndo();
					set.accept(v);
				});
			}
		};
	}

	private <E extends Enum<E>> Prop enumProp(Component name, Component hint, Component chip, E[] values, E def,
		Supplier<E> get, Consumer<E> set) {
		return new Prop(name, hint, chip, true) {
			@Override
			boolean isSet() {
				return get.get() != def;
			}

			@Override
			void reset() {
				set.accept(def);
			}

			@Override
			void reveal() {
				for (E value : values)
					if (value != def) {
						set.accept(value);
						return;
					}
			}

			@Override
			void buildEditor() {
				// 第一行是属性选择框（统一在 buildEditor 里建），这里只建第二行的值
				inlinePicker(FIELD_X, FIELD_W, name, hint, labels(values), get.get()
					.ordinal(), i -> {
					pushUndo();
					set.accept(values[i]);
					rebuildPending = true;
				});
			}
		};
	}

	private Prop boolProp(Component name, Component hint, Component chip, boolean def, Supplier<Boolean> get,
		Consumer<Boolean> set) {
		return new Prop(name, hint, chip, true) {
			@Override
			boolean isSet() {
				return get.get() != def;
			}

			@Override
			void reset() {
				set.accept(def);
			}

			@Override
			void reveal() {
				set.accept(!def);
			}

			@Override
			void buildEditor() {
				inlinePicker(FIELD_X, FIELD_W, name, hint, List.of(tr("on"), tr("off")), get.get() ? 0 : 1, i -> {
					pushUndo();
					set.accept(i == 0);
					rebuildPending = true;
				});
			}
		};
	}

	/**
	 * 一行的横向滚动 —— <b>照上游的做法：滚动量以"一格"为单位，不是像素</b>
	 * （上游 {@code getConditionScroll:589-600}：一列贡献 {@code (列宽+10) * min(1, 剩余滚动量)}）。
	 *
	 * <p>之前我们把滚动量当像素用，于是"一格 1px"—— 实测"滚半天几乎不动"；而且像素级偏移会把
	 * 胶囊从中间切开。按格滚：一格 = 一个胶囊，滚到位就是整整一格，不会切半个。
	 */
	private float rowScrollOffset(Card card, int index, float partial) {
		float scrollIndex = rowScrolls.isEmpty() ? 0
			: rowScrolls.get(Math.min(index, rowScrolls.size() - 1))
				.getValue(partial);
		float offset = 0;
		for (Prop prop : shownProps(card)) {
			float part = Mth.clamp(scrollIndex, 0, 1);
			offset += (chipWidth(prop) + CHIP_GAP) * part;
			scrollIndex -= part;
		}
		return offset;
	}

	/** 横向滚动最大格数（上游：右箭头在 {@code chaseTarget == 列数-1} 时消失）。 */
	private int maxRowScroll(Card card) {
		return Math.max(0, shownProps(card).size() - 1);
	}

	/** 一行胶囊是否超宽（上游 {@code isConditionAreaScrollable:582-587}）。 */
	private boolean rowScrollable(Card card) {
		int width = 26;
		for (Prop prop : shownProps(card))
			width += chipWidth(prop) + CHIP_GAP;
		return width + 16 > CARD_WIDTH - 26;
	}

	/** 整行的像素宽度（自检用）。 */
	private int rowWidth(Card card) {
		int width = 0;
		for (Prop prop : shownProps(card))
			width += chipWidth(prop) + CHIP_GAP;
		return width;
	}

	// ————————————————————————— 编辑器（上游 startEditing/stopEditing） —————————————————————————

	/**
	 * 编辑目标。<b>没有"加属性模式"了</b>（第四轮合并）：{@code propName} 为 null = 编辑这张卡的
	 * 动作/任务头；否则 = 编辑那个属性 —— 第一行是属性选择框，所以"加"就是"选到还没加的那个"。
	 *
	 * <p>为什么存**名字**而不是 {@code Prop} 对象：{@code entryProps()} 每帧都会重建一批 Prop，
	 * 对象身份活不过一次 rebuild，而名字是稳定的（同一张卡上属性名唯一）。
	 */
	private record EditTarget(boolean task, int taskIndex, int entryIndex, @Nullable String propName) {
	}

	/** 打开编辑器：{@code propName == null} = 动作/任务头；否则选中该属性。 */
	private void openEditor(Card card, @Nullable String propName) {
		editing = new EditTarget(card.task, card.taskIndex, card.entryIndex, propName);
		editingProp = null;
		editSnapshot = program.save(registries());
		// 幽灵槽装上当前值（照时刻表 startEditing 把 field 的物品推给服务端）：
		// 图标 → 槽 0，「等待信号」的一对频率 → 槽 1/2，用不上的槽**清空**
		//（用户实测："编辑属性的时候……幽灵槽位上放了一个木炭" —— 那是上一次留下的东西挂着没清）。
		sendGhostSlots();
		rebuildPending = true;
	}

	/**
	 * 把三个幽灵槽同步成"当前编辑对象该有的样子"：图标（槽 0）、一对频率（槽 1/2），其余清空。
	 *
	 * <p>必须**每次打开编辑器都推一遍**：幽灵槽的内容活在菜单里（{@code ghostInventory}），
	 * 上一次编辑留下的物品不会自己消失 —— 推空就是清理。
	 */
	private void sendGhostSlots() {
		if (minecraft == null || minecraft.getConnection() == null)
			return; // 自检在标题界面跑，没有连接，不能裸发（会 NPE）
		ArmProgramEntry entry = editing == null || editing.task ? null
			: entry(new Card(false, editing.taskIndex, editing.entryIndex));
		boolean signal = !editingProperty() && entry != null && entry.op.hasSignalMode();
		ItemStack icon = editingProperty() ? ItemStack.EMPTY : currentIcon();
		ItemStack first = signal && entry.frequency != null ? entry.frequency.getFirst()
			.getStack() : ItemStack.EMPTY;
		ItemStack second = signal && entry.frequency != null ? entry.frequency.getSecond()
			.getStack() : ItemStack.EMPTY;
		pushGhostSlot(0, icon);
		pushGhostSlot(1, first);
		pushGhostSlot(2, second);
	}

	private void pushGhostSlot(int index, ItemStack stack) {
		if (index >= menu.ghostInventory.getSlots())
			return;
		CatnipServices.NETWORK.sendToServer(new GhostItemSubmitPacket(stack, index));
	}

	/**
	 * 用两个幽灵槽里的物品拼出一对频率；**允许有一个是空的**；两个都空 = 没绑定（返回 {@code null}）。
	 *
	 * <p>用户第二十二轮："频率本身是可以填空的，不是必须两个物品。" Create 的红石链接频率就是
	 * 一对物品、每一格都可以留空（空物品本身也是频率的一部分），所以只填一格也要能绑上；
	 * 两格都空则视为"还没绑"（这样界面上"没绑频率"这件事仍然看得出来，运行时也好报"一直等"）。
	 *
	 * <p>这也是**之前完全缺失**的一步：幽灵槽只被画出来，从来没人把槽里的物品写进 {@code entry.frequency}，
	 * 于是界面上建出来的「等待信号」永远 {@code frequency == null} ——
	 * {@code ProgramRuntime.isSignalPowered} 对 null 直接返回 false，那条操作只会一直等（或按「时限」失败）。
	 */
	@Nullable
	private static Couple<Frequency> frequencyFrom(ItemStack first, ItemStack second) {
		if (first.isEmpty() && second.isEmpty())
			return null;
		Couple<Frequency> frequency = Couple.create(() -> Frequency.EMPTY);
		frequency.set(true, Frequency.of(first.copy()));
		frequency.set(false, Frequency.of(second.copy()));
		return frequency;
	}

	/** 末尾"＋"打开的那个属性：优先选**还没加**的第一个，全加满了就选第一个。 */
	@Nullable
	private String firstUnsetName(Card card) {
		List<Prop> all = card.task ? taskProps(task(card.taskIndex)) : entryProps(entry(card));
		for (Prop prop : all)
			if (!prop.show())
				return prop.name.getString();
		return all.isEmpty() ? null : all.get(0).name.getString();
	}

	/** 当前编辑对象现有的图标（空 = 用默认）。 */
	private ItemStack currentIcon() {
		if (editing == null)
			return ItemStack.EMPTY;
		if (editing.task) {
			ArmProgramTask task = task(editing.taskIndex);
			return task == null ? ItemStack.EMPTY : task.icon;
		}
		ArmProgramEntry entry = entry(new Card(false, editing.taskIndex, editing.entryIndex));
		return entry == null ? ItemStack.EMPTY : entry.icon;
	}

	/**
	 * 编辑器左下角那个垃圾桶：**删除正在编辑的东西**（用户要求："点击左侧垃圾桶应该删除，
	 * 而不是留下这条操作"）。语义：
	 * <ul>
	 *   <li>正在编辑某个**属性** → 把它恢复默认（= 从卡片上消失）</li>
	 *   <li>正在编辑**条目 / 任务**（没选中属性）→ 整条删掉</li>
	 * </ul>
	 * 想"改了不生效"用 ESC（{@link #keyPressed} 里走 {@link #closeEditor(boolean) closeEditor(false)}）。
	 */
	private void deleteEditing() {
		if (editing == null)
			return;
		Card card = new Card(editing.task, editing.taskIndex, editing.entryIndex);
		Prop prop = editingProp;
		// 快照要在**改动之前**取；closeEditor(true) 只提交图标、不再动结构
		pushUndo();
		closeEditor(true);
		if (prop != null)
			prop.reset(); // 属性恢复默认 = 该属性不再显示
		else
			removeWithoutUndo(card);
		rebuildPending = true;
	}

	private void closeEditor(boolean keep) {
		if (editing == null)
			return;
		if (keep) {
			// 图标只属于"条目/任务本体"：编辑属性时槽是空的（也不该生效），照写就会把图标抹掉
			if (!editingProperty()) {
				ItemStack picked = menu.ghostInventory.getStackInSlot(0)
					.copy();
				if (editing.task) {
					ArmProgramTask task = task(editing.taskIndex);
					if (task != null)
						task.icon = picked;
				} else {
					ArmProgramEntry entry = entry(new Card(false, editing.taskIndex, editing.entryIndex));
					if (entry != null)
						entry.icon = picked;
				}
				dirty = true;
			}
			// 「等待信号」的频率：把槽 1/2 里的一对物品真正写进条目
			// （这是本轮补上的缺失环节 —— 以前槽只是画出来，frequency 永远是 null）
			// ⚠️ 必须和上面图标那段一样守 `!editingProperty()`：编辑属性时三个槽是**主动推空**的
			//（`targetSlotsActive = 0`，槽 1/2 既不激活也不显示），照写就会把已经绑好的频率
			// 用 `frequencyFrom(空, 空) = null` 抹掉并落盘 —— 那条之后永远等不到信号。
			if (!editing.task && !editingProperty()) {
				ArmProgramEntry signal = entry(new Card(false, editing.taskIndex, editing.entryIndex));
				if (signal != null && signal.op.hasSignalMode()) {
					signal.frequency = frequencyFrom(menu.ghostInventory.getStackInSlot(1),
						menu.ghostInventory.getStackInSlot(2));
					dirty = true;
				}
			}
		} else if (editSnapshot != null) {
			program = ArmProgram.load(editSnapshot, registries());
		}
		if (minecraft != null && minecraft.getConnection() != null) {
			// 三个槽一起清（否则下次打开会看到上次留下的物品）
			for (int i = 0; i < menu.ghostInventory.getSlots(); i++)
				CatnipServices.NETWORK.sendToServer(new GhostItemSubmitPacket(ItemStack.EMPTY, i));
		}
		editing = null;
		editingProp = null;
		editSnapshot = null;
		menu.slotsActive = false;
		menu.targetSlotsActive = 0;
		rebuildPending = true;
	}

	/** 上游 {@code renderBg} 的编辑器那一段（:1029-1071）。
	 *
	 * <p><b>不要像上游那样把面板推到 z=200</b>：上游的编辑器控件是一个 {@code ScreenOverlay(200)}，
	 * 和面板同层；我们的控件是普通控件（z=0，{@code AbstractContainerScreen} 在 renderables 循环里画），
	 * 面板一旦抬高就会把它自己的控件盖住 —— 实测现象是"选择框里没有文字、完成/取消键看不见"。
	 * 我们编辑时不画卡片列表（不需要盖住谁），所以面板留 z=0，控件自然在它上面。
	 */
	private void renderEditor(GuiGraphics graphics) {
		graphics.fillGradient(0, 0, width, height, -1072689136, -804253680);
		AllGuiTextures.SCHEDULE_EDITOR.render(graphics, leftPos - 2, topPos + 40);
		AllGuiTextures.PLAYER_INVENTORY.render(graphics, leftPos + 38, topPos + 122);
		graphics.drawString(font, playerInventoryTitle, leftPos + 46, topPos + 128, 0x505050, false);
		boolean header = editing == null || editing.propName() == null;
		// 幽灵槽数量在 init() 里就算好了（updateTargetSlots）—— 那是"布局"信息，不该靠渲染来设：
		// 原来是写在 renderEditor 里的，于是**没渲染过的那一帧之前**槽位状态是过期的
		// （自检直接在 init 之后断言就抓到了：应为 2 实际 1）。
		// 面板顶上的那行字：编辑属性时就是属性名（上游也是"指令编辑器 / 条件编辑器"这样标），
		// 所以第一行的框里放的都是"值"，不会让人猜自己在改什么。
		Component hint = editingProp != null ? editingProp.name : header ? tr("editor.hint_op") : tr("editor.hint");
		int center = leftPos + (AllGuiTextures.SCHEDULE.getWidth() - 8) / 2;
		graphics.drawString(font, hint, center - font.width(hint) / 2, topPos + 44, 0x505050, false);

		// 幽灵槽：**只画当前用得到的那些**（槽 0 = 图标；槽 1/2 = 「等待信号」的一对频率）。
		// 用不上时既不画框、也不激活（槽里的物品由 openEditor 推空清掉，见 sendGhostSlots）。
		for (int i = 0; i < menu.targetSlotsActive && i < menu.ghostInventory.getSlots(); i++) {
			AllGuiTextures.SCHEDULE_EDITOR_ADDITIONAL_SLOT.render(graphics, leftPos + 53 + 20 * i, topPos + 87);
			ItemStack stored = menu.ghostInventory.getStackInSlot(i);
			// 槽 0 空着时显示"这个动作的默认图标"当预览；频率槽空 = 还没绑，不预览（免得看着像已绑定）
			ItemStack preview = i == 0 && stored.isEmpty() ? defaultIconForPreview() : stored;
			if (!preview.isEmpty())
				GuiGameElement.of(preview)
					.at(leftPos + 54 + 20 * i, topPos + 88)
					.render(graphics);
		}

		// 第二行那些"输入框底"（DATA_AREA）—— 控件自己在 renderEditor 之后画
		for (int[] box : editorBoxes)
			renderDataArea(graphics, box[0], box[1], box[2]);
	}

	private ItemStack defaultIconForPreview() {
		if (editing == null)
			return ItemStack.EMPTY;
		if (editing.task)
			return ArmIcons.taskIcon();
		ArmProgramEntry entry = entry(new Card(false, editing.taskIndex, editing.entryIndex));
		return entry == null ? ItemStack.EMPTY : ArmIcons.defaultIcon(entry.op);
	}

	/** 建编辑器控件（上游 {@code startEditing}）：确认/取消 + 类型选择框 或 该属性的控件。 */
	/**
	 * 建编辑器控件 —— <b>"选属性"和"改属性"是同一个界面</b>（用户第四轮要求：
	 * "把它们合并为一个界面，而不是选择属性一个界面、修改属性一个界面"）。
	 *
	 * <p>结构照动作编辑器（用户点名"就像第三幅图"）：
	 * <ul>
	 *   <li>第一行 = **属性选择框**，列出这张卡上的**全部**属性（含"还没加的"）——
	 *       所以"加一个属性"就是"选到它、把值改掉"，不再有第二个界面；</li>
	 *   <li>第二行 = 选中属性的**值控件**（枚举/数值/文本，DATA_AREA 盒子）。</li>
	 * </ul>
	 * 因为值控件是**直接写进条目**的，"完成"只负责关闭；想放弃整次编辑按 ESC（垃圾桶是删除）。
	 */
	private void buildEditor() {
		ArmProgramEntry entry = editing != null && !editing.task
			? entry(new Card(false, editing.taskIndex, editing.entryIndex))
			: null;
		ArmProgramTask task = editing == null ? null : task(editing.taskIndex);
		editorBoxes.clear();

		IconButton confirmBtn = new IconButton(leftPos + 56 + 168, topPos + 65 + 22, AllIcons.I_CONFIRM);
		confirmBtn.withCallback(() -> closeEditor(true));
		confirmBtn.setToolTip(tr("btn.done"));
		addEditorWidget(confirmBtn);

		IconButton cancelBtn = new IconButton(leftPos + 56 - 45, topPos + 65 + 22, AllIcons.I_TRASH);
		cancelBtn.withCallback(this::deleteEditing);
		// 垃圾桶 = **删除**（照上游 `ScheduleScreen:879-882`：`onEditorClose.accept(false)` 会让回调
		// 把正在编辑的条件/指令真的移除）。想"改了不生效"按 ESC（见 keyPressed）。
		cancelBtn.setToolTip(tr(editing != null && editing.propName() != null ? "btn.delete" : "btn.delete_card"));
		addEditorWidget(cancelBtn);

		// ——— 第一行：动作/任务的头（没有选中属性时）———
		if (editing.propName() == null) {
			if (entry != null) {
				picker(tr("entry.op"), tr("hint.entry_op"), labels(OperationType.values()), entry.op.ordinal(), i -> {
					entry.op = OperationType.values()[i];
					rebuildPending = true;
					dirty = true;
				});
				buildBaseControls(entry);
			} else if (task != null) {
				label(leftPos + 59, topPos + 69, tr("task.name"), 140);
				textField(FIELD_X, ROW2_Y, FIELD_W, tr("task.name"), tr("hint.task_name"), task.name, 24, v -> {
					task.name = v.isBlank() ? "task" : v;
					dirty = true;
				});
			}
			return;
		}

		// ——— 第一行：属性选择框（全部属性，含还没加的）；第二行：选中属性的值 ———
		List<Prop> all = entry != null ? entryProps(entry) : taskProps(task);
		int index = 0;
		for (int i = 0; i < all.size(); i++)
			if (all.get(i).name.getString()
				.equals(editing.propName())) {
				index = i;
				break;
			}
		editingProp = all.isEmpty() ? null : all.get(index);
		final int startIndex = index;
		picker(tr("edit.prop"), tr("hint.edit_prop"),
			all.stream()
				.map(p -> p.name)
				.toList(),
			startIndex, i -> {
				int picked = Mth.clamp(i, 0, all.size() - 1);
				// 只换"正在编辑哪个属性"：值控件直接写条目，所以这里不需要确认动作。
				editing = new EditTarget(editing.task, editing.taskIndex, editing.entryIndex,
					all.get(picked).name.getString());
				editingProp = all.get(picked);
				rebuildPending = true;
			});
		if (editingProp == null)
			return;
		// ⚠️ 第二行是**每个属性自己**建的（buildEditor 只建第二行；第一行统一是上面的选择框）
		editingProp.buildEditor();
	}

	/**
	 * 动作的**基础内容**（必填，第二行）：随动作类型整体切换 —— 用户反馈"选了等待还让我填坐标"。
	 *
	 * <p>上游也是这个结构：{@code IScheduleInput.initConfigurationWidgets} 每种指令自己决定第二行放什么
	 * （`ScheduleScreen:294-302`）。所以这里的排布照它来：图标槽右边 91..217 这一段，
	 * 一个控件一个 {@code DATA_AREA} 盒子（{@code ModularGuiLine.renderWidgetBG:33-72}）。
	 */
	private void buildBaseControls(ArmProgramEntry entry) {
		switch (entry.op) {
			case WAIT -> {
				// §7.6 v4：单线程之后没有"让位"，等待方式（阻塞/空闲）已经没区别 → 不再提供这项
				inlineInt(FIELD_X, 54, tr("entry.wait_amount"), tr("hint.entry_wait_amount"), 0, 120,
					entry.waitAmount, v -> {
						pushUndo();
						entry.waitAmount = v;
					});
				inlinePicker(FIELD_X + 56, 64, tr("entry.wait_unit"), tr("hint.entry_wait_unit"),
					labels(WaitUnit.values()), entry.waitUnit.ordinal(), i -> {
						entry.waitUnit = WaitUnit.values()[i];
						dirty = true;
					});
			}
			// 等红石（相邻信号）：第二行 = 极性；图标用第一个幽灵槽记
			case WAIT_REDSTONE -> inlinePicker(FIELD_X, FIELD_W, tr("entry.wait_power"),
				tr("hint.entry_wait_power"), List.of(tr("on"), tr("off")), entry.waitForPowered ? 0 : 1, i -> {
					entry.waitForPowered = i == 0;
					dirty = true;
				});
			// 收发信号（红石链接频率）：**两个**幽灵槽记一对物品（槽位/槽里的物品由 renderEditor 画，
			// 它们是真菜单槽，拖物品进来即绑定），所以第二行必须让开**两个**槽 ——
			// 用户实测："滚动选择框的左边把幽灵物品槽位占用了"（第二个槽在 GUI 73..91，而这一行原来从 77 起）。
			//
			// 方案 A（用户反馈第二轮重新算账）：模式 30 + 2 + 数值 30 + 2 + 单位 18 = 82。
			// 第二行**每个控件**的文字上限 = width - 10（汉字约 9px/字；Create 的 Label **不裁剪**、
			// 画超出盒子），所以标签必须事先就放得下 —— 三选一的语义靠悬停提示 hint.entry_signal_mode 兜。
			case SIGNAL -> {
				// ① 模式（接收 / 发送 / 中断）：**恒建** —— 宽度必须是同一个数，
				//    否则同一个操作切模式时控件会跳位（§7.16b 二 的 A 补丁）。
				inlinePicker(SIGNAL_MODE_X, SIGNAL_MODE_W, tr("entry.signal_mode"), tr("hint.entry_signal_mode"),
					signalModeLabels(), entry.signalMode.ordinal(), i -> {
						ArmProgramEntry.SignalMode picked =
							ArmProgramEntry.SignalMode.values()[Mth.clamp(i, 0, 2)];
						if (entry.signalMode == picked)
							return;
						ArmProgramEntry.SignalMode oldMode = entry.signalMode;
						pushUndo();
						entry.signalMode = picked;
						// ★ 电平与模式是**两个字段**（电平沿用老字段 waitForPowered，老卡才不会丢语义）：
						//   运行时读的是 waitForPowered，所以这里必须把它们钉在一起 —— 否则
						//   "中断"这条在默认 redpow=true 下会发 15（发的是"通电"）。接收那支不动这个字段：
						//   老卡的 redpow=false 必须原样留着（§7.16b 三 兼容清单 3）。
						if (picked == ArmProgramEntry.SignalMode.SEND_ON)
							entry.waitForPowered = true;
						else if (picked == ArmProgramEntry.SignalMode.SEND_OFF)
							entry.waitForPowered = false;
						// ⚠️ 复用 waitAmount 的陷阱（§7.16b 三）：waitt 的老默认是 **20**，而用户要的是
						// "1gt 足够"。所以**从接收切到发送**、且它还是那个从没被人改过的老默认（20 tick）时改写成 1。
						// 走 (b) 编辑器做法（不动 signalMode 的 setter）：玩家真的想发 20t 脉冲的条目
						// 在切模式时不会被改掉（他改过的话 waitAmount/waitUnit 已经不是这个组合了）。
						if (oldMode == ArmProgramEntry.SignalMode.RECEIVE
							&& entry.waitAmount == 20 && entry.waitUnit == WaitUnit.TICK)
							entry.waitAmount = 1;
						dirty = true;
						rebuildPending = true;
					});
				// ② 持续 = **数值 + 单位**（用户反馈第二轮：和等待/时限一样带 t/s/m 单位框）：
				//    只有"发"才建 —— 接收信号没有"持续"这个概念，给它留空槽只是浪费（§7.16b 二 A 的补丁）。
				if (entry.isSignalSend()) {
					inlineInt(SIGNAL_TICKS_X, SIGNAL_TICKS_W, tr("entry.wait_amount"),
						tr("hint.entry_signal_ticks"), 0, ArmProgramEntry.SIGNAL_MAX_AMOUNT,
						// ⚠️ 框里回显的是**数值**（0–120），不是换算后的 ticks：
						//    signalTicks() 会 × 单位（1 s = 20），拿它当回显值会出现"选 5 s 显示 100"。
						Mth.clamp(entry.waitAmount, 0, ArmProgramEntry.SIGNAL_MAX_AMOUNT), v -> {
							pushUndo();
							entry.waitAmount = v;
						});
					// 单位框与「等待」/「失败后」共用同一个枚举（opt.tick/second/minute = t/s/m）：
					// 运行时按 signalTicks() 换算（t=1 / s=20 / m=1200），0 = 一直保持（不与单位相乘）
					inlinePicker(SIGNAL_UNIT_X, SIGNAL_UNIT_W, tr("entry.wait_unit"), tr("hint.entry_wait_unit"),
						labels(WaitUnit.values()), entry.waitUnit.ordinal(), i -> {
							entry.waitUnit = WaitUnit.values()[i];
							dirty = true;
						});
				}
			}
			case CALL -> inlinePicker(FIELD_X, FIELD_W, tr("entry.call_target"), tr("hint.entry_call_target"),
				callTargetOptions(), callTargetIndex(entry.callTarget), i -> {
					List<String> names = callTargetNames();
					if (!names.isEmpty()) {
						pushUndo();
						entry.callTarget = names.get(Mth.clamp(i, 0, names.size() - 1));
					}
					dirty = true;
				});
			case THROW -> textField(FIELD_X, ROW2_Y, FIELD_W, tr("entry.error_message"),
				tr("hint.entry_error_message"), entry.errorMessage, 48, v -> {
					entry.errorMessage = v;
					dirty = true;
				});
			// 退出是终结操作：第二行什么都不需要
			case BREAK -> {
			}
			default -> {
				// 走到这里的都是"需要坐标"的动作：坐标（+ 左右键才有朝向）
				// ★ P1「坐标模型统一」（§19）：条目属性里的「坐标空间」（rel/abs 滚轮）已删 ——
				//   一份程序一个原点，条目不再有空间记号。原点的编辑入口（数值框 / 中键 / 整体偏移）
				//   是 P2/P3 的事，这一轮不做。
				if (!entry.op.requiresTarget())
					return;

				boolean face = needsFace(entry.op);
				// 坐标框**接管**原来那个「坐标空间」选择框的位置（宽 42）：右沿仍与上面那个选择框
				// （77..197）对齐。有「面」的操作用它原来的宽度（88..120），坐标框只占 77..109。
				int coordW = face ? 32 : FIELD_W;
				// 框里回显的是**世界位置**（= 原点 + 偏移），玩家在世界里看到的坐标就是它；
				// 写回去时要减去原点：模型里存的永远是相对原点的偏移（§19.2 第 1 条，运行时的锚点是臂）。
				BlockPos world = program.origin.offset(entry.offset);
				textField(FIELD_X, ROW2_Y, coordW, tr("entry.coords"), tr("hint.entry_coords"),
					world.getX() + "," + world.getY() + "," + world.getZ(), 24, v -> {
						BlockPos parsed = parseCoords(v);
						if (parsed != null) {
							pushUndo();
							entry.offset = parsed.subtract(program.origin);
						}
					});
				if (face)
					inlinePicker(FIELD_X + 32 + 4, 32, tr("entry.face"), tr("hint.entry_face"),
						labels(Direction.values()), entry.face.ordinal(), i -> {
							pushUndo();
							entry.face = Direction.values()[i];
							rebuildPending = true;
						});
			}
		}
	}

	/** {@code "1,-2,3"} → BlockPos；半截输入（空段/非数字）返回 null，保持原值。 */
	@Nullable
	private static BlockPos parseCoords(String text) {
		String[] parts = text.split(",");
		if (parts.length != 3)
			return null;
		try {
			return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
				Integer.parseInt(parts[2].trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void addEditorWidget(AbstractWidget widget) {
		editorWidgets.add(widget);
		addRenderableWidget(widget);
	}

	// ——— 编辑器第二行的"值控件"：一律 DATA_AREA 盒子 + 无边框输入（照时刻表 ModularGuiLine） ———

	/** 第二行的盒子（在 renderEditor 里画；控件自己在盒子上）。 */
	private final List<int[]> editorBoxes = new ArrayList<>();

	private void addEditorBox(int x, int y, int width) {
		editorBoxes.add(new int[] { leftPos + x, topPos + y, width });
	}

	/** 上游 {@code ModularGuiLine.box:65-72}：DATA_AREA 拉伸 + START/END 两个包头。 */
	private void renderDataArea(GuiGraphics graphics, int x, int y, int width) {
		UIRenderHelper.drawStretched(graphics, x, y, width, 18, 0, AllGuiTextures.DATA_AREA);
		AllGuiTextures.DATA_AREA_START.render(graphics, x, y);
		AllGuiTextures.DATA_AREA_END.render(graphics, x + width - 2, y);
	}

	/** 第二行的枚举选择框（上游 {@code addSelectionScrollInput}）。 */
	private void inlinePicker(int x, int width, Component name, Component hint, List<Component> options, int selected,
		IntConsumer onPick) {
		addEditorBox(x, ROW2_Y, width);
		SelectionScrollInput input = new SelectionScrollInput(leftPos + x, topPos + ROW2_Y, width, 18);
		input.forOptions(options);
		input.titled(name.copy());
		input.addHint(hint.copy());
		input.setState(selected);
		input.calling(onPick::accept);
		Label label = label(leftPos + x + 5, topPos + ROW2_Y + 4,
			options.get(Mth.clamp(selected, 0, options.size() - 1)), width - 10);
		input.writingTo(label);
		addEditorWidget(input);
	}

	/** 第二行的数值选择框（上游 {@code addScrollInput}）。{@code max} 是**含**的上界。 */
	private void inlineInt(int x, int width, Component name, Component hint, int min, int max, int value,
		IntConsumer onPick) {
		addEditorBox(x, ROW2_Y, width);
		// ⚠️ Create 的 {@code ScrollInput.clampState()} 是"到上界就退回一格"
		// （`if (state >= max) state = max - 1;`），所以范围要传 max+1 才能真正选到 max。
		// 第一版直接用 (0,120) → 等待时长只能选到 119（用户实测："范围是 0-119，不包含 120"）。
		ScrollInput input = new ScrollInput(leftPos + x, topPos + ROW2_Y, width, 18).withRange(min, max + 1);
		input.format(v -> Component.literal(String.valueOf(v)));
		input.titled(name.copy());
		input.addHint(hint.copy());
		input.setState(value);
		input.calling(onPick::accept);
		Label label = label(leftPos + x + 5, topPos + ROW2_Y + 4, Component.literal(String.valueOf(value)),
			width - 10);
		input.writingTo(label);
		addEditorWidget(input);
	}

	/** 第二行的文本框（上游 {@code addTextInput}：无边框 EditBox + 一整块 DATA_AREA）。 */
	private void textField(int x, int y, int width, Component name, Component hint, String value, int maxLength,
		Consumer<String> onEdit) {
		textField(x, y, width, name, hint, List.of(), value, maxLength, onEdit);
	}

	/**
	 * 同上，但可以再挂若干**附加行**（备注字段用它把多行全文摊在提示里）。
	 *
	 * <p>附加行必须是**一行一个 Component**（见 {@link #noteTipLines}）：把带 {@code \n} 的整段塞进
	 * 一个组件，在 tooltip 装得下屏幕时不会被断行，{@code \n} 会画成一个"缺字方框"（用户实测）。
	 */
	private void textField(int x, int y, int width, Component name, Component hint, List<Component> extraLines,
		String value, int maxLength, Consumer<String> onEdit) {
		addEditorBox(x, y, width);
		EditBox box = new EditBox(font, leftPos + x + 5, topPos + y + 4, width - 10, 8, name);
		box.setBordered(false);
		box.setTextColor(0xffffff);
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setResponder(onEdit);
		addEditorWidget(box);
		TooltipArea area = new TooltipArea(leftPos + x, topPos + y, width, 18);
		List<Component> tip = new ArrayList<>();
		tip.add(name.copy());
		tip.add(hint.copy()
			.withStyle(ChatFormatting.DARK_GRAY));
		tip.addAll(extraLines);
		area.withTooltip(tip);
		// ⚠️ 只"画"不"收事件"：上游也是把它 addRenderableOnly 进去的（ModularGuiLine.loadValues:96-99）。
		// 用 addRenderableWidget 的话它会把点击吃掉，EditBox 永远拿不到焦点（点不进输入框）。
		editorWidgets.add(area);
		addRenderableOnly(area);
	}

	private <E extends Enum<E>> void picker(Component name, Component hint, List<Component> options, int selected,
		IntConsumer onPick) {
		SelectionScrollInput input = new SelectionScrollInput(leftPos + 56, topPos + 65, 143, 16);
		input.forOptions(options);
		input.titled(name.copy());
		input.addHint(hint.copy());
		input.setState(selected);
		input.calling(onPick::accept);
		Label label = label(leftPos + 59, topPos + 69,
			options.get(Math.max(0, Math.min(options.size() - 1, selected))), 140);
		input.writingTo(label);
		addEditorWidget(input);
	}

	/** 数值属性：只建第二行的值控件（第一行是统一的选择框）。 */
	private void intPicker(Component name, Component hint, int min, int max, int value, IntConsumer onPick) {
		inlineInt(FIELD_X, FIELD_W, name, hint, min, max, value, onPick);
	}

	/**
	 * 造一个 Create 的 {@code Label} 并**注册进编辑器**。
	 *
	 * <p>⚠️ 三个坑都在这一处收口：
	 * <ol>
	 *   <li>构造器会丢掉传入文本（写死 "Label"），必须构造后 {@code setTextAndTrim}；</li>
	 *   <li>构造器还把 width 设成**未截断**文本的宽度（261px 的长说明 → 自检报"控件越界"）；</li>
	 *   <li>{@code setTextAndTrim(text, false, w)} 在文本超宽时只保留**最后一个字**再加 "…"
	 *       （它内部 `substring(startIndex, i+1)` 的下标算反了，实测长提示显示成"置..."）——
	 *       所以截断自己来做，传进去的文本一定已经放得下。</li>
	 * </ol>
	 */
	private Label label(int x, int y, Component text, int maxWidth) {
		Label label = new Label(x, y, text);
		label.setTextAndTrim(trimToWidth(text, maxWidth), false, maxWidth);
		label.withShadow(); // 上游 ScrollInput 的 label 也是带阴影的（ModularGuiLineBuilder:56）
		label.setWidth(maxWidth);
		// ⚠️ **只画不收事件**（上游 TooltipArea 就是这么挂的，见 ModularGuiLine.loadValues:96-99）。
		// 原版 1.21 的滚轮分发是 `getChildAt(x,y).filter(控件消费)`，而 `getChildAt` 是**从前往后**扫
		// 第一个命中的控件：标签比输入框先注册、又盖在它上面，于是标签"接住"滚轮后返回 false，
		// 整个事件就哑掉了 —— 症状正是用户报的"一会能滚动一会不能"（点在框边缘那 5px 时标签没覆盖到，
		// 就能滚）。自检 checkWheel 现在盯着这条。
		editorWidgets.add(label);
		addRenderableOnly(label);
		return label;
	}

	/** 按像素宽度从**头部**截断，超宽就加 "…"。 */
	private Component trimToWidth(Component text, int maxWidth) {
		if (font.width(text) <= maxWidth)
			return text;
		String raw = text.getString();
		String ellipsis = "…";
		int width = font.width(ellipsis);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (width + font.width(String.valueOf(c)) > maxWidth)
				break;
			sb.append(c);
			width += font.width(String.valueOf(c));
		}
		return Component.literal(sb + ellipsis);
	}


	private List<Component> labels(Enum<?>[] values) {
		List<Component> list = new ArrayList<>(values.length);
		for (Enum<?> value : values)
			// Locale.ROOT：默认 locale 是土耳其语时 `WAIT → waıt`（无点 i），键就命不中、界面显示原始 key
			list.add(tr("opt." + value.name()
				.toLowerCase(java.util.Locale.ROOT)));
		return list;
	}

	/**
	 * 「收发信号」模式选择框的三个选项（接收 / 发送 / 中断）。
	 *
	 * <p>⚠️ <b>不能走通用的 {@link #labels(Enum[])}</b>：它按枚举名派生成
	 * {@code opt.receive} / {@code opt.send_on} / {@code opt.send_off}，而 lang 里的键是
	 * {@code opt.signal_receive} / {@code opt.signal_send_on} / {@code opt.signal_send_off}
	 * （{@code langKeyProblem} 查的也是这三个键）。派生的键查不到译文 ⇒ 选择框里
	 * 画的是**原始 key**（实测 "…opt.receive" = 257px，远超 30-10=20），而且**中英两语都一样**。
	 *
	 * <p>标签是**短名**（接收 / 发送 / 中断；en 三字母 Rcv/Snd/Cut）：模式框只有 30 宽，
	 * 文字上限 {@code 30-10=20}（汉字 2 字 = 18 ✔ / 4 个字母 ≈ 24 ✗）。卡片标题上那句
	 * 「接收信号 / 发送信号 / 中断信号」是**另一组键**（{@code chip.signal_*}，见
	 * {@link ArmEntryText#signalHeadText}）—— 那里没有 20px 的上限，但也不能用长英文（见 SIGNAL_CHIP_MAX）。
	 *
	 * <p>顺序**按枚举 ordinal 走**（下标 i ↔ {@code SignalMode.values()[i]}）：这样枚举增删项时
	 * 选项与值不会错位。
	 */
	private static List<Component> signalModeLabels() {
		List<Component> list = new ArrayList<>(ArmProgramEntry.SignalMode.values().length);
		for (ArmProgramEntry.SignalMode mode : ArmProgramEntry.SignalMode.values())
			list.add(tr(switch (mode) {
				case RECEIVE -> "opt.signal_receive";
				case SEND_ON -> "opt.signal_send_on";
				case SEND_OFF -> "opt.signal_send_off";
			}));
		return list;
	}

	// ————————————————————————— 数据操作 —————————————————————————

	/** 底部第一个按钮：任务页 ↔ 操作页。 */
	private void togglePage() {
		if (!switchPage(page == Page.ENTRIES ? Page.TASKS : Page.ENTRIES)) {
			status = tr("status.no_task").getString();
			rebuildPending = true;
			return;
		}
		// 进操作页时"指针指着的那个任务"成了当前任务 ⇒ 写回卡（与界外 Ctrl+滚轮同一条路，§5.1 v5）
		if (page == Page.ENTRIES)
			pushTaskSelection();
		// ⚠️ 这里**不再**"把滚动归零"：每个列表有自己的位置（见 listScrolls 的说明），
		//   回到某个列表就该回到它上次看到的地方。要归零只需拖滑块/滚轮。
		clampListScroll(); // 只把它夹进**自己**的范围（内容可能在上次离开之后变短了）
		status = "";
		rebuildPending = true;
	}

	/**
	 * 页面切换的**状态部分**：把指针存回旧列表、取出新列表的（没记过就用 {@link #defaultPointer()}），
	 * 并在进操作页时把"指针指着的任务"变成当前任务。返回 {@code false} = 切不过去（一条任务都没有）。
	 *
	 * <p>为什么与按钮分开：自检要走**真实的切换路径**却**不能发包** —— {@link #pushTaskSelection()}
	 * 会把模式/任务推给服务端（顺手改掉玩家卡上的编辑状态），那是按钮该做的事，不是状态机该做的事。
	 */
	private boolean switchPage(Page to) {
		if (to == Page.ENTRIES && program.tasks.isEmpty())
			return false;
		pointers.put(listKey(), pointerIndex); // 旧列表（它的 key 由**旧的** page / currentTask 决定）
		if (to == Page.TASKS) {
			page = Page.TASKS;
		} else {
			currentTask = Mth.clamp(pointerIndex, 0, program.tasks.size() - 1);
			page = Page.ENTRIES;
		}
		pointerIndex = Math.max(0, pointers.getOrDefault(listKey(), defaultPointer()));
		return true;
	}

	/** 底部第三个按钮：在**指针之后**插入（"插入新操作到下方"），然后直接进编辑器。 */
	private void insertAfterPointer() {
		if (page == Page.TASKS)
			insertTaskAt(pointerIndex + 1);
		else
			insertEntryAt(pointerIndex + 1);
	}

	/**
	 * 列表末尾那张"新卡片"＋：**追加到末尾**（用户实测反馈："新建条目应该是追加新的条目，
	 * 而不是插到指针所在的条目下面"）。底部工具条那个按钮才是"插到指针下面"，两者分工不同。
	 */
	private void appendAtEnd() {
		if (page == Page.TASKS)
			insertTaskAt(program.tasks.size());
		else {
			ArmProgramTask task = task(currentTask);
			insertEntryAt(task == null ? 0 : task.entries.size());
		}
	}

	private void insertTaskAt(int at) {
		// 上限守卫（两个入口都走这里：底栏 ＋ 与列表末尾那张空卡）—— 必须在 pushUndo **之前**：
		// 加不进去就不该在撤销栈里留一条空操作，更不该让玩家一路加过配置上限。服务端是**整份接受或整份拒绝**
		// （`ArmProgramEditPacket` 只判 `isWithinLimits()`），超限时关界面 = 这一场编辑一个字都没落盘。
		if (!program.canAddTask()) {
			status = tr("status.too_many_tasks", ArmProgram.maxTasks()).getString();
			rebuildPending = true;
			return;
		}
		pushUndo();
		ArmProgramTask created = new ArmProgramTask();
		created.name = "task" + (program.tasks.size() + 1);
		int index = Mth.clamp(at, 0, program.tasks.size());
		program.tasks.add(index, created);
		pointerIndex = index;
		openEditor(new Card(true, index, -1), null);
	}

	private void insertEntryAt(int at) {
		ArmProgramTask task = task(currentTask);
		if (task == null) {
			status = tr("status.no_task").getString();
			rebuildPending = true;
			return;
		}
		if (totalEntries() >= ArmProgram.maxEntriesTotal()) {
			status = tr("status.over_limit", ArmProgram.maxEntriesTotal()).getString();
			rebuildPending = true;
			return;
		}
		pushUndo();
		int index = Mth.clamp(at, 0, task.entries.size());
		// ★ 新条目**必须给一个有意义的位置**（§19.6 用户实测踩到的坑）：偏移的含义是"相对程序原点"，
		//   而新卡的原点是 (0,0,0) ⇒ 把偏移留成 0 就等于"目标 = 世界原点"（离臂几十上百格、必然超范围），
		//   玩家看到的就是"臂在用绝对坐标"那种怪现象。默认取"你正看着的那个方块"，没看着就取你脚下 ——
		//   与"录一条"的直觉一致（等价于当场录了一条只有位置、还没选动作的条目）。
		ArmProgramEntry created = new ArmProgramEntry();
		created.offset = defaultNewEntryWorldPos()
			.subtract(program.origin);
		task.entries.add(index, created);
		pointerIndex = index;
		openEditor(new Card(false, currentTask, index), null);
	}

	/** 新条目的默认世界位置：看向的方块 → 没有就用玩家脚下；都没有才退回原点（等价于偏移 0）。 */
	private BlockPos defaultNewEntryWorldPos() {
		net.minecraft.client.player.LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null)
			return program.origin;
		if (minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
			&& hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK)
			return hit.getBlockPos();
		return player.blockPosition();
	}

	private int totalEntries() {
		return program.tasks.stream()
			.mapToInt(t -> t.entries.size())
			.sum();
	}

	private void remove(Card card) {
		pushUndo();
		removeWithoutUndo(card);
	}

	/** 真正干活的那半（{@link #deleteEditing} 已经自己 pushUndo 过了，不能再推一次）。 */
	private void removeWithoutUndo(Card card) {
		if (card.task) {
			if (card.taskIndex < 0 || card.taskIndex >= program.tasks.size())
				return;
			program.tasks.remove(card.taskIndex);
			currentTask = Mth.clamp(currentTask, 0, Math.max(0, program.tasks.size() - 1));
		} else {
			ArmProgramTask task = task(card.taskIndex);
			if (task != null && card.entryIndex >= 0 && card.entryIndex < task.entries.size())
				task.entries.remove(card.entryIndex);
		}
		pointerIndex = Mth.clamp(pointerIndex, 0, Math.max(0, layout().size() - 1));
		rebuildPending = true;
	}

	private void duplicate(Card card) {
		if (card.task) {
			ArmProgramTask source = task(card.taskIndex);
			// 复制任务同样要守上限（条目分支下面那条 `maxEntriesTotal()` 是它的对应物）：
			// 少这一道，玩家按一次"复制"就能把程序顶到不合法，然后在关界面时整份被服务端拒收。
			if (source == null || !program.canAddTask()) {
				if (source != null) {
					status = tr("status.too_many_tasks", ArmProgram.maxTasks()).getString();
					rebuildPending = true;
				}
				return;
			}
			pushUndo();
			ArmProgramTask copy = ArmProgramTask.load(source.save(registries()), registries());
			copy.name = source.name + "2";
			program.tasks.add(card.taskIndex, copy);
			pointerIndex = card.taskIndex;
		} else {
			ArmProgramEntry source = entry(card);
			if (source == null || totalEntries() >= ArmProgram.maxEntriesTotal())
				return;
			pushUndo();
			ArmProgramTask task = task(card.taskIndex);
			if (task != null)
				task.entries.add(card.entryIndex, ArmProgramEntry.load(source.save(registries()), registries()));
			pointerIndex = card.entryIndex;
		}
		rebuildPending = true;
	}

	private void move(Card card, int delta) {
		pushUndo();
		if (card.task) {
			int target = card.taskIndex + delta;
			if (target < 0 || target >= program.tasks.size())
				return;
			program.tasks.add(target, program.tasks.remove(card.taskIndex));
			pointerIndex = target;
		} else {
			ArmProgramTask task = task(card.taskIndex);
			if (task == null)
				return;
			int target = card.entryIndex + delta;
			if (target < 0 || target >= task.entries.size())
				return;
			task.entries.add(target, task.entries.remove(card.entryIndex));
			pointerIndex = target;
		}
		rebuildPending = true;
	}

	private void undo() {
		if (undoStack.isEmpty())
			return;
		redoStack.push(program.save(registries()));
		if (redoStack.size() > 50)
			redoStack.removeLast();
		program = ArmProgram.load(undoStack.pop(), registries());
		dirty = true;
		rebuildPending = true;
	}

	private void redo() {
		if (redoStack.isEmpty())
			return;
		undoStack.push(program.save(registries()));
		if (undoStack.size() > 50)
			// 删**最旧**的那份：`Deque.push` = `addFirst`，所以另一端是 removeLast()
			//（原来写的是 removeFirst —— 删掉的正是刚压进去的这份。与 undo()/pushUndo() 对齐。）
			undoStack.removeLast();
		program = ArmProgram.load(redoStack.pop(), registries());
		dirty = true;
		rebuildPending = true;
	}

	private void pushUndo() {
		undoStack.push(program.save(registries()));
		if (undoStack.size() > 50)
			undoStack.removeLast();
		redoStack.clear();
		dirty = true;
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable("create_sequenced_mechanical_arm.gui.editor." + key, args);
	}

	/** 操作的中文名（DEPLOY=组装：Create 的 deploying 官方译名"使用"给了 USE）。 */
	private static Component opName(OperationType op) {
		return tr("opt." + op.name()
			.toLowerCase());
	}

	// ————————————————————————— 自绘控件 —————————————————————————

	/** 带文字的 Create 按钮（用 IconButton 同一张 widgets 贴图，不引入新贴图）。 */
	static class TextButton extends AbstractSimiWidget {

		private final Component text;

		TextButton(int x, int y, int width, int height, Component text) {
			super(x, y, width, height);
			this.text = text;
		}

		void setToolTip(Component text) {
			toolTip.clear();
			toolTip.add(text);
		}

		@Override
		protected void doRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
			isHovered = mouseX >= getX() && mouseY >= getY() && mouseX < getX() + width && mouseY < getY() + height;
			AllGuiTextures button = !active ? AllGuiTextures.BUTTON_DISABLED
				: isHovered && AllKeys.isMouseButtonDown(0) ? AllGuiTextures.BUTTON_DOWN
					: isHovered ? AllGuiTextures.BUTTON_HOVER : AllGuiTextures.BUTTON;
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			// ⚠️ 按钮贴图是 **18×18**（`AllGuiTextures.BUTTON("widgets", 18, 18)`）。第一版写的是
			// `blit(..., min(width, 18), 18)` → 34px 宽的按钮只画出 18px 的底，文字居中飘在空白上，
			// 看起来就是"图标和输入框之间一个大空隙"（用户实测）。改成九宫格拉伸：四角 1px、
			// 四条边与中间拉伸（贴图的边正好 1px，拉伸不失真）。
			blit9(graphics, button, getX(), getY(), width, height);
			Minecraft mc = Minecraft.getInstance();
			int color = active ? 0xFFFFFF : 0xA0A0A0;
			graphics.drawString(mc.font, text, getX() + (width - mc.font.width(text)) / 2, getY() + (height - 8) / 2,
				color, false);
		}

		/** 九宫格画一张 18×18 的按钮贴图：四角 + 四边 + 中间，任意宽高都铺满。 */
		private static void blit9(GuiGraphics graphics, AllGuiTextures tex, int x, int y, int w, int h) {
			ResourceLocation loc = tex.getLocation();
			int u = tex.getStartX();
			int v = tex.getStartY();
			int tw = tex.getWidth();
			int th = tex.getHeight();
			if (w <= tw && h <= th) { // 放得下就原样搬，不放大贴图
				graphics.blit(loc, x, y, w, h, u, v, w, h, 256, 256);
				return;
			}
			int ex = Math.min(1, tw); // 边宽
			int ey = Math.min(1, th);
			graphics.blit(loc, x + ex, y + ey, w - ex * 2, h - ey * 2, u + ex, v + ey, Math.max(1, tw - ex * 2),
				Math.max(1, th - ey * 2), 256, 256);
			graphics.blit(loc, x + ex, y, w - ex * 2, ey, u + ex, v, Math.max(1, tw - ex * 2), ey, 256, 256);
			graphics.blit(loc, x + ex, y + h - ey, w - ex * 2, ey, u + ex, v + th - ey, Math.max(1, tw - ex * 2), ey,
				256, 256);
			graphics.blit(loc, x, y, ex, h, u, v, ex, th, 256, 256);
			graphics.blit(loc, x + w - ex, y, ex, h, u + tw - ex, v, ex, th, 256, 256);
		}
	}

	// ————————————————————————— 客户端自检 —————————————————————————

	/**
	 * 把界面整条构造路径跑一遍（任务页 + 操作页 + 每种动作/每个属性的编辑器），不渲染、不发包。
	 *
	 * <p>专用服务器碰不到客户端代码，而这套东西最容易出的错（构造期读 {@code minecraft} 字段、
	 * 控件 API 用错、热区算错）全是<b>点开界面那一刻</b>才炸 —— 编译和 {@code runServer} 一条都抓不到。
	 * 第一次实测就撞上"一开界面客户端掉线"。
	 */
	/**
	 * 打开编辑器是否会落到卡上记着的那个任务（§5.1 v5 的会话状态）。单独用一个两任务的假卡来测，
	 * 不动上面那套自检的构造（它统计的任务卡/操作卡数量是有意义的基线）。
	 */
	private static boolean sessionSeedOk(Minecraft mc, Inventory inventory, RegistryAccess registries) {
		ArmProgram two = new ArmProgram();
		for (int i = 0; i < 2; i++) {
			ArmProgramTask t = new ArmProgramTask();
			t.name = "t" + (i + 1);
			two.tasks.add(t);
		}
		two.editorTask = 1;
		two.editorMode = CardUiPacket.EDIT;
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE_RELATIVE.get());
		CardProgramStorage.write(card, two, registries);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		ItemStack.STREAM_CODEC.encode(buffer, card);
		ArmProgramScreen probe = new ArmProgramScreen(new ArmProgramMenu(0, inventory, buffer), inventory, tr("title"));
		return probe.currentTask == 1;
	}

	/**
	 * 空程序（卡上一条任务都没有）打开界面，必须落在**任务页**：操作页在那时没有任何可编辑的东西
	 * （用户实测反馈："在没有任务的时候打开编辑界面应该直接进入任务编辑而不是操作编辑"）。
	 */
	private static boolean emptyProgramStartsOnTasks(Inventory inventory, RegistryAccess registries) {
		ArmProgram empty = new ArmProgram();
		empty.editorMode = CardUiPacket.EDIT;
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE_RELATIVE.get());
		CardProgramStorage.write(card, empty, registries);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		ItemStack.STREAM_CODEC.encode(buffer, card);
		ArmProgramScreen probe = new ArmProgramScreen(new ArmProgramMenu(0, inventory, buffer), inventory, tr("title"));
		return probe.page == Page.TASKS;
	}

	/**
	 * 关掉界面再打开，是否回到离开时那一页 / 那一行 / 那个滚动位置（§18）。
	 *
	 * <p>用户要求：<i>"可以让操作页的指针位置和滚动位置也保留下来，这样方便玩家连续编辑测试。"</i>
	 * 这条走的是**真实的重开路径**：把浏览位置写进卡 → 用这张卡构造一个界面 → 看它落在哪
	 * （而不是去读 {@code pointers} 里那几个数，那只等于测了自己）。
	 */
	private static boolean viewStateSeedOk(Inventory inventory, RegistryAccess registries) {
		ArmProgram program = new ArmProgram();
		for (int i = 0; i < 2; i++) {
			ArmProgramTask task = new ArmProgramTask();
			task.name = "t" + (i + 1);
			for (int e = 0; e < 6; e++)
				task.entries.add(new ArmProgramEntry());
			program.tasks.add(task);
		}
		program.editorTask = 1; // 当前任务是第 2 个
		program.editorMode = CardUiPacket.EDIT;
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE_RELATIVE.get());
		CardProgramStorage.write(card, program, registries);
		CardProgramStorage.writeView(card, new CardProgramStorage.View(3, CardProgramStorage.PAGE_ENTRIES, 40f, 12f));
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		ItemStack.STREAM_CODEC.encode(buffer, card);
		ArmProgramScreen probe = new ArmProgramScreen(new ArmProgramMenu(0, inventory, buffer), inventory, tr("title"));
		return probe.page == Page.ENTRIES && probe.currentTask == 1 && probe.pointerIndex == 3
			&& Math.abs(probe.scrollOf("task#1") - 40f) < 0.01f && Math.abs(probe.scrollOf("tasks") - 12f) < 0.01f;
	}

	/** 自检用：实测到的任务数上限（加到第几个被编辑器挡住）；{@code -1} = 守卫没生效。 */
	private int probedTaskLimit = -1;

	public static String selfCheck(Minecraft mc) {
		try {
			Inventory inventory = mc.player != null ? mc.player.getInventory() : new Inventory(null);
			RegistryAccess registries = mc.level != null ? mc.level.registryAccess()
				: RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

			ArmProgram program = new ArmProgram();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "smoke";
			task.loop = LoopType.COUNT;
			task.loopCount = 3;
			program.tasks.add(task);
			for (OperationType op : OperationType.values()) {
				ArmProgramEntry entry = new ArmProgramEntry();
				entry.op = op;
				entry.offset = new BlockPos(1, 2, 3);
				entry.count = 2;
				entry.waitAmount = 20;
				entry.waitForPowered = false;
				entry.unloadRepeat = UnloadRepeat.UNTIL_EMPTY;
				entry.unloadAmount = UnloadAmount.MAX;
				entry.onSuccess = SkipAction.SKIP_TO;
				entry.onTimeout = SkipAction.SKIP_TO;
				entry.successTarget = "2";
				entry.timeoutTarget = "2";
				entry.note = op == OperationType.MINE ? ""
					: "smoke"; // MINE 那张故意不给备注：它的行宽正好落在"＋ 会被顶出去、但点击坐标仍在窗口内"那一段 —— 用户实测的 bug 就在那一段，自检必须有一张卡落在里面
				task.entries.add(entry);
			}
			// 一个"只有最基础内容"的 WAIT 卡：用来验证"等待不该有成功/超时/时限"（用户实测反馈）
			ArmProgramEntry plainWait = new ArmProgramEntry();
			plainWait.op = OperationType.WAIT;
			task.entries.add(plainWait);
			// ★ 「收发信号」的**发**那一态也要在烟雾数据里（§7.16b 四 断言 11）：
			//   上面那个循环建的 SIGNAL 是**收**（默认）—— "收有「失败后」、发没有"这条只能靠两条对照。
			ArmProgramEntry signalSend = new ArmProgramEntry();
			signalSend.op = OperationType.SIGNAL;
			signalSend.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
			task.entries.add(signalSend);
			// 第一张卡故意一个属性都没有：行尾那个 ＋ 在视野里，热区自检才能真去点它
			// （用户报过"＋ 的判定区偏左 8px" —— 对应 ADD_PROP_HIT_* 的说明）。
			// ★ 顺带挂一条**长备注**（接近上限）：守"备注胶囊只是摘要"——
			//   长备注会把行尾的 ＋ 顶出裁剪区（用户实测："挤到外面……没有显示，也可以点击"）。
			//   挂在**第一张卡**上是有意的：它一定在视野内，胶囊宽度那条断言才跑得到
			//   （挂在最后一张卡上时它会滚出视野，断言就被 `inside(...)` 跳过了 —— 第一版就是这么写的）。
			ArmProgramEntry plainTake = new ArmProgramEntry();
			plainTake.op = OperationType.TAKE;
			plainTake.offset = new BlockPos(0, 0, 0);
			plainTake.note = "放铲子进去然后等 5 秒再把东西取回来（这条是自检用的长备注，用来把备注胶囊顶到最宽）";
			task.entries.add(0, plainTake);

			ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE_RELATIVE.get());
			CardProgramStorage.write(card, program, registries);
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
			ItemStack.STREAM_CODEC.encode(buffer, card);

			ArmProgramMenu menu = new ArmProgramMenu(0, inventory, buffer);
			ArmProgramScreen screen = new ArmProgramScreen(menu, inventory, tr("title"));

			int widgets = 0;
			int editors = 0;
			String problem = null;

			screen.page = Page.TASKS;
			screen.init(mc, 427, 240);
			problem = screen.checkLayout("TASKS");
			int taskCards = screen.layout().size();
			screen.page = Page.ENTRIES;
			screen.init(mc, 427, 240);
			String entriesProblem = screen.checkLayout("ENTRIES");
			if (entriesProblem != null && problem == null)
				problem = entriesProblem;
			int entryCards = screen.layout().size();
			int rowWidth = screen.maxRowWidth();

			for (int e = 0; e < task.entries.size(); e++) {
				ArmProgramEntry entry = task.entries.get(e);
				screen.editingProp = null;
				// 动作头（第一行 = 动作选择框，第二行 = 该动作的必填内容）
				screen.editing = new EditTarget(false, 0, e, null);
				screen.init(mc, 427, 240);
				widgets += screen.editorWidgets.size();
				editors++;
				String p = screen.checkLayout("EDITOR-OP");
				if (p != null && problem == null)
					problem = entry.op + ": " + p;
				// 幽灵槽不能被第二行的控件压住（用户实测："滚动选择框的左边把幽灵物品槽位占用了"）
				String overlap = screen.checkGhostSlotClearance(entry);
				if (overlap != null && problem == null)
					problem = entry.op + ": " + overlap;

				// 每个属性：**同一个界面**（第一行是属性选择框，第二行是它的值）
				for (Prop prop : screen.entryProps(entry)) {
					screen.editingProp = prop;
					screen.editing = new EditTarget(false, 0, e, prop.name.getString());
					screen.init(mc, 427, 240);
					widgets += screen.editorWidgets.size();
					editors++;
					String q = screen.checkLayout("EDITOR-PROP");
					if (q != null && problem == null)
						problem = entry.op + "/" + prop.name.getString() + ": " + q;
					// 编辑属性时**一个幽灵槽都不该激活**（用户实测：那时还留着频率槽，槽里挂着上次的木炭）
					String propSlots = screen.checkGhostSlotClearance(entry);
					if (propSlots != null && problem == null)
						problem = entry.op + "/" + prop.name.getString() + ": " + propSlots;
				}
			}

			// 自带一条"属性表跟动作走"的硬断言（等待没有成功/超时/时限）：这类规则最容易在改动里
			// 悄悄回退，而它只在打开编辑器时才看得见。
			String propProblem = screen.checkPropTable(task);
			if (propProblem != null && problem == null)
				problem = propProblem;

			String revealProblem = revealInvariant(screen, task);
			if (revealProblem != null && problem == null)
				problem = revealProblem;

			// 滚轮自检（用户："一会能滚动一会不能"）：编辑器里鼠标底下的那个控件必须**真的能滚**
			String wheelProblem = screen.checkWheel(task);
			if (wheelProblem != null && problem == null)
				problem = wheelProblem;

			// ★ §7.16b 四 断言 9：收发信号第二行的 82px 算术（越界 / 标签放不下 / 盒子数）——
			// 这条只能自动验：Create 的 Label **不裁剪**（画超出盒子），排版错了在游戏里只表现为"文字糊出框"。
			String signalRow = screen.signalRowProblem();
			if (signalRow != null && problem == null)
				problem = "收发信号第二行: " + signalRow;
			// ★ 断言 10：收/发两套文案 + 「切到发送时默认 1gt」那条陷阱
			String signalWording = screen.signalWordingProblem();
			if (signalWording != null && problem == null)
				problem = "收发信号文案: " + signalWording;
			// ★ 断言 12：两个语言文件的键集必须一致、本轮要用的键都得在（缺键时把 key 名打进日志）
			String langKeys = langKeyProblem(mc);
			if (langKeys != null && problem == null)
				problem = "语言键: " + langKeys;

			// 垃圾桶 = 删除（用户实测反馈："点了垃圾桶它还留着"）：这里真按一下，看条目/属性是否消失
			String deleteProblem = screen.checkDelete();
			if (deleteProblem != null && problem == null)
				problem = deleteProblem;

			screen.editingProp = null;
			screen.editing = new EditTarget(true, 0, -1, null);
			screen.init(mc, 427, 240);
			widgets += screen.editorWidgets.size();
			editors++;
			String taskProblem = screen.checkLayout("EDITOR-TASK");
			if (taskProblem != null && problem == null)
				problem = "TASK: " + taskProblem;

			// 点击入口自检：真走一遍 mouseClicked，验证"卡片热区在容器屏幕之前拿到点击"。
			// 顺序写反时 AbstractContainerScreen 会无条件吞掉左右键，而它同样返回 true ——
			// 所以只有看**副作用**（标题芯片点击后应进入编辑器）才能抓到那个 bug。
			screen.editing = null;
			screen.editingProp = null;
			screen.page = Page.ENTRIES;
			screen.init(mc, 427, 240);
			screen.mouseClicked(screen.leftPos + 25 + 80, screen.topPos + screen.cardY(0) + 12, 0);
			boolean reachedHeat = screen.editing != null;
			screen.editing = null;
			screen.editingProp = null;

			// 点击热区自检（见 checkHitAreas 的说明）—— 必须先退出编辑器，否则 action() 会被短路
			screen.page = Page.ENTRIES;
			screen.init(mc, 427, 240);
			String hitProblem = reachedHeat ? screen.checkHitAreas() : "mouseClicked 没把点击交给卡片热区（顺序写反了？）";
			if (hitProblem != null && problem == null)
				problem = hitProblem;

			// 频率绑定自检：界面上填的两个物品必须真的能变成 entry.frequency
			//（这是之前**完全缺失**的一环：槽只是画出来，frequency 永远是 null ⇒ 那条「等待信号」永远等不到）
			String freqProblem = frequencyBindingProblem();
			if (freqProblem != null && problem == null)
				problem = freqProblem;

			// 任务数上限自检（第二十四轮审计发现的丢数据路径）：编辑器的 ＋ / 复制必须在**到上限那一刻**就挡住。
			String taskLimitGuard = screen.taskLimitProblem();
			if (taskLimitGuard != null && problem == null)
				problem = taskLimitGuard;

			// 编辑「等待信号」的属性后按「完成」不能把绑定的频率抹掉（第二十四轮审计）。
			String signalKeep = screen.signalFrequencyKeepProblem();
			if (signalKeep != null && problem == null)
				problem = signalKeep;

			// 撤销/重做在满栈时仍要成对可逆（第二十四轮审计）。
			String undoRedo = screen.undoRedoProblem();
			if (undoRedo != null && problem == null)
				problem = undoRedo;

			// 备注框的接线（第二十七轮：128 字上限 + 方案 B 的扁平化编辑）。第 0 条是那个只有动作、
			// 任何属性都没设的「取出」卡 —— 它的属性表里一定有"备注"这一项。
			String noteField = screen.noteFieldProblem(0);
			boolean noteFieldOk = noteField == null;
			if (noteField != null && problem == null)
				problem = "备注框: " + noteField;

			// 备注的悬停提示必须是**逐行**组件（用户实测："并没有实现多行排版"）
			String noteTip = noteTooltipProblem();
			if (noteTip != null && problem == null)
				problem = "备注提示: " + noteTip;

			// §12.3b：程序备注 / 任务备注的悬停提示（脚本导入写进去的那两个字段必须在界面上看得见）
			String scriptNotes = programAndTaskNoteProblem(inventory, registries);
			if (scriptNotes != null && problem == null)
				problem = "脚本备注: " + scriptNotes;

			screen.editingProp = null;
			screen.editing = null;
			screen.menu.slotsActive = false;

			// 滚动位置必须**每个列表一份**，右侧滑块必须拖得动（用户第二十七轮续十三）。
			String scrollIndep = screen.scrollIndependenceProblem();
			if (scrollIndep != null && problem == null)
				problem = "滚动位置: " + scrollIndep;
			// 指针同样要**每个列表一份**，且任务页要落在当前任务上（用户 2026-10-14 反馈）。
			String pointerIndep = screen.pointerIndependenceProblem();
			if (pointerIndep != null && problem == null)
				problem = "指针位置: " + pointerIndep;
			String scrollBar = screen.scrollBarProblem();
			if (scrollBar != null && problem == null)
				problem = "滚动条: " + scrollBar;

			// §5.1 v5：界面必须落在**卡上记着的那个任务**上（界外 Ctrl+滚轮选到任务 2，打开就得在任务 2）。
			// 这是"两个入口各说各话"的唯一防线：一边改了 etask、另一边还从 0 开始，用起来就像没生效。
			boolean sessionSeeded = true;
			boolean emptyOnTasks = true;
			boolean viewSeeded = true;
			try {
				sessionSeeded = sessionSeedOk(mc, inventory, registries);
				emptyOnTasks = emptyProgramStartsOnTasks(inventory, registries);
				viewSeeded = viewStateSeedOk(inventory, registries);
			} catch (Throwable t) {
				sessionSeeded = false;
				viewSeeded = false;
			}
			if (!sessionSeeded && problem == null)
				problem = "打开编辑器没有落到卡上记着的任务（etask 没被读）";
			if (!emptyOnTasks && problem == null)
				problem = "空卡打开编辑器没有直接进任务页";
			if (!viewSeeded && problem == null)
				problem = "关掉界面再打开没有回到离开时的页面 / 指针 / 滚动位置（§18）";

			if (problem != null)
				return "FAIL " + problem;
			// ★ 把**实测像素宽**也打进日志（本轮用户反馈：英文短名 Rcv/Snd/Cut 必须真的放进 30px 框）。
			//   为什么必须是"真实字体量"：Create 的 Label 不裁剪、画超出盒子，排版一旦算错在游戏里
			//   只表现为"文字糊出框"，没有任何报错 —— 只有把数字打出来才能与上限（width - 10）逐条对照。
			int modeLabelW = 0;
			for (Component option : signalModeLabels())
				modeLabelW = Math.max(modeLabelW, screen.font.width(option));
			int numLabelW = screen.font.width(String.valueOf(ArmProgramEntry.SIGNAL_MAX_AMOUNT));
			int unitLabelW = 0;
			for (Component unit : screen.labels(WaitUnit.values()))
				unitLabelW = Math.max(unitLabelW, screen.font.width(unit));
			return "OK 任务卡=" + taskCards + " 操作卡=" + entryCards + " 编辑器=" + editors + " 控件=" + widgets
				+ " 槽位=" + screen.menu.slots.size() + " 最宽行=" + rowWidth + " 最大胶囊宽=" + screen.maxChipWidth()
				+ " 第二行=" + (signalRow == null ? "82=30+2+30+2+18" : "FAIL") + " 文案="
				+ (signalWording == null ? "收发+单位" : "FAIL") + " 语言键=" + (langKeys == null ? "缺=0" : "FAIL")
				+ " 实测宽=模式" + modeLabelW + "/" + (SIGNAL_MODE_W - 10) + ",数值" + numLabelW + "/"
				+ (SIGNAL_TICKS_W - 10) + ",单位" + unitLabelW + "/" + (SIGNAL_UNIT_W - 10)
				+ " 会话落点=" + (sessionSeeded ? 1 : 0) + " 空卡进任务页=" + (emptyOnTasks ? 1 : 0) + " 视图="
				+ (viewSeeded ? "记住了" : "FAIL") + " 任务上限="
				+ screen.probedTaskLimit + " 备注框=" + (noteFieldOk ? "OK" : "FAIL") + " 滚动="
				+ (scrollIndep == null && scrollBar == null ? "独立+可拖" : "FAIL") + " 指针="
				+ (pointerIndep == null ? "按列表独立" : "FAIL") + " 热区=" + screen.hits;
		} catch (Throwable t) {
			StringBuilder report = new StringBuilder("FAIL ").append(t);
			for (StackTraceElement frame : t.getStackTrace()) {
				if (frame.getClassName()
					.startsWith("io.github.Ling")) {
					report.append(" @")
						.append(frame.getMethodName())
						.append(':')
						.append(frame.getLineNumber());
					break;
				}
			}
			return report.toString();
		}
	}

	/**
	 * 不变量："加一个属性"就只多一个胶囊。
	 *
	 * <p>踩过的两个坑都表现在同一条：① 加属性的选择框每滚过一项就回调一次，回调里直接
	 * {@code reveal()} —— 滚一遍把沿途所有属性全加了；② 早期 {@code 成功后} 的 reveal 顺手
	 * 写了一个落点，一次点击冒出两个胶囊。用户的原话是"点 ＋ 会冒出一堆内容"。
	 *
	 * <p>做法：往任务末尾借一条空条目，逐个动作、逐个"还没加的"属性在空条目上 reveal，
	 * 看胶囊数是否只 +1。借来的条目最后删掉。
	 */
	@Nullable
	private static String revealInvariant(ArmProgramScreen screen, ArmProgramTask task) {
		task.entries.add(new ArmProgramEntry());
		int index = task.entries.size() - 1;
		Card card = new Card(false, 0, index);
		try {
			for (OperationType op : OperationType.values()) {
				int candidates = 0;
				for (int k = 0;; k++) {
					ArmProgramEntry fresh = new ArmProgramEntry();
					fresh.op = op;
					task.entries.set(index, fresh);
					List<Prop> props = screen.entryProps(fresh);
					if (k >= props.size())
						break;
					Prop candidate = props.get(k);
					if (candidate.show())
						continue; // 已经不是默认值的属性本来就不在"可加"列表里
					candidates++;
					int before = screen.shownProps(card)
						.size();
					candidate.reveal();
					int after = screen.shownProps(card)
						.size();
					if (after > before + 1)
						return op + "/" + candidate.name.getString() + "：加一个属性多出 " + (after - before) + " 个胶囊";
				}
				if (candidates == 0)
					return op + "：没有任何可添加的属性（行尾的 ＋ 会点出空列表）";
			}
		} finally {
			task.entries.remove(index);
		}
		return null;
	}

	/**
	 * 自检：幽灵槽里的两个物品**必须真的能变成** {@code entry.frequency}。
	 *
	 * <p>为什么单独一条：这是本轮之前**完全缺失**的一环 —— 槽画了出来、也能拖物品进去，
	 * 但从来没人把槽里的东西写进频率，于是界面上建出来的「等待信号」{@code frequency == null}，
	 * {@code ProgramRuntime.isSignalSatisfied} 对 null 直接返回 false，那条操作只会一直等（或按「时限」失败）。
	 * 断言 {@link #frequencyFrom} 的三种情形：两个都有 → 一对频率；缺一个 → 不绑定（null）。
	 */
	@Nullable
	private static String frequencyBindingProblem() {
		ItemStack torch = new ItemStack(net.minecraft.world.item.Items.REDSTONE_TORCH);
		ItemStack ingot = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
		Couple<Frequency> bound = frequencyFrom(torch, ingot);
		if (bound == null)
			return "两个槽都有物品却绑不出频率";
		if (bound.getFirst()
			.getStack()
			.getItem() != torch.getItem()
			|| bound.getSecond()
				.getStack()
				.getItem() != ingot.getItem())
			return "绑出来的频率物品对不上（槽 1/2 的顺序错了）";
		if (frequencyFrom(torch, ItemStack.EMPTY) == null || frequencyFrom(ItemStack.EMPTY, ingot) == null)
			return "只填了一个槽就没绑上频率（用户要求：频率可以填空，一格也要能绑）";
		if (frequencyFrom(ItemStack.EMPTY, ItemStack.EMPTY) != null)
			return "两个槽都空却绑成了频率（应当视为未绑定）";
		return null;
	}

	/**
	 * 自检：**备注输入框的接线**（用户第二十七轮定的"128 字上限 + 方案 B 编辑"）。
	 *
	 * <p>为什么只能在客户端验：这两件事纯粹是"界面 ↔ 数据"的转换 —— 数据侧（`clipNote`/`flattenNote`/
	 * `joinNote`）服务端自检已经钉住了，但"框里显示的到底是不是扁平化后的文本""敲一个字之后存回去的到底
	 * 是字面 `\n` 还是真换行"只有真的把界面建起来才看得见。改坏了在游戏里的表现是：多行备注在框里变成
	 * 一个反斜杠加一个 n（看不出是换行），或者保存后原文的换行**变成了两个字面字符**。
	 *
	 * <p>断言走的就是用户那条路径本身：建备注框 → 读框里的文本 → 再往里敲一个字符 → 看条目里变成什么。
	 * 判据是"框里的文本 == {@code flattenNoteForField(备注)}"与"条目 == 原备注 + 敲进去的那个字符"，
	 * 任一条不成立就报红（见 PLAN 决策 77）。
	 */
	@Nullable
	private String noteFieldProblem(int entryIndex) {
		Card card = new Card(false, 0, entryIndex);
		ArmProgramEntry entry = entry(card);
		if (entry == null)
			return "备注框自检取不到第 " + entryIndex + " 条";
		String note = "第一行\n第二行";
		String backup = entry.note;
		EditTarget editingBackup = editing;
		Prop propBackup = editingProp;
		try {
			entry.note = note;
			editing = new EditTarget(false, 0, entryIndex, tr("entry.note")
				.getString());
			editingProp = null;
			init(minecraft, 427, 240);

			int boxes = editBoxCount();
			if (boxes == 0)
				return "备注这一页没建出输入框（属性表里的备注项没接上？）";
			if (boxes > 1)
				return "备注这一页建出了 " + boxes + " 个输入框（只该有备注框一个）—— 焦点/响应器会打架";
			EditBox box = firstEditBox();

			String shown = box.getValue();
			String want = ArmProgramEntry.flattenNoteForField(note);
			if (!shown.equals(want))
				return "备注框显示的不是扁平化后的文本：框里=[" + shown.replace("\n", "⏎") + "] 期望=["
					+ want.replace("\n", "⏎") + "]";
			if (shown.contains("\n"))
				return "备注框里出现了真换行（单行框装不下，会渲染成缺字）";

			// 写回：在框里接着敲一个字符，条目里应当是**真换行**的原文 + 那个字符
			box.setValue(shown + "尾");
			String expectNote = note + "尾";
			if (!expectNote.equals(entry.note))
				return "备注写回没有还原真换行：条目=[" + entry.note.replace("\n", "⏎") + "] 期望=["
					+ expectNote.replace("\n", "⏎") + "]";

			// 超出显示上限的备注（128 个换行存得下、扁平化后是 257 字）不能被从字面 `\n` 中间切开。
			// 这一串是**奇数**长度，硬截到 128 正好落在 `\` 与 `n` 之间 —— 那正是旧写法会留下的孤立反斜杠。
			entry.note = "a" + "\n".repeat(ArmProgramEntry.NOTE_MAX);
			editing = new EditTarget(false, 0, entryIndex, tr("entry.note")
				.getString());
			editingProp = null;
			init(minecraft, 427, 240);
			if (editBoxCount() != 1)
				return "换成超长备注后输入框个数变成了 " + editBoxCount() + "（应当是 1）";
			EditBox box2 = firstEditBox();
			String clipped = box2.getValue();
			if (clipped.length() > ArmProgramEntry.NOTE_MAX)
				return "备注框里的文本超过了上限（" + clipped.length() + " > " + ArmProgramEntry.NOTE_MAX
					+ "）—— 框会被硬截，截点可能落在字面 `\\n` 之间";
			if (clipped.endsWith("\\"))
				return "备注框被从字面 `\\n` 的中间截断了（末尾一个孤立反斜杠，敲一下就会被存进备注）";
			if (!clipped.endsWith("…"))
				return "备注显示不下时没有补省略号（框里=[" + clipped + "]）";
			return null;
		} finally {
			entry.note = backup;
			editing = editingBackup;
			editingProp = propBackup;
		}
	}

	/**
	 * 当前编辑器里的输入框个数。备注那一页只该有**一个**；建出多个说明焦点/响应器会打架，
	 * 所以"多个"要当接线故障报出来，而不是随便取第一个。
	 */
	private int editBoxCount() {
		int n = 0;
		for (GuiEventListener widget : editorWidgets)
			if (widget instanceof EditBox)
				n++;
		return n;
	}

	/** 当前编辑器里的第一个输入框（调用前先用 {@link #editBoxCount()} 确认正好一个）。 */
	private EditBox firstEditBox() {
		for (GuiEventListener widget : editorWidgets)
			if (widget instanceof EditBox b)
				return b;
		return null;
	}

	/**
	 * 自检：编辑器的任务数上限守卫真的接上了吗（第二十四轮审计发现的丢数据路径）。
	 *
	 * <p>为什么必须在界面上实测接线：服务端是<b>整份接受或整份拒绝</b>（只判 {@code isWithinLimits()}），
	 * 所以"编辑器能一路加过配置上限（maxTasks）"＝"关界面时这一场编辑一个字都落不了盘，玩家只看到一行红字"。
	 * 判据 {@link ArmProgram#canAddTask()} 的边界由服务端自检 {@code checkTaskLimit} 钉住；
	 * 这里钉的是<b>入口有没有用它</b>：把程序填到上限，再调真正的入口 {@link #insertTaskAt}，必须
	 * ① 被挡住且给出提示 ② 让出一个位置后能正常加进去（守卫不能把正常路径也挡死）。
	 *
	 * <p>探针会把程序填到上限再原样还原，全程只动 {@code program}/{@code status}；
	 * 不依赖连接（{@code sendGhostSlots} 在标题界面自己会早退）。
	 */
	@Nullable
	private String taskLimitProblem() {
		List<ArmProgramTask> backup = new ArrayList<>(program.tasks);
		try {
			while (program.canAddTask())
				program.tasks.add(new ArmProgramTask());
			probedTaskLimit = program.tasks.size();

			status = null;
			insertTaskAt(0); // 应当被挡
			if (program.tasks.size() != probedTaskLimit)
				return "任务数到上限后还能继续加（第 " + (probedTaskLimit + 1)
					+ " 个没被挡住）—— 关界面时整份程序会被服务端拒收，这一场编辑全丢";
			if (status == null || status.isEmpty())
				return "任务数到上限时被挡住了，但没有给出任何提示（玩家会以为按钮坏了）";

			// 让出一个位置：正常路径必须还能加（否则就是把守卫写死了）
			program.tasks.remove(0);
			int allowed = program.tasks.size() + 1;
			insertTaskAt(0);
			if (program.tasks.size() != allowed)
				return "任务数没到上限却加不进去了（守卫写反了？）";
			return null;
		} finally {
			program.tasks.clear();
			program.tasks.addAll(backup);
			editing = null;
			status = null;
		}
	}

	/**
	 * 自检：**编辑「等待信号」的某个属性之后按「完成」，绑定的频率必须还在**（第二十四轮审计）。
	 *
	 * <p>为什么会被抹掉：编辑属性时三个幽灵槽是**主动推空**的（`targetSlotsActive = 0`，槽 1/2 既不激活也不画），
	 * 而 `closeEditor(true)` 里频率那一段原来只守了 `!editing.task`、没守 `editingProperty()`
	 *（旁边图标那一段守了）—— 于是 `frequencyFrom(空, 空)` 返回 null，`entry.frequency` 被写成 null
	 * 并随 `dirty` 落盘，那条之后永远等不到信号。
	 *
	 * <p>断言方式就是那条用户路径：给一条「等待信号」绑上频率 → 进入"编辑属性"态 → `closeEditor(true)`
	 * → 频率必须还在。修复前这条必然为假。
	 */
	@Nullable
	private String signalFrequencyKeepProblem() {
		if (program.tasks.isEmpty())
			return "自检前提不成立：烟雾程序里没有任务";
		ArmProgramTask task = program.tasks.get(0);
		int index = -1;
		for (int i = 0; i < task.entries.size(); i++)
			if (task.entries.get(i).op.usesSignalPair()) {
				index = i;
				break;
			}
		if (index < 0)
			return "自检前提不成立：烟雾程序里没有「收发信号」条目";

		ArmProgramEntry signal = task.entries.get(index);
		Couple<Frequency> bound = Couple.create(() -> Frequency.EMPTY);
		bound.set(true, Frequency.of(new ItemStack(net.minecraft.world.item.Items.REDSTONE_TORCH)));
		bound.set(false, Frequency.of(new ItemStack(net.minecraft.world.item.Items.LEVER)));
		signal.frequency = bound;

		// 编辑的是"成功后"这个**属性**（不是条目本体）：这正是用户会走的那条路
		editing = new EditTarget(false, 0, index, "on_success");
		closeEditor(true);

		Couple<Frequency> after = task.entries.get(index).frequency;
		if (after == null)
			return "编辑「等待信号」的属性后按「完成」，绑定的频率被清空了（这条之后永远等不到信号）";
		// 断言"原样不变"，而不只是"不为 null"：即使槽里恰好有东西，被改写成槽里的物品同样是错的
		if (!ItemStack.isSameItemSameComponents(after.getFirst()
			.getStack(), bound.getFirst()
				.getStack())
			|| !ItemStack.isSameItemSameComponents(after.getSecond()
				.getStack(), bound.getSecond()
					.getStack()))
			return "编辑「等待信号」的属性后按「完成」，绑定的频率被改成了幽灵槽里的东西（应该原样保留）";
		return null;
	}

	/**
	 * 自检：撤销/重做在**栈满**之后仍然成对可逆（第二十四轮审计）。
	 *
	 * <p>起因：`redo()` 修剪撤销栈写的是 `removeFirst()`，而 `Deque.push` = `addFirst` —— 删掉的正是
	 * 刚压进去的那份（另外两处 `undo()`/`pushUndo()` 都是 `removeLast()`）。**复核结论：这条现在不可达** ——
	 * `pushUndo()` 自己就把栈夹在 50 以内，`undo()` 又会先弹掉一个，所以 `redo()` 那次 push 之后永远到不了 51。
	 * 修它只是消除陷阱（行为无变化），所以这里断的是**真正可达的不变量**：
	 * 满栈状态下「撤销 → 重做 → 撤销」必须回到同一份状态。
	 */
	@Nullable
	private String undoRedoProblem() {
		CompoundTag backup = program.save(registries());
		try {
			ArmProgramTask first = program.tasks.get(0);
			for (int i = 0; i < 60; i++) {
				first.name = "hist" + i;
				pushUndo(); // 栈顶 = 改动之前的状态
				first.name = "hist" + i + "x"; // 真改一下，撤销才有东西可回
			}
			if (undoStack.size() != 50)
				return "自检前提不成立：撤销栈没有被夹到 50（实际 " + undoStack.size() + "）";

			undo();
			String before = program.tasks.get(0).name;
			redo();
			if (before.equals(program.tasks.get(0).name))
				return "重做没有回到改动后的状态（撤销/重做不成对）";
			undo();
			String back = program.tasks.get(0).name;
			if (!before.equals(back))
				return "重做之后撤不回来（期望「" + before + "」，实际「" + back + "」）";
			return null;
		} finally {
			program = ArmProgram.load(backup, registries());
			undoStack.clear();
			redoStack.clear();
			editing = null;
		}
	}

	/**
	 * 自检：**每个列表的纵向滚动必须独立**（用户第二十七轮续十三实测）。
	 *
	 * <p>用户原话：<i>"操作卡的任务部分和操作部分的位置记录是一样的，也就是如果我编辑任务的时候在大概序号 15
	 * 的位置，那么我切换到选择任务的时候也是一样的位置，尽管这个位置没有任何操作，同样的，编辑其他任务的操作
	 * 的时候也会把位置移动到切换前任务 UI 的位置。你应该让它们独立出来，而不是共用一个位置信息。"</i>
	 *
	 * <p>这条断言**故意走真实入口的顺序**（决策 68 的教训：只测叶子方法等于没测）：
	 * 先滚任务页 → 切到操作页 → 读到的必须是操作页**自己的**位置；反向同理；不同任务之间也要各记各的。
	 * 顺带钉住那个更隐蔽的坑：切页时"归零"若不改 {@code chaseTarget}，下一 tick 就被拉回去。
	 */
	private String scrollIndependenceProblem() {
		Page savedPage = page;
		int savedTask = currentTask;
		try {
			// ⚠️ 这里**绕过 {@link #setListScroll} 的夹取**：自检程序里任务页只有 1 张卡（max = 0），
			//   夹取会把任何值变成 0，那这条断言就永远测不出"位置记在哪个 key 上"。
			//   夹取本身是另一件事（内容变短要收回），由 clampListScroll 负责。
			page = Page.TASKS;
			currentTask = 0;
			listScroll().setValue(40);
			listScroll().chase(40, 0.5f, Chaser.EXP);
			page = Page.ENTRIES;
			currentTask = 0;
			if (listScroll().getChaseTarget() > 0.5f)
				return "任务页的滚动漏进了操作页（操作页起始位置 = " + listScroll().getChaseTarget() + "）";
			// 操作页滚到 60，回任务页：刚才那 40 必须还在（这正是用户报的"共用一份位置"的反面）
			listScroll().setValue(60);
			listScroll().chase(60, 0.5f, Chaser.EXP);
			page = Page.TASKS;
			if (Math.abs(listScroll().getChaseTarget() - 40) > 0.5f)
				return "任务页记不住自己的滚动位置（期望 40，实际 " + listScroll().getChaseTarget() + "）";
			// 换一个任务的操作页：也要各记各的（key 不同 ⇒ 不该拿到上一个任务那份 60）
			page = Page.ENTRIES;
			currentTask = 0;
			float task0 = listScroll().getChaseTarget();
			currentTask = 1;
			float task1 = listScroll().getChaseTarget();
			if (Math.abs(task1 - task0) <= 0.5f)
				return "两个任务的操作页共用了一个滚动位置（都读到 " + task0 + "）";
			return null;
		} finally {
			page = savedPage;
			currentTask = savedTask;
		}
	}

	/**
	 * 自检：**指针也要按列表分开记**，而且任务页第一次打开时指针要落在**正在编辑的那个任务**上。
	 *
	 * <p>用户实测（2026-10-14）：<i>"此处的UI左侧的指针不会保留原本的位置（关闭UI后再次打开并且切换到任务界面的
	 * 结果，尽管它指着任务1但是实际上是任务2。另外操作界面的指针也是，切换UI之后会回到第一个）。"</i>
	 *
	 * <p>走 {@link #switchPage}（真实的切换状态机，去掉发包那一半）：只往 {@code pointers} 里塞两个数再读出来
	 * 等于没测（决策 68）。
	 */
	private String pointerIndependenceProblem() {
		Page savedPage = page;
		int savedTask = currentTask;
		int savedPointer = pointerIndex;
		Map<String, Integer> savedPointers = new HashMap<>(pointers);
		try {
			if (program.tasks.isEmpty())
				return "自检前提不成立：程序里一条任务都没有";

			// ① 操作页把指针放到第 3 行 → 切到任务页：指针必须落在**当前任务**上（不是从 0 开始）
			page = Page.ENTRIES;
			currentTask = 0;
			pointers.clear();
			pointerIndex = 3;
			if (!switchPage(Page.TASKS))
				return "从操作页切不到任务页";
			int expectTasks = Mth.clamp(currentTask, 0, program.tasks.size() - 1);
			if (pointerIndex != expectTasks)
				return "切到任务页时指针没有落在当前任务上（指针=" + pointerIndex + "，当前任务=" + currentTask + "）";
			// ② 切回操作页：刚才那第 3 行必须还在（用户报的"切换UI之后回到第一个"的反面）
			if (!switchPage(Page.ENTRIES))
				return "从任务页切不回操作页";
			if (pointerIndex != 3)
				return "切回来时操作页的指针没记住（期望 3，实际 " + pointerIndex + "）";

			// ③ 任务页自己也要记住（要有 ≥2 个任务才有意义：1 个任务时"记住的"和"默认的"是同一个数）
			if (program.tasks.size() >= 2) {
				page = Page.TASKS;
				currentTask = 0;
				pointers.clear();
				pointerIndex = 1; // 在任务页把指针挪到第 2 行
				if (!switchPage(Page.ENTRIES))
					return "任务页切到操作页失败";
				// 进操作页会把"指着的任务"变成当前任务；拨回任务 0，好让**默认值(0)** 与**记住的值(1)** 不同，
				// 否则这条断言在"指针没记住、只是落回了默认值"时也会绿。
				currentTask = 0;
				if (!switchPage(Page.TASKS))
					return "操作页切回任务页失败";
				if (pointerIndex != 1)
					return "任务页的指针没记住（期望 1，实际 " + pointerIndex + "）";
			}
			return null;
		} finally {
			page = savedPage;
			currentTask = savedTask;
			pointerIndex = savedPointer;
			pointers.clear();
			pointers.putAll(savedPointers);
		}
	}

	/**
	 * 自检：右侧滑块**拖得动**，而且拖到底 = 滚到底。
	 *
	 * <p>走的是真实入口（{@code mouseClicked} → {@code mouseDragged} → {@code mouseReleased}），
	 * 而不是直接调 {@code setListScroll} —— 用户报的"想要一个能拖的滑动条"里，"能拖"这两个字
	 * 全在鼠标事件那条链上，只测 setter 等于没测。
	 */
	private String scrollBarProblem() {
		Page savedPage = page;
		try {
			page = Page.ENTRIES;
			currentTask = 0;
			float max = maxListScroll();
			if (max <= 0)
				return "自检前提不成立：操作页内容装得下（max=" + max + "），滑块不会出现";
			if (barThumbHeight() < BAR_MIN_THUMB || barThumbHeight() > LIST_VIEW_H)
				return "滑块高度不在 [MIN, 视高] 内：" + barThumbHeight();
			// ★ 滑块必须画在**面板内侧**、且不压到卡片右侧那对 ↑↓ 箭头
			//  （用户第二十七轮续十四："滑块在 UI 外面，可以把它放在 UI 里面贴着右边"）。
			//   面板的右内侧边线在局部 236（与内容窗口右沿同一处），卡片 + ↑↓ 箭头到 207 为止。
			if (BAR_X <= 207 || BAR_X + BAR_W > 236)
				return "滚动条不在面板内侧（x=" + BAR_X + ".." + (BAR_X + BAR_W)
					+ "；合法区间 208..236，再往右就出面板、再往左压住 ↑↓ 箭头）";
			// ⚠️ 落点与拖点**必须不同**：如果两处取同一个 y，那么"按下时的那次跳转"就已经把位置放到位了，
			//   拖拽那条线即使完全没接上也会绿 —— 这正是"只测叶子方法"的老毛病（决策 68）。
			double bx = leftPos + BAR_X + 1;
			mouseClicked(bx, topPos + 16, 0); // 按在滑块上（0 位置）
			if (!draggingBar)
				return "点滚动条没有进入拖拽状态";
			mouseDragged(bx, topPos + 188, 0, 0, 0); // 拖到底 —— 只有拖拽这条线接通了才会到 max
			mouseReleased(bx, topPos + 188, 0);
			if (draggingBar)
				return "松开鼠标后仍然在拖拽";
			float bottom = listScroll().getChaseTarget();
			if (Math.abs(bottom - max) > 1f)
				return "拖到轨道底端没有滚到底（期望 " + max + "，实际 " + bottom + "）";
			// 反向：拖回顶端 → 0
			mouseClicked(bx, topPos + 188, 0);
			mouseDragged(bx, topPos + 16, 0, 0, 0);
			mouseReleased(bx, topPos + 16, 0);
			float top = listScroll().getChaseTarget();
			if (top > 1f)
				return "拖到轨道顶端没有回到 0（实际 " + top + "）";
			return null;
		} finally {
			page = savedPage;
		}
	}

	/**
	 * 自检：编辑条目时，**第二行的控件不许压住幽灵物品槽**。
	 *
	 * <p>用户实测（第十九轮）："滚动选择框的左边把幽灵物品槽位占用了，也因此我无法确认你是否设置正确了，
	 * 一个频道最多可以放两个物品，你准备了两个幽灵物品槽吗" —— 槽确实是两个
	 * （菜单 {@code 54 + 20 * i}，即 GUI 53..71 / 73..91），但「等待信号」那一行原来从 77 起，正好压住第二个。
	 * 这里断言两件事：① 需要两个槽的操作必须把两个槽都激活；② 第二行所有控件的左沿必须在两个槽右侧之外。
	 */
	@Nullable
	private String checkGhostSlotClearance(ArmProgramEntry entry) {
		// 期望的可用槽数：编辑属性 = 0；条目/任务本体 = 1（图标）；「收发信号」本体 = 3（图标 + 一对频率）
		int expect = editingProperty() ? 0 : entry.op.hasSignalMode() ? 3 : 1;
		if (menu.targetSlotsActive != expect)
			return "幽灵槽激活数应当是 " + expect + "，实际 " + menu.targetSlotsActive;
		// 只查**激活的**槽：未激活的槽既不画也点不到（比如非「收发信号」时第二个槽根本不存在），
		// 而且必须是**二维**相交才算压住 —— 编辑器底部那两颗按钮（完成/删除）x 很小但 y 在 190+，
		// 第一版按"x < 91"一刀切就把它们误报了。
		for (GuiEventListener widget : editorWidgets) {
			if (!(widget instanceof AbstractWidget w) || !w.visible)
				continue;
			for (int i = 0; i < menu.targetSlotsActive; i++) {
				int slotX = leftPos + 53 + 20 * i;
				int slotY = topPos + 87;
				boolean overlaps = w.getX() < slotX + 18 && w.getX() + w.getWidth() > slotX
					&& w.getY() < slotY + 18 && w.getY() + w.getHeight() > slotY;
				if (overlaps)
					return "控件压在幽灵槽 " + i + " 上：控件 GUI x=" + (w.getX() - leftPos) + ".."
						+ (w.getX() + w.getWidth() - leftPos) + "，槽位 " + (53 + 20 * i) + ".." + (71 + 20 * i);
			}
		}
		return null;
	}

	/**
	 * 属性表 / 基础内容必须跟动作走（用户实测反馈："等待不该有超时，也不该有时限"、
	 * "选了等待还让我填坐标"、"朝向应该放第二行"、"所有动作都能加完成后跳转"）。
	 *
	 * <p>正面断言：① **每个动作**都有「成功后」；② 只有有失败判定的动作（取出/放入/卸货/组装/等红石）
	 * 才有「超时后 + 时限」；③ 前摇已经删掉（任何动作都不该再有它）；④ 朝向**不在任何卡片的胶囊里**；
	 * ⑤ 第二行的 DATA_AREA 盒子数：位置类 1（坐标）、左键/右键 2（坐标+朝向）、等红石 2、等待 3。
	 */
	@Nullable
	private String checkPropTable(ArmProgramTask task) {
		String success = tr("entry.on_success").getString();
		String failure = tr("entry.on_failure").getString();
		String faceName = tr("entry.face").getString();
		boolean sawWait = false;
		boolean sawFace = false;
		boolean sawRetryWindow = false;
		boolean sawHardFail = false;
		for (int e = 0; e < task.entries.size(); e++) {
			ArmProgramEntry entry = task.entries.get(e);
			List<String> names = entryProps(entry).stream()
				.map(p -> p.name.getString())
				.toList();
			boolean terminal = entry.op == OperationType.BREAK || entry.op == OperationType.THROW;
			if (terminal) {
				// 终结操作：执行完就不在这个任务里了，任何出口都没有意义
				if (names.contains(success))
					return entry.op + " 是终结操作，不该有「成功后」";
			} else if (!names.contains(success))
				return entry.op + " 缺少「成功后」（所有动作都应该能加完成后跳转）";
			// 顺序也要守：成功后 永远排第一（终结操作除外）
			if (!terminal && !names.get(0)
				.equals(success))
				return entry.op + " 的第一个属性应当是「成功后」，实际是「" + names.get(0) + "」";
			if (names.contains(faceName))
				return entry.op + " 的「朝向」不该是胶囊属性（应在编辑器第二行）";
			// 按**条目**判（§7.16b 一.6）：同一条 SIGNAL，收有「失败后」、发没有
			boolean retryWindow = hasFailure(entry);
			boolean hardFail = hasHardFailure(entry);
			if (retryWindow) {
				sawRetryWindow = true;
				if (!names.contains(failure))
					return entry.op + " 会无限等，缺少「失败后」";
			} else if (hardFail) {
				sawHardFail = true;
				if (!names.contains(failure))
					return entry.op + " 有硬失败，缺少「失败后」";
			} else if (names.contains(failure))
				return entry.op + " 既不会无限等也没有硬失败，不该有「失败后」";
			if (entry.op == OperationType.WAIT)
				sawWait = true;
			if (needsFace(entry.op))
				sawFace = true;
			// 第二行的 DATA_AREA 盒子数（§19 坐标统一之后："坐标空间"选择框已删，坐标只剩一个框）：
			// 位置类 = 坐标 = 1；需要朝向的再多一个朝向 = 2；等待 = 时长 + 单位 = 2；
			// 收发信号 = 模式 +（只有发才有）数值 + 单位 ⇒ **接收 1 / 发送 3**（两个频率格是**菜单幽灵槽**，不算盒子）；
			// 调用 = 目标 = 1；抛错 = 信息 = 1；退出 = 0（终结操作，第二行不需要任何东西）
			editingProp = null;
			editing = new EditTarget(false, 0, e, null);
			init(minecraft, 427, 240);
			int boxes = editorBoxes.size();
			editing = null;
			int expected = switch (entry.op) {
				case WAIT -> 2;
				// 收发信号：模式 +（只有发才有）数值 + 单位 ⇒ 接收 1 / 发送 3（用户反馈第二轮加了单位框）
				case SIGNAL -> entry.isSignalSend() ? 3 : 1;
				case WAIT_REDSTONE, CALL, THROW -> 1;
				case BREAK -> 0;
				default -> needsFace(entry.op) ? 2 : 1;
			};
			if (boxes != expected)
				return entry.op + " 的基础内容应当是 " + expected + " 个控件，实际 " + boxes;
			// 「失败后」的编辑器：会重试的 4 个控件（去向/落点/时限/单位），只有硬失败的 2 个
			for (Prop prop : entryProps(entry)) {
				if (!prop.name.getString()
					.equals(failure))
					continue;
				editingProp = prop;
				editing = new EditTarget(false, 0, e, prop.name.getString());
				init(minecraft, 427, 240);
				int failBoxes = editorBoxes.size();
				editing = null;
				int want = retryWindow ? 4 : 2;
				if (failBoxes != want)
					return entry.op + " 的「失败后」应当是 " + want + " 个控件，实际 " + failBoxes;
			}
		}
		if (!sawWait || !sawFace || !sawRetryWindow || !sawHardFail)
			return "自检数据里缺少 等待/朝向/会无限等/有硬失败 四类动作中的某一类";
		return null;
	}

	// ——— §7.16b 四「客户端」的三条新断言 ———

	/**
	 * 自检 9：收发信号第二行那 **82px** 的算术（用户反馈第二轮：模式 30 + 2 + 数值 30 + 2 + 单位 18）。
	 *
	 * <p>为什么必须自动验：第二行可用宽度只有 {@code FIELD_W - 38 = 82}（左侧三个幽灵槽压到 GUI 111），
	 * 而每个控件里的文字上限是 {@code width - 10}（汉字约 9px/字），**Create 的 Label 不裁剪、画超出盒子**
	 * —— 排版一旦算错，游戏里只表现为"文字糊出框"，没有任何报错。
	 *
	 * <p>断言五件事：① 三段的宽度**加起来 ≤ 82** 且右沿 ≤ 197；② 每个控件右沿 ≤ 197；
	 * ③ 模式框的**每个选项**都放得下；④ 数值框的最大值与单位框的 t/s/m 都放得下；
	 * ⑤ 发模式恰好 3 个 DATA_AREA 盒子（模式 + 数值 + 单位）、接收模式 1 个。
	 */
	@Nullable
	private String signalRowProblem() {
		if (program.tasks.isEmpty())
			return "自检前提不成立：烟雾程序里没有任务";
		// ① 三个常量本身的账（这条不看控件，所以把常量改错的立刻就能抓到）
		int span = SIGNAL_MODE_W + 2 + SIGNAL_TICKS_W + 2 + SIGNAL_UNIT_W;
		if (span > SIGNAL_ROW_W)
			return "第二行的常量加起来超宽（" + SIGNAL_MODE_W + "+2+" + SIGNAL_TICKS_W + "+2+" + SIGNAL_UNIT_W
				+ "=" + span + " > " + SIGNAL_ROW_W + "）";
		if (SIGNAL_UNIT_X + SIGNAL_UNIT_W > SIGNAL_ROW_END)
			return "第二行的右沿越界（" + (SIGNAL_UNIT_X + SIGNAL_UNIT_W) + " > " + SIGNAL_ROW_END + "）";
		int numWidth = font.width(String.valueOf(ArmProgramEntry.SIGNAL_MAX_AMOUNT));
		if (numWidth > SIGNAL_TICKS_W - 10)
			return "数值框的最大值放不下（" + numWidth + " > " + (SIGNAL_TICKS_W - 10) + "）";
		List<Component> unitLabels = labels(WaitUnit.values());
		for (Component unit : unitLabels) {
			int w = font.width(unit);
			if (w > SIGNAL_UNIT_W - 10)
				return "单位选项放不下（" + unit.getString() + " " + w + " > " + (SIGNAL_UNIT_W - 10) + "）";
		}
		ArmProgramTask task = program.tasks.get(0);
		ArmProgramEntry probe = new ArmProgramEntry();
		probe.op = OperationType.SIGNAL;
		task.entries.add(probe);
		int index = task.entries.size() - 1;
		try {
			for (ArmProgramEntry.SignalMode mode : ArmProgramEntry.SignalMode.values()) {
				probe.signalMode = mode;
				editingProp = null;
				editing = new EditTarget(false, 0, index, null);
				init(minecraft, 427, 240);
				// ② 第二行的盒子右沿必须 ≤ 197（SIGNAL_ROW_END）= 82 = 30 + 2 + 30 + 2 + 18
				for (int[] box : editorBoxes) {
					int right = box[0] - leftPos + box[2];
					if (right > SIGNAL_ROW_END)
						return "控件越界（右沿 " + right + " > " + SIGNAL_ROW_END + "，模式=" + mode + "）";
				}
				// ③ 第二行的**值控件**都不许出面板（≥200 就会盖到右边的"+"列上）。
				// ⚠️ 必须只过滤"值控件"（滚动框 / 输入框）：编辑器左下那颗垃圾桶（卡局部 11..29）与
				//   右下那颗确认键（卡局部 224..242）**本来就在面板外**（上游排版就是这样），
				//   第一版拿 editorWidgets 全表去量 ⇒ 这条断言在任何语言、任何模式下都报
				//   「控件出面板（右沿 242，模式=RECEIVE）」（242 = 56+168+18 = 确认键右沿），
				//   把整条客户端自检一起染红（实测：en_us 客户端 2026-10-11）。
				for (GuiEventListener listener : editorWidgets) {
					if (!(listener instanceof ScrollInput) && !(listener instanceof EditBox))
						continue;
					AbstractWidget widget = (AbstractWidget) listener;
					if (!widget.visible)
						continue;
					int right = widget.getX() - leftPos + widget.getWidth();
					if (right > 200)
						return "控件出面板（右沿 " + right + "，模式=" + mode + "）";
				}
				for (Component option : signalModeLabels()) {
					int w = font.width(option);
					if (w > SIGNAL_MODE_W - 10)
						return "模式选项放不下（" + option.getString() + " " + w + " > " + (SIGNAL_MODE_W - 10) + "）";
				}
				// ⑤ 接收 = 1 个盒子（只有模式）；发送 = 3 个（模式 + 数值 + 单位）
				int want = mode == ArmProgramEntry.SignalMode.RECEIVE ? 1 : 3;
				if (editorBoxes.size() != want)
					return "模式 " + mode + " 的第二行应当是 " + want + " 个盒子，实际 " + editorBoxes.size();
			}
		} finally {
			editing = null;
			editingProp = null;
			task.entries.remove(index);
		}
		return null;
	}

	/**
	 * 给一个滚轮选择框喂**一次真实的滚轮事件**（照 {@link #checkWheel} 的手法）。
	 *
	 * <p>为什么不能用 {@code setState()}：Create 的 {@code ScrollInput.setState()} 只改自己那份 state，
	 * <b>不触发回调</b>（`onChanged()` 只在 `mouseScrolled` 里被调，Create 源码 `ScrollInput.java:147-155`），
	 * 而"选中某一项之后要发生什么"全在回调里。自检凡是"切一下选择框、再看数据变了没有"的地方，
	 * 都必须走这条路，否则断言测的是空气。
	 *
	 * @param forward 语义上的"向前滚一格"；{@code SelectionScrollInput} 构造时调了 {@code inverted()}，
	 *                所以它的 `pScrollY` 方向是反的 —— 这里两个方向都试，以 state 真的变了为准。
	 * @return 这一次滚轮是否真的改变了 state
	 */
	private boolean scrollOnce(ScrollInput input, boolean forward) {
		double x = input.getX() + input.getWidth() / 2.0;
		double y = input.getY() + input.getHeight() / 2.0;
		int before = input.getState();
		mouseScrolled(x, y, 0, forward ? -1 : 1);
		if (input.getState() == before)
			mouseScrolled(x, y, 0, forward ? 1 : -1);
		return input.getState() != before;
	}

	/**
	 * 自检 10：三态短语 + 尾巴（时间 / 老卡的"等断电"）+ 单位换算 +「切到发送时默认 1t」。
	 *
	 * <p>用户反馈第二轮的三条定稿：① 短语随模式变（接收信号 / 发送信号 / 中断信号），极性不再单独写；
	 * ② 持续带单位，{@code t=1 / s=20 / m=1200}（**真的换算**，不是只显示）；③ 发 + 持续 0 = 一直保持
	 * ⇒ 一个字都不写；接收 + 老卡 {@code redpow=false} ⇒ 仍然写"等断电"（不能静默隐瞒）。
	 *
	 * <p>文案只有一个来源（{@link ArmEntryText}），所以这条同时钉住了卡片标题芯片与护目镜 ——
	 * 复制粘贴漏改（把发送那支写成接收那支）在游戏里表现为"发送信号的卡片上写着接收信号"。
	 */
	@Nullable
	private String signalWordingProblem() {
		ArmProgramEntry recv = new ArmProgramEntry();
		recv.op = OperationType.SIGNAL;
		ArmProgramEntry send = new ArmProgramEntry();
		send.op = OperationType.SIGNAL;
		send.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		ArmProgramEntry cut = new ArmProgramEntry();
		cut.op = OperationType.SIGNAL;
		cut.signalMode = ArmProgramEntry.SignalMode.SEND_OFF;
		String recvHead = ArmEntryText.signalHeadText(recv).getString();
		String sendHead = ArmEntryText.signalHeadText(send).getString();
		String cutHead = ArmEntryText.signalHeadText(cut).getString();
		if (recvHead.equals(sendHead) || sendHead.equals(cutHead) || recvHead.equals(cutHead))
			return "三种模式的短语没有区分开（" + recvHead + " / " + sendHead + " / " + cutHead + "）";

		// ① 发 + 持续 0 = 一直保持 ⇒ **一个字都不写**；>0 ⇒ 写"数值 + 单位"
		send.waitAmount = 0;
		String holdTail = ArmEntryText.signalTailText(send).getString();
		if (!holdTail.isEmpty())
			return "持续 0（一直保持）不该写时间，实际写了「" + holdTail + "」";
		send.waitAmount = 1;
		send.waitUnit = WaitUnit.TICK;
		String tail = ArmEntryText.signalTailText(send).getString();
		if (!"1t".equals(tail))
			return "持续 1t 的尾巴应当是「1t」，实际「" + tail + "」";
		String sendBase = ArmEntryText.base(send).getString();
		if (!sendBase.contains(sendHead) || !sendBase.contains(tail))
			return "发送信号的第一行里缺短语或时间（" + sendBase + " vs " + sendHead + " + " + tail + "）";
		if (sendBase.contains(recvHead) || sendBase.contains(cutHead))
			return "发送信号的第一行写成了别的模式（复制粘贴漏改）：" + sendBase;
		for (String failKey : new String[] { "chip.fail_stop", "chip.fail_retry_forever", "chip.fail_jump" }) {
			String failWord = tr(failKey).getString();
			if (sendBase.contains(failWord))
				return "发信号的第一行里混进了失败文案（" + failKey + "）：" + sendBase;
		}

		// ② 单位**真的换算**（用户反馈第二轮："不要再无视单位"）
		send.waitAmount = 1;
		send.waitUnit = WaitUnit.SECOND;
		if (send.signalTicks() != 20)
			return "持续 1s 应当是 20 tick，实际 " + send.signalTicks() + "（单位被无视了）";
		String oneSecond = ArmEntryText.signalTailText(send).getString();
		if (!"1s".equals(oneSecond))
			return "持续 1s 的尾巴应当是「1s」，实际「" + oneSecond + "」";
		send.waitUnit = WaitUnit.MINUTE;
		if (send.signalTicks() != 1200)
			return "持续 1m 应当是 1200 tick，实际 " + send.signalTicks() + "（单位被无视了）";
		send.waitAmount = 0;
		if (send.signalTicks() != 0)
			return "持续 0（任何单位）都该当作「保持」，实际 " + send.signalTicks() + " tick";
		// ③ 短语与尾巴都要**装得进芯片**（renderSignalChip 的文字不裁剪，超出就是"糊出卡片"）
		ArmProgramEntry recvOff = new ArmProgramEntry();
		recvOff.op = OperationType.SIGNAL;
		recvOff.waitForPowered = false; // 老卡：界面上不再给这个开关，但必须照样写成"等断电"
		ArmProgramEntry sendLong = new ArmProgramEntry();
		sendLong.op = OperationType.SIGNAL;
		sendLong.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		sendLong.waitAmount = ArmProgramEntry.SIGNAL_MAX_AMOUNT;
		sendLong.waitUnit = WaitUnit.MINUTE; // 最宽的尾巴（120m）
		for (ArmProgramEntry chip : new ArmProgramEntry[] { recv, send, cut, recvOff, sendLong }) {
			int need = signalChipTextEnd(chip) + 2;
			if (need > SIGNAL_CHIP_MAX)
				return "标题芯片的「" + ArmEntryText.signalHeadText(chip)
					.getString() + "」文字会画到 " + need + " > " + SIGNAL_CHIP_MAX + "（文字不裁剪，会糊出卡片）";
			if (signalChipWidth(chip) < need)
				return "标题芯片宽 " + signalChipWidth(chip) + " 装不下文字 " + need;
		}
		String offWord = tr("entry.signal_wait_off").getString();
		String recvText = ArmEntryText.base(recvOff).getString();
		if (!recvText.contains(recvHead) || !recvText.contains(offWord))
			return "老卡（redpow=false）的接收信号没有写成「" + recvHead + " … " + offWord + "」：" + recvText;

		// 陷阱：waitt 的老默认是 20（不是 1）—— 从收切到发时必须是 1gt（用户："1gt 足够了"）
		ArmProgramTask task = program.tasks.isEmpty() ? null : program.tasks.get(0);
		if (task == null)
			return "自检前提不成立：烟雾程序里没有任务";
		ArmProgramEntry probe = new ArmProgramEntry();
		probe.op = OperationType.SIGNAL;
		probe.waitAmount = 20; // 从没被人改过的老默认（RECEIVE 下 waitt 走 WAIT 的单位，这里是 TICK）
		task.entries.add(probe);
		int index = task.entries.size() - 1;
		try {
			editingProp = null;
			editing = new EditTarget(false, 0, index, null);
			init(minecraft, 427, 240);
			SelectionScrollInput modePicker = null;
			for (GuiEventListener listener : editorWidgets)
				// ⚠️ 两个坑叠在一起，必须同时避开：
				// ① 第一行那个**操作**选择框也是 SelectionScrollInput（GUI x=56..199）——不按 x 分辨就会抓错
				//    控件，于是"切到发送"实际把**操作**换成了别的，断言读到的是"已经不是 SIGNAL"的 0；
				// ② **不能用 `setState()` 切模式**：Create 的 `ScrollInput.setState()` 只改自己的 state、
				//    **不触发回调**（`onChanged()` 只在 `mouseScrolled` 里被调，见 Create 源码
				//    `ScrollInput.java:147-155`），而"切模式"的全部副作用（模式 / 电平 / waitt→1gt）
				//    都在回调里 ⇒ 用 setState 写这条断言等于一次都没切过（实测报"实际 0"）。
				//    所以下面一律喂**真实滚轮事件**（与 checkWheel 同一手法）。
				if (listener instanceof SelectionScrollInput input && input.getX() - leftPos == SIGNAL_MODE_X
					&& modePicker == null)
					modePicker = input;
			if (modePicker == null)
				return "编辑器里找不到模式选择框";
			if (!scrollOnce(modePicker, true))
				return "模式选择框滚不动（喂了真实滚轮，state 没变）";
			if (modePicker.getState() != ArmProgramEntry.SignalMode.SEND_ON.ordinal())
				return "模式选择框没有滚到「发送」（state=" + modePicker.getState() + "）";
			int ticks = probe.signalTicks();
			if (ticks != 1)
				return "切到发送模式后持续不是 1gt（实际 " + ticks + "）—— waitt 的默认 20 被漏改了";
			// ★ 电平必须跟着模式走：运行时读的是 waitForPowered，而它在 RECEIVE 下的默认是 true ——
			//   不同步的话「中断」会发 15（发的是通电），而界面上写的是"中断信号"。
			if (!scrollOnce(modePicker, true)
				|| modePicker.getState() != ArmProgramEntry.SignalMode.SEND_OFF.ordinal())
				return "模式选择框没有滚到「中断」（state=" + modePicker.getState() + "）";
			if (probe.waitForPowered)
				return "选了「中断」但电平字段还是 true（那条会发出 15 = 通电）";
			if (!scrollOnce(modePicker, false)
				|| modePicker.getState() != ArmProgramEntry.SignalMode.SEND_ON.ordinal())
				return "模式选择框没有滚回「发送」（state=" + modePicker.getState() + "）";
			if (!probe.waitForPowered)
				return "选了「发送」但电平字段是 false（那条会发出 0 = 断电）";
		} finally {
			editing = null;
			editingProp = null;
			task.entries.remove(index);
		}
		return null;
	}

	/** 语言文件路径（数据包命名空间下的 lang），两边键集必须完全一致。 */
	private static final String LANG_DIR = "lang/";
	/**
	 * 本轮「收发信号」用到的**全部**键（枚举名由 {@code opt.<小写>} 派生，与
	 * {@link ArmEntryText#opName} / {@code CardEditPacket} 同一惯例 —— 枚举一改名这些键就查不到）。
	 *
	 * <p>用户反馈第二轮：模式短名（{@code opt.signal_*}）、标题短语（{@code chip.signal_*}）、
	 * 尾巴（{@code entry.signal_wait_off}）。已经不用的 {@code chip.signal_hold/pulse} 与
	 * {@code entry.signal_wait_on} 已从两个语言文件里**删掉**（极性含进短语之后它们是死键）。
	 */
	private static final String[] SIGNAL_LANG_KEYS = {
		"opt.signal", "opt.signal_receive", "opt.signal_send_on", "opt.signal_send_off",
		"chip.signal_recv", "chip.signal_send", "chip.signal_cut",
		"hint.entry_signal_mode", "hint.entry_signal_ticks",
		"entry.signal_mode", "entry.signal_wait_off", "entry.wait_unit"
	};

	/**
	 * 脚本（§12 的 P1）用到的**全部**键。
	 *
	 * <p>为什么值得单独列一张表：脚本的三个命令的反馈是**热键映射的字符串**（{@code code.check_failed} 之类），
	 * 键名打错的表现是"聊天栏显示原始 key"——编译、运行都不报错，而玩家一眼就看见。
	 * 除命令反馈外还有 §12.3b 那两个新备注的悬停标题（现在挂在标题芯片上）。
	 */
	private static final String[] SCRIPT_LANG_KEYS = {
		"need_player", "bad_name", "read_failed", "check_ok", "check_failed", "warning",
		"export_need_card", "export_empty", "export_header", "export_written", "export_failed",
		"import_need_card", "import_over_limit", "import_too_large", "import_ok",
		"program_note", "task_note"
	};

	/**
	 * 自检 12：两个语言文件的**键集必须完全一致**，且本轮要用的键都得在（§7.16b 四 断言 12）。
	 *
	 * <p>为什么值得一条断言：`opt.<枚举小写>` 是**派生**出来的键，枚举一改名（`WAIT_SIGNAL → SIGNAL`）
	 * 老键就自动查不到，而界面上只表现为"显示原始 key"—— 编译、运行、自检全都不报错。
	 * 缺键时把 **key 名**打进日志（不是只报个数），不然还得回去猜。
	 */
	@Nullable
	private static String langKeyProblem(Minecraft mc) {
		try {
			Map<String, String> zh = readLang(mc, "zh_cn");
			Map<String, String> en = readLang(mc, "en_us");
			if (zh.isEmpty() || en.isEmpty())
				return "读不到语言文件（zh=" + zh.size() + " en=" + en.size() + "）";
			List<String> onlyZh = new ArrayList<>();
			List<String> onlyEn = new ArrayList<>();
			for (String key : zh.keySet())
				if (!en.containsKey(key))
					onlyZh.add(key);
			for (String key : en.keySet())
				if (!zh.containsKey(key))
					onlyEn.add(key);
			if (!onlyZh.isEmpty() || !onlyEn.isEmpty())
				return "两边键集不一致（只在 zh：" + onlyZh + "；只在 en：" + onlyEn + "）";
			String prefix = "create_sequenced_mechanical_arm.gui.editor.";
			List<String> missing = new ArrayList<>();
			for (String key : SIGNAL_LANG_KEYS) {
				String full = prefix + key;
				if (!zh.containsKey(full))
					missing.add("zh:" + key);
				if (!en.containsKey(full))
					missing.add("en:" + key);
			}
			// 脚本的键挂在**另一个前缀**下：命令反馈在 `create_sequenced_mechanical_arm.code.*`，
			// 两个备注的悬停标题在 `create_sequenced_mechanical_arm.gui.editor.entry.*`
			for (String key : SCRIPT_LANG_KEYS) {
				boolean command = key.startsWith("export") || key.startsWith("import")
					|| key.equals("need_player") || key.equals("bad_name") || key.equals("read_failed")
					|| key.equals("check_ok") || key.equals("check_failed") || key.equals("warning");
				String full = command ? "create_sequenced_mechanical_arm.code." + key : prefix + "entry." + key;
				if (!zh.containsKey(full))
					missing.add("zh:" + key);
				if (!en.containsKey(full))
					missing.add("en:" + key);
			}
			if (!missing.isEmpty())
				return "缺键 " + missing;
			if (zh.containsKey(prefix + "opt.wait_signal") || en.containsKey(prefix + "opt.wait_signal"))
				return "残留死键 opt.wait_signal（枚举改名后它再也查不到）";
			return null;
		} catch (Throwable t) {
			return "读语言文件时异常 " + t;
		}
	}

	/** 读一份语言文件成 key → 译文（NeoForge 把 JSON 解析成 {@code Map<String,String>}）。 */
	private static Map<String, String> readLang(Minecraft mc, String locale) {
		Map<String, String> out = new LinkedHashMap<>();
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
			io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm.MODID,
			LANG_DIR + locale + ".json");
		try {
			// ⚠️ NeoForge 的 ResourceManager.getResource 返回 Optional<Resource>（原版是 @Nullable 的 Resource）
			Optional<Resource> found = mc.getResourceManager()
				.getResource(id);
			if (found.isEmpty()) {
				// 开发环境里资源没同步过来时不要静默：调用方会把"读不到"当失败报出来
				return out;
			}
			try (BufferedReader reader = found.get()
				.openAsReader()) {
				JsonObject json = JsonParser.parseReader(reader)
					.getAsJsonObject();
				for (String key : json.keySet())
					out.put(key, json.get(key)
						.getAsString());
			}
		} catch (Exception ignored) {
			// JSON 坏了：返回空表，由调用方报红
		}
		return out;
	}

	/**
	 * 滚轮自检：编辑面板里，鼠标底下的控件必须**真的能滚**。
	 *
	 * <p>用户实测："这个界面的滚动有问题，一会能滚动一会不能。" 原版 1.21 的分发是
	 * {@code ContainerEventHandler.mouseScrolled = getChildAt(x,y).filter(控件消费)} ——
	 * <b>只有最上层那个控件能拿到滚轮</b>，它不消费的话下面那个也不会兜底。
	 * 所以只要有一个"盖在上面但不能滚"的控件（Label、TooltipArea…），滚轮就整个哑掉。
	 * 这条断言把"最上层是谁"和"滚了有没有反应"一起打出来。
	 */
	@Nullable
	private String checkWheel(ArmProgramTask task) {
		for (int e = 0; e < task.entries.size(); e++) {
			// 每个属性编辑面板都试一遍（第一行是属性选择框，第二行是值控件）
			for (Prop prop : entryProps(task.entries.get(e))) {
				editingProp = null;
				editing = new EditTarget(false, 0, e, prop.name.getString());
				init(minecraft, 427, 240);
				int seen = 0;
				for (GuiEventListener listener : editorWidgets) {
					if (!(listener instanceof ScrollInput input))
						continue;
					if (!input.visible || !input.active)
						continue;
					seen++;
					// 第一个 ScrollInput 是"属性选择框"：这个动作只有 1 个属性时它没有第二项可滚，
					// 滚不动是正常的（不是分发坏了）—— 那种情况跳过，别误报。
					if (seen == 1 && entryProps(task.entries.get(e)).size() < 2)
						continue;
					double x = input.getX() + input.getWidth() / 2.0;
					double y = input.getY() + input.getHeight() / 2.0;
					String top = getChildAt(x, y)
						.map(c -> c.getClass()
							.getSimpleName())
						.orElse("无");
					int before = input.getState();
					mouseScrolled(x, y, 0, -1);
					if (input.getState() == before) // 到端了就换一个方向试（SelectionScrollInput 是反向的）
						mouseScrolled(x, y, 0, 1);
					if (input.getState() == before)
						return task.entries.get(e).op + "/" + prop.name.getString() + " 的滚轮改不动 "
							+ input.getClass()
								.getSimpleName() + "（" + before + "→" + input.getState() + "，该点最上层=" + top + "）";
					break;
				}
			}
		}
		editing = null;
		editingProp = null;
		return null;
	}

	/** 自检报告里用的"最宽的一行"（含行尾 ＋ 之外的部分）。 */
	private int maxRowWidth() {
		int max = 0;
		for (Card card : layout())
			max = Math.max(max, rowWidth(card));
		return max;
	}

	/** 自检报告里用的"最宽胶囊"（应当 ≤ {@link #CHIP_MAX}）。 */
	private int maxChipWidth() {
		int max = 0;
		for (Card card : layout())
			for (Prop prop : shownProps(card))
				max = Math.max(max, chipWidth(prop));
		return max;
	}

	/**
	 * "垃圾桶 = 删除"的自检：真按一下，看东西是否真的没了。
	 *
	 * <p>用户实测反馈："点击左侧垃圾桶图标之后会留下操作，而不是不添加或者删除。"
	 * 所以这条必须验证**副作用**：删属性 → 该属性不再显示；删条目 → 条目数 -1。
	 */
	@Nullable
	private String checkDelete() {
		ArmProgramTask task = task(0);
		if (task == null || task.entries.isEmpty())
			return "自检数据里没有条目可删";
		// ① 删属性：第一条卡上的第一个属性，按垃圾桶后应该恢复默认（不再显示）
		ArmProgramEntry first = task.entries.get(0);
		List<Prop> props = entryProps(first);
		if (props.isEmpty())
			return "卡 0 没有任何属性，删属性的断言跑不到";
		Prop prop = props.get(0);
		prop.reveal(); // 先让它显示出来才有得删
		if (!prop.show())
			return "属性「" + prop.name.getString() + "」reveal 之后仍然不显示";
		editingProp = prop;
		editing = new EditTarget(false, 0, 0, prop.name.getString());
		init(minecraft, 427, 240);
		deleteEditing();
		if (prop.show())
			return "按了垃圾桶，属性「" + prop.name.getString() + "」还在（应当恢复默认）";
		if (entryProps(first).get(0)
			.show())
			return "按了垃圾桶，属性「" + prop.name.getString() + "」在卡片上仍然可见";

		// ② 删条目：用**最后一条**（前面的卡要留给热区自检），条目数必须 -1
		int last = task.entries.size() - 1;
		int before = task.entries.size();
		editingProp = null;
		editing = new EditTarget(false, 0, last, null);
		init(minecraft, 427, 240);
		deleteEditing();
		if (task.entries.size() != before - 1)
			return "按了垃圾桶，条目数 " + before + " → " + task.entries.size() + "（应当 -1）";
		// ③ 编辑面板必须关掉，否则会停在一个已经不存在的条目上
		if (editing != null)
			return "删完之后编辑面板还开着";
		return null;
	}

	/** 布局自检：编辑器的控件必须落在窗口内（列表页没有控件，卡片热区由构造期算好）。 */
	@Nullable
	private String checkLayout(String which) {
		for (GuiEventListener listener : editorWidgets) {
			if (!(listener instanceof AbstractWidget widget))
				continue;
			if (widget.getX() < leftPos || widget.getY() < topPos || widget.getX() + widget.getWidth() > leftPos + 256
				|| widget.getY() + widget.getHeight() > topPos + 226)
				return which + " 控件越界 x=" + widget.getX() + " y=" + widget.getY() + " w=" + widget.getWidth();
		}
		// 编辑子面板打开时，底部按钮带（y≈196）里不许还有列表页的按钮：
		// 它们会压在子面板/背包上并且能被点到（实测反馈），上游也是把它们 visible = false。
		if (editing != null) {
			for (GuiEventListener child : children()) {
				if (child instanceof AbstractWidget widget && widget.getY() >= topPos + 190
					&& widget.getY() <= topPos + 215)
					return which + " 编辑时底部按钮仍在（y=" + widget.getY() + "）";
			}
		}
		return null;
	}

	/** 自检报告里回显的热区命中记录，例如 {@code header,remove,up,prop:个数,addprop,background}。 */
	private final List<String> hits = new ArrayList<>();

	/**
	 * 点击热区自检：用<b>绘制时用的坐标</b>反推点击点，再喂回 {@link #action}，逐个验证能否命中。
	 *
	 * <p>为什么必须自动测：热区和绘制是两套手算的坐标（一个 `x = mx-leftPos-25`、一个"卡片画在
	 * `topPos+cardY(i)`"），错一个常量就表现成"点哪儿都没反应" —— 实测踩过：y 多减了一次 CARD_TOP，
	 * 所有热区整体下移 25px，标题芯片/删除键/上下移全部点不到。人眼看不出来，日志可以。
	 *
	 * <p>{@code click = -1} 时 {@code action()} 只做判定与提示，不改任何数据。
	 */
	@Nullable
	private String checkHitAreas() {
		hits.clear();
		List<Card> cards = layout();
		for (int i = 0; i < cards.size(); i++) {
			Card card = cards.get(i);
			int top = cardY(i);
			int cardTop = topPos + top;
			// ★ 「备注」胶囊必须是**摘要** —— 这条**不看卡片在不在视野里**：
			//   判据是"任何一张卡都不许把行尾的 ＋ 顶出裁剪区"，而"在不在视野里"会让这条断言时灵时不灵
			//   （第一版把它放在 `inside(...)` 分支里，于是挂在最后一张卡上的长备注根本没被检查到）。
			List<Prop> cardProps = shownProps(card);
			for (Prop prop : cardProps)
				if (prop.name.getString()
					.equals(tr("entry.note").getString()) && chipWidth(prop) > NOTE_CHIP_MAX_W)
					return "卡片 " + i + " 的备注胶囊宽 " + chipWidth(prop) + "，超过上限 " + NOTE_CHIP_MAX_W
						+ "（长备注会把行尾的 ＋ 顶出裁剪区 —— 用户实测）";
			// 只验"裁剪区 (16,189) 之内"的热区：卡片滚出视野时那些点本来就点不到，
			// 要先滚过去才行 —— 拿这个去报错是自检自己的误报。
			if (inside(top + 12)) {
				if (!expect("header", leftPos + 25 + 80, cardTop + 12))
					return "卡片 " + i + " 的标题芯片点不到（期望 header）";
			}
			if (inside(top + 8) && !expect("remove", leftPos + 25 + 186, cardTop + 8))
				return "卡片 " + i + " 的删除键点不到（期望 remove）";
			if (inside(top + 14) && i > 0 && !expect("up", leftPos + 25 + 200, cardTop + 14))
				return "卡片 " + i + " 的上移点不到（期望 up）";
			if (inside(top + 26) && i < cards.size() - 1 && !expect("down", leftPos + 25 + 200, cardTop + 26))
				return "卡片 " + i + " 的下移点不到（期望 down）";
			// 行尾"添加属性"＋：**可见才可点**（绘制与命中同一个判据 addPropVisible）。
			// 不可见时必须**点不到** —— 用户报的就是"超出去了、没有显示，也可以点击"。
			int plusX = 26 + rowWidth(card) - 3;
			if (inside(top + CARD_ROW_Y + 8)) {
				if (addPropVisible(plusX)) {
					// 点击点取 ＋ 号自己的中心（贴图内第 8..13 列 → 画在 x-3 时中心是 x+8）
					if (!expect("addprop", leftPos + 25 + plusX + 11, cardTop + CARD_ROW_Y + 8))
						return "卡片 " + i + " 的添加属性＋点不到（期望 addprop；＋ 画在 x=" + plusX + "）";
				} else {
					// 不可见 ⇒ 必须点不到；而且必须能靠翻格够到（否则这个 ＋ 就永远消失了）。
					// ⚠️ 判据只看**这一次**点击的 lastHit：`hits` 是累计列表，拿它 contains 会把
					//   "前面某张卡的 ＋ 点成功过"当成这里的命中（第一版就是这么误报的）。
					// 记一个 hits 标记：日志里能看出这一条**真的被走到了**（否则它只是摆设）。
					hits.add("addprop-hidden");
					lastHit = "";
					action(null, leftPos + 25 + plusX + 11, cardTop + CARD_ROW_Y + 8, -1);
					if ("addprop".equals(lastHit))
						return "卡片 " + i + " 的 ＋ 已经在裁剪区外，却仍然点得到（画在 x=" + plusX + "）";
					if (!addPropReachable(chipWidth(shownProps(card).get(shownProps(card).size() - 1))))
						return "卡片 " + i + " 的 ＋ 不可见又够不到（滚到最右也没用；画在 x=" + plusX + "）";
				}
			}
			// 横向箭头：**绘制位置和判定位置必须是同一对常量**（第一版画在 40/203、判定却在 15/178，
			// 用户实测"箭头太靠右"）。这里把 chaser 摆到两端，各点一次可见的那个箭头。
			int arrowY = cardTop + scrollArrowY() + 3;
			if (rowScrollable(card) && inside(top + scrollArrowY() + 3)) {
				LerpedFloat chaser = rowScrolls.get(Math.min(i, rowScrolls.size() - 1));
				float saved = chaser.getChaseTarget();
				int max = maxRowScroll(card);
				chaser.chase(0, 0.5f, Chaser.EXP);
				if (max >= 1 && !expect("scrollright", leftPos + 25 + SCROLL_RIGHT_X + 2, arrowY))
					return "卡片 " + i + " 的横向右箭头点不到（画在 " + SCROLL_RIGHT_X + "）";
				chaser.chase(max, 0.5f, Chaser.EXP);
				if (max >= 1 && !expect("scrollleft", leftPos + 25 + SCROLL_LEFT_X + 2, arrowY))
					return "卡片 " + i + " 的横向左箭头点不到（画在 " + SCROLL_LEFT_X + "）";
				chaser.chase(saved, 0.5f, Chaser.EXP);
			}
			// ★ 胶囊行的热区必须在裁剪区之内（用户实测：鼠标已经在卡片右边缘之外，
				//   越过了胶囊的裁剪线，却仍然命中那个被切掉一半的属性 —— "这个问题只发生在右侧"）。
			if (inside(top + CARD_ROW_Y + 8)) {
				lastHit = "";
				action(null, leftPos + 25 + (ROW_CLIP_RIGHT - 25 + 3), cardTop + CARD_ROW_Y + 8, -1);
				if (lastHit.startsWith("prop:"))
					return "卡片 " + i + " 的胶囊热区越过了裁剪线（在 x=" + (ROW_CLIP_RIGHT - 25 + 3) + " 仍命中 "
						+ lastHit + "）";
			}
			// 第一个属性胶囊（如果有）
			List<Prop> shown = shownProps(card);
			if (inside(top + CARD_ROW_Y + 8) && !shown.isEmpty()) {
				if (chipWidth(shown.get(0)) > CHIP_MAX)
					return "卡片 " + i + " 的胶囊超过封顶宽度(" + CHIP_MAX + ")：" + chipWidth(shown.get(0));
				// ★ 封顶宽度的**真正判据**：滚到最右时 ＋ 必须够得到（这一条与"哪一枚胶囊"无关）
				if (!addPropReachable(CHIP_MAX))
					return "封顶宽度 " + CHIP_MAX + " 之下，滚到最右也够不到 ＋（见 addPropReachable）";
				// ★ 判据本体的边界（＋ 贴图 19 宽、裁剪区局部 18..179 ⇒ 左沿最多 160）
				if (!addPropVisible(160) || addPropVisible(161) || addPropVisible(17))
					return "addPropVisible 的边界不对（160 应当可见、161/17 应当不可见）";
				// ★ "滚到最右时 ＋ 一定看得见"——绘制与命中都走 plusLocalX，所以这里算的就是画出来的位置
				int plusAtMaxScroll = plusLocalX(rowWidth(card), rowWidth(card) - chipWidth(shown.get(shown.size() - 1))
					- CHIP_GAP);
				if (!addPropVisible(plusAtMaxScroll))
					return "卡片 " + i + " 滚到最右仍看不见 ＋（算出来在 x=" + plusAtMaxScroll + "）";
				if (!expect("prop:" + shown.get(0).name.getString(), leftPos + 25 + 26 + 20,
					cardTop + CARD_ROW_Y + 8))
					return "卡片 " + i + " 的属性胶囊点不到（期望 prop:" + shown.get(0).name.getString() + "）";
			}
			// ★ 行上下的空白必须**对称**（用户第二十七轮续十"属性下方出现了空白"→ 续十二"可以再窄一些"）。
			//   上方 = CARD_ROW_Y - (2 + CARD_HEADER)，下方 = CARD_HEIGHT - (CARD_ROW_Y + CHIP_H)。
			//   判据"下方不比上方更空（最多多 1px）"把"卡片底部又长出一块空白"钉死了：
			//   上游 64 高时下方是 19px（= 比上方多 14），这条会当场报出来。
			int padTop = CARD_ROW_Y - (2 + CARD_HEADER);
			int padBottom = CARD_HEIGHT - (CARD_ROW_Y + CHIP_H);
			if (padBottom > padTop + 1)
				return "卡片底部比顶部多留了 " + (padBottom - padTop) + "px 空白（上 " + padTop + " / 下 "
					+ padBottom + "）—— 用户报过这个";
			// ★ ≡ 与横向滚动箭头**有意允许重叠**（用户续十二："按钮没有重叠，不用担心按钮问题，
			//   直接调整即可"），但不能把箭头压死：箭头只有 8 行高，重叠 ≤ 3 行（被 ≡ 拿走的是
			//   x 181..184 那 3 列，箭头判定 x 177..184 左边 4 列照常可点）。
			int arrowOverlap = scrollArrowY() + 7 - (DUPLICATE_Y + 1) + 1;
			if (arrowOverlap > 3)
				return "≡ 的判定区压住了横向滚动箭头的 " + arrowOverlap + " 行（上限 3；箭头判定 "
					+ (scrollArrowY() - 1) + ".." + (scrollArrowY() + 7) + "，≡ 从 " + (DUPLICATE_Y + 1) + " 起）";
			// 卡片背景（下半部空白）：背景分支只在真正点击时才算命中，所以这里用 click=0
			// （它只把指针移过来，不改数据 —— 其余热区一律用 click=-1，免得自检把数据删了）。
			// ⚠️ 取点必须避开**胶囊行那 18 行热区**（CARD_ROW_Y-1 .. +CARD_ROW），否则会点到胶囊上、
			//   顺带把编辑器打开（自检不该改状态）。CARD_HEIGHT-3 在收边后仍落在胶囊行之下（46..49）。
			if (inside(top + CARD_HEIGHT - 3) && !expectClick("background", leftPos + 25 + 100,
				cardTop + CARD_HEIGHT - 3, 0))
				return "卡片 " + i + " 的背景点不到（期望 background）";
			// ≡ 复制键在右下角（上游位置；它自带底色，不能挪进标题栏）。
			if (inside(top + CARD_HEIGHT - 8) && !expect("duplicate", leftPos + 25 + DUPLICATE_X + 6,
				cardTop + CARD_HEIGHT - 8))
				return "卡片 " + i + " 的复制键点不到（期望 duplicate；画在 x=" + DUPLICATE_X + "）";
		}
		// 末尾"新卡片"（同样只验在视野内的情形）
		int newY = newCardY(cards);
		if (inside(newY + 6) && !expect("newcard", leftPos + 25 + 25, topPos + newY + 6))
			return "末尾的新卡片点不到（期望 newcard）";
		return null;
	}

	/** 内容坐标（相对 topPos）是否落在列表裁剪区 (16,16)-(236,189) 内。 */
	private static boolean inside(int contentY) {
		return contentY >= 16 && contentY <= 189;
	}

	private boolean expect(String expected, int mouseX, int mouseY) {
		return expectClick(expected, mouseX, mouseY, -1);
	}

	private boolean expectClick(String expected, int mouseX, int mouseY, int click) {
		lastHit = "";
		action(null, mouseX, mouseY, click);
		hits.add(lastHit.isEmpty() ? "?" : lastHit);
		return expected.equals(lastHit);
	}

}
