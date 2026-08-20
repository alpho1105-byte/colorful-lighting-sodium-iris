package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.iris.IrisPatchState;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Clears the patch-state accumulators before a pipeline is (possibly) constructed, so
// counts left behind by a constructor that threw cannot bleed into the next pipeline's
// snapshot. Harmless when the call returns a cached pipeline: the accumulators are
// only read at construction commit.
@Mixin(value = PipelineManager.class, remap = false)
abstract class IrisPipelineManagerMixin {
    @Inject(method = "preparePipeline", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$beginPatchState(
            NamespacedId dimensionId,
            CallbackInfoReturnable<WorldRenderingPipeline> callbackInfo
    ) {
        IrisPatchState.beginConstruction();
    }
}
