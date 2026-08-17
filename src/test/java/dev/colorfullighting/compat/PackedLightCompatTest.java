package dev.colorfullighting.compat;

import dev.colorfullighting.compat.iris.IrisShaderCompat;
import dev.colorfullighting.compat.sodium.FluidVertexLight;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import me.erykczy.colorfullighting.common.util.TrilinearLightSampler;

public final class PackedLightCompatTest {
    private PackedLightCompatTest() {
    }

    public static void main(String[] args) {
        // Fluid meshes are positioned with a section-local model offset, but colored
        // light must always be sampled in world space. Keep the world coordinate and
        // Sodium's already-interpolated per-vertex sky light as separate invariants.
        assert FluidVertexLight.sampleCoordinate(160, 0.25f, 1) == 160.75;
        assert FluidVertexLight.sampleCoordinate(-33, 0.75f, -1) == -32.75;
        int fluidLight = FluidVertexLight.packWithVanillaSky(
                0x00F000A0,
                ColorRGB8.fromRGB8(16, 32, 48)
        );
        PackedLightData fluidData = PackedLightData.unpackData(fluidLight);
        assert fluidData.skyLight4 == 15;
        assert fluidData.red8 == 16;
        assert fluidData.green8 == 32;
        assert fluidData.blue8 == 48;

        ColorRGB8 redCenter = TrilinearLightSampler.sample(
                0.5,
                0.5,
                0.5,
                (x, y, z) -> x == 0
                        ? ColorRGB4.fromRGB4(15, 0, 0)
                        : ColorRGB4.fromRGB4(0, 0, 15)
        );
        assert redCenter.red == 255 && redCenter.green == 0 && redCenter.blue == 0;
        ColorRGB8 redBlueBoundary = TrilinearLightSampler.sample(
                1.0,
                0.5,
                0.5,
                (x, y, z) -> x == 0
                        ? ColorRGB4.fromRGB4(15, 0, 0)
                        : ColorRGB4.fromRGB4(0, 0, 15)
        );
        assert redBlueBoundary.red == 128;
        assert redBlueBoundary.green == 0;
        assert redBlueBoundary.blue == 128;

        int vanillaFullBright = 0x00F000F0;
        assert !PackedLightCompat.isColorful(vanillaFullBright);
        assert PackedLightCompat.blockLight(vanillaFullBright) == 15;
        assert PackedLightCompat.skyLight(vanillaFullBright) == 15;

        int colorful = 0xFC098040;
        assert PackedLightCompat.isColorful(colorful);
        assert PackedLightCompat.blockLight(colorful) == 12;
        assert PackedLightCompat.skyLight(colorful) == 9;

        int colorfulRed = 0xF00000FF;
        assert PackedLightCompat.blockLight(colorfulRed) == 15;
        assert PackedLightCompat.skyLight(colorfulRed) == 0;

        assert PackedLightCompat.toVanilla(vanillaFullBright) == vanillaFullBright;
        assert PackedLightCompat.toVanilla(0) == 0;
        assert PackedLightCompat.toVanilla(colorful) == ((12 << 4) | (9 << 20));
        assert PackedLightCompat.toVanilla(colorfulRed) == (15 << 4);
        int colorfulDark = 0xF0050000;
        assert PackedLightCompat.toVanilla(colorfulDark) == (5 << 20);

        String vertex = "#version 330 core\n"
                + "in vec4 mc_Entity;\n"
                + "void main() {\n}\n";
        String fragment = "#version 330 core\n"
                + "vec3 blocklightCol = vec3(1.0);\n"
                + "vec2 lmCoordM = vec2(0.0);\n"
                + "vec3 playerPos = vec3(0.0);\n"
                + "vec3 GetHeldLighting() {\n"
                + "\tvec3 heldLightCol = blocklightCol;\n"
                + "\tvec3 heldLightCol2 = blocklightCol;\n"
                + "\treturn heldLightCol + heldLightCol2;\n"
                + "}\n"
                + "void main() {\n\tDoLighting(color, other);\n}\n";
        assert IrisShaderCompat.supports(vertex, fragment);

        String patchedVertex = IrisShaderCompat.patchVertex(vertex);
        assert patchedVertex.contains("layout(location = 15) in uvec4");
        assert patchedVertex.contains("colorfulMarker == 15u");
        assert IrisShaderCompat.patchVertex(patchedVertex).equals(patchedVertex);

        String patchedFragment = IrisShaderCompat.patchFragment(fragment);
        assert patchedFragment.contains("blocklightCol = colorfulTint");
        assert patchedFragment.indexOf("blocklightCol = colorfulTint")
                < patchedFragment.indexOf("DoLighting(color,");
        assert patchedFragment.contains(
                "vec3 heldLightCol = colorfulLightingSodiumCompat_HeldLight("
                        + "colorfulLightingSodiumCompat_HeldColor);");
        assert patchedFragment.contains(
                "vec3 heldLightCol2 = colorfulLightingSodiumCompat_HeldLight("
                        + "colorfulLightingSodiumCompat_HeldColor2);");
        assert !patchedFragment.contains("vec3 heldLightCol = blocklightCol;");
        assert patchedFragment.contains("uniform vec3 colorfulLightingSodiumCompat_HeldColor;");
        assert patchedFragment.indexOf("vec3 colorfulLightingSodiumCompat_HeldLight(")
                < patchedFragment.indexOf("vec3 GetHeldLighting(");
        assert patchedFragment.contains(
                "colorfulLightingSodiumCompat_PackBlocklight = blocklightCol;");
        // Entity-light hues must be blended continuously. Choosing only the strongest
        // light creates a hard Voronoi boundary wherever two differently colored
        // lights have equal distance-adjusted levels.
        assert patchedFragment.contains("colorfulDynColorSum += dynColor * dynLevelI;");
        assert patchedFragment.contains("colorfulDynColorWeight += dynLevelI;");
        assert patchedFragment.contains("colorfulDynColorSum / colorfulDynColorWeight");
        assert !patchedFragment.contains("if (dynLevelI > colorfulDynLevel)");

        String fragmentWithoutHeld = "#version 330 core\n"
                + "vec3 blocklightCol = vec3(1.0);\n"
                + "void main() {\n\tDoLighting(color, other);\n}\n";
        String patchedWithoutHeld = IrisShaderCompat.patchFragment(fragmentWithoutHeld);
        assert !patchedWithoutHeld.contains("colorfulLightingSodiumCompat_HeldLight");
        assert patchedWithoutHeld.contains("blocklightCol = colorfulTint");
        assert IrisShaderCompat.patchFragment(patchedFragment).equals(patchedFragment);

        String makeUpVertex = "#version 330 core\n"
                + "in vec4 mc_Entity;\n"
                + "out vec3 candleColor;\n"
                + "void main() {\n"
                + "\tgl_FogFragCoord = 1.0;\n"
                + "\tcandleColor = vec3(1.0);\n"
                + "#ifdef DYN_HAND_LIGHT\n"
                + "\tvec3 handLight = vec3(2.0);\n"
                + "\tcandleColor = max(candleColor, handLight);\n"
                + "#endif\n"
                + "\tcandleColor = clamp(candleColor, vec3(0.0), vec3(4.0));\n"
                + "}\n";
        String makeUpFragment = "#version 330 core\n"
                + "in vec3 candleColor;\n"
                + "void main() {}\n";
        assert IrisShaderCompat.supports(makeUpVertex, makeUpFragment);
        assert IrisShaderCompat.supportsVanillaVertexTint(makeUpVertex);
        String patchedMakeUp = IrisShaderCompat.patchVertex(makeUpVertex);
        assert patchedMakeUp.contains("layout(location = 15) in uvec4");
        assert patchedMakeUp.contains("vec3 colorfulLightingSodiumCompat_VertexColor;");
        assert patchedMakeUp.contains("uniform vec3 colorfulLightingSodiumCompat_HeldColor;");
        assert patchedMakeUp.contains("colorfulLightingSodiumCompat_MakeUpTint");
        assert patchedMakeUp.contains("candleColor = colorfulTint *");
        assert patchedMakeUp.indexOf("colorfulLightingSodiumCompat_MakeUpTint")
                < patchedMakeUp.indexOf("#ifdef DYN_HAND_LIGHT");
        assert patchedMakeUp.indexOf("colorfulLightingSodiumCompat_MakeUpTint")
                < patchedMakeUp.indexOf("candleColor = max(candleColor");
        assert patchedMakeUp.contains("colorfulLightingSodiumCompat_HandTint");
        assert patchedMakeUp.indexOf("colorfulLightingSodiumCompat_HandTint")
                < patchedMakeUp.indexOf("candleColor = max(candleColor");
        assert IrisShaderCompat.patchVertex(patchedMakeUp).equals(patchedMakeUp);
        assert IrisShaderCompat.patchFragment(makeUpFragment).equals(makeUpFragment);

        String preprocessedMakeUp = "#version 330 core\n"
                + "in vec4 mc_Entity;\n"
                + "uniform int heldItemId;\n"
                + "out vec3 candleColor;\n"
                + "void main() {\n"
                + "\tcandleColor = vec3(1.0);\n"
                + "\tif (heldItemId == 11001 || heldItemId == 11002) {\n"
                + "\t\tfloat handDistance = 1.0 - gl_FogFragCoord / 15.0;\n"
                + "\t\tvec3 handLight = vec3(handDistance * handDistance);\n"
                + "\t\tcandleColor = max(candleColor, handLight);\n"
                + "\t}\n"
                + "\tcandleColor = clamp(candleColor, vec3(0.0), vec3(4.0));\n"
                + "}\n";
        String patchedPreprocessedMakeUp = IrisShaderCompat.patchVertex(preprocessedMakeUp);
        assert patchedPreprocessedMakeUp.indexOf("colorfulLightingSodiumCompat_MakeUpTint")
                < patchedPreprocessedMakeUp.indexOf("if (heldItemId");
        assert patchedPreprocessedMakeUp.indexOf("colorfulLightingSodiumCompat_HandTint")
                > patchedPreprocessedMakeUp.indexOf("if (heldItemId");
        assert patchedPreprocessedMakeUp.indexOf("colorfulLightingSodiumCompat_HandTint")
                < patchedPreprocessedMakeUp.indexOf(
                        "candleColor = max(candleColor, colorfulLightingSodiumCompat_HandLight)"
                );
        assert patchedPreprocessedMakeUp.contains(
                "uniform int colorfulLightingSodiumCompat_HeldLevel;"
        );
        assert patchedPreprocessedMakeUp.contains(
                "colorfulLightingSodiumCompat_FallbackHandTint"
        );
        assert patchedPreprocessedMakeUp.indexOf(
                "colorfulLightingSodiumCompat_FallbackHandTint"
        ) < patchedPreprocessedMakeUp.indexOf("if (heldItemId");
        assert patchedPreprocessedMakeUp.contains(
                "vec3(colorfulLightingSodiumCompat_FallbackHandDistance"
                        + " * colorfulLightingSodiumCompat_FallbackHandDistance)"
        );

        String irisNormalizedMakeUp = makeUpVertex
                .replace("gl_FogFragCoord", "iris_FogFragCoord")
                .replace("vec3(0.0)", "vec3(0.0f)")
                .replace("vec3(4.0)", "vec3(4.0f)");
        assert IrisShaderCompat.supportsVanillaVertexTint(irisNormalizedMakeUp);
        String patchedNormalizedMakeUp = IrisShaderCompat.patchVertex(irisNormalizedMakeUp);
        assert patchedNormalizedMakeUp.contains("candleColor = colorfulTint *");
        assert patchedNormalizedMakeUp.contains("colorfulLightingSodiumCompat_HandTint");

        String eliteVertex = makeUpVertex
                .replace("candleColor", "candle_color")
                .replace("vec3 handLight = vec3(2.0);\n"
                                + "\tcandle_color = max(candle_color, handLight);",
                        "candle_color = max(candle_color, vec3(2.0));")
                .replace(
                        "clamp(candle_color, vec3(0.0), vec3(4.0))",
                        "clamp(candle_color, 0.0, 4.0)"
                );
        assert IrisShaderCompat.supports(eliteVertex, makeUpFragment);
        String patchedElite = IrisShaderCompat.patchVertex(eliteVertex);
        assert patchedElite.contains("candle_color = colorfulTint *");
        assert patchedElite.contains("colorfulLightingSodiumCompat_MakeUpTint");
        assert patchedElite.indexOf("colorfulLightingSodiumCompat_MakeUpTint")
                < patchedElite.indexOf("#ifdef DYN_HAND_LIGHT");
        assert patchedElite.indexOf("colorfulLightingSodiumCompat_MakeUpTint")
                < patchedElite.indexOf("candle_color = max(candle_color");
        assert patchedElite.contains("colorfulLightingSodiumCompat_HandTint");

        assert !IrisShaderCompat.supports(
                vertex,
                "#version 330 core\nvoid main() { DoLighting(color, other); }"
        );

        String vanillaVertex = "#version 330 core\n"
                + "in ivec2 iris_UV2;\n"
                + "vec2 lightCoord() {\n"
                + "\treturn vec2(iris_UV2) / 256.0;\n"
                + "}\n"
                + "void main() {\n"
                + "\tvec2 lm = lightCoord();\n"
                + "}\n";
        assert IrisShaderCompat.usesVanillaLightCoords(vanillaVertex);

        String sanitized = IrisShaderCompat.sanitizeVanillaVertex(vanillaVertex, true);
        assert sanitized.contains("in ivec2 iris_UV2;");
        assert sanitized.contains("ivec2 colorfulLightingSodiumCompat_UV2;");
        assert sanitized.contains("out vec3 colorfulLightingSodiumCompat_Color;");
        assert sanitized.contains("vec2(colorfulLightingSodiumCompat_UV2) / 256.0");
        assert !sanitized.contains("vec2(iris_UV2) / 256.0");
        assert sanitized.contains("colorfulLightingSodiumCompat_UV2 = iris_UV2;");
        assert !IrisShaderCompat.usesVanillaLightCoords(sanitized);

        String sanitizedPlain = IrisShaderCompat.sanitizeVanillaVertex(vanillaVertex, false);
        assert !sanitizedPlain.contains("colorfulLightingSodiumCompat_Color");
        assert sanitizedPlain.contains("colorfulLightingSodiumCompat_UV2 = iris_UV2;");

        String makeUpVanillaVertex = vanillaVertex.replace(
                "\tvec2 lm = lightCoord();",
                "\tvec2 lm = lightCoord();\n"
                        + "\tgl_FogFragCoord = 1.0;\n"
                        + "\tvec3 candleColor = vec3(lm.x);\n"
                        + "\tcandleColor = clamp(candleColor, vec3(0.0), vec3(4.0));"
        );
        String sanitizedMakeUp = IrisShaderCompat.sanitizeVanillaVertex(
                makeUpVanillaVertex,
                false,
                true
        );
        assert sanitizedMakeUp.contains("vec3 colorfulLightingSodiumCompat_VertexColor;");
        assert sanitizedMakeUp.contains("colorfulLightingSodiumCompat_MakeUpTint");
        assert sanitizedMakeUp.contains("candleColor = colorfulTint *");
        assert !sanitizedMakeUp.contains("out vec3 colorfulLightingSodiumCompat_Color;");

        assert IrisShaderCompat.supportsVanillaTint(fragment);
        assert !IrisShaderCompat.supportsVanillaTint("#version 330 core\nvoid main() {}\n");
    }
}
