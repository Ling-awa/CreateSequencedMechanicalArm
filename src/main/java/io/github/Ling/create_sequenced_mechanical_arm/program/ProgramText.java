package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptCompiler;
import io.github.Ling.create_sequenced_mechanical_arm.program.script.ScriptResult;

import net.minecraft.core.HolderLookup;

/**
 * 程序的**落盘形式**：`.sma` 文本（PLAN.md §17）。
 *
 * <p><b>为什么盘上只存文本</b>：文本是唯一真相，编译结果只活在内存里 —— 一次编译，不是每 tick。
 * 好处是存档可读、可 diff、可手工修，而且天花板从"编译后 NBT 的体积"（实测 35〜59 条，见 §15.4）
 * 换成"脚本字节数"（越一个数量级）。**性能不是理由**：解析一条是微秒级。
 *
 * <p><b>{@link #MAX_BYTES} 的出处（不许拍脑袋）</b>：NBT 的字符串字段是 <b>16 位长度</b>
 * —— {@code StringTag.write} 调 {@code DataOutput.writeUTF}，最长 65535 字节 ⇒ 一行文本存不下更多。
 * 这不是网络包的限制：{@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE = 32767} 只管
 * <b>未注册</b>的载荷，NeoForge 对注册载荷有 {@code GenericPacketSplitter}
 * （按 {@code CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH = 8388608} 自动分片）。
 * 留 4KB 余量给 NBT 包装与 UTF-8/UTF-16 口径差异，取 60 KiB。
 * 真实能放多少条由实测给出（自检的 {@code checkProgramSize} 打日志），不在这里写死。
 */
public final class ProgramText {

	private static final Logger LOGGER = LogUtils.getLogger();

	/** 文本字节上限（UTF-8）。依据见类注释：硬约束是 NBT 字符串的 16 位长度 65535。 */
	public static final int MAX_BYTES = 60 * 1024;

	private ProgramText() {
	}

	public static int utf8Length(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	public static boolean withinLimit(String text) {
		return utf8Length(text) <= MAX_BYTES;
	}

	/**
	 * 程序 → 文本（{@link ScriptCompiler#decompile} 的安全版）。
	 *
	 * <p>为什么必须包一层：反编译器里有一处**会抛**（{@code ScriptDecompiler.item} 用
	 * {@code ItemStack.CODEC.encodeStart(...).getOrThrow()} 判"规范形式"）。存储这条路一旦让异常穿出去，
	 * 后果是玩家关界面时**整场编辑白做**还看不到原因。这里转成 {@code null}，由调用方给出明确结果。
	 */
	@Nullable
	public static String toText(ArmProgram program, HolderLookup.Provider registries) {
		try {
			return ScriptCompiler.decompile(program, registries);
		} catch (RuntimeException failed) {
			LOGGER.warn("程序无法转成脚本文本（本次不落盘，原数据保持不动）", failed);
			return null;
		}
	}

	/**
	 * 文本 → 程序（{@link ScriptCompiler#compile} 的安全版）：**有错返回 {@code null}**（不抛）。
	 *
	 * <p>调用方按 {@code null} 当"没有程序"处理；但注意它与"空文本"要分开讲：
	 * 空文本是正常状态（新卡），编译失败是**数据有问题**，这里会留一条 WARN 带行号。
	 */
	@Nullable
	public static ArmProgram fromText(String text, HolderLookup.Provider registries) {
		if (text == null || text.isEmpty())
			return null;
		ScriptResult<ArmProgram> compiled = ScriptCompiler.compile(text, registries);
		if (compiled.hasErrors()) {
			List<String> lines = ScriptCompiler.errorLines(compiled.errors());
			int shown = Math.min(3, lines.size());
			LOGGER.warn("脚本文本编译失败（{} 字节，{} 个错误）：{}", utf8Length(text), lines.size(),
				String.join(" | ", lines.subList(0, shown)));
			return null;
		}
		return compiled.value();
	}

}
