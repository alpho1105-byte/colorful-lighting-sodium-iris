package dev.colorfullighting.compat.sodium;

/**
 * The position/texture/light packing of Sodium's CompactChunkVertex, shared by every
 * Colorful Lighting chunk-vertex encoder (plain Sodium and Veil layouts). Single
 * source: these values are decoded by Sodium's own chunk shaders, so any drift from
 * CompactChunkVertex - or between the two encoders - shows up as vertex corruption or
 * texture seams (a floor-vs-round divergence here has bitten once already).
 */
public final class CompactVertexPacking {
    public static final int POSITION_MAX_VALUE = 1_048_575;
    public static final int TEXTURE_MAX_VALUE = 32_767;

    private CompactVertexPacking() {
    }

    public static int quantizePosition(float value) {
        return (int) (((8.0F + value) / 32.0F) * 1_048_576.0F) & POSITION_MAX_VALUE;
    }

    public static int packPositionHi(int x, int y, int z) {
        return ((x >>> 10) & 0x3FF)
                | (((y >>> 10) & 0x3FF) << 10)
                | (((z >>> 10) & 0x3FF) << 20);
    }

    public static int packPositionLo(int x, int y, int z) {
        return (x & 0x3FF)
                | ((y & 0x3FF) << 10)
                | ((z & 0x3FF) << 20);
    }

    public static int encodeTexture(float center, float value) {
        int bias = value < center ? 1 : -1;
        // Math.round, not floor: Sodium's CompactChunkVertex rounds, and the GLSL
        // decode assumes it
        int encoded = Math.round(value * 32_768.0F) + bias;
        return (encoded & TEXTURE_MAX_VALUE) | ((bias >>> 31) << 15);
    }

    public static int packTexture(int u, int v) {
        return (u & 0xFFFF) | ((v & 0xFFFF) << 16);
    }

    public static int encodeLight(int light) {
        int sky = Math.clamp((light >>> 16) & 0xFF, 8, 248);
        int block = Math.clamp(light & 0xFF, 8, 248);
        return block | (sky << 8);
    }

    public static int packLightAndData(int light, int material, int sectionIndex) {
        return (light & 0xFFFF) | ((material & 0xFF) << 16) | ((sectionIndex & 0xFF) << 24);
    }
}
