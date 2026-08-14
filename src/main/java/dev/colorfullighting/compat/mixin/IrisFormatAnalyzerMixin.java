package dev.colorfullighting.compat.mixin;

import dev.colorfullighting.compat.sodium.ColorfulChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = FormatAnalyzer.class, remap = false)
abstract class IrisFormatAnalyzerMixin {
    @Unique
    private static final ThreadLocal<Integer> colorfulLightingSodiumCompat$colorfulOffset =
            new ThreadLocal<>();

    @Redirect(
            method = "createFormat",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/attribute/GlVertexFormat;builder(I)Lnet/caffeinemc/mods/sodium/client/gl/attribute/GlVertexFormat$Builder;"
            )
    )
    private static GlVertexFormat.Builder colorfulLightingSodiumCompat$extendStride(int stride) {
        colorfulLightingSodiumCompat$colorfulOffset.set(stride);
        return GlVertexFormat.builder(stride + Integer.BYTES);
    }

    @Redirect(
            method = "lambda$createFormat$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/attribute/GlVertexFormat$Builder;build()Lnet/caffeinemc/mods/sodium/client/gl/attribute/GlVertexFormat;"
            )
    )
    private static GlVertexFormat colorfulLightingSodiumCompat$addAttribute(
            GlVertexFormat.Builder builder
    ) {
        Integer offset = colorfulLightingSodiumCompat$colorfulOffset.get();
        if (offset == null) {
            throw new IllegalStateException("Missing Iris colorful-light vertex offset");
        }

        try {
            return builder.addElement(
                    ColorfulChunkVertexEncoder.COLORFUL_LIGHT,
                    ColorfulChunkVertexEncoder.ATTRIBUTE_LOCATION,
                    offset
            ).build();
        } finally {
            colorfulLightingSodiumCompat$colorfulOffset.remove();
        }
    }
}
