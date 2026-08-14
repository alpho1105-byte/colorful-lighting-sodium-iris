package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightGate;
import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
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
    private BlockAndTintGetter colorfulLightingSodiumCompat$level;

    @Unique
    private BlockPos colorfulLightingSodiumCompat$pos;

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
        colorfulLightingSodiumCompat$level = level;
    }

    @Inject(method = "writeQuad", at = @At("HEAD"))
    private void colorfulLightingSodiumCompat$capturePosition(
            ChunkModelBuilder modelBuilder,
            TranslucentGeometryCollector collector,
            Material material,
            BlockPos pos,
            ModelQuadView quad,
            ModelQuadFacing facing,
            boolean flip,
            CallbackInfo callbackInfo
    ) {
        colorfulLightingSodiumCompat$pos = pos;
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
        if (colorfulLightingSodiumCompat$level != null && colorfulLightingSodiumCompat$pos != null) {
            int colorfulLight = ColorfulLightGate.sampleColorful(
                    colorfulLightingSodiumCompat$level,
                    colorfulLightingSodiumCompat$level.getBlockState(colorfulLightingSodiumCompat$pos),
                    colorfulLightingSodiumCompat$pos
            );
            ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$setLight(colorfulLight);
        }
        vertex.light = vanillaLight;
    }
}
