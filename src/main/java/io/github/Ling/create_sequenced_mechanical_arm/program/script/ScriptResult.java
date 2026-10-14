package io.github.Ling.create_sequenced_mechanical_arm.program.script;

import java.util.List;

/**
 * 编译 / 反编译的结果：**要么给值、要么给全部错误**（PLAN.md §12.3）。
 *
 * <p>为什么不抛异常：编译失败是<b>玩家的日常输入</b>（代码编辑器就是要让人改错的），
 * 而"一次性列出全部错误"是用户明确要求的行为 —— 抛异常天然只能带一个。
 * 命令入口、自检、以后的 P1.5 数据包扫描都走这一个形状。
 *
 * <p>{@code warnings} 目前只有一类内容："这个组件不影响频率"（§12.2）——
 * 不静默改写、也不报硬错（玩家可能只是想要那个观感）。
 */
public record ScriptResult<T>(T value, List<ScriptError> errors, List<String> warnings) {

	public static <T> ScriptResult<T> ok(T value) {
		return new ScriptResult<>(value, List.of(), List.of());
	}

	public static <T> ScriptResult<T> ok(T value, List<String> warnings) {
		return new ScriptResult<>(value, List.of(), List.copyOf(warnings));
	}

	public static <T> ScriptResult<T> fail(List<ScriptError> errors) {
		return new ScriptResult<>(null, List.copyOf(errors), List.of());
	}

	public boolean hasErrors() {
		return !errors.isEmpty();
	}

	@Override
	public String toString() {
		return hasErrors() ? "失败(" + errors.size() + " 条错误) " + errors : "成功";
	}

}
