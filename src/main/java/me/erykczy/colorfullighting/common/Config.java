package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.JsonHelper;
import me.erykczy.colorfullighting.config.LightOverride;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Config {
    public static final ColorRGB4 defaultColor = ColorRGB4.fromRGB4(15, 15, 15);
    // volatile: reassigned on resource-reload worker threads while Sodium meshing
    // workers and the light-propagation thread read them concurrently; the maps are
    // fully built before assignment, so the volatile write is the publication fence
    private static volatile HashMap<ResourceLocation, ColorEmitter> colorEmitters = new HashMap<>();
    private static volatile HashMap<ResourceLocation, ColorFilter> colorFilters = new HashMap<>();
    // per-block entries keyed on blockstate properties ("modid:block[lit=true,color=red]"),
    // each list sorted most-specific-first at load time
    private static volatile HashMap<ResourceLocation, List<StateColorEmitter>> stateColorEmitters = new HashMap<>();
    private static volatile HashMap<ResourceLocation, List<StateColorFilter>> stateColorFilters = new HashMap<>();
    private static volatile Map<ResourceLocation, LightOverride> userBlockOverrides = Map.of();

    public static void setColorEmitters(HashMap<ResourceLocation, ColorEmitter> colors) {
        colorEmitters = colors;
    }

    public static void setColorFilters(HashMap<ResourceLocation, ColorFilter> filters) {
        colorFilters = filters;
    }

    public static void setStateColorEmitters(HashMap<ResourceLocation, List<StateColorEmitter>> colors) {
        stateColorEmitters = colors;
    }

    public static void setStateColorFilters(HashMap<ResourceLocation, List<StateColorFilter>> filters) {
        stateColorFilters = filters;
    }

    public static void setUserBlockOverrides(Map<ResourceLocation, LightOverride> overrides) {
        userBlockOverrides = Map.copyOf(overrides);
    }

    public static Set<ResourceLocation> defaultEmitterIdsForEditor() {
        HashMap<ResourceLocation, Boolean> ids = new HashMap<>();
        colorEmitters.keySet().forEach(id -> ids.put(id, Boolean.TRUE));
        stateColorEmitters.keySet().forEach(id -> ids.put(id, Boolean.TRUE));
        return Set.copyOf(ids.keySet());
    }

    @Nullable
    public static ColorEmitter defaultEmitterForEditor(ResourceLocation id) {
        ColorEmitter emitter = colorEmitters.get(id);
        if(emitter != null) return emitter;
        List<StateColorEmitter> stateEntries = stateColorEmitters.get(id);
        return stateEntries == null || stateEntries.isEmpty()
                ? null
                : stateEntries.getFirst().emitter();
    }

    @Nullable
    public static ColorEmitter defaultPlainEmitterForEditor(ResourceLocation id) {
        return colorEmitters.get(id);
    }

    public static List<StateColorEmitter> defaultStateEmittersForEditor(ResourceLocation id) {
        return List.copyOf(stateColorEmitters.getOrDefault(id, List.of()));
    }

    @Nullable
    public static ColorEmitter defaultEmitterForEditor(BlockStateAccessor blockState) {
        return findResourceEmitter(blockState.getBlockKey(), blockState);
    }

    private static boolean matchesProperties(@NotNull BlockStateAccessor blockState, Map<String, String> properties) {
        for(var entry : properties.entrySet()) {
            if(!entry.getValue().equals(blockState.getPropertyValue(entry.getKey())))
                return false;
        }
        return true;
    }

    @Nullable
    private static ColorEmitter findResourceEmitter(
            @Nullable ResourceKey<Block> blockResourceKey,
            @Nullable BlockStateAccessor blockState
    ) {
        if(blockResourceKey == null) return null;
        if(blockState != null) {
            List<StateColorEmitter> stateEntries = stateColorEmitters.get(blockResourceKey.location());
            if(stateEntries != null) {
                for(StateColorEmitter entry : stateEntries) {
                    if(matchesProperties(blockState, entry.properties()))
                        return entry.emitter();
                }
            }
        }
        return colorEmitters.get(blockResourceKey.location());
    }

    @Nullable
    private static ColorEmitter findEmitter(
            @Nullable ResourceKey<Block> blockResourceKey,
            @Nullable BlockStateAccessor blockState
    ) {
        ColorEmitter base = findResourceEmitter(blockResourceKey, blockState);
        if(blockResourceKey == null) return base;
        LightOverride override = userBlockOverrides.get(blockResourceKey.location());
        if(override == null) return base;
        if(override.disabled()) return new ColorEmitter(defaultColor, 0);
        ColorRGB4 color = override.color() != null
                ? override.color()
                : base == null ? defaultColor : base.color();
        int brightness = override.brightness() != null
                ? override.brightness()
                : base == null ? -1 : base.overriddenBrightness4();
        return new ColorEmitter(color, brightness);
    }

    @Nullable
    private static ColorFilter findFilter(@Nullable ResourceKey<Block> blockResourceKey, @Nullable BlockStateAccessor blockState) {
        if(blockResourceKey == null) return null;
        if(blockState != null) {
            List<StateColorFilter> stateEntries = stateColorFilters.get(blockResourceKey.location());
            if(stateEntries != null) {
                for(StateColorFilter entry : stateEntries) {
                    if(matchesProperties(blockState, entry.properties()))
                        return entry.filter();
                }
            }
        }
        return colorFilters.get(blockResourceKey.location());
    }

    public static ColorRGB4 getColorEmission(@NotNull LevelAccessor level, BlockPos pos) { return getColorEmission(level, pos, level.getBlockState(pos)); }
    public static ColorRGB4 getColorEmission(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        float lightEmission = blockState.getLightEmission(level, pos)/15.0f;

        ColorEmitter config = findEmitter(blockState.getBlockKey(), blockState);
        ColorRGB4 blockColor = config != null
                ? config.color().mul(config.overriddenBrightness4 < 0 ? lightEmission : config.overriddenBrightness4 /15.0f)
                : defaultColor.mul(lightEmission);

        return blockColor;
    }
    public static ColorRGB4 getLightColor(@NotNull BlockStateAccessor blockState) {
        ColorEmitter config = findEmitter(blockState.getBlockKey(), blockState);
        if(config != null)
            return config.color();
        return defaultColor;
    }
    public static ColorRGB4 getLightColor(@Nullable ResourceKey<Block> blockLocation) {
        ColorEmitter config = findEmitter(blockLocation, null);
        if(config != null)
            return config.color();
        return defaultColor;
    }

    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, ColorRGB4 defaultValue) {
        var blockState = level.getBlockState(pos);
        return blockState == null ? defaultValue : getColoredLightTransmittance(level, pos, blockState);
    }
    public static ColorRGB4 getColoredLightTransmittance(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        ColorFilter config = findFilter(blockState.getBlockKey(), blockState);
        if(config == null) return ColorRGB4.fromRGB4(15, 15, 15);
        return config.transmittance;
    }

    public static int getEmissionBrightness(@NotNull LevelAccessor level, BlockPos pos, int defaultValue) {
        var blockState = level.getBlockState(pos);
        return blockState == null ? defaultValue : getEmissionBrightness(level, pos, blockState);
    }
    public static int getEmissionBrightness(@NotNull LevelAccessor level, BlockPos pos, @NotNull BlockStateAccessor blockState) {
        ColorEmitter config = findEmitter(blockState.getBlockKey(), blockState);
        int blockBrightness = (config != null && config.overriddenBrightness4 >= 0)
                ? config.overriddenBrightness4
                : blockState.getLightEmission(level, pos);

        return blockBrightness;
    }
    public static int getEmissionBrightness(BlockStateAccessor blockState) {
        ColorEmitter config = findEmitter(blockState.getBlockKey(), blockState);
        if(config != null && config.overriddenBrightness4 >= 0)
            return config.overriddenBrightness4;
        return blockState.getLightEmission();
    }

    /**
     * @param color light color
     * @param overriddenBrightness4 4 bit value in range 0..15, by which light color is multiplied, if -1, vanilla emission for given block is used
     */
    public record ColorEmitter(ColorRGB4 color, int overriddenBrightness4) {
        public static ColorEmitter fromJsonElement(JsonElement value) throws IllegalArgumentException {
            ColorRGB4 color = getColorFromJsonElement(value);
            Integer brightness = getBrightnessFromJsonElement(value);
            if(color == null) throw new IllegalArgumentException(
                    "Invalid color \"" + describeValue(value) + "\" - expected \"#rrggbb\","
                            + " a dye name like \"light_blue\", or [r, g, b] with 0-255 components");
            if(brightness == null) throw new IllegalArgumentException(
                    "Invalid brightness in \"" + describeValue(value) + "\" - the level suffix"
                            + " is a single hex digit 0-F (\"a\"=10, \"f\"=15), not decimal;"
                            + " an array's 4th element is 0-255 (255 = level 15) or 0.0-1.0");
            return new ColorEmitter(color, brightness);
        }

        private static ColorRGB4 getColorFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 3) return null;
                return JsonHelper.getColor4FromJsonElements(array.get(0), array.get(1), array.get(2));
            }
            return JsonHelper.getColor4FromString(value.getAsString().split(";")[0]);
        }

        private static Integer getBrightnessFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 4) return -1;
                return JsonHelper.getInt4FromJsonElement(array.get(3));
            }
            String[] args = value.getAsString().split(";");
            if(args.length < 2) return -1;
            if(args.length > 2) return null;
            // canonical level parsing shared with EntityLightTextCodec
            return EntityLightTextCodec.tryParseLevelDigit(args[1]);
        }
    }

    private static String describeValue(JsonElement value) {
        String text = value.toString();
        return text.length() > 48 ? text.substring(0, 48) + "..." : text;
    }
    /** A color entry that applies only to states matching every listed property value. */
    public record StateColorEmitter(Map<String, String> properties, ColorEmitter emitter) {}

    /** A filter entry that applies only to states matching every listed property value. */
    public record StateColorFilter(Map<String, String> properties, ColorFilter filter) {}

    public record ColorFilter(ColorRGB4 transmittance) {
        public static ColorFilter fromJsonElement(JsonElement value) throws IllegalArgumentException {
            ColorRGB4 color = getColorFromJsonElement(value);
            if(color == null) throw new IllegalArgumentException(
                    "Invalid filter color \"" + describeValue(value) + "\" - expected \"#rrggbb\","
                            + " a dye name like \"light_blue\", or [r, g, b] with 0-255 components");
            return new ColorFilter(color);
        }

        private static ColorRGB4 getColorFromJsonElement(JsonElement value) {
            if(value.isJsonArray()) {
                var array = value.getAsJsonArray();
                if(array.size() < 3) return null;
                return JsonHelper.getColor4FromJsonElements(array.get(0), array.get(1), array.get(2));
            }
            return JsonHelper.getColor4FromString(value.getAsString());
        }
    }
}
