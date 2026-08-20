package dev.colorfullighting.compat.level;

/** Resolves renderer snapshots without depending on any optional renderer API. */
public final class RenderLevelScope {
    private RenderLevelScope() {
    }

    public static boolean belongsTo(Object renderedLevel, Object activeLevel) {
        if (renderedLevel == null || activeLevel == null) {
            return false;
        }

        Object rootLevel = renderedLevel instanceof RenderLevelView view
                ? view.colorfulLighting$getRootLevel()
                : renderedLevel;
        return rootLevel == activeLevel;
    }

    /**
     * A renderer may use the live level as its backing store while drawing blocks at
     * reserved coordinates outside the colored engine's allocated sections (Sable's
     * moving sublevels do this). Both the level identity and local data availability
     * are therefore required before emitting Colorful Lighting's packed marker.
     */
    public static boolean canSample(
            Object renderedLevel,
            Object activeLevel,
            boolean hasColoredLightData
    ) {
        return hasColoredLightData && belongsTo(renderedLevel, activeLevel);
    }
}
