package me.erykczy.colorfullighting.accessors;

import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.NotNull;

public class BlockStateWrapper implements BlockStateAccessor {
    final BlockState blockState;

    public BlockStateWrapper(@NotNull BlockState blockState) {
        this.blockState = blockState;
    }

    @Override
    public ResourceKey<Block> getBlockKey() {
        return blockState.getBlockHolder().getKey();
    }

    @Override
    public int getLightEmission() {
        return blockState.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    @Override
    public int getLightBlock() {
        return blockState.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    @Override
    public int getLightEmission(LevelAccessor level, BlockPos pos) {
        if(level instanceof LevelWrapper levelWrapper)
            return blockState.getLightEmission(levelWrapper.getWrappedLevel(), pos);
        return getLightEmission();
    }

    @Override
    public int getLightBlock(LevelAccessor level, BlockPos pos) {
        if(level instanceof LevelWrapper levelWrapper)
            return blockState.getLightBlock(levelWrapper.getWrappedLevel(), pos);
        return getLightBlock();
    }

    @Override
    public String getPropertyValue(String propertyName) {
        for(Property<?> property : blockState.getProperties()) {
            if(property.getName().equals(propertyName))
                return serializeValue(blockState, property);
        }
        return null;
    }

    private static <T extends Comparable<T>> String serializeValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
