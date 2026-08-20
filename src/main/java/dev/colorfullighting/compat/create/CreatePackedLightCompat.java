package dev.colorfullighting.compat.create;

import me.erykczy.colorfullighting.common.util.PackedLightData;

/** Packed-light policy for Create's moving contraption fallback renderer. */
public final class CreatePackedLightCompat {
    private CreatePackedLightCompat() {
    }

    public static int combineTemplateAndWorld(
            int templateLight,
            int worldLight,
            boolean shaderPointLightsActive
    ) {
        // Supported shader packs receive the moving source as a smooth point light.
        // Keeping Create's neutral, virtual-world self-light would add a second white
        // copy and wash out the point light's hue.
        if(shaderPointLightsActive) return worldLight;

        boolean templateColorful = PackedLightData.isColorful(templateLight);
        boolean worldColorful = PackedLightData.isColorful(worldLight);
        if(templateColorful == worldColorful)
            return PackedLightData.max(templateLight, worldLight);

        int colorfulLight = worldColorful ? worldLight : templateLight;
        int vanillaLight = worldColorful ? templateLight : worldLight;
        PackedLightData color = PackedLightData.unpackData(colorfulLight);
        int vanillaBlock8 = ((vanillaLight >>> PackedLightData.VANILLA_BLOCK4_SHIFT) & 0xF) * 17;
        int colorfulBlock8 = Math.max(color.red8, Math.max(color.green8, color.blue8));
        int targetBlock8 = Math.max(colorfulBlock8, vanillaBlock8);

        int red;
        int green;
        int blue;
        if(colorfulBlock8 == 0) {
            // A colorful marker may legitimately contain only sky light. With no hue
            // to preserve, retain a real vanilla block-light contribution as neutral.
            red = vanillaBlock8;
            green = vanillaBlock8;
            blue = vanillaBlock8;
        }
        else {
            red = scaleChannel(color.red8, colorfulBlock8, targetBlock8);
            green = scaleChannel(color.green8, colorfulBlock8, targetBlock8);
            blue = scaleChannel(color.blue8, colorfulBlock8, targetBlock8);
        }

        return PackedLightData.packData(
                Math.max(color.skyLight4, (vanillaLight >>> PackedLightData.VANILLA_SKY4_SHIFT) & 0xF),
                red,
                green,
                blue
        );
    }

    private static int scaleChannel(int channel, int sourceMaximum, int targetMaximum) {
        return Math.min(255, (channel * targetMaximum + sourceMaximum / 2) / sourceMaximum);
    }
}
