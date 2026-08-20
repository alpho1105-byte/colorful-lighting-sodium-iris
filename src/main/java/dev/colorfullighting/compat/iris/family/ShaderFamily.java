package dev.colorfullighting.compat.iris.family;

/**
 * One shader-pack family's colored-light adaptation. The core pipeline
 * (IrisShaderCompat) owns everything pack-agnostic - the packed-attribute decode, the
 * iris_UV2 sanitize, varying wiring, fail-closed routing - and asks the registry
 * (ShaderFamilies) which family, if any, should inject the actual hue swap.
 *
 * <p>Contract for every patch method:
 * <ul>
 *   <li><b>Fail closed</b>: when an expected idiom half-matches, return the input
 *       UNCHANGED rather than emitting partial surgery. An unpatched program renders
 *       correct vanilla-brightness light; a half-patched one may not compile.</li>
 *   <li><b>Idempotent</b>: patching an already-patched source must be a no-op (guard
 *       on an injected marker identifier).</li>
 *   <li><b>Namespaced</b>: every injected identifier goes through ShaderPatchNames or
 *       carries the {@code colorfulLightingSodiumCompat_} prefix.</li>
 * </ul>
 *
 * Adding a family: implement this, register it in ShaderFamilies, and follow
 * docs/ADDING_SHADER_SUPPORT.md (detection, insertion points, LUMA-vs-PEAK choice,
 * synthetic-source tests).
 */
public interface ShaderFamily {
    /** Stable lowercase identifier, e.g. {@code "complementary"}. */
    String id();

    /**
     * Whether this family computes its block light in the FRAGMENT stage of the given
     * source and {@link #patchFragment} can inject the hue swap there. When any
     * family matches, the core declares the vertex-to-fragment RGB varying.
     */
    boolean matchesFragmentTint(String fragmentSource);

    /**
     * Whether this family computes its block light in the VERTEX stage of the given
     * source and {@link #patchVertexLighting} can swap it in place (no varying
     * needed for the family's own path).
     */
    boolean matchesVertexTint(String vertexSource);

    /**
     * Injects the hue swap (and any held-light / dynamic-light hooks) into a fragment
     * this family matched. The decoded RGB is available as
     * {@code ShaderPatchNames.VARYING_NAME}; the method itself declares that varying
     * and anything else it needs.
     */
    default String patchFragment(String fragmentSource) {
        return fragmentSource;
    }

    /**
     * Injects the vertex-stage hue swap into a vertex source this family matched. The
     * decoded RGB is available as {@code ShaderPatchNames.VERTEX_COLOR_NAME}, already
     * declared and filled by the core decode.
     */
    default String patchVertexLighting(String vertexSource) {
        return vertexSource;
    }
}
