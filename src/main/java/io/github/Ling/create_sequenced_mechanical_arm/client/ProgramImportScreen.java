package io.github.Ling.create_sequenced_mechanical_arm.client;

import static com.simibubi.create.foundation.gui.AllGuiTextures.PLAYER_INVENTORY;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;

import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportFiles;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableMenu;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * 「操作导入桌」界面 —— 布局与进度条与上游蓝图桌**逐像素一致**
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
 *   <li><b>文件列表来自<b>本机磁盘</b></b>：每次 {@code init} 与按"刷新"都直接调
 *       {@link ProgramImportFiles#list()} 扫<b>玩家这台机器</b>上的 {@code sequenced_arm/scripts/}。
 *       ★ 这一步不再经过网络（用户改口径："没必要动服务端文件"）：第一步是客户端发一个空包请求、
 *       服务端读自己的目录再下发名字；现在文件就在玩家本机，一个来回都省了 ——
 *       而且"列表"永远不会与服务端那份（如果它曾经有过）不一致。</li>
 *   <li><b>"打开文件夹"按钮只能管本机，而脚本文件本来就在本机</b>（上游 {@code I_OPEN_FOLDER}，
 *       {@code SchematicTableScreen:98-102}）：{@code Util.getPlatform().openFile(...)} 在客户端打开
 *       **玩家自己这台机器**上的文件夹 —— 见 {@link #openScriptFolder()}。</li>
 *   <li><b>进度条改成"已收字节 / 总字节"</b>（用户口径）：第一步是固定 100 tick 的仪式感，
 *       现在数据是客户端一片片送上去的，诚实的进度只有一种 —— 服务端收到了多少字节。
 *       界面读的是 {@link ProgramImportTableBlockEntity#uploadProgress()} 与
 *       {@code uploadReceived / uploadTotal} 这对同步下来的整数（**同一对数**，
 *       不会出现"字已经 60/60、进度条还在 80%"）。旁边那行字直接写"已收 x / 共 y 字节"，
 *       不再有"还剩几秒"（片的节奏由客户端决定，界面不该替服务端承诺时间）。</li>
 * </ol>
 *
 * <h2>界面仍然不做裁决</h2>
 * 能不能导（输入槽是不是空白表、输出槽空不空、总量超不超、编译过不过、乱序超量超时）全部由服务端判
 * （{@code ProgramImportTableBlockEntity.startUpload / acceptChunk}）。所以确认键**不**按"输入有没有东西 +
 * 输出空不空"变灰：灰按钮说不出理由，而回执说得出（"请先在输入槽放一张空白操作表"）——
 * 菜单里原来那份"输入有东西 + 输出空格"的便捷判定**没有任何调用方**，已随本轮清理删除。
 * 界面自己只拦两类**本机**错误（否则会白发一个包）：列表为空、以及"这个文件读不出来 / 是空的 / 太大"
 * （{@link ProgramImportFiles#read}）。
 *
 * <p>⚠️ {@code AbstractSimiContainerScreen} 的硬约定（照上游）：{@code setWindowSize}/{@code setWindowOffset}
 * 必须在 {@code super.init()} **之前**调用。
 */
public class ProgramImportScreen extends AbstractSimiContainerScreen<ProgramImportTableMenu> {

	/** 只为"打开文件夹"失败时留一条可核对的日志（{@code openFile} 自己那条 error 不带我们的上下文）。 */
	private static final Logger LOGGER = LogUtils.getLogger();

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
	 * 打开文件夹：{@code IconButton(x+20, y+21, I_OPEN_FOLDER)}（上游 {@code SchematicTableScreen:98}）。
	 *
	 * <p>⚠️ 坐标**照抄上游**：{@code x+20} 就是选择框（{@code x+45}）左边那颗 18×18 的图标，
	 * 与底板左沿留 20px —— 用户截图里要的就是这个位置。别自己估。
	 */
	private static final int FOLDER_X = 20;
	private static final int FOLDER_Y = 21;
	/**
	 * 刷新：{@code IconButton(x+206, y+21, I_REFRESH)}（上游 {@code :103}）。
	 *
	 * <p>x+206 = 底板右沿之外 10px（底板 214 宽、控件起点 44 ⇒ 206+18=224 会伸出去一点，
	 * 上游这几个坐标本来就是这样）。
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
	private final Component openFolder = Component.translatable("create_sequenced_mechanical_arm.gui.import.open_folder");
	private final Component noScripts = Component.translatable("create_sequenced_mechanical_arm.gui.import.no_scripts");
	private final Component availableTitle =
		Component.translatable("create_sequenced_mechanical_arm.gui.import.available");

	private ScrollInput scriptsArea;
	private Label scriptsLabel;
	private IconButton confirmButton;
	private IconButton folderButton;
	private IconButton refreshButton;

	private float progress;
	private float chasingProgress;
	private float lastChasingProgress;

	/** 底板右下角那颗放大物品 = 这台机器本身（上游放的是蓝图桌的方块物品）。 */
	private final ItemStack renderedItem = new ItemStack(SequencedArmRegistration.PROGRAM_IMPORT_TABLE_ITEM.get());

	/** 当前本机目录里的脚本名（不含 {@code .sma}）。空 ⇒ 选择框不存在、确认键是灰的。 */
	private List<String> names = List.of();

	private List<Rect2i> extraAreas = Collections.emptyList();

	public ProgramImportScreen(ProgramImportTableMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	@Override
	protected void init() {
		// 这两个必须在 super.init() 之前（AbstractSimiContainerScreen 的注释：它按 imageWidth/offset 摆窗口）
		setWindowSize(BACKGROUND.getWidth(), BACKGROUND.getHeight() + 4 + PLAYER_INVENTORY.getHeight());
		setWindowOffset(-11, 8);
		super.init();

		int x = leftPos;
		int y = topPos + WIDGET_DY;

		scriptsLabel = new Label(x + LIST_LABEL_X, y + LIST_LABEL_Y, CommonComponents.EMPTY).withShadow();
		scriptsLabel.text = CommonComponents.EMPTY;

		confirmButton = new IconButton(x + CONFIRM_X, y + CONFIRM_Y, AllIcons.I_CONFIRM);
		confirmButton.withCallback(this::requestImport);
		confirmButton.active = false; // 列表到手之前没有任何东西可选
		addRenderableWidget(confirmButton);

		// 打开文件夹（上游 SchematicTableScreen:98-102）：位置、图标、回调顺序逐项照抄，
		// 只把目标目录从 CreatePaths.SCHEMATICS_DIR 换成脚本目录的唯一来源 ScriptFiles.directory()
		folderButton = new IconButton(x + FOLDER_X, y + FOLDER_Y, AllIcons.I_OPEN_FOLDER);
		folderButton.withCallback(this::openScriptFolder);
		folderButton.setToolTip(openFolder);
		addRenderableWidget(folderButton);

		refreshButton = new IconButton(x + REFRESH_X, y + REFRESH_Y, AllIcons.I_REFRESH);
		refreshButton.withCallback(this::reloadNames);
		refreshButton.setToolTip(refresh);
		addRenderableWidget(refreshButton);

		// 列表是**本机磁盘**的真相：每次打开都重新扫一遍（与上游 init 里 refresh 同一个位置）
		names = ProgramImportFiles.list();
		rebuildList();

		extraAreas = ImmutableList.of(
			new Rect2i(x + BACKGROUND.getWidth(), y + BACKGROUND.getHeight() - 40, 48, 48),
			new Rect2i(refreshButton.getX(), refreshButton.getY(), refreshButton.getWidth(),
				refreshButton.getHeight()));
	}

	/**
	 * 按 {@link #names} 重建选择框（打开界面、刷新两条路共用）。
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

	/**
	 * 重新扫本机目录（刷新按钮）。
	 *
	 * <p>★ 一个包都不发：脚本目录就在这台机器上，"刷新"只是再读一次盘。第一步那条
	 * "发个空包问服务端"的路已经删掉了（用户口径：服务端不碰文件）。
	 */
	private void reloadNames() {
		names = ProgramImportFiles.list();
		rebuildList();
	}

	/**
	 * ★「打开文件夹」：在**客户端本机**的文件管理器里打开脚本目录。
	 *
	 * <p><b>上游怎么做的</b>：{@code SchematicTableScreen:98-102} ——
	 * {@code folderButton = new IconButton(x + 20, y + 21, AllIcons.I_OPEN_FOLDER)} 配
	 * {@code Util.getPlatform().openFile(CreatePaths.SCHEMATICS_DIR.toFile())}。
	 * 这里的调用与签名逐字照抄，只换目标目录（见下一条）。
	 *
	 * <p><b>为什么必须先在客户端跑</b>：用户要求这颗按钮"真的能打开脚本目录"。
	 * {@code openFile} 只能开**玩家这台机器**上的文件夹，它在客户端（本界面就是客户端类）执行；
	 * 服务端不但开不了玩家的窗口，连 {@code java.awt.Desktop} 在无桌面环境上都不存在。
	 *
	 * <p><b>目录可能还不存在</b>（玩家一次都没导出过脚本）：原版 {@code Util.OS.openFile}
	 * 把内部异常全吞掉、只写一条 error 日志（{@code Util.java:995 → :982-993}），
	 * 所以"路径不存在"对调用方是完全静默的 —— 表现就又是用户最初报的"点了没反应"。
	 * 因此这里先 {@code Files.createDirectories(dir)} 把目录造出来（与"导出脚本时顺手建目录"
	 * 是同一个结果，不会产生任何多余状态），再交给 {@code openFile}。
	 *
	 * <p>任何一步失败都只给提示、不抛出去 —— GUI 里抛异常会直接崩客户端。
	 */
	private void openScriptFolder() {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		Path dir = ScriptFiles.directory();
		try {
			Files.createDirectories(dir); // 没导出过脚本时目录还不存在，先造出来（见方法注释）
		} catch (Exception e) {
			// 建不出来（权限 / 只读盘）就不要再去 openFile 了，否则同样是"点了没反应"
			notifyOpenFailed(player, dir, e);
			return;
		}

		try {
			Util.getPlatform()
				.openFile(dir.toFile());
		} catch (Exception e) {
			// openFile 自己会吞 IOException（Util.java:990-992），但别的平台实现可能抛别的
			notifyOpenFailed(player, dir, e);
		}
	}

	/** 失败提示：一条 actionbar，带上**具体路径**，这样玩家至少能自己走过去（绝不替他把路径藏起来）。 */
	private void notifyOpenFailed(LocalPlayer player, Path dir, Exception cause) {
		if (player != null)
			player.displayClientMessage(Component.translatable(
				"create_sequenced_mechanical_arm.gui.import.open_folder.failed", dir.toString()), true);
		LOGGER.warn("Could not open the script folder {}", dir, cause);
	}

	/**
	 * 确认：**读本机那个文件**，然后交给 {@link ProgramImportUploader} 分片上传。
	 *
	 * <p>这里只做本机能做的判断（列表空 / 名字越界 / 文件读不出来 / 空的 / 太大），
	 * 其余一律交给服务端（理由见类注释）。读盘失败的那几种就地给一条 actionbar ——
	 * 它们跟"服务端收不收"无关，发上去再被拒反而说不清楚。
	 *
	 * <p>★ 上传的是**文件的原始字节**（{@link ProgramImportFiles.Contents#bytes()}），
	 * 不是"解码成 String 再编码回去"：后者会把坏字节悄悄换成 U+FFFD，于是服务端收到的字节数
	 * 与文件大小对不上，一个 60 KiB 的文件可能因此越过上限。
	 */
	private void requestImport() {
		if (scriptsArea == null || names.isEmpty())
			return;
		int index = scriptsArea.getState();
		if (index < 0 || index >= names.size())
			return;
		String name = names.get(index);
		ProgramImportFiles.Contents contents = ProgramImportFiles.read(name);
		if (!contents.ok()) {
			notifyLocal(name, contents);
			return;
		}
		lastChasingProgress = chasingProgress = progress = 0;
		if (!ProgramImportUploader.start(name, contents.bytes())) {
			// 只可能是"已有一段在上传"（界面在传的时候又按了一次）：说一句，别静默
			LocalPlayer player = minecraft == null ? null : minecraft.player;
			if (player != null)
				player.displayClientMessage(
					Component.translatable("create_sequenced_mechanical_arm.import.already"), true);
		}
	}

	/** 本机读盘失败的回执（actionbar，与包那边的回执同一条通道）。 */
	private void notifyLocal(String name, ProgramImportFiles.Contents contents) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null)
			return;
		Component message = switch (contents.problem()) {
			case BAD_NAME -> Component.translatable("create_sequenced_mechanical_arm.import.bad_name");
			case NOT_FOUND -> Component.translatable("create_sequenced_mechanical_arm.import.not_found", name);
			case UNREADABLE -> Component.translatable("create_sequenced_mechanical_arm.import.unreadable", name);
			case TOO_LARGE -> Component.translatable("create_sequenced_mechanical_arm.import.too_large",
				contents.sizeBytes(), ProgramText.MAX_BYTES);
			case EMPTY -> Component.translatable("create_sequenced_mechanical_arm.import.empty_file", name);
			// 不可达（ok() 为真时调用方根本不会进来）：留一句明确的话而不是空白
			case OK -> Component.translatable("create_sequenced_mechanical_arm.import.unreadable", name);
		};
		player.displayClientMessage(message, true);
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
	 * <p>★ 这里读的 {@code uploadProgress()} 是**服务端同步下来的**「已收字节 / 总字节」，
	 * 客户端一帧都不自己推进（客户端只知道"我发到第几片了"，那不等于"服务端收下了几片"）。
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
				progress = menu.contentHolder.uploadProgress();
				chasingProgress += (progress - chasingProgress) * .5f;
			}
			confirmButton.active = false;

			if (scriptsLabel != null) {
				scriptsLabel.colored(LABEL_IMPORTING_COLOR);
				// 导入中显示"文件 + 已收/共多少字节"；完成态那一支没有文件名可显示（服务端已经清掉了）
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
	 * "《名字》 已收 x / 共 y 字节"。
	 *
	 * <p>两个数都是**服务端同步下来的判定依据**（{@code uploadReceived / uploadTotal}），
	 * 不是客户端自己数出来的 —— 客户端只知道自己发了几片，"服务端收下了多少"是它的答案。
	 * 这与进度条用的是同一对数（见 {@code ProgramImportTableBlockEntity.uploadProgress()}）。
	 */
	private Component importingLabel() {
		String file = menu.contentHolder == null || menu.contentHolder.importingFile == null ? ""
			: menu.contentHolder.importingFile;
		int received = menu.contentHolder == null ? 0 : menu.contentHolder.uploadReceived;
		int total = menu.contentHolder == null ? 0 : menu.contentHolder.uploadTotal;
		return Component.translatable("create_sequenced_mechanical_arm.gui.import.progress_bytes", file, received,
			total);
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
