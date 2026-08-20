package dev.colorfullighting.compat.iris.family;

/**
 * GLSL identifiers shared between the core patcher, the shader families, and the
 * uniform registrations in IrisIdMapUniformsMixin. Everything injected into pack
 * source is namespaced to avoid collisions with pack locals.
 */
public final class ShaderPatchNames {
    /** Vertex attribute carrying the packed colorful light (Sodium terrain path). */
    public static final String ATTRIBUTE_NAME = "colorfulLightingSodiumCompat_Light";
    /** Vertex-to-fragment varying carrying the decoded RGB (0..1). */
    public static final String VARYING_NAME = "colorfulLightingSodiumCompat_Color";
    /** Vertex-local decoded RGB for families that light in the vertex stage. */
    public static final String VERTEX_COLOR_NAME = "colorfulLightingSodiumCompat_VertexColor";
    /** Rename target for iris_UV2 in sanitized vanilla-format programs. */
    public static final String UV2_NAME = "colorfulLightingSodiumCompat_UV2";
    public static final String UV2_DECLARATION = "in ivec2 iris_UV2;";
    /** Captures the pack's original block-light hue before the terrain hue swap. */
    public static final String PACK_LIGHT_NAME = "colorfulLightingSodiumCompat_PackBlocklight";

    // Held-item uniforms; the names must match IrisIdMapUniformsMixin exactly.
    public static final String HELD_COLOR_NAME = "colorfulLightingSodiumCompat_HeldColor";
    public static final String HELD_LEVEL_NAME = "colorfulLightingSodiumCompat_HeldLevel";
    /**
     * 1 when Colorful Lighting has an opinion on the hand's light (a JSON item
     * definition, a user override, or a BlockItem-derived emission - including
     * "deliberately dark" cases like disabled overrides or a wet torch), 0 when the
     * item is unknown and the pack's own held-light definition must stay in charge.
     */
    public static final String HELD_AUTHORITY_NAME = "colorfulLightingSodiumCompat_HeldAuthority";
    /** Injected helper that turns a held emitter color into the pack's magnitude. */
    public static final String HELD_HELPER_NAME = "colorfulLightingSodiumCompat_HeldLight";
    public static final String FALLBACK_HAND_DISTANCE_NAME =
            "colorfulLightingSodiumCompat_FallbackHandDistance";

    /** Per-slot dynamic entity-light uniform prefix; names must match the mixin. */
    public static final String DYN_LIGHT_NAME = "colorfulLightingSodiumCompat_DynLight";
    /** Keep in sync with EntityLightManager.MAX_SHADER_LIGHTS. */
    public static final int MAX_DYNAMIC_LIGHTS = 8;

    /**
     * Rec.601 weights, the same ones the Complementary family's GetLuminance uses. Hue
     * swaps preserve the pack color's luminance rather than its peak channel: the pack
     * multiplies its block-light color into the lightmap unchanged, so a peak-preserving
     * swap silently darkens every non-white light (a pure blue at 16% of the pack's own
     * brightness, a pure red at 43%). Held light does not share that error because the
     * pack normalizes its luminance anyway, which is exactly why the two used to
     * diverge. The pack's own colored lighting normalizes luminance and rescales to
     * 0.13, within 6% of blocklightCol's luminance, so this is its native convention.
     */
    public static final String LUMA_WEIGHTS = "vec3(0.299, 0.587, 0.114)";

    public static final String MAIN = "void main() {";

    private ShaderPatchNames() {
    }
}
