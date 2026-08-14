package dev.colorfullighting.compat.iris;

public final class IrisShaderCompat {
    private static final String ATTRIBUTE_NAME = "colorfulLightingSodiumCompat_Light";
    private static final String VARYING_NAME = "colorfulLightingSodiumCompat_Color";
    private static final String UV2_NAME = "colorfulLightingSodiumCompat_UV2";
    private static final String PACK_LIGHT_NAME = "colorfulLightingSodiumCompat_PackBlocklight";
    private static final String HELD_COLOR_NAME = "colorfulLightingSodiumCompat_HeldColor";
    private static final String HELD_HELPER_NAME = "colorfulLightingSodiumCompat_HeldLight";
    private static final String HELD_LIGHTING_DEFINITION = "vec3 GetHeldLighting(";
    private static final String UV2_DECLARATION = "in ivec2 iris_UV2;";
    private static final String MAIN = "void main() {";
    private static final String LIGHTING_CALL = "DoLighting(color,";

    private IrisShaderCompat() {
    }

    public static boolean supports(String vertexSource, String fragmentSource) {
        return vertexSource != null
                && fragmentSource != null
                && vertexSource.contains("mc_Entity")
                && vertexSource.contains(MAIN)
                && fragmentSource.contains("vec3 blocklightCol")
                && fragmentSource.contains(LIGHTING_CALL);
    }

    public static boolean usesVanillaLightCoords(String vertexSource) {
        return vertexSource != null
                && vertexSource.contains(UV2_DECLARATION)
                && vertexSource.contains(MAIN)
                && !vertexSource.contains(UV2_NAME);
    }

    public static boolean supportsVanillaTint(String fragmentSource) {
        return fragmentSource != null
                && fragmentSource.contains("vec3 blocklightCol")
                && fragmentSource.contains(LIGHTING_CALL);
    }

    /**
     * Rewrites every iris_UV2 read to go through a global that decodes Colorful
     * Lighting's packed format. The packed halves overflow the signed shorts of the
     * vanilla UV2 attribute, so unpatched programs clamp them to black or full bright.
     * Bit operations are unaffected by the sign extension, which the decode relies on.
     */
    public static String sanitizeVanillaVertex(String source, boolean withTint) {
        String redirected = source.replaceAll("\\biris_UV2\\b", UV2_NAME);
        String renamedDeclaration = "in ivec2 " + UV2_NAME + ";";
        int declaration = redirected.indexOf(renamedDeclaration);
        if (declaration < 0) {
            return source;
        }

        String declarations = UV2_DECLARATION + "\nivec2 " + UV2_NAME + ";"
                + (withTint ? "\nout vec3 " + VARYING_NAME + ";" : "");
        redirected = redirected.substring(0, declaration)
                + declarations
                + redirected.substring(declaration + renamedDeclaration.length());

        int main = redirected.indexOf(MAIN);
        if (main < 0) {
            return source;
        }

        String initialization = "\n\t" + UV2_NAME + " = iris_UV2;\n"
                + (withTint ? "\t" + VARYING_NAME + " = vec3(0.0);\n" : "")
                + "\tif (((iris_UV2.y >> 12) & 15) == 15) {\n"
                + "\t\tint colorfulRed = iris_UV2.x & 255;\n"
                + "\t\tint colorfulGreen = (iris_UV2.x >> 8) & 255;\n"
                + "\t\tint colorfulBlue = (iris_UV2.y >> 4) & 255;\n"
                + "\t\tint colorfulBlock = max(colorfulRed, max(colorfulGreen, colorfulBlue)) >> 4;\n"
                + "\t\t" + UV2_NAME + " = ivec2(colorfulBlock << 4, (iris_UV2.y & 15) << 4);\n"
                + (withTint
                        ? "\t\t" + VARYING_NAME
                                + " = vec3(colorfulRed, colorfulGreen, colorfulBlue) / 255.0;\n"
                        : "")
                + "\t}";
        return redirected.substring(0, main + MAIN.length())
                + initialization
                + redirected.substring(main + MAIN.length());
    }

    public static String patchVertex(String source) {
        if (source.contains(ATTRIBUTE_NAME)) {
            return source;
        }

        String declarations = "layout(location = 15) in uvec4 " + ATTRIBUTE_NAME + ";\n"
                + "out vec3 " + VARYING_NAME + ";\n";
        String initialization = "\n\tuvec4 colorfulPacked = " + ATTRIBUTE_NAME + ";\n"
                + "\tuint colorfulMarker = colorfulPacked.a >> 4u;\n"
                + "\tuint colorfulBlue = ((colorfulPacked.a & 15u) << 4u) | (colorfulPacked.b >> 4u);\n"
                + "\t" + VARYING_NAME + " = colorfulMarker == 15u\n"
                + "\t\t? vec3(colorfulPacked.r, colorfulPacked.g, colorfulBlue) / 255.0\n"
                + "\t\t: vec3(0.0);";

        return insertAfterVersion(source, declarations)
                .replaceFirst("void main\\(\\) \\{", MAIN + initialization);
    }

    public static String patchFragment(String source) {
        if (source.contains(VARYING_NAME)) {
            return source;
        }

        String declarations = "in vec3 " + VARYING_NAME + ";\n"
                + "vec3 " + PACK_LIGHT_NAME + " = vec3(1.0);\n";
        int lightingCall = source.indexOf(LIGHTING_CALL);
        if (lightingCall < 0) {
            return source;
        }

        // Held-item lighting scales purely with camera distance and reuses blocklightCol
        // as its hue, so the normalized colorful swap below must not reach it: a faint
        // colored value at a light's range edge would otherwise be amplified into a
        // fully saturated wash wherever the held light dominates. Instead each hand gets
        // its own uniform carrying the held block's Colorful Lighting emitter color
        // (zero when there is none), falling back to a copy of the pack's own value
        // captured right before the swap.
        String patched = source;
        int heldLighting = patched.indexOf(HELD_LIGHTING_DEFINITION);
        if (heldLighting >= 0) {
            String heldHelper = "uniform vec3 " + HELD_COLOR_NAME + ";\n"
                    + "uniform vec3 " + HELD_COLOR_NAME + "2;\n"
                    + "vec3 " + HELD_HELPER_NAME + "(vec3 heldColor) {\n"
                    + "\tfloat heldPeak = max(max(heldColor.r, heldColor.g), heldColor.b);\n"
                    + "\tif (heldPeak < 0.001) return " + PACK_LIGHT_NAME + ";\n"
                    + "\tvec3 heldTint = pow(clamp(heldColor, vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                    + "\tfloat heldTintPeak = max(max(heldTint.r, heldTint.g), heldTint.b);\n"
                    + "\tfloat packPeak = max(max(" + PACK_LIGHT_NAME + ".r, "
                    + PACK_LIGHT_NAME + ".g), " + PACK_LIGHT_NAME + ".b);\n"
                    + "\treturn heldTint * (packPeak / max(heldTintPeak, 0.0001));\n"
                    + "}\n";
            patched = patched.substring(0, heldLighting)
                    + heldHelper
                    + patched.substring(heldLighting);
            patched = patched
                    .replace(
                            "vec3 heldLightCol = blocklightCol;",
                            "vec3 heldLightCol = " + HELD_HELPER_NAME
                                    + "(" + HELD_COLOR_NAME + ");"
                    )
                    .replace(
                            "vec3 heldLightCol2 = blocklightCol;",
                            "vec3 heldLightCol2 = " + HELD_HELPER_NAME
                                    + "(" + HELD_COLOR_NAME + "2);"
                    );
        }
        lightingCall = patched.indexOf(LIGHTING_CALL);

        String hook = PACK_LIGHT_NAME + " = blocklightCol;\n"
                + "\tfloat colorfulStrength = max(max(" + VARYING_NAME + ".r, "
                + VARYING_NAME + ".g), " + VARYING_NAME + ".b);\n"
                + "\tif (colorfulStrength > 0.0001) {\n"
                + "\t\tvec3 colorfulTint = pow(clamp(" + VARYING_NAME
                + ", vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                + "\t\tfloat colorfulTintPeak = max(max(colorfulTint.r, colorfulTint.g), colorfulTint.b);\n"
                + "\t\tfloat shaderPackPeak = max(max(blocklightCol.r, blocklightCol.g), blocklightCol.b);\n"
                + "\t\tblocklightCol = colorfulTint * (shaderPackPeak / max(colorfulTintPeak, 0.0001));\n"
                + "\t}\n\t";

        patched = patched.substring(0, lightingCall) + hook + patched.substring(lightingCall);
        return insertAfterVersion(patched, declarations);
    }

    private static String insertAfterVersion(String source, String insertion) {
        int lineEnd = source.indexOf('\n');
        if (lineEnd < 0) {
            return source + '\n' + insertion;
        }
        return source.substring(0, lineEnd + 1) + insertion + source.substring(lineEnd + 1);
    }
}
