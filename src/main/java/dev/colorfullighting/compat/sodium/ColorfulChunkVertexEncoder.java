package dev.colorfullighting.compat.sodium;

import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.DefaultChunkMeshAttributes;
import net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatAttribute;
import org.lwjgl.system.MemoryUtil;

public final class ColorfulChunkVertexEncoder {
    public static final int STRIDE = 24;
    public static final int ATTRIBUTE_LOCATION = 15;
    public static final VertexFormatAttribute COLORFUL_LIGHT = new VertexFormatAttribute(
            "COLORFUL_LIGHT",
            GlVertexAttributeFormat.UNSIGNED_BYTE,
            4,
            false,
            true
    );

    private static final int POSITION_MAX_VALUE = 1_048_575;
    private static final int TEXTURE_MAX_VALUE = 32_767;

    private ColorfulChunkVertexEncoder() {
    }

    public static GlVertexFormat createFormat() {
        return GlVertexFormat.builder(STRIDE)
                .addElement(DefaultChunkMeshAttributes.POSITION, 0, 0)
                .addElement(DefaultChunkMeshAttributes.COLOR, 1, 8)
                .addElement(DefaultChunkMeshAttributes.TEXTURE, 2, 12)
                .addElement(DefaultChunkMeshAttributes.LIGHT_MATERIAL_INDEX, 3, 16)
                .addElement(COLORFUL_LIGHT, ATTRIBUTE_LOCATION, 20)
                .build();
    }

    public static long write(long pointer, int material, ChunkVertexEncoder.Vertex[] vertices, int sectionIndex) {
        float textureCenterU = 0.0F;
        float textureCenterV = 0.0F;

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            textureCenterU += vertex.u;
            textureCenterV += vertex.v;
        }

        textureCenterU *= 0.25F;
        textureCenterV *= 0.25F;

        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int x = quantizePosition(vertex.x);
            int y = quantizePosition(vertex.y);
            int z = quantizePosition(vertex.z);
            int u = encodeTexture(textureCenterU, vertex.u);
            int v = encodeTexture(textureCenterV, vertex.v);
            int light = encodeLight(vertex.light);
            int colorfulLight = ((ColorfulLightVertex) vertex).colorfulLightingSodiumCompat$getLight();

            MemoryUtil.memPutInt(pointer, packPositionHi(x, y, z));
            MemoryUtil.memPutInt(pointer + 4L, packPositionLo(x, y, z));
            MemoryUtil.memPutInt(pointer + 8L, ColorARGB.mulRGB(vertex.color, vertex.ao));
            MemoryUtil.memPutInt(pointer + 12L, packTexture(u, v));
            MemoryUtil.memPutInt(pointer + 16L, packLightAndData(light, material, sectionIndex));
            MemoryUtil.memPutInt(pointer + 20L, colorfulLight);
            pointer += STRIDE;
        }

        return pointer;
    }

    private static int quantizePosition(float value) {
        return (int) (((8.0F + value) / 32.0F) * 1_048_576.0F) & POSITION_MAX_VALUE;
    }

    private static int packPositionHi(int x, int y, int z) {
        return ((x >>> 10) & 0x3FF)
                | (((y >>> 10) & 0x3FF) << 10)
                | (((z >>> 10) & 0x3FF) << 20);
    }

    private static int packPositionLo(int x, int y, int z) {
        return (x & 0x3FF)
                | ((y & 0x3FF) << 10)
                | ((z & 0x3FF) << 20);
    }

    private static int encodeTexture(float center, float value) {
        int bias = value < center ? 1 : -1;
        int encoded = Math.round(value * 32_768.0F) + bias;
        return (encoded & TEXTURE_MAX_VALUE) | ((bias >>> 31) << 15);
    }

    private static int packTexture(int u, int v) {
        return (u & 0xFFFF) | ((v & 0xFFFF) << 16);
    }

    private static int encodeLight(int light) {
        int sky = Math.clamp((light >>> 16) & 0xFF, 8, 248);
        int block = Math.clamp(light & 0xFF, 8, 248);
        return block | (sky << 8);
    }

    private static int packLightAndData(int light, int material, int sectionIndex) {
        return (light & 0xFFFF) | ((material & 0xFF) << 16) | ((sectionIndex & 0xFF) << 24);
    }
}
