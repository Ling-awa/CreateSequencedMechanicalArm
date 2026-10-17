package io.github.Ling.create_sequenced_mechanical_arm.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 操作记录表 —— **不携带任何数据**的物品，唯一的用途是"确定被上传的是哪条动力臂"。
 *
 * <p>用户口径（为什么是这样一个空壳物品）：上传 = 把手边那条臂里的程序**导出成文件**，
 * 属于"读世界里的东西"，与"手里那张卡上写着什么"无关。所以这个物品上既不存程序、
 * 也不存坐标、更不存"上次上传了哪条臂"—— 它只是一把**授权用的钥匙**：
 * 手持它 + 潜行右键一条可编程动力臂 = "把这条臂的程序给我"（见
 * {@code item/OperationRecordHandler} 与 {@code network/ExportScriptPacket}）。
 *
 * <p>⚠️ 它与 {@link OperationTableItem}（程序卡）是两种东西，别混：卡上**有**程序（{@code ArmProgramText}），
 * 记录表上**没有**任何程序数据。也因此它 {@code stacksTo(1)} 只是习惯上"工具不叠"，
 * 不是因为它装着东西。
 */
public class OperationRecordItem extends Item {

	public OperationRecordItem(Properties properties) {
		super(properties);
	}

	/**
	 * 是不是操作记录表。
	 *
	 * <p>与 {@link OperationTableItem#isTable(ItemStack)} 同一个写法：按**类型**判，不按物品 id 判 ——
	 * id 判据在改名/换注册名时会静默失效（模组改名过一次，正是这类判据最容易漂的地方）。
	 */
	public static boolean isRecord(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof OperationRecordItem;
	}

}
