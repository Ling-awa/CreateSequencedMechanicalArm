package io.github.Ling.create_smart_mechanical_arm.smartarm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.content.redstone.smartObserver.SmartObserverBlock;
import com.simibubi.create.content.redstone.smartObserver.SmartObserverBlockEntity;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.SmartArmConfig;
import io.github.Ling.create_smart_mechanical_arm.interaction.AnyBlockInteractionPoint;
import io.github.Ling.create_smart_mechanical_arm.interaction.ArmEffects;
import io.github.Ling.create_smart_mechanical_arm.interaction.RightClickBridge;
import io.github.Ling.create_smart_mechanical_arm.interaction.TargetInteraction;
import io.github.Ling.create_smart_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_smart_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_smart_mechanical_arm.mixin.ArmInteractionPointAccessor;
import io.github.Ling.create_smart_mechanical_arm.network.ArmProgramEditPacket;
import io.github.Ling.create_smart_mechanical_arm.network.CardEditPacket;
import io.github.Ling.create_smart_mechanical_arm.network.CardNotesPacket;
import io.github.Ling.create_smart_mechanical_arm.program.ArmEntryText;
import io.github.Ling.create_smart_mechanical_arm.program.ArmIcons;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgramTask;
import io.github.Ling.create_smart_mechanical_arm.program.ClipboardChecklist;
import io.github.Ling.create_smart_mechanical_arm.program.DeployRecipes;
import io.github.Ling.create_smart_mechanical_arm.program.ProgramCoordinates;
import io.github.Ling.create_smart_mechanical_arm.program.ProgramRuntime;
import io.github.Ling.create_smart_mechanical_arm.program.OperationType;
import io.github.Ling.create_smart_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_smart_mechanical_arm.program.script.ScriptResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
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
 * [selftest] command /smartarm registered: true
 * [selftest] preset depot_ab tasks=1 entries=2 nbtRoundTrip=true withinLimits=true
 * </pre>
 */
@EventBusSubscriber(modid = Create_smart_mechanical_arm.MODID)
public class SmartArmSelfTest {

	private static final Logger LOGGER = LogUtils.getLogger();

	@SubscribeEvent
	public static void onServerStarted(ServerStartedEvent event) {
		MinecraftServer server = event.getServer();
		boolean registered = server.getCommands()
			.getDispatcher()
			.getRoot()
			.getChild("smartarm") != null;
		LOGGER.info("[selftest] command /smartarm registered: {}", registered);

		// 生效的规模上限（**从配置读，不读常量**）—— 服务端这一侧的现场证据。
		// 反证方式：把 run/config/create_smart_mechanical_arm-common.toml 里的 maxTasks 改成 3，
		// 重启后这行应当报 任务上限=3，并且下面 `任务数上限` 那条自检探到第 4 个就被挡。
		LOGGER.info("[selftest] {}", SmartArmConfig.effectiveSummary());

		checkDeployRecipes(server);
		checkCardAndCoordinates(server);
		checkEditorSession(server);
		checkArbitraryBlockTarget(server);
		checkNotesMapping();
		checkSpinGuard();
		checkArmReachClamp();
		checkArmRangeGate();
		checkWaitSemantics();
		checkUsePlacesOnAir(server);
		checkSignalSendReceive(server);
		checkEntryPredicates();
		checkSignalSendPreset();
		checkArmPointClassification(server);
		checkFunnelTransferNotice(server);
		checkTakeGateEndToEnd(server);
		checkPrecheckRetryIsPerTick(server);
		checkClaimFollowsIntent(server);
		checkDeployPrecheckScansAllSlots(server);
		checkRetractBeforeNextMove(server);
		checkEffects(server);
		checkEditorSavePath(server);
		checkEntryMigration();
		checkIcons();
		checkTaskLimit();
		checkNotesClip();
		checkArmInventory(server);
		checkFakePlayerDrain(server);
		checkStructureTransform();
		checkLegacyJumpShift();
		checkCallRepeats(server);
		checkAttackAndMine(server);
		checkClipboardChecklist(server);
		checkOverflowStackAndExchange(server);
		checkProgramSize(server);
		checkScriptRoundTrip(server);

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
					if (entry.op.requiresTarget() && entry.offset.equals(BlockPos.ZERO))
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
		if (Boolean.getBoolean("create_smart_mechanical_arm.selftest.exit")) {
			LOGGER.info("[selftest] selftest.exit=true ⇒ 自检结束，请求服务器退出（日志到此为止就是全部结果）");
			// 另起一个守护线程调 halt(false)：它不 join 服务器线程，但**万一它阻塞**也不会把启动流程卡死。
			Thread exit = new Thread(() -> server.halt(false), "smartarm-selftest-exit");
			exit.setDaemon(true);
			exit.start();
		}
	}

	/**
	 * 「整份程序序列化后有多大、128KB 能放多少条」的实测（用户问的就是这个）。
	 *
	 * <p><b>为什么必须实测、不能估</b>：MC 的 {@code CompoundTag.sizeInBytes()} 不是序列化字节数，
	 * 而是<b>内存估算</b> —— 每个键固定 {@code 28 + 2×键名长 + 36}，再叠加值本身：
	 * <ul>
	 *   <li>{@code off}（BlockPos 的三个 int）单独一项就 ≈282 字节；</li>
	 *   <li>{@link ArmProgramEntry#save} 无条件写 ~18 个键、条件写 6 个 ⇒ 光键名开销就 ≈1.5KB，
	 *       于是"只有 op 的一条"也有 2KB 出头，而"塞满备注 + 图标 + 频率"的一条接近 4KB。</li>
	 * </ul>
	 * 凭直觉按"一条几十字节"估会差两个数量级：{@code maxEntriesTotal = 256} 这种值看起来毫无风险，
	 * 实际会在保存那一刻被 {@code ArmProgramEditPacket.MAX_TAG_BYTES}（128KB）整份拒收
	 * —— 而拒收的表现是"这一场编辑一个字都落不了盘"。这四行就是把它钉进日志。
	 *
	 * <p>⚠️ 这四行是<b>测量，不是断言</b>：数值随上游物品 id 长度与配置值变化，写死期望值只会变成噪音。
	 */
	private static void checkProgramSize(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();

		// ① 现有预设里条目最多的那一份 —— 一份真实会被存读的程序（不是人为造的最坏情况）
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
		int sampleBytes = sample == null ? 0 : sample.save(registries)
			.sizeInBytes();
		int perEntry = entries == 0 ? 0 : sampleBytes / entries;
		LOGGER.info("[selftest] 程序体积: 条目={} 字节={} 每条≈{} （那是预设「{}」，条目最多的那份）", entries, sampleBytes,
			perEntry, widest);

		// ② 两端：编辑器造得出的最重一条 / 最轻一条
		ArmProgramEntry heaviest = heaviestEntry();
		int heavyBytes = heaviest.save(registries)
			.sizeInBytes();
		// 「最轻」= 除了 op 全是字段默认值 —— 注意 save() 里那 ~18 个键是**无条件**写的，
		// 所以"一条什么都没有的操作"并不小，这是"能放多少条"的乐观上界。
		int lightBytes = new ArmProgramEntry().save(registries)
			.sizeInBytes();
		LOGGER.info("[selftest] 单条最重={} 字节（备注满 {} 字 + 自定义图标 + 两个频率物品 + 发信号 + 所有属性非默认）",
			heavyBytes, ArmProgramEntry.NOTE_MAX);
		LOGGER.info("[selftest] 单条最轻={} 字节（只有 op，其余全是默认值）", lightBytes);

		// ③ 换算：128KB ÷ 实测每条 = 能放多少条。给的是**区间**（真实程序里既有空操作也有塞满的，
		// 单点估计必然两头都不准），以及"当前配置上限那一档（全按最重）实测多大"。
		int cap = ArmProgramEditPacket.MAX_TAG_BYTES;
		int configured = SmartArmConfig.maxEntriesTotal();
		LOGGER.info("[selftest] 128KB 换算: 全按最轻≈{}条 全按最重≈{}条 全按预设每条≈{}条 ｜ 配置上限={}条 全按最重实测={} 字节（守卫 {}）",
			lightBytes == 0 ? 0 : cap / lightBytes, heavyBytes == 0 ? 0 : cap / heavyBytes,
			perEntry == 0 ? 0 : cap / perEntry, configured, filledBytes(registries, heaviest, configured), cap);
	}

	/** 编辑器造得出的"一条最重"：所有字段非默认 + 满额备注 + 自定义图标 + 一对频率。 */
	private static ArmProgramEntry heaviestEntry() {
		ArmProgramEntry entry = new ArmProgramEntry();
		entry.op = OperationType.SIGNAL;
		entry.offset = new BlockPos(1234567, 255, -1234567);
		entry.absolute = true;
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
		entry.callRepeat = ArmProgramTask.MAX_LOOP_COUNT;
		entry.errorMessage = "写给护目镜看的错误信息";
		entry.unloadRepeat = ArmProgramEntry.UnloadRepeat.UNTIL_EMPTY;
		entry.unloadAmount = ArmProgramEntry.UnloadAmount.MAX;
		return entry;
	}

	/**
	 * 一份"条目塞满、且每条都按最重算"的真实程序有多大（回答"配置上限那一档会不会超 128KB"）。
	 *
	 * <p>同一个 {@link ArmProgramEntry} 实例被反复放进列表：{@code save()} 只读，而"每条都一样重"
	 * 正是这里要的最坏情况。全按最重 ⇒ 结论是保守的（真实程序必然更小）。
	 */
	private static int filledBytes(HolderLookup.Provider registries, ArmProgramEntry entry, int count) {
		ArmProgram program = new ArmProgram();
		ArmProgramTask task = new ArmProgramTask();
		task.name = "size_probe";
		for (int i = 0; i < count; i++)
			task.entries.add(entry);
		program.tasks.add(task);
		return program.save(registries)
			.sizeInBytes();
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(targetPos, state);
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm))
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotPos, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
			SmartArmBlockEntity reloaded = new SmartArmBlockEntity(SmartArmRegistration.SMART_ARM_BE.get(), armPos,
				SmartArmRegistration.SMART_ARM.get()
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotA, depotState);
			level.setBlockAndUpdate(depotB, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
				stayedHome &= arm.phase == SmartArmBlockEntity.Phase.IDLE;
				noFailureWhileRetracting &= arm.runtime.getLastFailure() == null;
			}
			boolean stillHoldingB = !isEmpty(level, depotB);

			// ③ 折返走完（进度到静止位）⇒ 必须立刻动身，而且**从静止位起手**：
			//    进度应当是 **.5**（这一次动作只有"旋转+伸出"），不是 0（那会把折返再播一遍）。
			boolean movedAfterRest = false;
			boolean startedFromRest = false;
			float progressAtMoveStart = -1f;
			for (int i = 0; i < 40 && !movedAfterRest; i++) {
				arm.chasedPointProgress = 1f; // 模拟折返走完
				arm.runtime.tick(arm);
				movedAfterRest = arm.phase == SmartArmBlockEntity.Phase.MOVING;
				if (movedAfterRest) {
					progressAtMoveStart = arm.chasedPointProgress;
					startedFromRest = Math.abs(progressAtMoveStart - .5f) < .01f;
				}
			}

			// ④ 端到端：第二条也要真的取到
			boolean secondDone = false;
			for (int i = 0; i < 200 && !secondDone; i++) {
				arm.chasedPointProgress = 1f;
				arm.runtime.tick(arm);
				secondDone = isEmpty(level, depotB);
			}

			LOGGER.info("[selftest] 收回属于本次操作: 折返途中可动身={}（应为 false） 折返途中一直待机={} "
				+ "折返途中不报失败={}（B 还在={}） 折返走完立刻动身={} 从静止位起手={}（进度={}） 第2条真的取到={}",
				restingFlagRightAfter, stayedHome, noFailureWhileRetracting, stillHoldingB, movedAfterRest,
				startedFromRest, progressAtMoveStart, secondDone);

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
			if (movedAfterRest && !startedFromRest)
				LOGGER.error("[selftest] 折返走完后的这次移动**不是从静止位起手**（进度={}，应当是 .5）—— "
					+ "从 0 起手会把「折返」再播一遍，也就是把收回并进下一次动作", progressAtMoveStart);
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

	/** 走目标方块**自己的物品能力**放一个铁锭（不碰 BE 内部字段，与"世界里真的放了一个"一致）。 */
	private static boolean putOneInto(ServerLevel level, BlockPos pos) {		IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
		if (handler == null)
			return false;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			if (handler.insertItem(slot, new ItemStack(Items.IRON_INGOT), false)
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(depotA, depotState);
			level.setBlockAndUpdate(depotB, depotState);
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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

		ItemStack card = new ItemStack(SmartArmRegistration.OPERATION_TABLE_RELATIVE.get());
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

		LOGGER.info("[selftest] 录入会话固化: 模式={} 任务={} 负数夹取={} 越界夹取={}", modeKept, taskKept, clampedLow,
			clampedHigh);
		if (!modeKept || !taskKept)
			LOGGER.error("[selftest] 录入会话状态没有固化到卡上！modeKept={} taskKept={}（换物品就会丢模式）", modeKept,
				taskKept);
		// 越界要夹到**最后一个任务的序号**（不是 0）—— 这里第一版把期望写成 0，被自检当场抓出来了
		if (clampedLow != 0 || clampedHigh != session.tasks.size() - 1)
			LOGGER.error("[selftest] 越界任务下标没有被夹到合法范围：负数→{}（期望 0） 越界→{}（期望 {}，界面会索引越界）",
				clampedLow, clampedHigh, session.tasks.size() - 1);
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

		// 等待类必须**只有**三种原地等待：运行时靠这个标志决定"等待前先收回手臂"，
		// 把控制类（调用/退出/抛错）算进去就会在连续两条目标之间多绕一趟（§7.6 v4）。
		boolean waitSplit = OperationType.WAIT.isWait() && OperationType.WAIT_REDSTONE.isWait()
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
			LOGGER.error("[selftest] 等待类分类错了：只有 等待/等红石/等信号 该在等待前收回手臂，控制类不算");
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
	 * 等玩家补货、等红石链接通电都会误杀，已删除；换成只认"原地绕圈"的空转守卫。
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

		// 两种操作的"等待"必须都在 paced 里：WAIT/等红石/等信号 进 WAITING，阻塞重试（等玩家补货）进 RETRY
		boolean waitsCovered = OperationType.WAIT.isWait() && OperationType.SIGNAL.isWait()
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.WAITING)
			&& ProgramRuntime.isPaced(ProgramRuntime.EntryState.RETRY);

		LOGGER.info("[selftest] 空转守卫: 等待/重试算在做事={} 空闲/执行不算={} 覆盖所有等待类={} 上限={}tick", paced,
			notPaced, waitsCovered, ProgramRuntime.MAX_IDLE_SPINS);
		if (!paced || !notPaced)
			LOGGER.error("[selftest] 空转守卫的判据错了（paced={} notPaced={}）—— 会误杀等待或永远抓不到空转", paced,
				notPaced);
		if (!waitsCovered)
			LOGGER.error("[selftest] 有等待类操作没有被 paced 覆盖 —— 它会被空转守卫当成空转停机");
	}

	/**
	 * 「臂长」这条固有限制的自检（用户第十九轮："既然是固有限制……就不做处理吧"）。
	 *
	 * <p>不做任何用户可见的处理，但这条限制本身值得钉住 —— 它是我们**抄** Create 的两节 IK
	 * （{@link SmartArmAngleTarget}）里最容易抄错、也最容易被误当成"动画 bug"的一处：
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

		SmartArmAngleTarget near = alongRay(armPos, origin, 1.0);
		SmartArmAngleTarget atReach = alongRay(armPos, origin, reach);
		SmartArmAngleTarget far = alongRay(armPos, origin, 5.0);

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
	private static SmartArmAngleTarget alongRay(BlockPos armPos, Vec3 origin, double distance) {
		Vec3 point = origin.add(distance, 0, 0)
			.subtract(0, 0.5, 0); // clawFacing = DOWN ⇒ 反向偏移是 +UP×0.5
		return new SmartArmAngleTarget(armPos, point, Direction.DOWN, false);
	}

	/** 两节臂 + 底座角度是否一致（头部不算，理由见 {@link #checkArmReachClamp()}）。 */
	private static boolean sameArmPose(SmartArmAngleTarget a, SmartArmAngleTarget b) {
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(airPos, Blocks.AIR.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
		sendEntry.signalMode = ArmProgramEntry.SignalMode.SEND_OFF;
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
			LOGGER.error("[selftest] 发信号的持续时长没有往返稳定（waitAmount=3）");
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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

			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
				SmartArmBlockEntity probe = new SmartArmBlockEntity(SmartArmRegistration.SMART_ARM_BE.get(),
					armPos.offset(1, 0, 0), SmartArmRegistration.SMART_ARM.get()
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
				int signalCap = SmartArmBlockEntity.maxHeldSignals();
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
				level.setBlockAndUpdate(reloadPos, SmartArmRegistration.SMART_ARM.get()
					.defaultBlockState());
				SmartArmBlockEntity reloaded = level.getBlockEntity(reloadPos) instanceof SmartArmBlockEntity be
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

		LOGGER.info("[selftest] 失败出口谓词: 收有失败={} 发无失败={}", recvHasFailure, !sendHasNone);
		if (!recvHasFailure)
			LOGGER.error("[selftest] 收信号说自己没有重试窗口（编辑器不会给时限控件）");
		if (!sendHasNone)
			LOGGER.error("[selftest] 发信号也说自己有重试窗口（卡片上会冒出永不触发的「失败后」）");
		if (!hardOnly)
			LOGGER.error("[selftest] 挖 / 调用的硬失败谓词错了（应当有失败出口、没有重试窗口）");
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
	 * 卡片与坐标语义的自检（阶段 3a 的核心不变量）：
	 * ① 程序写进物品再读回来必须无损；② 绝对条目在插入时必须按臂的位置转成相对。
	 *
	 * <p>这两条都是"编译通过但可能静默错"的东西 —— 坐标算错只会让臂搬错地方，不会报任何错。
	 */
	private static void checkCardAndCoordinates(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();

		ArmProgram source = DevPresets.depotAB();
		ItemStack card = new ItemStack(SmartArmRegistration.OPERATION_TABLE_RELATIVE.get());
		CardProgramStorage.write(card, source, registries);
		boolean stored = CardProgramStorage.hasProgram(card);
		ArmProgram readBack = CardProgramStorage.read(card, registries);
		boolean lossless = readBack.save(registries)
			.equals(source.save(registries));
		LOGGER.info("[selftest] 卡片存取: stored={} lossless={}", stored, lossless);
		if (!stored || !lossless)
			LOGGER.error("[selftest] 卡片 NBT 往返失败！stored={} lossless={}", stored, lossless);

		// 绝对 → 相对：把预设的偏移当作"世界绝对坐标"，转换后应当回到原始的相对偏移
		ArmProgram absolute = DevPresets.depotAB();
		BlockPos armPos = new BlockPos(120, 70, -300);
		int marked = 0;
		for (ArmProgramTask task : absolute.tasks) {
			for (ArmProgramEntry entry : task.entries) {
				if (!entry.op.requiresTarget())
					continue;
				entry.offset = entry.offset.offset(armPos);
				entry.absolute = true;
				marked++;
			}
		}
		int converted = ProgramCoordinates.toRelative(absolute, armPos);
		boolean clean = true;
		for (ArmProgramTask task : absolute.tasks)
			for (ArmProgramEntry entry : task.entries)
				if (entry.absolute)
					clean = false;
		ArmProgramTask first = absolute.tasks.get(0);
		boolean offsetRestored = first.entries.get(0).offset.equals(new BlockPos(2, 0, 0));
		LOGGER.info("[selftest] 坐标转换: 标记绝对={} 转换={} 首条偏移复原={} 无残留绝对={}", marked, converted, offsetRestored,
			clean);
		if (marked != converted || !offsetRestored || !clean)
			LOGGER.error("[selftest] 坐标转换不变量失败：marked={} converted={} offsetRestored={} clean={}", marked,
				converted, offsetRestored, clean);
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
			.containsKey(ResourceLocation.fromNamespaceAndPath(Create_smart_mechanical_arm.MODID, "arm_program"));

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
		edited.tasks.get(0).loopCount = 7;
		edited.tasks.get(0).loop = ArmProgramTask.LoopType.COUNT;
		ItemStack card = new ItemStack(SmartArmRegistration.OPERATION_TABLE_RELATIVE.get());
		probe.setItemInHand(InteractionHand.MAIN_HAND, card);
		ArmProgramEditPacket.SaveResult first = ArmProgramEditPacket.applyTo(probe, edited.save(registries));
		ArmProgram saved = CardProgramStorage.read(card, registries);
		// ⚠️ 规模上限是**配置项**（`maxEntriesTotal` / `maxTasks`，见 §11 决策 69 的后续）：
		// 玩家把它调到装不下这个预设时，`first` 就是 OVER_LIMIT、卡上**什么都没写** ⇒ `saved` 是空程序。
		// 这一条原来直接 `.get(0)`，于是"配置值本身合法"（`defineInRange` 声明 1..4096）却把**服务端打崩**
		// ——而且崩在**每次开服**，不是玩家编辑卡片的时候（实测：`maxEntriesTotal = 2` ⇒
		// `IndexOutOfBoundsException` @ `SmartArmSelfTest.java:1494` + crash-report）。
		// 所以先判"卡上到底读出了东西没有"，再谈里面的字段。
		boolean savedReadable = !saved.tasks.isEmpty() && !saved.tasks.get(0).entries.isEmpty();
		boolean applied = savedReadable && saved.tasks.get(0).entries.get(0).delay == 37
			&& saved.tasks.get(0).loopCount == 7 && saved.tasks.get(0).loop == ArmProgramTask.LoopType.COUNT;
		boolean lossless = saved.save(registries)
			.equals(edited.save(registries));
		boolean formFlag = !saved.absolute; // 相对形态的卡 → 程序自称相对

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

		LOGGER.info("[selftest] 编辑器保存: 菜单已注册={} 落盘={} 无损={} 形态标记={} 超限被拒={} 原卡未动={} 空手被拒={}",
			menuRegistered, applied, lossless, formFlag, second == ArmProgramEditPacket.SaveResult.OVER_LIMIT, unchanged,
			third == ArmProgramEditPacket.SaveResult.NOT_HOLDING);

		if (!menuRegistered)
			LOGGER.error("[selftest] 菜单类型未注册 —— 右键卡开不出界面");
		if (first != ArmProgramEditPacket.SaveResult.OK || !applied || !lossless || !formFlag)
			LOGGER.error("[selftest] 编辑器保存路径坏了：first={} applied={} lossless={} formFlag={}", first, applied,
				lossless, formFlag);
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
					for (int i = 0; i < SmartArmBlockEntity.MAX_OVERFLOW; i++)
						arm.overflow.add(new ItemStack(Items.STONE, 1));
					boolean roomBefore = arm.canAccept(stone);
					arm.insertIntoInventory(stone.copyWithCount(64));
					merged = roomBefore && arm.overflow.size() == SmartArmBlockEntity.MAX_OVERFLOW
						&& arm.overflow.get(0)
							.getCount() > 1;
					// ③ 10 格都被**别的物品**占着时才是真的收不下
					fullWhenFull = !arm.canAccept(new ItemStack(Items.DIAMOND));
					// ④ 溢出格可抽：抽空一格后，同一个判据必须变成"收得下"
					ItemStack pulled = handler.extractItem(1, 64, false);
					overflowDrains = !pulled.isEmpty() && arm.overflow.size() == SmartArmBlockEntity.MAX_OVERFLOW - 1
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
		String detail;
		try {
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
			}
		} catch (Throwable t) {
			detail = "异常 " + t;
		} finally {
			level.setBlockAndUpdate(armPos, oldArm);
		}

		LOGGER.info("[selftest] 调用点轮次: 入栈时剩余=3={} 跑完一轮递减到2={} 用尽后返回调用方={} 次数=1 时一轮即返回={} （{}）",
			pushedThree, decremented, returnedAfterLast, singleRoundReturns, detail);
		if (!pushedThree)
			LOGGER.error("[selftest] 调用点没有把次数带进调用帧（{}）—— 「循环次数放在调用上」没生效", detail);
		if (!decremented)
			LOGGER.error("[selftest] 子任务跑完一轮后调用帧的剩余次数没有递减（{}）—— 会无限重跑子任务", detail);
		if (!returnedAfterLast || !singleRoundReturns)
			LOGGER.error("[selftest] 轮次用尽后没有正确返回调用方：returnedAfterLast={} singleRoundReturns={}（{}）",
				returnedAfterLast, singleRoundReturns, detail);
	}

	/** 主任务 = [调用 sub ×repeats]；子任务 = [等待 0 tick]（每轮一两 tick 就完）。 */	private static ArmProgram callProgram(int repeats) {
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
		// 子任务自己**故意**设成"一直重复"：如果实现里还读子任务的 loop，这条自检会立刻暴露
		sub.loop = ArmProgramTask.LoopType.FOREVER;
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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			level.setBlockAndUpdate(floorPos, Blocks.STONE.defaultBlockState());
			// ⚠️ 目标方块要用**没有碰撞**的那种（铁轨）：羊只有能"站进"那一格，它的碰撞箱才会与
			// 该格的 AABB 重叠。第一版把羊放在实心泥土格上 —— 它被碰撞推到方块上方，
			// 于是"挖掘没碰羊"这条断言其实是空转的（"攻击没打到羊"当场把这件事暴露了）。
			level.setBlockAndUpdate(targetPos, Blocks.RAIL.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
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
			probe.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SmartArmRegistration.OPERATION_TABLE_RELATIVE.get()));
			probe.setItemInHand(InteractionHand.OFF_HAND, clipboard);
			ItemStack card = probe.getMainHandItem();

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
			level.setBlockAndUpdate(armPos, SmartArmRegistration.SMART_ARM.get()
				.defaultBlockState());
			if (!(level.getBlockEntity(armPos) instanceof SmartArmBlockEntity arm)) {
				detail = "臂的方块实体没建出来";
			} else {
				// ——— ① 交换 ———
				ServerPlayer probe = FakePlayerFactory.getMinecraft(level);
				arm.setHeldItem(new ItemStack(Items.DIAMOND, 3));
				probe.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT, 5));
				SmartArmBlock.exchangeHeldItem(arm, probe, level);
				exchanged = arm.getHeldItem()
					.is(Items.IRON_INGOT) && arm.getHeldItem()
						.getCount() == 5
					&& probe.getMainHandItem()
						.is(Items.DIAMOND)
					&& probe.getMainHandItem()
						.getCount() == 3;
				probe.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				SmartArmBlock.takeHeldItem(arm, probe, level);
				tookHeld = arm.getHeldItem()
					.isEmpty()
					&& probe.getMainHandItem()
						.is(Items.IRON_INGOT)
					&& probe.getMainHandItem()
						.getCount() == 5;
				// 臂手已经空了：再按一次不该把玩家手上那件"换"走
				SmartArmBlock.takeHeldItem(arm, probe, level);
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
				SmartArmBlock.takeStackFromOverflow(arm, probe, level);
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
	 * <p>语料分两半：**真实预设**（{@code /smartarm card <预设>} 用的那一批，全是会被存进卡的程序）
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
	 *       ⇒ 程序备注丢 ⇒ "程序备注进 NBT"那行必须报红。</li>
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
			"task main\n  take @rel 0 0 0 /*" + overLongNote + "*/\n", 2, "128");
		boolean unknownTaskReported = reportsAt(registries,
			"task main\n  call 不存在的任务\n", 2, "unknown task") && reportsAt(registries,
				"task main\n  take @rel 0 0 0 fail=goto 不存在的任务\n", 2, "unknown task");
		// 未知任务名要**列出已知任务名**（用户点名要的：{@code unknown task: cook_wok} + 已知清单）
		ScriptResult<ArmProgram> unknownTask = ScriptCompiler.compile("task main\n  call 没有这个\n", registries);
		boolean knownListed = !unknownTask.hasErrors() || unknownTask.errors()
			.stream()
			.anyMatch(error -> error.message()
				.contains("main"));
		boolean unknownItemReported = reportsAt(registries, "task main\n  take @rel 0 0 0 icon=notamod:no_such_item\n", 2,
			"未知物品");
		boolean badCountReported = reportsAt(registries, "task main\n  take @rel 0 0 0 count=65\n", 2, "count");
		boolean badCoordReported = reportsAt(registries, "task main\n  take @rel 0 0 999999999\n", 2, "坐标");
		boolean badOpReported = reportsAt(registries, "task main\n  teleport @rel 0 0 0\n", 2, "未知操作");
		boolean badModeReported = reportsAt(registries, "task main\n  signal recv_x on freq=iron_ingot\n", 2, "信号模式");
		boolean missingFreqReported = reportsAt(registries, "task main\n  signal recv on\n", 2, "频率");
		// ⚠️ `/*` 在**引号外**才算注释开头（§12.3a 细则 2），所以这里必须用真的注释、
		// 而不是 "引号里带 /* 的字符串"：后者正是那条细则要保护的写法。
		boolean unclosedCommentReported = reportsAt(registries, "task main\n  take @rel 0 0 0 /* 没闭合\n", 2, "没有闭合");
		LOGGER.info(
			"[selftest] 脚本编译期错误: 超长备注={} 未知任务={} 已知任务名列出={} 未知物品={} 个数越界={} 坐标越界={} 未知操作={} 未知模式={} 缺频率={} 注释未闭合={}",
			overLongReported, unknownTaskReported, knownListed, unknownItemReported, badCountReported, badCoordReported,
			badOpReported, badModeReported, missingFreqReported, unclosedCommentReported);
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

		// ⑦ 备注与 128KB 预算的量化（§12.3a 细则 4：注释不是免费的）
		ArmProgram budgetProbe = new ArmProgram();
		budgetProbe.note = "汉".repeat(ArmProgramEntry.NOTE_MAX);
		ArmProgramTask budgetTask = new ArmProgramTask();
		budgetTask.name = "预算";
		budgetTask.note = "汉".repeat(ArmProgramEntry.NOTE_MAX);
		budgetProbe.tasks.add(budgetTask);
		int withNotes = budgetProbe.save(registries)
			.sizeInBytes();
		budgetProbe.note = "";
		budgetTask.note = "";
		int withoutNotes = budgetProbe.save(registries)
			.sizeInBytes();
		boolean noteCostSane = withNotes > withoutNotes;
		LOGGER.info("[selftest] 脚本备注预算: 满备注程序 {} 字节 vs 无备注 {} 字节 ⇒ 两条满备注 ≈ {} 字节（128KB 的 {}%）",
			withNotes, withoutNotes, withNotes - withoutNotes,
			String.format(java.util.Locale.ROOT, "%.1f", (withNotes - withoutNotes) * 100.0 / ArmProgramEditPacket.MAX_TAG_BYTES));
		if (!noteCostSane)
			problems.add("备注没有参与 NBT 体积（预算讨论无从谈起）");

		// ⑧ 程序头（§12.3 末尾，主代理 87①）：文本首行的坐标空间就是 ArmProgram.absolute。
		//    反编译器**永远写**；编译器缺省按 rel；导入时与手持卡形态不符是命令层的硬错（游戏内人工确认）。
		//    注意（假红修掉）：原来这里复用上面那个 `budgetTask`，而它**一条条目都没有** ⇒
		//    `anyMatch(entry -> entry.absolute)` 必然 false —— 一条永远红的断言等于没测。
		ArmProgram absProgram = new ArmProgram();
		absProgram.absolute = true;
		ArmProgramTask absTask = new ArmProgramTask();
		absTask.name = "绝对";
		ArmProgramEntry absEntry = new ArmProgramEntry();
		absEntry.op = OperationType.TAKE;
		absEntry.absolute = true;
		absEntry.offset = new BlockPos(10, 64, -3);
		absTask.entries.add(absEntry);
		absProgram.tasks.add(absTask);
		String absText = ScriptCompiler.decompile(absProgram, registries);
		ScriptResult<ArmProgram> absBack = ScriptCompiler.compile(absText, registries);
		boolean headerAlwaysWritten = absText.startsWith("@abs\n") && itemText.startsWith("@rel\n");
		boolean headerRoundTrip = !absBack.hasErrors() && absBack.value().absolute;
		boolean headerAbsKept = absBack.value().tasks.stream()
			.flatMap(task -> task.entries.stream())
			.anyMatch(entry -> entry.absolute);
		//    缺省（不写程序头）= rel：老的手写文本与老导出文件都吃得下
		ScriptResult<ArmProgram> noHeader = ScriptCompiler.compile("task main\n  take @rel 0 0 0\n", registries);
		boolean headerDefaultsRel = !noHeader.hasErrors() && !noHeader.value().absolute;
		boolean headerAfterTaskReported = reportsAt(registries, "task main\n  @abs\n", 2, "第一行");
		boolean doubleHeaderReported = reportsAt(registries, "@rel\n@abs\ntask main\n  take @rel 0 0 0\n", 2, "只能有一行");
		boolean headerExtraReported = reportsAt(registries, "@abs 1 2 3\ntask main\n  take @rel 0 0 0\n", 1, "后面不能再有");
		LOGGER.info("[selftest] 脚本程序头: 反编译永远写={} @abs往返={} 条目绝对性保留={} 缺省=rel={} 晚写报错={} 重复报错={} 多余记号报错={}",
			headerAlwaysWritten, headerRoundTrip, headerAbsKept, headerDefaultsRel, headerAfterTaskReported,
			doubleHeaderReported, headerExtraReported);
		if (!headerAlwaysWritten)
			problems.add("反编译器没有永远写出程序头 @abs/@rel（导出再导入会把坐标空间静默改掉）");
		if (!headerRoundTrip)
			problems.add("@abs 程序头过不了往返（绝对坐标表会被读成相对）");
		if (!headerAbsKept)
			problems.add("绝对程序里的条目绝对性丢了");
		if (!headerDefaultsRel)
			problems.add("不写程序头时没有缺省成 rel");
		if (!headerAfterTaskReported)
			problems.add("程序头写在 task 之后没有报错");
		if (!doubleHeaderReported)
			problems.add("程序头写了两行没有报错");
		if (!headerExtraReported)
			problems.add("程序头后面跟了别的东西没有报错");

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
			"task main\n  take @rel 0 0 0\n  /* 上面那句的说明 */\n  take @rel 1 0 0\n", registries);
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
		boolean unloadAmountRequired = reportsAt(registries, "task main\n  unload @rel 0 0 2 repeat=once\n", 2, "amount");
		ScriptResult<ArmProgram> oneStack = ScriptCompiler.compile("task main\n  unload @rel 0 0 2 amount=one_stack\n",
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

		for (String problem : problems)
			LOGGER.error("[selftest] 脚本：{}", problem);
		LOGGER.info("[selftest] 脚本文本往返: {} 处问题（不变量①真实预设 + 不变量①②手写语料 共 {} 份语料）", problems.size(),
			presets + corpus);
	}

	/** 逐字段比较（§12.4 的"逐字段"）：**哪一处不同要说出来**，不然自检红了还得回去猜。 */
	@Nullable
	private static String scriptDifference(ArmProgram a, ArmProgram b) {
		if (a.tasks.size() != b.tasks.size())
			return "任务数 " + a.tasks.size() + " → " + b.tasks.size();
		if (a.absolute != b.absolute)
			return "程序坐标形态变了";
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
			if (x.loop != y.loop)
				return "任务「" + x.name + "」的循环方式变了：" + x.loop + " → " + y.loop;
			if (x.loopCount != y.loopCount)
				return "任务「" + x.name + "」的循环次数变了：" + x.loopCount + " → " + y.loopCount;
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
		if (!a.offset.equals(b.offset))
			return where + "坐标变了：" + a.offset.toShortString() + " → " + b.offset.toShortString();
		if (a.absolute != b.absolute)
			return where + "绝对/相对变了";
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
		if (a.waitAmount != b.waitAmount)
			return where + "时长变了：" + a.waitAmount + " → " + b.waitAmount;
		if (a.waitUnit != b.waitUnit)
			return where + "时长单位变了：" + a.waitUnit + " → " + b.waitUnit;
		// 极性（等通电 / 等断电）**只对"收/等"有意义**：发信号（send_on / send_off）的强度由模式本身决定，
		// 运行时不读这个字段（ProgramRuntime 的 SIGNAL 分支：收 = 阻塞等待走极性真值表；发 = 直接发/释放）。
		// 所以文本面**有意**不为 send 携带它（§12.3：send_on/send_off 只带 place=），比较也必须放过 ——
		// 否则预设里那个"从 recv 改过来时留下的"残留值会把一条正确实现判红。
		boolean sendMode = a.op == OperationType.SIGNAL && a.signalMode != ArmProgramEntry.SignalMode.RECEIVE;
		if (!sendMode && a.waitForPowered != b.waitForPowered)
			return where + "极性变了：" + a.waitForPowered + " → " + b.waitForPowered;
		if (a.signalMode != b.signalMode)
			return where + "信号模式变了：" + a.signalMode + " → " + b.signalMode;
		if (!a.callTarget.equals(b.callTarget))
			return where + "调用目标变了：" + a.callTarget + " → " + b.callTarget;
		if (a.callRepeat != b.callRepeat)
			return where + "调用次数变了：" + a.callRepeat + " → " + b.callRepeat;
		if (!a.errorMessage.equals(b.errorMessage))
			return where + "错误信息变了";
		if (a.unloadRepeat != b.unloadRepeat)
			return where + "卸货方式变了：" + a.unloadRepeat + " → " + b.unloadRepeat;
		if (a.unloadAmount != b.unloadAmount)
			return where + "卸货上限变了：" + a.unloadAmount + " → " + b.unloadAmount;
		if (!ItemStack.matches(a.icon, b.icon))
			return where + "图标变了";
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

	/**
	 * 一行里既有条目又有注释（真实情况），所以取**内容那一段**：截到第一个 `/*` 为止。
	 * 程序头（{@code @abs}/{@code @rel}）从 C 阶段起永远是文本第一行（主代理 87①），
	 * 所以这里**先跳过它**，返回值才是"这条条目长什么样"。
	 */
	private static String firstLine(String text) {
		String rest = text;
		if (rest.startsWith("@abs") || rest.startsWith("@rel")) {
			int headerEnd = rest.indexOf('\n');
			rest = headerEnd < 0 ? "" : rest.substring(headerEnd + 1);
		}
		int eol = rest.indexOf('\n');
		String line = eol < 0 ? rest : rest.substring(0, eol);
		int comment = line.indexOf("/*");
		return (comment < 0 ? line : line.substring(0, comment)).trim();
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
	 */
	private static final Map<String, String> SCRIPT_CORPUS = scriptCorpus();

	private static Map<String, String> scriptCorpus() {
		Map<String, String> corpus = new LinkedHashMap<>();
		corpus.put("全部操作", """
			task 主任务 loop=count loopN=3
			  take      @rel 1 0 0 count=3 delay=10
			  deposit   @rel 0 0 2
			  unload    @rel 0 0 2 repeat=until_empty amount=max
			  deploy    @rel 2 0 0 face=up
			  attack    @abs 10 64 -3 face=north
			  mine      @abs 10 64 -3 face=down
			  use       @abs 10 64 -3 face=east
			  use_sneak @abs 10 64 -3 face=west
			  wait      5s
			  wait_redstone on
			  signal    recv on freq=iron_ingot + copper_ingot
			  call      子任务 repeat=2
			  break
			task 子任务 loop=until_fail
			  throw     "没材料了"
			""");
		corpus.put("收发信号三种模式", """
			task main loop=forever
			  signal recv on freq=iron_ingot + copper_ingot
			  signal send_on place=0t freq=iron_ingot + copper_ingot
			  signal send_off place=1s freq=redstone_torch + {}
			""");
		corpus.put("单位与时间", """
			task main
			  wait 100t
			  wait 2m
			  take @rel 0 0 0 timeout=5s fail=skip
			  take @rel 0 0 0 timeout=3t fail=stop
			""");
		corpus.put("跳转落点两种", """
			task main
			  take @rel 0 0 0 ok=goto 收尾 fail=goto 2
			  take @rel 0 0 0
			task 收尾
			  break
			""");
		corpus.put("多行备注", """
			task main
			  take @rel 0 0 0 /*第一行
			第二行
			  第三行有缩进*/
			""");
		corpus.put("含终止符的备注", "task main\n  take @rel 0 0 0 note=\"带 */ 的备注\\n第二行\"\n");
		corpus.put("任务行尾 + 条目紧跟", """
			task main /* 任务备注 */
			  take @rel 0 0 0
			  /* 紧跟着上一条 */
			""");
		// §12.3a 第 6 条（主代理 87②）：行尾一块 + 紧跟的独占行一块 ⇒ **合并**成一个备注，不报错
		corpus.put("两块注释合并", """
			task main
			  take @rel 0 0 0 /* 行尾一句 */
			  /* 紧接着再补一句 */
			""");
		// §12.3a 第 7 条：`task` 行之后、第一条条目之前的独占注释 ⇒ 归**这个任务**
		corpus.put("task 行后的注释归任务", """
			task main
			  /* 这是任务说明 */
			  take @rel 0 0 0
			""");
		// §12.3 末尾（主代理 87①）：程序头就是坐标空间，`@abs` 这份走的是另一条分支
		corpus.put("绝对坐标表", """
			@abs
			task main
			  take @abs 10 64 -3
			  deposit @abs 12 64 -3
			""");
		corpus.put("完整 SNBT 物品", """
			task main
			  take @rel 0 0 0 icon={id:"minecraft:black_banner",count:1,components:{"minecraft:base_color":"black"}}
			  take @rel 0 0 0 icon=minecraft:leather_helmet#C41E3A
			""");
		corpus.put("空物品槽", """
			task main
			  signal recv on freq={} + iron_ingot
			""");
		return java.util.Collections.unmodifiableMap(corpus);
	}

	/** 注释归属的用例（§12.3a 表格的四行各一条：程序头 / task 行尾 / 条目前后）。 */
	private static final String SCRIPT_NOTES_SAMPLE = """
		/*程序备注
		第二行*/
		task main /*任务备注*/
		  take @rel 0 0 0 /*条目备注
		第二行*/
		  deposit @rel 0 0 2
		""";

	/** {@code note="…"} 保底写法；与 {@link #SCRIPT_NOTES_SAMPLE} 的条目备注必须是**同一个字符串**。 */
	private static final String SCRIPT_QUOTED_NOTE_SAMPLE = "task main\n  take @rel 0 0 0 note=\"条目备注\\n第二行\"\n";

	/**
	 * §12.3a 第 6 条（主代理 87②）：**同一个归属对象上的两块注释合并**成一个备注（换行连接），
	 * **不报错**。行尾一块 + 紧跟其后的独占行一块 —— 玩家"再补一句注释"就是这个写法。
	 */
	private static final String SCRIPT_MERGED_NOTE_SAMPLE = "task main\n"
		+ "  take @rel 0 0 0 /* 行尾一句 */\n"
		+ "  /* 紧接着再补一句 */\n";

}

