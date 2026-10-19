package io.github.Ling.create_sequenced_mechanical_arm.importtable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
 * 中间是一段"收脚本字节"的过程。
 *
 * <p>骨架照 Create 的蓝图桌 {@code SchematicTableBlockEntity}（{@code SmartBlockEntity} 家族、
 * 2 格 {@link ItemStackHandler}、{@code clientPacket} 分支同步进度、{@code sendUpdate} 推包、
 * {@code canPlayerUse} 64 格），**状态机的两端刻意与它相反**（见下）。
 *
 * <h2>★ 用户改口径（第二步）：服务端**彻底不碰脚本文件**</h2>
 * "我觉得没必要动服务端文件" —— 所以这条路现在是<b>客户端读本机文件、分片上传</b>
 * （照上游 {@code ClientSchematicLoader → ServerSchematicLoader} 的分工）。服务端这边
 * <b>不列目录、不读文件、不写文件</b>，只做三件事：<b>收片</b>、<b>裁决</b>、<b>写卡</b>。
 * <ul>
 *   <li><b>列表</b>：客户端自己扫本机 {@code sequenced_arm/scripts/}（{@link ProgramImportFiles#list()}），
 *       不再有"请求列表 / 下发列表"这一对包；</li>
 *   <li><b>传输</b>：{@code network/ProgramImportUploadStartPacket}（一句"我要传《某某》，共 N 字节"）
 *       + {@code network/ProgramImportChunkPacket}（每片 {@link #CHUNK_BYTES}、每
 *       {@link #CHUNK_INTERVAL_TICKS} tick 一片，最多 {@link #MAX_CHUNKS} 片 ≈ 4 秒）；</li>
 *   <li><b>裁决仍在服务端</b>：总量上限、片序、乱序/超量/超时、能不能编译、写不写卡 —— 客户端说破天
 *       也只提供了一串字节。这是"数据在谁的磁盘上"与"谁说了算"两件事的分界。</li>
 * </ul>
 *
 * <h2>★ 进度条改成"已收字节 / 总字节"（不再有固定 100 tick）</h2>
 * 第一步的进度是服务端自己 {@code progress += 1f / IMPORT_TICKS} 走 100 tick，理由是"文件在服务端、
 * 数据量不该变成游戏节奏"。**那条理由随口径一起作废**：现在数据是客户端一片片送来的，
 * 服务端唯一诚实可说的事就是"收到了多少字节"。
 * <ul>
 *   <li>固定 tick 在这条链上是<b>错的</b>：60 KiB 与 18 字节的脚本都走 100 tick，玩家看到的是
 *       "明明传完了进度条还在爬"；反过来说，进度条该动的时候它不动（片还没来）；</li>
 *   <li>字节驱动是<b>可验证</b>的：{@code uploadReceived / uploadTotal} 两个整数同步给客户端，
 *       进度条与"已收 x / 共 y 字节"那行字用的是同一对数；</li>
 *   <li>顺带删掉了第一步那个"到点判定要用 {@code 1 − 半步} 而不是 {@code >= 1f}"的 float 坑
 *       —— 现在只有"收满"这一个条件（{@code received == total}），没有累加。</li>
 * </ul>
 *
 * <h2>会话的生命周期（一桌一会话）</h2>
 * <ul>
 *   <li>{@link #startUpload}：**服务端**的全部前置判定 + 消耗输入 + 开会话（{@link StartResult}）；</li>
 *   <li>{@link #acceptChunk}：片序 / 总量 / 溢出三处校验，任何一处不对 ⇒ **立刻中止会话**并回一个
 *       {@link ChunkResult} 让包那一侧说话；</li>
 *   <li>{@link #tick()}：只做清理 —— ① 上传者已经不是"开着这台机器的玩家"（关界面 / 走远 / 下线）
 *       ⇒ 丢弃；② 静默超过 {@link #UPLOAD_TIMEOUT_TICKS} ⇒ 丢弃。</li>
 * </ul>
 *
 * <p><b>为什么关界面靠 tick 清、而不是靠一个"界面关了"的包</b>：上游那条链的缺陷正是"界面一关就
 * 不再有进度"，服务端手里留着一份半成品；但反过来加一个 S2C/C2S 的"我关界面了"也不可靠
 * （客户端崩了、被杀了就不会发）。服务端每 tick 自己问一句"他还在看这台机器吗"是**唯一不依赖
 * 客户端自觉**的判据，超时是它的兜底（客户端每 {@link #CHUNK_INTERVAL_TICKS} tick 一片，
 * 所以 {@link #UPLOAD_TIMEOUT_TICKS} 是片间隔的 6 倍 —— 一次卡顿不会杀掉健康的上传）。
 *
 * <p>⚠️ 已知取舍（与第一步相同、照上游）：会话状态**不写存档**（只在 {@code clientPacket}
 * 那一份里写），所以这 4 秒里若区块被卸载，那张已消耗的输入表就没了（输出槽里也不会有东西）。
 * 没有补做，是因为"还原一份可能已经被改过的文本"比丢掉一张空白操作表更糟。
 */
public class ProgramImportTableBlockEntity extends SmartBlockEntity implements MenuProvider, IInteractionChecker, Clearable {

	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * 一片的字节数：**8 KiB**。
	 *
	 * <p>为什么是这个量级：60 KiB / 8 KiB ⇒ 最多 8 片。片越小则包越多（每片一个包 = 8 个包 ≈ 4 秒），
	 * 片越大则单包越大、一次丢包的代价越高。8 KiB 与上游 {@code SchematicUploadPacket} 的
	 * 分片思路同量级，而 8 片恰好让"最多 4 秒"这句承诺成立。
	 */
	public static final int CHUNK_BYTES = 8 * 1024;

	/**
	 * 两片之间的间隔（tick）：**10**，照上游 {@code ClientSchematicLoader.PACKET_DELAY = 10}。
	 *
	 * <p>为什么要有间隔而不是一口气全发完：一口气发 8 个包会在一 tick 内占满连接、并且让
	 * "进度条"变成"闪一下就好了"，玩家完全看不出机器在干活；10 tick 一片（0.5 秒）是上游
	 * 验证过的节奏，也让服务端有机会在**每片之间**发现"人不在了"。
	 */
	public static final int CHUNK_INTERVAL_TICKS = 10;

	/** 最多几片：{@code ceil(60 KiB / 8 KiB) = 8}（向上取整，最后一片可能不满）。 */
	public static final int MAX_CHUNKS = (ProgramText.MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

	/**
	 * 静默多久就丢弃这次上传（tick）：{@link #CHUNK_INTERVAL_TICKS} 的 6 倍 = 60 tick = 3 秒。
	 *
	 * <p>片间隔 10 tick，所以"健康的会话"最多 10 tick 就会有一片；给到 6 倍是为了容忍一次卡顿 /
	 * 一次 GC / 一次重传。同时它保证"玩家关掉界面之后，这台桌子在 3 秒内一定回到干净状态"，
	 * 下一位玩家不会撞上一个永远不结束的会话。
	 */
	public static final int UPLOAD_TIMEOUT_TICKS = 6 * CHUNK_INTERVAL_TICKS;

	/** 输入槽（空白操作表）与输出槽（导入结果）的下标 —— 菜单里的槽位顺序必须与它一致。 */
	public static final int SLOT_INPUT = 0;
	public static final int SLOT_OUTPUT = 1;

	/** 空字节数组（会话被清掉之后 {@link #completeUpload} 的兜底输入；**不要**每次 new 一个）。 */
	private static final byte[] EMPTY_BYTES = new byte[0];

	/** 存档键。与上游同名（{@code Inventory}），其余几个只出现在 {@code clientPacket} 那一份里。 */
	private static final String KEY_INVENTORY = "Inventory";
	private static final String KEY_IMPORTING = "Importing";
	private static final String KEY_FILE = "File";
	private static final String KEY_RECEIVED = "Received";
	private static final String KEY_TOTAL = "Total";

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

	/** 是否正在收一次上传（**服务端是权威**，客户端这份是同步下来的只读副本）。 */
	public boolean isImporting;
	/** 已收字节（服务端权威；客户端只读）。进度条与"已收 x / 共 y"那行字都用它。 */
	public int uploadReceived;
	/** 客户端声明的总字节（服务端权威；客户端只读）。 */
	public int uploadTotal;
	/** 这一 tick 结束后要不要把方块实体刷给客户端（上游同名字段的同一用法）。 */
	public boolean sendUpdate;
	/** 正在传的文件名 —— **只用于界面显示**，不参与任何判定。 */
	@Nullable
	public String importingFile;

	/** 谁在传（{@code null} = 无主会话，只有启动自检会这样开；它因此跳过"人还在吗"那道检查）。 */
	@Nullable
	private UUID uploader;
	/** 本会话声明的总字节（与 {@link #uploadTotal} 恒等，分开是因为后者是对客户端的字段）。 */
	private int expectedTotal;
	/** 已收字节（与 {@link #uploadReceived} 恒等，同上）。 */
	private int receivedBytes;
	/** 下一片应该是第几片（0 起）。乱序判据就是 {@code seq != nextSeq}。 */
	private int nextSeq;
	/** 距上一片过了多少 tick（超时判据）。 */
	private int idleTicks;
	/**
	 * 分片拼装缓冲。
	 *
	 * <p>为什么在<b>开始</b>时就开好、并且只按 {@link #expectedTotal} 收：容量上界在开会话那一刻
	 * 就被钉死了，于是"客户端谎报总量再猛灌"这条路不存在 —— 每次追加都要先过
	 * {@code receivedBytes + data.length <= expectedTotal}。
	 */
	@Nullable
	private ByteArrayOutputStream buffer;

	/** {@link #startUpload} 的结局；{@code STARTED} 之外都由调用方翻成一句回执。 */
	public enum StartResult {
		STARTED,
		/** 已经有一次上传在跑（同一张桌子同时只允许一个上传者）。 */
		ALREADY_UPLOADING,
		/** 输入槽里没有操作表。 */
		NO_INPUT,
		/** 输入槽里那张操作表**已经有程序**（导入只接受空白表）。 */
		NOT_BLANK,
		/** 输出槽里还有东西。 */
		OUTPUT_BUSY,
		/** 文件名不合法。 */
		BAD_NAME,
		/** 声明的总量是 0（空脚本）：永远收不满，只能在开始时就拒。 */
		EMPTY_FILE,
		/** 声明的总量超过 {@link ProgramText#MAX_BYTES}。 */
		TOO_LARGE
	}

	/** {@link #acceptChunk} 的结局。{@code ACCEPTED} / {@code COMPLETED} 之外都由调用方翻成一句回执。 */
	public enum ChunkResult {
		/** 收下了，还没收满。 */
		ACCEPTED,
		/** 收满了，并且**已经写进输出槽**。 */
		COMPLETED,
		/** 没有会话（上一个片之后会话被超时/离开清掉了，或这个客户端根本没开过会话）——**不重复报错**。 */
		NO_UPLOAD,
		/** 片序不对 / 总量与开会话时声明的不一致 / 片子大小非法 ⇒ 会话已中止。 */
		BAD_SEQUENCE,
		/** 收到的字节会超过声明的总量（或拼出来的文本超过上限）⇒ 会话已中止。 */
		TOO_LARGE,
		/** 收满了但编译不过：**一个字都不写**（会话已中止）。 */
		DOES_NOT_COMPILE,
		/** 收满了、编译也过了，但卡写不进去（理论上不可达，见 {@link #completeUpload}）。 */
		WRITE_FAILED
	}

	public ProgramImportTableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
	}

	// ————————————————————————— tick —————————————————————————

	/**
	 * 服务端每个 tick 只做**清理**（进度不再由 tick 推进，见类注释）。
	 *
	 * <p>两条清理各有分工：① **人在不在**是主判据（关界面 / 走远 / 下线都能覆盖，不依赖客户端
	 * 自觉发一个"我关了"）；② **超时**是兜底（客户端被杀了、包丢了、或者它压根不再发包）。
	 * 两条都走 {@link #abortUpload}，因此"丢弃"只有一个出口。
	 */
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
			return; // 客户端**不清理**（它没有会话，也不能写输出槽），理由见类注释

		// ② 上传者还开着这台机器的界面吗？（无主会话 —— 只有启动自检 —— 跳过这一条）
		if (uploader != null) {
			ServerPlayer player = level.getServer() == null ? null
				: level.getServer()
					.getPlayerList()
					.getPlayer(uploader);
			if (player == null
				|| !(player.containerMenu instanceof ProgramImportTableMenu menu)
				|| menu.contentHolder != this
				|| !canPlayerUse(player)) {
				abortUpload("上传者已离开 / 关掉了界面");
				return;
			}
		}

		// ③ 静默超时：片间隔 10 tick，60 tick 还没有新片 ⇒ 这次上传已经不可能诚实地完成
		if (++idleTicks > UPLOAD_TIMEOUT_TICKS) {
			abortUpload("静默超过 " + UPLOAD_TIMEOUT_TICKS + " tick（片间隔 " + CHUNK_INTERVAL_TICKS + "）");
		}
	}

	// ————————————————————————— 开始 / 收片 / 完成 —————————————————————————

	/**
	 * 开始一次上传：**全部前置校验 + 消耗输入 + 开会话**都在这里（唯一实现，自检也走它）。
	 *
	 * <p>校验顺序（每一步对应一条回执，绝不静默）：
	 * <ol>
	 *   <li>没有正在跑的上传（一桌一会话）；</li>
	 *   <li>输入槽里是**空白**操作表（{@link OperationTableItem} 且 {@link CardProgramStorage#hasProgram} 为假）
	 *       —— 这里要再判一次，因为槽位过滤只是玩家那一侧的方便，别的入口（漏斗 / 别的模组的管道）
	 *       不经过 {@code mayPlace}；</li>
	 *   <li>输出槽是空的（产出没有地方放就等于白消耗一张表）；</li>
	 *   <li>名字合法、声明的总量在 {@code (0, MAX_BYTES]} 之间。</li>
	 * </ol>
	 * 全部通过之后才消耗输入槽 —— 中途任何一条不过，输入那张表都还在原位
	 * （"点错了文件"不该花掉一张卡）。
	 *
	 * <p>@param uploader 上传者；{@code null} 表示无主会话（只有启动自检这样开，
	 *                  也因此跳过 {@link #tick()} 里"人还在不在"那道检查）
	 */
	public StartResult startUpload(@Nullable UUID uploader, @Nullable String name, int totalBytes) {
		if (isImporting)
			return StartResult.ALREADY_UPLOADING;

		ItemStack input = inventory.getStackInSlot(SLOT_INPUT);
		if (input.isEmpty() || !OperationTableItem.isTable(input))
			return StartResult.NO_INPUT;
		if (CardProgramStorage.hasProgram(input))
			return StartResult.NOT_BLANK;
		if (!inventory.getStackInSlot(SLOT_OUTPUT)
			.isEmpty())
			return StartResult.OUTPUT_BUSY;
		if (!ScriptFiles.isValidName(name))
			return StartResult.BAD_NAME;
		if (totalBytes <= 0)
			return StartResult.EMPTY_FILE;
		if (totalBytes > ProgramText.MAX_BYTES)
			return StartResult.TOO_LARGE;

		// ★ 消耗输入：与上游 startUpload 里的 `inventory.setStackInSlot(0, EMPTY)` 同一处语义。
		//   往后的中止（乱序 / 超量 / 超时 / 编译不过）**都不归还**这张表 —— 与第一步一致：
		//   会话一旦开始，"这张空白表被这次导入花掉了"就是既成事实，归还等于给了一条刷卡的缝。
		inventory.setStackInSlot(SLOT_INPUT, ItemStack.EMPTY);

		this.uploader = uploader;
		this.expectedTotal = totalBytes;
		this.receivedBytes = 0;
		this.nextSeq = 0;
		this.idleTicks = 0;
		this.buffer = new ByteArrayOutputStream(Math.min(totalBytes, CHUNK_BYTES * 2));
		this.importingFile = name == null ? "" : name.trim();
		this.uploadReceived = 0;
		this.uploadTotal = totalBytes;
		this.isImporting = true;
		this.sendUpdate = true;
		setChanged();
		return StartResult.STARTED;
	}

	/**
	 * 收一片。**每一片都要过三道门**，任何一道不过就立刻中止会话：
	 * <ol>
	 *   <li>总量与开会话时声明的一致（否则这就是另一次上传的片子，或者一个改过的包）；</li>
	 *   <li>片序正好是下一片（**乱序 = 中止**，不缓存、不等待 —— 缓存乱序片等于把"重组"这件
	 *       比它复杂得多的事搬进方块实体）；</li>
	 *   <li>追加之后不超过声明的总量（超量 = 中止，而不是"截断到上限"：截出来的文本会是一份
	 *       玩家没写过的半截脚本）。</li>
	 * </ol>
	 *
	 * <p>片大小另外卡一道 {@link #CHUNK_BYTES}：解码器已经卡过（{@code readByteArray}），
	 * 这里再判一次是因为这个方法也能被服务端自己的代码调（自检就是）；空片一律当非法
	 * —— 空片说明客户端的状态机坏了，继续收只会让"收了几片"变得没有意义。
	 */
	public ChunkResult acceptChunk(int seq, int totalBytes, byte[] data) {
		if (!isImporting || buffer == null)
			return ChunkResult.NO_UPLOAD;
		if (totalBytes != expectedTotal) {
			abortUpload("片里的总量（" + totalBytes + "）与开会话时声明的不一致（" + expectedTotal + "）");
			return ChunkResult.BAD_SEQUENCE;
		}
		if (seq != nextSeq) {
			abortUpload("片序不对：收到第 " + seq + " 片，应为第 " + nextSeq + " 片");
			return ChunkResult.BAD_SEQUENCE;
		}
		if (data.length <= 0 || data.length > CHUNK_BYTES) {
			abortUpload("片子大小非法：" + data.length + " 字节（一片 1.." + CHUNK_BYTES + "）");
			return ChunkResult.BAD_SEQUENCE;
		}
		if (receivedBytes + data.length > expectedTotal) {
			abortUpload("收到的字节会超过声明的总量：" + receivedBytes + " + " + data.length + " > " + expectedTotal);
			return ChunkResult.TOO_LARGE;
		}

		buffer.write(data, 0, data.length);
		receivedBytes += data.length;
		nextSeq++;
		idleTicks = 0;
		uploadReceived = receivedBytes;
		sendUpdate = true;
		setChanged();

		if (receivedBytes >= expectedTotal)
			return completeUpload();
		return ChunkResult.ACCEPTED;
	}

	/**
	 * 收满了：解码 → **编译过了才写卡** → 放进输出槽。
	 *
	 * <p>为什么"编译不过就一个字都不写"：产出是一张卡，卡上只存文本，存一份编译器不认的文本
	 * 等于给玩家一张"插进臂里也不生效"的废卡（而且是花了那张空白换来的）。
	 * 判据就是 {@code ScriptCompiler.compile(text).hasErrors()} —— 与编辑器 / 命令同一条。
	 *
	 * <p>三次拒绝都**先清会话再返回**（{@link #resetSession}），所以调用方拿到的结局与方块实体
	 * 现在的状态永远一致（{@code isImporting} 已经假、输出槽仍然是空的）。
	 */
	private ChunkResult completeUpload() {
		byte[] all = buffer == null ? EMPTY_BYTES : buffer.toByteArray();
		String file = importingFile == null ? "" : importingFile;
		// 先抓住"还需要的"，再清会话（清完之后 expectedTotal / buffer 都是 0 / null）
		resetSession();

		String text = stripBom(new String(all, StandardCharsets.UTF_8));
		// ★ 再判一次字节上限：这一份是**重新编码**之后的串，一个坏字节解码成 U+FFFD 再编码是 3 字节，
		//   所以"收到的字节没超"并不代表"要写进卡的文本没超"。上限只有一条（ProgramText.MAX_BYTES）。
		if (!ProgramText.withinLimit(text)) {
			LOGGER.error("{} 的导入结果超过文本上限（{} 字节 > {}），输出槽保持空 —— 输入那张空白表已经消耗",
				worldPosition, ProgramText.utf8Length(text), ProgramText.MAX_BYTES);
			return ChunkResult.TOO_LARGE;
		}
		HolderLookup.Provider registries = level == null ? null : level.registryAccess();
		if (registries == null) {
			LOGGER.error("{} 的导入结果没有 registry（方块实体不在世界里？），输出槽保持空", worldPosition);
			return ChunkResult.WRITE_FAILED;
		}
		var compiled = ScriptCompiler.compile(text, registries);
		if (compiled.hasErrors()) {
			List<String> lines = ScriptCompiler.errorLines(compiled.errors());
			LOGGER.warn("脚本《{}》编译不过（{} 处），拒绝写卡：{}", file, lines.size(),
				lines.isEmpty() ? "?" : lines.get(0));
			return ChunkResult.DOES_NOT_COMPILE;
		}

		ItemStack out = new ItemStack(SequencedArmRegistration.OPERATION_TABLE.get());
		CardProgramStorage.StoreResult stored = CardProgramStorage.writeText(out, text);
		if (stored != CardProgramStorage.StoreResult.OK) {
			LOGGER.error("{} 的导入结果无法写入操作表（{}，{} 字节），输出槽保持空 —— 输入那张空白表已经消耗",
				worldPosition, stored, text.length());
			return ChunkResult.WRITE_FAILED;
		}
		inventory.setStackInSlot(SLOT_OUTPUT, out);
		LOGGER.info("{} 完成导入《{}》（{} 字节）", worldPosition, file, text.length());
		return ChunkResult.COMPLETED;
	}

	/**
	 * 丢弃当前会话并**留一条日志**（超时 / 人走了 / 乱序 / 超量 / 调用方主动取消走这里）。
	 */
	public void abortUpload(String reason) {
		if (isImporting)
			LOGGER.info("{} 的上传被中止：{}（已收 {}/{} 字节）", worldPosition, reason, receivedBytes, expectedTotal);
		resetSession();
	}

	/**
	 * 把会话状态清干净（**幂等**：收满那条路进来第一件事就是它，而包那一侧在它之后还会再读状态
	 * 决定怎么说 —— 重复调用不该产生第二条日志或第二次标脏）。
	 *
	 * <p>与 {@link #abortUpload} 分开只为了一件事：**成功的收尾不该在日志里写"被中止"**。
	 * 进度归零是刻意的：界面那边"完成"的判据是输出槽里有东西，不是进度到 1
	 * （进度留着 1 会让下一张空表的进度条从满格开始）。
	 */
	private void resetSession() {
		isImporting = false;
		importingFile = null;
		uploader = null;
		expectedTotal = 0;
		receivedBytes = 0;
		nextSeq = 0;
		idleTicks = 0;
		buffer = null;
		uploadReceived = 0;
		uploadTotal = 0;
		sendUpdate = true;
		setChanged();
	}

	/**
	 * 进度（0..1）= **已收字节 / 总字节**（用户口径：进度条改成字节驱动）。
	 *
	 * <p>抽成一个方法而不是让界面自己算：界面里那个平滑追赶的动画与这行字必须用同一个数，
	 * 两处各算一遍就会出现"字已经 60/60 了、进度条还在 80%"这种自相矛盾的画面。
	 * {@code total <= 0} 一律回 0（没有会话 / 会话已清）。
	 */
	public float uploadProgress() {
		return uploadTotal <= 0 ? 0f : Mth.clamp(uploadReceived / (float) uploadTotal, 0f, 1f);
	}

	/** 吃掉 UTF-8 BOM。服务端也要做：**过网的字节不能假定客户端已经清理过**（改过的客户端可以直接发）。 */
	private static String stripBom(String text) {
		return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
	}

	// ————————————————————————— 存 / 读 —————————————————————————

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		tag.put(KEY_INVENTORY, inventory.serializeNBT(registries));
		super.write(tag, registries, clientPacket);
		// ★ 只在**同步包**那一份里写进度（照上游）：存档里毫无意义（读回来一定已经过期），
		//   而且它是包体、不是数据。同理客户端看不到分片缓冲。
		if (clientPacket && isImporting) {
			tag.putBoolean(KEY_IMPORTING, true);
			tag.putString(KEY_FILE, importingFile == null ? "" : importingFile);
			tag.putInt(KEY_RECEIVED, uploadReceived);
			tag.putInt(KEY_TOTAL, uploadTotal);
		}
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		inventory.deserializeNBT(registries, tag.getCompound(KEY_INVENTORY));
		super.read(tag, registries, clientPacket);
		if (!clientPacket)
			return;
		// 缺键 = 没在上传（服务端就是按"不写"来表达这件事的），所以这里判 getBoolean 而不是读一个假值
		boolean importing = tag.getBoolean(KEY_IMPORTING);
		isImporting = importing;
		importingFile = importing ? tag.getString(KEY_FILE) : null;
		uploadReceived = importing ? tag.getInt(KEY_RECEIVED) : 0;
		uploadTotal = importing ? tag.getInt(KEY_TOTAL) : 0;
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
	 * 这里仍然显式写一遍 —— 它是菜单是否继续有效的判据，值得在类里看得见；{@link #tick()} 里
	 * "上传者还在不在"那条检查也复用它。
	 */
	@Override
	public boolean canPlayerUse(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this)
			return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
			worldPosition.getZ() + 0.5D) <= 64.0D;
	}

	/**
	 * 从"玩家当前打开的菜单"反查这台机器 —— 两个 C2S 包（开上传 / 收片）共用的**唯一**权威入口。
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
