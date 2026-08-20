package dev.colorfullighting.compat.sodium;

import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.DefaultChunkMeshAttributes;
import net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatAttribute;
import org.lwjgl.system.MemoryUtil;

/** Veil's compact Sodium layout with Colorful Lighting appended after its normal. */
public final class ColorfulVeilChunkVertexEncoder {
    public static final int STRIDE = 28;

    private static final VertexFormatAttribute NORMAL = new VertexFormatAttribute(
            "NORMAL_INDEX",
            GlVertexAttributeFormat.BYTE,
            4,
            false,
            true
    );

    private ColorfulVeilChunkVertexEncoder() {
    }

    public static GlVertexFormat createFormat() {
        return GlVertexFormat.builder(STRIDE)
                .addElement(DefaultChunkMeshAttributes.POSITION, 0, 0)
                .addElement(DefaultChunkMeshAttributes.COLOR, 1, 8)
                .addElement(DefaultChunkMeshAttributes.TEXTURE, 2, 12)
                .addElement(DefaultChunkMeshAttributes.LIGHT_MATERIAL_INDEX, 3, 16)
                .addElement(NORMAL, 4, 20)
                .addElement(
                        ColorfulChunkVertexEncoder.COLORFUL_LIGHT,
                        ColorfulChunkVertexEncoder.ATTRIBUTE_LOCATION,
                        24
                )
                .build();
    }

    public static long write(
            long pointer,
            int material,
            ChunkVertexEncoder.Vertex[] vertices,
            int sectionIndex
    ) {
        float textureCenterU = 0.0F;
        float textureCenterV = 0.0F;
        for(ChunkVertexEncoder.Vertex vertex : vertices) {
            textureCenterU += vertex.u;
            textureCenterV += vertex.v;
        }
        textureCenterU *= 0.25F;
        textureCenterV *= 0.25F;

        for(ChunkVertexEncoder.Vertex vertex : vertices) {
            int x = CompactVertexPacking.quantizePosition(vertex.x);
            int y = CompactVertexPacking.quantizePosition(vertex.y);
            int z = CompactVertexPacking.quantizePosition(vertex.z);
            int u = CompactVertexPacking.encodeTexture(textureCenterU, vertex.u);
            int v = CompactVertexPacking.encodeTexture(textureCenterV, vertex.v);
            int light = CompactVertexPacking.encodeLight(vertex.light);
            int packedNormal = ((VeilNormalVertex) vertex).veil$getPackedNormal();
            int colorfulLight = ((ColorfulLightVertex) vertex)
                    .colorfulLightingSodiumCompat$getLight();

            MemoryUtil.memPutInt(pointer, CompactVertexPacking.packPositionHi(x, y, z));
            MemoryUtil.memPutInt(pointer + 4L, CompactVertexPacking.packPositionLo(x, y, z));
            MemoryUtil.memPutInt(pointer + 8L, ColorARGB.mulRGB(vertex.color, vertex.ao));
            MemoryUtil.memPutInt(pointer + 12L, CompactVertexPacking.packTexture(u, v));
            MemoryUtil.memPutInt(pointer + 16L, CompactVertexPacking.packLightAndData(light, material, sectionIndex));
            MemoryUtil.memPutInt(pointer + 20L, packedNormal);
            MemoryUtil.memPutInt(pointer + 24L, colorfulLight);
            pointer += STRIDE;
        }

        return pointer;
    }

}
