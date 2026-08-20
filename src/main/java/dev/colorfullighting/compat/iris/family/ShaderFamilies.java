package dev.colorfullighting.compat.iris.family;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Ordered registry of supported shader-pack families - first match wins, so put the
 * family with the more specific detection idiom first when idioms could overlap.
 * Resolution happens per stage source, but the CORE decides varying wiring per
 * program pair (a vertex matching one family and a fragment matching another must
 * still get a matching out/in pair - see IrisShaderCompat.patchVertex).
 */
public final class ShaderFamilies {
    private static final List<ShaderFamily> FAMILIES = List.of(
            new ComplementaryFamily(),
            new BslFamily(),
            new MakeUpFamily()
    );

    private ShaderFamilies() {
    }

    public static List<ShaderFamily> all() {
        return FAMILIES;
    }

    /** The family whose fragment-stage tint idiom matches, or null. */
    @Nullable
    public static ShaderFamily fragmentTintFamily(@Nullable String fragmentSource) {
        if (fragmentSource == null) return null;
        for (ShaderFamily family : FAMILIES) {
            if (family.matchesFragmentTint(fragmentSource)) return family;
        }
        return null;
    }

    /** The family whose vertex-stage tint idiom matches, or null. */
    @Nullable
    public static ShaderFamily vertexTintFamily(@Nullable String vertexSource) {
        if (vertexSource == null) return null;
        for (ShaderFamily family : FAMILIES) {
            if (family.matchesVertexTint(vertexSource)) return family;
        }
        return null;
    }
}
