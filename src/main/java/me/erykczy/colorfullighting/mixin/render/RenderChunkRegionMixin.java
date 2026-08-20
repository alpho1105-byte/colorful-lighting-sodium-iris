package me.erykczy.colorfullighting.mixin.render;

import dev.colorfullighting.compat.level.RenderLevelView;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Marks vanilla chunk-build snapshots as views of their live client level. */
@Mixin(RenderChunkRegion.class)
public abstract class RenderChunkRegionMixin implements RenderLevelView {
    @Shadow
    @Final
    protected Level level;

    @Override
    public Object colorfulLighting$getRootLevel() {
        return level;
    }
}
