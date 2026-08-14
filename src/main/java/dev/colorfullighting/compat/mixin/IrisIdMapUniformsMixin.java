package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.iris.HeldLightColors;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.IdMapUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Registers the held-item colorful-light uniforms alongside Iris's own held-item
// uniforms (heldBlockLightValue = main hand, heldBlockLightValue2 = off hand). Programs
// that do not declare them are unaffected; the patched fragments read them in
// GetHeldLighting.
@Mixin(value = IdMapUniforms.class, remap = false)
abstract class IrisIdMapUniformsMixin {
    @Inject(method = "addIdMapUniforms", at = @At("TAIL"))
    private static void colorfulLightingSodiumCompat$addHeldColorUniforms(
            FrameUpdateNotifier notifier,
            UniformHolder uniforms,
            IdMap idMap,
            boolean isOldHandLight,
            CallbackInfo callbackInfo
    ) {
        uniforms.uniform3f(
                UniformUpdateFrequency.PER_FRAME,
                "colorfulLightingSodiumCompat_HeldColor",
                HeldLightColors::mainHand
        );
        uniforms.uniform3f(
                UniformUpdateFrequency.PER_FRAME,
                "colorfulLightingSodiumCompat_HeldColor2",
                HeldLightColors::offHand
        );
    }
}
