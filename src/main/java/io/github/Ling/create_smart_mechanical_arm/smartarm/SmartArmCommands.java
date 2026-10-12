package io.github.Ling.create_smart_mechanical_arm.smartarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_smart_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_smart_mechanical_arm.network.ArmProgramEditPacket;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_smart_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_smart_mechanical_arm.program.script.ScriptResult;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 阶段 2 的临时开发入口：在没有卡片与 GUI 之前，用命令把预设操作表装进手臂。
 *
 * <p>用法（需 OP，站在手臂 8 格内）：
 * <pre>
 *   /smartarm info              查看任务/条目/状态/库存/错误
 *   /smartarm stop              回到程序开头并清错误
 *   /smartarm hold              把主手的 1 件物品放进臂内（DEPLOY 需要臂手上有应用物）
 *   /smartarm clear             清空臂内库存（物品掉在方块位置）
 *   /smartarm preset &lt;名字&gt;      装载预设（depot_ab / loop3 / wait / take_or / unload / deploy / mine / attack / use / use_sneak / subtasks / freq / hold）
 *   /smartarm card &lt;名字&gt;        把预设写进一张<b>相对</b>坐标操作表发给自己
 *   /smartarm card_abs &lt;名字&gt;    同上，但发<b>绝对</b>形态（用来验证"绝对卡插不进臂"）
 *   /smartarm export            把臂内现有的操作表写进主手的相对坐标操作表
 *   /smartarm code export [名字] 把主手卡上的程序导出成脚本文本（给了名字就同时落成文件）
 *   /smartarm code check &lt;名字&gt;  只编译那个脚本文件并回报（含行号的错误），不写卡
 *   /smartarm code import &lt;名字&gt; 读那个脚本文件，编译并写进主手卡（走同一套规模校验）
 * </pre>
 *
 * <p>坐标是<b>相对手臂</b>的偏移。卡片与 GUI 会在阶段 3 取代这套命令。
 */
@EventBusSubscriber(modid = Create_smart_mechanical_arm.MODID)
public class SmartArmCommands {

	@SubscribeEvent
	public static void register(RegisterCommandsEvent event) {
		LiteralArgumentBuilder<CommandSourceStack> preset = Commands.literal("preset");
		DevPresets.all()
			.forEach((name, supplier) -> preset.then(Commands.literal(name)
				.executes(ctx -> load(ctx.getSource(), supplier))));

		// 卡片：把预设写进一张操作表发给自己（阶段 3a 的测试入口，GUI 在 3c 取代它）
		LiteralArgumentBuilder<CommandSourceStack> card = Commands.literal("card");
		LiteralArgumentBuilder<CommandSourceStack> cardAbsolute = Commands.literal("card_abs");
		DevPresets.all()
			.forEach((name, supplier) -> {
				card.then(Commands.literal(name)
					.executes(ctx -> giveCard(ctx.getSource(), supplier, false)));
				cardAbsolute.then(Commands.literal(name)
					.executes(ctx -> giveCard(ctx.getSource(), supplier, true)));
			});

		event.getDispatcher()
			.register(Commands.literal("smartarm")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("info")
					.executes(ctx -> info(ctx.getSource())))
				.then(Commands.literal("stop")
					.executes(ctx -> stop(ctx.getSource())))
				.then(Commands.literal("hold")
					.executes(ctx -> hold(ctx.getSource())))
				.then(Commands.literal("clear")
					.executes(ctx -> clear(ctx.getSource())))
				.then(Commands.literal("export")
					.executes(ctx -> export(ctx.getSource())))
				.then(preset)
				.then(card)
				.then(cardAbsolute)
				.then(codeCommand()));
	}

	/**
	 * {@code /smartarm code …} —— 脚本文本（PLAN.md §12 的 P1）的三个入口。
	 *
	 * <p>权限跟着 {@code /smartarm} 走（{@code hasPermission(2)}，见上面的注册）：
	 * 这一组命令会**写文件**与**写卡**，不该给非 OP 用。
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> codeCommand() {
		return Commands.literal("code")
			// 不给名字：只打印到聊天（看/复制用）；给了名字：同时落到 config/…/scripts/<名字>.sma
			.then(Commands.literal("export")
				.executes(ctx -> codeExport(ctx.getSource(), null))
				.then(Commands.argument("名字", StringArgumentType.word())
					.executes(ctx -> codeExport(ctx.getSource(), StringArgumentType.getString(ctx, "名字")))))
			.then(Commands.literal("check")
				.then(Commands.argument("名字", StringArgumentType.word())
					.executes(ctx -> codeCheck(ctx.getSource(), StringArgumentType.getString(ctx, "名字")))))
			.then(Commands.literal("import")
				.then(Commands.argument("名字", StringArgumentType.word())
					.executes(ctx -> codeImport(ctx.getSource(), StringArgumentType.getString(ctx, "名字")))));
	}

	/** 把预设写进一张操作表物品并发给执行者（绝对形态用于测试"拒收绝对卡"这条路径）。 */
	private static int giveCard(CommandSourceStack source, Supplier<ArmProgram> preset, boolean absolute) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("需要由玩家执行"));
			return 0;
		}
		ArmProgram program = preset.get();
		ItemStack card = new ItemStack((absolute ? SmartArmRegistration.OPERATION_TABLE_ABSOLUTE
			: SmartArmRegistration.OPERATION_TABLE_RELATIVE).get());
		CardProgramStorage.write(card, program, source.registryAccess());
		if (!player.getInventory()
			.add(card))
			player.drop(card, false);
		source.sendSuccess(() -> Component.literal("已给予" + (absolute ? "绝对" : "相对") + "坐标操作表（"
			+ program.tasks.size() + " 个任务）"), false);
		return 1;
	}

	/** 把臂里现有的操作表写进主手的相对坐标操作表（方便复制到别的臂或备份）。 */
	private static int export(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("需要由玩家执行"));
			return 0;
		}
		SmartArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到智能动力臂"));
			return 0;
		}
		if (arm.getProgram()
			.isEmpty()) {
			source.sendFailure(Component.literal("这条臂还没有操作表"));
			return 0;
		}
		ItemStack held = player.getMainHandItem();
		if (!OperationTableItem.isTable(held)) {
			source.sendFailure(Component.literal("主手请拿一张操作表"));
			return 0;
		}
		if (OperationTableItem.isAbsoluteForm(held)) {
			source.sendFailure(Component.literal("臂内的程序是相对坐标，请用相对坐标操作表承载"));
			return 0;
		}
		CardProgramStorage.write(held, arm.getProgram(), source.registryAccess());
		source.sendSuccess(() -> Component.literal("已把臂内操作表写入手中卡片"), false);
		return 1;
	}

	// ————————————————————————— 脚本（§12 的 P1） —————————————————————————

	/** 脚本文件的目录：{@code config/create_smart_mechanical_arm/scripts}（§12.3c）。 */
	private static Path scriptDir() {
		return FMLPaths.CONFIGDIR.get()
			.resolve(Create_smart_mechanical_arm.MODID)
			.resolve("scripts");
	}

	/** 脚本文件名：只用字母数字下划线连字符，免得 {@code ../} 之类跑出脚本目录。 */
	@Nullable
	private static Path scriptFile(String name) {
		if (name == null || name.isEmpty())
			return null;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-';
			if (!ok)
				return null;
		}
		return scriptDir().resolve(name + ".sma");
	}

	/**
	 * {@code /smartarm code export [名字]}：主手卡上的程序 → 脚本文本。
	 *
	 * <p>不给名字就只打印到聊天（"看着改"用）；给了名字同时落成文件
	 * —— 那正是作者工作流：导出 → 用 IDEA 改 → 放回数据包（§12.3c）。
	 *
	 * <p>反馈走语言文件（{@code code.*} 前缀）：这一段是玩家可见的**可操作指引**，
	 * 而现有 {@code /smartarm} 的反馈都是 {@code Component.literal}（开发期临时入口）。
	 */
	private static int codeExport(CommandSourceStack source, @Nullable String name) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		if (!OperationTableItem.isTable(player.getMainHandItem())) {
			source.sendFailure(tr("code.export_need_card"));
			return 0;
		}
		ArmProgram program = CardProgramStorage.read(player.getMainHandItem(), source.registryAccess());
		if (program.tasks.isEmpty()) {
			source.sendFailure(tr("code.export_empty"));
			return 0;
		}
		String text = ScriptCompiler.decompile(program, source.registryAccess());
		source.sendSuccess(() -> tr("code.export_header", ScriptCompiler.summarize(program)), false);
		// 一整份脚本一行一行发：聊天栏会自己折行，玩家能整段选中复制
		text.lines()
			.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));

		if (name != null) {
			Path file = scriptFile(name);
			if (file == null) {
				source.sendFailure(tr("code.bad_name"));
				return 0;
			}
			try {
				// UTF-8 **无 BOM**（§12.3c）：Files.writeString 不带 BOM，正是要的
				Files.createDirectories(file.getParent());
				Files.writeString(file, text, StandardCharsets.UTF_8);
			} catch (IOException failed) {
				source.sendFailure(tr("code.export_failed", String.valueOf(failed.getMessage())));
				return 0;
			}
			source.sendSuccess(() -> tr("code.export_written", file.toString()), false);
		}
		return 1;
	}

	/** {@code /smartarm code check <名字>}：只编译并回报（**带行号**的错误一次性列全），不写卡。 */
	private static int codeCheck(CommandSourceStack source, String name) {
		Path file = scriptFile(name);
		if (file == null) {
			source.sendFailure(tr("code.bad_name"));
			return 0;
		}
		String text;
		try {
			text = readScript(file);
		} catch (IOException failed) {
			source.sendFailure(tr("code.read_failed", file.toString(), String.valueOf(failed.getMessage())));
			return 0;
		}
		ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, source.registryAccess());
		if (compiled.hasErrors()) {
			reportErrors(source, compiled);
			return 0;
		}
		source.sendSuccess(() -> tr("code.check_ok", ScriptCompiler.summarize(compiled.value())), false);
		compiled.warnings()
			.forEach(warning -> source.sendSuccess(() -> tr("code.warning", warning), false));
		return 1;
	}

	/**
	 * {@code /smartarm code import <名字>}：脚本文件 → 主手卡。
	 *
	 * <p><b>走的是同一套服务端校验</b>（§12.3c 明说"代码这条路不能绕过限制"）：
	 * {@link ArmProgram#isWithinLimits()} 与 {@link ArmProgramEditPacket#MAX_TAG_BYTES} 的 128KB，
	 * 都按编辑器保存那条路径的判据来；写得进卡才 {@link CardProgramStorage#write}。
	 */
	private static int codeImport(CommandSourceStack source, String name) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(tr("code.need_player"));
			return 0;
		}
		ItemStack held = player.getMainHandItem();
		if (!OperationTableItem.isTable(held)) {
			source.sendFailure(tr("code.import_need_card"));
			return 0;
		}
		Path file = scriptFile(name);
		if (file == null) {
			source.sendFailure(tr("code.bad_name"));
			return 0;
		}
		String text;
		try {
			text = readScript(file);
		} catch (IOException failed) {
			source.sendFailure(tr("code.read_failed", file.toString(), String.valueOf(failed.getMessage())));
			return 0;
		}
		ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, source.registryAccess());
		if (compiled.hasErrors()) {
			reportErrors(source, compiled);
			return 0;
		}
		ArmProgram program = compiled.value();
		// §12.3 末尾（主代理 87①）的硬错：脚本文本首行的坐标空间必须与手持卡的形态一致。
		// **不自动转换**：把绝对坐标当相对偏移读是"看起来完全正常"的错（臂会整体平移一格位），
		// 而程序里那个 abs 标记本来就只有"这份数据是什么"的含义（ArmProgramEditPacket 每次落盘都重写它）。
		boolean cardAbsolute = OperationTableItem.isAbsoluteForm(held);
		if (program.absolute != cardAbsolute) {
			String cardForm = cardAbsolute ? "绝对坐标操作表 / @abs" : "相对坐标操作表 / @rel";
			String scriptForm = program.absolute ? "绝对坐标操作表 / @abs" : "相对坐标操作表 / @rel";
			// 文案里两种形态各自说清（"+"/"@" 是编辑器与脚本里那两个记号，玩家一眼能对上）
			source.sendFailure(tr("code.import_form_mismatch", cardForm, scriptForm));
			return 0;
		}
		if (!program.isWithinLimits()) {
			source.sendFailure(tr("code.import_over_limit", ScriptCompiler.summarize(program)));
			return 0;
		}
		int bytes = program.save(source.registryAccess())
			.sizeInBytes();
		if (bytes > ArmProgramEditPacket.MAX_TAG_BYTES) {
			source.sendFailure(tr("code.import_too_large", bytes, ArmProgramEditPacket.MAX_TAG_BYTES));
			return 0;
		}
		CardProgramStorage.write(held, program, source.registryAccess());
		player.inventoryMenu.broadcastChanges();
		source.sendSuccess(() -> tr("code.import_ok", ScriptCompiler.summarize(program)), false);
		compiled.warnings()
			.forEach(warning -> source.sendSuccess(() -> tr("code.warning", warning), false));
		return 1;
	}

	/** 编译错误：**一次性列全**，每条自带行号（§12.3 的用户明确要求）。 */
	private static void reportErrors(CommandSourceStack source, ScriptResult<ArmProgram> compiled) {
		source.sendFailure(tr("code.check_failed", compiled.errors()
			.size()));
		ScriptCompiler.errorLines(compiled.errors())
			.forEach(line -> source.sendFailure(Component.literal("  " + line)));
	}

	/** 脚本命令的反馈（{@code create_smart_mechanical_arm.code.*}）。 */
	private static Component tr(String key, Object... args) {
		return Component.translatable("create_smart_mechanical_arm." + key, args);
	}

	/**
	 * 读一份脚本：**UTF-8**，LF / CRLF 都吃（§12.3c 明说文件是纯文本）。
	 *
	 * <p>项目在 PowerShell 写出的 UTF-8 BOM 上栽过一次：BOM 会变成文件第一个字符、
	 * 让第一行解析不出来，所以这里显式吃掉它。
	 */
	private static String readScript(Path file) throws IOException {
		String text = Files.readString(file, StandardCharsets.UTF_8);
		if (!text.isEmpty() && text.charAt(0) == '\uFEFF')
			text = text.substring(1);
		return text;
	}

	private static int info(CommandSourceStack source) {
		SmartArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到智能动力臂"));
			return 0;
		}
		ArmProgram program = arm.getProgram();
		List<String> lines = program.describe();
		source.sendSuccess(() -> Component.literal("操作表：" + program.tasks.size() + " 个任务"), false);
		lines.forEach(line -> source.sendSuccess(() -> Component.literal("  " + line), false));

		source.sendSuccess(() -> Component.literal("运行：task=" + arm.runtime.taskIndex + " entry="
			+ arm.runtime.entryIndex + " state=" + arm.runtime.state + " 空转计数=" + arm.runtime.idleSpins), false);
		source.sendSuccess(() -> Component.literal("库存：手持=" + describeHeld(arm) + " 溢出=" + arm.getOverflow()
			.size() + "/" + SmartArmBlockEntity.MAX_OVERFLOW + " 红石锁=" + arm.isRedstoneLocked() + " 转速="
			+ arm.getSpeed()), false);
		if (arm.runtime.getError() != null)
			source.sendSuccess(() -> Component.literal("错误：" + arm.runtime.getError()), false);
		else if (arm.runtime.getLastFailure() != null)
			source.sendSuccess(() -> Component.literal("最近失败：" + arm.runtime.getLastFailure()), false);
		return 1;
	}

	private static String describeHeld(SmartArmBlockEntity arm) {
		if (arm.getHeldItem()
			.isEmpty())
			return "空";
		return arm.getHeldItem()
			.getCount() + "x" + arm.getHeldItem()
				.getHoverName()
				.getString();
	}

	private static int stop(CommandSourceStack source) {
		SmartArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到智能动力臂"));
			return 0;
		}
		arm.runtime.resetToStart(arm);
		source.sendSuccess(() -> Component.literal("已停止并回到程序开头"), false);
		return 1;
	}

	private static int load(CommandSourceStack source, Supplier<ArmProgram> preset) {
		SmartArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到智能动力臂"));
			return 0;
		}
		ArmProgram program = preset.get();
		if (!program.isWithinLimits()) {
			source.sendFailure(Component.literal("预设超出规模上限"));
			return 0;
		}
		arm.setProgram(program);
		source.sendSuccess(() -> Component.literal("已装载操作表：" + program.tasks.size() + " 个任务"), false);
		return 1;
	}

	/**
	 * 把执行者主手的 1 件物品放进臂内（手持格优先）。
	 * 这是 DEPLOY 的测试入口 —— 臂手上必须有应用物才有东西可施加。阶段 3 的卡片会取代它。
	 */
	private static int hold(CommandSourceStack source) {
		SmartArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到智能动力臂"));
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("需要由玩家执行"));
			return 0;
		}
		ItemStack inHand = player.getMainHandItem();
		if (inHand.isEmpty()) {
			source.sendFailure(Component.literal("主手是空的"));
			return 0;
		}
		ItemStack moved = inHand.copyWithCount(1);
		inHand.shrink(1);
		arm.insertIntoInventory(moved);
		source.sendSuccess(() -> Component.literal("已放入手臂：" + moved.getHoverName()
			.getString()), false);
		return 1;
	}

	/** 清空臂内库存，物品掉在手臂方块位置（方便回收测试用的工具）。 */
	private static int clear(CommandSourceStack source) {
		SmartArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到智能动力臂"));
			return 0;
		}
		Level level = arm.getLevel();
		if (level != null) {
			if (!arm.getHeldItem()
				.isEmpty())
				Block.popResource(level, arm.getBlockPos(), arm.getHeldItem());
			arm.getOverflow()
				.forEach(stack -> Block.popResource(level, arm.getBlockPos(), stack));
		}
		arm.setHeldItem(ItemStack.EMPTY);
		arm.getOverflow()
			.clear();
		arm.markProgramDirty();
		source.sendSuccess(() -> Component.literal("已清空手臂库存（掉在方块位置）"), false);
		return 1;
	}

	@Nullable
	private static SmartArmBlockEntity findArm(CommandSourceStack source) {		ServerLevel level = source.getLevel();
		BlockPos origin = BlockPos.containing(source.getPosition());
		SmartArmBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -8, -8), origin.offset(8, 8, 8))) {
			if (!(level.getBlockEntity(pos) instanceof SmartArmBlockEntity arm))
				continue;
			double distance = pos.distSqr(origin);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = arm;
			}
		}
		return best;
	}

}
