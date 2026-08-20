package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.util.ColorRGB4;

/** Color/intensity operations shared by all moving light sources. */
public final class DynamicLightMath {
    private DynamicLightMath() {
    }

    /** Converts a tint plus light level into the engine's effective RGB intensities. */
    public static ColorRGB4 effectiveColor(EntityLight light) {
        return effectiveColorAt(light, 0.0);
    }

    /**
     * Applies the same continuous linear falloff used by the shader-pack point-light
     * path. Distance is measured from the source's real (non-quantized) position.
     */
    public static ColorRGB4 effectiveColorAt(EntityLight light, double distance) {
        double remainingLevel = Math.max(0.0, light.brightness4() - distance);
        double scale = remainingLevel / 15.0;
        return ColorRGB4.fromRGB4(
                (int) Math.round(light.red4() * scale),
                (int) Math.round(light.green4() * scale),
                (int) Math.round(light.blue4() * scale)
        );
    }

    /**
     * Max-combines effective channel intensities, then converts them back to a tint and
     * range. This prevents a dim secondary hue from inheriting another source's range.
     */
    public static EntityLight max(EntityLight first, EntityLight second) {
        ColorRGB4 a = effectiveColor(first);
        ColorRGB4 b = effectiveColor(second);
        return fromEffectiveColor(ColorRGB4.fromRGB4(
                Math.max(a.red4, b.red4),
                Math.max(a.green4, b.green4),
                Math.max(a.blue4, b.blue4)
        ));
    }

    /** Reconstructs a normalized tint and light range from effective RGB intensities. */
    public static EntityLight fromEffectiveColor(ColorRGB4 color) {
        int peak = Math.max(color.red4, Math.max(color.green4, color.blue4));
        if(peak <= 0) return EntityLight.of(0, 0, 0, 0);
        return EntityLight.of(
                Math.round(color.red4 * 15.0f / peak),
                Math.round(color.green4 * 15.0f / peak),
                Math.round(color.blue4 * 15.0f / peak),
                peak
        );
    }
}
