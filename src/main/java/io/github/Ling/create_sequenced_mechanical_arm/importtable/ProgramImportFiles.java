package io.github.Ling.create_sequenced_mechanical_arm.importtable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;

/**
 * 脚本目录的**本机读取**：列名字 + 读出原始字节与文本（唯一实现）。
 *
 * <p><b>★ 这个类只在客户端跑，服务端一次都不碰文件</b>（用户口径："没必要动服务端文件"）：
 * 脚本是玩家自己的资产，和 Create 的 {@code schematics/} 一样落在**运行这份客户端的那台机器**上
 * （{@link ScriptFiles#directory()}）。所以"有哪些脚本""这个脚本写了什么"两件事只有客户端知道，
 * 服务端也不该知道 —— 它只在玩家按下确认之后**收下**那串字节（分片上传，见
 * {@link ProgramImportTableBlockEntity}）并做最后的裁决（上限 / 编译 / 写卡）。
 *
 * <p>这与上游的分工完全一致：{@code ClientSchematicLoader} 在客户端读 {@code schematics/}、
 * 一片片发给 {@code ServerSchematicLoader}，服务端只负责拼装与落盘（那边落的是世界数据，
 * 我们这边落的是玩家手上的卡）。
 *
 * <p><b>这里只做"文件层"的三件事</b>：名字合法 → 存在且读得出 → 不超过
 * {@link ProgramText#MAX_BYTES}。**不在这里编译**：编译需要 {@code HolderLookup.Provider}，
 * 而"这份脚本能不能进卡"是服务端的裁决（导入桌）与命令自己的本地预检（{@code /sma code import}）
 * 各管一段，混进这个类只会让两条路的判据各说各话。
 *
 * <p>名字规则与导出那条路（{@link io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.ArmScriptExport}）
 * **共用** {@link ScriptFiles}：同一个 {@code <名字>.sma} 会被"导出 / 列表 / 读取 / 导入"四条路摸到，
 * 各写一份判据的后果是"导出的文件读不回来"。
 *
 * <p>⚠️ 这里**没有任何客户端专属类型**（纯 {@code java.nio}），所以启动自检（服务端进程）也能直接调它来
 * 断言"真文件 → 原样字节"这一段；"只在客户端跑"讲的是**谁该调它**，不是类的可见性或运行环境。
 */
public final class ProgramImportFiles {

	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * 一次列表的上限。
	 *
	 * <p>不是随手取的：这个数同时是 ① 界面里 {@code SelectionScrollInput} 的规模上限
	 * （再多人也翻不过来）与 ② "扫一次目录别把界面卡住"的护栏。**超出的部分不显示**，
	 * 玩家把文件分到子目录里就能都看到 —— 但导入只认顶层目录（名字里不许带 {@code /}，
	 * 见 {@link ScriptFiles#isValidName}），所以这是刻意的取舍，不是遗漏。
	 *
	 * <p>从"服务端下发的列表上限"变成"本地扫描上限"之后这个数的量级不变，
	 * 只是它不再与任何包体挂钩（列表根本不出本机）。
	 */
	public static final int MAX_FILES = 256;

	/** 一次读取的结局。{@code OK} 之外都带原因，由调用方翻成一句玩家看得懂的话。 */
	public enum Problem {
		OK,
		/** 名字不合法（见 {@link ScriptFiles#isValidName}）。 */
		BAD_NAME,
		/** 文件不存在（或不是普通文件）。 */
		NOT_FOUND,
		/** 磁盘读失败（权限 / 编码）。 */
		UNREADABLE,
		/** 文本超过 {@link ProgramText#MAX_BYTES}：与卡片落盘同一条上限。 */
		TOO_LARGE,
		/**
		 * 文件读出来了但是**空的**（只有空行 / 只有 BOM）。
		 *
		 * <p>为什么要单独一条：空文本能过 {@link ProgramText#withinLimit}，但上传上去的
		 * {@code totalBytes} 是 0 —— 一个"永远是 0 片"的上传会让桌子一直等到超时，
		 * 而玩家等 3 秒后只看到"上传被中止"。在**读的这一刻**就说清楚，代价是一条提示。
		 */
		EMPTY
	}

	/**
	 * 一次读取的结果。
	 *
	 * @param text  {@link Problem#OK} 时的**原文**（BOM 已去掉，理由见 {@link #read}）
	 * @param bytes {@link Problem#OK} 时的**原始字节**（同一份 BOM 已去掉的数据），
	 *              导入桌直接把它分片上传 —— 不做 {@code String → byte[]} 的二次编码，
	 *              免得"文件里是坏字节、传上去却变了长度"
	 */
	public record Contents(Problem problem, @Nullable String text, byte[] bytes) {
		public boolean ok() {
			return problem == Problem.OK;
		}

		/** 这份脚本的字节数（{@link Problem#TOO_LARGE} 时给玩家看"超了多少"）。 */
		public int sizeBytes() {
			return bytes.length;
		}

		private static Contents failed(Problem problem) {
			return new Contents(problem, null, EMPTY_BYTES);
		}
	}

	private static final byte[] EMPTY_BYTES = new byte[0];

	private ProgramImportFiles() {
	}

	/**
	 * 本机脚本目录里**可被导入**的脚本名（不含 {@code .sma}），按名字排序。
	 *
	 * <p>三条过滤各有理由：① 只要 {@code .sma}（别的东西不是脚本）；② 名字过一遍
	 * {@link ScriptFiles#isValidName} —— 不在列表里的名字点了也会被 {@link #read} 拒，
	 * 那不如一开始就不显示（否则玩家会看到一个"点了没反应"的条目）；③ **排序**：
	 * 界面用一个整数下标记住"选的是第几个"（{@code SelectionScrollInput.getState()}），
	 * 列表顺序不稳定的话，"选中的那个文件"会在两次刷新之间跳到别的文件上。
	 */
	public static List<String> list() {
		Path dir = ScriptFiles.directory();
		if (!Files.isDirectory(dir))
			return List.of();
		try (Stream<Path> files = Files.list(dir)) {
			List<String> names = new ArrayList<>();
			files.filter(Files::isRegularFile)
				.map(path -> path.getFileName()
					.toString())
				.filter(file -> file.endsWith(ScriptFiles.EXTENSION))
				.map(file -> file.substring(0, file.length() - ScriptFiles.EXTENSION.length()))
				.filter(ScriptFiles::isValidName)
				.sorted()
				.limit(MAX_FILES)
				.forEach(names::add);
			return names;
		} catch (IOException failed) {
			// 读不了目录 ≠ 没有脚本，但对界面来说是同一件事：给它一个空列表，日志里留证据。
			LOGGER.error("列举脚本目录失败：{}（{}）", dir, failed.toString());
			return List.of();
		}
	}

	/**
	 * 读出 {@code <名字>.sma} 的原始字节 + 文本，并做完文件层的三道校验。
	 *
	 * <p><b>BOM 在这里就去掉，而不是各调用方各去一次</b>：项目在 PowerShell 写出的 UTF-8 BOM 上
	 * 栽过一次（BOM 会变成文件第一个字符，让第一行解析不出来）。这个类是所有"读脚本"的**唯一入口**，
	 * 所以处理在这一处；服务端拼完分片之后还会再吃一次 BOM —— 那条路上来的字节是过网的，
	 * 不能假定客户端已经做过（改过的客户端可以直接上传带 BOM 的字节）。
	 *
	 * <p>返回的文本是**原文**（不是规范形式）：{@code CardProgramStorage.writeText} 也是原样落盘，
	 * 所以"文件里怎么写、卡上就怎么存"，玩家的注释与排版不会在这一步被重排
	 * （重排只在编辑器里点保存时才发生，那是刻意的）。
	 */
	public static Contents read(@Nullable String name) {
		Path file = ScriptFiles.file(name);
		if (file == null)
			return Contents.failed(Problem.BAD_NAME);
		// isRegularFile 而不是 exists：目录 / 设备文件也叫 exists，而它们的 readAllBytes 会抛 IOException，
		// 那会把"玩家点了一个同名文件夹"报成"磁盘读失败"。
		if (!Files.isRegularFile(file))
			return Contents.failed(Problem.NOT_FOUND);
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(file);
		} catch (IOException failed) {
			LOGGER.error("读取脚本失败：{}（{}）", file, failed.toString());
			return Contents.failed(Problem.UNREADABLE);
		}
		bytes = stripBom(bytes);
		// 上限判**字节**（盘上存的就是这串字节）：与卡片落盘同一条 ProgramText.MAX_BYTES
		if (bytes.length > ProgramText.MAX_BYTES)
			return new Contents(Problem.TOO_LARGE, null, bytes);
		String text = new String(bytes, StandardCharsets.UTF_8);
		if (text.isBlank())
			return new Contents(Problem.EMPTY, text, bytes);
		return new Contents(Problem.OK, text, bytes);
	}

	/** 吃掉 UTF-8 BOM（EF BB BF）。**认字节**而不是认字符：这一步发生在解码之前。 */
	private static byte[] stripBom(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
			byte[] trimmed = new byte[bytes.length - 3];
			System.arraycopy(bytes, 3, trimmed, 0, trimmed.length);
			return trimmed;
		}
		return bytes;
	}

}
