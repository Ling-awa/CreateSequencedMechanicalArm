package io.github.Ling.create_smart_mechanical_arm.program;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 护目镜用的<b>程序摘要</b> —— 客户端能看到的最小信息集。
 *
 * <p>为什么需要它：程序本体只在存盘路径（{@code clientPacket == false}）里写进 NBT，
 * 客户端拿不到 {@link ArmProgram}。而每次条目切换都会 {@code sendData()}，
 * 把整个程序（最多 256 条）塞进每个同步包显然不行 —— 所以这里只发几行摘要。
 *
 * <p>完整的程序同步留给阶段 3c 的编辑器 {@code Menu}（打开界面时才需要）。
 * 这也是之前"戴护目镜只看到『未装载操作表』"的根因：客户端读的是空程序。
 */
public class ArmProgramView {

	public int taskCount;
	public String taskName = "-";
	public int entryIndex;
	public int entryCount;
	public String entryText = "-";
	/**
	 * 当前条目本体（用户第三轮要求护目镜按"操作 + 必要属性 / - 可选属性…"分行显示）。
	 *
	 * <p>为什么要整条同步：正文的分行、属性判定、文案都要与卡片/编辑器**同一套**
	 * （{@code ArmEntryText}），客户端手里没有程序本体就只能拿到一行现成的字符串。
	 * 一条条目很小（几十字节 NBT），而同步只发生在条目切换/程序变更时 —— 比"把整份程序发过去"便宜得多，
	 * 也比"服务端拼好字符串发过来"正确（那样没法本地化、也没法跟卡片共用文案）。
	 */
	public ArmProgramEntry entry;
	public int loopCounter;
	public int loopMax;
	public String state = "IDLE";
	public String failure = "";
	public boolean halted;
	public String error = "";

	public static ArmProgramView capture(ArmProgram program, ProgramRuntime runtime,
		HolderLookup.Provider registries) {
		ArmProgramView view = new ArmProgramView();
		view.taskCount = program.tasks.size();
		view.state = runtime.state.name();
		view.halted = runtime.isHalted();
		view.failure = runtime.getLastFailure() == null ? "" : runtime.getLastFailure();
		view.error = runtime.getError() == null ? "" : runtime.getError();

		ArmProgramTask task = program.task(runtime.taskIndex);
		if (task != null) {
			view.taskName = task.name;
			view.entryCount = task.entries.size();
			view.loopMax = task.loopCount;
		}
		view.entryIndex = runtime.entryIndex;
		view.loopCounter = runtime.loopCounter;
		if (task != null && runtime.entryIndex >= 0 && runtime.entryIndex < task.entries.size()) {
			ArmProgramEntry current = task.entries.get(runtime.entryIndex);
			view.entry = current;
			view.entryText = current.describe(); // 调试/兜底用的单行版
		}
		return view;
	}

	public CompoundTag save(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("tc", taskCount);
		tag.putString("tn", taskName);
		tag.putInt("ei", entryIndex);
		tag.putInt("ec", entryCount);
		tag.putString("et", entryText);
		if (entry != null)
			tag.put("e", entry.save(registries));
		tag.putInt("lc", loopCounter);
		tag.putInt("lm", loopMax);
		tag.putString("st", state);
		tag.putString("fl", failure);
		tag.putBoolean("ha", halted);
		tag.putString("er", error);
		return tag;
	}

	public void load(CompoundTag tag, HolderLookup.Provider registries) {
		taskCount = tag.getInt("tc");
		taskName = tag.getString("tn");
		entryIndex = tag.getInt("ei");
		entryCount = tag.getInt("ec");
		entryText = tag.getString("et");
		entry = tag.contains("e") ? ArmProgramEntry.load(tag.getCompound("e"), registries) : null;
		loopCounter = tag.getInt("lc");
		loopMax = tag.getInt("lm");
		state = tag.contains("st") ? tag.getString("st") : "IDLE";
		failure = tag.getString("fl");
		halted = tag.getBoolean("ha");
		error = tag.getString("er");
	}

}
