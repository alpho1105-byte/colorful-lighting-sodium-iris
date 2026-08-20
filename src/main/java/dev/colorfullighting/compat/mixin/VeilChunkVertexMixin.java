package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.sodium.ColorfulChunkVertexType;
import dev.colorfullighting.compat.sodium.ColorfulVeilChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends Veil's replacement compact chunk format without linking against Veil. */
@Mixin(targets = "foundry.veil.forge.compat.sodium.VeilChunkVertex", remap = false)
abstract class VeilChunkVertexMixin implements ColorfulChunkVertexType {
    @Shadow
    @Final
    @Mutable
    public static GlVertexFormat VERTEX_FORMAT;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void colorfulLightingSodiumCompat$extendFormat(CallbackInfo callbackInfo) {
        VERTEX_FORMAT = ColorfulVeilChunkVertexEncoder.createFormat();
    }

    @Inject(method = "getEncoder", at = @At("HEAD"), cancellable = true)
    private void colorfulLightingSodiumCompat$useEncoder(
            CallbackInfoReturnable<ChunkVertexEncoder> callbackInfo
    ) {
        callbackInfo.setReturnValue(ColorfulVeilChunkVertexEncoder::write);
    }
}
