package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;

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
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 阶段 2 的临时开发入口：在没有卡片与 GUI 之前，用命令把预设操作表装进手臂。
 *
 * <p>用法（需 OP，站在手臂 8 格内）：
 * <pre>
 *   /sma info              查看任务/条目/状态/库存/错误
 *   /sma stop              回到程序开头并清错误
 *   /sma hold              把主手的 1 件物品放进臂内（DEPLOY 需要臂手上有应用物）
 *   /sma clear             清空臂内库存（物品掉在方块位置）
 *   /sma preset &lt;名字&gt;      装载预设（depot_ab / loop3 / wait / take_or / unload / deploy / mine / attack / use / use_sneak / subtasks / freq / hold）
 *   /sma card &lt;名字&gt;        把预设写进一张操作表发给自己
 *   /sma export            把臂内现有的操作表写进主手的操作表
 * </pre>
 *
 * <h2>★ 这里已经没有 {@code /sma code …} 了（用户口径："没必要动服务端文件"）</h2>
 * 那三条命令（脚本文件的 {@code export / check / import}）原本是"管理员后门"：跑在服务端、
 * 读写**服端**的 {@code run/sequenced_arm/scripts/}。用户把脚本文件整体归给玩家本机之后，
 * 服务端这条路就不该存在了 —— 它现在是**客户端**命令（{@link SequencedArmClientCommands}：
 * 需要权限、只动本机文件与手上的操作表，正好是"方便测试 / 创造玩家快速导入导出"的用途）。
 * 所以这个类里 {@code scriptDir() / scriptFile()} 与那一整组方法（含 {@code Files} / {@code Path}
 * 的用法）一并删除：**服务端不再有任何碰脚本文件的代码**。留在服务端这里的都是"只能由服务端做"
 * 的事（方块实体、方块库存、给玩家发卡）。
 *
 * <p>条目的坐标一律<b>相对执行这条程序的那条臂</b>（§19.7）：臂跑 {@code 臂位置 + 偏移}，
 * 插卡<b>不做任何平移</b>。录制原点（用户第四十轮起它作为**卡级**属性存在，见
 * {@code CardProgramStorage.readOrigin}）只服务录制与渲染，既不进这份程序、也不进脚本文本。
 * 卡片与 GUI 会在阶段 3 取代这套命令。
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID)
public class SequencedArmCommands {

	@SubscribeEvent
	public static void register(RegisterCommandsEvent event) {
		LiteralArgumentBuilder<CommandSourceStack> preset = Commands.literal("preset");
		DevPresets.all()
			.forEach((name, supplier) -> preset.then(Commands.literal(name)
				.executes(ctx -> load(ctx.getSource(), supplier))));

		// 卡片：把预设写进一张操作表发给自己（阶段 3a 的测试入口，GUI 在 3c 取代它）
		// P1 起只有一种操作表（绝对形态已删，§19）⇒ `card_abs` 子命令一并去掉。
		LiteralArgumentBuilder<CommandSourceStack> card = Commands.literal("card");
		DevPresets.all()
			.forEach((name, supplier) -> card.then(Commands.literal(name)
				.executes(ctx -> giveCard(ctx.getSource(), supplier))));

		event.getDispatcher()
			.register(Commands.literal("sma")
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
				.then(card));
	}

	/** 把预设写进一张操作表物品并发给执行者（P1 起只有一种操作表，§19）。 */
	private static int giveCard(CommandSourceStack source, Supplier<ArmProgram> preset) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("需要由玩家执行"));
			return 0;
		}
		ArmProgram program = preset.get();
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.write(card, program, source.registryAccess());
		if (!CardProgramStorage.hasProgram(card)) {
			source.sendFailure(Component.literal("预设存不进卡片（转成脚本文本失败或超限）"));
			return 0;
		}
		if (!player.getInventory()
			.add(card))
			player.drop(card, false);
		source.sendSuccess(() -> Component.literal("已给予操作表（" + program.tasks.size() + " 个任务）"), false);
		return 1;
	}

	/**
	 * 把臂里现有的操作表写进主手的操作表（方便复制到别的臂或备份）。
	 *
	 * <p>P1 起没有"形态对不对"这道检查。★ <b>导出的坐标是"相对执行它的那条臂"的偏移</b>
	 * （§19.7）：导到另一条臂上时臂会照着<b>自己的位置</b>跑同一份相对坐标 ——
	 * "搬一份程序到另一条臂"因此天然成立，不需要任何平移（曾经那步 {@code rebaseTo} 已删除）。
	 * 卡上的录制原点（用户第四十轮的卡级属性）**不跟着这份文本走**，导出也不动它。
	 *
	 * <p>⚠️ 别把这条与"客户端命令 {@code /sma code export}"搞混：那是**手上的卡 → 本机文件**
	 * （客户端、需权限、不经过服务端）；这一条是**臂里的程序 → 手上的卡**（服务端，得站在臂旁）。
	 */
	private static int export(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("需要由玩家执行"));
			return 0;
		}
		SequencedArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到可编程动力臂"));
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
		if (CardProgramStorage.write(held, arm.getProgram(),
			source.registryAccess()) != CardProgramStorage.StoreResult.OK) {
			source.sendFailure(Component.literal("臂内程序转成脚本文本失败，没有写入卡片"));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("已把臂内操作表写入手中卡片"), false);
		return 1;
	}

	private static int info(CommandSourceStack source) {
		SequencedArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到可编程动力臂"));
			return 0;
		}
		ArmProgram program = arm.getProgram();
		List<String> lines = program.describe();
		source.sendSuccess(() -> Component.literal("操作表：" + program.tasks.size() + " 个任务"), false);
		lines.forEach(line -> source.sendSuccess(() -> Component.literal("  " + line), false));

		source.sendSuccess(() -> Component.literal("运行：task=" + arm.runtime.taskIndex + " entry="
			+ arm.runtime.entryIndex + " state=" + arm.runtime.state + " 空转计数=" + arm.runtime.idleSpins), false);
		source.sendSuccess(() -> Component.literal("库存：手持=" + describeHeld(arm) + " 溢出=" + arm.getOverflow()
			.size() + "/" + SequencedArmBlockEntity.MAX_OVERFLOW + " 红石锁=" + arm.isRedstoneLocked() + " 转速="
			+ arm.getSpeed()), false);
		if (arm.runtime.getError() != null)
			source.sendSuccess(() -> Component.literal("错误：" + arm.runtime.getError()), false);
		else if (arm.runtime.getLastFailure() != null)
			source.sendSuccess(() -> Component.literal("最近失败：" + arm.runtime.getLastFailure()), false);
		return 1;
	}

	private static String describeHeld(SequencedArmBlockEntity arm) {
		if (arm.getHeldItem()
			.isEmpty())
			return "空";
		return arm.getHeldItem()
			.getCount() + "x" + arm.getHeldItem()
				.getHoverName()
				.getString();
	}

	private static int stop(CommandSourceStack source) {
		SequencedArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到可编程动力臂"));
			return 0;
		}
		arm.runtime.resetToStart(arm);
		source.sendSuccess(() -> Component.literal("已停止并回到程序开头"), false);
		return 1;
	}

	private static int load(CommandSourceStack source, Supplier<ArmProgram> preset) {
		SequencedArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到可编程动力臂"));
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
		SequencedArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到可编程动力臂"));
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
		SequencedArmBlockEntity arm = findArm(source);
		if (arm == null) {
			source.sendFailure(Component.literal("8 格内没有找到可编程动力臂"));
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
	private static SequencedArmBlockEntity findArm(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		BlockPos origin = BlockPos.containing(source.getPosition());
		SequencedArmBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -8, -8), origin.offset(8, 8, 8))) {
			if (!(level.getBlockEntity(pos) instanceof SequencedArmBlockEntity arm))
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
