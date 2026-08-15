package dev.colorfullighting.compat;

import dev.colorfullighting.compat.iris.IrisPatchState;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

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

    public static int sampleColorful(BlockAndTintGetter level, BlockState state, BlockPos pos) {
        return LevelRenderer.getLightColor(level, state, pos);
    }

    /**
     * True while entity lights should render as per-pixel shader point lights instead of
     * feeding the block engine (the pack's GetHeldLighting hook was injected and a pack
     * is active). Never touches Iris classes when Iris is absent.
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
