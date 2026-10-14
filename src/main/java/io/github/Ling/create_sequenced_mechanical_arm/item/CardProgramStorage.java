package io.github.Ling.create_sequenced_mechanical_arm.item;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ProgramText;

/**
 * 操作表在物品上的存取（PLAN.md §2.4 的"道具化"）。
 *
 * <p><b>盘上只存文本</b>（§17）：{@code CUSTOM_DATA."ArmProgramText"} 是一串 {@code .sma} 文本，
 * 编译结果只在内存里（{@link #read} 时编译一次）。这样卡片与臂方块共用**同一个落盘形式**，
 * 存档可读可改，天花板也不再是"编译后 NBT 的体积"。
 *
 * <p>为什么把格式换掉而**不动方法签名**：全工程有 17 处调用 {@link #read} / {@link #write}，
 * 它们要的都是"给我一份程序 / 把它存下去"，格式是这里的事。签名一改，17 处就得跟着改，
 * 而其中任何一处漏改都会变成"某条路还在按老格式写"——那种 bug 只在读盘时才现形（决策 68/85）。
 *
 * <p><b>旧键不读</b>（用户选的"不兼容"，§17.3）：0.1.0 之前这里存的是 {@code "ArmProgram"}（编译结果）。
 * 现在遇到它只记一条警告并**当空程序**处理；一旦有任何一次 {@link #writeText}，旧键会被顺手清掉。
 */
public final class CardProgramStorage {

	private static final Logger LOGGER = LogUtils.getLogger();

	/** 唯一真相：`.sma` 文本。 */
	private static final String KEY_TEXT = "ArmProgramText";

	/** 旧键（编译结果）。**只用于检测与清理，绝不读取**。 */
	private static final String KEY_LEGACY = "ArmProgram";

	/** 旧格式警告每进程只说一次（否则每次读卡都刷屏）。 */
	private static boolean warnedLegacy;

	/** {@link #write} / {@link #writeText} 的结果 —— 调用方要把失败**说出来**，不能静默丢。 */
	public enum StoreResult {
		/** 落盘成功。 */
		OK,
		/** 文本超过 {@link ProgramText#MAX_BYTES}（真正能放多少条见 §17.4/§17.7）。 */
		TOO_LARGE,
		/** 程序里有无法转成脚本形式的物品栈（反编译那处抛点），一个字节都没写。 */
		NOT_ENCODABLE
	}

	private CardProgramStorage() {
	}

	public static boolean hasProgram(ItemStack stack) {
		return readText(stack) != null;
	}

	/** 卡上的**原文**（导入进来的脚本会原样保留排版；GUI 保存过的则是规范形式）。 */
	@Nullable
	public static String readText(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null)
			return null;
		CompoundTag root = data.copyTag();
		String text = root.getString(KEY_TEXT);
		if (text.isEmpty()) {
			if (root.contains(KEY_LEGACY))
				warnLegacyDropped();
			return null;
		}
		return text;
	}

	/** 文本 → 程序（编译一次）。文本缺失或编译不过 ⇒ 空程序（后者由 {@link ProgramText} 留 WARN）。 */
	public static ArmProgram read(ItemStack stack, HolderLookup.Provider registries) {
		ArmProgram program = ProgramText.fromText(readText(stack), registries);
		return program == null ? new ArmProgram() : program;
	}

	/** 程序 → 文本 → 落盘。**失败时一个字节都不写**（不覆盖原有数据）。 */
	public static StoreResult write(ItemStack stack, ArmProgram program, HolderLookup.Provider registries) {
		String text = ProgramText.toText(program, registries);
		if (text == null)
			return StoreResult.NOT_ENCODABLE;
		return writeText(stack, text);
	}

	/** 直接落盘一段文本（`/sma code import` 走这里：保住作者的排版与注释）。 */
	public static StoreResult writeText(ItemStack stack, String text) {
		if (!ProgramText.withinLimit(text))
			return StoreResult.TOO_LARGE;
		CompoundTag root = new CompoundTag();
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		if (existing != null)
			root = existing.copyTag();
		root.remove(KEY_LEGACY);
		root.putString(KEY_TEXT, text);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return StoreResult.OK;
	}

	public static void clear(ItemStack stack) {
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		if (existing == null)
			return;
		CompoundTag root = existing.copyTag();
		root.remove(KEY_TEXT);
		root.remove(KEY_LEGACY);
		if (root.isEmpty())
			stack.remove(DataComponents.CUSTOM_DATA);
		else
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	private static void warnLegacyDropped() {
		if (warnedLegacy)
			return;
		warnedLegacy = true;
		LOGGER.warn("读到一个旧格式（编译结果）的程序：本版本起盘上只认文本，它被当成空程序。"
			+ "用 /sma code import 重新导入脚本即可（PLAN.md §17.3）。");
	}

}
