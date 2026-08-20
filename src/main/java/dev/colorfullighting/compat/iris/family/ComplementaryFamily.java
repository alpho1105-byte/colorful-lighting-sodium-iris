package dev.colorfullighting.compat.iris.family;

import dev.colorfullighting.compat.iris.IrisPatchState;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.colorfullighting.compat.iris.family.PatchToolkit.heldColorDeclarations;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.insertAfterVersion;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.lineIndent;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.lineStart;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.lumaExpression;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.peakExpression;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.tintExpression;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.tintSwapBlock;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.DYN_LIGHT_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_AUTHORITY_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_COLOR_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_HELPER_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_LEVEL_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.MAX_DYNAMIC_LIGHTS;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.PACK_LIGHT_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.VARYING_NAME;

/**
 * Complementary Reimagined/Unbound (and close derivatives): block light is computed in
 * the fragment stage as {@code blocklightCol} consumed by {@code DoLighting(...)}. The
 * hue swap preserves LUMINANCE (see ShaderPatchNames.LUMA_WEIGHTS); the held-light and
 * per-pixel entity-light hooks are anchored on the pack's {@code GetHeldLighting}
 * helper and its {@code lmCoordM}/{@code playerPos} locals.
 */
final class ComplementaryFamily implements ShaderFamily {
    private static final String LIGHTING_CALL = "DoLighting(color,";
    private static final String HELD_LIGHTING_DEFINITION = "vec3 GetHeldLighting(";
    private static final Pattern HELD_LEVEL_DECLARATION = Pattern.compile(
            "\\bfloat\\s+heldLight\\s*=\\s*heldBlockLightValue\\s*;\\s*"
                    + "float\\s+heldLight2\\s*=\\s*heldBlockLightValue2\\s*;"
    );
    /** Unbound nests the attenuation as pow2(pow2(heldLight ...)); older packs do not. */
    private static final Pattern HELD_ATTENUATION = Pattern.compile(
            "\\bheldLight\\s*=\\s*pow2\\s*\\(\\s*(?:pow2\\s*\\(\\s*)?heldLight\\b"
    );

    @Override
    public String id() {
        return "complementary";
    }

    @Override
    public boolean matchesFragmentTint(String fragmentSource) {
        return fragmentSource != null
                && fragmentSource.contains("vec3 blocklightCol")
                && fragmentSource.contains(LIGHTING_CALL);
    }

    @Override
    public boolean matchesVertexTint(String vertexSource) {
        return false;
    }

    @Override
    public String patchFragment(String source) {
        if (source.contains(VARYING_NAME) || !matchesFragmentTint(source)) {
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
        // fully saturated wash wherever the held light dominates. Each hand therefore
        // receives Colorful Lighting's authoritative level and emitter color. A zero
        // level disables the pack's own item definition; its captured blocklight hue is
        // only a harmless color fallback when no custom tint exists.
        //
        // Both hues carry the pack block-light color's luminance (see LUMA_WEIGHTS).
        // The pack divides the held hue by its own luminance before use and the block
        // hue not at all, so a shared luminance is the only normalization under which a
        // held and a placed source of the same color and level shade alike.
        String patched = source;
        String dynamicHook = "";
        int heldLighting = patched.indexOf(HELD_LIGHTING_DEFINITION);
        if (heldLighting >= 0) {
            String heldHelper = heldColorDeclarations()
                    + "vec3 " + HELD_HELPER_NAME + "(vec3 heldColor) {\n"
                    + "\tfloat heldPeak = " + peakExpression("heldColor") + ";\n"
                    + "\tif (heldPeak < 0.001) return " + PACK_LIGHT_NAME + ";\n"
                    + "\tvec3 heldTint = " + tintExpression("heldColor") + ";\n"
                    + "\tfloat heldTintLuma = " + lumaExpression("heldTint") + ";\n"
                    + "\tfloat packLuma = " + lumaExpression(PACK_LIGHT_NAME) + ";\n"
                    + "\treturn heldTint * (packLuma / max(heldTintLuma, 0.0001));\n"
                    + "}\n";
            patched = patched.substring(0, heldLighting)
                    + heldHelper
                    + patched.substring(heldLighting);
            // Non-authoritative hands (items unknown to Colorful Lighting) keep the
            // pack's own hue and level, so its item.properties definitions still work.
            // PACK_LIGHT_NAME holds the pre-swap blocklightCol - the pack's original
            // held hue - because the terrain hue swap runs before DoLighting.
            patched = patched
                    .replace(
                            "vec3 heldLightCol = blocklightCol;",
                            "vec3 heldLightCol = " + HELD_AUTHORITY_NAME + " != 0 ? "
                                    + HELD_HELPER_NAME + "(" + HELD_COLOR_NAME + ") : "
                                    + PACK_LIGHT_NAME + ";"
                    )
                    .replace(
                            "vec3 heldLightCol2 = blocklightCol;",
                            "vec3 heldLightCol2 = " + HELD_AUTHORITY_NAME + "2 != 0 ? "
                                    + HELD_HELPER_NAME + "(" + HELD_COLOR_NAME + "2) : "
                                    + PACK_LIGHT_NAME + ";"
                    );
            patched = patchHeldAuthority(patched);
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
                + tintSwapBlock("\t", "colorful", VARYING_NAME, "blocklightCol", true)
                + dynamicHook
                + "\t";

        patched = patched.substring(0, lightingCall) + hook + patched.substring(lightingCall);
        return insertAfterVersion(patched, declarations);
    }

    /**
     * Complementary normally starts from Iris's vanilla held-block light value and may
     * override it again for pack-specific item IDs. Colorful Lighting is authoritative
     * for local hand sources, so replace that input and re-apply both hands immediately
     * before Complementary's nonlinear attenuation.
     */
    private static String patchHeldAuthority(String source) {
        Matcher declaration = HELD_LEVEL_DECLARATION.matcher(source);
        String patched = source;
        if (declaration.find()) {
            String indent = lineIndent(source, declaration.start());
            String replacement = "float heldLight = " + HELD_AUTHORITY_NAME
                    + " != 0 ? float(" + HELD_LEVEL_NAME + ") : heldBlockLightValue;\n"
                    + indent + "float heldLight2 = " + HELD_AUTHORITY_NAME
                    + "2 != 0 ? float(" + HELD_LEVEL_NAME + "2) : heldBlockLightValue2;";
            patched = source.substring(0, declaration.start())
                    + replacement
                    + source.substring(declaration.end());
        }

        Matcher attenuation = HELD_ATTENUATION.matcher(patched);
        if (!attenuation.find()) {
            return patched;
        }

        // Re-assert authoritative hands after the pack's own item-ID overrides (e.g.
        // its lava-bucket branch), right before the nonlinear attenuation. Hands the
        // mod has no opinion on keep whatever the pack computed.
        int insertion = lineStart(patched, attenuation.start());
        String indent = lineIndent(patched, attenuation.start());
        String authority = indent + "// colorfulLightingSodiumCompat_HeldAuthority\n"
                + indent + "if (" + HELD_AUTHORITY_NAME + " != 0) {\n"
                + indent + "\theldLight = float(" + HELD_LEVEL_NAME + ");\n"
                + indent + "\theldLightCol = " + HELD_HELPER_NAME
                        + "(" + HELD_COLOR_NAME + ");\n"
                + indent + "}\n"
                + indent + "if (" + HELD_AUTHORITY_NAME + "2 != 0) {\n"
                + indent + "\theldLight2 = float(" + HELD_LEVEL_NAME + "2);\n"
                + indent + "\theldLightCol2 = " + HELD_HELPER_NAME
                        + "(" + HELD_COLOR_NAME + "2);\n"
                + indent + "}\n";
        return patched.substring(0, insertion) + authority + patched.substring(insertion);
    }

    /**
     * The dynamic entity-light hook needs the fragment's camera-relative position and
     * the mutable lightmap coordinate local, both of which are in scope at the
     * DoLighting call site the hook precedes. These are this pack family's local
     * names - another family would anchor its own.
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
}
