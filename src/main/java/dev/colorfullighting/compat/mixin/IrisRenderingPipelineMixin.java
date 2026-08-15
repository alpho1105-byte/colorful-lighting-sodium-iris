package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightingSodiumCompat;
import dev.colorfullighting.compat.iris.IrisPatchState;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
abstract class IrisRenderingPipelineMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void colorfulLightingSodiumCompat$reportPatchState(CallbackInfo callbackInfo) {
        ColorfulLightingSodiumCompat.LOGGER.info(
                "Sanitized {} shader-pack programs for Colorful Lighting packed light"
                        + " ({} with colored-light tint, {} with per-pixel entity lights, {} failures)",
                IrisPatchState.sanitizedCount(),
                IrisPatchState.tintedCount(),
                IrisPatchState.dynamicLightCount(),
                IrisPatchState.failureCount()
        );
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$resetPatchState(CallbackInfo callbackInfo) {
        IrisPatchState.reset();
    }
}
