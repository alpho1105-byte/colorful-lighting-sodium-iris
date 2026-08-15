package me.erykczy.colorfullighting.common;

import dev.colorfullighting.compat.ColorfulLightGate;
import me.erykczy.colorfullighting.api.EntityLight;
import me.erykczy.colorfullighting.api.EntityLightProvider;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side dynamic light sources attached to entities (the approach popularized by
 * dynamic-lights mods, on top of the colored engine). Every client tick the manager
 * computes each visible entity's light and routes it down one of two paths:
 *
 * <p><b>Block engine</b> (vanilla pipeline): entities sharing a block position are
 * combined (per-channel max) and every position whose combined light changed since the
 * previous tick is fed through the SAME update path a block change uses. Quantized to
 * blocks, like other dynamic-light mods.
 *
 * <p><b>Shader point lights</b> (supported Iris pack): the tracked lights are published
 * to per-frame uniforms and rendered per-pixel by the GetHeldLighting hook — the same
 * smooth camera-space falloff the pack uses for held-item light, with no block grid.
 * While this path is active the block engine receives no entity light (the per-position
 * diff drains naturally when the mode switches).
 */
public final class EntityLightManager {
    /** Keep in sync with IrisShaderCompat.MAX_DYNAMIC_LIGHTS. */
    private static final int MAX_SHADER_LIGHTS = 8;

    private static final ConcurrentHashMap<EntityType<?>, EntityLightProvider> providers = new ConcurrentHashMap<>();
    private static volatile Map<EntityType<?>, EntityLight> jsonLights = Map.of();
    /** position -> combined effective color; read by the propagation thread via Config. */
    private static final ConcurrentHashMap<Long, ColorRGB4> activeEmitters = new ConcurrentHashMap<>();

    private record ShaderLight(Entity entity, EntityLight light) {}
    private static volatile List<ShaderLight> shaderLights = List.of();

    // per-frame uniform data, rebuilt at most once per frame window (render thread)
    private static long lastFrameNanos;
    private static int frameCount;
    private static final float[] framePositions = new float[MAX_SHADER_LIGHTS * 4];
    private static final float[] frameColors = new float[MAX_SHADER_LIGHTS * 4];

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
            shaderLights = List.of();
            if(!activeEmitters.isEmpty()) activeEmitters.clear();
            return;
        }

        boolean shaderMode = ColorfulLightGate.shaderEntityLightsActive();
        List<ShaderLight> lights = shaderMode ? new ArrayList<>() : List.of();
        HashMap<Long, ColorRGB4> computed = new HashMap<>();
        for(Entity entity : level.entitiesForRendering()) {
            EntityLight light = resolveLight(entity);
            if(light == null) continue;
            if(shaderMode) {
                lights.add(new ShaderLight(entity, light));
            } else {
                long pos = BlockPos.containing(entity.getEyePosition()).asLong();
                computed.merge(pos, effectiveColor(light), EntityLightManager::maxChannels);
            }
        }
        shaderLights = shaderMode ? List.copyOf(lights) : List.of();

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
        shaderLights = List.of();
    }

    // ---- per-frame uniform suppliers (render thread) ----

    public static int shaderLightCount() {
        computeFrameData();
        return frameCount;
    }

    /** Camera-relative position (xyz) and light level (w) of shader light slot {@code index}. */
    public static org.joml.Vector4f shaderLightPosition(int index) {
        computeFrameData();
        int base = index * 4;
        return new org.joml.Vector4f(
                framePositions[base], framePositions[base + 1], framePositions[base + 2], framePositions[base + 3]);
    }

    /** Color (rgb, 0..1) of shader light slot {@code index}. */
    public static org.joml.Vector4f shaderLightColor(int index) {
        computeFrameData();
        int base = index * 4;
        return new org.joml.Vector4f(
                frameColors[base], frameColors[base + 1], frameColors[base + 2], frameColors[base + 3]);
    }

    private static void computeFrameData() {
        long now = System.nanoTime();
        if(now - lastFrameNanos < 2_000_000) return; // already built for this frame
        lastFrameNanos = now;

        frameCount = 0;
        List<ShaderLight> lights = shaderLights;
        Minecraft minecraft = Minecraft.getInstance();
        if(lights.isEmpty() || minecraft.level == null) return;

        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);

        record Positioned(Vec3 relative, EntityLight light, double distanceSq) {}
        List<Positioned> positioned = new ArrayList<>(lights.size());
        for(ShaderLight shaderLight : lights) {
            Entity entity = shaderLight.entity();
            if(entity.isRemoved()) continue;
            Vec3 relative = entity.getEyePosition(partialTick).subtract(cameraPos);
            positioned.add(new Positioned(relative, shaderLight.light(), relative.lengthSqr()));
        }
        positioned.sort((a, b) -> Double.compare(a.distanceSq(), b.distanceSq()));

        int count = Math.min(positioned.size(), MAX_SHADER_LIGHTS);
        for(int i = 0; i < count; i++) {
            Positioned entry = positioned.get(i);
            framePositions[i * 4] = (float) entry.relative().x;
            framePositions[i * 4 + 1] = (float) entry.relative().y;
            framePositions[i * 4 + 2] = (float) entry.relative().z;
            framePositions[i * 4 + 3] = entry.light().brightness4();
            frameColors[i * 4] = entry.light().red4() / 15.0f;
            frameColors[i * 4 + 1] = entry.light().green4() / 15.0f;
            frameColors[i * 4 + 2] = entry.light().blue4() / 15.0f;
            frameColors[i * 4 + 3] = 1.0f;
        }
        frameCount = count;
    }

    // ---- light resolution ----

    @Nullable
    private static EntityLight resolveLight(Entity entity) {
        EntityLight light = null;
        EntityLightProvider provider = providers.get(entity.getType());
        if(provider != null)
            light = provider.getLight(entity);
        else {
            EntityLight jsonLight = jsonLights.get(entity.getType());
            if(jsonLight != null) light = jsonLight;
        }

        // the built-in lights are user-adjustable from the config screen (0 disables)
        Integer configLevel = builtinConfigLevel(entity.getType());
        if(configLevel != null && light != null)
            light = configLevel == 0 ? null : EntityLight.of(light.red4(), light.green4(), light.blue4(), configLevel);

        int burningLevel = ColorfulLightingConfig.BURNING_ENTITY_LIGHT.get();
        if(burningLevel > 0 && entity.isOnFire() && !entity.isSpectator()) {
            ColorRGB4 fireColor = Config.getLightColor(Blocks.FIRE.builtInRegistryHolder().getKey());
            EntityLight fireLight = EntityLight.of(fireColor.red4, fireColor.green4, fireColor.blue4, burningLevel);
            light = light == null ? fireLight : maxLight(light, fireLight);
        }

        if(light == null || light.isDark()) return null;
        return light;
    }

    @Nullable
    private static Integer builtinConfigLevel(EntityType<?> type) {
        if(type == EntityType.GLOW_SQUID) return ColorfulLightingConfig.GLOW_SQUID_LIGHT.get();
        if(type == EntityType.BLAZE) return ColorfulLightingConfig.BLAZE_LIGHT.get();
        if(type == EntityType.MAGMA_CUBE) return ColorfulLightingConfig.MAGMA_CUBE_LIGHT.get();
        if(type == EntityType.ALLAY) return ColorfulLightingConfig.ALLAY_LIGHT.get();
        return null;
    }

    private static ColorRGB4 effectiveColor(EntityLight light) {
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
