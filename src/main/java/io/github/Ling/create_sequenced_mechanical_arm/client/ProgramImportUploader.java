package io.github.Ling.create_sequenced_mechanical_arm.client;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.importtable.ProgramImportTableBlockEntity;
import io.github.Ling.create_sequenced_mechanical_arm.network.ProgramImportChunkPacket;
import io.github.Ling.create_sequenced_mechanical_arm.network.ProgramImportUploadStartPacket;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 「把本机一个 {@code .sma} 分片传给服务端」这件事的**客户端发送端**（照上游
 * {@code ClientSchematicLoader}）：先发一句开场白，然后一片 /
 * {@link ProgramImportTableBlockEntity#CHUNK_INTERVAL_TICKS} tick 地发。
 *
 * <h2>为什么发送端是一个 tick 驱动的静态队列，而不是写在界面里</h2>
 * 上游那条链的毛病是"界面一关就不再发包"（进度只活在 {@code SchematicTableScreen} 的
 * {@code containerTick} 里）：玩家手贱关一下界面，服务端就永远等在半路。这里的发送端挂在
 * {@link ClientTickEvent.Post} 上，**与界面在不在无关** —— 关掉界面，剩下几片照样发完，
 * 服务端那边是一次完整的上传，不是一次半途而废。
 *
 * <p>服务端也不再依赖"界面还开着"来推进度：它自己每 tick 问一句"这个人还开着这台机器吗"，
 * 并有一个静默超时兜底（见 {@link ProgramImportTableBlockEntity#tick()}）。
 *
 * <h2>节奏与总量</h2>
 * <ul>
 *   <li>一片 {@link ProgramImportTableBlockEntity#CHUNK_BYTES}（8 KiB）；</li>
 *   <li>两片之间 {@link ProgramImportTableBlockEntity#CHUNK_INTERVAL_TICKS} tick
 *       （= 上游 {@code ClientSchematicLoader.PACKET_DELAY = 10}）；</li>
 *   <li>总量上限 {@code ProgramText.MAX_BYTES}（60 KiB）⇒ 最多
 *       {@link ProgramImportTableBlockEntity#MAX_CHUNKS} 片（60 KiB ÷ 8 KiB = 8 片），
 *       每片隔 {@link ProgramImportTableBlockEntity#CHUNK_INTERVAL_TICKS} tick（10）——
 *       两数相乘 = <b>80 tick ≈ 4 秒</b>（这就是"≈4 秒"的全部算法，没有别的常数）。</li>
 * </ul>
 *
 * <h2>什么时候自己收工</h2>
 * <ul>
 *   <li>最后一片发完 ⇒ 队列清空（**不**等服务端回执：回执是"导入成不成"，与"发没发完"是两件事）；</li>
 *   <li>换了连接（退出世界 / 换服务器）⇒ 立刻丢弃。**这条必须要**：否则"在 A 服按下导入、
 *       退回主菜单、进 B 服"会让剩下的片发到 B 服去，而 B 服那边只会看到一个没有会话的坏包；</li>
 *   <li>服务端中止了（超时 / 乱序 / 超量）⇒ 这里**察觉不到**（服务端不发 S2C），
 *       最多多发完手里那几片；服务端对"没有会话的片"静默丢弃，不会刷屏。
 *       为省一个 S2C 包而接受最多 3 秒的无效发包，是这一版刻意的取舍。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, value = Dist.CLIENT)
public final class ProgramImportUploader {

	private static final Logger LOGGER = LogUtils.getLogger();

	/** 同一时刻只允许一次上传（一张桌子一次会话；界面也只有一个）。 */
	@Nullable
	private static Upload active;

	/**
	 * 一次上传的发送状态。
	 *
	 * <p>{@code delay} 初值就是片间隔：开场白之后**先等一个间隔**再发第一片 ——
	 * 这样"最多 {@code MAX_CHUNKS} 片"正好对应 {@code MAX_CHUNKS × 10 tick = 4 秒}，
	 * 而不是"第一片免费、总共 3.5 秒"（后者会让文档里那句"≈4 秒"变成假的）。
	 */
	private static final class Upload {
		private final String name;
		private final byte[] data;
		private final int totalBytes;
		private final int chunks;
		/** 开传那一刻的连接：换了连接就说明这次上传已经不可能送到原来那台桌子上了。 */
		private final ClientPacketListener connection;
		private int nextSeq;
		private int delay = ProgramImportTableBlockEntity.CHUNK_INTERVAL_TICKS;

		private Upload(String name, byte[] data, ClientPacketListener connection) {
			this.name = name;
			this.data = data;
			this.totalBytes = data.length;
			this.chunks = (data.length + ProgramImportTableBlockEntity.CHUNK_BYTES - 1)
				/ ProgramImportTableBlockEntity.CHUNK_BYTES;
			this.connection = connection;
		}

		/** 第 {@code seq} 片的字节（最后一片可能不满）。越界返回 {@code null}（不该发生，留一道硬护栏）。 */
		@Nullable
		private byte[] chunk(int seq) {
			int from = seq * ProgramImportTableBlockEntity.CHUNK_BYTES;
			if (from < 0 || from >= totalBytes)
				return null;
			int to = Math.min(from + ProgramImportTableBlockEntity.CHUNK_BYTES, totalBytes);
			return Arrays.copyOfRange(data, from, to);
		}
	}

	private ProgramImportUploader() {
	}

	/**
	 * 开始一次上传：立刻发开场白（文件名 + 总字节），随后由 {@link #onClientTick} 分片。
	 *
	 * @return {@code false} = 没开始（已有一段在上传 / 空脚本 / 不在世界里）
	 */
	public static boolean start(String name, byte[] data) {
		if (active != null) {
			LOGGER.warn("已经有一次上传在跑（《{}》），忽略这一次（《{}》）", active.name, name);
			return false;
		}
		ClientPacketListener connection = Minecraft.getInstance()
			.getConnection();
		if (connection == null || data.length <= 0 || data.length > ProgramText.MAX_BYTES)
			return false;
		active = new Upload(name, data, connection);
		PacketDistributor.sendToServer(new ProgramImportUploadStartPacket(name, data.length));
		LOGGER.info("开始上传《{}》：{} 字节 / {} 片", name, data.length, active.chunks);
		return true;
	}

	/** 主动放弃（目前没有界面入口用它，留给"取消"按钮与调试）。 */
	public static void cancel() {
		if (active != null)
			LOGGER.info("放弃上传《{}》（已发 {}/{} 片）", active.name, active.nextSeq, active.chunks);
		active = null;
	}

	/**
	 * 每 tick 推进一步：等到点就发一片，发完就收工。
	 *
	 * <p>为什么用 {@link ClientTickEvent.Post} 而不是 {@code Pre}：发包与界面重绘都在这一 tick 里跑，
	 * Post 保证"这一 tick 的世界状态已经稳定"，也与本仓 {@code OperationRecordHandler} 的写法一致。
	 */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (active == null)
			return;
		// 换了连接（退世界 / 换服）⇒ 这次上传已经不可能送到原来那台桌子上了
		if (Minecraft.getInstance()
			.getConnection() != active.connection) {
			cancel();
			return;
		}
		if (--active.delay > 0)
			return;
		byte[] chunk = active.chunk(active.nextSeq);
		if (chunk == null) {
			// 不该发生（chunks 是算出来的）；真发生了说明状态坏了，收工而不是继续发
			LOGGER.error("上传《{}》的第 {} 片算不出来，放弃", active.name, active.nextSeq);
			cancel();
			return;
		}
		PacketDistributor.sendToServer(
			new ProgramImportChunkPacket(active.nextSeq, active.totalBytes, chunk));
		active.nextSeq++;
		active.delay = ProgramImportTableBlockEntity.CHUNK_INTERVAL_TICKS;
		if (active.nextSeq >= active.chunks) {
			LOGGER.info("上传《{}》的 {} 片已经发完（等服务器裁决）", active.name, active.chunks);
			active = null;
		}
	}

}
