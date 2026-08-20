package dev.colorfullighting.compat.iris;

import dev.colorfullighting.compat.iris.family.PatchToolkit;
import dev.colorfullighting.compat.iris.family.ShaderFamilies;
import dev.colorfullighting.compat.iris.family.ShaderFamily;

import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.ATTRIBUTE_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.MAIN;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.UV2_DECLARATION;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.UV2_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.VARYING_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.VERTEX_COLOR_NAME;

/**
 * The pack-agnostic half of the shader-pack integration: decoding the packed colorful
 * light (either the custom vertex attribute of the Sodium terrain path or the
 * overflowed iris_UV2 halves of vanilla-format programs) and wiring the decoded RGB
 * to whichever {@link ShaderFamily} claims the program. Everything pack-specific -
 * detection idioms, hue-swap insertion points, held/dynamic-light hooks - lives in
 * the family implementations under {@code family/}; see docs/ADDING_SHADER_SUPPORT.md
 * for the contract a new family must follow.
 */
public final class IrisShaderCompat {
    private IrisShaderCompat() {
    }

    /** Whether the Sodium terrain program pair is worth patching at all. */
    public static boolean supports(String vertexSource, String fragmentSource) {
        return vertexSource != null
                && fragmentSource != null
                && vertexSource.contains("mc_Entity")
                && vertexSource.contains(MAIN)
                && (supportsVanillaTint(fragmentSource)
                        || supportsVanillaVertexTint(vertexSource));
    }

    public static boolean usesVanillaLightCoords(String vertexSource) {
        return vertexSource != null
                && vertexSource.contains(UV2_DECLARATION)
                && vertexSource.contains(MAIN)
                && !vertexSource.contains(UV2_NAME);
    }

    /** Some family computes its block light in this fragment stage. */
    public static boolean supportsVanillaTint(String fragmentSource) {
        return ShaderFamilies.fragmentTintFamily(fragmentSource) != null;
    }

    /** Some family (MakeUp/E-LITE) computes its block light in this vertex stage. */
    public static boolean supportsVanillaVertexTint(String vertexSource) {
        return ShaderFamilies.vertexTintFamily(vertexSource) != null;
    }

    /**
     * Rewrites every iris_UV2 read to go through a global that decodes Colorful
     * Lighting's packed format. The packed halves overflow the signed shorts of the
     * vanilla UV2 attribute, so unpatched programs clamp them to black or full bright.
     * Bit operations are unaffected by the sign extension, which the decode relies on.
     */
    public static String sanitizeVanillaVertex(String source, boolean withTint) {
        return sanitizeVanillaVertex(source, withTint, false);
    }

    /**
     * Sanitizes a vanilla-format program and optionally exposes the decoded RGB either
     * to its fragment stage (a fragment-tint family) or to its own vertex lighting (a
     * vertex-tint family such as MakeUp).
     */
    public static String sanitizeVanillaVertex(
            String source,
            boolean withFragmentTint,
            boolean withVertexTint
    ) {
        String redirected = source.replaceAll("\\biris_UV2\\b", UV2_NAME);
        String renamedDeclaration = "in ivec2 " + UV2_NAME + ";";
        int declaration = redirected.indexOf(renamedDeclaration);
        if (declaration < 0) {
            return source;
        }

        String declarations = UV2_DECLARATION + "\nivec2 " + UV2_NAME + ";"
                + (withFragmentTint ? "\nout vec3 " + VARYING_NAME + ";" : "")
                + (withVertexTint ? "\nvec3 " + VERTEX_COLOR_NAME + ";\n"
                        + PatchToolkit.heldColorDeclarations() : "");
        redirected = redirected.substring(0, declaration)
                + declarations
                + redirected.substring(declaration + renamedDeclaration.length());

        int main = redirected.indexOf(MAIN);
        if (main < 0) {
            return source;
        }

        String initialization = "\n\t" + UV2_NAME + " = iris_UV2;\n"
                + (withFragmentTint ? "\t" + VARYING_NAME + " = vec3(0.0);\n" : "")
                + (withVertexTint ? "\t" + VERTEX_COLOR_NAME + " = vec3(0.0);\n" : "")
                + "\tif (((iris_UV2.y >> 12) & 15) == 15) {\n"
                + "\t\tint colorfulRed = iris_UV2.x & 255;\n"
                + "\t\tint colorfulGreen = (iris_UV2.x >> 8) & 255;\n"
                + "\t\tint colorfulBlue = (iris_UV2.y >> 4) & 255;\n"
                + "\t\tint colorfulBlock = max(colorfulRed, max(colorfulGreen, colorfulBlue)) >> 4;\n"
                + "\t\t" + UV2_NAME + " = ivec2(colorfulBlock << 4, (iris_UV2.y & 15) << 4);\n"
                + (withFragmentTint
                        ? "\t\t" + VARYING_NAME
                                + " = vec3(colorfulRed, colorfulGreen, colorfulBlue) / 255.0;\n"
                        : "")
                + (withVertexTint
                        ? "\t\t" + VERTEX_COLOR_NAME
                                + " = vec3(colorfulRed, colorfulGreen, colorfulBlue) / 255.0;\n"
                        : "")
                + "\t}";
        String sanitized = redirected.substring(0, main + MAIN.length())
                + initialization
                + redirected.substring(main + MAIN.length());
        return withVertexTint ? applyVertexTintFamily(sanitized) : sanitized;
    }

    /**
     * Patches a Sodium terrain vertex program: declares the packed attribute, decodes
     * it, and hands the decoded RGB to the matching vertex-tint family (if any).
     *
     * @param fragmentTint whether the paired fragment stage consumes the decoded RGB
     *        varying (its patchFragment declares a matching {@code in}). The two
     *        stages are patched by independent calls, so the vertex must declare and
     *        write the {@code out} whenever the fragment reads it - even for a
     *        vertex-tint-family vertex, or the program fails to link.
     */
    public static String patchVertex(String source, boolean fragmentTint) {
        if (source.contains(ATTRIBUTE_NAME)) {
            return source;
        }

        ShaderFamily vertexFamily = ShaderFamilies.vertexTintFamily(source);
        boolean vertexTint = vertexFamily != null;
        String declarations = "layout(location = 15) in uvec4 " + ATTRIBUTE_NAME + ";\n"
                + (vertexTint
                        ? "vec3 " + VERTEX_COLOR_NAME + ";\n" + PatchToolkit.heldColorDeclarations()
                        : "")
                + (fragmentTint || !vertexTint
                        ? (fragmentTint ? "out " : "") + "vec3 " + VARYING_NAME + ";\n"
                        : "");
        String decodeTarget = vertexTint ? VERTEX_COLOR_NAME : VARYING_NAME;
        String initialization = "\n\tuvec4 colorfulPacked = " + ATTRIBUTE_NAME + ";\n"
                + "\tuint colorfulMarker = colorfulPacked.a >> 4u;\n"
                + "\tuint colorfulBlue = ((colorfulPacked.a & 15u) << 4u) | (colorfulPacked.b >> 4u);\n"
                + "\t" + decodeTarget
                + " = colorfulMarker == 15u\n"
                + "\t\t? vec3(colorfulPacked.r, colorfulPacked.g, colorfulBlue) / 255.0\n"
                + "\t\t: vec3(0.0);"
                + (vertexTint && fragmentTint
                        ? "\n\t" + VARYING_NAME + " = " + VERTEX_COLOR_NAME + ";"
                        : "");

        String patched = PatchToolkit.insertAfterVersion(source, declarations)
                .replaceFirst("void main\\(\\) \\{", MAIN + initialization);
        return vertexTint ? vertexFamily.patchVertexLighting(patched) : patched;
    }

    /** Hands the fragment to the matching fragment-tint family (if any). */
    public static String patchFragment(String source) {
        ShaderFamily family = ShaderFamilies.fragmentTintFamily(source);
        return family == null ? source : family.patchFragment(source);
    }

    private static String applyVertexTintFamily(String sanitized) {
        ShaderFamily family = ShaderFamilies.vertexTintFamily(sanitized);
        return family == null ? sanitized : family.patchVertexLighting(sanitized);
    }
}
