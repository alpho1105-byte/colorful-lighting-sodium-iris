package me.erykczy.colorfullighting.common.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.function.Consumer;

public interface LevelAccessor {
    Object getWrappedLevel();
    int getSectionsCount();
    int getMinSectionY();
    int getMaxSectionY();
    boolean hasChunk(ChunkPos chunkPos);
    boolean hasChunkAndNeighbours(ChunkPos chunkPos);
    void findLightSources(ChunkPos chunkPos, Consumer<BlockPos> consumer);
    @Nullable
    BlockStateAccessor getBlockState(BlockPos pos);
    boolean isInBounds(BlockPos pos);
    void setSectionDirty(int x, int y, int z);

    /** Rebuilds every visible render section after initial colored-light seeding. */
    void rebuildAllSections();
}
