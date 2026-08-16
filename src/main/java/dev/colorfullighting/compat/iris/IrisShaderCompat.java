package dev.colorfullighting.compat.iris;

public final class IrisShaderCompat {
    private static final String ATTRIBUTE_NAME = "colorfulLightingSodiumCompat_Light";
    private static final String VARYING_NAME = "colorfulLightingSodiumCompat_Color";
    private static final String UV2_NAME = "colorfulLightingSodiumCompat_UV2";
    private static final String PACK_LIGHT_NAME = "colorfulLightingSodiumCompat_PackBlocklight";
    private static final String HELD_COLOR_NAME = "colorfulLightingSodiumCompat_HeldColor";
    private static final String HELD_HELPER_NAME = "colorfulLightingSodiumCompat_HeldLight";
    private static final String HELD_LIGHTING_DEFINITION = "vec3 GetHeldLighting(";
    private static final String DYN_LIGHT_NAME = "colorfulLightingSodiumCompat_DynLight";
    /** Keep in sync with EntityLightManager.MAX_SHADER_LIGHTS. */
    private static final int MAX_DYNAMIC_LIGHTS = 8;
    private static final String UV2_DECLARATION = "in ivec2 iris_UV2;";
    private static final String MAIN = "void main() {";
    private static final String LIGHTING_CALL = "DoLighting(color,";
    // Packs in the MakeUp family build their block-light color in the vertex stage
    // (candleColor) instead of exposing a fragment-side blocklightCol. Anchoring on the
    // clamp that closes that composition keeps the patch working across pack revisions
    // that change the clamp bounds or the terms feeding it.
    private static final String CANDLE_ANCHOR = "candleColor = clamp(candleColor,";
    private static final String CANDLE_ASSIGN = "candleColor = ";
    private static final String HAND_LIGHT_ASSIGN = "vec3 handLight = ";

    private IrisShaderCompat() {
    }

    /** Fragment-side hooks (Complementary family): a swappable blocklightCol. */
    private static boolean supportsFragmentTint(String fragmentSource) {
        return fragmentSource != null
                && fragmentSource.contains("vec3 blocklightCol")
                && fragmentSource.contains(LIGHTING_CALL);
    }

    /** Vertex-side hooks (MakeUp family): a candleColor the tint can be folded into. */
    public static boolean supportsVertexTint(String vertexSource) {
        return vertexSource != null && candleAnchorEnd(vertexSource) >= 0;
    }

    /** End offset of the candleColor clamp statement, or -1 when the pack has none. */
    private static int candleAnchorEnd(String source) {
        int anchor = source.indexOf(CANDLE_ANCHOR);
        if (anchor < 0) {
            return -1;
        }
        int end = source.indexOf(';', anchor);
        return end < 0 ? -1 : end + 1;
    }

    /**
     * End offset of the statement that builds candleColor from the lightmap, i.e. the
     * first assignment that is not one of the pack's later combining steps. The tint has
     * to land here rather than after the clamp: by then the pack has already maxed the
     * held-item light into candleColor, and recoloring that combined value repaints the
     * held light with the block's hue wherever it dominates.
     */
    private static int candleBlockLightEnd(String source) {
        int from = 0;
        while (true) {
            int assignment = source.indexOf(CANDLE_ASSIGN, from);
            if (assignment < 0) {
                return -1;
            }
            String rest = source.substring(assignment + CANDLE_ASSIGN.length());
            if (!rest.startsWith("max(") && !rest.startsWith("clamp(")) {
                int end = source.indexOf(';', assignment);
                return end < 0 ? -1 : end + 1;
            }
            from = assignment + CANDLE_ASSIGN.length();
        }
    }

    /**
     * Gives the pack's own held-item light the color of the held emitter, the same way
     * the fragment-lit family gets it through its held-light helper. Skipped when the
     * pack has no held-light branch.
     */
    private static String tintHandLight(String source) {
        int assignment = source.indexOf(HAND_LIGHT_ASSIGN);
        if (assignment < 0) {
            return source;
        }
        int end = source.indexOf(';', assignment);
        if (end < 0) {
            return source;
        }

        String tint = "\n\t\tvec3 colorfulHandColor = max(" + HELD_COLOR_NAME + ", "
                + HELD_COLOR_NAME + "2);\n"
                + "\t\tfloat colorfulHandStrength = max(max(colorfulHandColor.r,"
                + " colorfulHandColor.g), colorfulHandColor.b);\n"
                + "\t\tif (colorfulHandStrength > 0.001) {\n"
                + "\t\t\tvec3 colorfulHandTint = pow(clamp(colorfulHandColor, vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                + "\t\t\tfloat colorfulHandTintPeak = max(max(colorfulHandTint.r,"
                + " colorfulHandTint.g), colorfulHandTint.b);\n"
                + "\t\t\tfloat colorfulHandPeak = max(max(handLight.r, handLight.g), handLight.b);\n"
                + "\t\t\thandLight = colorfulHandTint * (colorfulHandPeak"
                + " / max(colorfulHandTintPeak, 0.0001));\n"
                + "\t\t}";
        String patched = source.substring(0, end + 1) + tint + source.substring(end + 1);
        if (patched.contains("uniform vec3 " + HELD_COLOR_NAME + ";")) {
            return patched;
        }
        return insertAfterVersion(patched, "uniform vec3 " + HELD_COLOR_NAME + ";\n"
                + "uniform vec3 " + HELD_COLOR_NAME + "2;\n");
    }

    /**
     * Swaps the hue of the pack's vertex-stage block-light color while keeping the
     * magnitude it derived from the lightmap, mirroring what the fragment-side
     * blocklightCol swap does for the other pack family.
     */
    private static String tintCandleColor(String source, String colorExpression) {
        if (candleAnchorEnd(source) < 0 || source.contains("colorfulCandleTint")) {
            return source;
        }
        int anchorEnd = candleBlockLightEnd(source);
        if (anchorEnd < 0) {
            return source;
        }

        String tint = "\n\tvec3 colorfulCandleSource = " + colorExpression + ";\n"
                + "\tfloat colorfulCandleStrength = max(max(colorfulCandleSource.r,"
                + " colorfulCandleSource.g), colorfulCandleSource.b);\n"
                + "\tif (colorfulCandleStrength > 0.0001) {\n"
                + "\t\tvec3 colorfulCandleTint = pow(clamp(colorfulCandleSource, vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                + "\t\tfloat colorfulCandleTintPeak = max(max(colorfulCandleTint.r,"
                + " colorfulCandleTint.g), colorfulCandleTint.b);\n"
                + "\t\tfloat colorfulCandlePeak = max(max(candleColor.r, candleColor.g), candleColor.b);\n"
                + "\t\tcandleColor = colorfulCandleTint * (colorfulCandlePeak"
                + " / max(colorfulCandleTintPeak, 0.0001));\n"
                + "\t}";
        // the pack maxes its held-item light in after this point, so it keeps its own
        // magnitude and only picks up the held emitter's color
        return tintHandLight(source.substring(0, anchorEnd) + tint + source.substring(anchorEnd));
    }

    public static boolean supports(String vertexSource, String fragmentSource) {
        return vertexSource != null
                && fragmentSource != null
                && vertexSource.contains("mc_Entity")
                && vertexSource.contains(MAIN)
                && (supportsFragmentTint(fragmentSource) || supportsVertexTint(vertexSource));
    }

    public static boolean usesVanillaLightCoords(String vertexSource) {
        return vertexSource != null
                && vertexSource.contains(UV2_DECLARATION)
                && vertexSource.contains(MAIN)
                && !vertexSource.contains(UV2_NAME);
    }

    public static boolean supportsVanillaTint(String fragmentSource) {
        return supportsFragmentTint(fragmentSource);
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
        String patched = redirected.substring(0, main + MAIN.length())
                + initialization
                + redirected.substring(main + MAIN.length());
        // vertex-lit packs consume the decoded color here instead of in the fragment
        return withTint ? tintCandleColor(patched, VARYING_NAME) : patched;
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

        String patched = insertAfterVersion(source, declarations)
                .replaceFirst("void main\\(\\) \\{", MAIN + initialization);
        // packs that light in the vertex stage are tinted here; the varying stays for
        // the fragment-side family and is simply unused by these packs
        return tintCandleColor(patched, VARYING_NAME);
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
        String dynamicHook = "";
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
            if (dynamicLightingSupported(patched)) {
                patched = patched.substring(0, patched.indexOf(HELD_LIGHTING_DEFINITION))
                        + dynamicLightDeclarations()
                        + patched.substring(patched.indexOf(HELD_LIGHTING_DEFINITION));
                dynamicHook = dynamicLightHook();
                IrisPatchState.recordDynamicLights();
            }
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
                + "\t}\n"
                + dynamicHook
                + "\t";

        patched = patched.substring(0, lightingCall) + hook + patched.substring(lightingCall);
        return insertAfterVersion(patched, declarations);
    }

    /**
     * The dynamic entity-light hook needs the fragment's camera-relative position and
     * the mutable lightmap coordinate local, both of which are in scope at the
     * DoLighting call site the hook precedes.
     */
    private static boolean dynamicLightingSupported(String source) {
        return source.contains("lmCoordM") && source.contains("playerPos");
    }

    /** One vec4 uniform per light slot: Iris's array uniforms upload a single vec4. */
    private static String dynamicLightDeclarations() {
        StringBuilder declarations = new StringBuilder();
        declarations.append("uniform int ").append(DYN_LIGHT_NAME).append("Count;\n");
        for (int i = 0; i < MAX_DYNAMIC_LIGHTS; i++) {
            declarations.append("uniform vec4 ").append(DYN_LIGHT_NAME).append(i).append(";\n");
            declarations.append("uniform vec4 ").append(DYN_LIGHT_NAME).append("Color").append(i).append(";\n");
        }
        return declarations.toString();
    }

    /**
     * Lightmap-equivalent entity lights: per-pixel continuous version of the block
     * engine's falloff (level minus one per block of distance, max-combined), merged
     * into the lightmap coordinate the pack shades with. The pack's lmCoordM.x equals
     * blockLightLevel / 15, so a level-N entity light renders exactly as bright as a
     * placed level-N source - LambDynamicLights semantics, without the block grid.
     */
    private static String dynamicLightHook() {
        StringBuilder hook = new StringBuilder();
        hook.append("\tif (").append(DYN_LIGHT_NAME).append("Count > 0) {\n");
        hook.append("\t\tvec4 colorfulDynLights[").append(MAX_DYNAMIC_LIGHTS).append("] = vec4[](");
        for (int i = 0; i < MAX_DYNAMIC_LIGHTS; i++) {
            if (i > 0) hook.append(", ");
            hook.append(DYN_LIGHT_NAME).append(i);
        }
        hook.append(");\n");
        hook.append("\t\tvec4 colorfulDynColorArr[").append(MAX_DYNAMIC_LIGHTS).append("] = vec4[](");
        for (int i = 0; i < MAX_DYNAMIC_LIGHTS; i++) {
            if (i > 0) hook.append(", ");
            hook.append(DYN_LIGHT_NAME).append("Color").append(i);
        }
        hook.append(");\n");
        hook.append("\t\tfloat colorfulDynLevel = 0.0;\n"
                + "\t\tvec3 colorfulDynColor = vec3(0.0);\n"
                + "\t\tfor (int i = 0; i < " + DYN_LIGHT_NAME + "Count; i++) {\n"
                + "\t\t\tvec4 dynLight = colorfulDynLights[i];\n"
                + "\t\t\tfloat dynLevelI = dynLight.w - length(playerPos - dynLight.xyz);\n"
                + "\t\t\tif (dynLevelI > colorfulDynLevel) {\n"
                + "\t\t\t\tcolorfulDynLevel = dynLevelI;\n"
                + "\t\t\t\tcolorfulDynColor = colorfulDynColorArr[i].rgb;\n"
                + "\t\t\t}\n"
                + "\t\t}\n"
                + "\t\tif (colorfulDynLevel > 0.0) {\n"
                + "\t\t\tfloat colorfulDynLm = min(colorfulDynLevel / 15.0, 1.0);\n"
                + "\t\t\tfloat colorfulDynW = colorfulDynLm / max(colorfulDynLm + lmCoordM.x, 0.0001);\n"
                + "\t\t\tblocklightCol = mix(blocklightCol, " + HELD_HELPER_NAME + "(colorfulDynColor), colorfulDynW);\n"
                + "\t\t\tlmCoordM.x = max(lmCoordM.x, colorfulDynLm);\n"
                + "\t\t}\n"
                + "\t}\n");
        return hook.toString();
    }

    private static String insertAfterVersion(String source, String insertion) {
        int lineEnd = source.indexOf('\n');
        if (lineEnd < 0) {
            return source + '\n' + insertion;
        }
        return source.substring(0, lineEnd + 1) + insertion + source.substring(lineEnd + 1);
    }
}
