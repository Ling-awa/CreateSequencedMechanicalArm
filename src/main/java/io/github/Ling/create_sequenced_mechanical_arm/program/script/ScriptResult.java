package io.github.Ling.create_sequenced_mechanical_arm.program.script;

import java.util.List;

/**
 * 编译 / 反编译的结果：**要么给值、要么给全部错误**（PLAN.md §12.3）。
 *
 * <p>为什么不抛异常：编译失败是<b>玩家的日常输入</b>（代码编辑器就是要让人改错的），
 * 而"一次性列出全部错误"是用户明确要求的行为 —— 抛异常天然只能带一个。
 * 命令入口、自检、以后的 P1.5 数据包扫描都走这一个形状。
 *
 * <p>⚠️ **有错时值也可能有内容**（§17.10 的修法）：模型允许一些编译器不认的状态，
 * 详见 {@link #withErrors}。所以调用方的顺序永远是"先看 {@link #hasErrors()}，再看值"。
 *
 * <p>{@code warnings} 目前只有一类内容："这个组件不影响频率"（§12.2）——
 * 不静默改写、也不报硬错（玩家可能只是想要那个观感）。
 */
public record ScriptResult<T>(T value, List<ScriptError> errors, List<String> warnings) {

	public static <T> ScriptResult<T> ok(T value, List<String> warnings) {
		return new ScriptResult<>(value, List.of(), List.copyOf(warnings));
	}

	/**
	 * 有错 —— **但值照样带回去**（"已经解析到哪算哪"）。
	 *
	 * <p>原来这里是 {@code value = null}，于是"编译有错"等于"内容没了"。而**模型允许编译器不认的状态**：
	 * 最典型的是**悬空的任务名** —— 玩家录了一条「调用 子任务B」，然后删掉或改名了任务 B；
	 * 这在模型里完全合法（运行时会按"跳转找不到任务"走失败出口），但编译期是**硬错**。
	 * 盘上又只有文本 ⇒ 那种程序一读回来就变成**空程序**，玩家的程序当场没了（§17.10 的真实报告）。
	 *
	 * <p>现在的分工：**错误照报**（命令入口看 {@link #hasErrors()} 拒绝写卡），
	 * **内容留着**（存储入口拿它做"带伤装载"，而不是把整份程序丢掉）。
	 */
	public static <T> ScriptResult<T> withErrors(T value, List<ScriptError> errors, List<String> warnings) {
		return new ScriptResult<>(value, List.copyOf(errors), List.copyOf(warnings));
	}

	public boolean hasErrors() {
		return !errors.isEmpty();
	}

	@Override
	public String toString() {
		return hasErrors() ? "失败(" + errors.size() + " 条错误) " + errors : "成功";
	}

}
