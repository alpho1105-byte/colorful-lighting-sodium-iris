package dev.colorfullighting.compat;

public final class PackedLightCompat {
    private static final int COLORFUL_MARKER = 0xF;

    private PackedLightCompat() {
    }

    public static boolean isColorful(int packedLight) {
        return (packedLight >>> 28) == COLORFUL_MARKER;
    }

    public static int blockLight(int packedLight) {
        if (!isColorful(packedLight)) {
            return (packedLight >>> 4) & 0xF;
        }

        int red = packedLight & 0xFF;
        int green = (packedLight >>> 8) & 0xFF;
        int blue = (packedLight >>> 20) & 0xFF;
        return Math.max(red, Math.max(green, blue)) >>> 4;
    }

    public static int skyLight(int packedLight) {
        if (!isColorful(packedLight)) {
            return (packedLight >>> 20) & 0xF;
        }

        return (packedLight >>> 16) & 0xF;
    }

    public static int toVanilla(int packedLight) {
        if (!isColorful(packedLight)) {
            return packedLight;
        }

        return (blockLight(packedLight) << 4) | (skyLight(packedLight) << 20);
    }
}
