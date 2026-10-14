package io.github.Ling.create_sequenced_mechanical_arm.program.script;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;

import net.minecraft.core.HolderLookup;

/**
 * 脚本语言的对外门面（PLAN.md §12.5 的 P1）：文本 ↔ {@link ArmProgram}。
 *
 * <p>为什么要有这一层：编译与反编译是**一对**，而调用方（命令入口、自检、以后的 P1.5 数据包扫描）
 * 只应该看见"进 / 出"两个方向，不该知道里面分了词法器 / 语法 / 规范形式几个类 ——
 * 拆了两个类之后，某处只用了编译却忘了用同一个上限口径（决策 68/85 的老病）就是从这里开始的。
 */
public final class ScriptCompiler {

	private ScriptCompiler() {
	}

	/**
	 * 编译 {@code .sma} 文本。**先看 {@link ScriptResult#hasErrors()}，再看值**
	 * —— 有错时值**也可能有内容**（"已经解析到哪算哪"，§17.10），命令入口照旧必须先看错误再决定要不要写卡。
	 */
	public static ScriptResult<ArmProgram> compile(String text, HolderLookup.Provider registries) {
		return ScriptParser.parse(text, registries);
	}

	/** 把模型写回**规范形式**的文本（§12.4 不变量 #2 的那个"规范形式"）。 */
	public static String decompile(ArmProgram program, HolderLookup.Provider registries) {
		return ScriptDecompiler.decompile(program, registries);
	}

	/**
	 * {@code canonical(text)}：先把文本编译再反编译。
	 *
	 * <p>不变量的写法就是它 —— {@code decompile(compile(text)) == canonical(text)} 与
	 * {@code decompile(compile(canonical(text))) == canonical(text)} 是同一件事。
	 * 文本编译不过时返回 {@code null}（没有"规范形式"可谈）。
	 */
	@Nullable
	public static String canonical(String text, HolderLookup.Provider registries) {
		ScriptResult<ArmProgram> compiled = compile(text, registries);
		if (compiled.hasErrors())
			return null;
		return decompile(compiled.value(), registries);
	}

	/**
	 * 把编译错误整理成可打印的行（**已经带行号、已按行排好**）。
	 *
	 * <p>命令入口与自检共用它：错误格式写两份必然漂移，而"带行号"正是用户点名要的净收益（§12.3）。
	 */
	public static List<String> errorLines(List<ScriptError> errors) {
		List<ScriptError> sorted = new ArrayList<>(errors);
		sorted.sort(java.util.Comparator.comparingInt(ScriptError::line));
		List<String> out = new ArrayList<>(sorted.size());
		for (ScriptError error : sorted)
			out.add(error.toString());
		return out;
	}

	/** 一行摘要：{@code 2 个任务 / 5 条操作}（命令反馈与自检都用它，口径只有一处）。 */
	public static String summarize(ArmProgram program) {
		int entries = program.tasks.stream()
			.mapToInt(task -> task.entries.size())
			.sum();
		return program.tasks.size() + " 个任务 / " + entries + " 条操作";
	}

}
