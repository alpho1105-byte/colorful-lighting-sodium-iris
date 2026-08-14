package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.common.util.ColorRGB4;

/**
 * Stores light color for each block in the section.
 * One short per block (12 bits used): unlike the previous cross-byte 12-bit packing,
 * entries never share storage units, so concurrent readers (mesh building) and the
 * writer threads can never tear a value or clobber a neighbouring entry.
 */
public class ColoredLightSection {
    private static final int ENTRY_COUNT = 16 * 16 * 16;
    public volatile short[] data;

    public ColoredLightSection() {}

    public int getColorIndex(int x, int y, int z) {
        return (y << 8 | z << 4 | x);
    }

    public ColorRGB4 get(int x, int y, int z) { return get(getColorIndex(x, y, z)); }
    public ColorRGB4 get(int colorIndex) {
        short[] entries = data;
        if(entries == null)
            return ColorRGB4.fromRGB4(0, 0, 0);
        int value = entries[colorIndex] & 0xFFF;
        return ColorRGB4.fromRGB4((value >>> 8) & 0x0F, (value >>> 4) & 0x0F, value & 0x0F);
    }

    public void set(int x, int y, int z, ColorRGB4 value) { set(getColorIndex(x, y, z), value); }
    public void set(int colorIndex, ColorRGB4 value) {
        if(!value.isInValidState()) {
            throw new IllegalArgumentException("Invalid ColoredLightSection.Entry: "+value);
        }
        short[] entries = data;
        if(entries == null) {
            synchronized (this) {
                if(data == null)
                    data = new short[ENTRY_COUNT];
                entries = data;
            }
        }
        entries[colorIndex] = (short) ((value.red4 << 8) | (value.green4 << 4) | value.blue4);
    }


    public void clear() {
        this.data = null;
    }
}
