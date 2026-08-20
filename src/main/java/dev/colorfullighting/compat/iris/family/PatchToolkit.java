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
