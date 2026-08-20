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

    // Translucent-sorting quad splitting (InnerPartitionBSPNode.interpolateAttributes)
    // fills scratch vertices through writeVertex, which cannot interpolate the packed
    // colorful value. Store the interpolated vanilla light instead: without this the
    // scratch vertex keeps a stale colorful value from an unrelated quad. Split
    // vertices therefore lose the colored tint but keep correct brightness (a missing
    // marker nibble makes every decode path fall back to vanilla light).
    @Inject(method = "writeVertex", at = @At("RETURN"))
    private static void colorfulLightingSodiumCompat$resetLightOnWrite(
            ChunkVertexEncoder.Vertex vertex,
            float x,
            float y,
            float z,
            int color,
            float ao,
            float u,
            float v,
            int light,
            CallbackInfo callbackInfo
    ) {
        ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$setLight(light);
    }
}
