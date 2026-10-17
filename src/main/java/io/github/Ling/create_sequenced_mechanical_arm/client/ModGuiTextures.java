package io.github.Ling.create_sequenced_mechanical_arm.client;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 本模组**自己的**GUI 贴图枚举 —— 只有我们真的用到的这些张（用户要求资源自持，方便自己画）。
 *
 * <h2>为什么要这一层</h2>
 * 界面原来直接引用 Create 的 {@code AllGuiTextures}，那些常量的 {@code location} 指向
 * {@code create:textures/gui/*.png} —— <b>贴图在别人 jar 里，用户改不了</b>。
 * 用户这轮的口径是"把整体的暗色改成黄铜色"：所以先把**主体贴图**（面板 / 卡片 / 胶囊 / 条件 /
 * 指针 / 滚动 / 编辑器 / 输入框底）搬进本模组、换成我们自己的枚举名，**按钮与图标不动**
 * （继续用 Create 的 {@code IconButton} + {@code AllIcons}）—— 用户之后自己给这几张 PNG 上色即可。
 *
 * <h2>三批搬运（每批一张表，uv/宽高逐字抄自 Create {@code foundation/gui/AllGuiTextures.java}）</h2>
 * <ul>
 *   <li><b>第 3 片</b>：界面自持 —— 操作导入桌 {@link ProgramImportScreen}、上传文件名面板
 *       {@link ExportScriptScreen}（{@code program_table.png} / {@code prompt_panel.png}）；</li>
 *   <li><b>第 4 片</b>：操作表编辑器 {@link ArmProgramScreen} 的<b>主体贴图</b>
 *       （{@code program_editor.png} ← Create {@code schedule.png}，
 *       {@code program_editor_extra.png} ← {@code schedule_2.png}）；</li>
 *   <li><b>第 4 片·裁剪</b>：有两个 sprite 家族在**别的、不该整张搬**的图集里，所以只把它们裁成独立小图 ——
 *       {@code data_area.png}（输入框底，从 {@code display_link.png} 裁）、
 *       {@code scroll_bar.png}（竖向滚动条，从 {@code stock_keeper.png} 裁）。裁完 uv 要减去裁剪原点，
 *       见下面对应的注释。</li>
 * </ul>
 *
 * <h2>⚠️ 图集尺寸不再都是 256×256</h2>
 * 上游 {@code AllGuiTextures.render} 用 7 参 {@code graphics.blit(...)}，它**写死**图集是 256×256；
 * 我们裁出来的那两张只有 {@code 8×18} / {@code 5×20}。所以这里每个常量都带自己的图集尺寸
 * （默认 256×256），{@link #render} / {@link #renderStretched} 一律走带 {@code textureWidth/Height}
 * 的重载 —— 否则 uv 会被按 256 算，画出来是错位的。
 *
 * <h2>为什么不用 {@code UIRenderHelper.drawStretched}</h2>
 * 它要求 {@code TextureSheetSegment}，而它内部把 uv 也写死成 {@code 图集 = 256}
 * （{@code :342-347}：{@code getStartX() / 256f}）—— 对小图集是错的。所以拉伸收进本枚举
 * （{@link #renderStretched}），z 用 {@code pose().translate(0,0,z)} 表达，
 * 与上游"把 z 加在顶点上"等价（本文件 {@code ArmProgramScreen} 里滚条早就在用同一种写法）。
 *
 * <h2>刻意<b>不</b>搬的东西</h2>
 * <ul>
 *   <li><b>按钮图标</b>（{@code AllIcons} + {@code icons.png} 图集）继续借用 Create ——
 *       用户明确说"按钮和图标不用改"，那 18×18 的图标不是本片范围；</li>
 *   <li><b>玩家背包面板</b>（{@code AllGuiTextures.PLAYER_INVENTORY}）也继续借用：
 *       它和原版容器界面是同一块通用底板，不属于我们要自持的美术；</li>
 *   <li><b>整张 {@code widgets.png} / {@code icons.png}</b> 一张都不复制 —— 那是按钮图集。</li>
 * </ul>
 *
 * <p>⚠️ {@code getWidth()}/{@code getHeight()} 的一致性很关键：{@link ArmProgramScreen} 用它算窗口尺寸
 * （{@code setWindowSize}）、标题居中与卡片/胶囊宽度，{@link ProgramImportScreen} 用它算进度条满宽。
 * 这些数值与 Create 原值**逐字相同**，换引用不改布局。
 */
public enum ModGuiTextures {

	// ———————— 第 3 片：uv / 宽高逐字抄自 Create AllGuiTextures.java:31,34,35 ————————

	/** 操作导入桌的底板：上游 {@code SCHEMATIC_TABLE("schematics", 10, 139, 214, 85)}。 */
	PROGRAM_IMPORT_TABLE("program_table", 10, 139, 214, 85),
	/** 导入进度条：上游 {@code SCHEMATIC_TABLE_PROGRESS("schematics", 10, 224, 84, 16)}。 */
	PROGRAM_IMPORT_PROGRESS("program_table", 10, 224, 84, 16),
	/** 上传文件名面板：上游 {@code SCHEMATIC_PROMPT("schematics_2", 213, 79)}（三参构造 ⇒ 起点 (0,0)）。 */
	SCRIPT_NAME_PROMPT("prompt_panel", 0, 0, 213, 79),

	// ———————— 第 4 片 A：主面板 + 卡片 + 条带 + 胶囊 + 指针（图集 program_editor.png = Create schedule.png 256×256）———————
	// uv 逐字抄自 AllGuiTextures.java:92-121（数值没动，只是换了名字与图集）。

	/** 主面板：上游 {@code SCHEDULE("schedule", 256, 226)}。窗口尺寸、标题居中、卡片宽度都从它算。 */
	EDITOR_PANEL("program_editor", 0, 0, 256, 226),
	/** 卡片主体色：上游 {@code SCHEDULE_CARD_LIGHT("schedule", 7, 233, 1, 1)}。 */
	CARD_LIGHT("program_editor", 7, 233, 1, 1),
	/** 卡片中间层：上游 {@code SCHEDULE_CARD_MEDIUM("schedule", 6, 233, 1, 1)}。 */
	CARD_MEDIUM("program_editor", 6, 233, 1, 1),
	/** 卡片描边：上游 {@code SCHEDULE_CARD_DARK("schedule", 5, 233, 1, 1)}。 */
	CARD_DARK("program_editor", 5, 233, 1, 1),
	/** 卡片"上移"：上游 {@code SCHEDULE_CARD_MOVE_UP("schedule", 51, 230, 12, 12)}。 */
	CARD_MOVE_UP("program_editor", 51, 230, 12, 12),
	/** 卡片"下移"：上游 {@code SCHEDULE_CARD_MOVE_DOWN("schedule", 65, 230, 12, 12)}。 */
	CARD_MOVE_DOWN("program_editor", 65, 230, 12, 12),
	/** 卡片"删除"：上游 {@code SCHEDULE_CARD_REMOVE("schedule", 51, 243, 12, 12)}。 */
	CARD_REMOVE("program_editor", 51, 243, 12, 12),
	/** 卡片"复制"：上游 {@code SCHEDULE_CARD_DUPLICATE("schedule", 65, 243, 12, 12)}。 */
	CARD_DUPLICATE("program_editor", 65, 243, 12, 12),
	/** 列表末尾"新卡片"底板：上游 {@code SCHEDULE_CARD_NEW("schedule", 79, 239, 16, 16)}。 */
	CARD_NEW("program_editor", 79, 239, 16, 16),
	/** 行尾"添加属性"＋：上游 {@code SCHEDULE_CONDITION_NEW("schedule", 96, 239, 19, 16)}。 */
	CONDITION_NEW("program_editor", 96, 239, 19, 16),
	/** 胶囊左端：上游 {@code SCHEDULE_CONDITION_LEFT("schedule", 116, 239, 6, 16)}。 */
	CONDITION_LEFT("program_editor", 116, 239, 6, 16),
	/** 胶囊左端（无图标版）：上游 {@code SCHEDULE_CONDITION_LEFT_CLEAN("schedule", 147, 239, 2, 16)}。 */
	CONDITION_LEFT_CLEAN("program_editor", 147, 239, 2, 16),
	/** 胶囊中段（横向拉伸）：上游 {@code SCHEDULE_CONDITION_MIDDLE("schedule", 123, 239, 1, 16)}。 */
	CONDITION_MIDDLE("program_editor", 123, 239, 1, 16),
	/** 胶囊上的虚拟物品槽：上游 {@code SCHEDULE_CONDITION_ITEM("schedule", 125, 239, 18, 16)}。 */
	CONDITION_ITEM("program_editor", 125, 239, 18, 16),
	/** 胶囊右端：上游 {@code SCHEDULE_CONDITION_RIGHT("schedule", 144, 239, 2, 16)}。 */
	CONDITION_RIGHT("program_editor", 144, 239, 2, 16),
	/** 横向翻格左箭头：上游 {@code SCHEDULE_SCROLL_LEFT("schedule", 161, 247, 4, 8)}。 */
	SCROLL_LEFT("program_editor", 161, 247, 4, 8),
	/** 横向翻格右箭头：上游 {@code SCHEDULE_SCROLL_RIGHT("schedule", 166, 247, 4, 8)}。 */
	SCROLL_RIGHT("program_editor", 166, 247, 4, 8),
	/** 左侧竖轨（深）：上游 {@code SCHEDULE_STRIP_DARK("schedule", 5, 235, 3, 1)}，竖向拉伸。 */
	STRIP_DARK("program_editor", 5, 235, 3, 1),
	/** 左侧竖轨（浅）：上游 {@code SCHEDULE_STRIP_LIGHT("schedule", 5, 237, 3, 1)}，竖向拉伸。 */
	STRIP_LIGHT("program_editor", 5, 237, 3, 1),
	/** 卡片左侧"等待"图标条：上游 {@code SCHEDULE_STRIP_WAIT("schedule", 1, 239, 11, 16)}。 */
	STRIP_WAIT("program_editor", 1, 239, 11, 16),
	/** 卡片左侧"动作"图标条：上游 {@code SCHEDULE_STRIP_TRAVEL("schedule", 12, 239, 11, 16)}。 */
	STRIP_TRAVEL("program_editor", 12, 239, 11, 16),
	/** 卡与卡之间的虚线：上游 {@code SCHEDULE_STRIP_DOTTED("schedule", 23, 239, 11, 16)}。 */
	STRIP_DOTTED("program_editor", 23, 239, 11, 16),
	/** 列表末尾的终止条：上游 {@code SCHEDULE_STRIP_END("schedule", 34, 239, 11, 16)}。 */
	STRIP_END("program_editor", 34, 239, 11, 16),
	/** 卡片左侧"任务"图标条：上游 {@code SCHEDULE_STRIP_ACTION("schedule", 209, 239, 11, 16)}。 */
	STRIP_ACTION("program_editor", 209, 239, 11, 16),
	/** 插入位置指针：上游 {@code SCHEDULE_POINTER("schedule", 185, 239, 21, 16)}。 */
	POINTER("program_editor", 185, 239, 21, 16),
	/** 指针（目标不在视野内）：上游 {@code SCHEDULE_POINTER_OFFSCREEN("schedule", 171, 239, 13, 16)}。 */
	POINTER_OFFSCREEN("program_editor", 171, 239, 13, 16),

	// ———————— 第 4 片 B：编辑子面板（图集 program_editor_extra.png = Create schedule_2.png 256×256）———————

	/** 编辑子面板：上游 {@code SCHEDULE_EDITOR("schedule_2", 256, 89)}（三参构造 ⇒ 起点 (0,0)）。 */
	EDITOR_BODY("program_editor_extra", 0, 0, 256, 89),
	/** 子面板里的幽灵物品槽：上游 {@code SCHEDULE_EDITOR_ADDITIONAL_SLOT("schedule_2", 55, 47, 32, 18)}。 */
	EDITOR_GHOST_SLOT("program_editor_extra", 55, 47, 32, 18),

	// ———————— 第 4 片 C·裁剪：输入框底（图集 data_area.png = 从 Create display_link.png 裁 8×18 @(0,163)）———————
	// 为什么不整张搬：display_link.png 是"显示链接 / 电梯联络"那两块界面的图集，整张搬进来一整套无关美术，
	// 而我们要的只是 x=0..8 / y=163..181 这 8×18 的一条。裁剪后 uv 全部减掉裁剪原点 (0,163)：
	//   DATA_AREA_START(0,163) → (0,0)   DATA_AREA(3,163) → (3,0)   DATA_AREA_END(5,163) → (5,0)
	// 注意这三个 sprite 的图集尺寸是 8×18（不是 256×256），所以走五参构造传图集尺寸。

	/** 输入框底左包头：上游 {@code DATA_AREA_START("display_link", 0, 163, 2, 18)}。 */
	DATA_AREA_START("data_area", 0, 0, 2, 18, 8, 18),
	/** 输入框底可拉伸主体：上游 {@code DATA_AREA("display_link", 3, 163, 1, 18)}。 */
	DATA_AREA("data_area", 3, 0, 1, 18, 8, 18),
	/** 输入框底右包头：上游 {@code DATA_AREA_END("display_link", 5, 163, 2, 18)}。 */
	DATA_AREA_END("data_area", 5, 0, 2, 18, 8, 18),

	// ———————— 第 4 片 D·裁剪：竖向滚动条（图集 scroll_bar.png = 从 Create stock_keeper.png 裁 5×20 @(219,192)）———————
	// 为什么不整张搬：stock_keeper.png 是整个"仓库管理员请求界面"的图集，我们要的只是右侧滑块那 5 像素宽的一条。
	// 裁剪原点 (219,192)，裁出 5×20（y=192..212 正好覆盖 TOP/PAD/MID/BOT 四段）：
	//   SCROLL_TOP(219,192)→(0,0)   SCROLL_PAD(219,196)→(0,4)   SCROLL_MID(219,197)→(0,5)   SCROLL_BOT(219,207)→(0,15)
	// 图集尺寸是 5×20（不是 256×256）。

	/** 滑块上包头：上游 {@code STOCK_KEEPER_REQUEST_SCROLL_TOP("stock_keeper", 219, 192, 5, 4)}。 */
	SCROLL_BAR_TOP("scroll_bar", 0, 0, 5, 4, 5, 20),
	/** 滑块可拉伸主体：上游 {@code STOCK_KEEPER_REQUEST_SCROLL_PAD("stock_keeper", 219, 196, 5, 1)}。 */
	SCROLL_BAR_PAD("scroll_bar", 0, 4, 5, 1, 5, 20),
	/** 滑块中间的握把：上游 {@code STOCK_KEEPER_REQUEST_SCROLL_MID("stock_keeper", 219, 197, 5, 9)}。 */
	SCROLL_BAR_MID("scroll_bar", 0, 5, 5, 9, 5, 20),
	/** 滑块下包头：上游 {@code STOCK_KEEPER_REQUEST_SCROLL_BOT("stock_keeper", 219, 207, 5, 5)}。 */
	SCROLL_BAR_BOT("scroll_bar", 0, 15, 5, 5, 5, 20);

	public final ResourceLocation location;
	private final int width;
	private final int height;
	private final int startX;
	private final int startY;
	/** 这张图集 PNG 的实际像素尺寸 —— 上游写死 256，我们裁过的那两张不是，见类注释。 */
	private final int sheetWidth;
	private final int sheetHeight;

	/** 整张图集就是 256×256（Create 的惯例）：{@code (file, width, height)} = 起点 (0,0)。 */
	ModGuiTextures(String file, int width, int height) {
		this(file, 0, 0, width, height);
	}

	/** 整张图集就是 256×256，但 sprite 有 uv 起点（上游四参构造 {@code :282-284}）。 */
	ModGuiTextures(String file, int startX, int startY, int width, int height) {
		this(file, startX, startY, width, height, 256, 256);
	}

	/** 图集不是 256×256（我们裁出来的小图必须走这个）。 */
	ModGuiTextures(String file, int startX, int startY, int width, int height, int sheetWidth, int sheetHeight) {
		this.location = ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID,
			"textures/gui/" + file + ".png");
		this.width = width;
		this.height = height;
		this.startX = startX;
		this.startY = startY;
		this.sheetWidth = sheetWidth;
		this.sheetHeight = sheetHeight;
	}

	public ResourceLocation getLocation() {
		return location;
	}

	public int getStartX() {
		return startX;
	}

	public int getStartY() {
		return startY;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	/** 整块画出来（上游 {@code AllGuiTextures.render}，{@code :300-302}；图集尺寸按本枚举自己那张算）。 */
	public void render(GuiGraphics graphics, int x, int y) {
		graphics.blit(location, x, y, width, height, (float) startX, (float) startY, width, height, sheetWidth,
			sheetHeight);
	}

	/**
	 * 整块拉伸到 {@code w × h} —— 等价于上游 {@code UIRenderHelper.drawStretched}（{@code :342-347}），
	 * 但图集尺寸取本枚举自己的（上游在那里写死 {@code /256f}，对我们的 8×18 / 5×20 是错的）。
	 *
	 * <p>{@code z} 与上游同义：上游把它加在四个顶点上，这里用 {@code pose().translate} 表达 ——
	 * 两者都只是把这一笔画深/画浅一点，行为和本类里滚条那处 {@code translate(0,0,300)} 一致。
	 */
	public void renderStretched(GuiGraphics graphics, int x, int y, int w, int h, int z) {
		graphics.pose()
			.pushPose();
		graphics.pose()
			.translate(0, 0, z);
		graphics.blit(location, x, y, w, h, (float) startX, (float) startY, width, height, sheetWidth, sheetHeight);
		graphics.pose()
			.popPose();
	}

	/**
	 * 只画左边 {@code partialWidth} 像素 —— 进度条用它（上游在 {@code SchematicTableScreen:164-168} 手写同一个 blit）。
	 *
	 * <p>高度固定取贴图高度（进度条是一整条，不按比例升高），宽度由调用方按进度算 ——
	 * 进度条的观感就是"从左往右长出来"，不是整条缩放。
	 */
	public void renderPartially(GuiGraphics graphics, int x, int y, int partialWidth) {
		graphics.blit(location, x, y, partialWidth, height, (float) startX, (float) startY, partialWidth, height,
			sheetWidth, sheetHeight);
	}

}
