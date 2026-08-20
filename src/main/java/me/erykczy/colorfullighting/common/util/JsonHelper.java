package me.erykczy.colorfullighting.common.util;

import com.google.gson.JsonElement;
import me.erykczy.colorfullighting.common.EntityLightTextCodec;
import net.minecraft.world.item.DyeColor;

import java.awt.*;

public abstract class JsonHelper {
    public static ColorRGB4 getColor4FromString(String string) {
        ColorRGB4 color = getColor4FromDyeName(string);
        if(color != null) return color;
        return getColor4FromHexString(string);
    }

    public static ColorRGB4 getColor4FromDyeName(String dyeName) {
        DyeColor dyeColor = DyeColor.byName(dyeName, null);
        if(dyeColor != null) {
            Color color = new Color(dyeColor.getTextColor());
            return ColorRGB4.fromRGB8(color.getRed(), color.getGreen(), color.getBlue());
        }
        return null;
    }

    public static ColorRGB4 getColor4FromHexString(String string) {
        // canonical hex parsing lives in EntityLightTextCodec; this wrapper adapts the
        // lenient null-returning contract resource loading relies on
        int rgb = EntityLightTextCodec.tryParseRgb8(string);
        if(rgb < 0) return null;
        return ColorRGB4.fromRGB8((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF);
    }

    public static ColorRGB4 getColor4FromJsonElements(JsonElement red, JsonElement green, JsonElement blue) {
        Integer redI = getInt4FromJsonElement(red);
        Integer greenI = getInt4FromJsonElement(green);
        Integer blueI = getInt4FromJsonElement(blue);
        if(redI == null || greenI == null || blueI == null) return null;
        ColorRGB4 color = ColorRGB4.fromRGB4(redI, greenI, blueI);
        if(!color.isInValidState()) return null;
        return color;
    }
    public static Integer getInt4FromJsonElement(JsonElement element) {
        int value;
        try {
            // rounding division, matching ColorRGB4.fromRGB8: plain /17 truncated
            // every 8-bit value below 17 to zero, silently turning a configured
            // component (or an array brightness like 15) into "no light"
            value = (element.getAsBigInteger().intValue() + 8) / 17;
        }
        catch (NumberFormatException e) {
            try {
                value = Math.round(element.getAsFloat() * 15.0f);
            } catch (RuntimeException e2) {
                return null; // non-numeric value must not abort the whole parse
            }
        }
        if(value >= 0 && value < 16) return value;
        return null;
    }
}
