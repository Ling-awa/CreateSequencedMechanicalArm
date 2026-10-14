package io.github.Ling.create_sequenced_mechanical_arm.program.script;

/**
 * 脚本的一条编译错误（PLAN.md §12.3：**带行号**，且一次性把全部错误列出来）。
 *
 * <p>为什么把"行号"做成字段而不是拼进消息里：命令 {@code /sma code check} 要按行排序输出，
 * 自检要断言"这一行报了错"——只有结构化的行号才能断言；拼进字符串的话，
 * 以后想改格式就得改所有断言（本项目已经吃过"判据写两份"的亏，决策 68/85）。
 */
public record ScriptError(int line, String message) {

	/** 日志 / 聊天栏用的一行：{@code 第 3 行: unknown task: cook_wok}。 */
	@Override
	public String toString() {
		return "第 " + line + " 行: " + message;
	}

}
