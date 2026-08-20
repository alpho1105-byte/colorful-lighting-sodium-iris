package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightingSodiumCompat;
import dev.colorfullighting.compat.iris.IrisShaderCompat;
import dev.colorfullighting.compat.iris.IrisPatchState;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(value = SodiumPrograms.class, remap = false)
abstract class IrisSodiumProgramsMixin {
    @Unique
    private static final AtomicBoolean colorfulLightingSodiumCompat$reported = new AtomicBoolean();

    @Inject(method = "transformShaders", at = @At("RETURN"), cancellable = true)
    private void colorfulLightingSodiumCompat$patchTerrainShaders(
            ProgramSource programSource,
            AlphaTest alphaTest,
            ProgramSet programSet,
            CallbackInfoReturnable<Map<PatchShaderType, String>> callbackInfo
    ) {
        Map<PatchShaderType, String> original = callbackInfo.getReturnValue();
        String vertex = original.get(PatchShaderType.VERTEX);
        String fragment = original.get(PatchShaderType.FRAGMENT);
        if (!IrisShaderCompat.supports(vertex, fragment)) {
            return;
        }

        Map<PatchShaderType, String> patched = new EnumMap<>(PatchShaderType.class);
        patched.putAll(original);
        // the vertex must know whether the fragment will consume the RGB varying,
        // otherwise a MakeUp-style vertex paired with a Complementary-style fragment
        // leaves the fragment's `in` without a matching `out`
        boolean fragmentTint = IrisShaderCompat.supportsVanillaTint(fragment);
        patched.put(PatchShaderType.VERTEX, IrisShaderCompat.patchVertex(vertex, fragmentTint));
        patched.put(PatchShaderType.FRAGMENT, IrisShaderCompat.patchFragment(fragment));
        callbackInfo.setReturnValue(patched);
        IrisPatchState.recordTerrainTint();

        if (colorfulLightingSodiumCompat$reported.compareAndSet(false, true)) {
            ColorfulLightingSodiumCompat.LOGGER.info(
                    "Iris shader-pack colored lighting patches enabled"
            );
        }
    }
}
