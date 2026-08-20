package dev.colorfullighting.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * References Sodium classes - call only when Sodium is loaded (CompatStatus guards).
 */
public final class SodiumVertexProbe {
    private SodiumVertexProbe() {
    }

    /**
     * True when ChunkVertexMixin was woven: the mixin adds the ColorfulLightVertex
     * interface to Sodium's chunk vertex, which is the load-bearing part of the
     * terrain compat - without it the packed colorful format never reaches the GPU
     * correctly and the world renders dark.
     */
    public static boolean chunkVertexExtended() {
        try {
            return ColorfulLightVertex.class.isAssignableFrom(ChunkVertexEncoder.Vertex.class);
        }
        catch (Throwable missingSodiumClass) {
            return false;
        }
    }
}
