package com.create.nautilus.content.oxygen;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.*; import net.minecraft.world.level.LevelReader; import net.minecraft.world.level.block.entity.BlockEntityType; import net.minecraft.world.level.block.state.BlockState;
public final class SnorkelBlock extends HorizontalKineticBlock implements IBE<SnorkelBlockEntity>{
 public SnorkelBlock(Properties p){super(p);} @Override public Direction.Axis getRotationAxis(BlockState s){return Direction.Axis.Y;}
 @Override public boolean hasShaftTowards(LevelReader l, BlockPos p, BlockState s, Direction f){return f==Direction.DOWN;}
 public Class<SnorkelBlockEntity> getBlockEntityClass(){return SnorkelBlockEntity.class;} public BlockEntityType<? extends SnorkelBlockEntity> getBlockEntityType(){return NautilusBlockEntityTypes.SNORKEL.get();}}
