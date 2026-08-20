package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.api.EntityLight;

/**
 * Codec for the canonical {@code #RRGGBB;L} light value - the single owner of the hex
 * wire format. Resource JSON, the user config, and the GUI all parse and print hex
 * colors through the helpers below (dye-name aliases resolve in JsonHelper before
 * reaching this format; the internal domain is always 4-bit 0..15 per channel).
 */
public final class EntityLightTextCodec {
    private EntityLightTextCodec() {
    }

    public static String encode(EntityLight light) {
        return encodeRgb8(light.red4() * 17, light.green4() * 17, light.blue4() * 17)
                + String.format(";%X", light.brightness4());
    }

    /** 8-bit channels to the canonical lowercase {@code #rrggbb} form. */
    public static String encodeRgb8(int red8, int green8, int blue8) {
        return String.format("#%02x%02x%02x", red8, green8, blue8);
    }

    /**
     * Strict {@code #RRGGBB} / {@code RRGGBB} to packed {@code 0xRRGGBB}, or -1 when
     * the string is not that shape. Lenient callers (resource loading, GUI input)
     * branch on -1; strict callers use {@link #parseRgb8}.
     */
    public static int tryParseRgb8(String value) {
        String digits = value.startsWith("#") ? value.substring(1) : value;
        if(digits.length() != 6) return -1;
        int rgb = 0;
        for(int index = 0; index < 6; index++) {
            int digit = Character.digit(digits.charAt(index), 16);
            if(digit < 0) return -1;
            rgb = rgb << 4 | digit;
        }
        return rgb;
    }

    /** Strict {@code #RRGGBB} to packed {@code 0xRRGGBB}; throws on any other shape. */
    public static int parseRgb8(String value) {
        int rgb = tryParseRgb8(value);
        if(rgb < 0)
            throw new IllegalArgumentException(
                    "Expected \"#RRGGBB\" (six hexadecimal digits), got \"" + value + "\"");
        return rgb;
    }

    /** Single hex digit {@code 0}-{@code F} to 0..15, or null when not that shape. */
    public static Integer tryParseLevelDigit(String value) {
        if(value.length() != 1) return null;
        int digit = Character.digit(value.charAt(0), 16);
        return digit < 0 ? null : digit;
    }

    public static EntityLight decode(String value) {
        String[] parts = value.strip().split(";", -1);
        if(parts.length > 2 || parts[0].isEmpty())
            throw new IllegalArgumentException("Expected #RRGGBB or #RRGGBB;L");

        int rgb = parseRgb8(parts[0]);

        int brightness = 15;
        if(parts.length == 2) {
            Integer level = tryParseLevelDigit(parts[1]);
            if(level == null)
                throw new IllegalArgumentException(
                        "The level suffix is a single hex digit 0-F (\"a\"=10, \"f\"=15),"
                                + " not decimal; got \";" + parts[1] + "\"");
            brightness = level;
        }
        return EntityLight.fromRGB8(
                (rgb >>> 16) & 0xFF,
                (rgb >>> 8) & 0xFF,
                rgb & 0xFF,
                brightness
        );
    }
}
