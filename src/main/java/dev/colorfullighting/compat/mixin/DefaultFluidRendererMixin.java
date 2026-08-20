package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import dev.colorfullighting.compat.sodium.FluidVertexLight;
import dev.colorfullighting.compat.ColorfulLightGate;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultFluidRenderer.class, remap = false)
abstract class DefaultFluidRendererMixin {
    @Unique
    private int colorfulLightingSodiumCompat$worldX;

    @Unique
    private int colorfulLightingSodiumCompat$worldY;

    @Unique
    private int colorfulLightingSodiumCompat$worldZ;

    @Unique
    private ModelQuadView colorfulLightingSodiumCompat$quad;

    @Unique
    private Direction colorfulLightingSodiumCompat$lightFace = Direction.UP;

    @Unique
    private int colorfulLightingSodiumCompat$vertexIndex;

    @Unique
    private LevelSlice colorfulLightingSodiumCompat$level;

    @Inject(method = "render", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$captureLevel(
            LevelSlice level,
            BlockState blockState,
            FluidState fluidState,
            BlockPos blockPos,
            BlockPos modelOffset,
            TranslucentGeometryCollector collector,
            ChunkModelBuilder modelBuilder,
            Material material,
            ColorProvider<FluidState> colorProvider,
            TextureAtlasSprite[] sprites,
            CallbackInfo callbackInfo
    ) {
        // blockPos is the world position. modelOffset is only (x & 15, y & 15,
        // z & 15) and must never be used for a level/colored-light lookup.
        colorfulLightingSodiumCompat$worldX = blockPos.getX();
        colorfulLightingSodiumCompat$worldY = blockPos.getY();
        colorfulLightingSodiumCompat$worldZ = blockPos.getZ();
        colorfulLightingSodiumCompat$level = level;
    }

    @Inject(method = "updateQuad", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$captureLightFace(
            ModelQuadViewMutable quad,
            LevelSlice level,
            BlockPos pos,
            LightPipeline lighter,
            Direction direction,
            ModelQuadFacing facing,
            float brightness,
            ColorProvider<FluidState> colorProvider,
            FluidState fluidState,
            CallbackInfo callbackInfo
    ) {
        colorfulLightingSodiumCompat$lightFace = direction;
    }

    @Inject(method = "writeQuad", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$capturePosition(
            ChunkModelBuilder modelBuilder,
            TranslucentGeometryCollector collector,
            Material material,
            BlockPos modelOffset,
            ModelQuadView quad,
            ModelQuadFacing facing,
            boolean flip,
            CallbackInfo callbackInfo
    ) {
        colorfulLightingSodiumCompat$quad = quad;
        colorfulLightingSodiumCompat$vertexIndex = 0;
    }

    @Redirect(
            method = "writeQuad",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;light:I",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void colorfulLightingSodiumCompat$attachColorfulLight(
            ChunkVertexEncoder.Vertex vertex,
            int vanillaLight
    ) {
        int vertexIndex = colorfulLightingSodiumCompat$vertexIndex++;
        int colorfulLight = vanillaLight;
        ModelQuadView quad = colorfulLightingSodiumCompat$quad;
        if (quad != null && vertexIndex < 4) {
            Direction lightFace = colorfulLightingSodiumCompat$lightFace;
            double sampleX = FluidVertexLight.sampleCoordinate(
                    colorfulLightingSodiumCompat$worldX,
                    quad.getX(vertexIndex),
                    lightFace.getStepX()
            );
            double sampleY = FluidVertexLight.sampleCoordinate(
                    colorfulLightingSodiumCompat$worldY,
                    quad.getY(vertexIndex),
                    lightFace.getStepY()
            );
            double sampleZ = FluidVertexLight.sampleCoordinate(
                    colorfulLightingSodiumCompat$worldZ,
                    quad.getZ(vertexIndex),
                    lightFace.getStepZ()
            );
            // fused gate + sample: null keeps vanilla light (wrong scope or missing data)
            ColorRGB8 colored = ColorfulLightGate.trySampleColorful(
                    colorfulLightingSodiumCompat$level,
                    sampleX, sampleY, sampleZ
            );
            if(colored != null) {
                colorfulLight = FluidVertexLight.packWithVanillaSky(vanillaLight, colored);
            }
        }
        ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$setLight(colorfulLight);
        vertex.light = vanillaLight;
    }
}
