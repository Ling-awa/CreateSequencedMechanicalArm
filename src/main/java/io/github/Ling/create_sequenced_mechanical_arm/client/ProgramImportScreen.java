package io.github.Ling.create_sequenced_mechanical_arm.client;

import static com.simibubi.create.foundation.gui.AllGuiTextures.PLAYER_INVENTORY;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;

import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableMenu;
import io.github.Ling.create_sequenced_mechanical_arm.network.ProgramImportRequestPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.ProgramImportStartPacket;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 「操作导入桌」界面 —— 布局、进度条与上游蓝图桌**逐像素一致**
 * （{@code SchematicTableScreen}），**但贴图已经是我们自己的**：底板与进度条来自
 * {@code assets/create_sequenced_mechanical_arm/textures/gui/program_table.png}，uv 由
 * {@link ModGuiTextures} 登记（用户要求资源自持，方便他自己画）。
 *
 * <p>仍然借用 Create 的只剩两样，且都与"这张桌子的美术"无关：玩家背包底板
 * {@code PLAYER_INVENTORY}（原版容器界面的通用底板）与按钮图标 {@code AllIcons}（{@code icons.png} 图集，
 * 用户口径"大部分按钮就用现在原版风格即可"）。
 *
 * <h2>与上游的三处差异（都是有理由的，不是顺手改的）</h2>
 * <ol>
 *   <li><b>文件列表来自服务端</b>：每次 {@code init} 都发一个空的
 *       {@link ProgramImportRequestPacket}，服务端回 {@code ProgramImportListPacket}（只有名字），
 *       到达时由 {@link #acceptList} 重建列表控件。所以这里**没有** {@code CreateClient.SCHEMATIC_SENDER}；
 *       "刷新"那颗按钮就是再发一次请求。</li>
 *   <li><b>没有"打开文件夹"按钮</b>（上游 {@code I_OPEN_FOLDER}）：脚本目录在<b>服务端</b>，
 *       在客户端上 {@code openFile} 打开的是玩家自己机器的文件夹 —— 要么打不开（专用服务器），
 *       要么指向一个和这台机器毫无关系的目录。那个 18×18 的位置干脆空着
 *       （与记录表导出界面"第三颗按钮没有对应动作就留空"同一个处理）。</li>
 *   <li><b>标题三态换成我们的文案、进度标签带剩余秒数</b>：用户要的"要时间 + 进度条"——
 *       进度条照上游画（四行逐字照抄），旁边再写一句"还有几秒"，因为 5 秒的固定时长
 *       （{@link ProgramImportTableBlockEntity#IMPORT_TICKS}）玩家应该看得见。</li>
 * </ol>
 *
 * <h2>界面仍然不做裁决</h2>
 * 能不能导（输入槽是不是空白表、输出槽空不空、文件在不在、编译过不过）全部由服务端判
 * （{@code ProgramImportTableBlockEntity.startImport}）。所以确认键**不**按 {@code menu.canWrite()}
 * 变灰：灰按钮说不出理由，而回执说得出（"请先在输入槽放一张空白操作表"）。
 * 唯一按本地状态灰掉的是"列表为空"——那时连文件都没有，点它没有任何意义。
 *
 * <p>⚠️ {@code AbstractSimiContainerScreen} 的硬约定（照上游）：{@code setWindowSize}/{@code setWindowOffset}
 * 必须在 {@code super.init()} **之前**调用。
 */
public class ProgramImportScreen extends AbstractSimiContainerScreen<ProgramImportTableMenu> {

	/**
	 * 界面底板与进度条：**我们自己的贴图**（{@code textures/gui/program_table.png}）。
	 *
	 * <p>uv 与宽高原样搬自 Create 的 {@code AllGuiTextures}（见 {@link ModGuiTextures} 的注释），
	 * 但 {@code location} 已经指向本模组命名空间 —— 用户要求资源自持，这样他能直接改这两张 PNG。
	 */
	private static final ModGuiTextures BACKGROUND = ModGuiTextures.PROGRAM_IMPORT_TABLE;
	private static final ModGuiTextures PROGRESS = ModGuiTextures.PROGRAM_IMPORT_PROGRESS;

	// ————————————————— 上游原样数值（SchematicTableScreen.java:66-131） —————————————————
	/** 控件整体比底板再下移 2px（上游 {@code int y = topPos + 2}）。 */
	private static final int WIDGET_DY = 2;
	/** 文件选择框：{@code SelectionScrollInput(x+45, y+21, 139, 18)}（上游 {@code :80}）。 */
	private static final int LIST_X = 45;
	private static final int LIST_Y = 21;
	private static final int LIST_W = 139;
	private static final int LIST_H = 18;
	/** 选择框里那行字：{@code Label(x+51, y+26)}（上游 {@code :76}）。 */
	private static final int LIST_LABEL_X = 51;
	private static final int LIST_LABEL_Y = 26;
	/** 确认：{@code IconButton(x+44, y+56, I_CONFIRM)}（上游 {@code :87}）。 */
	private static final int CONFIRM_X = 44;
	private static final int CONFIRM_Y = 56;
	/**
	 * 刷新：{@code IconButton(x+206, y+21, I_REFRESH)}（上游 {@code :103}）。
	 *
	 * <p>x+206 = 底板右沿之外 10px（底板 214 宽、控件起点 44 ⇒ 206+18=224 会伸出去一点，
	 * 上游原来这里还有一颗"打开文件夹"在 x+20；我们把它去掉后这块位置就是刷新独占）。
	 */
	private static final int REFRESH_X = 206;
	private static final int REFRESH_Y = 21;
	/** 进度条：{@code blit(..., x+70, y+59, ...)}（上游 {@code :167}）。 */
	private static final int PROGRESS_X = 70;
	private static final int PROGRESS_Y = 59;
	/** 列表为空时那行灰字：{@code (x+54, y+26)}、颜色 {@code 0xD3D3D3}（上游 {@code :157}）。 */
	private static final int NO_SCRIPTS_X = 54;
	private static final int NO_SCRIPTS_Y = 26;
	private static final int NO_SCRIPTS_COLOR = 0xD3D3D3;
	/** 标题：水平居中但多让出 8px、颜色 0x505050（上游 {@code :154}）。 */
	private static final int TITLE_INSET = 8;
	private static final int TITLE_Y = 4;
	private static final int TITLE_COLOR = 0x505050;
	/** 底板右下角外那颗放大的物品（上游 {@code :159-162}）。 */
	private static final float RENDERED_ITEM_SCALE = 3;
	private static final int RENDERED_ITEM_Z = -200;
	/** 导入中列表标签的颜色（上游 {@code :189}：{@code 0xCCDDFF}）。 */
	private static final int LABEL_IMPORTING_COLOR = 0xCCDDFF;
	private static final int LABEL_IDLE_COLOR = 0xFFFFFF;

	private final Component uploading = Component.translatable("create_sequenced_mechanical_arm.gui.import.uploading");
	private final Component finished = Component.translatable("create_sequenced_mechanical_arm.gui.import.finished");
	private final Component refresh = Component.translatable("create_sequenced_mechanical_arm.gui.import.refresh");
	private final Component noScripts = Component.translatable("create_sequenced_mechanical_arm.gui.import.no_scripts");
	private final Component availableTitle =
		Component.translatable("create_sequenced_mechanical_arm.gui.import.available");

	private ScrollInput scriptsArea;
	private Label scriptsLabel;
	private IconButton confirmButton;
	private IconButton refreshButton;

	private float progress;
	private float chasingProgress;
	private float lastChasingProgress;

	/** 底板右下角那颗放大物品 = 这台机器本身（上游放的是蓝图桌的方块物品）。 */
	private final ItemStack renderedItem = new ItemStack(SequencedArmRegistration.PROGRAM_IMPORT_TABLE_ITEM.get());

	/** 当前服务端给的脚本名（不含 {@code .sma}）。空 ⇒ 选择框不存在、确认键是灰的。 */
	private List<String> names = List.of();

	private List<Rect2i> extraAreas = Collections.emptyList();

	public ProgramImportScreen(ProgramImportTableMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	/**
	 * ★ 唯一的静态入口：{@code ProgramImportListPacket} 的客户端处理器把列表递到这里。
	 *
	 * <p>不做任何缓存：包到的时候如果界面已经不是我们（玩家关了），这一包就丢掉 ——
	 * 下次打开会重新请求。留一份全局缓存只会让"上次那个世界的文件列表"在下次开界面时闪一下。
	 */
	@OnlyIn(Dist.CLIENT)
	public static void acceptList(List<String> incoming) {
		if (Minecraft.getInstance().screen instanceof ProgramImportScreen screen)
			screen.applyNames(incoming);
	}

	private void applyNames(List<String> incoming) {
		names = List.copyOf(incoming);
		rebuildList();
	}

	@Override
	protected void init() {
		// 这两个必须在 super.init() 之前（AbstractSimiContainerScreen 的注释：它按 imageWidth/offset 摆窗口）
		setWindowSize(BACKGROUND.getWidth(), BACKGROUND.getHeight() + 4 + PLAYER_INVENTORY.getHeight());
		setWindowOffset(-11, 8);
		super.init();

		// 列表是**服务端**的真相：每次打开都从空开始、重新问一次（与上游 init 里 refresh 同一个位置）
		names = List.of();
		PacketDistributor.sendToServer(new ProgramImportRequestPacket());

		int x = leftPos;
		int y = topPos + WIDGET_DY;

		scriptsLabel = new Label(x + LIST_LABEL_X, y + LIST_LABEL_Y, CommonComponents.EMPTY).withShadow();
		scriptsLabel.text = CommonComponents.EMPTY;

		confirmButton = new IconButton(x + CONFIRM_X, y + CONFIRM_Y, AllIcons.I_CONFIRM);
		confirmButton.withCallback(this::requestImport);
		confirmButton.active = false; // 列表到手之前没有任何东西可选
		addRenderableWidget(confirmButton);

		refreshButton = new IconButton(x + REFRESH_X, y + REFRESH_Y, AllIcons.I_REFRESH);
		refreshButton.withCallback(this::requestList);
		refreshButton.setToolTip(refresh);
		addRenderableWidget(refreshButton);

		rebuildList();

		extraAreas = ImmutableList.of(
			new Rect2i(x + BACKGROUND.getWidth(), y + BACKGROUND.getHeight() - 40, 48, 48),
			new Rect2i(refreshButton.getX(), refreshButton.getY(), refreshButton.getWidth(),
				refreshButton.getHeight()));
	}

	/**
	 * 按 {@link #names} 重建选择框（打开界面、刷新、包到达三条路共用）。
	 *
	 * <p>为什么整块重建而不是"往旧控件里塞新选项"：{@code SelectionScrollInput.forOptions} 不会重置
	 * {@code state}，列表换短之后那个下标会指到列表外（上游刷新那颗按钮也是整块重建的，
	 * {@code :108-120}）。重建时**先摘掉旧控件**，否则会同时存在两个选择框、两个都吃滚轮。
	 */
	private void rebuildList() {
		if (scriptsArea != null) {
			removeWidget(scriptsArea);
			scriptsArea = null;
		}
		if (scriptsLabel != null)
			removeWidget(scriptsLabel);

		if (names.isEmpty())
			return; // 空列表：只剩 renderBg 里那行提示，确认键在 containerTick 里被灰掉

		scriptsLabel = new Label(leftPos + LIST_LABEL_X, topPos + WIDGET_DY + LIST_LABEL_Y, CommonComponents.EMPTY)
			.withShadow();
		scriptsLabel.text = CommonComponents.EMPTY;
		List<Component> options = new ArrayList<>(names.size());
		for (String name : names)
			options.add(Component.literal(name));
		scriptsArea = new SelectionScrollInput(leftPos + LIST_X, topPos + WIDGET_DY + LIST_Y, LIST_W, LIST_H)
			.forOptions(options)
			.titled(availableTitle.plainCopy())
			.writingTo(scriptsLabel);
		scriptsArea.onChanged(); // 让标签立刻显示第一项（上游刷新那条路也调它，:115）
		addRenderableWidget(scriptsArea);
		addRenderableWidget(scriptsLabel);
	}

	/** 重新请求列表（刷新按钮）：服务端回包后由 {@link #acceptList} 重建控件。 */
	private void requestList() {
		PacketDistributor.sendToServer(new ProgramImportRequestPacket());
	}

	/**
	 * 确认：把**选中的那个名字**发给服务端，由它校验并推进度。
	 *
	 * <p>这里只做"有没有东西可选"的本地判断（否则会发一个空名字的包出去）；
	 * 其余一律交给服务端（理由见类注释）。
	 */
	private void requestImport() {
		if (scriptsArea == null || names.isEmpty())
			return;
		int index = scriptsArea.getState();
		if (index < 0 || index >= names.size())
			return;
		lastChasingProgress = chasingProgress = progress = 0;
		PacketDistributor.sendToServer(new ProgramImportStartPacket(names.get(index)));
	}

	/**
	 * 画底板 / 标题三态 / 空列表提示 / 右下角物品 / 进度条 —— **四行进度条逐字照抄上游**
	 * （{@code :164-168}），只有宽度来源换成了我们自己的 {@code chasingProgress}。
	 */
	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int invX = getLeftOfCentered(PLAYER_INVENTORY.getWidth());
		int invY = topPos + BACKGROUND.getHeight() + 4;
		renderPlayerInventory(graphics, invX, invY);

		int x = leftPos;
		int y = topPos;

		BACKGROUND.render(graphics, x, y);

		// 标题三态（上游 :145-154）：正在导入 → 完成（输出槽有东西）→ 平时的标题
		Component titleText;
		if (menu.contentHolder != null && menu.contentHolder.isImporting)
			titleText = uploading;
		else if (menu.getSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT)
			.hasItem())
			titleText = finished;
		else
			titleText = title;

		graphics.drawString(font, titleText, x + (BACKGROUND.getWidth() - TITLE_INSET - font.width(titleText)) / 2,
			y + TITLE_Y, TITLE_COLOR, false);

		if (scriptsArea == null)
			graphics.drawString(font, noScripts, x + NO_SCRIPTS_X, y + NO_SCRIPTS_Y, NO_SCRIPTS_COLOR);

		GuiGameElement.of(renderedItem)
			.<GuiGameElement.GuiRenderBuilder>at(x + BACKGROUND.getWidth(), y + BACKGROUND.getHeight() - 40,
				RENDERED_ITEM_Z)
			.scale(RENDERED_ITEM_SCALE)
			.render(graphics);

		int width = (int) (PROGRESS.getWidth()
			* Mth.lerp(partialTicks, lastChasingProgress, chasingProgress));
		PROGRESS.renderPartially(graphics, x + PROGRESS_X, y + PROGRESS_Y, width);
	}

	/**
	 * 每 tick 的追赶逻辑（上游 {@code :172-212} 逐行照抄）：
	 * 导入中 ⇒ {@code chasing += (progress - chasing) * .5}（进度条平滑地追服务端那份）；
	 * 完成（输出槽有东西）⇒ 直接钉在 1；都没有 ⇒ 归零。
	 *
	 * <p>★ 这里读的 {@code importProgress} 是**服务端同步下来的**，客户端一帧都不自己推进
	 * （{@code ProgramImportTableBlockEntity} 的类注释解释了为什么这条链必须反过来）。
	 */
	@Override
	protected void containerTick() {
		super.containerTick();

		boolean finishedNow = menu.getSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT)
			.hasItem();
		boolean importing = menu.contentHolder != null && menu.contentHolder.isImporting;

		if (importing || finishedNow) {
			if (finishedNow) {
				chasingProgress = lastChasingProgress = progress = 1;
			} else {
				lastChasingProgress = chasingProgress;
				progress = menu.contentHolder.importProgress;
				chasingProgress += (progress - chasingProgress) * .5f;
			}
			confirmButton.active = false;

			if (scriptsLabel != null) {
				scriptsLabel.colored(LABEL_IMPORTING_COLOR);
				// 导入中显示"文件 + 还有几秒"；完成态那一支没有文件名可显示（服务端已经清掉了）
				scriptsLabel.text = importing ? importingLabel() : null;
			}
			if (scriptsArea != null)
				scriptsArea.visible = false;
		} else {
			progress = 0;
			chasingProgress = lastChasingProgress = 0;
			confirmButton.active = !names.isEmpty();

			if (scriptsLabel != null)
				scriptsLabel.colored(LABEL_IDLE_COLOR);
			if (scriptsArea != null) {
				scriptsArea.writingTo(scriptsLabel);
				scriptsArea.visible = true;
			}
		}
	}

	/**
	 * "《名字》 还有 N 秒"。
	 *
	 * <p>秒数由**固定时长**与当前进度算出来（{@code IMPORT_TICKS} 是常量，不是文件大小），
	 * 所以它是个诚实的倒计时：每 tick 少一点，到 0 的那一刻进度到 1、输出槽出现新卡。
	 * 取 {@code ceil} 且下限 1：显示 "0 秒" 而界面还在动会让人以为卡住了。
	 */
	private Component importingLabel() {
		String file = menu.contentHolder == null || menu.contentHolder.importingFile == null ? ""
			: menu.contentHolder.importingFile;
		int secondsLeft = Math.max(1,
			Mth.ceil((1f - Mth.clamp(progress, 0f, 1f)) * ProgramImportTableBlockEntity.IMPORT_TICKS / 20f));
		return Component.translatable("create_sequenced_mechanical_arm.gui.import.progress_time", file, secondsLeft);
	}

	/**
	 * 底板右下角那颗放大的物品算"GUI 之外"、会挡 JEI（上游同款两个区域，{@code :128-131}）。
	 * 这里仍然给两个：物品区与刷新按钮区。
	 */
	@Override
	public List<Rect2i> getExtraAreas() {
		return extraAreas;
	}

}
