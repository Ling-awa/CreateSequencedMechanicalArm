package io.github.Ling.create_sequenced_mechanical_arm.importtable;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 「操作导入桌」的方块实体：**一格输入（空白操作表）+ 一格输出（导入好的操作表）**，
 * 中间是一段带进度条的"读取脚本"过程。
 *
 * <p>骨架照 Create 的蓝图桌 {@code SchematicTableBlockEntity}（{@code SmartBlockEntity} 家族、
 * 2 格 {@link ItemStackHandler}、{@code clientPacket} 分支同步进度、{@code sendUpdate} 推包、
 * {@code canPlayerUse} 64 格），**只有一处刻意不同**：
 *
 * <h2>★ 进度由<b>服务端自己</b>按 tick 推进（不抄上游那条"客户端发包驱动"的链）</h2>
 * 上游的蓝图上传是<b>客户端</b>在读文件、分片上传，进度自然由客户端算：服务端只是收片、
 * 把 {@code uploadingProgress} 记下来，客户端每 10 tick 问一次（{@code ClientSchematicLoader}），
 * 界面一关就不再有进度。我们这条路恰好相反 —— 文件在<b>服务端</b>的磁盘上、文本在服务端一次读完，
 * 客户端全程只是"请求列表 / 请求导入"两句话。若照上游那条链，就会出现"玩家关掉界面 ⇒ 导入停在半路"
 * 以及"客户端说自己导完了"这种根本没法验证的状态（数据在服务端，凭什么听客户端的）。
 * 所以进度是服务端的 {@link #tick()} 里 {@code progress += 1f / IMPORT_TICKS}，
 * 客户端只读同步下来的值（{@code read(..., clientPacket = true)}），一帧都不自己推进。
 *
 * <p><b>为什么是固定 {@link #IMPORT_TICKS} 而不是"按文件字节数"</b>：按字节数意味着同一张脚本
 * 在小文件上"一瞬间完成"、在大文件上要等十几秒 —— 玩家无法预期，而且"快慢"会把磁盘速度
 * 泄漏成游戏节奏（在慢盘上抽风）。5 秒是个**固定的仪式感**：它只表达"这台机器在干活"，
 * 不承诺"越大的脚本越慢"。真要按数据量算，第一个该按的是<b>条目数</b>而不是字节数（注释与空行不该收费）。
 *
 * <h2>状态机的边界</h2>
 * <ul>
 *   <li>{@link #startImport}：**服务端**的判定与动作（校验槽位 → 校验文件 → 消耗输入 → 置 {@code isImporting}）。
 *       客户端只说"我想导入《某某》"，不参与任何判定；</li>
 *   <li>{@link #tick()}：推进进度，到 {@link #PROGRESS_DONE}（= 1 − 半步，**不是** {@code >= 1f}，
 *       理由见该常量）时调 {@link #finishImport()} 把文本写进输出槽；</li>
 *   <li>输入槽在**开始**时就被消耗（与上游 {@code startUpload} 清空输入槽同一个取舍）：否则玩家能在
 *       5 秒里把那张表抽走，产出却照样落进输出槽 —— 白得一张卡。</li>
 * </ul>
 *
 * <p>⚠️ 已知取舍（照上游）：导入过程中的状态**不写存档**（{@code clientPacket} 分支才写/读进度），
 * 所以这 5 秒里若区块被卸载，那张已消耗的输入表就没了（输出槽里也不会有东西）。没有补做，
 * 是因为"还原一份可能已经被改过的文件"比丢掉一张空白操作表更糟；真要持久化，得连文件内容一起存。
 */
public class ProgramImportTableBlockEntity extends SmartBlockEntity implements MenuProvider, IInteractionChecker, Clearable {

	private static final Logger LOGGER = LogUtils.getLogger();

	/** 一次导入的固定时长（tick）。100 tick = 5 秒，理由见类注释。 */
	public static final int IMPORT_TICKS = 100;

	/** 每 tick 推进的进度量。抽成常量：{@link #tick()} 里的加法与"到点"判定必须用同一个数。 */
	private static final float PROGRESS_STEP = 1f / IMPORT_TICKS;

	/**
	 * "进度已经到 1"的判定门限 = {@code 1 − 半步}。
	 *
	 * <p><b>为什么不直接判 {@code importProgress >= 1f}</b>：{@code 1f / 100} 在 float 里是
	 * {@code 0.0099999997…}，逐 tick 累加 100 次得到的是 {@code ≈0.999999}（**不是 1.0**）——
	 * 于是"正好 {@link #IMPORT_TICKS} tick 完成"这件事**永远不会发生**：进度条永久停在 99%、
	 * 输出槽永远空、{@code isImporting} 永远为真。自检曾因此一路红 7 项，而且后面三条"拒绝类"
	 * 断言拿到的其实是 {@code ALREADY_IMPORTING}（看起来像"判定漏了"，实则是上一次导入没结束）。
	 *
	 * <p>为什么是**半步**而不是随手一个 {@code 1e-6}：100 次 float 累加的最坏误差约 {@code 1e-5}
	 * 量级，半步（0.005）比它大两个数量级 ⇒ 不会漏判；而进位只发生在最后一次加法之后 ⇒ 也不会让
	 * 完成提前一 tick（第 99 tick 的进度约 0.99，仍低于 0.995）。
	 */
	private static final float PROGRESS_DONE = 1f - PROGRESS_STEP / 2f;

	/** 输入槽（空白操作表）与输出槽（导入结果）的下标 —— 菜单里的槽位顺序必须与它一致。 */
	public static final int SLOT_INPUT = 0;
	public static final int SLOT_OUTPUT = 1;

	/** 存档键。与上游同名（{@code Inventory}），其余三个只出现在 {@code clientPacket} 那一份里。 */
	private static final String KEY_INVENTORY = "Inventory";
	private static final String KEY_IMPORTING = "Importing";
	private static final String KEY_FILE = "File";
	private static final String KEY_PROGRESS = "Progress";

	/**
	 * 2 格库存。
	 *
	 * <p>{@code onContentsChanged} 里必须 {@code setChanged()}：不标脏的话"塞进输入槽然后退出去"
	 * 会让卡凭空消失（方块实体的存盘只在标脏时发生）。
	 */
	public class ProgramImportInventory extends ItemStackHandler {
		public ProgramImportInventory() {
			super(2);
		}

		@Override
		protected void onContentsChanged(int slot) {
			super.onContentsChanged(slot);
			setChanged();
		}
	}

	public final ProgramImportInventory inventory = new ProgramImportInventory();

	/** 是否正在导入（**服务端是权威**，客户端这份是同步下来的只读副本）。 */
	public boolean isImporting;
	/** 0..1 的进度（服务端每 tick 推，客户端只读）。 */
	public float importProgress;
	/** 这一 tick 结束后要不要把方块实体刷给客户端（上游同名字段的同一用法）。 */
	public boolean sendUpdate;
	/** 正在导入的文件名 —— **只用于界面显示**，不参与任何判定。 */
	@Nullable
	public String importingFile;

	/**
	 * 已经校验通过、等着写进输出槽的脚本文本。
	 *
	 * <p>为什么在<b>开始</b>时读、而不是完成时再读：完成那一刻再读一次，等于赌"这 5 秒里文件没被改" ——
	 * 被改过就是一半校验（开始）一半新内容（完成）。读一次、把校验过的那份攥在手里，
	 * 整个导入就是一个原子动作：进卡的永远是**通过校验的那份文本**。
	 */
	@Nullable
	private String pendingText;

	/** {@link #startImport} 的结局；{@code STARTED} 之外都由调用方翻成一句回执。 */
	public enum StartResult {
		STARTED,
		/** 已经有一次导入在跑（界面在跑的时候还按了确认）。 */
		ALREADY_IMPORTING,
		/** 输入槽里没有操作表。 */
		NO_INPUT,
		/** 输入槽里那张操作表**已经有程序**（导入只接受空白表）。 */
		NOT_BLANK,
		/** 输出槽里还有东西。 */
		OUTPUT_BUSY,
		/** 文件名不合法。 */
		BAD_NAME,
		/** 文件不存在。 */
		NOT_FOUND,
		/** 文件读不出来。 */
		UNREADABLE,
		/** 超过脚本文本上限。 */
		TOO_LARGE,
		/** 编译不过。 */
		DOES_NOT_COMPILE
	}

	public ProgramImportTableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
	}

	// ————————————————————————— tick —————————————————————————

	@Override
	public void tick() {
		super.tick();
		// ① 推包（上游同款）：所有状态变化都只置这个标记，真正发送集中在 tick 里，免得一帧发好几个包
		if (sendUpdate) {
			sendUpdate = false;
			if (level != null)
				level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 6);
		}
		if (!isImporting || level == null || level.isClientSide)
			return; // 客户端**不推进**进度（它没有 pendingText，也不能写输出槽），理由见类注释
		if (importProgress >= PROGRESS_DONE) {
			finishImport();
			return;
		}
		importProgress = Math.min(1f, importProgress + PROGRESS_STEP);
		sendUpdate = true;
		if (importProgress >= PROGRESS_DONE)
			finishImport();
	}

	// ————————————————————————— 开始 / 完成 —————————————————————————

	/**
	 * 开始一次导入：**全部校验 + 消耗输入 + 置状态**都在这里（唯一实现，自检也走它）。
	 *
	 * <p>校验顺序（每一步对应一条回执，绝不静默）：
	 * <ol>
	 *   <li>没有正在跑的导入；</li>
	 *   <li>输入槽里是**空白**操作表（{@link OperationTableItem} 且 {@link CardProgramStorage#hasProgram} 为假）
	 *       —— 这里要再判一次，因为槽位过滤只是玩家那一侧的方便，别的入口（漏斗 / 别的模组的管道）
	 *       不经过 {@code mayPlace}；</li>
	 *   <li>输出槽是空的（{@code canWrite()} 的另一半：产出没有地方放就等于白消耗一张表）；</li>
	 *   <li>文件存在、读得出、不超上限、**编译得过**（{@link ProgramImportFiles#read}）。</li>
	 * </ol>
	 * 全部通过之后才消耗输入槽 —— 中途任何一条不过，输入那张表都还在原位
	 * （"点错了文件"不该花掉一张卡）。
	 */
	public StartResult startImport(@Nullable String name, HolderLookup.Provider registries) {
		if (isImporting)
			return StartResult.ALREADY_IMPORTING;

		ItemStack input = inventory.getStackInSlot(SLOT_INPUT);
		if (input.isEmpty() || !OperationTableItem.isTable(input))
			return StartResult.NO_INPUT;
		if (CardProgramStorage.hasProgram(input))
			return StartResult.NOT_BLANK;
		if (!inventory.getStackInSlot(SLOT_OUTPUT)
			.isEmpty())
			return StartResult.OUTPUT_BUSY;

		ProgramImportFiles.Outcome file = ProgramImportFiles.read(name, registries);
		switch (file.problem()) {
			case BAD_NAME:
				return StartResult.BAD_NAME;
			case NOT_FOUND:
				return StartResult.NOT_FOUND;
			case UNREADABLE:
				return StartResult.UNREADABLE;
			case TOO_LARGE:
				return StartResult.TOO_LARGE;
			case DOES_NOT_COMPILE:
				return StartResult.DOES_NOT_COMPILE;
			case OK:
				break;
		}

		// ★ 消耗输入：与上游 startUpload 里的 `inventory.setStackInSlot(0, EMPTY)` 同一处语义
		inventory.setStackInSlot(SLOT_INPUT, ItemStack.EMPTY);
		beginImport(name == null ? "" : name.trim(), file.text() == null ? "" : file.text());
		return StartResult.STARTED;
	}

	/**
	 * 用一段**已经校验过**的文本进入导入状态（不碰槽位、不再读盘）。
	 *
	 * <p>为什么把它与 {@link #startImport} 分开：这是"导入"这件事的最小状态开关 ——
	 * 启动自检要验"完成时写失败就一个字都不写"（⑤），而那条路在真机上**不可达**
	 * （文本在开始时已经过了同一道 {@code ProgramText.withinLimit}），只能由自检从这道门喂进去。
	 * 校验本身仍然只在 {@link #startImport} / {@link ProgramImportFiles} 里，这里不做任何判定。
	 */
	public void beginImport(String file, String text) {
		pendingText = text;
		importingFile = file;
		importProgress = 0f;
		isImporting = true;
		sendUpdate = true;
		setChanged();
	}

	/**
	 * 进度到 1：把校验过的文本写进**一张新卡**，放进输出槽。
	 *
	 * <p><b>为什么是一张新卡，而不是把刚消耗的那张空白表还回去改</b>：空白表可能还带着
	 * 编辑状态键（模式 / 任务下标 / 录制原点，见 {@code CardProgramStorage}）—— 那是"上一张卡
	 * 怎么被用过"的痕迹，不该跟着导入结果走进新卡。新卡只有程序文本，干净、可预期。
	 *
	 * <p><b>写失败就不写</b>（{@code StoreResult != OK}）：一张"空白的操作表"落进输出槽比什么都没有更糟 ——
	 * 玩家会以为导入成功，然后把一张废卡插进臂里。失败时输出槽保持空、{@code isImporting} 归位，
	 * 并留一条 ERROR（这张输入表已经消耗了，只能在日志里解释为什么什么都没产出）。
	 */
	private void finishImport() {
		String text = pendingText;
		String file = importingFile;
		isImporting = false;
		importingFile = null;
		pendingText = null;
		importProgress = 0f;
		sendUpdate = true;
		setChanged();

		if (text == null) {
			LOGGER.error("{} 的导入没有待写入的文本（状态机不该走到这里），输出槽保持空", worldPosition);
			return;
		}
		ItemStack out = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.StoreResult stored = CardProgramStorage.writeText(out, text);
		if (stored != CardProgramStorage.StoreResult.OK) {
			LOGGER.error("{} 的导入结果无法写入操作表（{}，{} 字节），输出槽保持空 —— 输入那张空白表已经消耗",
				worldPosition, stored, text.length());
			return;
		}
		inventory.setStackInSlot(SLOT_OUTPUT, out);
		LOGGER.info("{} 完成导入《{}》（{} 字节）", worldPosition, file, text.length());
	}

	// ————————————————————————— 存 / 读 —————————————————————————

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		tag.put(KEY_INVENTORY, inventory.serializeNBT(registries));
		super.write(tag, registries, clientPacket);
		// ★ 只在**同步包**那一份里写进度（照上游）：存档里毫无意义（读回来一定已经过期），
		//   而且服务端每次 tick 都要发这一份 ⇒ 它是包体，不是数据。同理客户端看不到 pendingText。
		if (clientPacket && isImporting) {
			tag.putBoolean(KEY_IMPORTING, true);
			tag.putString(KEY_FILE, importingFile == null ? "" : importingFile);
			tag.putFloat(KEY_PROGRESS, importProgress);
		}
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		inventory.deserializeNBT(registries, tag.getCompound(KEY_INVENTORY));
		super.read(tag, registries, clientPacket);
		if (!clientPacket)
			return;
		// 缺键 = 没在导入（服务端就是按"不写"来表达这件事的），所以这里判 contains 而不是读一个假值
		boolean importing = tag.getBoolean(KEY_IMPORTING);
		isImporting = importing;
		importingFile = importing ? tag.getString(KEY_FILE) : null;
		importProgress = importing ? tag.getFloat(KEY_PROGRESS) : 0f;
	}

	/**
	 * 清空两格库存（{@link Clearable}：漏斗/命令/创造模式清空容器时走它）。
	 *
	 * <p>为什么不学上游用 Create 的 {@code ItemStackHandlerAccessor} 直接抹 {@code stacks} 列表：
	 * 那是 Create 内部给自己用的 mixin 访问器，我们跨模组强转它等于把版本兼容押在别人的内部类上；
	 * 两格里 {@code setStackInSlot} 一样干净，而且会正常触发 {@code onContentsChanged}（标脏）。
	 */
	@Override
	public void clearContent() {
		for (int slot = 0; slot < inventory.getSlots(); slot++)
			inventory.setStackInSlot(slot, ItemStack.EMPTY);
	}

	// ————————————————————————— 菜单 —————————————————————————

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return ProgramImportTableMenu.create(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("create_sequenced_mechanical_arm.gui.import.title");
	}

	/**
	 * 玩家能不能用这台机器（{@code MenuBase.stillValid} 会问这个，走 {@link IInteractionChecker}）。
	 *
	 * <p>64.0D 与上游 {@code SchematicTableBlockEntity.canPlayerUse} 逐字相同（= 8 格）：
	 * 服务端权威的"别隔着一片大陆开界面"。{@code SmartBlockEntity} 的默认实现算的是同一件事，
	 * 这里仍然显式写一遍 —— 它是菜单是否继续有效的判据，值得在类里看得见。
	 */
	@Override
	public boolean canPlayerUse(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this)
			return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
			worldPosition.getZ() + 0.5D) <= 64.0D;
	}

	/**
	 * 从"玩家当前打开的菜单"反查这台机器 —— 三个网络包共用的**唯一**权威入口。
	 *
	 * <p>为什么不收客户端发来的坐标：坐标是客户端给的，直接 {@code getBlockEntity} 会顺手加载远处区块，
	 * 而且"打开的是不是我这台机器"还得再验一遍。菜单是服务端自己开的，它的 {@code contentHolder}
	 * 就是玩家正在看的那一台，比任何坐标都可信。距离再问一次 {@link #canPlayerUse}（8 格）。
	 */
	@Nullable
	public static ProgramImportTableBlockEntity ofOpenMenu(Player player) {
		if (!(player instanceof ServerPlayer serverPlayer))
			return null;
		if (!(serverPlayer.containerMenu instanceof ProgramImportTableMenu menu))
			return null;
		ProgramImportTableBlockEntity table = menu.contentHolder;
		if (table == null || table.getLevel() != player.level())
			return null;
		return table.canPlayerUse(player) ? table : null;
	}

}
