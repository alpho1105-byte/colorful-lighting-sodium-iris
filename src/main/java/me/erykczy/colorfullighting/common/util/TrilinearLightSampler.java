package me.erykczy.colorfullighting.common.util;

import org.jetbrains.annotations.Nullable;

/**
 * Smoothly samples block-centered RGB light values at an arbitrary world position.
 * Works on the storage's packed 12-bit values ({@code r<<8|g<<4|b}) so the per-vertex
 * corner reads stay allocation-free.
 */
public final class TrilinearLightSampler {
    /** Corner lookup result signalling an unallocated section. */
    public static final int MISSING = -1;

    @FunctionalInterface
    public interface PackedLookup {
        /** 12-bit {@code r<<8|g<<4|b}, or {@link #MISSING} when the data is absent. */
        int get(int x, int y, int z);
    }

    private TrilinearLightSampler() {
    }

    /**
     * Trilinear blend of the eight corners around {@code (x, y, z)}. Weights are the
     * true fractional offsets and black corners participate in the blend, so the
     * sampled color fades smoothly with distance instead of snapping at range edges.
     *
     * <p>Returns null as soon as any corner reports {@link #MISSING} - the caller
     * falls back to vanilla light, preventing an absent section from being read as
     * real black at view-area or virtual-sublevel boundaries. Corners with a block Y
     * outside {@code [minBlockY, maxBlockY]} are legitimately dark instead: storage
     * never allocates beyond the build limits, and treating those corners as missing
     * would draw a fallback seam along the world's top and bottom faces.
     */
    @Nullable
    public static ColorRGB8 sampleOrNull(
            double x, double y, double z,
            int minBlockY, int maxBlockY,
            PackedLookup lookup
    ) {
        double gridX = x - 0.5;
        double gridY = y - 0.5;
        double gridZ = z - 0.5;
        int cornerX = (int) Math.floor(gridX);
        int cornerY = (int) Math.floor(gridY);
        int cornerZ = (int) Math.floor(gridZ);
        double deltaX = gridX - cornerX;
        double deltaY = gridY - cornerY;
        double deltaZ = gridZ - cornerZ;

        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        for(int dx = 0; dx <= 1; dx++) {
            for(int dy = 0; dy <= 1; dy++) {
                int blockY = cornerY + dy;
                boolean outsideWorld = blockY < minBlockY || blockY > maxBlockY;
                for(int dz = 0; dz <= 1; dz++) {
                    int packed = outsideWorld ? 0 : lookup.get(cornerX + dx, blockY, cornerZ + dz);
                    if(packed == MISSING) return null;
                    if(packed == 0) continue; // black corner: contributes nothing
                    double weight = (dx == 0 ? 1.0 - deltaX : deltaX)
                            * (dy == 0 ? 1.0 - deltaY : deltaY)
                            * (dz == 0 ? 1.0 - deltaZ : deltaZ);
                    red += ((packed >>> 8) & 0x0F) * weight;
                    green += ((packed >>> 4) & 0x0F) * weight;
                    blue += (packed & 0x0F) * weight;
                }
            }
        }

        return ColorRGB8.fromRGB8(
                (int) Math.round(red * 17.0),
                (int) Math.round(green * 17.0),
                (int) Math.round(blue * 17.0)
        );
    }
}
