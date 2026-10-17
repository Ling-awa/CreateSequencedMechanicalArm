package io.github.Ling.create_sequenced_mechanical_arm.item;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.client.ExportScriptScreen;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmBlock;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 操作记录表的**客户端**手势（纯客户端 + 一个 C2S 意图包）。两件事：
 *
 * <ol>
 *   <li><b>蓝色线框</b>：手持记录表、射线指着一条可编程动力臂 ⇒ 把那一格画成蓝色线框，
 *       眼睛能立刻确认"上传的就是这条"；</li>
 *   <li><b>潜行右键臂 ⇒ 弹"输入文件名"界面</b>（{@link ExportScriptScreen}），确认后发
 *       {@code network/ExportScriptPacket}，由服务端校验并落盘。</li>
 * </ol>
 *
 * <h2>线框：颜色/线宽/每 tick 重发，全部照 Create 的蓝图与笔</h2>
 * 出处 {@code SchematicAndQuillHandler:180-187}：{@code Outliner.showAABB(...).colored(0x6886c5).lineWidth(1/16f)}。
 * 上游用 {@code chaseAABB}（平滑追随），我们每 tick 直接 {@code showAABB} —— 效果一样（每 tick 重发本来就
 * 覆盖掉上一帧），而且省掉"槽位要一直活着"的麻烦。
 *
 * <p>⚠️ <b>key 必须是稳定值类型</b>（这里用常量字符串）：{@code OperationTableHandler:237}/{@code :263-264}
 * 记着这个坑 —— 拿临时对象当 key，Outliner 每 tick 会当成一条**新**轮廓累积，越画越多。
 *
 * <p>判据**先便宜后贵**：先看主手物品（一次 {@code instanceof}），再看射线（{@code mc.hitResult} 是每 tick
 * 已经算好的，不额外造对象），最后才看方块类型。
 *
 * <h2>为什么拦"键位事件"而不是 {@code RightClickBlock}</h2>
 * 与 {@link OperationTableHandler} 同一个理由（那边类注释有完整推导）：原版对"潜行 + 手上有东西"直接
 * 跳过方块交互，{@code useItemOn} 根本收不到这一下；而 {@code PlayerInteractEvent.RightClickBlock} 的
 * {@code setCanceled} 只改客户端预测结果，{@code ServerboundUseItemOnPacket} 照样发出去。
 * {@link InputEvent.InteractionKeyMappingTriggered} 是 {@code Minecraft.startUseItem} 里**发任何包之前**
 * 的最后一道闸门，取消掉就什么都不会发生 —— 正是"这一下右键只用来上传"要的语义。
 */
@EventBusSubscriber(modid = Create_sequenced_mechanical_arm.MODID, value = Dist.CLIENT)
public class OperationRecordHandler {

	/**
	 * 线框的 Outliner key：**常量字符串**（稳定值类型）。见类注释里那个"每 tick 累积"的坑。
	 *
	 * <p>为什么与 {@code OperationTableHandler} 的 {@code "sequenced_arm:origin"} 不同名：两者可能同时
	 * 存在（一只手换物时会话不会同时活跃，但 Outliner 的槽位是按 key 存的、跨物品不清理），
	 * 用同一个 key 会互相覆盖，出现"拿着记录表把操作表的原点框顶掉了"这种莫名其妙的观感。
	 */
	private static final String OUTLINE_KEY = "sequenced_arm:record_target";

	/** 与 Create 蓝图与笔完全同色同宽（{@code 0x6886c5}、{@code 1/16f}）。 */
	private static final int OUTLINE_COLOR = 0x6886c5;

	// ————————————————————————— 每 tick：蓝色线框 —————————————————————————

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null)
			return;
		// ① 最便宜的一步：主手是不是记录表
		ItemStack held = player.getMainHandItem();
		if (!OperationRecordItem.isRecord(held))
			return;
		BlockPos pos = aimedArm(mc);
		if (pos == null)
			return;
		// 整方块一个框（用户口径"显示为完整方块的方框"），与方块形状无关
		Outliner.getInstance()
			.showAABB(OUTLINE_KEY, new AABB(pos))
			.colored(OUTLINE_COLOR)
			.lineWidth(1 / 16f);
	}

	/**
	 * 射线指着的、**确实是我们的臂**的那一格；不是就 {@code null}。
	 *
	 * <p>⚠️ 指空气时 {@code mc.hitResult} **仍然是 BlockHitResult**（原版 {@code clip} 没打中时返回
	 * {@code BlockHitResult.miss}，位置是空气方块），所以必须看 {@code getType() == BLOCK} ——
	 * 只判 {@code instanceof} 会给空气画框（{@code OperationTableHandler:293-298} 同一个坑）。
	 */
	private static BlockPos aimedArm(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK)
			return null;
		BlockPos pos = blockHit.getBlockPos();
		if (mc.level == null || !(mc.level.getBlockState(pos)
			.getBlock() instanceof SequencedArmBlock))
			return null;
		return pos;
	}

	// ————————————————————————— 右键：打开"输入文件名"界面 —————————————————————————

	/**
	 * 使用键（主手）= 弹界面。**只认"潜行 + 记录表 + 指着我们的臂"**，别的组合一概不碰：
	 *
	 * <ul>
	 *   <li>不潜行 ⇒ 放行，让方块自己处理（这一条很重要：{@code SequencedArmBlock.useItemOn} 里
	 *       记录表被刻意排除在"交换物品"之外，所以不潜行右键臂**什么都不做**，不会有副作用）；</li>
	 *   <li>指着别的方块 ⇒ 放行（别的模组 / 原版的右键手势一个都不能挡）；</li>
	 *   <li>副手 ⇒ 放行（与 {@code OperationTableHandler} 同一条：只接管主手）。</li>
	 * </ul>
	 */
	@SubscribeEvent
	public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
		if (!event.isUseItem())
			return;
		if (event.getHand() != InteractionHand.MAIN_HAND)
			return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.screen != null)
			return;
		// 旁观者没有手部手势（服务端那一侧也会再挡一次，见 ExportScriptPacket.handle）
		if (player.isSpectator())
			return;
		if (!OperationRecordItem.isRecord(player.getMainHandItem()))
			return;
		if (!player.isShiftKeyDown())
			return;
		BlockPos pos = aimedArm(mc);
		if (pos == null)
			return;
		// ★ 先取消：取消发生在 Minecraft.startUseItem 的**发任何包之前** ⇒ 这一下右键
		//   既不会开方块界面、也不会给臂发"使用"包，只剩我们要的开界面。
		//   （手仍然会挥一下，手感与正常右键一致。）
		event.setCanceled(true);
		ExportScriptScreen screen = new ExportScriptScreen(pos);
		mc.setScreen(screen);
	}

}
