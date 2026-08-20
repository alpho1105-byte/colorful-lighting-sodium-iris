package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.PackedLightCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Decodes RGB packed light before Sable stores its vanilla two-channel mesh light. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.fancy.SubLevelMeshBuilder", remap = false)
abstract class SableSubLevelMeshBuilderMixin {
    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;block(I)I"
            )
    )
    private int colorfulLighting$decodeBlockLight(int packedLight) {
        return PackedLightCompat.blockLight(packedLight);
    }

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;sky(I)I"
            )
    )
    private int colorfulLighting$decodeSkyLight(int packedLight) {
        return PackedLightCompat.skyLight(packedLight);
    }
}
