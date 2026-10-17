package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.nio.file.Path;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 脚本文本文件的**目录与名字规则**（唯一来源）。
 *
 * <p><b>为什么必须只有一处</b>：同一个 {@code <名字>.sma} 会被几条路摸到 ——
 * 记录表的"上传"（导出成文件）、操作导入桌的列表/读取/分片上传、打开脚本文件夹，以及
 * 客户端的快捷命令 {@code /sma code import|export|check}。各写一份 {@code resolve(...)} 的后果是本项目栽过的
 * 那种"导出的文件读不回来"：文件真的写下去了，只是写在另一个目录里，不报任何错。
 * 所以目录常量、名字判据、扩展名全在这里，其它地方只许调 {@link #directory()} / {@link #file(String)}。
 *
 * <p><b>★ 文件属于「玩家本机」（用户口径，照 Create 的蓝图那套）</b>：脚本是玩家自己的资产，
 * 和 Create 的 {@code schematics/} 一样落在**运行这份客户端的那台机器**上
 * （{@code CreatePaths.GAME_DIR = FMLPaths.GAMEDIR.get()}，见上游 {@code CreatePaths:9-13}）。
 * {@link #directory()} 解析的是"**当前这一侧**的游戏目录"。
 *
 * <p><b>★ 第二步（用户："没必要动服务端文件"）：<b>只有客户端会调这里的任何方法</b></b>。
 * 玩家链路（记录表导出写盘、导入桌的列表与读取、打开文件夹、客户端命令）全在客户端执行；
 * 服务端那边**一个脚本文件都不读、不写、不列举**，它只经网络包**收下**客户端上传的字节
 * （与 {@code ClientSchematicLoader} 逐片发、{@code ServerSchematicLoader} 拼装是同一条分工）。
 * 因此 {@code ScriptFiles} 里不再有"服务端专用"的入口，也没有管理员后门了 ——
 * 那些命令已经搬到客户端（{@code SequencedArmClientCommands}），读写的是玩家本机这一份。
 *
 * <p><b>目录为什么搬出 {@code config/}</b>（用户口径）：脚本文本是**世界数据**（属于这个存档的臂），
 * 不是配置 —— 放在 {@code config/create_sequenced_mechanical_arm/scripts/} 里既会被"改配置"的直觉误导，
 * 也不随存档走。位置是**游戏根目录**下的 {@code run/sequenced_arm/scripts/}。
 */
public final class ScriptFiles {

	/** 相对游戏根目录的脚本目录（{@code run/sequenced_arm/scripts/}）。日志与断言都念这一串。 */
	public static final String DIRECTORY = "sequenced_arm/scripts";

	/** 扩展名（PLAN.md §17：盘上存的就是这串文本，所以后缀与语言同名）。 */
	public static final String EXTENSION = ".sma";

	/**
	 * 文件名长度上限（字符，不是字节）。
	 *
	 * <p>32 不是随手取的：它必须装得下界面上那个 {@code EditBox}（同一条上限喂给它，
	 * 见 {@code client/ExportScriptScreen}），也要给"臂坐标默认名"（{@code arm_12_-60_3}）留足余量；
	 * 同时挡住"把一整段中文当文件名"这种会把路径长度顶爆的输入。
	 */
	public static final int MAX_NAME = 32;

	/**
	 * 合法的文件名：{@code [A-Za-z0-9_\-\u4e00-\u9fa5]{1,32}}。
	 *
	 * <p>为什么**白名单**而不是"过滤掉 {@code ../}"：这个字符串会被直接拼进路径，
	 * 黑名单永远漏（{@code ..\}、{@code .../}、{@code a\\b}、NTFS 的交替数据流 {@code a:b}…）。
	 * 白名单里连点号都没有 ⇒ 不可能出现 {@code ..}、也不可能跑出脚本目录。
	 * 中文放行是用户口径（"文件名可以用中文"）；客户端命令 {@code /sma code …} 也吃同一份规则，
	 * 否则记录表导出 {@code 炒菜.sma} 之后 {@code /sma code import 炒菜} 会说不认识这个名字。
	 */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1," + MAX_NAME + "}");

	private ScriptFiles() {
	}

	/**
	 * 脚本目录：**当前这一侧**游戏根目录（{@code run/}）下的 {@code sequenced_arm/scripts}。
	 *
	 * <p>{@code FMLPaths.GAMEDIR} 而不是 {@code CONFIGDIR}：见类注释（文本是世界数据，不是配置）。
	 *
	 * <p>★ 第二步起**只有客户端会调它**：脚本是玩家本机的资产，服务端不读、不写、不列举任何脚本文件
	 * （用户："没必要动服务端文件"）。专用服务器上这串路径即使被解析出来也**没有任何调用方** ——
	 * 别再加服务端的调用点（那正是这一步删掉的东西）。
	 */
	public static Path directory() {
		return FMLPaths.GAMEDIR.get()
			.resolve("sequenced_arm")
			.resolve("scripts");
	}

	/** 名字是否合法（见 {@link #NAME}）。{@code null} / 空串一律不合法。 */
	public static boolean isValidName(@Nullable String name) {
		return name != null && NAME.matcher(name)
			.matches();
	}

	/** {@code <名字>.sma} 的完整路径；名字不合法时返回 {@code null}（**不要**替调用方兜底成某个默认名）。 */
	@Nullable
	public static Path file(@Nullable String name) {
		return isValidName(name) ? directory().resolve(name + EXTENSION) : null;
	}

}
