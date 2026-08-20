package dev.colorfullighting.compat.iris.family;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.colorfullighting.compat.iris.family.PatchToolkit.lineIndent;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.lineStart;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.matchingParen;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.peakExpression;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.tintExpression;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.tintSwapBlock;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.FALLBACK_HAND_DISTANCE_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_COLOR_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_LEVEL_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.VERTEX_COLOR_NAME;

/**
 * MakeUp Ultra Fast and E-LITE: block light is computed in the VERTEX stage and passed
 * to the fragment as an already-shaped {@code candleColor}/{@code candle_color}
 * contribution. Their held light is max-combined into that value, so the block-light
 * hue must be swapped before the combine and the held term tinted independently.
 * Tinting after the final clamp repaints both terms with whichever block hue is
 * non-zero and produces a hard split where their magnitudes cross.
 *
 * <p>Both terms keep the peak-preserving normalization, unlike the Complementary
 * fragment path: these packs consume the block and held colors the same way, so the
 * two stay consistent with each other whichever invariant is chosen - and the
 * pipeline's 0..4 candle clamp makes PEAK the only clip-safe choice (a
 * luminance-preserving saturated hue would push single channels 3-9x higher).
 */
final class MakeUpFamily implements ShaderFamily {
    private static final String MARKER = "colorfulLightingSodiumCompat_MakeUpTint";
    private static final Pattern CANDLE_CAMEL = Pattern.compile(
            "\\bcandleColor\\s*=\\s*clamp\\s*\\(\\s*candleColor\\s*,"
                    + "\\s*vec3\\s*\\(\\s*0(?:\\.0)?f?\\s*\\)\\s*,"
                    + "\\s*vec3\\s*\\(\\s*4(?:\\.0)?f?\\s*\\)\\s*\\)\\s*;"
    );
    private static final Pattern CANDLE_SNAKE = Pattern.compile(
            "\\bcandle_color\\s*=\\s*clamp\\s*\\(\\s*candle_color\\s*,"
                    + "\\s*0(?:\\.0)?f?\\s*,\\s*4(?:\\.0)?f?\\s*\\)\\s*;"
    );
    private static final Pattern HELD_ITEM_BRANCH = Pattern.compile(
            "\\bif\\s*\\([^{};]*heldItemId2?[^{};]*\\)\\s*\\{"
    );

    @Override
    public String id() {
        return "makeup";
    }

    @Override
    public boolean matchesFragmentTint(String fragmentSource) {
        return false;
    }

    @Override
    public boolean matchesVertexTint(String vertexSource) {
        return findCandleHook(vertexSource) != null;
    }

    @Override
    public String patchVertexLighting(String source) {
        if (source.contains(MARKER)) {
            return source;
        }

        CandleHook candleHook = findCandleHook(source);
        if (candleHook == null) {
            return source;
        }
        String candle = candleHook.variable();

        String blockHook = "\t// " + MARKER + "\n"
                + tintSwapBlock("\t", "colorful", VERTEX_COLOR_NAME, candle, false);

        String patched = source;
        HeldHook heldHook = candleHook.heldHook();
        if (heldHook != null) {
            String indent = lineIndent(source, heldHook.start());
            String handLight = "colorfulLightingSodiumCompat_HandLight";
            String handSource = "colorfulLightingSodiumCompat_HandSource";
            String heldReplacement = "// colorfulLightingSodiumCompat_HandTint\n"
                    + indent + "vec3 " + handLight + " = " + heldHook.expression() + ";\n"
                    + indent + "vec3 " + handSource + " = max(" + HELD_COLOR_NAME + ", "
                    + HELD_COLOR_NAME + "2);\n"
                    + tintSwapBlock(indent, "colorfulHand", handSource, handLight, false)
                    + indent + candle + " = max(" + candle + ", " + handLight + ");";
            patched = source.substring(0, heldHook.start())
                    + heldReplacement
                    + source.substring(heldHook.end());
        }

        int blockInsertion = candleHook.blockTintInsertion();
        String fallbackHandHook = fallbackHandHook(source, candle, heldHook);
        return patched.substring(0, blockInsertion)
                + blockHook
                + fallbackHandHook
                + patched.substring(blockInsertion);
    }

    private static String fallbackHandHook(
            String source,
            String candle,
            HeldHook heldHook
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
        // The strength check is fused into the outer guard here (a zero hand color
        // must skip the whole fallback, not apply an untinted pack hand light), so
        // this site keeps its own structure and shares only the tint expressions.
        return "\t// colorfulLightingSodiumCompat_FallbackHandTint\n"
                + "\tfloat " + handLevel + " = float(max(" + HELD_LEVEL_NAME + ", "
                + HELD_LEVEL_NAME + "2));\n"
                + "\tvec3 " + handSource + " = " + HELD_LEVEL_NAME + " >= "
                + HELD_LEVEL_NAME + "2 ? " + HELD_COLOR_NAME + " : " + HELD_COLOR_NAME + "2;\n"
                + "\tfloat colorfulFallbackHandStrength = " + peakExpression(handSource) + ";\n"
                + "\tif (" + handLevel + " > 0.0 && colorfulFallbackHandStrength > 0.0001) {\n"
                + "\t\tfloat " + FALLBACK_HAND_DISTANCE_NAME + " = clamp((" + handLevel
                + " - " + fogDistance + ") / 15.0, 0.0, 1.0);\n"
                + "\t\tif (" + FALLBACK_HAND_DISTANCE_NAME + " > 0.0) {\n"
                + "\t\t\tvec3 " + handLight + " = " + heldHook.fallbackExpression() + ";\n"
                + "\t\t\tvec3 colorfulFallbackHandTint = " + tintExpression(handSource) + ";\n"
                + "\t\t\tfloat colorfulFallbackHandTintPeak = "
                + peakExpression("colorfulFallbackHandTint") + ";\n"
                + "\t\t\tfloat colorfulFallbackHandPeak = " + peakExpression(handLight) + ";\n"
                + "\t\t\t" + handLight + " = colorfulFallbackHandTint *"
                + " (colorfulFallbackHandPeak / max(colorfulFallbackHandTintPeak, 0.0001));\n"
                + "\t\t\t" + candle + " = max(" + candle + ", " + handLight + ");\n"
                + "\t\t}\n"
                + "\t}\n";
    }

    private static CandleHook findCandleHook(String source) {
        if (source == null) {
            return null;
        }
        Matcher camel = CANDLE_CAMEL.matcher(source);
        if (camel.find()) {
            return candleHook(source, "candleColor", camel.start());
        }
        Matcher snake = CANDLE_SNAKE.matcher(source);
        if (snake.find()) {
            return candleHook(source, "candle_color", snake.start());
        }
        return null;
    }

    private static CandleHook candleHook(
            String source,
            String variable,
            int clampStart
    ) {
        HeldHook heldHook = findHeldHook(source, variable, clampStart);
        int insertion = lineStart(source, clampStart);
        if (heldHook != null) {
            insertion = findDynamicHandBranchStart(source, variable, heldHook.start());
        }
        return new CandleHook(variable, insertion, heldHook);
    }

    private static HeldHook findHeldHook(
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
                return new HeldHook(
                        matcher.start(),
                        statementEnd + 1,
                        expression,
                        findFallbackHandExpression(source, matcher.start())
                );
            }
        }
        return null;
    }

    private static String findFallbackHandExpression(String source, int combineStart) {
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

        Matcher heldBranch = HELD_ITEM_BRANCH.matcher(source);
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

    private record CandleHook(
            String variable,
            int blockTintInsertion,
            HeldHook heldHook
    ) {
    }

    private record HeldHook(
            int start,
            int end,
            String expression,
            String fallbackExpression
    ) {
    }
}
