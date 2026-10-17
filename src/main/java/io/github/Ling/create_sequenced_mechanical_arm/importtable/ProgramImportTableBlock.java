package io.github.Ling.create_sequenced_mechanical_arm.importtable;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllShapes;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.item.ItemHelper;

import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.NotNull;

/**
 * 「操作导入桌」方块 —— **逐条照抄 Create 蓝图桌**（{@code SchematicTableBlock.java:30-103}）。
 *
 * <p>为什么照抄而不自创：蓝图桌已经是"一格格子 + 一个进度条 + 一个文件列表"这块界面的既成范式，
 * 连形状、碰撞箱、方块状态定义都能直接借 Create 的 {@link AllShapes}（用户口径：界面资源先复用，
 * 等他自己画好再换）。差异只有一处：**方块模型**借的是 {@code create:block/schematic_table}
 * （见 {@code assets/.../blockstates/program_import_table.json}）。
 *
 * <p>{@code getCollisionShape} 用 {@code TABLE_POLE_SHAPE}（只有那根桌腿挡人）而
 * {@code getShape} 用整张桌子：桌子面比碰撞箱宽，玩家可以"贴着桌沿走"，视觉与手感都和蓝图桌一致。
 */
public class ProgramImportTableBlock extends HorizontalDirectionalBlock implements IBE<ProgramImportTableBlockEntity> {

	public static final MapCodec<ProgramImportTableBlock> CODEC = simpleCodec(ProgramImportTableBlock::new);

	public ProgramImportTableBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
		builder.add(FACING);
		super.createBlockStateDefinition(builder);
	}

	@Override
	public PushReaction getPistonPushReaction(BlockState state) {
		return PushReaction.BLOCK;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState()
			.setValue(FACING, context.getHorizontalDirection()
				.getOpposite());
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return AllShapes.TABLE_POLE_SHAPE;
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return AllShapes.SCHEMATICS_TABLE.get(state.getValue(FACING));
	}

	/**
	 * 空手（以及没被物品逻辑吃掉的那一下）右键 = 开界面。
	 *
	 * <p>判断写在**服务端**、客户端只回 {@code SUCCESS}（与上游同一条约定）：界面由
	 * {@code openMenu} 打开，客户端自己开一个空壳只会在下一 tick 被服务端的真实菜单顶掉。
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hitResult) {
		if (level.isClientSide)
			return InteractionResult.SUCCESS;
		withBlockEntityDo(level, pos, be -> player.openMenu(be, be::sendToMenu));
		return InteractionResult.SUCCESS;
	}

	/**
	 * 拆方块时把两格里的东西掉出来（上游同款）。
	 *
	 * <p>少了这一步的症状是"拆掉导入桌，里面那张表跟着方块一起消失"，而且不报任何错。
	 */
	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (!state.hasBlockEntity() || state.getBlock() == newState.getBlock())
			return;

		withBlockEntityDo(level, pos, be -> ItemHelper.dropContents(level, pos, be.inventory));
		level.removeBlockEntity(pos);
	}

	@Override
	public Class<ProgramImportTableBlockEntity> getBlockEntityClass() {
		return ProgramImportTableBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends ProgramImportTableBlockEntity> getBlockEntityType() {
		return SequencedArmRegistration.PROGRAM_IMPORT_TABLE_BE.get();
	}

	@Override
	protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
		return false;
	}

	@Override
	protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

}
