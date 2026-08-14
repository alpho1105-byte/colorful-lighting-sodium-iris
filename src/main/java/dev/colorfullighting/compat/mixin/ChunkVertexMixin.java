package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.sodium.ColorfulLightVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkVertexEncoder.Vertex.class, remap = false)
abstract class ChunkVertexMixin implements ColorfulLightVertex {
    @Unique
    private int colorfulLightingSodiumCompat$light;

    @Override
    public int colorfulLightingSodiumCompat$getLight() {
        return colorfulLightingSodiumCompat$light;
    }

    @Override
    public void colorfulLightingSodiumCompat$setLight(int packedLight) {
        colorfulLightingSodiumCompat$light = packedLight;
    }

    @Inject(method = "copyVertexTo", at = @At("HEAD"))
    private static void colorfulLightingSodiumCompat$copyLight(
            ChunkVertexEncoder.Vertex source,
            ChunkVertexEncoder.Vertex destination,
            CallbackInfo callbackInfo
    ) {
        int packedLight = ((ColorfulLightVertex) source).colorfulLightingSodiumCompat$getLight();
        ((ColorfulLightVertex) destination).colorfulLightingSodiumCompat$setLight(packedLight);
    }
}
