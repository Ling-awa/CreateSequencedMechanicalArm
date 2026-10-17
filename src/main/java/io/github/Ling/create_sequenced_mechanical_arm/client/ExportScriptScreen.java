package io.github.Ling.create_sequenced_mechanical_arm.client;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;

import io.github.Ling.create_sequenced_mechanical_arm.network.ExportScriptPacket;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 「上传操作记录表」的**文件名输入界面** —— 布局与贴图**照抄 Create 的蓝图与笔**
 * （{@code content/schematics/client/SchematicPromptScreen.java:37-93}）。
 *
 * <h2>为什么是"照抄"而不是自己画</h2>
 * 用户口径：导出功能对了，但界面不是他预想的那块"蓝图与笔的文件名面板"；**暂时复用 Create 的 GUI 资源**，
 * 等他画好自己的资源再换。所以这里不新建任何贴图（仓库里仍然没有 {@code textures/}，PLAN §0.7），
 * 整块面板、按钮底板与按钮图标全部直接用 {@link AllGuiTextures} / {@link AllIcons} 的现成常量渲染。
 *
 * <h2>与上游的两个动作</h2>
 * <ul>
 *   <li><b>确认</b>（{@code I_CONFIRM}，上游 {@code :62-67}）= 发既有的 {@link ExportScriptPacket}；</li>
 *   <li><b>取消</b>（{@code I_TRASH}，上游 {@code :54-60} 的 "discard"）= 关界面、什么都不发。</li>
 * </ul>
 * 上游第三颗按钮"立即转换"（{@code I_SCHEMATIC}，{@code :69-74}）**没有对应动作**，因此那个位置留空
 * （见 {@link #CONFIRM_X} 附近的注释）。
 *
 * <h2>界面仍然只负责"收集名字"</h2>
 * 能不能存、目录里有没有重名、程序能不能转成文本 —— 全部由服务端裁决并回一条 actionbar
 * （{@link ExportScriptPacket#handle}，成功那条与本体存完蓝图后的"另存为 X"同一条通道）。客户端这里只多做
 * 一件**不可能与服务端不一致**的事：用同一个 {@link ScriptFiles#isValidName} 提前挡掉非法名字并给红字，不静默关界面。
 *
 * <h2>提示文字放在哪（用户两轮反馈定的）</h2>
 * <ul>
 *   <li>规则摘要 ⇒ 输入框的<b>悬停提示</b>（面板上不再常驻灰字：浅色贴图上那行字读不清）；</li>
 *   <li>"名字不合法" ⇒ 面板里一小段红字 +（同时）悬停提示换成红色原因。</li>
 * </ul>
 * 见 {@link #refreshNameTooltip()} 与 {@link #drawError(GuiGraphics)}。
 */
public class ExportScriptScreen extends Screen {

	/**
	 * 文件名面板：{@code SCHEMATIC_PROMPT("schematics_2", 213, 79)}
	 * （{@code AllGuiTextures.java:31}；三参构造 ⇒ 起点 (0,0)，见 {@code :278-280}）。
	 */
	private static final AllGuiTextures BACKGROUND = AllGuiTextures.SCHEMATIC_PROMPT;

	// ————————————————————— 上游原样数值（SchematicPromptScreen.java:42-92） —————————————————————
	/** 控件整体比面板再下移 2px（上游 {@code :43} 的 {@code guiTop + 2}）。 */
	private static final int WIDGET_DY = 2;
	/** 名字输入框：{@code EditBox(x+49, y+26, 131, 10)}，无边框、白字（上游 {@code :45-52}）。 */
	private static final int FIELD_X = 49;
	private static final int FIELD_Y = 26;
	private static final int FIELD_W = 131;
	private static final int FIELD_H = 10;
	/** 取消（上游的"丢弃"）：{@code IconButton(x+7, y+53, I_TRASH)}（上游 {@code :54-60}）。 */
	private static final int CANCEL_X = 7;
	/** 确认：{@code IconButton(x+158, y+53, I_CONFIRM)}（上游 {@code :62-67}）。 */
	private static final int CONFIRM_X = 158;
	/**
	 * 两颗按钮的 y（上游 {@code :54/:62} 都是 {@code y + 53}）。
	 * 上游在 {@code x+180} 还有第三颗"立即转换"，我们没有对应动作 ⇒ 那个 18×18 的位置空着。
	 */
	private static final int BUTTON_Y = 53;
	/** 面板左下物品槽里那颗物品图标：(x+22, y+24)（上游 {@code :85-87}）。 */
	private static final int SLOT_ITEM_X = 22;
	private static final int SLOT_ITEM_Y = 24;
	/** 面板右下角外面那颗放大的物品：(x + 宽 + 6, y + 高 - 38, z = -200)，scale 3（上游 {@code :89-92}）。 */
	private static final int BIG_ITEM_DX = 6;
	private static final int BIG_ITEM_DY = -38;
	private static final int BIG_ITEM_Z = -200;
	private static final float BIG_ITEM_SCALE = 3;
	/** 标题：水平居中但多让出 8px（上游 {@code :83}），颜色 0x505050。 */
	private static final int TITLE_INSET = 8;
	private static final int TITLE_DY = 4;
	private static final int TITLE_COLOR = 0x505050;
	/**
	 * **非法名字**那一行红字的 y（用户反馈：原来那行灰字压在浅色面板贴图上，根本看不清）。
	 *
	 * <p>只留给"刚被拒"这一种状态 —— 规则摘要已经搬进输入框的**悬停提示**（见 {@link #refreshNameTooltip()}），
	 * 面板上不再常驻任何文字。数值取 40：输入框底沿是 {@code top+38}（{@link #WIDGET_DY} + {@link #FIELD_Y} +
	 * {@link #FIELD_H}），按钮顶沿是 {@code top+55}，文字占 40..49，上下都留得开。
	 */
	private static final int ERROR_DY = 40;
	/**
	 * 红字下面垫的深色底板（ARGB）。
	 *
	 * <p>为什么非垫不可：面板贴图是浅色的、还带花纹，红字直接画上去就是用户说的"太不容易看清"
	 * （灰字那版 0x6A6A6A 更糟，等于没有）。垫一块半透明黑把对比度问题一次性解决，而且**不引入任何贴图**
	 * （仓库里仍然没有 {@code textures/}，PLAN §0.7）；只在"被拒"这一小段时间出现。
	 */
	private static final int ERROR_PLATE = 0xC0101010;
	/** 红字颜色：比纯红亮一点，压在深色底板上也认得出。 */
	private static final int ERROR_TEXT_COLOR = 0xFFFF8080;
	/** 红字左右各留的边距（面板 213 宽，留 7 ⇒ 文字最宽 199，超了截断而不是画到面板外）。 */
	private static final int ERROR_PAD = 7;

	/** 目标是哪条臂：随包发给服务端，服务端再验一遍"那真的是我们的臂、你离得够近"。 */
	private final BlockPos pos;

	private EditBox nameField;
	/** 面板左上角 = 居中后的窗口原点（等价于上游 {@code AbstractSimiScreen} 算出来的 guiLeft / guiTop）。 */
	private int left;
	private int top;
	/** 槽里那颗（被导出程序的）臂；面板右下角那颗（手里拿着的）记录表 —— 与上游两颗物品一一对应。 */
	private ItemStack slotItem = ItemStack.EMPTY;
	private ItemStack bigItem = ItemStack.EMPTY;
	/**
	 * 玩家按过一次确认但名字不合法：才画那段红字、也才把悬停提示换成红色原因
	 * （否则一清空输入框就闪红）。名字一旦改对，输入框的 responder 会把它复位。
	 */
	private boolean nameRejected;

	public ExportScriptScreen(BlockPos pos) {
		super(Component.translatable("create_sequenced_mechanical_arm.gui.record.title"));
		this.pos = pos;
	}

	@Override
	protected void init() {
		left = (width - BACKGROUND.getWidth()) / 2;
		top = (height - BACKGROUND.getHeight()) / 2;
		int widgetY = top + WIDGET_DY;

		nameField = new EditBox(font, left + FIELD_X, widgetY + FIELD_Y, FIELD_W, FIELD_H, Component.empty());
		// 上游 :46-48：白字、无不可编辑态色差、**无边框**（边框和底板都长在 schematics_2.png 上）。
		nameField.setTextColor(-1);
		nameField.setTextColorUneditable(-1);
		nameField.setBordered(false);
		nameField.setMaxLength(ScriptFiles.MAX_NAME); // 与服务端的名字上限同源（32）
		nameField.setValue(defaultName());
		addRenderableWidget(nameField);
		nameField.setFocused(true);
		setFocused(nameField);

		// 规则摘要不再画在面板上，改成**输入框的悬停提示**（用户反馈：灰字看不清，建议做成悬停提示）。
		// 1.21.1 的写法（全对着源码核过，不凭记忆）：
		//   EditBox extends AbstractWidget（EditBox.java:28）
		//   ⇒ AbstractWidget#setTooltip(Tooltip)（AbstractWidget.java:78-80）+ Tooltip.create(Component)（Tooltip.java:35-37）；
		//   悬停时 AbstractWidget.render 把 Tooltip 交给 WidgetTooltipHolder（AbstractWidget.java:74）
		//   ⇒ Screen.setTooltipForNextRenderPass（WidgetTooltipHolder.java:37-57）
		//   ⇒ 最后由 Screen.renderWithTooltip 统一画出来（Screen.java:111-117）—— 所以普通 Screen 也生效，
		//   不需要自己写 renderTooltip。
		// setResponder 在每次文本变化后回调（EditBox.java:95-97、:170-171），用来随时把提示换成"合法/非法"那一版。
		nameField.setResponder(value -> {
			if (nameRejected && ScriptFiles.isValidName(value.trim()))
				nameRejected = false; // 名字改对了就把红字撤掉（只影响下一次绘制）
			refreshNameTooltip();
		});
		refreshNameTooltip();

		// 取消 = 什么都不发（不发包就没有副作用；服务端那边根本不知道有这一下）
		IconButton cancel = new IconButton(left + CANCEL_X, widgetY + BUTTON_Y, AllIcons.I_TRASH);
		cancel.withCallback(this::onClose);
		cancel.setToolTip(Component.translatable("create_sequenced_mechanical_arm.gui.record.cancel"));
		addRenderableWidget(cancel);

		IconButton confirm = new IconButton(left + CONFIRM_X, widgetY + BUTTON_Y, AllIcons.I_CONFIRM);
		confirm.withCallback(this::confirm);
		confirm.setToolTip(Component.translatable("create_sequenced_mechanical_arm.gui.record.confirm"));
		addRenderableWidget(confirm);

		// 两颗装饰性物品照上游的语义分工：槽里 = 被存下来的东西（这条臂的程序），画面外那颗 = 手里的工具。
		slotItem = new ItemStack(SequencedArmRegistration.SEQUENCED_ARM_ITEM.get());
		bigItem = new ItemStack(SequencedArmRegistration.OPERATION_RECORD.get());
	}

	/**
	 * 面板画在 {@code renderBackground} 里而不是 {@code render()} 里：原版 {@code Screen.render}
	 * 的顺序是"背景 → 全部 renderables"（{@code Screen.java:132-138}），所以写在背景这一步，
	 * 面板就正好压在暗底之上、输入框与两颗按钮之下（按钮的贴图要盖在面板的按钮位上）。
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick); // 原版世界里那层暗底（Screen.java:377-385）
		BACKGROUND.render(graphics, left, top);

		graphics.drawString(font, title,
			left + (BACKGROUND.getWidth() - TITLE_INSET - font.width(title)) / 2, top + TITLE_DY, TITLE_COLOR, false);

		GuiGameElement.of(slotItem)
			.at(left + SLOT_ITEM_X, top + SLOT_ITEM_Y)
			.render(graphics);
		GuiGameElement.of(bigItem)
			.scale(BIG_ITEM_SCALE)
			.at(left + BACKGROUND.getWidth() + BIG_ITEM_DX, top + BACKGROUND.getHeight() + BIG_ITEM_DY, BIG_ITEM_Z)
			.render(graphics);

		drawError(graphics);
	}

	/** 输入框里的名字（两端空白剥掉）：与 {@link #confirm()} 和悬停提示**同一个**取法，三处不会各说各话。 */
	private String currentName() {
		return nameField.getValue()
			.trim();
	}

	/**
	 * 把规则摘要（或"名字不合法"）挂到输入框的**悬停提示**上。
	 *
	 * <p>为什么改成提示框（用户反馈原话："灰字提示太不容易看清了，建议作为悬停在提示框上的提示"）：
	 * 提示框自带深色底与边框，对比度不再受面板贴图影响；面板上只留"刚被拒"那一小段红字
	 * （{@link #drawError(GuiGraphics)}），省出来的地方也不必再猜贴图上哪块是空白的。
	 *
	 * <p>文案**沿用既有的两个 lang 键**，一个字都不新造：能存或者还没被拒过 ⇒
	 * {@code gui.record.name_hint}（规则摘要，170px 宽自动折成两行，见 {@code Tooltip.splitTooltip}）；
	 * 被拒且仍不合法 ⇒ {@code gui.record.name_invalid} 并染红。
	 */
	private void refreshNameTooltip() {
		boolean invalid = nameRejected && !ScriptFiles.isValidName(currentName());
		MutableComponent text = Component.translatable(invalid
			? "create_sequenced_mechanical_arm.gui.record.name_invalid"
			: "create_sequenced_mechanical_arm.gui.record.name_hint");
		if (invalid)
			text.withStyle(ChatFormatting.RED);
		nameField.setTooltip(Tooltip.create(text));
	}

	/**
	 * "刚被拒"的那一小段红字：只在 {@link #nameRejected} 为真且名字**仍然**不合法时画，位置见 {@link #ERROR_DY}。
	 *
	 * <p>为什么不能纯靠提示框：提示框**要悬停才出现**，而"我按了保存、然后什么都没存下"这件事必须当场看见
	 * （用户口径：非法名不能静默）。所以保留一小段红字，但垫了深色底板（{@link #ERROR_PLATE}）——
	 * 与面板贴图彻底脱钩，这正是原来那行灰字读不清的根因；悬停提示那边同时换成红色原因，两条路都能读到。
	 *
	 * <p>文案可能比面板宽（中文 9px/字），先按可用宽度截断再居中 —— 宁可少几个字，也不要画到面板外面去。
	 * 顺带把状态清理（名字改对了就撤红字）放在 {@link #init()} 的 responder 里，绘制这一步只读状态。
	 */
	private void drawError(GuiGraphics graphics) {
		if (!nameRejected)
			return;
		int x0 = left + ERROR_PAD;
		int x1 = left + BACKGROUND.getWidth() - ERROR_PAD;
		graphics.fill(x0 - 3, top + ERROR_DY - 1, x1 + 3, top + ERROR_DY + 10, ERROR_PLATE);
		Component text = Component.translatable("create_sequenced_mechanical_arm.gui.record.name_invalid");
		String shown = font.substrByWidth(text, x1 - x0)
			.getString();
		graphics.drawString(font, shown, left + (BACKGROUND.getWidth() - font.width(shown)) / 2, top + ERROR_DY,
			ERROR_TEXT_COLOR, false);
	}

	/**
	 * 默认名字：{@code arm_<x>_<y>_<z>}。
	 *
	 * <p>为什么不空着：上传是"给这条臂留一份文件"，十次里有九次玩家并不想自己起名；
	 * 带上坐标的默认名既唯一（同一坐标只有一条臂）又自解释，撞名时服务端会明确拒绝并让换一个。
	 * 坐标的负号是合法字符（{@link ScriptFiles#isValidName} 放行 {@code -}）。
	 */
	private String defaultName() {
		return "arm_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers); // 其余键照旧进到输入框；ESC 由原版关界面
	}

	/**
	 * 确认：只发意图（名字 + 哪条臂），服务端校验后落盘并回执。
	 *
	 * <p>名字两端剥一次空白：文件名里本来就不许有空格，而"手滑多打一个空格"是最常见的输入错误
	 * —— 让它在这一层就被吃掉，比回一条"文件名不合法"更省事（中间的空格仍然会被服务端拒掉）。
	 *
	 * <p>名字不合法时**不关界面、也不发包**：服务端同样会拒（{@code record.bad_name}），
	 * 但先关界面再读状态栏对玩家来说是"我按了保存，然后什么都没存下"。留在这里给红字（垫了深色底板、
	 * 位置见 {@link #ERROR_DY}），悬停提示同一时间换成红色原因，改对再按。
	 */
	private void confirm() {
		String name = currentName();
		if (!ScriptFiles.isValidName(name)) {
			nameRejected = true;
			refreshNameTooltip(); // 提示框立刻变成红色的"名字不合法"，不必等玩家再动键盘
			return;
		}
		PacketDistributor.sendToServer(new ExportScriptPacket(name, pos));
		onClose();
	}

	/** 不暂停游戏：这是世界里的一次手势（与蓝图与笔那块界面同一个观感），暂停会让"臂上正在跑的程序"停在半路。 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

}
