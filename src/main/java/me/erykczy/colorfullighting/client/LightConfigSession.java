package me.erykczy.colorfullighting.client;

import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.client.LightFamilyIndex.Target;
import me.erykczy.colorfullighting.client.LightFamilyIndex.TargetKind;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.EntityLightManager;
import me.erykczy.colorfullighting.common.ItemLightDefinition;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.config.LightOverride;
import me.erykczy.colorfullighting.config.LightUserConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared unsaved working copy used by every screen in one editor session. */
public final class LightConfigSession {
    private final HashMap<ResourceLocation, LightOverride> blocks;
    private final HashMap<ResourceLocation, LightOverride> items;
    private final HashMap<ResourceLocation, LightOverride> entities;
    private final Set<ResourceLocation> defaultBlocks;
    private final Map<ResourceLocation, ItemLightDefinition> defaultItems;
    private final Map<ResourceLocation, EntityLight> defaultEntities;
    private final Set<ResourceLocation> providerEntities;
    private final HashMap<ResourceLocation, BrightnessState> blockBrightnessCache = new HashMap<>();
    private final HashMap<ResourceLocation, Boolean> blockMayEmitCache = new HashMap<>();
    @Nullable
    private LightOverride burning;

    public LightConfigSession(LightUserConfig.Snapshot snapshot) {
        blocks = new HashMap<>(snapshot.blocks());
        items = new HashMap<>(snapshot.items());
        entities = new HashMap<>(snapshot.entities());
        burning = snapshot.burningEntity();
        defaultBlocks = Config.defaultEmitterIdsForEditor();
        defaultItems = EntityLightManager.defaultItemDefinitionsForEditor();
        defaultEntities = EntityLightManager.defaultLightsForEditor();
        providerEntities = EntityLightManager.providerIdsForEditor();
    }

    public LightUserConfig.Snapshot snapshot() {
        return new LightUserConfig.Snapshot(blocks, items, entities, burning);
    }

    public LightOverride get(Target target) {
        LightOverride value = switch(target.kind()) {
            case BLOCK -> blocks.get(target.id());
            case ITEM -> items.get(target.id());
            case ENTITY -> entities.get(target.id());
            case BURNING -> burning;
        };
        return value == null ? LightOverride.empty() : value;
    }

    public void set(Target target, LightOverride value) {
        if(target.kind() == TargetKind.BURNING) {
            burning = value.isEmpty() ? null : value;
            return;
        }
        Map<ResourceLocation, LightOverride> section = section(target.kind());
        if(value.isEmpty()) section.remove(target.id());
        else section.put(target.id(), value);
    }

    public void disable(List<Target> targets) {
        targets.forEach(target -> set(target, LightOverride.disabledOverride()));
    }

    public void reset(List<Target> targets) {
        targets.forEach(target -> set(target, LightOverride.empty()));
    }

    public Aggregate aggregate(List<Target> targets) {
        if(targets.isEmpty()) return new Aggregate(null, false, null, false, false, false);
        LightOverride first = get(targets.getFirst());
        ColorRGB4 color = first.color();
        Integer brightness = first.brightness();
        boolean disabled = first.disabled();
        boolean colorMixed = false;
        boolean brightnessMixed = false;
        boolean disabledMixed = false;
        for(int index = 1; index < targets.size(); index++) {
            LightOverride next = get(targets.get(index));
            colorMixed |= !Objects.equals(color, next.color());
            brightnessMixed |= !Objects.equals(brightness, next.brightness());
            disabledMixed |= disabled != next.disabled();
        }
        return new Aggregate(color, colorMixed, brightness, brightnessMixed, disabled, disabledMixed);
    }

    public boolean isConfigured(Target target) {
        if(!get(target).isEmpty()) return true;
        return switch(target.kind()) {
            case BLOCK -> defaultBlocks.contains(target.id());
            case ITEM -> defaultItems.containsKey(target.id());
            case ENTITY -> defaultEntities.containsKey(target.id())
                    || providerEntities.contains(target.id());
            case BURNING -> true;
        };
    }

    public boolean isConfigured(List<Target> targets) {
        return targets.stream().anyMatch(this::isConfigured);
    }

    public boolean hasEffectiveLight(Target target) {
        Effective resolved = effective(target);
        if(resolved.disabled() || resolved.color() == null) return false;
        if(resolved.color().red4 == 0
                && resolved.color().green4 == 0
                && resolved.color().blue4 == 0) return false;
        if(resolved.brightness() != null) return resolved.brightness() > 0;
        return switch(target.kind()) {
            case BLOCK -> blockMayEmit(target.id());
            case ENTITY -> providerEntities.contains(target.id());
            default -> false;
        };
    }

    public boolean hasEffectiveLight(List<Target> targets) {
        return targets.stream().anyMatch(this::hasEffectiveLight);
    }

    public BrightnessState inheritedBrightness(List<Target> targets) {
        if(targets.isEmpty()) return new BrightnessState(null, false);
        Integer first = base(targets.getFirst()).brightness();
        for(int index = 1; index < targets.size(); index++) {
            if(!Objects.equals(first, base(targets.get(index)).brightness()))
                return new BrightnessState(first, true);
        }
        return new BrightnessState(first, false);
    }

    public Effective effective(Target target) {
        LightOverride override = get(target);
        Base base = base(target);
        if(override.disabled()) return new Effective(null, 0, true, "user");
        ColorRGB4 color = override.color() != null ? override.color() : base.color();
        Integer brightness = override.brightness() != null ? override.brightness() : base.brightness();
        return new Effective(color, brightness, false, override.isEmpty() ? base.source() : "user");
    }

    public ColorState aggregateEffectiveColor(List<Target> targets) {
        ColorRGB4 color = null;
        boolean found = false;
        for(Target target : targets) {
            Effective effective = effective(target);
            if(effective.disabled() || effective.color() == null) continue;
            if(!found) {
                color = effective.color();
                found = true;
            }
            else if(!Objects.equals(color, effective.color())) {
                return new ColorState(color, true);
            }
        }
        return new ColorState(color, false);
    }

    private Base base(Target target) {
        return switch(target.kind()) {
            case BLOCK -> blockBase(target.id());
            case ITEM -> itemBase(target.id());
            case ENTITY -> entityBase(target.id());
            case BURNING -> {
                EntityLight light = EntityLightManager.defaultBurningLightForEditor();
                yield new Base(
                        ColorRGB4.fromRGB4(light.red4(), light.green4(), light.blue4()),
                        light.brightness4(),
                        "default"
                );
            }
        };
    }

    private Base blockBase(ResourceLocation id) {
        Config.ColorEmitter emitter = Config.defaultEmitterForEditor(id);
        ColorRGB4 color = emitter == null ? Config.defaultColor : emitter.color();
        Integer brightness = blockOriginalBrightness(id).value();
        String source = defaultBlocks.contains(id) ? "resource" : "vanilla";
        return new Base(color, brightness, source);
    }

    private Base itemBase(ResourceLocation id) {
        ItemLightDefinition definition = defaultItems.get(id);
        if(definition != null && definition.light() != null) {
            EntityLight light = definition.light();
            return new Base(
                    ColorRGB4.fromRGB4(light.red4(), light.green4(), light.blue4()),
                    light.brightness4(),
                    "resource"
            );
        }
        if(BuiltInRegistries.ITEM.containsKey(id)) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if(item instanceof BlockItem blockItem) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
                BlockStateWrapper state = new BlockStateWrapper(blockItem.getBlock().defaultBlockState());
                Config.ColorEmitter emitter = Config.defaultEmitterForEditor(state);
                ColorRGB4 baseColor = emitter == null ? Config.defaultColor : emitter.color();
                int baseBrightness = emitter != null && emitter.overriddenBrightness4() >= 0
                        ? emitter.overriddenBrightness4()
                        : state.getLightEmission();
                Base blockBase = new Base(
                        baseColor,
                        baseBrightness,
                        "block"
                );
                LightOverride blockOverride = blocks.get(blockId);
                if(blockOverride == null) return blockBase;
                if(blockOverride.disabled()) return new Base(null, 0, "block");
                return new Base(
                        blockOverride.color() == null ? blockBase.color() : blockOverride.color(),
                        blockOverride.brightness() == null
                                ? blockBase.brightness()
                                : blockOverride.brightness(),
                        "block"
                );
            }
        }
        return new Base(null, 0, "none");
    }

    private Base entityBase(ResourceLocation id) {
        if(!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return new Base(null, 0, "none");
        EntityLight light = defaultEntities.get(id);
        return light == null
                ? providerEntities.contains(id)
                        ? new Base(Config.defaultColor, null, "provider")
                        : new Base(null, 0, "none")
                : new Base(
                        ColorRGB4.fromRGB4(light.red4(), light.green4(), light.blue4()),
                        light.brightness4(),
                        "resource"
                );
    }

    private BrightnessState blockOriginalBrightness(ResourceLocation id) {
        return blockBrightnessCache.computeIfAbsent(id, key -> {
            if(!BuiltInRegistries.BLOCK.containsKey(key))
                return new BrightnessState(0, false);
            Block block = BuiltInRegistries.BLOCK.get(key);
            Integer first = null;
            boolean found = false;
            for(var state : block.getStateDefinition().getPossibleStates()) {
                BlockStateWrapper wrapped = new BlockStateWrapper(state);
                Config.ColorEmitter emitter = Config.defaultEmitterForEditor(wrapped);
                int brightness = emitter != null && emitter.overriddenBrightness4() >= 0
                        ? emitter.overriddenBrightness4()
                        : wrapped.getLightEmission();
                if(!found) {
                    first = brightness;
                    found = true;
                }
                else if(first != brightness) {
                    return new BrightnessState(null, true);
                }
            }
            return new BrightnessState(first, false);
        });
    }

    private boolean blockMayEmit(ResourceLocation id) {
        return blockMayEmitCache.computeIfAbsent(id, key -> {
            if(!BuiltInRegistries.BLOCK.containsKey(key)) return false;
            Block block = BuiltInRegistries.BLOCK.get(key);
            for(var state : block.getStateDefinition().getPossibleStates()) {
                BlockStateWrapper wrapped = new BlockStateWrapper(state);
                Config.ColorEmitter emitter = Config.defaultEmitterForEditor(wrapped);
                int brightness = emitter != null && emitter.overriddenBrightness4() >= 0
                        ? emitter.overriddenBrightness4()
                        : wrapped.getLightEmission();
                if(brightness > 0) return true;
            }
            return false;
        });
    }

    private Map<ResourceLocation, LightOverride> section(TargetKind kind) {
        return switch(kind) {
            case BLOCK -> blocks;
            case ITEM -> items;
            case ENTITY -> entities;
            case BURNING -> throw new IllegalArgumentException("Burning is not a map section");
        };
    }

    private record Base(@Nullable ColorRGB4 color, @Nullable Integer brightness, String source) {
    }

    public record Aggregate(
            @Nullable ColorRGB4 color,
            boolean colorMixed,
            @Nullable Integer brightness,
            boolean brightnessMixed,
            boolean disabled,
            boolean disabledMixed
    ) {
    }

    public record Effective(
            @Nullable ColorRGB4 color,
            @Nullable Integer brightness,
            boolean disabled,
            String source
    ) {
    }

    public record ColorState(@Nullable ColorRGB4 color, boolean mixed) {
    }

    public record BrightnessState(@Nullable Integer value, boolean mixed) {
    }
}
