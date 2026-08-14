package dev.colorfullighting.compat;

import dev.colorfullighting.compat.iris.IrisShaderCompat;

public final class PackedLightCompatTest {
    private PackedLightCompatTest() {
    }

    public static void main(String[] args) {
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

        String fragmentWithoutHeld = "#version 330 core\n"
                + "vec3 blocklightCol = vec3(1.0);\n"
                + "void main() {\n\tDoLighting(color, other);\n}\n";
        String patchedWithoutHeld = IrisShaderCompat.patchFragment(fragmentWithoutHeld);
        assert !patchedWithoutHeld.contains("colorfulLightingSodiumCompat_HeldLight");
        assert patchedWithoutHeld.contains("blocklightCol = colorfulTint");
        assert IrisShaderCompat.patchFragment(patchedFragment).equals(patchedFragment);

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

        assert IrisShaderCompat.supportsVanillaTint(fragment);
        assert !IrisShaderCompat.supportsVanillaTint("#version 330 core\nvoid main() {}\n");
    }
}
