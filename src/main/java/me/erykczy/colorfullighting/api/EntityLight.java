package me.erykczy.colorfullighting.api;

import net.minecraft.world.item.DyeColor;

/**
 * The colored light an entity currently emits: a 4-bit RGB color and a 0..15 light
 * level. Values outside the valid ranges are clamped.
 */
public record EntityLight(int red4, int green4, int blue4, int brightness4) {
    public EntityLight {
        red4 = Math.clamp(red4, 0, 15);
        green4 = Math.clamp(green4, 0, 15);
        blue4 = Math.clamp(blue4, 0, 15);
        brightness4 = Math.clamp(brightness4, 0, 15);
    }

    /** 4-bit channels (0..15) and light level (0..15). */
    public static EntityLight of(int red4, int green4, int blue4, int brightness4) {
        return new EntityLight(red4, green4, blue4, brightness4);
    }

    /** 8-bit channels (0..255) and light level (0..15). */
    public static EntityLight fromRGB8(int red8, int green8, int blue8, int brightness4) {
        return new EntityLight((red8 + 8) / 17, (green8 + 8) / 17, (blue8 + 8) / 17, brightness4);
    }

    /** "#RRGGBB" (or "RRGGBB") hex color and light level (0..15). */
    public static EntityLight fromHex(String hex, int brightness4) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        int rgb = Integer.parseInt(digits, 16);
        return fromRGB8((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, brightness4);
    }

    /** A dye color (same mapping the JSON dye names use) and light level (0..15). */
    public static EntityLight fromDye(DyeColor dye, int brightness4) {
        int rgb = dye.getTextColor();
        return fromRGB8((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, brightness4);
    }

    public boolean isDark() {
        return brightness4 == 0 || (red4 == 0 && green4 == 0 && blue4 == 0);
    }
}
