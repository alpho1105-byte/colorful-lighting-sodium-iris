package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightingSodiumCompat;
import dev.colorfullighting.compat.iris.IrisPatchState;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Program patching runs eagerly inside this constructor (ShaderMap/SodiumPrograms are
// built in <init>), so the counts accumulated since beginConstruction belong to this
// instance. Constructor injection is limited to RETURN, which is sufficient: commit
// snapshots and clears in one step, and PipelineManager.preparePipeline's HEAD hook
// covers residue from a constructor that threw.
@Mixin(value = IrisRenderingPipeline.class, remap = false)
abstract class IrisRenderingPipelineMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void colorfulLightingSodiumCompat$commitPatchState(CallbackInfo callbackInfo) {
        IrisPatchState.Snapshot snapshot = IrisPatchState.commit(this);
        ColorfulLightingSodiumCompat.LOGGER.info(
                "Sanitized {} shader-pack programs for Colorful Lighting packed light"
                        + " ({} with colored-light tint, {} terrain programs,"
                        + " {} with per-pixel entity lights, {} failures)",
                snapshot.sanitized(),
                snapshot.tinted(),
                snapshot.terrainTint(),
                snapshot.dynamicLights(),
                snapshot.failures()
        );
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$forgetPatchState(CallbackInfo callbackInfo) {
        IrisPatchState.forget(this);
    }
}
