package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Dense section index over the rectangular view area, replacing the previous
 * {@code ConcurrentHashMap<Long, ColoredLightSection>}: a lookup is three subtractions,
 * three bounds checks, and one array read - no hashing and no boxed {@code Long} per
 * probe, which matters because the terrain meshing path performs many probes per
 * vertex on worker threads.
 *
 * <p>Concurrency: readers (mesh workers, the propagation thread, the client thread)
 * take one volatile {@link Index} snapshot per probe. Only the client thread
 * republishes, via {@link #rebuild} on view-area changes, reusing every overlapping
 * section object so in-flight readers keep a coherent - at worst slightly stale -
 * view; that matches the previous map's visibility semantics. Light-value writes never
 * touch the index: section payloads are individually thread-safe (one volatile
 * {@code short[]} with one short per block, so entries cannot tear).
 */
public class ColoredLightStorage {
    /** Missing-entry sentinel of the packed accessors; real values are 12-bit, >= 0. */
    public static final int MISSING = me.erykczy.colorfullighting.common.util.TrilinearLightSampler.MISSING;

    private record Index(
            int minSectionX, int minSectionY, int minSectionZ,
            int sizeX, int sizeY, int sizeZ,
            ColoredLightSection[] sections
    ) {
        static final Index EMPTY = new Index(0, 0, 0, 0, 0, 0, new ColoredLightSection[0]);

        @Nullable
        ColoredLightSection section(int sectionX, int sectionY, int sectionZ) {
            int localX = sectionX - minSectionX;
            int localY = sectionY - minSectionY;
            int localZ = sectionZ - minSectionZ;
            if(localX < 0 || localX >= sizeX
                    || localY < 0 || localY >= sizeY
                    || localZ < 0 || localZ >= sizeZ) {
                return null;
            }
            return sections[(localY * sizeZ + localZ) * sizeX + localX];
        }
    }

    private volatile Index index = Index.EMPTY;

    /**
     * Installs a dense index covering the given inclusive section bounds. Sections in
     * the overlap with the previous bounds are carried over (their light data stays
     * valid); everything else starts fresh. Client thread only.
     */
    public void rebuild(
            int minSectionX, int maxSectionX,
            int minSectionY, int maxSectionY,
            int minSectionZ, int maxSectionZ
    ) {
        Index old = index;
        int sizeX = maxSectionX - minSectionX + 1;
        int sizeY = maxSectionY - minSectionY + 1;
        int sizeZ = maxSectionZ - minSectionZ + 1;
        if(sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            index = Index.EMPTY;
            return;
        }

        ColoredLightSection[] sections = new ColoredLightSection[sizeX * sizeY * sizeZ];
        int flat = 0;
        for(int y = 0; y < sizeY; y++) {
            for(int z = 0; z < sizeZ; z++) {
                for(int x = 0; x < sizeX; x++) {
                    ColoredLightSection existing = old.section(
                            minSectionX + x, minSectionY + y, minSectionZ + z
                    );
                    sections[flat++] = existing != null ? existing : new ColoredLightSection();
                }
            }
        }
        index = new Index(minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ, sections);
    }

    @Nullable
    public ColorRGB4 getEntry(BlockPos blockPos) { return getEntry(blockPos.getX(), blockPos.getY(), blockPos.getZ()); }
    @Nullable
    public ColorRGB4 getEntry(int x, int y, int z) {
        int packed = getPackedEntry(x, y, z);
        if(packed == MISSING) return null;
        return ColorRGB4.fromRGB4((packed >>> 8) & 0x0F, (packed >>> 4) & 0x0F, packed & 0x0F);
    }

    /** 12-bit {@code r<<8|g<<4|b}, or {@link #MISSING} outside the allocated area. */
    public int getPackedEntry(int x, int y, int z) {
        // >> 4 is SectionPos.blockToSectionCoord, & 15 is SectionPos.sectionRelative
        ColoredLightSection section = index.section(x >> 4, y >> 4, z >> 4);
        if(section == null) return MISSING;
        return section.getPacked(x & 15, y & 15, z & 15);
    }

    public void setEntryUnsafe(BlockPos blockPos, ColorRGB4 value) { setEntryUnsafe(blockPos.getX(), blockPos.getY(), blockPos.getZ(), value); }
    public void setEntryUnsafe(int x, int y, int z, ColorRGB4 value) {
        ColoredLightSection section = index.section(x >> 4, y >> 4, z >> 4);
        if(section == null) return; // writes outside the allocated area are dropped
        section.set(x & 15, y & 15, z & 15, value);
    }

    public boolean containsEntry(BlockPos blockPos) { return containsEntry(blockPos.getX(), blockPos.getY(), blockPos.getZ()); }
    public boolean containsEntry(int x, int y, int z) {
        return index.section(x >> 4, y >> 4, z >> 4) != null;
    }

    public void clear() {
        index = Index.EMPTY;
    }
}
