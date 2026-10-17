package io.github.Ling.create_sequenced_mechanical_arm.sequencedarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;
import io.github.Ling.create_sequenced_mechanical_arm.program.ScriptFiles;

import net.minecraft.core.HolderLookup;

/**
 * "把一条臂里的程序导出成一个脚本文件"这件事的**全部校验与落盘**，一处实现。
 *
 * <p>为什么单独一个类、而且不碰玩家 / 方块实体：这是本项目**唯一**会在玩家手势下写磁盘的路径
 * （{@code /sma code export} 也能写，但它是 OP 命令）。把"哪些情况拒绝"集中在一处，
 * 才能同时被网络包（{@code network/ExportScriptPacket}）与**启动自检**
 * （{@code SequencedArmSelfTest.checkOperationRecordExport}）驱动 ——
 * 游戏里点一次才知道对不对的代码，等于没验证过。
 *
 * <p><b>顺序就是语义</b>（用户口径）：空程序 → 转不成文本 → 超上限 → 名字非法 → 同名 →
 * 写盘。前四条都在**碰磁盘之前**返回，最后那个"同名"由 {@link StandardOpenOption#CREATE_NEW}
 * 让内核来判（先 {@code exists} 再写是 TOCTOU 窗口，而且那个窗口正好是"覆盖掉别人的脚本"）。
 */
public final class ArmScriptExport {

	private static final Logger LOGGER = LogUtils.getLogger();

	/** 一条导出请求的结局（{@code OK} 之外全部**带原因**，由调用方翻成玩家看得懂的句子）。 */
	public enum Problem {
		OK,
		/** 臂里没有程序 —— 没东西可导。 */
		EMPTY_PROGRAM,
		/** {@link ProgramText#toText} 反编译抛了（有物品写不进脚本）：不落盘，原数据不动。 */
		NOT_ENCODABLE,
		/** 文本超过 {@link ProgramText#MAX_BYTES}：与卡片落盘同一条上限。 */
		TOO_LARGE,
		/** 文件名不合法（见 {@link ScriptFiles#isValidName}）。 */
		BAD_NAME,
		/** 同名文件已存在：**拒绝覆盖**（与"编辑器拒收超限程序"同一条基调）。 */
		NAME_TAKEN,
		/** 磁盘写失败（权限 / 磁盘满）。 */
		WRITE_FAILED
	}

	/**
	 * @param file 目标文件（{@code OK} / {@link Problem#NAME_TAKEN} / {@link Problem#WRITE_FAILED} 时有值，
	 *             其余 {@code null} —— 那时候连路径都还没算出来）
	 * @param args 给 lang 键用的参数（字节数 / 上限 / 异常信息…），顺序由调用方决定
	 */
	public record Outcome(Problem problem, @Nullable Path file, Object[] args) {
		public boolean ok() {
			return problem == Problem.OK;
		}

		/** 没有参数的结局（大多数）。 */
		public Outcome(Problem problem, @Nullable Path file) {
			this(problem, file, NO_ARGS);
		}
	}

	private static final Object[] NO_ARGS = new Object[0];

	private ArmScriptExport() {
	}

	/**
	 * 把 {@code program} 写成 {@code <游戏根目录>/sequenced_arm/scripts/<name>.sma}。
	 *
	 * <p>写之前 {@code Files.createDirectories}：新装的世界里那个目录本来不存在，
	 * 而"因为目录不存在所以导出失败"是最没必要教给玩家的一件事。
	 */
	public static Outcome write(@Nullable String name, ArmProgram program, HolderLookup.Provider registries) {
		if (program == null || program.tasks.isEmpty())
			return new Outcome(Problem.EMPTY_PROGRAM, null);
		// ★ 反编译可能抛（ProgramText 把它转成 null）：宁可不落盘，也不要写出半份文件
		String text = ProgramText.toText(program, registries);
		if (text == null)
			return new Outcome(Problem.NOT_ENCODABLE, null);
		if (!ProgramText.withinLimit(text))
			return new Outcome(Problem.TOO_LARGE, null,
				new Object[] { ProgramText.utf8Length(text), ProgramText.MAX_BYTES });
		Path file = ScriptFiles.file(name);
		if (file == null)
			return new Outcome(Problem.BAD_NAME, null);
		try {
			Files.createDirectories(file.getParent());
			// CREATE_NEW = "存在就抛"，这正是"拒绝覆盖"；UTF-8 无 BOM，与 /sma code export 同一口径
			Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
		} catch (FileAlreadyExistsException taken) {
			return new Outcome(Problem.NAME_TAKEN, file);
		} catch (IOException failed) {
			LOGGER.error("导出脚本失败：{}（{}）", file, failed.toString());
			return new Outcome(Problem.WRITE_FAILED, file, new Object[] { String.valueOf(failed.getMessage()) });
		}
		// 成功也留一行日志：玩家问"文件到底写哪去了"时，服务端日志里有完整路径
		// （新目录是 run/sequenced_arm/scripts/，与旧的 config/…/scripts 不是一回事）。
		LOGGER.info("已导出脚本：{}（{} 字节）", file, ProgramText.utf8Length(text));
		return new Outcome(Problem.OK, file);
	}

}
