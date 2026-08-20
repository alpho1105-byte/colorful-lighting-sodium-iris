package me.erykczy.colorfullighting.common.util;

public class PackedLightData {
    // Colorful packed layout, the single source of truth for these bit positions.
    // Mirrored by PackedLightCompat's allocation-free decoders and the GLSL decodes in
    // colored_light.glsl / IrisShaderCompat; keep all of them in sync when changing.
    public static final int RED8_SHIFT = 0;
    public static final int GREEN8_SHIFT = 8;
    public static final int SKY4_SHIFT = 16;
    public static final int BLUE8_SHIFT = 20;
    public static final int MARKER_SHIFT = 28;
    public static final int MARKER = 0xF;
    // Vanilla packed-light layout (LightTexture.pack): 4-bit block and sky nibbles.
    public static final int VANILLA_BLOCK4_SHIFT = 4;
    public static final int VANILLA_SKY4_SHIFT = 20;

    public int skyLight4;
    public int red8;
    public int blue8;
    public int green8;
    public int alpha4;

    public static int packData(int skyLight4, ColorRGB8 color) { return packData(skyLight4, color.red, color.green, color.blue); }
    public static int packData(int skyLight4, int red8, int green8, int blue8) {
        skyLight4 = Math.clamp(skyLight4, 0, 15);
        red8 = Math.clamp(red8, 0, 255);
        green8 = Math.clamp(green8, 0, 255);
        blue8 = Math.clamp(blue8, 0, 255);
        return red8 << RED8_SHIFT
                | green8 << GREEN8_SHIFT
                | skyLight4 << SKY4_SHIFT
                | blue8 << BLUE8_SHIFT
                | MARKER << MARKER_SHIFT;
    }

    public static PackedLightData unpackData(int packedData) {
        PackedLightData data = new PackedLightData();
        data.red8 = (packedData >>> RED8_SHIFT) & 0xFF;
        data.green8 = (packedData >>> GREEN8_SHIFT) & 0xFF;
        data.skyLight4 = (packedData >>> SKY4_SHIFT) & 0xF;
        data.blue8 = (packedData >>> BLUE8_SHIFT) & 0xFF;
        data.alpha4 = (packedData >>> MARKER_SHIFT) & 0xF;
        return data;
    }

    public static boolean isBlack(int packedData) {
        return packedData == 0xF0000000 || packedData == 0;
    }

    /**
     * Whether the value carries this mod's packed format (alpha nibble marker). While the
     * CPU shader-pack fallback is active, vanilla-format values flow through the same
     * combining helpers, and reading them as the colorful layout would produce garbage.
     */
    public static boolean isColorful(int packedData) {
        return (packedData >>> MARKER_SHIFT) == MARKER;
    }

    public static int blend(int lightColor0, int lightColor1, int lightColor2, int lightColor3) {
        if(!isColorful(lightColor0) && !isColorful(lightColor1) && !isColorful(lightColor2) && !isColorful(lightColor3)) {
            // vanilla-format inputs: replicate vanilla AmbientOcclusionFace.blend exactly
            if (lightColor0 == 0) lightColor0 = lightColor3;
            if (lightColor1 == 0) lightColor1 = lightColor3;
            if (lightColor2 == 0) lightColor2 = lightColor3;
            return lightColor0 + lightColor1 + lightColor2 + lightColor3 >> 2 & 0xFF00FF;
        }
        if (isBlack(lightColor0)) lightColor0 = lightColor3;
        if (isBlack(lightColor1)) lightColor1 = lightColor3;
        if (isBlack(lightColor2)) lightColor2 = lightColor3;

        // mixed formats occur at colored-data boundaries (view-area edge, build
        // limits): any vanilla corner must be converted, not decoded as colorful
        var data0 = unpackToColorful(lightColor0);
        var data1 = unpackToColorful(lightColor1);
        var data2 = unpackToColorful(lightColor2);
        var data3 = unpackToColorful(lightColor3);

        return packData(
                (data0.skyLight4 + data1.skyLight4 + data2.skyLight4 + data3.skyLight4) >> 2,
                (data0.red8 + data1.red8 + data2.red8 + data3.red8) >> 2,
                (data0.green8 + data1.green8 + data2.green8 + data3.green8) >> 2,
                (data0.blue8 + data1.blue8 + data2.blue8 + data3.blue8) >> 2
        );
    }

    public static int blend(int lightColor0, int lightColor1, int lightColor2, int lightColor3, float weight0, float weight1, float weight2, float weight3) {
        if(!isColorful(lightColor0) && !isColorful(lightColor1) && !isColorful(lightColor2) && !isColorful(lightColor3)) {
            // vanilla-format inputs: replicate vanilla's weighted blend exactly
            int sky = (int) ((lightColor0 >> 16 & 0xFF) * weight0 + (lightColor1 >> 16 & 0xFF) * weight1
                    + (lightColor2 >> 16 & 0xFF) * weight2 + (lightColor3 >> 16 & 0xFF) * weight3) & 0xFF;
            int block = (int) ((lightColor0 & 0xFF) * weight0 + (lightColor1 & 0xFF) * weight1
                    + (lightColor2 & 0xFF) * weight2 + (lightColor3 & 0xFF) * weight3) & 0xFF;
            return sky << 16 | block;
        }
        var data0 = unpackToColorful(lightColor0);
        var data1 = unpackToColorful(lightColor1);
        var data2 = unpackToColorful(lightColor2);
        var data3 = unpackToColorful(lightColor3);

        return packData(
                (int)(data0.skyLight4 * weight0 + data1.skyLight4 * weight1 + data2.skyLight4 * weight2 + data3.skyLight4 * weight3),
                (int)(data0.red8 * weight0 + data1.red8 * weight1 + data2.red8 * weight2 + data3.red8 * weight3),
                (int)(data0.green8 * weight0 + data1.green8 * weight1 + data2.green8 * weight2 + data3.green8 * weight3),
                (int)(data0.blue8 * weight0 + data1.blue8 * weight1 + data2.blue8 * weight2 + data3.blue8 * weight3)
        );
    }

    public static int max(int lightColor0, int lightColor1) {
        if(!isColorful(lightColor0) && !isColorful(lightColor1)) {
            // vanilla-format inputs: per-component max in the vanilla layout
            int block = Math.max((lightColor0 >>> VANILLA_BLOCK4_SHIFT) & 0xF, (lightColor1 >>> VANILLA_BLOCK4_SHIFT) & 0xF);
            int sky = Math.max((lightColor0 >>> VANILLA_SKY4_SHIFT) & 0xF, (lightColor1 >>> VANILLA_SKY4_SHIFT) & 0xF);
            return (block << VANILLA_BLOCK4_SHIFT) | (sky << VANILLA_SKY4_SHIFT);
        }
        var firstData = unpackToColorful(lightColor0);
        var secondData = unpackToColorful(lightColor1);

        return packData(
                Math.max(firstData.skyLight4, secondData.skyLight4),
                Math.max(firstData.red8, secondData.red8),
                Math.max(firstData.green8, secondData.green8),
                Math.max(firstData.blue8, secondData.blue8)
        );
    }

    /** Unpacks either format, converting vanilla block light to neutral RGB. */
    private static PackedLightData unpackToColorful(int packedLight) {
        if(isColorful(packedLight)) {
            return unpackData(packedLight);
        }

        int blockLight8 = ((packedLight >>> VANILLA_BLOCK4_SHIFT) & 0xF) * 17;
        return unpackData(packData(
                (packedLight >>> VANILLA_SKY4_SHIFT) & 0xF,
                blockLight8,
                blockLight8,
                blockLight8
        ));
    }
}
