package dev.colorfullighting.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.colorfullighting.compat.ColorfulLightGate;
import dev.colorfullighting.compat.create.CreatePackedLightCompat;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create bakes contraption geometry in a vanilla-lit virtual world, then combines
 * that light with Colorful Lighting's real-world sample while rendering. Catnip's
 * vanilla-only maxLight helper cannot combine the two packed layouts.
 */
@Pseudo
@Mixin(targets = "net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer", remap = false)
abstract class CreateSuperByteBufferMixin {
    @Unique
    private boolean colorfulLighting$shaderPointLightsActive;

    @Inject(method = "renderInto", at = @At("HEAD"))
    private void colorfulLighting$captureRenderMode(
            PoseStack input,
            VertexConsumer builder,
            CallbackInfo callbackInfo
    ) {
        colorfulLighting$shaderPointLightsActive = ColorfulLightGate.shaderEntityLightsActive();
    }

    // The first call combines an explicitly supplied custom light, if present. This
    // is not Create's live-world lookup, so it keeps the general packed-light rules.
    @Redirect(
            method = "renderInto",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/render/SuperByteBuffer;maxLight(II)I",
                    ordinal = 0,
                    remap = false
            )
    )
    private int colorfulLighting$combineCustomLight(int first, int second) {
        return PackedLightData.max(first, second);
    }

    // The second call combines virtual-world template light with light sampled at the
    // contraption's transformed world position. This boundary needs Create-specific
    // hue preservation and shader point-light de-duplication.
    @Redirect(
            method = "renderInto",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/render/SuperByteBuffer;maxLight(II)I",
                    ordinal = 1,
                    remap = false
            )
    )
    private int colorfulLighting$combineWorldLight(int templateLight, int worldLight) {
        return CreatePackedLightCompat.combineTemplateAndWorld(
                templateLight,
                worldLight,
                colorfulLighting$shaderPointLightsActive
        );
    }
}
