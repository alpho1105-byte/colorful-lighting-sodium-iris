package dev.colorfullighting.compat.sodium;

import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;

/** Shared coordinate and packing rules for colored Sodium fluid vertices. */
public final class FluidVertexLight {
    private FluidVertexLight() {
    }

    /**
     * Converts a quad-local vertex coordinate into the world-space point used to
     * sample colored light. The half-block offset matches the solid terrain path
     * and samples on the illuminated side of the face.
     */
    public static double sampleCoordinate(
            int worldBlockCoordinate,
            float vertexCoordinate,
            int lightFaceStep
    ) {
        return worldBlockCoordinate + vertexCoordinate + lightFaceStep * 0.5;
    }

    /** Keeps Sodium's per-vertex sky light while replacing only the block-light color. */
    public static int packWithVanillaSky(int vanillaLight, ColorRGB8 color) {
        int skyLight = (vanillaLight >>> 20) & 0xF;
        return PackedLightData.packData(skyLight, color);
    }
}
