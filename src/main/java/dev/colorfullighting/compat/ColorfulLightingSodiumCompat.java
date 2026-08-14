package dev.colorfullighting.compat;

import me.erykczy.colorfullighting.ColorfulLighting;
import org.slf4j.Logger;

/**
 * Constants of the merged-in Sodium/Iris compatibility layer. MOD_ID survives as the
 * resource namespace of the layer's Sodium terrain shaders.
 */
public final class ColorfulLightingSodiumCompat {
    public static final String MOD_ID = "colorful_lighting_sodium_compat";
    public static final Logger LOGGER = ColorfulLighting.LOGGER;

    private ColorfulLightingSodiumCompat() {
    }
}
