package io.github.Ling.create_sequenced_mechanical_arm.program;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItem;
import com.simibubi.create.content.kinetics.deployer.DeployerApplicationRecipe;
import com.simibubi.create.content.kinetics.deployer.ItemApplicationRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * deploying 的配方解析（PLAN.md §2.8 路线 B）。
 *
 * <p>优先级与过滤<b>照抄</b> Create 的 {@code DeployerBlockEntity.getRecipe}：
 * 顺序装配 100 → {@code create:deploying} 50 → {@code create:item_application} 50，
 * 过滤档位用 {@link AllRecipeTypes#shouldIgnoreInAutomation} 同款的 {@code CAN_BE_AUTOMATED}
 * （排除 {@code *_manual_only}）。手上是砂纸时先试抛光配方（同上游）。
 *
 * <p><b>兼容性缺口（已记入 PLAN.md R6b）</b>：Create 的 {@code DeployerRecipeSearchEvent}
 * 构造器要求一个 {@code DeployerBlockEntity}，智能臂无法复用，所以第三方为机械手注册的
 * "非配方类" deploying 来源在这里看不到。这是已知限制，不是遗漏。
 */
public final class DeployRecipes {

	private DeployRecipes() {
	}

	/**
	 * @param processed  载具上被加工的物品（取 1 个）
	 * @param applicator 臂手上持有的应用物
	 * @return 命中的配方，或 {@code null} 表示没有匹配
	 */
	@Nullable
	public static RecipeHolder<? extends Recipe<?>> find(Level level, ItemStack processed, ItemStack applicator) {
		ItemStackHandler handler = new ItemStackHandler(2);
		handler.setStackInSlot(0, processed.copyWithCount(1));
		handler.setStackInSlot(1, applicator.copyWithCount(1));
		RecipeWrapper wrapper = new RecipeWrapper(handler);
		// 必须按接口类型传参：AllRecipeTypes.find 的 I 由实参静态类型推断，
		// 传具体类型会推断出 Recipe<RecipeWrapper>，与目标的 Recipe<RecipeInput> 不兼容（同一坑上游是
		// 靠 checkRecipe(AllRecipeTypes, RecipeInput, Level) 这个接口参数签名绕开的）。
		RecipeInput findInput = wrapper;

		// 砂纸抛光：按"被加工物"单独查（上游也是这条特例）
		if (applicator.getItem() instanceof SandPaperItem) {
			RecipeInput polishInput = new SingleRecipeInput(processed.copyWithCount(1));
			Optional<RecipeHolder<Recipe<RecipeInput>>> polishing =
				AllRecipeTypes.SANDPAPER_POLISHING.find(polishInput, level)
					.filter(AllRecipeTypes.CAN_BE_AUTOMATED);
			if (polishing.isPresent())
				return polishing.get();
		}

		// 1) 顺序装配（优先级 100）
		Optional<RecipeHolder<DeployerApplicationRecipe>> sequenced = SequencedAssemblyRecipe.getRecipe(level, wrapper,
			AllRecipeTypes.DEPLOYING.getType(), DeployerApplicationRecipe.class);
		if (sequenced.isPresent())
			return sequenced.get();

		// 2) create:deploying（50）
		Optional<RecipeHolder<Recipe<RecipeInput>>> deploying =
			AllRecipeTypes.DEPLOYING.find(findInput, level)
				.filter(AllRecipeTypes.CAN_BE_AUTOMATED);
		if (deploying.isPresent())
			return deploying.get();

		// 3) create:item_application（50）
		Optional<RecipeHolder<Recipe<RecipeInput>>> application =
			AllRecipeTypes.ITEM_APPLICATION.find(findInput, level)
				.filter(AllRecipeTypes.CAN_BE_AUTOMATED);
		if (application.isPresent())
			return application.get();

		return null;
	}

	/** 配方是否要求保留手上的应用物（例如"应用一次但不消耗"）。 */
	public static boolean keepsHeldItem(Recipe<?> recipe) {
		return recipe instanceof ItemApplicationRecipe application && application.shouldKeepHeldItem();
	}

}
