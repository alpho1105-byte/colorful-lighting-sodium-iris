package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.JsonHelper;
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

public class Config {
    public static final ColorRGB4 defaultColor = ColorRGB4.fromRGB4(15, 15, 15);
    private static HashMap<ResourceLocation, ColorEmitter> colorEmitters = new HashMap<>();
    private static HashMap<ResourceLocation, ColorFilter> colorFilters = new HashMap<>();
    // per-block entries keyed on blockstate properties ("modid:block[lit=true,color=red]"),
    // each list sorted most-specific-first at load time
    private static HashMap<ResourceLocation, List<StateColorEmitter>> stateColorEmitters = new HashMap<>();
    private static HashMap<ResourceLocation, List<StateColorFilter>> stateColorFilters = new HashMap<>();

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

    private static boolean matchesProperties(@NotNull BlockStateAccessor blockState, Map<String, String> properties) {
        for(var entry : properties.entrySet()) {
            if(!entry.getValue().equals(blockState.getPropertyValue(entry.getKey())))
                return false;
        }
        return true;
    }

    @Nullable
    private static ColorEmitter findEmitter(@Nullable ResourceKey<Block> blockResourceKey, @Nullable BlockStateAccessor blockState) {
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

        // entity light occupying this position combines with whatever the block emits
        ColorRGB4 entityColor = EntityLightManager.getEmitterAt(pos.getX(), pos.getY(), pos.getZ());
        if(entityColor == null) return blockColor;
        return ColorRGB4.fromRGB4(
                Math.max(blockColor.red4, entityColor.red4),
                Math.max(blockColor.green4, entityColor.green4),
                Math.max(blockColor.blue4, entityColor.blue4)
        );
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

        ColorRGB4 entityColor = EntityLightManager.getEmitterAt(pos.getX(), pos.getY(), pos.getZ());
        if(entityColor == null) return blockBrightness;
        int entityBrightness = Math.max(entityColor.red4, Math.max(entityColor.green4, entityColor.blue4));
        return Math.max(blockBrightness, entityBrightness);
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
            if(color == null) throw new IllegalArgumentException("Invalid color.");
            if(brightness == null) throw new IllegalArgumentException("Invalid brightness.");
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
            try {
                int brightness = Integer.parseInt(args[1], 16);
                if(brightness >= 0 && brightness <= 15) return brightness;
                return null;
            }
            catch (NumberFormatException ignore) {
                return null;
            }
        }
    }
    /** A color entry that applies only to states matching every listed property value. */
    public record StateColorEmitter(Map<String, String> properties, ColorEmitter emitter) {}

    /** A filter entry that applies only to states matching every listed property value. */
    public record StateColorFilter(Map<String, String> properties, ColorFilter filter) {}

    public record ColorFilter(ColorRGB4 transmittance) {
        public static ColorFilter fromJsonElement(JsonElement value) throws IllegalArgumentException {
            ColorRGB4 color = getColorFromJsonElement(value);
            if(color == null) throw new IllegalArgumentException("Invalid color.");
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
