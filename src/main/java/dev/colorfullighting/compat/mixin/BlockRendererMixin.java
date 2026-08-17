package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightGate;
import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.frapi.mesh.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
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

    @Inject(method = "bufferQuad", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$beginQuad(
            MutableQuadViewImpl quad,
            float[] brightness,
            Material material,
            CallbackInfo callbackInfo
    ) {
        colorfulLightingSodiumCompat$vertexIndex = 0;
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
        Direction lightFace = quad.lightFace();
        ColoredLightEngine engine = ColoredLightEngine.getInstance();
        int colorfulLight;
        if(engine != null && vertexIndex < 4) {
            double normalX = lightFace == null ? 0.0 : lightFace.getStepX() * 0.5;
            double normalY = lightFace == null ? 0.0 : lightFace.getStepY() * 0.5;
            double normalZ = lightFace == null ? 0.0 : lightFace.getStepZ() * 0.5;
            Vec3 samplePos = new Vec3(
                    pos.getX() + quad.x(vertexIndex) + normalX,
                    pos.getY() + quad.y(vertexIndex) + normalY,
                    pos.getZ() + quad.z(vertexIndex) + normalZ
            );
            int skyLight = (vanillaLight >>> 20) & 0xF;
            colorfulLight = PackedLightData.packData(
                    skyLight,
                    engine.sampleTrilinearLightColor(samplePos)
            );
        }
        else {
            BlockPos samplePos = lightFace == null ? pos : pos.relative(lightFace);
            colorfulLight = ColorfulLightGate.sampleColorful(
                    level,
                    level.getBlockState(samplePos),
                    samplePos
            );
        }
        ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$setLight(colorfulLight);
        vertex.light = vanillaLight;
    }
}
