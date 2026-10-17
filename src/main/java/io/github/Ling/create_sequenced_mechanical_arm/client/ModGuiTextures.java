package io.github.Ling.create_sequenced_mechanical_arm.client;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 本模组**自己的**GUI 贴图枚举 —— 只有我们真的用到的这几张（用户要求资源自持，方便自己画）。
 *
 * <h2>为什么要这一层</h2>
 * 两个界面（操作导入桌 {@link ProgramImportScreen}、上传文件名面板 {@link ExportScriptScreen}）原来是直接引用
 * Create 的 {@code AllGuiTextures}（{@code SCHEMATIC_TABLE} / {@code SCHEMATIC_TABLE_PROGRESS} / {@code SCHEMATIC_PROMPT}），
 * 那些常量的 {@code location} 指向 {@code create:textures/gui/*.png} ——
 * <b>贴图在别人 jar 里，用户改不了</b>。所以那两张 PNG 已经整张复制进
 * {@code assets/create_sequenced_mechanical_arm/textures/gui/}（{@code program_table.png} / {@code prompt_panel.png}），
 * 这里把用到的三个 sprite 的 **uv 与宽高原样搬过来**（数值逐字抄自 Create
 * {@code foundation/gui/AllGuiTextures.java:31,34,35}，一个都没自己算）。
 *
 * <h2>形状照 Create 的 {@code AllGuiTextures}</h2>
 * {@code location / startX / startY / width / height} + {@code render(graphics,x,y)}；
 * 另外给进度条加了一个"只画左边 {@code width} 像素"的重载（上游是在 Screen 里手写 {@code blit}，
 * 我们收进枚举，免得界面里再散落一组 uv 数字）。
 *
 * <h2>刻意<b>不</b>搬的东西</h2>
 * <ul>
 *   <li><b>按钮图标</b>（{@code AllIcons} + {@code icons.png} 图集）继续借用 Create ——
 *       用户明确说"大部分按钮就用现在原版风格即可"，那 18×18 的图标不是本片范围；</li>
 *   <li><b>玩家背包面板</b>（{@code AllGuiTextures.PLAYER_INVENTORY}）也继续借用：
 *       它和原版容器界面是同一块通用底板，不属于"蓝图桌/文件名面板"这套我们要自持的美术。</li>
 * </ul>
 *
 * <p>⚠️ {@code getWidth()}/{@code getHeight()} 的一致性很关键：{@link ProgramImportScreen} 用它算窗口尺寸
 * （{@code setWindowSize}）与进度条满宽，改了 uv/宽高必须连贴图一起改，否则会出现"底板画一半"。
 */
public enum ModGuiTextures {

	// ———————— 以下三行的 uv / 宽高逐字抄自 Create AllGuiTextures.java:31,34,35 ————————

	/** 操作导入桌的底板：上游 {@code SCHEMATIC_TABLE("schematics", 10, 139, 214, 85)}。 */
	PROGRAM_IMPORT_TABLE("program_table", 10, 139, 214, 85),
	/** 导入进度条：上游 {@code SCHEMATIC_TABLE_PROGRESS("schematics", 10, 224, 84, 16)}。 */
	PROGRAM_IMPORT_PROGRESS("program_table", 10, 224, 84, 16),
	/** 上传文件名面板：上游 {@code SCHEMATIC_PROMPT("schematics_2", 213, 79)}（三参构造 ⇒ 起点 (0,0)）。 */
	SCRIPT_NAME_PROMPT("prompt_panel", 0, 0, 213, 79);

	public final ResourceLocation location;
	private final int width;
	private final int height;
	private final int startX;
	private final int startY;

	ModGuiTextures(String file, int width, int height) {
		this(file, 0, 0, width, height);
	}

	ModGuiTextures(String file, int startX, int startY, int width, int height) {
		this.location = ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID,
			"textures/gui/" + file + ".png");
		this.width = width;
		this.height = height;
		this.startX = startX;
		this.startY = startY;
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

	/** 整块画出来（上游 {@code AllGuiTextures.render}，{@code :300-302}）。 */
	public void render(GuiGraphics graphics, int x, int y) {
		graphics.blit(location, x, y, startX, startY, width, height);
	}

	/**
	 * 只画左边 {@code partialWidth} 像素 —— 进度条用它（上游在 {@code SchematicTableScreen:164-168} 手写同一个 blit）。
	 *
	 * <p>高度固定取贴图高度（进度条是一整条，不按比例升高），宽度由调用方按进度算 ——
	 * 进度条的观感就是"从左往右长出来"，不是整条缩放。
	 */
	public void renderPartially(GuiGraphics graphics, int x, int y, int partialWidth) {
		graphics.blit(location, x, y, startX, startY, partialWidth, height);
	}

}
