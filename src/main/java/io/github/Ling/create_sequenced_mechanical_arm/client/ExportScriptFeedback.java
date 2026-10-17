package io.github.Ling.create_sequenced_mechanical_arm.client;

import java.nio.file.Path;

import org.jetbrains.annotations.Nullable;

import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.ArmScriptExport;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 「服务端回传了脚本文本」之后的**客户端收尾**：写本机文件 + 一条 actionbar
 * （{@code network/ExportScriptResultPacket} 的唯一目的地）。
 *
 * <p>为什么单独一个客户端类、而不是写在网络包里：这里要摸 {@code Minecraft.getInstance().player}
 * 与"给玩家看的一句话"，两件都是客户端专属的事。放进包类的 {@code handle} 体里虽然也能跑
 * （本仓 {@code ExportScriptResultPacket.handle → ExportScriptFeedback.finish} 就是这个写法），
 * 但"写盘 + 提示"本身与网络无关，单独放一处才便于对照 {@link ArmScriptExport} 的两半。
 *
 * <h2>三种失败各有自己的话，绝不静默</h2>
 * <ul>
 *   <li><b>同名已存在</b> ⇒ 拒绝并提示（{@code record.exists}）—— 与"编辑器拒收超限程序"
 *       同一条基调，绝不覆盖别人的脚本；</li>
 *   <li><b>写不进去</b>（权限 / 只读盘 / 磁盘满 / 目录建不出来）⇒ {@code record.write_failed}，带异常信息，
 *       同时在客户端日志里留一条 ERROR（由 {@link ArmScriptExport#writeFile} 打出，带完整路径）；</li>
 *   <li><b>名字不合法</b> ⇒ {@code record.bad_name}（服务端已经拦过一道，这里是最后一道门）。</li>
 * </ul>
 *
 * <p>成功那一条与本体（Create 蓝图与笔）同口径：**只显示文件名**
 * （{@code SchematicAndQuillHandler:220} 传的就是 {@code file.getFileName().toString()}），
 * 而且传的是 {@code String} 而不是 {@link Path} —— {@code TranslatableContents} 的构造器在
 * 非生产环境只接受 {@code Component / Number / Boolean / String}，塞别的会当场抛
 * {@link IllegalArgumentException}（症状是"导出成功但屏幕上什么都没有"，这一版踩过）。
 */
public final class ExportScriptFeedback {

	private ExportScriptFeedback() {
	}

	/**
	 * 写本机文件并回执。只在客户端调用（{@code playToClient} 的处理器）。
	 *
	 * <p>玩家为 {@code null}（极少：包在退世界那一 tick 到达）时**仍然照写文件**：
	 * 文本是玩家自己刚要的，丢掉它才是坏事；只是没法给他看那条提示。
	 */
	public static void finish(@Nullable String name, @Nullable String text) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ArmScriptExport.Outcome outcome = ArmScriptExport.writeFile(name, text);
		if (player == null)
			return;
		switch (outcome.problem()) {
			case OK -> feedback(player, "record.written", ChatFormatting.GREEN, fileName(outcome.file()));
			case NAME_TAKEN -> feedback(player, "record.exists", ChatFormatting.RED, fileName(outcome.file()));
			case BAD_NAME -> feedback(player, "record.bad_name", ChatFormatting.RED);
			case WRITE_FAILED -> feedback(player, "record.write_failed", ChatFormatting.RED, outcome.args());
			// 下面三种在这条路上不可达（服务端 prepare 已挡掉、文本非空），留一句明确的话而不是空白
			case EMPTY_PROGRAM, NOT_ENCODABLE, TOO_LARGE -> feedback(player, "record.write_failed", ChatFormatting.RED,
				String.valueOf(outcome.problem()));
		}
	}

	/** 回执里那串字：**只有文件名**（含 {@code .sma}），理由见类注释。 */
	private static String fileName(@Nullable Path file) {
		return file == null ? "?" : file.getFileName()
			.toString();
	}

	/**
	 * 快捷栏上方那一行（actionbar）—— 与本体存完蓝图后的提示同一条通道
	 * （{@code LangBuilder#sendStatus} = {@code displayClientMessage(component(), true)}）。
	 * 参数只传 {@code String / Number}（见类注释里那个 {@code TranslatableContents} 的坑）。
	 */
	private static void feedback(LocalPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_sequenced_mechanical_arm." + key, args)
				.withStyle(color),
			true);
	}

}
