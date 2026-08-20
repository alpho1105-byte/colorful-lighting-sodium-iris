package dev.colorfullighting.compat;

import dev.colorfullighting.compat.iris.IrisPatchState;
import dev.colorfullighting.compat.level.RenderLevelScope;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Shader packs replace the vanilla core shaders that decode Colorful Lighting's packed
 * format. The primary defense is sanitizing the pack's own programs
 * (TransformPatcherMixin); when any program could not be sanitized, this fallback
 * converts vanilla-pipeline light lookups back to vanilla packed light on the CPU.
 * The Sodium terrain sampler calls the three-argument getLightColor overload directly,
 * which the fallback never touches, so terrain always keeps the colorful value.
 */
public final class ColorfulLightGate {
    // checked before any Iris class reference so the merged jar runs without Iris;
    // resolved lazily (first render call), when the mod list is fully built
    private static final boolean IRIS_LOADED = net.neoforged.fml.ModList.get().isLoaded("iris");

    private ColorfulLightGate() {
    }

    /**
     * The colored engine stores data for one live client level. Render snapshots
     * backed by that level are safe; Ponder, schematic, and other virtual worlds
     * must retain their own vanilla/fake lighting.
     */
    public static boolean canSampleColorful(Object renderedLevel, BlockPos pos) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        return engine != null && canSampleColorful(renderedLevel, engine.hasLightData(pos));
    }

    public static boolean canSampleColorful(Object renderedLevel, Vec3 pos) {
        return canSampleColorful(renderedLevel, pos.x, pos.y, pos.z);
    }

    /** Primitive overload for callers that gate and sample separately. */
    public static boolean canSampleColorful(Object renderedLevel, double x, double y, double z) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        return engine != null
                && scopeAllows(renderedLevel)
                && engine.hasLightData(x, y, z);
    }

    /**
     * Fused gate + sample for the per-vertex meshing path: one corner walk instead of
     * the previous hasLightData-then-sample pair. Null means "keep vanilla light" -
     * wrong level scope, no engine, or an unallocated corner section, exactly the
     * cases the boolean gate used to reject.
     */
    @Nullable
    public static ColorRGB8 trySampleColorful(Object renderedLevel, double x, double y, double z) {
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        if (engine == null || !scopeAllows(renderedLevel)) {
            return null;
        }
        return engine.trySampleTrilinearLightColor(x, y, z);
    }

    private static boolean canSampleColorful(Object renderedLevel, boolean hasColoredLightData) {
        return hasColoredLightData && scopeAllows(renderedLevel);
    }

    private static boolean scopeAllows(Object renderedLevel) {
        if (ColorfulLighting.clientAccessor == null) {
            return false;
        }

        LevelAccessor activeLevel = ColorfulLighting.clientAccessor.getLevel();
        return activeLevel != null
                && RenderLevelScope.belongsTo(renderedLevel, activeLevel.getWrappedLevel());
    }

    /**
     * True while entity lights may additionally render as smooth shader point lights
     * (the pack's GetHeldLighting hook was injected and a pack is active). CPU moving
     * lights are disabled in this mode to avoid rendering the same source twice. Never
     * touches Iris classes when Iris is absent.
     */
    public static boolean shaderEntityLightsActive() {
        return IRIS_LOADED
                && IrisPatchState.dynamicLightCount() > 0
                && IrisApi.getInstance().isShaderPackInUse();
    }

    public static int decodeForShaderPack(int packedLight) {
        if (!IRIS_LOADED
                || !PackedLightCompat.isColorful(packedLight)
                || !IrisPatchState.cpuDecodeNeeded()
                || !IrisApi.getInstance().isShaderPackInUse()) {
            return packedLight;
        }

        return PackedLightCompat.toVanilla(packedLight);
    }
}
