package me.erykczy.colorfullighting.common.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

public interface BlockStateAccessor {
    ResourceKey<Block> getBlockKey();
    int getLightEmission();
    int getLightBlock();
    int getLightEmission(LevelAccessor level, BlockPos pos);
    int getLightBlock(LevelAccessor level, BlockPos pos);

    /**
     * The state's value for the named blockstate property, serialized the way it appears
     * in commands (e.g. "true", "red", "3"), or null if the block has no such property.
     */
    @Nullable String getPropertyValue(String propertyName);
}
