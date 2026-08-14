package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.ColorfulLightingSodiumCompat;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderType;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
abstract class ShaderChunkRendererMixin {
    @Redirect(
            method = "createShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderLoader;loadShader(Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;)Lnet/caffeinemc/mods/sodium/client/gl/shader/GlShader;",
                    ordinal = 0
            )
    )
    private GlShader colorfulLightingSodiumCompat$loadVertexShader(
            ShaderType type,
            ResourceLocation location,
            ShaderConstants constants,
            String path,
            ChunkShaderOptions options
    ) {
        ResourceLocation selected = location;
        if (options.vertexType() instanceof CompactChunkVertex) {
            selected = ResourceLocation.fromNamespaceAndPath(
                    ColorfulLightingSodiumCompat.MOD_ID,
                    location.getPath()
            );
        }
        return ShaderLoader.loadShader(type, selected, constants);
    }
}
