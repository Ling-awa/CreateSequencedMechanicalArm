package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute;
import com.simibubi.create.content.logistics.item.filter.attribute.attributes.InTagAttribute;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.content.redstone.smartObserver.SmartObserverBlock;
import com.simibubi.create.content.redstone.smartObserver.SmartObserverBlockEntity;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.SequencedArmConfig;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportFiles;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity.ChunkResult;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity.StartResult;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableMenu;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.AnyBlockInteractionPoint;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.ArmEffects;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.RightClickBridge;
import io.github.Ling.create_sequenced_mechanical_arm.interaction.TargetInteraction;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_sequenced_mechanical_arm.mixin.ArmInteractionPointAccessor;
import io.github.Ling.create_sequenced_mechanical_arm.network.ArmProgramEditPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardEditPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardNotesPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.CardUiPacket;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmEntryText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmIcons;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_sequenced_mechanical_arm.program.ClipboardChecklist;
import io.github.Ling.create_sequenced_mechanical_arm.program.DeployRecipes;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramRuntime;
import io.github.Ling.create_sequenced_mechanical_arm.program.OperationType;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptParser;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import io.netty.buffer.Unpooled;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.createmod.catnip.data.Couple;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 启动期自检（阶段 2a）。
 *
 * <p>为什么不只靠"能编译"：操作表的价值全在<b>存读盘无损</b>上，而 NBT 键名写错
 * 是编译期抓不到的静默错误（服务器不会报错，只是重进世界后程序变成另一个样子）。
 * 这里把每个预设做一次 save → load → save 往返，逐字节比对，并确认命令真的注册上了。
 *
 * <p>结果会打进日志，形如：
 * <pre>
 * [selftest] command /sma registered: true
 * [selftest] preset depot_ab tasks=1 entries=2 nbtRoundTrip=true withinLimits=true
 * </pre>
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID)
public class SequencedArmSelfTest {

	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * 自检的**常态钉速**：{@code 0.5 条/tick} = 旧版节奏（一条"一瞬间出结果"的控制类条目
	 * 在状态机里两步 ⇒ 每 tick 一步）。
	 *
	 * <p>为什么不是 1.0（这一处上一轮写错了，三条红里有两条是它的后果）：速率的口径是**条/tick**，
	 * 而老夹具全是照"每 tick 走一个状态机 case"写的（"第 2 拍进 EXECUTING、第 3 拍出结果"），
	 * 也就是**半条/tick**。钉 1.0 等于让每条控制类条目一 tick 就走完：{@code EXECUTING} 这个
	 * 中间态在 tick 边界上再也看不到，而 {@code checkSignalCutHasNoDuration} /
	 * {@code runFilterEntry} 正是拿"看见过 EXECUTING"当"真的执行了"的唯一证人。
	 * 钉 0.5 让老夹具的时间线与旧版**逐 tick 相同**（含整数预算、没有零头），断言一条都不用放松。
	 */
	private static final double OLD_PACE_RATE = 0.5;

	@SubscribeEvent
	public static void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();
		boolean registered = server.getCommands()
			.getDispatcher()
			.getRoot()
			.getChild("sma") != null;
		LOGGER.info("[selftest] command /sma registered: {}", registered);

		// 生效的规模上限（**从配置读，不读常量**）—— 服务端这一侧的现场证据。
		// 反证方式：把 run/config/create_sequenced_mechanical_arm-common.toml 里的 maxTasks 改成 3，
		// 重启后这行应当报 任务上限=3，并且下面 `任务数上限` 那条自检探到第 4 个就被挡。
		LOGGER.info("[selftest] {}", SequencedArmConfig.effectiveSummary());
		// ★ **老夹具统一钉住"旧版节奏"**（速率 = 0.5 条/tick = 每 tick 一步），见 ProgramRuntime.pinRateForTests：
		//   下面 40 多条夹具是照"每条条目 2 tick"写的（第几拍进 EXECUTING、第几拍出结果、
		//   窗口内还在重试…），速率一变它们读到的中间状态就变了。**断言一条都不放松** ——
		//   钉住速率才能证明"换了速率、老语义没变"；专门验速率的 checkEntryRate 会自己覆盖成
		//   2.0 / 0.5 / 32 并**恢复**成 OLD_PACE_RATE。覆盖值优先于配置项（配置是运行期现读的，
		//   自检要的是钉死的节奏）。
		//   ⚠️ 为什么是 0.5 而不是 1.0：速率的单位是**条**，而"每 tick 一个状态机 case"是半条
		//   （一条控制类条目 = beginEntry + execute 两步），钉 1.0 会把中间态挤掉，理由见 OLD_PACE_RATE。
		//   ⚠️ 明确**依赖这条钉速**的夹具（去掉它必红，各自红在哪写在注释里）：
		//   checkPrecheckRetryIsPerTick（只跑一个 tick 看黄字是否已清）、
		//   checkSignalCutHasNoDuration（要在外面看见 EXECUTING）、
		//   checkFilterOperation（重试窗口按 tick 数、runFilterEntry 要看见 EXECUTING→IDLE）、
		//   checkCallRepeats / checkCallShift / checkCallSuccessExit（靠逐 tick 采样游标与栈深定位）。
		ProgramRuntime.pinRateForTests(OLD_PACE_RATE);

		checkDeployRecipes(server);
		checkCardAndCoordinates(server);
		checkShiftAllOffsets();
		checkEditorSession(server);
		checkArbitraryBlockTarget(server);
		checkNotesMapping();
		checkSpinGuard();
		checkArmReachClamp();
		checkArmRangeGate();
		checkWaitSemantics();
		checkUsePlacesOnAir(server);
		checkSignalSendReceive(server);
		checkSignalCutHasNoDuration(server);
		checkSignalActiveModel(server);
		checkSignalRange(server);
		checkEntryPredicates();
		checkSignalSendPreset();
		checkArmPointClassification(server);
		checkFunnelTransferNotice(server);
		checkTakeGateEndToEnd(server);
		checkPrecheckRetryIsPerTick(server);
		checkClaimFollowsIntent(server);
		checkDeployPrecheckScansAllSlots(server);
		checkRetractBeforeNextMove(server);
		checkCountMode(server);
		// 「收存」：把手持物品挪进溢出库存（无动作、无需坐标），与「个数模式」同一套夹具
		checkStash(server);
		checkMovePoseTimeline();
		checkClaimPositionPolicy();
		checkEffects(server);
		checkEditorSavePath(server);
		checkEntryMigration();
		checkIcons();
		checkTaskLimit();
		checkNotesClip();
		checkArmInventory(server);
		// 漏斗 / 溜槽的外部输入只能进手持格（溢出格拒收），臂自己的路径照常
		checkHeldOnlyInput(server);
		checkFakePlayerDrain(server);
		checkStructureTransform();
		checkLegacyJumpShift();
		checkCallRepeats(server);
		checkCallShift(server);
		checkCallSuccessExit(server);
		checkFilterOperation(server);
		checkEntryRate(server);
		checkRunLog(server);
		checkMainTaskLoopsForever(server);
		checkAttackAndMine(server);
		checkClipboardChecklist(server);
		checkOverflowStackAndExchange(server);
		checkProgramSize(server);
		checkTextStorage(server);
		checkScriptRoundTrip(server);
		checkOperationRecordExport(server);
		checkProgramImportTable(server);

		for (Map.Entry<String, Supplier<ArmProgram>> preset : DevPresets.all()
			.entrySet()) {
			ArmProgram program = preset.getValue()
				.get();
			CompoundTag first = program.save(server.registryAccess());
			ArmProgram reloaded = ArmProgram.load(first, server.registryAccess());
			CompoundTag second = reloaded.save(server.registryAccess());
			int entries = program.tasks.stream()
				.mapToInt(task -> task.entries.size())
				.sum();
			LOGGER.info("[selftest] preset {} tasks={} entries={} nbtRoundTrip={} withinLimits={}",
				preset.getKey(), program.tasks.size(), entries, first.equals(second), program.isWithinLimits());
			if (!first.equals(second))
				LOGGER.error("[selftest] preset {} 的 NBT 往返不一致！first={} second={}", preset.getKey(), first, second);			// 不变量：预设必须真的有操作，且每条"需要坐标"的操作都得带目标偏移。
			// （写这条是因为第一版 unload 预设构造了条目却忘了 add 进任务，日志里表现为 entries=0。）
			if (program.tasks.isEmpty() || entries == 0) {
				LOGGER.error("[selftest] preset {} 没有任何操作！", preset.getKey());
				continue;
			}
			// §7.6 v4 的结构不变量：多个任务时，除主任务（第一个）外都必须**被调用**过，
			// 否则那张卡永远不会跑 —— 这类"写了但接不上"的错最不容易在界面里看出来。
			if (program.tasks.size() > 1) {
				for (int t = 1; t < program.tasks.size(); t++) {
					String name = program.tasks.get(t).name;
					boolean called = program.tasks.stream()
						.flatMap(task -> task.entries.stream())
						.anyMatch(entry -> entry.op == OperationType.CALL && name.equals(entry.callTarget));
					if (!called)
						LOGGER.warn("[selftest] preset {} 的子任务「{}」没有任何调用点，永远不会执行", preset.getKey(), name);
				}
			}
			for (ArmProgramTask task : program.tasks) {
				for (ArmProgramEntry entry : task.entries) {
					// 问**条目级**的 needsTarget()：过滤的容器模式也指着一个世界目标，
					// 它的 (0,0,0) 一样可能不是本意（手持过滤没有目标，不该被这条警告盯上）。
					if (entry.needsTarget() && entry.offset.equals(BlockPos.ZERO))
						LOGGER.warn("[selftest] preset {} 的 {} 操作目标是零偏移，可能不是本意", preset.getKey(),
							entry.op);
					// §7.7 v3 的不变量：每条分支各带自己的落点，设了跳转就必须有落点。
					// 缺了它 jumpTo 会直接停机，而"停机"和"按顺序走"在界面上看不出区别 —— 所以在这里挡一道。
					if (entry.onSuccess == ArmProgramEntry.SkipAction.SKIP_TO && entry.successTarget.isBlank())
						LOGGER.error("[selftest] preset {} 的 {} 「成功后」设了跳转却没有落点", preset.getKey(), entry.op);
					if (entry.onTimeout == ArmProgramEntry.SkipAction.SKIP_TO && entry.timeoutTarget.isBlank())
						LOGGER.error("[selftest] preset {} 的 {} 「超时后」设了跳转却没有落点", preset.getKey(), entry.op);
					// §5.1 v5：跳转序号是 **1 起**的（用户实测："文本描述从 1 开始，代码里却是 0 起"）。
					// 数字落点必须落在 1..条数+1（+1 = 立即结束本轮）；越界只会让运行时停机，
					// 在界面里看不出任何区别 —— 所以在启动期挡一道。
					String jump = entry.onSuccess == ArmProgramEntry.SkipAction.SKIP_TO ? entry.successTarget : null;
					if (jump == null && entry.onTimeout == ArmProgramEntry.SkipAction.SKIP_TO)
						jump = entry.timeoutTarget;
					if (jump != null && !jump.isEmpty() && jump.chars()
						.allMatch(Character::isDigit)) {
						int target = Integer.parseInt(jump);
						if (target < 1 || target > task.entries.size() + 1)
							LOGGER.error("[selftest] preset {} 的 {} 跳转序号 {} 越界（序号 1 起，本任务 {} 条，合法 1..{}）",
								preset.getKey(), entry.op, target, task.entries.size(), task.entries.size() + 1);
					}
					if (entry.timeoutTicks() == 0)
						LOGGER.warn("[selftest] preset {} 的 {} 时限为 0，等于不重试直接超时", preset.getKey(), entry.op);
					// §7.6 v4：调用目标必须能解析（改了名/删了任务就会在这儿暴露）
					if (entry.op == OperationType.CALL && program.tasks.stream()
						.noneMatch(t -> t.name.equals(entry.callTarget)))
						LOGGER.error("[selftest] preset {} 的调用目标不存在：{}", preset.getKey(), entry.callTarget);
					// 等待信号必须真的绑了频率，否则永远等不到
					if (entry.op.usesSignalPair() && entry.frequency == null)
						LOGGER.error("[selftest] preset {} 有一条「收发信号」没绑频率", preset.getKey());
				}
			}
		}

		// 自检跑完**自己退出**（只在开关打开时）—— 这是给"跑一次自检验证改动"用的时间账优化：
		// 服务器不退出的话，`runServer` 只能挂到超时被杀，每次迭代白等两分钟；而且"被超时杀掉"是 exit 1，
		// 与"编译真的失败"长得一模一样（本项目为这个混淆过）。开关由 build.gradle 的 server run 从
		// `-PsmaSelfTest=exit` 透传（见 build.gradle 里那条 systemProperty），常规手动 `runServer` 不受影响。
		if (Boolean.getBoolean("create_sequenced_mechanical_arm.selftest.exit")) {
			LOGGER.info("[selftest] selftest.exit=true ⇒ 自检结束，请求服务器退出（日志到此为止就是全部结果）");
			// 另起一个守护线程调 halt(false)：它不 join 服务器线程，但**万一它阻塞**也不会把启动流程卡死。
			Thread exit = new Thread(() -> server.halt(false), "sequencedarm-selftest-exit");
			exit.setDaemon(true);
			exit.start();
		}
	}

	/**
	 * 「盘上那份东西有多大、上限能放多少条」的实测（用户问的就是这个）。
	 *
	 * <p><b>★ 口径在 §17 换过一次，所以两种数都打</b>：
	 * <ul>
	 *   <li><b>现在</b>（盘上存 {@code .sma} 文本）：天花板是**脚本字节数** {@link ProgramText#MAX_BYTES}。
	 *       硬约束不是网络包，而是 <b>NBT 字符串的 16 位长度 = 65535 字节</b>（§17.4）；</li>
	 *   <li><b>以前</b>（盘上存编译结果，判 {@code sizeInBytes()}）：128 KiB 只放得下几十条。
	 *       这行历史对照留在日志里，是为了说明**为什么要换存储形式**。</li>
	 * </ul>
	 *
	 * <p>⚠️ 这些是<b>测量，不是断言</b>：数值随上游物品 id 长度、备注语言（汉字 3 字节/字、ASCII 1 字节）变化，
	 * 写死期望值只会变成噪音。**断言只钉结构**（盘上是不是文本、载入会不会编译）——见 {@link #checkTextStorage}。
	 */
	private static void checkProgramSize(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();

		// ① 编辑器造得出的两端：一条最轻 / 一条最重（含满额备注），分别量脚本与编译后 NBT
		ArmProgramEntry heaviest = heaviestEntry();
		ArmProgramEntry lightest = new ArmProgramEntry();
		int lightText = perEntryTextBytes(registries, lightest);
		int heavyText = perEntryTextBytes(registries, heaviest);
		int lightNbt = perEntryNbtBytes(registries, lightest);
		int heavyNbt = perEntryNbtBytes(registries, heaviest);
		LOGGER.info("[selftest] 单条体积: 脚本 最轻={} 最重={} 字节 ｜ 编译后 NBT 最轻={} 最重={} 字节（备注满 {} 字）",
			lightText, heavyText, lightNbt, heavyNbt, ArmProgramEntry.NOTE_MAX);

		// ② 换算：现在的口径（文本）与历史口径（128KB ÷ 编译后 NBT）
		int textCap = ProgramText.MAX_BYTES;
		int oldCap = 128 * 1024;
		int configured = SequencedArmConfig.maxEntriesTotal();
		LOGGER.info("[selftest] 脚本上限 {} 字节换算: 全按最轻≈{}条 全按最重≈{}条（典型在两者之间）｜ 配置上限={}条",
			textCap, lightText == 0 ? 0 : textCap / lightText, heavyText == 0 ? 0 : textCap / heavyText, configured);
		LOGGER.info("[selftest] （历史对照）旧口径 128KiB ÷ 编译后 NBT: 全按最轻≈{}条 全按最重≈{}条 —— 换存储形式就是为了这个",
			lightNbt == 0 ? 0 : oldCap / lightNbt, heavyNbt == 0 ? 0 : oldCap / heavyNbt);
		LOGGER.info("[selftest] 硬约束: NBT 单个字符串 16 位长度=65535 字节，脚本上限 {} 必须小于它（余量 {} 字节）",
			textCap, 65535 - textCap);
		// ④ 关系断言（§17.4）：脚本上限必须留在 NBT 字符串的 16 位长度之内。
		//    这条不是"断言常量为真"式的自嗨 —— 它钉的是一条**跨系统的硬约束**：越过去不是"变慢/变大"，
		//    而是**区块存盘时抛异常**（存档直接坏掉）。将来有人想把上限提到 64KB 以上，必须在这里撞墙。
		//    反证：把 ProgramText.MAX_BYTES 改成 70000 ⇒ 这行立刻变红。
		if (textCap >= 65535)
			LOGGER.error("[selftest] 脚本上限 {} 不小于 NBT 字符串的 16 位长度上限 65535 —— 会写出存不下盘的存档",
				textCap);

		// ③ 一份真实预设（条目最多的那个）转成脚本有多大 —— 不是人为造的最坏情况
		String widest = "无";
		ArmProgram sample = null;
		int entries = 0;
		for (Map.Entry<String, Supplier<ArmProgram>> preset : DevPresets.all()
			.entrySet()) {
			ArmProgram candidate = preset.getValue()
				.get();
			int count = candidate.tasks.stream()
				.mapToInt(task -> task.entries.size())
				.sum();
			if (count > entries) {
				entries = count;
				widest = preset.getKey();
				sample = candidate;
			}
		}
		if (sample != null) {
			int scriptBytes = ProgramText.utf8Length(ScriptCompiler.decompile(sample, registries));
			LOGGER.info("[selftest] 真实预设「{}」{} 条 → 脚本 {} 字节（占上限 {}%，编译后 NBT {} 字节）", widest, entries,
				scriptBytes, String.format(java.util.Locale.ROOT, "%.1f", scriptBytes * 100.0 / textCap),
				sample.save(registries)
					.sizeInBytes());
		}
	}

	/** 一条操作**转成脚本**后的边际字节数（用 101 条与 1 条的差除掉表头那一份）。 */
	private static int perEntryTextBytes(HolderLookup.Provider registries, ArmProgramEntry entry) {
		return (scriptBytes(registries, entry, 101) - scriptBytes(registries, entry, 1)) / 100;
	}

	/**
	 * {@code count} 条同一个条目组成一份程序后**转成脚本**的字节数。
	 *
	 * <p>同一个 {@link ArmProgramEntry} 实例被反复放进列表：反编译只读字段，而"每条都一样"正是这里要的。
	 */
	private static int scriptBytes(HolderLookup.Provider registries, ArmProgramEntry entry, int count) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "size_probe";
		for (int i = 0; i < count; i++)
			task.entries.add(entry);
		program.tasks.add(task);
		return ProgramText.utf8Length(ScriptCompiler.decompile(program, registries));
	}

	/**
	 * 一条操作编译成 NBT 后的**边际** {@code sizeInBytes()}（**只为历史对照**，新口径不看它）。
	 *
	 * <p>用边际值（101 条与 1 条的差 ÷100）而不是"单条程序"：单条程序里那份程序/任务表头会把
	 * 每条摊高 900~1000 字节，于是历史数字会从 §15.4 记的 2216/3719 变成 3163/4666 —— 两个数都对，
	 * 但混在一起就没人知道哪个该信。**同一件事只能有一个口径**（决策 68/85）。
	 */
	private static int perEntryNbtBytes(HolderLookup.Provider registries, ArmProgramEntry entry) {
		return (nbtBytes(registries, entry, 101) - nbtBytes(registries, entry, 1)) / 100;
	}

	/** {@code count} 条同一个条目的程序编译成 NBT 后 {@code sizeInBytes()} 的大小。 */
	private static int nbtBytes(HolderLookup.Provider registries, ArmProgramEntry entry, int count) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "size_probe";
		for (int i = 0; i < count; i++)
			task.entries.add(entry);
		program.tasks.add(task);
		return program.save(registries)
			.sizeInBytes();
	}

	/** 编辑器造得出的"一条最重"：所有字段非默认 + 满额备注 + 自定义图标 + 一对频率。 */
	private static ArmProgramEntry heaviestEntry() {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.SIGNAL;
		// ⚠️ 这里原来给了一个"天文坐标"（1234567,255,-1234567）。用户第四十一轮起**不需要坐标的操作
		//    offset 恒为 0**（载入自愈 / 编辑器切换操作时清零 / 脚本也不写它）⇒ 那种状态已经**造不出来**，
		//    留着它会让"编辑器造得出的最重一条"这句话变成假的。改成 ZERO 对两个测量数字**都没有影响**：
		//    ① `off` 是无条件写的 IntArray，3 个 int 占的字节数与取值无关；② 反编译器本来就不为
		//    「收发信号」写坐标（这一轮之前也只是"offset 非零才写"那支的残留值）。
		entry.offset = new BlockPos(0, 0, 0);
		entry.delay = 12000;
		entry.face = Direction.NORTH;
		entry.failAmount = 120;
		entry.failUnit = ArmProgramEntry.WaitUnit.MINUTE;
		entry.onSuccess = ArmProgramEntry.SkipAction.SKIP_TO;
		entry.successTarget = "搬一趟";
		entry.onTimeout = ArmProgramEntry.SkipAction.SKIP_TO;
		entry.timeoutTarget = "12";
		// 128 个汉字 = 256 字节（sizeInBytes 按 UTF-16 算，与实际序列化的 UTF-8 无关）
		entry.note = "汉".repeat(ArmProgramEntry.NOTE_MAX);
		// 物品 id 字符串越长，估算越大 —— 拿 netherite_pickaxe 当"长 id"的代表
		entry.icon = new ItemStack(Items.NETHERITE_PICKAXE);
		entry.count = 64;
		entry.waitAmount = ArmProgramEntry.SIGNAL_MAX_AMOUNT;
		entry.waitUnit = ArmProgramEntry.WaitUnit.MINUTE;
		entry.waitForPowered = false;
		entry.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		entry.frequency = testFrequency(Items.REDSTONE_TORCH, Items.IRON_INGOT);
		entry.callTarget = "子任务名字";
		entry.callRepeat = ArmProgramEntry.MAX_CALL_REPEAT;
		entry.errorMessage = "写给护目镜看的错误信息";
		entry.unloadRepeat = ArmProgramEntry.UnloadRepeat.UNTIL_EMPTY;
		entry.unloadAmount = ArmProgramEntry.UnloadAmount.MAX;
		return entry;
	}

	/**
	 * ★ §17 的结构断言：**盘上只有文本**（卡片与臂本体都是），载入时编译，旧字段被丢弃。
	 *
	 * <p>为什么必须是<b>结构</b>断言，而不是"读回来一样就算过"：存储形式可以悄悄退回旧格式 ——
	 * 只要有人把 {@code program.save()} 再写回盘上，"读回来一样"照样成立（那条路上编译结果就是权威），
	 * 而 §17 的全部收益（可读、可 diff、上限抬一个数量级）当场作废。所以这里直接看 tag 里**是什么类型**。
	 *
	 * <p><b>反证</b>（两条里任意一条都必须让这里变红）：
	 * ① 把 {@link CardProgramStorage#write} 改回 {@code root.put("ArmProgram", program.save(...))}；
	 * ② 把 {@code SequencedArmBlockEntity.write} 改回 {@code tag.put("Program", program.save(...))}。
	 */
	private static void checkTextStorage(MinecraftServer server) {
		ServerLevel level = server.overworld();
		HolderLookup.Provider registries = server.registryAccess();
		List<String> problems = new ArrayList<>();
		ArmProgram program = DevPresets.mainWithSubTasks();

		// ① 卡片：盘上是 ArmProgramText（String），没有旧的 ArmProgram（Compound）
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.StoreResult stored = CardProgramStorage.write(card, program, registries);
		CustomData data = card.get(DataComponents.CUSTOM_DATA);
		CompoundTag cardRoot = data == null ? new CompoundTag() : data.copyTag();
		boolean cardIsText = stored == CardProgramStorage.StoreResult.OK
			&& cardRoot.contains("ArmProgramText", Tag.TAG_STRING) && !cardRoot.contains("ArmProgram");
		// 读回来 = 文本 → 编译：拿**规范形式**比，不要去比实现细节
		boolean cardRoundTrip = ScriptCompiler.decompile(program, registries)
			.equals(ScriptCompiler.decompile(CardProgramStorage.read(card, registries), registries));
		LOGGER.info("[selftest] 卡片存储: 结果={} 盘上是文本={} 内容一致={} 脚本字节={}", stored, cardIsText, cardRoundTrip,
			ProgramText.utf8Length(cardRoot.getString("ArmProgramText")));
		if (!cardIsText)
			problems.add("卡片没有把程序存成文本（tag=" + cardRoot + "）");
		if (!cardRoundTrip)
			problems.add("卡片上的文本编译回来与原文不一致");

		// ② 旧格式的卡（编译结果在盘上）：**不许**被当成有程序
		ItemStack legacyCard = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CompoundTag legacyRoot = new CompoundTag();
		legacyRoot.put("ArmProgram", program.save(registries));
		legacyCard.set(DataComponents.CUSTOM_DATA, CustomData.of(legacyRoot));
		boolean legacyCardEmpty = !CardProgramStorage.hasProgram(legacyCard)
			&& CardProgramStorage.read(legacyCard, registries).tasks.isEmpty();

		// ④ ★ 悬空任务名的程序必须**存得住**（§17.10：这条原来没有，后果是整份程序被清空）
		//    真实路径：录一条「调用 子任务B」→ 把任务 B 删掉或改名 ⇒ 模型里合法（运行时按"跳转找不到任务"
		//    走失败出口），但**编译期是硬错**。盘上只有文本 ⇒ 存储必须"带伤装载"，不能把程序丢掉。
		ArmProgram dangling = new ArmProgram();
		ArmProgramTask danglingMain = new ArmProgramTask();
		danglingMain.name = "main";
		ArmProgramEntry danglingCall = new ArmProgramEntry();
		danglingCall.op = OperationType.CALL;
		danglingCall.callTarget = "sub_task_gone"; // 程序里根本没有这个名字的任务
		danglingMain.entries.add(danglingCall);
		dangling.tasks.add(danglingMain);
		// 前提：编译**确实**报错 —— 而且报错时值**仍然有内容**（这正是 §17.10 改掉的那条契约：
		// 原来是 value=null ⇒ "编译有错"等于"内容没了"）。这条前提不成立，下面那条就测不到东西。
		ScriptResult<ArmProgram> strict = ScriptCompiler.compile(ScriptCompiler.decompile(dangling, registries),
			registries);
		boolean compilerRejectsButKeeps = strict.hasErrors() && strict.value() != null;
		ItemStack danglingCard = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.write(danglingCard, dangling, registries);
		ArmProgram danglingBack = CardProgramStorage.read(danglingCard, registries);
		boolean danglingKept = danglingBack.tasks.size() == 1
			&& danglingBack.tasks.get(0)
				.entries.size() == 1
			&& "sub_task_gone".equals(danglingBack.tasks.get(0)
				.entries.get(0).callTarget);
		LOGGER.info("[selftest] 悬空任务名（删掉被调用的任务）: 编译器报错但保住内容={} 存储往返不死={}", compilerRejectsButKeeps,
			danglingKept);
		if (!compilerRejectsButKeeps)
			problems.add("前提不成立：悬空任务名没被编译器当错误，或编译有错时值仍是空的（契约被改回去了）");
		if (!danglingKept)
			problems.add("悬空任务名的程序在存储往返里丢了内容（读回来是空的 ⇒ 玩家的程序会没）");

		// ③ 臂本体：存档 tag 里是 ProgramText、没有 Program；**另起一条全新的臂**读它（走 read 真实入口）
		BlockPos armPos = new BlockPos(0, 200, 0);
		BlockState oldState = level.getBlockState(armPos);
		boolean armIsText = false;
		boolean armCompileOnLoad = false;
		boolean armLegacyDropped = false;
		try {
			// 臂内的程序**不带原点**（用户第四十轮：原点住在卡上，臂运行时也不看它）⇒ 夹具就是
			// 一份普通程序，两边反编译出来的文本必须**逐字相同**（这与"文本能编译回同一份程序"是同一件事）。
			ArmProgram forArm = DevPresets.mainWithSubTasks();
			CompoundTag tag = armTagWith(level, armPos, forArm, registries);
			armIsText = tag != null && tag.contains("ProgramText", Tag.TAG_STRING) && !tag.contains("Program");
			if (tag != null) {
				SequencedArmBlockEntity fresh = freshArm(level, armPos);
				if (fresh != null) {
					fresh.read(tag, registries, false);
					armCompileOnLoad = !fresh.getProgram()
						.isEmpty()
						&& ScriptCompiler.decompile(fresh.getProgram(), registries)
							.equals(ScriptCompiler.decompile(forArm, registries));
					// 旧字段：去掉文本、把编译结果塞回去 ⇒ 必须当空程序（用户选的"不兼容"，§17.3）
					CompoundTag legacyTag = tag.copy();
					legacyTag.remove("ProgramText");
					legacyTag.put("Program", forArm.save(registries));
					fresh.read(legacyTag, registries, false);
					armLegacyDropped = fresh.getProgram()
						.isEmpty();
				}
			}
		} finally {
			level.setBlockAndUpdate(armPos, oldState);
		}
		LOGGER.info("[selftest] 臂本体存储: 盘上是文本={} 载入即编译={} 旧字段被丢弃={} 旧格式卡当空={}", armIsText,
			armCompileOnLoad, armLegacyDropped, legacyCardEmpty);
		if (!armIsText)
			problems.add("臂的存档 tag 里不是文本（或仍留着旧的 Program 字段）");
		if (!armCompileOnLoad)
			problems.add("臂载入时没有从文本编译出程序");
		if (!armLegacyDropped)
			problems.add("旧格式（编译结果）没有被丢弃，仍被当成程序");
		if (!legacyCardEmpty)
			problems.add("旧格式卡片仍被当成有程序");

		if (!problems.isEmpty())
			LOGGER.error("[selftest] 文本存储结构断言失败：{}", String.join("；", problems));
	}

	/** 放一条臂、装程序、写出存档 tag（null = 方块实体没建出来）。方块的恢复由调用方负责。 */
	@Nullable
	private static CompoundTag armTagWith(ServerLevel level, BlockPos armPos, ArmProgram program,
		HolderLookup.Provider registries) {
		SequencedArmBlockEntity arm = freshArm(level, armPos);
		if (arm == null)
			return null;
		arm.setProgram(program);
		CompoundTag tag = new CompoundTag();
		arm.write(tag, registries, false);
		return tag;
	}

	/** 在 {@code armPos} 放一条**全新**的臂（先清掉可能存在的旧方块实体）并返回它。 */
	@Nullable
	private static SequencedArmBlockEntity freshArm(ServerLevel level, BlockPos armPos) {
		level.setBlockAndUpdate(armPos, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
			.defaultBlockState());
		return level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm ? arm : null;
	}

	/**
	 * DEPLOY 的真实性验证：拿数据包里真实存在的 {@code create:deploying} 配方
	 * （{@code exposed_chiseled_copper} + 斧头 → {@code chiseled_copper}，且 {@code keep_held_item: true}）
	 * 喂给配方解析器，确认三族查找链路真的能命中。
	 *
	 * <p>这条链路的签名很容易在编译通过的情况下仍然错（泛型、注册表未就绪、包装类型不对），
	 * 所以值得在启动期实测一次。上游若改了这条配方，这里退化成 WARN，不会误报成致命错误。
	 */
	/**
	 * 「只进不出」分类的自检（用户第二十四轮实测）。
	 *
	 * <p>用户报的是："打包机被认为只能作为输出端（会提示这个元件是类似漏斗的只进不出）"。
	 * 旧判据是"有 {@code InvManipulationBehaviour} 就算只进不出"，两个方向都会错：
	 * <b>打包机</b>有这个行为对象、原版给的却是普通交互点（可以取），而
	 * <b>烈焰人 / 营火 / 粉碎轮控制器</b>没有那个行为对象、原版给的却是只进不出。
	 * 现在直接问 Create 的交互点注册表（{@link TargetInteraction#isDepositOnly}），这条自检把两边都钉住。
	 */
	private static void checkArmPointClassification(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos pos = new BlockPos(level.getSharedSpawnPos()
			.getX() + 4, 200, level.getSharedSpawnPos()
				.getZ() + 4);
		StringBuilder table = new StringBuilder();
		boolean ok = true;

		BlockState funnel = stateOf("create:brass_funnel");
		// 输入模式（EXTRACTING=false）：原版给的就是"只进不出"那一类
		ok &= expectPoint(table, level, pos, "输入漏斗", funnel, true, true);
		// 输出模式（EXTRACTING=true）：原版 `FunnelType.canCreatePoint` 直接为假 —— 连交互点都不是
		ok &= expectPoint(table, level, pos, "输出漏斗",
			funnel == null ? null : funnel.setValue(FunnelBlock.EXTRACTING, true), false, false);
		// ★ 用户报的那个：打包机可以取，不该被判成只进不出
		ok &= expectPoint(table, level, pos, "打包机", stateOf("create:packager"), false, true);
		ok &= expectPoint(table, level, pos, "置物台", stateOf("create:depot"), false, true);
		// 另一个方向：这两个没有 InvManipulationBehaviour，原版却是只进不出
		ok &= expectPoint(table, level, pos, "烈焰人", stateOf("create:blaze_burner"), true, true);
		ok &= expectPoint(table, level, pos, "营火", stateOf("minecraft:campfire"), true, true);
		ok &= expectPoint(table, level, pos, "重生锚", stateOf("minecraft:respawn_anchor"), true, true);
		// 反例锚：普通方块既不是交互点、也不是"只进不出"
		ok &= expectPoint(table, level, pos, "石头", Blocks.STONE.defaultBlockState(), false, false);
		// ⚠️ 不加粉碎轮控制器：它**不能这样摆** —— `CrushingWheelControllerBlock` 是两块粉碎轮之间的
		// 技术方块（`VALID` 属性），单独放下会立刻自我清除，于是"世界里其实没有这个方块"。
		// 第一版就这么写了，自检当场报出"粉碎轮控制器=可取(期望 只进不出)" —— 错的是自检，不是判据
		// （当时 `isInteractable` 用的是我传进去的意图状态，而不是世界里实际的状态，见 probe）。

		LOGGER.info("[selftest] 取物点分类（照原版注册表）: {}", table.toString()
			.trim());
		if (!ok)
			LOGGER.error("[selftest] 取物点分类与原版不一致 —— 「取出」会在这些元件上判错：{}", table.toString()
				.trim());

		// 分发优先级（上一轮修的那个坑）：漏斗**没有**通用物品能力，必须走它自己的行为对象。
		// 这条一旦坏了，"放入漏斗"就永远失败（通用能力为 null）。
		if (funnel != null) {
			Probe funnelProbe = probe(level, pos, funnel);
			LOGGER.info("[selftest] 漏斗分发: 只进不出={} 通用物品能力={}", funnelProbe.depositOnly(),
				funnelProbe.hasCapability());
			if (funnelProbe.hasCapability())
				LOGGER.warn("[selftest] 漏斗竟然有通用物品能力（上游变了？）—— 两条路径的优先级需要复核");
		}
	}

	/**
	 * 「往黄铜漏斗里放东西」必须让漏斗有反应（用户第二十四轮："对着黄铜漏斗放入的时候黄铜漏斗没有任何动画"）。
	 *
	 * <p>根因：原版 {@code FunnelPoint.insert}（`AllArmInteractionPointTypes:497-504`）在真的放进去之后
	 * 还会通知漏斗（{@code onTransfer} + {@code flap}），我们只做了"塞进它背后的容器"这一步。
	 *
	 * <p>这里把整条链真跑一遍：黄铜漏斗（输入模式、朝东、西边是箱子）+ 正对它的智能侦测器
	 * → 往漏斗里放一块铁 → 断言 ① 铁进了箱子 ② 侦测器被通知到了。
	 * {@code onTransfer} 在服务端唯一的可见效果就是侦测器（`turnOffTicks` 由 0 变 4），
	 * 所以侦测器的读数就是"漏斗内部被通知了"的证据；翻板动画（{@code flap}）只有客户端看得到，
	 * 服务端断言不了 —— 它和 {@code onTransfer} 是同一个 {@code if} 里的相邻两行，这里顺带确认
	 * "这次用的漏斗确实有翻板"（朝向水平时才有）。
	 *
	 * <p>第一版这条自检当场红了（`进了背后容器=false 漏斗被通知=false`）：刚放下的漏斗从没 tick 过，
	 * 它那个"背后容器"的缓存还是空的（`CapManipulationBehaviourBase` 只在 tick/lazyTick 里填），
	 * 于是 {@code insert} 原样退回物品、什么都不说。这是**真问题**（不只是自检的坑：
	 * "刚放下就投放"时臂会静默失败），修法见 {@code TargetInteraction.insert} 里那次
	 * {@code findNewCapability()} + "漏斗背后没有容器"的显式诊断。
	 */
	private static void checkFunnelTransferNotice(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos funnelPos = new BlockPos(spawn.getX() + 12, 200, spawn.getZ() + 12);
		BlockPos chestPos = funnelPos.west(); // 漏斗朝东 ⇒ 它"背后"（FACING 的反方向）是箱子
		BlockPos observerPos = funnelPos.north();

		BlockState funnelState = stateOf("create:brass_funnel");
		BlockState observerState = stateOf("create:content_observer");
		if (funnelState == null || observerState == null) {
			LOGGER.warn("[selftest] 漏斗通知自检跳过：缺少 create:brass_funnel / create:content_observer");
			return;
		}
		funnelState = funnelState.setValue(FunnelBlock.FACING, Direction.EAST);
		// 侦测器要"看着"漏斗：它检测的是 getTargetDirection(state)，墙面朝向下就是 FACING。
		// 侦测器在漏斗北边 ⇒ 从漏斗看过去是 NORTH ⇒ 侦测器必须朝 SOUTH。
		observerState = observerState.setValue(SmartObserverBlock.FACING, Direction.SOUTH);

		BlockState oldFunnel = level.getBlockState(funnelPos);
		BlockState oldChest = level.getBlockState(chestPos);
		BlockState oldObserver = level.getBlockState(observerPos);
		boolean moved = false;
		boolean chestHasItem = false;
		boolean notified = false;
		boolean hasFlap = false;
		String detail;
		try {
			level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
			level.setBlockAndUpdate(funnelPos, funnelState);
			level.setBlockAndUpdate(observerPos, observerState);
			hasFlap = level.getBlockEntity(funnelPos) instanceof FunnelBlockEntity funnel && funnel.hasFlap();
			int before = observerTicks(level, observerPos);
			TargetInteraction.Insert result = TargetInteraction.insert(level, funnelPos, new ItemStack(Items.IRON_INGOT), false);
			moved = result.remainder()
				.isEmpty();
			notified = observerTicks(level, observerPos) > before;
			chestHasItem = level.getBlockEntity(chestPos) instanceof Container container
				&& container.getContainerSize() > 0 && !container.getItem(0)
					.isEmpty();
			detail = "剩余=" + result.remainder()
				.getCount() + " 被挡=" + result.blockedReason();
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(observerPos, oldObserver);
			level.setBlockAndUpdate(funnelPos, oldFunnel);
			level.setBlockAndUpdate(chestPos, oldChest);
		}

		LOGGER.info("[selftest] 放入漏斗: 进了背后容器={} 箱子收到={} 漏斗被通知={} 这个漏斗有翻板={} （{}）", moved, chestHasItem,
			notified, hasFlap, detail);
		if (!moved || !chestHasItem)
			LOGGER.error("[selftest] 往黄铜漏斗放东西没有落进它背后的容器（{}）—— 漏斗那条分发路径坏了", detail);
		if (!notified)
			LOGGER.error("[selftest] 漏斗没有被通知（onTransfer 没调到）—— 用户实测的「对着黄铜漏斗放入时漏斗没有任何动画」就是少了这一步");
		if (!hasFlap)
			LOGGER.warn("[selftest] 这次用的漏斗没有翻板（朝向垂直时本来就没有）—— 翻板那条分支没被走到");
	}

	/**
	 * 「取出」判定链路的端到端自检（用户第二十四轮的那个提示）。
	 *
	 * <p>{@link #checkArmPointClassification} 证明的是判据本身与原版一致；这一条把<b>整条链路</b>跑一遍：
	 * 真的在世界里放一条臂 + 一个目标方块，真的喂一份「取出 2,0,0」的程序，真的跑一 tick 解释器，
	 * 读回它写下的失败原因（护目镜上黄字就是它）。
	 *
	 * <p>打包机**不允许**出现"只进不出"，而同样位置的漏斗**必须**出现 —— 后者是正对照：
	 * 少了它，这条自检在"判据永远返回 false"时也会通过。
	 */
	private static void checkTakeGateEndToEnd(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 56, 200, spawn.getZ() + 56);
		BlockPos targetPos = armPos.offset(2, 0, 0);

		TakeProbe packager = takeFailureAt(level, armPos, targetPos, stateOf("create:packager"));
		TakeProbe funnel = takeFailureAt(level, armPos, targetPos, stateOf("create:brass_funnel"));
		// "跑起来了"与"结论是什么"必须分开判：以前 `packager != null && !contains("只进不出")` 会把
		// "异常 …" / "方块实体没建出来" 这类**没跑起来**的结果当成通过（因为那些字符串里也没有"只进不出"）。
		boolean packagerOk = packager.ran()
			&& (packager.message() == null || !packager.message()
				.contains("只进不出"));
		boolean funnelBlocked = funnel.ran() && funnel.message() != null && funnel.message()
			.contains("只进不出");

		LOGGER.info("[selftest] 「取出」端到端: 打包机→「{}」(跑起来了={}) 漏斗→「{}」(跑起来了={})", text(packager.message()),
			packager.ran(), text(funnel.message()), funnel.ran());
		if (!packager.ran())
			LOGGER.error("[selftest] 打包机那条端到端自检**没跑起来**（{}）—— 等于没测", packager.message());
		else if (!packagerOk)
			LOGGER.error("[selftest] 打包机上「取出」仍被判成只进不出（用户报的就是这个）：{}", packager.message());
		if (!funnel.ran())
			LOGGER.error("[selftest] 漏斗那条端到端自检**没跑起来**（{}）—— 正对照失效", funnel.message());
		else if (!funnelBlocked)
			LOGGER.error("[selftest] 漏斗上「取出」没有被判成只进不出（{}）—— 判据改坏了？这条是正对照", funnel.message());
	}

	/** 日志用：null 也要看得见（那代表"前置检查放行了"，不是"没测"）。 */
	private static String text(@Nullable String message) {
		return message == null ? "无失败（前置检查放行）" : message;
	}

	/** 一次「取出」探针的结果：{@code ran} = 臂与程序真的跑起来了（"失败原因"与"压根没跑起来"必须分开）。 */
	private record TakeProbe(@Nullable String message, boolean ran) {
	}

	/** 在 {@code armPos} 放一条臂、在 {@code targetPos} 放目标方块，跑一 tick 一份「取出 2,0,0」的程序，读回失败原因。 */
	private static TakeProbe takeFailureAt(ServerLevel level, BlockPos armPos, BlockPos targetPos, @Nullable BlockState state) {
		if (state == null)
			return new TakeProbe(null, false);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldTarget = level.getBlockState(targetPos);
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(targetPos, state);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm))
				return new TakeProbe("臂的方块实体没建出来", false);
			arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过（与原版一致）
			ArmProgram program = new ArmProgram();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "自检";
			ArmProgramEntry entry = new ArmProgramEntry();
			entry.op = OperationType.TAKE;
			entry.offset = targetPos.subtract(armPos);
			task.entries.add(entry);
			program.tasks.add(task);
			arm.setProgram(program);
			arm.runtime.tick(arm);
			return new TakeProbe(arm.runtime.getLastFailure(), true);
		} catch (Throwable t) {
			return new TakeProbe("异常 " + t, false);
		} finally {
			level.setBlockAndUpdate(targetPos, oldTarget);
			level.setBlockAndUpdate(armPos, oldArm);
		}
	}

	/**
	 * 「前置检查失败后的重试是**每 tick** 的」（对齐上游动力臂：`ArmBlockEntity.tick()` 每 tick 都
	 * `searchForItem()` / `collectItem()`，`ArmBlockEntity.java:126-152`）。
	 *
	 * <p><b>为什么要这条断言</b>：这里原来是 10 tick 的节流（我们自己发明的"避免每 tick 查能力"），
	 * 在传送带上直接表现为"取不到 + 物品从眼前溜走"—— 高速带上的物品在点位上只停留几 tick，
	 * 每 10 tick 才看一眼必然错过，而且每次失败还会 `cancelMove()` 把手臂收回去
	 * （用户第二十七轮续二十三实测）。
	 *
	 * <p><b>判据为什么能分辨</b>：前置检查通过时 `beginEntry` 会**立刻**清掉黄字失败提示
	 * （`ProgramRuntime:392`），所以"放进物品后的**下一个** tick 提示就消失" ⟺ 重试没有间隔。
	 * 10 tick 节流下第二个 tick 只会去减 cooldown、提示仍在 ⇒ 这条断言会变红（做得到反证）。
	 */
	private static void checkPrecheckRetryIsPerTick(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 60, 200, spawn.getZ() + 60);
		BlockPos depotPos = armPos.offset(2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldDepot = level.getBlockState(depotPos);
		try {
			BlockState depotState = stateOf("create:depot");
			if (depotState == null) {
				LOGGER.error("[selftest] 重试节流自检**没跑起来**：找不到 create:depot");
				return;
			}
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotPos, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 重试节流自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过（与原版一致）
			ArmProgram program = new ArmProgram();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "自检";
			ArmProgramEntry entry = new ArmProgramEntry();
			entry.op = OperationType.TAKE;
			entry.offset = depotPos.subtract(armPos);
			task.entries.add(entry);
			program.tasks.add(task);
			arm.setProgram(program);

			arm.runtime.tick(arm); // ① 空载具 ⇒ 前置检查不通过（并写出黄字原因）
			String emptyFailure = arm.runtime.getLastFailure();
			boolean sawFailure = emptyFailure != null;

			boolean inserted = putOneInto(level, depotPos); // ② 走方块自己的物品能力放一个
			arm.runtime.tick(arm);                          // ③ **只跑一个 tick**
			String afterInsert = arm.runtime.getLastFailure();
			boolean cleared = afterInsert == null;
			// ③ 之后程序已经 `beginMove` ⇒ 取物类应当处于"要续锁"的窗口里
			boolean pinWhileTaking = arm.runtime.shouldPinTarget(arm);
			// ③b **断掉应力后仍然继续锁** —— 这是原版语义（上游只看 `chasedPointProgress < 1`），
			//    用户第二十七轮续二十五确认过："停机臂无限期占住物品是原版逻辑，我们的没有问题"。
			//    所以这里断言的是"**仍然**在锁"（真）—— 将来谁把它改成"没应力就松手"，这条会红。
			arm.setSpeed(0);
			boolean pinWithoutStress = arm.canPinTarget();
			arm.setSpeed(32); // 还原，后面的反例还要它伸过去

			// ⑥ **退出重进后仍然续锁**：读盘会把 `movingPoint` 清成 null（只留 phase/Offset），
			//    旧实现拿缓存当判据 ⇒ 重登即松手（用户第二十七轮续二十六实测：重进后物品被释放了）。
			//    这里真存一份 NBT、读进一个新 BE（同一坐标，目标是世界里那个真载具），断言仍然续锁。
			CompoundTag snapshot = arm.saveWithoutMetadata(server.registryAccess());
			SequencedArmBlockEntity reloaded = new SequencedArmBlockEntity(SequencedArmRegistration.SEQUENCED_ARM_BE.get(), armPos,
				SequencedArmRegistration.SEQUENCED_ARM.get()
					.defaultBlockState());
			reloaded.setLevel(level);
			reloaded.read(snapshot.copy(), server.registryAccess(), false);
			boolean pinAfterReload = reloaded.canPinTarget() && reloaded.pinPoint() != null;
			// ⑥b **重登后第一次取物不能空**：读盘会把 `movingHandler` 清成 null，而 `getMovingHandler()`
			//     旧写法在缓存为空时直接返回 null（自愈分支跑不到）⇒ 第一次 `take()` 必失败、重试才成功
			//     （用户第二十七轮续二十七实测："接上应力后第一下拿物品会拿空"）。
			boolean handlerAfterReload = reloaded.getMovingHandler() != null;
			boolean takeReadyAfterReload = pinAfterReload && handlerAfterReload;
			// 反例（挖掘）的结果：见 ④
			boolean otherRan = false;
			boolean otherPins = true;

			// ④ 反例：**"要取东西"之外的有目标条目不该锁**（一次投放/挖掘不该把整条带子停住几秒）。
			//    用「挖掘」当反例：它同样有目标、同样会伸过去，但不需要锁目标上的物品；
			//    换成「投放」要多两件夹具（手上得有东西 + 目标得放得下），第一版就是这么失败的
			//    —— 而且夹具一坏就变成"没跑起来"，所以宁可选夹具最少的那个反例。
			BlockState depotOldState = level.getBlockState(depotPos);
			BlockState stoneState = stateOf("minecraft:stone");
			if (stoneState == null) {
				LOGGER.error("[selftest] 锁物品窗口反例**没跑起来**：找不到 minecraft:stone");
			} else {
				level.setBlockAndUpdate(depotPos, stoneState);
				ArmProgram mineProgram = new ArmProgram();
				ArmProgramTask mineTask = new ArmProgramTask();
				mineTask.name = "自检挖掘";
				ArmProgramEntry mineEntry = new ArmProgramEntry();
				mineEntry.op = OperationType.MINE;
				mineEntry.offset = depotPos.subtract(armPos);
				mineEntry.face = Direction.UP;
				mineTask.entries.add(mineEntry);
				mineProgram.tasks.add(mineTask);
				arm.setProgram(mineProgram);
				arm.runtime.tick(arm);
				otherRan = arm.runtime.getLastFailure() == null;
				otherPins = arm.runtime.shouldPinTarget(arm);
				level.setBlockAndUpdate(depotPos, depotOldState);
			}

			LOGGER.info("[selftest] 前置检查重试节流: 空载具报因={} 放物品后下一 tick 就放行={} 间隔常量={} "
				+ "锁窗口: 取物移动中={} 断应力后={} 重登后={}（重登后句柄={}） 挖掘移动中={}（挖掘跑起来了={}）",
				sawFailure, cleared, ProgramRuntime.PRECHECK_RETRY_TICKS, pinWhileTaking, pinWithoutStress,
				takeReadyAfterReload, handlerAfterReload, otherPins, otherRan);
			if (!sawFailure)
				LOGGER.error("[selftest] 重试节流自检**没跑起来**：空载具时没报出失败原因（{}）—— 等于没测",
					text(emptyFailure));
			else if (!inserted)
				LOGGER.error("[selftest] 重试节流自检**没跑起来**：物品没能放进载具 —— 这条断言等于没测");
			else if (!cleared)
				LOGGER.error("[selftest] 前置检查**不是每 tick 重查**（放了物品后一个 tick 仍报「{}」）—— "
					+ "传送带上会表现为「取不到 + 物品溜走」，对齐上游必须每 tick 查", afterInsert);
			if (!pinWhileTaking)
				LOGGER.error("[selftest] 取物/组装在移动途中**没有续锁**目标物品 —— 传送带会把物品带走"
					+ "（上游 `ArmBlockEntity:133-140` 是每 tick 锁；到位那一 tick 也要锁）");
			if (!pinAfterReload)
				LOGGER.error("[selftest] 退出重进后**不再续锁**目标物品（读盘会把 `movingPoint` 清空，"
					+ "判据必须能按 `movingOffset` 重建）—— 已经定住的物品会被放回传送带");
			if (!handlerAfterReload)
				LOGGER.error("[selftest] 退出重进后 `getMovingHandler()` 返回 null（缓存为空时必须重解析）"
					+ "—— 表现就是重登后**第一次取物拿空**，重试才成功");
			if (!pinWithoutStress)
				LOGGER.error("[selftest] 断掉应力后**不再**续锁目标物品 —— 与原版（`ArmBlockEntity:133-140`）"
					+ "不一致：停机臂应当继续占住物品（用户确认过这是期望行为）");
			if (!otherRan)
				LOGGER.error("[selftest] 锁窗口反例（挖掘）**没跑起来**（{}）—— 这条反例等于没测",
					text(arm.runtime.getLastFailure()));
			else if (otherPins)
				LOGGER.error("[selftest] 非取物类（挖掘）也在续锁目标物品 —— 会把不该停的带子停住");
		} catch (Throwable t) {
			LOGGER.error("[selftest] 重试节流自检抛异常：{}", t.toString());
		} finally {
			level.setBlockAndUpdate(depotPos, oldDepot);
			level.setBlockAndUpdate(armPos, oldArm);
		}
	}

	/**
	 * 「组装的前置检查要**逐件找**，不能被产物挡住」（用户第二十七轮续三十六实测）。
	 *
	 * <p><b>为什么要有这条断言</b>：前置检查原来只看"第一个非空槽/那一格最靠前的一件"。
	 * 而在传送带上，最靠前的那件往往正是**刚加工出来的产物** —— 它不匹配配方，于是
	 * 前置检查报"没有匹配的配方"、手臂**干脆不伸出去**，后面那件能加工的**原料**永远等不到机会
	 * （用户原话："组装的时候臂会空一轮"）。上游没有这个问题：机械手（Deployer）是**逐件回调**的，
	 * 不匹配的直接放行（`BeltDeployerCallbacks:58-59`）。
	 *
	 * <p><b>判据为什么能分辨</b>：夹具是"产物在前、原料在后"的两格容器。
	 * ① 前置检查**必须放行**（说明它在逐件找）；把它改回"只看第一件" ⇒ 立刻报"没有匹配的配方" ⇒ 红。
	 * ② 反例：把原料拿走、只留产物 ⇒ 必须**报错**（否则说明这条断言永远绿、等于没测）。
	 * 夹具的两件物品都是**运行时从配方表里发现的**（不写死物品 id，避免配方改名/换包就失效）。
	 */
	private static void checkDeployPrecheckScansAllSlots(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 80, 200, spawn.getZ() + 80);
		BlockPos targetPos = armPos.offset(2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldTarget = level.getBlockState(targetPos);
		try {
			// ① 运行时找一份真实的 item_application 配方，问出"哪件是被加工物、哪件是手上的应用物"
			ItemStack raw = ItemStack.EMPTY;
			ItemStack applicator = ItemStack.EMPTY;
			for (RecipeHolder<?> holder : level.getRecipeManager()
				.getAllRecipesFor(AllRecipeTypes.ITEM_APPLICATION.getType())) {
				List<Ingredient> ingredients = holder.value()
					.getIngredients();
				if (ingredients.size() < 2)
					continue;
				for (ItemStack a : ingredients.get(0)
					.getItems()) {
					for (ItemStack b : ingredients.get(1)
						.getItems()) {
						if (DeployRecipes.find(level, a, b) != null) {
							raw = a.copy();
							applicator = b.copy();
						} else if (DeployRecipes.find(level, b, a) != null) {
							raw = b.copy();
							applicator = a.copy();
						}
						if (!raw.isEmpty())
							break;
					}
					if (!raw.isEmpty())
						break;
				}
				if (!raw.isEmpty())
					break;
			}
			if (raw.isEmpty()) {
				LOGGER.error("[selftest] 组装前置检查自检**没跑起来**：配方表里找不到任何 item_application 配方");
				return;
			}
			// ② "产物" = 一件**问过配方系统确认不匹配**的物品（不靠猜）
			ItemStack product = ItemStack.EMPTY;
			for (ItemStack candidate : List.of(new ItemStack(Items.DIRT), new ItemStack(Items.STONE),
				new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.GRAVEL))) {
				if (DeployRecipes.find(level, candidate, applicator) == null) {
					product = candidate;
					break;
				}
			}
			if (product.isEmpty()) {
				LOGGER.error("[selftest] 组装前置检查自检**没跑起来**：找不到一件不匹配配方的「产物」");
				return;
			}
			// ③ 目标方块：挑一个"至少 2 格、且是 Create 交互点"的容器（产物占前排、原料在后排）
			IItemHandler handler = null;
			for (String id : new String[] { "create:packager", "create:repackager", "create:chute",
				"create:basin", "create:depot" }) {
				BlockState candidate = stateOf(id);
				if (candidate == null)
					continue;
				level.setBlockAndUpdate(targetPos, candidate);
				IItemHandler probe = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, Direction.UP);
				if (probe != null && probe.getSlots() >= 2) {
					handler = probe;
					break;
				}
			}
			if (handler == null) {
				LOGGER.error("[selftest] 组装前置检查自检**没跑起来**：找不到一个 ≥2 格且属于 Create 交互点的容器");
				return;
			}
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 组装前置检查自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32);
			arm.setHeldItem(applicator.copy());
			ArmProgram program = new ArmProgram();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "自检组装";
			ArmProgramEntry entry = new ArmProgramEntry();
			entry.op = OperationType.DEPLOY;
			entry.offset = targetPos.subtract(armPos);
			task.entries.add(entry);
			program.tasks.add(task);

			// ④ 产物在前、原料在后 ⇒ 必须放行
			handler.insertItem(0, product.copy(), false);
			boolean fedRaw = handler.insertItem(1, raw.copyWithCount(1), false)
				.isEmpty();
			arm.setProgram(program);
			arm.runtime.tick(arm);
			String afterBoth = arm.runtime.getLastFailure();
			boolean passedWithProductInFront = afterBoth == null;

			// ⑤ 反例：只剩产物 ⇒ 必须报错（证明"放行"不是因为判据永远为真）
			ItemStack takenBack = handler.extractItem(1, 64, false);
			arm.runtime.resetToStart(arm);
			arm.runtime.tick(arm);
			String afterProductOnly = arm.runtime.getLastFailure();

			LOGGER.info("[selftest] 组装前置检查逐件找: 产物={} 原料={} 应用物={} "
				+ "（产物在前也放行={} 只剩产物就报错={}）",
				product.getHoverName()
					.getString(),
				raw.getHoverName()
					.getString(),
				applicator.getHoverName()
					.getString(),
				passedWithProductInFront, afterProductOnly != null);

			if (!fedRaw)
				LOGGER.error("[selftest] 组装前置检查自检**没跑起来**：原料没能放进第二格 —— 等于没测");
			else if (!takenBack.is(raw.getItem()) && !takenBack.is(raw.getItem()
				.getCraftingRemainingItem()))
				LOGGER.error("[selftest] 组装前置检查自检**没跑起来**：反例那步没能把原料取回来 —— 等于没测");
			if (!passedWithProductInFront)
				LOGGER.error("[selftest] 「组装」前置检查被**前面那件产物**挡住了（报出「{}」）—— "
					+ "手臂于是不伸出去，而后面那件能加工的原料永远等不到机会（用户实测：臂会空一轮）。"
					+ "上游是逐件回调、不匹配就放行（`BeltDeployerCallbacks:58-59`），判据必须与真正加工时同源",
					text(afterBoth));
			if (afterProductOnly == null)
				LOGGER.error("[selftest] 「组装」前置检查**只剩产物也放行** —— 这条断言永远绿、等于没测");
		} catch (Throwable t) {
			LOGGER.error("[selftest] 组装前置检查自检抛异常：{}", t.toString());
		} finally {
			level.setBlockAndUpdate(targetPos, oldTarget);
			level.setBlockAndUpdate(armPos, oldArm);
		}
	}

	/**
	 * 「收回属于**这一次**操作：折返没走完就不动身」（用户第二十七轮续三十七实测，对照原版动力臂动画）。
	 *
	 * <p><b>用户的原话（对照两条臂一步一步看出来的）</b>：
	 * <pre>
	 *   原版：旋转&amp;伸出 → 拿取 → **收回** → 旋转&amp;伸出 → 放入 → 收回
	 *   我们：旋转&amp;伸出 → 拿取 → **收回&amp;旋转&amp;伸出** → 放入 → 收回
	 * </pre>
	 * 即我们把"收回"并进了**下一次**动作的前半段 ⇒ 折回的同时就在转向新目标。
	 * 正确的语义：{@code progress} 走到 1.00 只是"伸出到位"；而**收回**是这一条操作的最后一段。
	 *
	 * <p><b>判据为什么能分辨</b>：第二条目标（B）里**放着东西**，所以第一条成功后前置检查必然放行 ——
	 * 于是"有没有动身"就完全取决于那道 {@code isAtRest()} 闸门：
	 * ① 折返这一段（进度停在 0）里必须**一直待机**、且**不报失败**（说明它看得见、只是不动身）；
	 * ② 折返走完（进度过 .5）后必须**立刻**动身（不许卡死）；
	 * ③ 最后第二条要真的把物品取到（端到端，证明这段等待没把程序卡住）。
	 * 把闸门去掉 ⇒ ①立刻变红（第一条成功的下一 tick 就 `beginMove` 了）。
	 */
	private static void checkRetractBeforeNextMove(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 90, 200, spawn.getZ() + 90);
		BlockPos depotA = armPos.offset(2, 0, 0);
		BlockPos depotB = armPos.offset(-2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldA = level.getBlockState(depotA);
		BlockState oldB = level.getBlockState(depotB);
		try {
			BlockState depotState = stateOf("create:depot");
			if (depotState == null) {
				LOGGER.error("[selftest] 收回时序自检**没跑起来**：找不到 create:depot");
				return;
			}
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotA, depotState);
			level.setBlockAndUpdate(depotB, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 收回时序自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32); // 一个单位 = 1024/32 = 32 tick，折返到位 = 16 tick
			ArmProgram program = new ArmProgram();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "自检收回";
			for (BlockPos target : new BlockPos[] { depotA, depotB }) {
				ArmProgramEntry entry = new ArmProgramEntry();
				entry.op = OperationType.TAKE;
				entry.offset = target.subtract(armPos);
				task.entries.add(entry);
			}
			program.tasks.add(task);
			arm.setProgram(program);

			boolean fedA = putOneInto(level, depotA);
			boolean fedB = putOneInto(level, depotB);
			// ① 跑到第一条成功（A 被取空）。自检不跑运动学，每轮把进度当作"已到位"。
			boolean firstDone = false;
			for (int i = 0; i < 200 && !firstDone; i++) {
				arm.chasedPointProgress = 1f;
				arm.runtime.tick(arm);
				firstDone = isEmpty(level, depotA);
			}
			// 此刻 `succeed()` 里的 `retract()` 刚把进度清零 ⇒ 手臂正在折返，绝不该算"可以动身"
			boolean restingFlagRightAfter = arm.isAtRest();

			// ② 折返这一段：**不推进进度**（时间在走、动画在折），只 tick —— 必须"看得见但不动身"
			boolean stayedHome = true;
			boolean noFailureWhileRetracting = true;
			for (int i = 0; i < 12; i++) {
				arm.runtime.tick(arm);
				stayedHome &= arm.phase == SequencedArmBlockEntity.Phase.IDLE;
				noFailureWhileRetracting &= arm.runtime.getLastFailure() == null;
			}
			boolean stillHoldingB = !isEmpty(level, depotB);

			// ③ 折返走完（进度到静止位）⇒ 必须立刻动身，而且**从整段的起点（0）起手**：
			//    前半段是"站在静止位转身"（臂角度不变），后半段才伸出 ⇒ 伸出开始时旋转已过半。
			//    用户第二十七轮续三十八实测的原版观感："转到一半才开始伸出（大约在角平分线上）"。
			boolean movedAfterRest = false;
			boolean startedFromZero = false;
			boolean atRestBeforeMove = false;
			float progressAtMoveStart = -1f;
			for (int i = 0; i < 40 && !movedAfterRest; i++) {
				arm.chasedPointProgress = 1f; // 模拟折返走完
				atRestBeforeMove = arm.isAtRest(); // 动身之前必须真的在静止位
				arm.runtime.tick(arm);
				movedAfterRest = arm.phase == SequencedArmBlockEntity.Phase.MOVING;
				if (movedAfterRest) {
					progressAtMoveStart = arm.chasedPointProgress;
					startedFromZero = progressAtMoveStart == 0f;
				}
			}

			// ④ 端到端：第二条也要真的取到
			boolean secondDone = false;
			for (int i = 0; i < 200 && !secondDone; i++) {
				arm.chasedPointProgress = 1f;
				arm.runtime.tick(arm);
				secondDone = isEmpty(level, depotB);
			}

			// ③b **"伸出开始时旋转已过半"**（用户第二十七轮续三十八实测的原版观感）。
			//     这是纯插值数学，直接问两个因子（渲染用的就是它们，同一份代码 —— 决策 68）：
			//       `extensionFactor(.5) == 0` ⇒ 伸出正好从 .5 开始；
			//       `rotationFactor(.5) == .5` ⇒ 那一刻旋转正好走完一半（视觉上接近角平分线）。
			boolean rotateLeadsExtension = Math.abs(SequencedArmBlockEntity.rotationFactor(.5f) - .5f) < 1e-4f
				&& Math.abs(SequencedArmBlockEntity.extensionFactor(.5f)) < 1e-4f;
			boolean extensionEndsWithRotation = Math.abs(SequencedArmBlockEntity.extensionFactor(1f) - 1f) < 1e-4f
				&& Math.abs(SequencedArmBlockEntity.rotationFactor(1f) - 1f) < 1e-4f;
			boolean noRotationAtStart = Math.abs(SequencedArmBlockEntity.rotationFactor(0f)) < 1e-4f
				&& Math.abs(SequencedArmBlockEntity.extensionFactor(0f)) < 1e-4f;

			LOGGER.info("[selftest] 收回属于本次操作: 折返途中可动身={}（应为 false） 折返途中一直待机={} "
				+ "折返途中不报失败={}（B 还在={}） 折返走完立刻动身={} 动身前在静止位={} 从 0 起手={}（进度={}） "
				+ "第2条真的取到={} ｜ 旋转领先伸出={}（转动半程才开始伸） 伸出与旋转同时收尾={} 起手时都不动={}",
				restingFlagRightAfter, stayedHome, noFailureWhileRetracting, stillHoldingB, movedAfterRest,
				atRestBeforeMove, startedFromZero, progressAtMoveStart, secondDone, rotateLeadsExtension,
				extensionEndsWithRotation, noRotationAtStart);

			if (!fedA || !fedB)
				LOGGER.error("[selftest] 收回时序自检**没跑起来**：物品没能放进两个载具 —— 等于没测");
			else if (!firstDone)
				LOGGER.error("[selftest] 收回时序自检**没跑起来**：第一条条目没成功 —— 等于没测");
			if (restingFlagRightAfter)
				LOGGER.error("[selftest] 条目成功后 `isAtRest()` 就是 true —— 说明**根本没有折返段**，"
					+ "收回又被并进下一次动作了（原版是「旋转+伸出 → 动作 → 收回」）");
			if (!stayedHome || !noFailureWhileRetracting)
				LOGGER.error("[selftest] 折返途中**动身了**（待机={} 不报失败={}）—— 收回必须属于本次操作："
					+ "折返没走完就开始下一次伸出，观感就是用户实测的「收回&旋转&伸出」", stayedHome,
					noFailureWhileRetracting);
			if (!movedAfterRest)
				LOGGER.error("[selftest] 折返走完之后**没有动身** —— 那道「等折返」的闸门把程序卡死了");
			if (movedAfterRest && (!startedFromZero || !atRestBeforeMove))
				LOGGER.error("[selftest] 折返走完后的这次移动不是「动身前在静止位（{}）且从 0 起手（进度={}）」"
					+ " —— 从 0 起手的前半段必须是原版的「站在静止位转身」，不能是折返", atRestBeforeMove,
					progressAtMoveStart);
			if (!rotateLeadsExtension)
				LOGGER.error("[selftest] **旋转没有领先伸出**（rotationFactor(.5)={} extensionFactor(.5)={}）—— "
					+ "原版是「转到一半才开始伸出」（旋转整段、伸出后半段，上游 `ArmBlockEntity:208` vs `:216`）；"
					+ "两个因子都取半段就会变成「一开始转就伸出」（用户第二十七轮续三十八实测）",
					SequencedArmBlockEntity.rotationFactor(.5f), SequencedArmBlockEntity.extensionFactor(.5f));
			if (!extensionEndsWithRotation)
				LOGGER.error("[selftest] 伸出与旋转**没有同时收尾**（ext(1)={} rot(1)={}）—— 到位那一刻两者都该是 1",
					SequencedArmBlockEntity.extensionFactor(1f), SequencedArmBlockEntity.rotationFactor(1f));
			if (!noRotationAtStart)
				LOGGER.error("[selftest] 移动起手那一刻就有动作（rot(0)={} ext(0)={}）—— 应当是「先转身、臂不动」",
					SequencedArmBlockEntity.rotationFactor(0f), SequencedArmBlockEntity.extensionFactor(0f));
			if (!secondDone)
				LOGGER.error("[selftest] 第二条条目最终没取到物品 —— 等待折返把程序卡住了");
		} catch (Throwable t) {
			LOGGER.error("[selftest] 收回时序自检抛异常：{}", t.toString());
		} finally {
			level.setBlockAndUpdate(depotA, oldA);
			level.setBlockAndUpdate(depotB, oldB);
			level.setBlockAndUpdate(armPos, oldArm);
		}
	}

	/**
	 * 「个数模式」自检（用户本轮口径：<b>等于 / 小于等于</b>，且这条属性**同时服务取出 / 放入 / 收存**）。
	 *
	 * <p>默认模式**固定是「小于等于」**（用户 2026-10-19 二次确认：「还是改成默认小于等于吧，
	 * 用到等于的情况更少」）：上一版「按操作给默认（取出 = 等于）」已经取消，所以下面的 NBT 段
	 * 断的是"取出与放入**都不写键**、老卡的缺键**都读成小于等于**"。
	 *
	 * <p>语义（已与用户对过，别再改）：<b>等于</b> = 必须能完成恰好 N 个，不够就走「失败后」（默认重试）；
	 * <b>小于等于</b> = 有多少动多少、上限 N，不足 N <b>不算失败</b>，也就是取出操作改造前的行为。
	 *
	 * <p>六条行为 + 一条 NBT 往返，每条的反证（改哪一行会变红）写在各自注释里。
	 * 夹具与 {@link #checkRetractBeforeNextMove} 同一套：真世界 + 真臂 + 真置物台 + 真 {@code runtime.tick()}，
	 * 自检**不跑运动学** —— 每拍把 {@code chasedPointProgress} 当作"已到位"（公开字段，见 PLAN §0.5 夹具注意）。
	 */
	private static void checkCountMode(MinecraftServer server) {
		// ——— ⑦ NBT 往返（不依赖世界，先跑：夹具坏了也不该把这条一起吞掉）———
		// ⚠️ 默认模式**固定是「小于等于」**（用户 2026-10-19 二次确认：「还是改成默认小于等于吧，
		//   用到等于的情况更少」）：`save` 只在**非** ATMOST 时写 `cmode`，`load` 缺键也回落 ATMOST，
		//   与操作无关（上一版「取出 = 等于」的按操作默认已取消）。
		// 反证：把 save() 里那句 `if (countMode != CountMode.ATMOST)` 改成无条件写 ⇒「默认不写键」红；
		//       把 load() 的 enumOf 兜底写死成 EXACT ⇒「老卡读成小于等于」红。
		ArmProgramEntry exactEntry = ArmProgramEntry.fresh(OperationType.TAKE);
		exactEntry.countMode = ArmProgramEntry.CountMode.EXACT;
		exactEntry.count = 3;
		CompoundTag exactTag = exactEntry.save(server.registryAccess());
		ArmProgramEntry exactBack = ArmProgramEntry.load(exactTag.copy(), server.registryAccess());
		boolean exactKept = exactBack.countIsExact() && exactBack.count == 3;
		boolean exactWroteKey = exactTag.contains("cmode");
		// 默认（小于等于）**不该**占 cmode 这个键 —— 取出与放入都一样（模式不再按操作区分）
		ArmProgramEntry takeAtMostEntry = ArmProgramEntry.fresh(OperationType.TAKE);
		takeAtMostEntry.count = 3;
		CompoundTag takeAtMostTag = takeAtMostEntry.save(server.registryAccess());
		ArmProgramEntry atMostEntry = ArmProgramEntry.fresh(OperationType.DEPOSIT);
		atMostEntry.count = 3;
		CompoundTag atMostTag = atMostEntry.save(server.registryAccess());
		boolean keyAbsent = !takeAtMostTag.contains("cmode") && !atMostTag.contains("cmode")
			&& !takeAtMostEntry.countIsExact() && !atMostEntry.countIsExact();
		// 老卡：只有 count、没有 cmode 的**取出**条目 ⇒ 读成「小于等于」（= 有多少拿多少，老行为）
		CompoundTag legacyTag = takeAtMostTag.copy();
		ArmProgramEntry legacyBack = ArmProgramEntry.load(legacyTag, server.registryAccess());
		boolean legacyTakeAtMost = !legacyBack.countIsExact() && legacyBack.count == 3;
		// 老卡：没有 cmode 的**放入**条目 ⇒ 同样读成「小于等于」（= 有多少放多少）
		CompoundTag legacyDepositTag = new CompoundTag();
		legacyDepositTag.putString("op", OperationType.DEPOSIT.name());
		legacyDepositTag.putInt("count", 3);
		ArmProgramEntry legacyDepositBack = ArmProgramEntry.load(legacyDepositTag, server.registryAccess());
		boolean legacyDepositAtMost = !legacyDepositBack.countIsExact() && legacyDepositBack.count == 3;

		// ——— ⑧ 「个数」的默认值**按操作给**（用户口径：放入 / 收存默认 64 = 整摞）———
		// 反证：
		//   ① 把 defaultCount() 里的 DEPOSIT / STASH 分支删掉（都回落到 1）⇒「放入 / 收存默认是 64」红；
		//   ② 把 load() 里那条"放入 + 没写过 cmode + count==1 ⇒ 64"的迁移删掉 ⇒「老卡的放入迁成整摞」红；
		//   ③ 把那句迁移的 `!tag.contains("cmode")` 去掉 ⇒「等于 1 的放入没被迁移」红（玩家真选的 1 被改掉）。
		boolean countDefaults = ArmProgramEntry.defaultCount(OperationType.DEPOSIT) == 64
			&& ArmProgramEntry.defaultCount(OperationType.STASH) == 64
			&& ArmProgramEntry.defaultCount(OperationType.TAKE) == 1;
		// 默认的放入**不该**算成"玩家改过"（countIsSet 要跟 defaultCount 比，不是跟 1 比）。
		// ⚠️ 用户 2026-10-19 改了显示口径：个数属于**填写的内容**，「取出 / 放入 / 收存」的第一行**永远**写出
		//    「最多 N 个 / 等于 N 个」（`ArmEntryText.base`，见 `ArmEntryText.selfCheck` 里那条断言）——
		//    所以这里钉的**不再是**"卡片上不显示"，而是"默认值不算非默认"（`describe()` 因此不拖 noise）。
		ArmProgramEntry freshDeposit = new ArmProgramEntry();
		freshDeposit.op = OperationType.DEPOSIT;
		freshDeposit.count = ArmProgramEntry.defaultCount(OperationType.DEPOSIT);
		boolean depositDefaultQuiet = !freshDeposit.countIsSet();
		// 老卡：改造前「放入」根本不读 count，存着的 1 是字段初始值 ⇒ 读进来必须变成 64
		CompoundTag oldDepositTag = new CompoundTag();
		oldDepositTag.putString("op", OperationType.DEPOSIT.name());
		oldDepositTag.putInt("count", 1);
		ArmProgramEntry oldDepositBack = ArmProgramEntry.load(oldDepositTag, server.registryAccess());
		boolean legacyDepositMigrated = oldDepositBack.count == 64;
		// 玩家**真的**选了"放入恰好 1 个"（EXACT 一定写过 cmode）⇒ 不许被那条迁移改掉
		CompoundTag exactOneTag = oldDepositTag.copy();
		exactOneTag.putString("cmode", ArmProgramEntry.CountMode.EXACT.name());
		ArmProgramEntry exactOneBack = ArmProgramEntry.load(exactOneTag, server.registryAccess());
		boolean exactOneKept = exactOneBack.count == 1 && exactOneBack.countIsExact();

		LOGGER.info("[selftest] 个数模式 NBT: 等于模式往返={} 非默认（等于）写了 cmode={} 默认（小于等于）不写键={} "
			+ "老卡·取出读成小于等于={} 老卡·放入读成小于等于={}", exactKept, exactWroteKey, keyAbsent,
			legacyTakeAtMost, legacyDepositAtMost);
		LOGGER.info("[selftest] 个数默认值: 放入=64 且 收存=64 且 取出=1={} 默认值不算非默认={} 老卡的放入迁成整摞={} 等于1的放入没被迁移={}",
			countDefaults, depositDefaultQuiet, legacyDepositMigrated, exactOneKept);
		if (!countDefaults)
			LOGGER.error("[selftest] 「个数」的默认值不是「取出 1 / 放入 64 / 收存 64」—— 默认的放入 / 收存会变成一次 1 个");
		if (!depositDefaultQuiet)
			LOGGER.error("[selftest] 默认的「放入」被算成了「玩家改过」（countIsSet 在拿 1 当基准，应该问 defaultCount）");
		if (!legacyDepositMigrated)
			LOGGER.error("[selftest] 老卡里的「放入」没有迁成 64（整摞）—— 那些卡会一次只放 1 个");
		if (!exactOneKept)
			LOGGER.error("[selftest] 显式选了「放入·等于 1 个」的条目被迁移改成了 64（cmode 键的判据丢了）");
		if (!exactKept)
			LOGGER.error("[selftest] 个数模式「等于」过不了 NBT 往返（存了 EXACT、读回来是别的模式）");
		if (!exactWroteKey)
			LOGGER.error("[selftest] 「等于」没有写进 cmode —— 非默认模式不写就丢语义（读回来会变成小于等于）");
		if (!keyAbsent)
			LOGGER.error("[selftest] 默认模式（小于等于）把 cmode 键写进了 NBT —— 默认值不该占 NBT 键（取出 / 放入都一样）");
		if (!legacyTakeAtMost)
			LOGGER.error("[selftest] 没有 cmode 键的老「取出」卡没有读成「小于等于」（应当是「有多少拿多少」）");
		if (!legacyDepositAtMost)
			LOGGER.error("[selftest] 没有 cmode 键的老「放入」卡没有读成「小于等于」（应当是「有多少放多少」）");

		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 94, 200, spawn.getZ() + 94);
		// 每个用例一个**自己的**置物台：夹具之间不互相清容器（清容器要真抽，抽的路径本身也是被测对象）
		BlockPos takeAtMostSrc = armPos.offset(2, 0, 0);
		BlockPos takeExactSrc = armPos.offset(0, 0, 2);
		BlockPos takeShortSrc = armPos.offset(0, 0, -2);
		BlockPos depAtMostSink = armPos.offset(-2, 0, 0);
		BlockPos depExactSink = armPos.offset(2, 0, -2);
		BlockPos depShortSink = armPos.offset(-2, 0, 2);
		BlockPos[] targets = { takeAtMostSrc, takeExactSrc, takeShortSrc, depAtMostSink, depExactSink, depShortSink };
		BlockState oldArm = level.getBlockState(armPos);
		BlockState[] oldTargets = new BlockState[targets.length];
		for (int i = 0; i < targets.length; i++)
			oldTargets[i] = level.getBlockState(targets[i]);

		boolean takeAtMost = false;
		boolean takeExactEnough = false;
		boolean takeExactShort = false;
		boolean depositAtMost = false;
		boolean depositExactEnough = false;
		boolean depositExactShort = false;
		boolean fed = false;
		String detail;
		try {
			// 先把区块加载出来：启动期新加的方块实体会被**挂起**到区块真正加载为止（其它夹具同一条纪律）
			level.getChunkAt(armPos);
			for (BlockPos target : targets)
				level.getChunkAt(target);
			BlockState depot = stateOf("create:depot");
			if (depot == null) {
				LOGGER.error("[selftest] 个数模式自检**没跑起来**：找不到 create:depot");
				return;
			}
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			for (BlockPos target : targets)
				level.setBlockAndUpdate(target, depot);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 个数模式自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过（与原版一致）
			// 前提：物品真的放进了载具（否则下面的断言全是空转）。
			// ⚠️ 用 `&` 而不是 `&&`：三个载具都要真的喂上，短路会让"哪一个没喂上"看不出来。
			fed = putOneInto(level, takeAtMostSrc, new ItemStack(Items.IRON_INGOT, 1))
				& putOneInto(level, takeExactSrc, new ItemStack(Items.IRON_INGOT, 5))
				& putOneInto(level, takeShortSrc, new ItemStack(Items.IRON_INGOT, 1));

			// ——— ① 取出 · 小于等于：容器 1 个、count=3 ⇒ 有多少拿多少（1 个），**不算失败** ———
			// 反证：让 take() 在 ATMOST 下也要求够数 ⇒ 源还剩 1、臂里 0。
			clearArm(arm);
			int beforeAtMost = countIn(level, takeAtMostSrc);
			runCountEntry(arm, takeAtMostSrc, OperationType.TAKE, 3, ArmProgramEntry.CountMode.ATMOST,
				() -> countIn(level, takeAtMostSrc) != beforeAtMost, 80);
			takeAtMost = countIn(level, takeAtMostSrc) == 0 && armItems(arm) == 1;

			// ——— ② 取出 · 等于 · 够数：容器 5 个、count=3 ⇒ **恰好**拿走 3 个（容器剩 2）———
			// 反证：把 take() 的 EXACT 分支改回 ATMOST 那一支（跳过 exactTakeSlot）⇒ 臂里会多出来（3 没变但
			// 若把 count 当上限用的旧写法会把 5 拿光）⇒ 容器剩 0、臂里 5。
			clearArm(arm);
			int beforeExact = countIn(level, takeExactSrc);
			runCountEntry(arm, takeExactSrc, OperationType.TAKE, 3, ArmProgramEntry.CountMode.EXACT,
				() -> countIn(level, takeExactSrc) != beforeExact, 80);
			takeExactEnough = countIn(level, takeExactSrc) == 2 && armItems(arm) == 3;

			// ——— ③ 取出 · 等于 · 不够：容器 1 个、count=3 ⇒ **不成功**（走 handleFailure、什么都不动）———
			// 反证：把 EXACT 的"凑不出就失败"删掉（退化成有多少拿多少）⇒ 容器剩 0、臂里 1。
			clearArm(arm);
			runCountEntry(arm, takeShortSrc, OperationType.TAKE, 3, ArmProgramEntry.CountMode.EXACT, () -> false, 80);
			String takeShortFailure = arm.runtime.getLastFailure();
			takeExactShort = countIn(level, takeShortSrc) == 1 && armItems(arm) == 0
				&& takeShortFailure != null && takeShortFailure.contains("数量不足");

			// ——— ④ 放入 · 小于等于：手上 5 个、count=3 ⇒ 目标 +3、臂里剩 2 ———
			// 反证：把 deposit() 的 ATMOST 分支改回"整摞都塞"（`held` 而不是 `copyWithCount(amount)`）⇒ 目标 5、臂里 0；
			// 把回收那句漏掉"没参与的那一截"（`back = remainder`）⇒ 臂里 0（物品凭空少了 2 个）。
			arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 5));
			runCountEntry(arm, depAtMostSink, OperationType.DEPOSIT, 3, ArmProgramEntry.CountMode.ATMOST,
				() -> arm.getHeldItem()
					.getCount() != 5, 80);
			depositAtMost = countIn(level, depAtMostSink) == 3 && arm.getHeldItem()
				.getCount() == 2;

			// ——— ⑤ 放入 · 等于 · 够数：手上 5 个、count=3 ⇒ 目标 +3、臂里剩 2 ———
			// 反证：EXACT 分支忘了先判"手上够不够"、直接按 min 塞 ⇒ ③④ 里那种"塞一半"就会在 ⑥ 被抓住。
			arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 5));
			runCountEntry(arm, depExactSink, OperationType.DEPOSIT, 3, ArmProgramEntry.CountMode.EXACT,
				() -> arm.getHeldItem()
					.getCount() != 5, 80);
			depositExactEnough = countIn(level, depExactSink) == 3 && arm.getHeldItem()
				.getCount() == 2;

			// ——— ⑥ 放入 · 等于 · 不够：手上 2 个、count=3 ⇒ 目标不变、臂里仍 2 ———
			// 反证：删掉 EXACT 的"手上不足 N 个"那条判定 ⇒ 会先真塞 2 个（目标 +2），物品被拆散了。
			arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 2));
			runCountEntry(arm, depShortSink, OperationType.DEPOSIT, 3, ArmProgramEntry.CountMode.EXACT, () -> false, 80);
			String depShortFailure = arm.runtime.getLastFailure();
			depositExactShort = countIn(level, depShortSink) == 0 && arm.getHeldItem()
				.getCount() == 2 && depShortFailure != null && depShortFailure.contains("手上不足");

			detail = "源1余=" + countIn(level, takeAtMostSrc) + " 源2余=" + countIn(level, takeExactSrc) + " 源3余="
				+ countIn(level, takeShortSrc) + " 目标1=" + countIn(level, depAtMostSink) + " 目标2="
				+ countIn(level, depExactSink) + " 目标3=" + countIn(level, depShortSink) + " 臂内=" + armItems(arm)
				+ " 失败原因=" + text(takeShortFailure) + " / " + text(depShortFailure);
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			for (int i = 0; i < targets.length; i++)
				level.setBlockAndUpdate(targets[i], oldTargets[i]);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 个数模式: 取出·小于等于={} 取出·等于·够数={} 取出·等于·不够={} 放入·小于等于={} "
			+ "放入·等于·够数={} 放入·等于·不够={}（前提·物品已放入载具={} 实测：{}）", takeAtMost, takeExactEnough,
			takeExactShort, depositAtMost, depositExactEnough, depositExactShort, fed, detail);
		if (!fed)
			LOGGER.error("[selftest] 个数模式自检**没跑起来**：物品没能放进载具 —— 六条断言等于没测");
		if (!takeAtMost)
			LOGGER.error("[selftest] 「取出 · 小于等于」不再「有多少拿多少」（容器 1 个、个数 3）：这等于改掉了"
				+ "取出改造前的默认行为");
		if (!takeExactEnough)
			LOGGER.error("[selftest] 「取出 · 等于」没有恰好拿 3 个（容器 5 个时应当剩 2、臂里 3）");
		if (!takeExactShort)
			LOGGER.error("[selftest] 「取出 · 等于」在凑不出 3 个时仍然动了东西（容器 1 个时应当原样不动、"
				+ "臂里不多个数，并走「失败后」重试）");
		if (!depositAtMost)
			LOGGER.error("[selftest] 「放入 · 小于等于」没有按上限搬（手上 5 个、个数 3 ⇒ 目标 3、臂里 2）");
		if (!depositExactEnough)
			LOGGER.error("[selftest] 「放入 · 等于」没有恰好搬 3 个（手上 5 个 ⇒ 目标 3、臂里 2）");
		if (!depositExactShort)
			LOGGER.error("[selftest] 「放入 · 等于」在手上不足 3 个时仍然塞了东西（手上 2 个时应当一个都不动）");
	}

	/**
	 * 把一条「取 / 放」条目跑到**第一次有结果**为止（自检不跑运动学：每拍把
	 * {@code chasedPointProgress} 当作"已到位"，手法与 {@link #checkRetractBeforeNextMove} 相同）。
	 *
	 * <p>{@code stop} 是"这一条已经有结果"的<b>可观测量</b>（源 / 目标 / 臂里某个数量变了），
	 * 不是运行时内部游标 —— 主任务恒循环，成功后这一条会立刻从头再来（第二次当然会失败），
	 * 不停在第一次结果处，断言看到的就永远是第二轮。
	 */
	private static void runCountEntry(SequencedArmBlockEntity arm, BlockPos target, OperationType op, int count,
		ArmProgramEntry.CountMode mode, BooleanSupplier stop, int maxTicks) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检个数";
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = op;
		entry.offset = target.subtract(arm.getBlockPos());
		entry.count = count;
		entry.countMode = mode;
		task.entries.add(entry);
		program.tasks.add(task);
		arm.setProgram(program); // 换程序会 resetToStart（游标归零、错误清空、收回）
		for (int i = 0; i < maxTicks && !stop.getAsBoolean(); i++) {
			arm.chasedPointProgress = 1f;
			arm.runtime.tick(arm);
		}
	}

	/** 那个方块里现在一共有几个物品（所有槽合计；夹具里只有一种物品，数得清就够用）。 */
	private static int countIn(ServerLevel level, BlockPos pos) {
		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
		if (handler == null)
			return -1;
		int total = 0;
		for (int slot = 0; slot < handler.getSlots(); slot++)
			total += handler.getStackInSlot(slot)
				.getCount();
		return total;
	}

	/** 臂里现在一共有几个物品（手持 + 溢出）—— 判"臂里多了几个"用它，比只看手持稳。 */
	private static int armItems(SequencedArmBlockEntity arm) {
		int total = arm.getHeldItem()
			.getCount();
		for (ItemStack stack : arm.getOverflow())
			total += stack.getCount();
		return total;
	}

	/** 把臂清空（手持 + 溢出）：每个用例都从"空手"开始，断言才数得清。 */
	private static void clearArm(SequencedArmBlockEntity arm) {
		arm.setHeldItem(ItemStack.EMPTY);
		arm.getOverflow()
			.clear();
	}

	/**
	 * 一次移动的**姿态时间线**（纯函数，不需要世界）—— 用户第二十七轮续三十九实测发现的回归。
	 *
	 * <p><b>用户实测</b>：<i>"动画的旋转缺失了一部分……原本的旋转，前面一半没有了，然后突然瞬移到后面一半，
	 * 这一半旋转是正常的，旋转+伸出。"</i> 根因是我把旋转那行挪到了"`target` 被覆盖成静止位"**之后**
	 * ⇒ 前半段里旋转的两个端点都成了 `previousBaseAngle` ⇒ 冻结，到 `progress = .5` 才一步跳到一半。
	 * 上游的顺序是**先算旋转、再覆盖 target**（`ArmBlockEntity:205-215`）。
	 *
	 * <p>这条断言直接把时间线上四个点钉住（起点静止、前半段在转、.5 处转了一半且臂未伸、终点两者同时到位），
	 * 另外钉住"没有目标（收回/等待）时旋转必须冻结"。把顺序改回去 ⇒ 第二条立刻变红。
	 */
	private static void checkMovePoseTimeline() {
		float tol = 1e-3f;
		SequencedArmAngleTarget aim = SequencedArmAngleTarget.of(90f, 100f, 20f, 30f);
		SequencedArmAngleTarget rest = SequencedArmAngleTarget.RESTING;
		SequencedArmAngleTarget p0 = SequencedArmBlockEntity.poseAt(0f, rest, aim, 0f);
		SequencedArmAngleTarget p25 = SequencedArmBlockEntity.poseAt(.25f, rest, aim, 0f);
		SequencedArmAngleTarget p50 = SequencedArmBlockEntity.poseAt(.5f, rest, aim, 0f);
		SequencedArmAngleTarget p75 = SequencedArmBlockEntity.poseAt(.75f, rest, aim, 0f);
		SequencedArmAngleTarget p100 = SequencedArmBlockEntity.poseAt(1f, rest, aim, 0f);
		SequencedArmAngleTarget frozen = SequencedArmBlockEntity.poseAt(.25f, rest, null, 0f);

		boolean startsAtOldDirection = Math.abs(p0.baseAngle) < tol;
		// ★ 反证点：前半段必须**已经在转**。90° 的一趟转身里，.25 处应当是 25% = 22.5°
		//   （我第一版把期望写成 45° ⇒ 变成一条"永远红"的断言 —— 决策 88：错的期望值和错的实现一样有害，
		//    所以这里连"非零且单调"一起断言，而不是只钉一个数）。
		boolean rotatesDuringFirstHalf = Math.abs(p25.baseAngle - 22.5f) < tol && p25.baseAngle > 0f
			&& p25.baseAngle < p50.baseAngle;
		boolean armStaysPutWhileTurning = Math.abs(p25.lowerArmAngle - rest.lowerArmAngle) < tol
			&& Math.abs(p25.upperArmAngle - rest.upperArmAngle) < tol;
		boolean halfTurnWhenExtensionStarts = Math.abs(p50.baseAngle - 45f) < tol
			&& Math.abs(p50.lowerArmAngle - rest.lowerArmAngle) < tol;
		boolean rotateAndExtendTogether = Math.abs(p75.baseAngle - 67.5f) < tol
			&& Math.abs(p75.lowerArmAngle - (rest.lowerArmAngle + aim.lowerArmAngle) / 2f) < tol;
		boolean bothFinishTogether = Math.abs(p100.baseAngle - aim.baseAngle) < tol
			&& Math.abs(p100.lowerArmAngle - aim.lowerArmAngle) < tol
			&& Math.abs(p100.upperArmAngle - aim.upperArmAngle) < tol;
		boolean frozenWithoutTarget = Math.abs(frozen.baseAngle) < tol
			&& Math.abs(frozen.lowerArmAngle - rest.lowerArmAngle) < tol;

		LOGGER.info("[selftest] 移动姿态时间线: 起点朝旧方向={} 前半段就在转={}（.25 时={}°） "
			+ "转身时臂不动={} .5 处转了一半={}（={}°） 后半段旋转+伸出同时={} 终点同时到位={} "
			+ "无目标时旋转冻结={}",
			startsAtOldDirection, rotatesDuringFirstHalf, p25.baseAngle, armStaysPutWhileTurning,
			halfTurnWhenExtensionStarts, p50.baseAngle, rotateAndExtendTogether, bothFinishTogether,
			frozenWithoutTarget);

		if (!startsAtOldDirection)
			LOGGER.error("[selftest] 移动起点不是「朝旧方向」（baseAngle={}）—— 应当从手臂当前朝向开始转",
				p0.baseAngle);
		if (!rotatesDuringFirstHalf)
			LOGGER.error("[selftest] **前半段旋转被冻结或不对**（.25 时 baseAngle={}°，应当是 22.5°："
				+ "90° 的一趟在四分之一处转了 25%）—— 冻结的表现就是用户实测的"
				+ "「旋转的前一半没有了、突然瞬移到后一半」；"
				+ "根因是旋转那行被排在「`target` 被覆盖成静止位」之后（上游是 `ArmBlockEntity:205-215`）",
				p25.baseAngle);
		if (!armStaysPutWhileTurning)
			LOGGER.error("[selftest] 前半段手臂**伸了**（lower={} upper={}）—— 前半段应当只转身、臂不动",
				p25.lowerArmAngle, p25.upperArmAngle);
		if (!halfTurnWhenExtensionStarts)
			LOGGER.error("[selftest] 伸出开始时旋转**不是一半**（baseAngle={}°）—— 原版是「转到一半才开始伸出」",
				p50.baseAngle);
		if (!rotateAndExtendTogether)
			LOGGER.error("[selftest] 后半段旋转与伸出**没有同步**（baseAngle={}° lower={}）",
				p75.baseAngle, p75.lowerArmAngle);
		if (!bothFinishTogether)
			LOGGER.error("[selftest] 终点姿态不对（base={} lower={} upper={}）", p100.baseAngle,
				p100.lowerArmAngle, p100.upperArmAngle);
		if (!frozenWithoutTarget)
			LOGGER.error("[selftest] 没有目标时旋转**没有冻结**（baseAngle={}°）—— 收回/等待期间不许转向",
				frozen.baseAngle);
	}

	/**
	 * 认领物品的**位置条件**：取物一进范围就按住（原地不动）；组装等它走到中间再按住。
	 * （用户第二十七轮续四十实测：<i>"它会捕获并定住物品，但是物品会立即瞬移到中间而不是留在原本的位置"</i>。）
	 *
	 * <p>⚠️ **这条断言只能钉住"规则"，钉不住"世界里的位置"** —— 自检没有传送带夹具（见 §7.23 末的说明），
	 * 所以"物品真的停在原地"必须由游戏内实测确认。这里能钉的是：
	 * ① 取物：容差 = 整格（±.51，与带子自己的加工范围一致）；② 组装：容差 ≥ 一个 tick 的带速
	 * （否则高速带上永远追不上中心）；③ 组装慢带子下的容差 = 1/32 格（半个像素，肉眼就是"中间"）。
	 */
	private static void checkClaimPositionPolicy() {
		float tol = 1e-4f;
		float slowStep = 1 / 64f; // 16rpm：一格 64 tick
		float fastStep = .25f; // 256rpm：一格 4 tick
		float takeRange = SequencedArmBlockEntity.claimTolerance(false, slowStep);
		float deploySlow = SequencedArmBlockEntity.claimTolerance(true, slowStep);
		float deployFast = SequencedArmBlockEntity.claimTolerance(true, fastStep);

		boolean takeTakesWholeSegment = Math.abs(takeRange - .51f) < tol;
		boolean deployWaitsForCentre = deploySlow < takeRange && Math.abs(deploySlow - 1 / 32f) < tol;
		boolean deployKeepsUpOnFastBelts = Math.abs(deployFast - fastStep) < tol;

		// ★ 「作业位」判据（用户第二十七轮续四十一实测：组装**必须等物品走到中间**才动手）：
		//   本体只在物品跨过格子中心那一 tick 才询问机器（`BeltInventory:268-297`），并在那一刻把它**居中**
		//   （`:292`：`segment + .5f ± 1/512f`）。所以离中心 0.1 格**不算**到位 ——
		//   这正是旧写法（认整格 ±.51）出错的地方：手臂在半路就动手 ⇒ 产物提前半格出生 ⇒ 挡住后面那件。
		float slowTol = SequencedArmBlockEntity.claimTolerance(true, slowStep);
		boolean oneTenthOffIsNotAtWorkPosition = !SequencedArmBlockEntity.atWorkPosition(.5f + .1f, .5f, slowTol);
		boolean centreIsAtWorkPosition = SequencedArmBlockEntity.atWorkPosition(.5f, .5f, slowTol);
		boolean oldWholeSegmentRuleWouldHaveAccepted = SequencedArmBlockEntity.atWorkPosition(.5f + .1f, .5f, .51f);
		boolean snapIsVanillaLine = Math.abs(SequencedArmBlockEntity.centreSnapPosition(3, true) - (3.5f + 1 / 512f)) < tol
			&& Math.abs(SequencedArmBlockEntity.centreSnapPosition(3, false) - (3.5f - 1 / 512f)) < tol;

		LOGGER.info("[selftest] 认领位置条件: 取物容差={}（整格） 组装容差·慢带={}（16rpm，应当 1/32） "
			+ "组装容差·快带={}（应当 ≥ 一个 tick 的带速 {}） ｜ 作业位: 离中心 .1 格不算到位={} 正中算={} "
			+ "（旧的整格判据会误判为到位={}） 居中坐标照抄本体={}",
			takeRange, deploySlow, deployFast, fastStep, oneTenthOffIsNotAtWorkPosition, centreIsAtWorkPosition,
			oldWholeSegmentRuleWouldHaveAccepted, snapIsVanillaLine);

		if (!takeTakesWholeSegment)
			LOGGER.error("[selftest] 「取物」的认领范围不是整格（{}，应当 .51 = 带子自己的加工范围）—— "
				+ "取物应当是「一进入判定范围就按住」", takeRange);
		if (!deployWaitsForCentre)
			LOGGER.error("[selftest] 「组装」没有「等物品走到中间」（慢带容差={}）—— 一进范围就按住会把物品"
				+ "从格子边缘摁住（用户实测的「瞬移到中间」就是这么来的）", deploySlow);
		if (!deployKeepsUpOnFastBelts)
			LOGGER.error("[selftest] 快带子上「组装」的容差**没有跟着带速走**（{}，应当等于一个 tick 的位移 {}）"
				+ "—— 比它小则每 tick 直接跨过窗口、永远认领不到；比它大则等于「一进范围就按住」", deployFast,
				fastStep);
		if (!oneTenthOffIsNotAtWorkPosition || !centreIsAtWorkPosition)
			LOGGER.error("[selftest] 「作业位」判据不对（离中心 .1 格算到位={}，正中算到位={}）—— "
				+ "组装必须只在物品**走到格子中心**后才动手（本体 `BeltInventory:268-297`）；"
				+ "否则手臂在半路加工、产物提前半格出生挡住后面那件（用户第二十七轮续四十一实测）",
				oneTenthOffIsNotAtWorkPosition, centreIsAtWorkPosition);
		if (!snapIsVanillaLine)
			LOGGER.error("[selftest] 「居中」坐标与本体的 HOLD 不一致（{}，应当 index + .5 ± 1/512）—— "
				+ "本体在 HOLD 那一刻就是这么居中的（`BeltInventory:292`）",
				SequencedArmBlockEntity.centreSnapPosition(3, true));
	}

	/** 走目标方块**自己的物品能力**放一个铁锭（不碰 BE 内部字段，与"世界里真的放了一个"一致）。 */
	private static boolean putOneInto(ServerLevel level, BlockPos pos) {
		return putOneInto(level, pos, new ItemStack(Items.IRON_INGOT));
	}

	/**
	 * 同上，但放进**指定的物品**（「过滤」的自检要摆"第一格是金锭、第二格是铁锭"这类组合）。
	 * 同样只走方块自己的能力，不碰 BE 内部字段。
	 */
	private static boolean putOneInto(ServerLevel level, BlockPos pos, ItemStack stack) {
		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
		if (handler == null)
			return false;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			if (handler.insertItem(slot, stack.copy(), false)
				.isEmpty())
				return true;
		}
		return false;
	}

	/** 那个方块现在是不是空的（同样走方块自己的能力，不看内部字段）。 */
	private static boolean isEmpty(ServerLevel level, BlockPos pos) {
		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
		if (handler == null)
			return false;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			if (!handler.getStackInSlot(slot)
				.isEmpty())
				return false;
		}
		return true;
	}

	/**
	 * 「认领窗口跟**程序意图**走，不跟手臂状态走」（用户第二十七轮续三十五实测）。
	 *
	 * <p><b>为什么要有这条断言</b>：§7.22 把每条条目改成"伸 → 加工 → 收"之后，认领窗口原来写成
	 * {@code state ∈ {MOVING, EXECUTING}}，于是**收回 + 待机那一整段没有任何人认领那一格** ——
	 * 传送带上的物品直接从臂下流过去，用户看到的就是"又变成无法定住被加工的物品了"。
	 * 正确的分法是：**动作归动作（相位/动画），认领归认领（程序意图）**。
	 *
	 * <p><b>判据为什么能分辨</b>：这里真跑运行时——
	 * ① 还没动身（前置检查没过、状态是 RETRY）时就必须已经在认领窗口里；
	 * ② 条目成功后（手臂正在收回 + 待机，状态 IDLE/RETRY）**仍然**在认领窗口里；
	 * ③ 换到另一条条目时，认领的**目标跟着换**（不能还按着旧目标那一格）；
	 * ④ 反例：反例条目（挖掘）不认领。
	 * 把判据改回"看状态"，②会立刻变红（成功后的下一个 tick 状态已经不是 MOVING/EXECUTING）。
	 */
	private static void checkClaimFollowsIntent(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 70, 200, spawn.getZ() + 70);
		BlockPos depotA = armPos.offset(2, 0, 0);
		BlockPos depotB = armPos.offset(-2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldA = level.getBlockState(depotA);
		BlockState oldB = level.getBlockState(depotB);
		try {
			BlockState depotState = stateOf("create:depot");
			if (depotState == null) {
				LOGGER.error("[selftest] 认领窗口自检**没跑起来**：找不到 create:depot");
				return;
			}
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotA, depotState);
			level.setBlockAndUpdate(depotB, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 认领窗口自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32);

			BlockPos offsetA = depotA.subtract(armPos);
			BlockPos offsetB = depotB.subtract(armPos);
			ArmProgram program = new ArmProgram();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "自检认领";
			ArmProgramEntry first = new ArmProgramEntry();
			first.op = OperationType.TAKE;
			first.offset = offsetA;
			task.entries.add(first);
			ArmProgramEntry second = new ArmProgramEntry();
			second.op = OperationType.TAKE;
			second.offset = offsetB;
			task.entries.add(second);
			program.tasks.add(task);
			arm.setProgram(program);

			// ① 一个 tick 都没伸过去：空载具 ⇒ 前置检查不过、状态进 RETRY —— 仍然必须认领
			arm.runtime.tick(arm);
			boolean claimsBeforeMoving = arm.canPinTarget();
			boolean offsetFollowsFirst = offsetA.equals(arm.runtime.pinOffset(arm));
			String precheckReason = arm.runtime.getLastFailure();
			boolean ran = precheckReason != null; // 前提：这一 tick 真的跑了前置检查（否则等于没测）

			// ② 让第一条**真的成功**（放到 A 里的物品被取走），然后在"收回 + 待机"期间看认领。
			//    自检不跑运动学（`arm.tick()` 需要真实动力网络，见"出库顺序"那条的同样处理）：
			//    每轮把 `chasedPointProgress` 置 1 当作"已到位"，其余全走**真实入口** `runtime.tick()`。
			//    ⚠️ "成功了没有"必须用**与待测判据无关**的事实来判（A 里的物品没了），
			//    不能用 `pinOffset` 自己 —— 否则判据一坏，这里就变成"没跑起来"，红得不明不白。
			boolean fed = putOneInto(level, depotA);
			boolean succeeded = false;
			for (int i = 0; i < 200; i++) {
				arm.chasedPointProgress = 1f;
				arm.runtime.tick(arm);
				if (isEmpty(level, depotA)) {
					succeeded = true; // 物品真的被取走了 ⇒ 第一条成功、已经轮到第二条
					break;
				}
			}
			boolean claimsWhileRetracting = arm.canPinTarget();
			// ②b **不许有"聋窗"**：成功之后的**下一个** tick 就必须在跑前置检查了。
			//     判据：第二条的目标（B）是空的 ⇒ 只要前置检查真的跑了，就会报出「目标为空」。
			//     曾经在成功后会 `setIdleCooldown(swingTicks())`（16rpm = 64 tick 完全不 tick），
			//     而 16rpm 下 64 tick 恰好是一件物品走完一整格的时间 ⇒ 用户实测"组装的时候臂会空一轮"。
			//     反证：把那次待机加回去 ⇒ 这里立刻变红（`lastFailure` 还是 null）。
			boolean noDeafWindow = false;

			// ③ 接下来几 tick（收回 + 待机整段）都要继续认领，且认领的是**新**目标
			boolean claimsThroughout = true;
			boolean offsetFollowsSecond = true;
			arm.runtime.tick(arm);
			noDeafWindow = arm.runtime.getLastFailure() != null;
			for (int i = 0; i < 40; i++) {
				arm.runtime.tick(arm);
				claimsThroughout &= arm.canPinTarget();
				offsetFollowsSecond &= offsetB.equals(arm.runtime.pinOffset(arm));
			}

			// ④ 反例：停机（无应力）时也仍然认领（原版语义，用户确认过）
			arm.setSpeed(0);
			boolean claimsWhileStopped = arm.canPinTarget();
			arm.setSpeed(32);

			LOGGER.info("[selftest] 认领窗口跟程序意图: 未动身就认领={}（前置检查跑了={}） 目标跟着第1条={} "
				+ "第1条成功后仍在认领={}（真的成功了={}） 收回+待机整段都认领={} 目标跟着第2条={} 停机仍认领={} "
				+ "成功后下一 tick 就接着查={}",
				claimsBeforeMoving, ran, offsetFollowsFirst, claimsWhileRetracting, succeeded, claimsThroughout,
				offsetFollowsSecond, claimsWhileStopped, noDeafWindow);

			if (!ran)
				LOGGER.error("[selftest] 认领窗口自检**没跑起来**：空载具时前置检查没报出原因 —— 等于没测");
			else if (!fed)
				LOGGER.error("[selftest] 认领窗口自检**没跑起来**：物品没能放进载具 A —— 后面那些断言等于没测");
			else if (!succeeded)
				LOGGER.error("[selftest] 认领窗口自检**没跑起来**：第一条条目一直没成功（最后一句原因「{}」）"
					+ "—— 等于没测", text(arm.runtime.getLastFailure()));
			if (!claimsBeforeMoving)
				LOGGER.error("[selftest] 还没动身时**不在认领窗口里** —— 认领必须由程序意图决定"
					+ "（当前条目是取物/组装就该认领），不能等手臂开始移动才认领："
					+ "否则'空载具等物品'期间进来的物品谁都不按住，传送带直接把它带走");
			if (!offsetFollowsFirst || !offsetFollowsSecond)
				LOGGER.error("[selftest] 认领的目标**没有跟着当前条目换**（第1条={} 第2条一致={}）—— "
					+ "会让手臂按着旧目标那一格上的物品，新目标那格却没人管", offsetFollowsFirst, offsetFollowsSecond);
			if (!claimsWhileRetracting || !claimsThroughout)
				LOGGER.error("[selftest] 条目成功后（手臂收回 + 待机期间）**松手了**（成功后={} 整段={}）—— "
					+ "这正是 §7.22 引入的回归：那一整段没人认领，物品就从臂下流走", claimsWhileRetracting,
					claimsThroughout);
			if (!claimsWhileStopped)
				LOGGER.error("[selftest] 停机（无应力）后不再认领 —— 与原版不一致（`ArmBlockEntity:133-140`）");
			if (!noDeafWindow)
				LOGGER.error("[selftest] 条目成功之后**有一段时间不跑前置检查**（" + "下一个 tick 还没报出第二条的失败原因）"
					+ " —— 那段'聋窗'会让传送带上的一件物品白白走过：16rpm 下 64 tick 恰好等于走完一整格，"
					+ "用户实测就是'组装的时候臂会空一轮'");
		} catch (Throwable t) {
			LOGGER.error("[selftest] 认领窗口自检抛异常：{}", t.toString());
		} finally {
			level.setBlockAndUpdate(depotA, oldA);
			level.setBlockAndUpdate(depotB, oldB);
			level.setBlockAndUpdate(armPos, oldArm);
		}
	}

	/** 一次探测：摆上方块 → 问三件事（只进不出 / 是交互点 / 有通用物品能力）→ 还原方块。 */
	private record Probe(boolean depositOnly, boolean interactable, boolean hasCapability, String kind) {
	}

	private static Probe probe(ServerLevel level, BlockPos pos, BlockState state) {
		BlockState previous = level.getBlockState(pos);
		try {
			level.setBlockAndUpdate(pos, state);
			// ★ 一定用**世界里实际的状态**问，而不是我传进来的意图状态：有些方块（如粉碎轮控制器）
			// 摆下去就自己没了，拿意图状态去问会得到"它是交互点、但世界那里其实是空气"的假象。
			BlockState actual = level.getBlockState(pos);
			ArmInteractionPoint point = ArmInteractionPoint.create(level, pos, actual);
			return new Probe(TargetInteraction.isDepositOnly(level, pos), ArmInteractionPoint.isInteractable(level, pos, actual),
				TargetInteraction.handler(level, pos) != null, point == null ? "无取物点" : point.getClass()
					.getSimpleName());
		} finally {
			level.setBlockAndUpdate(pos, previous);
		}
	}

	/** 摆一个方块状态，把探测结果写进日志串，并与期望值比对。 */
	private static boolean expectPoint(StringBuilder table, ServerLevel level, BlockPos pos, String label,
		@Nullable BlockState state, boolean depositOnly, boolean interactable) {
		if (state == null) {
			// ⚠️ 以前这里是 `return true` —— 于是上游一改名（例如 Create 换了方块 id），整张分类表照样"全绿"，
			// 而实际上那一条根本没测到。这些方块都是功能必需的交互目标，缺一个就是功能真的坏了，必须报出来。
			table.append(label)
				.append("=方块缺失(上游改了 id？) ");
			LOGGER.warn("[selftest] 取物点分类缺少方块：{} —— 这一条没测到，且相关功能会失效", label);
			return false;
		}
		Probe probe = probe(level, pos, state);
		boolean ok = probe.depositOnly() == depositOnly && probe.interactable() == interactable;
		table.append(label)
			.append('=')
			.append(probe.kind())
			.append('/')
			.append(probe.depositOnly() ? "只进不出" : "可取");
		if (!probe.interactable())
			table.append("+非交互点");
		if (!ok)
			table.append("(期望 ")
				.append(depositOnly ? "只进不出" : "可取")
				.append(interactable ? "" : "+非交互点")
				.append(')');
		table.append(' ');
		return ok;
	}

	/** 智能侦测器的脉冲读数（{@code onTransfer} 的唯一服务端痕迹）；不是侦测器返回 -1。 */
	private static int observerTicks(ServerLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof SmartObserverBlockEntity observer ? observer.turnOffTicks : -1;
	}

	/** 按 id 取一个方块的默认状态；取不到（含被解析成空气）返回 {@code null}。 */
	@Nullable
	private static BlockState stateOf(String id) {
		ResourceLocation key = ResourceLocation.tryParse(id);
		if (key == null)
			return null;
		Block block = BuiltInRegistries.BLOCK.get(key);
		if (block == null || block == Blocks.AIR)
			return null;
		return block.defaultBlockState();
	}

	/**
	 * 反馈层（{@code ArmEffects}）的启动期自检。
	 *
	 * <p>为什么值得单独测：这个类只在 DEPLOY 真的执行时才被加载，而它引用的两样东西
	 * ——Create 的音效表（{@code AllSoundEvents.SANDING_SHORT}）与物品粒子
	 * （{@code ItemParticleOption} + {@code ParticleTypes.ITEM}）——只要有一个在专用服务器上
	 * 不可用（音效事件未绑定、dist 不匹配），就<b>只有抬手 deploy 那一刻才会炸</b>：
	 * 编译通过、别处的运行路径也全都发现不了。这里把 {@code deploy()} 的两条分支都走一遍
	 * （物品拾取声 + 砂纸音效 + 物品粒子），顺带验证 TAKE 的取物声。
	 *
	 * <p>副作用安全：位置取在出生点上方 200 格，启动期没有玩家在线，
	 * 服务端的 {@code playSound}/{@code sendParticles} 在没有接收者时是空操作。
	 */
	private static void checkEffects(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos at = new BlockPos(level.getSharedSpawnPos()
			.getX() + 8, 200, level.getSharedSpawnPos()
				.getZ() + 8);
		try {
			ArmEffects.deploy(level, at, Vec3.atCenterOf(at), new ItemStack(net.minecraft.world.item.Items.IRON_INGOT), true);
			ArmEffects.pickup(level, at, ItemStack.EMPTY, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
			LOGGER.info("[selftest] 反馈层: 音效+粒子路径可用=true（拾取声 + 砂纸条分支 + 物品粒子）");
		} catch (Throwable t) {
			LOGGER.error("[selftest] 反馈层执行抛异常 —— 游戏里 DEPLOY 会跟着炸！", t);
		}
	}

	private static void checkDeployRecipes(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Item processedItem = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("exposed_chiseled_copper"));
		Item applicatorItem = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("iron_axe"));
		if (processedItem == null || applicatorItem == null) {
			LOGGER.warn("[selftest] deploy 配方验证跳过：原版物品缺失（exposed_chiseled_copper / iron_axe）");
			return;
		}
		ItemStack processed = new ItemStack(processedItem);
		ItemStack applicator = new ItemStack(applicatorItem);
		RecipeHolder<? extends Recipe<?>> recipe = DeployRecipes.find(level, processed, applicator);
		if (recipe == null) {
			LOGGER.warn("[selftest] deploy 配方解析未命中（上游可能改了这条配方）");
			return;
		}
		LOGGER.info("[selftest] deploy 配方解析命中: id={} keepHeldItem={}", recipe.id(),
			DeployRecipes.keepsHeldItem(recipe.value()));
	}

	/**
	 * 录入会话状态的自检（§5.1 v5）。用户实测："切换物品之后就回到录入模式" —— 修法是把"模式 + 正在编辑
	 * 的任务"写进<b>卡自己</b>的 NBT（{@code emode}/{@code etask}），客户端换手持槽、退出重进都从卡上读。
	 *
	 * <p>这里守两件事：① 两个字段写进物品再读回来无损（键名写错就会静默退回默认值，表现为"模式又没了"）；
	 * ② 越界/负数的任务下标读回来立刻被夹进合法范围 —— 界面拿到越界下标就是索引越界崩溃。
	 */
	private static void checkEditorSession(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();
		ArmProgram session = new ArmProgram();
		for (int i = 0; i < 3; i++) {
			ArmProgramTask task = new ArmProgramTask();
			task.name = "任务" + (i + 1);
			session.tasks.add(task);
		}
		session.editorMode = "EDIT";
		session.editorTask = 2;

		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.write(card, session, registries);
		ArmProgram back = CardProgramStorage.read(card, registries);
		boolean modeKept = "EDIT".equals(back.editorMode);
		boolean taskKept = back.editorTask == 2;

		// 改坏的下标（负数 / 越界）必须在读取时就夹回合法范围
		session.editorTask = -5;
		CardProgramStorage.write(card, session, registries);
		int clampedLow = CardProgramStorage.read(card, registries).editorTask;
		session.editorTask = 99;
		CardProgramStorage.write(card, session, registries);
		int clampedHigh = CardProgramStorage.read(card, registries).editorTask;

		// ③ 导入脚本这条路（`writeText`）**不碰**模式：导入一份别人的脚本不该把玩家的模式打回录入；
		//    但"正在编辑的任务"要按**新程序**的任务数夹住（新脚本可能只有一个任务）。
		//    §17.9 的回归就是死在这里没被测到 —— 这条断言是补上的那一半。
		session.editorTask = 2;
		ItemStack imported = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.write(imported, session, registries);
		ArmProgram shorter = new ArmProgram();
		ArmProgramTask onlyTask = new ArmProgramTask();
		onlyTask.name = "只有一个";
		shorter.tasks.add(onlyTask);
		CardProgramStorage.writeText(imported, ScriptCompiler.decompile(shorter, registries));
		ArmProgram afterImport = CardProgramStorage.read(imported, registries);
		boolean modeSurvivesImport = "EDIT".equals(afterImport.editorMode);
		boolean taskClampedToImport = afterImport.editorTask == 0 && afterImport.tasks.size() == 1;

		LOGGER.info("[selftest] 录入会话固化: 模式={} 任务={} 负数夹取={} 越界夹取={} 导入后模式保住={} 导入后任务夹到={}",
			modeKept, taskKept, clampedLow, clampedHigh, modeSurvivesImport, afterImport.editorTask);
		if (!modeKept || !taskKept)
			LOGGER.error("[selftest] 录入会话状态没有固化到卡上！modeKept={} taskKept={}（换物品就会丢模式）", modeKept,
				taskKept);
		// 越界要夹到**最后一个任务的序号**（不是 0）—— 这里第一版把期望写成 0，被自检当场抓出来了
		if (clampedLow != 0 || clampedHigh != session.tasks.size() - 1)
			LOGGER.error("[selftest] 越界任务下标没有被夹到合法范围：负数→{}（期望 0） 越界→{}（期望 {}，界面会索引越界）",
				clampedLow, clampedHigh, session.tasks.size() - 1);
		if (!modeSurvivesImport || !taskClampedToImport)
			LOGGER.error("[selftest] 导入脚本把编辑状态弄坏了：模式保住={}（应为 true） 任务夹到={}（应为 0，新程序只有 1 个任务）",
				modeSurvivesImport, afterImport.editorTask);

		// ④ 浏览位置（§18）：页面 / 操作页指针 / 两个列表的滚动 —— 用户要"关掉去试一下、再打开接着改"
		CardProgramStorage.writeView(card, new CardProgramStorage.View(3, CardProgramStorage.PAGE_ENTRIES, 42.5f, 12f));
		CardProgramStorage.View viewBack = CardProgramStorage.readView(card);
		boolean viewKept = viewBack.entry() == 3 && CardProgramStorage.PAGE_ENTRIES.equals(viewBack.page())
			&& Math.abs(viewBack.scroll() - 42.5f) < 0.01f && Math.abs(viewBack.taskScroll() - 12f) < 0.01f;
		// ★ 写浏览位置**不能碰程序**（它走的是自己那条轻量通道，见 CardViewPacket）
		boolean viewKeepsProgram = CardProgramStorage.read(card, registries).tasks.size() == session.tasks.size();
		// 客户端送来的脏数据不许进 NBT：页名乱写 ⇒ 当操作页；指针负数 / 滚动 NaN、负数 ⇒ 归 0
		CardProgramStorage.writeView(card, new CardProgramStorage.View(-7, "乱写的页", Float.NaN, -3f));
		CardProgramStorage.View dirtyBack = CardProgramStorage.readView(card);
		boolean viewSanitised = dirtyBack.entry() == 0 && CardProgramStorage.PAGE_ENTRIES.equals(dirtyBack.page())
			&& dirtyBack.scroll() == 0f && dirtyBack.taskScroll() == 0f;
		// 从没打开过编辑器的新卡 ⇒ 默认值（不是"读出一个空指针"那种惊喜）
		boolean viewDefault = CardProgramStorage
			.readView(new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()))
			.equals(CardProgramStorage.View.DEFAULT);

		LOGGER.info("[selftest] 浏览位置（§18）: 存得住={} 不动程序={} 脏数据被过滤={} 新卡取默认={} （指针={} 页={} 滚动={}/{}）",
			viewKept, viewKeepsProgram, viewSanitised, viewDefault, viewBack.entry(), viewBack.page(), viewBack.scroll(),
			viewBack.taskScroll());
		if (!viewKept || !viewKeepsProgram || !viewSanitised || !viewDefault)
			LOGGER.error("[selftest] 浏览位置有问题：存得住={} 不动程序={} 脏数据被过滤={} 新卡取默认={}", viewKept,
				viewKeepsProgram, viewSanitised, viewDefault);
	}

	/**
	 * 「对任意方块也能动手」的自检（用户第二轮反馈）。
	 *
	 * <p>守两件事：① 操作分类正确 —— 只有需要交换物品的四类要求 Create 交互点，攻击 / 使用 /
	 * 潜行使用不要求（这个分类在录入与运行时<b>共用</b>，写错一边就是"能录但跑不动"）；
	 * ② {@link AnyBlockInteractionPoint} 对一个**普通方块**（石头，不是 Create 交互点）能给出可用的
	 * 爪子目标，而且取物点真的在方块<b>外面</b> —— 退化成"方块中心"的话爪子会插进方块里
	 * （阶段 1 实测过这个现象）。
	 */
	private static void checkArbitraryBlockTarget(MinecraftServer server) {
		boolean split = OperationType.TAKE.needsInteractionPoint() && OperationType.DEPOSIT.needsInteractionPoint()
			&& OperationType.UNLOAD.needsInteractionPoint() && OperationType.DEPLOY.needsInteractionPoint()
			&& !OperationType.MINE.needsInteractionPoint() && !OperationType.ATTACK.needsInteractionPoint()
			&& !OperationType.USE.needsInteractionPoint() && !OperationType.USE_SNEAK.needsInteractionPoint();

		// v27：旧的混合操作 PUNCH 必须迁到挖掘（老卡因此继续只挖方块、不会突然开始打实体）
		boolean legacyPunch = OperationType.byName("PUNCH", null) == OperationType.MINE;

		// 等待类必须**只有**两种原地等待：运行时靠这个标志决定"等待前先收回手臂"，
		// 把控制类（调用/退出/抛错）算进去就会在连续两条目标之间多绕一趟（§7.6 v4）。
		// ⚠️ 原来的第三条（"等相邻红石"）已按用户口径整体删除（"我们有更好的无线信号系统可以使用"）：
		//   判据本身仍然只有 {@code OperationType.isWait()} 一处，这行只负责把它钉死。
		boolean waitSplit = OperationType.WAIT.isWait()
			&& OperationType.SIGNAL.isWait() && !OperationType.CALL.isWait() && !OperationType.BREAK.isWait()
			&& !OperationType.THROW.isWait() && !OperationType.TAKE.isWait() && !OperationType.MINE.isWait();

		ServerLevel level = server.overworld();
		BlockPos pos = new BlockPos(level.getSharedSpawnPos()
			.getX() + 6, 200, level.getSharedSpawnPos()
				.getZ() + 6);
		BlockState stone = Blocks.STONE.defaultBlockState();
		boolean interactable = ArmInteractionPoint.isInteractable(level, pos, stone);
		ArmInteractionPoint point = AnyBlockInteractionPoint.create(level, pos, stone, Direction.UP);
		ArmInteractionPointAccessor access = (ArmInteractionPointAccessor) point;
		Vec3 claw = access.create$getInteractionPositionVector();
		Direction dir = access.create$getInteractionDirection();
		boolean outside = claw.y > pos.getY() + 1.0 - 1.0e-6; // 石头上表面之上
		// ★ 方向：顶面录的点，爪尖必须**朝下扎**（face.getOpposite()）—— 用户炒锅截图那个
		// "手臂插进方块、头还翘起来"就是这里写反了（ArmAngleTarget 会沿 clawFacing.getOpposite() 挪 IK 目标）。
		boolean facingInward = dir == Direction.DOWN;
		boolean anglesOk = point.getTargetAngles(pos.offset(2, 0, 0), false) != null;

		// ★ 碰撞箱真的参与了计算吗（用户第三轮："结合碰撞箱和操作面共同决定运动点位"）：
		// 拿一个**下半砖**当例子 —— 它的顶面只到 0.5，如果代码还在用"方块中心 + 固定 0.5"，
		// 落点会跑到 1.0（手悬在半空）；正确结果是贴着 0.5 那个真实顶面。
		BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
		Vec3 slabClaw = ((ArmInteractionPointAccessor) AnyBlockInteractionPoint.create(level, pos, slab,
			Direction.UP)).create$getInteractionPositionVector();
		boolean shapeAware = Math.abs(slabClaw.y - (pos.getY() + 0.5)) < 0.2;
		boolean sameColumn = Math.abs(slabClaw.x - (pos.getX() + 0.5)) < 1.0e-6
			&& Math.abs(slabClaw.z - (pos.getZ() + 0.5)) < 1.0e-6;

		LOGGER.info(
			"[selftest] 运行时分类: 目标门槛正确={} 等待类正确={} 旧PUNCH归挖掘={} 石头是交互点={} 爪子高于方块顶面={} 朝向={} 爪尖朝内={} 角度可算={} 半砖贴面={} 面中心对齐={}",
			split, waitSplit, legacyPunch, interactable, outside, dir, facingInward, anglesOk, shapeAware, sameColumn);
		if (!split)
			LOGGER.error("[selftest] 操作分类错了：攻击/挖掘不该要求 Create 交互点（用户反馈：对任意方块都能试）");
		if (!legacyPunch)
			LOGGER.error("[selftest] 旧名字 PUNCH 没有迁到「挖掘」—— 老卡里的攻击条目会突然开始打实体（可能打到玩家）");
		if (!waitSplit)
			LOGGER.error("[selftest] 等待类分类错了：只有 等待/收信号 该在等待前收回手臂，控制类不算");
		if (interactable)
			LOGGER.warn("[selftest] 石头居然被认成 Create 交互点（上游变了？）—— 这条自检的前提需要复核");
		if (!outside || !anglesOk)
			LOGGER.error("[selftest] 通用交互点有问题：爪子在方块内（{}）/ 角度算不出={}", claw, anglesOk);
		if (!facingInward)
			LOGGER.error("[selftest] 爪尖朝向写反了：顶面录的点应当朝下扎（现在 {}）—— 手会插进方块、头会翘起来", dir);
		if (!shapeAware || !sameColumn)
			LOGGER.error("[selftest] 落点没有跟着碰撞箱走：半砖顶面落点={}（期望 y≈{}，即贴 0.5 那个真实顶面）/ 面中心对齐={}",
				slabClaw, pos.getY() + 0.5, sameColumn);
	}

	/**
	 * 「剪贴板 → 备注」的序号映射自检（用户第三轮要求）。
	 *
	 * <p>守三件事：① 第 i 行写进第 i 条（差一位是最容易犯、又最难看见的错）；② 空行**跳过**而不是把备注
	 * 清空（玩家用空行给某条留白）；③ 超出行数的行被忽略并计数。这三条在游戏里全靠肉眼数，只能靠断言。
	 */
	private static void checkNotesMapping() {
		ArmProgramTask task = new ArmProgramTask();
		task.name = "备注";
		for (int i = 0; i < 3; i++) {
			ArmProgramEntry entry = new ArmProgramEntry();
			entry.op = OperationType.WAIT;
			entry.note = "原有" + i;
			task.entries.add(entry);
		}
		int[] counts = CardNotesPacket.applyNotes(task, List.of("第一行", "   ", "第三行", "多出来的一行"));
		boolean ordinal = "第一行".equals(task.entries.get(0).note) && "原有1".equals(task.entries.get(1).note)
			&& "第三行".equals(task.entries.get(2).note);
		boolean counted = counts[0] == 2 && counts[1] == 1;

		// 长行必须被截到 NOTE_MAX（与编辑器备注框、协议上限一致），否则界面显示一截、实际存另一截
		ArmProgramTask longTask = new ArmProgramTask();
		ArmProgramEntry longEntry = new ArmProgramEntry();
		longEntry.op = OperationType.WAIT;
		longTask.entries.add(longEntry);
		CardNotesPacket.applyNotes(longTask, List.of("x".repeat(ArmProgramEntry.NOTE_MAX + 20)));
		boolean truncated = longEntry.note.length() == ArmProgramEntry.NOTE_MAX
			&& longEntry.note.endsWith("…");

		// ★ v27：导入按**条目**、**保留换行**（用户定的）
		String pasted = "第一行\n第二行";
		boolean newlineKept = ArmProgramEntry.clipNote(pasted.strip())
			.equals(pasted);
		// 超长时补省略号，且**省略号算在上限之内**（否则截断后仍超上限、协议那层还是抛异常）
		String tooLong = ArmProgramEntry.clipNote("y".repeat(ArmProgramEntry.NOTE_MAX + 5));
		boolean ellipsisInsideLimit = tooLong.length() == ArmProgramEntry.NOTE_MAX && tooLong.endsWith("…");
		// 多行备注经过 NBT 往返必须一字不差（换行不能丢、也不能被拆成两条）
		ArmProgramEntry multi = new ArmProgramEntry();
		multi.op = OperationType.WAIT;
		multi.note = pasted;
		ArmProgramEntry multiBack = ArmProgramEntry.load(multi.save(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
		boolean nbtKeepsNewline = multiBack.note.equals(pasted);
		// 编辑框的两种形式互转必须闭环（方案 B：字面 \n ↔ 真换行）
		boolean flattenRoundTrip = ArmProgramEntry.joinNote(ArmProgramEntry.flattenNote(pasted))
			.equals(pasted);

		// ★ 编辑框里**实际显示**的那一串：换行是 1 个字符、显示成 2 个，所以"存得下"不等于"显示得下"。
		// 判据：① 放得下时原样（不加省略号）；② 放不下时补省略号、且**不会从字面 `\n` 中间切开**
		//（切开会在框里留下一个孤立反斜杠，玩家敲一下就被存进备注）；③ 任何输入都不超过框的上限。
		String manyNewlines = "\n".repeat(ArmProgramEntry.NOTE_MAX);
		// 这一条专门用来抓"切断"：扁平化后是 257 字（奇数），硬截到 128 正好切在 `\` 与 `n` 之间。
		String splitProbe = "a" + manyNewlines;
		String fieldFits = ArmProgramEntry.flattenNoteForField(pasted);
		String fieldOver = ArmProgramEntry.flattenNoteForField(splitProbe);
		boolean fieldWhenFits = fieldFits.equals(ArmProgramEntry.flattenNote(pasted));
		boolean fieldWhenOver = fieldOver.length() == ArmProgramEntry.NOTE_MAX && fieldOver.endsWith("…")
			&& !fieldOver.endsWith("\\");
		boolean fieldLimitHeld = true;
		for (String probe : List.of(pasted, manyNewlines, splitProbe, "a".repeat(ArmProgramEntry.NOTE_MAX - 1) + "\n",
			"a".repeat(ArmProgramEntry.NOTE_MAX), "\n" + "z".repeat(ArmProgramEntry.NOTE_MAX))) {
			String field = ArmProgramEntry.flattenNoteForField(probe);
			boolean fits = ArmProgramEntry.flattenNote(probe)
				.length() <= ArmProgramEntry.NOTE_MAX;
			boolean ok = field.length() <= ArmProgramEntry.NOTE_MAX
				&& (fits ? field.equals(ArmProgramEntry.flattenNote(probe))
					: field.endsWith("…") && !field.endsWith("\\"));
			if (!ok) {
				fieldLimitHeld = false;
				LOGGER.error("[selftest] 备注框显示形式在输入[{}]上不对：显示长度={} 放得下={}",
					probe.length() > 8 ? probe.length() + " 字" : probe.replace("\n", "\\n"), field.length(), fits);
			}
		}

		LOGGER.info("[selftest] 剪贴板→备注: 按序号={} 空行跳过={} 写入/忽略={}/{} 截到上限={} 换行保留={} 省略号在上限内={} NBT保换行={} 扁平化闭环={}",
			ordinal, ordinal, counts[0], counts[1], truncated, newlineKept, ellipsisInsideLimit, nbtKeepsNewline,
			flattenRoundTrip);
		LOGGER.info("[selftest] 备注框显示形式: 放得下就原样={} 放不下不切字面换行={} 任何输入都不超上限={} （超限时长度={} 结尾={}）",
			fieldWhenFits, fieldWhenOver, fieldLimitHeld, fieldOver.length(),
			fieldOver.isEmpty() ? "" : fieldOver.substring(fieldOver.length() - 1));
		// ★ 卡片上的「备注」胶囊只能是**摘要**：长备注会把整行占满、把行尾的「添加属性」＋ 顶出裁剪区
		//   （用户实测："备注太长会把添加属性的按键挤到外面，并且即使超出去了……也可以点击"）。
		//   判据是纯文本，客户端渲染与这里共用同一份实现（ArmEntryText.noteSummary）。
		String summary = ArmEntryText.noteSummary("第一行\n" + "字".repeat(ArmProgramEntry.NOTE_MAX));
		boolean chipSummary = summary.length() == ArmEntryText.NOTE_CHIP_CHARS + 1 && summary.endsWith("…")
			&& !summary.contains("\n") && summary.startsWith("第一行");
		boolean chipShortUntouched = "短备注".equals(ArmEntryText.noteSummary("短备注"))
			&& "".equals(ArmEntryText.noteSummary(null));
		// ★ 备注的 tooltip 必须是**逐行**文本（详见 ArmEntryText.noteLines）：
		//   把带 \n 的整段塞进一个组件，在 tooltip 装得下屏幕时 NeoForge **不会**断行，
		//   \n 会被画成一个"缺字方框"（用户实测："并没有实现多行排版"）。
		boolean noteLinesSplit = List.of("第一行", "第二行", "第三行")
			.equals(ArmEntryText.noteLines("第一行\n第二行\n第三行"));
		boolean noteLinesEdge = ArmEntryText.noteLines("没有换行")
			.size() == 1 && ArmEntryText.noteLines("").isEmpty() && ArmEntryText.noteLines(null)
				.isEmpty()
			&& ArmEntryText.noteLines("尾行\n").size() == 2;
		LOGGER.info("[selftest] 备注胶囊: 长备注取前{}字+省略号={} 换行压成空格=true 短备注原样={} ｜ tooltip逐行={} 边界={} （摘要=[{}]）",
			ArmEntryText.NOTE_CHIP_CHARS, chipSummary, chipShortUntouched, noteLinesSplit, noteLinesEdge, summary);
		if (!chipSummary)
			LOGGER.error("[selftest] 备注胶囊不是摘要（长度={}）—— 长备注会把卡片整行占满，行尾的 ＋ 会被顶出去", summary.length());
		if (!chipShortUntouched)
			LOGGER.error("[selftest] 短备注/空备注被摘要逻辑改动了（短备注应当原样、空备注给空串）");
		if (!noteLinesSplit || !noteLinesEdge)
			LOGGER.error("[selftest] 备注的 tooltip 不是逐行的（拆分={} 边界={}）—— 一段带 \\n 的文字在 tooltip 装得下时"
				+ "不会被断行，会被画成缺字方框（用户实测：\"并没有实现多行排版\"）", noteLinesSplit, noteLinesEdge);
		if (!ordinal)
			LOGGER.error("[selftest] 备注序号对错了：{}", task.entries.stream()
				.map(e -> e.note)
				.toList());
		if (!counted)
			LOGGER.error("[selftest] 备注计数错了（写入/忽略 = {}/{}，期望 2/1）", counts[0], counts[1]);
		if (!truncated)
			LOGGER.error("[selftest] 超长备注没有被截到 {} 字（编辑器输入框的上限）", ArmProgramEntry.NOTE_MAX);
		if (!newlineKept || !nbtKeepsNewline || !flattenRoundTrip)
			LOGGER.error("[selftest] 多行备注没保住：换行保留={} NBT保换行={} 扁平化闭环={}"
				+ " —— 按条目导入时换行必须原样留着（它代表「一条待办里的多行」）", newlineKept, nbtKeepsNewline,
				flattenRoundTrip);
		if (!ellipsisInsideLimit)
			LOGGER.error("[selftest] 超长截断的省略号没有算在上限之内（长度={}）—— 截断后仍超上限，协议那层会抛异常",
				tooLong.length());
		if (!fieldWhenFits)
			LOGGER.error("[selftest] 备注框的显示形式把放得下的备注改了：显示=[{}] 期望=[{}]",
				fieldFits.replace("\n", "\\n"), ArmProgramEntry.flattenNote(pasted)
					.replace("\n", "\\n"));
		if (!fieldWhenOver)
			LOGGER.error("[selftest] 备注框放不下时没有好好截断（长度={} 结尾=[{}]）—— 换行是 1 个字符、显示成 2 个，"
				+ "128 个换行存得下但显示是 256 字；直接交给输入框会被从 `\\n` 中间截开，留下一个孤立反斜杠",
				fieldOver.length(), fieldOver.endsWith("\\") ? "\\" : "（不是反斜杠）");
		if (!fieldLimitHeld)
			LOGGER.error("[selftest] 有输入的备注框显示形式超过了 {} 字 —— 输入框会硬截，界面显示与实际存的不再一致",
				ArmProgramEntry.NOTE_MAX);
	}

	/**
	 * 「等待不会被空转守卫停机」的自检（用户第三轮）。
	 *
	 * <p>用户问："连续无进展超时是如何触发的呢？我觉得我们应该不需要这个吧？毕竟有些事件需要玩家主动触发，
	 * 动力臂必须一直等待。" —— 原来那两条计时/步数预算（60s 无成功、20 万步）确实是**按时间**停机的，
	 * 等玩家补货、等待红石链接通电都会误杀，已删除；换成只认"原地绕圈"的空转守卫。
	 *
	 * <p>这条自检守的就是新守卫的**判据**：移动/等待/延迟/重试必须算"在跟世界打交道"（否则长时间的等待会被
	 * 当成空转停机 —— 这正是我们要根除的那个行为）；而空闲/执行不能算（否则守卫永远抓不到任何东西）。
	 */
	private static void checkSpinGuard() {
		boolean paced = ProgramRuntime.isPaced(ProgramRuntime.EntryState.MOVING)
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.WAITING)
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.DELAY)
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.RETRY);
		boolean notPaced = !ProgramRuntime.isPaced(ProgramRuntime.EntryState.IDLE)
			&& !ProgramRuntime.isPaced(ProgramRuntime.EntryState.EXECUTING)
			&& !ProgramRuntime.isPaced(ProgramRuntime.EntryState.HALTED);

		// 两种操作的"等待"必须都在 paced 里：WAIT/收信号 进 WAITING，阻塞重试（等玩家补货）进 RETRY
		boolean waitsCovered = OperationType.WAIT.isWait() && OperationType.SIGNAL.isWait()
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.WAITING)
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.RETRY);

		LOGGER.info("[selftest] 空转守卫: 等待/重试算在做事={} 空闲/执行不算={} 覆盖所有等待类={} 上限={}条", paced,
			notPaced, waitsCovered, ProgramRuntime.MAX_IDLE_SPINS);
		if (!paced || !notPaced)
			LOGGER.error("[selftest] 空转守卫的判据错了（paced={} notPaced={}）—— 会误杀等待或永远抓不到空转", paced,
				notPaced);
		if (!waitsCovered)
			LOGGER.error("[selftest] 有等待类操作没有被 paced 覆盖 —— 它会被空转守卫当成空转停机");
	}

	/**
	 * 「每 tick 推进多少条」的自检（用户本轮需求）：配置项 {@code entriesPerTick} 的运行时效果。
	 *
	 * <p><b>"一条"的口径 = 一条"一瞬间出结果"的条目被完整跑完</b>（{@code succeed} / 跳转 / 收工 /
	 * 返回调用方）—— 状态机上是**两步**（{@code beginEntry} → {@code execute}），详见
	 * {@link ProgramRuntime#STEPS_PER_ENTRY}。所以速率 = 2 时"两条相邻的无动作条目"必须在
	 * **同一个 tick** 内跑完（4 步预算），而速率 = 0.5 时一条控制类条目正好跨 2 tick（每 tick 1 步，
	 * 与旧版逐 tick 一致）。拿"状态机的一步"当一条、又只给速率那么多预算，结果就是每条要花半条预算
	 * ⇒ 速率整体只剩一半 —— 上一轮 ①② 两条红都是这个根因。
	 *
	 * <p>夹具：主任务 = [发信号（把频率 f 置位）、取出 2,0,0（空置物台 ⇒ 前置检查永远不过 ⇒ 卡在它上面）]。
	 * 两条都是**无动作**条目（不移动、不等待），第二条会在原地重试 —— 于是"游标停在下标 1
	 * 且状态 RETRY"就是"两条都跑完了"的证据，而 `entryIndex == 0` 就是"一条都没跑"的证据。
	 *
	 * <p><b>反证（改哪一行会变红）</b>：
	 * <ol>
	 *   <li>{@code ProgramRuntime.tick} 里 {@code rateBudget += effectiveRate() * STEPS_PER_ENTRY}
	 *       的 {@code * STEPS_PER_ENTRY} 去掉（预算按"步"当"条"发）⇒ 速率 = 2 时
	 *       {@code 两条同一 tick 跑完} 变 false（4 步只够一条）；</li>
	 *   <li>把"一条"改成按状态机步数记账（把 {@code maxStepsPerTick()} 换成 {@code maxEntriesPerTick()}，
	 *       或每步扣两步预算）⇒ 同上变红；</li>
	 *   <li>去掉循环里那句 `isPaced` 就让出 ⇒ {@code 移动/等待类不受影响} 变 false（等待会在同一 tick 里被连推）；</li>
	 *   <li>谁把 0.5 实现成"每 tick 至少推进 1 条"（例如 {@code Math.max(1, …)}）⇒
	 *       {@code 速率=0.5 时行为与旧版一致} 变 false（第 1 tick 就把整条跑完了）；</li>
	 *   <li>把 {@code rateBudget -= 1.0} 删掉（预算只加不扣）⇒ ② 会在第 2 tick 把两条都推完、
	 *       而且越跑越快（攒账）。</li>
	 * </ol>
	 */
	private static void checkEntryRate(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 104, 200, spawn.getZ() + 104);
		BlockPos depotPos = armPos.offset(2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldDepot = level.getBlockState(depotPos);
		Couple<Frequency> freq = testFrequency(Items.CLAY_BALL, Items.BRICK);
		boolean ran = false;
		boolean twoPerTick = false;
		boolean halfIsOldPace = false;
		boolean pacedUnaffected = false;
		boolean capHolds = false;
		String detail = "没跑起来";
		try {
			BlockState depotState = stateOf("create:depot");
			if (depotState == null) {
				LOGGER.error("[selftest] 推进速率自检**没跑起来**：找不到 create:depot");
				return;
			}
			level.getChunkAt(armPos);
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotPos, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 推进速率自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过（与原版一致）
			ran = true;

			// ——— ① 速率 = 2：两条相邻的无动作条目在**同一个 tick** 里跑完 ———
			// 先按常态速率换程序：`setProgram` 会走 `resetToStart`（速率预算归零），
			// 于是下面第一 tick 拿到的预算就是**确定**的 2 × STEPS_PER_ENTRY = 4 步，
			// 不依赖上一段测试留下了什么零头。
			ProgramRuntime.pinRateForTests(OLD_PACE_RATE);
			arm.setProgram(rateProbeProgram(freq, depotPos.subtract(armPos)));
			ProgramRuntime.pinRateForTests(2.0);
			arm.runtime.tick(arm);
			String afterFirst = "游标=" + arm.runtime.entryIndex + " 状态=" + arm.runtime.state + " 原因="
				+ arm.runtime.getLastFailure();
			// 判据只用"游标"和"状态"（不钉失败文案）：第一条发信号跑完（两步）⇒ 游标到 1；
			// 第二条取出被"目标为空"挡住 ⇒ 在**同一 tick 的第三步**就进了 RETRY。两条都在这一 tick 里发生。
			twoPerTick = arm.runtime.entryIndex == 1 && arm.runtime.state == ProgramRuntime.EntryState.RETRY;
			detail = "①" + afterFirst;

			// ——— ② 速率 = 0.5：行为与旧版一致（一条 2 tick：每 tick 一步）———
			ProgramRuntime.pinRateForTests(OLD_PACE_RATE);
			arm.setProgram(rateProbeProgram(freq, depotPos.subtract(armPos)));
			ProgramRuntime.pinRateForTests(0.5);
			arm.runtime.tick(arm); // 第 1 tick：预算 = 1 步 ⇒ 只进 EXECUTING，游标不动
			boolean nothingYet = arm.runtime.entryIndex == 0;
			arm.runtime.tick(arm); // 第 2 tick：再攒 1 步 ⇒ execute 出结果、游标到 1
			boolean oneAfterTwo = arm.runtime.entryIndex == 1;
			halfIsOldPace = nothingYet && oneAfterTwo;
			detail += " ②第1tick游标=" + (nothingYet ? 0 : arm.runtime.entryIndex) + " 第2tick游标=" + arm.runtime.entryIndex;

			// ——— ③ 有动作的条目**一点都不变快**：速率拉到 32，一条「等待 20 tick」照样要 20 个真实 tick ———
			// 判据不看"臂动没动"（那需要运动学），只看两件事：
			// ⓐ 高倍率下前 3 tick：游标**还停在等待那一条（下标 0）**、状态 WAITING ——
			//    速率若能推快有动作的条目，20 tick 的等待会在第 1 tick 就被冲过去；
			// ⓑ 一直数到"它后面那条发信号真的执行了"为止：耗掉的**真实 tick 数一点不少**（>= waitTicks）——
			//    这一条把"只看 3 tick 就下结论"的空子堵上（把等待时长改小也照样红）。
			// ⚠️ 旧夹具把 ⓐ 写成 `entryIndex == 1`：等待是**第 1 条**（下标 0），下标 1 是后面那条
			//    「发信号」—— 那个期望值只在"等待已经跑完"时成立，而这条断言要的正是"还没跑完"。
			//    上一轮它就是这样恒红的（现场 游标=0 状态=WAITING，其实行为完全正确）。
			final int waitTicks = 20;
			ProgramRuntime.pinRateForTests(OLD_PACE_RATE);
			arm.setProgram(rateProbeWaitProgram(freq, waitTicks));
			ProgramRuntime.pinRateForTests(32.0);
			for (int i = 0; i < 3; i++)
				arm.runtime.tick(arm);
			String afterThree = "游标=" + arm.runtime.entryIndex + " 状态=" + arm.runtime.state;
			boolean stillOnWait = arm.runtime.entryIndex == 0
				&& arm.runtime.state == ProgramRuntime.EntryState.WAITING;
			// 证人 = 臂的发射表里真的出现了这个频率（第二条「发信号」执行过的副作用，不靠内部状态猜）。
			// `setProgram` 会把上一段留下的发射清干净（onProgramReplaced → clearTransmissions），
			// 所以这里为真只可能来自本夹具。
			int ticksToNextEntry = 3;
			boolean nextEntryRan = false;
			while (stillOnWait && !nextEntryRan && ticksToNextEntry < waitTicks * 2) {
				arm.runtime.tick(arm);
				ticksToNextEntry++;
				nextEntryRan = arm.holdsSignal(freq);
			}
			pacedUnaffected = stillOnWait && nextEntryRan && ticksToNextEntry >= waitTicks;
			detail += " ③高倍率下等待（前3tick）:" + afterThree + " 后面那条真的执行了=" + nextEntryRan
				+ " 一共耗掉真实tick=" + ticksToNextEntry + "/" + waitTicks;

			// ——— ④ 每 tick 条数硬上限（防"一条自跳转脚本"卡在一个 tick 里）———
			int capAtDefault = withRate(2.0, ProgramRuntime::maxEntriesPerTick);
			int capAtMax = withRate(SequencedArmConfig.MAX_ENTRIES_PER_TICK, ProgramRuntime::maxEntriesPerTick);
			capHolds = capAtDefault >= 2 && capAtDefault <= 8 && capAtMax == (int) SequencedArmConfig.MAX_ENTRIES_PER_TICK;
			detail += " ④上限(速率2)=" + capAtDefault + " 上限(速率" + SequencedArmConfig.MAX_ENTRIES_PER_TICK + ")=" + capAtMax;

			// 交回"旧版节奏"给后面的夹具（它们全按每 tick 一步写）
			ProgramRuntime.pinRateForTests(OLD_PACE_RATE);
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			ProgramRuntime.pinRateForTests(OLD_PACE_RATE); // 异常路径也不能把速率留给后面的夹具
			level.setBlockAndUpdate(depotPos, oldDepot);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 推进速率: 跑起来={} 速率2⇒两条同一tick跑完={} 速率0.5⇒与旧版一致(一条2tick)={} "
			+ "有动作的条目不受影响={} 每tick硬上限={} （{}）", ran, twoPerTick, halfIsOldPace, pacedUnaffected, capHolds,
			detail);
		if (!ran)
			LOGGER.error("[selftest] 推进速率自检**没跑起来**（{}）—— 等于没测", detail);
		if (!twoPerTick)
			LOGGER.error("[selftest] 速率=2 时两条相邻的无动作条目**没有在同一个 tick 里跑完**（{}）—— "
				+ "两种可能：① 预算没按条给（`rateBudget += 速率` 少了 `* STEPS_PER_ENTRY`，"
				+ "于是 4 步的额度只够一条）；② 硬上限写成了 `maxEntriesPerTick()` 而不是 `maxStepsPerTick()`"
				+ "（步数闸把两条砍成一条）。改的是 ProgramRuntime.tick 的预算那两行", detail);
		if (!halfIsOldPace)
			LOGGER.error("[selftest] 速率=0.5 时行为**与旧版不一致**（旧版是一条 2 tick：第 1 tick 进 EXECUTING、"
				+ "第 2 tick 出一条）（{}）—— 预算被夹成了每 tick 至少一步 / 每步扣的不是一步", detail);
		if (!pacedUnaffected)
			LOGGER.error("[selftest] 有动作的条目被速率带快了（高倍率下「等待 20 tick」本该停在 WAITING、游标不动，"
				+ "并且要耗掉 >= 20 个真实 tick 才轮到后面那条）（{}）—— 循环里那句 `isPaced` 的让出被删掉了？"
				+ "这一条就是「慢的那些仍然按真实 tick 走」", detail);
		if (!capHolds)
			LOGGER.error("[selftest] 每 tick 的条目数硬上限不对（{}）—— `maxEntriesPerTick()` 必须 ≥ max(8, ceil(速率))，"
				+ "否则「一条跳回自己的脚本」能在同一个 tick 里无限转（或者合法速率被上限卡住）", detail);
	}

	/**
	 * 运行日志自检（用户需求："导出最近的日志，比如用剪贴板潜行右键可以打印最近的日志"）。
	 *
	 * <p>四条断言，每条都写清"改哪一行会让它变红"：
	 * <ol>
	 *   <li><b>记录</b>：夹具是 {@code [等待 1t, 取出 空置物台]}，跑 10 个 tick ⇒ 缓冲里必须出现
	 *       {@code [+Nt] 日志#2 take 失败：…}。变红的方式：删掉 {@code ProgramRuntime.handleFailure}
	 *       里那句 {@code arm.logLine(...)}；或把 {@code logWhere} 的 {@code entryIndex + 1} 改成
	 *       {@code entryIndex}（序号会差 1，"#2" 变 "#1"）。</li>
	 *   <li><b>不刷屏</b>：同一段里重试了好几 tick，缓冲**只能有 2 行**（等待开始 + 那条失败）。
	 *       变红的方式：把记录点从"状态变化处"挪进 {@code tick()} 每 tick 一行；或者把
	 *       {@code handleFailure} 里的 {@code changed} 条件去掉。</li>
	 *   <li><b>上限</b>：容量钉成 8，跑 40 条「等待」⇒ 只剩最后 8 行、最旧那行真的没了。
	 *       变红的方式：把 {@code logLine} 里那个 {@code while (logLines.size() >= capacity) pollFirst()}
	 *       删掉。</li>
	 *   <li><b>关闭</b>：容量 0 ⇒ 一行都不产生。变红的方式：让 {@code logCapacity()} 忽略 0
	 *       （比如把 {@code Mth.clamp(..., 0, MAX)} 改成 {@code Math.max(1, ...)}）。</li>
	 *   <li><b>导出</b>：真的调 {@code SequencedArmBlockEntity.exportLog}（公开静态，与
	 *       {@code CardEditPacket.addEntry} 同一手法）⇒ 读回来的剪贴板里有头部、行数不超容量；
	 *       另外验"装不下时只带最新那些行"与"只读剪贴板拒写"。
	 *       变红的方式：把 {@code exportLog} 里的 {@code new ClipboardContent(...)} 换成"就地改"
	 *       （读回来就看不到新内容）、或者把头部那一行删掉。</li>
	 * </ol>
	 *
	 * <p>⚠️ <b>为什么不断言 {@code [+Nt]} 里的 N &gt; 0</b>：本自检整个跑在
	 * {@code ServerStartedEvent} 里，{@code level.getGameTime()} 在这期间**不前进**，
	 * 所以每条间隔都必然是 0。能钉的只有**格式**（`[+0t]` 开头、全部匹配 {@code ^\[\+\d+t\] }），
	 * 写一条"N ≥ 1"的断言只会变成永远红的假守卫（决策 88③）。
	 */
	private static void checkRunLog(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 108, 200, spawn.getZ() + 108);
		BlockPos depotPos = armPos.offset(2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldDepot = level.getBlockState(depotPos);
		boolean ran = false;
		boolean failureLogged = false;
		boolean tickPrefix = false;
		boolean noSpam = false;
		boolean cappedAtEight = false;
		boolean oldestDropped = false;
		boolean disabledSilent = false;
		boolean exported = false;
		boolean headerPresent = false;
		boolean fitsCapacity = false;
		boolean trimmedKeepsNewest = false;
		boolean readOnlyRefused = false;
		String detail = "没跑起来";
		try {
			BlockState depotState = stateOf("create:depot");
			Item clipboardItem = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "clipboard"));
			if (depotState == null || clipboardItem == Items.AIR) {
				LOGGER.error("[selftest] 运行日志自检**没跑起来**：缺少 create:depot / create:clipboard");
				return;
			}
			level.getChunkAt(armPos);
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotPos, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 运行日志自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过
			ran = true;

			// ——— ①② 记录：等待 1 tick → 对空置物台取出（前置检查必报原因）———
			// 容量钉成一个**远大于本段产出**的确定值（= 一次导出的总容量 8×50）：
			// 配置是运行期现读的，玩家把 logLines 改成 0 也不该让这条断言变成"没测到"。
			SequencedArmBlockEntity.pinLogLinesForTests(SequencedArmBlockEntity.LOG_LINES_PER_PAGE
				* SequencedArmBlockEntity.LOG_MAX_PAGES);
			arm.setProgram(runLogProgram("日志", depotPos.subtract(armPos), 1));
			for (int i = 0; i < 10; i++)
				arm.runtime.tick(arm);
			List<String> lines = arm.logSnapshot();
			failureLogged = lines.stream()
				.anyMatch(line -> line.contains("失败：") && line.contains("#2 take"));
			// 第一行是 [+0t]（缓冲的第一行没有"上一条"），并且每一行都带那个前缀
			tickPrefix = !lines.isEmpty() && lines.get(0)
				.startsWith("[+0t] ")
				&& lines.stream()
					.allMatch(line -> line.matches("^\\[\\+\\d+t\\] .*"));
			noSpam = lines.size() == 2;
			detail = "①②" + String.join(" ｜ ", lines);

			// ——— ③ 上限：容量 8，跑一长串「等待」⇒ 只剩最后 8 行 ———
			SequencedArmBlockEntity.pinLogLinesForTests(8);
			arm.setProgram(runLogProgram("上限", null, 40));
			arm.runtime.tick(arm);
			String firstEver = arm.logSnapshot()
				.isEmpty() ? "" : arm.logSnapshot()
					.get(0);
			// 一条「等待 1t」在 0.5 条/tick 下约 3 tick ⇒ 60 tick 能产出约 20 行，足够把 8 行的缓冲挤满
			for (int i = 0; i < 60; i++)
				arm.runtime.tick(arm);
			List<String> capped = arm.logSnapshot();
			cappedAtEight = capped.size() == 8;
			// 最旧那行必须真的没了（只判 size 不够：容量若写成"全部保留"这里也照样 8 行就错了）
			oldestDropped = !firstEver.isEmpty() && !capped.contains(firstEver);
			detail += " ③" + capped.size() + "行 最后一行=[" + (capped.isEmpty() ? "" : capped.get(capped.size() - 1)) + "]";

			// ——— ④ 关闭：容量 0 ⇒ 一行都不产生 ———
			SequencedArmBlockEntity.pinLogLinesForTests(0);
			arm.setProgram(runLogProgram("关闭", depotPos.subtract(armPos), 5));
			for (int i = 0; i < 30; i++)
				arm.runtime.tick(arm);
			disabledSilent = !arm.logEnabled() && arm.logSnapshot()
				.isEmpty();

			// ——— ⑤ 导出：真的写进一块剪贴板（新建的，连组件都没有）———
			SequencedArmBlockEntity.pinLogLinesForTests(SequencedArmBlockEntity.LOG_LINES_PER_PAGE
				* SequencedArmBlockEntity.LOG_MAX_PAGES);
			arm.setProgram(runLogProgram("导出", depotPos.subtract(armPos), 3));
			for (int i = 0; i < 12; i++)
				arm.runtime.tick(arm);
			List<String> buffered = arm.logSnapshot();
			ItemStack clipboard = new ItemStack(clipboardItem);
			SequencedArmBlockEntity.LogExport result = SequencedArmBlockEntity.exportLog(clipboard, arm.getBlockPos(),
				buffered);
			List<ClipboardEntry> flat = flatClipboardEntries(clipboard);
			exported = result.ok() && result.written() == buffered.size()
				&& !buffered.isEmpty()
				&& flat.size() == buffered.size() + 1;
			headerPresent = !flat.isEmpty() && flat.get(0).text.getString()
				.contains("运行日志");
			fitsCapacity = flat.size() <= SequencedArmBlockEntity.LOG_LINES_PER_PAGE
				* SequencedArmBlockEntity.LOG_MAX_PAGES;
			detail += " ⑤导出=" + result.written() + "行/丢" + result.dropped() + "行 块内条目=" + flat.size();

			// 装不下时：只带**最新**那些行，并如实报出丢了几行
			int room = SequencedArmBlockEntity.LOG_LINES_PER_PAGE * SequencedArmBlockEntity.LOG_MAX_PAGES - 1;
			List<String> many = new ArrayList<>();
			for (int i = 0; i < room + 50; i++)
				many.add("[+0t] 合成第" + i + "行");
			ItemStack big = new ItemStack(clipboardItem);
			SequencedArmBlockEntity.LogExport trimmed = SequencedArmBlockEntity.exportLog(big, arm.getBlockPos(), many);
			List<ClipboardEntry> bigFlat = flatClipboardEntries(big);
			// 一并钉住分页：400 条 ÷ 每页 8 条 = 50 页（正好是 Create 界面能翻到的最后一页）
			trimmedKeepsNewest = trimmed.ok() && trimmed.written() == room && trimmed.dropped() == 50
				&& ClipboardEntry.readAll(big)
					.size() == SequencedArmBlockEntity.LOG_MAX_PAGES
				&& !bigFlat.isEmpty()
				&& bigFlat.get(bigFlat.size() - 1)
					.text.getString()
					.contains("合成第" + (many.size() - 1) + "行");
			detail += " ⑤裁剪=" + trimmed.written() + "/" + trimmed.dropped();

			// 只读剪贴板（1.20 老存档迁移过来的那种）一个字都不该动
			ItemStack readOnly = clipboardWith(buffered, true);
			ClipboardContent roBefore = readOnly.get(AllDataComponents.CLIPBOARD_CONTENT);
			SequencedArmBlockEntity.LogExport refused = SequencedArmBlockEntity.exportLog(readOnly, arm.getBlockPos(),
				buffered);
			readOnlyRefused = !refused.ok() && refused.problem() == SequencedArmBlockEntity.LogProblem.READ_ONLY
				&& readOnly.get(AllDataComponents.CLIPBOARD_CONTENT) == roBefore;

			// 交回"读配置"给后面的夹具
			SequencedArmBlockEntity.pinLogLinesForTests(null);
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			SequencedArmBlockEntity.pinLogLinesForTests(null); // 异常路径也不能把容量留给后面的夹具
			level.setBlockAndUpdate(depotPos, oldDepot);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 运行日志: 记录失败原因={} tick前缀={} 不刷屏={} 上限8行={} 丢最旧={} 关闭即静默={} ｜ "
			+ "导出={} 有头部={} 行数≤容量={} 超量只带最新={} 只读拒写={} （{}）", failureLogged, tickPrefix, noSpam,
			cappedAtEight, oldestDropped, disabledSilent, exported, headerPresent, fitsCapacity, trimmedKeepsNewest,
			readOnlyRefused, detail);
		if (!ran)
			LOGGER.error("[selftest] 运行日志自检**没跑起来**（{}）—— 等于没测", detail);
		if (!failureLogged)
			LOGGER.error("[selftest] 条目失败了，缓冲里却没有那行原因（{}）—— 用户导出日志正是要看它："
				+ "检查 ProgramRuntime.handleFailure 里的 arm.logLine（以及 logWhere 的序号）", detail);
		if (!tickPrefix)
			LOGGER.error("[selftest] 日志行没有带上 [+Nt] 前缀（或者第一行不是 [+0t]）—— 用户要的是"
				+ "「看清节奏」，没有 tick 差的绝对时间对他没用：检查 SequencedArmBlockEntity.logLine", detail);
		if (!noSpam)
			LOGGER.error("[selftest] 一次失败 + 重试在缓冲里留下了 {} 行（应当只有 2 行：等待开始 + 那条失败）"
				+ "—— 记录点不能放进每 tick 都跑的 tick()，也不能去掉 handleFailure 里的 changed 条件", detail);
		if (!cappedAtEight || !oldestDropped)
			LOGGER.error("[selftest] 环形缓冲没有按容量丢最旧的（上限8行={} 丢最旧={}）—— "
				+ "检查 SequencedArmBlockEntity.logLine 里的 while (size() >= capacity) pollFirst()", detail);
		if (!disabledSilent)
			LOGGER.error("[selftest] logLines = 0 时仍然产生了日志行 —— 关闭记录必须一行都不产生，"
				+ "而且每个记录点都要先判 arm.logEnabled()", detail);
		if (!exported || !headerPresent || !fitsCapacity)
			LOGGER.error("[selftest] 日志没能写进剪贴板（导出={} 有头部={} 行数≤容量={}）—— "
				+ "检查 SequencedArmBlockEntity.exportLog（必须新建 ClipboardContent 写回去，并且带头部那一行）", detail);
		if (!trimmedKeepsNewest)
			LOGGER.error("[selftest] 剪贴板装不下时没有「只带最新那些行」或丢的行数没报对", detail);
		if (!readOnlyRefused)
			LOGGER.error("[selftest] 只读剪贴板被改写了 —— 1.20 老存档迁移过来的那种剪贴板必须一个字都不动", detail);
	}

	/**
	 * ★ 「主任务恒为无限循环」的自检（用户口径："任务循环次数已经不需要了，因为只有一个主任务有这个属性，
	 * 但是主任务是不断循环的"）。
	 *
	 * <p><b>为什么要有这一条</b>：任务级循环（{@code loop} / {@code loopN}）整块删除之后，
	 * "跑完最后一条会怎样"完全由 {@code ProgramRuntime.finishTask} 里主任务那一支决定 ——
	 * 它写错的表现是**静默**的（程序停在末尾不动，或者一轮跑完就收工），界面上看不出任何异常。
	 *
	 * <p><b>夹具</b>：主任务 = [等待 0t, 等待 0t]（两条**一瞬间就出结果**的无动作条目；不碰世界、
	 * 不依赖交互点，也不产生"失败重试"）。跑到 {@code 80} 拍，用两个**互相独立**的证人数"跑了几轮"：
	 * <ol>
	 *   <li><b>游标</b>：先看见它停在**第 2 条**（下标 1），再看见它**回到第 1 条**（下标 0）——
	 *       这就是"一轮走完、从头再来"；每一对"见到 1 → 见到 0"记一轮；</li>
	 *   <li><b>运行日志</b>：数"跑完一轮"那行的条数（记录点⑧，{@code finishTask} 里唯一的日志行）。</li>
	 * </ol>
	 * 另外全程 {@code isHalted()} 必须为假 —— 恒循环**不许**被当成"跑完就停机"。
	 *
	 * <p><b>改哪一行会变红</b>：
	 * <ul>
	 *   <li>把 {@code ProgramRuntime.finishTask} 里主任务那一支改成停机（{@code endTask} / {@code halt}）⇒
	 *       游标轮数 = 0（停在末尾不动）且 {@code isHalted()} 为真、日志里**一条**"跑完一轮"都没有；</li>
	 *   <li>把"重新回到第 1 条"写成 {@code entryIndex = task.entries.size() - 1}（回到**最后一条**）⇒
	 *       游标永远不再回到 0 ⇒ 轮数 0（这条断言就是靠"下标 1 → 0 的回落"抓它的）；</li>
	 *   <li>只让它重跑**一次**（例如加个计数器停在第二轮）⇒ 轮数 < 3，与日志条数同样对不上。</li>
	 * </ul>
	 */
	private static void checkMainTaskLoopsForever(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		// 与别的夹具错开（各条自检用 +52…+108 一带的空地）
		BlockPos armPos = new BlockPos(spawn.getX() + 120, 200, spawn.getZ() + 76);
		BlockState oldArm = level.getBlockState(armPos);
		int rounds = 0;
		int loggedRounds = 0;
		boolean sawSecondEntry = false;
		boolean halted = false;
		boolean ran = false;
		try {
			level.getChunkAt(armPos); // 离出生点有点远：先把区块拿下来，免得方块实体没建出来（同 checkRunLog）
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 主任务恒循环 自检没跑起来（臂的方块实体没建出来）");
			} else {
				ran = true;
				arm.setSpeed(32); // 转速为 0 时解释器整 tick 跳过
				// 容量钉成一个远大于本段产出的确定值（本段约十几轮 ⇒ 二十来行）
				SequencedArmBlockEntity.pinLogLinesForTests(SequencedArmBlockEntity.LOG_LINES_PER_PAGE
					* SequencedArmBlockEntity.LOG_MAX_PAGES);
				ArmProgram program = new ArmProgram();
				ArmProgramTask main = new ArmProgramTask();
				main.name = "恒循环";
				for (int i = 0; i < 2; i++) {
					ArmProgramEntry wait = new ArmProgramEntry();
					wait.op = OperationType.WAIT;
					wait.waitAmount = 0;
					wait.waitUnit = ArmProgramEntry.WaitUnit.TICK;
					main.entries.add(wait);
				}
				program.tasks.add(main);
				arm.setProgram(program);
				arm.runtime.resetToStart(arm); // 也把运行日志清空（换卡/回到开头 = 另一个故事）
				int previous = arm.runtime.entryIndex;
				for (int i = 0; i < 80; i++) {
					arm.runtime.tick(arm);
					int now = arm.runtime.entryIndex;
					if (now == 1)
						sawSecondEntry = true;
					// 一轮走完的证据：游标从**后面**回到第 1 条（下标 0）
					if (previous != 0 && now == 0 && arm.runtime.callStack.isEmpty())
						rounds++;
					previous = now;
					if (arm.runtime.isHalted())
						halted = true;
				}
				for (String line : arm.logSnapshot())
					if (line.contains("跑完一轮"))
						loggedRounds++;
			}
		} catch (Throwable t) {
			LOGGER.error("[selftest] 主任务恒循环 自检异常：{}", t.toString());
		} finally {
			SequencedArmBlockEntity.pinLogLinesForTests(null); // 异常路径也不能把容量留给后面的夹具
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 主任务恒循环: 跑起来={} 见到第 2 条={} 回到第 1 条的轮数={} 日志里「跑完一轮」={} 期间停机={}",
			ran, sawSecondEntry, rounds, loggedRounds, halted);
		if (!ran)
			LOGGER.error("[selftest] 主任务恒循环 自检没跑起来 —— 等于没测");
		if (halted)
			LOGGER.error("[selftest] 主任务跑完一轮就**停机**了（用户口径：主任务是不断循环的）—— "
				+ "查 ProgramRuntime.finishTask 的主任务那一支是不是又走回 endTask/halt");
		if (!sawSecondEntry)
			LOGGER.error("[selftest] 主任务恒循环 的对照组不成立：80 拍里一次都没看见游标停在**第 2 条**"
				+ "（夹具的两条「等待 0t」根本没跑起来，下面的轮数断言就是测了空气）");
		if (rounds < 3 || loggedRounds < 3)
			LOGGER.error("[selftest] 主任务没有**恒循环**（回到第 1 条的轮数={}、日志里「跑完一轮」={}，都应当 ≥ 3）—— "
				+ "用户口径：任务循环次数不需要了，主任务走完最后一条就回到第 1 条永久重复；"
				+ "查 ProgramRuntime.finishTask 里 callStack 为空时那一支（它必须重置游标并 markProgramDirty，而不是 endTask）",
				rounds, loggedRounds);
	}

	/**
	 * 运行日志夹具：{@code [等待 1t × waits]}（{@code offset} 非空时再加一条「取出」）。
	 *
	 * <p>「等待」是**最确定**的产日志条目（不碰世界、不依赖交互点、一条必然产生一行）；
	 * 末尾那条「取出」指向**空的**置物台 ⇒ 前置检查必然失败 ⇒ 一定产出"失败原因"那一行。
	 */
	private static ArmProgram runLogProgram(String name, @Nullable BlockPos offset, int waits) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = name;
		for (int i = 0; i < waits; i++) {
			ArmProgramEntry wait = new ArmProgramEntry();
			wait.op = OperationType.WAIT;
			wait.waitAmount = 1;
			wait.waitUnit = ArmProgramEntry.WaitUnit.TICK;
			task.entries.add(wait);
		}
		if (offset != null) {
			ArmProgramEntry take = new ArmProgramEntry();
			take.op = OperationType.TAKE;
			take.offset = offset;
			task.entries.add(take);
		}
		program.tasks.add(task);
		return program;
	}

	/** 把一个剪贴板里的**所有页**摊成一条条目表（读回来的东西，断言用）。 */
	private static List<ClipboardEntry> flatClipboardEntries(ItemStack clipboard) {
		List<ClipboardEntry> flat = new ArrayList<>();
		for (List<ClipboardEntry> page : ClipboardEntry.readAll(clipboard))
			flat.addAll(page);
		return flat;
	}

	/** 在给定速率下问一个静态问题（自检专用：问完就恢复成常态钉速 {@link #OLD_PACE_RATE}，免得影响其它夹具）。 */
	private static int withRate(double rate, IntSupplier question) {
		ProgramRuntime.pinRateForTests(rate);
		try {
			return question.getAsInt();
		} finally {
			ProgramRuntime.pinRateForTests(OLD_PACE_RATE);
		}
	}

	/**
	 * 速率夹具：主任务 = [发信号（把 {@code freq} 置位）、取出 {@code offset}（置物台空着 ⇒ 前置检查必不过）]。
	 *
	 * <p>为什么用「发信号」当"无动作条目"：它不移动手臂、不等待，而且**效果能从外面看见**
	 * （`hasAnyLoadedPower`）—— 判据因此不依赖任何内部字段。第二条用「取出」是为了用
	 * "它在原地重试"当**终止标志**：游标停在下标 1 就是"两条都跑完了"。
	 */
	private static ArmProgram rateProbeProgram(Couple<Frequency> freq, BlockPos offset) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检速率";
		ArmProgramEntry send = new ArmProgramEntry();
		send.op = OperationType.SIGNAL;
		send.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		send.waitForPowered = true;
		send.frequency = freq;
		task.entries.add(send);
		ArmProgramEntry take = new ArmProgramEntry();
		take.op = OperationType.TAKE;
		take.offset = offset;
		task.entries.add(take);
		program.tasks.add(task);
		return program;
	}

	/**
	 * 速率夹具（有动作的那一支）：主任务 = [等待 {@code waitTicks} tick、发信号]。
	 *
	 * <p>「等待」必然进 {@code WAITING}（{@link ProgramRuntime#isPaced} 为真），而且它的时长
	 * 只由 tick 累加决定；后面那条「发信号」是"速率有没有把它带快"的证人，判据用**副作用**
	 * （{@code arm.holdsSignal(freq)}：臂的发射表里真的出现了这个频率），不靠内部状态猜。
	 */
	private static ArmProgram rateProbeWaitProgram(Couple<Frequency> freq, int waitTicks) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检速率等待";
		ArmProgramEntry wait = new ArmProgramEntry();
		wait.op = OperationType.WAIT;
		wait.waitAmount = waitTicks;
		wait.waitUnit = ArmProgramEntry.WaitUnit.TICK;
		task.entries.add(wait);
		ArmProgramEntry send = new ArmProgramEntry();
		send.op = OperationType.SIGNAL;
		send.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		send.waitForPowered = true;
		send.frequency = freq;
		task.entries.add(send);
		program.tasks.add(task);
		return program;
	}

	/**
	 * 「臂长」这条固有限制的自检（用户第十九轮："既然是固有限制……就不做处理吧"）。
	 *
	 * <p>不做任何用户可见的处理，但这条限制本身值得钉住 —— 它是我们**抄** Create 的两节 IK
	 * （{@link SequencedArmAngleTarget}）里最容易抄错、也最容易被误当成"动画 bug"的一处：
	 * 需求距离被 `clamp(..., 1/8, a + b)` 夹住（a = 14/16、b = 15/16 ⇒ 总长 1.8125 格），
	 * 超过它就只剩一个解 —— **完全伸直**。
	 *
	 * <p>断言方式：沿**同一条射线**（从臂原点出发，把 clawFacing 那 0.5 的偏移抵消掉）取三个点 ——
	 * 近处（1 格）必须与"伸直姿态"**不同**（说明真的会弯），而"恰好等于臂长"与"远到 5 格"两点，
	 * **两节臂的角度必须完全相同**（说明确实被夹住了，再远也不会更远）。
	 *
	 * <p>⚠️ 只比两节臂的角度、**不比头部**：头部角度是由 `clawTarget`（真实爪点）反算的，
	 * 所以目标越远头部越"够着看"—— 这正是"手臂拉直 + 头伸得怪"的完整由来，不是 bug。
	 * 若哪天有人把 clamp 去掉或改错比例，这条会立刻报出来。
	 */
	private static void checkArmReachClamp() {
		BlockPos armPos = BlockPos.ZERO;
		// 臂原点 = 方块中心 + 6/16（非吸顶）；IK 真正瞄准的点还会再沿 clawFacing 的反方向挪 0.5
		// （clawFacing 传 DOWN ⇒ 反方向是 UP），所以想让 IK 目标落在同一条射线上，点得先减掉那 0.5。
		Vec3 origin = new Vec3(0.5, 0.5 + 6 / 16d, 0.5);
		double reach = 14 / 16d + 15 / 16d;

		SequencedArmAngleTarget near = alongRay(armPos, origin, 1.0);
		SequencedArmAngleTarget atReach = alongRay(armPos, origin, reach);
		SequencedArmAngleTarget far = alongRay(armPos, origin, 5.0);

		boolean bendsWhenClose = !sameArmPose(near, atReach);
		boolean straightWhenFar = sameArmPose(atReach, far);

		LOGGER.info("[selftest] 臂长限制（固有限制，不做处理）: 臂长={} 近处会弯={} 超出后两节臂角度固定={}",
			String.format(java.util.Locale.ROOT, "%.2f", reach), bendsWhenClose, straightWhenFar);
		if (!bendsWhenClose)
			LOGGER.error("[selftest] 近处目标（1 格）居然和臂长尽头一样直 —— IK 的弯折解丢了，抄错了？");
		if (!straightWhenFar)
			LOGGER.error("[selftest] 超出臂长之后两节臂还在变（恰好臂长 vs 5 格不同）—— clamp 没了，手臂会越伸越长");
	}

	/** 沿臂原点出发的 +X 射线取一个爪点（抵消掉 clawFacing 的 0.5 偏移，让 IK 目标真的在同一条射线上）。 */
	private static SequencedArmAngleTarget alongRay(BlockPos armPos, Vec3 origin, double distance) {
		Vec3 point = origin.add(distance, 0, 0)
			.subtract(0, 0.5, 0); // clawFacing = DOWN ⇒ 反向偏移是 +UP×0.5
		return new SequencedArmAngleTarget(armPos, point, Direction.DOWN, false);
	}

	/** 两节臂 + 底座角度是否一致（头部不算，理由见 {@link #checkArmReachClamp()}）。 */
	private static boolean sameArmPose(SequencedArmAngleTarget a, SequencedArmAngleTarget b) {
		return Math.abs(a.baseAngle - b.baseAngle) < 1.0e-4
			&& Math.abs(a.lowerArmAngle - b.lowerArmAngle) < 1.0e-4
			&& Math.abs(a.upperArmAngle - b.upperArmAngle) < 1.0e-4;
	}

	/**
	 * 「臂的活动范围」判定的自检（用户第十九轮："动力臂对很远的无法触及的操作位置产生了动作反应，
	 * 是不是有些操作没有写距离判定？" —— 确实一条都没有，本轮补上）。
	 *
	 * <p>判据与 Create 添加交互点时用的**同一个**（`ArmBlockEntity.getRange()` = `mechanicalArmRange`，默认 5 格），
	 * 所以这里守住两件事：范围内的点必须放行、远到离谱的点必须拦下；以及"范围来自 Create 的配置"（不是我们另写一个数）。
	 */
	private static void checkArmRangeGate() {
		BlockPos armPos = new BlockPos(0, 64, 0);
		int range = ArmBlockEntity.getRange();
		boolean nearAllowed = ProgramRuntime.withinArmRange(armPos, armPos.offset(2, 0, 2));
		boolean edgeAllowed = ProgramRuntime.withinArmRange(armPos, armPos.offset(range - 1, 0, 0));
		boolean farBlocked = !ProgramRuntime.withinArmRange(armPos, armPos.offset(40, 0, 0));
		boolean diagBlocked = !ProgramRuntime.withinArmRange(armPos, armPos.offset(9, 0, 9));

		LOGGER.info("[selftest] 臂的范围判定: 范围={}格(来自Create配置) 近处放行={} 边界放行={} 远处拦下={} 斜向远处拦下={}",
			range, nearAllowed, edgeAllowed, farBlocked, diagBlocked);
		if (range < 1)
			LOGGER.error("[selftest] 臂范围配置读出来是 {} —— 判定会全拦，检查 ArmBlockEntity.getRange()", range);
		if (!nearAllowed || !edgeAllowed)
			LOGGER.error("[selftest] 范围内的目标被拦了（近处={} 边界={}）—— 距离判定写反了？", nearAllowed, edgeAllowed);
		if (!farBlocked || !diagBlocked)
			LOGGER.error("[selftest] 远处的目标没被拦下（40格={} 斜向9格={}）—— 这就是「远距离产生动作反应」的根因", farBlocked,
				diagBlocked);
	}

	/**
	 * 「等信号」的两个自检（用户第二十二轮实测反馈）。
	 *
	 * <p>① **极性**：用户报"无论如何都是取反 —— 收到信号时判定为失败（停住），没信号时反而继续执行"。
	 * 根因是查询侧自己异或了极性、调用侧又比一次（取反两次）。这条把真值表钉死：
	 * 等通电 + 有电 = 通过；等通电 + 没电 = 不通过；等断电反之。
	 *
	 * <p>② **两个频率槽都要占位**：用户定稿的卡片读法是「图标 · 等信号 · 信号物品 · 信号物品 · 开/关」，
	 * 而且"频率是可以填空的，不是必须两个物品" ⇒ {@link ArmIcons#signalPair} 必须**总是给回两个元素**
	 * （没绑就是两个空栈），这样空槽的位置才留得住。
	 */
	private static void checkWaitSemantics() {
		boolean polarity = ProgramRuntime.waitSatisfied(true, true) && !ProgramRuntime.waitSatisfied(false, true)
			&& ProgramRuntime.waitSatisfied(false, false) && !ProgramRuntime.waitSatisfied(true, false);

		ArmProgramEntry signal = new ArmProgramEntry();
		signal.op = OperationType.SIGNAL;
		Couple<Frequency> pair = Couple.create(() -> Frequency.EMPTY);
		pair.set(true, Frequency.of(new ItemStack(Items.REDSTONE_TORCH)));
		pair.set(false, Frequency.of(ItemStack.EMPTY)); // 频率允许有一格留空（用户明确要求）
		signal.frequency = pair;

		ItemStack[] slots = ArmIcons.signalPair(signal);
		boolean twoSlots = slots.length == 2;
		boolean firstKept = slots[0].getItem() == Items.REDSTONE_TORCH && slots[1].isEmpty();

		ArmProgramEntry unbound = new ArmProgramEntry();
		unbound.op = OperationType.SIGNAL;
		ItemStack[] empty = ArmIcons.signalPair(unbound);
		boolean unboundReserves = empty.length == 2 && empty[0].isEmpty() && empty[1].isEmpty();

		// 图标槽与频率槽是分开的：自定义图标只进图标槽，两个频率槽照样显示频率
		ArmProgramEntry custom = new ArmProgramEntry();
		custom.op = OperationType.SIGNAL;
		custom.frequency = pair;
		custom.icon = new ItemStack(Items.DIAMOND);
		boolean iconSeparate = ArmIcons.iconFor(custom)
			.getItem() == Items.DIAMOND && ArmIcons.signalPair(custom)[0].getItem() == Items.REDSTONE_TORCH;

		// ★ §7.16b 四 断言 2 的第六项：**缺 sigmode 的条目 = RECEIVE**（老卡没有这个键 ⇒ 行为一字不变）。
		// 这里用"只含注册表"的 Provider，纯模型、不需要世界（与客户端的做法一致）。
		HolderLookup.Provider bare = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ArmProgramEntry legacySignal = new ArmProgramEntry();
		legacySignal.op = OperationType.SIGNAL;
		legacySignal.frequency = pair;
		legacySignal.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		CompoundTag legacySignalTag = legacySignal.save(bare);
		legacySignalTag.remove("sigmode");
		boolean legacyDefaultReceive = ArmProgramEntry.load(legacySignalTag, bare).signalMode
			== ArmProgramEntry.SignalMode.RECEIVE;
		if (!legacyDefaultReceive)
			LOGGER.error("[selftest] 缺 sigmode 的条目没有落在 RECEIVE（老卡里那条「收」会变成「发」）");

		LOGGER.info("[selftest] 等信号: 极性真值表={} 频率两槽占位={} 空格保留={} 未绑也占位={} 图标与频率分开={} 缺sigmode=收={}",
			polarity, twoSlots, firstKept, unboundReserves, iconSeparate, legacyDefaultReceive);
		if (!polarity)
			LOGGER.error("[selftest] 「等信号」极性错了（又出现取反两次）：收到信号应当通过");
		if (!twoSlots || !firstKept || !unboundReserves)
			LOGGER.error("[selftest] 「等信号」的频率槽没有成对占位：两槽={} 空格保留={} 未绑也占位={}", twoSlots, firstKept,
				unboundReserves);
		if (!iconSeparate)
			LOGGER.error("[selftest] 自定义图标混进频率槽了（图标槽与两个频率槽必须各管各的）");
	}

	/**
	 * 「使用」打在**空气格**上时真的能把方块放进去吗（用户第二十二轮："使用也可以是放置方块"）。
	 *
	 * <p>为什么值得真跑一遍：运行时前置检查以前对 USE/USE_SNEAK 也要求"目标位置有方块"，
	 * 而这轮的修法只是**放开那条检查** —— 放置能否成立取决于原版机制
	 * （`BlockPlaceContext`：点到的位置**可以替换**时，`getClickedPos()` 就是那一格本身，空气正好满足），
	 * 以及我们 `RightClickBridge.use` 里那条 `stack.useOn(context)` 分支。
	 * 这条把三件事串起来验证：放在世界里的臂 + 假玩家 + 对空气格用一次 → 那一格变成石头。
	 */
	private static void checkUsePlacesOnAir(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		// 放在出生点保护半径之外（默认 16 格，且 MinecraftServer 只有 getter、改不了）
		BlockPos armPos = new BlockPos(spawn.getX() + 48, 200, spawn.getZ() + 48);
		BlockPos airPos = armPos.offset(2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldAir = level.getBlockState(airPos);
		boolean placed = false;
		String detail;
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(airPos, Blocks.AIR.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				arm.setHeldItem(new ItemStack(Blocks.STONE));
				RightClickBridge.Result result = arm.rightClick.use(airPos, Direction.UP, false);
				placed = level.getBlockState(airPos)
					.is(Blocks.STONE);
				detail = "result=" + result;
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(airPos, oldAir);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 使用→空气格放方块: 放上了={} （{}）", placed, detail);
		if (!placed)
			LOGGER.error("[selftest] 「使用」对着空气格没有放下方块（{}）—— 用户要的「使用也可以是放置方块」没成立", detail);
	}

	// ——————————————— §7.16b 四「服务端」的 8 条新断言（收发信号，3e-C） ———————————————

	/**
	 * 断言 1–6：收发信号的**行为**（§7.16b 四）。
	 *
	 * <p>为什么必须要（用户点名的那条边界）：发送是"臂在世界里按住一个红石链接频率"，
	 * 而 {@code hasAnyLoadedPower} **只读强度、完全不看 isAlive()** —— 停机 / 拆卡 / 区块卸载时
	 * 只要少调一次 {@code clearTransmissions()}，世界里就留下一个**永远通电**的频率，
	 * 而任何界面都看不出异常。位置与钩子照 {@link #checkUsePlacesOnAir}：真放一块臂、真驱动、finally 复原。
	 */
	private static void checkSignalSendReceive(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();

		// ——— 断言 1：老名迁移 + 缺省模式 ———
		boolean migrated = OperationType.byName("WAIT_SIGNAL", null) == OperationType.SIGNAL;
		boolean defaultReceive = new ArmProgramEntry().signalMode == ArmProgramEntry.SignalMode.RECEIVE;
		LOGGER.info("[selftest] 收发信号: 老名迁移={} 缺省模式={}", migrated, defaultReceive ? "收" : "发");
		if (!migrated)
			LOGGER.error("[selftest] 旧名字 WAIT_SIGNAL 没有迁到 SIGNAL（老卡里那条会变成 TAKE）");
		if (!defaultReceive)
			LOGGER.error("[selftest] 缺 sigmode 的条目没有落在 RECEIVE");

		// ——— 断言 2：NBT 往返 / 收信号零扰动 / 发信号时长 ———
		boolean modeRoundTrip = true;
		for (ArmProgramEntry.SignalMode mode : ArmProgramEntry.SignalMode.values()) {
			ArmProgramEntry e = new ArmProgramEntry();
			e.op = OperationType.SIGNAL;
			e.signalMode = mode;
			ArmProgramEntry back = ArmProgramEntry.load(e.save(registries), registries);
			if (back.signalMode != mode)
				modeRoundTrip = false;
		}
		ArmProgramEntry recvEntry = new ArmProgramEntry();
		recvEntry.op = OperationType.SIGNAL;
		recvEntry.waitForPowered = false;
		recvEntry.note = "老卡";
		CompoundTag recvTag = recvEntry.save(registries);
		boolean receiveClean = !recvTag.contains("sigmode");
		ArmProgramEntry sendEntry = new ArmProgramEntry();
		sendEntry.op = OperationType.SIGNAL;
		// ⚠️ 旧口径这里是 SEND_OFF（那时"发"= 收发合一的发）。本轮用户口径把「中断」定成
		//   **不接收持续时间**（立即清除该频率）⇒ 有持续时间的只剩「发送通电」，所以这条
		//   "时长往返"的载体换成 SEND_ON —— 断言本身（signalTicks() == 3）一个字没动。
		sendEntry.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		sendEntry.waitAmount = 3;
		boolean sendTicksRoundTrip = ArmProgramEntry.load(sendEntry.save(registries), registries)
			.signalTicks() == 3;
		// 缺 sigmode 的条目 = RECEIVE（老卡零扰动的前提）
		CompoundTag legacyTag = recvTag.copy();
		legacyTag.remove("sigmode");
		boolean legacyReceive = ArmProgramEntry.load(legacyTag, registries).signalMode
			== ArmProgramEntry.SignalMode.RECEIVE;

		LOGGER.info("[selftest] 收发信号 NBT: sigmode往返={} 收信号零扰动={} 发信号时长往返={}", modeRoundTrip,
			receiveClean, sendTicksRoundTrip);
		if (!modeRoundTrip)
			LOGGER.error("[selftest] sigmode 的 NBT 往返不稳定");
		if (!receiveClean)
			LOGGER.error("[selftest] 收信号条目被写进了 sigmode（老卡不再逐字节不变）");
		if (!sendTicksRoundTrip)
			LOGGER.error("[selftest] 发信号的持续时长没有往返稳定（send_on + waitAmount=3）");
		if (!legacyReceive)
			LOGGER.error("[selftest] 缺 sigmode 的条目没有落在 RECEIVE");

		// ——— 断言 3：接收回归（老卡的 redpow=false 照样等断电） ———
		boolean receiveRegression = ProgramRuntime.waitSatisfied(true, true)
			&& !ProgramRuntime.waitSatisfied(false, true) && ProgramRuntime.waitSatisfied(false, false)
			&& !ProgramRuntime.waitSatisfied(true, false);
		LOGGER.info("[selftest] 收发信号 接收回归: 老卡等断电仍生效={}", receiveRegression);
		if (!receiveRegression)
			LOGGER.error("[selftest] 「等断电」的老卡极性错了（接收也吃成了发送的语义）");

		// ——— 断言 4–6：行为（真放一块臂） ———
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 52, 201, spawn.getZ() + 52);
		BlockState oldArm = level.getBlockState(armPos);
		Couple<Frequency> f1 = testFrequency(Items.REDSTONE_TORCH, Items.IRON_INGOT);
		Couple<Frequency> f2 = testFrequency(Items.LEVER, Items.REDSTONE);
		Couple<Frequency> f3 = testFrequency(Items.STONE, Items.DIRT);
		String error = null;
		boolean powered = false;
		boolean offAfterHalt = false;
		boolean removedFromNetwork = false;
		boolean noPulseAfterClear = false;
		boolean oldFrequencyClean = false;
		boolean multiHold = false;
		boolean releaseOne = false;
		boolean cardChangeClears = false;
		boolean nbtRoundTrip = false;
		boolean capRejects17th = false;
		boolean capVisible = false;
		boolean capSkipped = false;
		int capProbedLimit = -1;
		boolean haltedTickPulse = false;
		boolean unloadUnpowered = false;
		boolean reloadRepowered = false;
		boolean unloadKeptFields = false;
		boolean signalsTabCaptured = false;
		CompoundTag signalsTab = new CompoundTag();
		try {
			// ★ 先补齐"脉冲计时器挂在所有早退之前"这条**没人守**的守卫（§7.16c 反证②：旧自检直接调
			// tickArmLinkTimers，从不走主循环 ⇒ 把计时器挪到 isHalted() 之后也照样全绿）。
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				error = "臂的方块实体没建出来（早退守卫）";
			} else {
				// 停机态 + 真走 runtime.tick()（主循环）：脉冲必须照常走完
				arm.runtime.halt(arm, "自检：停机态脉冲");
				arm.emitSignal(f1, 15, 2);
				boolean duringPulse = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1);
				arm.runtime.tick(arm);
				arm.runtime.tick(arm);
				boolean wentOff = !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1);
				haltedTickPulse = duringPulse && wentOff && arm.runtime.isHalted();
				if (!haltedTickPulse)
					LOGGER.error("[selftest] 停机态下脉冲没有靠 runtime.tick 走完 —— 计时器不在所有早退之前");
				arm.clearTransmissions();
			}

			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				error = "臂的方块实体没建出来";
			} else {
				// ④ 发通电 → 网络里有电；停机 → **仍然通电**（§7.16e 有意的反转）；两路并存
				arm.emitSignal(f1, 15, 0);
				powered = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1);
				arm.emitSignal(f2, 15, 0);
				multiHold = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1)
					&& Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f2)
					&& arm.heldSignalCount() == 2;
				arm.runtime.halt(arm, "自检");
				offAfterHalt = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1)
					&& Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f2);
				removedFromNetwork = arm.getArmLinkLevel() == 15;
				// ④b 「中断」只断指定那一路（§7.16e 规则 ①）
				boolean released = arm.releaseSignal(f1);
				releaseOne = released && !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1)
					&& !arm.holdsSignal(f1) && Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f2)
					&& arm.isTransmittingTo(f2);
				if (!releaseOne)
					LOGGER.error("[selftest] releaseSignal 没有只断指定那一路（要么 A 还通着，要么把 B 也断了）");

				// ⑤ NBT 往返（频率集合 + 电平 + 脉冲剩余）：**在清干净之前**存一份
				arm.emitSignal(f1, 15, 0); // A 回来，并且带一个未到期的脉冲
				arm.emitSignal(f3, 7, 5);
				signalsTab = arm.saveWithoutMetadata(registries);
				signalsTabCaptured = true;
				CompoundTag copy = signalsTab.copy();
				SequencedArmBlockEntity probe = new SequencedArmBlockEntity(SequencedArmRegistration.SEQUENCED_ARM_BE.get(),
					armPos.offset(1, 0, 0), SequencedArmRegistration.SEQUENCED_ARM.get()
						.defaultBlockState());
				probe.setLevel(level);
				probe.read(copy, registries, false);
				nbtRoundTrip = probe.heldSignalCount() == arm.heldSignalCount()
					&& probe.heldSignalCount() == 3 && probe.isTransmittingTo(f1) && probe.isTransmittingTo(f2)
					&& probe.isTransmittingTo(f3) && probe.getArmLinkLevel() == 15 && probe.holdsSignal(f3);
				if (!nbtRoundTrip)
					LOGGER.error("[selftest] 信号表的 NBT 往返不一致（频率集合/电平/脉冲剩余）");

				// ⑥ 配置上限：第 (maxHeldSignals+1) 个被拒，而且**状态可见**（不静默丢）
				// 上限来自配置项 `maxHeldSignals`（默认 64）—— 断言要**跟着配置走**，
				// 否则"把上限调大"会把这门自检变成假红。
				arm.clearTransmissions();
				int signalCap = SequencedArmBlockEntity.maxHeldSignals();
				capProbedLimit = signalCap;
				List<Couple<Frequency>> many = testFrequencies(signalCap + 1);
				if (many.size() <= signalCap) {
					// 频率池太小（配置被拉得很高时才会发生）：这一条**跳过**，不假装通过也不假红。
					capSkipped = true;
					capRejects17th = true;
					capVisible = true;
					LOGGER.warn("[selftest] 频率池只凑出 {} 组 < 配置上限 {} —— 信号上限这条断言本次跳过", many.size(),
						signalCap);
				} else {
					int accepted = 0;
					for (Couple<Frequency> freq : many) {
						arm.emitSignal(freq, 15, 0);
						if (arm.holdsSignal(freq))
							accepted++;
					}
					capRejects17th = accepted == signalCap && arm.heldSignalCount() == signalCap
						&& !arm.holdsSignal(many.get(signalCap));
					capVisible = arm.rejectedSignalCount() == signalCap;
					if (!capRejects17th)
						LOGGER.error("[selftest] 信号上限 {} 没生效（第 {} 个频率也被收下了）", signalCap, signalCap + 1);
					if (!capVisible)
						LOGGER.error("[selftest] 超上限被拒之后没有可见状态（护目镜与自检都看不见）");
				}
				arm.clearTransmissions();

				// ⑦ 操作卡变化 ⇒ 全部清零退网（§7.16e 的另一处"会断"）
				arm.emitSignal(f1, 15, 0);
				arm.emitSignal(f2, 15, 0);
				arm.setProgram(new ArmProgram());
				cardChangeClears = arm.heldSignalCount() == 0
					&& !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1)
					&& !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f2) && !arm.holdsSignal(f1);
				if (!cardChangeClears)
					LOGGER.error("[selftest] 换卡之后信号没有全断（setProgram 少了清零）");

				// ⑧ 区块卸载 / 重新加载（§7.16e：卸载只退网、字段与 NBT 都留着；加载自动重新入网）
				// 不真卸区块（那要世界重载），直接调钩子 + 真放一块**临时**臂当"重新加载"的目标。
				// ⚠️ 必须用**世界里真实存在**的方块实体：`updateNetworkOf` 会把 `isAlive()` 为假的成员
				// 从网络里剔掉（`isAlive` 要求 `level.getBlockEntity(pos) == this`），所以拿一个
				// `new` 出来、从没放进世界的 BE 去断言"重新入网"是**测试骗自己**（这一版第一稿就是这么错的，
				// 日志抓出来：`存档Signals=1 读回条数=1 含f2=true 电平=15` 但入网=false）。
				arm.clearTransmissions();
				arm.emitSignal(f2, 15, 0);
				CompoundTag beforeUnload = arm.saveWithoutMetadata(registries);
				arm.onChunkUnloaded();
				unloadUnpowered = !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f2);
				unloadKeptFields = arm.holdsSignal(f2) && arm.getArmLinkLevel() == 15;
				BlockPos reloadPos = armPos.offset(2, 0, 0);
				BlockState oldReload = level.getBlockState(reloadPos);
				level.setBlockAndUpdate(reloadPos, SequencedArmRegistration.SEQUENCED_ARM.get()
					.defaultBlockState());
				SequencedArmBlockEntity reloaded = level.getBlockEntity(reloadPos) instanceof SequencedArmBlockEntity be
					? be : null;
				if (reloaded == null)
					LOGGER.error("[selftest] 放不下用来验证「重新加载」的临时臂");
				else
					reloaded.read(beforeUnload.copy(), registries, false);
				reloadRepowered = reloaded != null && reloaded.heldSignalCount() == 1
					&& Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f2);
				if (!unloadUnpowered)
					LOGGER.error("[selftest] 区块卸载之后那个频率还通着（应当退网，否则是幽灵常通频率）");
				if (!unloadKeptFields)
					LOGGER.error("[selftest] 区块卸载把电平/信号表清掉了（用户要求保留字段与 NBT）");
				if (!reloadRepowered)
					LOGGER.error("[selftest] 重新加载之后没有自动重新入网（信号恢复不了）");
				if (reloaded != null)
					reloaded.clearTransmissions();
				level.setBlockAndUpdate(reloadPos, oldReload); // 清掉临时臂（它会被正常移除并退网）

				// ⑨ 旧断言 ⑥ 的替代：两路 → 全清 → **两路都不残留**（表空 + 都没电）
				arm.clearTransmissions();
				arm.emitSignal(f1, 15, 0);
				arm.emitSignal(f2, 15, 0);
				arm.clearTransmissions();
				oldFrequencyClean = !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(f1)
					&& !arm.isTransmittingTo(f1) && !arm.holdsSignal(f1);
				boolean keyReleased = arm.currentArmLinkKey() == null;
				noPulseAfterClear = !arm.runtime.tickArmLinkTimers(arm) && arm.getArmLinkLevel() == 0;
				if (!keyReleased)
					LOGGER.error("[selftest] 清干净之后表里还留着频率（应当一起清掉）");
			}
		} catch (Throwable t) {
			error = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}
		if (error != null)
			LOGGER.error("[selftest] 收发信号 行为自检异常：{}", error);

		// 脉冲那条旧判据（"到点自动恢复成发之前的电平"）在新设计里没有意义：到点就是**归 0**
		//（"保持"是常态）。这里保留"停机态真走主循环"的结论当那条断言的替身。
		LOGGER.info("[selftest] 收发信号 发送: 发通电→通电={} 停机→仍通电={} 两路并存={} 电平还在={}", powered,
			offAfterHalt, multiHold, removedFromNetwork);
		if (!powered)
			LOGGER.error("[selftest] 发出去的频率在网络里没有电（emitSignal 没接上红石链接网络）");
		if (!offAfterHalt)
			LOGGER.error("[selftest] 停机后信号断了 —— §7.16e 已把这条旧行为【反转】：停机必须仍通电");
		if (!multiHold)
			LOGGER.error("[selftest] 两个频率不能同时通电（多路并存没成立）");
		if (!removedFromNetwork)
			LOGGER.error("[selftest] 停机把电平也清了（停机只该不断，不该动信号）");

		LOGGER.info("[selftest] 收发信号 中断: 只断指定那一路={}", releaseOne);
		LOGGER.info("[selftest] 收发信号 脉冲: 停机态走主循环到点归零={} 清干净后无残留={}", haltedTickPulse,
			noPulseAfterClear);
		if (!noPulseAfterClear)
			LOGGER.error("[selftest] 收干净之后脉冲计时器/电平还在（应当清成 -1 与 0）");

		LOGGER.info("[selftest] 收发信号 换卡: 全断={} 老频率不残留={}", cardChangeClears, oldFrequencyClean);
		if (!oldFrequencyClean)
			LOGGER.error("[selftest] 清干净之后老频率仍然通电（clearTransmissions 少了一步）");

		String capNote = capSkipped ? "上限断言=跳过（频率池不足）" : "上限断言=已实测";
		LOGGER.info("[selftest] 收发信号 NBT: 往返={} 配置上限={} 拒绝第{}个={} 拒绝可见={} {}", nbtRoundTrip,
			capProbedLimit, capProbedLimit + 1, capRejects17th, capVisible, capNote);
		LOGGER.info("[selftest] 收发信号 区块: 卸载退网={} 字段保留={} 重载自动入网={}", unloadUnpowered,
			unloadKeptFields, reloadRepowered);
		if (!signalsTabCaptured)
			LOGGER.error("[selftest] 没能抓到信号表的 NBT 快照（NBT 往返这条断言等于没跑）");
	}

	/**
	 * 自检用的 {@code count} 组互不相同的频率（两两配对物品注册表里的物品）。
	 *
	 * <p>用注册表而不是写死组数：物品名单会随版本变，写死迟早编译不过；也不用 {@code Items.AIR}
	 * （它会让 {@code Frequency} 变成 EMPTY，那样就没有"不同频率"可言了）。
	 */
	private static List<Couple<Frequency>> testFrequencies(int count) {
		List<Item> pool = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM)
			if (item != Items.AIR)
				pool.add(item);
		List<Couple<Frequency>> out = new ArrayList<>();
		// 第 i 组 = (pool[i], pool[i+count])：**间隔 count** 保证任意两组的集合都不相同
		//（相邻取 item[i]/item[i+1] 时，(a,b) 与 (b,a) 会撞成同一个频率对 —— 那样信号上限那条断言会假红）。
		if (pool.size() < count * 2)
			return out;
		for (int i = 0; i < count; i++)
			out.add(testFrequency(pool.get(i), pool.get(i + count)));
		return out;
	}

	/** 自检用的频率对（两个物品 = Create 红石链接的一对频率）。 */
	private static Couple<Frequency> testFrequency(Item first, Item second) {
		Couple<Frequency> freq = Couple.create(() -> Frequency.EMPTY);
		freq.set(true, Frequency.of(new ItemStack(first)));
		freq.set(false, Frequency.of(new ItemStack(second)));
		return freq;
	}

	/**
	 * 断言 7：失败出口谓词**按条目**判（§7.16b 四 断言 7 / 决策 73）。
	 *
	 * <p>同一条 {@code SIGNAL}：收 = 有重试窗口（等一下条件就变）；发 = **没有失败出口**
	 * （发送永远成功、也不阻塞）—— 写成"按类型"就会在发的卡片上冒出一个**永不触发**的「失败后」。
	 *
	 * <p>★ 「过滤」按用户口径也在"有重试窗口"那一类（控制类判断、**失败默认 retry**）——
	 * 这里顺带把**新建条目的默认值**也钉住：{@code failAmount = -1} 就是"一直重试"，
	 * 编辑器因此会给它「时限/单位」两个控件（判据同源 {@link ArmProgramEntry#hasRetryWindow()}）。
	 * 反证：把 {@code hasRetryWindow()} 里 FILTER 那一支删掉 ⇒ filterRetry 变 false ⇒ 这条红。
	 */
	private static void checkEntryPredicates() {
		ArmProgramEntry recv = new ArmProgramEntry();
		recv.op = OperationType.SIGNAL;
		ArmProgramEntry send = new ArmProgramEntry();
		send.op = OperationType.SIGNAL;
		send.signalMode = ArmProgramEntry.SignalMode.SEND_ON;

		boolean recvHasFailure = recv.hasRetryWindow() && recv.hasFailureExit();
		boolean sendHasNone = !send.hasRetryWindow() && !send.hasFailureExit() && !send.hasHardFailureExit()
			&& !send.isSignalReceive();
		// 挖 / 调用属于"有失败出口、没有重试窗口"（§7.7：失败是立刻有结论的）
		ArmProgramEntry mine = new ArmProgramEntry();
		mine.op = OperationType.MINE;
		ArmProgramEntry call = new ArmProgramEntry();
		call.op = OperationType.CALL;
		boolean hardOnly = !mine.hasRetryWindow() && mine.hasHardFailureExit() && !call.hasRetryWindow()
			&& call.hasHardFailureExit();
		// 过滤：新建条目就落在"会重试"这一类，默认窗口 = 一直重试（-1）
		ArmProgramEntry filterHeld = new ArmProgramEntry();
		filterHeld.op = OperationType.FILTER;
		ArmProgramEntry filterContainer = new ArmProgramEntry();
		filterContainer.op = OperationType.FILTER;
		filterContainer.filterSource = ArmProgramEntry.FilterSource.CONTAINER;
		boolean filterRetry = filterHeld.hasRetryWindow() && filterHeld.hasFailureExit()
			&& !filterHeld.hasHardFailureExit() && filterHeld.failAmount == -1
			&& filterContainer.hasRetryWindow() && filterContainer.failAmount == -1;
		// 两处判据各问各的：过滤**不移动**（类型级），但容器模式**有坐标**（条目级）
		boolean filterTwoQuestions = !filterHeld.movesArm() && !filterHeld.needsTarget() && !filterContainer.movesArm()
			&& filterContainer.needsTarget();

		LOGGER.info("[selftest] 失败出口谓词: 收有失败={} 发无失败={} 过滤会重试={} 过滤两问分开={}", recvHasFailure,
			!sendHasNone, filterRetry, filterTwoQuestions);
		if (!recvHasFailure)
			LOGGER.error("[selftest] 收信号说自己没有重试窗口（编辑器不会给时限控件）");
		if (!sendHasNone)
			LOGGER.error("[selftest] 发信号也说自己有重试窗口（卡片上会冒出永不触发的「失败后」）");
		if (!hardOnly)
			LOGGER.error("[selftest] 挖 / 调用的硬失败谓词错了（应当有失败出口、没有重试窗口）");
		if (!filterRetry)
			LOGGER.error("[selftest] 过滤不在「会重试」那一类（或者默认值不是「一直重试」）—— 用户口径："
				+ "过滤是控制类操作，失败默认 retry；编辑器该给它「时限/单位」控件");
		if (!filterTwoQuestions)
			LOGGER.error("[selftest] 过滤的两处判据混了（应当：不移动手臂 + 容器模式有坐标）—— "
				+ "movesArm() 是类型级、needsTarget() 是条目级，删掉任何一个都会让臂先飞过去或让坐标框消失");

		// ★ 「接收距离」的**单点判据**（用户口径：距离只约束「收」；0 = 只能自己接收发送）：
		//   ① 收 + 0 ⇒ 没有（默认不占胶囊位、脚本也不写它）；
		//   ② 收 + 16 ⇒ 有；③ 发 / 中断 + 16 ⇒ **没有**（发送侧不读它）；
		//   ④ NBT：默认 0 不写键（老卡零扰动）、写了的能往返、超范围的读回来被夹进 0..256。
		//   反证：把 hasSignalRange() 里的 isSignalReceive() 去掉 ⇒ ③ 红；把 clampSignalRange 里的
		//   Math.min 去掉 ⇒ ④ 的"夹进 256"红。
		ArmProgramEntry recvZero = new ArmProgramEntry();
		recvZero.op = OperationType.SIGNAL;
		ArmProgramEntry recvSome = new ArmProgramEntry();
		recvSome.op = OperationType.SIGNAL;
		recvSome.signalRange = 16;
		ArmProgramEntry sendSome = new ArmProgramEntry();
		sendSome.op = OperationType.SIGNAL;
		sendSome.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		sendSome.signalRange = 16;
		boolean rangePredicate = !recvZero.hasSignalRange() && recvSome.hasSignalRange() && !sendSome.hasSignalRange();
		HolderLookup.Provider bare = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		CompoundTag zeroTag = recvZero.save(bare);
		boolean rangeZeroNotWritten = !zeroTag.contains("range");
		CompoundTag rangeTag = recvSome.save(bare);
		boolean rangeRoundTrip = rangeTag.contains("range")
			&& ArmProgramEntry.load(rangeTag, bare).signalRange == 16;
		CompoundTag overTag = rangeTag.copy();
		overTag.putInt("range", 9999);
		boolean rangeClamped = ArmProgramEntry.load(overTag, bare).signalRange
			== ArmProgramEntry.SIGNAL_RANGE_MAX;
		LOGGER.info("[selftest] 接收距离判据: 收+0={} 收+16={} 发+16={} 默认不写键={} 往返={} 超范围夹取={}",
			recvZero.hasSignalRange(), recvSome.hasSignalRange(), sendSome.hasSignalRange(), rangeZeroNotWritten,
			rangeRoundTrip, rangeClamped);
		if (!rangePredicate)
			LOGGER.error("[selftest] hasSignalRange() 判据不对（应当：收 + 半径>0 才算有；发 / 中断恒无）");
		if (!rangeZeroNotWritten)
			LOGGER.error("[selftest] 接收距离默认 0 也写了 range 键（老卡存取前后不再逐字节不变）");
		if (!rangeRoundTrip)
			LOGGER.error("[selftest] 接收距离过不了 NBT 往返（存 16 读回来不是 16）");
		if (!rangeClamped)
			LOGGER.error("[selftest] 接收距离超范围时没有被夹进 0.." + ArmProgramEntry.SIGNAL_RANGE_MAX);
	}

	/**
	 * 断言 8：新增的预设 {@code signal_send} —— 两条发信号都**必须绑频率**、模式一收零发（这里是两发）。
	 *
	 * <p>预设是自检的输入，也是"用户给的用法"的文档化：发通电（保持）→ 等 1 秒 → 发断电。
	 */
	private static void checkSignalSendPreset() {
		ArmProgram program = DevPresets.signalSend();
		int receives = 0;
		int sends = 0;
		int entries = 0;
		boolean bound = true;
		boolean hold = false;
		boolean releaseOff = false;
		for (ArmProgramTask task : program.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				entries++;
				if (entry.op != OperationType.SIGNAL)
					continue;
				if (entry.frequency == null)
					bound = false;
				if (entry.isSignalReceive())
					receives++;
				else {
					sends++;
					if (entry.signalMode == ArmProgramEntry.SignalMode.SEND_ON && entry.waitForPowered
						&& entry.signalTicks() == 0)
						hold = true;
					if (entry.signalMode == ArmProgramEntry.SignalMode.SEND_OFF && !entry.waitForPowered)
						releaseOff = true;
				}
			}
		}
		LOGGER.info("[selftest] 预设 signal_send tasks={} entries={} 发={} 收={} 绑频率={} 持段={} 断电={}",
			program.tasks.size(), entries, sends, receives, bound, hold, releaseOff);
		if (program.tasks.size() != 1 || entries != 3 || sends != 2 || receives != 0)
			LOGGER.error("[selftest] 预设 signal_send 的结构不对（期望 1 任务 3 条、两发零收）");
		if (!bound)
			LOGGER.error("[selftest] 预设 signal_send 的发信号没有绑频率（永远发不出去）");
		if (!hold)
			LOGGER.error("[selftest] 预设 signal_send 的第一条不是「发通电 + 一直保持」");
		if (!releaseOff)
			LOGGER.error("[selftest] 预设 signal_send 的最后一条不是「发断电」（电平 0 是合法的发送值）");
	}

	/**
	 * 断言：「<b>中断</b>」（{@code SEND_OFF}）<b>没有持续时间</b> —— 用户本轮口径：
	 * "关于收发信号，『中断』应该是立即把信号清除，无论是否无限或有剩余时间，所以应该不接收持续时间的参数。"
	 *
	 * <p>这条是**文本面 + 运行时**两侧一起钉。判据只有一个：{@link ArmProgramEntry#hasSignalDuration()}
	 * （只有 {@code SEND_ON} 有持续时间），消费方是编辑器（数值/单位两个控件）、
	 * {@code ScriptDecompiler}（{@code place=}）、{@code ScriptParser}（写 {@code place=} 报错）、
	 * {@code ArmEntryText}（标题尾巴）。别对着"是不是发"（{@code isSignalSend()}）判 ——
	 * 那个把中断也算成发，于是界面给控件、脚本要参数，而运行时**一个字都不读**。
	 *
	 * <p>③ 那段的夹具（用户口径：中断 = <b>当场清除</b>，无论是否无限或还有剩余时间）必须自己
	 * 造出前提并观察到副作用，否则就是"测了空气"：<b>先</b>{@code setProgram} + {@code resetToStart}
	 * （两者都会 {@code clearTransmissions()}）<b>再</b>发那个 {@code 15} 电平的"一直保持"，
	 * 否则到「中断」执行时表里本来就是空的；"执行过"看 {@code EXECUTING} + 动手前频率仍在保持
	 * （**不能**看 {@code entryIndex}：单条目任务成功后会立刻 {@code finishTask} 把它重置回 0）。
	 *
	 * <p>反证（改哪一行会变红）：
	 * <ul>
	 *   <li>把 {@link ArmProgramEntry#hasSignalDuration()} 改成 {@code isSignalSend()}
	 *       ⇒ ① 立刻红（反编译又写出 {@code send_off place=…}），② 也红（解析器收下 {@code place=} 不报错），
	 *       ④ 第一条红（接收模式也变成"有持续时间"）；</li>
	 *   <li>把 {@code ScriptDecompiler} 那支的 {@code else if (entry.hasSignalDuration())} 改回
	 *       {@code else} ⇒ 只有 ① 红；把 {@code ScriptParser} 的两条模式判断删掉 ⇒ 只有 ② 红；</li>
	 *   <li>把 {@code ArmProgramScreen} 的第二行判断改回 {@code entries.isSignalSend()} ⇒
	 *       {@code signalRowProblem()} 的模式盒子数那条红（中断 1 ≠ 3）；</li>
	 *   <li>把 {@code ProgramRuntime} 的 SIGNAL 分支中"中断"那支改成
	 *       {@code emitSignal(entry.frequency, 0, entry.signalTicks())}（= 变成"发个 0 电平的脉冲"）
	 *       ⇒ ③ 红：那一刻频率仍在表里 / 脉冲没被取消；</li>
	 *   <li>把 {@code releaseSignal} 里的 {@code heldSignals.remove(freq)} 删掉 ⇒ ③ 红
	 *       （{@code holdsSignal} 仍真）；把 {@code link.leaveNetwork(false)} 删掉 ⇒ ③ 红（电平还在 15）。</li>
	 * </ul>
	 */
	private static void checkSignalCutHasNoDuration(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();

		// ——— ① 反编译：中断条目**不含** place=；发送通电照旧写（对照） ———
		ArmProgram probeProgram = new ArmProgram();
		ArmProgramTask probeTask = new ArmProgramTask();
		probeTask.name = "main";
		ArmProgramEntry probeCut = new ArmProgramEntry();
		probeCut.op = OperationType.SIGNAL;
		probeCut.signalMode = ArmProgramEntry.SignalMode.SEND_OFF;
		probeCut.waitForPowered = false;
		probeCut.frequency = testFrequency(Items.REDSTONE_TORCH, Items.IRON_INGOT);
		// 故意留一个"旧口径的残留值"：老卡 / 预设里那条中断就会带着它，
		// 反编译器必须**照样一个字都不写**（写了它，玩家会以为"5 秒后才断"）
		probeCut.waitAmount = 5;
		probeCut.waitUnit = ArmProgramEntry.WaitUnit.SECOND;
		ArmProgramEntry probeSend = new ArmProgramEntry();
		probeSend.op = OperationType.SIGNAL;
		probeSend.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
		probeSend.frequency = testFrequency(Items.REDSTONE_TORCH, Items.IRON_INGOT);
		probeSend.waitAmount = 0; // 0 = 一直保持：规范形式仍然要写出来（0t 与"没写"是两件事）
		probeTask.entries.add(probeCut);
		probeTask.entries.add(probeSend);
		probeProgram.tasks.add(probeTask);
		String text = ScriptCompiler.decompile(probeProgram, registries);
		// ⚠️ 程序里只有一个任务、两条条目 ⇒ 反编译结果正好三行：「task main」+ 两条条目（一行一条）。
		//   跳过头行、按顺序取前两条，而不是拿整份文本 contains：`place=` 也出现在**发送**那一行上，
		//   笼统地 contains 会把"中断写了 place="与"发送写了 place="混成一件事。
		String firstEntryLine = "";
		String secondEntryLine = "";
		for (String line : text.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("task"))
				continue; // 只要条目行，跳过任务头
			if (trimmed.isEmpty())
				continue;
			if (firstEntryLine.isEmpty())
				firstEntryLine = trimmed;
			else if (secondEntryLine.isEmpty())
				secondEntryLine = trimmed;
		}
		boolean cutNoPlace = firstEntryLine.contains("send_off") && !firstEntryLine.contains("place=");
		boolean sendOnKeepsPlace = secondEntryLine.contains("send_on") && secondEntryLine.contains("place=");
		LOGGER.info("[selftest] 中断没有持续时间 反编译: 第1条=[{}] 第2条=[{}]", firstEntryLine, secondEntryLine);
		if (!cutNoPlace)
			LOGGER.error("[selftest] 中断条目反编译出了 place=（旧口径：中断被当成发，写了一个运行时根本不读的时长）");
		if (!sendOnKeepsPlace)
			LOGGER.error("[selftest] 发送通电条目没有写出 place=（0t = 一直保持必须写得出来，否则与「没写」分不清）");

		// ——— ② 手写 place= 必须**明确报错**（中断 / 接收各一条），而 send_on 照旧收 ———
		boolean cutPlaceRejected = reportsAt(registries,
			"task main\n  signal send_off place=5s freq=redstone_torch + iron_ingot\n", 2, "中断");
		boolean recvPlaceRejected = reportsAt(registries,
			"task main\n  signal recv on place=5s freq=redstone_torch + iron_ingot\n", 2, "接收");
		ScriptResult<ArmProgram> sendOk = ScriptCompiler.compile(
			"task main\n  signal send_on place=5s freq=redstone_torch + iron_ingot\n", registries);
		LOGGER.info("[selftest] 中断没有持续时间 脚本: 中断+place 报错={} 接收+place 报错={} send_on+place 编译过={}", cutPlaceRejected,
			recvPlaceRejected, !sendOk.hasErrors());
		if (!cutPlaceRejected)
			LOGGER.error("[selftest] 手写的「中断 + place=5s」没有报错（静默收下会让玩家以为 5 秒后才断）");
		if (!recvPlaceRejected)
			LOGGER.error("[selftest] 手写的「接收 + place=5s」没有报错（接收只是等通电，没有持续时间）");
		if (sendOk.hasErrors())
			LOGGER.error("[selftest] send_on + place=5s 反而编译不过了：{}", ScriptCompiler.errorLines(sendOk.errors()));

		// ——— ④ 判据本身：只有「发送通电」有持续时间（声明进来是为了让编译器盯着这个变量：
		//     它一旦无人读，说明下面的断言被谁删掉了） ———
		boolean predicateCorrect = !probeCut.hasSignalDuration()
			&& !new ArmProgramEntry().hasSignalDuration() // 缺省 = RECEIVE
			&& probeSend.hasSignalDuration();
		LOGGER.info("[selftest] 中断没有持续时间 判据: 中断={} 接收={} 发送={}", probeCut.hasSignalDuration(),
			new ArmProgramEntry().hasSignalDuration(), probeSend.hasSignalDuration());
		if (!predicateCorrect)
			LOGGER.error("[selftest] hasSignalDuration() 判据不对（中断 / 接收不该有持续时间，只有发送有）");

		// ——— ③ 运行时：先给该频率一个"长期保持"的脉冲，再跑一条「中断」⇒ 当场清除 ———
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		// 位置与 checkSignalSendReceive 用的是同一块空地（不重叠：那一块用 +52..+2，这条用 +64）
		BlockPos armPos = new BlockPos(spawn.getX() + 64, 201, spawn.getZ() + 58);
		BlockState oldArm = level.getBlockState(armPos);
		Couple<Frequency> freq = testFrequency(Items.CLAY_BALL, Items.BRICK);
		boolean cutEntryRan = false;
		boolean pulseCancelled = false;
		boolean armPlaced = false;
		boolean stillHeldAfterHold = false;
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				armPlaced = false;
			} else {
				armPlaced = true;
				ArmProgram program = new ArmProgram();
				ArmProgramTask task = new ArmProgramTask();
				task.name = "cut";
				ArmProgramEntry cut = new ArmProgramEntry();
				cut.op = OperationType.SIGNAL;
				cut.signalMode = ArmProgramEntry.SignalMode.SEND_OFF;
				cut.waitForPowered = false;
				cut.frequency = freq;
				task.entries.add(cut);
				program.tasks.add(task);
				// ⚠️ **装卡必须在"发保持"之前**：`setProgram` 与 `resetToStart` 都会
				//   `clearTransmissions()`（换卡 / 回到开头都要把发出去的频率收干净，§7.16e）。
				//   旧夹具把 `emitSignal` 写在它们之前 ⇒ 那条「中断」执行时表里早已是空的，
				//   "当场清除"看起来通过，其实是在清一个从来没装进去过的信号（测了空气）。
				arm.setProgram(program);
				arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过（与原版一致）
				arm.runtime.resetToStart(arm);
				arm.emitSignal(freq, 15, 0); // 15 = 通电、ticks=0 = "一直保持"（没有剩余时间可以到期）
				// 前提（现场对照，缺一不可）：服务端世界 / 没撞 `maxHeldSignals` / 真的进网且有电 / 确实是"保持"。
				boolean timerFired = arm.runtime.tickArmLinkTimers(arm);
				stillHeldAfterHold = !level.isClientSide
					&& arm.heldSignalCount() == 1 // 没到上限：表里就这一条（被上限拒的话这里是 0）
					&& arm.rejectedSignalCount() == 0 // 没被 `maxHeldSignals` 静默拒掉
					&& arm.isTransmittingTo(freq) // `addToNetwork` + `updateNetworkOf` 都走到了：表里有它且电平 > 0
					&& arm.holdsSignal(freq) && arm.getArmLinkLevel() == 15
					&& Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(freq) // 网络表那格真有电（isAlive ⇒ 世界里的 BE）
					&& !timerFired; // 「保持」= pulse 为 -1、**没有**"到点归零"可推
				//   ↑ 旧夹具在这里写反了（要求它返回 true = "有一路到点归零"）：ticks=0 的保持恰恰没有
				//     到点可推 ⇒ 这条对照恒假、整条前提恒不成立，"当场清除"于是又变成测空气。现场证据：
				//     日志里 `保持成立（相位对照）=false`。
				LOGGER.info("[selftest] 中断没有持续时间 运行时前提: 服务端={} 表里条数={} 被上限拒={} 表里有它={} "
					+ "电平={} 网络有电={} 计时器到点={}", !level.isClientSide, arm.heldSignalCount(), arm.rejectedSignalCount(),
					arm.holdsSignal(freq), arm.getArmLinkLevel(),
					Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(freq), timerFired);
				// ★ "那条「中断」条目被执行了"只能看**副作用**，不能看游标（`entryIndex`）：
				//   ① 它是控制类条目 —— 第 1 拍 `beginEntry` 只把它置成 `EXECUTING`（同一拍不动作），
				//      第 2 拍 `execute` 才 `releaseSignal` + `succeed`；
				//   ② `succeed` 之后 `finishTask` 会把 `entryIndex` **重置成 0**（单条目任务必然收工）
				//      ⇒ "游标越过 0 号"在这个夹具里**永远不可能为真**（旧夹具 `entryIndex == 1` 就是这么恒红的）。
				//   所以判据 = 看见过 `EXECUTING` + 它动手**前**那个频率确实还在保持（前后对照）。
				//   没有这一条，"表是空的"可能只是"根本没人发过信号"—— 那是决策 85 里"测了空气"的那类假绿。
				boolean sawExecuting = false;
				boolean heldWhileExecuting = false;
				for (int i = 0; i < 8; i++) {
					arm.runtime.tick(arm);
					if (arm.runtime.state != ProgramRuntime.EntryState.EXECUTING)
						continue;
					sawExecuting = true;
					heldWhileExecuting = arm.holdsSignal(freq) && arm.getArmLinkLevel() == 15
						&& Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(freq);
					arm.runtime.tick(arm); // 主循环这一拍才是 execute → releaseSignal + succeed
					break;
				}
				cutEntryRan = sawExecuting && heldWhileExecuting;
				// 三件事一起查（用户口径的核心）：① 表里**没有**这个频率了（不是"留着一个强度 0 的成员"）
				// ② 网络里电平为 0（leaveNetwork 先归 0 再退网，少一步就留一个"永远通电"的频率）
				// ③ 臂的发射电平也回 0，且**未到期的脉冲计时器没有东西可推**了（脉冲被取消）。
				// ⚠️ 不用 `!isTransmittingTo(freq)`：它在"表里没有这条"时**也是 false**，
				//   拿它当断言等于把"已经清掉"与"根本没有过"混成一件事（测了空气）。
				pulseCancelled = !arm.holdsSignal(freq) && arm.heldSignalCount() == 0
					&& !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(freq)
					&& arm.getArmLinkLevel() == 0 && !arm.runtime.tickArmLinkTimers(arm);
			}
		} catch (Throwable t) {
			LOGGER.error("[selftest] 中断立即清除 行为自检异常：{}", t.toString());
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}
		LOGGER.info("[selftest] 中断没有持续时间 运行时: 臂放好={} 保持成立（相位对照）={} 中断条目被执行={} 中断当场清除={}", armPlaced,
			stillHeldAfterHold, cutEntryRan, pulseCancelled);
		if (!armPlaced)
			LOGGER.error("[selftest] 中断立即清除 这条断言的前提不成立：臂的方块实体没建出来");
		if (!stillHeldAfterHold)
			LOGGER.error("[selftest] 反转前的对照组不对：发出去的 15 电平 + 长期保持没有成立（后面的\"当场清除\"就说明不了问题）");
		if (!cutEntryRan)
			LOGGER.error("[selftest] 那条「中断」条目没有被执行（没看见它进 EXECUTING，或它动手前那个频率就已经不在保持里 —— "
				+ "后面的\"当场清除\"就是测了空气）");
		if (!pulseCancelled)
			LOGGER.error(
				"[selftest] 「中断」没有立即清除该频率（用户口径：无论是否无限或有剩余时间，当场电平 0 + 退网 + 取消未到期脉冲）");
	}

	// ——————————————— 底座 base_powered 的判据（用户第十九轮反馈，本轮收紧） ———————————————

	/**
	 * 断言 ①–④：客户端该画 {@code base} 还是 {@code base_powered} ——
	 * {@code SequencedArmBlockEntity.isSignalActive()} 的完整口径。
	 *
	 * <p><b>用户口径（本轮原话）</b>：「base_powered 的模型接收信号发光的时候<b>必须成功接收信号</b>才发光，
	 * <b>尝试接收但是没有就不会发光</b>」。旧口径把"正在等"和"被相邻红石点亮"
	 * 也算成"收到" ⇒ "一直在等、一直没信号"的臂**常亮** —— 那正是这次反馈的现象，两条旧判据已删。
	 *
	 * <p><b>用户口径</b>：「这个只与无线信号有关，与接收红石信号无关。」
	 * 于是"按相邻红石等待"那条操作<b>整条删除</b>（用户后来的话：「我现在倾向于移除这个操作，
	 * 因为我们有更好的无线信号系统可以使用」）⇒ 原来那条断言 ⑤（那条操作成功不亮，两条极性各一条）
	 * 与被测对象一起消失：**那条操作没有了，也就不存在"它成功时亮不亮"这回事**。
	 * ① 里"相邻红石没通电时不亮"那半段同理删除（那个夹具用的是已删的枚举常量，段不成立）；
	 * ① 的"收信号没通电时不亮"半段与本段的其余部分**一个字都没放松**。
	 *
	 * <p><b>每条断言"改哪一行会变红"</b>：
	 * <ol>
	 *   <li>{@code 正在等但没信号 ⇒ false}（用户这次反馈的字面场景）：把"正在等"那条判据加回
	 *       {@code computeSignalActive()}（{@code runtime.state == WAITING} 且当前条目是收信号），
	 *       或者在"开始等"时就调 {@code flashSignalReceived()} ⇒ 本次日志里 ① 的
	 *       {@code 亮=} 变 true、{@code 不亮成立=} 变 false；</li>
	 *   <li>{@code 真的收到 ⇒ 亮，约 10 tick 后灭}：删掉 {@code ProgramRuntime.tickWait} 里那行
	 *       {@code arm.flashSignalReceived();} ⇒ {@code ② 收到即亮} 变 false；把
	 *       {@code SIGNAL_FLASH_TICKS} 改到 8 以外（如 1）⇒ ② 的 {@code 亮了几拍} 越界变红；
	 *       删掉 {@code tickSignalActive()} 里的 {@code signalFlashTicks--} ⇒ 一直不灭，
	 *       倒计时循环撞上 60 的护栏 ⇒ ② 变红；</li>
	 *   <li>{@code 正在发 ⇒ 亮；脉冲到点归零后 ⇒ 灭}：把 {@code getArmLinkLevel() > 0} 从
	 *       {@code computeSignalActive()} 删掉 ⇒ {@code ③ 发时亮} 变 false；让 {@code tickArmLink()}
	 *       到点不把电平归 0 ⇒ {@code ③ 归零后灭} 变 false；</li>
	 *   <li>{@code 被红石点亮（redstoneLocked）⇒ false}（旧口径已被用户推翻）：把
	 *       {@code if (redstoneLocked) return true;} 加回 {@code computeSignalActive()} ⇒
	 *       {@code ④ 红石点亮不亮} 变红。</li>
	 * </ol>
	 *
	 * <p><b>驱动方式</b>：自检<b>不跑 {@code arm.tick()}</b>（运动学要真实动力网络，见
	 * {@link #checkOverflowStackAndExchange}），逐 tick 调的是 {@code arm.tick()} 里同样那一句
	 * {@link SequencedArmBlockEntity#tickSignalActive()}（脉冲倒计时 + 口径重算 + 翻转检测）与
	 * {@code runtime.tick(arm)}；「收信号」只有真跑解释器才会走到，**不伪造**运行时状态。
	 *
	 * <p><b>夹具隔离</b>：四段断言**共用同一条臂**（同一个方块实体实例 ⇒ 同一张
	 * {@code heldSignals} 表），而「发」出去的信号是**持久状态** —— {@code emitSignal(..., ticks = 0)}
	 * 就是"一直保持"，只有「中断」/ 换卡 / 拆方块才断。所以每段起跑前都走
	 * {@link #quietArm(SequencedArmBlockEntity)} 显式清干净，并把"确实清干净了"当成前提断言出来：
	 * 上一段漏过来的 15 电平只会红在**前提**上，不会伪装成 ① / ④ 的判据错。
	 *
	 * <p><b>日志字段口径</b>：{@code 亮=} 印的是 {@code isSignalActive()} 的**真实**值，
	 * 断言值另外用 {@code 不亮成立=} 印。旧日志把断言值挂在 {@code 亮=} 后面，于是"确实不亮"（断言成立）
	 * 被读成 {@code 亮=true}（"本该不亮却亮了"）—— 那次 {@code run/logs/latest.log} 的 ① / ④ 就是这样被
	 * 误读成红，实际上一条 {@code ERROR} 都没有。
	 */
	private static void checkSignalActiveModel(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		// 与上面两条信号自检（+52 / +64）用同一片空地，但不重叠
		BlockPos armPos = new BlockPos(spawn.getX() + 76, 201, spawn.getZ() + 60);
		BlockPos senderPos = armPos.offset(0, 0, 3);
		BlockPos powerPos = armPos.offset(-1, 0, 0); // **紧邻**（红石信号只给邻居）
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldSender = level.getBlockState(senderPos);
		BlockState oldPower = level.getBlockState(powerPos);
		Couple<Frequency> recvFreq = testFrequency(Items.GOLD_INGOT, Items.COPPER_INGOT);
		Couple<Frequency> sendFreq = testFrequency(Items.QUARTZ, Items.FLINT);

		boolean placed = false;
		// —— 夹具隔离的前提（true = 进那一段之前这条臂身上**没有被持有的信号、没有未到期的短脉冲**）——
		// 为什么每条都要断言：见 quietArm() 的注释（各段共用同一个方块实体实例 + 信号是持久状态）。
		boolean quietBeforeRecv = false;
		boolean quietBeforeSend = false;
		boolean quietBeforeLock = false;
		// ⚠️ 日志字段口径（本轮修的就是这个）：`litIn*` 是**真实的亮灭**（isSignalActive() 的现场值），
		//   `*Dark` 才是断言（true = "不亮"成立）。旧日志把**断言值**印在 `亮=` 后面 ⇒
		//   断过的时候日志读作 `亮=true`，看着像"本该不亮却亮了"（run/logs/latest.log 的 ① / ④ 就是
		//   这样被误读成三处红的，其实那次一条 ERROR 都没有）。电平 / 脉冲一起印出来，
		//   下次真泄漏时能当场看出是"表里那 15 漏过来了"还是"脉冲没到期"。
		boolean recvReachedWaiting = false;
		boolean freqIdle = false;
		boolean litInRecvWait = false;
		int levelAtRecv = -1;
		int flashAtRecv = -1;
		boolean recvWaitingDark = false; // ①（收信号）
		boolean litOnReceive = false; // ②
		int flashRemainingOnLight = -1;
		boolean fadedAfterFlash = false;
		int flashLasted = -1;
		boolean sendLit = false; // ③
		boolean sendDarkAfterPulse = false;
		boolean lockEngaged = false; // ④
		boolean litWhileLocked = false;
		int levelAtLock = -1;
		int flashAtLock = -1;
		boolean lockDark = false;
		// ⚠️ 原来这里还有一大段 ⑤（"按相邻红石等待"那条操作成功 ⇒ 不亮，两条极性各一条）及其夹具前提。
		//   **整段随那条操作一起删除**（用户口径：「我现在倾向于移除这个操作，
		//   因为我们有更好的无线信号系统可以使用」）：被测的那条操作不存在了，
		//   "它成功时亮不亮"也就没有意义。原本它守的那条区分（只有无线信号点亮底座）
		//   现在由 `tickWait` 里 `op == SIGNAL && isSignalReceive()` 那层判据 + ② / ④ 两条断言守着。
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 信号活动 这条断言的前提不成立：臂的方块实体没建出来");
			} else {
				placed = true;
				arm.setSpeed(32); // 转速为 0 时解释器整 tick 跳过（与原版一致）

				// ——— ① 正在等一路频率、但那一路**没通电** ⇒ 也不亮 ———
				// ⚠️ 这一段的"等相邻红石"那一半（同一个 ① 里的前半段）已随该操作一起删除：
				//   它要构造的条目用的是已删除的枚举常量。留在这里的是**用户这次反馈的字面场景**：
				//   "尝试接收但没收到 ⇒ 不亮"，一个字都没放松。
				ArmProgramEntry recv = new ArmProgramEntry();
				recv.op = OperationType.SIGNAL;
				recv.signalMode = ArmProgramEntry.SignalMode.RECEIVE;
				recv.waitForPowered = true;
				recv.frequency = recvFreq;
				// ★ 接收半径（用户口径：距离只约束「收」）：本段的发端臂在 +3 格，而默认的 0 只认
				// **同一格**（= 自己发自己收的自环）⇒ 这条夹具必须显式给一个够得着的半径。
				// 反证：把它改回 0（或删掉这一行）⇒ ② 的「收到即亮」永远等不到 ⇒ 本段红。
				recv.signalRange = 16;
				arm.setProgram(oneEntryProgram("recv", recv));
				arm.runtime.resetToStart(arm);
				quietBeforeRecv = quietArm(arm); // 这一段也必须从"手上没有任何信号"起跑
				for (int i = 0; i < 14; i++) {
					arm.runtime.tick(arm);
					arm.tickSignalActive();
					if (arm.runtime.state == ProgramRuntime.EntryState.WAITING)
						recvReachedWaiting = true;
				}
				// 前提（现场对照）：那一路频率此刻**确实没电**，否则"没收到"与"收到了"就分不清了
				freqIdle = !Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(recvFreq);
				litInRecvWait = arm.isSignalActive();
				levelAtRecv = arm.getArmLinkLevel();
				flashAtRecv = arm.signalFlashTicks();
				recvWaitingDark = recvReachedWaiting && freqIdle && !litInRecvWait;

				// ——— ② 真的收到了 ⇒ 当场亮，约 10 tick 后灭 ———
				level.setBlockAndUpdate(senderPos, SequencedArmRegistration.SEQUENCED_ARM.get()
					.defaultBlockState());
				if (!(level.getBlockEntity(senderPos) instanceof SequencedArmBlockEntity sender)) {
					LOGGER.error("[selftest] 信号活动 发端臂没建出来：②（收到即亮）没法验证");
				} else {
					sender.emitSignal(recvFreq, 15, 0); // 15 = 通电、ticks=0 = 一直保持
					boolean networkPowered = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(recvFreq);
					if (!networkPowered) {
						LOGGER.error("[selftest] 信号活动 发端没让那一路频率通电（②的对照组成立不了）");
					} else {
						// 收到 = tickWait 在这一拍发现极性吻合（同一拍 succeed ⇒ 只有短脉冲能证明"收到过"）
						for (int i = 0; i < 12; i++) {
							arm.runtime.tick(arm);
							arm.tickSignalActive();
							if (arm.isSignalActive()) {
								litOnReceive = true;
								flashRemainingOnLight = arm.signalFlashTicks(); // 亮起来那一刻还剩几拍（服务端真值）
								break;
							}
						}
						// 数"还亮了几拍"：脉冲到点必须自己灭（护栏 60 = 永不灭时撞上去变红）
						int extra = 0;
						while (arm.isSignalActive() && extra < 60) {
							arm.tickSignalActive();
							extra++;
						}
						flashLasted = litOnReceive ? 1 + extra : -1;
						fadedAfterFlash = litOnReceive && !arm.isSignalActive() && flashLasted >= 8
							&& flashLasted <= 12;
					}
					sender.clearTransmissions(); // 先收干净，再撤方块（拆块也会清，这里留双重保险）
				}
				level.setBlockAndUpdate(senderPos, oldSender); // 发端撤掉（BE 被移除 ⇒ 退网）

				// ——— ③ 正在发 ⇒ 亮；发出去的脉冲到点电平归零 ⇒ 灭 ———
				arm.setProgram(new ArmProgram()); // 判据只看电平，不需要程序
				// ③ 自己也从"干净"起跑：上一段 ② 是**收**，收完之后 pulse 可能还剩几拍，
				// 带着它进 ③ 会让 `sendLit` 变成"脉冲借来的亮"（测了空气）。
				quietBeforeSend = quietArm(arm);
				arm.emitSignal(sendFreq, 15, 0); // 15 = 通电、ticks=0 = 一直保持
				arm.tickSignalActive();
				sendLit = arm.getArmLinkLevel() > 0 && arm.isSignalActive();
				// 换成 3 tick 的脉冲：让它在计时器上真的"到点归 0"（主循环里同一个调用）
				arm.emitSignal(sendFreq, 15, 3);
				for (int i = 0; i < 10 && arm.getArmLinkLevel() > 0; i++) {
					arm.runtime.tickArmLinkTimers(arm);
					arm.tickSignalActive();
				}
				sendDarkAfterPulse = arm.getArmLinkLevel() == 0 && !arm.isSignalActive();
				// ③ 用完就把那个"一直保持"的 15 断掉：它是**持久状态**，不清就跨到 ④ 去
				//（下面 ④ 还会再用 quietArm 兜一次 —— 双保险，因为这条最容易被后来的改动绕过）。
				arm.clearTransmissions();
				arm.tickSignalActive(); // 让口径按"已清空"重算，别把 ③ 的旧值带进 ④

				// ——— ④ 被相邻红石点亮（redstoneLocked）⇒ **仍然不亮**（旧的"点亮就亮天线"已被用户推翻） ———
				// 前提自证：进 ④ 前 `getArmLinkLevel() == 0`。少了这一条，③ 漏出来的 15 会让
				// computeSignalActive() 恒真，④ 的"不亮"就假红成"运行时把 redstoneLocked 加回去了"。
				quietBeforeLock = quietArm(arm);
				level.setBlockAndUpdate(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState());
				arm.redstoneUpdate(); // 方块变化本身也会调它（SequencedArmBlock.neighborChanged），这里显式确认
				lockEngaged = arm.isRedstoneLocked() && arm.hasNeighborSignal();
				arm.tickSignalActive();
				litWhileLocked = arm.isSignalActive();
				levelAtLock = arm.getArmLinkLevel();
				flashAtLock = arm.signalFlashTicks();
				lockDark = lockEngaged && !litWhileLocked && levelAtLock == 0;

				// ⚠️ 这里原来是 ⑤（"按相邻红石等待"那条操作成功 ⇒ 不亮，两条极性各一条），**整段删除**：
				//   被测的那条操作已按用户口径移除，枚举常量都不存在了，构造不出夹具。
				//   它原来守的区分（只有**无线信号**点亮底座）仍然由 ② / ④ 与
				//   `ProgramRuntime.tickWait` 里 `op == SIGNAL && isSignalReceive()` 那层判据守着。
			}
		} catch (Throwable t) {
			LOGGER.error("[selftest] 信号活动 行为自检异常：{}", t.toString());
		} finally {
			level.setBlockAndUpdate(powerPos, oldPower);
			level.setBlockAndUpdate(senderPos, oldSender);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		// ★ 字段口径：`亮=` 印的是**真实亮灭**（isSignalActive()），`不亮成立=` 印的才是断言。
		//   旧日志只印断言、却挂在 `亮=` 后面 ⇒ 断过（= "确实不亮"）时读作 `亮=true`，
		//   看上去正好是反的（用户报的"三处红"就是这么来的：那次 run/logs/latest.log 一条 ERROR 都没有）。
		LOGGER.info("[selftest] 信号活动 ① 收信号没通电时不亮: 前提·无持有信号={} 进等待={} 该频率无电={} 亮={}（电平={} 脉冲={}） 不亮成立={}",
			quietBeforeRecv, recvReachedWaiting, freqIdle, litInRecvWait, levelAtRecv, flashAtRecv, recvWaitingDark);
		LOGGER.info("[selftest] 信号活动 ② 收到即亮={} 亮起时脉冲剩余={} 亮了几拍={} 到点自己灭={}", litOnReceive,
			flashRemainingOnLight, flashLasted, fadedAfterFlash);
		LOGGER.info("[selftest] 信号活动 ③ 发时亮={} 归零后灭={}（进 ③ 前无持有信号={}）", sendLit, sendDarkAfterPulse,
			quietBeforeSend);
		LOGGER.info("[selftest] 信号活动 ④ 红石点亮不亮: 前提·无持有信号={} 锁已生效={} 亮={}（电平={} 脉冲={}） 不亮成立={}",
			quietBeforeLock, lockEngaged, litWhileLocked, levelAtLock, flashAtLock, lockDark);
		// ⚠️ ⑤（"按相邻红石等待"那条操作成功不亮）的日志与两条断言**随那条操作一起删除**：见上面 ④ 之后那段说明。
		if (!placed)
			LOGGER.error("[selftest] 信号活动 自检没跑起来（臂没建成）—— 下面 ①–④ 的断言等于没验");
		// 夹具隔离的前提**自己也要红**：下次谁再漏一条"一直保持"的信号进来，会在这里直接报，
		// 而不是伪装成 ① / ④ 的判据错（那样会把人往 computeSignalActive() 里带）。
		if (!quietBeforeRecv || !quietBeforeSend || !quietBeforeLock)
			LOGGER.error("[selftest] 信号活动 的夹具前提不成立：进 ① / ③ / ④ 之前这条臂上还留着被持有的信号或未到期的短脉冲"
				+ "（{}/{}/{}）—— 这是夹具没隔离干净（信号是持久状态，只有「中断」/换卡/clearTransmissions 才断），"
				+ "不是 computeSignalActive() 的错", quietBeforeRecv, quietBeforeSend, quietBeforeLock);
		if (!recvReachedWaiting)
			LOGGER.error("[selftest] 信号活动 ① 的对照组不成立：程序没停在「收信号」的 WAITING 上"
				+ "（那条\"正在等也不亮\"的断言就是测了空气）");
		if (!freqIdle)
			LOGGER.error("[selftest] 信号活动 ① 的对照组不成立：还没发信号，那一路频率就已经有电了");
		// ① 用户这次反馈的字面场景：尝试接收但没收到 ⇒ **不亮**
		if (!recvWaitingDark)
			LOGGER.error("[selftest] 信号活动 ① 「正在等但没收到信号」把底座点亮了"
				+ "（用户口径：必须成功接收才发光，尝试接收不算）—— 查 computeSignalActive() 里是不是又把\"正在等\"算进去了");
		// ② 收到的那一刻起亮，且看得见、会自己灭
		if (!litOnReceive)
			LOGGER.error("[selftest] 信号活动 ② 真的收到了却没有点亮"
				+ "（查 ProgramRuntime.tickWait 里那行 arm.flashSignalReceived()，以及 computeSignalActive() 里的 signalFlashTicks）");
		if (!fadedAfterFlash)
			LOGGER.error("[selftest] 信号活动 ② 短脉冲没有在约 {} tick 后熄灭（实际 {} 拍）—— 查 SIGNAL_FLASH_TICKS 与 tickSignalActive() 的递减",
				SequencedArmBlockEntity.SIGNAL_FLASH_TICKS, flashLasted);
		// ③ 发：电平 > 0 就亮，归零就灭
		if (!sendLit)
			LOGGER.error("[selftest] 信号活动 ③ 正在发信号却没有点亮（computeSignalActive() 里的 getArmLinkLevel() 那条丢了）");
		if (!sendDarkAfterPulse)
			LOGGER.error("[selftest] 信号活动 ③ 发出去的脉冲到点电平归零后还亮着（\"曾经发过\"不算）");
		// ④ 旧口径已被推翻：被红石点亮 ≠ 收到信号
		if (!lockEngaged)
			LOGGER.error("[selftest] 信号活动 ④ 的对照组不成立：红石锁没生效（相邻红石没点亮这条臂）");
		else if (!lockDark)
			LOGGER.error("[selftest] 信号活动 ④ 被相邻红石点亮就把底座点亮了"
				+ "（用户口径：那只是\"通电即暂停\"，不是收到信号 —— 查 computeSignalActive() 里是不是又把 redstoneLocked 加回去了）");
		// ⚠️ 原来的 ⑤（"按相邻红石等待"那条操作的两条成功路径都必须是"不亮 + 脉冲 0"）**整块删除**：
		//   那条操作已被用户删除（「我们有更好的无线信号系统可以使用」），
		//   连夹具都构造不出来，那两条断言也就失去了被测对象。
		//   ⚠️ 但**判据本身没有放松**：`ProgramRuntime.tickWait` 里
		//   `if (entry.op == OperationType.SIGNAL && entry.isSignalReceive()) arm.flashSignalReceived();`
		//   那一层仍然在（把 `if` 去掉 ⇒ ②「收到即亮」与 ④「红石点亮不亮」之间的区分就只剩 ④；
		//   ②/④ 里"只有无线信号点亮底座"仍然被逐条钉着）。
	}

	/**
	 * 夹具隔离：把这条臂上**所有被持有的信号**与未到期的短脉冲清干净，并返回"确实清干净了"。
	 *
	 * <p><b>为什么必须显式清（用户这次报的"假红"根因就在这里）</b>：④ 之前那几段断言
	 * （① 收信号 / ② 收到 / ③ 发 / ④ 红石锁）**共用同一个方块实体实例**，
	 * 而"发"出去的信号是<b>持久状态</b> —— {@code emitSignal(freq, 15, 0)} 的 {@code ticks = 0} 就是
	 * "一直保持"（§7.16e 用户口径），只有「中断」({@link SequencedArmBlockEntity#releaseSignal})、
	 * 换卡（{@code setProgram} / {@code resetToStart} 里的
	 * {@link SequencedArmBlockEntity#clearTransmissions()}）与拆方块才会断。③ 为了验"发时亮"正是发了
	 * 15 并<b>一直保持</b>；这个 15 一旦漏到 ④（以及任何在它之后写的段落），
	 * {@link SequencedArmBlockEntity#getArmLinkLevel()} {@code > 0} 会让 {@code computeSignalActive()}
	 * 恒真 —— 于是 ① / ④ 的"不亮"会假红成"运行时把'在等 / 被红石点亮'也算成收到"，
	 * 把人往一个根本不存在的运行时 bug 上带。
	 *
	 * <p>所以每段起跑前都调这里，并把返回值断言成"前提成立"：下次再泄漏会**先红在前提上**。
	 *
	 * <p>前提只查**输入**（表空 + 电平 0 + 脉冲 0），<b>不查</b> {@code isSignalActive()} ——
	 * 后者正是 ① / ④ 要验的东西，放进前提里就成了自证。
	 */
	private static boolean quietArm(SequencedArmBlockEntity arm) {
		arm.clearTransmissions(); // 持久信号只能这样断（或 releaseSignal(freq) 逐条断）
		// 短脉冲没有 setter，只能靠"每 tick 递减一格"自然到期（tickSignalActive 与 arm.tick() 同一条路）
		for (int i = 0; i < SequencedArmBlockEntity.SIGNAL_FLASH_TICKS && arm.signalFlashTicks() > 0; i++)
			arm.tickSignalActive();
		arm.tickSignalActive(); // 表已空 ⇒ 顺手把 signalActive 口径按新状态重算一次，别留上段的旧值
		return arm.heldSignalCount() == 0 && arm.getArmLinkLevel() == 0 && arm.signalFlashTicks() == 0;
	}

	/** 一份只含一个任务的程序（每份程序至少一个任务，否则解释器没有可跑的入口）。 */
	private static ArmProgram oneEntryProgram(String taskName, ArmProgramEntry entry) {
		ArmProgram program = new ArmProgram();
		program.tasks.add(namedTask(taskName, entry));
		return program;
	}

	// ——————————————— 「收发信号」的接收距离（用户口径：距离只约束「收」） ———————————————

	/**
	 * 断言：<b>接收距离</b>（用户口径："默认为 0（只能自己接收发送），范围是 0~256 格"；距离<b>只约束「收」</b>）。
	 *
	 * <p>五条，每条都真驱动 {@code runtime.tick()}（不看常量、不调私有方法）：
	 * <ol>
	 *   <li><b>自环</b>：本臂发 F1（15、保持），<b>同一条臂</b>「收 F1 · range=0」⇒ 成功
	 *       （{@code distSqr == 0 <= 0} —— 这就是"只能自己接收发送"）；</li>
	 *   <li><b>够不着</b>：第二只臂在 {@link #SIGNAL_RANGE_TEST_FAR} 格外发 F2，本臂「收 F2 · range=0」⇒ 不成功；</li>
	 *   <li><b>够得着</b>：同一只第二臂 + 本臂「收 F2 · range=256」⇒ 成功；</li>
	 *   <li><b>发送侧不受距离影响</b>：远臂的「发」条目 {@code signalRange = 0}（**真走 execute → emitSignal**）、
	 *       本臂 {@link #SIGNAL_RANGE_TEST_FAR} 格外「收 · range=256」⇒ 收到；</li>
	 *   <li><b>含边界</b>：第二只臂正好摆在 N = {@link #SIGNAL_RANGE_TEST_EDGE} 格（沿 x 轴，欧氏 = 切比雪夫同解）
	 *       ⇒「range=N」成功、「range=N-1」不成功。</li>
	 * </ol>
	 *
	 * <p><b>每条的反证</b>：
	 * <ul>
	 *   <li>把 {@code isSignalPowered} 的距离判定删掉（退回"只看同频率有没有人通电"）⇒ ② / ⑤后半 红；</li>
	 *   <li>把那个 {@code <=} 写成 {@code <} ⇒ ① （{@code 0 < 0} 不成立）与 ⑤前半（正好 N 格）都红；</li>
	 *   <li>把距离判定**挪到发送侧**（例如 emit 时按自己半径筛）⇒ ④ 红（远臂 range=0 就发不出去了）；</li>
	 *   <li>把 {@code hasSignalRange()} 写成恒 {@code false}（缓存三元组丢了半径那一项也一样）⇒ ③ 红。</li>
	 * </ul>
	 *
	 * <p>⚠️ 判据用臂上那个"收到"短脉冲而不是游标：单条目任务成功后主任务恒循环会把 {@code entryIndex}
	 * 抹回 0（见 {@link #checkSignalCutHasNoDuration} 的同一段说明）。
	 */
	private static void checkSignalRange(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		// 与其它信号自检（+52/+52、+64/+58、+76/+60）用同一片空地，z 取 +76 这一带（没人用过）
		BlockPos nearPos = new BlockPos(spawn.getX() + 52, 201, spawn.getZ() + 76);
		BlockPos farPos = nearPos.offset(SIGNAL_RANGE_TEST_FAR, 0, 0);
		BlockPos edgePos = nearPos.offset(SIGNAL_RANGE_TEST_EDGE, 0, 0);
		BlockState oldNear = level.getBlockState(nearPos);
		BlockState oldFar = level.getBlockState(farPos);
		BlockState oldEdge = level.getBlockState(edgePos);
		Couple<Frequency> loopFreq = testFrequency(Items.BOWL, Items.STICK);
		Couple<Frequency> farFreq = testFrequency(Items.SUGAR, Items.GLASS_BOTTLE);
		Couple<Frequency> sendFreq = testFrequency(Items.ARROW, Items.BONE);
		Couple<Frequency> edgeFreq = testFrequency(Items.STRING, Items.FEATHER);

		boolean selfLoop = false;
		boolean tooFarBlocked = false;
		boolean withinReach = false;
		boolean sendIgnoresRange = false;
		boolean edgeIncluded = false;
		boolean edgeExcluded = false;
		// 前提（现场对照）：那只远臂 / 边界臂真的让频率通上了电 —— 没有它，"没收到"可能只是"根本没人发"
		boolean farPowered = false;
		boolean edgePowered = false;
		boolean farSendRan = false;
		boolean quietBefore = false;
		String error = null;
		try {
			SequencedArmBlockEntity arm = freshArm(level, nearPos);
			SequencedArmBlockEntity farArm = freshArm(level, farPos);
			SequencedArmBlockEntity edgeArm = freshArm(level, edgePos);
			if (arm == null || farArm == null || edgeArm == null) {
				error = "臂的方块实体没建出来（near=" + (arm != null) + " far=" + (farArm != null) + " edge="
					+ (edgeArm != null) + "）";
			} else {
				// ——— ① 自环：本臂发 F1（15、保持），同一条臂「收 F1 · range=0」⇒ 成功 ———
				// ⚠️ 顺序：先把程序装好（setProgram / resetToStart 会 clearTransmissions），**再**发信号。
				armForReceive(arm, receiveEntry(loopFreq, 0));
				quietBefore = quietArm(arm);
				arm.emitSignal(loopFreq, 15, 0); // 15 = 通电、ticks=0 = 一直保持
				SignalProbe loop = driveReceive(arm, 12);
				selfLoop = loop.waited() && loop.received();

				// ——— ② 够不着：远臂在自己那一格上通电，本臂「收 F2 · range=0」⇒ 不成功 ———
				quietArm(farArm); // 远臂只需要"干净地发"，不需要程序
				farArm.emitSignal(farFreq, 15, 0);
				farPowered = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(farFreq);
				armForReceive(arm, receiveEntry(farFreq, 0));
				SignalProbe farZero = driveReceive(arm, 12);
				tooFarBlocked = farZero.waited() && !farZero.received();

				// ——— ③ 够得着：同一只远臂 + 本臂「收 F2 · range=256」⇒ 成功 ———
				armForReceive(arm, receiveEntry(farFreq, 256));
				SignalProbe farWide = driveReceive(arm, 12);
				withinReach = farWide.received();

				// ——— ④ 发送侧不受距离影响：远臂「发」条目的 range=0，本臂 100 格外 range=256 ⇒ 收到 ———
				// 这一条把「距离只管收」钉死：发送侧那个 0 是"只能同格"的值，但它对发送毫无影响。
				ArmProgramEntry send = new ArmProgramEntry();
				send.op = OperationType.SIGNAL;
				send.signalMode = ArmProgramEntry.SignalMode.SEND_ON;
				send.waitForPowered = true;
				send.frequency = sendFreq;
				send.signalRange = 0; // 发送侧的距离：**不参与任何判定**
				send.waitAmount = 0; // 0 = 一直保持（没有到点恢复）
				farArm.setProgram(oneEntryProgram("send", send));
				farArm.setSpeed(32);
				farArm.runtime.resetToStart(farArm);
				quietArm(farArm);
				for (int i = 0; i < 6 && !farArm.holdsSignal(sendFreq); i++)
					farArm.runtime.tick(farArm); // 真走 execute → arm.emitSignal（不是直接调 emitSignal）
				farSendRan = farArm.holdsSignal(sendFreq);
				armForReceive(arm, receiveEntry(sendFreq, 256));
				SignalProbe sent = driveReceive(arm, 12);
				sendIgnoresRange = farSendRan && sent.received();

				// ——— ⑤ 含边界：第二只臂正好 N 格（沿 x 轴）———
				quietArm(edgeArm); // 同样只需要"干净地发"
				edgeArm.emitSignal(edgeFreq, 15, 0);
				edgePowered = Create.REDSTONE_LINK_NETWORK_HANDLER.hasAnyLoadedPower(edgeFreq);
				armForReceive(arm, receiveEntry(edgeFreq, SIGNAL_RANGE_TEST_EDGE));
				SignalProbe edgeIn = driveReceive(arm, 12);
				edgeIncluded = edgeIn.received();
				armForReceive(arm, receiveEntry(edgeFreq, SIGNAL_RANGE_TEST_EDGE - 1));
				SignalProbe edgeOut = driveReceive(arm, 12);
				edgeExcluded = edgeOut.waited() && !edgeOut.received();
			}
		} catch (Throwable t) {
			error = "行为自检异常：" + t;
		} finally {
			level.setBlockAndUpdate(nearPos, oldNear);
			level.setBlockAndUpdate(farPos, oldFar);
			level.setBlockAndUpdate(edgePos, oldEdge);
		}
		// ★ 日志口径：**断言值只出现在 `=` 右边**（这个模组踩过"标签印成断言值"的坑）。
		LOGGER.info("[selftest] 信号距离: 自环(range=0)={} 够不着({}格外 range=0)={} 够得着(range=256)={} "
			+ "发送侧不受距离影响={} 边界(range={})={} 边界外(range={})={}",
			selfLoop, SIGNAL_RANGE_TEST_FAR, tooFarBlocked, withinReach, sendIgnoresRange, SIGNAL_RANGE_TEST_EDGE,
			edgeIncluded, SIGNAL_RANGE_TEST_EDGE - 1, edgeExcluded);
		LOGGER.info("[selftest] 信号距离 前提: 夹具干净={} 远臂通电={} 边界臂通电={} 远臂发送条目真的跑了={}", quietBefore,
			farPowered, edgePowered, farSendRan);
		if (error != null) {
			LOGGER.error("[selftest] 信号距离 这条断言的前提不成立：{}", error);
			return;
		}
		if (!quietBefore)
			LOGGER.error("[selftest] 信号距离 夹具起跑时不干净（表里还有信号 / 脉冲没到期）—— 后面的结论说明不了问题");
		if (!farPowered || !edgePowered)
			LOGGER.error("[selftest] 信号距离 远臂 / 边界臂没有让频率通上电（对照组不成立，「没收到」就说明不了问题）");
		if (!farSendRan)
			LOGGER.error("[selftest] 信号距离 远臂的「发」条目没有真的执行（④ 就退化成测了空气）");
		if (!selfLoop)
			LOGGER.error("[selftest] 信号距离 ① 自环没成功：range=0 时同一格（distSqr=0）必须算收到"
				+ " —— 查 anyPowerWithin 里的比较是不是写成了 `<`");
		if (!tooFarBlocked)
			LOGGER.error("[selftest] 信号距离 ② 100 格外、range=0 竟然收到了 —— 距离判定没生效（还在用不带位置的通电查询？）");
		if (!withinReach)
			LOGGER.error("[selftest] 信号距离 ③ range=256 却收不到 100 格外的信号 —— 半径判定把「够得着」也筛掉了");
		if (!sendIgnoresRange)
			LOGGER.error("[selftest] 信号距离 ④ 发送侧被距离影响了（用户口径：距离只管收，发出去不管）");
		if (!edgeIncluded)
			LOGGER.error("[selftest] 信号距离 ⑤ range 正好等于距离的那一格必须**算收到**（含边界）");
		if (!edgeExcluded)
			LOGGER.error("[selftest] 信号距离 ⑤ range 比距离少一格却收到了 —— 边界判定少了 1 格");
	}

	/** 自检用的"够不着"距离（格）：远臂摆在本臂 +x 这个格数上（用户口径的 ~100 格）。 */
	private static final int SIGNAL_RANGE_TEST_FAR = 100;
	/** 自检用的"正好在边界上"的距离（格）：沿 x 轴摆，欧氏距离与切比雪夫同解。 */
	private static final int SIGNAL_RANGE_TEST_EDGE = 8;

	/** 一条「收信号」条目：等通电、绑这对频率、接收半径 = {@code range}。 */
	private static ArmProgramEntry receiveEntry(Couple<Frequency> freq, int range) {
		ArmProgramEntry recv = new ArmProgramEntry();
		recv.op = OperationType.SIGNAL;
		recv.signalMode = ArmProgramEntry.SignalMode.RECEIVE;
		recv.waitForPowered = true;
		recv.frequency = freq;
		recv.signalRange = range;
		return recv;
	}

	/**
	 * 把一条「收信号」条目装进臂并从第 1 条起跑。
	 *
	 * <p>⚠️ 它**不发信号**：{@code setProgram} / {@code resetToStart} 都会 {@code clearTransmissions()}，
	 * 所以"要先发的那个频率"必须在调用本方法**之后**再发（顺序写反 ⇒ 收到的是自己刚被清掉的东西，
	 * 自环那一条会变成"测了空气"）。
	 */
	private static void armForReceive(SequencedArmBlockEntity arm, ArmProgramEntry receive) {
		arm.setProgram(oneEntryProgram("recv", receive));
		arm.setSpeed(32); // 转速为 0 时解释器整 tick 跳过（与原版一致）
		arm.runtime.resetToStart(arm);
		quietArm(arm); // 同样必须在上面两行之后（它们会清发射表）
	}

	/**
	 * 驱动若干拍，返回"到过等待态吗 / 真的收到了吗"。
	 *
	 * <p>判据用臂上那个"收到"短脉冲（{@code tickWait} 只在极性成立**那一刻**调
	 * {@code flashSignalReceived()}）：游标不可用 —— 单条目任务成功后主任务恒循环会把 {@code entryIndex}
	 * 抹回 0（见 {@link #checkSignalCutHasNoDuration}）。{@code waited} 是"没收到"那一侧的前提：
	 * 它证明条目**真的进了等待态**，而不是压根没跑。
	 */
	private static SignalProbe driveReceive(SequencedArmBlockEntity arm, int ticks) {
		boolean waited = false;
		boolean received = false;
		for (int i = 0; i < ticks; i++) {
			arm.runtime.tick(arm);
			if (arm.runtime.state == ProgramRuntime.EntryState.WAITING)
				waited = true;
			if (arm.signalFlashTicks() > 0)
				received = true;
		}
		return new SignalProbe(waited, received);
	}

	/** {@link #driveReceive} 的结果：到过等待态吗 / 真的收到了吗。 */
	private record SignalProbe(boolean waited, boolean received) {
	}

	/**
	 * 卡片与坐标语义的自检（PLAN.md §19.7 的用户口径 + 用户第四十轮的"原点搬到卡上"）：
	 * ① 程序写进物品再读回来必须无损，**卡级原点**也必须能活过往返（含"没设"这一态）；
	 * ② 卡上的原点只服务**录制**（`offset = 世界位置 − 原点`）：这一条由下面"设过 ⇒ 录得进去"
	 * 的那段真实录制路径钉着 —— 没设时 {@code CardEditPacket.addEntry} 会用 {@code card.need_origin} 拒绝；
	 * ③ ★ <b>插卡不做任何平移</b>：同一张卡分别插进两条位置不同的臂，两次得到的 {@code offset}
	 * 必须**完全相同**，而世界目标按各自臂的位置算（{@code 臂位置 + offset}）。
	 *
	 * <p>③ 这条就是"防止有人把平移加回来"的守卫（曾经的 {@code ProgramCoordinates.rebaseTo} 干的正是
	 * 平移）：它一旦回来，两次插卡读到的 offset 就会因为臂位置不同而不同 ⇒ 这条立刻红。
	 * 只断言"offset 非空"是没用的 —— 平移之后 offset 照样非空。
	 */
	private static void checkCardAndCoordinates(MinecraftServer server) {
		ServerLevel level = server.overworld();
		HolderLookup.Provider registries = server.registryAccess();

		ArmProgram source = DevPresets.depotAB();
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.write(card, source, registries);
		boolean stored = CardProgramStorage.hasProgram(card);
		ArmProgram readBack = CardProgramStorage.read(card, registries);
		boolean lossless = readBack.save(registries)
			.equals(source.save(registries));
		LOGGER.info("[selftest] 卡片存取: stored={} lossless={}", stored, lossless);
		if (!stored || !lossless)
			LOGGER.error("[selftest] 卡片 NBT 往返失败！stored={} lossless={}", stored, lossless);

		// ——— ① 原点：**卡级**属性（用户第四十轮从程序里搬出来）———
		// 下面这一串一起测，缺一条就会漏掉一种坏法：
		//   · 设过的值活过往返（写进 long 再读回来）；
		//   · 写 null 之后**键真的没了**（不是写个 0 顶替）；
		//   · (0,0,0) 是**合法值**，与"没设"必须区分得开（哨兵反证，§0.5 那条"永远为真的断言"）；
		//   · 写它不能碰程序文本，文本里也不能有 @origin（原点不跟着脚本走）。
		// ⚠️ 这里直接写键名字面量 `ArmProgramOrigin`：它是在测"**盘上**的键"，用私有常量会让
		//    改坏存储时这条断言跟着一起变绿（判据写两份会漂移，但"盘上格式"本身就是要钉住的东西）。
		BlockPos marked = new BlockPos(12, 70, -34);
		CardProgramStorage.writeOrigin(card, marked);
		CustomData withOrigin = card.get(DataComponents.CUSTOM_DATA);
		boolean originKeyWritten = withOrigin != null && withOrigin.copyTag()
			.contains("ArmProgramOrigin");
		boolean originKept = marked.equals(CardProgramStorage.readOrigin(card));
		CardProgramStorage.writeOrigin(card, BlockPos.ZERO);
		boolean zeroIsSet = BlockPos.ZERO.equals(CardProgramStorage.readOrigin(card));
		CardProgramStorage.writeOrigin(card, null);
		boolean unsetKept = CardProgramStorage.readOrigin(card) == null;
		CustomData afterRemove = card.get(DataComponents.CUSTOM_DATA);
		boolean originKeyGone = afterRemove == null || !afterRemove.copyTag()
			.contains("ArmProgramOrigin");
		// 原点**不跟着程序文本走**：文本里没有 @origin（写头那条一旦被加回来立刻红）
		String cardText = CardProgramStorage.readText(card);
		boolean headerNotInText = cardText != null && !cardText.contains(ScriptParser.HEADER);
		// 而且写它**不能碰程序**：三步 writeOrigin 之后卡上那份程序必须一条不少
		// （writeOrigin 只动自己那个键 —— 万一它把整个 CUSTOM_DATA 换掉了，这里立刻红）
		boolean programSurvived = CardProgramStorage.read(card, registries)
			.tasks.size() == source.tasks.size();
		// ★ 没设原点（键不存在）时**录制必须被拒**（§19.7）：原点搬上卡之后，这条门禁读的就是
		// `readOrigin(card) == null`。走**真实的服务端入口** `CardEditPacket.addEntry` 才测得到 ——
		// 只断言 readOrigin 为 null 的话，"门禁改回读程序里的字段（永远为假）"这件事照样能通过。
		ServerPlayer recorder = fakePlayer(level);
		// 假玩家不可用（某些环境下建不出来）⇒ 这条测不到：按"不判红"处理，与其它自检同一口径
		boolean gateHolds = recorder == null;
		if (recorder != null) {
			recorder.setItemInHand(InteractionHand.MAIN_HAND, card);
			int before = countEntries(CardProgramStorage.read(card, registries));
			CardEditPacket.addEntry(recorder, card, CardProgramStorage.read(card, registries), registries,
				new CardEditPacket(CardEditPacket.Action.ADD_ENTRY, BlockPos.ZERO, Direction.UP, OperationType.MINE));
			gateHolds = countEntries(CardProgramStorage.read(card, registries)) == before;
			recorder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		}
		LOGGER.info("[selftest] 卡级原点: 写键={} 活过往返={} (0,0,0)算设过={} 写null⇒读回未设={} "
			+ "写null⇒键消失={} 文本不含 @origin={} 程序没被碰={} 没设⇒录制被拒={}", originKeyWritten, originKept,
			zeroIsSet, unsetKept, originKeyGone, headerNotInText, programSurvived, gateHolds);
		if (!originKeyWritten)
			LOGGER.error("[selftest] 原点没有写进卡（键 ArmProgramOrigin 缺失）—— 录制换算会永远被 card.need_origin 拒掉");
		if (!originKept)
			LOGGER.error("[selftest] 卡级原点过不了落盘往返（读回来 {}）",
				String.valueOf(CardProgramStorage.readOrigin(card)));
		if (!zeroIsSet)
			LOGGER.error("[selftest] 「原点设在 (0,0,0)」读回来变成「没设」—— 哨兵值把合法值吞了");
		if (!unsetKept)
			LOGGER.error("[selftest] 写 null 之后读回来仍有原点 —— 「删掉键」没有生效");
		if (!originKeyGone)
			LOGGER.error("[selftest] 写 null 只是把值改成 0，键还在 —— 「设过 (0,0,0)」与「没设」落盘后就分不清了");
		if (!headerNotInText)
			LOGGER.error("[selftest] 卡片文本里出现了 @origin —— 原点又跟着脚本走了（用户第四十轮要求它只住在卡上）");
		if (!programSurvived)
			LOGGER.error("[selftest] 写卡级原点把卡上的程序弄没了 —— writeOrigin 应当只动它自己那个键");
		if (!gateHolds)
			LOGGER.error("[selftest] 卡上没有原点时录制竟然成功了 —— 没有参考系就没有相对坐标，"
				+ "这条门禁（card.need_origin）必须读卡上的键");

		// ——— ③ 插卡不做任何平移：两条位置不同的臂，offset 必须逐条相同 ———
		BlockPos nearArm = new BlockPos(120, 70, -300);
		BlockPos farArm = new BlockPos(-40, 12, 77);
		boolean cardInserted = false;
		boolean offsetsIdentical = false;
		boolean worldFollowsArm = false;
		int compared = 0;
		String detail = "";

		ItemStack loadable = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		ArmProgram onCard = DevPresets.depotAB();
		CardProgramStorage.write(loadable, onCard, registries);
		// 卡上的原点（**卡级**属性，用户第四十轮）故意设成一个**与两条臂都不同**的位置：只要有人
		// 把"卡上原点"或"臂的位置"掺进 offset，下面两条断言必红（原点设成 (0,0,0) 时"平移 0"
		// 看不出来 ⇒ 假绿）。
		CardProgramStorage.writeOrigin(loadable, new BlockPos(7, 64, -9));

		List<BlockPos> cardOffsets = new ArrayList<>();
		for (ArmProgramTask task : onCard.tasks)
			for (ArmProgramEntry entry : task.entries)
				cardOffsets.add(entry.offset);

		SequencedArmBlockEntity first = freshArm(level, nearArm);
		SequencedArmBlockEntity second = freshArm(level, farArm);
		if (first == null || second == null) {
			detail = "有臂方块实体没建出来";
		} else {
			ServerPlayer probe = fakePlayer(level);
			boolean firstOk = probe != null
				&& SequencedArmBlock.loadProgramFromCard(first, loadable, probe, level) != null
				&& !first.getProgram()
					.isEmpty();
			ItemStack secondCard = loadable.copy();
			boolean secondOk = probe != null
				&& SequencedArmBlock.loadProgramFromCard(second, secondCard, probe, level) != null
				&& !second.getProgram()
					.isEmpty();
			cardInserted = firstOk && secondOk;

			List<BlockPos> firstOffsets = new ArrayList<>();
			List<BlockPos> secondOffsets = new ArrayList<>();
			for (ArmProgramTask task : first.getProgram().tasks)
				for (ArmProgramEntry entry : task.entries)
					firstOffsets.add(entry.offset);
			for (ArmProgramTask task : second.getProgram().tasks)
				for (ArmProgramEntry entry : task.entries)
					secondOffsets.add(entry.offset);

			offsetsIdentical = cardInserted && firstOffsets.size() == cardOffsets.size()
				&& secondOffsets.size() == cardOffsets.size();
			if (offsetsIdentical) {
				for (int i = 0; i < cardOffsets.size(); i++) {
					if (!cardOffsets.get(i)
						.equals(firstOffsets.get(i))
						|| !cardOffsets.get(i)
							.equals(secondOffsets.get(i))) {
						offsetsIdentical = false;
						detail = "第 " + i + " 条：卡上 " + cardOffsets.get(i)
							.toShortString() + " / 近臂 " + firstOffsets.get(i)
								.toShortString() + " / 远臂 " + secondOffsets.get(i)
									.toShortString();
						break;
					}
				}
			}
			// ★ 世界目标按**每条臂自己**算 —— 用**真实录制路径**证明，而不是拿公式自己跟自己比：
			//   把原点设到近臂（这正是玩家"对着动力臂按中键"那一下）→ 录一条"近臂自己的位置"
			//   （等价于卡里写 0 0 0）→ offset 必须恰好是 (0,0,0)，世界位置回到近臂，
			//   而给远臂跑同一份程序时世界位置就是远臂自己。用户报的 bug 正是这一条：
			//   以前"卡里写 0 0 0"读成"世界原点"，于是臂跑去挖 (0,0,0)。
			worldFollowsArm = offsetsIdentical && probe != null;
			if (worldFollowsArm) {
				// ① 玩家那一下"对着动力臂按中键设原点" —— 直接走真实的服务端那一支
				CardUiPacket.applyOrigin(probe, loadable, CardProgramStorage.read(loadable, registries), registries,
					nearArm);
				// ② 录一条"目标 = 近臂自己的位置"（= 卡里写 0 0 0）；MINE 不经交互点判定，换算才是被测的那一步
				CardEditPacket.addEntry(probe, loadable, CardProgramStorage.read(loadable, registries), registries,
					new CardEditPacket(CardEditPacket.Action.ADD_ENTRY, nearArm, Direction.UP, OperationType.MINE));
				ArmProgram recorded = CardProgramStorage.read(loadable, registries);
				ArmProgramTask recordedTask = recorded.task(0);
				boolean gotIt = recordedTask != null && !recordedTask.entries.isEmpty();
				BlockPos recordedOffset = gotIt ? recordedTask.entries.get(recordedTask.entries.size() - 1).offset : null;
				// 原点现在读**卡**（它不在程序里了）：这正是"录制换算用的是哪一把尺"的落点
				BlockPos recordedOrigin = CardProgramStorage.readOrigin(loadable);
				boolean originIsThatArm = nearArm.equals(recordedOrigin);
				boolean armItselfIsZero = gotIt && recordedOffset.equals(BlockPos.ZERO);
				boolean worldBackToArm = gotIt && recordedOrigin != null
					&& recordedOrigin.offset(recordedOffset)
						.equals(nearArm);
				// 同一份程序给远臂跑 ⇒ 世界目标换成远臂自己（目标跟着臂走，§19.7）
				boolean otherArmRunsSomewhereElse = gotIt
					&& !farArm.offset(recordedOffset)
						.equals(nearArm.offset(recordedOffset));
				worldFollowsArm = originIsThatArm && armItselfIsZero && worldBackToArm && otherArmRunsSomewhereElse;
				detail = "中键设原点后卡上原点=" + String.valueOf(recordedOrigin) + " 录出的 offset="
					+ (recordedOffset == null ? "无" : recordedOffset.toShortString());
			} else {
				detail = "假玩家不可用，录制往返没测";
			}
			compared = cardOffsets.size();

			level.setBlockAndUpdate(nearArm, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(farArm, Blocks.AIR.defaultBlockState());
		}

		LOGGER.info("[selftest] 插卡不平移: 插卡成功={} 两条臂上 offset 逐条相同={}（比了 {} 条）世界目标=臂位置+offset={} （{}）",
			cardInserted, offsetsIdentical, compared, worldFollowsArm, detail);
		if (!cardInserted)
			LOGGER.error("[selftest] 插卡失败（前提不成立）：cardInserted={} {}", cardInserted, detail);
		if (compared == 0 && cardInserted)
			LOGGER.error("[selftest] 夹具里一条条目都没有 —— 这条断言等于没测");
		if (!offsetsIdentical)
			LOGGER.error("[selftest] ★ 插卡平移回来了：同一张卡插进两条位置不同的臂，offset 竟然不同（{}）"
				+ " —— 条目坐标必须永远相对执行它的那条臂（PLAN.md §19.7）", detail);
		if (!worldFollowsArm)
			LOGGER.error("[selftest] 世界目标不再是「臂位置 + 偏移」（PLAN.md §19.7）");
	}

	/**
	 * 自检：<b>批量坐标平移</b>（任务页那个「坐标偏移」按钮的全部逻辑，{@link ArmProgram#shiftAllOffsets}）。
	 *
	 * <p>为什么必须有这条：那段逻辑是"一次点下去改一堆条目"的批量破坏性操作，判据写错的表现分两种
	 * 而且都不会报错 —— ① 改多了：等待 / 信号 / 调用那些条目也各多出一个偏移（`.sma` 文本里凭空
	 * 多出一行坐标，玩家之后还会以为那是目标）；② 改少了：只搬了第一个任务，其余任务原地不动
	 * （玩家看到的就是"搬了一半"）。两种都只在游戏里对着卡片才看得出来。
	 *
	 * <p>断言四件事（前三条正是需求里钉的三件）：① 有目标的条目 offset 全部 += Δ；
	 * ② 没有目标的条目 offset **一点没动**；③ **多任务都覆盖到了**（夹具就是两个任务）；
	 * ④ 顺带钉边界：{@code (0,0,0)} 什么都不改（界面靠它决定"不记撤销"）。
	 *
	 * <p><b>怎么让它变红</b>（心里清楚改哪一行会红）：
	 * ① 把 {@code shiftAllOffsets} 里那个 {@code if (!entry.needsTarget()) continue;} 改回
	 * {@code entry.op.requiresTarget()} ⇒ {@code 容器过滤也搬了} 变 false；
	 * ② 把 {@code needsTarget()} 里返回 {@code true} 那半删掉 ⇒ {@code 没有目标条目没被动}
	 * 变 false（手持过滤被当成了有目标）；
	 * ③ 把那个双层循环的外层改成只遍历 {@code tasks.get(0)} ⇒ {@code 多任务一起搬} 变 false；
	 * ④ 把 {@code (0,0,0)} 那道早退删掉 ⇒ {@code 零位移不记一步} 变 false；
	 * ⑤ 把 {@code entry.offset = shift(...)} 改成 {@code entry.offset = new BlockPos(dx,dy,dz)}
	 * （丢掉了原值）⇒ {@code 逐条 +Δ} 变 false。
	 */
	private static void checkShiftAllOffsets() {
		// 夹具：**两个任务**（"多任务都覆盖到了"要两个才测得出），每个任务里混着
		// 有目标（TAKE/MINE）、**条目级**有目标（过滤的容器模式）、无目标（WAIT/SIGNAL）的条目。
		ArmProgram program = new ArmProgram();
		ArmProgramTask first = new ArmProgramTask();
		first.name = "t1";
		ArmProgramEntry take = new ArmProgramEntry();
		take.op = OperationType.TAKE;
		take.offset = new BlockPos(1, 2, 3);
		ArmProgramEntry wait = new ArmProgramEntry();
		wait.op = OperationType.WAIT;
		wait.offset = new BlockPos(7, 0, -7); // 无意义的值：**故意非零**，才分得清"没动"与"本来就是 0"
		// ★ 过滤的铁律：同一份程序里放三条过滤，只有"容器模式"那条有坐标 ——
		//   这就是 needsTarget() 与 requiresTarget() 分家的唯一现场。
		ArmProgramEntry filterHeld = new ArmProgramEntry();
		filterHeld.op = OperationType.FILTER;
		filterHeld.filterSource = ArmProgramEntry.FilterSource.HELD;
		filterHeld.offset = new BlockPos(9, 9, 9); // 故意非零：手持模式没有坐标，它不该被搬
		ArmProgramEntry filterContainer = new ArmProgramEntry();
		filterContainer.op = OperationType.FILTER;
		filterContainer.filterSource = ArmProgramEntry.FilterSource.CONTAINER;
		filterContainer.offset = new BlockPos(2, 0, -3);
		first.entries.add(take);
		first.entries.add(wait);
		first.entries.add(filterHeld);
		first.entries.add(filterContainer);
		ArmProgramTask second = new ArmProgramTask();
		second.name = "t2";
		ArmProgramEntry mine = new ArmProgramEntry();
		mine.op = OperationType.MINE;
		mine.offset = new BlockPos(-4, 0, 5);
		ArmProgramEntry signal = new ArmProgramEntry();
		signal.op = OperationType.SIGNAL;
		signal.offset = new BlockPos(3, 3, 3);
		second.entries.add(mine);
		second.entries.add(signal);
		program.tasks.add(first);
		program.tasks.add(second);

		int expectedTargets = 3; // 取出 + 挖掘 + **容器过滤**
		boolean precondition = take.op.requiresTarget() && mine.op.requiresTarget()
			&& !wait.op.requiresTarget() && !signal.op.requiresTarget() && program.tasks.size() == 2;

		int changed = program.shiftAllOffsets(10, -20, 5);
		// 逐条 +Δ（按**原值 + Δ**算期望，不是写死的数：写死的数在改夹具时会变成"自检自己错了"）
		boolean targetsShifted = take.offset.equals(new BlockPos(11, -18, 8))
			&& mine.offset.equals(new BlockPos(6, -20, 10));
		// ★ 条目级判据的两条对账：容器过滤跟着搬、手持过滤一点不动
		boolean containerFilterShifted = filterContainer.offset.equals(new BlockPos(12, -20, 2));
		boolean heldFilterUntouched = filterHeld.offset.equals(new BlockPos(9, 9, 9));
		// 无目标条目**一点没动**（这正是"只动需要目标的条目"那条取舍的落点）
		boolean othersUntouched = wait.offset.equals(new BlockPos(7, 0, -7))
			&& signal.offset.equals(new BlockPos(3, 3, 3)) && heldFilterUntouched;
		// 多任务都覆盖到了：两个任务各有至少一条被搬过（第一个任务那条）。
		boolean allTasksCovered = take.offset.getX() == 11 && mine.offset.getX() == 6;
		boolean countRight = changed == expectedTargets;
		BlockPos beforeZero = second.entries.get(0).offset;
		int zeroChanged = program.shiftAllOffsets(0, 0, 0);
		boolean zeroIsNoop = zeroChanged == 0 && second.entries.get(0).offset.equals(beforeZero);

		LOGGER.info("[selftest] 坐标偏移: 有目标条目+Δ={} 容器过滤也搬了={} 没目标条目没被动={} 多任务一起搬={} "
			+ "计数正确={} 零位移不记一步={}", targetsShifted, containerFilterShifted, othersUntouched,
			allTasksCovered, countRight, zeroIsNoop);
		if (!precondition)
			LOGGER.error("[selftest] 坐标偏移自检**没跑起来**：夹具的动作分类与预设不符"
				+ "（requiresTarget 改了？这条等于没测）");
		if (!(targetsShifted && containerFilterShifted && othersUntouched && allTasksCovered && countRight
			&& zeroIsNoop))
			LOGGER.error("[selftest] 批量坐标平移有断言为假：有目标条目+Δ={}（应为 11,-18,8 / 6,-20,10）"
				+ " 容器过滤也搬了={}（应为 12,-20,2） 没目标条目没被动={}（应为 7,0,-7 / 3,3,3 / 9,9,9）"
				+ " 多任务一起搬={} 计数={}（应为 {}）"
				+ " 零位移不记一步={} —— 这条一旦为假，游戏里就是「搬了一半」或「等待条目也多了个坐标」",
				targetsShifted, containerFilterShifted, othersUntouched, allTasksCovered, changed, expectedTargets,
				zeroIsNoop);
	}

	/** 假玩家（自检里点界面的那些路径共用）；不可用时返回 null 并说一声，不把自检判红。 */
	@Nullable
	private static ServerPlayer fakePlayer(ServerLevel level) {
		try {
			return FakePlayerFactory.getMinecraft(level);
		} catch (Throwable unavailable) {
			LOGGER.warn("[selftest] 假玩家不可用（{}）", unavailable.toString());
			return null;
		}
	}

	/** 一份程序里的条目总数（"这一下录进去了没有"的判据；条目是散在各任务里的，不能只看 tasks.size）。 */
	private static int countEntries(ArmProgram program) {
		return program.tasks.stream()
			.mapToInt(task -> task.entries.size())
			.sum();
	}

	/**
	 * 编辑器保存路径的自检（阶段 3c）。界面本身没法在专用服务器上点，但**服务端那一半必须可信**：
	 * 编辑器改完的程序是通过 {@link ArmProgramEditPacket#applyTo} 落盘的，这条路走错
	 * （漏判规模上限、把原卡写坏、主手已经没卡还照写）在游戏里表现为"改完重进就变形"，
	 * 编译期一点提示都没有。所以这里用 NeoForge 的假玩家把三种结果都跑一遍。
	 *
	 * <p>顺带确认菜单类型真的注册上了 —— 漏掉 {@code MENUS.register(bus)} 的话，
	 * 玩家右键卡时会开不出界面，而服务端启动日志一切正常。
	 */
	private static void checkEditorSavePath(MinecraftServer server) {
		ServerLevel level = server.overworld();
		HolderLookup.Provider registries = server.registryAccess();

		boolean menuRegistered = BuiltInRegistries.MENU
			.containsKey(ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "arm_program"));

		ServerPlayer probe;
		try {
			probe = FakePlayerFactory.getMinecraft(level);
		} catch (Throwable t) {
			LOGGER.warn("[selftest] 编辑器保存自检跳过：假玩家不可用（{}）", t.toString());
			return;
		}

		// ① 正常保存：改动必须落到卡上，且往返无损
		ArmProgram edited = DevPresets.mainWithSubTasks();
		edited.tasks.get(0).entries.get(0).delay = 37;
		ItemStack card = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		// 卡上先设一个**非零**原点：编辑器保存（整份程序 → NBT → 文本）**绝不能**把它顶掉或丢掉
		// —— 用户第四十轮把原点搬上卡，这条正是那个"搬家"的落点。
		CardProgramStorage.writeOrigin(card, new BlockPos(30, 66, -12));
		probe.setItemInHand(InteractionHand.MAIN_HAND, card);
		ArmProgramEditPacket.SaveResult first = ArmProgramEditPacket.applyTo(probe, edited.save(registries));
		ArmProgram saved = CardProgramStorage.read(card, registries);
		// ⚠️ 规模上限是**配置项**（`maxEntriesTotal` / `maxTasks`，见 §11 决策 69 的后续）：
		// 玩家把它调到装不下这个预设时，`first` 就是 OVER_LIMIT、卡上**什么都没写** ⇒ `saved` 是空程序。
		// 这一条原来直接 `.get(0)`，于是"配置值本身合法"（`defineInRange` 声明 1..4096）却把**服务端打崩**
		// ——而且崩在**每次开服**，不是玩家编辑卡片的时候（实测：`maxEntriesTotal = 2` ⇒
		// `IndexOutOfBoundsException` @ `SequencedArmSelfTest.java:1494` + crash-report）。
		// 所以先判"卡上到底读出了东西没有"，再谈里面的字段。
		boolean savedReadable = !saved.tasks.isEmpty() && !saved.tasks.get(0).entries.isEmpty();
		// ⚠️ 这里原来还比"任务级循环（loop / loopN）**随卡往返**" —— 那两个字段已删除
		//   （用户口径：主任务恒为无限循环，循环次数不需要了），那半句断言随之消失；
		//   剩下的"delay 真的落盘了"一字未改。
		boolean applied = savedReadable && saved.tasks.get(0).entries.get(0).delay == 37;
		boolean lossless = saved.save(registries)
			.equals(edited.save(registries));
		// P1（§19）：`program.absolute` 那个"坐标空间"标记已删。用户第四十轮起原点是**卡级**属性，
		// 编辑器保存走的是"整份程序 → 文本 → 卡"，所以这里改钉"卡上的原点**没被动过**"
		// （旧版钉的是"原点过 NBT 往返"，那个字段已经不在模型里了）。
		BlockPos originAfterSave = CardProgramStorage.readOrigin(card);
		boolean originUntouched = new BlockPos(30, 66, -12).equals(originAfterSave);

		// ② 超限必须整份拒绝，且不能把原来那张卡改坏
		ArmProgram tooBig = new ArmProgram();
		for (int i = 0; i <= ArmProgram.maxTasks(); i++) {
			ArmProgramTask task = new ArmProgramTask();
			task.name = "t" + i;
			tooBig.tasks.add(task);
		}
		ArmProgramEditPacket.SaveResult second = ArmProgramEditPacket.applyTo(probe, tooBig.save(registries));
		boolean unchanged = CardProgramStorage.read(card, registries)
			.save(registries)
			.equals(saved.save(registries));

		// ③ 主手没卡时必须拒
		probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		ArmProgramEditPacket.SaveResult third = ArmProgramEditPacket.applyTo(probe, edited.save(registries));

		LOGGER.info("[selftest] 编辑器保存: 菜单已注册={} 落盘={} 无损={} 卡上原点没被动过={} 超限被拒={} 原卡未动={} 空手被拒={}",
			menuRegistered, applied, lossless, originUntouched, second == ArmProgramEditPacket.SaveResult.OVER_LIMIT,
			unchanged, third == ArmProgramEditPacket.SaveResult.NOT_HOLDING);

		if (!menuRegistered)
			LOGGER.error("[selftest] 菜单类型未注册 —— 右键卡开不出界面");
		if (first != ArmProgramEditPacket.SaveResult.OK || !applied || !lossless || !originUntouched)
			LOGGER.error("[selftest] 编辑器保存路径坏了：first={} applied={} lossless={} 卡上原点={}", first, applied,
				lossless, String.valueOf(originAfterSave));
		if (second != ArmProgramEditPacket.SaveResult.OVER_LIMIT || !unchanged)
			LOGGER.error("[selftest] 超限没有整份拒绝，或把原卡写坏了：second={} unchanged={}", second, unchanged);
		if (third != ArmProgramEditPacket.SaveResult.NOT_HOLDING)
			LOGGER.error("[selftest] 主手无卡时应当拒绝保存，实际={}", third);
	}

	/**
	 * 语义迁移的自检：v1 → v2（§7.7 改版）→ v3（两条分支各带落点）都必须读出原来的意图。
	 *
	 * <p>为什么值得测：这是<b>唯一会让玩家存档"看起来还在、行为却变了"</b>的地方
	 * —— v1 的 {@code force/forceTimeout}（重试到限时）与 {@code onFail}（立刻失败时怎么办）
	 * 在 v2 里合并成了「时限 + 超时后」；v3 又把共用的 {@code skip} 拆成
	 * 「成功后落点 / 超时后落点」。迁移错了不会报任何错，只会让老程序的跳转静默消失。
	 */
	private static void checkEntryMigration() {
		CompoundTag v1 = new CompoundTag();
		v1.putString("op", "TAKE");
		v1.put("off", NbtUtils.writeBlockPos(new BlockPos(2, 0, 0)));
		v1.putBoolean("force", true);
		v1.putInt("ftimeout", 150);
		v1.putString("fail", "SKIP_TO"); // v1：立刻失败就跳
		v1.putString("skip", "3");

		ArmProgramEntry migrated = ArmProgramEntry.load(v1, RegistryAccess.EMPTY);
		boolean timeoutKept = migrated.failAmount == 150;
		boolean jumpKept = migrated.onTimeout == ArmProgramEntry.SkipAction.SKIP_TO
			&& migrated.timeoutTarget.equals("3");

		// 已经写过 onTimeout 的 v1 卡片：以它为准，不被 onFail 覆盖
		CompoundTag v1Both = v1.copy();
		v1Both.putString("timeout", "CONTINUE");
		ArmProgramEntry both = ArmProgramEntry.load(v1Both, RegistryAccess.EMPTY);
		boolean explicitWins = both.onTimeout == ArmProgramEntry.SkipAction.CONTINUE;

		// v3 自己写的标签：两条分支的落点各存各的、往返无损
		ArmProgramEntry fresh = new ArmProgramEntry();
		fresh.failAmount = 33;
		fresh.onSuccess = ArmProgramEntry.SkipAction.SKIP_TO;
		fresh.onTimeout = ArmProgramEntry.SkipAction.SKIP_TO;
		fresh.successTarget = "task2";
		fresh.timeoutTarget = "4";
		ArmProgramEntry roundTrip = ArmProgramEntry.load(fresh.save(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
		boolean v3RoundTrip = roundTrip.failAmount == 33 && roundTrip.onSuccess == fresh.onSuccess
			&& roundTrip.onTimeout == fresh.onTimeout && roundTrip.successTarget.equals("task2")
			&& roundTrip.timeoutTarget.equals("4");
		boolean distinct = !roundTrip.successTarget.equals(roundTrip.timeoutTarget);

		LOGGER.info("[selftest] v1→v2→v3 迁移: 时限保留={} 跳转保留={} 显式优先={} v3往返={} 两落点独立={}", timeoutKept,
			jumpKept, explicitWins, v3RoundTrip, distinct);
		if (!timeoutKept || !jumpKept || !explicitWins || !v3RoundTrip || !distinct)
			LOGGER.error("[selftest] 迁移坏了：timeoutKept={} jumpKept={} explicitWins={} v3RoundTrip={} distinct={}",
				timeoutKept, jumpKept, explicitWins, v3RoundTrip, distinct);
	}

	/**
	 * 图标自检（§5.2 的"虚拟物品图标"）。
	 *
	 * <p>为什么值得测：默认图标是<b>按物品 id 查出来的</b>（Create 的置物台/黄铜手/滑槽…）；
	 * id 写错或上游改名时不会报任何错，只会在卡片上显示一片空白 —— 玩家根本不知道哪里不对。
	 * 顺带验证"自定义图标"能无损存读（它是个 ItemStack，走组件化 NBT，容易写错）。
	 */
	private static void checkIcons() {
		StringBuilder missing = new StringBuilder();
		for (OperationType op : OperationType.values())
			if (ArmIcons.defaultIcon(op)
				.isEmpty())
				missing.append(op)
					.append(' ');
		boolean taskIcon = !ArmIcons.taskIcon()
			.isEmpty();

		ArmProgramEntry entry = new ArmProgramEntry();
		entry.icon = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
		ArmProgramEntry back = ArmProgramEntry.load(entry.save(RegistryAccess.EMPTY), RegistryAccess.EMPTY);
		boolean roundTrip = ItemStack.isSameItemSameComponents(back.icon, entry.icon);
		boolean emptyStaysEmpty = ArmProgramEntry.load(new ArmProgramEntry().save(RegistryAccess.EMPTY),
			RegistryAccess.EMPTY).icon.isEmpty();

		LOGGER.info("[selftest] 图标: 默认缺失=[{}] 任务图标={} 自定义往返={} 空图标仍为空={}", missing.toString()
			.trim(), taskIcon, roundTrip, emptyStaysEmpty);
		if (missing.length() > 0 || !taskIcon || !roundTrip || !emptyStaysEmpty)
			LOGGER.error("[selftest] 图标表有问题：缺失=[{}] 任务图标={} 往返={} 空={}", missing.toString()
				.trim(), taskIcon, roundTrip, emptyStaysEmpty);
	}

	/**
	 * 「任务数上限」判据边界的自检（第二十四轮审计发现的丢数据路径）。
	 *
	 * <p>为什么值得钉：服务端是<b>整份接受或整份拒绝</b>（{@code ArmProgramEditPacket} 只判
	 * {@code isWithinLimits()}），所以"编辑器能不能加到第 {@code maxTasks() + 1} 个"直接决定
	 * "关界面时这一场编辑是否全丢"。编辑器侧的<b>接线</b>由客户端自检
	 * （{@code ArmProgramScreen.taskLimitProblem}，它真的去调 {@code insertTaskAt}）实测；
	 * 这里钉的是<b>模型侧判据</b>：到上限前都放行、到上限即停<b>且这份程序仍然合法</b>
	 * （否则会出现"界面不让加、服务端却要拒"的双重标准）、超出即不合法。
	 *
	 * <p>上限从**配置**读（默认 32）：把 config 里的 {@code maxTasks} 改成 3，这条自检探到的
	 * 上限就必须变成 3 —— 这是"配置真的接上了"的反证入口。
	 */
	private static void checkTaskLimit() {
		int limit = ArmProgram.maxTasks();
		ArmProgram program = new ArmProgram();
		boolean grew = true;
		for (int i = 0; i < limit; i++) {
			grew &= program.canAddTask();
			ArmProgramTask task = new ArmProgramTask();
			task.name = "t" + i;
			program.tasks.add(task);
		}
		// 正好到上限：编辑器不该再放行，但这份程序**仍然是合法的**（服务端应当收下）
		boolean stopsAtLimit = !program.canAddTask() && program.isWithinLimits();
		program.tasks.add(new ArmProgramTask()); // 第 limit + 1 个
		boolean overLimitIllegal = !program.canAddTask() && !program.isWithinLimits();

		LOGGER.info("[selftest] 任务数上限: 配置上限={} 到上限前都放行={} 到上限即停且仍合法={} 超出即不合法={}",
			limit, grew, stopsAtLimit, overLimitIllegal);
		if (!grew || !stopsAtLimit || !overLimitIllegal)
			LOGGER.error("[selftest] 任务数上限的判据坏了：grew={} stopsAtLimit={} overLimitIllegal={}"
				+ " —— 「界面能加但服务端要拒」会让关界面时整场编辑全丢", grew, stopsAtLimit, overLimitIllegal);
	}

	/**
	 * 「剪贴板 → 备注」这条链路必须**先夹到协议上限再编码**（第二十四轮审计发现的断线路径）。
	 *
	 * <p>根因：编码器用的是 {@code writeUtf(line, NOTE_MAX)}，而 MC 的 {@code Utf8String.write}
	 * 对超长字符串是**抛 {@code EncoderException}**、不是截断；编码发生在客户端的 netty 管线里，
	 * 所以"剪贴板抄了一行长文本 + 潜行右键写备注"会把客户端打成断线（{@code String too big}）。
	 * Create 的剪贴板不限制单条文本长度 —— 原来的自检只喂了 {@code applyNotes}（服务端截断那半边），
	 * **正好绕过了会炸的编码器**。
	 *
	 * <p>这里走真协议：夹过的行长文本必须能编码 + 解码回来、内容与夹取口径一致；
	 * 未夹的那条只记录"会不会抛"（那是 MC 自己的行为，不该钉成我们的断言，但值得留在日志里当证据）。
	 */
	private static void checkNotesClip() {
		String longLine = "x".repeat(ArmProgramEntry.NOTE_MAX + 20);
		String clipped = CardNotesPacket.clip(longLine);
		boolean lengthOk = clipped.length() == ArmProgramEntry.NOTE_MAX;

		String decoded = null;
		boolean codecOk = false;
		try {
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
			CardNotesPacket.STREAM_CODEC.encode(buffer, new CardNotesPacket(List.of(clipped)));
			CardNotesPacket back = CardNotesPacket.STREAM_CODEC.decode(buffer);
			decoded = back.lines()
				.isEmpty() ? "" : back.lines()
					.get(0);
			codecOk = clipped.equals(decoded);
		} catch (Throwable t) {
			codecOk = false;
		}

		// 直接问 MC 的原语：超长字符串在 writeUtf 上是**抛异常**还是截断。
		// （不要拿协议编码器去问 —— 它自己已经夹了一道，问出来的永远是"不抛"，那是在量自己的修复，不是在量根因。）
		boolean rawThrows = false;
		try {
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
			buffer.writeUtf(longLine, ArmProgramEntry.NOTE_MAX);
		} catch (Throwable t) {
			rawThrows = true;
		}

		LOGGER.info("[selftest] 剪贴板备注: 夹到上限={} 编码往返一致={} MC 的原语对超长行抛异常={}（这就是断线的根因）", lengthOk,
			codecOk, rawThrows);
		if (!lengthOk || !codecOk)
			LOGGER.error("[selftest] 剪贴板备注没有夹到 {} 字：lengthOk={} codecOk={} decoded='{}'"
				+ " —— 超长行会让编码器抛异常、把客户端打成断线", ArmProgramEntry.NOTE_MAX, lengthOk, codecOk, decoded);
	}

	/**
	 * 臂的库存能力（第二十五轮审计发现的整条落空：全工程没有任何能力注册）。
	 *
	 * <p>PLAN §7.3 要求"通过 capability 暴露给漏斗/滑槽 + 手持格禁抽 + 溢出格可抽"，
	 * 但此前**一处注册都没有**，所以这几条从来没生效过（不会报错，只会"漏斗喂不进去"）。
	 * 这里用真能力查找（{@code level.getCapability}）而不是直接调自己的方法 —— 否则测的是实现，
	 * 不是"别的元件能不能看见它"。
	 *
	 * <p>顺带钉住审计发现的第二条：**溢出里的残堆必须能并入**。旧 `canAccept` 只看"格数 < 10"，
	 * 于是 10 个各装 1 个石头的残堆会被判成满仓，后面的物品直接掉地上 —— 而 §7.3 的规则是"堆叠到溢出"。
	 */
	private static void checkArmInventory(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 60, 200, spawn.getZ() + 60);
		BlockState oldArm = level.getBlockState(armPos);
		boolean capability = false;
		boolean heldProtected = false;
		boolean merged = false;
		boolean overflowDrains = false;
		boolean fullWhenFull = false;
		String detail;
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, armPos, Direction.UP);
				capability = handler != null;
				detail = handler == null ? "getCapability 返回 null" : handler.getSlots() + " 个槽";
				if (handler != null) {
					ItemStack stone = new ItemStack(Items.STONE);
					arm.heldItem = new ItemStack(Items.IRON_PICKAXE);
					// ① 手持格禁抽（工具只能由臂自己的「放入」转移出去）
					heldProtected = handler.extractItem(0, 64, false)
						.isEmpty() && !arm.heldItem.isEmpty();
					// ② 溢出里 10 个各 1 个石头的残堆：必须还能收下（并入已有堆）
					arm.overflow.clear();
					for (int i = 0; i < SequencedArmBlockEntity.MAX_OVERFLOW; i++)
						arm.overflow.add(new ItemStack(Items.STONE, 1));
					boolean roomBefore = arm.canAccept(stone);
					arm.insertIntoInventory(stone.copyWithCount(64));
					merged = roomBefore && arm.overflow.size() == SequencedArmBlockEntity.MAX_OVERFLOW
						&& arm.overflow.get(0)
							.getCount() > 1;
					// ③ 10 格都被**别的物品**占着时才是真的收不下
					fullWhenFull = !arm.canAccept(new ItemStack(Items.DIAMOND));
					// ④ 溢出格可抽：抽空一格后，同一个判据必须变成"收得下"
					ItemStack pulled = handler.extractItem(1, 64, false);
					overflowDrains = !pulled.isEmpty() && arm.overflow.size() == SequencedArmBlockEntity.MAX_OVERFLOW - 1
						&& arm.canAccept(new ItemStack(Items.DIAMOND));
					detail = handler.getSlots() + " 个槽 空位判据=" + roomBefore + " 并堆后首格="
						+ arm.overflow.get(0)
							.getCount() + " 溢出=" + arm.overflow.size() + " 抽出=" + pulled.getCount();
				}
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 臂的库存: 能力已注册={} 手持格禁抽={} 溢出残堆可并入={} 满仓判据正确={} 溢出可抽={} （{}）",
			capability, heldProtected, merged, fullWhenFull, overflowDrains, detail);
		if (!capability)
			LOGGER.error("[selftest] 臂没有暴露物品能力 —— 漏斗/滑槽喂不进料（PLAN §7.3 落空），{}", detail);
		if (!heldProtected)
			LOGGER.error("[selftest] 手持格被外部抽走了 —— 漏斗会把臂手上的工具顺手拿走");
		if (!merged)
			LOGGER.error("[selftest] 溢出里的残堆没有被并入（残堆会被误判成满仓，后面的物品掉地上）：{}", detail);
		if (!fullWhenFull || !overflowDrains)
			LOGGER.error("[selftest] 满仓判据或溢出抽取不对：fullWhenFull={} overflowDrains={}（{}）", fullWhenFull,
				overflowDrains, detail);
	}

	/**
	 * <b>外部输入只能进手持格</b>（用户口径：「给臂的漏斗输入添加一个限制，通过臂只能把物品输入到臂的
	 * 手持槽位，溢出槽位不能被安山 / 黄铜漏斗等方式输入」）。
	 *
	 * <p>{@link SequencedArmItemHandler} 就是暴露给漏斗 / 溜槽 / 管道的那个 {@code IItemHandler}
	 * （槽 0 = 手持、槽 1..10 = 溢出），所以"能不能进来"只需在这里判：
	 * <ul>
	 *   <li>① {@code insertItem(1, 5×铁, false)} 必须**原样退回 5 个**、溢出库存一个都不多；</li>
	 *   <li>② {@code insertItem(0, 5×铁, false)} 必须真的进手持（手持已有同种时并堆）；</li>
	 *   <li>③ {@code isItemValid(1, 任意) == false}、{@code isItemValid(0, …) == true}
	 *       —— 漏斗的**预检**就走这个（有的自动化只问它、有的直接 insert，两道门都要有）；</li>
	 *   <li>④ <b>内部路径仍然能用</b>：{@code insertIntoOverflow(5×铁, false)} 真的进溢出 ——
	 *       否则"臂自己也装不进溢出"会悄悄坏掉（拒收只管**外部**那两道门）；</li>
	 *   <li>⑤ {@code simulate=true} 时 ①②④ 都**一个字节都不改状态</b>。</li>
	 * </ul>
	 *
	 * <p>反证：把 {@code insertItem} 里那句 {@code if (slot != 0) return stack;} 删掉（回到按槽号塞溢出）
	 * ⇒ ① 红；把 {@code isItemValid} 改回 {@code true} ⇒ ③ 红；把内部调用改成走 {@code insertItem(1, …)}
	 * ⇒ ④ 红（外部那道门会把臂自己也挡住）；把 {@code insertHeld} 的 {@code !simulate} 判断去掉
	 * ⇒ ⑤ 红（模拟一次就把东西放进了手持格）。
	 *
	 * <p>夹具用 {@link #freshArm}（与其它夹具同一套「造臂」辅助方法，不另造世界）。
	 */
	private static void checkHeldOnlyInput(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 62, 200, spawn.getZ() + 62);
		BlockState oldArm = level.getBlockState(armPos);
		boolean overflowRejected = false;
		boolean heldAccepted = false;
		boolean heldMerged = false;
		boolean validitySplit = false;
		boolean internalStillWorks = false;
		boolean simulateClean = false;
		String detail;
		try {
			level.getChunkAt(armPos);
			SequencedArmBlockEntity arm = freshArm(level, armPos);
			// 走 capability 拿 handler：这才是漏斗 / 溜槽真正看到的那个实例
			IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, armPos, Direction.UP);
			if (arm == null || handler == null) {
				detail = arm == null ? "臂的方块实体没建出来" : "getCapability 返回 null";
			} else {
				ItemStack iron5 = new ItemStack(Items.IRON_INGOT, 5);
				ItemStack stone = new ItemStack(Items.STONE);
				arm.setHeldItem(ItemStack.EMPTY);
				arm.getOverflow()
					.clear();
				// ① 溢出格拒收：返回值还是 5 个、溢出库存没变
				ItemStack overflowLeft = handler.insertItem(1, iron5.copy(), false);
				overflowRejected = overflowLeft.getCount() == 5 && arm.getOverflow()
					.isEmpty();
				// ③ 预检：溢出 false / 手持 true
				validitySplit = !handler.isItemValid(1, stone) && handler.isItemValid(0, stone);
				// ② 手持格收下（并堆）：先占位再补同一摞
				handler.insertItem(0, iron5.copy(), false);
				boolean firstIntoHand = arm.getHeldItem()
					.getCount() == 5;
				handler.insertItem(0, new ItemStack(Items.IRON_INGOT, 2), false);
				heldMerged = firstIntoHand && arm.getHeldItem()
					.getCount() == 7;
				heldAccepted = !arm.getHeldItem()
					.isEmpty();
				// ④ 内部路径：臂自己往溢出里装（「取出」/「组装」/「收存」用的就是它）
				ItemStack innerLeft = arm.itemHandler()
					.insertIntoOverflow(new ItemStack(Items.STONE, 5), false);
				internalStillWorks = innerLeft.isEmpty() && overflowItems(arm) == 5;
				// ⑤ 模拟不改状态：把三条都再模拟一次，逐项比对前后的手持 / 溢出
				arm.setHeldItem(ItemStack.EMPTY);
				arm.getOverflow()
					.clear();
				ItemStack simOverflow = handler.insertItem(1, iron5.copy(), true);
				ItemStack simHeld = handler.insertItem(0, iron5.copy(), true);
				ItemStack simInner = arm.itemHandler()
					.insertIntoOverflow(new ItemStack(Items.STONE, 5), true);
				simulateClean = simOverflow.getCount() == 5 && simHeld.isEmpty() && simInner.isEmpty()
					&& arm.getHeldItem()
						.isEmpty()
					&& arm.getOverflow()
						.isEmpty();
				detail = "溢出退回=" + overflowLeft.getCount() + " 手持=" + arm.getHeldItem()
					.getCount() + " 内部溢出=" + overflowItems(arm);
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 漏斗输入只进手持格: 溢出拒收={} 手持收下={} 手持并堆={} 预检分流={} 内部路径照常={} "
			+ "模拟不改状态={} （{}）", overflowRejected, heldAccepted, heldMerged, validitySplit, internalStillWorks,
			simulateClean, detail);
		if (!overflowRejected)
			LOGGER.error("[selftest] 外部插入**写进了溢出格** —— 漏斗 / 溜槽能绕过手臂直接塞满溢出库存（{}）", detail);
		if (!heldAccepted || !heldMerged)
			LOGGER.error("[selftest] 外部插入进不了手持格 / 同类不并堆（heldAccepted={} merged={}）—— "
				+ "漏斗连正常的补料都做不到了", heldAccepted, heldMerged);
		if (!validitySplit)
			LOGGER.error("[selftest] isItemValid 没有按槽分流（溢出 true / 手持 false）—— 漏斗的预检会往溢出格里塞");
		if (!internalStillWorks)
			LOGGER.error("[selftest] **臂自己**也装不进溢出了 —— 拒收只管外部两道门，"
				+ "insertIntoOverflow 必须照常可用（「取出」「组装」「收存」全都靠它）：{}", detail);
		if (!simulateClean)
			LOGGER.error("[selftest] simulate=true 改了状态 —— 漏斗模拟一次就把东西放进了臂里：{}", detail);
	}

	/** 溢出库存里现在一共有几个物品（收存 / 外部输入的断言都数它）。 */
	private static int overflowItems(SequencedArmBlockEntity arm) {
		int total = 0;
		for (ItemStack stack : arm.getOverflow())
			total += stack.getCount();
		return total;
	}

	/**
	 * 「收存」自检（用户口径：把手持物品收入臂的**溢出库存**，无动作、无需坐标）。
	 *
	 * <p>夹具与 {@link #checkCountMode} 同一套"造臂 + 真 {@code runtime.tick} ➜ 真结果"的手法
	 * （{@link #runStashEntry}），每拍把 {@code chasedPointProgress} 当作"已到位"。
	 *
	 * <p>六条（前五条是行为、第六条是卡片文案）：
	 * <ol>
	 *   <li>手上 5 个、{@code count=3} **小于等于** ⇒ 溢出 +3、手上剩 2；</li>
	 *   <li>手上 2 个、{@code count=3} **等于** ⇒ 一个都不搬、走失败（「数量不足」），溢出 / 手上都不变；</li>
	 *   <li>手上 5 个、{@code count=3} **等于** ⇒ 溢出 +3、手上剩 2；</li>
	 *   <li>溢出塞满（10 格全是别的物品）⇒ {@code count=3} 小于等于失败（「溢出库存已满」）、手上不变；</li>
	 *   <li>空手 ⇒ 失败（「手上没有物品」）；</li>
	 *   <li>卡片第一行是「收存 最多 64 个」—— 服务端读不到译文，断的是**送给客户端渲染的键与参数**。</li>
	 * </ol>
	 *
	 * <p>反证：把 {@code stash} 里的 {@code insertIntoOverflow} 换成 {@code insert(...)} ⇒ ① 红
	 * （那一摞并回手持、溢出还是 0）；把"等于模式先模拟"删掉 ⇒ 溢出塞满那条会用部分成功冒充成功；
	 * 把"扣手上"删掉 ⇒ 物品凭空多一份；把 {@code hasCount()} 里的 STASH 删掉 ⇒ ⑥ 红。
	 */
	private static void checkStash(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 96, 200, spawn.getZ() + 96);
		BlockState oldArm = level.getBlockState(armPos);
		boolean atMostMoves = false;
		boolean exactShortFails = false;
		boolean exactMoves = false;
		boolean fullFails = false;
		boolean emptyHandFails = false;
		boolean cardAtMost64 = false;
		String detail;
		try {
			level.getChunkAt(armPos);
			SequencedArmBlockEntity arm = freshArm(level, armPos);
			if (arm == null) {
				detail = "臂的方块实体没建出来";
			} else {
				arm.setSpeed(32); // 转速为 0 时解释器整 tick 跳过（与 checkCountMode 同一条纪律）
				// ① 手上 5 个、count=3 小于等于 ⇒ 溢出 +3、手上剩 2
				clearArm(arm);
				arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 5));
				runStashEntry(arm, 3, ArmProgramEntry.CountMode.ATMOST, () -> arm.getHeldItem()
					.getCount() != 5, 80);
				atMostMoves = overflowItems(arm) == 3 && arm.getHeldItem()
					.getCount() == 2;
				// ② 手上 2 个、count=3 等于 ⇒ 一个都不搬、走失败
				clearArm(arm);
				arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 2));
				runStashEntry(arm, 3, ArmProgramEntry.CountMode.EXACT, () -> false, 80);
				String shortFailure = arm.runtime.getLastFailure();
				exactShortFails = overflowItems(arm) == 0 && arm.getHeldItem()
					.getCount() == 2 && shortFailure != null && shortFailure.contains("数量不足");
				// ③ 手上 5 个、count=3 等于 ⇒ 溢出 +3、手上剩 2
				clearArm(arm);
				arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 5));
				runStashEntry(arm, 3, ArmProgramEntry.CountMode.EXACT, () -> arm.getHeldItem()
					.getCount() != 5, 80);
				exactMoves = overflowItems(arm) == 3 && arm.getHeldItem()
					.getCount() == 2;
				// ④ 溢出塞满（10 格各一摞**别的**物品）⇒ count=3 小于等也应该失败、手上不变
				//   ⚠️ 必须用**别的物品**把 10 格占满：同种物品会先并堆，那样不算"满"。
				clearArm(arm);
				for (int i = 0; i < SequencedArmBlockEntity.MAX_OVERFLOW; i++)
					arm.getOverflow()
						.add(new ItemStack(Items.STONE, 64));
				arm.setHeldItem(new ItemStack(Items.IRON_INGOT, 5));
				runStashEntry(arm, 3, ArmProgramEntry.CountMode.ATMOST, () -> false, 80);
				String fullFailure = arm.runtime.getLastFailure();
				fullFails = arm.getHeldItem()
					.getCount() == 5 && fullFailure != null && fullFailure.contains("溢出库存已满");
				// ⑤ 空手 ⇒ 失败
				clearArm(arm);
				runStashEntry(arm, 3, ArmProgramEntry.CountMode.ATMOST, () -> false, 80);
				String emptyFailure = arm.runtime.getLastFailure();
				emptyHandFails = overflowItems(arm) == 0 && emptyFailure != null && emptyFailure.contains("手上没有物品");
				// ⑥ 卡片第一行「收存 最多 64 个」：服务端读不到客户端译文（那是 assets 资源），
				//   所以断的是**送给客户端渲染的那两样东西**：键（chip.count_atmost ⇒ 客户端写「最多」）
				//   + 参数（defaultCount(STASH) = 64）。两件合起来与"含「最多 64 个」"等价，且与语言无关。
				Component stashCount = ArmEntryText.countText(ArmProgramEntry.fresh(OperationType.STASH));
				if (stashCount.getContents() instanceof TranslatableContents translatable) {
					Object[] args = translatable.getArgs();
					cardAtMost64 = translatable.getKey()
						.endsWith("chip.count_atmost") && args.length == 1
						&& String.valueOf(args[0])
							.equals(String.valueOf(ArmProgramEntry.defaultCount(OperationType.STASH)));
				}
				detail = "手上余=" + arm.getHeldItem()
					.getCount() + " 溢出=" + overflowItems(arm) + " 失败原因=" + text(shortFailure) + " / "
					+ text(fullFailure) + " / " + text(emptyFailure) + " 卡片=" + stashCount.getString();
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 收存: 小于等于按上限搬={} 等于·不够就失败={} 等于·够数搬够={} 溢出满就失败={} "
			+ "空手失败={} 卡片「最多 64 个」={} （{}）", atMostMoves, exactShortFails, exactMoves, fullFails,
			emptyHandFails, cardAtMost64, detail);
		if (!atMostMoves)
			LOGGER.error("[selftest] 「收存 · 小于等于」没有把 min(手上, count) 挪进溢出（手上 5、个数 3 ⇒ 溢出 3、手上 2）：{}",
				detail);
		if (!exactShortFails)
			LOGGER.error("[selftest] 「收存 · 等于」在手上不足时动了东西（手上 2、个数 3 ⇒ 应当一个都不搬并走失败）：{}",
				detail);
		if (!exactMoves)
			LOGGER.error("[selftest] 「收存 · 等于」没有恰好搬 3 个（手上 5 ⇒ 溢出 3、手上 2）：{}", detail);
		if (!fullFails)
			LOGGER.error("[selftest] 溢出塞满时「收存」没有失败（应当报「溢出库存已满」且手上不变）：{}", detail);
		if (!emptyHandFails)
			LOGGER.error("[selftest] 空手时「收存」没有失败（应当报「手上没有物品」）：{}", detail);
		if (!cardAtMost64)
			LOGGER.error("[selftest] 「收存」的卡片个数文案不是「最多 64 个」（键 / 参数不对）：{}", detail);
	}

	/**
	 * 把一条「收存」条目跑到**第一次有结果**为止 —— 手法与 {@link #runCountEntry} 完全相同
	 * （自检不跑运动学：每拍把 {@code chasedPointProgress} 当作"已到位"）。
	 *
	 * <p>⚠️ 收存**没有坐标**：条目 offset 保持 0（它是"不读坐标"的操作，夹具也必须是模型定义内的状态）。
	 */
	private static void runStashEntry(SequencedArmBlockEntity arm, int count, ArmProgramEntry.CountMode mode,
		BooleanSupplier stop, int maxTicks) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检收存";
		ArmProgramEntry entry = ArmProgramEntry.fresh(OperationType.STASH);
		entry.count = count;
		entry.countMode = mode;
		task.entries.add(entry);
		program.tasks.add(task);
		arm.setProgram(program); // 换程序会 resetToStart（游标归零、错误清空、收回）
		for (int i = 0; i < maxTicks && !stop.getAsBoolean(); i++) {
			arm.chasedPointProgress = 1f;
			arm.runtime.tick(arm);
		}
	}

	/**
	 * 假玩家背包里的东西必须收回来（第二十五轮审计发现的"物品黑洞"），且**按用户定的规则归位**：
	 * <b>手持格空 → 掉落物进手持格；手持格有东西 → 进溢出</b>。
	 *
	 * <p>Create 的 {@code DeployerFakePlayer} 会取消 {@code LivingDropsEvent} 并把掉落物塞进假玩家背包
	 *（`DeployerFakePlayer:114-124`），而 `placeItemBackInInventory` 从 0 号槽开始找位置 ——
	 * 所以"先读主手、再排空其余槽"这个顺序本身就实现了上述规则（空手时第一条掉落正落在主手）。
	 * 反过来的顺序会把刚收进手持格的掉落物覆盖掉（第一版自检抓到过"牛排消失"）。
	 *
	 * <p>三个情形都要钉住：① 手上有工具 + 别处有掉落；② 空手 + 一条掉落；③ 空手 + 两条掉落
	 *（第一条进手持、第二条进溢出 —— 这才能证明"否则进溢出"那一半）。
	 */
	private static void checkFakePlayerDrain(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 64, 200, spawn.getZ() + 64);
		BlockState oldArm = level.getBlockState(armPos);
		boolean dropToOverflow = false;
		boolean toolKept = false;
		boolean dropToHand = false;
		boolean secondDropToOverflow = false;
		String detail;
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
				arm.heldItem = pickaxe.copy();
				arm.overflow.clear();
				DeployerFakePlayer fake = arm.getFakePlayer();
				fake.getInventory()
					.setItem(5, new ItemStack(Items.PORKCHOP, 3));
				fake.getInventory()
					.setItem(0, pickaxe.copy());
				arm.syncHeldItemFromFakePlayer();
				toolKept = ItemStack.isSameItemSameComponents(arm.heldItem, pickaxe);
				dropToOverflow = fake.getInventory()
					.getItem(5)
					.isEmpty()
					&& arm.overflow.stream()
						.anyMatch(s -> s.is(Items.PORKCHOP));

				// ② 空手 + 一条掉落（落在主手格）：必须进**手持格**
				arm.heldItem = ItemStack.EMPTY;
				arm.overflow.clear();
				fake.getInventory()
					.setItem(0, new ItemStack(Items.BEEF, 2));
				arm.syncHeldItemFromFakePlayer();
				dropToHand = arm.heldItem.is(Items.BEEF) && arm.heldItem.getCount() == 2
					&& fake.getInventory()
						.getItem(0)
						.isEmpty()
					&& arm.overflow.isEmpty();

				// ③ 空手 + 两条不同的掉落：第一条补进手持，第二条进溢出（"否则进溢出"那一半）
				arm.heldItem = ItemStack.EMPTY;
				arm.overflow.clear();
				fake.getInventory()
					.setItem(0, new ItemStack(Items.BEEF, 2));
				fake.getInventory()
					.setItem(5, new ItemStack(Items.PORKCHOP, 4));
				arm.syncHeldItemFromFakePlayer();
				secondDropToOverflow = arm.heldItem.is(Items.BEEF)
					&& arm.overflow.stream()
						.anyMatch(s -> s.is(Items.PORKCHOP))
					&& fake.getInventory()
						.getItem(5)
						.isEmpty();
				detail = "手持=" + arm.heldItem.getHoverName()
					.getString() + " 溢出=" + arm.overflow.size() + " 格";
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 假玩家背包排空: 手持满→掉落进溢出={} 工具没被误收={} 空手→掉落进手持={} 第二条进溢出={} （{}）",
			dropToOverflow, toolKept, dropToHand, secondDropToOverflow, detail);
		if (!dropToOverflow || !toolKept)
			LOGGER.error("[selftest] 假玩家背包没有排空干净或误收了手持物：dropToOverflow={} toolKept={}（{}）"
				+ " —— 击杀掉落物会随 player.discard() 一起消失", dropToOverflow, toolKept, detail);
		if (!dropToHand)
			LOGGER.error("[selftest] 空手时掉落物没有进手持格（{}）—— 规则是「手持空就补手持」", detail);
		if (!secondDropToOverflow)
			LOGGER.error("[selftest] 手持格被占后第二条掉落物没有进溢出（{}）—— 规则的后半句没生效", detail);
	}

	/**
	 * 结构变换的数学不变量（第二十五轮补上的钩子）。
	 *
	 * <p>臂内部存的是**相对偏移**，蓝图旋转时要把这个偏移一起转（上游 `ArmBlockEntity.transform:424-433`
	 * 对存下来的 `Pos` 调 `transform.applyWithoutOffset`）。这条不写"某个角度应该变成什么"，
	 * 而是钉那条真正的不变量：**旋转两端点之差 == 旋转那个差值**（旋转是线性的，
	 * `R(目标) − R(臂) = R(目标 − 臂)`）。这样换任何角度/镜像都成立，也不依赖我对旋转方向的记忆。
	 */
	private static void checkStructureTransform() {
		BlockPos arm = new BlockPos(10, 64, 10);
		// 偏移带 Z 分量：镜像只翻一个轴，只取 X 分量的话"某种镜像不改变偏移"是正常现象，
		// 那会让这条自检看起来像没生效（第一版就是这么误报的）。
		BlockPos offset = new BlockPos(2, 0, 3);
		BlockPos target = arm.offset(offset);

		StructureTransform rotate = new StructureTransform(BlockPos.ZERO, Direction.Axis.Y, Rotation.CLOCKWISE_90, null);
		boolean rotationMoves = !rotate.applyWithoutOffset(offset)
			.equals(offset);
		boolean rotationConsistent = rotate.apply(target)
			.subtract(rotate.apply(arm))
			.equals(rotate.applyWithoutOffset(offset));

		boolean mirrorMoves = false;
		boolean mirrorConsistent = true;
		for (Mirror mirror : new Mirror[] { Mirror.LEFT_RIGHT, Mirror.FRONT_BACK }) {
			StructureTransform transform = new StructureTransform(BlockPos.ZERO, Direction.Axis.Y, Rotation.NONE, mirror);
			mirrorMoves |= !transform.applyWithoutOffset(offset)
				.equals(offset);
			mirrorConsistent &= transform.apply(target)
				.subtract(transform.apply(arm))
				.equals(transform.applyWithoutOffset(offset));
		}

		LOGGER.info("[selftest] 结构变换（蓝图旋转/镜像）: 旋转确实改变偏移={} 旋转两端点之差一致={} 镜像改变偏移={} 镜像一致={}",
			rotationMoves, rotationConsistent, mirrorMoves, mirrorConsistent);
		if (!rotationMoves || !mirrorMoves)
			LOGGER.warn("[selftest] 结构变换没有改变偏移（上游行为变了？）—— 这条自检的前提需要复核");
		if (!rotationConsistent || !mirrorConsistent)
			LOGGER.error("[selftest] 旋转相对偏移与旋转两端点不等价：旋转一致={} 镜像一致={}"
				+ " —— 蓝图转出来的程序会指错方向", rotationConsistent, mirrorConsistent);
	}

	/**
	 * v3 → v4 迁移里"头插门控条目"必须补偿跳转序号（第二十五轮审计发现的静默错位）。
	 *
	 * <p>`ArmProgramTask.load` 会给带任务级频率的老卡在 0 号位插一条「等待信号」，
	 * 而跳转落点是**1 基序号**（`ProgramRuntime.jumpTo` 直接 `oneBased - 1` 当下标）——
	 * 不补偿的话，老卡上每一处"跳转到 N"都静默指向前一条。这条自检此前**完全没有覆盖**：
	 * 原来的迁移自检只造了条目级标签，从来没走过任务级这条分支。
	 */
	private static void checkLegacyJumpShift() {
		CompoundTag legacyTask = new CompoundTag();
		legacyTask.putString("name", "老任务");
		// ★ 老卡里那两个"任务级循环"键**故意留着**：任务级循环已删除，`ArmProgramTask.load`
		//   现在**直接无视**它们（用户明确不要向后兼容）—— 这一段顺带把"带着它们的卡照样读得进来"
		//   钉住（真删了字段读取之后，这两行只是无害的残留键）。
		legacyTask.putString("loop", "COUNT");
		legacyTask.putInt("loopN", 2);
		ListTag entries = new ListTag();
		entries.add(entryWithJump("2")); // 老卡的"成功后跳转到第 2 条"
		entries.add(entryWithJump("")); // 无跳转：不能被误改
		entries.add(entryWithJump("3")); // "跳转到第 3 条"
		entries.add(entryWithJump("4")); // "N = 条数 + 1" = 立即结束本轮
		legacyTask.put("entries", entries);
		// v3 的任务级频率（{freq, inv} 两个物品：红石火把 + 拉杆）
		ListTag freq = new ListTag();
		freq.add(new ItemStack(Items.REDSTONE_TORCH).saveOptional(RegistryAccess.EMPTY));
		freq.add(new ItemStack(Items.LEVER).saveOptional(RegistryAccess.EMPTY));
		legacyTask.put("freq", freq);

		ArmProgramTask loaded = ArmProgramTask.load(legacyTask, RegistryAccess.EMPTY);
		boolean gateInserted = loaded.entries.size() == 5
			&& loaded.entries.get(0).op == OperationType.SIGNAL
			&& loaded.entries.get(0).frequency != null;
		boolean shifted = "3".equals(loaded.entries.get(1).successTarget)
			&& "".equals(loaded.entries.get(2).successTarget)
			&& "4".equals(loaded.entries.get(3).successTarget)
			&& "5".equals(loaded.entries.get(4).successTarget);

		// 非数字落点（任务名）必须原样保留：迁移只动纯数字
		CompoundTag namedTask = new CompoundTag();
		namedTask.putString("name", "带名字的老任务");
		ListTag namedEntries = new ListTag();
		ArmProgramEntry namedJump = new ArmProgramEntry();
		namedJump.op = OperationType.TAKE;
		namedJump.onSuccess = ArmProgramEntry.SkipAction.SKIP_TO;
		namedJump.successTarget = "收集任务"; // 名字里也可能有数字，但整体不是纯数字
		namedEntries.add(namedJump.save(RegistryAccess.EMPTY));
		namedTask.put("entries", namedEntries);
		namedTask.put("freq", freq.copy());
		ArmProgramTask namedLoaded = ArmProgramTask.load(namedTask, RegistryAccess.EMPTY);
		boolean nameKept = "收集任务".equals(namedLoaded.entries.get(1).successTarget);

		LOGGER.info("[selftest] v3→v4 迁移: 门控已头插={} 数字落点整体+1={} 任务名落点原样={}",
			gateInserted, shifted, nameKept);
		if (!gateInserted)
			LOGGER.error("[selftest] 带频率的老任务没有在开头插入「等待信号」门控（迁移坏了）");
		if (!shifted)
			LOGGER.error("[selftest] 头插门控后数字落点没有 +1 —— 老卡上每一处「跳转到 N」都会指向前一条");
		if (!nameKept)
			LOGGER.error("[selftest] 任务名落点被改动了：{}（迁移只该动纯数字）", namedLoaded.entries.get(1).successTarget);
	}

	private static CompoundTag entryWithJump(String target) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.TAKE;
		entry.onSuccess = ArmProgramEntry.SkipAction.SKIP_TO;
		entry.successTarget = target;
		return entry.save(RegistryAccess.EMPTY);
	}

	/**
	 * 「循环次数放在调用上」的端到端自检（用户第二十七轮定稿的语义）。
	 *
	 * <p>规则：调用点让子任务跑 {@code entry.callRepeat} 轮；**子任务自身的 {@code loop} 不参与**
	 *（否则就是"调用 ×3 × 自己循环 ×2"要相乘、谁都算不清）。这里跑真解释器，直接读
	 * {@code runtime.callStack}（公开字段）里的 {@code repeatsLeft}：子任务每跑完一轮应当**递减**，
	 * 减到 1 之后再跑完一轮才返回调用方。
	 *
	 * <p>子任务体用「等待 0 tick」——每轮一两 tick 就完，测试不必等真实时长。
	 *
	 * <p>**用户第四十轮补的一条**：{@code callRepeat = 0} = 无限循环。这条必须靠"帧一直在、
	 * 剩余次数一直是 0"来判 —— 只要有人把 0 夹成 1（{@code Math.max(1, …)}）或者让
	 * {@code withOneFewerRepeat} 把 0 减成 -1，子任务就会返回，这条自检立刻变红。
	 */
	private static void checkCallRepeats(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 68, 200, spawn.getZ() + 68);
		BlockState oldArm = level.getBlockState(armPos);
		boolean pushedThree = false;
		boolean decremented = false;
		boolean returnedAfterLast = false;
		boolean singleRoundReturns = false;
		boolean infiniteKeepsFrame = false;
		String detail;
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				arm.setSpeed(32);
				// 主任务 = [调用 sub ×3]；sub = [等待 0 tick]
				arm.setProgram(callProgram(3));
				// 压栈不是在第一条 tick：CALL 先 `beginEntry`（不碰世界 ⇒ 直接 EXECUTING），
				// 下一 tick 的 `execute()` 里才 `callTask` 压帧。所以这里要"跑到帧出现为止"。
				for (int i = 0; i < 10 && !pushedThree; i++) {
					arm.runtime.tick(arm);
					if (arm.runtime.callStack.size() == 1)
						pushedThree = arm.runtime.callStack.get(0)
							.repeatsLeft() == 3;
				}
				// 跑到"轮次递减"那一刻（子任务跑完第一轮但还没返回）
				for (int i = 0; i < 60 && !decremented; i++) {
					arm.runtime.tick(arm);
					if (arm.runtime.callStack.size() == 1 && arm.runtime.callStack.get(0)
						.repeatsLeft() == 2)
						decremented = true;
				}
				// 跑到调用栈清空（第三轮跑完 ⇒ 返回调用方）
				for (int i = 0; i < 200 && !arm.runtime.callStack.isEmpty(); i++)
					arm.runtime.tick(arm);
				returnedAfterLast = arm.runtime.callStack.isEmpty();

				// 对照：次数 = 1 时，子任务跑完一轮就该直接返回（不留帧）
				arm.setProgram(callProgram(1));
				boolean sawFrame = false;
				for (int i = 0; i < 60; i++) {
					arm.runtime.tick(arm);
					if (arm.runtime.callStack.size() == 1)
						sawFrame = true;
					else if (sawFrame && arm.runtime.callStack.isEmpty()) {
						singleRoundReturns = true;
						break;
					}
				}
				detail = "次数=3 时首帧=" + 3 + "/实测见递减=" + decremented + " 栈空=" + returnedAfterLast;

				// 对照③：次数 = 0 = 无限循环 —— 子任务跑多少轮都**不许**返回，帧要一直在，
				// 而且剩余次数要一直是 0（被递减、或被夹成 1，都会让这条变红）
				arm.setProgram(callProgram(ArmProgramEntry.CALL_REPEAT_INFINITE));
				boolean sawZeroFrame = false;
				boolean neverSatisfied = true;
				for (int i = 0; i < 120; i++) {
					arm.runtime.tick(arm);
					if (arm.runtime.callStack.size() == 1) {
						sawZeroFrame = true;
						if (arm.runtime.callStack.get(0)
							.repeatsLeft() != ArmProgramEntry.CALL_REPEAT_INFINITE)
							neverSatisfied = false;
					} else if (sawZeroFrame)
						neverSatisfied = false; // 栈空了 = 它返回了 = 不是无限
				}
				infiniteKeepsFrame = sawZeroFrame && neverSatisfied;
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 调用点轮次: 入栈时剩余=3={} 跑完一轮递减到2={} 用尽后返回调用方={} 次数=1 时一轮即返回={} "
			+ "0=无限时一直不返回={} （{}）",
			pushedThree, decremented, returnedAfterLast, singleRoundReturns, infiniteKeepsFrame, detail);
		if (!pushedThree)
			LOGGER.error("[selftest] 调用点没有把次数带进调用帧（{}）—— 「循环次数放在调用上」没生效", detail);
		if (!decremented)
			LOGGER.error("[selftest] 子任务跑完一轮后调用帧的剩余次数没有递减（{}）—— 会无限重跑子任务", detail);
		if (!returnedAfterLast || !singleRoundReturns)
			LOGGER.error("[selftest] 轮次用尽后没有正确返回调用方：returnedAfterLast={} singleRoundReturns={}（{}）",
				returnedAfterLast, singleRoundReturns, detail);
		if (!infiniteKeepsFrame)
			LOGGER.error("[selftest] 次数=0 的「无限循环」没生效（帧被弹掉或剩余次数被递减）—— "
				+ "0 被当成有限次数了（{}）", detail);
	}

	/**
	 * 「调用偏移」的端到端自检（用户口径：给「调用」加一个"偏移"，让同一个任务能被多个调用点复用）。
	 *
	 * <p>语义（照做，别"优化"）：它是**调用点上的一个 Δ** —— 该调用点生效期间，子任务里每个
	 * {@code needsTarget()} 条目的世界目标 = 臂坐标 + 条目自己的 offset + Δ；嵌套叠加
	 *（A→B→C 时 C 里的条目 = Δ1+Δ2）、返回调用方时恢复；<b>子任务自己的 offset 一个字节都不许改</b>
	 *（那正是"可复用"的命门）；零偏移 = 什么都没加。
	 *
	 * <p>为什么必须跑真解释器：这三件事全是运行时行为，写在 GUI 或注释里都没有守卫
	 *（§0.5："断言必须驱动真实入口"）。这里读 {@code runtime.pinOffset(arm)} —— 它走的就是**唯一那个
	 * 咽喉** {@code ProgramRuntime.offsetOf}（世界目标 = 臂坐标 + 它的返回值），再直接读调用栈里的生效偏移。
	 *
	 * <p><b>六条各自怎么变红</b>：
	 * <ol>
	 *   <li>生效偏移 / 世界目标：把 {@code offsetOf} 里那句 {@code .offset(effectiveCallShift())} 删掉，
	 *       或 {@code callTask} 压栈时把偏移写成 {@code BlockPos.ZERO} ⇒ 立红；
	 *   <li>子任务自己的 offset 没被改：让实现"顺手改写子任务"（例如在 {@code callTask} 里遍历 callee
	 *       把 Δ 加进每条的 offset）⇒ 立红 —— 这一条就是"可复用"的命门；
	 *   <li>返回后调用方不受影响：把生效偏移改成"维护一个字段"（压栈时加、弹栈时忘了减）
	 *       ⇒ 回到调用方后它还被 Δ 推着 ⇒ 立红；
	 *   <li>嵌套叠加：压栈那句把 {@code effectiveCallShift()} 换成 ZERO（只留本层的 Δ）⇒ C 里少一层 ⇒ 红；
	 *   <li>零偏移等于没加：让 {@code hasCallShift()} 恒真（save 无条件写 {@code coff} / 反编译无条件写
	 *       {@code shift=}）⇒ 前两条立红；
	 *   <li>存读不丢：把 {@code save()} 里 {@code f.put("s", …)} 删掉（或 load 恒取 ZERO）⇒ 立红。
	 * </ol>
	 */
	private static void checkCallShift(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 84, 200, spawn.getZ() + 84);
		BlockState oldArm = level.getBlockState(armPos);
		BlockPos delta = new BlockPos(3, -1, 2); // 非零、一眼看得出：(2,0,0) + Δ = (5,-1,2)
		BlockPos subOffset = new BlockPos(2, 0, 0);
		BlockPos callerOffset = new BlockPos(1, 0, 0);
		BlockPos delta1 = new BlockPos(2, 0, 0);
		BlockPos delta2 = new BlockPos(-1, 3, 1);
		boolean shiftApplied = false;
		boolean targetMoved = false;
		boolean subOffsetUntouched = false;
		boolean callerRestored = false;
		boolean nested = false;
		boolean zeroEqualsUnset = false;
		boolean nbtKeeps = false;
		BlockPos shiftAtB = null;
		BlockPos nestedPin = null;
		String detail;
		try {
			level.getChunkAt(armPos); // 启动期新放的方块要先加载区块（照 checkAttackAndMine 的前三次踩坑）
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				arm.setSpeed(32);

				// ——— ① 生效偏移 = 调用点的 Δ；② 子任务自己的 offset 没被改 ———
				// 子任务里那条 take **连不上交互点**（臂在天上）⇒ 它一直待在重试窗口里，当前条目稳定停在
				// 它上面（pinOffset 才读得到）—— 这也正好是"跑完之后"那一刻。
				arm.setProgram(callShiftProbe(delta));
				// 压栈不在第一条 tick：CALL 先 beginEntry（不碰世界 ⇒ 直接 EXECUTING），下一 tick 才
				// callTask 压帧（与 checkCallRepeats 同一条时序，不能假设一 tick 就到）。
				for (int i = 0; i < 40 && arm.runtime.callStack.isEmpty(); i++)
					arm.runtime.tick(arm);
				BlockPos wantTarget = subOffset.offset(delta);
				shiftApplied = arm.runtime.effectiveCallShift()
					.equals(delta);
				targetMoved = wantTarget.equals(arm.runtime.pinOffset(arm));
				// 逐条比**子任务自己的**字段（不是"目标算没算对"）：可复用全靠它一个字节都没被动过
				ArmProgramTask subTask = arm.getProgram().tasks.size() > 1 ? arm.getProgram().tasks.get(1) : null;
				subOffsetUntouched = subTask != null && subTask.entries.size() == 1
					&& subTask.entries.get(0).offset.equals(subOffset);
				// 日志要报的是**①那一刻**的现场（后面几段会把程序换掉，跑到最后再读就成了另一个场景的值）
				String firstShift = posText(arm.runtime.effectiveCallShift());
				String firstPin = posText(arm.runtime.pinOffset(arm));

				// ——— ⑥ 存读不丢：把带偏移的调用栈存下、读回，生效偏移不变 ———
				CompoundTag stackTag = arm.runtime.save();
				ProgramRuntime restored = new ProgramRuntime();
				restored.load(stackTag);
				nbtKeeps = restored.effectiveCallShift()
					.equals(delta) && restored.save()
						.equals(stackTag);

				// ——— ③ 返回后调用方不受影响 ———
				// 子任务换成「等待 0 tick」（一两 tick 就跑完 ⇒ 回到调用方的第 2 条 take）。
				arm.setProgram(callShiftReturnProbe(delta, callerOffset));
				boolean backToCaller = false;
				for (int i = 0; i < 120 && !backToCaller; i++) {
					arm.runtime.tick(arm);
					backToCaller = arm.runtime.callStack.isEmpty() && arm.runtime.entryIndex == 1;
				}
				callerRestored = backToCaller && arm.runtime.effectiveCallShift()
					.equals(BlockPos.ZERO) && callerOffset.equals(arm.runtime.pinOffset(arm));

				// ——— ④ 嵌套叠加：main → B(Δ1) → C(Δ2)，C 里条目的生效偏移应当是 Δ1+Δ2 ———
				arm.setProgram(callShiftNestedProbe(delta1, delta2, subOffset));
				for (int i = 0; i < 120 && nestedPin == null; i++) {
					arm.runtime.tick(arm);
					if (arm.runtime.callStack.size() == 1 && shiftAtB == null)
						shiftAtB = arm.runtime.effectiveCallShift();
					else if (arm.runtime.callStack.size() == 2 && nestedPin == null)
						nestedPin = arm.runtime.pinOffset(arm);
				}
				nested = delta1.equals(shiftAtB) && subOffset.offset(delta1)
					.offset(delta2)
					.equals(nestedPin);

				// ——— ⑤ 零偏移 = 什么都没加（存储 / 文本 / 运行时三处等价）———
				ArmProgram zeroProgram = callShiftProbe(BlockPos.ZERO);
				ArmProgramEntry zeroCall = zeroProgram.tasks.get(0).entries.get(0);
				ArmProgramEntry plainCall = new ArmProgramEntry();
				plainCall.op = OperationType.CALL;
				plainCall.callTarget = "sub";
				// ① 存储：零偏移不写 coff 键 ⇒ 与"从没设过这个字段"的条目逐字节相同
				boolean zeroNoKey = zeroCall.save(server.registryAccess())
					.equals(plainCall.save(server.registryAccess()));
				// ② 文本：零偏移不进 .sma（与 repeat=1 / count=1 同一惯例）
				boolean zeroNoText = !ScriptCompiler.decompile(zeroProgram, server.registryAccess())
					.contains("shift=");
				// ③ 运行时：子任务里条目的目标就是条目自己的 offset（不多不少）
				arm.setProgram(zeroProgram);
				for (int i = 0; i < 40 && arm.runtime.callStack.isEmpty(); i++)
					arm.runtime.tick(arm);
				boolean zeroRuntime = arm.runtime.effectiveCallShift()
					.equals(BlockPos.ZERO) && subOffset.equals(arm.runtime.pinOffset(arm));
				zeroEqualsUnset = zeroNoKey && zeroNoText && zeroRuntime;

				detail = "Δ=" + posText(delta) + " 生效=" + firstShift + " 目标=" + firstPin + " B层="
					+ posText(shiftAtB) + " C层=" + posText(nestedPin);
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 调用偏移: 生效偏移=调用点Δ={} 子任务条目目标挪了Δ={} 子任务offset没被改={} "
			+ "返回后调用方不受影响={} 嵌套叠加(Δ1+Δ2)={} 零偏移=没加={} 存读不丢={} （{}）", shiftApplied, targetMoved,
			subOffsetUntouched, callerRestored, nested, zeroEqualsUnset, nbtKeeps, detail);
		if (!shiftApplied)
			LOGGER.error("[selftest] 调用偏移没有生效（栈顶那一帧里的 Δ 不对）—— 子任务的目标不会挪（{}）", detail);
		if (!targetMoved)
			LOGGER.error("[selftest] 子任务里 needsTarget() 条目的世界目标不是「臂坐标 + offset + Δ」——"
				+ "叠加点不在 offsetOf 里、或根本没叠（{}）", detail);
		if (!subOffsetUntouched)
			LOGGER.error("[selftest] 子任务自己的 offset 被改动了 —— 那就不是「调用点上的 Δ」，"
				+ "同一个任务没法被复用（{}）", detail);
		if (!callerRestored)
			LOGGER.error("[selftest] 返回调用方之后偏移没有恢复（调用方条目的目标还带着 Δ）（{}）", detail);
		if (!nested)
			LOGGER.error("[selftest] 嵌套调用没有叠加（C 里条目的生效偏移应当是 Δ1+Δ2）（{}）", detail);
		if (!zeroEqualsUnset)
			LOGGER.error("[selftest] 零偏移与「没设过这个字段」不等价（不该写 NBT 键 / 不该进文本 / 不该改目标）（{}）",
				detail);
		if (!nbtKeeps)
			LOGGER.error("[selftest] 调用偏移过不了 Runtime 的 NBT 往返（臂卸载再载入后子任务的目标会跳回去）（{}）",
				detail);
	}

	/**
	 * 「调用条目的成功后出口」端到端自检（用户报的运行时 bug）。
	 *
	 * <p><b>现场</b>：脚本 `call 炒锅1 ok=goto 14` 跑完**没有**跳到第 14 条，而是继续执行第 9 条
	 * （于是给 `{}+pot` 那一路频率通了电 —— 用户明确看到"这个信号被激活了"），接着死在第 10 条
	 * `take 2 2 -1` 上（那格包裹早被取走交出去了 ⇒ 永远取不到）。同一份脚本里 `call 汤锅1 ok=goto 30`、
	 * `call 烤肉1 ok=goto 10`、`call 蒸笼1 ok=goto 10`、`call 煮茶1 ok=goto 18` 四处是同一个坑。
	 *
	 * <p><b>根因</b>：被调任务**成功**结束时走 {@code ProgramRuntime.endTask(arm, false, null)} 里
	 * {@code !callStack.isEmpty()} 那一支 —— 它把游标恢复成 {@code frame.returnEntry()} 就 return，
	 * **从头到尾没读调用条目的 {@code onSuccess}**（只有失败那一支读 {@code onTimeout}）；而"成功后"的
	 * 普通路径只有条目**自己**跑完才走（{@code succeed}），「调用」条目从来没有"自己跑完"这一刻
	 * ⇒ CALL 上的 {@code ok=} 一直是被**静默忽略**的（界面上它明明画着"成功跳转 N"，文本也照样反编译出来）。
	 *
	 * <p><b>五条断言 + 各自的反证</b>（都跑真解释器）。①②③ 的落点是「抛错」条目 —— 一执行就停机
	 * 且游标不动，于是"停在第几条 + 停机原因"唯一确定了走的是哪个出口。**④⑤ 走两层栈深，落点必须
	 * 停在原地（长等待），不能用抛错**：落点自己抛错 = 走**失败**出口 ⇒ 失败冒泡到调用方 ⇒ 停机那一刻
	 * 游标已经在调用方、栈也弹空了，读到的"栈深=0、游标在 main"是**夹具自己造成的**，不是"没跳"
	 * （这正是 ④ 第一版报红的原因；当时停机原因里的"：B 的成功落点"恰恰证明 B 真的跳到了第 3 条）。
	 * <ol>
	 *   <li>① {@code call B ok=goto 3}（B 立刻成功结束）⇒ 落在第 3 条。反证：删掉 {@code endTask}
	 *       成功支里那句 {@code applySuccessExit}（恢复成只 {@code markProgramDirty()}）⇒ 落在第 2 条，变红。</li>
	 *   <li>② {@code call B}（不写 ok）⇒ 落在调用条目的**下一条**（第 2 条），且不该出现"成功 → 跳转"提示。
	 *       反证：成功返回时把游标退回「调用」条目自己的下标却忘了让它再 {@code ++}（= 又跑一遍同一条调用、
	 *       永不返回），或把 {@code onSuccess == NONE} 也当跳转 ⇒ 变红。</li>
	 *   <li>③ 同一张 {@code ok=goto 3} 的调用卡、B 走失败出口（{@code throw}）⇒ 必须落 {@code fail=goto 4}。
	 *       反证：把成功/失败两支互换、或让失败也走 {@code applySuccessExit} ⇒ 落到第 3 条，变红。</li>
	 *   <li>④ 两层栈深 main → A → B：A 里 {@code call B ok=goto 3}，B **正常跑完**（一条会抛错的都没有）⇒
	 *       A 必须停在**第 3 条**（长等待）、栈深回到 A 的 1（main 那一帧还在）、A 的第 2 / 第 4 条
	 *       一次都没被执行过。反证：删掉 {@code endTask} 成功支里的 {@code applySuccessExit}
	 *       （= 回到"只恢复游标、不读出口"的旧行为）⇒ A 接着跑第 2 条那条抛错 ⇒ 立红；
	 *       把出口判据挪进 {@code finishTask} 的主任务那支 ⇒ 读不到 A 的调用卡 ⇒ 红。</li>
	 *   <li>⑤ **用户现场的直接复现**（{@code run/config/create_sequenced_mechanical_arm/scripts/cookery_test.txt}）：
	 *       主循环 → 判断炒锅 → 炒锅1，判断炒锅**第 8 条** {@code call 炒锅1 ok=goto 14}，第 9 条是
	 *       "占用炒锅2"的 {@code signal send_on freq={} + pot}、第 10 条是 {@code take 2 2 -1}；
	 *       炒锅1 正常结束后必须落在**第 14 条**，且**第 9 / 第 10 条一次都不能被踩到**
	 *       （第 9 条会把 {@code {}+pot} 那一路频率通电 —— 用户看到的就是这个）。
	 *       反证：同 ④（删掉 {@code applySuccessExit}）⇒ 第 9 条那条标记抛错被执行 ⇒ 立红。</li>
	 * </ol>
	 */
	private static void checkCallSuccessExit(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 100, 200, spawn.getZ() + 100);
		BlockState oldArm = level.getBlockState(armPos);
		boolean callJumped = false;
		boolean plainCallContinues = false;
		boolean failedCallUsesTimeout = false;
		boolean nestedCallJumped = false;
		boolean userShapeCallExit = false;
		String detail;
		try {
			level.getChunkAt(armPos); // 启动期新放的方块要先加载区块（照 checkAttackAndMine / checkCallShift）
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				arm.setSpeed(32);

				// ——— ① 成功返回 ⇒ 应用调用卡的「成功后」= 跳转 ———
				CallExitRun okRun = runCallExit(arm, callExitProbe(succeedingSub(), "3", null));
				callJumped = okRun.halted() && okRun.entryIndex() == 2 && okRun.error()
					.contains("成功后跳转的落点") && okRun.sawSuccessHint();

				// ——— ② 不写 ok ⇒ 仍然落在调用条目的下一条（现有行为，不能被改坏）———
				CallExitRun plainRun = runCallExit(arm, callExitProbe(succeedingSub(), null, null));
				plainCallContinues = plainRun.halted() && plainRun.entryIndex() == 1 && plainRun.error()
					.contains("调用条目的下一条") && !plainRun.sawSuccessHint();

				// ——— ③ 子任务失败 ⇒ 还是走「失败后」（成功出口不许抢）———
				CallExitRun failRun = runCallExit(arm, callExitProbe(failingSub(), "3", "4"));
				failedCallUsesTimeout = failRun.halted() && failRun.entryIndex() == 3 && failRun.error()
					.contains("失败后跳转的落点") && !failRun.sawSuccessHint();

				// ——— ④ 两层栈深 main→A→B：B **正常跑完** ⇒ A 必须停在它的 ok=goto 3 落点（第 3 条）———
				// 落点是"长等待"（**不抛错**）：只有停在原地才读得到现场（栈深 / 游标 / 提示三件）。
				// ⚠️ 第一版这里用"落点也抛错"来定位 —— 那条路读到的**不是**"跳没跳"：落点抛错 =
				//   走失败出口 ⇒ 失败冒泡到 main 的调用卡 ⇒ 停机那一刻游标已经在 main、栈也弹空了
				//   （旧红：停机@第 2 条、栈深=0；而停机原因里的"：B 的成功落点"恰恰证明 B 真的跳到了第 3 条）。
				CallParkRun nestedRun = runCallPark(arm, twoLevelCallExitProbe(), 200, 1, 1, 2, 3);
				nestedCallJumped = !nestedRun.halted() && nestedRun.taskIndex() == 1 && nestedRun.entryIndex() == 2
					&& nestedRun.callDepth() == 1 && nestedRun.state() == ProgramRuntime.EntryState.WAITING
					&& nestedRun.sawSuccessHint() && nestedRun.depthWhenVisited(2) == 1
					&& !nestedRun.visited(1) && !nestedRun.visited(3);

				// ——— ⑤ 用户现场（cookery_test.txt）：主循环 → 判断炒锅 → 炒锅1（两层）———
				// 炒锅1 正常结束后，判断炒锅必须落在第 8 条 `call 炒锅1 ok=goto 14` 的落点**第 14 条**，
				// 而且第 9 条（用户脚本里是 `signal send_on freq={} + pot`，把 `{}+pot` 一路通电）与
				// 第 10 条 `take 2 2 -1`（那格包裹早被取走 ⇒ 永远取不到）**一次都没被踩到**。
				CallParkRun userRun = runCallPark(arm, userScriptShapeProbe(), 300, 1, 8, 9, 13);
				userShapeCallExit = !userRun.halted() && userRun.taskIndex() == 1 && userRun.entryIndex() == 13
					&& userRun.callDepth() == 1 && userRun.state() == ProgramRuntime.EntryState.WAITING
					&& userRun.sawSuccessHint() && userRun.depthWhenVisited(13) == 1 && !userRun.visited(8)
					&& !userRun.visited(9);

				detail = "①" + okRun.text() + " ②" + plainRun.text() + " ③" + failRun.text() + " ④" + nestedRun.text()
					+ " ⑤" + userRun.text();
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 调用成功后跳转: call ok=goto 3⇒落第 3 条={} 不写 ok⇒落调用条目下一条={} "
			+ "子任务失败⇒走超时落点={} 两层(主→A→B)B正常跑完⇒A 的 ok=goto 3 生效={} "
			+ "用户现场(主循环→判断炒锅→炒锅1)⇒落第 14 条且第 9/10 条没被执行={} （{}）", callJumped, plainCallContinues,
			failedCallUsesTimeout, nestedCallJumped, userShapeCallExit, detail);
		if (!callJumped)
			LOGGER.error("[selftest] 「调用」上的 ok=goto 没生效（成功返回时没读调用条目的 onSuccess）——"
				+ "用户脚本里的 call X ok=goto N 会被静默忽略（{}）", detail);
		if (!plainCallContinues)
			LOGGER.error("[selftest] 不写 ok 的「调用」没有落在调用条目的下一条（现有行为被改坏了）（{}）", detail);
		if (!failedCallUsesTimeout)
			LOGGER.error("[selftest] 子任务失败时没有走「失败后」（被成功出口抢走了）（{}）", detail);
		if (!nestedCallJumped)
			LOGGER.error("[selftest] 两层栈深（main→A→B）里 B 成功返回后 A 的 ok=goto 没生效 ——"
				+ "A 没有停在它的第 3 条落点（要么弹帧那条路没走 applySuccessExit，要么读错了调用卡）（{}）", detail);
		if (!userShapeCallExit)
			LOGGER.error("[selftest] 用户脚本的形状（主循环→判断炒锅→炒锅1）没复现：炒锅1 正常结束后，判断炒锅必须落在"
				+ "第 14 条，且第 9 条（signal send_on {}+pot 占用炒锅2）与第 10 条（take 2 2 -1）一次都不能被执行"
				+ "（{}）", detail);
	}

	/** 出口探针的一次运行：停没停、停在第几条、停机原因、调用栈深度、期间见没见过"成功 → 跳转"提示。 */
	private record CallExitRun(boolean halted, int entryIndex, String error, int callDepth, boolean sawSuccessHint) {
		String text() {
			return (halted ? "停机@第" + (entryIndex + 1) + "条「" + error + "」" : "没停机@" + entryIndex)
				+ " 栈深=" + callDepth + " 见成功提示=" + sawSuccessHint;
		}
	}

	/**
	 * 跑一个出口探针程序直到停机（或 200 tick 用完），把判据读出来。
	 *
	 * <p>为什么判据是"停机 + 游标 + 停机原因"三件一起：落点全是「抛错」，它一执行就 {@code halt()}，
	 * 而 {@code halt()} **不动游标**（与 {@link #runRoute} 同一条手法）—— 三件合起来才唯一确定走的是哪个出口。
	 * 另外记一个"期间见没见过 {@code 成功 → 跳转}"（{@code applySuccessExit} 写进 lastFailure 的那句），
	 * 它是"走的是成功出口"的第二个独立证人：不写 ok 的那条**必须没有**它。
	 */
	private static CallExitRun runCallExit(SequencedArmBlockEntity arm, ArmProgram program) {
		arm.setProgram(program); // setProgram 会 resetToStart（清 lastFailure / 调用栈）⇒ 三次运行互不串味
		boolean sawSuccessHint = false;
		for (int i = 0; i < 200 && !arm.runtime.isHalted(); i++) {
			arm.runtime.tick(arm);
			String failure = arm.runtime.getLastFailure();
			if (failure != null && failure.startsWith("成功 → 跳转"))
				sawSuccessHint = true;
		}
		String error = arm.runtime.getError();
		return new CallExitRun(arm.runtime.isHalted(), arm.runtime.entryIndex, error == null ? "" : error,
			arm.runtime.callStack.size(), sawSuccessHint);
	}

	/**
	 * 一次**不停机**探针的现场：游标停在哪、栈深多少、期间有没有踩过被盯的那几条、踩到它们时栈深多少。
	 *
	 * <p>为什么需要它（而 {@link CallExitRun} 不够）：那条路要求"落点自己能让程序停下来"，于是落点全是
	 * 「抛错」—— 而在**被调任务**里抛错 = 走失败出口 ⇒ 失败**冒泡到调用方** ⇒ 停机那一刻游标已经在
	 * 调用方了，读出来的是"失败冒泡之后的现场"，不是"跳没跳"。两层栈深的探针必须换成
	 * "落点 = 长等待（停在原地、不回退也不停机）+ 轨迹采样"。
	 */
	private record CallParkRun(boolean halted, int taskIndex, int entryIndex, int callDepth,
		ProgramRuntime.EntryState state, boolean sawSuccessHint, String error, int[] watched, boolean[] seen,
		int[] depthAt) {
		/** 这一条**被踩到过**吗（{@code entry} 是 task 内的 0 基下标）。 */
		boolean visited(int entry) {
			for (int k = 0; k < watched.length; k++)
				if (watched[k] == entry)
					return seen[k];
			return false;
		}

		/** 踩到这一条时的栈深（从没踩到返回 -1）——"被调任务返回后回到调用方的深度"的证人。 */
		int depthWhenVisited(int entry) {
			for (int k = 0; k < watched.length; k++)
				if (watched[k] == entry)
					return seen[k] ? depthAt[k] : -1;
			return -1;
		}

		String text() {
			StringBuilder trace = new StringBuilder();
			for (int k = 0; k < watched.length; k++)
				trace.append(" 第")
					.append(watched[k] + 1)
					.append("条=")
					.append(seen[k] ? "踩过(" + depthAt[k] + "层)" : "没踩");
			return (halted ? "停机@任务" + taskIndex + "第" + (entryIndex + 1) + "条「" + error + "」"
				: "没停机@" + state + " 任务" + taskIndex + "第" + (entryIndex + 1) + "条") + " 栈深=" + callDepth
				+ " 见成功提示=" + sawSuccessHint + "轨迹:" + trace;
		}
	}

	/**
	 * 跑一个"靠停在原地定位"的探针：每 tick **前**后各采一次游标，记录被盯的那几条有没有被踩过、踩到时的栈深。
	 *
	 * <p>为什么要采两次：一次 tick 只推进一条原语，但有些条目**在同一 tick 内**就走完了（例如「发信号」在
	 * {@code execute} 里直接 {@code succeed}）—— 只采 tick 之后那次会漏掉它们；而"抛错"这类条目
	 * 是在 tick 内把游标弹回调用方才停机的，只采之后那次就会读到调用方（= 第一版 ④ 的错法）。
	 * 前后各采一次，两种都抓得住。
	 *
	 * @param watchTask    只在这个任务的游标上采样（0 基任务下标）
	 * @param watchEntries 被盯的条目下标（0 基），日志里按"第 N+1 条"报
	 */
	private static CallParkRun runCallPark(SequencedArmBlockEntity arm, ArmProgram program, int ticks, int watchTask,
		int... watchEntries) {
		arm.setProgram(program); // 同 runCallExit：setProgram 会 resetToStart ⇒ 几次运行互不串味
		boolean[] seen = new boolean[watchEntries.length];
		int[] depthAt = new int[watchEntries.length];
		boolean sawSuccessHint = false;
		for (int i = 0; i < ticks && !arm.runtime.isHalted(); i++) {
			sampleCursor(arm, watchTask, watchEntries, seen, depthAt);
			arm.runtime.tick(arm);
			sampleCursor(arm, watchTask, watchEntries, seen, depthAt);
			String failure = arm.runtime.getLastFailure();
			if (failure != null && failure.startsWith("成功 → 跳转"))
				sawSuccessHint = true;
		}
		String error = arm.runtime.getError();
		return new CallParkRun(arm.runtime.isHalted(), arm.runtime.taskIndex, arm.runtime.entryIndex,
			arm.runtime.callStack.size(), arm.runtime.state, sawSuccessHint, error == null ? "" : error, watchEntries,
			seen, depthAt);
	}

	/** 采一次游标：在盯的任务里且正好停在被盯的某一条上，就记下"踩过"与当时的栈深。 */
	private static void sampleCursor(SequencedArmBlockEntity arm, int watchTask, int[] watchEntries, boolean[] seen,
		int[] depthAt) {
		if (arm.runtime.taskIndex != watchTask)
			return;
		for (int k = 0; k < watchEntries.length; k++) {
			if (arm.runtime.entryIndex == watchEntries[k]) {
				seen[k] = true;
				depthAt[k] = arm.runtime.callStack.size();
			}
		}
	}

	/** 一条「抛错」：落点条目 —— 一执行就停机，游标**留在它自己那一格**（判"落在第几条"的手法）。 */
	private static ArmProgramEntry throwEntry(String message) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.THROW;
		entry.errorMessage = message;
		return entry;
	}

	/** 一条「等待 0 tick」：子任务体（两三 tick 就跑完 ⇒ 立刻成功返回调用方）。 */
	private static ArmProgramEntry waitZeroEntry() {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.WAIT;
		entry.waitAmount = 0;
		entry.waitUnit = ArmProgramEntry.WaitUnit.TICK;
		return entry;
	}

	/** 一条「调用」：目标 + 成功后落点 + 失败后落点（落点传 {@code null} = 不设，即走 NONE）。 */
	private static ArmProgramEntry callExitEntry(String target, @Nullable String okTarget, @Nullable String failTarget) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.CALL;
		entry.callTarget = target;
		entry.callRepeat = 1;
		if (okTarget != null) {
			entry.onSuccess = ArmProgramEntry.SkipAction.SKIP_TO;
			entry.successTarget = okTarget;
		}
		if (failTarget != null) {
			entry.onTimeout = ArmProgramEntry.SkipAction.SKIP_TO;
			entry.timeoutTarget = failTarget;
		}
		return entry;
	}

	/** 一个任务：名字 + 若干条目。 */
	private static ArmProgramTask namedTask(String name, ArmProgramEntry... entries) {
		ArmProgramTask task = new ArmProgramTask();
		task.name = name;
		for (ArmProgramEntry entry : entries)
			task.entries.add(entry);
		return task;
	}

	/** 子任务体：立刻成功结束（一条「等待 0 tick」）。 */
	private static ArmProgramTask succeedingSub() {
		return namedTask("sub", waitZeroEntry());
	}

	/** 子任务体：走失败出口（一条「抛错」）。 */
	private static ArmProgramTask failingSub() {
		return namedTask("sub", throwEntry("子任务抛错"));
	}

	/**
	 * 夹具（单层）：main = [调用 sub（ok/fail 可设）, 抛错「主任务第 2 条（调用条目的下一条）」,
	 * 抛错「主任务第 3 条（成功后跳转的落点）」, 抛错「主任务第 4 条（失败后跳转的落点）」]。
	 *
	 * <p>序号都从 1 数（用户可见的编号体系）：{@code ok=goto 3} ⇒ 落在下标 2；{@code fail=goto 4} ⇒ 落在下标 3；
	 * 不写 ok ⇒ 落在下标 1。三条落点各是一句不同的抛错信息，"落在第几条"因此没有歧义。
	 */
	private static ArmProgram callExitProbe(ArmProgramTask sub, @Nullable String okTarget, @Nullable String failTarget) {
		ArmProgram program = new ArmProgram();
		program.tasks.add(namedTask("main", callExitEntry(sub.name, okTarget, failTarget),
			throwEntry("主任务第 2 条（调用条目的下一条）"), throwEntry("主任务第 3 条（成功后跳转的落点）"),
			throwEntry("主任务第 4 条（失败后跳转的落点）")));
		program.tasks.add(sub);
		return program;
	}

	/** 一条「退出」：正常收任务（走 {@code endTask(arm, false, null)} 那一支 —— 在子任务里 = **成功**返回调用方）。 */
	private static ArmProgramEntry breakEntry() {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.BREAK;
		return entry;
	}

	/**
	 * 一条"停住不动的落点"：120 分钟 = 144000 tick，探针只跑一两百 tick，等于**停在这一条上**。
	 *
	 * <p>为什么落点要这样写（而不是像 ①②③ 那样用「抛错」）：抛错 = 走**失败**出口，在被调任务里它会
	 * 冒泡到调用方，于是停机那一刻游标已经不在落点上了 —— 读不到"到底落没落到这一条"。
	 * 停住的落点则让 游标 / 栈深 / {@code lastFailure} 三件都能直接读，且程序不结束。
	 */
	private static ArmProgramEntry parkedWaitEntry() {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.WAIT;
		entry.waitAmount = 120;
		entry.waitUnit = ArmProgramEntry.WaitUnit.MINUTE;
		return entry;
	}

	/**
	 * 夹具（两层栈深 main → A → B，**用户的形状**）：main = [调用 A]；
	 * A = [调用 B {@code ok=goto 3}, 抛错「A 的下一条（第 2 条）」, 长等待「A 的成功落点（第 3 条）」,
	 * 抛错「A 的第 4 条（成功落点的下一条）」]；B = [等待 0 tick ×2]——**B 一条会抛错的都没有**（正常跑完）。
	 *
	 * <p>A 的第 2 / 第 4 条是"会留下证据的标记"（抛错 ⇒ 一执行就停机），B 返回后 A 只要没跳到第 3 条、
	 * 或者跳到之后又往下走，标记就会立刻把它抓住 —— 这两条同时也是"第 N+1 条没被执行"的证人。
	 */
	private static ArmProgram twoLevelCallExitProbe() {
		ArmProgram program = new ArmProgram();
		program.tasks.add(namedTask("main", callExitEntry("A", null, null)));
		program.tasks.add(namedTask("A", callExitEntry("B", "3", null), throwEntry("A 的下一条（第 2 条，不该被执行）"),
			parkedWaitEntry(), throwEntry("A 的第 4 条（成功落点的下一条，不该被执行）")));
		program.tasks.add(namedTask("B", waitZeroEntry(), waitZeroEntry()));
		return program;
	}

	/**
	 * 夹具（**用户现场的直接复现**：{@code cookery_test.txt} 的主循环 → 判断炒锅 → 炒锅1，两层栈深）。
	 *
	 * <p>条目**位置与条数照抄用户脚本**：判断炒锅第 1..7 条（用户脚本里是「过滤是不是炒锅」/「收信号看占用」）、
	 * <b>第 8 条</b> {@code call 炒锅1 ok=goto 14}、<b>第 9 条</b>（用户脚本是
	 * {@code signal send_on place=0t freq={} + kaleidoscope_cookery:pot}，**"占用炒锅2"**）、
	 * <b>第 10 条</b> {@code take 2 2 -1}（那格包裹早被交出去 ⇒ 永远取不到）、第 11..13 条、
	 * <b>第 14 条</b> = 落点（用户脚本是 {@code break}）。唯一偏离的是"要靠世界才能跑"的那几条：
	 * <ul>
	 *   <li>第 9 条换成一条**会留下证据的抛错**（用户那条 {@code signal send_on} 要红石链接网络，无头自检里
	 *       造不出来；"这一条被执行过"的老手法就是抛错 —— 一执行就停机且游标可读）；</li>
	 *   <li>「过滤 / 收信号」等换成「等待 0 tick」（**正常跑完**，绝不抛错）；
	 *       第 10 条保留真正的 {@code take 2 2 -1}（臂在天上 ⇒ 前置检查永远不过 ⇒ 卡在它上面）；</li>
	 *   <li>第 14 条落点从 {@code break} 改成"长等待"：落点必须**停在原地**才读得到现场
	 *       （{@code break} 会让程序一路走下去 —— 主任务跑完会自动重开）。</li>
	 * </ul>
	 */
	private static ArmProgram userScriptShapeProbe() {
		ArmProgram program = new ArmProgram();
		program.tasks.add(namedTask("主循环", callExitEntry("判断炒锅", null, null), callExitEntry("取炒锅1", null, null)));
		ArmProgramTask judge = namedTask("判断炒锅",
			waitZeroEntry(), waitZeroEntry(), waitZeroEntry(), waitZeroEntry(), waitZeroEntry(), waitZeroEntry(),
			waitZeroEntry(), // 第 1..7 条（用户脚本里是 filter / signal recv —— 要世界，这里用无世界替身）
			callExitEntry("炒锅1", "14", null), // 第 8 条：用户现场 `call 炒锅1 ok=goto 14`
			throwEntry("判断炒锅第 9 条（用户脚本是 signal send_on freq={}+pot 占用炒锅2）—— 不该被执行"),
			takeEntryAt(new BlockPos(2, 2, -1)), // 第 10 条：用户脚本 `take 2 2 -1`
			waitZeroEntry(), waitZeroEntry(), waitZeroEntry(), // 第 11..13 条
			parkedWaitEntry()); // 第 14 条：ok=goto 14 的落点
		program.tasks.add(judge);
		program.tasks.add(namedTask("炒锅1", waitZeroEntry(), breakEntry())); // 正常跑完（用户脚本末条也是 break）
		program.tasks.add(namedTask("取炒锅1", waitZeroEntry()));
		return program;
	}

	/** 调试用：BlockPos → {@code x,y,z}；null（没有目标）→ "无"。 */
	private static String posText(@Nullable BlockPos pos) {
		return pos == null ? "无" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	/** 夹具 A：main = [调用 sub shift=Δ]，sub = [取出 2 0 0]（连不上交互点 ⇒ 一直重试在它上面）。 */
	private static ArmProgram callShiftProbe(BlockPos delta) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask main = new ArmProgramTask();
		main.name = "main";
		main.entries.add(callShiftEntry("sub", delta, 1));
		program.tasks.add(main);
		ArmProgramTask sub = new ArmProgramTask();
		sub.name = "sub";
		sub.entries.add(takeEntryAt(new BlockPos(2, 0, 0)));
		program.tasks.add(sub);
		return program;
	}

	/** 夹具 B（返回后恢复）：同上，但子任务换成「等待 0 tick」，main 第 2 条是取物 {@code callerOffset}。 */
	private static ArmProgram callShiftReturnProbe(BlockPos delta, BlockPos callerOffset) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask main = new ArmProgramTask();
		main.name = "main";
		main.entries.add(callShiftEntry("sub", delta, 1));
		main.entries.add(takeEntryAt(callerOffset));
		program.tasks.add(main);
		ArmProgramTask sub = new ArmProgramTask();
		sub.name = "sub";
		ArmProgramEntry wait = new ArmProgramEntry();
		wait.op = OperationType.WAIT;
		wait.waitAmount = 0;
		wait.waitUnit = ArmProgramEntry.WaitUnit.TICK;
		sub.entries.add(wait);
		program.tasks.add(sub);
		return program;
	}

	/** 夹具 C（嵌套）：main → 调用 B(Δ1)；B = [调用 C(Δ2)]；C = [取出 2 0 0]。 */
	private static ArmProgram callShiftNestedProbe(BlockPos delta1, BlockPos delta2, BlockPos subOffset) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask main = new ArmProgramTask();
		main.name = "main";
		main.entries.add(callShiftEntry("B", delta1, 1));
		program.tasks.add(main);
		ArmProgramTask b = new ArmProgramTask();
		b.name = "B";
		b.entries.add(callShiftEntry("C", delta2, 1));
		program.tasks.add(b);
		ArmProgramTask c = new ArmProgramTask();
		c.name = "C";
		c.entries.add(takeEntryAt(subOffset));
		program.tasks.add(c);
		return program;
	}

	/** 一条「调用」：目标名 + 调用偏移 Δ + 轮次（夹具用，别处不要拿它当"新建条目"）。 */
	private static ArmProgramEntry callShiftEntry(String target, BlockPos shift, int repeats) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.CALL;
		entry.callTarget = target;
		entry.callShift = shift;
		entry.callRepeat = repeats;
		return entry;
	}

	/** 一条「取出」：坐标就是它的 offset。 */
	private static ArmProgramEntry takeEntryAt(BlockPos offset) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.TAKE;
		entry.offset = offset;
		return entry;
	}

	/** 主任务 = [调用 sub ×repeats]；子任务 = [等待 0 tick]（每轮一两 tick 就完）。 */
	private static ArmProgram callProgram(int repeats) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask main = new ArmProgramTask();
		main.name = "main";
		ArmProgramEntry call = new ArmProgramEntry();
		call.op = OperationType.CALL;
		call.callTarget = "sub";
		call.callRepeat = repeats;
		main.entries.add(call);
		program.tasks.add(main);
		ArmProgramTask sub = new ArmProgramTask();
		sub.name = "sub";
		ArmProgramEntry wait = new ArmProgramEntry();
		wait.op = OperationType.WAIT;
		wait.waitAmount = 0;
		wait.waitUnit = ArmProgramEntry.WaitUnit.TICK;
		sub.entries.add(wait);
		// ⚠️ 这里原来还有一句 `sub.loop = FOREVER`（"如果实现里还读子任务的循环，这条自检会立刻暴露"）。
		//   任务级循环整体删除后**连字段都不存在了**，"子任务自己循环"这种组合在模型里不可能出现 ⇒
		//   那条反证随字段一起消失（不是被放松：它证明的东西已经由类型系统保证）。
		program.tasks.add(sub);
		return program;
	}

	/**
	 * 「攻击 / 挖掘」拆分后的端到端自检（用户第二十七轮）。
	 *
	 * <p>要钉的三件事：
	 * ① **挖掘只碰方块** —— 这是拆分的全部理由。本测试把一只羊**正好放在被挖的那一格 AABB 里**：
	 *   旧 `PUNCH` 的"实体优先"会去打它，拆开后的挖掘必须让它毫发无伤（羊的血量不变）。
	 * ② **挖掘真的能挖掉方块** —— 连着挥到破为止（进度由 vanilla 给，我们不在动身前判断能不能挖）。
	 * ③ **攻击打的是那一格里的实体** —— 同一只羊，`attack()` 之后血量必须下降；格里没东西时也**不算失败**
	 *   （照机械手：挥空就是挥空），所以返回值必须是完成。
	 */
	private static void checkAttackAndMine(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 72, 200, spawn.getZ() + 72);
		BlockPos targetPos = armPos.offset(2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldTarget = level.getBlockState(targetPos);
		boolean mineKeepsEntitySafe = false;
		boolean mineBroken = false;
		boolean attackHurt = false;
		boolean attackEmptyIsDone = false;
		boolean sheepInside = false;
		boolean knockbackSourceOk = false;
		String detail;
		net.minecraft.world.entity.animal.Sheep sheep = null;
		BlockPos floorPos = targetPos.below();
		BlockState oldFloor = level.getBlockState(floorPos);
		try {
			// 先把区块加载出来：启动期新加实体会被**挂起**到区块真正加载为止，
			// 于是"生成"成功但查询查不到（前三次自检的前提断言就是这么红的）。
			level.getChunkAt(armPos);
			level.getChunkAt(targetPos);
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(floorPos, Blocks.STONE.defaultBlockState());
			// ⚠️ 目标方块要用**没有碰撞**的那种（铁轨）：羊只有能"站进"那一格，它的碰撞箱才会与
			// 该格的 AABB 重叠。第一版把羊放在实心泥土格上 —— 它被碰撞推到方块上方，
			// 于是"挖掘没碰羊"这条断言其实是空转的（"攻击没打到羊"当场把这件事暴露了）。
			level.setBlockAndUpdate(targetPos, Blocks.RAIL.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				// ⚠️ 用 EntityType.spawn（而不是自己 create + addFreshEntity）：它顺带处理
				// "区块要加载"和"真的进世界"这两件事。第一版自己建实体，结果它压根没进世界 ——
				// 攻击打不到它、"挖掘没碰羊"于是成了空转断言；是下面这条前提断言把它抓出来的。
				sheep = EntityType.SHEEP.spawn(level, targetPos, MobSpawnType.COMMAND);
				if (sheep == null) {
					detail = "羊没生成出来（跳过）";
				} else {
					sheep.setNoAi(true);
					// 前提断言：羊必须真的在那一格里，否则下面两条都没意义（自检也要自证前提）
					sheepInside = !level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
						new AABB(targetPos))
						.isEmpty();
					float healthBefore = sheep.getHealth();

					// ③ 先攻击：血量必须下降（同时反证"羊确实在格里"）
					arm.rightClick.attack(targetPos, Direction.SOUTH);
					attackHurt = sheep.getHealth() < healthBefore;

					// ① 再挖掘：挖到破为止，羊的血量不许变
					float healthAfterAttack = sheep.getHealth();
					for (int i = 0; i < 80 && !level.getBlockState(targetPos)
						.isAir(); i++)
						arm.rightClick.mine(targetPos, Direction.UP);
					mineBroken = level.getBlockState(targetPos)
						.isAir();
					mineKeepsEntitySafe = sheep.getHealth() == healthAfterAttack;

					// ④ 格里没有实体时攻击也必须算完成（挥空 = 挥了一击）
					sheep.discard();
					sheep = null;
					attackEmptyIsDone = arm.rightClick.attack(targetPos, Direction.UP) == RightClickBridge.Result.SUCCESS;

					// ⑤ 击退方向的**来源**：攻击者（假玩家）必须被摆到录下来的那一面之外 ——
					// 方向是 vanilla 用"来源位置 − 实体位置"算的，来源在哪一侧，实体就被推向另一侧。
					// 这里验的是来源摆位（不需要实体，因此不受"启动期实体进不了世界"的影响）。
					boolean sourceOnFaceSide = true;
					for (Direction dir : new Direction[] { Direction.SOUTH, Direction.NORTH, Direction.EAST,
						Direction.WEST }) {
						arm.rightClick.attack(targetPos, dir);
						Vec3 at = arm.getFakePlayer()
							.position();
						double along = at.x * dir.getStepX() + at.z * dir.getStepZ();
						double here = targetPos.getCenter().x * dir.getStepX()
							+ targetPos.getCenter().z * dir.getStepZ();
						if (!(along > here))
							sourceOnFaceSide = false;
					}
					if (!sourceOnFaceSide)
						LOGGER.error("[selftest] 攻击没有把假玩家摆到录下来的那一面之外 —— 击退方向会与臂的朝向无关"
							+ "（用户实测：总是 -x -z）");
					knockbackSourceOk = sourceOnFaceSide;
					detail = "羊在格里=" + sheepInside + " 挖掘后目标=" + (mineBroken ? "已挖掉" : "还在") + " 羊血量="
						+ healthAfterAttack + "→" + (mineKeepsEntitySafe ? "未变" : "被扣了！");
				}
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			if (sheep != null)
				sheep.discard();
			level.setBlockAndUpdate(targetPos, oldTarget);
			level.setBlockAndUpdate(floorPos, oldFloor);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 攻击/挖掘拆分: 前提·羊在那一格里={} 挖掘挖掉了方块={} 挖掘没碰那一格里的羊={} 攻击打到了羊={} 格里没实体时攻击算完成={} 击退来源在录下来的那一面={} （{}）",
			sheepInside, mineBroken, mineKeepsEntitySafe, attackHurt, attackEmptyIsDone, knockbackSourceOk, detail);
		if (!sheepInside)
			LOGGER.warn("[selftest] 「挖掘不碰实体」的行为验证**没做成**：羊没能进到目标格里（{}）。"
				+ "这条自检拒绝在前提不成立时假装通过 —— 该性质目前由代码结构保证（mine() 里一行实体查询都没有），"
				+ "并需要进游戏实测确认", detail);
		if (!mineBroken)
			LOGGER.error("[selftest] 挖掘没能挖掉方块（{}）—— 连着挥 80 次都没破，进度逻辑坏了", detail);
		if (sheepInside && !mineKeepsEntitySafe)
			LOGGER.error("[selftest] 挖掘打到了站在那一格里的实体（{}）—— 这正是 PUNCH 拆开要解决的问题（误伤）", detail);
		if (sheepInside && !attackHurt)
			LOGGER.error("[selftest] 攻击没有打到那一格里的实体（{}）—— 拆开之后攻击只剩打实体这一件事", detail);
		if (!attackEmptyIsDone)
			LOGGER.error("[selftest] 格里没有实体时攻击没有算完成（{}）—— 照机械手：挥空也是挥了一击", detail);
	}

	/**
	 * 「副手剪贴板 = 待办清单」的自检（PLAN.md §5.5，用户第二十七轮）。
	 *
	 * <p>这条功能有两个**静默**的失效方式，都必须靠断言抓：
	 * <ol>
	 *   <li><b>备注写错行</b>：录第 3 条却把清单第 4 行写成备注 —— 游戏里看不出，跑起来才发现顺序全错。
	 *       这里真跑一遍 {@code CardEditPacket.addEntry}，断言"每录一条就吃掉清单的下一行"，
	 *       并在清单录完之后断言不再自动带备注；</li>
	 *   <li><b>勾写回去了但客户端收不到</b>：{@code AbstractContainerMenu.remoteSlots} 里那份副本与手上这份
	 *       **共享同一个组件值对象**，就地改 {@code entry.checked} 会让"变了吗"的判定为否
	 *       ⇒ 客户端永远看到旧勾。本自检因此不只断言"勾对了"，还断言**旧的那个组件对象没被改动**
	 *       （即我们是新建对象写回去的，这样槽位同步才发得出去）。</li>
	 * </ol>
	 *
	 * <p>勾的**范围**也是断言的一部分：已完成的行勾上、当前那一行清掉，再往后的行一律不碰 ——
	 * 这条规则是用户选的方案 A（"勾由项目管，玩家不用手动勾"）的落地口径，改了它就该有人看见。
	 */
	private static void checkClipboardChecklist(MinecraftServer server) {
		// ——— ① 纯投影：不碰世界、不碰物品，先把"第几行算做完了"的判据钉住 ———
		List<String> plan = List.of("挖铁", "放箱子", "打怪");
		ArmProgramTask pure = new ArmProgramTask();
		pure.name = "投影";
		pure.entries.add(noteEntry(OperationType.TAKE, "挖铁"));
		pure.entries.add(noteEntry(OperationType.DEPOSIT, ""));
		pure.entries.add(noteEntry(OperationType.ATTACK, ""));
		int half = ClipboardChecklist.consumed(plan, pure);
		boolean stopsAtGap = half == 1 && "放箱子".equals(ClipboardChecklist.nextNote(plan, pure));
		pure.entries.get(1).note = "放箱子";
		pure.entries.get(2).note = "打怪";
		boolean allMatched = ClipboardChecklist.consumed(plan, pure) == 3
			&& ClipboardChecklist.nextNote(plan, pure)
				.isEmpty()
			&& ClipboardChecklist.allDone(plan, pure);
		// 归一化：真换行 / 字面 \n / 连续空白都应当认得出是同一行
		boolean normalized = ClipboardChecklist.sameLine("第一行\n第二行", "第一行\\n第二行")
			&& ClipboardChecklist.sameLine("第 1  条", "第 1 条");
		// 顺序贪心：清单第 1 行在任务里出现在第 2 条上时，第 2 行的"挖铁"不能算已完成
		ArmProgramTask shuffled = new ArmProgramTask();
		shuffled.entries.add(noteEntry(OperationType.TAKE, "放箱子"));
		shuffled.entries.add(noteEntry(OperationType.TAKE, "挖铁"));
		boolean ordered = ClipboardChecklist.consumed(plan, shuffled) == 1;
		// 空备注不算匹配（没带备注录进去的条目不该把清单吃掉）
		ArmProgramTask blank = new ArmProgramTask();
		blank.entries.add(noteEntry(OperationType.TAKE, ""));
		boolean blankIgnored = ClipboardChecklist.consumed(plan, blank) == 0;
		// 窗口边界
		boolean window = ClipboardChecklist.windowStart(0, 10, 8) == 0
			&& ClipboardChecklist.windowStart(5, 10, 8) == 2 && ClipboardChecklist.windowStart(10, 10, 8) == 2
			&& ClipboardChecklist.windowStart(5, 3, 8) == 0;

		// ——— ② 端到端：真的录一条，看卡上的备注与副手剪贴板的勾 ———
		boolean recorded = false;
		boolean noteFollowsPlan = false;
		boolean tickWritten = false;
		boolean ticksFollowed = false;
		boolean rangeRespected = false;
		boolean oldObjectUntouched = false;
		boolean clearedOnUndo = false;
		boolean readOnlyKept = false;
		String detail = "";
		try {
			ServerLevel level = server.overworld();
			HolderLookup.Provider registries = server.registryAccess();
			Item clipboardItem = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "clipboard"));
			if (clipboardItem == Items.AIR) {
				LOGGER.error("[selftest] 找不到 create:clipboard —— 清单自检的前提不成立（Create 没装上？）");
				return;
			}
			List<String> lines = List.of("第一行", "第二行", "第三行");
			// 第 3 行故意**预先勾上**：它是"还没轮到"的行，按规则不该被我们碰
			ItemStack clipboard = clipboardWith(lines, false, 2);
			ServerPlayer probe = FakePlayerFactory.getMinecraft(level);
			probe.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
			probe.setItemInHand(InteractionHand.OFF_HAND, clipboard);
			ItemStack card = probe.getMainHandItem();
			// ★ 这张卡的录制基准 = 世界原点（用户第四十轮起原点是**卡级**属性）：**必须显式设**，
			// 不然录制会被 `card.need_origin` 拒绝，下面这几条断言全都测不到东西。
			// 顺便也是"原点设在 (0,0,0)"这种**合法值**与"没设"是两件事的活证据
			// （设过 ⇒ 键存在 ⇒ 录得进去；没设 ⇒ 键不存在 ⇒ 拒绝录制）。
			CardUiPacket.applyOrigin(probe, card, CardProgramStorage.read(card, registries), registries, BlockPos.ZERO);

			ClipboardContent before = clipboard.get(AllDataComponents.CLIPBOARD_CONTENT);
			record(probe, card, registries, new BlockPos(1, 2, 3));
			ArmProgramTask task = CardProgramStorage.read(card, registries).task(0);
			recorded = task != null && task.entries.size() == 1;
			noteFollowsPlan = recorded && "第一行".equals(task.entries.get(0).note);
			ClipboardContent after = clipboard.get(AllDataComponents.CLIPBOARD_CONTENT);
			// ★ 两个**分开**的判据（合成一条会把"根本没写"误报成"就地改了"）：
			//   ① 写了吗：内容对象必须被换成新的；
			//   ② 旧对象动过吗：它是 remoteSlots 那份共享引用的值，就地改它 = 槽位同步判不出变化。
			tickWritten = before != null && after != null && before != after;
			oldObjectUntouched = before != null && !before.pages()
				.get(0)
				.get(0).checked;
			rangeRespected = after != null && after.pages()
				.get(0)
				.get(1).checked == false
				&& after.pages()
					.get(0)
					.get(2).checked; // 第 3 行预先勾着，没轮到它 → 不动
			rangeRespected = after != null && after.pages()
				.get(0)
				.get(1).checked == false
				&& after.pages()
					.get(0)
					.get(2).checked; // 第 3 行预先勾着，没轮到它 → 不动

			record(probe, card, registries, new BlockPos(4, 5, 6));
			ArmProgramTask task2 = CardProgramStorage.read(card, registries).task(0);
			boolean second = task2 != null && task2.entries.size() == 2 && "第二行".equals(task2.entries.get(1).note);
			ticksFollowed = second
				&& clipboard.get(AllDataComponents.CLIPBOARD_CONTENT)
					.pages()
					.get(0)
					.get(1).checked;

			// 左键撤销最后一条：第 2 行的勾必须**还回去**（这就是"勾由项目管"的意义：
			// 程序退了，清单也退，两处永远说的是同一件事）
			undo(probe, card, registries, new BlockPos(4, 5, 6));
			clearedOnUndo = !clipboard.get(AllDataComponents.CLIPBOARD_CONTENT)
				.pages()
				.get(0)
				.get(1).checked
				&& CardProgramStorage.read(card, registries)
					.task(0).entries.size() == 1;

			// 只读剪贴板（1.20 老存档迁移过来的）一个字都不该动
			ItemStack readOnly = clipboardWith(lines, true, 0);
			ClipboardContent roBefore = readOnly.get(AllDataComponents.CLIPBOARD_CONTENT);
			probe.setItemInHand(InteractionHand.OFF_HAND, readOnly);
			ClipboardPlan.syncTicks(probe, CardProgramStorage.read(card, registries));
			readOnlyKept = readOnly.get(AllDataComponents.CLIPBOARD_CONTENT) == roBefore;
			probe.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			detail = "备注依次=[" + (task2 == null ? "" : task2.entries.stream()
				.map(e -> e.note)
				.toList()) + "] 撤销后剩 "
				+ CardProgramStorage.read(card, registries)
					.task(0).entries.size() + " 条";
		} catch (Throwable t) {
			detail = "异常 " + t;
		}

		LOGGER.info("[selftest] 副手清单: 判据·断档即停={} 全对上={} 归一化={} 顺序贪心={} 空备注不算={} 窗口={} ｜ "
			+ "端到端·录进卡={} 备注跟着清单={} 勾写回去了={} 勾跟着走={} 只碰已轮到的那几行={} 旧组件没被就地改={} 撤销还勾={} 只读不动={} （{}）",
			stopsAtGap, allMatched, normalized, ordered, blankIgnored, window, recorded, noteFollowsPlan, tickWritten,
			ticksFollowed, rangeRespected, oldObjectUntouched, clearedOnUndo, readOnlyKept, detail);
		if (!stopsAtGap)
			LOGGER.error("[selftest] 清单匹配没有在「断档」处停下（实际完成 {} 条 / 下一条应当是 [放箱子]）", half);
		if (!allMatched)
			LOGGER.error("[selftest] 清单全对上之后 consumed/nextNote/allDone 三者不一致");
		if (!normalized)
			LOGGER.error("[selftest] 清单的文本归一化没认出「同一行的两种写法」（换行 / 连续空格）");
		if (!ordered)
			LOGGER.error("[selftest] 清单匹配没有按顺序贪心 —— 乱序的备注会被误判成已完成");
		if (!blankIgnored)
			LOGGER.error("[selftest] 空备注被当成了「匹配上了某一行」");
		if (!window)
			LOGGER.error("[selftest] 清单窗口的边界不对（应当锚在当前的上一行、且不越界）");
		if (!recorded || !noteFollowsPlan)
			LOGGER.error("[selftest] 录制时没有把清单的下一行写成备注（recorded={} noteFollowsPlan={}）—— "
				+ "清单 HUD 与备注是同一份判据，这里错了整条链路都错", recorded, noteFollowsPlan);
		if (!tickWritten)
			LOGGER.error("[selftest] 清单的勾根本没写回剪贴板（录完之后内容对象没变）—— 玩家在剪贴板上看不到任何进度");
		if (!ticksFollowed)
			LOGGER.error("[selftest] 清单的勾没有跟着录制走（第 2 行应当被勾上）");
		if (!rangeRespected)
			LOGGER.error("[selftest] 清单的勾越界了：还没轮到的第 3 行被改了（或者当前那一行没清掉）");
		if (!oldObjectUntouched)
			LOGGER.error("[selftest] 旧的那个剪贴板内容对象被就地改了 —— 槽位同步会判成「没变」、客户端永远看不到勾"
				+ "（必须新建 ClipboardEntry/ClipboardContent 再 set 回去）");
		if (!clearedOnUndo)
			LOGGER.error("[selftest] 左键撤销之后清单上那一行的勾没有还回去");
		if (!readOnlyKept)
			LOGGER.error("[selftest] 只读剪贴板被改写了");
	}

	/**
	 * 「与臂交换手上的东西」+「溢出库存按栈式出库」的自检（用户第二十七轮）。
	 *
	 * <p>三件事在游戏里只能靠手点，所以必须断言：
	 * <ol>
	 *   <li><b>交换</b>：手持物品右键 = 整组互换；空手右键 = 取走臂手那一件；臂手空时右键什么都不该发生
	 *       （第一版很容易写成"空手套白狼"：把玩家手里那件又"换"回给自己）；</li>
	 *   <li><b>栈式出库顺序</b>：用用户给的例子当断言 —— 依次进泥土、草方块、泥土、石头，
	 *       两块泥土合并 ⇒ 溢出是 {@code [泥土×2, 草方块, 石头]}，出库顺序必须是 <b>石头 → 草方块 → 泥土</b>
	 *       （旧行为是"靠前的先出"，正好相反）；</li>
	 *   <li><b>卸货走的是同一个顺序</b>：真放一个黄铜漏斗 + 箱子，真跑一遍 UNLOAD，读箱子里的落格顺序 ——
	 *       "两处各写一份顺序"必然有一天不一致，所以这里验的是**同一件事的两个出口**。</li>
	 * </ol>
	 *
	 * <p>运动学偷懒：自检不跑 {@code arm.tick()}，进入 MOVING 之后直接把
	 * {@code chasedPointProgress} 设成 1（公开字段）当作"已到位"——这条测的是出库顺序，不是移动动画。
	 */
	private static void checkOverflowStackAndExchange(MinecraftServer server) {
		ServerLevel level = server.overworld();
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 76, 200, spawn.getZ() + 76);
		BlockPos funnelPos = armPos.offset(2, 0, 0);
		BlockPos chestPos = funnelPos.west();
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldFunnel = level.getBlockState(funnelPos);
		BlockState oldChest = level.getBlockState(chestPos);

		boolean exchanged = false;
		boolean tookHeld = false;
		boolean emptyStaysEmpty = false;
		boolean stackOrder = false;
		boolean takesTop = false;
		boolean handGotTop = false;
		boolean emptyHandFallsThrough = false;
		boolean sneakTakesOverflow = false;
		boolean plainTakeHeld = false;
		boolean itemSwaps = false;
		boolean unloadOrder = false;
		boolean premise = false;
		String orderText = "";
		String detail = "";
		try {
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				// ——— ① 交换 ———
				ServerPlayer probe = FakePlayerFactory.getMinecraft(level);
				arm.setHeldItem(new ItemStack(Items.DIAMOND, 3));
				probe.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT, 5));
				SequencedArmBlock.exchangeHeldItem(arm, probe, level);
				exchanged = arm.getHeldItem()
					.is(Items.IRON_INGOT) && arm.getHeldItem()
						.getCount() == 5
					&& probe.getMainHandItem()
						.is(Items.DIAMOND)
					&& probe.getMainHandItem()
						.getCount() == 3;
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				SequencedArmBlock.takeHeldItem(arm, probe, level);
				tookHeld = arm.getHeldItem()
					.isEmpty()
					&& probe.getMainHandItem()
						.is(Items.IRON_INGOT)
					&& probe.getMainHandItem()
						.getCount() == 5;
				// 臂手已经空了：再按一次不该把玩家手上那件"换"走
				SequencedArmBlock.takeHeldItem(arm, probe, level);
				emptyStaysEmpty = arm.getHeldItem()
					.isEmpty()
					&& probe.getMainHandItem()
						.is(Items.IRON_INGOT)
					&& probe.getMainHandItem()
						.getCount() == 5;

				// ——— ② 栈式出库顺序（用户给的例子）———
				arm.getOverflow()
					.clear();
				arm.getOverflow()
					.add(new ItemStack(Items.DIRT, 2));
				arm.getOverflow()
					.add(new ItemStack(Items.GRASS_BLOCK));
				arm.getOverflow()
					.add(new ItemStack(Items.STONE));
				premise = arm.getOverflow()
					.size() == 3;
				List<ItemStack> order = arm.overflowOutOrder();
				orderText = order.stream()
					.map(s -> s.getItem()
						.getDescriptionId() + "×" + s.getCount())
					.toList()
					.toString();
				stackOrder = order.size() == 3 && order.get(0)
					.is(Items.STONE) && order.get(1)
						.is(Items.GRASS_BLOCK) && order.get(2)
							.is(Items.DIRT);
				takesTop = arm.itemHandler()
					.extractFromEnd(64, true)
					.is(Items.STONE);
				// 真取一组（不走分发，直接调那一条实现）：手上应当是栈顶那件、溢出少一格
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				SequencedArmBlock.takeStackFromOverflow(arm, probe, level);
				handGotTop = probe.getMainHandItem()
					.is(Items.STONE) && arm.getOverflow()
						.size() == 2;
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

				// ——— ②b **真·分发路径**（上一次漏掉的就是这里：只测了三个方法本身，
				//       没测"原版到底会调用哪一个" —— 而 bug 正好出在分发上）———
				// 原版顺序：先 useItemOn，只有它回 PASS_TO_DEFAULT_BLOCK_INTERACTION 才轮到 useWithoutItem
				//（服务端 ServerPlayerGameMode:366-377 / 客户端 MultiPlayerGameMode:339-351）。
				// 每一小步都从**已知状态**开始，免得后面几步互相污染（第一版就是这么写错的）。
				BlockState armState = level.getBlockState(armPos);
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(armPos), Direction.UP, armPos, false);

				// ① 空手调 useItemOn：**必须放行**，否则原版永远不会去问 useWithoutItem
				arm.setHeldItem(new ItemStack(Items.DIAMOND, 1));
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				probe.setShiftKeyDown(false);
				ItemInteractionResult emptyItemPath = armState.useItemOn(ItemStack.EMPTY, level, probe,
					InteractionHand.MAIN_HAND, hit);
				emptyHandFallsThrough = emptyItemPath == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

				// ② 潜行 + 空手：从**溢出**拿一组，臂手一动不动（用户报的就是这一条坏了）。
				//    走 pressUse = 原版的完整顺序（useItemOn → 看返回值 → useWithoutItem），
				//    这样"分发"这一步才算真被测到（第一版直接调 useWithoutItem，绕过了 bug 所在的那一步）。
				arm.setHeldItem(new ItemStack(Items.DIAMOND, 1));
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				probe.setShiftKeyDown(true);
				InteractionResult sneakResult = pressUse(armState, level, probe, hit);
				sneakTakesOverflow = sneakResult.consumesAction() && probe.getMainHandItem()
					.is(Items.GRASS_BLOCK) // 石头上一步已被取走 ⇒ 现在栈顶是草方块
					&& arm.getHeldItem()
						.is(Items.DIAMOND);

				// ③ 对照：不潜行 + 空手 = 取走臂手那件（臂手因此变空）
				arm.setHeldItem(new ItemStack(Items.DIAMOND, 1));
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				probe.setShiftKeyDown(false);
				pressUse(armState, level, probe, hit);
				plainTakeHeld = probe.getMainHandItem()
					.is(Items.DIAMOND) && arm.getHeldItem()
						.isEmpty();

				// ④ 手持物品（不潜行）：必须是"交换"
				arm.setHeldItem(new ItemStack(Items.DIAMOND, 1));
				probe.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT, 2));
				InteractionResult itemPath = pressUse(armState, level, probe, hit);
				itemSwaps = itemPath.consumesAction() && arm.getHeldItem()
					.is(Items.IRON_INGOT) && arm.getHeldItem()
						.getCount() == 2
					&& probe.getMainHandItem()
						.is(Items.DIAMOND);
				probe.setShiftKeyDown(false);
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

				// ——— ③ 卸货：真漏斗 + 真箱子，读落格顺序 ———
				BlockState funnelState = stateOf("create:brass_funnel");
				if (funnelState == null) {
					detail = "缺少 create:brass_funnel，卸货顺序没测成";
				} else {
					level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
					level.setBlockAndUpdate(funnelPos, funnelState.setValue(FunnelBlock.FACING, Direction.EAST));
					// 用户例子再来一遍（这次用真插入路径：两块泥土必须合并成一格）
					arm.getOverflow()
						.clear();
					arm.itemHandler()
						.insertIntoOverflow(new ItemStack(Items.DIRT), false);
					arm.itemHandler()
						.insertIntoOverflow(new ItemStack(Items.GRASS_BLOCK), false);
					arm.itemHandler()
						.insertIntoOverflow(new ItemStack(Items.DIRT), false);
					arm.itemHandler()
						.insertIntoOverflow(new ItemStack(Items.STONE), false);
					premise = premise && arm.getOverflow()
						.size() == 3
						&& arm.getOverflow()
							.get(0)
							.getCount() == 2;

					arm.setSpeed(32);
					ArmProgram program = new ArmProgram();
					ArmProgramTask task = new ArmProgramTask();
					task.name = "自检";
					ArmProgramEntry entry = new ArmProgramEntry();
					entry.op = OperationType.UNLOAD;
					entry.offset = funnelPos.subtract(armPos);
					entry.unloadAmount = ArmProgramEntry.UnloadAmount.MAX;
					task.entries.add(entry);
					program.tasks.add(task);
					arm.setProgram(program);
					for (int i = 0; i < 6; i++) {
						arm.runtime.tick(arm);
						arm.chasedPointProgress = 1f; // 自检不跑运动学：直接当作"已到位"
					}
					Container chest = level.getBlockEntity(chestPos) instanceof Container c ? c : null;
					unloadOrder = chest != null && chest.getContainerSize() >= 3
						&& chest.getItem(0)
							.is(Items.STONE)
						&& chest.getItem(1)
							.is(Items.GRASS_BLOCK)
						&& chest.getItem(2)
							.is(Items.DIRT);
					detail = "箱子前三格=" + (chest == null ? "没有箱子" : stackNames(chest));
				}
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(chestPos, oldChest);
			level.setBlockAndUpdate(funnelPos, oldFunnel);
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 交换与栈式出库: 交换={} 空手取回={} 臂手空时不动作={} ｜ 泥土先合并={} 出库顺序倒着={} "
			+ "取一组拿到栈顶={} 空手真取到了={} 卸货顺序一致={} ｜ 分发·空手放行={} 潜行取溢出={} 空手取臂手={} 持物即交换={} （{}）",
			exchanged, tookHeld, emptyStaysEmpty, premise, stackOrder, takesTop, handGotTop, unloadOrder,
			emptyHandFallsThrough, sneakTakesOverflow, plainTakeHeld, itemSwaps, detail);
		if (!exchanged)
			LOGGER.error("[selftest] 右键交换没有整组互换（臂手与玩家手上的东西对不上）");
		if (!tookHeld)
			LOGGER.error("[selftest] 空手右键没有把臂手上那一件取回来");
		if (!emptyStaysEmpty)
			LOGGER.error("[selftest] 臂手是空的时候，空手右键把玩家手上那件弄丢了（不该有任何变化）");
		if (!premise)
			LOGGER.error("[selftest] 栈式出库自检的前提不成立：四件东西插进去之后溢出不是 [泥土×2, 草方块, 石头]（{}）—— "
				+ "泥土没有合并，下面的顺序断言就没意义了", detail);
		if (!stackOrder)
			LOGGER.error("[selftest] 溢出出库顺序不是「后进的先出」：期望 石头→草方块→泥土，实际 {}", orderText);
		if (!takesTop)
			LOGGER.error("[selftest] 空手潜行右键取一组没有从靠后的那格开始取（模拟抽取拿到的不是栈顶）");
		if (!handGotTop)
			LOGGER.error("[selftest] 空手潜行右键没有真的取到东西（或者取得的不是栈顶那一格）");
		if (!unloadOrder)
			LOGGER.error("[selftest] 卸货没有走同一个出库顺序（箱子里的落格顺序应当是 石头 → 草方块 → 泥土）：{}", detail);
		// ——— 分发路径（用户实测的那个 bug 就出在这三行）———
		if (!emptyHandFallsThrough)
			LOGGER.error("[selftest] 空手调 useItemOn 没有返回 PASS_TO_DEFAULT_BLOCK_INTERACTION —— "
				+ "原版于是**永远走不到** useWithoutItem，空手潜行右键会变成别的动作"
				+ "（用户实测：空手潜行右键把臂手那件换到了手上，取不出溢出的东西）");
		if (!sneakTakesOverflow)
			LOGGER.error("[selftest] 空手潜行右键没有从溢出拿一组（或者动了臂手）—— 潜行这条手势被别的分支抢走了");
		if (!plainTakeHeld)
			LOGGER.error("[selftest] 空手（不潜行）右键没有取走臂手上那一件");
		if (!itemSwaps)
			LOGGER.error("[selftest] 手持物品右键没有走成「交换」");
	}

	/**
	 * 自检用小工具：**完全照原版顺序**按一次"右键方块"。
	 *
	 * <p>原版是"先问 {@code useItemOn}，只有它回 {@code PASS_TO_DEFAULT_BLOCK_INTERACTION} 才轮到
	 * {@code useWithoutItem}"（服务端 {@code ServerPlayerGameMode:366-377}、客户端
	 * {@code MultiPlayerGameMode:339-351}）。直接调 {@code useWithoutItem} 会把这一步绕过去 ——
	 * 而"空手潜行右键取不出溢出"这个 bug 恰恰就出在这一步上（见用户实测）。
	 */
	private static InteractionResult pressUse(BlockState state, Level level, ServerPlayer player, BlockHitResult hit) {
		ItemStack stack = player.getMainHandItem();
		ItemInteractionResult itemResult = state.useItemOn(stack, level, player, InteractionHand.MAIN_HAND, hit);
		if (itemResult.consumesAction())
			return itemResult.result();
		if (itemResult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION)
			return state.useWithoutItem(level, player, hit);
		return InteractionResult.PASS;
	}

	/** 日志用：容器前三格的物品名。 */
	private static String stackNames(Container container) {		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(3, container.getContainerSize()); i++) {
			ItemStack stack = container.getItem(i);
			sb.append(stack.isEmpty() ? "空" : stack.getItem()
				.getDescriptionId() + "×" + stack.getCount())
				.append(i < 2 ? " | " : "");
		}
		return sb.toString();
	}

	/** 自检用小工具：造一条带备注的条目。 */
	private static ArmProgramEntry noteEntry(OperationType op, String note) {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = op;
		entry.note = note;
		return entry;
	}

	/**
	 * 自检用小工具：造一张写了 {@code lines} 的剪贴板（第 0 页），把 {@code ticked} 里的行预先勾上。
	 *
	 * <p>{@code readOnly} 用来验"只读剪贴板不被改写"（1.20 老存档迁移过来的那种）。
	 */
	private static ItemStack clipboardWith(List<String> lines, boolean readOnly, int... ticked) {
		List<Integer> marks = new ArrayList<>();
		for (int mark : ticked)
			marks.add(mark);
		List<ClipboardEntry> entries = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++)
			entries.add(new ClipboardEntry(marks.contains(i), Component.literal(lines.get(i))));
		List<List<ClipboardEntry>> pages = new ArrayList<>();
		pages.add(entries);
		ItemStack stack = new ItemStack(
			BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "clipboard")));
		stack.set(AllDataComponents.CLIPBOARD_CONTENT,
			new ClipboardContent(ClipboardOverrides.ClipboardType.WRITTEN, pages, readOnly, 0, Optional.empty()));
		return stack;
	}

	/** 自检用小工具：把"录一条"走一遍真正的服务端路径（{@code CardEditPacket.addEntry}）。 */	private static void record(ServerPlayer player, ItemStack card, HolderLookup.Provider registries, BlockPos pos) {
		ArmProgram program = CardProgramStorage.read(card, registries);
		CardEditPacket.addEntry(player, card, program, registries,
			new CardEditPacket(CardEditPacket.Action.ADD_ENTRY, pos, Direction.UP, OperationType.TAKE));
	}

	/** 自检用小工具：把"左键撤销"走一遍真正的服务端路径（{@code CardEditPacket.removeAt}）。 */
	private static void undo(ServerPlayer player, ItemStack card, HolderLookup.Provider registries, BlockPos pos) {
		ArmProgram program = CardProgramStorage.read(card, registries);
		CardEditPacket.removeAt(player, card, program, registries, pos);
	}

	// ————————————————————————— 脚本语言（§12 的 P1） —————————————————————————

	/**
	 * 脚本文本 ↔ 操作表的两条往返不变量（§12.4，P1 的验收硬指标）。
	 *
	 * <p><b>为什么要这两条</b>：文本一旦与模型不一致，它就变成**第三个真理来源**
	 * —— 与决策 68/85 记的"判据写两份必然漂移"是同一个病。
	 * ① {@code compile(decompile(p)) ≡ p} 保证"导出再导入 = 什么都没变"（不会静默丢字段）；
	 * ② {@code decompile(compile(t)) == canonical(t)} 保证"写出来的文本就是规范形式"。
	 *
	 * <p>语料分两半：**真实预设**（{@code /sma card <预设>} 用的那一批，全是会被存进卡的程序）
	 * 与**手写用例**（多行注释、含 {@code *&#47;} 的备注、超限备注、未知任务/物品、越界数值、
	 * 全部操作类型、{@code #RRGGBB} 染色、完整 SNBT 物品）。
	 *
	 * <p><b>反证（决策 68：新守卫必须做一次"反证"，确认它真的会红）</b> —— 都只需改一行、跑一次启动：
	 * <ol>
	 *   <li>把 {@code ScriptDecompiler.writeSuffixes} 里 {@code if (entry.count != 1)} 改成 {@code if (false)}
	 *       ⇒ 抽到取物条目的 count 会丢 ⇒ {@code [selftest] 脚本不变量①…} 必须报出「count」那条差异；</li>
	 *   <li>把 {@code time(...)} 的单位后缀全改成 {@code "t"} ⇒ 单位丢失 ⇒ 不变量①必须红；</li>
	 *   <li>把 {@code ScriptParser.attachComment} 里的 {@code ctx.owner} 改成永远 {@code PROGRAM}
	 *       ⇒ 条目备注全挂到程序备注上 ⇒ {@code 注释归属} 与不变量①同时红；</li>
	 *   <li>把 {@code noteSuffix} 的 {@code note.contains("*&#47;")} 判断去掉（一律用块注释）
	 *       ⇒ 含 {@code *&#47;} 的备注把注释提前闭合 ⇒ 不变量①必须红（保底形式那条用例）；</li>
	 *   <li>{@code ArmProgram.save} 里把 {@code if (!note.isEmpty())} 改成 {@code if (false)}
	 *       ⇒ 程序备注丢 ⇒ "程序备注进 NBT"那行必须报红；</li>
	 *   <li>把 {@code ArmProgramEntry.needsTarget()} 的过滤那一支删掉 ⇒ 容器模式的坐标与
	 *       {@code source=} 都不再写出来 ⇒ 「过滤容器」那份语料的不变量①必须红。
	 *       ⚠️ **第四十一轮起这条红的来路变了**：{@code scriptDifference} 现在只在"这一条需要坐标"时
	 *       比 offset（见那里的说明），单靠不变量①判不到它 —— 现在由两处接手：
	 *       ① ⑫ 的 {@code 过滤容器模式照旧写坐标}（反编译侧：直接看文本里有没有那两个数）；
	 *       ② 解析侧的模型不变量（{@code filter 12 64 -30 source=container} 会当场报"手持模式没有坐标"）
	 *       ⇒ 「过滤容器」那份语料**编译不过** ⇒ 不变量①那条也红。</li>
	 * </ol>
	 */
	/**
	 * 「过滤」操作的自检（用户口径：幽灵物品 + 过滤位置二选一）。
	 *
	 * <p><b>用户口径的两条新语义（本轮）</b>：
	 * <ol>
	 *   <li><b>过滤是控制类操作、臂不移动</b>（"判断过滤是一瞬间的事情，不需要动力臂伸过去判断"）：
	 *       容器模式的坐标只是"<b>读哪个方块</b>"的数据参数 ⇒ 容器那几条必须在**完全不模拟到位**
	 *       的条件下跑出结果，而且整个过程中臂<b>一次都不许进 MOVING</b>（⑩ 的两条正面证据）；</li>
	 *   <li><b>失败默认 retry</b>（与「收信号」同一条路）：不匹配 / 没有物品可比都是
	 *       "还没到时机"，在重试窗口里反复判断、窗口到了才走「失败后」（⑨ 的两条）。</li>
	 * </ol>
	 *
	 * <p><b>为什么这里的断言全都用真物品、真数据组件</b>：过滤的语义**完全**是 Create 本体的
	 * （{@code FilterItemStack.of(幽灵物品)} 按物品类型选 {@code ListFilterItemStack} /
	 * {@code AttributeFilterItemStack} / {@code PackageFilterItemStack}）。拿假对象/mock 去测，
	 * 测到的是我们自己写的那层壳，而真正会出错的地方恰好是"数据组件摆得对不对"
	 * （{@code FILTER_ITEMS} 是 {@code ItemContainerContents}、属性过滤是
	 * {@code ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES} 这个 {@code List<ItemAttributeEntry>}）。
	 *
	 * <p>每条断言后面都写清了"改哪一行会让它变红"—— 没有反证的守卫不算验证过。
	 *
	 * <p>过滤的语义归属：比对**完全**交给 {@code FilterItemStack.of(幽灵物品)} 选出来的包装类
	 * （列表 / 属性 / 包裹），我们不自己写比对逻辑。候选的两种来源（手持 / 外部容器的第一格）
	 * 与三条出口（成功后 / 失败后 / 空过滤器算成功）都由 {@code ProgramRuntime.filter} 决定。
	 */
	private static void checkFilterOperation(MinecraftServer server) {
		ServerLevel level = server.overworld();

		// ——— ① 物品过滤（列表过滤器 create:filter，里面只有铁锭）———
		// 反证：把字段名从 `minecraft.iron_ingot` 改成别的 / 把列表写成空列表 ⇒ goldMatches 会变真
		ItemStack listFilter = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:filter")));
		listFilter.set(AllDataComponents.FILTER_ITEMS, ItemContainerContents.fromItems(List.of(
			new ItemStack(Items.IRON_INGOT), new ItemStack(Items.OAK_PLANKS))));
		// ⚠️ 组件全空时 `FilterItemStack.of` 会**故意退回裸包装器**（本体为了性能，见 FilterItemStack:26-33），
		//    所以这里补一个自定名组件，让 Create 真的走 "按滤波器物品类型分派" 那条路。
		listFilter.set(DataComponents.CUSTOM_NAME, Component.literal("自检过滤器"));
		FilterItemStack list = FilterItemStack.of(listFilter);
		boolean ironMatches = list.test(level, new ItemStack(Items.IRON_INGOT), false);
		boolean plankMatches = list.test(level, new ItemStack(Items.OAK_PLANKS), false);
		boolean goldMatches = list.test(level, new ItemStack(Items.GOLD_INGOT), false);

		// ——— ② 属性过滤（create:attribute_filter + "在标签里" 属性）———
		// 反证：把属性换成 `InTagAttribute(ItemTags.LOGS)` ⇒ plankByTag 会变假
		ItemStack tagFilter = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:attribute_filter")));
		tagFilter.set(AllDataComponents.ATTRIBUTE_FILTER_WHITELIST_MODE, AttributeFilterWhitelistMode.WHITELIST_DISJ);
		tagFilter.set(AllDataComponents.ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES,
			List.of(new ItemAttribute.ItemAttributeEntry(new InTagAttribute(ItemTags.PLANKS), false)));
		FilterItemStack byTag = FilterItemStack.of(tagFilter);
		boolean plankByTag = byTag.test(level, new ItemStack(Items.OAK_PLANKS), false);
		boolean ironByTag = byTag.test(level, new ItemStack(Items.IRON_INGOT), false);

		// ——— ④ 包裹过滤（create:package_filter + 一个地址）———
		// 反证：把 filter 的地址改成 "码头-B" ⇒ sameAddress 会变假
		ItemStack packageFilter =
			new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:package_filter")));
		packageFilter.set(AllDataComponents.PACKAGE_ADDRESS, "码头-A");
		FilterItemStack byAddress = FilterItemStack.of(packageFilter);
		ItemStack boxA = PackageStyles.getRandomBox();
		PackageItem.addAddress(boxA, "码头-A");
		ItemStack boxB = PackageStyles.getRandomBox();
		PackageItem.addAddress(boxB, "码头-B");
		boolean sameAddress = byAddress.test(level, boxA, false);
		boolean otherAddress = byAddress.test(level, boxB, false);

		// ——— ⑤ 空过滤器 = 匹配一切（本体口径：FilterItemStack.isEmpty() ⇒ test 恒真）———
		// 反证：让程序在 filter.isEmpty() 时走失败出口 ⇒ emptyMatchesEverything 变假
		FilterItemStack emptyFilter = FilterItemStack.of(ItemStack.EMPTY);
		boolean emptyIsEmpty = emptyFilter.isEmpty();
		boolean emptyMatchesEverything = emptyFilter.test(level, new ItemStack(Items.DIAMOND), false)
			&& emptyFilter.test(level, new ItemStack(Items.DIRT), false);

		// ——— ③⑥⑦⑧ 走**真解释器**（照 checkCallRepeats 的手法：建臂 → setProgram → runtime.tick）———
		BlockPos spawn = level.getSharedSpawnPos();
		BlockPos armPos = new BlockPos(spawn.getX() + 76, 200, spawn.getZ() + 76);
		BlockPos depotPos = armPos.offset(2, 0, 0);
		BlockPos vaultPos = armPos.offset(-2, 0, 0);
		BlockState oldArm = level.getBlockState(armPos);
		BlockState oldDepot = level.getBlockState(depotPos);
		BlockState oldVault = level.getBlockState(vaultPos);
		boolean heldMatch = false;
		boolean heldMismatch = false;
		boolean heldEmpty = false;
		boolean containerMatch = false;
		boolean containerMismatch = false;
		boolean containerEmpty = false;
		boolean firstSlotOnly = false;
		boolean containerNeverMoved = false;
		boolean retryStaysWaiting = false;
		boolean retryWindowExpires = false;
		boolean emptyCandidateRetries = false;
		boolean routeSuccess = false;
		boolean routeFailure = false;
		String detail;
		try {
			BlockState depotState = stateOf("create:depot");
			BlockState vaultState = stateOf("create:item_vault");
			if (depotState == null || vaultState == null) {
				LOGGER.error("[selftest] 过滤自检**没跑起来**：找不到 create:depot / create:item_vault");
				return;
			}
			level.setBlockAndUpdate(armPos, SequencedArmRegistration.SEQUENCED_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotPos, depotState);
			// 容器那几条用**物品保险箱**（`create:item_vault`，默认 20 槽）而不是置物台：
			// 置物台只有一格，"第一格是金锭、第二格是铁锭"这种组合根本摆不出来，而那条正是用户选的
			// "只看第一格"口径唯一的反证入口。保险箱自己有 `Capabilities.ItemHandler.BLOCK`
			//（`ItemVaultBlockEntity.registerCapabilities`），而**单方块本身就是它的 controller**
			//（构造里 `radius = length = 1`），所以不用拼多方块结构。
			// ⚠️ 过滤**不需要**目标是 Create 交互点（`needsInteractionPoint()` 只认取/放/卸/组装），
			//   所以保险箱这种非交互点方块对"外部容器模式"是合法的目标。
			level.setBlockAndUpdate(vaultPos, vaultState);
			if (!(level.getBlockEntity(armPos) instanceof SequencedArmBlockEntity arm)) {
				LOGGER.error("[selftest] 过滤自检**没跑起来**：臂的方块实体没建出来");
				return;
			}
			arm.setSpeed(32); // 解释器在转速为 0 时整 tick 跳过（与原版一致）

			// ★ 重试窗口（tick）：⑧⑨ 两条都用它 —— 窗口**不为 0** 才能看出"先重试、再到时"这件事。
			final int retryWindow = 4;

			// ③ 手持过滤：手上放铁锭 → 符合；改装金锭 → 同一份条目变成不符合；手空 → 总是失败。
			//    反证：把 filter() 里 HELD 那一支读成 overflow / 读错槽 ⇒ 三条一起变味。
			//    手持模式本来就不碰世界（臂不移动）⇒ 一句 beginEntry 就进执行态。
			arm.setProgram(filterProgram(ArmProgramEntry.FilterSource.HELD, listFilter, depotPos.subtract(armPos)));
			arm.setHeldItem(new ItemStack(Items.IRON_INGOT));
			heldMatch = runFilterEntry(arm, 80).success();
			arm.setProgram(filterProgram(ArmProgramEntry.FilterSource.HELD, listFilter, depotPos.subtract(armPos)));
			arm.setHeldItem(new ItemStack(Items.GOLD_INGOT));
			heldMismatch = !runFilterEntry(arm, 80).success();
			arm.setProgram(filterProgram(ArmProgramEntry.FilterSource.HELD, listFilter, depotPos.subtract(armPos)));
			arm.setHeldItem(ItemStack.EMPTY);
			heldEmpty = !runFilterEntry(arm, 80).success();

			// ⑧ 出口路由（真解释器 + 成功后/失败后跳转）：同一条程序、只换手上那一份，
			//    符合时必须落到"成功后"的落点、不符合时必须落到"失败后"的落点。
			//    ⚠️ 那条失败路**要穿过重试窗口**（不匹配 → 重试 → 窗口到了才跳）⇒ 程序必须给一个**有限**窗口：
			//    `failAmount` 默认是 -1（一直重试），去向永远到不了 —— 这正是本轮的新语义，
			//    拿默认值写这条路由用例会得到"永远不跳转"（上一版这里是 applySkip，才没暴露这一点）。
			//    反证：把 filter() 里的 succeed/applySkip 互换 ⇒ 两条一起红。
			arm.setProgram(routeProgram(depotPos.subtract(armPos), retryWindow));
			arm.setHeldItem(new ItemStack(Items.IRON_INGOT));
			routeSuccess = runRoute(arm) == 0; // 0 = 成功后跳回条目 1
			arm.setProgram(routeProgram(depotPos.subtract(armPos), retryWindow));
			arm.setHeldItem(new ItemStack(Items.GOLD_INGOT));
			routeFailure = runRoute(arm) == 1; // 1 = 失败后跳到条目 2（那条抛错 ⇒ 停在下标 1）

			// ⑨ 不匹配**不是立刻停机**，而是在窗口内重试（用户口径：失败默认 retry）。
			//    上半：跑满窗口**之前**必须还在重试（没停机、游标还在条目 1、重试计时已经在走）；
			//    下半：窗口一到就走失败后（落到条目 2 的「抛错」⇒ 停机且游标停在下标 1）。
			//    ⚠️ 判据**不能**写成 `state == RETRY`：重试是"每 tick 重跑一次判断"，
			//    状态在 RETRY / EXECUTING 之间来回跳（看一眼正好落在 EXECUTING 会假红）。
			//    真正要断言的是"**没停机、也没跳到失败落点**，而且重试计时已经在累加"。
			//    ★ 反证（改一行就会红）：把 `filter()` 里那三处 `handleFailure(...)` 换回上一版的
			//      `applySkip(arm, task, entry, entry.onTimeout, ...)` ⇒ 第 1 tick 就跳到条目 2
			//      ⇒ entryIndex 变 1 ⇒ retryStaysWaiting 变 false；
			//      把窗口判据（`timeoutTicks()`）改成恒 0 ⇒ 同样立刻跳。
			arm.setProgram(routeProgram(depotPos.subtract(armPos), retryWindow));
			arm.setHeldItem(new ItemStack(Items.GOLD_INGOT)); // 列表过滤器只认铁锭 ⇒ 必不匹配
			for (int i = 0; i < retryWindow - 1; i++)
				arm.runtime.tick(arm);
			retryStaysWaiting = !arm.runtime.isHalted() && arm.runtime.entryIndex == 0
				&& arm.runtime.waitElapsed > 0;
			for (int i = 0; i < 30 && !arm.runtime.isHalted(); i++)
				arm.runtime.tick(arm);
			retryWindowExpires = arm.runtime.isHalted() && arm.runtime.entryIndex == 1;

			// ⑥/⑦ 外部容器：容器模式取**第一个非空槽**（只看第一格）。
			//    ★ 臂**不移动**（过滤是控制类操作）：所以这几条**完全不模拟到位** ——
			//    上一版那个 `simulateArrival`（每轮把 chasedPointProgress 置 1）已经删掉，
			//    判据只剩"容器模式照样在几十 tick 内跑出结果，且期间一次都没进 MOVING"。
			//    反证：把 beginEntry 的问法改回 `entry.needsTarget()`（= 让臂为容器模式先飞过去）
			//    ⇒ 条目永远停在 MOVING、一次都执行不到 ⇒ containerNeverMoved 与 ⑥⑦ 一起红。
			clearContainer(level, vaultPos); // 先摆成"空的保险箱"：⑥ 要看的就是"没有物品可比"
			arm.setProgram(filterProgram(ArmProgramEntry.FilterSource.CONTAINER, listFilter,
				vaultPos.subtract(armPos)));
			FilterRun containerEmptyRun = runFilterEntry(arm, 40);
			containerEmpty = !containerEmptyRun.success();
			arm.setHeldItem(ItemStack.EMPTY);
			boolean containerMoved = containerEmptyRun.moved();

			// ⑦ [金锭, 铁锭] + 幽灵铁锭 ⇒ **失败**（第一格是金锭，不能"找任意一格符合的"）。
			//    反证：把 filter() 里那个 firstNonEmptySlot 换成"遍历所有槽找匹配的" ⇒ 这条变真。
			//    槽序为什么可信：`putOneInto` 与 `firstNonEmptySlot` 都从 0 号槽往大扫，
			//    而且**都走同一个能力入口**（`Capabilities.ItemHandler.BLOCK, 方向 UP`），
			//    探测与读取还都是 `extractItem(slot, 1, true)` —— 同一次调用，不存在"探测说非空、
			//    读出来却是别的东西"这种不一致（不需要再赌 `getStackInSlot` 与它是否同序）。
			boolean goldFirst = putOneInto(level, vaultPos, new ItemStack(Items.GOLD_INGOT));
			boolean ironSecond = putOneInto(level, vaultPos, new ItemStack(Items.IRON_INGOT));
			arm.setProgram(filterProgram(ArmProgramEntry.FilterSource.CONTAINER, listFilter,
				vaultPos.subtract(armPos)));
			FilterRun bothMismatchRun = runFilterEntry(arm, 40);
			boolean bothMismatch = !bothMismatchRun.success();
			containerMoved |= bothMismatchRun.moved();
			// 反例：把第一格换成铁锭 ⇒ 同一份条目必须成功（证明上一条不是"容器模式永远失败"）
			clearContainer(level, vaultPos);
			putOneInto(level, vaultPos, new ItemStack(Items.IRON_INGOT));
			putOneInto(level, vaultPos, new ItemStack(Items.GOLD_INGOT));
			arm.setProgram(filterProgram(ArmProgramEntry.FilterSource.CONTAINER, listFilter,
				vaultPos.subtract(armPos)));
			FilterRun firstMatchRun = runFilterEntry(arm, 40);
			boolean firstMatch = firstMatchRun.success();
			containerMoved |= firstMatchRun.moved();
			firstSlotOnly = goldFirst && ironSecond && bothMismatch && firstMatch;
			containerMatch = firstMatch;
			containerMismatch = bothMismatch;
			containerNeverMoved = !containerMoved;

			// ⑨b "候选没有物品"也走**同一条路**（也是"不满足"，不是立刻停机）：容器空着，
			//    跑满窗口之前必须还在重试（没停机、重试计时在走）。判据同上：不看是不是正好落在 RETRY。
			//    反证：把 `filter()` 里那条"容器里没有物品"改回 `applySkip(...)` ⇒ 立刻停机
			//    ⇒ emptyCandidateRetries 变 false。
			clearContainer(level, vaultPos);
			ArmProgram emptyRetry = filterProgram(ArmProgramEntry.FilterSource.CONTAINER, listFilter,
				vaultPos.subtract(armPos));
			emptyRetry.tasks.get(0).entries.get(0).failAmount = retryWindow;
			arm.setProgram(emptyRetry);
			for (int i = 0; i < retryWindow - 1; i++)
				arm.runtime.tick(arm);
			emptyCandidateRetries = !arm.runtime.isHalted() && arm.runtime.entryIndex == 0
				&& arm.runtime.waitElapsed > 0;
			// ⚠️ 这两个标签曾经写反（bothMismatch 印成"符合"、还标注"应为 false"），
			//    而断言要的恰恰是"金锭在前 ⇒ 不符 = true" —— 误导性的文案会让人去改断言而不是查夹具。
			detail = "容器=[金锭,铁锭]→不符=" + bothMismatch + "（应为 true） 容器=[铁锭,金锭]→符合=" + firstMatch
				+ "（应为 true） 摆进保险箱=" + goldFirst + "/" + ironSecond + " 容器模式没动过臂=" + containerNeverMoved
				+ " 窗口内还在重试=" + retryStaysWaiting + " 窗口到了才走失败后=" + retryWindowExpires
				+ " 无物品也是重试=" + emptyCandidateRetries;
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
			level.setBlockAndUpdate(depotPos, oldDepot);
			level.setBlockAndUpdate(vaultPos, oldVault);
		}

		LOGGER.info("[selftest] 过滤: 物品(铁符合={} 金不符={}) 列表标签(木板符合={}) 属性过滤(木板符合={} 铁不符={}) "
			+ "包裹(同址={} 异址={}) 空过滤器匹配一切={}(空={}) 手持(符合={} 不符={} 空={}) "
			+ "容器(符合={} 不符={} 空={}) 只看第一格={} 出口路由(成功={} 失败={}) "
			+ "不移动臂={} 窗口内重试={} 窗口到了才走失败后={} 无物品也重试={} （{}）",
			ironMatches, !goldMatches, plankMatches, plankByTag, !ironByTag, sameAddress, !otherAddress,
			emptyMatchesEverything, emptyIsEmpty, heldMatch, heldMismatch, heldEmpty, containerMatch, containerMismatch,
			containerEmpty, firstSlotOnly, routeSuccess, routeFailure, containerNeverMoved, retryStaysWaiting,
			retryWindowExpires, emptyCandidateRetries, detail);

		if (!ironMatches || goldMatches || !plankMatches)
			LOGGER.error("[selftest] 过滤①失败：列表过滤器没按物品比（铁符合={} 金不符={} 木板符合={}）—— "
				+ "改的是 FILTER_ITEMS 那个组件或幽灵物品本身", ironMatches, !goldMatches, plankMatches);
		if (!plankByTag || ironByTag)
			LOGGER.error("[selftest] 过滤②失败：属性过滤（在标签里）没生效（木板符合={} 铁不符={}）—— "
				+ "改的是 ATTRIBUTE_FILTER_MATCHED_ATTRIBUTES 里的那条属性", plankByTag, !ironByTag);
		if (!sameAddress || otherAddress)
			LOGGER.error("[selftest] 过滤④失败：包裹过滤没按地址比（同址={} 异址={}）—— 改的是 PACKAGE_ADDRESS",
				sameAddress, !otherAddress);
		if (!emptyIsEmpty || !emptyMatchesEverything)
			LOGGER.error("[selftest] 过滤⑤失败：空过滤器没有「匹配一切」（isEmpty={} 匹配={}）—— "
				+ "空过滤器时不该走 test，本体口径是直接算成功", emptyIsEmpty, emptyMatchesEverything);
		if (!heldMatch || !heldMismatch)
			LOGGER.error("[selftest] 过滤③失败：手持模式的比对跑错了（铁锭符合={} 金锭不符={}）—— 两种可能："
				+ "① filter() 里 HELD 那一支读的不是 getHeldItem()（读成 overflow / 读错槽）；"
				+ "② 条目压根没执行到 filter()（判据见 runFilterEntry 的注释：必须看见过 EXECUTING 才算跑了）",
				heldMatch, heldMismatch);
		if (!heldEmpty)
			LOGGER.error("[selftest] 过滤⑥失败：手上没有物品时被当成了成功（用户口径：没有物品就**不满足** —— "
				+ "默认「一直重试」时它永远不该成功，手一空就成功的唯一可能是比对那一段短路了）");
		if (!containerMatch || !containerMismatch || !firstSlotOnly)
			LOGGER.error("[selftest] 过滤⑦失败：外部容器没有「只看第一格」（符合=[铁锭,金锭]={} 不符=[金锭,铁锭]={} "
				+ "只看第一格={}）—— 两种可能：① firstNonEmptySlot 被换成「遍历找匹配的」（那会变成 符合=true 不符=false）；"
				+ "② 条目压根没执行到（容器模式现在**不移动手臂**、也不该需要任何「模拟到位」）（{}）", containerMatch,
				containerMismatch, firstSlotOnly, detail);
		if (!containerEmpty)
			LOGGER.error("[selftest] 过滤⑥失败：容器里一个非空格都没有时被当成了成功（用户口径：没有物品就**不满足** —— "
				+ "与不匹配同一条路，默认「一直重试」时它永远不该成功）");
		if (!containerNeverMoved)
			LOGGER.error("[selftest] 过滤⑩失败：容器模式**让臂动过**（期间进过 MOVING）—— 过滤是控制类操作，"
				+ "判断是一瞬间的事，臂不该伸过去。改的是 ProgramRuntime.beginEntry 那个判据"
				+ "（应当是 `entry.movesArm()`，不是 `entry.needsTarget()`）（{}）", detail);
		if (!routeSuccess || !routeFailure)
			LOGGER.error("[selftest] 过滤⑧失败：出口路由错了（符合→成功后={} 不符→失败后={}）—— "
				+ "filter() 里 succeed 与失败那条路用反了，或条目的落点没生效、或路由程序忘了给重试窗口"
				+ "（默认 -1 = 一直重试 ⇒ 失败后永远到不了）", routeSuccess, routeFailure);
		if (!retryStaysWaiting || !retryWindowExpires)
			LOGGER.error("[selftest] 过滤⑨失败：不匹配没有「在窗口内重试、窗口到了才走失败后」"
				+ "（窗口内还在重试={} 窗口到了才走失败后={}）—— 反证一行：把 filter() 里那三处 handleFailure(...) "
				+ "换回 applySkip(...) ⇒ 第 1 tick 就停机 ⇒ 这条红（用户口径：过滤失败默认 retry）",
				retryStaysWaiting, retryWindowExpires);
		if (!emptyCandidateRetries)
			LOGGER.error("[selftest] 过滤⑨b失败：候选没有物品时不是「重试」而是立刻走了失败出口"
				+ "（用户口径：没有物品也是一种不满足，与不匹配同一条路）");
	}

	/** 「过滤」条目的最小程序：主任务 = [过滤]，成功后不设（收工）——单独跑一条时"成功"= 条目走完。 */
	private static ArmProgram filterProgram(ArmProgramEntry.FilterSource source, ItemStack filterItem,
		BlockPos offset) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检过滤";
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.FILTER;
		entry.filterSource = source;
		entry.filterItem = filterItem.copy();
		entry.offset = offset;
		task.entries.add(entry);
		program.tasks.add(task);
		return program;
	}

	/**
	 * 跑一条「过滤」条目的结果：{@code success} = 走的是成功出口；{@code moved} = 期间见过 {@code MOVING}。
	 *
	 * <p><b>为什么要把"动过没有"也带出来</b>：过滤是**控制类操作**（用户口径："判断过滤是一瞬间的事情，
	 * 不需要动力臂伸过去判断"），臂**一次都不该移动** —— 那是"容器模式的坐标只是数据参数"这件事的
	 * <b>正面证据</b>，光断言"跑出结果了"证明不了它没先飞过去（上一版就是靠模拟到位才跑得动的）。
	 */
	private record FilterRun(boolean success, boolean moved) {
	}

	/**
	 * 跑一条「过滤」条目，返回 {@link FilterRun}（走的是成功出口吗 / 期间臂动过没有）。
	 *
	 * <p><b>判据为什么不能是 {@code getLastFailure()}</b>（上一版就是它，于是 ③⑥⑦ 读到的全是假值）：
	 * 失败出口最终落到 {@code halt()}（{@code onTimeout} 默认 {@code NONE}），而 {@code halt()}
	 * **只写 {@code error}**（停机原因，护目镜上是单独一行：{@code SequencedArmBlockEntity:891}），
	 * **从不写 {@code lastFailure}** —— 那个字段是"重试中的黄字提示"，只有 {@code CONTINUE} /
	 * {@code SKIP_TO} / {@code UNTIL_FAIL} 三条出口会写。于是 {@code lastFailure == null} 在"成功"
	 * 与"停机"两种情况下**都成立**，它根本不是成败判据（"不符"被读成了"成功"，"手空"同样）。
	 *
	 * <p>现在改看**运行时状态**，并且要求"**看见过执行态**"才算成功：
	 * <ul>
	 *   <li>{@code HALTED} ⇒ 失败（用户口径：不符 / 没有物品就是走失败后；NONE 出口在窗口到点后把它变成停机）；</li>
	 *   <li>先看见 {@code EXECUTING}、之后又回到 {@code IDLE} ⇒ 成功（{@code succeed} 会
	 *       {@code resetEntryCounters()} 把状态恢复成 IDLE）。为什么必须两步都在：单看"最后不是停机"
	 *       会把"压根没执行到 filter()"（转速为 0、目标没解析出来）也判成成功 —— 那是自检最怕的假绿。</li>
	 *   <li>一直停在 {@code RETRY}（不匹配 + 默认"一直重试"）⇒ 既不是成功也不是停机 ⇒ 返回 false。
	 *       这正是"失败默认 retry"在自检里的样子：**不是立刻停机**，所以这里不能把 RETRY 当停机。</li>
	 * </ul>
	 * 从而窗口内既没成功也没停机的，一律判失败（**不默认成功**）。
	 *
	 * <p>★ 这个入口**不需要也不再有**"模拟到位"那一步（上一版有个 {@code simulateArrival} 参数，
	 * 每轮把 {@code chasedPointProgress} 置 1）：过滤不移动手臂，容器模式与手持模式跑法完全一样 ——
	 * "不模拟到位也能跑出结果"本身就是"臂没有伸过去"的一半证据（另一半见 {@link FilterRun#moved()}）。
	 *
	 * @param ticks 给多少 tick（判据是"结果"不是"到位"，所以手持与容器给的是同一个量级）
	 */
	private static FilterRun runFilterEntry(SequencedArmBlockEntity arm, int ticks) {
		boolean executed = false;
		boolean moved = false;
		for (int i = 0; i < ticks; i++) {
			arm.runtime.tick(arm);
			if (arm.runtime.state == ProgramRuntime.EntryState.MOVING)
				moved = true;
			if (arm.runtime.isHalted())
				return new FilterRun(false, moved); // 失败出口（窗口到点后的 onTimeout = NONE ⇒ halt）
			if (arm.runtime.state == ProgramRuntime.EntryState.EXECUTING)
				executed = true;
			else if (executed && arm.runtime.state == ProgramRuntime.EntryState.IDLE)
				return new FilterRun(true, moved); // 执行过、又回到空闲 ⇒ 走的是成功出口
		}
		return new FilterRun(false, moved);
	}

	/**
	 * 「过滤」的**出口路由**程序：主任务 = [过滤（成功后跳 1）/（失败后跳 2）、抛错]。
	 *
	 * <p>条目用 1 基序号（用户可见的编号体系）：{@code 成功 → 跳转 1} = 回到条目 1（自己），
	 * {@code 失败 → 跳转 2} = 跳到条目 2。
	 *
	 * <p>⚠️ 第二条**故意用「抛错」而不是「等待 0」**：等待跑完会走 {@code finishTask}，
	 * 那会把 {@code entryIndex} 重置成 0 并让程序收工 —— 于是"失败落点"与"成功落点"在
	 * 观察窗口里**长得一模一样**（下标都是 0）。抛错会真的是"停机 + 游标停在下标 1"，
	 * 两个出口才区分得开（判据见 {@link #runRoute}）。
	 *
	 * @param retryWindow 失败重试窗口（ticks）—— ★ <b>必须给一个有限值</b>：{@code failAmount} 的默认值
	 *                    是 {@code -1} = "一直重试"（用户口径：过滤**失败默认 retry**），
	 *                    不去向就永远到不了，这条用例会变成"两个出口都没到"（{@code -1}）。
	 *                    运行时那条路是 {@code handleFailure} → {@code RETRY} → 窗口到点 → {@code applySkip}。
	 */
	private static ArmProgram routeProgram(BlockPos offset, int retryWindow) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "自检过滤路由";
		ArmProgramEntry filter = new ArmProgramEntry();
		filter.op = OperationType.FILTER;
		filter.filterSource = ArmProgramEntry.FilterSource.HELD;
		filter.filterItem = new ItemStack(Items.IRON_INGOT);
		filter.offset = offset;
		filter.failAmount = retryWindow;
		filter.failUnit = ArmProgramEntry.WaitUnit.TICK;
		filter.onSuccess = ArmProgramEntry.SkipAction.SKIP_TO;
		filter.successTarget = "1"; // 回到自己 ⇒ 只有"成功"这一条路能到
		filter.onTimeout = ArmProgramEntry.SkipAction.SKIP_TO;
		filter.timeoutTarget = "2"; // 落到第二条 ⇒ 只有"失败"这一条路能到
		task.entries.add(filter);
		ArmProgramEntry landing = new ArmProgramEntry();
		landing.op = OperationType.THROW;
		landing.errorMessage = "自检：这里是失败后的落点";
		task.entries.add(landing);
		program.tasks.add(task);
		return program;
	}

	/**
	 * 跑路由程序，返回**出口编号**：{@code 0} = 走的是"成功后"（跳回条目 1、游标停在下标 0）、
	 * {@code 1} = 走的是"失败后"（跳到条目 2、停机且游标停在下标 1）、{@code -1} = 两个都没到。
	 *
	 * <p>为什么两个出口能区分：成功后是"跳回自己"，所以**会一直重跑**、游标一直回到 0；
	 * 失败后落到的那条是「抛错」⇒ 立刻停机，游标**留**在下标 1。
	 * 反证：把实现里的两个出口互换，这两条断言必然同时红。
	 */
	private static int runRoute(SequencedArmBlockEntity arm) {
		for (int i = 0; i < 120; i++) {
			arm.runtime.tick(arm);
			if (arm.runtime.isHalted())
				return arm.runtime.entryIndex == 1 ? 1 : -1;
			// 成功后跳回自己：`succeed` 会写下"成功 → 跳转 1" 并把游标放回下标 0
			String failure = arm.runtime.getLastFailure();
			if (failure != null && failure.startsWith("成功") && arm.runtime.entryIndex == 0)
				return 0;
		}
		return -1;
	}

	/** 把目标容器清空（走方块自己的能力，不看内部字段）——自检要摆不同的第一格。 */
	private static void clearContainer(ServerLevel level, BlockPos pos) {
		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
		if (handler == null)
			return;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			ItemStack extracted = handler.extractItem(slot, 64, false);
			if (!extracted.isEmpty())
				slot--; // 同一格可能还有（栈不合法时）
		}
	}

	/**
	 * 脚本往返（§12.4 的两条不变量）。
	 *
	 * <p>不变量①：{@code compile(decompile(p)) ≡ p}（逐字段）；
	 * 不变量②：{@code decompile(compile(t)) == canonical(t)}（反编译是幂等的规范形式）。
	 *
	 * <p>反证清单（改哪一行会让这里红）：
	 * <ol>
	 *   <li>把 {@code ScriptDecompiler.writeSuffixes} 里 {@code if (entry.count != 1)} 改成 {@code if (false)}
	 *       ⇒ 抽到取物条目的 count 会丢 ⇒ {@code [selftest] 脚本不变量①…} 必须报出「count」那条差异；</li>
	 *   <li>把 {@code time(...)} 的单位后缀全改成 {@code "t"} ⇒ 单位丢失 ⇒ 不变量①必须红；</li>
	 *   <li>把 {@code ScriptParser.attachComment} 里的 {@code ctx.owner} 改成永远 {@code PROGRAM}
	 *       ⇒ 条目备注全挂到程序备注上 ⇒ {@code 注释归属} 与不变量①同时红；</li>
	 *   <li>把 {@code noteSuffix} 的 {@code note.contains("*&#47;")} 判断去掉（一律用块注释）
	 *       ⇒ 含 {@code *&#47;} 的备注把注释提前闭合 ⇒ 不变量①必须红（保底形式那条用例）；</li>
	 *   <li>{@code ArmProgram.save} 里把 {@code if (!note.isEmpty())} 改成 {@code if (false)}
	 *       ⇒ 程序备注丢 ⇒ "程序备注进 NBT"那行必须报红；</li>
	 *   <li>把 {@code ArmProgramEntry.needsTarget()} 的过滤那一支删掉 ⇒ 容器模式的坐标与
	 *       {@code source=} 都不再写出来 ⇒ 「过滤容器」那份语料的不变量①必须红。
	 *       ⚠️ **第四十一轮起这条红的来路变了**：{@code scriptDifference} 现在只在"这一条需要坐标"时
	 *       比 offset（见那里的说明），单靠不变量①判不到它 —— 现在由两处接手：
	 *       ① ⑫ 的 {@code 过滤容器模式照旧写坐标}（反编译侧：直接看文本里有没有那两个数）；
	 *       ② 解析侧的模型不变量（{@code filter 12 64 -30 source=container} 会当场报"手持模式没有坐标"）
	 *       ⇒ 「过滤容器」那份语料**编译不过** ⇒ 不变量①那条也红。</li>
	 * </ol>
	 */
	private static void checkScriptRoundTrip(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();
		List<String> problems = new ArrayList<>();

		// ① 真实预设：全部导出 → 编译 → 比字段（含程序/任务/条目备注，§12.4 的"逐字段"）
		int presets = 0;
		int presetProblems = problems.size();
		for (Map.Entry<String, Supplier<ArmProgram>> entry : DevPresets.all()
			.entrySet()) {
			presets++;
			ArmProgram original = entry.getValue()
				.get();
			String text = ScriptCompiler.decompile(original, registries);
			ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, registries);
			if (compiled.hasErrors()) {
				problems.add("预设 " + entry.getKey() + " 反编译出来的文本编译不过：" + ScriptCompiler.errorLines(compiled.errors()));
				continue;
			}
			String difference = scriptDifference(original, compiled.value());
			if (difference != null)
				problems.add("预设 " + entry.getKey() + " 的不变量①不成立：" + difference);
			// ② 规范形式：同一份模型写两次必须一字不差（这条挂了，说明反编译器里藏了随机/顺序依赖）
			String again = ScriptCompiler.decompile(compiled.value(), registries);
			if (!text.equals(again))
				problems.add("预设 " + entry.getKey() + " 的反编译不是幂等的（两次输出不同）");
		}
		LOGGER.info("[selftest] 脚本不变量①（真实预设）: {} 份预设，逐字段等价失败 {} 处", presets,
			problems.size() - presetProblems);

		// ② 手写语料：覆盖"文本面"的那些写法（含全部操作类型、三种物品写法、两种备注写法）
		int corpus = 0;
		int corpusProblems = problems.size();
		for (Map.Entry<String, String> sample : SCRIPT_CORPUS.entrySet()) {
			corpus++;
			String source = sample.getValue();
			ScriptResult<ArmProgram> first = ScriptCompiler.compile(source, registries);
			if (first.hasErrors()) {
				problems.add("语料 " + sample.getKey() + " 编译不过：" + ScriptCompiler.errorLines(first.errors()));
				continue;
			}
			String canonical = ScriptCompiler.decompile(first.value(), registries);
			ScriptResult<ArmProgram> second = ScriptCompiler.compile(canonical, registries);
			if (second.hasErrors()) {
				problems.add("语料 " + sample.getKey() + " 的规范形式编译不过：" + ScriptCompiler.errorLines(second.errors()));
				continue;
			}
			// ★ 不变量 #2：decompile(compile(t)) == canonical(t)
			//   （canonical(t) = decompile(compile(t))，所以这条的实质是"再走一轮输出不变"）
			String canonicalAgain = ScriptCompiler.decompile(second.value(), registries);
			if (!canonical.equals(canonicalAgain))
				problems.add("语料 " + sample.getKey() + " 的不变量②不成立（规范形式再编译后变了）");
			// ★ 不变量 #1 在语料上也要成立：compile(canonical) ≡ compile(t)
			String difference = scriptDifference(first.value(), second.value());
			if (difference != null)
				problems.add("语料 " + sample.getKey() + " 的不变量①不成立：" + difference);
		}
		LOGGER.info("[selftest] 脚本不变量②（手写语料）: {} 份语料，规范形式失败 {} 处", corpus,
			problems.size() - corpusProblems);

		// ③ 注释 = 备注的**归属**（§12.3a）：三种归属各一条，逐字符比对
		ScriptResult<ArmProgram> notes = ScriptCompiler.compile(SCRIPT_NOTES_SAMPLE, registries);
		boolean notesCompiled = !notes.hasErrors();
		String programNote = notesCompiled ? notes.value().note : "";
		String taskNote = notesCompiled && !notes.value().tasks.isEmpty() ? notes.value().tasks.get(0).note : "";
		String entryNote = notesCompiled && !notes.value().tasks.isEmpty() && !notes.value().tasks.get(0).entries.isEmpty()
			? notes.value().tasks.get(0).entries.get(0).note
			: "";
		// ⚠️ "编译不过"要先报，别的判据都在它成立的前提下才有意义（第一版把顺序写反，
		//   编译失败时会额外报三条"备注丢了"的假错 —— 决策 85："只量该量的东西"）。
		boolean programNoteOk = notesCompiled && "程序备注\n第二行".equals(programNote);
		boolean taskNoteOk = notesCompiled && "任务备注".equals(taskNote);
		boolean entryNoteOk = notesCompiled && "条目备注\n第二行".equals(entryNote);
		// 块注释与 note="…" 是同一个字段的两种写法（§12.3a 细则 3），编译后必须一模一样
		ScriptResult<ArmProgram> quoted = ScriptCompiler.compile(SCRIPT_QUOTED_NOTE_SAMPLE, registries);
		boolean quotedOk = !quoted.hasErrors() && !quoted.value().tasks.isEmpty()
			&& !quoted.value().tasks.get(0).entries.isEmpty()
			&& quoted.value().tasks.get(0).entries.get(0).note.equals("条目备注\n第二行");
		LOGGER.info("[selftest] 脚本注释归属: 程序备注={} 任务备注={} 条目备注={} 保底写法同值={} （程序=[{}] 任务=[{}] 条目=[{}]）",
			programNoteOk, taskNoteOk, entryNoteOk, quotedOk, showNote(programNote), showNote(taskNote), showNote(entryNote));
		if (!notesCompiled)
			problems.add("注释归属用例编译不过：" + ScriptCompiler.errorLines(notes.errors()));
		if (!programNoteOk)
			problems.add("文件最前面的注释块没有变成程序备注：[ " + showNote(programNote) + " ]");
		if (!taskNoteOk)
			problems.add("task 行尾的注释没有变成任务备注：[ " + showNote(taskNote) + " ]");
		if (!entryNoteOk)
			problems.add("条目行尾 / 紧跟其后的注释没有变成条目备注：[ " + showNote(entryNote) + " ]");
		if (!quotedOk)
			problems.add("note=\"…\" 保底写法与块注释不产生同一个备注（两种写法必须是一个字段）");

		// ④ 两个新字段的 NBT 往返（§12.3b：**非空才写**）
		ArmProgram noteProgram = notesCompiled ? notes.value() : new ArmProgram();
		CompoundTag noteTag = noteProgram.save(registries);
		boolean programNoteKey = noteTag.contains("note");
		boolean taskNoteKey = !noteTag.getList("tasks", Tag.TAG_COMPOUND)
			.isEmpty()
			&& noteTag.getList("tasks", Tag.TAG_COMPOUND)
				.getCompound(0)
				.contains("note");
		ArmProgram noteBack = ArmProgram.load(noteTag, registries);
		boolean noteTextKept = noteBack.note.equals(programNote)
			&& (noteBack.tasks.isEmpty() || noteBack.tasks.get(0).note.equals(taskNote));
		ArmProgram plain = new ArmProgram();
		plain.tasks.add(new ArmProgramTask());
		boolean emptyNotWritten = !plain.save(registries)
			.contains("note")
			&& !plain.save(registries)
				.getList("tasks", Tag.TAG_COMPOUND)
				.getCompound(0)
				.contains("note");
		LOGGER.info("[selftest] 脚本备注字段: 程序 note 键={} 任务 note 键={} 文本保真={} 空备注不写键={}", programNoteKey,
			taskNoteKey, noteTextKept, emptyNotWritten);
		if (!programNoteKey || !taskNoteKey)
			problems.add("程序 / 任务备注没有写进 NBT（键 note 缺失）—— 导出再导入会丢注释");
		if (!noteTextKept)
			problems.add("程序 / 任务备注过不了 NBT 往返");
		if (!emptyNotWritten)
			problems.add("空备注也被写进了 NBT（§12.3b 要求非空才写）");

		// ⑤ 编译期必须报错的那些（§12.3 的净收益）：每条都要报在**写那一行**上
		String overLongNote = "x".repeat(ArmProgramEntry.NOTE_MAX + 1);
		boolean overLongReported = reportsAt(registries,
			"task main\n  take 0 0 0 /*" + overLongNote + "*/\n", 2, "128");
		boolean unknownTaskReported = reportsAt(registries,
			"task main\n  call 不存在的任务\n", 2, "unknown task") && reportsAt(registries,
				"task main\n  take 0 0 0 fail=goto 不存在的任务\n", 2, "unknown task");
		// 未知任务名要**列出已知任务名**（用户点名要的：{@code unknown task: cook_wok} + 已知清单）
		ScriptResult<ArmProgram> unknownTask = ScriptCompiler.compile("task main\n  call 没有这个\n", registries);
		boolean knownListed = !unknownTask.hasErrors() || unknownTask.errors()
			.stream()
			.anyMatch(error -> error.message()
				.contains("main"));
		boolean unknownItemReported = reportsAt(registries, "task main\n  take 0 0 0 icon=notamod:no_such_item\n", 2,
			"未知物品");
		boolean badCountReported = reportsAt(registries, "task main\n  take 0 0 0 count=65\n", 2, "count");
		boolean badCoordReported = reportsAt(registries, "task main\n  take 0 0 999999999\n", 2, "坐标");
		boolean badOpReported = reportsAt(registries, "task main\n  teleport 0 0 0\n", 2, "未知操作");
		boolean badModeReported = reportsAt(registries, "task main\n  signal recv_x on freq=iron_ingot\n", 2, "信号模式");
		boolean missingFreqReported = reportsAt(registries, "task main\n  signal recv on\n", 2, "频率");
		// 「接收距离」的三条编译期错误（用户口径：只对「收」有意义、0..256）：
		// ① 发送侧写了 range= ⇒ 报"range 只对收信号有意义"（**不静默丢弃**，否则玩家以为"只发 16 格内"）；
		// ② 超出 0..256 ⇒ 报范围错；③ 写在别的操作上 ⇒ 报"range 只对 signal 有意义"。
		// 反证：把 ScriptParser 里那段 `if (parsed.range != 0 && parsed.mode != RECEIVE)` 删掉 ⇒ ① 红。
		boolean rangeOnSendReported = reportsAt(registries,
			"task main\n  signal send_on range=16 freq=iron_ingot + copper_ingot\n", 2, "range")
			&& reportsAt(registries, "task main\n  signal send_off range=1 freq=iron_ingot + copper_ingot\n", 2,
				"range");
		boolean rangeOutOfRangeReported = reportsAt(registries,
			"task main\n  signal recv on range=300 freq=iron_ingot + copper_ingot\n", 2, "range")
			&& reportsAt(registries, "task main\n  signal recv on range=-1 freq=iron_ingot + copper_ingot\n", 2,
				"range");
		boolean rangeWrongOpReported = reportsAt(registries, "task main\n  take 0 0 0 range=16\n", 2, "range");
		// ⚠️ `/*` 在**引号外**才算注释开头（§12.3a 细则 2），所以这里必须用真的注释、
		// 而不是 "引号里带 /* 的字符串"：后者正是那条细则要保护的写法。
		boolean unclosedCommentReported = reportsAt(registries, "task main\n  take 0 0 0 /* 没闭合\n", 2, "没有闭合");
		LOGGER.info(
			"[selftest] 脚本编译期错误: 超长备注={} 未知任务={} 已知任务名列出={} 未知物品={} 个数越界={} 坐标越界={} 未知操作={} 未知模式={} 缺频率={} 注释未闭合={}",
			overLongReported, unknownTaskReported, knownListed, unknownItemReported, badCountReported, badCoordReported,
			badOpReported, badModeReported, missingFreqReported, unclosedCommentReported);
		LOGGER.info("[selftest] 脚本编译期错误（接收距离）: 发送侧写了range={} 越界={} 写错操作={}", rangeOnSendReported,
			rangeOutOfRangeReported, rangeWrongOpReported);
		if (!overLongReported)
			problems.add("超过 " + ArmProgramEntry.NOTE_MAX + " 字的备注没有报错（静默截断会破坏不变量①）");
		if (!unknownTaskReported)
			problems.add("未知任务名没有在编译期报错（这是跳转只认任务名换来的净收益）");
		if (!knownListed)
			problems.add("未知任务名的错误里没有列出已知任务名");
		if (!unknownItemReported)
			problems.add("未知物品 id 没有报错");
		if (!badCountReported)
			problems.add("count 越界（65）没有报错");
		if (!badCoordReported)
			problems.add("坐标越界没有报错");
		if (!badOpReported)
			problems.add("未知操作名没有报错");
		if (!badModeReported)
			problems.add("未知信号模式没有报错");
		if (!missingFreqReported)
			problems.add("signal 没绑频率没有报错（那条条目会永远等不到）");
		if (!unclosedCommentReported)
			problems.add("块注释没有闭合没有报错");
		if (!rangeOnSendReported)
			problems.add("发送 / 中断写了 range= 没有报错（静默收下会让玩家以为\"只发 N 格内\"）");
		if (!rangeOutOfRangeReported)
			problems.add("range 超出 0..256 没有报错");
		if (!rangeWrongOpReported)
			problems.add("range 写在非 signal 操作上没有报错");

		// ⑥ 物品的三种写法（§12.1）：只有"无组件 / 唯一组件是染色 / 其它"这三种输出
		ItemStack plainItem = new ItemStack(Items.IRON_INGOT);
		ItemStack dyed = new ItemStack(Items.LEATHER_HELMET);
		dyed.set(DataComponents.DYED_COLOR, new DyedItemColor(0xC41E3A, true));
		ItemStack bannered = new ItemStack(Items.BLACK_BANNER);
		// ⚠️ 必须**真给一个组件**：空白旗帜本来就没有组件，写成裸 id 才是对的（原来这条断言因此假红）。
		//    用 `custom_name` 还有个好处：它**不参与频率身份**（§12.2）⇒ 顺带覆盖"组件不影响频率"的那种物品。
		bannered.set(DataComponents.CUSTOM_NAME, Component.literal("示例"));
		ArmProgram itemProbe = new ArmProgram();
		ArmProgramTask itemTask = new ArmProgramTask();
		itemTask.name = "物品";
		ArmProgramEntry ta = new ArmProgramEntry();
		ta.op = OperationType.TAKE;
		ta.offset = new BlockPos(1, 0, 0);
		ta.icon = plainItem;
		ArmProgramEntry tb = new ArmProgramEntry();
		tb.op = OperationType.TAKE;
		tb.offset = new BlockPos(2, 0, 0);
		tb.icon = dyed;
		ArmProgramEntry tc = new ArmProgramEntry();
		tc.op = OperationType.TAKE;
		tc.offset = new BlockPos(3, 0, 0);
		tc.icon = bannered;
		itemTask.entries.add(ta);
		itemTask.entries.add(tb);
		itemTask.entries.add(tc);
		itemProbe.tasks.add(itemTask);
		String itemText = ScriptCompiler.decompile(itemProbe, registries);
		boolean plainWritten = itemText.contains("icon=minecraft:iron_ingot")
			&& !itemText.contains("icon={id:\"minecraft:iron_ingot\"");
		boolean sugarWritten = itemText.contains("#C41E3A");
		// 完整 SNBT 的键顺序是 codec 的字典序（components 在 id 之前），所以不能断言 `icon={id:…`
		boolean snbtWritten = itemText.contains("icon={components:") && itemText.contains("id:\"minecraft:black_banner\"");
		ScriptResult<ArmProgram> itemBack = ScriptCompiler.compile(itemText, registries);
		boolean sugarKept = !itemBack.hasErrors() && itemBack.value().tasks.get(0).entries.get(1)
			.icon.get(DataComponents.DYED_COLOR) != null
			&& itemBack.value().tasks.get(0).entries.get(1)
				.icon.get(DataComponents.DYED_COLOR)
				.rgb() == 0xC41E3A;
		String itemDump = itemText.replace("\n", " ⏎ ");
		if (itemDump.length() > 400)
			itemDump = itemDump.substring(0, 400) + "…";
		LOGGER.info("[selftest] 脚本物品写法: 无组件只写 id={} 唯一染色用 #RRGGBB={} 其它写完整 SNBT={} 染色往返={} 全文=[{}]",
			plainWritten, sugarWritten, snbtWritten, sugarKept, itemDump);
		if (!plainWritten)
			problems.add("无组件物品没有写成简写 id（§12.1 的规范形式）");
		if (!sugarWritten)
			problems.add("唯一组件是染色时没有写成 #RRGGBB");
		if (!snbtWritten)
			problems.add("带其它组件的物品没有写成完整 SNBT");
		if (!sugarKept)
			problems.add("#RRGGBB 染色过不了往返");

		// ⑦ 备注与**脚本字节预算**的量化（§12.3a 细则 4：注释不是免费的）。
		//    §17 起预算单位从"编译后 NBT"换成了"脚本字节"：注释就是备注、备注就是文本，量的正是它。
		ArmProgram budgetProbe = new ArmProgram();
		budgetProbe.note = "汉".repeat(ArmProgramEntry.NOTE_MAX);
		ArmProgramTask budgetTask = new ArmProgramTask();
		budgetTask.name = "预算";
		budgetTask.note = "汉".repeat(ArmProgramEntry.NOTE_MAX);
		budgetProbe.tasks.add(budgetTask);
		int withNotes = ProgramText.utf8Length(ScriptCompiler.decompile(budgetProbe, registries));
		budgetProbe.note = "";
		budgetTask.note = "";
		int withoutNotes = ProgramText.utf8Length(ScriptCompiler.decompile(budgetProbe, registries));
		boolean noteCostSane = withNotes > withoutNotes;
		LOGGER.info("[selftest] 脚本备注预算: 满备注 {} 字节 vs 无备注 {} 字节 ⇒ 两条满备注 ≈ {} 字节（占上限 {}%）", withNotes,
			withoutNotes, withNotes - withoutNotes,
			String.format(java.util.Locale.ROOT, "%.2f", (withNotes - withoutNotes) * 100.0 / ProgramText.MAX_BYTES));
		if (!noteCostSane)
			problems.add("备注没有进入脚本（预算讨论无从谈起）");

		// ⑧ 原点**不在脚本里**（用户第四十轮：它搬到了卡上，`@origin` 头彻底删除）。
		//    两条一起测才钉得住 —— 它们是**两个方向**：少任何一条，另一半坏掉都能蒙过去：
		//      ① 反编译出来的文本**不含** `@origin`（有人把写头那条加回来 ⇒ 立刻红）；
		//      ② 手写 `@origin 1 2 3` 必须**报错**（有人把"静默忽略 / 照收"加回来 ⇒ 立刻红）。
		//    只测②的话，"反编译器又写出这一行"会静默通过（那份文本自己又能编译回来，看着一切正常）。
		//    ⚠️ 反证要点（§0.5）：把 ScriptDecompiler 的注释掉那句加回来 ⇒ ①红；
		//    把 ScriptParser 里 `@origin` 那一支改成 `return;` ⇒ ②红。
		ArmProgram headerProbe = new ArmProgram();
		ArmProgramTask headerTask = new ArmProgramTask();
		headerTask.name = "原点在卡上";
		ArmProgramEntry headerEntry = new ArmProgramEntry();
		headerEntry.op = OperationType.TAKE;
		headerEntry.offset = new BlockPos(-7, 2, 5);
		headerTask.entries.add(headerEntry);
		headerProbe.tasks.add(headerTask);
		String headerProbeText = ScriptCompiler.decompile(headerProbe, registries);
		boolean headerNotWritten = !headerProbeText.contains(ScriptParser.HEADER)
			&& !itemText.contains(ScriptParser.HEADER);
		// 文本照样能编译回同一份偏移（去掉原点**没有**弄丢任何坐标信息）
		ScriptResult<ArmProgram> headerProbeBack = ScriptCompiler.compile(headerProbeText, registries);
		boolean headerProbeRoundTrip = !headerProbeBack.hasErrors()
			&& !headerProbeBack.value().tasks.isEmpty()
			&& !headerProbeBack.value().tasks.get(0).entries.isEmpty()
			&& headerProbeBack.value().tasks.get(0).entries.get(0).offset.equals(headerEntry.offset);
		// 手写 `@origin 1 2 3` ⇒ 第一行报错，且提示要说清"它现在存在卡上"
		boolean headerRejected = reportsAt(registries, "@origin 1 2 3\ntask main\n  take 0 0 0\n", 1, "卡上");
		LOGGER.info("[selftest] 脚本不再有原点头: 反编译文本不含 @origin={} 去掉头不丢坐标={} "
			+ "手写 @origin 报错={}", headerNotWritten, headerProbeRoundTrip, headerRejected);
		if (!headerNotWritten)
			problems.add("反编译出来的文本里出现了 @origin 头 —— 原点又跟着脚本走了（用户第四十轮要求它只住在卡上）");
		if (!headerProbeRoundTrip)
			problems.add("一份普通程序（含非零偏移）反编译再编译之后坐标变了");
		if (!headerRejected)
			problems.add("手写 @origin 没有被报错（它会让人以为参考点跟着文本走，其实在卡上）");

		// ⑨ §12.3a 第 6 条：同一个归属对象上的两块注释**合并**成一个备注（换行连接），不报错
		ScriptResult<ArmProgram> merged = ScriptCompiler.compile(SCRIPT_MERGED_NOTE_SAMPLE, registries);
		boolean mergedCompiled = !merged.hasErrors();
		String mergedNote = mergedCompiled && !merged.value().tasks.isEmpty()
			&& !merged.value().tasks.get(0).entries.isEmpty() ? merged.value().tasks.get(0).entries.get(0).note : "";
		// 期望值**带首尾空格**：`/* 行尾一句 */` 的块体原样就是" 行尾一句 "（§12.3a 逐字符原样），
		// 少写空格会把一条正确实现判红。
		boolean mergedOk = mergedCompiled && " 行尾一句 \n 紧接着再补一句 ".equals(mergedNote);
		boolean noQuotedDuplicateError = mergedCompiled || merged.errors()
			.stream()
			.noneMatch(error -> error.message()
				.contains("已经有一个备注"));
		boolean mergedCanonicalKeeps = mergedCompiled && ScriptCompiler.canonical(SCRIPT_MERGED_NOTE_SAMPLE, registries)
			.contains("行尾一句") && ScriptCompiler.canonical(SCRIPT_MERGED_NOTE_SAMPLE, registries)
				.contains("紧接着再补一句");
		LOGGER.info("[selftest] 脚本两块注释合并: 编译通过={} 备注=[{}] 没有报重复={} 规范形式两块都在={}", mergedCompiled,
			showNote(mergedNote), noQuotedDuplicateError, mergedCanonicalKeeps);
		if (!mergedCompiled)
			problems.add("两块注释（行尾 + 紧跟独占行）被当成错误了：" + ScriptCompiler.errorLines(merged.errors()));
		if (!mergedOk)
			problems.add("两块注释没有合并成换行连接的备注：[ " + showNote(mergedNote) + " ]");
		if (!mergedCanonicalKeeps)
			problems.add("两块注释合并后再反编译，备注内容丢了");

		// ⑨b 写在条目**上面**的注释归**上一条**（§12.3a 唯一那条规则：归"上面最近的那个拥有备注的东西"）
		ScriptResult<ArmProgram> leading = ScriptCompiler.compile(
			"task main\n  take 0 0 0\n  /* 上面那句的说明 */\n  take 1 0 0\n", registries);
		ArmProgramTask leadingTask = leading.hasErrors() || leading.value().tasks.isEmpty() ? null
			: leading.value().tasks.get(0);
		boolean leadingHasTwo = leadingTask != null && leadingTask.entries.size() > 1;
		String leadingFirst = leadingHasTwo ? leadingTask.entries.get(0).note : "";
		String leadingSecond = leadingHasTwo ? leadingTask.entries.get(1).note : "";
		boolean leadingOk = !leading.hasErrors() && " 上面那句的说明 ".equals(leadingFirst) && leadingSecond.isEmpty();
		LOGGER.info("[selftest] 脚本注释归属(写在上面): 归上一条={} 上一条备注=[{}] 下一条备注=[{}]", leadingOk,
			showNote(leadingFirst), showNote(leadingSecond));
		if (!leadingOk)
			problems.add("写在条目上面的注释没有归到**上一条**：[上=" + showNote(leadingFirst) + " 下="
				+ showNote(leadingSecond) + " ]");

		// ⑩ §12.3 末尾（主代理 87③）：`unload amount=` 必填，反编译器永远写
		boolean unloadAmountRequired = reportsAt(registries, "task main\n  unload 0 0 2 repeat=once\n", 2, "amount");
		ScriptResult<ArmProgram> oneStack = ScriptCompiler.compile("task main\n  unload 0 0 2 amount=one_stack\n",
			registries);
		boolean oneStackRoundTrip = !oneStack.hasErrors()
			&& ScriptCompiler.decompile(oneStack.value(), registries)
				.contains("amount=one_stack");
		LOGGER.info("[selftest] 脚本 unload 必填: 缺 amount 报错={} one_stack 往返={}", unloadAmountRequired, oneStackRoundTrip);
		if (!unloadAmountRequired)
			problems.add("unload 缺 amount= 没有报错（靠枚举默认值 = 第二个真理来源）");
		if (!oneStackRoundTrip)
			problems.add("unload amount=one_stack 过不了往返");

		// ⑪ SNBT 写法必须能被**原版 TagParser** 吃下（§12.1 明说复用 ItemStack.CODEC 的 SNBT）
		String snbtProbe = null;
		String snbtError = null;
		for (int i = itemText.indexOf("icon="); i >= 0; i = itemText.indexOf("icon=", i + 1)) {
			int end = itemText.indexOf('\n', i);
			String line = end < 0 ? itemText.substring(i) : itemText.substring(i, end);
			String value = line.substring("icon=".length())
				.trim();
			if (!value.startsWith("{"))
				continue;
			snbtProbe = value;
			try {
				net.minecraft.nbt.TagParser.parseTag(value);
			} catch (Throwable failed) {
				snbtError = value + " ⇒ " + failed;
			}
		}
		LOGGER.info("[selftest] 脚本 SNBT 原版解析: 探针={} 错误={}", snbtProbe == null ? "无" : showNote(snbtProbe), snbtError);
		if (snbtError != null)
			problems.add("SNBT 写法过不了原版 TagParser：" + snbtError);

		// ⑫ ★ 「不需要坐标的操作」不带坐标（用户第四十一轮：脚本里 `call 任务` 后面跟着一串 `-3 0 -1`，
		//    而「调用」是控制类操作 —— 既不移动、也没有目标，运行时**根本不读**这个字段）。
		//    四条一起钉，缺任何一条都会漏掉一半；再加一条 ②b 把"报错但内容还在"（§17.10）钉住
		//    （**改哪一行会让这条红**写在每条的括号里）：
		//      ① 反编译**不写**（把 ScriptDecompiler.writeOperands 里那个
		//         `else if (!entry.offset.equals(BlockPos.ZERO))` 支加回来 ⇒ ① 变 false）；
		//      ② 手写坐标**必须报错**（把 ScriptParser.parseEntry 里 `looksLikePosition` 那条
		//         报错删掉、退回 optionalPosition ⇒ ② 变 false：又变成静默接受）；
		//      ②b 报错之后**内容还在**（§17.10 带伤装载：把 `rest.subList(0,3).clear()` 那行
		//         换成 `return` ⇒ ②b 变 false：老文本里那串残留坐标会让整条「调用」消失）。
		//         这一条与②必须分开数：合成一个布尔量，红了也看不出是"没收下"还是"整条丢了"；
		//      ③ 老数据**自愈**（把 ArmProgramEntry.load 末尾那句 `if (!entry.needsTarget()) …ZERO`
		//         删掉 ⇒ ③ 变 false）；
		//      ④ 「过滤」的**容器模式照旧带坐标**（把 ArmProgramEntry.needsTarget() 里
		//         `op == FILTER && filterSource == CONTAINER` 那一支删掉 ⇒ ④ 变 false：
		//         两边同时丢坐标 —— 那是"容器模式的坐标是数据参数"这条被误伤的现场）。
		ArmProgram noCoordProbe = new ArmProgram();
		ArmProgramTask noCoordTask = new ArmProgramTask();
		noCoordTask.name = "main";
		ArmProgramEntry callProbe = new ArmProgramEntry();
		callProbe.op = OperationType.CALL;
		callProbe.callTarget = "task2";
		// 故意塞一个"老卡 / 老文本里的残留值"：写出侧**不该**把它写进文本
		callProbe.offset = new BlockPos(-3, 0, -1);
		noCoordTask.entries.add(callProbe);
		// 同一条判据的另一半：「过滤」的容器模式有坐标（数据参数），必须照旧写出来
		ArmProgramEntry containerFilterProbe = new ArmProgramEntry();
		containerFilterProbe.op = OperationType.FILTER;
		containerFilterProbe.filterSource = ArmProgramEntry.FilterSource.CONTAINER;
		containerFilterProbe.offset = new BlockPos(2, 0, -3);
		noCoordTask.entries.add(containerFilterProbe);
		noCoordProbe.tasks.add(noCoordTask);
		// 被调用的那个任务也要真存在：不然 ④ 编译这段文本时会因为"unknown task: task2"报错，
		// 而那是**另一条**守卫（悬空任务名，见 checkTextStorage ④）的事，不该让它污染这一条。
		ArmProgramTask noCoordSub = new ArmProgramTask();
		noCoordSub.name = "task2";
		ArmProgramEntry subBreak = new ArmProgramEntry();
		subBreak.op = OperationType.BREAK;
		noCoordSub.entries.add(subBreak);
		noCoordProbe.tasks.add(noCoordSub);

		String noCoordText = ScriptCompiler.decompile(noCoordProbe, registries);
		// ① 断言**那一行本身**：坐标没了、任务名还在（只判"不含 -3 0 -1"会漏掉"整行都没写出来"）
		String callLine = "";
		for (String printed : noCoordText.split("\n"))
			if (printed.trim()
				.startsWith("call"))
				callLine = printed.trim();
		boolean callWithoutCoords = callLine.equals("call task2") && !noCoordText.contains("-3 0 -1");
		boolean containerFilterCoordsKept = noCoordText.contains("2 0 -3");
		// ② 手写这种文本必须**报错**（静默丢弃会让"坐标已经删掉"与"坐标没生效"看不出区别）
		boolean handWrittenCoordsRejected = reportsAt(registries, "task main\n  call -3 0 -1 task2\n", 2, "不需要坐标")
			&& reportsAt(registries, "task main\n  wait 1 2 3 5s\n", 2, "不需要坐标");
		// ②b 报了错，但**这一行剩下的内容照样解析**（§17.10 带伤装载：老文本里的残留坐标不该
		//     让整条「调用」消失）—— 所以判据要同时看"有错"与"值里那条调用还在"
		ScriptResult<ArmProgram> coordsLineBack = ScriptCompiler.compile(
			"task main\n  call -3 0 -1 子任务\n  take 0 0 0\ntask 子任务\n  break\n", registries);
		boolean handWrittenCoordsKeptEntry = coordsLineBack.hasErrors()
			&& coordsLineBack.value().tasks.size() == 2
			&& coordsLineBack.value().tasks.get(0).entries.size() == 2
			&& "子任务".equals(coordsLineBack.value().tasks.get(0).entries.get(0).callTarget);
		// ③ 自愈：把那条"带坐标的调用"按老卡那样存一遍再读回来，offset 必须归零
		ArmProgramEntry healedEntry = ArmProgramEntry.load(callProbe.save(registries), registries);
		boolean noCoordOffsetHealed = healedEntry.offset.equals(BlockPos.ZERO);
		// ④ 过滤容器模式**照旧解析**（坐标吃进去了、并且留在模型里）：只测"写出"会漏掉解析那一半
		ScriptResult<ArmProgram> containerBack = ScriptCompiler.compile(noCoordText, registries);
		boolean containerFilterParsed = !containerBack.hasErrors()
			&& containerBack.value().tasks.size() == 2
			&& containerBack.value().tasks.get(0).entries.size() == 2
			&& containerBack.value().tasks.get(0).entries.get(1).offset.equals(new BlockPos(2, 0, -3))
			&& containerBack.value().tasks.get(0).entries.get(1).needsTarget();
		// 同一条判据的界外：**手持**过滤写了坐标也要报错（否则手写 `filter 1 2 3` 会落进模型里
		// 变成一个"定义外"的状态：反编译器不写它、载入时还会被清掉）
		boolean heldFilterWithCoordsRejected = reportsAt(registries, "task main\n  filter 1 2 3 source=held\n", 2,
			"手持模式");
		LOGGER.info("[selftest] 无坐标操作不带坐标: 调用条目反编译不含坐标={} 手写坐标报错={} 报错后内容还在={} "
			+ "载入后 offset 归零={} 过滤容器模式照旧写坐标={} 容器模式坐标往返={} 手持过滤写坐标报错={} （文本=[{}]）",
			callWithoutCoords, handWrittenCoordsRejected, handWrittenCoordsKeptEntry, noCoordOffsetHealed,
			containerFilterCoordsKept, containerFilterParsed, heldFilterWithCoordsRejected, showNote(noCoordText));
		if (!callWithoutCoords)
			problems.add("反编译把「调用」的坐标写进了脚本（那串数字运行时根本不看，用户就是被它问住的）");
		if (!handWrittenCoordsRejected)
			problems.add("手写的「调用」坐标没有被报错 —— 又变成静默接受（噪声会继续在文本里传下去）");
		if (!handWrittenCoordsKeptEntry)
			problems.add("手写坐标报错之后那条「调用」整条没了（§17.10：盘上只有文本，编译期严格不能吃掉内容）");
		if (!noCoordOffsetHealed)
			problems.add("老卡里「调用」的残留坐标没有被清零（读进来还留着，下一次保存才会发现它还在）");
		if (!containerFilterCoordsKept)
			problems.add("「过滤」容器模式的坐标没有被写出来（它是数据参数，丢了就变成读手上那份）");
		if (!containerFilterParsed)
			problems.add("「过滤」容器模式的坐标过不了脚本往返（或读回来 needsTarget() 变成假）");
		if (!heldFilterWithCoordsRejected)
			problems.add("手持过滤写了坐标没有被报错 —— 模型里会出现一个「不需要坐标却带着坐标」的状态");

		for (String problem : problems)
			LOGGER.error("[selftest] 脚本：{}", problem);
		LOGGER.info("[selftest] 脚本文本往返: {} 处问题（不变量①真实预设 + 不变量①②手写语料 共 {} 份语料）", problems.size(),
			presets + corpus);
	}

	/**
	 * 「操作记录表 → 上传」这条路（用户口径：记录表**不携带任何数据**，只用来确定"上传的是哪条臂"）。
	 *
	 * <p>★ **用户改口径（照 Create 那套）**：脚本文件住在**玩家本机**，服务端不存玩家脚本。
	 * 于是这条路被劈成两半，断言也按两半分开：
	 * <ol>
	 *   <li>{@code 服务端不写盘}：{@link ArmScriptExport#prepare} 只返回文本，**磁盘上不多任何文件**。
	 *       把 {@code prepare} 改成顺手调 {@code writeFile} ⇒ 变 false（那正是被推翻的旧口径：
	 *       旧实现 {@code write} 在服务端直接落盘）；</li>
	 *   <li>{@code 回传文本一致}：{@code prepare} 的文本必须与臂里程序的反编译结果逐字相同
	 *       （否则玩家会存下一份"另一份脚本"）；</li>
	 *   <li>{@code 落盘成功} / {@code 内容一致}：客户端那一半 {@link ArmScriptExport#writeFile}
	 *       写进 {@link ScriptFiles#directory()}，内容逐字相同。把 {@code writeString} 的 {@code text}
	 *       改成 {@code ""}、或删掉 {@code createDirectories} ⇒ 变 false；</li>
	 *   <li>{@code 空程序被拒}：删掉 {@code prepare} 开头那条 {@code tasks.isEmpty()} 判断
	 *       ⇒ 变 false（空程序会被写成一个空文件）；</li>
	 *   <li>{@code 非法名被拒}：把 {@link ScriptFiles#isValidName} 改成 {@code return true}
	 *       ⇒ 变 false（{@code ../escape} 会真的写到脚本目录**外面**）；</li>
	 *   <li>{@code 同名被拒} / {@code 原文件未被覆盖}：把 {@code CREATE_NEW} 换成 {@code CREATE} /
	 *       {@code TRUNCATE_EXISTING} ⇒ 变 false；</li>
	 *   <li>{@code 目录统一}：本机目录、管理员后门 {@code /sma code …} 与 {@code file(name)} 必须算出同一串路径。</li>
	 * </ol>
	 *
	 * <p>为什么这一段能跑在服务端自检里：两个半段都是**纯 {@code java.nio}**（不碰任何客户端类），
	 * "文件属于玩家本机"是**调用方**的语义而不是这段代码的语义 —— 自检进程里
	 * {@link ScriptFiles#directory()} 解析出的就是它自己的游戏目录。
	 *
	 * <p>⚠️ 这条**不**覆盖 {@code network/ExportScriptPacket} 里的"主手物品 / 方块与 BE 类型 / 距离"
	 * 三道守卫 —— 它们要一个 {@code IPayloadContext}（玩家、连接），无头环境拿不到；
	 * 那三道属于「待游戏内验证」。
	 */
	private static void checkOperationRecordExport(MinecraftServer server) {
		ServerLevel level = server.overworld();
		HolderLookup.Provider registries = server.registryAccess();
		List<String> problems = new ArrayList<>();
		Path dir = ScriptFiles.directory();
		String name = "selftest_record_export";
		Path file = dir.resolve(name + ScriptFiles.EXTENSION);

		// 先清掉上一轮可能留下的同名文件：不清的话 ②（能写成）与 ⑤（同名被拒）会互相污染，
		// 于是"上一轮失败留下的残骸"会让这一轮的红/绿看起来完全无缘无故。
		Path emptyFile = dir.resolve("selftest_record_empty" + ScriptFiles.EXTENSION);
		try {
			Files.deleteIfExists(file);
			Files.deleteIfExists(emptyFile);
		} catch (IOException failed) {
			problems.add("清不掉上一轮留下的 " + file + "（" + failed + "）");
		}

		ArmProgram program = DevPresets.mainWithSubTasks();

		// ① 服务端那一半：只准备文本，**磁盘上不许出现任何文件**（这是被推翻的旧口径的替代断言）
		// ② 客户端那一半：拿回传文本写本机文件，内容与臂里程序反编译结果逐字相同
		BlockPos armPos = new BlockPos(0, 200, 4);
		BlockState oldState = level.getBlockState(armPos);
		boolean serverWroteNothing = false;
		boolean textRoundTrips = false;
		boolean exported = false;
		boolean contentMatches = false;
		String expectedText = null;
		// ⑥ 要用：{@code writeFile} 真的把文件写到了哪儿（而不是"两个常量算出来相等"）
		Path writtenPath = null;
		try {
			SequencedArmBlockEntity arm = freshArm(level, armPos);
			if (arm == null) {
				problems.add("放不出测试用的臂（" + armPos.toShortString() + "），导出这条路测不到");
			} else {
				arm.setProgram(program);
				expectedText = ProgramText.toText(arm.getProgram(), registries);
				ArmScriptExport.Prepared prepared = ArmScriptExport.prepare(name, arm.getProgram(), registries);
				serverWroteNothing = prepared.ok() && prepared.text() != null && !Files.exists(file);
				if (!prepared.ok()) {
					problems.add("服务端那一半拒绝了这次导出：problem=" + prepared.problem());
				} else if (expectedText == null) {
					problems.add("前提不成立：臂里的程序反编译成了 null（prepare 却成功了，文案对不上）");
				} else {
					textRoundTrips = expectedText.equals(prepared.text());
					if (!textRoundTrips)
						problems.add("服务端回传的文本与臂里的程序文本不一致（玩家会存下另一份脚本）");
					ArmScriptExport.Outcome written = ArmScriptExport.writeFile(name, prepared.text());
					exported = written.ok() && Files.isRegularFile(file);
					writtenPath = written.file();
					if (!exported)
						problems.add("写本机文件没有成功：problem=" + written.problem() + " file=" + written.file());
					else
						contentMatches = expectedText.equals(Files.readString(file, StandardCharsets.UTF_8));
				}
				if (!serverWroteNothing)
					problems.add("服务端那一半动了磁盘（prepare 只该返回文本，文件却出现了）");
			}
		} catch (IOException failed) {
			problems.add("读回导出的文件失败：" + failed);
		} finally {
			level.setBlockAndUpdate(armPos, oldState);
		}

		// ③ 空程序被拒：两半各拒一次，而且**一个文件都不许留下**
		// （写成空文件比拒绝更糟：它看起来像一份能用的脚本）
		ArmScriptExport.Prepared emptyPrepared = ArmScriptExport.prepare("selftest_record_empty", new ArmProgram(),
			registries);
		ArmScriptExport.Outcome emptyWrite = ArmScriptExport.writeFile("selftest_record_empty", "");
		boolean emptyRejected = emptyPrepared.problem() == ArmScriptExport.Problem.EMPTY_PROGRAM
			&& emptyWrite.problem() == ArmScriptExport.Problem.EMPTY_PROGRAM
			&& !Files.exists(emptyFile);
		if (!emptyRejected)
			problems.add("空程序没有被拒（prepare=" + emptyPrepared.problem() + " writeFile=" + emptyWrite.problem() + "）");

		// ④ 非法文件名被拒（两半各判一次；每个输入各是一条独立的反证：`.`/`/`/`..`/超长/空/空白/null）
		String[] badNames = { "", " ", "../escape", "a/b", "a.b", "x".repeat(ScriptFiles.MAX_NAME + 1), null };
		boolean badNamesRejected = true;
		String badNameAccepted = null;
		for (String bad : badNames) {
			boolean serverRejects = ArmScriptExport.prepare(bad, program, registries)
				.problem() == ArmScriptExport.Problem.BAD_NAME;
			boolean clientRejects = ArmScriptExport.writeFile(bad, "selftest")
				.problem() == ArmScriptExport.Problem.BAD_NAME;
			if (serverRejects && clientRejects)
				continue;
			badNamesRejected = false;
			if (badNameAccepted == null)
				badNameAccepted = String.valueOf(bad) + "（服务端拒=" + serverRejects + " 客户端拒=" + clientRejects + "）";
		}
		if (!badNamesRejected)
			problems.add("这个文件名被接受了（应当拒绝）：[" + badNameAccepted + "]");

		// ⑤ 同名被拒：第二份程序与第一份**不同**，所以"被覆盖"能被读出来
		ArmProgram other = new ArmProgram();
		ArmProgramTask otherTask = new ArmProgramTask();
		otherTask.name = "other";
		ArmProgramEntry otherEntry = new ArmProgramEntry();
		otherEntry.op = OperationType.WAIT;
		otherTask.entries.add(otherEntry);
		other.tasks.add(otherTask);
		ArmScriptExport.Outcome second = ArmScriptExport.write(name, other, registries);
		boolean sameNameRejected = second.problem() == ArmScriptExport.Problem.NAME_TAKEN;
		boolean firstKept = false;
		try {
			firstKept = expectedText != null && Files.isRegularFile(file)
				&& expectedText.equals(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException failed) {
			problems.add("第二次导出之后读回第一份文件失败：" + failed);
		}
		if (!sameNameRejected)
			problems.add("同名导出没有被拒（problem=" + second.problem() + "）—— 它会覆盖别人的脚本");
		if (!firstKept)
			problems.add("同名那次导出把第一份文件覆盖掉了");

		// ⑥ 目录统一：本机目录、{@code file(name)} 与"导出那条路**真的**写下的路径"必须是同一串
		//    （PLAIN-ASCII 尾段也钉一下，免得有人把目录常量改成 config 下却忘了改文案）。
		//    ★ 这条断言的旧口径是"客户端的路径 == 服务端命令 {@code SequencedArmCommands.scriptDir()}"，
		//    第二步把服务端那条路整组删掉了（服务端不碰脚本文件）⇒ 改成与 {@code writeFile} 的
		//    实际产物对齐 —— 判据仍然"只有一处"，而且断言的是**真的落在那个目录**，比比较两个常量更强。
		boolean dirUnified = dir.equals(ScriptFiles.directory())
			&& dir.endsWith(ScriptFiles.DIRECTORY)
			&& file.getParent()
				.equals(dir)
			&& file.equals(ScriptFiles.file(name))
			&& file.equals(writtenPath);
		if (!dirUnified)
			problems.add("脚本目录不统一：目录=" + dir + " 期望文件=" + file + " 实际写下的=" + writtenPath);

		LOGGER.info("[selftest] 记录表导出: 服务端不写盘={} 回传文本一致={} 落盘成功={} 内容一致={} 空程序被拒={} 非法名被拒={} "
			+ "同名被拒={} 原文件未被覆盖={} 目录统一={} （本机目录={}）", serverWroteNothing, textRoundTrips, exported,
			contentMatches, emptyRejected, badNamesRejected, sameNameRejected, firstKept, dirUnified, dir);
		for (String problem : problems)
			LOGGER.error("[selftest] 记录表导出：{}", problem);

		// 收尾：把自检写进去的文件删掉（这是**真实目录**，跑完自检不该在里面留东西）
		try {
			Files.deleteIfExists(file);
			Files.deleteIfExists(emptyFile);
		} catch (IOException failed) {
			LOGGER.warn("[selftest] 记录表导出：删不掉自检产生的 {}（{}），手动删一下", file, failed.toString());
		}
	}

	/**
	 * 「操作导入桌」（第二步：**客户端读本机文件 + 分片上传**）的自检：把"上传 → 裁决 → 写卡"整条链
	 * 从**真实入口**驱动一遍（真的跑 {@code startUpload / acceptChunk / tick()}，真的看输出槽里那张卡）。
	 *
	 * <p>★ <b>这一步推翻了两条旧口径，所以这一节整体重写了</b>（不是"顺手改松"）：
	 * ① 文件不再由服务端读 —— 于是"文件不存在 / 读不出来"这类断言从服务端搬到了客户端
	 * （{@link ProgramImportFiles#read}，这里仍然用真文件驱动它，因为它是界面按确认时真正走的那一段）；
	 * ② 进度不再由 tick 推进（固定 100 tick 已删） —— 于是 {@code 进度推进} 那条断言换成
	 * "收下第一片之后 {@code uploadReceived} 与 {@code uploadProgress()} 往前走了、但还没到 1"，
	 * 它钉的是**字节驱动**这件事本身。
	 *
	 * <p>字段与"改哪一行会让它变红"：
	 * <ol>
	 *   <li>{@code 空输入被拒}：删掉 {@code startUpload} 开头那条
	 *       {@code input.isEmpty() || !isTable(input)} 判断 ⇒ 变 false（会在没有输入的情况下开会话）；</li>
	 *   <li>{@code 非空白输入被拒}：删掉 {@code if (CardProgramStorage.hasProgram(input)) return NOT_BLANK;}
	 *       ⇒ 变 false（一张已经有程序的表会被"导入"覆盖成新程序，等于静默丢程序）；</li>
	 *   <li>{@code 一桌一会话}：删掉 {@code startUpload} 第一行 {@code if (isImporting) return ALREADY_UPLOADING;}
	 *       ⇒ 变 false（同一张桌子会被两个上传同时灌进一个缓冲）；</li>
	 *   <li>{@code 前多半片} / {@code 字节进度}：把 {@code acceptChunk} 里
	 *       {@code uploadReceived = receivedBytes;} 那一句删掉 ⇒ 变 false（进度永远 0，
	 *       界面上的进度条不动）；把 {@code uploadProgress()} 改成固定值 ⇒ 同样变 false；</li>
	 *   <li>{@code 产出与文本一致}：把 {@code completeUpload} 里的
	 *       {@code CardProgramStorage.writeText(out, text)} 换成写空文本、或把 {@code text} 换成文件名
	 *       ⇒ 变 false；把 {@code inventory.setStackInSlot(SLOT_OUTPUT, out)} 删掉 ⇒ 变 false；</li>
	 *   <li>{@code 乱序被拒}：删掉 {@code acceptChunk} 里 {@code seq != nextSeq} 那道 ⇒ 变 false
	 *       （乱序片会被静默拼进文本，产出是一份玩家没写过的脚本）；</li>
	 *   <li>{@code 片里总量不一致被拒}：删掉 {@code totalBytes != expectedTotal} 那道 ⇒ 变 false
	 *       （另一次上传的片能被拼进这次会话）；</li>
	 *   <li>{@code 超量被拒}：删掉 {@code receivedBytes + data.length > expectedTotal} 那道 ⇒ 变 false
	 *       （会话能以超过被接受的总量继续吃字节）；</li>
	 *   <li>{@code 开始就超上限被拒} / {@code 空文件被拒}：删掉 {@code totalBytes > MAX_BYTES} /
	 *       {@code totalBytes <= 0} 那两道 ⇒ 变 false（一个"永远收不满"的会话会挂到超时）；
	 *       而且这两条与下面 {@code 拒收不消耗} 一起钉住"**拒绝发生在消耗输入之前**"；</li>
	 *   <li>{@code 输入被消耗}：把 {@code startUpload} 里那句
	 *       {@code inventory.setStackInSlot(SLOT_INPUT, ItemStack.EMPTY)} 删掉 ⇒ 变 false
	 *       （输入表还在 + 输出又出来一张 ⇒ 白得一张卡）；</li>
	 *   <li>{@code 编译不过被拒}：把 {@code completeUpload} 里的 {@code compiled.hasErrors()} 判断删掉
	 *       ⇒ 变 false（会给玩家一张编译器不认的卡）；</li>
	 *   <li>{@code 超时丢弃}：把 {@code tick()} 里那条静默超时删掉 ⇒ 变 false
	 *       （关掉界面之后这张桌子会永远卡在"正在导入"）；</li>
	 *   <li>{@code 输出槽占用被拒}：删掉 {@code if (!getStackInSlot(SLOT_OUTPUT).isEmpty()) return OUTPUT_BUSY;}
	 *       ⇒ 变 false（产出会顶掉玩家放在输出槽里的东西）；</li>
	 *   <li>{@code 收满但写不下就不写}：把 {@code completeUpload} 里
	 *       {@code if (!ProgramText.withinLimit(text)) ... return TOO_LARGE;} 删掉 ⇒ 变 false
	 *       （一张**没有程序**的空操作表会落进输出槽，玩家会以为导入成功）；</li>
	 *   <li>{@code 输入槽过滤}：把 {@link ProgramImportTableMenu#isAcceptedInput} 里的
	 *       {@code !CardProgramStorage.hasProgram(stack)} 删掉 ⇒ 变 false；</li>
	 *   <li>{@code 列表里有它} / {@code 本机读得出}：把 {@link ProgramImportFiles#list} 里的
	 *       {@code .sma} 过滤或 {@code isValidName} 过滤删掉、或把 {@code read} 里的
	 *       {@code isRegularFile} 删掉 ⇒ 对应那项变 false（列表里会出现导不进来的名字）。</li>
	 * </ol>
	 *
	 * <p>⚠️ 这条**不**覆盖两个网络包里的三道守卫（"玩家开着的菜单是不是这台机器"、
	 * 旁观者、片大小解码上限）—— 它们要一个 {@code IPayloadContext}（真实连接），无头环境拿不到；
	 * 那三条属于「待游戏内验证」。界面本身（进度条、标题三态）、以及"上传者关界面后由
	 * {@code tick()} 丢弃"那条（它要一个真 ServerPlayer），同理。
	 */
	private static void checkProgramImportTable(MinecraftServer server) {
		ServerLevel level = server.overworld();
		List<String> problems = new ArrayList<>();

		Path dir = ScriptFiles.directory();
		String goodName = "selftest_import_ok";
		String badName = "selftest_import_bad";
		String goodText = "task main\n  wait 1s\n  break\n";
		String badText = "task main\n  take 0 0 0\n  this_is_not_an_operation\n";
		Path goodFile = dir.resolve(goodName + ScriptFiles.EXTENSION);
		// 分片夹具：把这一段字节**劈成两片**发。协议允许最后一片不满、也允许片小于 8 KiB
		// （CHUNK_BYTES 是上限而不是定长），这样"收了半片 / 收满"两个状态都能在真实入口上观察到。
		byte[] goodBytes = goodText.getBytes(StandardCharsets.UTF_8);
		int half = goodBytes.length / 2;
		byte[] firstChunk = Arrays.copyOfRange(goodBytes, 0, half);
		byte[] secondChunk = Arrays.copyOfRange(goodBytes, half, goodBytes.length);
		byte[] badBytes = badText.getBytes(StandardCharsets.UTF_8);

		try {
			Files.createDirectories(dir);
			Files.writeString(goodFile, goodText, StandardCharsets.UTF_8);
		} catch (IOException failed) {
			LOGGER.error("[selftest] 操作导入桌：写不出夹具脚本（{}），这一批测不到", failed.toString());
			return;
		}

		BlockPos tablePos = new BlockPos(2, 200, 8);
		BlockState oldState = level.getBlockState(tablePos);

		boolean emptyInputRejected = false;
		boolean notBlankRejected = false;
		boolean oneSessionAtATime = false;
		boolean listed = false;
		boolean localReadable = false;
		boolean halfChunkAccepted = false;
		boolean byteProgress = false;
		boolean producedWithProgram = false;
		boolean textMatchesFile = false;
		boolean inputConsumed = false;
		boolean importStopped = false;
		boolean outOfOrderRejected = false;
		boolean totalMismatchRejected = false;
		boolean overflowRejected = false;
		boolean tooLargeAtStartRejected = false;
		boolean emptyFileRejected = false;
		boolean rejectedKeepsInput = false;
		boolean compileRejected = false;
		boolean compileKeepsOutputEmpty = false;
		boolean timeoutDrops = false;
		boolean noSessionIsSilent = false;
		boolean outputBusyRejected = false;
		boolean noCardWhenUnwritable = false;
		boolean filterOk = false;
		// 诊断用：这些"拒绝类"以前红过，而错误文案只说"没有拒绝"——
		// 实测那时拿到的是 ALREADY_UPLOADING（上一次会话根本没结束），照文案去查判定会白查一轮。
		StartResult busyResult = null;
		StartResult tooLargeResult = null;
		ChunkResult lastChunkResult = null;

		try {
			level.setBlockAndUpdate(tablePos, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(tablePos, SequencedArmRegistration.PROGRAM_IMPORT_TABLE.get()
				.defaultBlockState());
			ProgramImportTableBlockEntity table =
				level.getBlockEntity(tablePos) instanceof ProgramImportTableBlockEntity be ? be : null;
			if (table == null) {
				problems.add("放不出测试用的导入桌（" + tablePos.toShortString() + "），导入这条路测不到");
			} else {
				// ① 空输入：连一张表都没有，不能开会话
				emptyInputRejected = table.startUpload(null, goodName, goodBytes.length) == StartResult.NO_INPUT
					&& !table.isImporting;

				// ①b 非空白输入：一张**已经有程序**的表也不能当输入（输入槽过滤与服务端判定两处都要拦）
				ItemStack used = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
				CardProgramStorage.writeText(used, goodText);
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT, used);
				notBlankRejected = table.startUpload(null, goodName, goodBytes.length) == StartResult.NOT_BLANK
					&& CardProgramStorage.hasProgram(table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT))
					&& !table.isImporting;
				filterOk = !ProgramImportTableMenu.isAcceptedInput(used)
					&& ProgramImportTableMenu.isAcceptedInput(new ItemStack(
						SequencedArmRegistration.OPERATION_TABLE.get()));
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT, ItemStack.EMPTY);

				// ② 列表与读取都走**本机磁盘**（界面按确认时真正走的两个入口）
				listed = ProgramImportFiles.list()
					.contains(goodName);
				ProgramImportFiles.Contents contents = ProgramImportFiles.read(goodName);
				localReadable = contents.ok() && goodText.equals(contents.text())
					&& Arrays.equals(goodBytes, contents.bytes());

				// ③ 成功路径：开会话 → 半片 → 收满，输出槽出现一张**带程序**的表，文本与文件逐字相同
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				boolean started = table.startUpload(null, goodName, goodBytes.length) == StartResult.STARTED;
				// 一桌一会话：已经有一个在跑时再来一个必须被拒（否则两个上传会灌进同一个缓冲）
				oneSessionAtATime = table.startUpload(null, goodName, goodBytes.length)
					== StartResult.ALREADY_UPLOADING;
				boolean fromZero = table.isImporting && table.uploadReceived == 0 && table.uploadProgress() == 0f;
				ChunkResult first = table.acceptChunk(0, goodBytes.length, firstChunk);
				halfChunkAccepted = first == ChunkResult.ACCEPTED && table.isImporting;
				// ★ 进度是**字节驱动**的：收下半片之后就是 received/total，
				//   不是"某个固定 tick 走到的百分比"（固定 100 tick 已随旧口径删除）
				byteProgress = table.uploadReceived == half && table.uploadTotal == goodBytes.length
					&& table.uploadProgress() > 0f && table.uploadProgress() < 1f;
				lastChunkResult = table.acceptChunk(1, goodBytes.length, secondChunk);
				ItemStack produced = table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT);
				producedWithProgram = lastChunkResult == ChunkResult.COMPLETED
					&& produced.getItem() == SequencedArmRegistration.OPERATION_TABLE.get()
						.asItem() && CardProgramStorage.hasProgram(produced);
				String fromFile = null;
				try {
					fromFile = Files.readString(goodFile, StandardCharsets.UTF_8);
				} catch (IOException failed) {
					problems.add("读回夹具脚本失败：" + failed);
				}
				textMatchesFile = fromFile != null && fromFile.equals(CardProgramStorage.readText(produced));
				inputConsumed = table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT)
					.isEmpty();
				importStopped = started && fromZero && !table.isImporting && table.uploadReceived == 0
					&& table.uploadTotal == 0;
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);

				// ④ 乱序：第 1 片先到 ⇒ 中止（不缓存乱序片、不等待重排）
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				table.startUpload(null, goodName, goodBytes.length);
				outOfOrderRejected = table.acceptChunk(1, goodBytes.length, secondChunk) == ChunkResult.BAD_SEQUENCE
					&& !table.isImporting;
				table.abortUpload("自检收尾");

				// ⑤ 片里的总字节与开会话时声明的不一致 ⇒ 中止（另一次上传的片不能拼进来）
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				table.startUpload(null, goodName, goodBytes.length);
				totalMismatchRejected = table.acceptChunk(0, goodBytes.length + 1, firstChunk)
					== ChunkResult.BAD_SEQUENCE && !table.isImporting;

				// ⑥ 超量：声明的总量比这一片还小 ⇒ 中止（不"截断到上限"，那会拼出半截脚本）
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				table.startUpload(null, goodName, half);
				overflowRejected = table.acceptChunk(0, half, goodBytes) == ChunkResult.TOO_LARGE
					&& !table.isImporting;

				// ⑦ 开始就超上限 / 空文件：**拒绝发生在消耗输入之前** ⇒ 输入槽里那张空白表还在
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				tooLargeResult = table.startUpload(null, goodName, ProgramText.MAX_BYTES + 1);
				tooLargeAtStartRejected = tooLargeResult == StartResult.TOO_LARGE && !table.isImporting;
				emptyFileRejected = table.startUpload(null, goodName, 0) == StartResult.EMPTY_FILE
					&& !table.isImporting;
				ItemStack kept = table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT);
				rejectedKeepsInput = !kept.isEmpty() && !CardProgramStorage.hasProgram(kept);
				if (tooLargeAtStartRejected && !rejectedKeepsInput)
					problems.add("开会话被拒之后输入槽里那张空白表被消耗掉了（被拒不该花掉一张卡）");

				// ⑧ 编译不过：收满了也**一个字都不写**（输出槽保持空）
				table.startUpload(null, badName, badBytes.length);
				compileRejected = table.acceptChunk(0, badBytes.length, badBytes)
					== ChunkResult.DOES_NOT_COMPILE
					&& table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT)
						.isEmpty();
				compileKeepsOutputEmpty = compileRejected && !table.isImporting;

				// ⑨ 超时：开会话之后一片都不来 ⇒ tick() 的静默超时把会话丢掉
				//    （"玩家关掉界面"那条路就靠它兜底 —— 人手不在时没有别的信号）
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				table.startUpload(null, goodName, goodBytes.length);
				for (int tick = 0; tick <= ProgramImportTableBlockEntity.UPLOAD_TIMEOUT_TICKS; tick++)
					table.tick();
				timeoutDrops = !table.isImporting
					&& table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT)
						.isEmpty();

				// ⑩ 没有会话时的片：**静默丢弃**（NO_UPLOAD），不报错、不重建会话
				noSessionIsSilent = table.acceptChunk(0, goodBytes.length, firstChunk) == ChunkResult.NO_UPLOAD;

				// ⑪ 输出槽有物品：不能开会话（也不能把输入表吃掉）
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT, new ItemStack(Items.STONE));
				busyResult = table.startUpload(null, goodName, goodBytes.length);
				outputBusyRejected = busyResult == StartResult.OUTPUT_BUSY
					&& !table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT)
						.isEmpty()
					&& !table.isImporting;
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT, ItemStack.EMPTY);

				// ⑫ 收满但**写不下**就不写：8 片全是非法 UTF-8 单字节（0x80）⇒ 解码成 U+FFFD 之后
				//    重新编码是 3 倍长度，于是"收到的字节没超"而"要写进卡的文本超了"。
				//    这是 completeUpload 里那道 withinLimit 的**唯一真实触发路径**（没有测试专用门）。
				int total = ProgramText.MAX_BYTES;
				int per = total / ProgramImportTableBlockEntity.MAX_CHUNKS;
				byte[] garbage = new byte[per];
				Arrays.fill(garbage, (byte) 0x80);
				table.inventory.setStackInSlot(ProgramImportTableBlockEntity.SLOT_INPUT,
					new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get()));
				table.startUpload(null, "selftest_import_garbage", total);
				ChunkResult garbageResult = ChunkResult.ACCEPTED;
				for (int seq = 0; seq < ProgramImportTableBlockEntity.MAX_CHUNKS; seq++)
					garbageResult = table.acceptChunk(seq, total, garbage);
				noCardWhenUnwritable = garbageResult == ChunkResult.TOO_LARGE
					&& table.inventory.getStackInSlot(ProgramImportTableBlockEntity.SLOT_OUTPUT)
						.isEmpty()
					&& !table.isImporting;
			}
		} finally {
			level.setBlockAndUpdate(tablePos, oldState);
			try {
				Files.deleteIfExists(goodFile);
			} catch (IOException failed) {
				LOGGER.warn("[selftest] 操作导入桌：删不掉自检产生的脚本（{}），手动删一下", failed.toString());
			}
		}

		if (!emptyInputRejected)
			problems.add("没有输入操作表时也开始了上传（应当拒绝）");
		if (!notBlankRejected)
			problems.add("输入槽那张表里已经有程序，却还是开始了上传（应当拒绝）");
		if (!oneSessionAtATime)
			problems.add("同一张桌子开了两个上传会话（一桌一会话这条被破坏了）");
		if (!listed)
			problems.add("脚本列表里找不到刚写下去的脚本（list() 的过滤/排序有问题）");
		if (!localReadable)
			problems.add("本机读取没有把文件原样交出来（read 的 BOM / 字节 / 文本有问题）");
		if (!halfChunkAccepted)
			problems.add("第一片没有被接收（acceptChunk 的片序 / 总量 / 大小判定有问题）");
		if (!byteProgress)
			problems.add("进度不是「已收字节 / 总字节」：收了半片之后 uploadReceived / uploadProgress 不对");
		if (!producedWithProgram)
			problems.add("收满之后输出槽里没有一张带程序的操作表（最后一片的结局=" + lastChunkResult + "）");
		if (!textMatchesFile)
			problems.add("输出那张操作表里的文本与文件内容不一致");
		if (!inputConsumed)
			problems.add("开会话之后输入槽没有清空（输入会被导入两次）");
		if (!importStopped)
			problems.add("收满之后 isImporting / 已收字节没有归位（会一直循环完成）");
		if (!outOfOrderRejected)
			problems.add("乱序片没有被拒（应当中止会话，而不是把它拼进文本）");
		if (!totalMismatchRejected)
			problems.add("片里的总字节与开会话时声明的不一致却没有被拒（另一次上传的片能拼进来）");
		if (!overflowRejected)
			problems.add("超过声明总量的片没有被拒（会话会一直吃字节）");
		if (!tooLargeAtStartRejected)
			problems.add("声明总量超过上限却没有被拒（startUpload 返回 " + tooLargeResult
				+ "）：一个永远收不满的会话会挂到超时");
		if (!emptyFileRejected)
			problems.add("空脚本（声明总量 0）没有被拒：它一片都不会来，只会等到超时");
		if (!rejectedKeepsInput)
			problems.add("被拒之后输入槽里的空白操作表被消耗掉了");
		if (!compileKeepsOutputEmpty)
			problems.add("编译不过的脚本还是产生了输出（最后一片应当 DOES_NOT_COMPILE 且输出槽为空）");
		if (!timeoutDrops)
			problems.add("开会话之后一片都不来，静默超时却没有把会话丢掉（桌子会永远卡在「正在导入」）");
		if (!noSessionIsSilent)
			problems.add("没有会话时的片没有返回 NO_UPLOAD（包那一侧会把同一件事重复报好几遍）");
		if (!outputBusyRejected)
			problems.add("输出槽有物品时还是开了会话（startUpload 返回 " + busyResult
				+ "，应为 OUTPUT_BUSY —— 产出会顶掉玩家的东西）");
		if (!noCardWhenUnwritable)
			problems.add("拼出来的文本超过上限之后输出槽里仍然出现了东西 —— 一张空卡比什么都没有更糟");
		if (!filterOk)
			problems.add("输入槽过滤没有拒绝「已经有程序的操作表」");

		LOGGER.info("[selftest] 操作导入桌: 空输入被拒={} 非空白输入被拒={} 一桌一会话={} 列表里有它={} 本机读得出={} "
			+ "前多半片={} 字节进度={} 产出带程序={} 文本与文件一致={} 输入被消耗={} 完成后归位={} 乱序被拒={} "
			+ "片里总量不一致被拒={} 超量被拒={} 开始就超上限被拒={} 空文件被拒={} 拒收不消耗={} 编译不过被拒={} "
			+ "超时丢弃={} 无会话静默={} 输出槽占用被拒={} 收满但写不下就不写={} （片上限={} 字节，最多 {} 片，"
			+ "静默超时={} tick，目录={}）", emptyInputRejected, notBlankRejected, oneSessionAtATime, listed,
			localReadable, halfChunkAccepted, byteProgress, producedWithProgram, textMatchesFile, inputConsumed,
			importStopped, outOfOrderRejected, totalMismatchRejected, overflowRejected, tooLargeAtStartRejected,
			emptyFileRejected, rejectedKeepsInput, compileKeepsOutputEmpty, timeoutDrops, noSessionIsSilent,
			outputBusyRejected, noCardWhenUnwritable, ProgramImportTableBlockEntity.CHUNK_BYTES,
			ProgramImportTableBlockEntity.MAX_CHUNKS, ProgramImportTableBlockEntity.UPLOAD_TIMEOUT_TICKS, dir);
		for (String problem : problems)
			LOGGER.error("[selftest] 操作导入桌：{}", problem);
	}

	/** 逐字段比较（§12.4 的"逐字段"）：**哪一处不同要说出来**，不然自检红了还得回去猜。 */
	@Nullable
	private static String scriptDifference(ArmProgram a, ArmProgram b) {
		if (a.tasks.size() != b.tasks.size())
			return "任务数 " + a.tasks.size() + " → " + b.tasks.size();
		// ⚠️ 这里原来还比 `origin` / `hasOrigin`。用户第四十轮原点搬到了**卡上**（它不属于程序、
		// 也不进文本），这两个字段已从模型里删除 —— 文本往返本来就不该影响卡上的参考点。
		if (!a.editorMode.equals(b.editorMode))
			return "编辑模式变了：" + a.editorMode + " → " + b.editorMode;
		if (a.editorTask != b.editorTask)
			return "正在编辑的任务下标变了：" + a.editorTask + " → " + b.editorTask;
		if (!a.note.equals(b.note))
			return "程序备注不同：[ " + showNote(a.note) + " ] → [ " + showNote(b.note) + " ]";
		for (int t = 0; t < a.tasks.size(); t++) {
			ArmProgramTask x = a.tasks.get(t);
			ArmProgramTask y = b.tasks.get(t);
			if (!x.name.equals(y.name))
				return "任务 " + t + " 名字变了：" + x.name + " → " + y.name;
			// ⚠️ 这里原来还比"循环方式 / 循环次数"：任务级循环删除后没有那两个字段了
			//   （主任务恒为无限循环、子任务的轮次归调用点的 callRepeat）⇒ 那两条比较随之删除。
			if (!x.note.equals(y.note))
				return "任务「" + x.name + "」的备注不同：[ " + showNote(x.note) + " ] → [ " + showNote(y.note) + " ]";
			if (!ItemStack.matches(x.icon, y.icon))
				return "任务「" + x.name + "」的图标变了";
			if (x.entries.size() != y.entries.size())
				return "任务「" + x.name + "」的条目数变了：" + x.entries.size() + " → " + y.entries.size();
			for (int e = 0; e < x.entries.size(); e++) {
				String problem = entryDifference(x.name, e, x.entries.get(e), y.entries.get(e));
				if (problem != null)
					return problem;
			}
		}
		return null;
	}

	@Nullable
	private static String entryDifference(String task, int index, ArmProgramEntry a, ArmProgramEntry b) {
		String where = "任务「" + task + "」第 " + (index + 1) + " 条";
		if (a.op != b.op)
			return where + "操作变了：" + a.op + " → " + b.op;
		// ★ offset 只在**这一条需要坐标**时比较（用户第四十一轮）：对不需要坐标的操作（等待 /
		//   信号 / 调用 / 退出 / 抛错 / 手持过滤），"offset 恒为 0"已经是**模型定义**的一部分
		//   （ArmProgramEntry.load 读完就自愈、编辑器切换操作时清零、ScriptDecompiler 也不写它）
		//   ⇒ 文本里本来就没有它，拿它当往返不变量等于把这条定义判红。这就是那条**被推翻的旧口径**
		//   （"非目标操作的非零 offset 也要在文本里往返"）—— 现在它的反证改由
		//   checkScriptRoundTrip ⑫ 的四条断言承担（写不写、收不收、自愈、容器过滤照旧）。
		//   只要**任一侧**需要坐标就比：那样"一边是容器过滤、一边是手持"这种真差异不会被这一条漏掉
		//   （它随后由下面的 filterSource 比较报出来，比"坐标变了"更贴切）。
		if ((a.needsTarget() || b.needsTarget()) && !a.offset.equals(b.offset))
			return where + "坐标变了：" + a.offset.toShortString() + " → " + b.offset.toShortString();
		if (a.delay != b.delay)
			return where + "delay 变了：" + a.delay + " → " + b.delay;
		if (a.face != b.face)
			return where + "朝向变了：" + a.face + " → " + b.face;
		if (a.failAmount != b.failAmount)
			return where + "时限变了：" + a.failAmount + " → " + b.failAmount;
		if (a.failUnit != b.failUnit)
			return where + "时限单位变了：" + a.failUnit + " → " + b.failUnit;
		if (a.onSuccess != b.onSuccess)
			return where + "成功后动作变了：" + a.onSuccess + " → " + b.onSuccess;
		if (!a.successTarget.equals(b.successTarget))
			return where + "成功落点变了：" + a.successTarget + " → " + b.successTarget;
		if (a.onTimeout != b.onTimeout)
			return where + "超时后动作变了：" + a.onTimeout + " → " + b.onTimeout;
		if (!a.timeoutTarget.equals(b.timeoutTarget))
			return where + "超时落点变了：" + a.timeoutTarget + " → " + b.timeoutTarget;
		if (!a.note.equals(b.note))
			return where + "备注不同：[ " + showNote(a.note) + " ] → [ " + showNote(b.note) + " ]";
		if (a.count != b.count)
			return where + "个数变了：" + a.count + " → " + b.count;
		// ★ 个数模式也必须逐字段往返（用户本轮口径：等于 / 小于等于）。
		//   加了字段却忘了写进文本的症状**是静默的**：脚本里"等于 3 个"读回来变成"最多 3 个"，
		//   于是这条操作从"必须够数才动"退化成"有多少拿多少"—— 只有丢过物品才会发现。
		//   反证：把 ScriptDecompiler 里那段 `if (entry.countMode != CountMode.ATMOST)` 删掉
		//   ⇒ 语料「个数模式」/「收存」反编译回来就丢了「等于」，这里报「个数模式变了」。
		//   （只在**两侧都**有 count 参数的操作上比：其它操作的这个字段从不进文本，比它等于把定义判红。）
		if (a.op.hasCount() && b.op.hasCount() && a.countMode != b.countMode)
			return where + "个数模式变了：" + a.countMode + " → " + b.countMode;
		// ★ 「时长」只在**这一条真有持续时间**时才进文本，所以只有那种情况才比：
		//   SIGNAL 里只有「发送通电」写 `place=`（用户本轮口径：中断 = 立即清除该频率、接收 = 等通电，
		//   两者都**不接收**持续时间参数），WAIT 写自己的时长。拿空参数去比等于把模型里
		//   那个"运行时一个字都不读"的残留值判成往返不变量 —— 那是**被这次推翻的旧口径**
		//   （旧语料里 `signal send_off place=1s`、旧断言"中断也带持续"都按新口径改掉了：
		//   语料里那行现在不含 place=，解析器对它报错；NBT 那条的载体换成 SEND_ON）。
		//   判据只有一处：{@link ArmProgramEntry#hasSignalDuration()}。
		boolean signalNoDuration = a.op == OperationType.SIGNAL && !a.hasSignalDuration()
			|| (b.op == OperationType.SIGNAL && !b.hasSignalDuration());
		if (!signalNoDuration && a.waitAmount != b.waitAmount)
			return where + "时长变了：" + a.waitAmount + " → " + b.waitAmount;
		if (!signalNoDuration && a.waitUnit != b.waitUnit)
			return where + "时长单位变了：" + a.waitUnit + " → " + b.waitUnit;
		// 极性（等通电 / 等断电）**只对"收/等"有意义**：发信号（send_on / send_off）的强度由模式本身决定，
		// 运行时不读这个字段（ProgramRuntime 的 SIGNAL 分支：收 = 阻塞等待走极性真值表；发 = 直接发/释放）。
		// 所以文本面**有意**不为 send 携带它（§12.3：只有 send_on 带 place=，两种发都不带极性），比较也必须放过 ——
		// 否则预设里那个"从 recv 改过来时留下的"残留值会把一条正确实现判红。
		boolean sendMode = a.op == OperationType.SIGNAL && a.signalMode != ArmProgramEntry.SignalMode.RECEIVE;
		if (!sendMode && a.waitForPowered != b.waitForPowered)
			return where + "极性变了：" + a.waitForPowered + " → " + b.waitForPowered;
		if (a.signalMode != b.signalMode)
			return where + "信号模式变了：" + a.signalMode + " → " + b.signalMode;
		// ★ 「接收距离」也必须逐字段往返 —— 但**只比「收」那一侧**：发送 / 中断的 `range=` 在文本里
		//   一个字都不写（用户口径：距离只约束收，见 ArmProgramEntry.hasSignalRange），拿它们残留的值
		//   去比等于把"运行时不读它"判成往返不变量（与上面 sendMode / signalNoDuration 同一理由）。
		//   反证：把 ScriptDecompiler 里那段 `if (entry.hasSignalRange()) out.append(" range=")` 删掉
		//   ⇒ 语料「接收距离」报"接收距离变了：16 → 0"。
		boolean rangeMeaningful = (a.op == OperationType.SIGNAL && a.isSignalReceive())
			|| (b.op == OperationType.SIGNAL && b.isSignalReceive());
		if (rangeMeaningful && a.signalRange != b.signalRange)
			return where + "接收距离变了：" + a.signalRange + " → " + b.signalRange;
		if (!a.callTarget.equals(b.callTarget))
			return where + "调用目标变了：" + a.callTarget + " → " + b.callTarget;
		if (a.callRepeat != b.callRepeat)
			return where + "调用次数变了：" + a.callRepeat + " → " + b.callRepeat;
		// ★ 调用偏移也要逐字段往返：加了字段却忘了写进文本的症状是**静默的**（子任务目标不挪，
		//   看起来"这条调用本来就没偏移"）—— 这段比较本身就是不变量①的守卫。
		if (!a.callShift.equals(b.callShift))
			return where + "调用偏移变了：" + a.callShift.toShortString() + " → " + b.callShift.toShortString();
		if (!a.errorMessage.equals(b.errorMessage))
			return where + "错误信息变了";
		if (a.unloadRepeat != b.unloadRepeat)
			return where + "卸货方式变了：" + a.unloadRepeat + " → " + b.unloadRepeat;
		if (a.unloadAmount != b.unloadAmount)
			return where + "卸货上限变了：" + a.unloadAmount + " → " + b.unloadAmount;
		if (!ItemStack.matches(a.icon, b.icon))
			return where + "图标变了";
		// 「过滤」的两个新字段也必须逐字段往返 —— 加了字段却忘了写进文本的**症状是静默的**
		//（过滤器默认空 = 匹配一切，看起来"能跑"），所以比较本身也是这条不变量的守卫。
		if (a.filterSource != b.filterSource)
			return where + "过滤位置变了：" + a.filterSource + " → " + b.filterSource;
		if (!ItemStack.matches(a.filterItem, b.filterItem))
			return where + "幽灵过滤器物品变了：" + (a.filterItem.isEmpty() ? "未设置"
				: a.filterItem.getHoverName()
					.getString());
		// 频率：**逐字段比物品与染色**（§12.2：参与频率身份的只有这两项；但这里连组件一起比，
		// 因为不变量 #1 要的是"模型逐字段不变"，而不是"频率身份不变"）
		if ((a.frequency == null) != (b.frequency == null))
			return where + "频率绑没绑变了";
		if (a.frequency != null && b.frequency != null) {
			if (!ItemStack.matches(a.frequency.getFirst()
				.getStack(), b.frequency.getFirst()
					.getStack())
				|| !ItemStack.matches(a.frequency.getSecond()
					.getStack(), b.frequency.getSecond()
						.getStack()))
				return where + "频率的那对物品变了：" + frequencyText(a) + " → " + frequencyText(b);
		}
		return null;
	}

	private static String frequencyText(ArmProgramEntry entry) {
		if (entry.frequency == null)
			return "未绑";
		return entry.frequency.getFirst()
			.getStack()
			.getHoverName()
			.getString() + " + " + entry.frequency.getSecond()
				.getStack()
				.getHoverName()
				.getString();
	}

	/** 编译一小段文本，断言**在某一行**报出的错误里含某个关键词。 */
	private static boolean reportsAt(HolderLookup.Provider registries, String text, int line, String keyword) {
		ScriptResult<ArmProgram> result = ScriptCompiler.compile(text, registries);
		if (!result.hasErrors())
			return false;
		return result.errors()
			.stream()
			.anyMatch(error -> error.line() == line && error.message()
				.contains(keyword));
	}

	/** 日志里显示备注：换行画成 `\n`（否则多行备注会把一行日志撑开）。 */
	private static String showNote(String note) {
		String value = note == null ? "" : note;
		if (value.length() > 40)
			value = value.substring(0, 40) + "…";
		return value.replace("\n", "\\n");
	}

	/**
	 * 手写语料（§12.4 要求的那几类）：**全部操作类型**、三种物品写法、
	 * {@code t/s/m} 三种单位、跳转的两种落点（任务名 / 序号）、卸货两种参数、
	 * {@code freq} 两格 / 一格 / 留空、多行备注、含 {@code *&#47;} 的备注。
	 *
	 * <p>为什么用常量表而不是写成一大段文本再切：每一份都要**独立编译**并各自报错，
	 * 切成一份一份之后，"哪一份坏了"在日志里直接看得见。
	 *
	 * <p>⚠️ 本轮删掉了两类行（都随被删功能一起消失，不变量本身没放松）：
	 * ① 任务头的 {@code loop=} / {@code loopN=} —— 任务级循环删除后这两个键会**报错**
	 *   （"task 头里不认识这个键"），留在语料里这一份就"编译不过"，整条不变量②跟着红；
	 * ② 那行已删除操作的关键字 —— 那个关键字现在会报"未知操作"（这正是**期望行为**：
	 *   用户明确不要向后兼容），所以语料里不能再留它。
	 */
	private static final Map<String, String> SCRIPT_CORPUS = scriptCorpus();

	private static Map<String, String> scriptCorpus() {
		Map<String, String> corpus = new LinkedHashMap<>();
		corpus.put("全部操作", """
			task 主任务
			  take      1 0 0 count=3 delay=10
			  deposit   0 0 2
			  unload    0 0 2 repeat=until_empty amount=max
			  deploy    2 0 0 face=up
			  attack    10 64 -3 face=north
			  mine      10 64 -3 face=down
			  use       10 64 -3 face=east
			  use_sneak 10 64 -3 face=west
			  wait      5s
			  signal    recv on freq=iron_ingot + copper_ingot
			  call      子任务 repeat=2
			  break
			task 子任务
			  throw     "没材料了"
			""");
		// 个数模式（用户口径：等于 / 小于等于，取出与放入共用）。
		// ⚠️ 默认模式**固定是「小于等于」**（用户 2026-10-19 二次确认），不再按操作给：
		// ① 取出 · 小于等于（默认）+ 个数 3 ⇒ 只写 `count=3`（模式键**省略**）；
		// ② 放入 · 等于 + 个数 1 ⇒ 模式键**必须**写出来（`countmode=exact`）—— 这一行正是
		//    "非默认模式不写就丢语义"的反证：把 ScriptDecompiler 里那段 countmode 删掉 ⇒ 不变量①报「个数模式变了」；
		// ③ 取出 · 等于 + 个数 5 ⇒ 同样必须写 `countmode=exact`（非默认那一态永远显式表达）。
		//   反证：把反编译器的判据写成"只写 atmost"⇒ ②③ 立刻报「个数模式变了：EXACT → ATMOST」。
		corpus.put("个数模式", """
			task 主任务
			  take 1 0 0 count=3
			  deposit 0 0 2 count=1 countmode=exact
			  take 0 0 -2 count=5 countmode=exact
			""");
		// 「收存」（新操作）：关键词自动是 `stash`（枚举名小写），`count=` / `countmode=` 复用既有解析。
		// ① 省掉一切 ⇒ 默认（最多 64 个），规范形式里 `count=` 一个字都不写；
		// ② 非默认的个数 + 等于 ⇒ 两个键都写。
		// 反证：把 ScriptDecompiler 的 `case TAKE, DEPOSIT, STASH` 里的 STASH 删掉 ⇒ ② 报「个数变了」。
		corpus.put("收存", """
			task 主任务
			  stash
			  stash count=16 countmode=exact
			""");
		// 调用次数的两个端点（用户第四十轮：0 = 无限循环、上限 64）—— 这两个值进文本再出来必须一字不变
		corpus.put("调用次数端点", """
			task 主任务
			  call 子任务 repeat=0
			  call 子任务 repeat=64
			task 子任务
			  break
			""");
		// 「调用偏移」（用户口径：调用点上的 Δ，让同一个任务被多个调用点复用）。
		// 两条各钉一种写法：逗号那种是**反编译器的规范形式**（往返必须一字不变），
		// 空格那种是"与坐标同一种三个整数"的手写容忍（反编译器会把规范形式再写回逗号那种）。
		// 零偏移**不写**这条惯例由 checkCallShift 的"零偏移 = 没加"顶着。
		corpus.put("调用偏移", """
			task 主任务
			  call 子任务 repeat=2 shift=1,0,0
			  call 子任务 shift=-3 2 -1
			task 子任务
			  take 0 0 2
			""");
		// ⚠️ 这一份的 `send_off` 行**本轮改过**：原来是 `signal send_off place=1s freq=…`，
		//    那是**被用户本轮口径推翻的旧写法** ——「中断」立即清除该频率，**不接收持续时间**，
		//    所以它的规范形式里没有 `place=`，而手写 `place=` 现在会被解析器**明确报错**
		//    （留在语料里会让这一份"编译不过"，整条不变量②跟着红）。
		corpus.put("收发信号三种模式", """
			task main
			  signal recv on freq=iron_ingot + copper_ingot
			  signal send_on place=0t freq=iron_ingot + copper_ingot
			  signal send_off freq=redstone_torch + {}
			""");
		// 「接收距离」（用户口径：距离只约束「收」，0 = 只有同一格才算收到）：
		// ① 收 + range=16 ⇒ 规范形式必须**写出来**（不写就等于静默变成"只能同格"）；
		// ② 收 + 没写 range ⇒ 默认 0，规范形式里也**不该出现** range=（默认值不进文本）。
		// 反证：把 ScriptDecompiler 那段 `if (entry.hasSignalRange())` 删掉 ⇒ ① 的不变量①报"接收距离变了"。
		corpus.put("接收距离", """
			task main
			  signal recv on range=16 freq=iron_ingot + copper_ingot
			  signal recv on freq=iron_ingot + copper_ingot
			""");
		corpus.put("单位与时间", """
			task main
			  wait 100t
			  wait 2m
			  take 0 0 0 timeout=5s fail=skip
			  take 0 0 0 timeout=3t fail=stop
			""");
		corpus.put("跳转落点两种", """
			task main
			  take 0 0 0 ok=goto 收尾 fail=goto 2
			  take 0 0 0
			task 收尾
			  break
			""");
		corpus.put("多行备注", """
			task main
			  take 0 0 0 /*第一行
			第二行
			  第三行有缩进*/
			""");
		corpus.put("含终止符的备注", "task main\n  take 0 0 0 note=\"带 */ 的备注\\n第二行\"\n");
		corpus.put("任务行尾 + 条目紧跟", """
			task main /* 任务备注 */
			  take 0 0 0
			  /* 紧跟着上一条 */
			""");
		// §12.3a 第 6 条（主代理 87②）：行尾一块 + 紧跟的独占行一块 ⇒ **合并**成一个备注，不报错
		corpus.put("两块注释合并", """
			task main
			  take 0 0 0 /* 行尾一句 */
			  /* 紧接着再补一句 */
			""");
		// §12.3a 第 7 条：`task` 行之后、第一条条目之前的独占注释 ⇒ 归**这个任务**
		corpus.put("task 行后的注释归任务", """
			task main
			  /* 这是任务说明 */
			  take 0 0 0
			""");
		// ⚠️ 这里原来还有一份「非零原点的程序」语料（首行 `@origin 100 64 -300`）。
		// 用户第四十轮把原点搬到了卡上 ⇒ 那一行现在**编译不过**，语料整个删掉；
		// "含非零偏移的程序能往返"由 ⑧ 的 headerProbe 顶着（它带一条 offset = -7 2 5 的条目）。
		corpus.put("完整 SNBT 物品", """
			task main
			  take 0 0 0 icon={id:"minecraft:black_banner",count:1,components:{"minecraft:base_color":"black"}}
			  take 0 0 0 icon=minecraft:leather_helmet#C41E3A
			""");
		corpus.put("空物品槽", """
			task main
			  signal recv on freq={} + iron_ingot
			""");
		// 「过滤」两模式（用户口径：手持模式不写坐标，容器模式写坐标 + face=）。
		// 手持那条用**简写 id**、容器那条用**完整 SNBT**（带组件）—— 两种物品写法各走一遍。
		// ⚠️ 完整 SNBT 那条**不能带空格**：词法器会把 `{…}` 内部的空格切开（现有语料里的
		//    `{id:"minecraft:black_banner",count:1,components:{…}}` 也是不带空格的写法），
		//    带空格的 SNBT 要写成 `item={` + 无空格内容。这里用染色 SNBT —— 它是**另一条**
		//    规范形式分支（唯一组件是染色时会写成 `#RRGGBB`），所以这条同时钉住"SNBT 读进来
		//    与简写写出去"这条不对称路径仍然无损。
		// ⚠️⚠️ **坐标必须紧跟关键字**（`filter 12 64 -30 source=…`），不能写在 `item=` 后面：
		//    `ScriptParser.parseEntry` 对「不碰目标」的操作（过滤的 requiresTarget() 是 false）
		//    只调一次 `optionalPosition(rest)`，而它看的是**紧接着关键字的那三个记号**是不是整数
		//    —— 把坐标挪到后缀后面，`rest[0]` 就是 `source=`，坐标永远吃不到，后缀循环再把 `12`
		//    当成"不认识的一段"报错（原来这两行就是这么红起来的）。
		//    顺序与反编译器的规范形式一致：**位置 → source= → face= → item=**（与 `take 1 0 0 count=3`
		//    同一套"位置在前、后缀在后"的既有约定）。
		corpus.put("过滤手持", """
			task main
			  filter source=held item=minecraft:iron_ingot
			""");
		corpus.put("过滤容器", """
			task main
			  filter 12 64 -30 source=container face=north item=create:filter
			  filter 0 0 2 source=container item={id:"minecraft:leather_helmet",count:1,components:{"minecraft:dyed_color":12801229}}
			  filter source=held item={}
			""");
		return java.util.Collections.unmodifiableMap(corpus);
	}

	/** 注释归属的用例（§12.3a 表格那几行各一条：文件最前面 / task 行尾 / 条目前后）。 */
	private static final String SCRIPT_NOTES_SAMPLE = """
		/*程序备注
		第二行*/
		task main /*任务备注*/
		  take 0 0 0 /*条目备注
		第二行*/
		  deposit 0 0 2
		""";

	/** {@code note="…"} 保底写法；与 {@link #SCRIPT_NOTES_SAMPLE} 的条目备注必须是**同一个字符串**。 */
	private static final String SCRIPT_QUOTED_NOTE_SAMPLE = "task main\n  take 0 0 0 note=\"条目备注\\n第二行\"\n";

	/**
	 * §12.3a 第 6 条（主代理 87②）：**同一个归属对象上的两块注释合并**成一个备注（换行连接），
	 * **不报错**。行尾一块 + 紧跟其后的独占行一块 —— 玩家"再补一句注释"就是这个写法。
	 */
	private static final String SCRIPT_MERGED_NOTE_SAMPLE = "task main\n"
		+ "  take 0 0 0 /* 行尾一句 */\n"
		+ "  /* 紧接着再补一句 */\n";

}

