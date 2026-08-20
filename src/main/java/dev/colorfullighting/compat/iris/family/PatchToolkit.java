package dev.colorfullighting.compat.iris.family;

import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_AUTHORITY_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_COLOR_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_LEVEL_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.LUMA_WEIGHTS;

/**
 * Text-surgery and GLSL-snippet helpers shared by the core patcher and every shader
 * family. Family code should compose these instead of re-writing the idioms: the
 * tint curve, the epsilon, and the metric invariants live here exactly once.
 */
public final class PatchToolkit {
    private PatchToolkit() {
    }

    /**
     * Inserts declarations after the {@code #version} line, skipping the contiguous
     * preprocessor prologue: {@code #extension} and {@code #pragma} directives must
     * precede any declaration on strict compilers.
     */
    public static String insertAfterVersion(String source, String insertion) {
        int lineEnd = source.indexOf('\n');
        if (lineEnd < 0) {
            return source + '\n' + insertion;
        }
        int insertAt = lineEnd + 1;
        while (insertAt < source.length()) {
            int nextEnd = source.indexOf('\n', insertAt);
            String line = (nextEnd < 0 ? source.substring(insertAt) : source.substring(insertAt, nextEnd)).strip();
            if (!line.isEmpty() && !line.startsWith("#extension") && !line.startsWith("#pragma")) {
                break;
            }
            insertAt = nextEnd < 0 ? source.length() : nextEnd + 1;
        }
        return source.substring(0, insertAt) + insertion + source.substring(insertAt);
    }

    /** The held-item uniform declarations (colors, levels, authority - both hands). */
    public static String heldColorDeclarations() {
        return "uniform vec3 " + HELD_COLOR_NAME + ";\n"
                + "uniform vec3 " + HELD_COLOR_NAME + "2;\n"
                + "uniform int " + HELD_LEVEL_NAME + ";\n"
                + "uniform int " + HELD_LEVEL_NAME + "2;\n"
                + "uniform int " + HELD_AUTHORITY_NAME + ";\n"
                + "uniform int " + HELD_AUTHORITY_NAME + "2;\n";
    }

    /** {@code pow(clamp(src, 0, 1), 1.3)} - the shared tint-shaping curve. */
    public static String tintExpression(String sourceExpression) {
        return "pow(clamp(" + sourceExpression + ", vec3(0.0), vec3(1.0)), vec3(1.3))";
    }

    /** Peak channel of a vec3 expression. */
    public static String peakExpression(String vector) {
        return "max(max(" + vector + ".r, " + vector + ".g), " + vector + ".b)";
    }

    /** Rec.601 luminance of a vec3 expression (see ShaderPatchNames.LUMA_WEIGHTS). */
    public static String lumaExpression(String vector) {
        return "dot(" + vector + ", " + LUMA_WEIGHTS + ")";
    }

    /**
     * The shared hue-swap block: when the colorful source is non-black, replace the
     * target's hue with the shaped tint, rescaled so the target keeps its previous
     * magnitude under the given metric. Choose the metric from how the pack consumes
     * the color: LUMINANCE when the pack multiplies the hue into its lighting
     * unchanged (Complementary's fragment path - the only invariant its unnormalized
     * block hue and luminance-normalized held hue share); PEAK when the pack clamps
     * the value afterwards (MakeUp's 0..4 candle pipeline - a luminance-preserving
     * saturated hue would push single channels 3-9x higher and clip).
     */
    public static String tintSwapBlock(
            String indent,
            String prefix,
            String sourceExpression,
            String targetVariable,
            boolean preserveLuma
    ) {
        String strength = prefix + "Strength";
        String tint = prefix + "Tint";
        String tintMetric = tint + (preserveLuma ? "Luma" : "Peak");
        String targetMetric = prefix + (preserveLuma ? "TargetLuma" : "TargetPeak");
        String metricOfTint = preserveLuma ? lumaExpression(tint) : peakExpression(tint);
        String metricOfTarget = preserveLuma
                ? lumaExpression(targetVariable)
                : peakExpression(targetVariable);
        return indent + "float " + strength + " = " + peakExpression(sourceExpression) + ";\n"
                + indent + "if (" + strength + " > 0.0001) {\n"
                + indent + "\tvec3 " + tint + " = " + tintExpression(sourceExpression) + ";\n"
                + indent + "\tfloat " + tintMetric + " = " + metricOfTint + ";\n"
                + indent + "\tfloat " + targetMetric + " = " + metricOfTarget + ";\n"
                + indent + "\t" + targetVariable + " = " + tint
                        + " * (" + targetMetric + " / max(" + tintMetric + ", 0.0001));\n"
                + indent + "}\n";
    }

    /**
     * The injected helper turning an emitter color (0..1 RGB) into a hue that carries
     * the pack's original block-light magnitude: luminance-preserving against
     * {@code PACK_LIGHT_NAME}, falling back to the pack hue for a black input. The
     * caller must have declared {@code PACK_LIGHT_NAME} earlier in the same insertion.
     */
    public static String packLightTintHelper() {
        return "vec3 " + ShaderPatchNames.HELD_HELPER_NAME + "(vec3 heldColor) {\n"
                + "\tfloat heldPeak = " + peakExpression("heldColor") + ";\n"
                + "\tif (heldPeak < 0.001) return " + ShaderPatchNames.PACK_LIGHT_NAME + ";\n"
                + "\tvec3 heldTint = " + tintExpression("heldColor") + ";\n"
                + "\tfloat heldTintLuma = " + lumaExpression("heldTint") + ";\n"
                + "\tfloat packLuma = " + lumaExpression(ShaderPatchNames.PACK_LIGHT_NAME) + ";\n"
                + "\treturn heldTint * (packLuma / max(heldTintLuma, 0.0001));\n"
                + "}\n";
    }

    /** One vec4 uniform per light slot: Iris's array uniforms upload a single vec4. */
    public static String dynamicLightDeclarations() {
        StringBuilder declarations = new StringBuilder();
        declarations.append("uniform int ").append(ShaderPatchNames.DYN_LIGHT_NAME).append("Count;\n");
        for (int i = 0; i < ShaderPatchNames.MAX_DYNAMIC_LIGHTS; i++) {
            declarations.append("uniform vec4 ").append(ShaderPatchNames.DYN_LIGHT_NAME).append(i).append(";\n");
            declarations.append("uniform vec4 ").append(ShaderPatchNames.DYN_LIGHT_NAME).append("Color").append(i).append(";\n");
        }
        return declarations.toString();
    }

    /**
     * Lightmap-equivalent entity lights: per-pixel continuous version of the block
     * engine's falloff (level minus one per block of distance, max-combined), merged
     * into the lightmap coordinate the pack shades with. Hue is a continuous weighted
     * blend of every in-range entity light; selecting the strongest light's hue would
     * create a hard boundary wherever two differently colored lights have equal level.
     * The pack's block-light lightmap coordinate equals blockLightLevel / 15, so a
     * level-N entity light renders exactly as bright as a placed level-N source -
     * LambDynamicLights semantics, without the block grid.
     *
     * @param positionVariable  the pack's camera-relative fragment position local in
     *                          scope at the insertion point (e.g. "playerPos")
     * @param lightmapXVariable the pack's mutable block-light lightmap coordinate
     *                          (e.g. "lmCoordM.x" or "lightmap.x")
     */
    public static String dynamicLightHook(String positionVariable, String lightmapXVariable) {
        StringBuilder hook = new StringBuilder();
        hook.append("\tif (").append(ShaderPatchNames.DYN_LIGHT_NAME).append("Count > 0) {\n");
        hook.append("\t\tvec4 colorfulDynLights[").append(ShaderPatchNames.MAX_DYNAMIC_LIGHTS).append("] = vec4[](");
        for (int i = 0; i < ShaderPatchNames.MAX_DYNAMIC_LIGHTS; i++) {
            if (i > 0) hook.append(", ");
            hook.append(ShaderPatchNames.DYN_LIGHT_NAME).append(i);
        }
        hook.append(");\n");
        hook.append("\t\tvec4 colorfulDynColorArr[").append(ShaderPatchNames.MAX_DYNAMIC_LIGHTS).append("] = vec4[](");
        for (int i = 0; i < ShaderPatchNames.MAX_DYNAMIC_LIGHTS; i++) {
            if (i > 0) hook.append(", ");
            hook.append(ShaderPatchNames.DYN_LIGHT_NAME).append("Color").append(i);
        }
        hook.append(");\n");
        hook.append("\t\tfloat colorfulDynLevel = 0.0;\n"
                + "\t\tvec3 colorfulDynColorSum = vec3(0.0);\n"
                + "\t\tfloat colorfulDynColorWeight = 0.0;\n"
                + "\t\tfor (int i = 0; i < " + ShaderPatchNames.DYN_LIGHT_NAME + "Count; i++) {\n"
                + "\t\t\tvec4 dynLight = colorfulDynLights[i];\n"
                + "\t\t\tfloat dynLevelI = dynLight.w - length(" + positionVariable + " - dynLight.xyz);\n"
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
                + "\t\t\tfloat colorfulDynW = colorfulDynLm / max(colorfulDynLm + " + lightmapXVariable + ", 0.0001);\n"
                + "\t\t\tblocklightCol = mix(blocklightCol, " + ShaderPatchNames.HELD_HELPER_NAME + "(colorfulDynColor), colorfulDynW);\n"
                + "\t\t\t" + lightmapXVariable + " = max(" + lightmapXVariable + ", colorfulDynLm);\n"
                + "\t\t}\n"
                + "\t}\n");
        return hook.toString();
    }

    /** Index of the first character of the line containing {@code index}. */
    public static int lineStart(String source, int index) {
        return source.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
    }

    /** The whitespace prefix of the line containing {@code index}, up to it. */
    public static String lineIndent(String source, int index) {
        int lineStart = lineStart(source, index);
        return source.substring(lineStart, index);
    }

    /** Index of the parenthesis closing the one at {@code openParen}, or -1. */
    public static int matchingParen(String source, int openParen) {
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
}
