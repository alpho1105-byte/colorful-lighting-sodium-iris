package me.erykczy.colorfullighting.common;

import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.api.EntityLightProvider;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side dynamic light sources attached to entities (the approach popularized by
 * dynamic-lights mods, on top of the colored engine). Every client tick the manager
 * computes each visible entity's light, combines entities sharing a block position
 * (per-channel max), and diffs the result against the previous tick: every position
 * whose combined light changed is fed through the SAME update path a block change uses,
 * so the engine removes the old contribution and propagates the new one. The active
 * emitter map is consulted by Config's emission lookups when the engine seeds light, and
 * is read from the propagation thread, hence the concurrent map.
 */
public final class EntityLightManager {
    private static final ConcurrentHashMap<EntityType<?>, EntityLightProvider> providers = new ConcurrentHashMap<>();
    private static volatile Map<EntityType<?>, EntityLight> jsonLights = Map.of();
    /** position -> combined effective color; read by the propagation thread via Config. */
    private static final ConcurrentHashMap<Long, ColorRGB4> activeEmitters = new ConcurrentHashMap<>();

    private static final int BURNING_LIGHT_LEVEL = 10;

    private EntityLightManager() {
    }

    public static void registerProvider(EntityType<?> type, EntityLightProvider provider) {
        providers.put(type, provider);
    }

    public static void setJsonLights(Map<EntityType<?>, EntityLight> lights) {
        jsonLights = lights;
    }

    /** The combined entity light emitted at this position, or null if none. */
    @Nullable
    public static ColorRGB4 getEmitterAt(int x, int y, int z) {
        if(activeEmitters.isEmpty()) return null; // common fast path
        return activeEmitters.get(BlockPos.asLong(x, y, z));
    }

    public static void tick(ColoredLightEngine engine) {
        ClientLevel level = Minecraft.getInstance().level;
        if(level == null) {
            if(!activeEmitters.isEmpty()) activeEmitters.clear();
            return;
        }

        HashMap<Long, ColorRGB4> computed = new HashMap<>();
        for(Entity entity : level.entitiesForRendering()) {
            ColorRGB4 effective = resolveEffectiveLight(entity);
            if(effective == null) continue;
            long pos = BlockPos.containing(entity.getEyePosition()).asLong();
            computed.merge(pos, effective, EntityLightManager::maxChannels);
        }

        if(computed.isEmpty() && activeEmitters.isEmpty()) return;

        Set<Long> changed = new HashSet<>();
        for(var entry : activeEmitters.entrySet()) {
            ColorRGB4 now = computed.get(entry.getKey());
            if(now == null || !sameColor(now, entry.getValue()))
                changed.add(entry.getKey());
        }
        for(var entry : computed.entrySet()) {
            ColorRGB4 before = activeEmitters.get(entry.getKey());
            if(before == null || !sameColor(before, entry.getValue()))
                changed.add(entry.getKey());
        }
        if(changed.isEmpty()) return;

        // publish the new emitter set BEFORE enqueueing, so the seeding the engine does
        // for these positions reads the fresh values
        activeEmitters.keySet().retainAll(computed.keySet());
        activeEmitters.putAll(computed);

        for(Long pos : changed)
            engine.onBlockLightPropertiesChanged(BlockPos.of(pos));
    }

    public static void reset() {
        activeEmitters.clear();
    }

    @Nullable
    private static ColorRGB4 resolveEffectiveLight(Entity entity) {
        EntityLight light = null;
        EntityLightProvider provider = providers.get(entity.getType());
        if(provider != null)
            light = provider.getLight(entity);
        else {
            EntityLight jsonLight = jsonLights.get(entity.getType());
            if(jsonLight != null) light = jsonLight;
        }

        if(entity.isOnFire() && !entity.isSpectator()) {
            ColorRGB4 fireColor = Config.getLightColor(Blocks.FIRE.builtInRegistryHolder().getKey());
            EntityLight fireLight = EntityLight.of(fireColor.red4, fireColor.green4, fireColor.blue4, BURNING_LIGHT_LEVEL);
            light = light == null ? fireLight : maxLight(light, fireLight);
        }

        if(light == null || light.isDark()) return null;
        float scale = light.brightness4() / 15.0f;
        return ColorRGB4.fromRGB4(
                Math.round(light.red4() * scale),
                Math.round(light.green4() * scale),
                Math.round(light.blue4() * scale)
        );
    }

    private static EntityLight maxLight(EntityLight a, EntityLight b) {
        return EntityLight.of(
                Math.max(a.red4(), b.red4()),
                Math.max(a.green4(), b.green4()),
                Math.max(a.blue4(), b.blue4()),
                Math.max(a.brightness4(), b.brightness4())
        );
    }

    private static ColorRGB4 maxChannels(ColorRGB4 a, ColorRGB4 b) {
        return ColorRGB4.fromRGB4(
                Math.max(a.red4, b.red4),
                Math.max(a.green4, b.green4),
                Math.max(a.blue4, b.blue4)
        );
    }

    private static boolean sameColor(ColorRGB4 a, ColorRGB4 b) {
        return a.red4 == b.red4 && a.green4 == b.green4 && a.blue4 == b.blue4;
    }
}
