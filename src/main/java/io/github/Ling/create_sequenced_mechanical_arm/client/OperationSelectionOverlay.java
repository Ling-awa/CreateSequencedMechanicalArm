package io.github.Ling.create_sequenced_mechanical_arm.client;

import java.util.List;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.AllKeys;
import com.simibubi.create.foundation.gui.AllGuiTextures;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmIcons;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 「当前操作」选择条 —— 复刻 Create **部署蓝图**时那一条（{@code ToolSelectionScreen} 的画法 +
 * {@code SchematicHandler.mouseScrolled} 的交互）。用户要求：
 * <i>"机械动力部署蓝图的时候是 alt+滚动来切换当前动作的，我们也改用 alt+滚轮选择当前行动，
 * 然后使用和部署蓝图使用一样的GUI而不是文字提示，这样方便玩家知道正在做什么、到下一个操作应该如何滚动。"</i>
 *
 * <p>照抄的那几点（都能在 Create 源码里逐行对上）：
 * <ul>
 *   <li><b>尺寸与位置</b>：{@code w = max(条目数*50 + 30, 220)}、{@code h = 30}，
 *       画在 {@code x = (屏宽 - w)/2 + 15}、{@code y = 屏高 - h - 75}（{@code ToolSelectionScreen:47-49,68-69}）；</li>
 *   <li><b>背景</b>：{@code AllGuiTextures.HUD_BACKGROUND} 拉伸，未聚焦半透明（α=1/2）、聚焦 7/8；</li>
 *   <li><b>聚焦</b>：按住 <b>左 Alt</b>（Create 的 {@code AllKeys.TOOL_MENU} 默认键就是 {@code GLFW_KEY_LEFT_ALT}）
 *       时整条升起 10px、当前项高亮并显示说明；松开即应用
 *       （{@code Focus Schematic Overlay} 那个键位，见 {@code AllKeys:22}）；</li>
 *   <li><b>滚动方向</b>：向上滚 = 下一个（{@code ToolSelectionScreen.cycle:57-60} 是反的，照抄）；</li>
 *   <li><b>图标</b>：照抄"先画一份纯黑的当描边、再画本色"两次绘制（{@code ToolSelectionScreen:128-135}）。</li>
 * </ul>
 *
 * <p>与蓝图那条的差别只有一个：条目是<b>本模组的 7 种可录入操作</b>（= 所有需要坐标的操作，
 * 顺序取枚举声明顺序），图标走 {@link ArmIcons#defaultIcon}，文案走本地化。
 *
 * <p>状态刻意<b>不</b>放在 {@code OperationTableHandler} 里：那把"选哪个操作"的视图状态和手势逻辑混在一起，
 * 而这里本来就有一个天生的宿主（HUD 图层）。处理器只问 {@link #selected()}、只调 {@link #cycle(int)}。
 */
public class OperationSelectionOverlay implements LayeredDraw.Layer {

	public static final OperationSelectionOverlay INSTANCE = new OperationSelectionOverlay();

	/** 可录入的操作 = 所有需要坐标的操作；顺序就是滚轮顺序（枚举声明顺序）。 */
	private final List<OperationType> ops = OperationType.recordable();

	private int selection;
	/** 手持操作表且处于录入模式 —— 才画这条。 */
	private boolean visible;
	/** 按住 Alt：升起 + 高亮 + 可滚动（与蓝图那条的 focused 同义）。 */
	private boolean focused;
	private float yOffset;

	private final int w;
	private static final int H = 30;
	/**
	 * 未聚焦时"没被选中"的条目压暗到什么程度。
	 *
	 * <p>不是 alpha —— 物品渲染类型不透明，alpha 看不出效果（详见 {@code draw()} 里的说明）。
	 * 0.45 是照着上游"未聚焦 α=0.2"的观感调的：够暗到能一眼分出当前项，又不至于看不清图标是什么。
	 */
	private static final float DIM = 0.45f;

	private OperationSelectionOverlay() {
		w = Math.max(ops.size() * 50 + 30, 220);
	}

	// ————————————————————————— 状态 —————————————————————————

	public OperationType selected() {
		return ops.get(selection);
	}

	/** 每客户端 tick 调一次（由 {@code OperationTableHandler} 驱动，省得每帧去读卡）。 */
	public void tick(boolean visible) {
		this.visible = visible;
		focused = visible && AllKeys.altDown();
		// 照抄 ToolSelectionScreen.update()：聚焦时升到 10，松开按 0.9 衰减回去
		if (focused)
			yOffset += (10 - yOffset) * .1f;
		else
			yOffset *= .9f;
	}

	/** 滚轮切换（只在聚焦时由处理器调用）。方向照抄上游：向上滚 = 下一个。 */
	public void cycle(int direction) {
		selection += (direction < 0) ? 1 : -1;
		selection = (selection + ops.size()) % ops.size();
	}

	// ————————————————————————— 绘制 —————————————————————————

	@Override
	public void render(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!visible || mc.options.hideGui || mc.player == null || mc.screen != null)
			return;

		Font font = mc.font;
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		int x = (screenW - w) / 2 + 15;
		int y = screenH - H - 75;

		var pose = graphics.pose();
		pose.pushPose();
		pose.translate(0, -yOffset, focused ? 100 : 0);

		AllGuiTextures gray = AllGuiTextures.HUD_BACKGROUND;
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1, 1, 1, focused ? 7 / 8f : 1 / 2f);
		graphics.blit(gray.location, x - 15, y, gray.getStartX(), gray.getStartY(), w, H, gray.getWidth(),
			gray.getHeight());

		// 聚焦时在下面展开一块说明（照抄上游 toolTipAlpha 的那段）：这行说明就是"现在在做什么"
		float toolTipAlpha = yOffset / 10;
		int textAlpha = ((int) (toolTipAlpha * 0xFF)) << 24;
		if (toolTipAlpha > 0.25f) {
			RenderSystem.setShaderColor(.7f, .7f, .8f, toolTipAlpha);
			// 三行（用户第八十九轮图五口径）：① 白字 = 这个操作做什么（取出 / 放入带数量关系）
			// ② 现在这条提示（右键方块录入）③ 灰字 = 只在编辑器里才改得到的那件事。
			graphics.blit(gray.location, x - 15, y + 33, gray.getStartX(), gray.getStartY(), w, H + 34, gray.getWidth(),
				gray.getHeight());
			RenderSystem.setShaderColor(1, 1, 1, 1);
			graphics.drawString(font, firstLine(selected()), x - 10, y + 38, 0xEEEEEE + textAlpha, false);
			graphics.drawString(font, tr("hud.op_menu.record"), x - 10, y + 50, 0xCCDDFF + textAlpha, false);
			Component extra = extraLine(selected());
			if (extra != null)
				graphics.drawString(font, extra, x - 10, y + 62, 0x99AACC + textAlpha, false);
		}

		RenderSystem.setShaderColor(1, 1, 1, 1);
		// 键位提示：没聚焦时告诉玩家"按住 Alt"，聚焦后告诉玩家"滚轮切换"
		String hint = focused ? tr("hud.op_menu.cycle").getString() : tr("hud.op_menu.focus").getString();
		graphics.drawCenteredString(font, hint, screenW / 2, y - 10, 0xCCDDFF);

		// ⚠️ 画物品之前必须自己设好 3D 物品光照（用户实测："方块物品只渲染顶端，其他面都是黑的"）。
		// 缘由在 GuiGraphics.renderItem 里写着：它**只对平面模型**（usesBlockLight()==false，比如拉杆/铁镐）
		// 自己切平面光照再切回来；**方块模型是"方块光照"**，它假定调用方已经把光照准备好。
		// 原版是在 GameRenderer 里给整个 GUI 通道设一次（:1059 setupFor3DItems），而我们这条图层排在最上面，
		// 继承到的可能已经是别人留下的平面光照 —— 于是方块只有朝"平面光"的那一面亮、其余面全黑。
		// 显式设一次即可（Create 自己的 StockKeeperRequestScreen:534/545 也是这么干的）。
		Lighting.setupFor3DItems();
		for (int i = 0; i < ops.size(); i++) {
			pose.pushPose();
			int slotX = x + i * 50 + 16;
			if (i == selection) {
				pose.translate(0, -10, 0);
				graphics.drawCenteredString(font, tr("gui.editor.opt." + key(ops.get(i))), x + i * 50 + 24, y + 28,
					0xCCDDFF);
			}
			// ⚠️ 压暗**不能用 alpha**：物品走的是 entitySolid / entityCutout 这类**不透明**渲染类型
			// （它们自己会 disableBlend），ColorModulator 的 alpha 只会改帧缓冲的 alpha 通道，屏幕上看不出变化 ——
			// 这正是用户报的"松开 Alt 之后物品一直是不透明的"。改成缩放 RGB（乘一个暗色）就一定能看见，
			// 视觉上同样是"没选中的退到背景里"。选中项保持全亮，和上游"选中项 alpha=1"的意图一致。
			float brightness = (focused || i == selection) ? 1f : DIM;
			RenderSystem.setShaderColor(brightness, brightness, brightness, 1f);
			graphics.renderItem(ArmIcons.defaultIcon(ops.get(i)), slotX, y + 11);
			RenderSystem.setShaderColor(1, 1, 1, 1);
			pose.popPose();
		}
		// 保持"GUI 通道期间 = 3D 物品光照"这个原版约定，别把后面的图层甩在平面光照上
		Lighting.setupFor3DItems();

		RenderSystem.setShaderColor(1, 1, 1, 1);
		RenderSystem.disableBlend();
		pose.popPose();
	}

	/**
	 * 第一行（白字）：这个操作做什么。
	 *
	 * <p>取出 / 放入 / 收存**要带上数量关系**（用户第八十九轮："对于取出放入，第一行白色文本要附带数量关系"）：
	 * 录进去的条目按 {@link ArmProgramEntry#fresh} 的默认值走 —— 取出 1、放入 64、收存 64，模式是「最多」，
	 * 所以这里直接把"最多 N 个"接在描述后面，玩家在录之前就知道这一条会搬多少。
	 */
	private static Component firstLine(OperationType op) {
		Component desc = tr("hud.op." + key(op));
		if (!op.hasCount())
			return desc;
		return desc.copy()
			.append(tr("hud.op_count_atmost", ArmProgramEntry.defaultCount(op)));
	}

	/**
	 * 第三行（灰字）：只在编辑器里才改得到的那件事 —— 数量、潜行开关。
	 *
	 * <p>没有额外要说的操作返回 {@code null}（那时候只画两行）。
	 */
	@Nullable
	private static Component extraLine(OperationType op) {
		if (op.hasCount())
			return tr("hud.op_extra.count");
		if (op == OperationType.USE)
			return tr("hud.op_extra.sneak");
		return null;
	}

	private static String key(OperationType op) {
		return op.name()
			.toLowerCase(java.util.Locale.ROOT);
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable("create_sequenced_mechanical_arm." + key, args);
	}

	/**
	 * 客户端自检用：条目表必须正好是"所有需要坐标的操作"，图标都在，滚轮能环绕；
	 * 并报出其中有多少个是**方块模型**图标 —— 那些正是"必须先把光照设成 3D 物品"的那几个
	 * （用户实测的"方块只有顶面亮、其余面全黑"就出在它们身上，见 {@code draw()} 的说明）。
	 */
	public String selfCheck() {
		List<OperationType> expected = java.util.Arrays.stream(OperationType.values())
			.filter(OperationType::requiresTarget)
			.toList();
		if (!ops.equals(expected))
			return "操作选择条的内容不是全部可录入操作：" + ops + " vs " + expected;
		int blockModels = 0;
		ItemRenderer renderer = Minecraft.getInstance()
			.getItemRenderer();
		for (OperationType op : ops) {
			ItemStack icon = ArmIcons.defaultIcon(op);
			if (icon.isEmpty())
				return op + " 没有图标";
			if (renderer.getModel(icon, null, null, 0)
				.usesBlockLight())
				blockModels++;
		}
		int before = selection;
		cycle(-1);
		boolean forward = selection != before;
		cycle(1);
		if (selection != before || !forward)
			return "滚轮切换没有正确环绕（before=" + before + " now=" + selection + "）";
		// 三行说明（用户第八十九轮图五）：① 白字描述（取出/放入带数量）② 现在这条提示 ③ 灰字额外说明。
		// 缺键的表现是**画出一串裸 key**（编译、运行都不报错），所以按"不许出现 key 前缀"断言。
		for (OperationType op : ops) {
			String first = firstLine(op).getString();
			if (first.contains("create_sequenced_mechanical_arm"))
				return op + " 的第一行说明缺译文：" + first;
			if (op.hasCount()) {
				String suffix = tr("hud.op_count_atmost", ArmProgramEntry.defaultCount(op)).getString();
				if (suffix.contains("create_sequenced_mechanical_arm") || !first.endsWith(suffix))
					return op + " 的第一行没带数量关系（要 N 个那条文案）：[" + first + "] 期望以 [" + suffix + "] 结尾";
				Component extra = extraLine(op);
				if (extra == null || extra.getString()
					.contains("create_sequenced_mechanical_arm"))
					return op + " 缺第三行「在编辑模式中编辑数量」";
			}
		}
		Component sneakExtra = extraLine(OperationType.USE);
		if (sneakExtra == null || sneakExtra.getString()
			.contains("create_sequenced_mechanical_arm"))
			return "「使用」缺第三行「在编辑模式中启用潜行」";
		if (extraLine(OperationType.MINE) != null)
			return "「挖掘」不该有第三行说明";
		return "OK(" + ops.size() + "项, 其中方块模型=" + blockModels + "个需要3D光照)";
	}

}
