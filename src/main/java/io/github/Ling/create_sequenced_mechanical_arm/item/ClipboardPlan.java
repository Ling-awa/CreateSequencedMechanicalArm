package io.github.Ling.create_sequenced_mechanical_arm.item;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;

import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgram;
import io.github.Ling.create_sequenced_mechanical_arm.program.ArmProgramEntry;
import io.github.Ling.create_sequenced_mechanical_arm.program.ClipboardChecklist;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 副手剪贴板 = 一张**待办清单**（PLAN.md §5.5，用户第二十七轮）。
 *
 * <p>本类收口"读清单"与"划掉已做完的"这两件事，<b>两边都要用</b>：
 * <ul>
 *   <li>服务端：录一条操作时把<b>下一个未完成的行</b>写成新条目的备注（{@code CardEditPacket.addEntry}），
 *       写完程序顺手把副手清单的勾对齐（{@link #syncTicks}）—— 服务端是权威，客户端只显示；</li>
 *   <li>客户端：HUD 读同一份行、用同一个 {@link ClipboardChecklist#consumed} 现算"已完成 / 当前 / 下一个"。</li>
 * </ul>
 *
 * <h2>为什么勾选由**服务端**写</h2>
 * 勾选状态存在剪贴板物品的组件里。服务端写完程序会调 {@code inventoryMenu.broadcastChanges()}
 * （现有的录制路径一直这么做），而 {@code InventoryMenu} 的第 45 号槽就是副手（{@code InventoryMenu:80}
 * 用 {@code new Slot(playerInventory, 40, ...)}）—— 所以服务端改、客户端跟着更新，
 * <b>不需要新开任何同步通道，也不会有"客户端乐观写入被服务端否掉"的中间态</b>。
 *
 * <p>⚠️ 写回时必须<b>新建</b> {@link ClipboardContent} 与 {@link ClipboardEntry} 对象，不能就地改
 * {@code entry.checked}：{@code AbstractContainerMenu.remoteSlots} 里那份副本与手上这份**共享同一个组件值对象**
 * （{@code ItemStack.copy()} 只复制补丁表、值还是同一个引用），就地改会让
 * {@code synchronizeSlotToRemote} 的 {@code ItemStack.matches(...)} 判定为"没变"⇒ 客户端永远收不到更新
 * （{@code AbstractContainerMenu:228-239}）。
 */
public final class ClipboardPlan {

	private ClipboardPlan() {
	}

	/** Create 剪贴板的注册名（按 id 查，见 {@link #isClipboard}）。 */
	private static final ResourceLocation CLIPBOARD_ID =
		ResourceLocation.fromNamespaceAndPath("create", "clipboard");

	/**
	 * 是不是 Create 的剪贴板。
	 *
	 * <p><b>按物品 id 查，不引 {@code AllBlocks.CLIPBOARD}</b>：那个字段的类型是 Registrate 的
	 * {@code BlockEntry}，它不在本模组的编译类路径上（Create 自己用得了，我们引不动）——
	 * 直接查注册表既没有这个依赖，也符合本模组一贯的"查不到就当作没有"的防御写法。
	 */
	public static boolean isClipboard(ItemStack stack) {
		if (stack == null || stack.isEmpty())
			return false;
		Item item = BuiltInRegistries.ITEM.get(CLIPBOARD_ID);
		return item != Items.AIR && stack.is(item);
	}

	/**
	 * 剪贴板**当前那一页**的文本行（一条条目 = 一行，条目内部的换行原样保留）。
	 *
	 * <p>读的是"最后打开的那一页"（{@code ClipboardEntry.getLastViewedEntries}）—— 玩家看到哪页就是哪页。
	 * 空行跳过不代表丢弃位置：返回的列表就是"清单"，下标与玩家眼里的行号一一对应。
	 *
	 * <p>全空 → 返回空表（"副手没有清单"），这样调用方到处都只需要判 {@code isEmpty()}。
	 */
	public static List<String> lines(ItemStack clipboard) {
		if (!isClipboard(clipboard))
			return List.of();
		List<String> lines = new ArrayList<>();
		for (ClipboardEntry entry : ClipboardEntry.getLastViewedEntries(clipboard)) {
			String text = entry.text == null ? "" : entry.text.getString();
			// ★ 按**条目**导入、**保留内部的换行**（用户第二十七轮）。
			// Create 的剪贴板本来就是"一条 = 一行"（界面上按回车才新增条目），`\n` 只可能来自
			// **粘贴的多行文本**；按 `\n` 拆开会把"一条待办"散成好几条，让"一条 ↔ 一个操作"对不上
			//（清单 HUD 正是按这个对应关系工作）。strip() 只去首尾空白，不碰内部结构；
			// 超长由 clipNote 补省略号 —— 与写进备注时的口径完全相同，所以两者一定匹配得上。
			lines.add(ArmProgramEntry.clipNote(text.strip()));
		}
		while (!lines.isEmpty() && lines.get(lines.size() - 1)
			.isEmpty())
			lines.remove(lines.size() - 1); // 末尾的空行不算内容
		if (lines.stream()
			.allMatch(String::isBlank))
			return List.of();
		return lines;
	}

	/**
	 * 把副手清单的勾对齐到"当前录入任务"的进度（用户选的方案 A：<b>勾由项目管，玩家不用手动勾</b>）。
	 *
	 * <p>规则：<b>已录进去的行勾上，正在做的那一行清掉勾</b>（它显然还没完成）；
	 * 这一行**之后**的行一律不碰 —— 理由有两条，都不是"怕碰玩家的东西"：
	 * <ol>
	 *   <li>一拿着卡就把整页的勾清空，看起来像 bug（玩家会以为剪贴板坏了）；</li>
	 *   <li>Create 会把**已勾选**的 {@code #地址} 行从地址补全里隐藏（{@code AddressEditBoxHelper:83}），
	 *       替玩家取消勾选会把这些地址重新塞回补全列表。</li>
	 * </ol>
	 * 而"当前这一行"必须清：否则剪贴板显示"已完成"、HUD 显示"当前"，两处自相矛盾。
	 *
	 * <p>另外，副手剪贴板<b>打不开自己的界面</b>（{@code ClipboardBlockItem.use} 对副手直接
	 * {@code pass}），所以"玩家手动勾的那些"只可能是别处留下的旧勾 —— 本方法会按上面的规则覆盖它。
	 */
	public static void syncTicks(ServerPlayer player, ArmProgram program) {
		List<String> lines = lines(player.getOffhandItem());
		if (lines.isEmpty() || program == null)
			return;
		int index = Math.max(0, Math.min(program.editorTask, program.tasks.size() - 1));
		int consumed = ClipboardChecklist.consumed(lines, program.task(index));
		writeTicks(player.getOffhandItem(), lines.size(), consumed);
	}

	/**
	 * 真正写勾：只改 {@code [0, min(consumed, 行数-1)]} 这一段，内容没变就**不动物品**
	 * （不动就不会触发一次无意义的槽位同步）。
	 *
	 * @return 是否真的写了
	 */
	static boolean writeTicks(ItemStack clipboard, int lineCount, int consumed) {
		if (!isClipboard(clipboard))
			return false;
		ClipboardContent old = clipboard.get(AllDataComponents.CLIPBOARD_CONTENT);
		if (old == null || old.readOnly())
			return false; // 只读剪贴板（1.20 老存档迁移过来的）不改
		List<List<ClipboardEntry>> pages = ClipboardEntry.readAll(clipboard);
		if (pages.isEmpty())
			return false;
		int page = Math.min(Math.max(0, old.previouslyOpenedPage()), pages.size() - 1);
		List<ClipboardEntry> target = pages.get(page);
		int last = Math.min(consumed, Math.min(lineCount, target.size()) - 1);
		boolean changed = false;
		for (int i = 0; i <= last; i++)
			if (target.get(i).checked != (i < consumed))
				changed = true;
		if (!changed)
			return false;

		List<ClipboardEntry> fresh = new ArrayList<>(target.size());
		for (int i = 0; i < target.size(); i++) {
			ClipboardEntry entry = target.get(i);
			boolean checked = i <= last ? i < consumed : entry.checked;
			ClipboardEntry copy = new ClipboardEntry(checked, entry.text);
			if (!entry.icon.isEmpty())
				copy.displayItem(entry.icon, entry.itemAmount);
			fresh.add(copy);
		}
		List<List<ClipboardEntry>> newPages = new ArrayList<>(pages.size());
		for (int p = 0; p < pages.size(); p++)
			newPages.add(p == page ? fresh : pages.get(p));
		clipboard.set(AllDataComponents.CLIPBOARD_CONTENT,
			new ClipboardContent(old.type(), newPages, old.readOnly(), old.previouslyOpenedPage(), old.copiedValues()));
		return true;
	}

}
