package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.iris.IrisPatchState;
import dev.colorfullighting.compat.iris.IrisShaderCompat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

// Sanitizes every vanilla-format shader-pack program that reads iris_UV2, so colorful
// packed light is decoded on the GPU no matter which code path delivered it. Programs
// that expose the pack's blocklightCol/DoLighting hooks additionally receive the RGB
// tint, giving entities, block entities, and particles colored lighting.
@Mixin(value = TransformPatcher.class, remap = false)
abstract class TransformPatcherMixin {
    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true)
    private static void colorfulLightingSodiumCompat$sanitizeVanillaProgram(
            String name,
            String vertex,
            String geometry,
            String tessControl,
            String tessEval,
            String fragment,
            AlphaTest alphaTest,
            boolean hasChunkOffset,
            boolean isLines,
            ShaderAttributeInputs inputs,
            Object2ObjectMap<?, ?> customTextures,
            CallbackInfoReturnable<Map<PatchShaderType, String>> callbackInfo
    ) {
        Map<PatchShaderType, String> original = callbackInfo.getReturnValue();
        if (original == null) {
            return;
        }

        String transformedVertex = original.get(PatchShaderType.VERTEX);
        String transformedFragment = original.get(PatchShaderType.FRAGMENT);
        if (!IrisShaderCompat.usesVanillaLightCoords(transformedVertex)) {
            return;
        }

        // A varying cannot cross geometry or tessellation stages, so tint only direct
        // vertex-to-fragment programs; everything else still gets the light sanitized.
        boolean withTint = original.get(PatchShaderType.GEOMETRY) == null
                && original.get(PatchShaderType.TESS_CONTROL) == null
                && original.get(PatchShaderType.TESS_EVAL) == null
                && IrisShaderCompat.supportsVanillaTint(transformedFragment);

        String patchedVertex = IrisShaderCompat.sanitizeVanillaVertex(transformedVertex, withTint);
        if (patchedVertex.equals(transformedVertex)) {
            IrisPatchState.recordFailure();
            return;
        }

        String patchedFragment = transformedFragment;
        if (withTint) {
            patchedFragment = IrisShaderCompat.patchFragment(transformedFragment);
            if (patchedFragment.equals(transformedFragment)) {
                withTint = false;
                patchedVertex = IrisShaderCompat.sanitizeVanillaVertex(transformedVertex, false);
                patchedFragment = transformedFragment;
                if (patchedVertex.equals(transformedVertex)) {
                    IrisPatchState.recordFailure();
                    return;
                }
            }
        }

        Map<PatchShaderType, String> patched = new EnumMap<>(PatchShaderType.class);
        patched.putAll(original);
        patched.put(PatchShaderType.VERTEX, patchedVertex);
        patched.put(PatchShaderType.FRAGMENT, patchedFragment);
        callbackInfo.setReturnValue(patched);
        IrisPatchState.recordSanitized(withTint);
    }
}
