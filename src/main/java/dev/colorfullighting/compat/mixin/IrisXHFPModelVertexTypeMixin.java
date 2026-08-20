package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import dev.colorfullighting.compat.sodium.ColorfulChunkVertexType;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.irisshaders.iris.vertices.sodium.terrain.XHFPModelVertexType;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = XHFPModelVertexType.class, remap = false)
abstract class IrisXHFPModelVertexTypeMixin implements ColorfulChunkVertexType {
    @Shadow
    @Final
    private GlVertexFormat format;

    @Inject(method = "getEncoder", at = @At("RETURN"), cancellable = true)
    private void colorfulLightingSodiumCompat$appendColorfulLight(
            CallbackInfoReturnable<ChunkVertexEncoder> callbackInfo
    ) {
        ChunkVertexEncoder delegate = callbackInfo.getReturnValue();
        int stride = format.getStride();
        int colorfulOffset = stride - Integer.BYTES;

        callbackInfo.setReturnValue((pointer, material, vertices, sectionIndex) -> {
            long end = delegate.write(pointer, material, vertices, sectionIndex);
            long colorfulPointer = pointer + colorfulOffset;

            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                int colorfulLight = ((ColorfulLightVertex) vertex)
                        .colorfulLightingSodiumCompat$getLight();
                MemoryUtil.memPutInt(colorfulPointer, colorfulLight);
                colorfulPointer += stride;
            }

            return end;
        });
    }
}
