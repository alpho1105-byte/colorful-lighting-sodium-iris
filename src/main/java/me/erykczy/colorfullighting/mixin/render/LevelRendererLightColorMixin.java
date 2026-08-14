package me.erykczy.colorfullighting.mixin.render;

import dev.colorfullighting.compat.ColorfulLightGate;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// CPU fallback for shader packs whose programs could not all be sanitized: decodes the
// colorful packed value back to vanilla packed light for block entities, particles, and
// falling blocks, which all use this two-argument overload. Direct three-argument
// callers (the Sodium light cache and the terrain sampler) intentionally keep the
// colorful value, so the decode lives here and not in the three-argument mixin.
@Mixin(LevelRenderer.class)
public class LevelRendererLightColorMixin {
    @Inject(
            method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void colorfullighting$decodeForShaderPack(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int packedLight = cir.getReturnValueI();
        int decoded = ColorfulLightGate.decodeForShaderPack(packedLight);
        if(decoded != packedLight) {
            cir.setReturnValue(decoded);
        }
    }
}
