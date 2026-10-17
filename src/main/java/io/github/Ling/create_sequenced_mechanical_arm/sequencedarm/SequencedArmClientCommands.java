package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportFiles;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.network.ArmProgramTextPacket;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /sma code export|import|check} —— **客户端**命令（用户口径："指令就变为客户端但是需要权限的指令，
 * 仅仅作为一个方便测试或者创造玩家快速导入导出的方法"）。
 *
 * <h2>为什么从服务端搬到客户端</h2>
 * 第一步这三条命令跑在服务端、读写的是**服端**的 {@code run/sequenced_arm/scripts/}（"管理员后门"）。
 * 用户把口径改成"服务端彻底不碰脚本文件"之后，服务端那条路（连带 {@code SequencedArmCommands} 里的
 * {@code scriptDir/scriptFile}）已经删掉了：文件属于玩家本机，只有客户端能读写它。
 * 于是这三条命令变成"**本机文件 ↔ 手上的操作表**"的快捷方式 —— 方便测试与创造玩家，
 * 而不是一套服务端功能。
 *
 * <h2>注册方式（核过源码）</h2>
 * <ul>
 *   <li>事件：{@link RegisterClientCommandsEvent}（{@code x/net/neoforged/neoforge/client/event/
 *       RegisterClientCommandsEvent.java:37-60}），**游戏总线**、只在物理客户端触发
 *       （{@code ClientNeoForgeMod:52} 里 {@code ClientCommandHandler.init()} 在登录时
 *       通过 {@code NeoForge.EVENT_BUS.post(...)} 发它，见 {@code ClientCommandHandler.java:63}）；</li>
 *   <li>分发器类型是 {@code CommandDispatcher<CommandSourceStack>}，与 {@link CommandSourceStack} 同源；</li>
 *   <li>执行时用的源是 {@code ClientCommandSourceStack}（{@code ClientCommandHandler.java:106-110}），
 *       它把 {@code player.getPermissionLevel()} 当权限、并把玩家本身当 {@code executing} 传进去；</li>
 *   <li>分发链：聊天栏先试客户端命令（{@code ClientPacketListener.sendCommand:2541-2542} →
 *       {@code ClientCommandHandler.runCommand}），**解析不了才转发给服务端**
 *       （{@code runCommand:154-159} 对 {@code dispatcherUnknownCommand/Argument} 返回 false）。
 *       所以服务端那几条 {@code /sma info|preset|…} 不受影响。</li>
 * </ul>
 *
 * <h2>★ 权限判据：{@code hasPermissions(2) || isCreative()} —— 都核过源码</h2>
 * <ul>
 *   <li>{@code Entity.hasPermissions(int)}（{@code Entity.java:3275-3277}）= {@code getPermissionLevel() >= level}；</li>
 *   <li>{@code LocalPlayer.getPermissionLevel()}（{@code LocalPlayer.java:442-445}）返回客户端缓存的
 *       {@code permissionLevel}，它由**服务端**通过 {@code ClientboundEntityEventPacket}(id 24..28) 下发
 *       （{@code LocalPlayer.handleEntityEvent:512-518}；发送侧 {@code PlayerList.sendPlayerPermissionLevel:628-640}，
 *       进服时 {@code PlayerList.java:210} 就发一次、{@code /op} 与 {@code /deop} 再发一次
 *       （{@code :615}/{:code :624}），单人游戏由 {@code IntegratedServer.publishServer:213} 设置）
 *       ⇒ 客户端上这个值**可靠**，不是"猜一个 0"；</li>
 *   <li>{@code LocalPlayer.isCreative()} 来自 {@code AbstractClientPlayer:41-45}（读 {@code PlayerInfo} 的
 *       游戏模式），也是客户端权威数据。</li>
 * </ul>
 * 两者取或：真正的 OP 在非创造模式也该能用（测试常常在生存里跑），而创造玩家没有 OP
 * 时这也是个"方便测试"的入口 —— 正是用户说的用途。
 *
 * <p>⚠️ <b>刻意不用 Brigadier 的 {@code .requires(...)}</b>：{@code requires} 为假时 Brigadier 报的是
 * "未知参数"，而 {@code ClientCommandHandler.runCommand} 对那个异常类型**返回 false ⇒ 把命令转发给服务端**
 * ⇒ 玩家会收到服务端那句"未知的命令"，而不是我们这条明确的"需要权限"。
 * 所以门开在**执行体第一行**（{@link #allowed}），拒绝时给一条 lang 文案。
 *
 * <p>⚠️ <b>不能用 {@code source.getPlayer()}</b>：{@code CommandSourceStack.getPlayer():424-427} 只认
 * {@code ServerPlayer}，客户端一定是 {@code null}。要用 {@code getEntity()}（就是本地玩家）
 * 或 {@code Minecraft.getInstance().player}。同理 {@code source.getLevel()} / {@code getServer()}
 * 在 {@code ClientCommandSourceStack} 里直接抛 {@code UnsupportedOperationException}，一行都不能调。
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, value = Dist.CLIENT)
public final class SequencedArmClientCommands {

	private SequencedArmClientCommands() {
	}

	@SubscribeEvent
	public static void register(RegisterClientCommandsEvent event) {
		event.getDispatcher()
			.register(Commands.literal("sma")
				.then(codeCommand()));
	}

	/**
	 * {@code /sma code …} 三个枝叶。
	 *
	 * <p>只注册 {@code code} 这一枝（不是整棵 {@code /sma}）：别的子命令（{@code info/hold/preset/…}）
	 * 仍然只有服务端有，客户端这边不认识的参数会被转发回去（见类注释的分发链）。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> codeCommand() {
		return Commands.literal("code")
			// 不给名字 ⇒ 自动挑一个没被占用的 card_N.sma（同名不覆盖，见 export）
			.then(Commands.literal("export")
				.executes(ctx -> export(ctx.getSource(), null))
				.then(Commands.argument("名字", StringArgumentType.word())
					.executes(ctx -> export(ctx.getSource(), StringArgumentType.getString(ctx, "名字")))))
			.then(Commands.literal("check")
				.executes(ctx -> check(ctx.getSource())))
			.then(Commands.literal("import")
				.then(Commands.argument("名字", StringArgumentType.word())
					.executes(ctx -> importFile(ctx.getSource(), StringArgumentType.getString(ctx, "名字")))));
	}

	// ————————————————————————— export —————————————————————————

	/**
	 * {@code /sma code export [名字]}：**主手操作表的程序文本 → 本机文件**，全程不经过服务端。
	 *
	 * <p>为什么这条路可以完全在本地：读的是主手那张卡上的 {@code ArmProgramText}（客户端有这份物品数据），
	 * 写的是玩家本机的 {@code run/sequenced_arm/scripts/<名字>.sma}（{@link ScriptFiles}）。
	 * 落盘的全部判据与提示都复用 {@link ArmScriptExport#writeFile}（同名不覆盖、UTF-8 无 BOM、
	 * 超上限不写）—— 与"操作记录表上传"那条路的第二个半段**同一个实现**，不另写一份。
	 *
	 * <p>不给名字时自动取第一个没被占用的 {@code card_N}：同一条判据（同名拒绝）意味着
	 * "随手导出"永远不该覆盖上一条。
	 */
	private static int export(CommandSourceStack source, @Nullable String name) {
		if (!allowed(source))
			return 0;
		LocalPlayer player = localPlayer(source);
		if (player == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		ItemStack held = player.getMainHandItem();
		if (!OperationTableItem.isTable(held)) {
			source.sendFailure(tr("code.export_need_card"));
			return 0;
		}
		HolderLookup.Provider registries = registries();
		if (registries == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		String text = textOf(held, registries);
		if (text == null || text.isBlank()) {
			source.sendFailure(tr("code.export_empty"));
			return 0;
		}
		String chosen = name != null ? name : defaultExportName();
		if (chosen == null) {
			// card_1..card_999 全被占：不是错误，是"自己起个名字"
			source.sendFailure(tr("code.export_no_free_name"));
			return 0;
		}
		ArmScriptExport.Outcome outcome = ArmScriptExport.writeFile(chosen, text);
		switch (outcome.problem()) {
			case OK -> source.sendSuccess(() -> tr("code.export_written", String.valueOf(outcome.file())), false);
			case NAME_TAKEN -> source.sendFailure(tr("record.exists", fileName(outcome.file())));
			case BAD_NAME -> source.sendFailure(tr("code.bad_name"));
			case TOO_LARGE -> source.sendFailure(tr("code.import_too_large", outcome.args()[0], outcome.args()[1]));
			case WRITE_FAILED -> source.sendFailure(tr("code.export_failed", String.valueOf(outcome.args()[0])));
			// 不可能出现在 writeFile 这一半（空程序/转不成文本在 prepare 那一半）
			case EMPTY_PROGRAM, NOT_ENCODABLE -> source.sendFailure(tr("code.export_empty"));
		}
		return outcome.ok() ? 1 : 0;
	}

	// ————————————————————————— import（写入必须过服务端） —————————————————————————

	/**
	 * {@code /sma code import <名字>}：**本机文件 → 主手操作表**。
	 *
	 * <p>两段，缺一不可：
	 * <ol>
	 *   <li><b>本地</b>（这一侧）：读文件（{@link ProgramImportFiles#read}：名字 / 存在 / 字节上限 / 非空，
	 *       并顺手吃掉 BOM）→ 编译 → 规模；任一条不过就地报，一个包都不发；</li>
	 *   <li><b>服务端</b>（{@link ArmProgramTextPacket}）：**真正的写入**。客户端直接改本地物品 NBT
	 *       会被服务端权威副本在下一 tick 顶掉（症状："提示说导入了，松手一看卡还是空的"），
	 *       而且卡上存什么本来就该由服务端裁定（它会把这五道判据再跑一遍）。</li>
	 * </ol>
	 */
	private static int importFile(CommandSourceStack source, String name) {
		if (!allowed(source))
			return 0;
		LocalPlayer player = localPlayer(source);
		if (player == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		if (!OperationTableItem.isTable(player.getMainHandItem())) {
			source.sendFailure(tr("code.import_need_card"));
			return 0;
		}
		HolderLookup.Provider registries = registries();
		if (registries == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		ProgramImportFiles.Contents contents = ProgramImportFiles.read(name);
		if (!contents.ok()) {
			source.sendFailure(readProblem(name, contents));
			return 0;
		}
		String text = contents.text() == null ? "" : contents.text();
		ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, registries);
		if (compiled.hasErrors()) {
			reportErrors(source, compiled);
			return 0;
		}
		if (!compiled.value()
			.isWithinLimits()) {
			source.sendFailure(tr("code.import_over_limit", ScriptCompiler.summarize(compiled.value())));
			return 0;
		}
		// ★ 写入走服务端（见方法注释第 2 条）
		PacketDistributor.sendToServer(new ArmProgramTextPacket(text));
		source.sendSuccess(() -> tr("code.import_sent", name), false);
		return 1;
	}

	// ————————————————————————— check —————————————————————————

	/**
	 * {@code /sma code check}：**主手操作表**的文本本地编译一遍，聊天栏报错误 / 警告条数与前几行。
	 *
	 * <p>不再要文件名（用户口径："不再需要文件参数"）：要检查的是"我手上这张卡到底能不能用"，
	 * 而那张卡就是主手这一张 —— 先导出成文件再检查，反而会在"卡上文本"与"文件文本"之间
	 * 多出一道可能不一致的环节。
	 */
	private static int check(CommandSourceStack source) {
		if (!allowed(source))
			return 0;
		LocalPlayer player = localPlayer(source);
		if (player == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		ItemStack held = player.getMainHandItem();
		if (!OperationTableItem.isTable(held)) {
			source.sendFailure(tr("code.import_need_card"));
			return 0;
		}
		HolderLookup.Provider registries = registries();
		if (registries == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		String text = textOf(held, registries);
		if (text == null || text.isBlank()) {
			source.sendFailure(tr("code.export_empty"));
			return 0;
		}
		ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, registries);
		if (compiled.hasErrors()) {
			reportErrors(source, compiled);
			return 0;
		}
		source.sendSuccess(() -> tr("code.check_ok", ScriptCompiler.summarize(compiled.value())), false);
		List<String> warnings = compiled.warnings();
		if (!warnings.isEmpty()) {
			source.sendSuccess(() -> tr("code.warn_header", warnings.size()), false);
			warnings.stream()
				.limit(CHAT_LINES)
				.forEach(warning -> source.sendSuccess(() -> tr("code.warning", warning), false));
		}
		return 1;
	}

	// ————————————————————————— 小工具 —————————————————————————

	/** 聊天栏一次最多列几行（错误 / 警告都按它截断）：刷屏会把这些信息本身挤出可见区。 */
	private static final int CHAT_LINES = 8;

	/**
	 * 权限门槛：{@code hasPermissions(2) || isCreative()}（依据见类注释）。
	 *
	 * <p>拒绝时**必须**说一句话：用户口径是"客户端但需要权限的指令"，玩家得知道为什么没反应。
	 */
	private static boolean allowed(CommandSourceStack source) {
		LocalPlayer player = localPlayer(source);
		if (player != null && (player.hasPermissions(2) || player.isCreative()))
			return true;
		source.sendFailure(tr("code.no_permission"));
		return false;
	}

	/**
	 * 命令的执行者 = 本地玩家。
	 *
	 * <p>两个来源：{@code getEntity()}（{@code ClientCommandSourceStack} 用玩家当 executing，
	 * 正常路径一定有）与 {@code Minecraft.getInstance().player}（兜底，例如经
	 * {@code ClickEvent.RUN_COMMAND} 的空源）。**不用 {@code getPlayer()}** —— 那个在客户端恒为
	 * {@code null}（{@code CommandSourceStack.java:424-427} 只认 {@code ServerPlayer}）。
	 */
	@Nullable
	private static LocalPlayer localPlayer(CommandSourceStack source) {
		return source.getEntity() instanceof LocalPlayer local ? local : Minecraft.getInstance().player;
	}

	/** 客户端侧的注册表（编译脚本要它）。不在世界里 ⇒ {@code null}，调用方给一句"需要由玩家执行"。 */
	@Nullable
	private static HolderLookup.Provider registries() {
		return Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.registryAccess();
	}

	/**
	 * 主手卡上的**脚本原文**；卡上没有文本（旧格式卡）时现反编译一份。
	 *
	 * <p>导出 / 检查用的是**卡上原文**（§17.6）：{@code /sma code import} 存进去的排版与注释
	 * 原样还给作者；只有 GUI 保存过的卡（文本由反编译生成）才会是规范形式 ——
	 * 两条路都对得上"盘上是什么，导出就是什么"。兜底是为了不让旧卡上的导出功能整个失效。
	 */
	@Nullable
	private static String textOf(ItemStack held, HolderLookup.Provider registries) {
		String stored = CardProgramStorage.readText(held);
		if (stored != null)
			return stored;
		return ProgramText.toText(CardProgramStorage.read(held, registries), registries);
	}

	/**
	 * 不给名字时挑一个没被占用的 {@code card_N}（1..999）。
	 *
	 * <p>为什么是这个形状：名字规则是白名单（{@link ScriptFiles#isValidName}，只有中英文/数字/`_`/`-`），
	 * 所以时间戳、坐标之类都不能直接当默认名；而"随手导出"最怕的是覆盖 —— 挑第一个空位
	 * 既不覆盖，也不会让两次导出撞在同一个名字上。
	 */
	@Nullable
	private static String defaultExportName() {
		for (int i = 1; i <= 999; i++) {
			String candidate = "card_" + i;
			Path file = ScriptFiles.file(candidate);
			if (file != null && !Files.exists(file))
				return candidate;
		}
		return null;
	}

	/** 本机读文件失败的四种原因各给一句话（与导入桌那条路**共用同一批键**）。 */
	private static Component readProblem(String name, ProgramImportFiles.Contents contents) {
		return switch (contents.problem()) {
			case BAD_NAME -> tr("code.bad_name");
			case NOT_FOUND -> tr("import.not_found", name);
			case UNREADABLE -> tr("import.unreadable", name);
			case TOO_LARGE -> tr("code.import_too_large", contents.sizeBytes(), ProgramText.MAX_BYTES);
			case EMPTY -> tr("import.empty_file", name);
			// 不可达（ok() 为真时调用方根本不会进来）
			case OK -> tr("import.unreadable", name);
		};
	}

	/** 编译错误：**一次性列全**（上限 {@link #CHAT_LINES}），每条自带行号（§12.3 的用户明确要求）。 */
	private static void reportErrors(CommandSourceStack source, ScriptResult<ArmProgram> compiled) {
		source.sendFailure(tr("code.check_failed", compiled.errors()
			.size()));
		ScriptCompiler.errorLines(compiled.errors())
			.stream()
			.limit(CHAT_LINES)
			.forEach(line -> source.sendFailure(Component.literal("  " + line)));
	}

	/** 回执里那串字：**只有文件名**（含 {@code .sma}），与记录表导出那条路同口径。 */
	private static String fileName(@Nullable Path file) {
		return file == null ? "?" : file.getFileName()
			.toString();
	}

	/** 反馈（{@code create_sequenced_mechanical_arm.code.*}）：参数只传 String / Number。 */
	private static Component tr(String key, Object... args) {
		return Component.translatable("create_sequenced_mechanical_arm." + key, args);
	}

}
