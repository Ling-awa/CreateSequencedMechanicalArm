package io.github.Ling.create_sequenced_mechanical_arm.network;

import io.github.Ling.create_sequenced_mechanical_arm.Create_sequenced_mechanical_arm;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_sequenced_mechanical_arm.item.CardProgramStorage.View;
import io.github.Ling.create_sequenced_mechanical_arm.item.OperationTableItem;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 编辑器"看到哪"的 C2S 意图包（PLAN.md §18）：页面 + 操作页指针 + 两个列表的滚动。
 *
 * <p>用户要求：<i>"可以让操作页的指针位置和滚动位置也保留下来，这样方便玩家连续编辑测试。"</i>
 * 关掉界面去世界里试一下、再打开接着改，界面得回到你离开时那一行。
 *
 * <p><b>为什么不挂在 {@code ArmProgramEditPacket}（全量保存）上</b>：那条路要过规模上限与体积校验，
 * 而"滚动到第 15 行"根本不是数据改动 —— 玩家只是挪了挪视线，没有任何理由为此冒"整份程序被拒"的风险
 * （与 {@link CardUiPacket} 的分工理由相同，见它的注释）。所以它走自己这条轻量通道，只写四个键。
 *
 * <p>服务端**不信任**包里的数：页名只认两个取值、滚动压进合法区间、指针非负（{@link CardProgramStorage#writeView}）。
 * 越界的指针不会在这里夹到"列表长度"——那是界面的事（只有它知道列表现在多长），它恢复时会自己夹。
 */
public record CardViewPacket(int entry, String page, float scroll, float taskScroll) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CardViewPacket> TYPE =
		new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(Create_sequenced_mechanical_arm.MODID, "card_view"));

	public static final StreamCodec<FriendlyByteBuf, CardViewPacket> STREAM_CODEC = StreamCodec.of(
		(buf, packet) -> {
			buf.writeVarInt(packet.entry());
			buf.writeUtf(packet.page());
			buf.writeFloat(packet.scroll());
			buf.writeFloat(packet.taskScroll());
		},
		buf -> new CardViewPacket(buf.readVarInt(), buf.readUtf(), buf.readFloat(), buf.readFloat()));

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(CardViewPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			ItemStack card = player.getMainHandItem();
			if (!OperationTableItem.isTable(card))
				return;
			// 只动那四个键：程序文本、模式、任务下标都不碰（包里的数由 writeView 过滤）
			CardProgramStorage.writeView(card,
				new View(packet.entry(), packet.page(), packet.scroll(), packet.taskScroll()));
			// 与其它卡包同一条约定：写完把权威副本广播回客户端（本地那份因此与服务端一致）
			player.inventoryMenu.broadcastChanges();
		});
	}

}
