package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.PackedLightCompat;
import net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = LightDataAccess.class, remap = false)
abstract class SodiumLightDataAccessMixin {
    @Redirect(
            method = "compute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;block(I)I"
            )
    )
    private int colorfulLightingSodiumCompat$decodeBlockLight(int packedLight) {
        return PackedLightCompat.blockLight(packedLight);
    }

    @Redirect(
            method = "compute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;sky(I)I"
            )
    )
    private int colorfulLightingSodiumCompat$decodeSkyLight(int packedLight) {
        return PackedLightCompat.skyLight(packedLight);
    }
}
