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
 * "把一条臂里的程序导出成一个脚本文件"这件事的**全部校验与落盘**，一处实现 ——
 * 但它被**劈成两半**，因为文件搬到了玩家本机（用户改口径：照 Create 蓝图那套，
 * 文件在客户端、服务端不存玩家脚本）。
 *
 * <h2>两半分别在哪一侧跑</h2>
 * <ul>
 *   <li>{@link #prepare}：**服务端**。只校验、只把脚本文本准备好，**一个字节都不写盘**
 *       （服务端没有玩家的磁盘）。它被 {@code network/ExportScriptPacket} 驱动；</li>
 *   <li>{@link #writeFile}：**客户端**。拿到服务端回传的文本之后才写本地文件
 *       （被 {@code network/ExportScriptResultPacket} 驱动）。"同名不覆盖"在这里由
 *       {@link StandardOpenOption#CREATE_NEW} 让内核查，而不是先 {@code exists} 再写
 *       （那是 TOCTOU 窗口，而窗口正好是"覆盖掉别人的脚本"）。</li>
 * </ul>
 *
 * <p><b>顺序就是语义</b>（用户口径）：空程序 → 转不成文本 → 超上限 → 名字非法 → 同名 → 写盘。
 * 前四条全在 {@link #prepare} 里、**碰磁盘之前**返回。
 *
 * <p>{@link #write} 是两半合起来的便捷入口（校验 + 写文件）。它**只能**在客户端调；
 * 留着它主要是为了让启动自检 {@code SequencedArmSelfTest.checkOperationRecordExport}
 * 从同一个真实入口驱动这两半（游戏里点一次才知道对不对的代码，等于没验证过）。
 *
 * <p>为什么这次调整能这么干净：上游 {@code SchematicAndQuillHandler:207-233} 也是
 * "客户端先落本地（{@code CreatePaths.SCHEMATICS_DIR}）→ 需要时再上传"，服务端只收上传；
 * 我们这一段恰好是"服务端先把文本回传，客户端落本地"，两边都满足"服务端不存玩家脚本"。
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
		/** 磁盘写失败（权限 / 只读盘 / 磁盘满 / 目录建不出来）。 */
		WRITE_FAILED
	}

	/**
	 * 客户端写盘那一半的结局。
	 *
	 * @param file {@code OK} / {@link Problem#NAME_TAKEN} / {@link Problem#WRITE_FAILED} 时有值，
	 *             其余 {@code null}（那时候连路径都还没算出来）
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

	/**
	 * 服务端校验那一半的结局：**只有文本，没有路径** —— 服务端根本不知道（也不该知道）玩家把它存到哪。
	 *
	 * @param text {@link Problem#OK} 时是完整的脚本文本，其余 {@code null}
	 * @param args 给 lang 键用的参数（超限时是"多少字节 / 上限多少"）
	 */
	public record Prepared(Problem problem, @Nullable String text, Object[] args) {
		public boolean ok() {
			return problem == Problem.OK;
		}
	}

	private static final Object[] NO_ARGS = new Object[0];

	private ArmScriptExport() {
	}

	/**
	 * ★ **服务端**那一半：校验 + 反编译出文本，**不碰磁盘**（服务端不存玩家脚本）。
	 *
	 * <p>之所以服务端还要验一遍名字，而不是只信客户端：名字会进玩家本机的路径，
	 * 而"客户端说合法"在改过的客户端上等于没说。客户端也有一份同样的检查
	 * （{@code client/ExportScriptScreen}），那是为了在关界面前就把红字给出来 ——
	 * 判据只有 {@link ScriptFiles#isValidName} 一处，两边不会各说各话。
	 */
	public static Prepared prepare(@Nullable String name, ArmProgram program, HolderLookup.Provider registries) {
		if (program == null || program.tasks.isEmpty())
			return failed(Problem.EMPTY_PROGRAM);
		// ★ 反编译可能抛（ProgramText 把它转成 null）：宁可不发回去，也不要发半份文本
		String text = ProgramText.toText(program, registries);
		if (text == null)
			return failed(Problem.NOT_ENCODABLE);
		if (!ProgramText.withinLimit(text))
			return new Prepared(Problem.TOO_LARGE, null,
				new Object[] { ProgramText.utf8Length(text), ProgramText.MAX_BYTES });
		if (!ScriptFiles.isValidName(name))
			return failed(Problem.BAD_NAME);
		return new Prepared(Problem.OK, text, NO_ARGS);
	}

	/**
	 * ★ **客户端**那一半：把服务端回传的文本写成
	 * {@code <本机游戏目录>/sequenced_arm/scripts/<名字>.sma}。
	 *
	 * <p>写之前 {@code Files.createDirectories}：新装的客户端里那个目录本来不存在，
	 * 而"因为目录不存在所以导出失败"是最没必要教给玩家的一件事。
	 *
	 * <p>再判一次 {@link ProgramText#withinLimit}：这一半是**落盘前的最后一道门**
	 * （客户端不能相信网络），超限就不写 —— 与卡片落盘同一条上限。
	 */
	public static Outcome writeFile(@Nullable String name, @Nullable String text) {
		if (text == null || text.isEmpty())
			return new Outcome(Problem.EMPTY_PROGRAM, null);
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
		// 成功也留一行日志：玩家问"文件到底写哪去了"时，客户端日志里有完整路径
		// （它现在是**玩家本机**的 run/sequenced_arm/scripts/，不再是服务端那份）。
		LOGGER.info("已导出脚本：{}（{} 字节）", file, ProgramText.utf8Length(text));
		return new Outcome(Problem.OK, file);
	}

	/**
	 * 两半合起来：校验 + 写本地文件。**只能在客户端调**（服务端没有玩家的磁盘）。
	 *
	 * <p>启动自检用它驱动整条路；游戏里则走"服务端 {@link #prepare} → 回传文本 → 客户端
	 * {@link #writeFile}"这条真正的链路。两半各自也被单独断言（尤其"prepare 不写盘"）。
	 */
	public static Outcome write(@Nullable String name, ArmProgram program, HolderLookup.Provider registries) {
		Prepared prepared = prepare(name, program, registries);
		if (!prepared.ok())
			return new Outcome(prepared.problem(), null, prepared.args());
		return writeFile(name, prepared.text());
	}

	private static Prepared failed(Problem problem) {
		return new Prepared(problem, null, NO_ARGS);
	}

}
