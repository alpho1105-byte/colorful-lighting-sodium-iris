package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.iris.HeldLightColors;
import dev.colorfullighting.compat.iris.family.ShaderPatchNames;
import me.erykczy.colorfullighting.common.EntityLightManager;
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
                ShaderPatchNames.HELD_COLOR_NAME,
                HeldLightColors::mainHand
        );
        uniforms.uniform3f(
                UniformUpdateFrequency.PER_FRAME,
                ShaderPatchNames.HELD_COLOR_NAME + "2",
                HeldLightColors::offHand
        );
        uniforms.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                ShaderPatchNames.HELD_LEVEL_NAME,
                HeldLightColors::mainHandLevel
        );
        uniforms.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                ShaderPatchNames.HELD_LEVEL_NAME + "2",
                HeldLightColors::offHandLevel
        );
        uniforms.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                ShaderPatchNames.HELD_AUTHORITY_NAME,
                HeldLightColors::mainHandAuthority
        );
        uniforms.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                ShaderPatchNames.HELD_AUTHORITY_NAME + "2",
                HeldLightColors::offHandAuthority
        );
        uniforms.uniform1i(
                UniformUpdateFrequency.PER_FRAME,
                ShaderPatchNames.DYN_LIGHT_NAME + "Count",
                EntityLightManager::shaderLightCount
        );
        // one vec4 uniform per light slot: Iris array uniforms upload a single vec4;
        // names and slot count come from ShaderPatchNames, the same constants the
        // shader families emit, so the two sides cannot drift
        for (int i = 0; i < ShaderPatchNames.MAX_DYNAMIC_LIGHTS; i++) {
            final int slot = i;
            uniforms.uniform4f(
                    UniformUpdateFrequency.PER_FRAME,
                    ShaderPatchNames.DYN_LIGHT_NAME + slot,
                    () -> EntityLightManager.shaderLightPosition(slot)
            );
            uniforms.uniform4f(
                    UniformUpdateFrequency.PER_FRAME,
                    ShaderPatchNames.DYN_LIGHT_NAME + "Color" + slot,
                    () -> EntityLightManager.shaderLightColor(slot)
            );
        }
    }
}
