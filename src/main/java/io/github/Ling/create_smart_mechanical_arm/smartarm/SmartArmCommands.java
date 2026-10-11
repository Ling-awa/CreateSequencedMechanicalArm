package io.github.Ling.create_smart_mechanical_arm.smartarm;

import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_smart_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;

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
 *   /smartarm info              查看任务/条目/状态/库存/错误
 *   /smartarm stop              回到程序开头并清错误
 *   /smartarm hold              把主手的 1 件物品放进臂内（DEPLOY 需要臂手上有应用物）
 *   /smartarm clear             清空臂内库存（物品掉在方块位置）
 *   /smartarm preset &lt;名字&gt;      装载预设（depot_ab / loop3 / wait / take_or / unload / deploy / mine / attack / use / use_sneak / subtasks / freq / hold）
 *   /smartarm card &lt;名字&gt;        把预设写进一张<b>相对</b>坐标操作表发给自己
 *   /smartarm card_abs &lt;名字&gt;    同上，但发<b>绝对</b>形态（用来验证"绝对卡插不进臂"）
 *   /smartarm export            把臂内现有的操作表写进主手的相对坐标操作表
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
				.then(cardAbsolute));
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
