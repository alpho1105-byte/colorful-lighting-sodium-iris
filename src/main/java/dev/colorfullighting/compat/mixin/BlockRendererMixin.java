package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightGate;
import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.frapi.mesh.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = BlockRenderer.class, remap = false)
abstract class BlockRendererMixin extends AbstractBlockRenderContext {
    @Redirect(
            method = "bufferQuad",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;light:I",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void colorfulLightingSodiumCompat$attachColorfulLight(
            ChunkVertexEncoder.Vertex vertex,
            int vanillaLight,
            MutableQuadViewImpl quad,
            float[] brightness,
            Material material
    ) {
        Direction lightFace = quad.lightFace();
        BlockPos samplePos = lightFace == null ? pos : pos.relative(lightFace);
        int colorfulLight = ColorfulLightGate.sampleColorful(
                level,
                level.getBlockState(samplePos),
                samplePos
        );
        ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$setLight(colorfulLight);
        vertex.light = vanillaLight;
    }
}
