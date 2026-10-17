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

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptResult;

import net.minecraft.core.HolderLookup;

/**
 * 「操作导入桌」要读的那份脚本目录：**列名字** + **读出并校验一段可用文本**（唯一实现）。
 *
 * <p><b>为什么单独一个类、而且只跑在服务端</b>（这是整个功能与蓝图桌最大的区别）：
 * Create 的蓝图桌是客户端读 {@code schematics/} 目录再把内容分片上传（{@code ClientSchematicLoader}），
 * 所以在多人游戏里"列表"来自客户端自己的硬盘。我们的脚本目录 {@link ScriptFiles#directory()} 是
 * <b>服务端</b>的世界数据（{@code run/sequenced_arm/scripts/}，专用服务器上就是服端根目录）——
 * 客户端根本没有那个目录。所以列表与文本都只能由服务端读、再经网络包下发/裁决
 * （{@code network/ProgramImportRequestPacket} / {@code ProgramImportListPacket} / {@code ProgramImportStartPacket}）。
 *
 * <p><b>校验顺序就是语义</b>（每一步失败都带一个可翻译的原因，绝不静默）：
 * 名字合法 → 文件存在 → 读得出来 → 不超过 {@link ProgramText#MAX_BYTES} → <b>编译得过</b>。
 * 大小检查放在编译之前：超限的文本编译出来也写不进卡（{@code CardProgramStorage.writeText} 会拒），
 * 白花一次解析；而"编译不过"必须在**开始导入之前**挡掉 —— 导入的产物是一张卡，
 * 卡上只能存文本，存一份编译器不认的文本等于给玩家一张"插进臂里也不生效"的废卡。
 *
 * <p>名字规则与命令 {@code /sma code import}、导出那条路<b>共用</b> {@link ScriptFiles}：
 * 同一个 {@code <名字>.sma} 会在三条路上被摸到，各写一份判据的后果是"导出的文件读不回来"。
 */
public final class ProgramImportFiles {

	private static final Logger LOGGER = LogUtils.getLogger();

	/**
	 * 一次下发的列表上限。
	 *
	 * <p>不是随手取的：这个数同时是 ① 包体上界（约 256 × 33 字节 ≈ 8 KiB，远小于
	 * {@code ProgramText.MAX_BYTES}，也不会触发 NeoForge 的自动分片）与 ② 界面里
	 * {@code SelectionScrollInput} 的规模上限（再多人也翻不过来）。**超出的部分不显示**，
	 * 玩家把文件分到子目录里就能都看到 —— 但导入只认顶层目录（名字里不许带 {@code /}，
	 * 见 {@link ScriptFiles#isValidName}），所以这是刻意的取舍，不是遗漏。
	 */
	public static final int MAX_FILES = 256;

	/** 一次读取请求的结局。{@code OK} 之外都带原因，由调用方翻成一句玩家看得懂的话。 */
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
		/** {@link ScriptCompiler#compile} 报了错：**这份文本不许进卡**（见类注释）。 */
		DOES_NOT_COMPILE
	}

	/**
	 * 一次读取的结果。
	 *
	 * @param text   {@link Problem#OK} 时是被校验过的**原文**（原样写进卡，保住作者的排版与注释）
	 * @param bytes  这个文件的 UTF-8 字节数（{@link Problem#TOO_LARGE} 时给玩家看"差多少"）
	 * @param errors 编译错误条数（{@link Problem#DOES_NOT_COMPILE} 时给玩家看"错了几行"）
	 */
	public record Outcome(Problem problem, @Nullable String text, int bytes, int errors) {
		public boolean ok() {
			return problem == Problem.OK;
		}

		private static Outcome failed(Problem problem) {
			return new Outcome(problem, null, 0, 0);
		}
	}

	private ProgramImportFiles() {
	}

	/**
	 * 目录里**可被导入**的脚本名（不含 {@code .sma}），按名字排序。
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
	 * 读出 {@code <名字>.sma} 并做完全部校验。
	 *
	 * <p>返回的文本是**原文**（不是规范形式）：{@code CardProgramStorage.writeText} 也是原样落盘，
	 * 所以"文件里怎么写、卡上就怎么存"，玩家的注释与排版不会在这一步被重排
	 * （重排只在编辑器里点保存时才发生，那是刻意的）。
	 */
	public static Outcome read(@Nullable String name, HolderLookup.Provider registries) {
		Path file = ScriptFiles.file(name);
		if (file == null)
			return Outcome.failed(Problem.BAD_NAME);
		// isRegularFile 而不是 exists：目录 / 设备文件也叫 exists，而它们的 readString 会抛 IOException，
		// 那会把"玩家点了一个同名文件夹"报成"磁盘读失败"。
		if (!Files.isRegularFile(file))
			return Outcome.failed(Problem.NOT_FOUND);
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException failed) {
			LOGGER.error("读取脚本失败：{}（{}）", file, failed.toString());
			return Outcome.failed(Problem.UNREADABLE);
		}
		int bytes = ProgramText.utf8Length(text);
		if (!ProgramText.withinLimit(text))
			return new Outcome(Problem.TOO_LARGE, null, bytes, 0);

		ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, registries);
		if (compiled.hasErrors()) {
			List<String> lines = ScriptCompiler.errorLines(compiled.errors());
			// 第一行错误进日志（玩家只看到"错了几行"，运维/作者要看具体哪一行时不用再跑一次命令）
			LOGGER.warn("脚本《{}》编译不过（{} 处），拒绝导入：{}", name, lines.size(), lines.isEmpty() ? "?" : lines.get(0));
			return new Outcome(Problem.DOES_NOT_COMPILE, null, bytes, lines.size());
		}
		return new Outcome(Problem.OK, text, bytes, 0);
	}

}
