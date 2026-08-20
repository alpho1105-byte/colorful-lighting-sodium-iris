package dev.colorfullighting.compat.level;

/**
 * Implemented by renderer snapshots that read blocks from another level.
 *
 * <p>The colored-light engine is owned by the live client level. Render-only
 * views of that same level may safely use it, while independent virtual levels
 * such as Ponder worlds must keep their own light implementation.</p>
 */
public interface RenderLevelView {
    Object colorfulLighting$getRootLevel();
}
