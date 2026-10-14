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

	/**
	 * 编辑器"视口"状态：模式（{@code RECORD}/{@code EDIT}）与正在编辑的任务下标（§17.9）。
	 *
	 * <p>**为什么与文本并排、而不写进文本**：这两个字段说的是"玩家怎么看这份程序"，不是程序本身。
	 * 写进 `.sma` 的话，`/sma code import` 一份别人的脚本就会顺手改掉你的模式；反过来你在文本里手写
	 * `mode=EDIT` 也毫无意义。§17 之前它们寄生在卡里的**程序 NBT** 上（{@code emode}/{@code etask}），
	 * 换成文本存储后那里没位置了 —— 少了这一处，症状正是用户报的
	 * "切换物品又回到录入模式 / 切完任务一录入就跳回任务一"。
	 */
	private static final String KEY_MODE = "ArmProgramMode";
	private static final String KEY_TASK = "ArmProgramTaskIndex";

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
		return textOf(rootTag(stack));
	}

	/** 文本 → 程序（编译一次），并把卡上的编辑状态装回去。空卡 / 编译不过 ⇒ 空程序。 */
	public static ArmProgram read(ItemStack stack, HolderLookup.Provider registries) {
		CompoundTag root = rootTag(stack);
		ArmProgram program = ProgramText.fromText(textOf(root), registries);
		if (program == null)
			program = new ArmProgram();
		// 编辑状态总是从卡上取（缺键 = 新卡 / 旧卡 ⇒ 默认录入模式 + 第 1 个任务），
		// 且**必须在程序读完之后**夹下标（任务数才知道）
		program.editorMode = root.contains(KEY_MODE) ? root.getString(KEY_MODE) : ArmProgram.MODE_RECORD;
		program.editorTask = Math.max(0, root.getInt(KEY_TASK));
		program.clampEditorTask();
		return program;
	}

	/** 程序 → 文本 + 编辑状态 → 落盘。**失败时一个字节都不写**（不覆盖原有数据）。 */
	public static StoreResult write(ItemStack stack, ArmProgram program, HolderLookup.Provider registries) {
		String text = ProgramText.toText(program, registries);
		if (text == null)
			return StoreResult.NOT_ENCODABLE;
		if (!ProgramText.withinLimit(text))
			return StoreResult.TOO_LARGE;
		CompoundTag root = rootForWrite(stack);
		root.putString(KEY_TEXT, text);
		root.putString(KEY_MODE, program.editorMode);
		root.putInt(KEY_TASK, program.editorTask);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return StoreResult.OK;
	}

	/**
	 * 直接落盘一段文本（`/sma code import` 走这里：保住作者的排版与注释）。
	 *
	 * <p><b>刻意不动编辑状态</b>：导入一份脚本不该把玩家的模式打回录入。任务下标照旧留在卡上，
	 * 读的时候会按**新程序**的任务数夹住（新脚本可能只有一个任务）。
	 */
	public static StoreResult writeText(ItemStack stack, String text) {
		if (!ProgramText.withinLimit(text))
			return StoreResult.TOO_LARGE;
		CompoundTag root = rootForWrite(stack);
		root.putString(KEY_TEXT, text);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return StoreResult.OK;
	}

	/** 取一份可写的 CUSTOM_DATA 根标签（保留卡上别的东西，顺手清掉旧格式键）。 */
	private static CompoundTag rootForWrite(ItemStack stack) {
		CompoundTag root = rootTag(stack);
		root.remove(KEY_LEGACY);
		return root;
	}

	/** 卡上的 CUSTOM_DATA 根标签（没有就给一份空的；**调用方可以改**）。 */
	private static CompoundTag rootTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? new CompoundTag() : data.copyTag();
	}

	/** 卡上的程序文本；没有（或只有旧格式）⇒ {@code null}。 */
	@Nullable
	private static String textOf(CompoundTag root) {
		String text = root.getString(KEY_TEXT);
		if (text.isEmpty()) {
			if (root.contains(KEY_LEGACY))
				warnLegacyDropped();
			return null;
		}
		return text;
	}

	public static void clear(ItemStack stack) {
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		if (existing == null)
			return;
		CompoundTag root = existing.copyTag();
		root.remove(KEY_TEXT);
		root.remove(KEY_LEGACY);
		root.remove(KEY_MODE);
		root.remove(KEY_TASK);
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
