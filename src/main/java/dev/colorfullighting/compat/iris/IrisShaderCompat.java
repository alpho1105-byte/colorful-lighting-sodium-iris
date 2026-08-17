package dev.colorfullighting.compat.iris;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisShaderCompat {
    private static final String ATTRIBUTE_NAME = "colorfulLightingSodiumCompat_Light";
    private static final String VARYING_NAME = "colorfulLightingSodiumCompat_Color";
    private static final String VERTEX_COLOR_NAME = "colorfulLightingSodiumCompat_VertexColor";
    private static final String UV2_NAME = "colorfulLightingSodiumCompat_UV2";
    private static final String PACK_LIGHT_NAME = "colorfulLightingSodiumCompat_PackBlocklight";
    private static final String HELD_COLOR_NAME = "colorfulLightingSodiumCompat_HeldColor";
    private static final String HELD_LEVEL_NAME = "colorfulLightingSodiumCompat_HeldLevel";
    private static final String HELD_HELPER_NAME = "colorfulLightingSodiumCompat_HeldLight";
    private static final String FALLBACK_HAND_DISTANCE_NAME =
            "colorfulLightingSodiumCompat_FallbackHandDistance";
    private static final String HELD_LIGHTING_DEFINITION = "vec3 GetHeldLighting(";
    private static final String DYN_LIGHT_NAME = "colorfulLightingSodiumCompat_DynLight";
    /** Keep in sync with EntityLightManager.MAX_SHADER_LIGHTS. */
    private static final int MAX_DYNAMIC_LIGHTS = 8;
    private static final String UV2_DECLARATION = "in ivec2 iris_UV2;";
    private static final String MAIN = "void main() {";
    private static final String LIGHTING_CALL = "DoLighting(color,";
    private static final Pattern MAKEUP_CANDLE_CAMEL = Pattern.compile(
            "\\bcandleColor\\s*=\\s*clamp\\s*\\(\\s*candleColor\\s*,"
                    + "\\s*vec3\\s*\\(\\s*0(?:\\.0)?f?\\s*\\)\\s*,"
                    + "\\s*vec3\\s*\\(\\s*4(?:\\.0)?f?\\s*\\)\\s*\\)\\s*;"
    );
    private static final Pattern MAKEUP_CANDLE_SNAKE = Pattern.compile(
            "\\bcandle_color\\s*=\\s*clamp\\s*\\(\\s*candle_color\\s*,"
                    + "\\s*0(?:\\.0)?f?\\s*,\\s*4(?:\\.0)?f?\\s*\\)\\s*;"
    );
    private static final Pattern MAKEUP_HELD_ITEM_BRANCH = Pattern.compile(
            "\\bif\\s*\\([^{};]*heldItemId2?[^{};]*\\)\\s*\\{"
    );

    private IrisShaderCompat() {
    }

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

    public static boolean supportsVanillaTint(String fragmentSource) {
        return fragmentSource != null
                && fragmentSource.contains("vec3 blocklightCol")
                && fragmentSource.contains(LIGHTING_CALL);
    }

    /** MakeUp Ultra Fast and E-LITE calculate their block-light color in the vertex stage. */
    public static boolean supportsVanillaVertexTint(String vertexSource) {
        return makeupCandleVariable(vertexSource) != null;
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
     * to its fragment stage (Complementary) or to its own vertex lighting (MakeUp).
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
                        + heldColorDeclarations() : "");
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
        return withVertexTint ? patchMakeUpVertexLighting(sanitized) : sanitized;
    }

    public static String patchVertex(String source) {
        if (source.contains(ATTRIBUTE_NAME)) {
            return source;
        }

        boolean makeUp = supportsVanillaVertexTint(source);
        String declarations = "layout(location = 15) in uvec4 " + ATTRIBUTE_NAME + ";\n"
                + (makeUp
                        ? "vec3 " + VERTEX_COLOR_NAME + ";\n" + heldColorDeclarations()
                        : "out vec3 " + VARYING_NAME + ";\n");
        String initialization = "\n\tuvec4 colorfulPacked = " + ATTRIBUTE_NAME + ";\n"
                + "\tuint colorfulMarker = colorfulPacked.a >> 4u;\n"
                + "\tuint colorfulBlue = ((colorfulPacked.a & 15u) << 4u) | (colorfulPacked.b >> 4u);\n"
                + "\t" + (makeUp ? VERTEX_COLOR_NAME : VARYING_NAME)
                + " = colorfulMarker == 15u\n"
                + "\t\t? vec3(colorfulPacked.r, colorfulPacked.g, colorfulBlue) / 255.0\n"
                + "\t\t: vec3(0.0);";

        String patched = insertAfterVersion(source, declarations)
                .replaceFirst("void main\\(\\) \\{", MAIN + initialization);
        return makeUp ? patchMakeUpVertexLighting(patched) : patched;
    }

    public static String patchFragment(String source) {
        if (source.contains(VARYING_NAME) || !supportsVanillaTint(source)) {
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

    private static String heldColorDeclarations() {
        return "uniform vec3 " + HELD_COLOR_NAME + ";\n"
                + "uniform vec3 " + HELD_COLOR_NAME + "2;\n"
                + "uniform int " + HELD_LEVEL_NAME + ";\n"
                + "uniform int " + HELD_LEVEL_NAME + "2;\n";
    }

    /**
     * MakeUp/E-LITE pass their already-shaped block-light contribution to the fragment
     * as candleColor/candle_color. Their held light is max-combined into that value, so
     * the block-light hue must be swapped before the combine and the held term tinted
     * independently. Tinting after the final clamp repaints both terms with whichever
     * block hue is non-zero and produces a hard split where their magnitudes cross.
     */
    private static String patchMakeUpVertexLighting(String source) {
        if (source.contains("colorfulLightingSodiumCompat_MakeUpTint")) {
            return source;
        }

        MakeUpCandleHook candleHook = findMakeUpCandleHook(source);
        if (candleHook == null) {
            return source;
        }
        String candle = candleHook.variable();

        String blockHook = "\t// colorfulLightingSodiumCompat_MakeUpTint\n"
                + "\tvec3 colorfulSource = " + VERTEX_COLOR_NAME + ";\n"
                + "\tfloat colorfulSourceStrength = max(max(colorfulSource.r, colorfulSource.g), colorfulSource.b);\n"
                + "\tif (colorfulSourceStrength > 0.0001) {\n"
                + "\t\tvec3 colorfulTint = pow(clamp(colorfulSource, vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                + "\t\tfloat colorfulTintPeak = max(max(colorfulTint.r, colorfulTint.g), colorfulTint.b);\n"
                + "\t\tfloat shaderPackPeak = max(max(" + candle + ".r, " + candle + ".g), " + candle + ".b);\n"
                + "\t\t" + candle + " = colorfulTint * (shaderPackPeak / max(colorfulTintPeak, 0.0001));\n"
                + "\t}\n";

        String patched = source;
        MakeUpHeldHook heldHook = candleHook.heldHook();
        if (heldHook != null) {
            String indent = lineIndent(source, heldHook.start());
            String handLight = "colorfulLightingSodiumCompat_HandLight";
            String handSource = "colorfulLightingSodiumCompat_HandSource";
            String heldReplacement = "// colorfulLightingSodiumCompat_HandTint\n"
                    + indent + "vec3 " + handLight + " = " + heldHook.expression() + ";\n"
                    + indent + "vec3 " + handSource + " = max(" + HELD_COLOR_NAME + ", "
                    + HELD_COLOR_NAME + "2);\n"
                    + indent + "float colorfulHandStrength = max(max(" + handSource + ".r, "
                    + handSource + ".g), " + handSource + ".b);\n"
                    + indent + "if (colorfulHandStrength > 0.0001) {\n"
                    + indent + "\tvec3 colorfulHandTint = pow(clamp(" + handSource
                    + ", vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                    + indent + "\tfloat colorfulHandTintPeak = max(max(colorfulHandTint.r,"
                    + " colorfulHandTint.g), colorfulHandTint.b);\n"
                    + indent + "\tfloat colorfulHandPeak = max(max(" + handLight + ".r, "
                    + handLight + ".g), " + handLight + ".b);\n"
                    + indent + "\t" + handLight + " = colorfulHandTint * (colorfulHandPeak"
                    + " / max(colorfulHandTintPeak, 0.0001));\n"
                    + indent + "}\n"
                    + indent + candle + " = max(" + candle + ", " + handLight + ");";
            patched = source.substring(0, heldHook.start())
                    + heldReplacement
                    + source.substring(heldHook.end());
        }

        int blockInsertion = candleHook.blockTintInsertion();
        String fallbackHandHook = makeUpFallbackHandHook(source, candle, heldHook);
        return patched.substring(0, blockInsertion)
                + blockHook
                + fallbackHandHook
                + patched.substring(blockInsertion);
    }

    private static String makeUpFallbackHandHook(
            String source,
            String candle,
            MakeUpHeldHook heldHook
    ) {
        if (heldHook == null || heldHook.fallbackExpression() == null
                || !"candleColor".equals(candle)) {
            return "";
        }
        String fogDistance = source.contains("iris_FogFragCoord")
                ? "iris_FogFragCoord"
                : source.contains("gl_FogFragCoord") ? "gl_FogFragCoord" : null;
        if (fogDistance == null) {
            return "";
        }

        String handLevel = "colorfulLightingSodiumCompat_FallbackHandLevel";
        String handSource = "colorfulLightingSodiumCompat_FallbackHandSource";
        String handLight = "colorfulLightingSodiumCompat_FallbackHandLight";
        return "\t// colorfulLightingSodiumCompat_FallbackHandTint\n"
                + "\tfloat " + handLevel + " = float(max(" + HELD_LEVEL_NAME + ", "
                + HELD_LEVEL_NAME + "2));\n"
                + "\tvec3 " + handSource + " = " + HELD_LEVEL_NAME + " >= "
                + HELD_LEVEL_NAME + "2 ? " + HELD_COLOR_NAME + " : " + HELD_COLOR_NAME + "2;\n"
                + "\tfloat colorfulFallbackHandStrength = max(max(" + handSource + ".r, "
                + handSource + ".g), " + handSource + ".b);\n"
                + "\tif (" + handLevel + " > 0.0 && colorfulFallbackHandStrength > 0.0001) {\n"
                + "\t\tfloat " + FALLBACK_HAND_DISTANCE_NAME + " = clamp((" + handLevel
                + " - " + fogDistance + ") / 15.0, 0.0, 1.0);\n"
                + "\t\tif (" + FALLBACK_HAND_DISTANCE_NAME + " > 0.0) {\n"
                + "\t\t\tvec3 " + handLight + " = " + heldHook.fallbackExpression() + ";\n"
                + "\t\t\tvec3 colorfulFallbackHandTint = pow(clamp(" + handSource
                + ", vec3(0.0), vec3(1.0)), vec3(1.3));\n"
                + "\t\t\tfloat colorfulFallbackHandTintPeak = max(max(colorfulFallbackHandTint.r,"
                + " colorfulFallbackHandTint.g), colorfulFallbackHandTint.b);\n"
                + "\t\t\tfloat colorfulFallbackHandPeak = max(max(" + handLight + ".r, "
                + handLight + ".g), " + handLight + ".b);\n"
                + "\t\t\t" + handLight + " = colorfulFallbackHandTint *"
                + " (colorfulFallbackHandPeak / max(colorfulFallbackHandTintPeak, 0.0001));\n"
                + "\t\t\t" + candle + " = max(" + candle + ", " + handLight + ");\n"
                + "\t\t}\n"
                + "\t}\n";
    }

    private static String makeupCandleVariable(String source) {
        MakeUpCandleHook hook = findMakeUpCandleHook(source);
        return hook == null ? null : hook.variable();
    }

    private static MakeUpCandleHook findMakeUpCandleHook(String source) {
        if (source == null) {
            return null;
        }
        Matcher camel = MAKEUP_CANDLE_CAMEL.matcher(source);
        if (camel.find()) {
            return makeUpCandleHook(source, "candleColor", camel.start());
        }
        Matcher snake = MAKEUP_CANDLE_SNAKE.matcher(source);
        if (snake.find()) {
            return makeUpCandleHook(source, "candle_color", snake.start());
        }
        return null;
    }

    private static MakeUpCandleHook makeUpCandleHook(
            String source,
            String variable,
            int clampStart
    ) {
        MakeUpHeldHook heldHook = findMakeUpHeldHook(source, variable, clampStart);
        int insertion = lineStart(source, clampStart);
        if (heldHook != null) {
            insertion = findDynamicHandBranchStart(source, variable, heldHook.start());
        }
        return new MakeUpCandleHook(variable, insertion, heldHook);
    }

    private static MakeUpHeldHook findMakeUpHeldHook(
            String source,
            String variable,
            int clampStart
    ) {
        Pattern combine = Pattern.compile(
                "\\b" + Pattern.quote(variable) + "\\s*=\\s*max\\s*\\(\\s*"
                        + Pattern.quote(variable) + "\\s*,"
        );
        Matcher matcher = combine.matcher(source);
        while (matcher.find()) {
            if (matcher.start() >= clampStart) {
                return null;
            }
            int openParen = source.indexOf('(', matcher.start());
            int closeParen = matchingParen(source, openParen);
            if (closeParen < 0) {
                return null;
            }
            int statementEnd = closeParen + 1;
            while (statementEnd < source.length()
                    && Character.isWhitespace(source.charAt(statementEnd))) {
                statementEnd++;
            }
            if (statementEnd >= source.length() || source.charAt(statementEnd) != ';') {
                return null;
            }
            String expression = source.substring(matcher.end(), closeParen).trim();
            if (!expression.isEmpty()) {
                return new MakeUpHeldHook(
                        matcher.start(),
                        statementEnd + 1,
                        expression,
                        findMakeUpFallbackHandExpression(source, matcher.start())
                );
            }
        }
        return null;
    }

    private static String findMakeUpFallbackHandExpression(String source, int combineStart) {
        Pattern handAssignment = Pattern.compile("\\bvec3\\s+handLight\\s*=\\s*");
        Matcher matcher = handAssignment.matcher(source);
        String expression = null;
        while (matcher.find() && matcher.start() < combineStart) {
            int statementEnd = source.indexOf(';', matcher.end());
            if (statementEnd < 0 || statementEnd >= combineStart) {
                break;
            }
            expression = source.substring(matcher.end(), statementEnd).trim();
        }
        if (expression == null || !Pattern.compile("\\bhandDistance\\b")
                .matcher(expression).find()) {
            return null;
        }
        return expression.replaceAll("\\bhandDistance\\b", FALLBACK_HAND_DISTANCE_NAME);
    }

    private static int matchingParen(String source, int openParen) {
        if (openParen < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = openParen; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '(') {
                depth++;
            }
            else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int findDynamicHandBranchStart(
            String source,
            String variable,
            int combineStart
    ) {
        int directive = source.lastIndexOf("#ifdef DYN_HAND_LIGHT", combineStart);
        int lastEndif = source.lastIndexOf("#endif", combineStart);
        if (directive >= 0 && lastEndif < directive) {
            return lineStart(source, directive);
        }

        Matcher heldBranch = MAKEUP_HELD_ITEM_BRANCH.matcher(source);
        int heldBranchStart = -1;
        while (heldBranch.find() && heldBranch.end() <= combineStart) {
            heldBranchStart = heldBranch.start();
        }
        if (heldBranchStart >= 0) {
            return lineStart(source, heldBranchStart);
        }

        Pattern blockAssignment = Pattern.compile(
                "\\b" + Pattern.quote(variable) + "\\s*=\\s*"
                        + "(?!max\\s*\\(|clamp\\s*\\()"
        );
        Matcher assignment = blockAssignment.matcher(source);
        int blockLightEnd = -1;
        while (assignment.find() && assignment.start() < combineStart) {
            int statementEnd = source.indexOf(';', assignment.end());
            if (statementEnd < 0 || statementEnd >= combineStart) {
                break;
            }
            blockLightEnd = statementEnd + 1;
        }
        if (blockLightEnd >= 0) {
            int nextLine = source.indexOf('\n', blockLightEnd);
            return nextLine < 0 ? blockLightEnd : nextLine + 1;
        }
        return lineStart(source, combineStart);
    }

    private static int lineStart(String source, int index) {
        return source.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
    }

    private static String lineIndent(String source, int index) {
        int lineStart = lineStart(source, index);
        return source.substring(lineStart, index);
    }

    private record MakeUpCandleHook(
            String variable,
            int blockTintInsertion,
            MakeUpHeldHook heldHook
    ) {
    }

    private record MakeUpHeldHook(
            int start,
            int end,
            String expression,
            String fallbackExpression
    ) {
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
     * into the lightmap coordinate the pack shades with. Hue is a continuous weighted
     * blend of every in-range entity light; selecting the strongest light's hue would
     * create a hard boundary wherever two differently colored lights have equal level.
     * The pack's lmCoordM.x equals blockLightLevel / 15, so a level-N entity light
     * renders exactly as bright as a placed level-N source - LambDynamicLights
     * semantics, without the block grid.
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
                + "\t\tvec3 colorfulDynColorSum = vec3(0.0);\n"
                + "\t\tfloat colorfulDynColorWeight = 0.0;\n"
                + "\t\tfor (int i = 0; i < " + DYN_LIGHT_NAME + "Count; i++) {\n"
                + "\t\t\tvec4 dynLight = colorfulDynLights[i];\n"
                + "\t\t\tfloat dynLevelI = dynLight.w - length(playerPos - dynLight.xyz);\n"
                + "\t\t\tif (dynLevelI > 0.0) {\n"
                + "\t\t\t\tvec3 dynColor = colorfulDynColorArr[i].rgb;\n"
                + "\t\t\t\tcolorfulDynLevel = max(colorfulDynLevel, dynLevelI);\n"
                + "\t\t\t\tcolorfulDynColorSum += dynColor * dynLevelI;\n"
                + "\t\t\t\tcolorfulDynColorWeight += dynLevelI;\n"
                + "\t\t\t}\n"
                + "\t\t}\n"
                + "\t\tif (colorfulDynLevel > 0.0) {\n"
                + "\t\t\tvec3 colorfulDynColor = colorfulDynColorSum / colorfulDynColorWeight;\n"
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
