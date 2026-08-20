package dev.colorfullighting.compat;

import dev.colorfullighting.compat.create.CreatePackedLightCompat;
import dev.colorfullighting.compat.iris.IrisShaderCompat;
import dev.colorfullighting.compat.level.RenderLevelScope;
import dev.colorfullighting.compat.level.RenderLevelView;
import dev.colorfullighting.compat.sodium.FluidVertexLight;
import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.common.ColoredLightStorage;
import me.erykczy.colorfullighting.common.DynamicLightMath;
import me.erykczy.colorfullighting.common.EntityLightTextCodec;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import me.erykczy.colorfullighting.common.util.TrilinearLightSampler;
import me.erykczy.colorfullighting.client.IdentityFamilyGrouping;
import me.erykczy.colorfullighting.client.LightEditorPaging;
import me.erykczy.colorfullighting.config.LightOverride;

import java.util.List;

public final class PackedLightCompatTest {
    private PackedLightCompatTest() {
    }

    private static java.util.Set<String> subtract(java.util.Set<String> from, java.util.Set<String> remove) {
        java.util.Set<String> result = new java.util.HashSet<>(from);
        result.removeAll(remove);
        return result;
    }

    public static void main(String[] args) {
        // Entity/item lights with different hues must combine by their effective
        // per-channel intensity. Taking the brightest level and the raw RGB maxima
        // would incorrectly promote a dim secondary hue to full range.
        EntityLight warmFull = EntityLight.of(15, 8, 0, 15);
        EntityLight dimBlue = EntityLight.of(0, 0, 15, 5);
        EntityLight combinedEntityLight = DynamicLightMath.max(warmFull, dimBlue);
        ColorRGB4 combinedIntensity = DynamicLightMath.effectiveColor(combinedEntityLight);
        assert combinedIntensity.red4 == 15;
        assert combinedIntensity.green4 == 8;
        assert combinedIntensity.blue4 == 5;
        assert combinedEntityLight.brightness4() == 15;

        // Moving lights use their exact world position and the same continuous linear
        // falloff as the shader path, rather than snapping their source to a BlockPos.
        ColorRGB4 warmAtFiveBlocks = DynamicLightMath.effectiveColorAt(warmFull, 5.0);
        assert warmAtFiveBlocks.red4 == 10;
        assert warmAtFiveBlocks.green4 == 5;
        assert warmAtFiveBlocks.blue4 == 0;
        ColorRGB4 warmAtRange = DynamicLightMath.effectiveColorAt(warmFull, 15.0);
        assert warmAtRange.red4 == 0;
        assert warmAtRange.green4 == 0;
        assert warmAtRange.blue4 == 0;

        // The editor and exported resource pack share this canonical text format.
        EntityLight editorLight = EntityLight.of(15, 8, 5, 10);
        String encodedEditorLight = EntityLightTextCodec.encode(editorLight);
        assert encodedEditorLight.equals("#ff8855;A");
        assert EntityLightTextCodec.decode(encodedEditorLight).equals(editorLight);
        assert EntityLightTextCodec.decode("#ff8855").brightness4() == 15;

        // The codec is the single hex parser every other surface delegates to.
        assert EntityLightTextCodec.tryParseRgb8("#ffd94a") == 0xffd94a;
        assert EntityLightTextCodec.tryParseRgb8("FFD94A") == 0xffd94a;
        assert EntityLightTextCodec.tryParseRgb8("#ffd94") == -1;   // five digits
        assert EntityLightTextCodec.tryParseRgb8("#ggd94a") == -1;  // non-hex digit
        assert EntityLightTextCodec.encodeRgb8(255, 217, 74).equals("#ffd94a");
        assert EntityLightTextCodec.tryParseLevelDigit("a") == 10;
        assert EntityLightTextCodec.tryParseLevelDigit("F") == 15;
        assert EntityLightTextCodec.tryParseLevelDigit("10") == null; // decimal rejected
        boolean decimalSuffixRejected = false;
        try {
            EntityLightTextCodec.decode("#ffd94a;10");
        }
        catch (IllegalArgumentException expected) {
            decimalSuffixRejected = expected.getMessage().contains("single hex digit");
        }
        assert decimalSuffixRejected;

        // V2 fields are independent: a missing color or brightness keeps the
        // underlying definition, while a brightness without any base creates white.
        LightOverride colorOnly = new LightOverride(
                ColorRGB4.fromRGB4(2, 4, 15), null, false
        );
        EntityLight recolored = colorOnly.apply(warmFull);
        assert recolored != null;
        assert recolored.equals(EntityLight.of(2, 4, 15, 15));
        LightOverride brightnessOnly = new LightOverride(null, 6, false);
        assert brightnessOnly.apply(warmFull).equals(EntityLight.of(15, 8, 0, 6));
        assert brightnessOnly.apply(null).equals(EntityLight.of(15, 15, 15, 6));
        assert colorOnly.apply(null) == null;
        assert LightOverride.disabledOverride().apply(warmFull) == null;

        // The page boundary is structural: even a partially filled configured page
        // never shares rows with unconfigured results.
        List<Integer> fifteen = java.util.stream.IntStream.range(0, 15).boxed().toList();
        List<Integer> eighteen = java.util.stream.IntStream.range(0, 18).boxed().toList();
        List<Integer> unconfigured = List.of(100, 101, 102);
        var pages15 = LightEditorPaging.partition(fifteen, unconfigured, 6);
        assert pages15.size() == 4;
        assert pages15.get(2).configured() && pages15.get(2).entries().size() == 3;
        assert !pages15.get(3).configured();
        var pages18 = LightEditorPaging.partition(eighteen, unconfigured, 6);
        assert pages18.size() == 4;
        assert pages18.get(2).configured() && pages18.get(2).entries().size() == 6;
        assert !pages18.get(3).configured();

        // Family grouping is a one-pass identity relation, so equal-looking keys do
        // not merge and there is no graph walk that could recurse or loop.
        Object torchItem = new Object();
        Object otherTorchItem = new Object();
        record FakeBlock(Object item, String name) {}
        List<FakeBlock> fakeBlocks = List.of(
                new FakeBlock(torchItem, "floor torch"),
                new FakeBlock(torchItem, "wall torch"),
                new FakeBlock(otherTorchItem, "mod torch")
        );
        var families = IdentityFamilyGrouping.group(fakeBlocks, FakeBlock::item, key -> true);
        assert families.size() == 2;
        assert families.get(torchItem).size() == 2;
        assert families.get(otherTorchItem).size() == 1;

        // Ponder, schematics, and other virtual worlds may share coordinates with the
        // live client world. They must retain their own fake/vanilla light instead of
        // sampling the live world's colored-light storage.
        Object activeLevel = new Object();
        Object foreignLevel = new Object();
        RenderLevelView activeSnapshot = () -> activeLevel;
        RenderLevelView foreignSnapshot = () -> foreignLevel;
        assert RenderLevelScope.belongsTo(activeLevel, activeLevel);
        assert RenderLevelScope.belongsTo(activeSnapshot, activeLevel);
        assert !RenderLevelScope.belongsTo(foreignLevel, activeLevel);
        assert !RenderLevelScope.belongsTo(foreignSnapshot, activeLevel);

        // Sable's moving sublevels resolve to the live root level, but their plot
        // coordinates can sit outside the colored engine's allocated sections. In
        // that case the renderer must keep the vanilla packed light instead of
        // encoding a valid colorful marker with black RGB channels.
        assert RenderLevelScope.canSample(activeSnapshot, activeLevel, true);
        assert !RenderLevelScope.canSample(activeSnapshot, activeLevel, false);
        assert !RenderLevelScope.canSample(foreignSnapshot, activeLevel, true);

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

        TrilinearLightSampler.PackedLookup redOrBlue = (x, y, z) -> x == 0 ? 0xF00 : 0x00F;
        ColorRGB8 redCenter = TrilinearLightSampler.sampleOrNull(
                0.5, 0.5, 0.5, Integer.MIN_VALUE, Integer.MAX_VALUE, redOrBlue
        );
        assert redCenter != null;
        assert redCenter.red == 255 && redCenter.green == 0 && redCenter.blue == 0;
        ColorRGB8 redBlueBoundary = TrilinearLightSampler.sampleOrNull(
                1.0, 0.5, 0.5, Integer.MIN_VALUE, Integer.MAX_VALUE, redOrBlue
        );
        assert redBlueBoundary != null;
        assert redBlueBoundary.red == 128;
        assert redBlueBoundary.green == 0;
        assert redBlueBoundary.blue == 128;

        // A MISSING corner aborts the whole sample (caller keeps vanilla light) - an
        // absent section must never be read as real black at data boundaries.
        assert TrilinearLightSampler.sampleOrNull(
                0.5, 0.5, 0.5, Integer.MIN_VALUE, Integer.MAX_VALUE,
                (x, y, z) -> x == 0 ? 0xF00 : TrilinearLightSampler.MISSING
        ) == null;
        // ...but corners beyond the build limits are legitimately dark, not missing:
        // the lookup is never consulted for them.
        ColorRGB8 ceiling = TrilinearLightSampler.sampleOrNull(
                0.5, 319.9, 0.5, -64, 319,
                (x, y, z) -> y > 319 ? TrilinearLightSampler.MISSING : 0xF00
        );
        assert ceiling != null;
        assert ceiling.red == 153 && ceiling.green == 0 && ceiling.blue == 0; // 15 * 0.6 * 17

        // Dense storage index: null/MISSING outside the allocated area, black inside
        // an untouched section, value round-trip, overlap-keeping rebuild, silent drop
        // of out-of-area writes. Propagation correctness rests on the null-vs-black
        // distinction: a null stops a wave (section gone), black just ends it.
        ColoredLightStorage lightStorage = new ColoredLightStorage();
        assert lightStorage.getEntry(8, 8, 8) == null;
        assert lightStorage.getPackedEntry(8, 8, 8) == ColoredLightStorage.MISSING;
        assert !lightStorage.containsEntry(8, 8, 8);
        lightStorage.setEntryUnsafe(8, 8, 8, ColorRGB4.fromRGB4(15, 0, 0)); // dropped
        assert lightStorage.getEntry(8, 8, 8) == null;

        lightStorage.rebuild(-1, 1, -4, 19, -1, 1); // blocks -16..31 x/z, -64..319 y
        assert lightStorage.containsEntry(8, 8, 8);
        assert lightStorage.getPackedEntry(8, 8, 8) == 0; // allocated but untouched
        assert lightStorage.getEntry(8, 8, 8).equals(ColorRGB4.fromRGB4(0, 0, 0));
        assert lightStorage.containsEntry(-16, -64, -16);
        assert lightStorage.containsEntry(31, 319, 31);
        assert !lightStorage.containsEntry(32, 8, 8);                              // outside x
        assert lightStorage.getPackedEntry(8, 320, 8) == ColoredLightStorage.MISSING; // above world
        lightStorage.setEntryUnsafe(-9, -33, 7, ColorRGB4.fromRGB4(12, 3, 9));     // negative coords
        assert lightStorage.getEntry(-9, -33, 7).equals(ColorRGB4.fromRGB4(12, 3, 9));
        assert lightStorage.getPackedEntry(-9, -33, 7) == (12 << 8 | 3 << 4 | 9);

        lightStorage.rebuild(0, 2, -4, 19, -1, 1); // shift east by one section column
        assert lightStorage.getEntry(-9, -33, 7) == null;  // dropped column
        assert lightStorage.getPackedEntry(8, 8, 8) == 0;  // kept section
        lightStorage.setEntryUnsafe(40, 8, 8, ColorRGB4.fromRGB4(1, 2, 3)); // fresh column
        lightStorage.rebuild(0, 2, -4, 19, -1, 1); // identical rebuild keeps everything
        assert lightStorage.getEntry(40, 8, 8).equals(ColorRGB4.fromRGB4(1, 2, 3));
        lightStorage.clear();
        assert lightStorage.getPackedEntry(8, 8, 8) == ColoredLightStorage.MISSING;

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

        // Create's contraption fallback renderer combines the vanilla light baked in
        // its virtual render world with colorful light sampled from the real world.
        // A mixed-layout max must preserve both sunlight and the colorful marker.
        int vanillaSun = 15 << 20;
        int mixedSunAndRed = PackedLightData.max(vanillaSun, colorfulRed);
        PackedLightData mixedSunAndRedData = PackedLightData.unpackData(mixedSunAndRed);
        assert PackedLightCompat.isColorful(mixedSunAndRed);
        assert mixedSunAndRedData.skyLight4 == 15;
        assert mixedSunAndRedData.red8 == 255;
        assert mixedSunAndRedData.green8 == 0;
        assert mixedSunAndRedData.blue8 == 0;
        assert PackedLightData.max(colorfulRed, vanillaSun) == mixedSunAndRed;

        // AO corner blends can mix formats at the colored-data boundary (view-area
        // edge, build limits). A vanilla corner must contribute neutral gray at its
        // block-light level, not be decoded with the colorful layout (which would read
        // its sky nibble as red and report zero sky light).
        int colorfulDim = PackedLightData.packData(2, 32, 16, 8);
        int vanillaCorner = 0x00F000E0; // sky 15, block 14
        int mixedBlend = PackedLightData.blend(colorfulDim, colorfulDim, colorfulDim, vanillaCorner);
        PackedLightData mixedBlendData = PackedLightData.unpackData(mixedBlend);
        assert PackedLightData.isColorful(mixedBlend);
        assert mixedBlendData.skyLight4 == (2 + 2 + 2 + 15) >> 2;
        assert mixedBlendData.red8 == (32 + 32 + 32 + 238) >> 2;
        assert mixedBlendData.green8 == (16 + 16 + 16 + 238) >> 2;
        assert mixedBlendData.blue8 == (8 + 8 + 8 + 238) >> 2;
        // all-vanilla inputs still take the exact vanilla fast path
        assert PackedLightData.blend(0x00200020, 0x00200020, 0x00200020, 0x00200020) == 0x00200020;

        int mixedWeighted = PackedLightData.blend(
                colorfulDim, colorfulDim, vanillaCorner, vanillaCorner,
                0.25f, 0.25f, 0.25f, 0.25f
        );
        PackedLightData mixedWeightedData = PackedLightData.unpackData(mixedWeighted);
        assert PackedLightData.isColorful(mixedWeighted);
        assert mixedWeightedData.skyLight4 == 8;   // 2*0.5 + 15*0.5 truncated
        assert mixedWeightedData.red8 == 135;      // 32*0.5 + 238*0.5
        assert mixedWeightedData.green8 == 127;
        assert mixedWeightedData.blue8 == 123;

        // packData/unpackData round-trip and the marker nibble, guarding the shared
        // layout constants that PackedLightCompat and the GLSL decoders mirror
        PackedLightData roundTrip = PackedLightData.unpackData(PackedLightData.packData(9, 250, 3, 77));
        assert roundTrip.skyLight4 == 9;
        assert roundTrip.red8 == 250;
        assert roundTrip.green8 == 3;
        assert roundTrip.blue8 == 77;
        assert roundTrip.alpha4 == PackedLightData.MARKER;

        // Create bakes its virtual contraption world with neutral vanilla block
        // light. That light may raise intensity, but it must not desaturate the hue
        // sampled from the real world's colored-light engine.
        int magenta = PackedLightData.packData(0, 255, 64, 255);
        int createCpuLight = CreatePackedLightCompat.combineTemplateAndWorld(
                15 << 4,
                magenta,
                false
        );
        PackedLightData createCpuData = PackedLightData.unpackData(createCpuLight);
        assert createCpuData.red8 == 255;
        assert createCpuData.green8 == 64;
        assert createCpuData.blue8 == 255;

        // A brighter neutral template may raise the effective intensity, but the
        // lower color channels must scale proportionally rather than becoming white.
        int dimMagenta = PackedLightData.packData(4, 85, 17, 85);
        int raisedMagenta = CreatePackedLightCompat.combineTemplateAndWorld(
                15 << 20 | 15 << 4,
                dimMagenta,
                false
        );
        PackedLightData raisedMagentaData = PackedLightData.unpackData(raisedMagenta);
        assert raisedMagentaData.skyLight4 == 15;
        assert raisedMagentaData.red8 == 255;
        assert raisedMagentaData.green8 == 51;
        assert raisedMagentaData.blue8 == 255;

        // The custom-light call may already have made the template colorful before
        // Create reaches its live-world lookup. Mixed layouts preserve hue in either
        // argument order.
        int raisedFromColorfulTemplate = CreatePackedLightCompat.combineTemplateAndWorld(
                dimMagenta,
                15 << 4,
                false
        );
        PackedLightData raisedFromColorfulTemplateData = PackedLightData.unpackData(
                raisedFromColorfulTemplate
        );
        assert raisedFromColorfulTemplateData.red8 == 255;
        assert raisedFromColorfulTemplateData.green8 == 51;
        assert raisedFromColorfulTemplateData.blue8 == 255;

        int colorfulSkyOnly = PackedLightData.packData(11, 0, 0, 0);
        int skyAndNeutralBlock = CreatePackedLightCompat.combineTemplateAndWorld(
                colorfulSkyOnly,
                6 << 4,
                false
        );
        PackedLightData skyAndNeutralBlockData = PackedLightData.unpackData(skyAndNeutralBlock);
        assert skyAndNeutralBlockData.skyLight4 == 11;
        assert skyAndNeutralBlockData.red8 == 102;
        assert skyAndNeutralBlockData.green8 == 102;
        assert skyAndNeutralBlockData.blue8 == 102;

        int vanillaTemplate = 5 << 20 | 6 << 4;
        int vanillaWorld = 12 << 20 | 3 << 4;
        assert CreatePackedLightCompat.combineTemplateAndWorld(
                vanillaTemplate,
                vanillaWorld,
                false
        ) == PackedLightData.max(vanillaTemplate, vanillaWorld);

        int cyan = PackedLightData.packData(9, 0, 128, 255);
        assert CreatePackedLightCompat.combineTemplateAndWorld(
                magenta,
                cyan,
                false
        ) == PackedLightData.max(magenta, cyan);

        // Supported Iris packs light moving sources per pixel. In that mode the
        // virtual world's self-light must be discarded so it cannot add a second,
        // neutral copy over the shader point light.
        int liveAmbient = 7 << 20 | 3 << 4;
        assert CreatePackedLightCompat.combineTemplateAndWorld(
                15 << 4,
                liveAmbient,
                true
        ) == liveAmbient;

        // Flywheel uploads two unsigned shorts (block, sky). Passing the RGB format
        // through unchanged would turn its color bytes and marker into near-full light.
        int flywheelLight = PackedLightCompat.toVanilla(colorful);
        assert (flywheelLight & 0xFFFF) == (12 << 4);
        assert ((flywheelLight >>> 16) & 0xFFFF) == (9 << 4);

        // Sable dims sky light for rotated/moving sublevels. Its vanilla bit mask
        // destroys Colorful Lighting's marker and blue channel, so compatibility code
        // must scale only the colorful sky nibble and preserve RGB verbatim.
        assert PackedLightCompat.scaleSkyLight(0x00F000A0, 8) == 0x008000A0;
        int scaledColorful = PackedLightCompat.scaleSkyLight(colorful, 8);
        PackedLightData scaledColorfulData = PackedLightData.unpackData(scaledColorful);
        assert PackedLightCompat.isColorful(scaledColorful);
        assert scaledColorfulData.skyLight4 == 4;
        assert scaledColorfulData.red8 == 0x40;
        assert scaledColorfulData.green8 == 0x80;
        assert scaledColorfulData.blue8 == 0xC0;

        String vertex = "#version 330 core\n"
                + "in vec4 mc_Entity;\n"
                + "void main() {\n}\n";
        String fragment = "#version 330 core\n"
                + "vec3 blocklightCol = vec3(1.0);\n"
                + "vec2 lmCoordM = vec2(0.0);\n"
                + "vec3 playerPos = vec3(0.0);\n"
                + "vec3 GetHeldLighting() {\n"
                + "\tfloat heldLight = heldBlockLightValue;\n"
                + "\tfloat heldLight2 = heldBlockLightValue2;\n"
                + "\tvec3 heldLightCol = blocklightCol;\n"
                + "\tvec3 heldLightCol2 = blocklightCol;\n"
                + "\tif (heldItemId == 45032) heldLight = 15.0;\n"
                + "\theldLight = pow2(heldLight);\n"
                + "\theldLight2 = pow2(heldLight2);\n"
                + "\treturn heldLight * heldLightCol + heldLight2 * heldLightCol2;\n"
                + "}\n"
                + "void main() {\n\tDoLighting(color, other);\n}\n";
        assert IrisShaderCompat.supports(vertex, fragment);

        String patchedVertex = IrisShaderCompat.patchVertex(vertex, true);
        assert patchedVertex.contains("layout(location = 15) in uvec4");
        assert patchedVertex.contains("colorfulMarker == 15u");
        assert IrisShaderCompat.patchVertex(patchedVertex, true).equals(patchedVertex);

        String patchedFragment = IrisShaderCompat.patchFragment(fragment);
        assert patchedFragment.contains("blocklightCol = colorfulTint");
        assert patchedFragment.indexOf("blocklightCol = colorfulTint")
                < patchedFragment.indexOf("DoLighting(color,");
        assert patchedFragment.contains(
                "vec3 heldLightCol = colorfulLightingSodiumCompat_HeldAuthority != 0 ? "
                        + "colorfulLightingSodiumCompat_HeldLight("
                        + "colorfulLightingSodiumCompat_HeldColor) : "
                        + "colorfulLightingSodiumCompat_PackBlocklight;");
        assert patchedFragment.contains(
                "vec3 heldLightCol2 = colorfulLightingSodiumCompat_HeldAuthority2 != 0 ? "
                        + "colorfulLightingSodiumCompat_HeldLight("
                        + "colorfulLightingSodiumCompat_HeldColor2) : "
                        + "colorfulLightingSodiumCompat_PackBlocklight;");
        assert patchedFragment.contains(
                "float heldLight = colorfulLightingSodiumCompat_HeldAuthority != 0"
                        + " ? float(colorfulLightingSodiumCompat_HeldLevel) : heldBlockLightValue;"
        );
        assert patchedFragment.contains(
                "float heldLight2 = colorfulLightingSodiumCompat_HeldAuthority2 != 0"
                        + " ? float(colorfulLightingSodiumCompat_HeldLevel2) : heldBlockLightValue2;"
        );
        assert patchedFragment.contains("uniform int colorfulLightingSodiumCompat_HeldAuthority;");
        assert patchedFragment.contains("if (colorfulLightingSodiumCompat_HeldAuthority != 0) {");
        assert !patchedFragment.contains("heldLight = heldBlockLightValue;");
        assert !patchedFragment.contains("heldLight2 = heldBlockLightValue2;");
        assert !patchedFragment.contains("vec3 heldLightCol = blocklightCol;");
        assert patchedFragment.contains("uniform vec3 colorfulLightingSodiumCompat_HeldColor;");
        assert patchedFragment.contains("uniform int colorfulLightingSodiumCompat_HeldLevel;");
        assert patchedFragment.contains("uniform int colorfulLightingSodiumCompat_HeldLevel2;");
        int packHeldOverride = patchedFragment.indexOf("heldItemId == 45032");
        int colorfulHeldOverride = patchedFragment.indexOf(
                "// colorfulLightingSodiumCompat_HeldAuthority"
        );
        int heldAttenuation = patchedFragment.indexOf("heldLight = pow2(heldLight);");
        assert packHeldOverride >= 0;
        assert colorfulHeldOverride > packHeldOverride;
        assert colorfulHeldOverride < heldAttenuation;
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
        String patchedMakeUp = IrisShaderCompat.patchVertex(makeUpVertex, false);
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
        assert IrisShaderCompat.patchVertex(patchedMakeUp, false).equals(patchedMakeUp);
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
        String patchedPreprocessedMakeUp = IrisShaderCompat.patchVertex(preprocessedMakeUp, false);
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
        String patchedNormalizedMakeUp = IrisShaderCompat.patchVertex(irisNormalizedMakeUp, false);
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
        String patchedElite = IrisShaderCompat.patchVertex(eliteVertex, false);
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

        // A MakeUp-style vertex paired with a Complementary-style fragment: the two
        // stages are patched independently, so the vertex must still declare and write
        // the out varying the fragment's `in` consumes, or the program fails to link.
        String hybridMakeUp = IrisShaderCompat.patchVertex(makeUpVertex, true);
        assert hybridMakeUp.contains("out vec3 colorfulLightingSodiumCompat_Color;");
        assert hybridMakeUp.contains(
                "colorfulLightingSodiumCompat_Color = colorfulLightingSodiumCompat_VertexColor;");
        assert hybridMakeUp.contains("colorfulLightingSodiumCompat_MakeUpTint");
        // and the plain MakeUp patch still omits the varying entirely
        assert !patchedMakeUp.contains("colorfulLightingSodiumCompat_Color");

        // BSL family: fragment-stage lighting through GetLighting(albedo...), colorless
        // hand light whose LEVEL becomes authoritative per hand, and dynamic entity
        // lights anchored on the worldPos/lightmap locals.
        String bslVertex = "#version 330 core\n"
                + "attribute vec4 mc_Entity;\n"
                + "void main() {\n}\n";
        String bslFragment = "#version 330 core\n"
                + "uniform int heldBlockLightValue, heldBlockLightValue2;\n"
                + "vec3 blocklightCol = vec3(0.2, 0.1, 0.05);\n"
                + "vec2 ApplyDynamicHandlight(vec2 lightmap, vec3 worldPos) {\n"
                + "    float heldLightValue = max(float(heldBlockLightValue), float(heldBlockLightValue2));\n"
                + "    lightmap.x = max(lightmap.x, heldLightValue / 15.0);\n"
                + "    return lightmap;\n"
                + "}\n"
                + "void GetLighting(inout vec3 albedo, vec2 lightmap) {\n"
                + "    albedo *= blocklightCol * lightmap.x;\n"
                + "}\n"
                + "void main() {\n"
                + "\tvec2 lightmap = clamp(lmCoord, vec2(0.0), vec2(1.0));\n"
                + "\tvec3 worldPos = ToWorld(viewPos);\n"
                + "\tlightmap = ApplyDynamicHandlight(lightmap, worldPos);\n"
                + "\tGetLighting(albedo.rgb, lightmap);\n"
                + "}\n";
        assert IrisShaderCompat.supports(bslVertex, bslFragment);
        assert IrisShaderCompat.supportsVanillaTint(bslFragment);
        String patchedBsl = IrisShaderCompat.patchFragment(bslFragment);
        // per-hand level authority replaces the pack uniform only for authoritative hands
        assert patchedBsl.contains(
                "float heldLightValue = max(colorfulLightingSodiumCompat_HeldAuthority != 0"
                        + " ? float(colorfulLightingSodiumCompat_HeldLevel) : float(heldBlockLightValue),"
                        + " colorfulLightingSodiumCompat_HeldAuthority2 != 0"
                        + " ? float(colorfulLightingSodiumCompat_HeldLevel2) : float(heldBlockLightValue2));");
        // hue swap and dynamic hook precede the lighting call (first GetLighting(albedo
        // occurrence is the call; the definition reads "GetLighting(inout")
        assert patchedBsl.contains("colorfulLightingSodiumCompat_PackBlocklight = blocklightCol;");
        assert patchedBsl.indexOf("blocklightCol = colorfulTint")
                < patchedBsl.indexOf("GetLighting(albedo");
        assert patchedBsl.contains("length(worldPos - dynLight.xyz)");
        assert patchedBsl.contains("lightmap.x = max(lightmap.x, colorfulDynLm);");
        assert patchedBsl.contains("uniform int colorfulLightingSodiumCompat_HeldAuthority;");
        // the colorless hand boost gets its own hue: the pre-boost lightmap is
        // captured at the handlight call, and the hand's share of the final lightmap
        // is re-hued (emitter color when authoritative, pack warm hue otherwise) so a
        // placed light's fringe color is never amplified into a saturated wash
        assert patchedBsl.contains(
                "float colorfulLightingSodiumCompat_PlacedLightmapX = lightmap.x;"
                        + " lightmap = ApplyDynamicHandlight(lightmap, worldPos);");
        assert patchedBsl.contains("float colorfulHandShare = clamp((lightmap.x"
                + " - colorfulLightingSodiumCompat_PlacedLightmapX)");
        assert patchedBsl.indexOf("colorfulHandShare") > patchedBsl.indexOf("blocklightCol = colorfulTint");
        assert patchedBsl.indexOf("colorfulHandShare") < patchedBsl.indexOf("colorfulDynLightCount > 0".replace("colorfulDyn", "colorfulLightingSodiumCompat_Dyn"));
        assert IrisShaderCompat.patchFragment(patchedBsl).equals(patchedBsl);
        // family routing stays disjoint: BSL has no DoLighting, Complementary has no
        // GetLighting(albedo - and the Complementary shell still patches with its own
        // playerPos anchor (asserted above), not BSL's worldPos
        assert !patchedFragment.contains("worldPos");

        // CompatMixinRouting and the mixin config must list exactly the same classes:
        // a mixin present in the json but missing from the routing table would only
        // surface at runtime through the warn-and-default fallback.
        try (java.io.InputStream mixinsStream = java.util.Objects.requireNonNull(
                PackedLightCompatTest.class.getResourceAsStream(
                        "/colorful_lighting_compat.mixins.json"),
                "mixin config not on classpath")) {
            String json = new String(mixinsStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int clientStart = json.indexOf('[', json.indexOf("\"client\""));
            int clientEnd = json.indexOf(']', clientStart);
            assert clientStart >= 0 && clientEnd > clientStart : "client mixin list not found";
            java.util.Set<String> listed = new java.util.HashSet<>();
            java.util.regex.Matcher name = java.util.regex.Pattern.compile("\"([A-Za-z0-9_]+)\"")
                    .matcher(json.substring(clientStart, clientEnd));
            while (name.find()) listed.add(name.group(1));
            assert listed.equals(CompatMixinRouting.MIXIN_MODS.keySet())
                    : "mixins.json and CompatMixinRouting diverged; json-only="
                            + subtract(listed, CompatMixinRouting.MIXIN_MODS.keySet())
                            + " table-only="
                            + subtract(CompatMixinRouting.MIXIN_MODS.keySet(), listed);
        }
        catch (java.io.IOException exception) {
            throw new RuntimeException(exception);
        }

        // #extension directives must stay ahead of injected declarations; the insert
        // point skips the preprocessor prologue after #version.
        String extensionVertex = "#version 330 core\n"
                + "#extension GL_ARB_shader_texture_lod : enable\n"
                + "in vec4 mc_Entity;\n"
                + "void main() {\n}\n";
        String patchedExtensionVertex = IrisShaderCompat.patchVertex(extensionVertex, true);
        assert patchedExtensionVertex.indexOf("#extension GL_ARB_shader_texture_lod")
                < patchedExtensionVertex.indexOf("layout(location = 15) in uvec4");
        String extensionVanilla = "#version 330 core\n"
                + "#extension GL_ARB_shader_texture_lod : enable\n"
                + "in ivec2 iris_UV2;\n"
                + "void main() {\n"
                + "\tvec2 lm = vec2(iris_UV2);\n"
                + "}\n";
        String sanitizedExtension = IrisShaderCompat.sanitizeVanillaVertex(extensionVanilla, true);
        assert sanitizedExtension.contains("out vec3 colorfulLightingSodiumCompat_Color;");
        assert sanitizedExtension.indexOf("#extension GL_ARB_shader_texture_lod") >= 0;
    }
}
