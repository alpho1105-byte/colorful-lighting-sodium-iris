package dev.colorfullighting.compat;

import me.erykczy.colorfullighting.common.util.PackedLightData;

/**
 * Allocation-free decoders for hot paths (Sodium's light cache runs these per cached
 * block). The bit positions come from PackedLightData, the layout's single owner; only
 * the shift/mask arithmetic is repeated here to avoid unpackData's per-call object.
 */
public final class PackedLightCompat {
    private PackedLightCompat() {
    }

    public static boolean isColorful(int packedLight) {
        return PackedLightData.isColorful(packedLight);
    }

    public static int blockLight(int packedLight) {
        if (!isColorful(packedLight)) {
            return (packedLight >>> PackedLightData.VANILLA_BLOCK4_SHIFT) & 0xF;
        }

        int red = (packedLight >>> PackedLightData.RED8_SHIFT) & 0xFF;
        int green = (packedLight >>> PackedLightData.GREEN8_SHIFT) & 0xFF;
        int blue = (packedLight >>> PackedLightData.BLUE8_SHIFT) & 0xFF;
        return Math.max(red, Math.max(green, blue)) >>> 4;
    }

    public static int skyLight(int packedLight) {
        if (!isColorful(packedLight)) {
            return (packedLight >>> PackedLightData.VANILLA_SKY4_SHIFT) & 0xF;
        }

        return (packedLight >>> PackedLightData.SKY4_SHIFT) & 0xF;
    }

    public static int toVanilla(int packedLight) {
        if (!isColorful(packedLight)) {
            return packedLight;
        }

        return (blockLight(packedLight) << PackedLightData.VANILLA_BLOCK4_SHIFT)
                | (skyLight(packedLight) << PackedLightData.VANILLA_SKY4_SHIFT);
    }

    /**
     * Scales only sky light while retaining the input format. Sable applies this
     * operation to moving sublevels; its vanilla mask would otherwise overwrite
     * Colorful Lighting's blue channel and format marker.
     */
    public static int scaleSkyLight(int packedLight, int skyLightScale) {
        int scaledSky = Math.clamp(skyLight(packedLight) * skyLightScale / 15, 0, 15);
        if (!isColorful(packedLight)) {
            // clears bits 20-31 (sky nibble and above), matching the vanilla layout
            return (packedLight & 0x000FFFFF)
                    | (scaledSky << PackedLightData.VANILLA_SKY4_SHIFT);
        }

        int clearedSky = packedLight & ~(0xF << PackedLightData.SKY4_SHIFT);
        return clearedSky | (scaledSky << PackedLightData.SKY4_SHIFT);
    }
}
