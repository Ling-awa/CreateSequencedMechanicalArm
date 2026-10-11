package io.github.Ling.create_smart_mechanical_arm.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import io.github.Ling.create_smart_mechanical_arm.program.ArmProgram;

/**
 * 操作表在物品上的存取（PLAN.md §2.4 的"道具化"）。
 *
 * <p>数据放在 {@code CUSTOM_DATA} 的 {@code ArmProgram} 子标签里 —— 与臂方块 NBT 里用的是
 * <b>同一套 {@link ArmProgram} 编解码</b>，所以卡片与方块之间可以无损互拷。
 * 频率、坐标标记这些字段因此天然跟着卡片走。
 */
public final class CardProgramStorage {

	private static final String KEY = "ArmProgram";

	private CardProgramStorage() {
	}

	public static boolean hasProgram(ItemStack stack) {
		return readTag(stack) != null;
	}

	public static ArmProgram read(ItemStack stack, HolderLookup.Provider registries) {
		CompoundTag tag = readTag(stack);
		return tag == null ? new ArmProgram() : ArmProgram.load(tag, registries);
	}

	public static void write(ItemStack stack, ArmProgram program, HolderLookup.Provider registries) {
		CompoundTag root = new CompoundTag();
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		if (existing != null)
			root = existing.copyTag();
		root.put(KEY, program.save(registries));
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	public static void clear(ItemStack stack) {
		CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
		if (existing == null)
			return;
		CompoundTag root = existing.copyTag();
		root.remove(KEY);
		if (root.isEmpty())
			stack.remove(DataComponents.CUSTOM_DATA);
		else
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	private static CompoundTag readTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null)
			return null;
		CompoundTag tag = data.copyTag()
			.getCompound(KEY);
		return tag.isEmpty() ? null : tag;
	}

}
