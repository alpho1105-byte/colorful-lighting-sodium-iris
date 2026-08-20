package dev.colorfullighting.compat.iris.family;

import dev.colorfullighting.compat.iris.IrisPatchState;

import static dev.colorfullighting.compat.iris.family.PatchToolkit.heldColorDeclarations;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.insertAfterVersion;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.lineIndent;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.lineStart;
import static dev.colorfullighting.compat.iris.family.PatchToolkit.tintSwapBlock;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_AUTHORITY_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.HELD_LEVEL_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.PACK_LIGHT_NAME;
import static dev.colorfullighting.compat.iris.family.ShaderPatchNames.VARYING_NAME;

/**
 * BSL (Complementary's ancestor, so it shares the {@code blocklightCol} idiom but
 * lights through {@code GetLighting(albedo...)} instead of {@code DoLighting}): block
 * light is computed in the fragment stage as {@code blocklightCol * lightmap.x-curve}
 * multiplied straight into albedo with no clamp, so the hue swap preserves LUMINANCE.
 *
 * <p>Held light: BSL's DYNAMIC_HANDLIGHT is colorless by design - it only raises the
 * block-light lightmap coordinate ({@code ApplyDynamicHandlight}), and the boosted
 * region takes whatever hue {@code blocklightCol} has. The patch makes the LEVEL
 * authoritative per hand (honoring the HeldAuthority contract: unknown items keep the
 * pack's own {@code heldBlockLightValue}); the held hue keeps following the pack's
 * block-light color, matching BSL's native look.
 *
 * <p>Dynamic entity lights anchor on the {@code worldPos} (camera-relative) and
 * mutable {@code lightmap} locals in scope at the GetLighting call site.
 *
 * <p>BSL's own MULTICOLORED_BLOCKLIGHT (off by default) assigns {@code blocklightCol}
 * just before the lighting call; this hook inserts after that assignment, so where
 * Colorful Lighting has color data its hue wins and elsewhere (black varying skips
 * the swap) MCBL's voxel color is preserved.
 */
final class BslFamily implements ShaderFamily {
    /** The call shape shared by every BSL gbuffers program ("GetLighting(albedo..."). */
    private static final String LIGHTING_CALL = "GetLighting(albedo";
    /** Both ApplyDynamicHandlight variants fold the hands with this exact line. */
    private static final String HANDLIGHT_LEVEL_LINE =
            "float heldLightValue = max(float(heldBlockLightValue), float(heldBlockLightValue2));";
    /** The handlight application in world programs and in the hand program. */
    private static final String HANDLIGHT_CALL =
            "lightmap = ApplyDynamicHandlight(lightmap, worldPos);";
    private static final String HANDLIGHT_HAND_CALL =
            "lightmap = ApplyDynamicHandlightHand(lightmap);";
    private static final String PLACED_LIGHTMAP_NAME = "colorfulLightingSodiumCompat_PlacedLightmapX";

    @Override
    public String id() {
        return "bsl";
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

        // the helper references PACK_LIGHT_NAME, so it must follow that declaration
        // within the same insertion block
        String declarations = "in vec3 " + VARYING_NAME + ";\n"
                + "vec3 " + PACK_LIGHT_NAME + " = vec3(1.0);\n"
                + heldColorDeclarations()
                + PatchToolkit.packLightTintHelper();

        // Held-light LEVEL authority: authoritative hands use Colorful Lighting's
        // level (including "deliberately dark" zero), unknown items keep the pack's
        // own uniform. Applies to both ApplyDynamicHandlight variants when the pack
        // was compiled with DYNAMIC_HANDLIGHT > 0; a no-op otherwise.
        String patched = source.replace(
                HANDLIGHT_LEVEL_LINE,
                "float heldLightValue = max("
                        + HELD_AUTHORITY_NAME + " != 0 ? float(" + HELD_LEVEL_NAME
                        + ") : float(heldBlockLightValue), "
                        + HELD_AUTHORITY_NAME + "2 != 0 ? float(" + HELD_LEVEL_NAME
                        + "2) : float(heldBlockLightValue2));"
        );

        // BSL's hand light is a colorless lightmap boost, so it inherits whatever hue
        // blocklightCol carries. After the placed-hue swap that hue is SPATIALLY
        // VARYING: where the hand light dominates, a faint colored value at a placed
        // light's range edge would be amplified into a fully saturated wash (a red
        // player next to a red lamp). Capture the pre-boost lightmap so the hook can
        // give the hand's share its own hue: the authoritative emitter color, or the
        // pack's original warm hue for items the mod has no opinion on.
        String beforeCapture = patched;
        patched = patched
                .replace(HANDLIGHT_CALL,
                        "float " + PLACED_LIGHTMAP_NAME + " = lightmap.x; " + HANDLIGHT_CALL)
                .replace(HANDLIGHT_HAND_CALL,
                        "float " + PLACED_LIGHTMAP_NAME + " = lightmap.x; " + HANDLIGHT_HAND_CALL);
        boolean handShareAvailable = !patched.equals(beforeCapture);

        String dynamicHook = "";
        if (dynamicLightingSupported(patched)) {
            declarations += PatchToolkit.dynamicLightDeclarations();
            dynamicHook = PatchToolkit.dynamicLightHook("worldPos", "lightmap.x");
            IrisPatchState.recordDynamicLights();
        }

        int lightingCall = patched.indexOf(LIGHTING_CALL);
        if (lightingCall < 0) {
            return source;
        }
        int insertion = lineStart(patched, lightingCall);
        String indent = lineIndent(patched, lightingCall);
        // hand-share hue: HeldColor uniforms are non-zero only for an authoritative,
        // lit hand, and the helper falls back to the pack's original hue for black -
        // so one expression covers authoritative (emitter hue), disabled (warm), and
        // unknown-item (warm) hands alike
        String handShareHook = !handShareAvailable ? "" :
                indent + "float colorfulHandShare = clamp((lightmap.x - " + PLACED_LIGHTMAP_NAME
                        + ") / max(lightmap.x, 0.0001), 0.0, 1.0);\n"
                + indent + "if (colorfulHandShare > 0.001) {\n"
                + indent + "\tblocklightCol = mix(blocklightCol, " + ShaderPatchNames.HELD_HELPER_NAME
                        + "(max(" + ShaderPatchNames.HELD_COLOR_NAME + ", "
                        + ShaderPatchNames.HELD_COLOR_NAME + "2)), colorfulHandShare);\n"
                + indent + "}\n";
        String hook = indent + PACK_LIGHT_NAME + " = blocklightCol;\n"
                + tintSwapBlock(indent, "colorful", VARYING_NAME, "blocklightCol", true)
                + handShareHook
                + dynamicHook;

        patched = patched.substring(0, insertion) + hook + patched.substring(insertion);
        return insertAfterVersion(patched, declarations);
    }

    /**
     * The dynamic entity-light hook needs a camera-relative fragment position and the
     * mutable lightmap local, both in scope at the GetLighting call site the hook
     * precedes. These are BSL's local names.
     */
    private static boolean dynamicLightingSupported(String source) {
        // assignment form: a bare "vec3 worldPos" would also match function
        // parameters (gbuffers_water declares helpers taking worldPos long before
        // main's own local exists)
        int call = source.indexOf(LIGHTING_CALL);
        int worldPos = source.indexOf("vec3 worldPos = ");
        int lightmap = source.indexOf("vec2 lightmap = ");
        return worldPos >= 0 && worldPos < call && lightmap >= 0 && lightmap < call;
    }
}
