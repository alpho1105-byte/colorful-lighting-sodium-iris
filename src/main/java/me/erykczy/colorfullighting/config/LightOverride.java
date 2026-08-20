package me.erykczy.colorfullighting.config;

import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import org.jetbrains.annotations.Nullable;

/** A partial user override. Missing fields continue to use the underlying definition. */
public record LightOverride(
        @Nullable ColorRGB4 color,
        @Nullable Integer brightness,
        boolean disabled
) {
    public LightOverride {
        if(brightness != null && (brightness < 0 || brightness > 15))
            throw new IllegalArgumentException("Brightness must be in range 0..15");
        if(disabled) {
            color = null;
            brightness = null;
        }
    }

    public static LightOverride empty() {
        return new LightOverride(null, null, false);
    }

    public static LightOverride disabledOverride() {
        return new LightOverride(null, null, true);
    }

    public boolean isEmpty() {
        return color == null && brightness == null && !disabled;
    }

    @Nullable
    public EntityLight apply(@Nullable EntityLight base) {
        if(disabled) return null;
        if(base == null && brightness == null) return null;
        ColorRGB4 resolvedColor = color != null
                ? color
                : base == null
                        ? ColorRGB4.fromRGB4(15, 15, 15)
                        : ColorRGB4.fromRGB4(base.red4(), base.green4(), base.blue4());
        int resolvedBrightness = brightness != null
                ? brightness
                : base == null ? 0 : base.brightness4();
        EntityLight result = EntityLight.of(
                resolvedColor.red4,
                resolvedColor.green4,
                resolvedColor.blue4,
                resolvedBrightness
        );
        return result.isDark() ? null : result;
    }
}
