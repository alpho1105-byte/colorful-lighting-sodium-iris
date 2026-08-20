package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightGate;
import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.frapi.mesh.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext;
import net.minecraft.core.Direction;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockRenderer.class, remap = false)
abstract class BlockRendererMixin extends AbstractBlockRenderContext {
    @Unique
    private int colorfulLightingSodiumCompat$vertexIndex;
    // quad-invariant values hoisted out of the per-vertex redirect (4x per quad on
    // every meshing worker); BlockRenderer instances are per-worker, so plain fields
    // are thread-safe here
    @Unique
    private double colorfulLightingSodiumCompat$normalX;
    @Unique
    private double colorfulLightingSodiumCompat$normalY;
    @Unique
    private double colorfulLightingSodiumCompat$normalZ;

    @Inject(method = "bufferQuad", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$beginQuad(
            MutableQuadViewImpl quad,
            float[] brightness,
            Material material,
            CallbackInfo callbackInfo
    ) {
        colorfulLightingSodiumCompat$vertexIndex = 0;
        Direction lightFace = quad.lightFace();
        colorfulLightingSodiumCompat$normalX = lightFace == null ? 0.0 : lightFace.getStepX() * 0.5;
        colorfulLightingSodiumCompat$normalY = lightFace == null ? 0.0 : lightFace.getStepY() * 0.5;
        colorfulLightingSodiumCompat$normalZ = lightFace == null ? 0.0 : lightFace.getStepZ() * 0.5;
    }

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
        int vertexIndex = colorfulLightingSodiumCompat$vertexIndex++;
        int colorfulLight = vanillaLight;
        if(vertexIndex < 4) {
            double sampleX = pos.getX() + quad.x(vertexIndex) + colorfulLightingSodiumCompat$normalX;
            double sampleY = pos.getY() + quad.y(vertexIndex) + colorfulLightingSodiumCompat$normalY;
            double sampleZ = pos.getZ() + quad.z(vertexIndex) + colorfulLightingSodiumCompat$normalZ;
            // fused gate + sample: null keeps vanilla light (wrong scope or missing data)
            ColorRGB8 colored = ColorfulLightGate.trySampleColorful(level, sampleX, sampleY, sampleZ);
            if(colored != null) {
                int skyLight = (vanillaLight >>> 20) & 0xF;
                colorfulLight = PackedLightData.packData(skyLight, colored);
            }
        }
        ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$setLight(colorfulLight);
        vertex.light = vanillaLight;
    }
}
