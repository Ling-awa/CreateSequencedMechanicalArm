package io.github.Ling.create_smart_mechanical_arm.client;

import com.mojang.logging.LogUtils;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.program.ArmEntryText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import org.slf4j.Logger;

/**
 * 编辑器界面的客户端自检触发器（PLAN.md §5.2 的验收补充）。
 *
 * <p>为什么必须有它：专用服务器碰不到任何客户端代码，而 3c 的界面最容易出的两类错
 * ——<b>构造期读 {@code minecraft} 字段</b>（{@code Screen.minecraft} 要到 {@code init} 才赋值，
 * 在构造器里读就是 NPE，表现为"一开界面就掉线"）和<b>某个操作的面板分支写崩</b>——
 * 全都是点开界面那一刻才炸。第一次实测就正好撞上了前者，这条自检就是为了让它在进世界的
 * 第一 tick 就暴露在日志里，而不是等玩家右键。
 *
 * <p>只跑一次，结果是一条日志；不渲染、不发包、不改世界。
 */
@EventBusSubscriber(modid = Create_smart_mechanical_arm.MODID, value = Dist.CLIENT)
public class EditorSelfCheck {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static boolean done;
	/** 自检进行中（见 {@link #onPlaySound}）。 */
	private static boolean running;
	/** 自检期间被屏蔽的 UI 音效条数 —— 打进日志，把"我听不到"变成可核对的事实。 */
	private static int suppressed;

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (done)
			return;
		Minecraft mc = Minecraft.getInstance();
		// 等资源加载完（加载遮罩消失 = 已经在标题界面或更后面），此时字体/贴图都就绪
		if (mc.getOverlay() != null)
			return;
		done = true; // 先置位：就算自检自己炸了也不要每 tick 重试
		running = true;
		try {
			logChecked("操作表编辑器界面构造", () -> ArmProgramScreen.selfCheck(mc));
			// 选择条 HUD 单独一条：它验的是"全部可录入操作 + 图标 + 滚轮环绕"，
			// 与编辑器那套（卡片/控件/热区）不是一回事，混在一起报错就看不出是哪边坏了。
			logChecked("操作选择条", () -> OperationSelectionOverlay.INSTANCE.selfCheck());
			// 护目镜步骤的分行结构（用户第三轮要求：操作+必要属性一行，可选属性各占一行）
			logChecked("步骤文案结构", ArmEntryText::selfCheck);
			// 副手清单 HUD 的显示模型（用户第二十七轮：已完成/当前/下一个 + 8 行窗口）
			logChecked("副手清单 HUD", () -> ClipboardChecklistOverlay.INSTANCE.selfCheck());
		} finally {
			running = false; // 异常也要复位：不复位就等于整个游戏静音（UI 那一类）
		}
		LOGGER.info("[selfcheck] 自检期间屏蔽 UI 音效 {} 条（Create 滚轮选择框的咔哒音就是这些）", suppressed);
	}

	/**
	 * 每条自检**各自兜底**：一条抛异常不该把后面的检查与音效计数一起吞掉。
	 *
	 * <p>原来三条挤在同一个 try 里，第二条只要炸，"滚轮选择框的咔哒音"这个用户亲自提过的现象
	 * 就再也拿不到可核对的数字了 —— 诊断信息本身也是要保护的东西。
	 */
	private static void logChecked(String label, java.util.function.Supplier<String> check) {
		try {
			LOGGER.info("[selfcheck] {}: {}", label, check.get());
		} catch (Throwable t) {
			LOGGER.error("[selfcheck] {} 自检自己抛了异常（这一条失效，其余照跑）：{}", label, t.toString());
		}
	}

	/**
	 * 自检期间屏蔽 UI 音效。
	 *
	 * <p>为什么需要它：用户实测"游戏启动进入主菜单界面之后会产生滚轮选择框的咔哒音效"。根因就是这条自检
	 * —— {@code ArmProgramScreen.checkWheel} 会给每个滚轮选择框<b>真喂一次滚轮</b>（那是唯一能抓到
	 * "控件被装饰性 Label 吃掉滚轮"的办法），而 Create 的 {@code ScrollInput.mouseScrolled} 每次值变化
	 * 都会播一次 {@code AllSoundEvents.SCROLL_VALUE}（{@code SimpleSoundInstance.forUI} ⇒ <b>MASTER</b>
	 * 音源，见 Create 源码 {@code ScrollInput:147-153}）。自检跑在标题界面的第一 tick，于是那几十次
	 * 滚轮就变成了一串咔哒声。
	 *
	 * <p>为什么不能"把音量调成 0"：{@code SoundManager.updateSourceVolume(MASTER, ≤0)} 会直接
	 * {@code SoundManager.stop()} —— 整个声音引擎连背景音乐一起被掐掉。这里只对<b>单条</b>声音下手，
	 * 且只挡 {@link SoundSource#MASTER} 这一类（UI 反馈音：原生按钮音、Create 的滚轮/确认音）；
	 * <b>音乐是 MUSIC 类，不受影响</b>。自检窗口只有几毫秒，正常游玩时不会察觉。
	 */
	@SubscribeEvent
	public static void onPlaySound(PlaySoundEvent event) {
		if (!running)
			return;
		SoundInstance sound = event.getSound();
		if (sound == null || sound.getSource() != SoundSource.MASTER)
			return;
		event.setSound(null);
		suppressed++;
	}

}
