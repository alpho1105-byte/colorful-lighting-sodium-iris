package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.sodium.VeilNormalVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;

/** Gives Colorful Lighting a compile-time-neutral view of Veil's injected normal getter. */
@Mixin(value = ChunkVertexEncoder.Vertex.class, priority = 900, remap = false)
abstract class VeilChunkVertexEncoderVertexMixin implements VeilNormalVertex {
}
