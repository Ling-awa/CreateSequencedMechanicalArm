package io.github.Ling.create_smart_mechanical_arm.network;

import io.github.Ling.create_smart_mechanical_arm.Create_smart_mechanical_arm;
import io.github.Ling.create_smart_mechanical_arm.item.CardProgramStorage;
import io.github.Ling.create_smart_mechanical_arm.item.ClipboardPlan;
import io.github.Ling.create_smart_mechanical_arm.item.OperationTableItem;
import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 时刻表式编辑器的保存包（PLAN.md §5.2）。
 *
 * <p><b>为什么整份程序一起传，而不是"增量编辑"</b>：上游 Create 的 {@code ScheduleEditPacket}
 * 就是整份 {@code Schedule} 一起传（{@code ScheduleItem.use} → 编辑 → 关闭时发），
 * 服务端只做"校验 + 落盘"。这套做法的好处是<b>状态只有一份权威</b>：客户端怎么改都行，
 * 服务端要么整体接受、要么整体拒绝，不会出现"增量补丁对不上底稿"的中间态。
 * 代价是包体大一点 —— 256 条上限的程序约几十 KB，相对 1 MB 的载荷上限可以忽略。
 *
 * <p>{@code PLAN.md §5.2} 里原本写的是"增量编辑 + S2C 同步 + BE 并发编辑锁"，这里改成了
 * 全量提交，<b>原因有二</b>：
 * <ol>
 *   <li>编辑对象是<b>手上的卡</b>（§5.1 的编辑模式），卡在同一时刻只可能在一个玩家的主手里，
 *       不存在"两个人同时编辑同一份程序"——BE 上的 {@code editorLock} 没有用武之地；</li>
 *   <li>单一写者由菜单自己保证：{@code HeldItemGhostItemMenu.stillValid} 要求卡片还握在主手，
 *       落盘时再校验一次"主手还是那张卡"，两道门都不需要额外锁。</li>
 * </ol>
 * 将来若要"直接编辑臂里那份程序"，那时才需要 BE 侧锁，届时另开一条 S2C 同步通道。
 */
public record ArmProgramEditPacket(CompoundTag program) implements CustomPacketPayload {

	/** 防御性上限：合法程序（8 任务 × 256 条目）实测在几十 KB 量级，128 KB 足够宽松。 */
	public static final int MAX_TAG_BYTES = 128 * 1024;

	public enum SaveResult {
		OK, NOT_HOLDING, OVER_LIMIT, TOO_LARGE
	}

	public static final CustomPacketPayload.Type<ArmProgramEditPacket> TYPE =
		new CustomPacketPayload.Type<>(
			ResourceLocation.fromNamespaceAndPath(Create_smart_mechanical_arm.MODID, "arm_program_edit"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ArmProgramEditPacket> STREAM_CODEC =
		StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, ArmProgramEditPacket::program, ArmProgramEditPacket::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	// ————————————————————————— 服务端处理 —————————————————————————

	public static void handle(ArmProgramEditPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			SaveResult result = applyTo(player, packet.program());
			HolderLookup.Provider registries = player.level()
				.registryAccess();
			switch (result) {
				case OK -> {
					ArmProgram saved = CardProgramStorage.read(player.getMainHandItem(), registries);
					int entries = saved.tasks.stream()
						.mapToInt(task -> task.entries.size())
						.sum();
					feedback(player, "card.editor_saved", ChatFormatting.GREEN, saved.tasks.size(), entries);
				}
				case NOT_HOLDING -> feedback(player, "card.editor_not_holding", ChatFormatting.RED);
				case OVER_LIMIT -> feedback(player, "card.over_limit", ChatFormatting.RED);
				case TOO_LARGE -> feedback(player, "card.editor_too_large", ChatFormatting.RED);
			}
		});
	}

	/**
	 * 校验并落盘。<b>抽成静态方法是为了能在启动自检里直接跑</b> —— 这条路径
	 * 编译通过但语义错的后果很重（改完的程序重进世界就变了形），必须可被断言。
	 */
	public static SaveResult applyTo(ServerPlayer player, CompoundTag tag) {
		ItemStack card = player.getMainHandItem();
		if (!OperationTableItem.isTable(card))
			return SaveResult.NOT_HOLDING;
		if (tag.sizeInBytes() > MAX_TAG_BYTES)
			return SaveResult.TOO_LARGE;

		HolderLookup.Provider registries = player.level()
			.registryAccess();
		ArmProgram program = ArmProgram.load(tag, registries);
		if (!program.isWithinLimits())
			return SaveResult.OVER_LIMIT;

		// 程序自己的坐标形态标记跟着卡片形态走：卡片是哪种形态，程序就自称哪种
		// （条目级 absolute 才是运行语义，这个标记只用于"这份数据是什么"的可读性）
		program.absolute = OperationTableItem.isAbsoluteForm(card);
		CardProgramStorage.write(card, program, registries);
		// 编辑器里可能删过条目、改过备注 ⇒ 副手清单的勾按新程序重算（§5.5）
		ClipboardPlan.syncTicks(player, program);
		player.inventoryMenu.broadcastChanges();
		return SaveResult.OK;
	}

	private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
		player.displayClientMessage(
			Component.translatable("create_smart_mechanical_arm." + key, args)
				.withStyle(color),
			false);
	}

}
