package dev.colorfullighting.compat;

import java.util.Map;

/**
 * Which mod each compat mixin targets - the data behind CompatMixinPlugin's gating,
 * kept free of mixin/FML imports so the plain-JVM test harness can verify it against
 * colorful_lighting_compat.mixins.json (the two lists must stay identical; a mixin
 * missing here would only be caught at runtime by the warn-and-default fallback).
 *
 * <p>The earlier name-prefix convention broke once (TransformPatcherMixin targets
 * Iris but is not named Iris*), and with "required": true a mis-routed mixin applied
 * against an absent mod's class would crash the load - a new mixin must be added
 * here deliberately.
 */
public final class CompatMixinRouting {
    public static final String SODIUM = "sodium";
    public static final String IRIS = "iris";
    public static final String SABLE = "sable";
    public static final String FLYWHEEL = "flywheel";
    public static final String CREATE = "create";
    public static final String VEIL = "veil";
    public static final String FLEROVIUM = "flerovium";

    /** Mixin simple class name to the mod id whose presence gates it. */
    public static final Map<String, String> MIXIN_MODS = Map.ofEntries(
            Map.entry("BlockRendererMixin", SODIUM),
            Map.entry("ChunkVertexMixin", SODIUM),
            Map.entry("CompactChunkVertexMixin", SODIUM),
            Map.entry("CreateSuperByteBufferMixin", CREATE),
            Map.entry("DefaultFluidRendererMixin", SODIUM),
            Map.entry("FleroviumItemRendererMixin", FLEROVIUM),
            Map.entry("FlywheelColoredLitInstanceMixin", FLYWHEEL),
            Map.entry("IrisFormatAnalyzerMixin", IRIS),
            Map.entry("IrisIdMapUniformsMixin", IRIS),
            Map.entry("IrisPipelineManagerMixin", IRIS),
            Map.entry("IrisRenderingPipelineMixin", IRIS),
            Map.entry("IrisSodiumProgramsMixin", IRIS),
            Map.entry("IrisXHFPModelVertexTypeMixin", IRIS),
            Map.entry("ResourcePackScannerMixin", SODIUM),
            Map.entry("ShaderChunkRendererMixin", SODIUM),
            Map.entry("SodiumLevelSliceMixin", SODIUM),
            Map.entry("SodiumLightDataAccessMixin", SODIUM),
            Map.entry("SableClientSubLevelMixin", SABLE),
            Map.entry("SableSubLevelMeshBuilderMixin", SABLE),
            Map.entry("TransformPatcherMixin", IRIS),
            Map.entry("VeilChunkVertexEncoderVertexMixin", VEIL),
            Map.entry("VeilChunkVertexMixin", VEIL)
    );

    private CompatMixinRouting() {
    }
}
