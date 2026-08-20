package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.PackedLightCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves Colorful Lighting's packed format when Sable dims sublevel sky. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ClientSubLevel", remap = false)
abstract class SableClientSubLevelMixin {
    @Shadow
    public abstract int getLatestSkyLightScale();

    @Inject(method = "scaleLightColor", at = @At("HEAD"), cancellable = true)
    private void colorfulLighting$scalePackedSky(
            int packedLight,
            CallbackInfoReturnable<Integer> callbackInfo
    ) {
        if (!PackedLightCompat.isColorful(packedLight)) {
            return;
        }

        callbackInfo.setReturnValue(PackedLightCompat.scaleSkyLight(
                packedLight,
                getLatestSkyLightScale()
        ));
    }
}
